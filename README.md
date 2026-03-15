# FileAssistant

FileAssistant is a privacy-first, on-device file organiser for Android — think Hazel for Android. It watches a folder you choose via the Storage Access Framework, classifies each new or modified file using a Rust core library compiled directly into the app, and proposes where to move it — all without any network access or cloud dependency.

This repository is the minimal bootstrap skeleton: every layer is wired up end-to-end (SAF UI → Kotlin foreground service → Rust JNI → JSON proposal → broadcast back to UI), but no real classification logic exists yet.

---

## Prerequisites

| Tool | Version |
|------|---------|
| Android Studio | Hedgehog (2023.1.1) or newer |
| Android SDK | API 34 (compileSdk), API 31+ device/emulator |
| Android NDK | 26.x (installed via SDK Manager) |
| Rust | stable (via [rustup](https://rustup.rs)) |
| cargo-ndk | latest (`cargo install cargo-ndk`) |
| JDK | 17 |

---

## Build locally

```bash
# 1. Add Android cross-compilation targets
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android

# 2. Install cargo-ndk
cargo install cargo-ndk

# 3. Set ANDROID_NDK_HOME (adjust to your NDK path)
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.1.10909125

# 4. Build the debug APK (Rust library is compiled automatically before preBuild)
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

---

## Architecture

```
User selects folder (SAF / ACTION_OPEN_DOCUMENT_TREE)
        │
        ▼
  MainActivity
  (stores URI, binds to WatcherService)
        │  start/stop
        ▼
  WatcherService  ──── foreground notification ────▶ Notification Shade
  (FileObserver on selected path)
        │ file event (CREATE / CLOSE_WRITE)
        ▼
  RustBridge.nativeOnFileEvent(eventType, path)
  RustBridge.nativeClassify(path)
        │ JNI call
        ▼
  Rust core (libfileassistant_core.so)
  – logs event via android_logger
  – returns hardcoded JSON proposal
        │ jstring
        ▼
  LocalBroadcastManager  (action: dev.fileassistant.PROPOSAL)
        │
        ▼
  MainActivity BroadcastReceiver
  ("Last proposal:" TextView updated)
```

---

## Roadmap

- **React Native UI layer** — richer settings and rule management screens
- **Real classification logic** — file-type heuristics, name patterns, MIME type matching in Rust
- **UniFFI migration** — replace hand-written JNI with generated bindings via [mozilla/uniffi-rs](https://github.com/mozilla/uniffi-rs)
- **RDF/YAML rule engine** — user-defined organisational rules stored as portable YAML/Turtle files
- **Nextcloud SAF integration** — watch and organise files on a Nextcloud-mounted storage provider
