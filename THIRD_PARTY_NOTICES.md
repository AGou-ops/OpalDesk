# Third-Party Notices (`THIRD_PARTY_NOTICES.md`)

**OpalDesk** (`io.github.lengsukq.opaldesk`, `io.github.lengsukq.opaldesk.daemon.OpalDeskDaemon`, and `io.github.lengsukq.opaldesk.mac`) is licensed under the [MIT License](LICENSE).

---

## 1. Upstream Project Attribution

- **[ExtBridge](https://github.com/duppjuzo/ExtBridge)**
  - Copyright (c) 2026 ExtBridge Contributors (`duppjuzo`)
  - License: [MIT License](LICENSE)
  - OpalDesk originated as a fork of `duppjuzo/ExtBridge` (`v3.5.3`) and subsequently diverged into an independent multi-display Android desktop & macOS streaming project with its own package identity, icon system, version line, and runtime namespace.

---

## 2. Bundled Native Executable: `helper/jniLibs/arm64-v8a/libadb.so`

- **Binary Path**: `helper/jniLibs/arm64-v8a/libadb.so`
- **SHA-256**: `47ea035fa5ed57f6149a2b025bbbd4b21584c355c05d0400416804715e4c12de`
- **Upstream Source & Version**:
  - Built from **[termux/android-tools](https://github.com/termux/android-tools)** / **[nmeum/android-tools](https://github.com/nmeum/android-tools)** (`platform-tools 34.0.0`, `Android Debug Bridge version 1.0.41`, `arm64-v8a` Android Bionic target), derived from Google AOSP `packages/modules/adb`.
- **How OpalDesk Uses `libadb.so`**:
  - `libadb.so` is packaged under `jniLibs/arm64-v8a/` solely so Android's package installer extracts it with executable permissions into `nativeLibraryDir`.
  - OpalDesk **never** loads `libadb.so` via `System.loadLibrary` or JNI into its own process address space; `AdbProcessRunner.kt` invokes it strictly as a standalone child CLI executable via `ProcessBuilder` (`adb pair` / `adb connect` / `adb push` / `adb shell` over a private `0700` `localfilesystem:` Unix socket) for optional on-device Wireless Debugging activation.
- **Included Upstream Licenses & Source Availability**:
  - **AOSP `adb` / `base` / `crypto` / `diagnose_usb` / `libcutils`**: **Apache License 2.0** (`Copyright (C) The Android Open Source Project`).
  - **BoringSSL** (`crypto` / `ssl` TLS & SPAKE2): **ISC / OpenSSL / SSLeay-style BSD Licenses** (`Copyright (c) Google Inc.`, `The OpenSSL Project`, `Eric Young`).
  - **Protocol Buffers (`libprotobuf` 21.5)**: **BSD-3-Clause License** (`Copyright 2008 Google Inc.`).
  - **Compression Libraries (`zstd`, `lz4`, `brotli`)**: **BSD-2-Clause / BSD-3-Clause / MIT Licenses**.
  - **`libusb` (`libusb.info`)**: **GNU Lesser General Public License v2.1 (LGPL-2.1-or-later)**.
    - *LGPL-2.1 Compliance & Replacement Note*: `libadb.so` is a separate standalone command-line executable (`ProcessBuilder`), not linked into OpalDesk's Java/Kotlin application code (`classes.dex` or `opaldesk.jar`). Full upstream build recipes and patches for `android-tools` (`34.0.0`) are publicly available at <https://github.com/termux/termux-packages/tree/master/packages/android-tools> and <https://github.com/nmeum/android-tools>. Users may freely replace `helper/jniLibs/arm64-v8a/libadb.so` with any compatible `arm64-v8a` `adb` binary rebuilt from modified `libusb` / `android-tools` sources and re-package the APK via `./gradlew :helper:assembleRelease` (`.\gradlew.bat :helper:assembleRelease` on Windows).

---

## 3. Kotlin / AndroidX / Compose / Shizuku Libraries

1. **AndroidX Activity Compose (`androidx.activity:activity-compose:1.13.0`) & Window (`androidx.window:window:1.5.0`)**
   - Copyright (C) The Android Open Source Project
   - License: [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
2. **JetBrains Compose Multiplatform (`org.jetbrains.compose.foundation` / `ui` `1.12.0`) & Kotlinx Coroutines (`1.10.2`)**
   - Copyright (C) JetBrains s.r.o. and Contributors
   - License: [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
3. **Miuix KMP (`top.yukonga.miuix.kmp:miuix-ui` / `miuix-preference` / `miuix-icons` / `miuix-squircle` `0.9.4`)**
   - Copyright (C) YuKongA and Miuix Contributors (<https://github.com/miuix-kotlin-multiplatform/miuix>)
   - License: [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
4. **Shizuku API (`dev.rikka.shizuku:aidl` / `api` / `provider` `13.1.5`)**
   - Copyright (C) RikkaW and Shizuku Contributors (<https://github.com/RikkaApps/Shizuku-API>)
   - License: [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
