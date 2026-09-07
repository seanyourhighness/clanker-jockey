package net.clankerjockey.core.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves BundleBootstrap actually locates and extracts a bundle zip, and that
 * it degrades safely (returns null) when nothing is present. Uses a small
 * synthetic bundle (dummy files) so the test is fast and hermetic.
 */
class BundleBootstrapTest {

    /** Write a tiny synthetic bundle zip rooted at clankerjockey/ with the required files. */
    private static Path makeFakeBundleZip(Path dir) throws IOException {
        Path zip = dir.resolve("clankerjockey-bundle.zip");
        String[] entries = {
                "clankerjockey/bin/llama-server.exe",
                "clankerjockey/models/littlelamb-0.3b-toolcalling-q8_0.gguf",
                "clankerjockey/stt/bin/whisper-server.exe",
                "clankerjockey/stt/models/ggml-small.en.bin",
                "clankerjockey/tts/bin/pocket-tts.exe",
                "clankerjockey/tts/models/tokenizer.model",
                "clankerjockey/tts/voices/jo.wav",
        };
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String e : entries) {
                zos.putNextEntry(new ZipEntry(e));
                zos.write(("dummy-" + e).getBytes());
                zos.closeEntry();
            }
        }
        return zip;
    }

    @Test
    void extractsZipDroppedNextToGameDir(@TempDir Path gameDir) throws IOException {
        makeFakeBundleZip(gameDir);
        List<String> log = new ArrayList<>();
        Path out = BundleBootstrap.ensureBundle(gameDir, log::add);
        assertNotNull(out, "expected a bundle dir to be returned");
        assertEquals(gameDir.resolve("clankerjockey"), out);
        assertTrue(Files.isRegularFile(out.resolve("bin/llama-server.exe")));
        assertTrue(Files.isRegularFile(out.resolve("models/littlelamb-0.3b-toolcalling-q8_0.gguf")));
        assertTrue(Files.isRegularFile(out.resolve("stt/bin/whisper-server.exe")));
        assertTrue(Files.isRegularFile(out.resolve("tts/voices/jo.wav")));
    }

    @Test
    void returnsNullWhenNothingPresent(@TempDir Path gameDir) {
        Path out = BundleBootstrap.ensureBundle(gameDir, s -> {});
        assertNull(out, "expected null when no bundle is present");
    }

    @Test
    void alreadyExtractedBundleIsUsedWithoutReExtract(@TempDir Path gameDir) throws IOException {
        // Pre-create a usable bundle dir (no zip).
        Path models = gameDir.resolve("clankerjockey/models");
        Files.createDirectories(models);
        Files.createDirectories(gameDir.resolve("clankerjockey/bin"));
        Files.writeString(gameDir.resolve("clankerjockey/bin/llama-server"), "x");
        Files.writeString(models.resolve("littlelamb-0.3b-toolcalling-q8_0.gguf"), "x");
        Path out = BundleBootstrap.ensureBundle(gameDir, s -> {});
        assertNotNull(out);
        assertEquals(gameDir.resolve("clankerjockey"), out);
    }

    @Test
    void zipSlipEntryIsSkippedNotFollowed(@TempDir Path gameDir) throws IOException {
        Path zip = gameDir.resolve("clankerjockey-bundle.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("clankerjockey/bin/llama-server"));
            zos.write("ok".getBytes());
            zos.closeEntry();
            // malicious entry trying to escape the game dir
            zos.putNextEntry(new ZipEntry("clankerjockey/../evil.txt"));
            zos.write("pwned".getBytes());
            zos.closeEntry();
        }
        Path out = BundleBootstrap.ensureBundle(gameDir, s -> {});
        assertNotNull(out);
        assertTrue(Files.isRegularFile(out.resolve("bin/llama-server")));
        assertFalse(Files.exists(gameDir.resolve("evil.txt")), "zip-slip entry must not be written");
    }

    @Test
    void partialBundleStillReturnsDir(@TempDir Path gameDir) throws IOException {
        // A bundle with the TTS half missing but LLM present: should still return
        // the dir (each sidecar degrades independently), not null.
        Path zip = gameDir.resolve("clankerjockey-bundle.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("clankerjockey/bin/llama-server"));
            zos.write("x".getBytes());
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("clankerjockey/models/littlelamb-0.3b-toolcalling-q8_0.gguf"));
            zos.write("x".getBytes());
            zos.closeEntry();
            // no stt/ or tts/ at all
        }
        Path out = BundleBootstrap.ensureBundle(gameDir, s -> {});
        assertNotNull(out, "partial bundle should still resolve to the bundle dir");
    }
}
