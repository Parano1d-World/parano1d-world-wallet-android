#!/bin/bash
# Parano1d World Wallet for Android (phone + Wear OS) — Mac build.
#
#   bash Scripts/build-mac.sh wear-demo   # Wear OS app only, demo data, for the Wear OS emulator (no NDK/Rust needed)
#   bash Scripts/build-mac.sh debug       # phone + watch debug APKs (needs NDK, Rust, Go, parameters)
#   bash Scripts/build-mac.sh bundle      # signed release .aab files for Play Console internal testing
#   bash Scripts/build-mac.sh checks      # upstream (ignotusnemo/paramo1d) test suite: source pins, Rust, tunnel, Bluetooth frames, UI
#
# Toolchain pinned by upstream: Rust 1.96.0 (rust-toolchain.toml), Go 1.27.3 (tunnel/go.mod),
# JDK 21, Gradle 8.14.3, Android SDK platform 36 + build-tools 36.0.0, NDK 30.0.16248370.
# Phone hardware: Android 8+, ARM64 with NEON/AES/PMULL, updated Android System WebView.
#
# Stops at the first failure. Logs go to build/logs/.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/paramo1d"
VARIANT="${1:-debug}"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/30.0.16248370}"
# Gradle 8.14 runs on JDK 17–24 only. Android Studio 2026.2 bundles Java 25, so prefer an installed JDK 21 (or 17).
java_major() { "$1/bin/java" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/{print $2; exit}'; }
pick_java() {
  local h v
  for v in 21 17; do
    h="$(/usr/libexec/java_home -v "$v" 2>/dev/null || true)"
    [ -n "$h" ] && [ -x "$h/bin/java" ] && [ "$(java_major "$h")" = "$v" ] && { echo "$h"; return; }
  done
  for h in "$HOME/.homebrew/opt/openjdk@21" /usr/local/opt/openjdk@21 /opt/homebrew/opt/openjdk@21 "$HOME/.homebrew/opt/openjdk@17" /usr/local/opt/openjdk@17 /opt/homebrew/opt/openjdk@17; do
    [ -x "$h/libexec/openjdk.jdk/Contents/Home/bin/java" ] && { echo "$h/libexec/openjdk.jdk/Contents/Home"; return; }
  done
  echo "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
}
JAVA="${JAVA_HOME:-$(pick_java)}"
ARTIFACTS="$ROOT/build/parameters"
LOGS="$ROOT/build/logs"
mkdir -p "$LOGS"
export PATH="$HOME/.cargo/bin:$PATH"

need() { echo "MISSING: $1"; echo "  → $2"; exit 1; }
# Tool checks report everything missing at once, so one round of installs is enough.
MISSING=0
want() { echo "MISSING: $1"; echo "  → $2"; MISSING=1; }

[ -d "$SDK" ] || need "Android SDK at $SDK" "Install Android Studio, open it once and finish setup (it installs the SDK)."
[ -x "$JAVA/bin/java" ] || need "JDK at $JAVA" "Install Android Studio (it bundles a JDK), or set JAVA_HOME to JDK 17 or 21."
JAVA_MAJOR="$(java_major "$JAVA")"
case "$JAVA_MAJOR" in 17|18|19|20|21|22|23|24) ;; *) want "JDK 21 (found Java ${JAVA_MAJOR:-unknown} at $JAVA, which Gradle 8.14 can't run on)" "brew install openjdk@21   (the build finds it automatically)";; esac
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK" JAVA_HOME="$JAVA" PATH="$JAVA/bin:$PATH"
[ -f "$SRC/android/local.properties" ] || echo "sdk.dir=$SDK" > "$SRC/android/local.properties"

# Public-room moderation: same feed URL and Ed25519 key as the iOS builds (written there by
# moderation-site `npm run setup`). Until setup has run, iOS ships it disabled and so does Android.
MOD="${PARANO1D_MODERATION_CONFIG:-}"   # optional: a moderation config to bundle (the repo already carries assets/moderation.json)
if [ -n "$MOD" ]; then cp "$MOD" "$SRC/android/app/src/main/assets/moderation.json"; echo "Moderation config: $MOD"; fi

if [ "$VARIANT" = "checks" ]; then
  cd "$SRC"
  python3 scripts/check-upstream.py
  cargo test --locked --release --workspace 2>&1 | tee "$LOGS/cargo-test.log"
  (cd tunnel && go test ./...) 2>&1 | tee "$LOGS/go-test.log"
  mkdir -p target/nearby-wire-check
  javac -d target/nearby-wire-check android/app/src/main/java/org/parano1d/courier/NearbyWire.java android/tests/NearbyWireTest.java
  java -cp target/nearby-wire-check org.parano1d.courier.NearbyWireTest
  (cd android/tests && npm ci && npx playwright install chromium && CHROME="$(node -p "require('playwright').chromium.executablePath()")" npm test) 2>&1 | tee "$LOGS/ui-test.log"
  echo "All checks passed."
  exit 0
fi

if [ "$VARIANT" = "wear-demo" ]; then
  (cd "$SRC/android" && ./gradlew :wear:assembleDebug --console=plain) 2>&1 | tee "$LOGS/wear-demo.log"
  echo
  echo "Built: $SRC/android/wear/build/outputs/apk/debug/wear-debug.apk"
  echo "Install on a running Wear OS emulator:  \"$SDK/platform-tools/adb\" install -r \"$SRC/android/wear/build/outputs/apk/debug/wear-debug.apk\""
  exit 0
fi

for platform in "platforms;android-36" "build-tools;36.0.0"; do [ -d "$SDK/${platform/;//}" ] || want "SDK package $platform" "Android Studio → Settings → Android SDK: install Android 16 (API 36) SDK Platform and Build-Tools 36.0.0."; done
[ -x "$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android26-clang" ] || want "Android NDK 30.0.16248370" "Android Studio → Settings → Languages & Frameworks → Android SDK → SDK Tools → NDK (Side by side) → Show Package Details → 30.0.16248370."
command -v cargo >/dev/null || want "Rust" "Install from https://rustup.rs"
command -v go >/dev/null || want "Go (builds the app-only VPN library)" "brew install go"
[ "$MISSING" = 0 ] || { echo; echo "Install the items above, then run the build again."; exit 1; }
if [ "$VARIANT" = "check" ]; then echo "Tools ready: Java $JAVA_MAJOR, NDK, Rust, Go, SDK 36."; exit 0; fi
(cd "$SRC" && rustup toolchain install 1.96.0 --profile minimal --target aarch64-linux-android >/dev/null)

if [ ! -f "$ARTIFACTS/.fetched" ]; then
  echo "Downloading the pinned verification parameters (≈94 MB, hash-checked)…"
  python3 "$SRC/scripts/fetch-android-artifacts.py" --output "$ARTIFACTS" 2>&1 | tee "$LOGS/parameters.log"
  touch "$ARTIFACTS/.fetched"
fi

if [ "$VARIANT" = "bundle" ]; then
  for name in PARANO1D_ANDROID_KEYSTORE_PATH PARANO1D_ANDROID_STORE_PASSWORD PARANO1D_ANDROID_KEY_ALIAS; do
    [ -n "${!name:-}" ] || need "$name" "See ANDROID-README.md → Upload key. The same key signs the phone and watch bundles."
  done
fi

python3 "$SRC/scripts/build-courier.py" --ndk "$NDK" --sdk "$SDK" --java-home "$JAVA" \
  --gradle "$SRC/android/gradlew" --artifacts "$ARTIFACTS" --go "$(command -v go)" --variant "$VARIANT" 2>&1 | tee "$LOGS/build-$VARIANT.log"
