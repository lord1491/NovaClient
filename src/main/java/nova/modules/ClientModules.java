package nova.modules;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import nova.core.*;
import nova.gui.SplashScreen;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class ClientModules {
    private ClientModules() {}

    private static int hsb(float h, float s, float v) {
        h = (h - (float) Math.floor(h)) * 6f;
        int i = (int) h;
        float f = h - i, p = v * (1 - s), q = v * (1 - s * f), t = v * (1 - s * (1 - f)), r, g, b;
        switch (i % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return 0xFF000000 | (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }

    /** Accent colour + window colour of the GUI and HUD. */
    public static class Theme extends Mod {
        static Theme INSTANCE;
        Setting.Mode color = mode("Accent", "Orange", "Orange", "Purple", "Blue", "Red", "Green", "Pink", "Cyan", "Gold", "Rainbow", "Custom");
        Setting.Num cr = num("Custom red", 255, 0, 255, 1), cg = num("Custom green", 123, 0, 255, 1), cb = num("Custom blue", 61, 0, 255, 1);
        Setting.Mode panelMode = mode("Panel", "Nova", "Nova", "Midnight", "Amoled", "Slate", "Forest", "Wine", "Custom");
        Setting.Num pr = num("Panel red", 17, 0, 90, 1), pg = num("Panel green", 12, 0, 90, 1), pb = num("Panel blue", 14, 0, 90, 1);

        public Theme() { super("Theme", Category.CLIENT, "Accent colour and window colour of the GUI and HUD."); INSTANCE = this; }

        public static int accent() {
            if (INSTANCE == null) return 0xFFFF7B3D;
            return switch (INSTANCE.color.value) {
                case "Blue" -> 0xFF4D8AFF;
                case "Red" -> 0xFFFF4D5A;
                case "Green" -> 0xFF3DDC84;
                case "Pink" -> 0xFFFF4DC4;
                case "Purple" -> 0xFF8A4DFF;
                case "Cyan" -> 0xFF35D0E8;
                case "Gold" -> 0xFFFFC53D;
                case "Rainbow" -> hsb((System.currentTimeMillis() % 6000L) / 6000f, 0.62f, 1f);
                case "Custom" -> 0xFF000000 | (INSTANCE.cr.getInt() << 16) | (INSTANCE.cg.getInt() << 8) | INSTANCE.cb.getInt();
                default -> 0xFFFF7B3D;
            };
        }

        /** Base colour of the window (dark colours work best, every other shade in the menu is derived from it). */
        public static int panel() {
            if (INSTANCE == null) return 0xFF110C0E;
            return switch (INSTANCE.panelMode.value) {
                case "Midnight" -> 0xFF0B0E17;
                case "Amoled" -> 0xFF000000;
                case "Slate" -> 0xFF14171B;
                case "Forest" -> 0xFF0B130E;
                case "Wine" -> 0xFF160A10;
                case "Custom" -> 0xFF000000 | (INSTANCE.pr.getInt() << 16) | (INSTANCE.pg.getInt() << 8) | INSTANCE.pb.getInt();
                default -> 0xFF110C0E;
            };
        }
    }

    /** Size, shape and glow of the click GUI. */
    public static class Layout extends Mod {
        static Layout INSTANCE;
        Setting.Num width = num("Width", 600, 400, 800, 10), height = num("Height", 360, 280, 480, 10);
        Setting.Mode columns = mode("Columns", "Auto", "Auto", "1", "2", "3");
        Setting.Num radius = num("Corner radius", 9, 0, 14, 1), glow = num("Glow (%)", 100, 0, 200, 10), opacity = num("Window opacity (%)", 92, 40, 100, 1);
        Setting.Bool sheen = bool("Gradient and header line", true);
        Setting.Mode shape = mode("Shape", "Rounded", "Rounded", "Squircle", "Angular");

        public Layout() { super("Layout", Category.CLIENT, "Size, corners, glow and opacity of this menu."); INSTANCE = this; }

        public static int width() { return INSTANCE == null ? 600 : INSTANCE.width.getInt(); }
        public static int height() { return INSTANCE == null ? 360 : INSTANCE.height.getInt(); }
        public static int radius() { return INSTANCE == null ? 9 : INSTANCE.radius.getInt(); }
        public static float glow() { return INSTANCE == null ? 1f : (float) INSTANCE.glow.get() / 100f; }
        public static int opacity() { return INSTANCE == null ? 92 : INSTANCE.opacity.getInt(); }
        public static boolean sheen() { return INSTANCE == null || INSTANCE.sheen.get(); }
        /** 0 = rounded (circle), 1 = squircle, 2 = angular (cut corners). */
        public static int shapeIndex() { return INSTANCE == null ? 0 : INSTANCE.shape.is("Squircle") ? 1 : INSTANCE.shape.is("Angular") ? 2 : 0; }

        /** Number of card columns for a content width. */
        public static int cols(int cw) {
            if (INSTANCE == null || INSTANCE.columns.is("Auto")) return cw >= 340 ? 2 : 1;
            int n = Integer.parseInt(INSTANCE.columns.value);
            return Math.max(1, Math.min(n, (cw + 6) / 116));
        }
    }

    /**
     * Your own picture behind the menu. Put .png / .jpg files in config/nova/images, then pick one here.
     * The picture is drawn as a grid of coloured cells ("Pixel size") with plain fills, so it does not depend on texture APIs
     * that change between Minecraft versions.
     */
    public static class Background extends Mod {
        static Background INSTANCE;
        Setting.Mode image = mode("Image", "None", "None");
        Setting.Action refresh = action("Refresh image list", Background::refreshAction);
        Setting.Mode area = mode("Draw on", "Window", "Window", "Whole screen");
        Setting.Mode fit = mode("Fit", "Cover", "Cover", "Stretch");
        Setting.Num opacity = num("Image opacity (%)", 60, 5, 100, 5), dim = num("Image dim (%)", 30, 0, 90, 5);
        Setting.Num pixel = num("Pixel size", 3, 2, 10, 1), cardOp = num("Card opacity (%)", 70, 20, 100, 5);

        private static final class Cache { String key = ""; int n; int[] x, y, w, h, c; }
        private static BufferedImage src;
        private static String srcKey = "";
        private static Cache cache;

        public Background() { super("Background", Category.CLIENT, "Your own picture behind the menu (config/nova/images)."); INSTANCE = this; }

        private static Path dir() { return FabricLoader.getInstance().getConfigDir().resolve("nova").resolve("images"); }

        /** Reads the image folder into the "Image" list (also runs every time the menu opens). */
        public static void scan() {
            if (INSTANCE == null) return;
            List<String> names = new ArrayList<>();
            try { Files.createDirectories(dir()); } catch (Exception ignored) {}
            try (Stream<Path> st = Files.list(dir())) {
                st.forEach(f -> {
                    String n = f.getFileName().toString(), l = n.toLowerCase();
                    if (l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".gif") || l.endsWith(".bmp")) names.add(n);
                });
            } catch (Exception ignored) {}
            Collections.sort(names);
            names.add(0, "None");
            INSTANCE.image.setModes(names.toArray(new String[0]));
        }

        private static void refreshAction() {
            scan();
            U.chat("Put .png / .jpg files in " + dir() + " - " + (INSTANCE.image.modes.length - 1) + " found.");
        }

        private static boolean active() { return INSTANCE != null && INSTANCE.enabled && !INSTANCE.image.is("None"); }

        /** How see-through the cards are (1 when there is no picture). */
        public static float cardAlpha() { return active() ? (float) INSTANCE.cardOp.get() / 100f : 1f; }

        public static void drawScreen(DrawContext ctx, int sw, int sh, float alpha) {
            if (active() && INSTANCE.area.is("Whole screen")) paint(ctx, 0, 0, sw, sh, 0, alpha);
        }

        public static void drawWindow(DrawContext ctx, int x, int y, int w, int h, int rad, float alpha) {
            if (active() && INSTANCE.area.is("Window")) paint(ctx, x, y, w, h, rad, alpha);
        }

        private static void paint(DrawContext ctx, int ox, int oy, int w, int h, int rad, float alpha) {
            Background b = INSTANCE;
            Path p = dir().resolve(b.image.value);
            if (!Files.exists(p)) return;
            String fk = p + "|" + p.toFile().lastModified();
            if (!fk.equals(srcKey)) {
                srcKey = fk; src = null; cache = null;
                try {
                    src = readScaled(p.toFile());
                    if (src == null) throw new java.io.IOException("unsupported image format");
                } catch (Throwable t) { U.chat("Could not load image: " + t.getMessage()); }
            }
            if (src == null) return;
            int cell = b.pixel.getInt();
            if (b.area.is("Whole screen")) cell = Math.max(cell, 5);
            while ((w / cell + 1) * (h / cell + 1) > 30000) cell++;
            String key = fk + "|" + w + "x" + h + "|" + cell + "|" + b.fit.value + "|" + rad;
            if (cache == null || !key.equals(cache.key)) cache = build(key, w, h, cell, rad, b.fit.is("Cover"));
            int a = Math.round(255f * (float) b.opacity.get() / 100f * alpha);
            if (a > 3) {
                Cache c = cache;
                for (int i = 0; i < c.n; i++)
                    ctx.fill(ox + c.x[i], oy + c.y[i], ox + c.x[i] + c.w[i], oy + c.y[i] + c.h[i], (a << 24) | (c.c[i] & 0xFFFFFF));
            }
            float dm = (float) b.dim.get() / 100f;
            if (dm > 0.01f) Gfx.rrect(ctx, ox, oy, w, h, rad, Gfx.a(0xFF000000, dm * alpha));
        }

        /** Reads the picture, at reduced size for huge files (keeps memory low). */
        private static BufferedImage readScaled(java.io.File f) throws java.io.IOException {
            try (ImageInputStream in = ImageIO.createImageInputStream(f)) {
                if (in == null) return null;
                Iterator<ImageReader> it = ImageIO.getImageReaders(in);
                if (!it.hasNext()) return null;
                ImageReader rd = it.next();
                try {
                    rd.setInput(in, true, true);
                    int sub = Math.max(1, Math.max(rd.getWidth(0), rd.getHeight(0)) / 1600);
                    ImageReadParam prm = rd.getDefaultReadParam();
                    prm.setSourceSubsampling(sub, sub, 0, 0);
                    return rd.read(0, prm);
                } finally { rd.dispose(); }
            }
        }

        private static boolean close(int a, int b) {
            return Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) <= 10
                && Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) <= 10
                && Math.abs((a & 255) - (b & 255)) <= 10;
        }

        /** Average colour of every cell, rounded corners cut out, equal neighbours merged into one wide rectangle. */
        private static Cache build(String key, int w, int h, int cell, int rad, boolean cover) {
            int cols = (w + cell - 1) / cell, rows = (h + cell - 1) / cell;
            double iw = src.getWidth(), ih = src.getHeight(), sx, sy, offX = 0, offY = 0;
            if (cover) {
                double sc = Math.max(w / iw, h / ih);
                sx = sy = sc;
                offX = (iw * sc - w) / 2; offY = (ih * sc - h) / 2;
            } else { sx = w / iw; sy = h / ih; }
            int[] col = new int[cols * rows];
            for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
                int dx0 = c * cell, dy0 = r * cell, dx1 = Math.min(w, dx0 + cell), dy1 = Math.min(h, dy0 + cell);
                if (rad > 0) {
                    double cxm = (dx0 + dx1) / 2.0, cym = (dy0 + dy1) / 2.0;
                    double ax = Math.max(rad, Math.min(w - rad, cxm)), ay = Math.max(rad, Math.min(h - rad, cym));
                    double ddx = cxm - ax, ddy = cym - ay;
                    if (ddx * ddx + ddy * ddy > (double) rad * rad) continue;   // outside the rounded corner
                }
                int sr = 0, sg = 0, sb = 0;
                for (int j = 0; j < 3; j++) for (int i = 0; i < 3; i++) {
                    double fx = dx0 + (dx1 - dx0) * (i + 0.5) / 3.0, fy = dy0 + (dy1 - dy0) * (j + 0.5) / 3.0;
                    int u = (int) Math.max(0, Math.min(iw - 1, (fx + offX) / sx)), v = (int) Math.max(0, Math.min(ih - 1, (fy + offY) / sy));
                    int argb = src.getRGB(u, v);
                    sr += (argb >> 16) & 255; sg += (argb >> 8) & 255; sb += argb & 255;
                }
                col[r * cols + c] = 0xFF000000 | ((sr / 9) << 16) | ((sg / 9) << 8) | (sb / 9);
            }
            Cache ca = new Cache();
            int max = cols * rows;
            ca.key = key; ca.x = new int[max]; ca.y = new int[max]; ca.w = new int[max]; ca.h = new int[max]; ca.c = new int[max];
            for (int r = 0; r < rows; r++) {
                int c = 0;
                while (c < cols) {
                    int v = col[r * cols + c];
                    if (v == 0) { c++; continue; }
                    int e = c + 1;
                    while (e < cols && col[r * cols + e] != 0 && close(v, col[r * cols + e])) e++;
                    int x = c * cell, y = r * cell;
                    ca.x[ca.n] = x; ca.y[ca.n] = y; ca.w[ca.n] = Math.min(w, e * cell) - x; ca.h[ca.n] = Math.min(h, y + cell) - y; ca.c[ca.n] = v;
                    ca.n++;
                    c = e;
                }
            }
            return ca;
        }
    }

    /**
     * Sounds of the click GUI. Pick one of the built-in sounds, or put your own .wav / .aiff / .au files in
     * config/nova/sounds, press "Refresh sound list" and pick "File: yourname.wav".
     */
    public static class Gui extends Mod {
        static Gui INSTANCE;
        /** {name in the list, vanilla sound id}. An id that does not exist in this version just falls back to the normal click. */
        static final String[][] PRESETS = {
            {"Click", "ui.button.click"}, {"Pling", "block.note_block.pling"}, {"Bell", "block.note_block.bell"},
            {"Chime", "block.note_block.chime"}, {"Harp", "block.note_block.harp"}, {"Bit", "block.note_block.bit"},
            {"Hat", "block.note_block.hat"}, {"Snare", "block.note_block.snare"}, {"Bass drum", "block.note_block.basedrum"},
            {"Xylophone", "block.note_block.xylophone"}, {"Iron xylophone", "block.note_block.iron_xylophone"},
            {"Cow bell", "block.note_block.cow_bell"}, {"Banjo", "block.note_block.banjo"}, {"Guitar", "block.note_block.guitar"},
            {"Flute", "block.note_block.flute"}, {"Amethyst", "block.amethyst_block.chime"}, {"XP orb", "entity.experience_orb.pickup"},
            {"Pop", "entity.item.pickup"}, {"Lever", "block.lever.click"}, {"Wood button", "block.wooden_button.click_on"},
            {"Stone button", "block.stone_button.click_on"}, {"Tripwire", "block.tripwire.click_on"}, {"Comparator", "block.comparator.click"}
        };
        static final String FILE = "File: ";

        private static String[] presetNames() {
            String[] n = new String[PRESETS.length];
            for (int i = 0; i < n.length; i++) n[i] = PRESETS[i][0];
            return n;
        }

        Setting.Bool sounds = bool("Click sounds", true);
        Setting.Mode sound = mode("Sound", "Click", presetNames());
        Setting.Action refreshSounds = action("Refresh sound list", Gui::refreshAction);
        Setting.Action preview = action("Play sound", () -> play(1.0f));
        Setting.Num fileVolume = num("Custom file volume (%)", 80, 5, 100, 5);
        Setting.Bool fileVary = bool("Custom file: pitch follows action", true);
        Setting.Bool splash = bool("Startup splash", true);
        Setting.Num splashLen = num("Splash length (s)", 2.5, 1.0, 6.0, 0.5);
        Setting.Action previewSplash = action("Preview splash", () -> {
            MinecraftClient c = MinecraftClient.getInstance();
            c.setScreen(new SplashScreen(c.currentScreen));
        });

        private static final class Pcm { byte[] data; AudioFormat fmt; long modified; }
        private static final Map<String, Pcm> PCM = new HashMap<>();
        private static String lastFileErr = "";

        public Gui() { super("GUI", Category.CLIENT, "Sounds of this menu (own sounds: config/nova/sounds)."); INSTANCE = this; scan(); }

        public static boolean sounds() { return INSTANCE == null || INSTANCE.sounds.get(); }
        public static boolean splashOn() { return INSTANCE == null || INSTANCE.splash.get(); }
        public static float splashSeconds() { return INSTANCE == null ? 2.5f : (float) INSTANCE.splashLen.get(); }
        public static int customSoundCount() { return INSTANCE == null ? 0 : Math.max(0, INSTANCE.sound.modes.length - PRESETS.length); }

        private static Path dir() { return FabricLoader.getInstance().getConfigDir().resolve("nova").resolve("sounds"); }

        /** Reads the sounds folder into the "Sound" list (also runs every time the menu opens). */
        public static void scan() {
            if (INSTANCE == null) return;
            List<String> files = new ArrayList<>();
            try { Files.createDirectories(dir()); } catch (Exception ignored) {}
            try (Stream<Path> st = Files.list(dir())) {
                st.forEach(f -> {
                    String n = f.getFileName().toString(), l = n.toLowerCase();
                    if (l.endsWith(".wav") || l.endsWith(".aif") || l.endsWith(".aiff") || l.endsWith(".au")) files.add(FILE + n);
                });
            } catch (Exception ignored) {}
            Collections.sort(files);
            List<String> all = new ArrayList<>(Arrays.asList(presetNames()));
            all.addAll(files);
            INSTANCE.sound.setModes(all.toArray(new String[0]));
        }

        private static void refreshAction() {
            scan();
            U.chat("Put .wav / .aiff / .au files in " + dir() + " - " + (INSTANCE.sound.modes.length - PRESETS.length) + " found. (.mp3 / .ogg are not supported, convert them to .wav.)");
        }

        /** Plays the selected GUI sound. Pitch is the one the GUI asks for (higher for "on", lower for "off", ...). */
        public static void play(float pitch) {
            String sel = INSTANCE == null ? "Click" : INSTANCE.sound.value;
            if (sel.startsWith(FILE)) {
                if (playFile(sel.substring(FILE.length()), pitch)) return;
                sel = "Click";   // file missing / unreadable: normal click instead
            }
            String id = "ui.button.click";
            for (String[] p : PRESETS) if (p[0].equals(sel)) id = p[1];
            SoundEvent ev = Registries.SOUND_EVENT.get(Identifier.of("minecraft", id));
            var sm = MinecraftClient.getInstance().getSoundManager();
            if (ev == null) sm.play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, pitch));
            else sm.play(PositionedSoundInstance.master(ev, pitch));
        }

        private static Pcm load(String name) throws Exception {
            Path f = dir().resolve(name).normalize();
            if (!f.startsWith(dir().normalize()) || !Files.isRegularFile(f)) throw new java.io.FileNotFoundException(name);
            if (Files.size(f) > 20L * 1024 * 1024) throw new java.io.IOException("file is bigger than 20 MB");
            long mod = Files.getLastModifiedTime(f).toMillis();
            Pcm c = PCM.get(name);
            if (c != null && c.modified == mod) return c;
            try (AudioInputStream in = AudioSystem.getAudioInputStream(f.toFile())) {
                AudioFormat src = in.getFormat();
                int ch = Math.max(1, src.getChannels());
                AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, src.getSampleRate(), 16, ch, ch * 2, src.getSampleRate(), false);
                try (AudioInputStream conv = AudioSystem.getAudioInputStream(pcm, in)) {
                    Pcm p = new Pcm();
                    p.data = conv.readAllBytes(); p.fmt = pcm; p.modified = mod;
                    PCM.put(name, p);
                    return p;
                }
            }
        }

        private static boolean playFile(String name, float pitch) {
            try {
                final Pcm p = load(name);
                final double vol = INSTANCE == null ? 0.8 : INSTANCE.fileVolume.get() / 100.0;
                final float pr = INSTANCE != null && !INSTANCE.fileVary.get() ? 1f : Math.max(0.5f, Math.min(2f, pitch));
                Thread t = new Thread(() -> {   // opening a line can block for a moment: keep it off the render thread
                    try {
                        float rate = p.fmt.getSampleRate() * pr;
                        AudioFormat f = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, p.fmt.getChannels(), p.fmt.getFrameSize(), rate, false);
                        Clip clip = AudioSystem.getClip();
                        clip.open(f, p.data, 0, p.data.length);
                        if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                            FloatControl g = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                            float db = (float) (20.0 * Math.log10(Math.max(0.0001, vol)));
                            g.setValue(Math.max(g.getMinimum(), Math.min(g.getMaximum(), db)));
                        }
                        clip.addLineListener(e -> { if (e.getType() == LineEvent.Type.STOP) clip.close(); });
                        clip.start();
                    } catch (Throwable ex) { ex.printStackTrace(); }
                }, "nova-sound");
                t.setDaemon(true);
                t.start();
                lastFileErr = "";
                return true;
            } catch (Throwable e) {
                String m = name + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                if (!m.equals(lastFileErr)) { lastFileErr = m; U.chat("Could not play sound file (" + m + "). Use 16 / 24 bit PCM .wav files."); }
                return false;
            }
        }
    }

    /** Small popups in the bottom right when a module is switched on or off. */
    public static class Notify extends Mod {
        static Notify INSTANCE;
        record N(String text, boolean on, long t0) {}
        static final List<N> LIST = new ArrayList<>();
        Setting.Num seconds = num("Duration (s)", 2.5, 1, 6, 0.5);

        public Notify() { super("Notifications", Category.CLIENT, "Popups when a module is toggled."); INSTANCE = this; }

        public static void push(String name, boolean on) {
            if (INSTANCE == null || !INSTANCE.enabled) return;
            LIST.add(0, new N(name, on, System.currentTimeMillis()));
            while (LIST.size() > 6) LIST.remove(LIST.size() - 1);
        }

        @Override public void onHud(DrawContext ctx) {
            int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
            long now = System.currentTimeMillis();
            long life = (long) (seconds.get() * 1000);
            LIST.removeIf(n -> now - n.t0() > life);
            int acc = Theme.accent();
            int pan = Gfx.mix(Theme.panel(), 0xFFFFFFFF, 0.05f);
            for (int i = 0; i < LIST.size(); i++) {
                N n = LIST.get(i);
                long age = now - n.t0();
                float in = Gfx.ease(age / 260f);
                float out = 1f - Gfx.ease((age - (life - 300)) / 300f);
                String state = n.on() ? "enabled" : "disabled";
                int w = U.width(n.text()) + U.width(state) + 34, h = 20;
                int x = sw - w - 8 + Math.round((1 - in) * (w + 12)), y = sh - 44 - i * 24;
                float al = Math.min(in, out);
                Gfx.shadow(ctx, x, y, w, h, 5, al);
                Gfx.rrect(ctx, x, y, w, h, 5, Gfx.a((0xF0 << 24) | (pan & 0xFFFFFF), al));
                Gfx.rrect(ctx, x, y + 4, 2, h - 8, 1, Gfx.a(acc, al));
                int dot = n.on() ? 0xFF4DE08A : 0xFFFF5A5A;
                Gfx.rrect(ctx, x + 7, y + 7, 6, 6, 3, Gfx.a(dot, al));
                U.text(ctx, n.text(), x + 19, y + 6, Gfx.a(0xFFFFFFFF, al));
                U.text(ctx, state, x + 23 + U.width(n.text()), y + 6, Gfx.a(n.on() ? acc : 0xFF8E8380, al));
            }
        }
    }
}
