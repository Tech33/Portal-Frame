# Frame

An open-source slideshow **screensaver for the Meta Portal Go & Meta Portal devices** (Android 10 / API 29) that shows your **Google Photos** or **iCloud** shared albums. Set it up on-device by scanning the album's QR code (or pasting the link), and it plays your photos whenever the Portal is idle — with a clock, captions, ambient color, and full **Home Assistant** smart-home integration.

> Repo name: **PortalFrame** (`com.portalhacks.frame`). The app displays as **Frame**.

---

## Install it on a Portal

### ⬇️ [Download the latest APK](https://github.com/Tech33/Portal-Frame/releases/latest/download/Frame.apk)

That link always serves the newest signed release. Prefer to pick a version (or grab the `.sha256`)? Browse all builds on the **[Releases page](https://github.com/Tech33/Portal-Frame/releases/latest)**.

---

### ⚡ 1-Click Installation (Recommended)

To make installation as simple as possible, the project includes an automatic 1-click installer inside the `provisioning` folder. It will download the ADB tools, get the latest version of Frame, install the app, disable Facebook/Portal updater tools, grant secure permissions, and configure the screensaver automatically.

1. **Prerequisite**: Turn on **USB debugging** on the Portal:
   * Go to **Settings ➔ About**.
   * Tap the **Portal logo / build number 7 times** until it says *"You are now a developer."*
   * Go back, open the new **Debug** settings menu, and toggle **ADB Enabled** to **ON**.
2. **Connect**: Plug the Portal into your computer using a USB cable and authorize debugging when prompted on the Portal screen.
3. **Run the Installer**:
   * **Windows**: Double-click **`provisioning/provision.bat`**.
   * **macOS / Linux**: Double-click (or run in Terminal) **`provisioning/provision.command`**.
4. **Protected Mode & Wireless ADB**:
   * The installer automatically enables **Protected Mode** by default, locking the screensaver so other apps can't override it.
   * It also automatically configures **Wireless ADB** (port `5555`) and outputs the Portal's IP address.
   * To connect wirelessly next time without a USB cable, just open your Terminal / Command Prompt and run:
     ```bash
     adb connect <PORTAL-IP>:5555
     ```

For manual installation steps or to restore default stock settings, see the full **[Install & User Guide](INSTALL.md)**.
After that, open **Frame → Updates** on the Portal to check for wireless updates — no computer required.

---

## In-App Updates (Sideloading Support)

Since the Portal OS's default "Unknown Sources" toggle (`install_non_market_apps`) can prevent apps from installing update packages, the provisioning script runs the following two commands to allow Portal Frame to update itself wirelessly:

1. Disable the Meta overlay (which hides/blocks package installer action buttons):
   ```bash
   adb shell pm disable-user --user 0 com.facebook.aloha.rro.niu.android
   ```
2. Grant the update installation permission directly via appops:
   ```bash
   adb shell appops set com.portalhacks.frame REQUEST_INSTALL_PACKAGES allow
   ```

---

## Version Tracker

Each release publishes a **`version.json`** on GitHub (repo root and attached to the release) with `versionCode`, `versionName`, `apkUrl`, and `sha256`. The app reads it directly for wireless OTA updates:

`https://github.com/Tech33/Portal-Frame/releases/latest/download/version.json`

---

## Screenshots

<table>
  <tr>
    <td width="50%"><img src="docs/screenshots/01-slideshow.png" alt="Slideshow screensaver"><br><sub><b>Slideshow</b> — your photos when the Portal is idle</sub></td>
    <td width="50%"><img src="docs/screenshots/02-settings-albums.png" alt="Albums settings"><br><sub><b>Albums</b> — add several, stop, reorder, or remove each</sub></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/03-add-album.png" alt="Add an album"><br><sub><b>Add an album</b> — scan a QR code or paste a link</sub></td>
    <td><img src="docs/screenshots/04-empty-state.png" alt="First-run setup"><br><sub><b>Setup</b> — clean first run onboarding</sub></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/05-broadcast-announcement.png" alt="Broadcast Announcement"><br><sub><b>Smart Broadcast Banner</b> — remote phone pairing & trip location clues</sub></td>
    <td><img src="docs/screenshots/06-home-assistant.png" alt="Home Assistant Integration"><br><sub><b>Home Assistant</b> — MQTT auto-discovery controls & sensors</sub></td>
  </tr>
</table>

<sub>Sample photos shown; personal photos, album names, and links are scrubbed.</sub>

---

## Features

### 📸 Photos & Providers
- **Google Photos & iCloud Shared Albums**: Paste or scan any public shared album link; new photos appear automatically without requiring sign-in or API keys (supports up to 5,000 photos on iCloud).
- **On-Device Setup**: Scan the album QR code with your phone or paste via the on-device keyboard / local web manager (`http://<PORTAL-IP>:8080/add`).
- **Multi-Album Rotation**: Add as many albums as you like; merge or interleave them seamlessly.
- **Side-by-Side Portraits**: Pairs vertical photos automatically for landscape displays.

### 🏡 Home Assistant & MQTT Integration
- **Zero-Config Auto-Discovery**: Automatically exposes the Portal as a rich Home Assistant device over standard MQTT:
  - **Screen Power Switch**: Turn physical display sleep and wake on/off via automations.
  - **Screen Brightness Slider**: Smooth backlight control.
  - **Media Volume Control**: Adjust playback volume.
  - **Chimes & Alert Tones**: Play doorbell chimes and alert beeps remotely.
  - **Battery Sensors**: Live battery level, charging state, and AC power status.
  - **Ambient Light & Sound Level**: Real-time lux reading and room noise level monitoring.
  - **Broadcast Message Text & Entity**: Push instant announcements and banner messages from Home Assistant.
  - **Embedded HA Dashboard**: Quick one-tap floating pill to open your native Home Assistant dashboard.

### 📍 Smart Broadcast Announcements & Trip Showcases
- **Location Clue Recognition**: Broadcast messages like *"Our trip to Portugal! 🇵🇹"* or *"Visiting Dublin"* automatically detect the destination and isolate photos taken during that vacation.
- **Trip Date Clustering**: Groups all photos taken during that vacation timeframe ($\pm 1$ day) so the entire trip is showcased without photos from other destinations leaking in.
- **Phone QR Broadcast**: Tap the broadcast icon in Settings to scan a QR code on your phone and instantly push announcements to the frame from anywhere on your Wi-Fi network.

### ⏰ Selectable Clock Styles & Pitch-Black Night Mode
- **Google Nest Flip Clock**: Realistic 2-card flip clock with crisp physical crease lines, AM/PM indicator, and date/weather line.
- **Nest Hub Style**: Modern bold typography with a soft, tinted drop shadow.
- **Modern Glassmorphic**: Frosted glass container with a subtle ambient border.
- **Pitch-Black Screen Isolation**: When night/clock-only mode is active, the photo engine is completely blanked and hidden (`#000000 !important`), eliminating any backlight bleed or photos running in the background.
- **Battery Widget**: Displays live charge percentage and charging status bolt icon directly on the clock line.

### 🖐️ Touch & Gesture Controls
- **Swipe**: Swipe left or right to skip between next and previous photos.
- **Tap Photo / Long-Press**: Pause slideshow and reveal the quick action capsule menu (**Settings**, **Exit**).
- **Long-Press Clock**: Drag to move or pinch to resize the clock widget dynamically.

---

## For Developers

Kotlin app (Jetpack Compose settings UI + Android Views slideshow), built with Gradle:

```bash
./gradlew assembleDebug      # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # -> app/build/outputs/apk/release/app-release.apk
```

Requires JDK 17–21 and an Android SDK (`ANDROID_SDK_ROOT`, or a git-ignored `local.properties` with `sdk.dir=…`). See **[CONTRIBUTING.md](CONTRIBUTING.md)** for details and project layout, and **[RELEASING.md](RELEASING.md)** for cutting a signed release.

---

## License & Security

[MIT](LICENSE) — third-party attributions in [NOTICE](NOTICE). See [SECURITY.md](SECURITY.md) for the trust model and how to report issues.
