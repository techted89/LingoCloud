#!/bin/bash
# Build script for LingoCloud Xposed Module
# Android 15 (SDK 35) Compatible

echo "================================"
echo "LingoCloud Build Script"
echo "================================"

# Check for Android SDK
if [ -z "$ANDROID_SDK_ROOT" ] && [ -z "$ANDROID_HOME" ]; then
    echo "Error: ANDROID_SDK_ROOT or ANDROID_HOME not set"
    exit 1
fi

# Clean previous builds
echo "[1/4] Cleaning previous builds..."
./gradlew clean

# Build release APK
echo "[2/4] Building release APK..."
./gradlew assembleRelease

# Check build result
if [ $? -eq 0 ]; then
    echo "[3/4] Build successful!"
    APK_PATH="app/build/outputs/apk/release/app-release.apk"

    if [ -f "$APK_PATH" ]; then
        echo "[4/4] APK location: $APK_PATH"
        echo ""
        echo "Next steps:"
        echo "1. Sign the APK (if not already signed)"
        echo "2. Install: adb install -r $APK_PATH"
        echo "3. Enable in LSPosed Manager"
        echo "4. Select target apps in scope"
        echo "5. Reboot device"
    else
        echo "Error: APK not found at expected location"
        exit 1
    fi
else
    echo "Error: Build failed"
    exit 1
fi
