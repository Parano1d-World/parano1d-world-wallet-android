#!/usr/bin/env python3
"""Build the Paramo1d Android application with its pinned verification parameters."""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path
import sys as _sys
HOST = "darwin-x86_64" if _sys.platform == "darwin" else "linux-x86_64"  # NDK uses darwin-x86_64 on Intel and Apple-silicon Macs

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--ndk", required=True, type=Path)
parser.add_argument("--sdk", required=True, type=Path)
parser.add_argument("--java-home", required=True, type=Path)
parser.add_argument("--gradle", required=True, type=Path)
parser.add_argument("--artifacts", required=True, type=Path)
parser.add_argument("--go", required=True, type=Path)
parser.add_argument("--variant", choices=("debug", "release", "bundle"), default="debug", help="bundle = signed release .aab files for Play (phone + Wear OS)")
args = parser.parse_args()
if args.variant in ("release", "bundle"):
    required = ("PARANO1D_ANDROID_KEYSTORE_PATH", "PARANO1D_ANDROID_STORE_PASSWORD", "PARANO1D_ANDROID_KEY_ALIAS")
    missing = [name for name in required if not os.environ.get(name)]
    if missing:
        parser.error("release signing requires " + ", ".join(missing))
root = Path(__file__).resolve().parents[1]
profile = json.loads((root / "profiles/mainnet.json").read_text())
destination = root / "android/app/src/main/assets/parameters"
destination.mkdir(parents=True, exist_ok=True)
names = set()
names.add("release.id")
for category in ("artifacts", "matrix_caches"):
    for name, pin in profile[category].items():
        filename = name if category == "artifacts" else name + ".spancache"
        path = args.artifacts / filename
        encoded = path.read_bytes()
        if len(encoded) != pin["bytes"] or hashlib.sha256(encoded).hexdigest() != pin["sha256"]:
            parser.error("verification parameter does not match its release pin: " + filename)
        names.add(filename)
for name in profile["matrix_caches"]:
    filename = name + ".pmatrix"
    if not (args.artifacts / filename).is_file():
        parser.error("missing prepared matrix: " + filename)
    names.add(filename)
for existing in destination.iterdir():
    if existing.name not in names:
        parser.error("unexpected generated parameter file: " + existing.name)
for name in sorted(names - {"release.id"}):
    shutil.copy2(args.artifacts / name, destination / name)
release = "\n".join(name + ":" + hashlib.sha256((destination / name).read_bytes()).hexdigest() for name in sorted(names - {"release.id"}))
(destination / "release.id").write_text(hashlib.sha256(release.encode()).hexdigest())
subprocess.run([sys.executable, str(root / "scripts/check-upstream.py")], check=True)
subprocess.run([sys.executable, str(root / "scripts/build-android.py"), "--ndk", str(args.ndk.resolve())], cwd=root, check=True)
jni = root / "android/app/src/main/jniLibs/arm64-v8a"
jni.mkdir(parents=True, exist_ok=True)
(jni / "libparamo1d.so").unlink(missing_ok=True)
shutil.copy2(root.parent / "wallet-native/android-core/target/aarch64-linux-android/release/libparano1d_android.so", jni / "libparano1d_android.so")
# Load check: every core entry point must be defined in the library itself. An undefined one makes the
# library fail to load on the phone (the app can't start), so nothing is packaged.
def _undefined_core_symbols(path):
    import struct
    data = path.read_bytes()
    shoff = struct.unpack_from("<Q", data, 0x28)[0]; shentsize, shnum = struct.unpack_from("<HH", data, 0x3A)
    sections = [struct.unpack_from("<IIQQQQIIQQ", data, shoff + i * shentsize) for i in range(shnum)]
    dynsym = next(s for s in sections if s[1] == 11); dynstr = sections[dynsym[6]]
    missing = []
    for i in range(dynsym[5] // 24):
        name, _, _, shndx, _, _ = struct.unpack_from("<IBBHQQ", data, dynsym[4] + i * 24)
        text = data[dynstr[4] + name:data.index(b"\0", dynstr[4] + name)].decode()
        if shndx == 0 and (text.startswith("paranoid_") or text.startswith("Java_")):
            missing.append(text)
    return missing
_missing = _undefined_core_symbols(jni / "libparano1d_android.so")
if _missing:
    raise SystemExit("libparano1d_android.so would not load on a phone; undefined: " + ", ".join(_missing))
tunnel_env = os.environ.copy()
tunnel_env.update(CGO_ENABLED="1", GOOS="android", GOARCH="arm64")
tunnel_env["CC"] = str(args.ndk.resolve() / "toolchains/llvm/prebuilt" / HOST / "bin/aarch64-linux-android26-clang")
subprocess.run([str(args.go.resolve()), "-C", str(root / "tunnel"), "build", "-trimpath", "-ldflags=-s -w", "-buildmode=c-shared", "-o", str(jni / "libparamo1d_tunnel.so"), "./cmd/mobile"], env=tunnel_env, check=True)
# Release builds ship no local folder paths: scan every native library.
if args.variant in ("release", "bundle"):
    leaks = []
    for library in sorted(jni.glob("*.so")):
        data = library.read_bytes()
        for marker in {b"/Users/", b"/home/", str(Path.home()).encode()}:
            if marker in data:
                leaks.append(f"{library.name}: contains {marker.decode()}")
    if leaks:
        raise SystemExit("Native libraries contain local folder paths; nothing was packaged:\n  " + "\n  ".join(leaks))
env = os.environ.copy()
env.update(JAVA_HOME=str(args.java_home.resolve()), ANDROID_HOME=str(args.sdk.resolve()), ANDROID_SDK_ROOT=str(args.sdk.resolve()))
env["PATH"] = str(args.java_home.resolve() / "bin") + os.pathsep + env["PATH"]
tasks = {"debug": [":app:assembleDebug", ":wear:assembleDebug"], "release": [":app:assembleRelease", ":wear:assembleRelease"], "bundle": [":app:bundleRelease", ":wear:bundleRelease"]}[args.variant]
gate = os.environ.get("PARANO1D_NODE_CHECK")
if args.variant == "bundle" and gate:
    # Release gate: unit tests, the Rust check that an imported
    # master key derives the iOS address, then the live mainnet node check. Only then are bundles made.
    subprocess.run([str(args.gradle.resolve()), "-p", str(root / "android"), ":app:testDebugUnitTest", "--console=plain", "--no-daemon"], env=env, check=True)
    subprocess.run(["cargo", "test", "--locked", "--release", "--lib", "--", "ios_master_key", "approval_", "changed_payment_review", "an_authorized_noid_packet"], cwd=root, check=True)
    subprocess.run(["bash", gate, str(root), str(args.artifacts.resolve()), os.environ.get("PARANO1D_NODE_CHECK_LOG", str(root.parent / "build/logs/node-check.log"))], check=True)
    subprocess.run([str(args.gradle.resolve()), "-p", str(root / "android"), *tasks, "--console=plain", "--no-daemon"], env=env, check=True)
else:
    subprocess.run([str(args.gradle.resolve()), "-p", str(root / "android"), ":app:testDebugUnitTest", *tasks, "--console=plain", "--no-daemon"], env=env, check=True)
if args.variant == "bundle":
    outputs = [root / "android/app/build/outputs/bundle/release/app-release.aab", root / "android/wear/build/outputs/bundle/release/wear-release.aab"]
else:
    outputs = [root / f"android/{m}/build/outputs/apk/{args.variant}/{m}-{args.variant}.apk" for m in ("app", "wear")]
print(json.dumps([{"file": str(p), "bytes": p.stat().st_size, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in outputs], indent=2))
