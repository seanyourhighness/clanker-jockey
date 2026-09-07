package net.clankerjockey.mod;

import net.clankerjockey.core.runtime.BundleBootstrap;
import net.clankerjockey.core.runtime.BundleDownloader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/**
 * Full-screen prompt shown when the ClankerJockey sidecar bundle is missing
 * on first launch. Offers a one-click download (with progress) from the
 * GitHub Release, or a cancel to continue degraded.
 *
 * <p>Forge 1.20.1 / official mappings.
 */
public class BundleDownloadScreen extends Screen {

    private final Path gameDir;
    private final Runnable onComplete;   // called on the client thread after extract succeeds
    private final Runnable onDeclined;   // called on the client thread if the user cancels

    private Button downloadButton;
    private Button cancelButton;
    private BundleDownloader downloader;
    private boolean done;
    private String status = "Ready to download the AI runtime.";

    public BundleDownloadScreen(Path gameDir, Runnable onComplete, Runnable onDeclined) {
        super(Component.literal("ClankerJockey — AI Runtime"));
        this.gameDir = gameDir;
        this.onComplete = onComplete;
        this.onDeclined = onDeclined;
    }

    /** Keep the world running while the user downloads (don't pause). */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int by = this.height / 2 + 40;
        this.downloadButton = Button.builder(Component.literal("Download (1.13 GB)"),
                b -> startDownload())
                .bounds(cx - 160, by, 160, 20)
                .build();
        this.cancelButton = Button.builder(Component.literal("Continue without"),
                b -> decline())
                .bounds(cx + 5, by, 160, 20)
                .build();
        this.addRenderableWidget(this.downloadButton);
        this.addRenderableWidget(this.cancelButton);
    }

    private void startDownload() {
        if (downloader != null) {
            return; // already downloading
        }
        this.downloadButton.active = false;
        this.cancelButton.active = true;
        this.status = "Starting download…";
        Path target = gameDir.resolve(BundleBootstrap.BUNDLE_ZIP_NAME);
        this.downloader = BundleDownloader.start(
                BundleBootstrap.bundleUrl(), target,
                (received, total) -> {
                    if (total > 0) {
                        long pct = (received * 100) / total;
                        this.status = String.format("Downloading… %d / %d  (%d%%)",
                                mb(received), mb(total), pct);
                    } else {
                        this.status = "Downloading… " + mb(received);
                    }
                });
    }

    private void decline() {
        if (downloader != null) {
            downloader.cancel();
        }
        done = true;
        Minecraft.getInstance().setScreen(null);
        onDeclined.run();
    }

    @Override
    public void tick() {
        super.tick();
        if (downloader != null && !done) {
            BundleDownloader.State s = downloader.state();
            if (s == BundleDownloader.State.DONE) {
                done = true;
                this.status = "Download complete — extracting…";
                // Extract on a background thread so we don't block the render loop.
                Path gameDir = this.gameDir;
                Path target = gameDir.resolve(BundleBootstrap.BUNDLE_ZIP_NAME);
                Thread t = new Thread(() -> {
                    Path bundleDir = BundleBootstrap.ensureBundle(gameDir,
                            msg -> System.out.println("[clankerjockey] " + msg));
                    // Hop back to the client thread to close the screen and continue.
                    Minecraft.getInstance().execute(() -> {
                        if (bundleDir != null) {
                            onComplete.run();
                        } else {
                            this.status = "Extract failed — try again.";
                            done = false;
                        }
                        Minecraft.getInstance().setScreen(null);
                    });
                }, "clankerjockey-bundle-extract");
                t.setDaemon(true);
                t.start();
            } else if (s == BundleDownloader.State.FAILED) {
                done = true;
                this.status = "Download failed: " + downloader.lastError();
                this.downloadButton.active = true;
                this.cancelButton.active = true;
            } else if (s == BundleDownloader.State.CANCELLED) {
                // handled by decline()
            }
        }
    }

    @Override
    public void removed() {
        if (downloader != null && !done) {
            downloader.cancel();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(g);
        int cx = this.width / 2;
        int cy = this.height / 2;
        g.drawCenteredString(this.minecraft.font, "ClankerJockey needs its AI runtime", cx, cy - 60, 0xFFFFFF);
        g.drawCenteredString(this.minecraft.font, "The brain, voice-in, and voice-out run locally.", cx, cy - 44, 0xA0A0A0);
        g.drawCenteredString(this.minecraft.font, "This is a one-time ~1.13 GB download.", cx, cy - 28, 0xA0A0A0);
        g.drawCenteredString(this.minecraft.font, status, cx, cy - 2, 0x70FFE0);

        // Draw a simple progress bar under the status line when downloading.
        if (downloader != null && !done) {
            long total = downloader.totalBytes();
            long recv = downloader.receivedBytes();
            int barX = cx - 150;
            int barY = cy + 10;
            int barW = 300;
            int barH = 8;
            g.fill(barX, barY, barX + barW, barY + barH, 0x303030);
            if (total > 0) {
                int fill = (int) ((barW * (double) recv / total));
                g.fill(barX, barY, barX + fill, barY + barH, 0x30C030);
            }
        }

        super.render(g, mouseX, mouseY, partialTicks); // draws the buttons
    }

    private static long mb(long bytes) {
        return bytes / (1024L * 1024L);
    }
}
