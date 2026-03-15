package com.LingoCloudTranslate.lingocloud;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.DropDownPreference;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * SettingsActivity - Module Control Center for LingoCloud
 * Android 15 (SDK 35) Compatible
 *
 * Provides UI for:
 * - Enabling/disabling the module
 * - Selecting translation service (Gemini/Microsoft)
 * - Entering API keys (encrypted storage)
 * - Target language selection
 * - App whitelist configuration
 */
public class SettingsActivity extends AppCompatActivity {
    private static final String TAG = "LingoCloud";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, new SettingsFragment())
                .commit();
        }

        // Start the translation service
        startTranslationService();
    }

    private void startTranslationService() {
        Intent serviceIntent = new Intent(this, TranslationServer.class);
        startForegroundService(serviceIntent);
        Log.d(TAG, "TranslationServer started");
    }

    /**
     * Settings Fragment containing all preferences
     */
    public static class SettingsFragment extends PreferenceFragmentCompat {
        private static final String PREF_FILE = "settings";
        private MasterKey masterKey;
        private EncryptedSharedPreferences encryptedPrefs;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            getPreferenceManager().setSharedPreferencesName(PREF_FILE);
            getPreferenceManager().setSharedPreferencesMode(android.content.Context.MODE_WORLD_READABLE);
            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            // Initialize encrypted preferences
            initEncryptedPreferences();

            // Setup preference behaviors
            setupModuleToggle();
            setupServiceProvider();
            setupApiKeyInput();
            setupTargetLanguage();
            setupAppWhitelist();
            setupTestConnection();
        }

        @Override
        public void onPause() {
            super.onPause();
            // Make preferences readable by Xposed module
            try {
                java.io.File prefsDir = new java.io.File(requireContext().getApplicationInfo().dataDir, "shared_prefs");
                java.io.File prefsFile = new java.io.File(prefsDir, PREF_FILE + ".xml");
                if (prefsDir.exists()) {
                    prefsDir.setExecutable(true, false);
                    prefsDir.setReadable(true, false);
                }
                if (prefsFile.exists()) {
                    prefsFile.setReadable(true, false);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to set preference permissions", e);
            }
        }

        private void initEncryptedPreferences() {
            try {
                masterKey = new MasterKey.Builder(requireContext())
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

                encryptedPrefs = (EncryptedSharedPreferences) EncryptedSharedPreferences.create(
                    requireContext(),
                    "secure_settings",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize encrypted preferences", e);
            }
        }

        private void setupModuleToggle() {
            SwitchPreferenceCompat moduleToggle = findPreference("module_enabled");
            if (moduleToggle != null) {
                moduleToggle.setOnPreferenceChangeListener((preference, newValue) -> {
                    boolean enabled = (Boolean) newValue;
                    String msg = enabled ? "LingoCloud enabled" : "LingoCloud disabled";
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
                    return true;
                });
            }
        }

        private void setupServiceProvider() {
            DropDownPreference serviceProvider = findPreference("service_provider");
            if (serviceProvider != null) {
                serviceProvider.setOnPreferenceChangeListener((preference, newValue) -> {
                    String service = (String) newValue;
                    Toast.makeText(requireContext(),
                        "Service changed to: " + service, Toast.LENGTH_SHORT).show();
                    return true;
                });
            }
        }

        private void setupApiKeyInput() {
            setupSpecificApiKeyInput("gemini_api_key");
            setupSpecificApiKeyInput("microsoft_api_key");
        }

        private void setupSpecificApiKeyInput(String prefKey) {
            EditTextPreference apiKeyPref = findPreference(prefKey);
            if (apiKeyPref != null) {
                // Mask the API key input
                apiKeyPref.setOnBindEditTextListener(editText -> {
                    editText.setInputType(
                        InputType.TYPE_CLASS_TEXT |
                        InputType.TYPE_TEXT_VARIATION_PASSWORD
                    );
                });

                // Show masked version in summary
                apiKeyPref.setSummaryProvider(preference -> {
                    String value = ((EditTextPreference) preference).getText();
                    if (value == null || value.isEmpty()) {
                        return "Not set (required for translation)";
                    }
                    return "••••••••" + value.substring(Math.max(0, value.length() - 4));
                });

                // Save to encrypted preferences
                apiKeyPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    String key = (String) newValue;
                    if (key != null && !key.trim().isEmpty()) {
                        // Also store in encrypted prefs for extra security
                        if (encryptedPrefs != null) {
                            encryptedPrefs.edit().putString("encrypted_" + prefKey, key).apply();
                        }
                        Toast.makeText(requireContext(),
                            "API Key saved securely", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                });
            }
        }

        private void setupTargetLanguage() {
            DropDownPreference targetLang = findPreference("target_lang");
            if (targetLang != null) {
                targetLang.setOnPreferenceChangeListener((preference, newValue) -> {
                    String lang = (String) newValue;
                    Toast.makeText(requireContext(),
                        "Target language: " + getLanguageDisplayName(lang),
                        Toast.LENGTH_SHORT).show();
                    return true;
                });
            }
        }

        private void setupAppWhitelist() {
            EditTextPreference whitelistPref = findPreference("app_whitelist");
            if (whitelistPref != null) {
                whitelistPref.setSummaryProvider(preference -> {
                    String value = ((EditTextPreference) preference).getText();
                    if (value == null || value.isEmpty()) {
                        return "All apps (leave empty to translate all)";
                    }
                    return "Apps: " + value;
                });

                whitelistPref.setDialogMessage(
                    "Enter package names separated by commas\n" +
                    "Example: com.instagram.android, com.twitter.android"
                );
            }
        }

        private void setupTestConnection() {
            Preference testPref = findPreference("test_connection");
            if (testPref != null) {
                testPref.setOnPreferenceClickListener(preference -> {
                    testApiConnection();
                    return true;
                });
            }
        }

        private void testApiConnection() {
            String service = getPreferenceManager()
                .getSharedPreferences()
                .getString("service_provider", "Gemini");
            String apiKeyKey = service.equals("Gemini") ? "gemini_api_key" : "microsoft_api_key";
            String apiKey = getPreferenceManager()
                .getSharedPreferences()
                .getString(apiKeyKey, "");
            String targetLang = getPreferenceManager()
                .getSharedPreferences()
                .getString("target_lang", "en");

            if (apiKey.isEmpty()) {
                Toast.makeText(requireContext(),
                    "Please enter an API key for " + service + " first", Toast.LENGTH_LONG).show();
                return;
            }

            Toast.makeText(requireContext(),
                "Testing " + service + " connection...", Toast.LENGTH_SHORT).show();

            TranslationClient client = new TranslationClient();
            client.translate("Hello World", service, apiKey, targetLang, result -> {
                requireActivity().runOnUiThread(() -> {
                    if (result != null && !result.isEmpty()) {
                        Toast.makeText(requireContext(),
                            "Success! Translation: " + result, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(requireContext(),
                            "Connection failed. Check API key.", Toast.LENGTH_LONG).show();
                    }
                });
            });
        }

        private String getLanguageDisplayName(String code) {
            switch (code) {
                case "en": return "English";
                case "es": return "Spanish";
                case "fr": return "French";
                case "de": return "German";
                case "it": return "Italian";
                case "pt": return "Portuguese";
                case "ru": return "Russian";
                case "ja": return "Japanese";
                case "ko": return "Korean";
                case "zh": return "Chinese (Simplified)";
                case "ar": return "Arabic";
                case "hi": return "Hindi";
                default: return code;
            }
        }
    }
}
