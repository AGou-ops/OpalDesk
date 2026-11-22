# AGENTS.md — OpalDesk Universal Compositor Architecture & Collaboration Guide

## 1. Architectural Philosophy (v1.0.0 Universal Compositor Rewrite)

OpalDesk (`io.github.lengsukq.opaldesk`) is a **100% OEM-agnostic, Rootless-First Wayland-Style Multi-Window Compositor** for Android 11–17 (`minSdk = 30`, `targetSdk = 37`).

### Core Invariants (Non-Negotiable)
1. **Zero OEM / Freeform Hacks**:
   - Never invoke `WINDOWING_MODE_FREEFORM (5)`, `WINDOWING_MODE_MULTI_WINDOW (6)`, `ITaskOrganizer`, MIUI/HyperOS hidden APIs (`com.miui.*`, AppOps `10021`), or `SYSTEM_ALERT_WINDOW` split-brain floating headers.
   - Every managed app runs in standard AOSP **`WINDOWING_MODE_FULLSCREEN (1)`** inside its own dedicated, pooled `VirtualDisplay` (`OpalWin-<id>`).
2. **Unified 120fps Client GPU Composition ("皮肉合一")**:
   - Each window's title bar chrome, rounded squircle corners, shadow, 8-way resize handles, and live `TextureView` (`GL_TEXTURE_EXTERNAL_OES`) live inside a **single Compose `RenderNode`**.
   - Translating (dragging) a window mutates only client GPU matrix offsets (**zero Binder calls and zero `VirtualDisplay.resize()` calls** during pure movement).
3. **Rootless-First (`uid=2000` `u:r:shell:s0`) + Strictly Optional (~5%) Root Enhancements**:
   - 95%+ of functionality runs rootlessly via Built-in Wireless Debugging (`libadb.so`), Shizuku, or PC ADB.
   - Even when launched via Root (`su` / Sui), `OpalDeskDaemon` immediately executes `dropRootFailClosed()` to `uid=2000` (`u:r:shell:s0`) before opening sockets or registering Binder IPC.
   - Optional `--root-enhance` only sets standard AOSP `force_resizable_activities=1` and `enable_non_resizable_multi_window=1` prior to dropping privileges.
4. **LRU `TaskSnapshot` Hibernation (`VirtualDisplayPool`)**:
   - Bypasses Android 14+ `VirtualDisplayAdapter` per-UID display quotas: foreground windows hold live `VirtualDisplay`s, while older background windows automatically capture a `HardwareBuffer` JPEG `TaskSnapshot` and release their `VirtualDisplay` slot, waking seamlessly when clicked.
5. **Dual Input Routing (`PerDisplayInputRouter`)**:
   - Primary: `IInputManager.injectInputEvent` targeted to each window's `displayId` (`MotionEvent` up to 10 pointers, stylus tilt/pressure, hover, scroll wheel, and `KeyEvent`).
   - Automatic Linux Kernel Fallback: Per-display `/dev/uhid` virtual absolute pointer + keyboard (`UhidInputDevice` + `IInputManager.addUniqueIdAssociation`) whenever an OEM ROM blocks cross-display injection.

---

## 2. Module Structure & Boundaries

```text
├── bridge/                                  # Pure Java 11 Shell Daemon (:bridge -> assets/opaldesk.jar)
│   ├── src/io/github/lengsukq/opaldesk/daemon/
│   │   ├── OpalDeskDaemon.java              # Entry point, --verify-helper / --stop / --root-enhance, privilege drop
│   │   ├── DaemonConfig.java                # Vendor-neutral config & per-app WindowProfile persistence
│   │   ├── DisplayGeometryUtil.java         # Even-aligned frames, snap zones, DPI & cascade math
│   │   ├── compositor/
│   │   │   ├── WindowProfile.java           # DESKTOP, PORTRAIT, TABLET independent resolution/DPI profiles
│   │   │   ├── WindowSession.java           # Pure JVM state model & JSON serializer
│   │   │   ├── VirtualDisplayPool.java      # LRU lease pool, snapshot hibernation & dynamic OEM quota ceiling
│   │   │   └── CompositorEngine.java        # Window lifecycle, Z-order, snap/maximize & task reconciliation
│   │   ├── input/
│   │   │   └── PerDisplayInputRouter.java   # Per-display IInputManager + Linux /dev/uhid auto-fallback
│   │   ├── ipc/
│   │   │   ├── BridgeProtocol.java          # v2 Binder transaction codes (TX 10..22, 99) & constants
│   │   │   ├── BridgeBinderService.java     # Fail-closed caller UID + APK signing SHA-256 verified Binder
│   │   │   ├── HelperClientBridge.java      # Binder handshake & zero-roundtrip scene JSON push
│   │   │   └── DaemonControlSocket.java     # Single-instance @io.github.lengsukq.opaldesk.daemon.ctl socket
│   │   ├── platform/                        # Thin Android hidden-API reflection adapters (isolated from core logic)
│   │   └── stream/Uhid*.java, SimpleJson    # Linux 64-bit struct uhid_event protocol & minimal JSON builder
│   └── test/                                # JUnit 4 JVM unit tests (zero Android device required)
│
├── helper/                                  # Kotlin 21 + Jetpack Compose Foundation Client APK (:helper)
│   ├── src/io/github/lengsukq/opaldesk/
│   │   ├── MainActivity.kt                  # Universal Compositor Console & pool/input tuning
│   │   ├── DesktopActivity.kt               # Fullscreen immersive workstation on Display 0 + keyboard shortcuts
│   │   ├── ExternalDesktopActivity.kt       # Automatic USB-C / HDMI external monitor workstation
│   │   ├── BridgeClient.kt / BridgeService.kt / BridgeProvider.kt
│   │   ├── compositor/
│   │   │   ├── OpalCompositorWorkspace.kt   # Shared 120fps multi-window canvas, integrated chrome, Dock & Exposé
│   │   │   ├── WindowSurfaceHost.kt         # TextureView SurfaceTexture lifecycle, input mapping & hibernate card
│   │   │   └── AppCatalogRepository.kt      # Async launcher catalog & LruCache icons
│   │   ├── protocol/                        # BridgeProtocol.kt & CompositorModels.kt
│   │   ├── ui/                              # Vendor-neutral OpalPrimitives.kt & 4-channel ActivationModal.kt
│   │   └── adb/                             # 3-tier Wireless ADB, mDNS, Shizuku & Root-to-Shell bootstrap
│   └── test/                                # JUnit 4 JVM unit tests
│
└── macos/OpalDeskMac/                       # Native macOS Swift 6 companion project
```

---

## 3. Coding, UI & Git Conventions

- **Zero Vendor Coupling in `:helper`**: Build UI components exclusively on `androidx.compose.foundation` + `OpalPrimitives.kt`. Do **not** introduce `com.miui.*` reflection, MIUI AppOps, or Material3 dependencies not declared in `helper/build.gradle.kts`.
- **Testable Core**: Keep `CompositorEngine`, `VirtualDisplayPool`, `DisplayGeometryUtil`, and `CompositorGeometry` free of hard Android Binder singletons so every new windowing feature ships with deterministic JUnit 4 tests.
- **Security**: Never bypass `BridgeBuildConfig.EXPECTED_HELPER_CERT_SHA256` verification, never weaken `AdbProcessRunner.isAllowedLocalHost()`, and never execute the daemon loop as `uid=0`.

---

## 4. Build, Test & Live Device Deployment Commands

```bash
# 1. Initialize local PKCS12 signing key once (creates gitignored opaldesk.p12)
./scripts/init-signing.sh

# 2. Run all JVM unit tests across :bridge and :helper
./gradlew :bridge:test :helper:testDebugUnitTest

# 3. Assemble Debug APK (auto-compiles :bridge:dexBridgeJar into helper assets/opaldesk.jar)
./gradlew :helper:assembleDebug

# 4. One-command build, install & start daemon over USB/Wireless ADB
./scripts/dev-deploy.sh --logs
```
