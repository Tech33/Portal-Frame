#!/bin/bash
# Move to the directory containing this script
cd "$(dirname "$0")"

echo "=== Portal Frame Restore Tool ==="

# Check if local or system adb exists
ADB="adb"
if ! command -v adb &> /dev/null; then
    if [ -d "platform-tools" ]; then
        ADB="./platform-tools/adb"
    else
        echo "ADB not found. Downloading Android platform-tools for macOS..."
        curl -L -o platform-tools.zip https://dl.google.com/android/repository/platform-tools-latest-darwin.zip
        unzip -q platform-tools.zip
        rm platform-tools.zip
        ADB="./platform-tools/adb"
    fi
fi

echo "Connecting to device... Please plug in your Meta Portal via USB."
$ADB wait-for-device
echo "Device connected!"

echo "1. Re-enabling stock Aloha launcher..."
$ADB shell pm enable com.facebook.aloha.launcher 2>/dev/null

echo "2. Re-enabling OS updates..."
$ADB shell pm enable com.facebook.systemupdates 2>/dev/null
$ADB shell pm enable com.facebook.portal.updater 2>/dev/null
$ADB shell pm enable com.facebook.updater 2>/dev/null
$ADB shell pm enable com.oculus.updater 2>/dev/null

echo "3. Disabling on-device installs (Unknown Sources)..."
$ADB shell settings put secure install_non_market_apps 0

echo "4. Disabling Frame screensaver and guard..."
$ADB shell am broadcast -n com.portalhacks.frame/.ConfigReceiver --ez guard false 2>/dev/null
$ADB shell settings put secure screensaver_enabled 0
$ADB shell settings put secure screensaver_components ""

echo "5. Uninstalling Frame client APK..."
$ADB uninstall com.portalhacks.frame

echo "=== Restore Complete! Stock settings restored. ==="
echo ""
read -p "Press Enter to exit..."
