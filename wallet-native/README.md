# wallet-native: the iOS token/NFT cores for Android

Builds `libparano1d_android.so`, the app's single native library. It links three cores, like the
iPhone app links them together:

| Core | Source | Java class |
|---|---|---|
| Paramo1d device node, chat, NOID wallet | `../paramo1d` | `NativeCore` |
| iOS Node (native planner, submit, settle/expire) | `node/` = iOS `Node/`, paths adjusted only | `NodeCore` |
| iOS wallet core (canonical review, signing, payment journal) | `native/` = iOS `Native/` | `WalletCore` |

`sdk/` is the iOS `Vendor/parano1d-sdk`. Its vendored noid crates differ from Paramo1d's (they add the
authorization circuits), so their package names carry an `_sdk` suffix to coexist in one lockfile;
the Rust code still imports them as `noid_core`, `noid_tx`, … (dependency renames). No Rust source was
changed except the two `#[path]` includes in `node/src`.

Update from a newer iOS build with `python3 Scripts/update-wallet-native.py <ios-testflight-tree>`.
