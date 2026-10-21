package nova.modules;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import nova.core.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** GodTrident, RainNoti, SpotifyHud (Spotify + Apple Music) and FakeBanScreen. Written for Nova from the feature descriptions. */
public final class ExtraFun {
    private ExtraFun() {}

    // ------------------------------------------------------------------ GodTrident

    /** Riptide without water / rain and a shorter charge. The checks live in TridentMixin; this class only holds the settings. */
    public static class GodTrident extends Mod {
        static GodTrident INSTANCE;
        Setting.Bool anywhere = bool("Riptide out of water", true);
        Setting.Num charge = num("Charge (ticks, vanilla 10)", 3, 0, 10, 1);

        public GodTrident() {
            super("GodTrident", Category.MOVEMENT, "Riptide without water and a shorter charge. Needs the Nova mixins; a server that checks water itself still refuses it.");
            INSTANCE = this;
        }

        public static boolean anywhere() { return INSTANCE != null && INSTANCE.enabled && INSTANCE.anywhere.get(); }
        public static int charge(int vanilla) { return INSTANCE != null && INSTANCE.enabled ? INSTANCE.charge.getInt() : vanilla; }
    }

    // ------------------------------------------------------------------ RainNoti

    public static class RainNoti extends Mod {
        Setting.Bool chat = bool("Chat", true), sound = bool("Sound", true), popup = bool("Popup", true),
                stops = bool("Also when it stops", false), thunder = bool("Thunder", false);
        Setting.Num seconds = num("Popup time (s)", 4, 1, 10, 1);
        boolean rain, thundering, init;
        Object worldRef;
        String msg;
        long at;

        public RainNoti() { super("RainNoti", Category.MISC, "Popup, chat and sound when it starts raining."); }

        @Override public void onEnable() { init = false; worldRef = mc.world; msg = null; }

        void fire(String s) {
            if (chat.get()) U.chat(s);
            if (sound.get()) mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_CHIME, 1.0f));
            msg = s; at = System.currentTimeMillis();
        }

        @Override public void onTick() {
            if (worldRef != mc.world) { worldRef = mc.world; init = false; }   // new dimension / server: start from its weather
            boolean r = mc.world.isRaining(), th = mc.world.isThundering();
            if (init) {
                if (r && !rain) fire("It started raining.");
                else if (!r && rain && stops.get()) fire("The rain stopped.");
                if (thunder.get() && th && !thundering) fire("Thunder started.");
            }
            rain = r; thundering = th; init = true;
        }

        @Override public void onHud(DrawContext ctx) {
            if (!popup.get() || msg == null) return;
            long life = (long) (seconds.get() * 1000), age = System.currentTimeMillis() - at;
            if (age > life) { msg = null; return; }
            float al = Math.min(Gfx.ease(age / 250f), 1f - Gfx.ease((age - (life - 300)) / 300f));
            int w = U.width(msg) + 22, h = 20, x = (mc.getWindow().getScaledWidth() - w) / 2, y = 30 - Math.round((1 - Gfx.ease(age / 250f)) * 24);
            Gfx.shadow(ctx, x, y, w, h, 5, al);
            Gfx.rrect(ctx, x, y, w, h, 5, Gfx.a(0xF0141414, al));
            Gfx.rrect(ctx, x + 6, y + 7, 6, 6, 3, Gfx.a(0xFF4D9DFF, al));
            U.text(ctx, msg, x + 17, y + 6, Gfx.a(0xFFFFFFFF, al));
        }
    }

    // ------------------------------------------------------------------ SpotifyHud

    /**
     * Shows what the desktop Spotify or Apple Music app is playing, with album art ("Music app" setting: Spotify / Apple Music / Auto). No account or login needed, it reads the local app:
     * Windows: the system media session through PowerShell (artist, title, play state, position, length and the cover,
     * which is saved to a temp file); falls back to the Spotify window title (no cover, no progress).
     * macOS: AppleScript (cover is a URL). Linux: playerctl (cover is a URL or file). Everything runs on a background thread;
     * the cover is decoded there, shrunk to the size it is drawn at and uploaded as a texture on the render thread.
     */
    public static class SpotifyHud extends Mod {
        private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("nova");
        static final int SPOTIFY_GREEN = 0xFF1ED760, APPLE_RED = 0xFFFA243C;
        static final int COVER = 40, ROUND = 7;   // cover size in GUI pixels (the texture has exactly this size) and its corner radius
        static final Identifier ART_ID = Identifier.of("nova", "spotify_art");

        Setting.Mode corner = mode("Corner", "Bottom right", "Top right", "Top left", "Bottom left", "Bottom right");
        Setting.Mode source = mode("Music app", "Spotify", "Spotify", "Apple Music", "Auto");   // Auto = whichever is playing (Spotify first)
        Setting.Mode color = mode("Color", "Album art", "Album art", "Client accent", "App color", "Spotify green", "Apple red", "Custom");
        Setting.Num cr = num("Custom red", 30, 0, 255, 1), cg = num("Custom green", 215, 0, 255, 1), cb = num("Custom blue", 96, 0, 255, 1);
        Setting.Num refresh = num("Refresh (s)", 2, 1, 10, 1), maxWidth = num("Max width", 220, 140, 300, 10);
        Setting.Bool hideIdle = bool("Hide when not playing", true), art = bool("Album art", true), equalizer = bool("Equalizer", true),
                progress = bool("Progress bar", true), scroll = bool("Scroll long titles", true), glow = bool("Glow", true);

        // written by the poll thread, read by the HUD
        static volatile String artist = "", title = "", state = "off", status = "";   // state: playing / paused / off
        static volatile String wantApp = "Spotify", activeApp = "Spotify";   // wantApp = the setting, activeApp = what is shown right now
        static volatile double posSec, durSec;
        static volatile long polledAt;
        static volatile byte[] pendingPng;       // decoded + shrunk cover (PNG) waiting for the render thread
        static volatile boolean clearArt;        // the current track has no cover
        static volatile int artColor;            // vivid colour taken from the cover, 0 = none
        static String curArt = "", loadedKey;    // poll thread only
        volatile boolean running;
        Thread worker;
        boolean warned;

        // HUD-thread state
        NativeImageBackedTexture tex;
        boolean hasArt;
        float vis;
        long lastFrame, changedAt;
        String lastKey = "";

        public SpotifyHud() { super("SpotifyHud", Category.HUD, "Shows the track playing in the desktop Spotify or Apple Music app (cover, artist, title, progress)."); }

        /** The HUD colour: the cover's colour by default (falls back to the client accent), the client accent, Spotify green, or your own RGB. */
        int base() {
            if (color.is("Spotify green")) return SPOTIFY_GREEN;
            if (color.is("Apple red")) return APPLE_RED;
            if (color.is("App color")) return activeApp.equals("Apple Music") ? APPLE_RED : SPOTIFY_GREEN;
            if (color.is("Custom")) return 0xFF000000 | (cr.getInt() << 16) | (cg.getInt() << 8) | cb.getInt();
            if (color.is("Album art") && hasArt && artColor != 0) return artColor;
            return ClientModules.Theme.accent();
        }

        @Override public void onEnable() {
            if (running && worker != null && worker.isAlive()) return;
            running = true;
            loadedKey = null;
            Thread t = new Thread(() -> {
                while (running && worker == Thread.currentThread()) {
                    wantApp = source.value;
                    try { poll(); }
                    catch (Throwable e) {
                        state = "off"; status = "Could not read " + wantApp;
                        if (!warned) { warned = true; LOG.warn("MusicHud: poll failed", e); }
                    }
                    try { Thread.sleep(Math.max(1000L, (long) (refresh.get() * 1000))); } catch (InterruptedException e) { return; }
                }
            }, "nova-spotify");
            t.setDaemon(true);
            worker = t;
            t.start();
        }

        @Override public void onDisable() {
            running = false;
            if (worker != null) worker.interrupt();
            worker = null;
            state = "off";
            vis = 0;
            dropTexture();
        }

        void dropTexture() {
            if (tex != null) { try { mc.getTextureManager().destroyTexture(ART_ID); } catch (Throwable ignored) {} tex = null; }
            hasArt = false;
        }

        /** Runs a local command (fixed arguments, no user input) and returns its output; empty on timeout. */
        static String run(String... cmd) throws Exception {
            Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();   // stderr is dropped: PowerShell writes progress / CLIXML noise there
            p.getOutputStream().close();
            if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); return ""; }
            return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).replace("﻿", "").trim();
        }

        /**
         * Windows script (sent as -EncodedCommand, result comes back as Base64 so no console encoding is involved).
         * 1) system media session of Spotify: "M|status|position|length|coverFile|artist|title" (the cover is written to %TEMP%)
         * 2) fallback, window titles of Spotify: "T|title1 newline title2"
         */
        static final String PS_T = String.join("\n",
                "$ProgressPreference='SilentlyContinue'; $ErrorActionPreference='Stop'; $r=$null",
                "try {",
                "  Add-Type -AssemblyName System.Runtime.WindowsRuntime",
                "  $m=[System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1",
                "  function Aw($op,$t){ $x=$m.MakeGenericMethod($t).Invoke($null,@($op)); [void]$x.Wait(5000); $x.Result }",
                "  [void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]",
                "  [void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties,Windows.Media.Control,ContentType=WindowsRuntime]",
                "  $mgr=Aw ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])",
                "  foreach($s in $mgr.GetSessions()){ if(@MATCH@){",
                "    $p=Aw ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])",
                "    $st=[string]$s.GetPlaybackInfo().PlaybackStatus; $tl=$s.GetTimelineProperties()",
                "    $pos=$tl.Position.TotalSeconds; $len=$tl.EndTime.TotalSeconds-$tl.StartTime.TotalSeconds",
                "    if($st -eq 'Playing'){ $pos+=([DateTimeOffset]::UtcNow-$tl.LastUpdatedTime).TotalSeconds }",
                "    if($len -gt 0 -and $pos -gt $len){ $pos=$len }; if($pos -lt 0){ $pos=0 }",
                "    $cov=''",
                "    try { if($p.Thumbnail){ [void][Windows.Storage.Streams.IRandomAccessStreamWithContentType,Windows.Storage.Streams,ContentType=WindowsRuntime]; $rs=Aw ($p.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType]); $src=[System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($rs); $f=Join-Path $env:TEMP 'nova_spotify_cover.bin'; $fs=[System.IO.File]::Create($f); $src.CopyTo($fs); $fs.Close(); $src.Close(); $cov=$f } } catch { $cov='!'+($_.Exception.Message -replace '[|\\r\\n]',' ') }",
                "    $r='M|'+$st+'|'+[int]$pos+'|'+[int]$len+'|'+$cov+'|'+$p.Artist+'|'+$p.Title; break } }",
                "} catch {}",
                "if(-not $r){ $ErrorActionPreference='SilentlyContinue'; $r='T|'+((Get-Process -Name @PROCS@ | Where-Object { $_.MainWindowTitle } | ForEach-Object { $_.MainWindowTitle }) -join [string][char]10) }",
                "[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($r))");

        /** The PowerShell script for one app: which media sessions count (AppUserModelId) and which processes to look at for the title fallback. */
        static String ps(boolean apple) {
            return PS_T.replace("@MATCH@", apple
                            ? "$s.SourceAppUserModelId -like '*AppleMusic*' -or $s.SourceAppUserModelId -like '*Apple*Music*' -or $s.SourceAppUserModelId -like '*iTunes*'"
                            : "$s.SourceAppUserModelId -like '*Spotify*'")
                    .replace("@PROCS@", apple ? "AppleMusic,iTunes" : "Spotify");
        }

        static boolean generic(String t, boolean apple) {
            if (apple) return true;   // Apple Music / iTunes window titles never contain the track, only the media session does
            String l = t.toLowerCase(Locale.ROOT);
            return l.equals("spotify") || l.equals("spotify free") || l.equals("spotify premium") || l.equals("advertisement")
                    || l.equals("spotify - web player") || l.startsWith("spotify premium") || l.startsWith("spotify free");
        }

        static void poll() throws Exception {
            curArt = "";
            pollSource();
            boolean off = state.equals("off");
            loadArt(off ? "" : curArt, off ? "" : artist, off ? "" : title);
        }

        /** Spotify, Apple Music, or (Auto) whichever is playing: Spotify first, then Apple Music, else the one that is paused. */
        static void pollSource() throws Exception {
            String want = wantApp;
            if (!want.equals("Auto")) { pollOne(want); activeApp = want; return; }
            pollOne("Spotify");
            if (state.equals("playing")) { activeApp = "Spotify"; return; }
            String sArtist = artist, sTitle = title, sState = state, sStatus = status, sArt = curArt;
            double sPos = posSec, sDur = durSec; long sAt = polledAt;
            pollOne("Apple Music");
            if (state.equals("playing")) { activeApp = "Apple Music"; return; }
            if (!sState.equals("off")) {                        // Spotify is open but paused: show that
                artist = sArtist; title = sTitle; state = sState; status = sStatus; curArt = sArt;
                posSec = sPos; durSec = sDur; polledAt = sAt;
                activeApp = "Spotify";
            } else if (!state.equals("off")) activeApp = "Apple Music";
            else { activeApp = "Music app"; status = "Open Spotify or Apple Music"; }
        }

        static void pollOne(String app) throws Exception {
            boolean apple = app.equals("Apple Music");
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                String enc = Base64.getEncoder().encodeToString(ps(apple).getBytes(StandardCharsets.UTF_16LE));
                String out = run("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", enc);
                String b64 = null;   // the result is the last line that looks like Base64 (anything else PowerShell printed is ignored)
                String[] ls = out.split("\\R");
                for (int k = ls.length - 1; k >= 0 && b64 == null; k--) if (ls[k].trim().matches("[A-Za-z0-9+/=]{4,}")) b64 = ls[k].trim();
                if (b64 == null) { state = "off"; status = "PowerShell failed"; LOG.warn("MusicHud: unexpected PowerShell output: {}", out.length() > 600 ? out.substring(0, 600) : out); return; }
                String res = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
                if (res.startsWith("M|")) { parse(res.substring(2), 1.0, app); return; }
                String best = null, any = null;
                for (String t : (res.startsWith("T|") ? res.substring(2) : "").split("\n")) {
                    t = t.trim();
                    if (t.isEmpty()) continue;
                    if (any == null) any = t;
                    if (best == null && !generic(t, apple) && t.contains(" - ")) best = t;
                }
                if (any == null) { state = "off"; status = "Open " + app; return; }
                if (best == null) { state = "paused"; status = "Nothing playing"; return; }   // title is just "Spotify Premium/Free" when paused or during an ad
                int i = best.indexOf(" - ");
                artist = best.substring(0, i); title = best.substring(i + 3); state = "playing"; status = ""; durSec = 0;
            } else if (os.contains("mac")) {
                if (apple) {   // the Music app (duration is already in seconds, artwork is not a URL: the cover is looked up by artist + title)
                    parse(run("osascript", "-e", "if application \"Music\" is running then tell application \"Music\" to return (if player state is playing then \"playing\" else \"paused\") & \"|\" & (round (player position)) & \"|\" & (round (duration of current track)) & \"||\" & (artist of current track) & \"|\" & (name of current track)"), 1.0, app);
                } else {
                    parse(run("osascript", "-e", "if application \"Spotify\" is running then tell application \"Spotify\" to return (if player state is playing then \"playing\" else \"paused\") & \"|\" & (round (player position)) & \"|\" & (round ((duration of current track) / 1000)) & \"|\" & (artwork url of current track) & \"|\" & (artist of current track) & \"|\" & (name of current track)"), 1.0, app);
                }
            } else {   // Linux: Apple Music has no official app; Cider and similar clients show up as MPRIS players
                String players = apple ? "cider,Cider,apple-music,AppleMusic" : "spotify";
                try { parse(run("playerctl", "-p", players, "metadata", "--format", "{{status}}|{{position}}|{{mpris:length}}|{{mpris:artUrl}}|{{artist}}|{{title}}"), 1e-6, app); }
                catch (java.io.IOException e) { state = "off"; status = "Install playerctl"; }
            }
        }

        /** "status|position|length|cover|artist|title"; position and length times `scale` are seconds; cover is a file path or an https URL. */
        static void parse(String out, double scale, String app) {
            String[] s = out.split("\\|", 6);
            if (s.length < 6 || s[5].trim().isEmpty()) { state = "off"; status = "Open " + app; return; }
            double p = 0, d = 0;
            try { p = Double.parseDouble(s[1].trim()) * scale; d = Double.parseDouble(s[2].trim()) * scale; } catch (NumberFormatException ignored) {}
            curArt = s[3].trim(); artist = s[4].trim(); title = s[5].trim(); status = "";
            posSec = p; durSec = d; polledAt = System.currentTimeMillis();
            state = s[0].trim().equalsIgnoreCase("playing") ? "playing" : "paused";
        }

        // ---------------------------------------------------------------- cover art (poll thread)

        /** Loads the cover (file path or https URL); only decodes again when it changed. Without a cover from the app it is looked up by artist and title. */
        static void loadArt(String src, String artistName, String titleName) {
            try {
                if (src.startsWith("!")) { LOG.warn("SpotifyHud: Windows gave no cover: {}", src.substring(1)); src = ""; }
                if (src.isEmpty() && !titleName.isEmpty()) src = lookupCover(artistName, titleName);
                if (src.isEmpty()) { if (loadedKey != null) { loadedKey = null; artColor = 0; clearArt = true; } return; }
                byte[] raw;
                String key;
                if (src.startsWith("https://")) {
                    if (src.equals(loadedKey)) return;
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) java.net.URI.create(src).toURL().openConnection();
                    c.setConnectTimeout(4000); c.setReadTimeout(4000);
                    try (java.io.InputStream in = c.getInputStream()) { raw = in.readNBytes(3_000_000); }
                    key = src;
                } else {
                    if (src.startsWith("file:")) src = java.nio.file.Path.of(java.net.URI.create(src)).toString();
                    raw = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(src));
                    key = "f" + raw.length + ":" + Arrays.hashCode(raw);
                    if (key.equals(loadedKey)) return;
                }
                if (raw.length < 16) { if (loadedKey != null) { loadedKey = null; artColor = 0; clearArt = true; } return; }   // Windows briefly gives an empty thumbnail between tracks
                loadedKey = key;
                decode(raw);
                LOG.info("SpotifyHud: cover loaded ({} bytes) for {}", raw.length, titleName);
            } catch (Throwable e) {
                loadedKey = null;
                if (!artWarned) { artWarned = true; LOG.warn("SpotifyHud: cover failed: {}", e.toString()); }
            }
        }

        static String lookupKey = "", lookupUrl = "";

        /** Fallback when the app gives no cover: the iTunes search API (no key, no login) finds the album art by artist and title. */
        static String lookupCover(String artistName, String titleName) {
            String key = artistName + "\0" + titleName;
            if (key.equals(lookupKey)) return lookupUrl;
            lookupKey = key; lookupUrl = "";
            try {
                String q = java.net.URLEncoder.encode(artistName + " " + titleName, StandardCharsets.UTF_8);
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) java.net.URI.create("https://itunes.apple.com/search?media=music&entity=song&limit=5&term=" + q).toURL().openConnection();
                c.setConnectTimeout(4000); c.setReadTimeout(4000);
                String body;
                try (java.io.InputStream in = c.getInputStream()) { body = new String(in.readNBytes(500_000), StandardCharsets.UTF_8); }
                com.google.gson.JsonArray res = com.google.gson.JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("results");
                String first = artistName.split("[,&]")[0].trim().toLowerCase(Locale.ROOT), pick = null;
                for (com.google.gson.JsonElement e : res) {
                    com.google.gson.JsonObject o = e.getAsJsonObject();
                    if (!o.has("artworkUrl100")) continue;
                    String url = o.get("artworkUrl100").getAsString();
                    if (pick == null) pick = url;
                    if (!first.isEmpty() && o.has("artistName") && o.get("artistName").getAsString().toLowerCase(Locale.ROOT).contains(first)) { pick = url; break; }
                }
                if (pick != null) lookupUrl = pick.replace("100x100", "200x200");
            } catch (Throwable e) { LOG.warn("SpotifyHud: cover lookup failed: {}", e.toString()); }
            return lookupUrl;
        }
        static boolean artWarned;

        /** Centre-crops to a square, box-averages down to COVER x COVER, stores it as PNG bytes and picks a vivid colour from it. */
        static void decode(byte[] raw) throws Exception {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(raw));
            if (img == null) throw new IllegalStateException("unreadable image");
            int side = Math.min(img.getWidth(), img.getHeight()), ox = (img.getWidth() - side) / 2, oy = (img.getHeight() - side) / 2;
            java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(COVER, COVER, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            double wr = 0, wg = 0, wb = 0, wsum = 0;
            for (int y = 0; y < COVER; y++) for (int x = 0; x < COVER; x++) {
                int x0 = ox + x * side / COVER, x1 = Math.max(x0 + 1, ox + (x + 1) * side / COVER);
                int y0 = oy + y * side / COVER, y1 = Math.max(y0 + 1, oy + (y + 1) * side / COVER);
                long r = 0, g = 0, b = 0; int n = 0;
                for (int yy = y0; yy < y1; yy++) for (int xx = x0; xx < x1; xx++) { int p = img.getRGB(xx, yy); r += (p >> 16) & 255; g += (p >> 8) & 255; b += p & 255; n++; }
                int rr = (int) (r / n), gg = (int) (g / n), bb = (int) (b / n);
                out.setRGB(x, y, 0xFF000000 | (rr << 16) | (gg << 8) | bb);
                float[] hsb = java.awt.Color.RGBtoHSB(rr, gg, bb, null);
                double w = hsb[1] * hsb[2] + 0.02;   // colourful, bright pixels count most
                wr += rr * w; wg += gg * w; wb += bb * w; wsum += w;
            }
            float[] h = java.awt.Color.RGBtoHSB((int) (wr / wsum), (int) (wg / wsum), (int) (wb / wsum), null);
            artColor = 0xFF000000 | (java.awt.Color.HSBtoRGB(h[0], Math.max(0.45f, h[1]), Math.max(0.8f, h[2])) & 0xFFFFFF);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(out, "png", bo);
            pendingPng = bo.toByteArray();
            clearArt = false;
        }

        // ---------------------------------------------------------------- drawing (render thread)

        static String fit(String s, int w) {
            if (U.width(s) <= w) return s;
            while (s.length() > 1 && U.width(s + "...") > w) s = s.substring(0, s.length() - 1);
            return s + "...";
        }

        static String clock(double sec) { int s = (int) Math.max(0, sec); return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60); }

        /** Draws text clipped to w pixels; if it is wider it slowly scrolls back and forth (with a pause at both ends). */
        void marquee(DrawContext ctx, String s, int x, int y, int w, int color, long now, boolean allowScroll) {
            int tw = U.width(s);
            if (tw <= w) { U.text(ctx, s, x, y, color); return; }
            if (!allowScroll) { U.text(ctx, fit(s, w), x, y, color); return; }
            double dist = tw - w + 6, travel = dist / 28.0 * 1000.0, pause = 1500;
            double t = (now - changedAt) % (long) (travel * 2 + pause * 2);
            double off = t < pause ? 0 : t < pause + travel ? (t - pause) / travel * dist : t < pause * 2 + travel ? dist : (1 - (t - pause * 2 - travel) / travel) * dist;
            ctx.enableScissor(x, y - 2, x + w, y + 11);
            U.text(ctx, s, x - (int) off, y, color);
            ctx.disableScissor();
        }

        /** Uploads a freshly decoded cover, or drops the old one (render thread only). */
        void syncTexture() {
            byte[] png = pendingPng;
            if (png != null) {
                pendingPng = null;
                try {
                    NativeImage ni = NativeImage.read(new java.io.ByteArrayInputStream(png));
                    dropTexture();
                    tex = new NativeImageBackedTexture(() -> "nova-spotify-cover", ni);
                    mc.getTextureManager().registerTexture(ART_ID, tex);
                    hasArt = true;
                } catch (Throwable e) {
                    hasArt = false;
                    LOG.warn("SpotifyHud: could not upload cover: {}", e.toString());
                }
            }
            if (clearArt) { clearArt = false; dropTexture(); }
        }

        /** The cover with rounded corners: one 1-pixel-high strip per row, so no shader or mask is needed. */
        static void drawCover(DrawContext ctx, int x, int y) {
            for (int i = 0; i < COVER; i++) {
                int d = i < ROUND ? ROUND - i : i >= COVER - ROUND ? i - (COVER - ROUND) + 1 : 0, inset = 0;
                if (d > 0) inset = (int) Math.ceil(ROUND - Math.sqrt(Math.max(0.0, (double) ROUND * ROUND - (d - 0.5) * (d - 0.5))));
                ctx.drawTexture(RenderPipelines.GUI_TEXTURED, ART_ID, x + inset, y + i, (float) inset, (float) i, COVER - inset * 2, 1, COVER, COVER);
            }
        }

        @Override public void onHud(DrawContext ctx) {
            syncTexture();
            long now = System.currentTimeMillis();
            String st = state, ti = title, ar = artist;
            boolean have = !ti.isEmpty() && !st.equals("off");
            boolean show = (have && (st.equals("playing") || !hideIdle.get())) || (!hideIdle.get() && st.equals("off"));
            if (!have) { ti = activeApp + " not detected"; ar = status.isEmpty() ? "Open " + activeApp : status; }
            boolean on = st.equals("playing");

            // fade in / out
            float dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (now - lastFrame) / 1000f);
            lastFrame = now;
            vis = Math.max(0f, Math.min(1f, vis + (show ? 1 : -1) * dt * 4f));
            String key = ti + "\0" + ar;
            if (!key.equals(lastKey)) { lastKey = key; changedAt = now; }
            if (vis <= 0.02f) return;
            float al = Gfx.ease(vis), pop = Gfx.ease((now - changedAt) / 450f);   // pop: the cover flashes briefly when the track changes

            double dur = durSec, cur = posSec + (on ? (now - polledAt) / 1000.0 : 0);
            if (dur > 0 && cur > dur) cur = dur;
            boolean bar = progress.get() && have && dur > 0, cov = art.get() && hasArt && have;
            String t0 = bar ? clock(cur) : "", t1 = bar ? clock(dur) : "";

            int col = base();
            int pad = 7, h = COVER + pad * 2, m = 6, gap = 10;
            boolean eq = equalizer.get(), right = corner.value.endsWith("right");
            int eqW = eq ? 14 : 0;
            int textMax = maxWidth.getInt() - pad - COVER - gap - 10 - (eq ? eqW + 8 : 0);
            int tw = Math.max(86, Math.min(textMax, Math.max(U.width(ti), U.width(ar))));   // wide enough for the time labels under the bar
            int w = pad + COVER + gap + tw + 10 + (eq ? eqW + 8 : 0);
            int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
            int slide = Math.round((1 - al) * 18);
            int x = (right ? sw - w - m + slide : m - slide);
            int y = corner.value.startsWith("Top") ? 26 : sh - h - m - 40;   // bottom corners sit above the module toggle popups

            int tint = on ? col : 0xFF7A7A7A;
            if (glow.get() && on) Gfx.glow(ctx, x, y, w, h, 9, col, 0.45f * al, 9);
            Gfx.shadow(ctx, x, y, w, h, 9, al);
            Gfx.rrect(ctx, x, y, w, h, 9, Gfx.a(Gfx.mix(0xFF2A2A2A, col, on ? 0.5f : 0f), al));                          // 1px border
            Gfx.rrectV(ctx, x + 1, y + 1, w - 2, h - 2, 8,                                                              // body: dark, tinted by the HUD colour at the top
                    Gfx.a(Gfx.mix(0xFF16161A, col, on ? 0.22f : 0.04f) | 0xFF000000, al * 0.97f),
                    Gfx.a(Gfx.mix(0xFF0B0B0D, col, on ? 0.06f : 0.01f) | 0xFF000000, al * 0.97f));

            // cover: real album art (rounded), or a tile in the HUD colour with the first letter
            int cx = x + pad, cy = y + pad;
            if (on) Gfx.glow(ctx, cx, cy, COVER, COVER, ROUND, col, 0.4f * al, 5);
            Gfx.rrect(ctx, cx - 1, cy - 1, COVER + 2, COVER + 2, ROUND + 1, Gfx.a(Gfx.mix(0xFF2A2A2A, col, on ? 0.7f : 0f), al));
            if (cov) {
                drawCover(ctx, cx, cy);
                if (al < 0.98f) Gfx.rrect(ctx, cx, cy, COVER, COVER, ROUND, Gfx.a(0xFF121212, 1f - al));   // fades the texture, which cannot be tinted
            } else {
                Gfx.rrectV(ctx, cx, cy, COVER, COVER, ROUND, Gfx.a(on ? Gfx.mix(col, 0xFFFFFFFF, 0.2f) : 0xFF5A5A5A, al), Gfx.a(on ? Gfx.mix(col, 0xFF000000, 0.6f) : 0xFF2E2E2E, al));
                String letter = have ? ti.substring(0, 1).toUpperCase(Locale.ROOT) : "?";
                U.text(ctx, letter, cx + (COVER - U.width(letter)) / 2, cy + (COVER - 8) / 2, Gfx.a(0xFFFFFFFF, al));
            }
            if (have && !on) {   // paused: dim the cover and draw a pause symbol
                Gfx.rrect(ctx, cx, cy, COVER, COVER, ROUND, Gfx.a(0xFF000000, al * 0.55f));
                Gfx.rrect(ctx, cx + COVER / 2 - 6, cy + COVER / 2 - 7, 4, 14, 1, Gfx.a(0xFFFFFFFF, al));
                Gfx.rrect(ctx, cx + COVER / 2 + 2, cy + COVER / 2 - 7, 4, 14, 1, Gfx.a(0xFFFFFFFF, al));
            }
            if (pop < 1f && have) Gfx.rrect(ctx, cx, cy, COVER, COVER, ROUND, Gfx.a(0xFFFFFFFF, al * (1f - pop) * 0.4f));

            int tx = x + pad + COVER + gap;
            marquee(ctx, ti, tx, y + 8, tw, Gfx.a(on ? 0xFFFFFFFF : 0xFFBBBBBB, al), now, scroll.get());
            marquee(ctx, ar, tx, y + 20, tw, Gfx.a(0xFFA0A0A0, al), now, scroll.get());

            if (bar) {   // progress with a knob (smoothed between polls) and the times under it
                int by = y + 33, fill = Math.round((float) (cur / dur) * tw);
                Gfx.rrect(ctx, tx, by, tw, 3, 1, Gfx.a(0xFF343434, al));
                if (fill > 0) Gfx.rrect(ctx, tx, by, Math.max(3, fill), 3, 1, Gfx.a(tint, al));
                if (on) Gfx.rrect(ctx, tx + Math.max(0, Math.min(tw - 5, fill - 2)), by - 1, 5, 5, 2, Gfx.a(0xFFFFFFFF, al));
                U.text(ctx, t0, tx, y + 40, Gfx.a(0xFF8A8A8A, al));
                U.text(ctx, t1, tx + tw - U.width(t1), y + 40, Gfx.a(0xFF8A8A8A, al));
            } else {
                U.text(ctx, on ? "NOW PLAYING" : (have ? "PAUSED" : ""), tx, y + 36, Gfx.a(tint, al));
            }

            if (eq) {   // 4 bars; they dance while playing and sit flat when paused
                int ex = x + w - pad - eqW + 1, base = cy + COVER / 2 + 7;
                double t = now / 1000.0;
                for (int i = 0; i < 4; i++) {
                    int bh = on ? 3 + (int) Math.round((0.3 + 0.7 * Math.abs(Math.sin(t * (3.1 + i * 1.7) + i * 1.9))) * 11) : 3;
                    Gfx.rrect(ctx, ex + i * 4, base - bh, 2, bh, 1, Gfx.a(tint, al));
                }
            }
        }
    }

    // ------------------------------------------------------------------ FakeBanScreen

    /**
     * Disconnects you from the server with a ban message of your choice. The disconnect is real (you leave the server);
     * only the message is made up. The text is generic and does not copy any server's wording.
     */
    public static class FakeBanScreen extends Mod {
        Setting.Mode kind = mode("Type", "Permanent", "Permanent", "Temporary", "IP ban");
        Setting.Mode reason = mode("Reason", "Cheating", "Cheating", "Unfair advantage", "Hacked client", "Griefing", "Spamming");
        Setting.Num days = num("Days (temporary)", 7, 1, 365, 1);
        Setting.Bool stopReconnect = bool("Turn AutoReconnect off first", true);

        public FakeBanScreen() { super("FakeBanScreen", Category.MISC, "Leaves the server and shows a ban message instead of the normal one (turns itself off).");  }

        @Override public void onEnable() {
            enabled = false;   // one-shot, like CoordSnapper
            var h = mc.getNetworkHandler();
            if (h == null || mc.getCurrentServerEntry() == null) { U.chat("Join a multiplayer server first."); return; }
            String head;
            if (kind.is("Temporary")) head = "\u00a7cYou are temporarily banned from this server!\n\n\u00a77Time left: \u00a7f" + days.getInt() + " days";
            else if (kind.is("IP ban")) head = "\u00a7cYour IP address is banned from this server!";
            else head = "\u00a7cYou are permanently banned from this server!";
            Text msg = Text.literal(head + "\n\n\u00a77Reason: \u00a7f" + reason.value);
            if (stopReconnect.get() && ExtraMore.AutoReconnect.INSTANCE != null) ExtraMore.AutoReconnect.INSTANCE.setEnabled(false);
            ExtraMore.relogArmed = false;
            h.getConnection().disconnect(msg);
        }
    }
}
