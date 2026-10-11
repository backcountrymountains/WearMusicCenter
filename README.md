Music Center for Wear
================================

Application for customizable music control from Android Wear devices with support for additional watch's buttons and digital crown.

This is a maintained fork of [matejdro/WearMusicCenter](https://github.com/matejdro/WearMusicCenter).
It fixes the watch getting stuck on "Playback Stopped", silent watch-app deaths,
podcast queues (AntennaPod etc.) and always-on mode on Wear OS 5+, and adds a
playback speed list and settings backup/restore.

**Install: see [INSTALL.md](INSTALL.md)** (also covers moving over from the original app).

The code changes in this fork were written by Claude (Anthropic's AI coding
assistant), directed and tested on a Galaxy Watch Ultra by the maintainer. If you
want your own AI agent to fix something, point it at [AGENTS.md](AGENTS.md).

## Installing

See [INSTALL.md](INSTALL.md).

## Building

### Prerequisites

* [Android Studio 3.0 or newer](https://developer.android.com/studio/index.html)

### Build process

#### 1. Pull the repo
#### 2. Pull the submodules

Run command `git submodule update --init`

#### 3. Open the project in the Android Studio and wait for its dependencies to resolve
