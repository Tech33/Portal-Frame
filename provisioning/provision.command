#!/bin/bash
# Move to the directory containing this script
cd "$(dirname "$0")"

echo "=== Portal Frame Provisioning Tool ==="

# Check if local or system adb exists
ADB="adb"
if ! command -v adb &> /dev/null; then
    if [ ! -d "platform-tools" ]; then
        echo "ADB not found. Downloading Android platform-tools for macOS..."
        curl -L -o platform-tools.zip https://dl.google.com/android/repository/platform-tools-latest-darwin.zip
        unzip -q platform-tools.zip
        rm platform-tools.zip
    fi
    ADB="./platform-tools/adb"
fi

# Download latest Frame.apk
DOWNLOAD=true
if [ -f "Frame.apk" ]; then
    read -p "Frame.apk already exists. Download the latest version from GitHub? (y/n) [y]: " choice
    choice=${choice:-y}
    if [[ ! "$choice" =~ ^[Yy]$ ]]; then
        DOWNLOAD=false
    fi
fi

if [ "$DOWNLOAD" = true ]; then
    echo "Downloading the latest version of Frame APK..."
    curl -L -o Frame.apk https://github.com/Tech33/Portal-Frame/releases/latest/download/Frame.apk
fi

echo "Connecting to device... Please plug in your Meta Portal via USB and authorize the USB debugging prompt on the screen."
$ADB wait-for-device
echo "Device connected!"

echo "1. Installing Frame APK..."
INSTALL_OUTPUT=$($ADB install -r Frame.apk 2>&1)
echo "$INSTALL_OUTPUT"
if echo "$INSTALL_OUTPUT" | grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE"; then
    echo "------------------------------------------------------------"
    echo "⚠️  WARNING: Signature mismatch detected!"
    echo "An existing version of Frame is installed with a conflicting certificate"
    echo "(e.g., debug vs. release key)."
    echo "To update, the existing app must be uninstalled first."
    echo "WARNING: This will reset your on-device settings and album links."
    echo "------------------------------------------------------------"
    read -p "Uninstall the existing version and retry installation? (y/n) [y]: " uninstall_choice
    uninstall_choice=${uninstall_choice:-y}
    if [[ "$uninstall_choice" =~ ^[Yy]$ ]]; then
        echo "Uninstalling existing app..."
        $ADB uninstall com.portalhacks.frame
        echo "Reinstalling..."
        $ADB install Frame.apk
    else
        echo "❌ Installation aborted by user."
        exit 1
    fi
elif echo "$INSTALL_OUTPUT" | grep -q "INSTALL_FAILED_VERSION_DOWNGRADE"; then
    echo "------------------------------------------------------------"
    echo "⚠️  WARNING: Version downgrade detected!"
    echo "The version you are trying to install is older than the installed version."
    echo "------------------------------------------------------------"
    read -p "Force downgrade? (y/n) [y]: " downgrade_choice
    downgrade_choice=${downgrade_choice:-y}
    if [[ "$downgrade_choice" =~ ^[Yy]$ ]]; then
        echo "Installing with downgrade flag (-d)..."
        $ADB install -d -r Frame.apk
    else
        echo "❌ Installation aborted by user."
        exit 1
    fi
elif echo "$INSTALL_OUTPUT" | grep -q "Failure"; then
    echo "❌ ERROR: Installation failed: $INSTALL_OUTPUT"
    exit 1
fi

echo "2. Pushing photos..."
if [ -d "photos" ]; then
    $ADB shell mkdir -p /sdcard/Pictures/
    $ADB push photos/. /sdcard/Pictures/
    echo "Photos pushed!"
else
    echo "No 'photos' folder found next to the script. Skipping photo push."
fi

echo "3. Granting permissions..."
$ADB shell pm grant com.portalhacks.frame android.permission.WRITE_SECURE_SETTINGS
$ADB shell pm grant com.portalhacks.frame android.permission.CAMERA

echo "4. Enabling on-device installs (Unknown Sources)..."
# The Portal OS 'install_non_market_apps' toggle is broken and has no effect.
# Fix: disable the Meta RRO overlay that breaks the installer dialog, then
# grant REQUEST_INSTALL_PACKAGES directly via appops (same approach as Immortal).
$ADB shell pm disable-user --user 0 com.facebook.aloha.rro.niu.android 2>/dev/null || true
$ADB shell appops set com.portalhacks.frame REQUEST_INSTALL_PACKAGES allow 2>/dev/null || true
$ADB shell settings put secure install_non_market_apps 1

echo "5. Installing Shizuku (silent in-app update bridge)..."
# Shizuku lets Frame install APKs silently via the ADB shell user, completely
# bypassing the Portal's broken/invisible package installer dialog.
SHIZUKU_APK="shizuku.apk"
if [ ! -f "$SHIZUKU_APK" ]; then
    echo "   Downloading Shizuku..."
    SHIZUKU_URL=$(curl -sL "https://api.github.com/repos/RikkaApps/Shizuku/releases/latest" \
        | python3 -c "import sys,json; r=json.load(sys.stdin); print(next(a['browser_download_url'] for a in r['assets'] if a['name'].endswith('.apk')))")
    curl -L -o "$SHIZUKU_APK" "$SHIZUKU_URL"
fi
$ADB install -r "$SHIZUKU_APK" 2>/dev/null || true
echo "   Starting Shizuku ADB service..."
$ADB shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh 2>/dev/null || \
    echo "   ⚠️  Shizuku start.sh not found — open the Shizuku app on Portal first, then re-run this script."

echo "6. Freezing OS updates..."
$ADB shell pm disable-user --user 0 com.facebook.systemupdates 2>/dev/null
$ADB shell pm disable-user --user 0 com.facebook.portal.updater 2>/dev/null
$ADB shell pm disable-user --user 0 com.facebook.updater 2>/dev/null
$ADB shell pm disable-user --user 0 com.oculus.updater 2>/dev/null

echo "7. Replacing home screen (disabling Aloha launcher)..."
$ADB shell pm disable-user --user 0 com.facebook.aloha.launcher 2>/dev/null

echo "8. Setting Frame as screensaver and enabling guard..."
$ADB shell settings put secure screensaver_enabled 1
$ADB shell settings put secure screensaver_components com.portalhacks.frame/.FrameDreamService
$ADB shell settings put secure screensaver_activate_on_dock 1
$ADB shell settings put secure screensaver_activate_on_sleep 1
# Enable screensaver guard in the app prefs
$ADB shell am broadcast -n com.portalhacks.frame/.ConfigReceiver --ez guard true

echo "=== Provisioning Complete! ==="
echo "Your Meta Portal has been provisioned as a custom device."
echo ""
read -p "Press Enter to exit..."
