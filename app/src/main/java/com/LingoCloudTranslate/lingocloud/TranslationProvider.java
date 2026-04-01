package com.LingoCloudTranslate.lingocloud;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.LruCache;

public class TranslationProvider extends ContentProvider {
    public static final Uri CONTENT_URI = Uri.parse("content://com.LingoCloudTranslate.lingocloud.provider/translations");

    // In-memory cache for sub-millisecond IPC reads
    private static final LruCache<String, String> memoryCache = new LruCache<>(5000);

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (selectionArgs == null || selectionArgs.length < 2) return null;
        String originalText = selectionArgs[0];
        String targetLang = selectionArgs[1];
        String cacheKey = originalText + "_" + targetLang;

        String translated = memoryCache.get(cacheKey);

        MatrixCursor cursor = new MatrixCursor(new String[]{"original", "translated"});
        if (translated != null) {
            cursor.addRow(new Object[]{originalText, translated});
        }
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (values == null) return uri;
        String originalText = values.getAsString("original");
        String translated = values.getAsString("translated");
        String targetLang = values.getAsString("targetLang");

        if (originalText != null && translated != null && targetLang != null) {
            memoryCache.put(originalText + "_" + targetLang, translated);
        }

        if (getContext() != null) {
            getContext().getContentResolver().notifyChange(uri, null);
        }
        return uri;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.com.LingoCloudTranslate.lingocloud.translation";
    }
}
