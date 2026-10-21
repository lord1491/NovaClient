package nova.modules;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;
import nova.core.*;

import java.util.*;
import java.util.regex.Pattern;

/** Second batch of modules: reconnect, staff, spawner tools, region map, block ESP and the mixin based ones. */
public final class ExtraMore {
    private ExtraMore() {}

    // ------------------------------------------------------------------ reconnect / relog

    static ServerInfo lastServer;
    static int reconnectTimer = -1, tries, relogTicks = 20;
    static boolean relogArmed;

    /** Runs every client tick, also on the title / disconnect screens (ModuleManager.tick stops outside a world). */
    public static void offlineTick(MinecraftClient c) {
        if (c.world != null && c.getCurrentServerEntry() != null) {
            lastServer = c.getCurrentServerEntry();
            tries = 0; reconnectTimer = -1;
            return;
        }
        if (!(c.currentScreen instanceof DisconnectedScreen) || lastServer == null) { reconnectTimer = -1; return; }
        AutoReconnect ar = AutoReconnect.INSTANCE;
        boolean auto = ar != null && ar.enabled && tries < ar.maxTries.getInt();
        if (!relogArmed && !auto) return;
        if (reconnectTimer < 0) reconnectTimer = relogArmed ? relogTicks : ar.delay.getInt() * 20;
        if (--reconnectTimer > 0) return;
        reconnectTimer = -1;
        relogArmed = false;
        tries++;
        ConnectScreen.connect(new TitleScreen(), c, ServerAddress.parse(lastServer.address), lastServer, false, null);
    }

    public static class AutoReconnect extends Mod {
        static AutoReconnect INSTANCE;
        Setting.Num delay = num("Delay (s)", 5, 1, 60, 1), maxTries = num("Max tries", 5, 1, 50, 1);

        public AutoReconnect() { super("AutoReconnect", Category.MISC, "Rejoins the last server when you get disconnected."); INSTANCE = this; }
    }

    public static class AutoRelog extends Mod {
        Setting.Num yLevel = num("Y level", -64, -64, 320, 1), delay = num("Delay (s)", 1, 1, 30, 1);

        public AutoRelog() { super("AutoRelog", Category.MISC, "Disconnects and rejoins when you fall below the Y level."); }

        @Override public void onTick() {
            if (mc.player.getY() >= yLevel.get() || mc.getNetworkHandler() == null || mc.getCurrentServerEntry() == null) return;
            lastServer = mc.getCurrentServerEntry();
            relogTicks = delay.getInt() * 20;
            relogArmed = true;
            mc.getNetworkHandler().getConnection().disconnect(Text.literal("Nova AutoRelog"));
        }
    }

    // ------------------------------------------------------------------ staff list

    static final Pattern STAFF = Pattern.compile("\\b(owner|admin|administrator|mod|moderator|helper|staff|dev|developer|manager|sr\\.?\\s?mod|jr\\.?\\s?mod|support)\\b",
            Pattern.CASE_INSENSITIVE);

    static List<String> staffOnline() {
        List<String> out = new ArrayList<>();
        var h = MinecraftClient.getInstance().getNetworkHandler();
        if (h == null) return out;
        for (PlayerListEntry e : h.getPlayerList()) {
            Text dn = e.getDisplayName();
            if (dn == null) continue;
            String s = dn.getString();
            if (STAFF.matcher(s).find()) out.add(s);
        }
        return out;
    }

    public static class StaffList extends Mod {
        Setting.Bool panel = bool("Show list", true), alert = bool("Chat alert on join", true);
        List<String> shown = new ArrayList<>();
        Set<String> known = new HashSet<>();
        boolean first = true;
        int t;

        public StaffList() { super("StaffList", Category.MISC, "Finds staff by rank words in the tab list (Admin, Mod, Helper, ...)."); }

        @Override public void onEnable() { known.clear(); first = true; t = 0; }

        @Override public void onTick() {
            if (t++ % 20 != 0) return;
            shown = staffOnline();
            Set<String> now = new HashSet<>(shown);
            if (alert.get() && !first) for (String s : now) if (!known.contains(s)) U.chat("Staff online: " + s);
            known = now; first = false;
        }

        @Override public void onHud(DrawContext ctx) {
            if (!panel.get() || shown.isEmpty()) return;
            int w = U.width("Staff (" + shown.size() + ")");
            for (String s : shown) w = Math.max(w, U.width(s));
            w += 12;
            int h = 16 + shown.size() * 11;
            int x = mc.getWindow().getScaledWidth() - w - 6, y = 40;
            Gfx.rrect(ctx, x, y, w, h, 5, 0xCC141414);
            U.text(ctx, "Staff (" + shown.size() + ")", x + 6, y + 4, ClientModules.Theme.accent());
            for (int i = 0; i < shown.size(); i++) U.text(ctx, shown.get(i), x + 6, y + 16 + i * 11, 0xFFFFFFFF);
        }
    }

    // ------------------------------------------------------------------ spawners

    static List<BlockEntity> blockEntities(int radius) {
        List<BlockEntity> out = new ArrayList<>();
        var mc = MinecraftClient.getInstance();
        ChunkPos cp = mc.player.getChunkPos();
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            WorldChunk c = mc.world.getChunkManager().getWorldChunk(U.cx(cp) + dx, U.cz(cp) + dz);
            if (c != null) out.addAll(c.getBlockEntities().values());
        }
        return out;
    }

    static boolean isSpawner(BlockEntity be) { return be instanceof MobSpawnerBlockEntity || be instanceof TrialSpawnerBlockEntity; }

    /** Stops you from breaking a spawner by accident. */
    public static class SpawnerProtect extends Mod {
        Setting.Bool sneak = bool("Allow while sneaking", true);

        public SpawnerProtect() { super("SpawnerProtect", Category.WORLD, "Cancels mining when you aim at a spawner (survival; creative breaks instantly)."); }

        @Override public void onTick() {
            if (!mc.options.attackKey.isPressed() || (sneak.get() && U.sneak())) return;
            if (!(mc.crosshairTarget instanceof BlockHitResult h) || h.getType() != HitResult.Type.BLOCK) return;
            var s = mc.world.getBlockState(h.getBlockPos());
            if (s.isOf(Blocks.SPAWNER) || s.isOf(Blocks.TRIAL_SPAWNER)) {
                mc.options.attackKey.setPressed(false);
                mc.interactionManager.cancelBlockBreaking();
            }
        }
    }

    public static class SpawnerNotifier extends Mod {
        Setting.Num radius = num("Radius (chunks)", 8, 1, 16, 1);
        Setting.Bool chat = bool("Chat", true), sound = bool("Sound", true);
        final Set<Long> known = new HashSet<>();
        Object worldRef;
        int t;

        public SpawnerNotifier() { super("SpawnerNotifier", Category.WORLD, "Tells you when a new spawner shows up in loaded chunks."); }

        @Override public void onEnable() { known.clear(); worldRef = null; t = 0; }

        @Override public void onTick() {
            if (t++ % 20 != 0) return;
            if (worldRef != mc.world) { known.clear(); worldRef = mc.world; }
            List<BlockPos> fresh = new ArrayList<>();
            for (BlockEntity be : blockEntities(radius.getInt()))
                if (isSpawner(be) && known.add(be.getPos().asLong())) fresh.add(be.getPos());
            if (fresh.isEmpty()) return;
            if (chat.get()) {
                if (fresh.size() <= 5) for (BlockPos p : fresh) U.chat("Spawner at " + p.getX() + " " + p.getY() + " " + p.getZ());
                else U.chat(fresh.size() + " new spawners found (first: " + fresh.get(0).getX() + " " + fresh.get(0).getY() + " " + fresh.get(0).getZ() + ")");
            }
            if (sound.get()) mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_PLING, 1.2f));
        }
    }

    public static class SpawnerNametags extends Mod {
        Setting.Num range = num("Range", 96, 8, 200, 8);
        Setting.Bool activation = bool("Only inside activation radius (16)", false);
        List<BlockPos> spawners = new ArrayList<>();
        int t;

        public SpawnerNametags() { super("SpawnerNametags", Category.RENDER, "Labels spawners through walls; red = inside the 16 block activation radius."); }

        @Override public void onTick() {
            if (t++ % 20 != 0) return;
            List<BlockPos> out = new ArrayList<>();
            for (BlockEntity be : blockEntities(8)) if (isSpawner(be)) out.add(be.getPos());
            spawners = out;
        }

        @Override public void onHud(DrawContext ctx) {
            Proj pr = Proj.begin();
            if (pr == null) return;
            int shown = 0;
            for (BlockPos p : spawners) {
                Vec3d c = Vec3d.ofCenter(p);
                double d = c.distanceTo(pr.eye);
                if (d > range.get() || (activation.get() && d > 16) || shown >= 24) continue;
                double[] s = pr.project(c.add(0, 0.9, 0));
                if (s == null) continue;
                shown++;
                String txt = "Spawner " + (int) d + "m";
                int w = U.width(txt) + 10, x = (int) Math.round(s[0]) - w / 2, y = (int) Math.round(s[1]) - 7;
                Gfx.rrect(ctx, x, y, w, 13, 4, 0xCC141414);
                Gfx.rrect(ctx, x + 2, y + 3, 2, 7, 1, d <= 16 ? 0xFFFF4D4D : 0xFF9AA0AA);
                U.text(ctx, txt, x + 7, y + 3, 0xFFFFFFFF);
            }
        }
    }

    // ------------------------------------------------------------------ block ESP

    public static class BlockESP extends Mod {
        Setting.Num range = num("Range", 64, 8, 160, 4), radius = num("Radius (chunks)", 6, 1, 12, 1);
        Setting.Bool spawners = bool("Spawners", true), vaults = bool("Vaults", true), beacons = bool("Beacons", true),
                enchant = bool("Enchanting tables", false), command = bool("Command blocks", true), shulkers = bool("Shulker boxes", false),
                tracers = bool("Tracers", false);
        record Hit(BlockPos pos, int color) {}
        List<Hit> hits = new ArrayList<>();
        int t;

        public BlockESP() { super("BlockESP", Category.RENDER, "Outlines special blocks (spawners, vaults, beacons, ...) through walls."); }

        @Override public void onEnable() { hits.clear(); t = 0; }

        @Override public void onTick() {
            if (t++ % 20 != 0) return;
            List<Hit> out = new ArrayList<>();
            for (BlockEntity be : blockEntities(radius.getInt())) {
                int col = 0;
                if (isSpawner(be)) col = spawners.get() ? 0xFFFF4D4D : 0;
                else if (be instanceof VaultBlockEntity) col = vaults.get() ? 0xFF4DE8FF : 0;
                else if (be instanceof BeaconBlockEntity) col = beacons.get() ? 0xFFFFE04D : 0;
                else if (be instanceof EnchantingTableBlockEntity) col = enchant.get() ? 0xFFB06BFF : 0;
                else if (be instanceof CommandBlockBlockEntity) col = command.get() ? 0xFFFF9A55 : 0;
                else if (be instanceof ShulkerBoxBlockEntity) col = shulkers.get() ? 0xFFFF6BD5 : 0;
                if (col != 0) out.add(new Hit(be.getPos(), col));
            }
            hits = out;
        }

        @Override public void onHud(DrawContext ctx) {
            Proj pr = Proj.begin();
            if (pr == null) return;
            double rg = range.get() * range.get();
            List<Hit> near = new ArrayList<>();
            for (Hit h : hits) if (Vec3d.ofCenter(h.pos()).squaredDistanceTo(pr.eye) <= rg) near.add(h);
            near.sort(Comparator.comparingDouble(h -> Vec3d.ofCenter(h.pos()).squaredDistanceTo(pr.eye)));
            double sx = pr.sw / 2.0, sy = pr.sh;
            for (int i = 0; i < Math.min(48, near.size()); i++) {
                Hit h = near.get(i);
                pr.wire(ctx, new Box(h.pos()), h.color());
                if (tracers.get()) {
                    double[] s = pr.screen(Vec3d.ofCenter(h.pos()));
                    Gfx.line(ctx, sx, sy, s[0], s[1], Gfx.a(h.color(), 0.9f));
                }
            }
        }
    }

    // ------------------------------------------------------------------ region map

    public static class RegionMap extends Mod {
        Setting.Num cell = num("Cell size (px)", 3, 2, 6, 1);
        Setting.Mode corner = mode("Corner", "Bottom left", "Bottom left", "Top left", "Bottom right");
        Setting.Bool coords = bool("Show coordinates", true);
        static final int R = 16;
        final boolean[] loaded = new boolean[(2 * R + 1) * (2 * R + 1)];
        int t;

        public RegionMap() { super("RegionMap", Category.HUD, "Loaded chunks around you with the 32x32 region borders (r.X.Z file)."); }

        @Override public void onTick() {
            if (t++ % 10 != 0) return;
            ChunkPos cp = mc.player.getChunkPos();
            for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++)
                loaded[(dz + R) * (2 * R + 1) + (dx + R)] = mc.world.getChunkManager().getWorldChunk(U.cx(cp) + dx, U.cz(cp) + dz) != null;
        }

        @Override public void onHud(DrawContext ctx) {
            var p = mc.player;
            ChunkPos cp = p.getChunkPos();
            int cx = U.cx(cp), cz = U.cz(cp), n = 2 * R + 1, cs = cell.getInt();
            int w = n * cs + 8, h = n * cs + 8 + (coords.get() ? 12 : 0);
            int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
            int x = corner.is("Bottom right") ? sw - w - 6 : 6;
            int y = corner.is("Top left") ? 6 : sh - h - 6;
            Gfx.rrect(ctx, x, y, w, h, 5, 0xCC141414);
            int gx = x + 4, gy = y + 4, acc = ClientModules.Theme.accent();
            for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
                int px = gx + (dx + R) * cs, py = gy + (dz + R) * cs;
                boolean on = loaded[(dz + R) * n + (dx + R)];
                ctx.fill(px, py, px + cs - 1, py + cs - 1, on ? 0xFF2E8A58 : 0xFF2A2A2E);
                if (Math.floorMod(cx + dx, 32) == 0) ctx.fill(px - 1, py, px, py + cs, acc);
                if (Math.floorMod(cz + dz, 32) == 0) ctx.fill(px, py - 1, px + cs, py, acc);
            }
            int ppx = gx + R * cs, ppy = gy + R * cs;
            ctx.fill(ppx, ppy, ppx + cs - 1, ppy + cs - 1, 0xFFFFFFFF);
            if (coords.get()) {
                String s = "r." + Math.floorDiv(cx, 32) + "." + Math.floorDiv(cz, 32) + "  chunk " + Math.floorMod(cx, 32) + "," + Math.floorMod(cz, 32);
                U.text(ctx, s, x + 4, y + h - 11, 0xFFD8D8D8);
            }
        }
    }

    // ------------------------------------------------------------------ mixin based modules

    /** Camera leaves your body; WASD / Space / Shift fly it (Ctrl = faster). Your player stays where it is. */
    public static class Freecam extends Mod {
        Setting.Num speed = num("Speed (blocks/s)", 8, 1, 40, 1);
        Setting.Bool freeze = bool("Freeze player", true);
        Object worldRef;

        public Freecam() { super("Freecam", Category.RENDER, "Detached camera. Needs the Nova mixins (see README)."); }

        private void init() {
            var p = mc.player;
            Vec3d e = p.getEyePos();
            CamState.fx = e.x; CamState.fy = e.y; CamState.fz = e.z;
            CamState.yaw = p.getYaw(); CamState.pitch = p.getPitch();
            CamState.begin();
            worldRef = mc.world;
        }

        @Override public void onEnable() { if (mc.player != null) init(); CamState.freecam = true; }
        @Override public void onDisable() { CamState.freecam = false; }

        @Override public void onTick() {
            if (worldRef != mc.world) init();
            CamState.speed = speed.get();
            if (freeze.get()) {
                mc.options.forwardKey.setPressed(false); mc.options.backKey.setPressed(false);
                mc.options.leftKey.setPressed(false); mc.options.rightKey.setPressed(false);
                mc.options.jumpKey.setPressed(false); mc.options.sneakKey.setPressed(false);
                mc.options.sprintKey.setPressed(false);
            }
        }
    }

    /** Look around without turning the player. */
    public static class Freelook extends Mod {
        public Freelook() { super("Freelook", Category.RENDER, "Turns the camera, not your character. Needs the Nova mixins."); }

        @Override public void onEnable() {
            if (mc.player != null) { CamState.yaw = mc.player.getYaw(); CamState.pitch = mc.player.getPitch(); CamState.begin(); }
            CamState.freelook = true;
        }
        @Override public void onDisable() { CamState.freelook = false; }
    }

    public static class SlowSwing extends Mod {
        static SlowSwing INSTANCE;
        Setting.Num speed = num("Speed", 0.15, 0.05, 1, 0.05);

        public SlowSwing() { super("SlowSwing", Category.RENDER, "Slower hand swing animation. Needs the Nova mixins."); INSTANCE = this; }

        public static boolean active() { return INSTANCE != null && INSTANCE.enabled; }
        public static int duration(int original) { return Math.max(1, (int) Math.round(original / INSTANCE.speed.get())); }
    }
}
