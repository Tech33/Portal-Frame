@echo off
cd /d "%~dp0"
echo === Portal Frame Provisioning Tool ===

:: Check if adb is on path or local
set ADB=adb
where adb >nul 2>nul
if %errorlevel% neq 0 (
    if not exist "platform-tools" (
        echo ADB not found. Downloading Android platform-tools for Windows...
        curl -L -o platform-tools.zip https://dl.google.com/android/repository/platform-tools-latest-windows.zip
        echo Extracting platform-tools...
        powershell -Command "Expand-Archive -Path platform-tools.zip -DestinationPath . -Force"
        del platform-tools.zip
    )
    set ADB=platform-tools\adb.exe
)

:: Download latest Frame.apk
set DOWNLOAD=true
set choice=y
if exist "Frame.apk" (
    set /p choice="Frame.apk already exists. Download the latest version from GitHub? (y/n) [y]: "
)
if /i "%choice%" neq "y" if /i "%choice%" neq "yes" (
    set DOWNLOAD=false
)

if "%DOWNLOAD%"=="true" (
    echo Downloading the latest version of Frame APK...
    curl -L -o Frame.apk https://github.com/Tech33/Portal-Frame/releases/latest/download/Frame.apk
)

echo Connecting to device... Please plug in your Meta Portal via USB and authorize the USB debugging prompt on the screen.
%ADB% wait-for-device
echo Device connected!

echo 1. Installing Frame APK...
%ADB% install -r Frame.apk > install_log.txt 2>&1
type install_log.txt

findstr /i "INSTALL_FAILED_UPDATE_INCOMPATIBLE" install_log.txt >nul
if %errorlevel% equ 0 (
    echo ------------------------------------------------------------
    echo WARNING: Signature mismatch detected!
    echo An existing version of Frame is installed with a conflicting certificate
    echo (e.g., debug vs. release key).
    echo To update, the existing app must be uninstalled first.
    echo WARNING: This will reset your on-device settings and album links.
    echo ------------------------------------------------------------
    set uninstall_choice=y
    set /p uninstall_choice="Uninstall the existing version and retry installation? (y/n) [y]: "
    if /i "%uninstall_choice%" neq "n" (
        echo Uninstalling existing app...
        %ADB% uninstall com.portalhacks.frame
        echo Reinstalling...
        %ADB% install Frame.apk
    ) else (
        echo Installation aborted by user.
        del install_log.txt
        pause
        exit /b 1
    )
) else (
    findstr /i "INSTALL_FAILED_VERSION_DOWNGRADE" install_log.txt >nul
    if %errorlevel% equ 0 (
        echo ------------------------------------------------------------
        echo WARNING: Version downgrade detected!
        echo The version you are trying to install is older than the installed version.
        echo ------------------------------------------------------------
        set downgrade_choice=y
        set /p downgrade_choice="Force downgrade? (y/n) [y]: "
        if /i "%downgrade_choice%" neq "n" (
            echo Installing with downgrade flag (-d)...
            %ADB% install -d -r Frame.apk
        ) else (
            echo Installation aborted by user.
            del install_log.txt
            pause
            exit /b 1
        )
    ) else (
        findstr /i "Failure" install_log.txt >nul
        if %errorlevel% equ 0 (
            echo ERROR: Installation failed.
            del install_log.txt
            pause
            exit /b 1
        )
    )
)
del install_log.txt

echo 2. Pushing photos...
if exist "photos" (
    %ADB% shell mkdir -p /sdcard/Pictures/
    %ADB% push photos\. /sdcard/Pictures/
    echo Photos pushed!
) else (
    echo No 'photos' folder found next to the script. Skipping photo push.
)

echo 3. Granting permissions...
%ADB% shell pm grant com.portalhacks.frame android.permission.WRITE_SECURE_SETTINGS
%ADB% shell pm grant com.portalhacks.frame android.permission.CAMERA

echo 4. Enabling on-device installs (Unknown Sources)...
REM The Portal OS 'install_non_market_apps' toggle is broken and has no effect.
REM Fix: disable the Meta RRO overlay that breaks the installer dialog, then
REM grant REQUEST_INSTALL_PACKAGES directly via appops (same approach as Immortal).
%ADB% shell pm disable-user --user 0 com.facebook.aloha.rro.niu.android >nul 2>nul
%ADB% shell appops set com.portalhacks.frame REQUEST_INSTALL_PACKAGES allow >nul 2>nul
%ADB% shell settings put secure install_non_market_apps 1

echo 5. Freezing OS updates...
%ADB% shell pm disable-user --user 0 com.facebook.systemupdates >nul 2>nul
%ADB% shell pm disable-user --user 0 com.facebook.portal.updater >nul 2>nul
%ADB% shell pm disable-user --user 0 com.facebook.updater >nul 2>nul
%ADB% shell pm disable-user --user 0 com.oculus.updater >nul 2>nul

echo 6. Replacing home screen (disabling Aloha launcher)...
%ADB% shell pm disable-user --user 0 com.facebook.aloha.launcher >nul 2>nul

echo 7. Setting Frame as screensaver and enabling guard...
%ADB% shell settings put secure screensaver_enabled 1
%ADB% shell settings put secure screensaver_components com.portalhacks.frame/.FrameDreamService
%ADB% shell settings put secure screensaver_activate_on_dock 1
%ADB% shell settings put secure screensaver_activate_on_sleep 1
%ADB% shell am broadcast -n com.portalhacks.frame/.ConfigReceiver --ez guard true

echo === Provisioning Complete! ===
echo Your Meta Portal has been provisioned as a custom device.
echo.
pause
