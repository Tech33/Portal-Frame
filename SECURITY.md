# Security Policy

Frame is a sideloaded hobby app for the Meta Portal Go. There's no server component and it
stores no accounts or credentials, but here's the trust model and how to report issues.

## Reporting a vulnerability

Please open a GitHub issue, or for anything sensitive, contact the maintainer privately rather
than filing a public issue with exploit details. There's no formal SLA — this is a hobby
project — but reports are appreciated and will be looked at.

## Trust model & hardening

- **Network.** The app talks to photo providers' hosts (Google Photos / iCloud and their image
  CDNs), weather lookup endpoints (GeoJS, Open-Meteo), and **GitHub Releases** for optional
  in-app updates — always over **HTTPS**. Cleartext traffic is disabled via
  `res/xml/network_security_config.xml`. Album fetch, image download, and OTA download all refuse
  non-HTTPS URLs and cap response sizes.

- **In-app updates (GitHub).** Settings → **Check for updates** fetches
  `version.json` from `github.com/Tech33/Portal-Frame/releases/latest/download/version.json`,
  compares `versionCode` to the installed build, and optionally downloads `Frame.apk` from the same
  release. Download URLs must be on `github.com` or `githubusercontent.com`; APK size is capped at
  80 MB; when `sha256` is present in the manifest the download is verified before install. The user
  must grant **Install unknown apps** for Frame (Android 8+). Updates only succeed when signed with
  the same release key as the installed build.

- **Public shared albums only.** Providers read **public, link-shared** albums via their public
  share endpoints — Google Photos by scraping the share page (the Library API was deprecated
  2025-03-31), iCloud via the `sharedstreams` web API. These are unofficial and may break if the
  provider changes its format; each fails closed (falls back to the bundled sample photos). The app
  has no account access and cannot read a private library.

- **`ConfigReceiver` (exported).** This broadcast receiver lets the album be set over ADB
  (`am broadcast`) without rebuilding, so it is exported and any app on the device could send to
  it. It only writes the app's own private `SharedPreferences`, and it **validates** the album URL
  (`PhotoSources.matches`) — it persists only an empty value (clear) or a recognised Google Photos
  / iCloud shared-album link, ignoring anything else.

- **`MainActivity` (exported).** The slideshow Activity is intentionally exported so it can be
  launched for testing (`am start`) and by the screensaver trampoline. It displays photos only
  and takes no untrusted parameters.

- **Permissions.** `INTERNET`, `ACCESS_NETWORK_STATE`, `REQUEST_INSTALL_PACKAGES` (in-app updates),
  and `CAMERA` (camera is used only for the on-device QR scan, and is optional).
  `android:allowBackup="false"`.

## Supported versions

This is a rolling hobby project; only the latest `main` is maintained.
