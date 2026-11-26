# Parano1d World Wallet for Android (Play Store edition)

Self-custody NOID wallet for Android phones and Wear OS watches, published by KoAjent LLC (Parano1d World).

- **Keys stay on your phone.** They're created on the phone and kept in an encrypted store protected by the Android Keystore. An app PIN or password unlocks the wallet; the phone's own screen lock can be added.
- **On-device node.** The app verifies the chain on the phone instead of trusting a server.
- **No trading.** There's no exchange, token market or NFT sale in this edition. You can view and send what you own.
- **Private chat.** End-to-end encrypted between contacts who exchange signed invitations, plus a moderated public room. Messages can also travel between nearby phones over Bluetooth.
- **Mining monitor.** You can pair your own mining computer to see its status and start or stop it. The phone never mines.

Built on the open-source Paramo1d Android node and messenger (`paramo1d/`, Apache License 2.0, see `paramo1d/LICENSE`).
This repository is published for review and audit. See `LICENSE` for what you may do with it, and `SECURITY.md` to report a vulnerability privately.

## Build

You need a Mac with JDK 21, the Android SDK (platform 36, NDK 30.0.16248370), Rust (see `paramo1d/rust-toolchain.toml`) and Go.
The verification parameters are downloaded and checked against the SHA-256 in `paramo1d/android/build-inputs.json`.

```
bash Scripts/build-mac.sh debug    # phone and Wear OS debug apps
```
