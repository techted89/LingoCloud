# LingoCloud Translator

A production-grade Xposed Module for real-time UI translation on Android 15 (SDK 35).

## Features

- **Real-time Translation**: Intercepts and translates UI text in any app
- **Dual API Support**: Google Gemini 2.0 Flash & Microsoft Azure Translator
- **Android 15 Compatible**: Built for SDK 35 with proper foreground service handling
- **Encrypted Storage**: API keys stored securely using Android Keystore
- **Smart Caching**: LRU cache prevents repeated API calls
- **Recursion Guard**: Prevents infinite translation loops
- **App Whitelist**: Select which apps to translate

## Architecture

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  Target App     │────▶│   Xposed Hook    │────▶│  Translation    │
│  (Instagram,    │     │  (HookMain.java) │     │  Client         │
│   Telegram)     │◄────│                  │◄────│  (OkHttp)       │
└─────────────────┘     └──────────────────┘     └─────────────────┘
                              │                           │
                              ▼                           ▼
                       ┌──────────────┐          ┌───────────────┐
                       │ LRU Cache    │          │ Gemini / MS   │
                       │ (500 items)  │          │ Translate API │
                       └──────────────┘          └───────────────┘
```

## Project Structure

```
lingocloud/
├── app/
│   ├── build.gradle                    # Module dependencies (Xposed API = compileOnly)
│   └── src/main/
│       ├── AndroidManifest.xml         # Xposed metadata & permissions
│       ├── assets/
│       │   └── xposed_init             # Entry point: com.example.lingocloud.HookMain
│       ├── java/com/example/lingocloud/
│       │   ├── HookMain.java           # Main Xposed hook implementation
│       │   ├── TranslationClient.java  # HTTP client for Gemini & Microsoft APIs
│       │   ├── TranslationServer.java  # Foreground Service (Android 15 FGS)
│       │   └── SettingsActivity.java   # Module configuration UI
│       └── res/
│           ├── xml/root_preferences.xml # Settings UI layout
│           ├── layout/settings_activity.xml
│           └── values/
│               ├── strings.xml
│               ├── colors.xml
│               └── themes.xml
├── build.gradle                        # Project-level build config
├── settings.gradle
└── gradle.properties
```

## Building

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- JDK 11 or higher
- Android SDK 35

### Build Steps

1. Open project in Android Studio
2. Sync Gradle files
3. Build → Build Bundle(s) / APK(s) → Build APK(s)
4. Signed APK will be in `app/build/outputs/apk/release/`

### Command Line Build

```bash
./gradlew assembleRelease
```

## Installation

### 1. Prerequisites

- Rooted device with Magisk/KernelSU
- LSPosed framework installed (API 93+)
- Zygisk enabled in LSPosed

### 2. Install Module

```bash
# Push APK to device
adb install -r app/build/outputs/apk/release/app-release.apk

# Or install manually by opening the APK
```

### 3. Configure LSPosed

1. Open LSPosed Manager
2. Go to **Modules** tab
3. Find **LingoCloud Translator**
4. Enable the module
5. Select target apps in **Scope** (e.g., Instagram, Telegram, Twitter)
6. Reboot device

### 4. Configure Module

1. Open **LingoCloud Translator** app
2. Enable **Translation** toggle
3. Select **Service Provider** (Gemini or Microsoft)
4. Enter your **API Key**
5. Select **Target Language**
6. (Optional) Configure **App Whitelist**
7. Tap **Test Connection** to verify

## API Keys

### Google Gemini

1. Visit [Google AI Studio](https://makersuite.google.com/app/apikey)
2. Create an API key
3. Copy and paste into LingoCloud settings

### Microsoft Translator

1. Visit [Azure Portal](https://portal.azure.com/)
2. Create a **Translator** resource
3. Copy **Key 1** from **Keys and Endpoint**
4. Paste into LingoCloud settings

## Configuration Options

| Setting | Description | Default |
|---------|-------------|---------|
| `module_enabled` | Master toggle for translation | `true` |
| `service_provider` | API to use (Gemini/Microsoft) | `Gemini` |
| `gemini_api_key` | Your Gemini API key (encrypted) | `""` |
| `microsoft_api_key` | Your Microsoft API key (encrypted) | `""` |
| `target_lang` | Output language code | `en` |
| `app_whitelist` | Comma-separated package names | `""` (all apps) |

## Technical Details

### Hook Strategy

The module uses a multi-layer hooking approach:

1. **TextView.setText()** - Primary hook for standard UI elements
2. **StaticLayout.Builder** - Secondary hook for custom-drawn text

### Android 15 Compatibility

- `compileSdk 35` with `targetSdk 35`
- `XSharedPreferences` for cross-process settings (required for API 93+)
- `ForegroundService` with `dataSync` type for background operation
- `compileOnly` Xposed API to avoid ClassCastException

### Security

- API keys encrypted using Android Keystore (AES-256-GCM)
- `EncryptedSharedPreferences` for secure storage
- World-readable prefs only for LSPosed compatibility

### Performance

- **LRU Cache**: 500 entries to minimize API calls
- **Async Translation**: Non-blocking HTTP requests
- **Thread Pool**: 3 concurrent translation workers
- **Recursion Guard**: Prevents infinite loops
- **Text Filtering**: Skips numeric/symbol-only strings

## Troubleshooting

### Module Not Working

1. Check LSPosed logs: `LSPosed Manager → Logs`
2. Verify module is enabled in LSPosed
3. Ensure target app is in LSPosed scope
4. Check `module_enabled` toggle in settings

### Translation Not Occurring

1. Verify API key is entered correctly
2. Test connection in settings
3. Check internet connectivity
4. Review logcat: `adb logcat -s LingoCloud:D`

### App Crashes

1. Check if app uses SSL pinning (Facebook/Instagram)
2. Add app to blacklist in HookMain.java
3. Check for recursive translation (tag guard should prevent this)

## Logs

View module logs via:

```bash
# LSPosed logs
adb shell cat /data/adb/lspd/log/verbose.log

# Logcat
adb logcat -s LingoCloud:D
adb logcat -s Xposed:D
```

## License

MIT License - See LICENSE file for details

## Credits

- [LSPosed Framework](https://github.com/LSPosed/LSPosed)
- [Xposed API](https://github.com/rovo89/XposedBridge)
- [OkHttp](https://square.github.io/okhttp/)
- [AndroidX Security](https://developer.android.com/jetpack/androidx/releases/security)

## Disclaimer

This module is for educational purposes. Use at your own risk. The authors are not responsible for any account bans or issues caused by using this module.
# LingoCloud
