package com.LingoCloudTranslate.lingocloud;

import android.os.Handler;
import android.webkit.WebView;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import static org.junit.Assert.assertEquals;
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
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
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

        // Ensure clean cache state before each test
        HookMain.TranslationCache.clear();
    }

    // --- original tests ---

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

    // --- additional tests ---

    /**
     * Verifies that GeminiTranslator.translate is only called once when a second
     * requestTranslation arrives for the same text while the first is in-flight,
     * and that both DOM node sets are injected when the translation succeeds.
     */
    @Test
    public void testRequestTranslation_InFlight_DeduplicatesRequests() {
        try (MockedStatic<HookMain.TranslationCache> mockedCache = mockStatic(HookMain.TranslationCache.class);
             MockedStatic<HookMain.GeminiTranslator> mockedTranslator = mockStatic(HookMain.GeminiTranslator.class)) {

            // Cache miss for "Hello" both times
            mockedCache.when(() -> HookMain.TranslationCache.get("Hello")).thenReturn(null);

            // Capture the callback so we can drive it manually
            AtomicReference<HookMain.TranslationCallback> capturedCallback = new AtomicReference<>();
            mockedTranslator.when(() -> HookMain.GeminiTranslator.translate(
                    eq("Hello"), any(HookMain.TranslationCallback.class)))
                .thenAnswer(invocation -> {
                    capturedCallback.set(invocation.getArgument(1));
                    return null;
                });

            // First request — triggers translation
            bridge.requestTranslation("Hello", "[\"node1\"]");
            // Second request for the same text while still in-flight
            bridge.requestTranslation("Hello", "[\"node2\"]");

            // translate() must have been called exactly once
            mockedTranslator.verify(
                () -> HookMain.GeminiTranslator.translate(eq("Hello"), any()),
                times(1)
            );

            // Resolve the translation
            mockedCache.when(() -> HookMain.TranslationCache.put(anyString(), anyString()))
                .thenAnswer(inv -> null);
            capturedCallback.get().onSuccess("Hola");

            // Both node sets should have been injected via the JS executor
            ArgumentCaptor<String> jsCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockJsExecutor, times(2)).evaluateJavascript(jsCaptor.capture());

            boolean foundNode1 = jsCaptor.getAllValues().stream()
                .anyMatch(js -> js.contains("\"[\\\"node1\\\"]\""));
            boolean foundNode2 = jsCaptor.getAllValues().stream()
                .anyMatch(js -> js.contains("\"[\\\"node2\\\"]\""));

            assertTrue("node1 should have been injected", foundNode1);
            assertTrue("node2 should have been injected", foundNode2);
        }
    }

    /**
     * When GeminiTranslator reports a failure the in-flight entry must be cleaned
     * up and no DOM injection must occur.
     */
    @Test
    public void testRequestTranslation_TranslationFailure_CleansUpInFlight() {
        try (MockedStatic<HookMain.TranslationCache> mockedCache = mockStatic(HookMain.TranslationCache.class);
             MockedStatic<HookMain.GeminiTranslator> mockedTranslator = mockStatic(HookMain.GeminiTranslator.class)) {

            mockedCache.when(() -> HookMain.TranslationCache.get("Hello")).thenReturn(null);

            AtomicReference<HookMain.TranslationCallback> capturedCallback = new AtomicReference<>();
            mockedTranslator.when(() -> HookMain.GeminiTranslator.translate(
                    eq("Hello"), any(HookMain.TranslationCallback.class)))
                .thenAnswer(invocation -> {
                    capturedCallback.set(invocation.getArgument(1));
                    return null;
                });

            bridge.requestTranslation("Hello", "[\"node1\"]");
            capturedCallback.get().onFailure("API error");

            // No JS must have been evaluated
            verify(mockJsExecutor, never()).evaluateJavascript(anyString());

            // The in-flight map must have been cleared: a fresh request should trigger translate again
            bridge.requestTranslation("Hello", "[\"node2\"]");
            mockedTranslator.verify(
                () -> HookMain.GeminiTranslator.translate(eq("Hello"), any()),
                times(2)
            );
        }
    }

    /**
     * When mainHandler is null the DOM injection must still run by invoking the
     * Runnable directly (no post()).
     */
    @Test
    public void testInjectTranslationBackToDOM_NullHandler_RunsRunnableDirectly() {
        TranslationBridge bridgeNoHandler = new TranslationBridge(mockWebView, mockJsExecutor, null);

        try (MockedStatic<HookMain.TranslationCache> mockedCache = mockStatic(HookMain.TranslationCache.class)) {
            mockedCache.when(() -> HookMain.TranslationCache.get("Bye")).thenReturn("Adios");

            bridgeNoHandler.requestTranslation("Bye", "[\"nodeX\"]");

            ArgumentCaptor<String> jsCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockJsExecutor).evaluateJavascript(jsCaptor.capture());
            assertTrue(jsCaptor.getValue().contains("\"Adios\""));
        }
    }

    /**
     * Multiple different texts must each trigger their own independent translate call.
     */
    @Test
    public void testRequestTranslation_DifferentTexts_EachTriggerTranslate() {
        try (MockedStatic<HookMain.TranslationCache> mockedCache = mockStatic(HookMain.TranslationCache.class);
             MockedStatic<HookMain.GeminiTranslator> mockedTranslator = mockStatic(HookMain.GeminiTranslator.class)) {

            mockedCache.when(() -> HookMain.TranslationCache.get(anyString())).thenReturn(null);
            mockedTranslator.when(() -> HookMain.GeminiTranslator.translate(anyString(), any()))
                .thenAnswer(inv -> null);

            bridge.requestTranslation("Hello", "[\"node1\"]");
            bridge.requestTranslation("World", "[\"node2\"]");

            mockedTranslator.verify(
                () -> HookMain.GeminiTranslator.translate(eq("Hello"), any()), times(1));
            mockedTranslator.verify(
                () -> HookMain.GeminiTranslator.translate(eq("World"), any()), times(1));
        }
    }

    /**
     * Regression: a cached result injected back to the DOM must include the
     * translated text in the JS payload, not the original text.
     */
    @Test
    public void testRequestTranslation_CachedResult_InjectsTranslatedText() {
        try (MockedStatic<HookMain.TranslationCache> mockedCache = mockStatic(HookMain.TranslationCache.class)) {
            mockedCache.when(() -> HookMain.TranslationCache.get("Good morning"))
                .thenReturn("Buenos dias");

            bridge.requestTranslation("Good morning", "[\"n1\"]");

            ArgumentCaptor<String> jsCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockJsExecutor).evaluateJavascript(jsCaptor.capture());

            String js = jsCaptor.getValue();
            assertTrue("Translated text must appear in JS payload", js.contains("\"Buenos dias\""));
            assertTrue("Node ID must appear in JS payload", js.contains("\"[\\\"n1\\\"]\""));
        }
    }
}