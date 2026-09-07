package net.clankerjockey.mod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.clankerjockey.core.engine.EngineConfig;
import net.clankerjockey.core.engine.EngineException;
import net.clankerjockey.core.engine.InferenceEngine;
import net.clankerjockey.core.runtime.BundleBootstrap;
import net.clankerjockey.mod.agent.ClankerJockeyAgent;
import net.minecraft.client.render.entity.VillagerEntityRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * ClankerJockey client-side entrypoint (Fabric): spawns llama-server as a child
 * process (same layout as Forge), registers the companion renderer and starts
 * the agent loop once the engine is healthy.
 */
public class ClankerJockeyClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger(ClankerJockeyMod.MOD_ID + "-client");

    private static volatile InferenceEngine engine;

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(ClankerJockeyMod.COMPANION_TYPE, VillagerEntityRenderer::new);
        Thread engineThread = new Thread(() -> {
            // Locate (and, on first launch, extract) the sidecar bundle under
            // <gamedir>/clankerjockey. Degrade-safe: a missing bundle means the
            // engine below logs a warning and the mod runs degraded.
            Path gameDir = Path.of(".").toAbsolutePath();
            Path bundleDir = BundleBootstrap.ensureBundle(gameDir, msg -> LOGGER.info("[{}] {}", ClankerJockeyMod.MOD_ID, msg));
            if (bundleDir == null) {
                LOGGER.warn("[{}] no sidecar bundle found; the mod runs degraded (no brain)", ClankerJockeyMod.MOD_ID);
            }
            try {
                engine = createEngine();
                engine.start();
                LOGGER.info("[{}] inference engine healthy on port {}", ClankerJockeyMod.MOD_ID, engine.port());
                ClankerJockeyAgent.start(engine);
                LOGGER.info("[{}] companion agent online", ClankerJockeyMod.MOD_ID);
            } catch (EngineException e) {
                // Never crash the game over the assistant: log loudly and run degraded.
                LOGGER.error("[{}] failed to start inference engine; mod runs degraded", ClankerJockeyMod.MOD_ID, e);
                engine = null;
            }
        }, "clankerjockey-engine");
        engineThread.setDaemon(true);
        engineThread.start();
        LOGGER.info("[{}] client initialized", ClankerJockeyMod.MOD_ID);
    }

    private static InferenceEngine createEngine() throws EngineException {
        Path gameDir = Path.of(".").toAbsolutePath();
        String os = System.getProperty("os.name").toLowerCase();
        String binaryName = os.contains("win") ? "llama-server.exe" : "llama-server";
        Path binary = gameDir.resolve("clankerjockey/bin/" + binaryName);
        Path model = gameDir.resolve("clankerjockey/models/littlelamb-0.3b-toolcalling-q8_0.gguf");

        if (!Files.isExecutable(binary)) {
            throw new EngineException("llama-server not found at " + binary
                    + " - unpack the ClankerJockey runtime bundle into the game directory");
        }
        if (!Files.isRegularFile(model)) {
            throw new EngineException("model not found at " + model
                    + " - unpack the ClankerJockey runtime bundle into the game directory");
        }

        return new InferenceEngine(EngineConfig.builder()
                .serverBinary(binary)
                .modelPath(model)
                .port(0)
                .threads(Runtime.getRuntime().availableProcessors() >= 8 ? 4 : 2)
                .contextSize(8192)
                .extraArgs(List.of("--jinja"))
                .build());
    }
}
