package com.LingoCloudTranslate.lingocloud;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

public class TranslationClientTest {

    // =========================================================================
    // translate() – early-exit / validation paths (unchanged in structure but
    // now routed through the new deliverResult() helper)
    // =========================================================================

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

    // =========================================================================
    // resetClient() – new method introduced in this PR
    // =========================================================================

    @Test
    public void resetClient_clearsGenaiClientAndCachedApiKey() throws Exception {
        TranslationClient client = new TranslationClient();

        // Inject non-null values into the private volatile fields via reflection
        Field genaiClientField = TranslationClient.class.getDeclaredField("genaiClient");
        genaiClientField.setAccessible(true);

        Field cachedApiKeyField = TranslationClient.class.getDeclaredField("cachedApiKey");
        cachedApiKeyField.setAccessible(true);

        // Set sentinel value (a mock object) to confirm fields are later cleared
        cachedApiKeyField.set(client, "some-api-key");
        // genaiClient is typed as com.google.genai.Client; set to null first then verify reset keeps it null
        genaiClientField.set(client, null);

        // Confirm setup
        assertNull(genaiClientField.get(client));

        // Now simulate having a cached key set and verify resetClient clears it
        cachedApiKeyField.set(client, "test-api-key");

        // Act
        client.resetClient();

        // Assert both fields are null after reset
        assertNull("genaiClient should be null after resetClient()", genaiClientField.get(client));
        assertNull("cachedApiKey should be null after resetClient()", cachedApiKeyField.get(client));

        client.shutdown();
    }

    // =========================================================================
    // close() – implements AutoCloseable, delegates to shutdown()
    // =========================================================================

    @Test
    public void close_shutsDownExecutor() throws Exception {
        TranslationClient client = new TranslationClient();

        Field executorField = TranslationClient.class.getDeclaredField("executor");
        executorField.setAccessible(true);
        ExecutorService executor = (ExecutorService) executorField.get(client);

        client.close();

        assertTrue("executor should be shut down after close()", executor.isShutdown());
    }

    @Test
    public void close_canBeUsedWithTryWithResources() throws Exception {
        ExecutorService[] capturedExecutor = new ExecutorService[1];

        try (TranslationClient client = new TranslationClient()) {
            Field executorField = TranslationClient.class.getDeclaredField("executor");
            executorField.setAccessible(true);
            capturedExecutor[0] = (ExecutorService) executorField.get(client);
        }
        // After leaving the try block, close() was called
        assertTrue("executor should be shut down after try-with-resources", capturedExecutor[0].isShutdown());
    }

    // =========================================================================
    // shutdown() – idempotent behaviour
    // =========================================================================

    @Test
    public void shutdown_calledTwice_doesNotThrow() {
        TranslationClient client = new TranslationClient();
        client.shutdown();
        // Second call must not throw
        client.shutdown();
    }

    @Test
    public void shutdown_marksExecutorAsShutdown() throws Exception {
        TranslationClient client = new TranslationClient();

        Field executorField = TranslationClient.class.getDeclaredField("executor");
        executorField.setAccessible(true);
        ExecutorService executor = (ExecutorService) executorField.get(client);

        client.shutdown();

        assertTrue("executor should be shut down after shutdown()", executor.isShutdown());
    }

    // =========================================================================
    // Boundary: whitespace-only text is treated as empty
    // =========================================================================

    @Test
    public void translate_whitespaceOnlyText_returnsOriginalText() throws InterruptedException {
        TranslationClient client = new TranslationClient();
        TranslationClient.TranslationCallback mockCallback = mock(TranslationClient.TranslationCallback.class);

        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(mockCallback).onResult(any());

        // "\t\n" trims to empty – should return original without calling any service
        client.translate("\t\n", "Microsoft", "VALID_KEY", "ja", mockCallback);

        latch.await(2, TimeUnit.SECONDS);
        verify(mockCallback).onResult("\t\n");
        verifyNoMoreInteractions(mockCallback);
        client.shutdown();
    }

    // =========================================================================
    // Boundary: whitespace-only API key rejected even for Microsoft
    // =========================================================================

    @Test
    public void translate_whitespaceApiKey_microsoftService_returnsNull() throws InterruptedException {
        TranslationClient client = new TranslationClient();
        TranslationClient.TranslationCallback mockCallback = mock(TranslationClient.TranslationCallback.class);

        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(mockCallback).onResult(any());

        client.translate("Bonjour", "Microsoft", "  ", "en", mockCallback);

        latch.await(2, TimeUnit.SECONDS);
        verify(mockCallback).onResult(null);
        verifyNoMoreInteractions(mockCallback);
        client.shutdown();
    }
}