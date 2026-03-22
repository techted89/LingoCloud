package com.LingoCloudTranslate.lingocloud;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for HookMain.TranslationCache - the thread-safe in-memory cache
 * used for translation results and in-flight request deduplication.
 */
public class TranslationCacheTest {

    @Before
    public void setUp() {
        // Reset all static state before each test to guarantee isolation
        HookMain.TranslationCache.clear();
    }

    // --- get / put ---

    @Test
    public void get_missingKey_returnsNull() {
        assertNull(HookMain.TranslationCache.get("nonexistent"));
    }

    @Test
    public void put_thenGet_returnsCachedValue() {
        HookMain.TranslationCache.put("Hello", "Hola");
        assertEquals("Hola", HookMain.TranslationCache.get("Hello"));
    }

    @Test
    public void put_overwritesExistingEntry() {
        HookMain.TranslationCache.put("Hello", "Hola");
        HookMain.TranslationCache.put("Hello", "Salut");
        assertEquals("Salut", HookMain.TranslationCache.get("Hello"));
    }

    @Test
    public void put_multipleKeys_storedIndependently() {
        HookMain.TranslationCache.put("Hello", "Hola");
        HookMain.TranslationCache.put("Goodbye", "Adios");
        assertEquals("Hola", HookMain.TranslationCache.get("Hello"));
        assertEquals("Adios", HookMain.TranslationCache.get("Goodbye"));
    }

    // --- clear ---

    @Test
    public void clear_removesAllCachedEntries() {
        HookMain.TranslationCache.put("Hello", "Hola");
        HookMain.TranslationCache.put("World", "Mundo");
        HookMain.TranslationCache.clear();
        assertNull(HookMain.TranslationCache.get("Hello"));
        assertNull(HookMain.TranslationCache.get("World"));
    }

    @Test
    public void clear_resetsFetchingSet() {
        HookMain.TranslationCache.markFetching("SomeText");
        assertTrue(HookMain.TranslationCache.isFetching("SomeText"));
        HookMain.TranslationCache.clear();
        assertFalse(HookMain.TranslationCache.isFetching("SomeText"));
    }

    // --- isFetching ---

    @Test
    public void isFetching_beforeAnyMark_returnsFalse() {
        assertFalse(HookMain.TranslationCache.isFetching("NotMarked"));
    }

    @Test
    public void isFetching_afterMarkFetching_returnsTrue() {
        HookMain.TranslationCache.markFetching("SomeText");
        assertTrue(HookMain.TranslationCache.isFetching("SomeText"));
    }

    // --- markFetching ---

    @Test
    public void markFetching_addsKeyToFetchingSet() {
        assertFalse(HookMain.TranslationCache.isFetching("TextA"));
        HookMain.TranslationCache.markFetching("TextA");
        assertTrue(HookMain.TranslationCache.isFetching("TextA"));
    }

    @Test
    public void markFetching_calledTwice_keyStillMarked() {
        HookMain.TranslationCache.markFetching("TextB");
        HookMain.TranslationCache.markFetching("TextB"); // idempotent
        assertTrue(HookMain.TranslationCache.isFetching("TextB"));
    }

    // --- unmarkFetching ---

    @Test
    public void unmarkFetching_removesKeyFromFetchingSet() {
        HookMain.TranslationCache.markFetching("TextC");
        HookMain.TranslationCache.unmarkFetching("TextC");
        assertFalse(HookMain.TranslationCache.isFetching("TextC"));
    }

    @Test
    public void unmarkFetching_nonExistentKey_doesNotThrow() {
        // Should be a no-op, not an exception
        HookMain.TranslationCache.unmarkFetching("NeverMarked");
        assertFalse(HookMain.TranslationCache.isFetching("NeverMarked"));
    }

    // --- tryMarkFetching ---

    @Test
    public void tryMarkFetching_firstCall_returnsTrueAndMarks() {
        assertTrue(HookMain.TranslationCache.tryMarkFetching("TextD"));
        assertTrue(HookMain.TranslationCache.isFetching("TextD"));
    }

    @Test
    public void tryMarkFetching_alreadyMarked_returnsFalse() {
        HookMain.TranslationCache.markFetching("TextE");
        assertFalse(HookMain.TranslationCache.tryMarkFetching("TextE"));
    }

    @Test
    public void tryMarkFetching_afterUnmark_returnsTrueAgain() {
        HookMain.TranslationCache.markFetching("TextF");
        HookMain.TranslationCache.unmarkFetching("TextF");
        assertTrue(HookMain.TranslationCache.tryMarkFetching("TextF"));
    }

    @Test
    public void tryMarkFetching_doesNotInterfereWithCacheEntries() {
        HookMain.TranslationCache.put("TextG", "TranslatedG");
        assertTrue(HookMain.TranslationCache.tryMarkFetching("TextG"));
        // Cache entry must still be intact
        assertEquals("TranslatedG", HookMain.TranslationCache.get("TextG"));
    }

    // --- boundary / regression ---

    @Test
    public void put_emptyStringKey_storedAndRetrieved() {
        HookMain.TranslationCache.put("", "EmptyKeyValue");
        assertEquals("EmptyKeyValue", HookMain.TranslationCache.get(""));
    }

    @Test
    public void get_afterClear_returnsNullEvenForPreviouslyStoredKey() {
        HookMain.TranslationCache.put("Persist", "Value");
        HookMain.TranslationCache.clear();
        assertNull(HookMain.TranslationCache.get("Persist"));
    }

    @Test
    public void tryMarkFetching_multipleDifferentKeys_areIndependent() {
        assertTrue(HookMain.TranslationCache.tryMarkFetching("KeyAlpha"));
        assertTrue(HookMain.TranslationCache.tryMarkFetching("KeyBeta"));
        // Both should be marked
        assertTrue(HookMain.TranslationCache.isFetching("KeyAlpha"));
        assertTrue(HookMain.TranslationCache.isFetching("KeyBeta"));
        // Second call for each should fail
        assertFalse(HookMain.TranslationCache.tryMarkFetching("KeyAlpha"));
        assertFalse(HookMain.TranslationCache.tryMarkFetching("KeyBeta"));
    }
}