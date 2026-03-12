# LingoCloud - Quick Start Guide

## 1. Build the Module

```bash
cd /path/to/lingocloud
./gradlew assembleRelease
```

## 2. Install on Device

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

## 3. Configure LSPosed

1. Open **LSPosed Manager**
2. Go to **Modules** tab
3. Enable **LingoCloud Translator**
4. Set **Scope** to target apps (e.g., Instagram, Telegram)
5. **Reboot**

## 4. Configure Module

1. Open **LingoCloud** app
2. Enable **Translation** toggle
3. Select **Service** (Gemini/Microsoft)
4. Enter **API Key**
5. Select **Target Language**
6. Tap **Test Connection**

## API Key Setup

### Google Gemini (Recommended - Free tier available)

```
https://makersuite.google.com/app/apikey
```

### Microsoft Translator

```
https://portal.azure.com/ → Translator Resource → Keys
```

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Module not appearing | Check `xposed_init` in assets/ |
| No translation | Check LSPosed logs, verify API key |
| App crashes | Add package to blacklist in HookMain.java |
| High battery usage | Reduce target apps in whitelist |

## File Reference

| File | Purpose |
|------|---------|
| `HookMain.java` | Xposed hook entry point |
| `TranslationClient.java` | HTTP client for APIs |
| `TranslationServer.java` | Foreground service |
| `SettingsActivity.java` | Configuration UI |
| `AndroidManifest.xml` | Xposed metadata |
| `xposed_init` | Hook class path |

## Code Snippets

### Add to Whitelist

```java
// In HookMain.java, BLACKLISTED_PACKAGES
add("com.your.app");
```

### Custom Translation Logic

```java
// In TranslationClient.java
case "Custom":
    callCustomApi(text, apiKey, callback);
    break;
```

### Debug Logging

```bash
adb logcat -s LingoCloud:D
```

## Architecture Flow

```
App UI Text
    ↓
TextView.setText() [Hooked]
    ↓
HookMain.handleTextUpdate()
    ↓
Check Cache → Miss
    ↓
TranslationClient.translate()
    ↓
OkHttp → Gemini/Microsoft API
    ↓
Cache Result
    ↓
Update TextView (UI Thread)
```

## Android 15 Specifics

- `compileSdk 35`
- `XSharedPreferences` (API 93+)
- `FOREGROUND_SERVICE_DATA_SYNC`
- `compileOnly` Xposed API

## Support

- LSPosed: https://github.com/LSPosed/LSPosed
- Xposed: https://forum.xda-developers.com/xposed
