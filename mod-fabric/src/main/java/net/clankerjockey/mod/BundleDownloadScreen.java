package net.clankerjockey.mod;

import net.clankerjockey.core.runtime.BundleBootstrap;
import net.clankerjockey.core.runtime.BundleDownloader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.nio.file.Path;

/**
 * Full-screen prompt shown when the ClankerJockey sidecar bundle is missing
 * on first launch. Offers a one-click download (with progress) from the
 * GitHub Release, or a cancel to continue degraded.
 *
 * <p>Fabric 1.21.1 / yarn mappings.
 */
public class BundleDownloadScreen extends Screen {

    private final Path gameDir;
    private final Runnable onComplete;   // client thread, after extract succeeds
    private final Runnable onDeclined;   // client thread, if the user cancels

    private ButtonWidget downloadButton;
    private ButtonWidget cancelButton;
    private BundleDownloader downloader;
    private boolean done;
    private String status = "Ready to download the AI runtime.";

    public BundleDownloadScreen(Path gameDir, Runnable onComplete, Runnable onDeclined) {
        super(Text.of("ClankerJockey — AI Runtime"));
        this.gameDir = gameDir;
        this.onComplete = onComplete;
        this.onDeclined = onDeclined;
    }

    /** Keep the world running while the user downloads (don't pause). */
    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int by = this.height / 2 + 40;
        this.downloadButton = ButtonWidget.builder(Text.of("Download (1.13 GB)"),
                b -> startDownload())
                .position(cx - 160, by)
                .width(160)
                .build();
        this.cancelButton = ButtonWidget.builder(Text.of("Continue without"),
                b -> decline())
                .position(cx + 5, by)
                .width(160)
                .build();
        this.addDrawable(this.downloadButton);
        this.addDrawable(this.cancelButton);
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
        MinecraftClient.getInstance().setScreen(null);
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
                Thread t = new Thread(() -> {
                    Path bundleDir = BundleBootstrap.ensureBundle(gameDir,
                            msg -> System.out.println("[clankerjockey] " + msg));
                    // Hop back to the client thread to close the screen and continue.
                    MinecraftClient.getInstance().execute(() -> {
                        if (bundleDir != null) {
                            onComplete.run();
                        } else {
                            this.status = "Extract failed — try again.";
                            done = false;
                        }
                        MinecraftClient.getInstance().setScreen(null);
                    });
                }, "clankerjockey-bundle-extract");
                t.setDaemon(true);
                t.start();
            } else if (s == BundleDownloader.State.FAILED) {
                done = true;
                this.status = "Download failed: " + downloader.lastError();
                this.downloadButton.active = true;
                this.cancelButton.active = true;
            }
            // CANCELLED is handled by decline().
        }
    }

    @Override
    public void removed() {
        if (downloader != null && !done) {
            downloader.cancel();
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        this.renderBackground(ctx, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int cy = this.height / 2;
        ctx.drawCenteredTextWithShadow(this.textRenderer, "ClankerJockey needs its AI runtime", cx, cy - 60, 0xFFFFFF);
        ctx.drawCenteredTextWithShadow(this.textRenderer, "The brain, voice-in, and voice-out run locally.", cx, cy - 44, 0xA0A0A0);
        ctx.drawCenteredTextWithShadow(this.textRenderer, "This is a one-time ~1.13 GB download.", cx, cy - 28, 0xA0A0A0);
        ctx.drawCenteredTextWithShadow(this.textRenderer, status, cx, cy - 2, 0x70FFE0);

        // Simple progress bar under the status line while downloading.
        if (downloader != null && !done) {
            long total = downloader.totalBytes();
            long recv = downloader.receivedBytes();
            int barX = cx - 150;
            int barY = cy + 10;
            int barW = 300;
            int barH = 8;
            ctx.fill(barX, barY, barX + barW, barY + barH, 0x303030);
            if (total > 0) {
                int fill = (int) ((barW * (double) recv / total));
                ctx.fill(barX, barY, barX + fill, barY + barH, 0x30C030);
            }
        }

        super.render(ctx, mouseX, mouseY, delta); // draws the buttons
    }

    private static long mb(long bytes) {
        return bytes / (1024L * 1024L);
    }
}
