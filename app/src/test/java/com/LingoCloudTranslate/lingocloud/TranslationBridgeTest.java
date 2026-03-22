package com.LingoCloudTranslate.lingocloud;

import android.os.Handler;
import android.webkit.WebView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.os.Handler;
import android.webkit.WebView;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;

public class TranslationBridgeTest {

    private WebView mockWebView;
    private TranslationBridge.JavascriptExecutor mockJsExecutor;
    private Handler mockHandler;
    private TranslationBridge bridge;

    @Before
    public void setup() {
        mockWebView = mock(WebView.class);
        mockJsExecutor = mock(TranslationBridge.JavascriptExecutor.class);
        mockHandler = mock(Handler.class);

        // Mock handler to run immediately
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return true;
        }).when(mockHandler).post(any(Runnable.class));

        // Ensure JSONObject works in tests without crashing
        // The Android test environment might throw a "Method ... not mocked."
        // but `unitTests.returnDefaultValues = true` is in build.gradle

        bridge = new TranslationBridge(mockWebView, mockJsExecutor, mockHandler);

        // Ensure cache is clean before each test
        HookMain.TranslationCache.clear();
    }

    @After
    public void tearDown() {
        HookMain.TranslationCache.clear();
    }

    // -------------------------------------------------------------------------
    // Null / empty input guards
    // -------------------------------------------------------------------------

    @Test
    public void testRequestTranslation_cachedHit() {
        // Arrange
        String originalText = "Hello World";
        String translatedText = "Hola Mundo";
        String domNodeIdsJson = "[\"node-1\"]";

        // Explicitly put in the cache directly.
        HookMain.translationCache = mock(android.util.LruCache.class);
        org.mockito.Mockito.when(HookMain.translationCache.get(originalText)).thenReturn(translatedText);

        // Act
        bridge.requestTranslation(originalText, domNodeIdsJson);

        // Assert
        verify(mockJsExecutor).evaluateJavascript(anyString());
    }

    // -------------------------------------------------------------------------
    // Cache-hit path
    // -------------------------------------------------------------------------

    @Test
    public void testRequestTranslation_inFlightDeduplication() {
        // Arrange
        String originalText = "Hello World";
        String domNodeIdsJson1 = "[\"node-1\"]";
        String domNodeIdsJson2 = "[\"node-2\"]";

        // The first call puts it in-flight
        bridge.requestTranslation(originalText, domNodeIdsJson1);

        // The second call should add to the pending list without initiating another translation
        bridge.requestTranslation(originalText, domNodeIdsJson2);

        // In the mock environment, no cache is populated yet. The in-flight deduplication logic
        // happens before calling translation client, so mockJsExecutor should not have been called
        // because the async translation doesn't execute its callback in this mocked environment.
        verify(mockJsExecutor, org.mockito.Mockito.never()).evaluateJavascript(anyString());
    }

    // -------------------------------------------------------------------------
    // Null handler – runnable executes synchronously
    // -------------------------------------------------------------------------

    @Test
    public void testRequestTranslation_NullHandler_ExecutesRunnableDirectly() throws Exception {
        // Build bridge with null Handler; injectTranslationBackToDOM must still run the runnable
        TranslationBridge nullHandlerBridge = new TranslationBridge(mockWebView, mockJsExecutor, null);

        try (MockedStatic<HookMain.TranslationCache> mockedCache = mockStatic(HookMain.TranslationCache.class)) {
            mockedCache.when(() -> HookMain.TranslationCache.get("Hello")).thenReturn("Hola");

            nullHandlerBridge.requestTranslation("Hello", "[\"node1\"]");

            // jsExecutor must still have been called even with no handler
            verify(mockJsExecutor, times(1)).evaluateJavascript(anyString());
        }
    }

    // -------------------------------------------------------------------------
    // Failure path – GeminiTranslator.translate calls onFailure
    // -------------------------------------------------------------------------

    @Test
    public void testRequestTranslation_TranslationFailure_DoesNotInjectDOM() throws Exception {
        try (MockedStatic<HookMain.TranslationCache> mockedCache = mockStatic(HookMain.TranslationCache.class);
             MockedStatic<HookMain.GeminiTranslator> mockedTranslator = mockStatic(HookMain.GeminiTranslator.class)) {

            // Simulate cache miss
            mockedCache.when(() -> HookMain.TranslationCache.get("Hello")).thenReturn(null);

            // Capture callback and immediately invoke onFailure
            mockedTranslator.when(() -> HookMain.GeminiTranslator.translate(
                    anyString(), any(HookMain.TranslationCallback.class)))
                .thenAnswer(invocation -> {
                    HookMain.TranslationCallback cb = invocation.getArgument(1);
                    cb.onFailure("API error");
                    return null;
                });

            bridge.requestTranslation("Hello", "[\"node1\"]");

            // No DOM injection should have occurred
            verify(mockJsExecutor, never()).evaluateJavascript(anyString());
        }
    }

    // -------------------------------------------------------------------------
    // In-flight deduplication
    // -------------------------------------------------------------------------

    @Test
    public void testRequestTranslation_InFlightDedup_SecondCallQueued() throws Exception {
        try (MockedStatic<HookMain.TranslationCache> mockedCache = mockStatic(HookMain.TranslationCache.class);
             MockedStatic<HookMain.GeminiTranslator> mockedTranslator = mockStatic(HookMain.GeminiTranslator.class)) {

            // Simulate cache miss for both calls
            mockedCache.when(() -> HookMain.TranslationCache.get("Hello")).thenReturn(null);
            // Suppress put() to avoid side-effects
            mockedCache.when(() -> HookMain.TranslationCache.put(anyString(), anyString())).thenAnswer(inv -> null);

            // Capture the callback so we can trigger it later
            ArgumentCaptor<HookMain.TranslationCallback> callbackCaptor =
                    ArgumentCaptor.forClass(HookMain.TranslationCallback.class);

            // First call: GeminiTranslator.translate is invoked and holds a callback
            bridge.requestTranslation("Hello", "[\"node1\"]");

            mockedTranslator.verify(() ->
                HookMain.GeminiTranslator.translate(anyString(), callbackCaptor.capture()));

            // Second call with same text but different node: should be batched, NOT fire a new translate()
            bridge.requestTranslation("Hello", "[\"node2\"]");

            // translate() should still have been called exactly once (second call is deduplicated)
            mockedTranslator.verify(() ->
                HookMain.GeminiTranslator.translate(anyString(), any(HookMain.TranslationCallback.class)),
                org.mockito.Mockito.times(1));

            // Now resolve the callback
            HookMain.TranslationCallback capturedCallback = callbackCaptor.getValue();
            capturedCallback.onSuccess("Hola");

            // Both node batches should have been injected – jsExecutor called twice
            verify(mockJsExecutor, times(2)).evaluateJavascript(anyString());
        }
    }

    // -------------------------------------------------------------------------
    // JS content correctness – special characters
    // -------------------------------------------------------------------------

    @Test
    public void testRequestTranslation_SpecialCharsInTranslation_AreProperlyEscaped() throws Exception {
        try (MockedStatic<HookMain.TranslationCache> mockedCache = mockStatic(HookMain.TranslationCache.class)) {
            // Translation contains quotes and backslash – must be safely escaped via JSONObject.quote
            String rawTranslation = "It's a \"test\" with\\backslash";
            mockedCache.when(() -> HookMain.TranslationCache.get("Something")).thenReturn(rawTranslation);

            bridge.requestTranslation("Something", "[\"node42\"]");

            ArgumentCaptor<String> jsCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockJsExecutor).evaluateJavascript(jsCaptor.capture());

            String js = jsCaptor.getValue();
            // The JS must not contain unescaped raw quotes that would break syntax
            // JSONObject.quote wraps in outer quotes and escapes inner ones
            assertTrue("JS should contain escaped translation", js.contains("\\\"test\\\""));
        }
    }

    // -------------------------------------------------------------------------
    // Cache-hit path: second call with same key hits cache (no async needed)
    // -------------------------------------------------------------------------

    @Test
    public void testRequestTranslation_SameCacheHitTwice_CallsJsTwice() throws Exception {
        try (MockedStatic<HookMain.TranslationCache> mockedCache = mockStatic(HookMain.TranslationCache.class)) {
            mockedCache.when(() -> HookMain.TranslationCache.get("Bye")).thenReturn("Adios");

            bridge.requestTranslation("Bye", "[\"n1\"]");
            bridge.requestTranslation("Bye", "[\"n2\"]");

            // Each cache hit triggers its own injection
            verify(mockJsExecutor, times(2)).evaluateJavascript(anyString());
        }
    }
}