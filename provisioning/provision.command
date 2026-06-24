#!/bin/bash
echo "======================================================="
echo "       Portal-Frame v1.5.7 One-Click Installer        "
echo "======================================================="
echo ""

# 1. Grab platform tools depending on OS
if [ ! -d "platform-tools" ]; then
    echo "[+] Downloading ADB platform-tools..."
    if [[ "$OSTYPE" == "darwin"* ]]; then
        curl -L -o tools.zip https://dl.google.com/android/repository/platform-tools-latest-darwin.zip
    else
        curl -L -o tools.zip https://dl.google.com/android/repository/platform-tools-latest-linux.zip
    fi
    unzip tools.zip && rm tools.zip
fi
ADB="./platform-tools/adb"

# 2. Download APK assets
echo "[+] Downloading required applications..."
curl -L -o portal-frame.apk https://github.com/Tech33/Portal-Frame/releases/download/v1.5.13/Frame.apk

echo ""
echo "Please connect your Meta Portal via USB."
echo "Ensure ADB is enabled under Settings -> Debug -> ADB Enabled."
read -p "Press [Enter] when ready to continue..."

echo "[+] Waiting for Portal connection..."
$ADB wait-for-device
echo "[+] Device connected!"

# 3. Process installations
echo "[+] Sideloading Portal-Frame..."
$ADB install -r -d portal-frame.apk

# 4. Grant Required Permissions via ADB
echo "[+] Automating application permissions..."
# Grant Portal-Frame camera access (used for setup QR scanning)
$ADB shell pm grant com.portalhacks.frame android.permission.CAMERA 2>/dev/null
# Attempt secure settings grant (silencing errors if firmware restricts it)
$ADB shell pm grant com.portalhacks.frame android.permission.WRITE_SECURE_SETTINGS 2>/dev/null

# Disable Meta installer overlay to restore native package installer buttons
$ADB shell cmd overlay disable --user 0 com.oculus.apps.installer.overlay 2>/dev/null || true
# Disable background safety verifier to stop OS from intercepting sideloaded apps
$ADB shell settings put global package_verifier_enable 0 2>/dev/null || true

# 5. Boot straight into Portal-Frame
echo "[+] Booting up Portal-Frame..."
$ADB shell monkey -p com.portalhacks.frame -c android.intent.category.LAUNCHER 1

echo ""
echo "======================================================="
echo "SUCCESS: Installation and Permission Grant Complete!"
echo "======================================================="
rm portal-frame.apk
