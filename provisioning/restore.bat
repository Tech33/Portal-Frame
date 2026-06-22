@echo off
cd /d "%~dp0"
echo === Portal Frame Restore Tool ===

:: Check if adb is on path or local
set ADB=adb
where adb >nul 2>nul
if %errorlevel% neq 0 (
    if exist "platform-tools" (
        set ADB=platform-tools\adb.exe
    ) else (
        echo ADB not found. Downloading Android platform-tools for Windows...
        curl -L -o platform-tools.zip https://dl.google.com/android/repository/platform-tools-latest-windows.zip
        echo Extracting platform-tools...
        powershell -Command "Expand-Archive -Path platform-tools.zip -DestinationPath . -Force"
        del platform-tools.zip
        set ADB=platform-tools\adb.exe
    )
)

echo Connecting to device... Please plug in your Meta Portal via USB.
%ADB% wait-for-device
echo Device connected!

echo 1. Re-enabling stock Aloha launcher...
%ADB% shell pm enable com.facebook.aloha.launcher >nul 2>nul

echo 2. Re-enabling OS updates...
%ADB% shell pm enable com.facebook.systemupdates >nul 2>nul
%ADB% shell pm enable com.facebook.portal.updater >nul 2>nul
%ADB% shell pm enable com.facebook.updater >nul 2>nul
%ADB% shell pm enable com.oculus.updater >nul 2>nul

echo 3. Disabling on-device installs (Unknown Sources)...
%ADB% shell settings put secure install_non_market_apps 0

echo 4. Disabling Frame screensaver and guard...
%ADB% shell am broadcast -n com.portalhacks.frame/.ConfigReceiver --ez guard false >nul 2>nul
%ADB% shell settings put secure screensaver_enabled 0
%ADB% shell settings put secure screensaver_components ""

echo 5. Uninstalling Frame client APK...
%ADB% uninstall com.portalhacks.frame

echo === Restore Complete! Stock settings restored. ===
echo.
pause
