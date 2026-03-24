package com.LingoCloudTranslate.lingocloud;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import de.robv.android.xposed.XposedBridge;

public class IPCClient {
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
