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

# Download latest Frame.apk if it doesn't exist
if [ ! -f "Frame.apk" ]; then
    echo "Downloading the latest version of Frame APK..."
    curl -L -o Frame.apk https://github.com/Tech33/Portal-Frame/releases/latest/download/Frame.apk
fi

echo "Connecting to device... Please plug in your Meta Portal via USB and authorize the USB debugging prompt on the screen."
$ADB wait-for-device
echo "Device connected!"

echo "1. Installing Frame APK..."
$ADB install -r Frame.apk

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
$ADB shell settings put secure install_non_market_apps 1

echo "5. Freezing OS updates..."
$ADB shell pm disable-user --user 0 com.facebook.systemupdates 2>/dev/null
$ADB shell pm disable-user --user 0 com.facebook.portal.updater 2>/dev/null
$ADB shell pm disable-user --user 0 com.facebook.updater 2>/dev/null
$ADB shell pm disable-user --user 0 com.oculus.updater 2>/dev/null

echo "6. Replacing home screen (disabling Aloha launcher)..."
$ADB shell pm disable-user --user 0 com.facebook.aloha.launcher 2>/dev/null

echo "7. Setting Frame as screensaver and enabling guard..."
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
