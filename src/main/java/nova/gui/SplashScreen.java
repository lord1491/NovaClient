package nova.gui;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import nova.core.Gfx;
import nova.core.ModuleManager;
import nova.core.U;
import nova.modules.ClientModules;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Short start-up card with a few status lines; any key or click skips it. */
public class SplashScreen extends Screen {
    private static final int TEXT = 0xFFF0E9E6, DIM = 0xFF8E8380;

    private final Screen next;
    private final long start = System.nanoTime();
    private final float secs;
    private final String version;
    private final String[] lines;
    private boolean finished;

    public SplashScreen(Screen next) {
        super(Text.literal("Nova"));
        this.next = next;
        this.secs = Math.max(1f, ClientModules.Gui.splashSeconds());
        this.version = FabricLoader.getInstance().getModContainer("nova").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
        boolean cfg = Files.exists(FabricLoader.getInstance().getConfigDir().resolve("nova.json"));
        this.lines = new String[]{
            ModuleManager.MODULES.size() + " modules loaded",
            cfg ? "Config loaded" : "No saved config yet",
            ClientModules.Gui.customSoundCount() + " custom sounds",
            countSchematics() + " schematics found",
            FabricLoader.getInstance().isModLoaded("litematica") ? "Litematica found" : "Litematica not found"
        };
    }

    private static int countSchematics() {
        Path[] dirs = {FabricLoader.getInstance().getGameDir().resolve("schematics"), FabricLoader.getInstance().getConfigDir().resolve("nova").resolve("schematics")};
        int n = 0;
        for (Path d : dirs) {
            if (!Files.isDirectory(d)) continue;
            try (Stream<Path> st = Files.list(d)) {
                n += (int) st.filter(f -> f.getFileName().toString().toLowerCase().endsWith(".litematic")).count();
            } catch (Exception ignored) {}
        }
        return n;
    }

    private float elapsed() { return (System.nanoTime() - start) / 1e9f; }

    private void finish() {
        if (finished) return;
        finished = true;
        MinecraftClient.getInstance().setScreen(next);
    }

    @Override public boolean shouldPause() { return false; }
    @Override public void close() { finish(); }
    @Override public void tick() { if (elapsed() >= secs) finish(); }
    @Override public boolean keyPressed(KeyInput input) { finish(); return true; }
    @Override public boolean mouseClicked(Click click, boolean doubled) { finish(); return true; }

    @Override public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        float e = elapsed();
        float fadeIn = Gfx.ease(e / 0.35f);
        float fadeOut = 1f - Gfx.ease((e - (secs - 0.45f)) / 0.45f);
        float al = Math.min(fadeIn, fadeOut);
        float p = Gfx.ease(Math.min(1f, e / Math.max(0.5f, secs - 0.4f)));
        int acc = ClientModules.Theme.accent(), pan = ClientModules.Theme.panel();

        ctx.fill(0, 0, width, height, Gfx.a(Gfx.mix(pan, 0xFF000000, 0.55f), al));
        int w = 250, h = 124, x = (width - w) / 2, y = (height - h) / 2 - 6;
        Gfx.shadow(ctx, x, y, w, h, 8, al);
        Gfx.glow(ctx, x, y, w, h, 8, acc, al * 0.6f, 16);
        Gfx.rrect(ctx, x, y, w, h, 8, Gfx.a(Gfx.mix(pan, acc, 0.4f), al));
        Gfx.rrectV(ctx, x + 1, y + 1, w - 2, h - 2, 7, Gfx.a(Gfx.mix(pan, acc, 0.1f), al), Gfx.a(Gfx.mix(pan, 0xFF000000, 0.3f), al));

        String title = "N O V A";
        U.glowText(ctx, title, x + (w - U.width(title)) / 2, y + 10, Gfx.a(acc, al), al);
        String ver = version.isEmpty() ? "client" : "client " + version;
        U.text(ctx, ver, x + (w - U.width(ver)) / 2, y + 22, Gfx.a(DIM, al));

        for (int i = 0; i < lines.length; i++) {
            float ra = Gfx.ease((p - i * 0.17f) / 0.12f) * al;
            if (ra <= 0.01f) continue;
            int ry = y + 38 + i * 11;
            boolean done = ra > 0.9f * al;
            Gfx.rrect(ctx, x + 16, ry + 2, 5, 5, 2, Gfx.a(done ? acc : DIM, ra));
            U.text(ctx, lines[i], x + 28, ry, Gfx.a(TEXT, ra));
        }

        int bx = x + 16, by = y + h - 16, bw = w - 32;
        Gfx.rrect(ctx, bx, by, bw, 5, 2, Gfx.a(Gfx.mix(pan, 0xFFFFFFFF, 0.17f), al));
        int fw = Math.max(0, Math.round(bw * p));
        if (fw > 3) {
            Gfx.glow(ctx, bx, by, fw, 5, 2, acc, al * 0.5f, 6);
            Gfx.rrect(ctx, bx, by, fw, 5, 2, Gfx.a(acc, al));
        }
        String hint = "Press any key to skip";
        U.text(ctx, hint, (width - U.width(hint)) / 2, y + h + 8, Gfx.a(DIM, al * 0.8f));
    }
}
