# Security Policy

Frame is a sideloaded hobby app for the Meta Portal Go. There's no server component and it
stores no accounts or credentials, but here's the trust model and how to report issues.

## Reporting a vulnerability

Please open a GitHub issue, or for anything sensitive, contact the maintainer privately rather
than filing a public issue with exploit details. There's no formal SLA — this is a hobby
project — but reports are appreciated and will be looked at.

## Trust model & hardening

- **Network.** The app only talks to the photo providers' hosts (Google Photos / iCloud and their
  image CDNs), always over **HTTPS**. Cleartext traffic is disabled via
  `res/xml/network_security_config.xml`, and the album fetch (`PhotoSources` → `GooglePhotosSource`
  / `ApplePhotosSource`) and image download (`ImageLoader`) both refuse non-HTTPS URLs and cap
  response sizes to avoid memory/disk exhaustion from a hostile or oversized response.

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

- **Permissions.** `INTERNET`, `ACCESS_NETWORK_STATE`, and `CAMERA` (camera is used only for the
  on-device QR scan, and is optional). `android:allowBackup="false"`.

## Supported versions

This is a rolling hobby project; only the latest `main` is maintained.
