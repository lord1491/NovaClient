package nova.modules;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import nova.core.*;

import java.util.*;

public final class RenderModules {
    private RenderModules() {}

    static final int C_PLAYER = 0xFFFF5A5A, C_HOSTILE = 0xFFFFA23A, C_PASSIVE = 0xFF5CFF8A, C_ITEM = 0xFF55D8FF;

    static int colorFor(Entity e, boolean players, boolean hostiles, boolean passives, boolean items) {
        if (e == mcPlayer() || e instanceof ArmorStandEntity || DefenseModules.AntiBot.isBot(e)) return 0;
        if (e instanceof ItemEntity) return items ? C_ITEM : 0;
        if (!(e instanceof LivingEntity le) || !le.isAlive()) return 0;
        if (e instanceof PlayerEntity) return players ? (ExtraCombat.FriendGuard.isFriend(e) ? 0xFF4D8DFF : C_PLAYER) : 0;
        if (e instanceof Monster) return hostiles ? C_HOSTILE : 0;
        return passives ? C_PASSIVE : 0;
    }

    private static String one(float v) { return String.valueOf(Math.round(v * 10f) / 10f); }

    private static PlayerEntity mcPlayer() { return net.minecraft.client.MinecraftClient.getInstance().player; }

    public static class ESP extends Mod {
        Setting.Num range = num("Range", 96, 8, 256, 8);
        Setting.Bool players = bool("Players", true), hostiles = bool("Hostiles", true), passives = bool("Passives", false), items = bool("Items", false);
        Setting.Mode style = mode("Style", "Box", "Box", "Corners", "Filled", "3D");
        Setting.Bool health = bool("Health bar", true), names = bool("Names", true), distance = bool("Distance", false);

        public ESP() { super("ESP", Category.RENDER, "Boxes with health bars and names around entities, through walls."); }

        @Override public void onHud(DrawContext ctx) {
            Proj pr = Proj.begin();
            if (pr == null) return;
            for (Entity e : mc.world.getEntities()) {
                int col = colorFor(e, players.get(), hostiles.get(), passives.get(), items.get());
                if (col == 0 || mc.player.distanceTo(e) > range.get()) continue;
                float[] r = pr.rect(Proj.lerpBox(e));
                if (r == null) continue;
                int x1 = Math.round(r[0]), y1 = Math.round(r[1]), x2 = Math.round(r[2]), y2 = Math.round(r[3]);
                if (x2 - x1 < 2) { x1--; x2++; }
                switch (style.value) {
                    case "Corners" -> corners(ctx, x1, y1, x2, y2, col);
                    case "3D" -> pr.wire(ctx, Proj.lerpBox(e), col);
                    case "Filled" -> { ctx.fill(x1, y1, x2, y2, Gfx.a(col, 0.18f)); box(ctx, x1, y1, x2, y2, col); }
                    default -> box(ctx, x1, y1, x2, y2, col);
                }
                if (health.get() && e instanceof LivingEntity le) {
                    float hp = Math.max(0f, Math.min(1f, le.getHealth() / Math.max(1f, le.getMaxHealth())));
                    int bx = x1 - 5, bh = y2 - y1;
                    ctx.fill(bx - 1, y1 - 1, bx + 3, y2 + 1, 0xAA000000);
                    int fh = Math.round(bh * hp);
                    ctx.fill(bx, y2 - fh, bx + 2, y2, Gfx.mix(0xFFFF3B3B, 0xFF4DFF6A, hp));
                }
                int cx = (x1 + x2) / 2;
                if (names.get()) {
                    String n = e instanceof ItemEntity ie ? ie.getStack().getName().getString() + (ie.getStack().getCount() > 1 ? " x" + ie.getStack().getCount() : "")
                            : e.getName().getString();
                    int nw = U.width(n);
                    Gfx.rrect(ctx, cx - nw / 2 - 3, y1 - 14, nw + 6, 12, 4, 0x99000000);
                    U.text(ctx, n, cx - nw / 2, y1 - 12, 0xFFFFFFFF);
                }
                if (distance.get()) {
                    String d = (int) mc.player.distanceTo(e) + "m";
                    U.text(ctx, d, cx - U.width(d) / 2, y2 + 3, 0xFFD8D8D8);
                }
            }
        }

        static void box(DrawContext c, int x1, int y1, int x2, int y2, int col) {
            Gfx.outline(c, x1 - 1, y1 - 1, x2 + 1, y2 + 1, 0xAA000000);
            Gfx.outline(c, x1 + 1, y1 + 1, x2 - 1, y2 - 1, 0x66000000);
            Gfx.outline(c, x1, y1, x2, y2, col);
        }

        static void seg(DrawContext c, int xa, int ya, int xb, int yb, int col) {
            c.fill(Math.min(xa, xb), Math.min(ya, yb), Math.max(xa, xb) + 1, Math.max(ya, yb) + 1, col);
        }

        static void corners(DrawContext c, int x1, int y1, int x2, int y2, int col) {
            int l = Math.max(3, Math.min(x2 - x1, y2 - y1) / 4);
            int[][] cs = {{x1, y1, 1, 1}, {x2, y1, -1, 1}, {x1, y2, 1, -1}, {x2, y2, -1, -1}};
            for (int[] k : cs) {
                int x = k[0], y = k[1], dx = k[2], dy = k[3];
                seg(c, x - dx, y - dy, x + dx * l, y - dy, 0xAA000000);
                seg(c, x - dx, y - dy, x - dx, y + dy * l, 0xAA000000);
                seg(c, x, y, x + dx * l, y, col);
                seg(c, x, y, x, y + dy * l, col);
            }
        }
    }

    public static class Tracers extends Mod {
        Setting.Num range = num("Range", 128, 8, 256, 8);
        Setting.Bool players = bool("Players", true), hostiles = bool("Hostiles", false), passives = bool("Passives", false), items = bool("Items", false);
        Setting.Mode from = mode("From", "Bottom", "Bottom", "Crosshair");
        Setting.Mode target = mode("Aim at", "Body", "Body", "Feet");

        public Tracers() { super("Tracers", Category.RENDER, "Lines from you to entities."); }

        @Override public void onHud(DrawContext ctx) {
            Proj pr = Proj.begin();
            if (pr == null) return;
            double sx = pr.sw / 2.0, sy = from.is("Bottom") ? pr.sh : pr.sh / 2.0;
            for (Entity e : mc.world.getEntities()) {
                int col = colorFor(e, players.get(), hostiles.get(), passives.get(), items.get());
                if (col == 0 || mc.player.distanceTo(e) > range.get()) continue;
                Vec3d p = Proj.lerpPos(e).add(0, target.is("Body") ? e.getHeight() * 0.5 : 0.05, 0);
                double[] s = pr.screen(p);
                Gfx.line(ctx, sx + 1, sy - 1, s[0] + 1, s[1] + 1, 0x88000000);
                Gfx.line(ctx, sx, sy, s[0], s[1], Gfx.a(col, 0.95f));
            }
        }
    }

    public static class StorageESP extends Mod {
        Setting.Num range = num("Range", 64, 8, 160, 4), radius = num("Radius (chunks)", 6, 1, 12, 1);
        Setting.Bool chests = bool("Chests", true), ender = bool("Ender chests", true), shulkers = bool("Shulkers", true),
                barrels = bool("Barrels", true), other = bool("Hoppers / furnaces", false), spawners = bool("Spawners", true);
        record Hit(BlockPos pos, int color) {}
        List<Hit> hits = new ArrayList<>();
        int t;

        public StorageESP() { super("StorageESP", Category.RENDER, "Outlines containers through walls (only what the server sent you)."); }

        @Override public void onEnable() { hits.clear(); t = 0; }

        @Override public void onTick() {
            if (t++ % 20 != 0) return;
            List<Hit> found = new ArrayList<>();
            ChunkPos cp = mc.player.getChunkPos();
            int r = radius.getInt();
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                WorldChunk c = mc.world.getChunkManager().getWorldChunk(U.cx(cp) + dx, U.cz(cp) + dz);
                if (c == null) continue;
                for (BlockEntity be : c.getBlockEntities().values()) {
                    int col = 0;
                    if (be instanceof ChestBlockEntity || be instanceof TrappedChestBlockEntity) col = chests.get() ? 0xFFFFA23A : 0;
                    else if (be instanceof EnderChestBlockEntity) col = ender.get() ? 0xFFB06BFF : 0;
                    else if (be instanceof ShulkerBoxBlockEntity) col = shulkers.get() ? 0xFFFF6BD5 : 0;
                    else if (be instanceof BarrelBlockEntity) col = barrels.get() ? 0xFFD2A05A : 0;
                    else if (be instanceof MobSpawnerBlockEntity) col = spawners.get() ? 0xFFFF4D4D : 0;
                    else if (WorldModules.isStorage(be)) col = other.get() ? 0xFF9AA0AA : 0;
                    if (col != 0) found.add(new Hit(be.getPos(), col));
                }
            }
            hits = found;
        }

        @Override public void onHud(DrawContext ctx) {
            Proj pr = Proj.begin();
            if (pr == null) return;
            double rg = range.get() * range.get();
            List<Hit> near = new ArrayList<>();
            for (Hit h : hits) if (Vec3d.ofCenter(h.pos()).squaredDistanceTo(pr.eye) <= rg) near.add(h);
            near.sort(Comparator.comparingDouble(h -> Vec3d.ofCenter(h.pos()).squaredDistanceTo(pr.eye)));
            for (int i = 0; i < Math.min(48, near.size()); i++) pr.wire(ctx, new Box(near.get(i).pos()), near.get(i).color());
        }
    }

    public static class OreESP extends Mod {
        Setting.Num radius = num("Radius (chunks)", 4, 1, 8, 1), range = num("Draw range", 48, 8, 128, 4), perTick = num("Chunks / tick", 2, 1, 8, 1);
        Setting.Bool diamond = bool("Diamond", true), debris = bool("Ancient debris", true), emerald = bool("Emerald", true),
                gold = bool("Gold", true), iron = bool("Iron", false), redstone = bool("Redstone", false),
                lapis = bool("Lapis", false), coal = bool("Coal", false), copper = bool("Copper", false);
        record Hit(BlockPos pos, int color) {}
        final Map<Long, List<Hit>> byChunk = new HashMap<>();
        final ArrayDeque<ChunkPos> queue = new ArrayDeque<>();

        public OreESP() { super("OreESP", Category.RENDER, "Outlines ores in chunks the server sent you (anti-xray test)."); }

        @Override public void onEnable() { byChunk.clear(); queue.clear(); }
        @Override public void onDisable() { byChunk.clear(); queue.clear(); }

        int colorOf(BlockState s) {
            if (s.isAir()) return 0;
            if (diamond.get() && s.isIn(BlockTags.DIAMOND_ORES)) return 0xFF4DE8FF;
            if (debris.get() && s.isOf(Blocks.ANCIENT_DEBRIS)) return 0xFFB5683A;
            if (emerald.get() && s.isIn(BlockTags.EMERALD_ORES)) return 0xFF4DFF7A;
            if (gold.get() && s.isIn(BlockTags.GOLD_ORES)) return 0xFFFFE04D;
            if (iron.get() && s.isIn(BlockTags.IRON_ORES)) return 0xFFE0C8B0;
            if (redstone.get() && s.isIn(BlockTags.REDSTONE_ORES)) return 0xFFFF4D4D;
            if (lapis.get() && s.isIn(BlockTags.LAPIS_ORES)) return 0xFF4D6DFF;
            if (coal.get() && s.isIn(BlockTags.COAL_ORES)) return 0xFF8A8A8A;
            if (copper.get() && s.isIn(BlockTags.COPPER_ORES)) return 0xFFFF9A55;
            return 0;
        }

        private void refill() {
            ChunkPos cp = mc.player.getChunkPos();
            int r = radius.getInt();
            byChunk.keySet().removeIf(k -> {
                ChunkPos c = new ChunkPos(k);
                return Math.abs(U.cx(c) - U.cx(cp)) > r + 2 || Math.abs(U.cz(c) - U.cz(cp)) > r + 2;
            });
            List<ChunkPos> list = new ArrayList<>();
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) list.add(new ChunkPos(U.cx(cp) + dx, U.cz(cp) + dz));
            final int pcx = U.cx(cp), pcz = U.cz(cp);
            list.sort(Comparator.comparingInt(c -> (U.cx(c) - pcx) * (U.cx(c) - pcx) + (U.cz(c) - pcz) * (U.cz(c) - pcz)));
            queue.addAll(list);
        }

        private void scan(WorldChunk c) {
            List<Hit> out = new ArrayList<>();
            ChunkSection[] secs = c.getSectionArray();
            int bottom = mc.world.getBottomSectionCoord();
            for (int i = 0; i < secs.length && out.size() < 250; i++) {
                ChunkSection sec = secs[i];
                if (sec == null || sec.isEmpty() || !sec.hasAny(st -> colorOf(st) != 0)) continue;
                for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                    int col = colorOf(sec.getBlockState(x, y, z));
                    if (col != 0) out.add(new Hit(new BlockPos(c.getPos().getStartX() + x, (bottom + i) * 16 + y, c.getPos().getStartZ() + z), col));
                }
            }
            byChunk.put(c.getPos().toLong(), out);
        }

        @Override public void onTick() {
            if (queue.isEmpty()) refill();
            for (int n = 0; n < perTick.getInt() && !queue.isEmpty(); n++) {
                ChunkPos cp = queue.poll();
                WorldChunk c = mc.world.getChunkManager().getWorldChunk(U.cx(cp), U.cz(cp));
                if (c == null) { byChunk.remove(cp.toLong()); continue; }
                scan(c);
            }
        }

        @Override public void onHud(DrawContext ctx) {
            Proj pr = Proj.begin();
            if (pr == null) return;
            double rg = range.get() * range.get();
            List<Hit> near = new ArrayList<>();
            for (List<Hit> l : byChunk.values()) for (Hit h : l)
                if (Vec3d.ofCenter(h.pos()).squaredDistanceTo(pr.eye) <= rg) near.add(h);
            near.sort(Comparator.comparingDouble(h -> Vec3d.ofCenter(h.pos()).squaredDistanceTo(pr.eye)));
            for (int i = 0; i < Math.min(64, near.size()); i++) pr.wire(ctx, new Box(near.get(i).pos()).expand(0.002), near.get(i).color());
        }
    }

    /** Green = all bedrock, orange = has obsidian. */
    public static class HoleESP extends Mod {
        Setting.Num radius = num("Radius", 8, 3, 16, 1);
        record H(BlockPos pos, int color) {}
        List<H> holes = new ArrayList<>();
        int t;

        public HoleESP() { super("HoleESP", Category.RENDER, "Highlights safe holes around you."); }

        @Override public void onEnable() { holes.clear(); t = 0; }

        private int kind(BlockPos b) {
            var s = mc.world.getBlockState(b);
            if (s.isOf(Blocks.BEDROCK)) return 2;
            if (s.isOf(Blocks.OBSIDIAN) || s.isOf(Blocks.CRYING_OBSIDIAN) || s.isOf(Blocks.NETHERITE_BLOCK)) return 1;
            return 0;
        }

        @Override public void onTick() {
            if (t++ % 10 != 0) return;
            List<H> out = new ArrayList<>();
            BlockPos c = mc.player.getBlockPos();
            int r = radius.getInt();
            for (int x = -r; x <= r; x++) for (int y = -3; y <= 3; y++) for (int z = -r; z <= r; z++) {
                BlockPos p = c.add(x, y, z);
                if (!mc.world.getBlockState(p).isAir() || !mc.world.getBlockState(p.up()).isAir()) continue;
                int worst = kind(p.down());
                if (worst == 0) continue;
                for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                    worst = Math.min(worst, kind(p.offset(d)));
                    if (worst == 0) break;
                }
                if (worst > 0) out.add(new H(p, worst == 2 ? 0xFF4DFF7A : 0xFFFFA23A));
            }
            holes = out;
        }

        @Override public void onHud(DrawContext ctx) {
            Proj pr = Proj.begin();
            if (pr == null) return;
            for (int i = 0; i < Math.min(40, holes.size()); i++)
                pr.wire(ctx, new Box(holes.get(i).pos()).contract(0.05, 0.6, 0.05), holes.get(i).color());
        }
    }

    public static class GlowESP extends Mod {
        Setting.Bool players = bool("Players", true), mobs = bool("Mobs", true), items = bool("Items", false);
        public GlowESP() { super("GlowESP", Category.RENDER, "Outlines entities through walls (client-side glow)."); }

        @Override public void onTick() {
            for (Entity e : mc.world.getEntities()) {
                if (e == mc.player) continue;
                boolean g = e instanceof PlayerEntity ? players.get() : e instanceof ItemEntity ? items.get() : e instanceof LivingEntity && mobs.get();
                if (g) e.setGlowing(true);
            }
        }
        @Override public void onDisable() {
            if (mc.world == null) return;
            for (Entity e : mc.world.getEntities()) e.setGlowing(false);
        }
    }

    public static class Fullbright extends Mod {
        public Fullbright() { super("Fullbright", Category.RENDER, "Permanent night vision (client-side)."); }
        @Override public void onTick() { mc.player.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 400, 0, false, false)); }
        @Override public void onDisable() { if (mc.player != null) mc.player.removeStatusEffect(StatusEffects.NIGHT_VISION); }
    }

    public static class NoBob extends Mod {
        boolean old;
        public NoBob() { super("NoBob", Category.RENDER, "Disables view bobbing."); }
        @Override public void onEnable() { old = mc.options.getBobView().getValue(); mc.options.getBobView().setValue(false); }
        @Override public void onDisable() { mc.options.getBobView().setValue(old); }
    }

    public static class FovChanger extends Mod {
        Setting.Num fov = num("FOV", 100, 30, 110, 1);
        int old = 70;
        public FovChanger() { super("FovChanger", Category.RENDER, "Overrides your field of view."); }
        @Override public void onEnable() { old = mc.options.getFov().getValue(); }
        @Override public void onTick() { mc.options.getFov().setValue(fov.getInt()); }
        @Override public void onDisable() { mc.options.getFov().setValue(old); }
    }

    public static class DropESP extends Mod {
        Setting.Num range = num("Range", 64, 8, 256, 8), minStack = num("Min stack", 1, 1, 64, 1);
        Setting.Mode style = mode("Style", "Label", "Label", "Label + box", "Box");
        Setting.Bool icon = bool("Item icon", true), distance = bool("Distance", false);

        public DropESP() { super("DropESP", Category.RENDER, "Labels and boxes for dropped items, through walls."); }

        @Override public void onHud(DrawContext ctx) {
            Proj pr = Proj.begin();
            if (pr == null) return;
            boolean label = !style.is("Box"), box = !style.is("Label");
            for (Entity e : mc.world.getEntities()) {
                if (!(e instanceof ItemEntity ie) || !ie.isAlive()) continue;
                ItemStack st = ie.getStack();
                if (st.getCount() < minStack.getInt() || mc.player.distanceTo(e) > range.get()) continue;
                float[] r = pr.rect(Proj.lerpBox(e));
                if (r == null) continue;
                int x1 = Math.round(r[0]), y1 = Math.round(r[1]), x2 = Math.round(r[2]), y2 = Math.round(r[3]);
                if (x2 - x1 < 5) { x1 -= 2; x2 += 2; }
                if (y2 - y1 < 5) { y1 -= 2; y2 += 2; }
                if (box) ESP.box(ctx, x1, y1, x2, y2, C_ITEM);
                if (!label) continue;
                String t = st.getName().getString() + (st.getCount() > 1 ? " x" + st.getCount() : "");
                String d = distance.get() ? " " + (int) mc.player.distanceTo(e) + "m" : "";
                boolean ic = icon.get();
                int w = (ic ? 20 : 6) + U.width(t) + U.width(d), h = ic ? 18 : 13;
                int x = (x1 + x2) / 2 - w / 2, y = y1 - h - 2;
                Gfx.rrect(ctx, x, y, w, h, 4, 0xCC141414);
                int tx = x + 4;
                if (ic) { ctx.drawItem(st, x + 2, y + 1); tx = x + 20; }
                U.text(ctx, t, tx, y + (h - 8) / 2, 0xFFFFFFFF);
                if (!d.isEmpty()) U.text(ctx, d, tx + U.width(t), y + (h - 8) / 2, 0xFF9A9A9A);
            }
        }
    }

    public static class NameTags extends Mod {
        Setting.Num range = num("Range", 96, 8, 256, 8);
        Setting.Bool players = bool("Players", true), hostiles = bool("Hostiles", false), passives = bool("Passives", false);
        Setting.Bool health = bool("Health", true), distance = bool("Distance", true), gear = bool("Armor and held items", true), durability = bool("Durability bars", true);
        Setting.Mode style = mode("Style", "Matte", "Matte", "Minimal");

        public NameTags() { super("NameTags", Category.RENDER, "Name, health, distance and gear above players, through walls."); }

        @Override public void onHud(DrawContext ctx) {
            Proj pr = Proj.begin();
            if (pr == null) return;
            for (Entity e : mc.world.getEntities()) {
                if (!(e instanceof LivingEntity le)) continue;
                int col = colorFor(e, players.get(), hostiles.get(), passives.get(), false);
                if (col == 0 || mc.player.distanceTo(e) > range.get()) continue;
                double[] s = pr.project(Proj.lerpPos(e).add(0, e.getHeight() + 0.3, 0));
                if (s == null) continue;
                String name = e.getName().getString();
                String hp = health.get() ? one(le.getHealth() + le.getAbsorptionAmount()) : null;
                String dist = distance.get() ? (int) mc.player.distanceTo(e) + "m" : null;
                int hpCol = Gfx.mix(0xFFFF3B3B, 0xFF4DFF6A, Math.max(0f, Math.min(1f, le.getHealth() / Math.max(1f, le.getMaxHealth()))));
                boolean matte = style.is("Matte");
                int w = (matte ? 10 : 4) + U.width(name) + (hp != null ? 6 + U.width(hp) : 0) + (dist != null ? 6 + U.width(dist) : 0);
                int x = (int) Math.round(s[0]) - w / 2, y = (int) Math.round(s[1]) - 14;
                if (matte) {
                    Gfx.rrect(ctx, x, y, w, 13, 4, 0xCC141414);
                    Gfx.rrect(ctx, x + 2, y + 3, 2, 7, 1, col);
                }
                int tx = x + (matte ? 7 : 2);
                U.text(ctx, name, tx, y + 3, 0xFFFFFFFF);
                tx += U.width(name) + 6;
                if (hp != null) { U.text(ctx, hp, tx, y + 3, hpCol); tx += U.width(hp) + 6; }
                if (dist != null) U.text(ctx, dist, tx, y + 3, 0xFF9A9A9A);
                if (!gear.get()) continue;
                EquipmentSlot[] slots = {EquipmentSlot.MAINHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND};
                List<ItemStack> shown = new ArrayList<>();
                for (EquipmentSlot sl : slots) { ItemStack st = le.getEquippedStack(sl); if (!st.isEmpty()) shown.add(st); }
                int gx = (int) Math.round(s[0]) - shown.size() * 17 / 2, gy = y - 19;
                for (ItemStack st : shown) {
                    ctx.drawItem(st, gx, gy);
                    if (durability.get()) ctx.drawStackOverlay(mc.textRenderer, st, gx, gy);
                    gx += 17;
                }
            }
        }
    }
}
