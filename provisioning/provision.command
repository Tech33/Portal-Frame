#!/bin/bash
# Move to the directory containing this script
cd "$(dirname "$0")"

echo "======================================================="
echo "       Portal-Frame One-Click Installer for Mac        "
echo "======================================================="
echo ""

# 1. Grab platform tools depending on OS
ADB="adb"
if ! command -v adb &> /dev/null; then
    if [ -d "platform-tools" ]; then
        ADB="./platform-tools/adb"
    else
        echo "[+] Downloading ADB platform-tools..."
        if [[ "$OSTYPE" == "darwin"* ]]; then
            curl -L -o tools.zip https://dl.google.com/android/repository/platform-tools-latest-darwin.zip
        else
            curl -L -o tools.zip https://dl.google.com/android/repository/platform-tools-latest-linux.zip
        fi
        unzip -q tools.zip && rm tools.zip
        ADB="./platform-tools/adb"
    fi
fi

# 2. Download latest APK asset
echo "[+] Downloading latest Portal-Frame application..."
curl -L -o portal-frame.apk https://github.com/Tech33/Portal-Frame/releases/latest/download/Frame.apk

echo ""
echo "Please connect your Meta Portal via USB."
echo "Ensure ADB is enabled under Settings -> Debug -> ADB Enabled."
read -p "Press [Enter] when ready to continue..."

echo "[+] Waiting for Portal connection..."
$ADB wait-for-device
echo "[+] Device connected!"

# 3. Process installations
echo "[+] Sideloading Portal-Frame..."
INSTALL_RES=$($ADB install -r -d portal-frame.apk 2>&1)
echo "$INSTALL_RES"

if [[ "$INSTALL_RES" == *"INSTALL_FAILED_UPDATE_INCOMPATIBLE"* ]]; then
    echo "------------------------------------------------------------"
    echo "WARNING: Signature mismatch detected!"
    echo "An existing version of Frame is installed with a conflicting certificate"
    echo "(e.g., debug vs. release key)."
    echo "To update, the existing app must be uninstalled first."
    echo "WARNING: This will reset your on-device settings and album links."
    echo "------------------------------------------------------------"
    read -p "Uninstall the existing version and retry installation? (y/n) [y]: " uninstall_choice
    uninstall_choice=${uninstall_choice:-y}
    if [[ "$uninstall_choice" =~ ^[Yy]$ ]]; then
        echo "[+] Uninstalling existing app..."
        $ADB uninstall com.portalhacks.frame
        echo "[+] Reinstalling..."
        $ADB install portal-frame.apk
    else
        echo "[-] Installation aborted by user."
        rm portal-frame.apk
        exit 1
    fi
fi

# 4. Grant Required Permissions via ADB
echo "[+] Automating application permissions..."
# Grant Portal-Frame camera access (used for setup QR scanning)
$ADB shell pm grant com.portalhacks.frame android.permission.CAMERA 2>/dev/null
# Secure settings grant for screensaver management
$ADB shell pm grant com.portalhacks.frame android.permission.WRITE_SECURE_SETTINGS 2>/dev/null

# 5. Enable on-device installs (Unknown Sources)
echo "[+] Enabling on-device installs (Unknown Sources)..."
$ADB shell settings put secure install_non_market_apps 1 2>/dev/null
$ADB shell cmd overlay disable --user 0 com.oculus.apps.installer.overlay 2>/dev/null || true
$ADB shell settings put global package_verifier_enable 0 2>/dev/null || true

# 6. Freeze OS updates
echo "[+] Freezing OS updates..."
$ADB shell pm disable-user --user 0 com.facebook.systemupdates 2>/dev/null || true
$ADB shell pm disable-user --user 0 com.facebook.portal.updater 2>/dev/null || true
$ADB shell pm disable-user --user 0 com.facebook.updater 2>/dev/null || true
$ADB shell pm disable-user --user 0 com.oculus.updater 2>/dev/null || true

# 7. Replace home screen (disable Aloha launcher)
echo "[+] Replacing home screen (disabling Aloha launcher)..."
$ADB shell pm disable-user --user 0 com.facebook.aloha.launcher 2>/dev/null || true

# 8. Set Frame as screensaver and enable guard (Protected Mode)
echo "[+] Setting Frame as screensaver and enabling guard..."
$ADB shell settings put secure screensaver_enabled 1 2>/dev/null
$ADB shell settings put secure screensaver_components com.portalhacks.frame/.FrameDreamService 2>/dev/null
$ADB shell settings put secure screensaver_activate_on_dock 1 2>/dev/null
$ADB shell settings put secure screensaver_activate_on_sleep 1 2>/dev/null
$ADB shell am broadcast -n com.portalhacks.frame/.ConfigReceiver --ez guard true 2>/dev/null

# 9. Boot straight into Portal-Frame
echo "[+] Booting up Portal-Frame..."
$ADB shell monkey -p com.portalhacks.frame -c android.intent.category.LAUNCHER 1

echo ""
echo "======================================================="
echo "SUCCESS: Installation and Permission Grant Complete!"
echo "======================================================="
rm portal-frame.apk
echo ""
read -p "Press [Enter] to exit..."
