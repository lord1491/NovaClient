package nova.modules;

import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;
import nova.core.*;
import nova.modules.ClientModules.Theme;

import java.util.*;

public final class HudModules {
    private HudModules() {}

    public static class Watermark extends Mod {
        public Watermark() { super("Watermark", Category.HUD, "Client name and FPS."); }
        @Override public void onHud(DrawContext ctx) {
            String fps = mc.getCurrentFps() + " fps";
            int w = U.width("Nova") + U.width(fps) + 22;
            Gfx.rrect(ctx, 4, 4, w, 16, 5, 0xB0100C0E);
            Gfx.rrect(ctx, 4, 6, 2, 12, 1, Theme.accent());
            U.text(ctx, "Nova", 11, 8, Theme.accent());
            U.text(ctx, fps, 11 + U.width("Nova") + 7, 8, 0xFFD8D0CC);
        }
    }

    public static class ModuleList extends Mod {
        final Map<Mod, Float> anim = new HashMap<>();
        long lastNs = System.nanoTime();
        public ModuleList() { super("ModuleList", Category.HUD, "Lists your enabled modules."); }

        @Override public void onHud(DrawContext ctx) {
            long now = System.nanoTime();
            float dt = Math.min(0.1f, (now - lastNs) / 1e9f);
            lastNs = now;
            float k = 1f - (float) Math.exp(-dt * 14);
            List<Mod> list = new ArrayList<>();
            for (Mod m : ModuleManager.MODULES) {
                if (m.category == Category.HUD || m.category == Category.CLIENT) continue;
                float a = anim.getOrDefault(m, 0f);
                a += ((m.enabled ? 1f : 0f) - a) * k;
                anim.put(m, a);
                if (a > 0.02f) list.add(m);
            }
            list.sort((a, b) -> U.width(b.name) - U.width(a.name));
            int sw = mc.getWindow().getScaledWidth(), y = 4, acc = Theme.accent();
            for (Mod m : list) {
                float a = Gfx.ease(anim.get(m));
                int w = U.width(m.name) + 10;
                int x = sw - w - 4 + Math.round((1 - a) * (w + 8));
                int h = Math.max(1, Math.round(13 * a));
                Gfx.rrect(ctx, x, y, w, h, 4, Gfx.a(0xB0100C0E, a));
                if (h > 8) {
                    Gfx.rrect(ctx, x + w - 2, y + 2, 2, h - 4, 1, Gfx.a(acc, a));
                    U.text(ctx, m.name, x + 5, y + 3, Gfx.a(0xFFFFFFFF, a));
                }
                y += h + 1;
            }
        }
    }

    public static class Coordinates extends Mod {
        public Coordinates() { super("Coordinates", Category.HUD, "Shows your XYZ."); }
        @Override public void onHud(DrawContext ctx) {
            var p = mc.player;
            U.text(ctx, String.format("XYZ %d %d %d", (int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ())),
                    4, mc.getWindow().getScaledHeight() - 12, 0xFFFFFFFF);
        }
    }

    public static class SpeedHud extends Mod {
        Vec3d prev = Vec3d.ZERO; double bps;
        public SpeedHud() { super("SpeedHud", Category.HUD, "Blocks per second."); }
        @Override public void onEnable() { prev = mc.player != null ? U.pos(mc.player) : Vec3d.ZERO; }
        @Override public void onTick() {
            Vec3d now = U.pos(mc.player);
            double dx = now.x - prev.x, dz = now.z - prev.z;
            bps = Math.sqrt(dx * dx + dz * dz) * 20;
            prev = now;
        }
        @Override public void onHud(DrawContext ctx) {
            U.text(ctx, String.format("%.2f b/s", bps), 4, mc.getWindow().getScaledHeight() - 22, 0xFFFFFFFF);
        }
    }

    public static class PingHud extends Mod {
        public PingHud() { super("PingHud", Category.HUD, "Your latency."); }
        @Override public void onHud(DrawContext ctx) {
            var h = mc.getNetworkHandler();
            if (h == null) return;
            var e = h.getPlayerListEntry(mc.player.getUuid());
            if (e != null) U.text(ctx, e.getLatency() + " ms", 4, mc.getWindow().getScaledHeight() - 32, 0xFFFFFFFF);
        }
    }

    public static class ArmorHud extends Mod {
        public ArmorHud() { super("ArmorHud", Category.HUD, "Armor pieces above the hotbar."); }
        @Override public void onHud(DrawContext ctx) {
            int x = mc.getWindow().getScaledWidth() / 2 + 10, y = mc.getWindow().getScaledHeight() - 74;
            HudLayout.track(x, y, 72, 16);   // the item icons are drawn directly, so tell the HUD editor how big this is
            EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
            for (EquipmentSlot s : slots) {
                ItemStack st = mc.player.getEquippedStack(s);
                if (!st.isEmpty()) { ctx.drawItem(st, x, y); ctx.drawStackOverlay(mc.textRenderer, st, x, y); }
                x += 18;
            }
        }
    }

    public static class PlayerRadar extends Mod {
        Setting.Num range = num("Range", 128, 16, 512, 16);
        public PlayerRadar() { super("PlayerRadar", Category.HUD, "Nearby players and distance."); }
        @Override public void onHud(DrawContext ctx) {
            List<PlayerEntity> list = new ArrayList<>();
            for (PlayerEntity p : mc.world.getPlayers()) if (p != mc.player && p.distanceTo(mc.player) <= range.get()) list.add(p);
            list.sort(Comparator.comparingDouble(p -> p.distanceTo(mc.player)));
            int y = 16;
            for (int i = 0; i < Math.min(6, list.size()); i++) {
                var p = list.get(i);
                U.text(ctx, p.getName().getString() + " " + (int) p.distanceTo(mc.player) + "m", 4, y, 0xFFFFFFFF);
                y += 10;
            }
        }
    }

    /**
     * Minimap: a top-down map of the blocks around you drawn from the chunks the client already has (map colours, height
     * shading, water depth). Round or square, optionally turning with your view, with dots for players / hostile mobs / animals.
     * The colours are sampled into a grid every few ticks and drawn as merged horizontal runs under a matrix
     * (translate + rotate + scale), so it stays cheap and scrolls smoothly between samples.
     */
    public static class Minimap extends Mod {
        Setting.Mode shape = mode("Shape", "Round", "Round", "Square");
        Setting.Mode corner = mode("Corner", "Top left", "Top left", "Top right", "Bottom left", "Bottom right");
        Setting.Num size = num("Size (px)", 110, 70, 200, 10);
        Setting.Num radius = num("Radius (blocks)", 40, 16, 96, 4);
        Setting.Bool rotate = bool("Rotate with view", true);
        Setting.Bool shade = bool("Height shading", true);
        Setting.Mode cave = mode("Cave view", "Auto", "Auto", "Off", "Always");   // Auto: under a roof (cave, Nether) show the floor around you instead of the roof
        Setting.Bool players = bool("Players", true), hostiles = bool("Hostile mobs", true), animals = bool("Animals", false);
        Setting.Bool north = bool("North marker", true), coords = bool("Coordinates", true);
        Setting.Num interval = num("Update every (ticks)", 5, 2, 20, 1);

        static final int VOID = 0xFF0B0B0D, WATER = 0x3F76E4;
        int[] col = new int[0];          // ARGB per cell, 0 = chunk not loaded
        int n, gr, ox, oz, tick;         // grid width, grid radius in cells, block the grid is centred on, ticks since the last sample
        Object worldRef;
        boolean valid;

        public Minimap() { super("Minimap", Category.HUD, "Top-down map of the blocks around you with players and mobs (round / square, rotates with your view)."); }

        @Override public void onEnable() { valid = false; }
        @Override public void onDisable() { valid = false; col = new int[0]; worldRef = null; }

        static int scale(int argb, float f) {
            int r = Math.min(255, Math.round(((argb >> 16) & 255) * f)), g = Math.min(255, Math.round(((argb >> 8) & 255) * f)), b = Math.min(255, Math.round((argb & 255) * f));
            return 0xFF000000 | r << 16 | g << 8 | b;
        }

        @Override public void onTick() {
            var p = mc.player; var w = mc.world;
            if (p == null || w == null) return;
            int need = (int) Math.ceil(radius.get() * (rotate.get() && shape.is("Square") ? 1.42 : 1.0)) + 2;   // a rotated square needs the corners too
            boolean moved = valid && (Math.abs(p.getBlockX() - ox) > 3 || Math.abs(p.getBlockZ() - oz) > 3);
            if (!valid || worldRef != w || need != gr || moved || ++tick >= interval.getInt()) { sample(need); tick = 0; }
        }

        /** Colours of every column in the grid: surface block (or the floor below you in cave view), water by depth. */
        void sample(int r) {
            var p = mc.player; var w = mc.world;
            gr = r; n = 2 * r + 1; worldRef = w; valid = true;
            ox = p.getBlockX(); oz = p.getBlockZ();
            int py = (int) Math.floor(p.getY());
            if (col.length != n * n) col = new int[n * n];
            int[] hts = new int[n * n];
            boolean[] wet = new boolean[n * n];
            boolean caveMode = cave.is("Always") || (cave.is("Auto") && w.getTopY(Heightmap.Type.MOTION_BLOCKING, ox, oz) > py + 2);
            int floorY = caveMode ? Math.max(w.getBottomY(), py - 16) : w.getBottomY();
            BlockPos.Mutable pos = new BlockPos.Mutable();
            for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) {
                int x = ox - r + i, z = oz - r + j, k = j * n + i;
                if (w.getChunkManager().getWorldChunk(x >> 4, z >> 4) == null) { col[k] = 0; hts[k] = py; continue; }
                int top = w.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1;
                int y = caveMode ? Math.min(top, py + 3) : top, color = VOID;
                for (; y >= floorY; y--) {
                    pos.set(x, y, z);
                    BlockState st = w.getBlockState(pos);
                    if (st.getFluidState().isIn(FluidTags.WATER)) {
                        int d = 1;
                        for (int yy = y - 1; yy >= floorY && d < 12; yy--) {
                            pos.set(x, yy, z);
                            if (!w.getBlockState(pos).getFluidState().isIn(FluidTags.WATER)) break;
                            d++;
                        }
                        color = scale(0xFF000000 | WATER, MathHelper.clamp(1.05f - d * 0.05f, 0.45f, 1f));
                        wet[k] = true;
                        break;
                    }
                    MapColor m = st.getMapColor(w, pos);
                    if (m == MapColor.CLEAR) continue;                      // air, glass, ...: look further down
                    color = 0xFF000000 | m.color;
                    break;
                }
                hts[k] = y;
                col[k] = color;
            }
            if (!shade.get()) return;
            for (int j = 1; j < n; j++) for (int i = 1; i < n; i++) {   // brighter when higher than the block to the north / west, darker when lower
                int k = j * n + i;
                if (col[k] == 0 || wet[k] || col[k - 1] == 0 || col[k - n] == 0 || col[k] == VOID) continue;
                int d = (hts[k] - hts[k - n]) + (hts[k] - hts[k - 1]);
                col[k] = scale(col[k], MathHelper.clamp(1f + d * 0.07f, 0.7f, 1.25f));
            }
        }

        @Override public void onHud(DrawContext ctx) {
            var p = mc.player; var w = mc.world;
            if (p == null || w == null || !valid || worldRef != w) return;
            int sz = size.getInt(), pad = 3, textH = coords.get() ? 13 : 0, m = 6;
            double rad = radius.get();
            boolean round = shape.is("Round");
            int totalW = sz + pad * 2, totalH = sz + pad * 2 + textH;
            int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
            int x = corner.value.endsWith("right") ? sw - totalW - m : m;
            int y = corner.value.startsWith("Top") ? 26 : sh - totalH - m - 40;   // bottom corners sit above the module toggle popups
            int mx = x + pad, my = y + pad, cxp = mx + sz / 2, cyp = my + sz / 2, acc = Theme.accent();

            // frame: panel + square map, or a circle with the coordinates in a small chip under it
            if (round) {
                Gfx.rrect(ctx, mx - 1, my - 1, sz + 2, sz + 2, (sz + 2) / 2, Gfx.a(acc, 0.85f));
                Gfx.rrect(ctx, mx, my, sz, sz, sz / 2, VOID);
            } else {
                Gfx.rrect(ctx, x, y, totalW, totalH, 5, 0xCC141414);
                Gfx.rrect(ctx, mx - 1, my - 1, sz + 2, sz + 2, 3, Gfx.a(acc, 0.85f));
                Gfx.rrect(ctx, mx, my, sz, sz, 3, VOID);
            }

            double px = p.getX(), pz = p.getZ();
            float s = (float) (sz / (2.0 * rad));                                   // pixels per block
            boolean rot = rotate.get();
            float a = rot ? (float) (Math.PI - Math.toRadians(p.getYaw())) : 0f;    // turns your facing direction to "up"
            ctx.enableScissor(mx, my, mx + sz, my + sz);
            var st = ctx.getMatrices();
            st.pushMatrix();
            st.translate(cxp, cyp);
            if (rot) st.rotate(a);
            st.scale(s, s);
            st.translate((float) (ox - gr - px), (float) (oz - gr - pz));           // cell (0,0) sits at the grid corner, relative to you
            double r2 = rad * rad;
            for (int j = 0; j < n; j++) {
                int i0 = 0, i1 = n - 1;
                if (round) {                                                       // only the columns inside the circle
                    double ry = (oz - gr + j + 0.5) - pz, h2 = r2 - ry * ry;
                    if (h2 <= 0) continue;
                    double hc = Math.sqrt(h2);
                    i0 = Math.max(0, (int) Math.ceil(px - hc - (ox - gr) - 0.5));
                    i1 = Math.min(n - 1, (int) Math.floor(px + hc - (ox - gr) - 0.5));
                }
                int i = i0;
                while (i <= i1) {                                                  // merge runs of the same colour into one fill
                    int c = col[j * n + i], e = i + 1;
                    while (e <= i1 && col[j * n + e] == c) e++;
                    if (c != 0) ctx.fill(i, j, e, j + 1, c);
                    i = e;
                }
            }
            st.popMatrix();

            // dots (same rotation as the map, drawn at pixel size)
            double ca = Math.cos(a), sa = Math.sin(a), lim = sz / 2.0 - 3;
            if (players.get() || hostiles.get() || animals.get()) {
                for (Entity e : w.getEntities()) {
                    if (e == p || !(e instanceof LivingEntity le) || !le.isAlive()) continue;
                    int dc;
                    boolean pl = e instanceof PlayerEntity;
                    if (e instanceof PlayerEntity o) {
                        if (!players.get() || o.isSpectator()) continue;
                        dc = ExtraCombat.FriendGuard.isFriend(o) ? 0xFF55FF55 : 0xFFFFFFFF;
                    } else if (e instanceof Monster) {
                        if (!hostiles.get()) continue;
                        dc = 0xFFFF4040;
                    } else if (e instanceof AnimalEntity) {
                        if (!animals.get()) continue;
                        dc = 0xFFFFD34D;
                    } else continue;
                    double rx = e.getX() - px, rz = e.getZ() - pz;
                    double dx = (ca * rx - sa * rz) * s, dy = (sa * rx + ca * rz) * s;
                    boolean out = round ? Math.hypot(dx, dy) > lim : Math.abs(dx) > lim || Math.abs(dy) > lim;
                    if (out) {
                        if (!pl) continue;                                         // players stick to the edge, mobs just vanish
                        if (round) { double k = lim / Math.hypot(dx, dy); dx *= k; dy *= k; }
                        else { dx = MathHelper.clamp(dx, -lim, lim); dy = MathHelper.clamp(dy, -lim, lim); }
                    }
                    int ex = cxp + (int) Math.round(dx), ey = cyp + (int) Math.round(dy);
                    ctx.fill(ex - 2, ey - 2, ex + 3, ey + 3, 0xFF000000);
                    ctx.fill(ex - 1, ey - 1, ex + 2, ey + 2, dc);
                }
            }
            // you: white dot with a coloured nose pointing where you look
            double yaw = Math.toRadians(p.getYaw());
            double fx = rot ? 0 : -Math.sin(yaw), fy = rot ? -1 : Math.cos(yaw);
            int nx = cxp + (int) Math.round(fx * 5), ny = cyp + (int) Math.round(fy * 5);
            ctx.fill(cxp - 2, cyp - 2, cxp + 3, cyp + 3, 0xFF000000);
            ctx.fill(cxp - 1, cyp - 1, cxp + 2, cyp + 2, 0xFFFFFFFF);
            ctx.fill(nx - 1, ny - 1, nx + 1, ny + 1, acc);
            ctx.disableScissor();

            if (north.get()) {
                double r = sz / 2.0 - 7;
                int nxm = cxp + (int) Math.round(Math.sin(a) * r), nym = cyp + (int) Math.round(-Math.cos(a) * r);
                Gfx.rrect(ctx, nxm - 5, nym - 5, 10, 10, 5, 0xB0000000);
                U.text(ctx, "N", nxm - U.width("N") / 2, nym - 4, 0xFFFF5555);
            }
            if (coords.get()) {
                String t = (int) Math.floor(px) + ", " + (int) Math.floor(p.getY()) + ", " + (int) Math.floor(pz);
                int tw = U.width(t), ty = my + sz + pad + 1;
                if (round) Gfx.rrect(ctx, cxp - tw / 2 - 4, ty - 2, tw + 8, 12, 4, 0xB0141414);
                U.text(ctx, t, cxp - tw / 2, ty, 0xFFFFFFFF);
            }
        }
    }

    public static class PotionHud extends Mod {
        public PotionHud() { super("PotionHud", Category.HUD, "Active status effects."); }
        @Override public void onHud(DrawContext ctx) {
            int y = mc.getWindow().getScaledHeight() / 2 - 20;
            for (StatusEffectInstance e : mc.player.getStatusEffects()) {
                int secs = e.getDuration() / 20;
                U.text(ctx, e.getEffectType().value().getName().getString() + " " + (e.getAmplifier() + 1)
                        + " " + (secs / 60) + ":" + String.format("%02d", secs % 60), 4, y, 0xFFFFFFFF);
                y += 10;
            }
        }
    }
}
