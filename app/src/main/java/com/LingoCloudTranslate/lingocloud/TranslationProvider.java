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

    /**
     * Perform provider initialization.
     *
     * @return `true` if the provider was successfully loaded, `false` otherwise.
     */
    @Override
    public boolean onCreate() {
        return true;
    }

    /**
     * Looks up a cached translation for the provided original text and target language and returns it as a cursor.
     *
     * @param selectionArgs an array where index 0 is the original text and index 1 is the target language to look up
     * @return a Cursor with columns "original" and "translated" containing one row when a cached translation exists, an empty cursor when no cached translation exists, or `null` if `selectionArgs` is `null` or has fewer than two elements
     */
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

    /**
     * Stores a translation in the provider's in-memory cache and notifies content observers.
     *
     * @param uri    the content URI for this insertion operation
     * @param values a ContentValues object expected to contain the keys "original", "translated", and "targetLang"; when all three are present the translation is saved in the cache
     * @return       the same `uri` passed to the method
     */
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

    /**
     * Ignores update requests for translations; this provider does not support modifying stored entries.
     *
     * @param uri the target content URI for the update request
     * @param values the new values to apply (ignored)
     * @param selection the optional WHERE clause to select rows (ignored)
     * @param selectionArgs arguments for the selection clause (ignored)
     * @return 0 to indicate that zero rows were updated
     */
    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    /**
     * Does not remove any records; deletion is not supported by this provider.
     *
     * @param uri the content URI to delete from (ignored)
     * @param selection selection clause to apply (ignored)
     * @param selectionArgs selection arguments (ignored)
     * @return the number of rows deleted, always 0
     */
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    /**
     * Provide the MIME type for the translations content endpoint.
     *
     * @param uri the URI whose MIME type is requested; this implementation ignores the URI
     * @return the MIME type `vnd.android.cursor.dir/vnd.com.LingoCloudTranslate.lingocloud.translation` indicating a directory of translation records
     */
    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.com.LingoCloudTranslate.lingocloud.translation";
    }
}
