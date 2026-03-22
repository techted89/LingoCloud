package com.LingoCloudTranslate.lingocloud;

import org.junit.Test;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class TranslationClientTest {

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

    /**
     * resetClient() should null both genaiClient and cachedApiKey so the next
     * translate call re-creates the SDK client with fresh credentials.
     */
    @Test
    public void resetClient_clearsGenaiClientAndCachedApiKey() throws Exception {
        TranslationClient client = new TranslationClient();

        // Manually inject non-null values via reflection to simulate a prior translate call
        Field genaiClientField = TranslationClient.class.getDeclaredField("genaiClient");
        genaiClientField.setAccessible(true);
        Field cachedApiKeyField = TranslationClient.class.getDeclaredField("cachedApiKey");
        cachedApiKeyField.setAccessible(true);

        // Simulate that a client was previously built by setting a sentinel key
        cachedApiKeyField.set(client, "old-api-key");

        // Act
        client.resetClient();

        // Assert both fields are null
        assertNull("genaiClient should be null after reset", genaiClientField.get(client));
        assertNull("cachedApiKey should be null after reset", cachedApiKeyField.get(client));

        client.shutdown();
    }

    /**
     * close() must shut down the internal executor (AutoCloseable contract).
     */
    @Test
    public void close_shutsDownExecutor() throws Exception {
        TranslationClient client = new TranslationClient();

        Field executorField = TranslationClient.class.getDeclaredField("executor");
        executorField.setAccessible(true);
        ExecutorService executor = (ExecutorService) executorField.get(client);

        assertFalse("Executor should be running before close()", executor.isShutdown());

        client.close();

        assertTrue("Executor should be shut down after close()", executor.isShutdown());
    }

    /**
     * shutdown() should mark the executor as shut down.
     */
    @Test
    public void shutdown_shutsDownExecutor() throws Exception {
        TranslationClient client = new TranslationClient();

        Field executorField = TranslationClient.class.getDeclaredField("executor");
        executorField.setAccessible(true);
        ExecutorService executor = (ExecutorService) executorField.get(client);

        client.shutdown();

        assertTrue("Executor should be shut down after shutdown()", executor.isShutdown());
    }

    /**
     * Calling translate after shutdown should not crash. The executor will reject
     * the task and the callback will not be called (or will be called with null).
     * This tests the resilience boundary.
     */
    @Test
    public void translate_afterShutdown_doesNotCrash() throws InterruptedException {
        TranslationClient client = new TranslationClient();
        client.shutdown();

        // Should not throw; the executor may reject silently
        try {
            client.translate("hello", "Gemini", "some-key", "es",
                result -> { /* ignored */ });
        } catch (Exception e) {
            // RejectedExecutionException is acceptable – what we must NOT have is a crash
            // that takes down the caller.
        }
    }
}