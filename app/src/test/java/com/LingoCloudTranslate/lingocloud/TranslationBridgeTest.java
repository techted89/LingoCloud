package com.LingoCloudTranslate.lingocloud;

import android.os.Handler;
import android.webkit.WebView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class TranslationBridgeTest {

    private WebView mockWebView;
    private TranslationBridge bridge;
    private TranslationBridge.JavascriptExecutor mockJsExecutor;
    private Handler mockHandler;

    @Before
    public void setup() throws Exception {
        mockWebView = mock(WebView.class);
        mockJsExecutor = mock(TranslationBridge.JavascriptExecutor.class);
        mockHandler = mock(Handler.class);

        // Force the Handler to execute Runnables immediately and synchronously
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return true;
        }).when(mockHandler).post(any(Runnable.class));

        bridge = new TranslationBridge(mockWebView, mockJsExecutor, mockHandler);
        // Clear translation cache state before each test
        HookMain.TranslationCache.clear();
    }

    @After
    public void tearDown() {
        HookMain.TranslationCache.clear();
    }

    @Test
    public void testRequestTranslation_EmptyOrNullInput() {
        bridge.requestTranslation(null, "[\"node1\"]");
        verify(mockHandler, never()).post(any());

        bridge.requestTranslation("", "[\"node2\"]");
        verify(mockHandler, never()).post(any());

        bridge.requestTranslation("   ", "[\"node3\"]");
        verify(mockHandler, never()).post(any());
    }

    @Test
    public void testRequestTranslation_PrimedCache() throws Exception {
        try (MockedStatic<HookMain.TranslationCache> mockedCache = mockStatic(HookMain.TranslationCache.class)) {
            mockedCache.when(() -> HookMain.TranslationCache.get("Hello")).thenReturn("Hola");

            bridge.requestTranslation("Hello", "[\"node1\"]");

            ArgumentCaptor<String> jsCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockJsExecutor).evaluateJavascript(jsCaptor.capture());

            String js = jsCaptor.getValue();

            assertTrue(js.contains("JSON.parse"));
            assertTrue(js.contains("document.getElementById"));
            assertTrue(js.contains("innerText = text"));
            assertTrue(js.contains("\"Hola\""));
            assertTrue(js.contains("\"[\\\"node1\\\"]\""));
        }
    }

    /**
     * When the same text is requested twice concurrently (before the first API call completes),
     * the second call should be batched into the in-flight list rather than triggering
     * a second API request.
     */
    @Test
    public void testRequestTranslation_InFlightDeduplication() throws Exception {
        try (MockedStatic<HookMain.TranslationCache> mockedCache = mockStatic(HookMain.TranslationCache.class);
             MockedStatic<HookMain.GeminiTranslator> mockedTranslator = mockStatic(HookMain.GeminiTranslator.class)) {

            // Cache miss for both calls
            mockedCache.when(() -> HookMain.TranslationCache.get("Hello")).thenReturn(null);

            // Capture the callback passed to GeminiTranslator.translate
            AtomicReference<HookMain.TranslationCallback> capturedCallback = new AtomicReference<>();
            mockedTranslator.when(() -> HookMain.GeminiTranslator.translate(eq("Hello"), any(HookMain.TranslationCallback.class)))
                .thenAnswer(invocation -> {
                    capturedCallback.set(invocation.getArgument(1));
                    return null;
                });

            // First call - should trigger API request
            bridge.requestTranslation("Hello", "[\"node1\"]");
            // Second call for same text while first is in-flight
            bridge.requestTranslation("Hello", "[\"node2\"]");

            // GeminiTranslator.translate should only be called ONCE despite two requests
            mockedTranslator.verify(() -> HookMain.GeminiTranslator.translate(eq("Hello"), any()), times(1));

            // Now simulate a successful API response
            mockedCache.when(() -> HookMain.TranslationCache.put("Hello", "Hola")).thenAnswer(i -> null);
            capturedCallback.get().onSuccess("Hola");

            // Both nodes should have received a JS update
            ArgumentCaptor<String> jsCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockJsExecutor, times(2)).evaluateJavascript(jsCaptor.capture());
            List<String> jsCalls = jsCaptor.getAllValues();
            // Both injections should contain the translated text
            assertTrue(jsCalls.get(0).contains("\"Hola\""));
            assertTrue(jsCalls.get(1).contains("\"Hola\""));
            // Each injection should contain its respective node ID
            boolean hasNode1 = jsCalls.get(0).contains("node1") || jsCalls.get(1).contains("node1");
            boolean hasNode2 = jsCalls.get(0).contains("node2") || jsCalls.get(1).contains("node2");
            assertTrue("node1 should appear in JS updates", hasNode1);
            assertTrue("node2 should appear in JS updates", hasNode2);
        }
    }

    /**
     * When translation fails, the in-flight entry is removed so future requests
     * can retry.
     */
    @Test
    public void testRequestTranslation_OnFailure_RemovesInFlightEntry() throws Exception {
        try (MockedStatic<HookMain.TranslationCache> mockedCache = mockStatic(HookMain.TranslationCache.class);
             MockedStatic<HookMain.GeminiTranslator> mockedTranslator = mockStatic(HookMain.GeminiTranslator.class)) {

            mockedCache.when(() -> HookMain.TranslationCache.get(anyString())).thenReturn(null);

            AtomicReference<HookMain.TranslationCallback> capturedCallback = new AtomicReference<>();
            mockedTranslator.when(() -> HookMain.GeminiTranslator.translate(eq("Hello"), any(HookMain.TranslationCallback.class)))
                .thenAnswer(invocation -> {
                    capturedCallback.set(invocation.getArgument(1));
                    return null;
                });

            bridge.requestTranslation("Hello", "[\"node1\"]");

            // Simulate failure
            capturedCallback.get().onFailure("Network error");

            // No JS should have been injected
            verify(mockJsExecutor, never()).evaluateJavascript(anyString());

            // The in-flight map should now be empty so a retry should trigger a new API call
            // Reset the capture and make a new request
            AtomicReference<HookMain.TranslationCallback> retryCallback = new AtomicReference<>();
            mockedTranslator.when(() -> HookMain.GeminiTranslator.translate(eq("Hello"), any(HookMain.TranslationCallback.class)))
                .thenAnswer(invocation -> {
                    retryCallback.set(invocation.getArgument(1));
                    return null;
                });

            bridge.requestTranslation("Hello", "[\"node3\"]");
            mockedTranslator.verify(() -> HookMain.GeminiTranslator.translate(eq("Hello"), any()), times(2));
        }
    }

    /**
     * When mainHandler is null, injectTranslationBackToDOM should run the Runnable directly
     * (synchronous execution path).
     */
    @Test
    public void testInjectTranslation_NullHandler_RunsDirectly() throws Exception {
        // Build a bridge with no handler
        TranslationBridge nullHandlerBridge = new TranslationBridge(mockWebView, mockJsExecutor, null);

        try (MockedStatic<HookMain.TranslationCache> mockedCache = mockStatic(HookMain.TranslationCache.class)) {
            mockedCache.when(() -> HookMain.TranslationCache.get("Bye")).thenReturn("Adios");

            nullHandlerBridge.requestTranslation("Bye", "[\"nodeX\"]");

            // JS should still be evaluated even without a Handler
            ArgumentCaptor<String> jsCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockJsExecutor).evaluateJavascript(jsCaptor.capture());
            assertTrue(jsCaptor.getValue().contains("\"Adios\""));
        }
    }

    /**
     * The generated JS must properly quote special characters in the translated text
     * to avoid injection / parse errors.
     */
    @Test
    public void testInjectTranslation_SpecialCharsInText_AreProperlyQuoted() throws Exception {
        try (MockedStatic<HookMain.TranslationCache> mockedCache = mockStatic(HookMain.TranslationCache.class)) {
            // Translation contains double quotes and backslash
            mockedCache.when(() -> HookMain.TranslationCache.get("hi")).thenReturn("say \"hello\"");

            bridge.requestTranslation("hi", "[\"nodeQ\"]");

            ArgumentCaptor<String> jsCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockJsExecutor).evaluateJavascript(jsCaptor.capture());
            // JSONObject.quote should have escaped the inner quotes
            String js = jsCaptor.getValue();
            // The JS string should not contain unescaped raw double quotes inside the value
            assertTrue("JS should contain escaped quotes", js.contains("\\\"hello\\\""));
        }
    }
}