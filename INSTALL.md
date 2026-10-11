# Install

You need **two** files from the [latest release](https://github.com/backcountrymountains/WearMusicCenter/releases/latest):
`mobile-release.apk` (phone) and `wear-release.apk` (watch).

## 1. Phone

1. Download `mobile-release.apk` on your phone and open it.
2. Allow "install unknown apps" for your browser when asked. Tap **Install**.

## 2. Watch

1. Watch: **Settings → About watch → Software information**, tap **Software version** 5 times
   (on non-Samsung watches: **Build number**). This turns on Developer options.
2. Watch: **Settings → Developer options** → turn on **ADB debugging** and **Wireless debugging**.
3. Phone: install [Wear Installer 2](https://play.google.com/store/apps/details?id=org.freepoc.wearinstaller2)
   from the Play Store, pick `wear-release.apk`, and follow its prompts.
4. Turn **Wireless debugging** off again when done (it drains battery).

Prefer a computer? `adb pair <ip:port>`, `adb connect <ip:port>`, `adb install wear-release.apk`.

## 3. Set up

Open **Music Center for Wear** on the phone and grant the permissions it asks for.
Start some music. The watch app should show it.

## Moving from the original app (matejdro)

This fork installs **next to** the original, so nothing is lost while you switch.

1. Install both new apps as above. Keep the old ones for now.
2. Open the old phone app and the new one side by side, and copy your button
   layouts and settings across. (Both are called *Music Center for Wear*; the new one is version 1.11+, see App info.)
3. Uninstall the old app from the phone **and** the watch.
4. If a watch button was set to open the old app, set it again.
5. New app: **Settings → Back up settings**. Next time you reinstall, use
   **Restore settings** instead of redoing everything.

Problems? [Open an issue](https://github.com/backcountrymountains/WearMusicCenter/issues).
