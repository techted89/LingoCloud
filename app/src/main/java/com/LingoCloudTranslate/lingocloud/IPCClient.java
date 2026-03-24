package com.LingoCloudTranslate.lingocloud;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import de.robv.android.xposed.XposedBridge;

public class IPCClient {
    /**
     * Looks up a cached translation for the given source text and language from the local content provider.
     *
     * @param context      the Android context used to access the ContentResolver; may be null
     * @param originalText the source text to translate
     * @param targetLang   the target language code for the translation
     * @return the translated text if a matching cached entry is found; `null` if not found, if `context` is null, or if an error occurs
     */
    public static String getTranslationFast(Context context, String originalText, String targetLang) {
        if (context == null) return null;
        Uri uri = Uri.parse("content://com.LingoCloudTranslate.lingocloud.provider/translations");

        try (Cursor cursor = context.getContentResolver().query(
                uri, null, "original = ?", new String[]{originalText, targetLang}, null)) {

            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndexOrThrow("translated"));
            }
        } catch (Exception e) {
            XposedBridge.log("LingoCloud IPC Error: " + e.getMessage());
        }
        return null; // Cache miss
    }
}
