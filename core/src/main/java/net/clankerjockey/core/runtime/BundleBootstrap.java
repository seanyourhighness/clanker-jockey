package net.clankerjockey.core.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Date;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Locates (and, if needed, extracts) the ClankerJockey sidecar bundle under
 * {@code <game dir>/clankerjockey/}.
 *
 * <p>Pure JDK: no Minecraft, no logging dependency, so it lives in the plain-Java
 * {@code core} module and is shared by both the Forge and Fabric entrypoints.
 * Logging is delegated to a caller-supplied {@link Consumer} so each loader can
 * route to its own logger.
 *
 * <p>Resolution order (first usable hit wins):
 * <ol>
 *   <li>An already-extracted, usable bundle at {@code <gamedir>/clankerjockey/}.</li>
 *   <li>An explicit path from the {@code CLANKERJOCKEY_BUNDLE} env var (a zip, or an
 *       already-extracted {@code clankerjockey} directory).</li>
 *   <li>A bundle zip dropped next to the game dir (a few known names), extracted into
 *       the game dir on first launch.</li>
 * </ol>
 *
 * <p>Everything is degrade-safe: a missing or incomplete bundle returns {@code null}
 * (or leaves the existing partial bundle in place) rather than throwing, so the caller
 * logs a warning and runs with that feature disabled.
 */
public final class BundleBootstrap {

    /** Directory name the mod expects the bundle to live in, under the game dir. */
    public static final String BUNDLE_DIR_NAME = "clankerjockey";

    /**
     * Canonical download location for the Windows sidecar bundle, published as a
     * GitHub Release asset (fits under the 2 GB per-asset limit). Overridable via
     * the {@code CLANKERJOCKEY_BUNDLE_URL} env var for mirrors/CDN.
     */
    public static final String BUNDLE_URL =
            "https://github.com/seanyourhighness/clanker-jockey/releases/download/v0.1.0/clankerjockey-win-bundle.zip";

    /** File name the bundle zip is saved as when downloaded into the game dir. */
    public static final String BUNDLE_ZIP_NAME = "clankerjockey-win-bundle.zip";

    /** Marker written after a successful extraction so we don't re-unpack every launch. */
    private static final String MARKER = ".clankerjockey-bundle";

    /** Zip file names the mod recognises when dropped next to the game dir. */
    private static final String[] ZIP_NAMES = {
            "clankerjockey-bundle.zip",
            "clankerjockey-win-bundle.zip",
            "clankerjockey.zip",
    };

    private BundleBootstrap() {}

    /**
     * The URL to download the bundle from: {@link #BUNDLE_URL} unless the
     * {@code CLANKERJOCKEY_BUNDLE_URL} env var overrides it (for mirrors/CDN).
     */
    public static String bundleUrl() {
        String env = System.getenv("CLANKERJOCKEY_BUNDLE_URL");
        return (env != null && !env.isBlank()) ? env.trim() : BUNDLE_URL;
    }

    /** The LLM model file whose presence marks the "brain" half of the bundle. */
    public static Path modelFile(Path bundleDir) {
        return bundleDir.resolve("models/littlelamb-0.3b-toolcalling-q8_0.gguf");
    }

    /**
     * Ensure the bundle is available under {@code gameDir}. Returns the
     * {@code <gamedir>/clankerjockey} directory if a usable bundle is present
     * (already there, or extracted now), or {@code null} if nothing usable was
     * found. Never throws for the "not present" case.
     */
    public static Path ensureBundle(Path gameDir, Consumer<String> log) {
        Consumer<String> l = log == null ? s -> {} : log;
        Path base = gameDir.toAbsolutePath().normalize();
        Path bundleDir = base.resolve(BUNDLE_DIR_NAME);

        // 1) already extracted and usable -> use it, no re-extract.
        if (isUsable(bundleDir) || hasMarker(bundleDir)) {
            l.accept("bundle present at " + bundleDir);
            return bundleDir;
        }

        // 2) explicit env override (a zip or an already-extracted dir).
        String env = System.getenv("CLANKERJOCKEY_BUNDLE");
        if (env != null && !env.isBlank()) {
            Path p = Path.of(env).toAbsolutePath().normalize();
            if (Files.isDirectory(p) && isUsable(p)) {
                l.accept("using bundle from CLANKERJOCKEY_BUNDLE dir " + p);
                return p;
            }
            if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".zip")) {
                Path out = extractZip(p, base, l);
                if (out != null) {
                    return out;
                }
            }
        }

        // 3) a zip dropped next to the game dir.
        for (String name : ZIP_NAMES) {
            Path zip = base.resolve(name);
            if (Files.isRegularFile(zip)) {
                l.accept("extracting bundle from " + zip);
                Path out = extractZip(zip, base, l);
                if (out != null) {
                    return out;
                }
            }
        }

        l.accept("no ClankerJockey bundle found in " + base
                + " - drop clankerjockey-bundle.zip here, or extract into " + BUNDLE_DIR_NAME + "/");
        return null;
    }

    /** A bundle is usable when the LLM model and its server binary are both present. */
    private static boolean isUsable(Path bundleDir) {
        boolean model = Files.isRegularFile(modelFile(bundleDir));
        boolean bin = Files.isRegularFile(bundleDir.resolve("bin/llama-server"))
                || Files.isRegularFile(bundleDir.resolve("bin/llama-server.exe"));
        return model && bin;
    }

    private static boolean hasMarker(Path bundleDir) {
        return Files.isRegularFile(bundleDir.resolve(MARKER));
    }

    private static void writeMarker(Path bundleDir) {
        try {
            Files.writeString(bundleDir.resolve(MARKER),
                    "ClankerJockey sidecar bundle marker\nextracted: " + new Date() + "\n");
        } catch (IOException ignored) {
            // marker is best-effort; a failed write just means we re-check next launch
        }
    }

    /**
     * Extract a bundle zip into {@code gameDir}. The zip's entries are expected to be
     * rooted at {@code clankerjockey/...} so extraction lands at
     * {@code <gamedir>/clankerjockey/}. Returns the bundle dir on success (even if
     * partially complete, so each sidecar can degrade independently), or {@code null}
     * if the zip could not be read.
     */
    private static Path extractZip(Path zip, Path gameDir, Consumer<String> log) {
        Path bundleDir = gameDir.resolve(BUNDLE_DIR_NAME);
        int count = 0;
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                Path target = gameDir.resolve(e.getName()).normalize();
                // zip-slip guard: the resolved target must stay under the bundle dir
                // (not merely the game dir), so a "clankerjockey/../evil.txt" entry
                // that normalizes back up to the game dir is still rejected.
                if (!target.startsWith(bundleDir)) {
                    log.accept("skipping unsafe zip entry " + e.getName());
                    continue;
                }
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (InputStream in = zf.getInputStream(e)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    count++;
                }
            }
            log.accept("extracted " + count + " files into " + bundleDir);
        } catch (IOException ex) {
            log.accept("failed to extract " + zip + ": " + ex.getMessage());
            return null;
        }
        // Marker on any successful extract (even a partial one) so a large zip is
        // not re-unpacked every launch. A partial bundle still degrades per-sidecar;
        // delete the bundle dir to force a re-extract.
        writeMarker(bundleDir);
        if (!isUsable(bundleDir)) {
            log.accept("extracted bundle looks incomplete at " + bundleDir
                    + " - missing sidecars will run disabled");
        }
        return bundleDir;
    }
}
