# Guide for coding agents

Read this before changing code. It is written for any AI coding agent (Claude
Code, Codex, Gemini CLI, Copilot, ...) that a user has pointed at this repo to
fix a bug or add a feature. `CLAUDE.md` just imports this file.

## What the app is

A Wear OS remote for phone media playback. Two APKs that only work as a pair:

| Module | Runs on | Role |
|---|---|---|
| `mobile/` | phone | Watches media sessions (`NotificationService`, `music/MusicService.kt`), runs actions, owns all config, pushes state to the watch |
| `wear/` | watch | Display + input only (`watch/view`, `watch/communication/PhoneConnection`). Holds no config of its own |
| `common/` | both | Protobuf messages and shared constants (`CommPaths`) |
| `wearutils/` | both | Git submodule (matejdro/WearUtils): logging, preferences, exception forwarding |

Phone and watch talk over the Wear **Data Layer**. Data items go through
`DataClient`; anything time-sensitive must call `setUrgent()` or it can arrive
minutes late.

## Build

```
git submodule update --init
./gradlew :mobile:assembleDebug :wear:assembleDebug
```

JDK 21, Android SDK platform 36 / build-tools 36. A `:wear` build takes several
minutes. Release builds sign only if a gitignored `keystore.properties`
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) exists in the repo root.
Without one, use debug builds.

`common/build.gradle` pins `protoc` 3.0.0, which has no `osx-aarch_64` binary. On
Apple Silicon, build under Rosetta or bump protoc locally. Do not commit the bump
without checking that the generated code still compiles.

## Testing on devices

- **Install both APKs, signed with the same key.** The Data Layer only connects
  apps with the same application ID *and* signing certificate. A mismatched pair
  fails silently: the watch says it needs the phone app ("To use this app, you need to also install phone app").
- Debug builds are debuggable, so app logs can be read without root:
  `adb shell run-as io.github.backcountrymountains.wearmusiccenter ls cache/logs/`
  then `cat` the newest `log_N.log`. The files rotate, so the newest is not always `log_0`.
  Release builds: the phone app's **Get support** setting collects logs
  from both devices into a file the user can share.
- Wear OS adb over Wi-Fi drops when the watch dozes. Put the action and the log
  dump in one command.
- Only a human can check how the watch screen looks in ambient/always-on mode.
  `screencap` returns nothing while the panel dozes.

## Known traps (each cost real debugging time)

- **Ambient:** use `AmbientLifecycleObserver`. `AmbientModeSupport` compiles and
  runs but never fires its callbacks on Wear OS 5+. Do not switch back.
- **Resync:** `PhoneConnection` must re-request state every time the UI becomes
  active. A "running" flag that skipped this left the watch stuck on "Playback
  Stopped" until it was force-stopped.
- **Podcast queues:** `MediaController.queue` is empty for apps like AntennaPod.
  The real queue comes from the app's media *browse tree*. See
  `mobile/.../music/MediaBrowseClient.kt`.
- **Watch lists:** `CurvingLayoutCallback` owns each row's `translationX`. Use
  scale/pivot to style rows instead.
- **"Sent" is not "received":** log lines written before a Data Layer `await()`
  do not prove delivery. Check the watch side.
- The default SharedPreferences file is named after the application ID.
  `config/SettingsBackup.kt` renames it on restore, and anything else that touches
  prefs files directly must account for this too.

## Working norms

- Keep changes small and focused: one fix per PR, and say in the description
  how it was tested on real hardware (watch model and Wear OS version).
- Changing anything shared (`common/`, Data Layer paths, protobufs) means
  rebuilding and reinstalling **both** apps.
- Never commit `keystore.properties`, `*.jks`, `local.properties` or APKs.
