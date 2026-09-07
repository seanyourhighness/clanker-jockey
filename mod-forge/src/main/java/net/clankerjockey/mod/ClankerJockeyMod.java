package net.clankerjockey.mod;

import net.clankerjockey.core.engine.EngineConfig;
import net.clankerjockey.core.engine.EngineException;
import net.clankerjockey.core.engine.GenOptions;
import net.clankerjockey.core.engine.InferenceEngine;
import net.clankerjockey.core.engine.SttConfig;
import net.clankerjockey.core.engine.SttEngine;
import net.clankerjockey.core.engine.TtsConfig;
import net.clankerjockey.core.engine.TtsEngine;
import net.clankerjockey.core.runtime.BundleBootstrap;
import net.minecraft.client.Minecraft;
import net.clankerjockey.mod.agent.ClankerJockeyAgent;
import net.clankerjockey.mod.companion.ClankerJockeyModCompanion;
import net.minecraft.client.renderer.entity.VillagerRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * ClankerJockey main entrypoint (Forge). On client setup, spawns llama-server,
 * PocketTTS.cpp, and whisper-server as child processes of the game JVM and
 * keeps them ready. Each sidecar is resolved from {@code <game dir>/clankerjockey/}
 * and started degraded-safe: a missing bundle logs a warning and that feature
 * runs disabled, never crashing the game.
 */
@Mod(ClankerJockeyMod.MOD_ID)
public class ClankerJockeyMod {

    public static final String MOD_ID = "clankerjockey";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile InferenceEngine engine;
    private static volatile TtsEngine tts;
    private static volatile SttEngine stt;
    private static volatile String ttsVoice = "jo.wav";
    private static volatile VoiceCapture voiceCapture;

    public ClankerJockeyMod() {
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.register(this);
        ClankerJockeyModCompanion.ENTITY_TYPES.register(modBus);
    }

    @SubscribeEvent
    public void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            // Locate (and, on first launch, extract) the sidecar bundle under
            // <gamedir>/clankerjockey. If it's missing entirely, offer a
            // one-click download screen (option 3); declining runs degraded.
            Path gameDir = Path.of(".").toAbsolutePath();
            Path bundleDir = BundleBootstrap.ensureBundle(gameDir, msg -> LOGGER.info("[{}] {}", MOD_ID, msg));
            if (bundleDir != null) {
                startSidecars();
            } else {
                LOGGER.warn("[{}] no sidecar bundle found; offering the download screen", MOD_ID);
                Minecraft.getInstance().setScreen(new BundleDownloadScreen(gameDir,
                        this::startSidecars,
                        () -> LOGGER.warn("[{}] bundle declined; the mod runs degraded (text-only, no brain)", MOD_ID)));
            }
        });
    }

    /**
     * Start all three sidecars (brain, voice-out, voice-in). Called once the
     * bundle is present — either found/extracted at launch, or after the user
     * finishes the download screen. Runs on the client thread; each sidecar
     * degrades independently.
     */
    private void startSidecars() {
        try {
            engine = createEngine();
            engine.start();
            LOGGER.info("[{}] inference engine healthy on port {}", MOD_ID, engine.port());
            ClankerJockeyAgent.start(engine);
            LOGGER.info("[{}] companion agent online", MOD_ID);
            ClankerJockeyAgent clankerjockeyAgent = ClankerJockeyAgent.instance();
            if (clankerjockeyAgent != null) {
                new WorldSensors(clankerjockeyAgent::onSignal).register();
                LOGGER.info("[{}] world sensors online", MOD_ID);
            }
        } catch (EngineException e) {
            // Never crash the game over the assistant: log loudly and run degraded.
            LOGGER.error("[{}] failed to start inference engine; mod runs degraded", MOD_ID, e);
            engine = null;
        }
        startTts();
        startStt();
    }

    /**
     * Start the PocketTTS.cpp sidecar. Independent of the inference engine:
     * a missing TTS bundle degrades to text-only chat, never a crash.
     */
    private void startTts() {
        try {
            tts = createTts();
            tts.start();
            LOGGER.info("[{}] TTS engine healthy on port {} (voice={})", MOD_ID, tts.port(), ttsVoice);
        } catch (EngineException e) {
            LOGGER.warn("[{}] TTS unavailable; chat stays text-only ({}: {})",
                    MOD_ID, e.getClass().getSimpleName(), e.getMessage());
            tts = null;
        }
    }

    private static TtsEngine createTts() throws EngineException {
        Path gameDir = Path.of(".").toAbsolutePath();
        String os = System.getProperty("os.name").toLowerCase();
        String binaryName = os.contains("win") ? "pocket-tts.exe" : "pocket-tts";
        Path binary = gameDir.resolve("clankerjockey/tts/bin/" + binaryName);
        Path modelsDir = gameDir.resolve("clankerjockey/tts/models");
        Path voicesDir = gameDir.resolve("clankerjockey/tts/voices");

        if (!Files.isExecutable(binary)) {
            throw new EngineException("pocket-tts not found at " + binary
                    + " - unpack the ClankerJockey TTS bundle into the game directory");
        }
        if (!Files.isRegularFile(modelsDir.resolve("tokenizer.model"))) {
            throw new EngineException("TTS models not found at " + modelsDir
                    + " - unpack the ClankerJockey TTS bundle into the game directory");
        }

        return new TtsEngine(TtsConfig.builder()
                .binary(binary)
                .modelsDir(modelsDir)
                .voicesDir(voicesDir)
                .port(0) // auto-pick free port, loopback only
                .threads(Runtime.getRuntime().availableProcessors() >= 8 ? 4 : 2)
                .precision("int8")
                .lsdSteps(1)
                .build());
    }

    /**
     * Start the whisper.cpp sidecar. Independent of the inference engine and
     * TTS: a missing STT bundle degrades to text-only input, never a crash.
     */
    private void startStt() {
        try {
            stt = createStt();
            stt.start();
            LOGGER.info("[{}] STT engine healthy on port {}", MOD_ID, stt.port());
        } catch (EngineException e) {
            LOGGER.warn("[{}] STT unavailable; voice input disabled ({}: {})",
                    MOD_ID, e.getClass().getSimpleName(), e.getMessage());
            stt = null;
        }
    }

    private static SttEngine createStt() throws EngineException {
        Path gameDir = Path.of(".").toAbsolutePath();
        String os = System.getProperty("os.name").toLowerCase();
        String binaryName = os.contains("win") ? "whisper-server.exe" : "whisper-server";
        Path binary = gameDir.resolve("clankerjockey/stt/bin/" + binaryName);
        Path model = gameDir.resolve("clankerjockey/stt/models/ggml-small.en.bin");

        if (!Files.isExecutable(binary)) {
            throw new EngineException("whisper-server not found at " + binary
                    + " - unpack the ClankerJockey STT bundle into the game directory");
        }
        if (!Files.isRegularFile(model)) {
            throw new EngineException("whisper model not found at " + model
                    + " - unpack the ClankerJockey STT bundle into the game directory");
        }

        return new SttEngine(SttConfig.builder()
                .binary(binary)
                .modelPath(model)
                .port(0) // auto-pick free port, loopback only
                .threads(Runtime.getRuntime().availableProcessors() >= 8 ? 4 : 2)
                .language("en")
                .build());
    }

    /** Companion body attributes (mod bus, both sides). */
    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class CommonModEvents {
    }

    /** Client-only registration (companion renderer). */
    @Mod.EventBusSubscriber(modid = MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(ClankerJockeyModCompanion.COMPANION_TYPE.get(),
                    VillagerRenderer::new);
        }
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
                .port(0) // auto-pick free port, loopback only
                .threads(Runtime.getRuntime().availableProcessors() >= 8 ? 4 : 2)
                .contextSize(8192)
                // thinking-style model: default template emits empty output
                .extraArgs(List.of("--jinja"))
                .build());
    }

    /** Current engine instance, or null if startup failed. */
    public static InferenceEngine engine() {
        return engine;
    }

    /** Generate with production defaults (LittleLamb needs noThink). */
    public static String generate(String prompt) throws EngineException {
        InferenceEngine e = engine;
        if (e == null || !e.isRunning()) {
            throw new EngineException("inference engine is not running");
        }
        return e.generate(prompt, GenOptions.noThink(120, 0.7));
    }

    /** Current TTS engine instance, or null if the TTS bundle is absent. */
    public static TtsEngine tts() {
        return tts;
    }

    /**
     * Speak a line in the configured cloned voice. Returns the WAV bytes, or
     * {@code null} when TTS is unavailable (caller falls back to text-only).
     * Never throws: a TTS failure must not break chat.
     */
    public static byte[] speak(String text) {
        TtsEngine t = tts;
        if (t == null || !t.isRunning() || text == null || text.isBlank()) {
            return null;
        }
        try {
            return t.speakWav(text, ttsVoice);
        } catch (EngineException e) {
            LOGGER.warn("[{}] TTS synthesis failed; line stays text-only", MOD_ID, e);
            return null;
        }
    }

    /** Select the reference voice sample (inside the TTS voices dir). */
    public static void setTtsVoice(String voice) {
        ttsVoice = voice;
    }

    /**
     * Synthesize a line in the cloned voice AND play it in-game (client
     * thread only). Returns true if audio was queued for playback. This is
     * the full TTS loop: {@code reply -> speak -> play}.
     */
    public static boolean speakAndPlay(String text) {
        byte[] wav = speak(text);
        if (wav == null) {
            return false;
        }
        return TtsAudioPlayer.play(wav, 1.0f, 1.0f);
    }

    /**
     * Start continuous microphone capture. On each detected speech clip the
     * full voice loop runs: STT transcribes, the LLM replies, and the reply
     * is spoken in the cloned voice. No-op (returns false) if no mic or no
     * STT engine is available. Must be called on the client thread.
     *
     * <p>This is the immersive entry point: the player talks to the mic and
     * Vera answers out loud — no typing, no UI.
     */
    public static boolean startVoiceLoop() {
        if (isVoiceLoopActive()) {
            return true; // already listening
        }
        SttEngine s = stt;
        if (s == null || !s.isRunning()) {
            LOGGER.warn("[{}] voice loop unavailable: STT engine not running", MOD_ID);
            return false;
        }
        VoiceCapture capture = voiceCapture == null ? new VoiceCapture() : voiceCapture;
        voiceCapture = capture;
        boolean started = capture.start(wav -> {
            String text;
            try {
                text = s.transcribe(wav, "mic.wav");
            } catch (EngineException e) {
                LOGGER.warn("[{}] STT failed on mic clip", MOD_ID, e);
                return;
            }
            if (text == null || text.isBlank()) {
                return; // silence / no speech
            }
            LOGGER.info("[{}] heard: {}", MOD_ID, text);
            String reply = ClankerJockeyAgent.handleVoice(text);
            if (reply == null || reply.isBlank()) {
                try {
                    reply = generate(text); // fallback: plain LLM reply
                } catch (EngineException e) {
                    LOGGER.warn("[{}] LLM reply failed", MOD_ID, e);
                    return;
                }
            }
            if (reply != null && !reply.isBlank()) {
                speakAndPlay(reply);
            }
        });
        if (!started) {
            LOGGER.warn("[{}] no microphone available; voice loop disabled", MOD_ID);
        }
        return started;
    }

    /**
     * Stop the voice loop (release the microphone). Idempotent: a no-op when
     * the loop isn't running. The TTS/STT sidecars keep running; only capture
     * stops.
     */
    public static synchronized void stopVoiceLoop() {
        VoiceCapture c = voiceCapture;
        if (c != null) {
            c.stop();
        }
    }

    /** True when the voice loop is actively capturing from the microphone. */
    public static boolean isVoiceLoopActive() {
        VoiceCapture c = voiceCapture;
        return c != null && c.isCapturing();
    }

    /** Current STT engine instance, or null if the STT bundle is absent. */
    public static SttEngine stt() {
        return stt;
    }

    /**
     * Transcribe 16 kHz mono audio (WAV or raw PCM) to text. Returns the
     * transcript, or {@code null} when STT is unavailable or it fails.
     * Never throws: an STT failure must not break chat.
     */
    public static String transcribe(byte[] audio, String fileName) {
        SttEngine s = stt;
        if (s == null || !s.isRunning() || audio == null || audio.length == 0) {
            return null;
        }
        try {
            return s.transcribe(audio, fileName);
        } catch (EngineException e) {
            LOGGER.warn("[{}] STT transcription failed", MOD_ID, e);
            return null;
        }
    }
}
