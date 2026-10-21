package nova.modules;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import nova.core.*;
import nova.modules.ClientModules.Theme;

import java.util.*;

/**
 * The scanners only read what the server actually sent to this client, which makes them useful for checking
 * whether anti-xray / base hiding really withholds data.
 */
public final class WorldModules {
    private WorldModules() {}

    public static class Nuker extends Mod {
        Setting.Num range = num("Range", 4, 1, 6, 1);
        Setting.Num perTick = num("Blocks per tick", 1, 1, 10, 1);
        public Nuker() { super("Nuker", Category.WORLD, "Breaks blocks around you."); }

        @Override public void onTick() {
            var p = mc.player;
            Vec3d eye = p.getEyePos();
            int r = range.getInt();
            BlockPos base = p.getBlockPos();
            List<BlockPos> list = new ArrayList<>();
            for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
                BlockPos pos = base.add(x, y, z);
                BlockState s = mc.world.getBlockState(pos);
                if (s.isAir() || !s.getFluidState().isEmpty() || s.getHardness(mc.world, pos) < 0) continue;
                if (Vec3d.ofCenter(pos).squaredDistanceTo(eye) > range.get() * range.get()) continue;
                list.add(pos);
            }
            list.sort(Comparator.comparingDouble(b -> Vec3d.ofCenter(b).squaredDistanceTo(eye)));
            for (int i = 0; i < Math.min(perTick.getInt(), list.size()); i++)
                mc.interactionManager.updateBlockBreakingProgress(list.get(i), Direction.UP);
            if (!list.isEmpty()) p.swingHand(Hand.MAIN_HAND);
        }
    }

    abstract static class Scanner extends Mod {
        Setting.Num radius = num("Radius (chunks)", 8, 1, 32, 1);
        Setting.Num interval = num("Interval (ticks)", 40, 5, 400, 5);
        Setting.Bool announce = bool("Chat announce", true);
        final Map<Long, String> found = new LinkedHashMap<>();
        int t;

        Scanner(String n, String d) { super(n, Category.WORLD, d); }

        abstract void scanChunk(WorldChunk c);

        @Override public void onEnable() { found.clear(); t = 0; }
        @Override public void onDisable() { found.clear(); }

        @Override public void onTick() {
            if (++t % interval.getInt() != 0) return;
            ChunkPos cp = mc.player.getChunkPos();
            int r = radius.getInt();
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                WorldChunk c = mc.world.getChunkManager().getWorldChunk(U.cx(cp) + dx, U.cz(cp) + dz);
                if (c != null) scanChunk(c);
            }
        }

        void report(WorldChunk c, BlockPos at, String msg) {
            String full = name + ": " + msg + " @ " + at.getX() + " " + at.getY() + " " + at.getZ();
            String old = found.put(c.getPos().toLong(), full);
            if (old == null && announce.get()) U.chat(full);
        }

        @Override public void onHud(DrawContext ctx) {
            int y = 70, n = 0;
            U.text(ctx, name + " (" + found.size() + ")", 2, y, Theme.accent());
            for (String s : found.values()) {
                if (++n > 8) break;
                y += 10;
                U.text(ctx, s.substring(name.length() + 2), 2, y, 0xFFFFFFFF);
            }
        }
    }

    static boolean isStorage(BlockEntity be) {
        return be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity
                || be instanceof HopperBlockEntity || be instanceof DispenserBlockEntity
                || be instanceof AbstractFurnaceBlockEntity;
    }

    public static class StashFinder extends Scanner {
        Setting.Num min = num("Min containers", 4, 1, 64, 1);
        public StashFinder() { super("StashFinder", "Chunks that contain many storage blocks."); }

        @Override void scanChunk(WorldChunk c) {
            int n = 0; BlockPos first = null;
            for (BlockEntity be : c.getBlockEntities().values()) if (isStorage(be)) { n++; if (first == null) first = be.getPos(); }
            if (n >= min.getInt()) report(c, first, n + " containers");
        }
    }

    public static class BaseFinder extends Scanner {
        Setting.Num min = num("Min base blocks", 5, 1, 64, 1);
        public BaseFinder() { super("BaseFinder", "Chunks with several player-made block entities (chests, beds, signs...)."); }

        @Override void scanChunk(WorldChunk c) {
            int n = 0; BlockPos first = null;
            for (BlockEntity be : c.getBlockEntities().values()) {
                if (isStorage(be) || be instanceof BedBlockEntity || be instanceof SignBlockEntity
                        || be instanceof EnderChestBlockEntity || be instanceof EnchantingTableBlockEntity
                        || be instanceof BrewingStandBlockEntity || be instanceof BeaconBlockEntity) {
                    n++; if (first == null) first = be.getPos();
                }
            }
            if (n >= min.getInt()) report(c, first, n + " base blocks");
        }
    }

    public static class SpawnerFinder extends Scanner {
        public SpawnerFinder() { super("SpawnerFinder", "Finds mob spawners the server sent you."); }

        @Override void scanChunk(WorldChunk c) {
            for (BlockEntity be : c.getBlockEntities().values())
                if (be instanceof MobSpawnerBlockEntity) { report(c, be.getPos(), "spawner"); return; }
        }
    }

    public static class OreScan extends Scanner {
        Setting.Mode target = mode("Target", "Diamond", "Diamond", "Debris", "Emerald", "Gold", "Iron", "Any");
        Setting.Num min = num("Min count", 1, 1, 64, 1);
        public OreScan() {
            super("OreScan", "Counts ore blocks in loaded chunks (anti-xray test).");
            radius.setDefault(4.0); interval.setDefault(100.0);
        }

        boolean match(BlockState s) {
            return switch (target.value) {
                case "Diamond" -> s.isIn(BlockTags.DIAMOND_ORES);
                case "Debris" -> s.isOf(Blocks.ANCIENT_DEBRIS);
                case "Emerald" -> s.isIn(BlockTags.EMERALD_ORES);
                case "Gold" -> s.isIn(BlockTags.GOLD_ORES);
                case "Iron" -> s.isIn(BlockTags.IRON_ORES);
                default -> s.isIn(BlockTags.DIAMOND_ORES) || s.isIn(BlockTags.EMERALD_ORES) || s.isIn(BlockTags.GOLD_ORES)
                        || s.isIn(BlockTags.IRON_ORES) || s.isOf(Blocks.ANCIENT_DEBRIS);
            };
        }

        @Override void scanChunk(WorldChunk c) {
            ChunkSection[] secs = c.getSectionArray();
            int bottom = mc.world.getBottomSectionCoord();
            int n = 0; BlockPos first = null;
            for (int i = 0; i < secs.length; i++) {
                ChunkSection sec = secs[i];
                if (sec == null || sec.isEmpty() || !sec.hasAny(this::match)) continue;
                for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                    if (match(sec.getBlockState(x, y, z))) {
                        n++;
                        if (first == null) first = new BlockPos(c.getPos().getStartX() + x, (bottom + i) * 16 + y, c.getPos().getStartZ() + z);
                    }
                }
            }
            if (n >= min.getInt()) report(c, first, n + " " + target.value.toLowerCase());
        }
    }
}
