package net.clankerjockey.core.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Threaded, cancellable downloader for the ClankerJockey sidecar bundle.
 *
 * <p>Pure JDK (no Minecraft): streams the bundle zip from a URL to a
 * {@code <target>.part} temp file, reporting progress via a callback, then
 * atomically renames it to {@code target} on success. A cancel or failure
 * leaves the {@code .part} file so a later attempt can be inspected, and never
 * leaves a half-written file at the final {@code target} path.
 *
 * <p>Runs on a daemon thread; the UI polls {@link #state()}/{@link
 * #progress()} and must call {@link #finish()} (or {@link #cancel()}) exactly
 * once on the client thread. Redirects (GitHub release assets 302-redirect) are
 * followed automatically.
 */
public final class BundleDownloader {

    public enum State { IDLE, DOWNLOADING, DONE, FAILED, CANCELLED }

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(5);
    private static final int BUFFER = 8192;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile State state = State.IDLE;
    private volatile long totalBytes = -1;
    private volatile long receivedBytes = 0;
    private volatile String lastError;

    private BundleDownloader() {}

    /**
     * Begin a download on a daemon thread.
     *
     * @param url        the bundle zip URL (may redirect)
     * @param target     final destination for the zip (e.g.
     *                   {@code <gamedir>/clankerjockey-bundle.zip})
     * @param onProgress called on the download thread with (received, total);
     *                   {@code total} is -1 until the Content-Length is known
     */
    public static BundleDownloader start(String url, Path target, BiConsumer<Long, Long> onProgress) {
        BundleDownloader d = new BundleDownloader();
        Thread t = new Thread(() -> d.run(url, target, onProgress), "clankerjockey-bundle-download");
        t.setDaemon(true);
        t.start();
        return d;
    }

    private void run(String url, Path target, BiConsumer<Long, Long> onProgress) {
        cancelled.set(false);
        state = State.DOWNLOADING;
        totalBytes = -1;
        receivedBytes = 0;
        lastError = null;
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try {
            // Remove a stale .part from an earlier interrupted attempt.
            if (Files.exists(part)) {
                Files.delete(part);
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(READ_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                state = State.FAILED;
                lastError = "HTTP " + resp.statusCode();
                return;
            }
            long contentLength = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
            totalBytes = contentLength;
            try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(part)) {
                byte[] buf = new byte[BUFFER];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancelled.get()) {
                        state = State.CANCELLED;
                        return;
                    }
                    out.write(buf, 0, n);
                    receivedBytes += n;
                    onProgress.accept(receivedBytes, totalBytes);
                }
            }
            // Atomically move the completed file into place.
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            state = State.DONE;
        } catch (IOException e) {
            if (cancelled.get()) {
                state = State.CANCELLED;
            } else {
                state = State.FAILED;
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state = State.CANCELLED;
        }
    }

    /** Invoke exactly once on the client thread; returns the terminal state. */
    public State finish() {
        return state;
    }

    /** Stop the download (idempotent). The .part file is left for inspection. */
    public void cancel() {
        cancelled.set(true);
    }

    public State state() {
        return state;
    }

    public boolean isDone() {
        return state == State.DONE;
    }

    public boolean isFailed() {
        return state == State.FAILED;
    }

    public boolean isCancelled() {
        return state == State.CANCELLED;
    }

    public long totalBytes() {
        return totalBytes;
    }

    public long receivedBytes() {
        return receivedBytes;
    }

    public String lastError() {
        return lastError;
    }
}
