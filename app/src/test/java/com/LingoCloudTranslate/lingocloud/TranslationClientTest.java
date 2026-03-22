package com.LingoCloudTranslate.lingocloud;

import org.junit.Test;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TranslationClientTest {

    // --- original tests ---

    @Test
    public void translate_emptyApiKey_returnsNullAndDoesNotCallService() throws InterruptedException {
        // Arrange
        TranslationClient client = new TranslationClient();
        TranslationClient.TranslationCallback mockCallback = mock(TranslationClient.TranslationCallback.class);

        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(mockCallback).onResult(any());

        // Act
        client.translate("Hello World", "Gemini", "   ", "es", mockCallback);

        // Assert
        latch.await(2, TimeUnit.SECONDS);
        verify(mockCallback).onResult(null);
        verifyNoMoreInteractions(mockCallback);
        client.shutdown();
    }

    @Test
    public void translate_emptyText_returnsEmptyText() throws InterruptedException {
        // Arrange
        TranslationClient client = new TranslationClient();
        TranslationClient.TranslationCallback mockCallback = mock(TranslationClient.TranslationCallback.class);

        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(mockCallback).onResult(any());

        // Act
        client.translate("   ", "Gemini", "VALID_KEY", "es", mockCallback);

        // Assert
        latch.await(2, TimeUnit.SECONDS);
        verify(mockCallback).onResult("   ");
        verifyNoMoreInteractions(mockCallback);
        client.shutdown();
    }

    @Test
    public void translate_unknownService_returnsNull() throws InterruptedException {
        // Arrange
        TranslationClient client = new TranslationClient();
        TranslationClient.TranslationCallback mockCallback = mock(TranslationClient.TranslationCallback.class);

        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(mockCallback).onResult(any());

        // Act
        client.translate("Hello", "UnknownProvider", "VALID_KEY", "es", mockCallback);

        // Assert
        latch.await(2, TimeUnit.SECONDS);
        verify(mockCallback).onResult(null);
        verifyNoMoreInteractions(mockCallback);
        client.shutdown();
    }

    // --- additional tests ---

    /**
     * resetClient() must clear the cached genaiClient and cachedApiKey fields so
     * that the next Gemini call builds a fresh SDK client.
     */
    @Test
    public void resetClient_clearsInternalClientAndApiKey() throws Exception {
        TranslationClient client = new TranslationClient();

        // Inject synthetic values into the private fields via reflection
        Field genaiClientField = TranslationClient.class.getDeclaredField("genaiClient");
        genaiClientField.setAccessible(true);
        Field cachedApiKeyField = TranslationClient.class.getDeclaredField("cachedApiKey");
        cachedApiKeyField.setAccessible(true);

        // Simulate a previously-initialized state
        cachedApiKeyField.set(client, "old-api-key");

        // Act
        client.resetClient();

        // Both fields must be null after reset
        assertNull("genaiClient must be null after reset", genaiClientField.get(client));
        assertNull("cachedApiKey must be null after reset", cachedApiKeyField.get(client));

        client.shutdown();
    }

    /**
     * resetClient() is idempotent – calling it multiple times must not throw.
     */
    @Test
    public void resetClient_calledMultipleTimes_doesNotThrow() {
        TranslationClient client = new TranslationClient();
        client.resetClient();
        client.resetClient();
        client.shutdown();
    }

    /**
     * close() must implement AutoCloseable so it can be used in try-with-resources
     * without throwing an exception.
     */
    @Test
    public void close_implementsAutoCloseable_doesNotThrow() throws Exception {
        try (TranslationClient client = new TranslationClient()) {
            // No-op body: close() is called implicitly and must not throw
        }
    }

    /**
     * Microsoft service path: empty API key must still return null via the early-exit
     * guard, the same as for the Gemini path.
     */
    @Test
    public void translate_microsoftService_emptyApiKey_returnsNull() throws InterruptedException {
        TranslationClient client = new TranslationClient();
        TranslationClient.TranslationCallback mockCallback = mock(TranslationClient.TranslationCallback.class);

        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(mockCallback).onResult(any());

        client.translate("Bonjour", "Microsoft", "", "en", mockCallback);

        latch.await(2, TimeUnit.SECONDS);
        verify(mockCallback).onResult(null);
        client.shutdown();
    }

    /**
     * Microsoft service path: empty text must be returned as-is, bypassing the API
     * call.
     */
    @Test
    public void translate_microsoftService_emptyText_returnsTextAsIs() throws InterruptedException {
        TranslationClient client = new TranslationClient();
        TranslationClient.TranslationCallback mockCallback = mock(TranslationClient.TranslationCallback.class);

        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(mockCallback).onResult(any());

        client.translate("  ", "Microsoft", "VALID_KEY", "en", mockCallback);

        latch.await(2, TimeUnit.SECONDS);
        verify(mockCallback).onResult("  ");
        client.shutdown();
    }

    /**
     * Regression: after shutdown(), calling translate() with an unknown service must
     * still deliver null through the callback synchronously (the early default-case
     * branch runs before touching the executor).
     */
    @Test
    public void translate_unknownService_afterShutdown_stillDeliversNull() throws InterruptedException {
        TranslationClient client = new TranslationClient();
        client.shutdown(); // shut down first

        TranslationClient.TranslationCallback mockCallback = mock(TranslationClient.TranslationCallback.class);
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(mockCallback).onResult(any());

        client.translate("Hi", "NonExistent", "KEY", "fr", mockCallback);

        latch.await(2, TimeUnit.SECONDS);
        verify(mockCallback).onResult(null);
    }
}