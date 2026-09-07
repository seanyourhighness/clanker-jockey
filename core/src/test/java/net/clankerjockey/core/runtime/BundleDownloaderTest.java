package net.clankerjockey.core.runtime;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link BundleDownloader} against a local HTTP server: successful
 * download (with progress + atomic rename), and cancel (leaves no final file).
 */
class BundleDownloaderTest {

    @TempDir
    Path tmp;

    private HttpServer startServer(Path serveFile, int delayMs) throws Exception {
        byte[] body = Files.readAllBytes(serveFile);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/bundle.zip", exchange -> {
            if (delayMs > 0) {
                try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}
            }
            exchange.getResponseHeaders().add("Content-Length", String.valueOf(body.length));
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    @Test
    void downloadsAndRenamesToFinal(@TempDir Path dir) throws Exception {
        Path payload = dir.resolve("payload.bin");
        byte[] data = "the-clankerjockey-bundle-bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(payload, data);

        HttpServer server = startServer(payload, 0);
        try {
            String url = "http://localhost:" + server.getAddress().getPort() + "/bundle.zip";
            Path target = dir.resolve("bundle.zip");
            AtomicLong lastTotal = new AtomicLong(-1);
            CountDownLatch progress = new CountDownLatch(1);

            BundleDownloader d = BundleDownloader.start(url, target, (recv, total) -> {
                lastTotal.set(total);
                if (total > 0) progress.countDown();
            });

            // Wait for a terminal state (the thread may not have started yet,
            // so IDLE/DOWNLOADING both mean "keep waiting").
            long deadline = System.currentTimeMillis() + 10_000;
            BundleDownloader.State s;
            while (System.currentTimeMillis() < deadline
                    && (s = d.state()) != BundleDownloader.State.DONE
                    && s != BundleDownloader.State.FAILED
                    && s != BundleDownloader.State.CANCELLED) {
                Thread.sleep(10);
            }
            s = d.finish();

            assertTrue(progress.await(5, TimeUnit.SECONDS), "progress callback fired");
            assertEquals(BundleDownloader.State.DONE, s);
            assertTrue(d.isDone());
            assertEquals(data.length, d.receivedBytes());
            assertEquals(data.length, lastTotal.get(), "Content-Length reported");
            // Atomic rename: final file present, .part gone, bytes match.
            assertTrue(Files.isRegularFile(target));
            assertFalse(Files.exists(dir.resolve("bundle.zip.part")));
            assertArrayEquals(data, Files.readAllBytes(target));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cancelLeavesNoFinalFile(@TempDir Path dir) throws Exception {
        Path payload = dir.resolve("payload.bin");
        byte[] data = new byte[256 * 1024];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        Files.write(payload, data);

        // Slow server so the download is still in flight when we cancel.
        HttpServer server = startServer(payload, 2000);
        try {
            String url = "http://localhost:" + server.getAddress().getPort() + "/bundle.zip";
            Path target = dir.resolve("bundle.zip");
            BundleDownloader d = BundleDownloader.start(url, target, (r, t) -> {});

            // Let it start, then cancel.
            Thread.sleep(150);
            d.cancel();

            long deadline = System.currentTimeMillis() + 5_000;
            BundleDownloader.State s;
            while (System.currentTimeMillis() < deadline
                    && (s = d.state()) != BundleDownloader.State.CANCELLED
                    && s != BundleDownloader.State.FAILED
                    && s != BundleDownloader.State.DONE) {
                Thread.sleep(10);
            }
            s = d.finish();
            assertTrue(d.isCancelled(), "state should be CANCELLED, was " + s);
            // No final file should have been produced.
            assertFalse(Files.isRegularFile(target), "final file must not exist after cancel");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void bundleUrlDefaultIsGitHubRelease() {
        String url = BundleBootstrap.bundleUrl();
        assertNotNull(url);
        assertTrue(url.contains("github.com/seanyourhighness/clanker-jockey"), url);
        assertTrue(url.endsWith(".zip"), url);
    }
}
