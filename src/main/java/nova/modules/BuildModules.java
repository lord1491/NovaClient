package nova.modules;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.*;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.ChestType;
import net.minecraft.block.enums.SlabType;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.TrappedChestBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.chunk.WorldChunk;
import nova.core.*;
import nova.gui.SchematicScreen;
import nova.modules.ClientModules.Theme;
import nova.schem.Litematic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

public final class BuildModules {
    private BuildModules() {}

    /**
     * Builds a loaded .litematic by placing blocks the way a player would: turns smoothly towards the face it clicks,
     * only clicks when the crosshair is really on that face, stays inside reach and needs line of sight.
     * Shows the schematic as coloured ghost outlines and can walk to wherever the next block can be placed from.
     * Put .litematic files in .minecraft/schematics (Litematica's folder) or config/nova/schematics.
     */
    public static class Schematic extends Mod {
        Setting.Action openBuilder = action("Open builder window", this::openBuilder);
        Setting.Mode file = mode("File", "None", "None");
        Setting.Action refresh = action("Refresh file list", this::refresh);
        Setting.Action load = action("Load selected file", this::loadSelected);
        Setting.Action originHere = action("Origin: my position", this::originHere);
        Setting.Action originLook = action("Origin: block I look at", this::originLook);
        Setting.Action unload = action("Unload", this::unload);
        Setting.Mode anchor = mode("Origin is", "Corner", "Corner", "Center");
        Setting.Mode rotation = mode("Rotation", "0", "0", "90", "180", "270");
        Setting.Num offX = num("Offset X", 0, -64, 64, 1), offY = num("Offset Y", 0, -64, 64, 1), offZ = num("Offset Z", 0, -64, 64, 1);
        Setting.Mode order = mode("Order", "Bottom-up", "Bottom-up", "Nearest");
        Setting.Num reach = num("Reach", 4.5, 2, 6, 0.1);
        Setting.Num perTick = num("Blocks / tick", 1, 1, 6, 1);
        Setting.Num delay = num("Delay (ticks)", 1, 0, 10, 1);
        Setting.Bool rotate = bool("Rotate", true), ray = bool("Line of sight check", true), humanize = bool("Humanize", true),
                matchFacing = bool("Match facing", true), creative = bool("Creative fill", true);
        Setting.Bool restock = bool("Take items from chests", true);
        Setting.Num chestRange = num("Chest range", 64, 16, 160, 4);
        Setting.Mode restockFrom = mode("Restock from", "Marked, else any", "Marked, else any", "Marked only", "Any nearby");
        Setting.Action markLook = action("Mark / unmark chest I look at", this::markLook);
        Setting.Action markNear = action("Mark chests near me", this::markNear);
        Setting.Action markWaterLook = action("Mark / unmark water I look at (optional)", this::markWater);
        Setting.Action clearMarks = action("Clear marked chests / water", this::clearMarks);
        Setting.Bool showMarks = bool("Show marked chests", true), stockFiller = bool("Restock filler too", true);
        Setting.Bool water = bool("Use water buckets", true), lava = bool("Use lava buckets", true);
        Setting.Num waterBuckets = num("Water buckets to carry", 2, 1, 8, 1), waterRange = num("Water search radius", 16, 4, 40, 1);
        Setting.Num takeStacks = num("Stacks per item", 9, 1, 36, 1);
        Setting.Bool layerOnly = bool("Restock current layer only", true);
        Setting.Num keepFree = num("Free slots to keep", 0, 0, 8, 1);
        Setting.Num fromLayer = num("First layer", 1, 1, 400, 1), toLayer = num("Last layer (0 = all)", 0, 0, 400, 1);
        Setting.Mode liteLayers = mode("Litematica layers", "Layer range", "All layers", "One layer", "Layer range");
        Setting.Num pauseHp = num("Pause below health (0 = off)", 0, 0, 19, 1);
        Setting.Bool stopWhenDone = bool("Disable when finished", false);
        Setting.Action copyList = action("Copy material list", this::copyMaterials);
        Setting.Mode buildMode = mode("Build mode", "Continuous", "Continuous", "Layer by layer");
        Setting.Bool cleanAsYouGo = bool("Remove filler as I build", true), unstick = bool("Auto unstick", true);
        Setting.Num stuckSecs = num("Unstick after (s)", 20, 5, 120, 5);
        Setting.Num aimSpeed = num("Aim speed", 40, 5, 100, 1), maxTurn = num("Max turn / tick", 25, 2, 90, 1);
        Setting.Bool outline = bool("Show outline", true), showNext = bool("Show next block", true), progress = bool("Progress HUD", true);
        Setting.Bool useLitematica = bool("Load into Litematica", true), showSchem = bool("Built-in preview", false), walk = bool("Auto walk", true), edgeSafety = bool("Sneak at edges", true), showPath = bool("Show path", true);
        Setting.Bool sprintWalk = bool("Sprint on long walks", true), lookWalkOpt = bool("Look where I walk", true), walkPauses = bool("Occasional pauses", true), diagonal = bool("Diagonal walking", true);
        Setting.Num chestMemMin = num("Chest memory (min)", 10, 1, 60, 1);
        Setting.Bool filler = bool("Filler blocks", true), cleanFiller = bool("Remove filler when done", true);
        Setting.Bool fixWrong = bool("Fix wrong blocks", true), fixForeign = bool("Replace other wrong blocks", false);
        Setting.Mode fillerBlock = mode("Filler block", "Cobblestone", "Cobblestone", "Dirt", "Stone", "Netherrack", "Cobbled Deepslate", "Oak Planks");
        Setting.Num ghostRange = num("Preview range", 12, 6, 48, 1), ghostMax = num("Preview blocks", 40, 10, 400, 10);

        record Plan(int idx, BlockPos pos, BlockPos against, Direction face, Vec3d hit, Item item, BlockState state) {}

        Litematic schem;
        String loadedFile = "";
        Map<Item, Integer> needTotal = new LinkedHashMap<>();
        private final ArrayDeque<int[]> rateLog = new ArrayDeque<>();
        List<Litematic.Entry> view = new ArrayList<>();
        String viewRot = "";
        int vx, vy, vz;
        BlockPos origin;
        final BitSet done = new BitSet();
        int firstPending, doneCount, tick, cd, curIdx = -1;
        final Map<Item, Integer> missing = new LinkedHashMap<>();
        // items still needed per schematic layer (y), filled by recount(); restocking only fetches the shortfall of the layer being built
        Map<Integer, Map<Item, Integer>> layerNeed = new HashMap<>();
        Map<Integer, Map<Item, Integer>> recLayerNeed = new HashMap<>();
        int recLayerY = Integer.MIN_VALUE;
        Map<Item, Integer> recLayerMap;
        BlockPos nextPos, chestPos;
        int rcd, openTries, restockTick;
        // chests: drained = canonical chest pos -> walkClock until which it is skipped (it had nothing we need); marked = chests the user picked
        final Map<BlockPos, Integer> drained = new HashMap<>();
        // what each chest held when we last looked inside (canonical pos -> items left after taking, and when): chests that cannot have what is missing are not visited again
        record ChestMem(Map<Item, Integer> items, int tick) {}
        final Map<BlockPos, ChestMem> chestMem = new HashMap<>();
        final Set<BlockPos> marked = new HashSet<>();
        final Set<BlockPos> markedWater = new HashSet<>();   // optional: water sources the player marked as the place to refill buckets
        int refillAim, refillNoAim;
        Object markWorld;
        Set<Item> visitWanted = new HashSet<>();
        int visitTook, restockBlockedUntil, lastAct, stuckCount, layerMin, layerCacheTick = -1, layerCache, removeCacheTick = -1;
        boolean visitFull;
        Set<BlockPos> removeCache = Set.of();
        String status = "";

        // block index (view is sorted by y, z, x so the packed keys are ascending -> binary search gives the entry index)
        long[] keys = new long[0];
        final BitSet surf = new BitSet();

        // Litematica bridge
        Object litePlacement;
        String liteKey = "", liteFailKey = "";
        Object liteLayerPrevMode; boolean liteLayerSet, liteLayerWarned; String liteLayerKey = "";   // Litematica render layer we changed (and the mode to give back)
        boolean liteWarned;
        int liteTick;
        Object liteWorld;

        // incremental recount
        int recPos = -1, recCount;
        BitSet recDone = new BitSet();
        Map<Item, Integer> recNeed = new HashMap<>();

        // ghost preview
        record Ghost(BlockPos pos, int color) {}
        private record G(double d, int i) {}
        List<Ghost> ghosts = List.of();
        int ghostTick;

        // walking
        List<BlockPos> path = List.of();
        int pathIdx, walkIdx = -1, stuck, pathTicks, walkCd, doorCd, walkClock, failIdx = -1, failCount;
        int jumpCd, jumpTries, sneakHold, pauseTicks;
        double bestProg = 1e9;
        boolean lookWalk;
        float walkPitch = 12f;
        Vec3d lastPos;
        BlockPos walkChest, foundChest;
        boolean keysHeld;
        String walkStatus = "";
        final Map<Integer, Integer> badTargets = new HashMap<>();
        // give-up memory that survives "Auto unstick": strikes = how often a target was parked (parking time doubles each time),
        // clicks = {clicks, last tick} per target, to notice blocks the server keeps refusing
        final Map<Integer, Integer> strikes = new HashMap<>();
        final Map<Integer, int[]> clicks = new HashMap<>();
        int lastDoneSeen = -1, lastBuiltTick, noPlaceTicks, aimWait;
        boolean aiming;   // set by placeOne(): the view was still turning towards the target this tick
        // restock loop breaker: pause chest trips when trips follow each other without a single block being built in between
        int missingTick = -9999;
        int rangeSkipped, recSkipped, activeTicks;
        // per schematic layer (y, 0-based): blocks in total / already right in the world. Filled by recount() for ALL layers (also the ones outside the chosen range) so the window can show them.
        int[] layerTot = new int[0], layerDoneN = new int[0], recLayerTot = new int[0], recLayerDoneN = new int[0];
        boolean announced;
        int restockPauseUntil, lastTripDone = -1, fruitlessTrips, shortTick = -9999;
        boolean shortCache;

        // layer verification: reads the REAL world instead of trusting the done flags (a block the client predicted but the server refused stays flagged done)
        int gateY = Integer.MIN_VALUE;                       // layer we were building last: leaving it upwards needs a clean world check
        int stuckLayerY = Integer.MIN_VALUE, stuckLayerHits; // a stuck layer gets one real retry before it may be skipped
        int finalY = -1;                                     // final check before "finished": next layer to verify
        int chkY = Integer.MIN_VALUE, chkTotal, chkMissing, chkTick = -9999;   // result of the last check, shown in the builder window
        final Map<BlockPos, Integer> placedTick = new HashMap<>();             // when we last clicked a block into place (server needs a moment to confirm)
        final Map<BlockPos, int[]> tune = new HashMap<>();                     // note blocks being tuned: {note the server will have after our clicks, tick of last click, lost clicks}

        // filler blocks (bridges, stairs, support columns) that we placed ourselves
        final Set<BlockPos> fillers = new HashSet<>();
        BlockPos fillTarget;
        int fillFor = -1, fillScan, fillFail, bridgeTries, fillCacheTick = -1;
        boolean bridgeBusy;
        Item fillCache;

        // wrong blocks: junk = blocks we are going to break so the right one can be placed, ours = every position we placed something at
        final Set<BlockPos> junk = new HashSet<>(), ours = new HashSet<>(), gaveUp = new HashSet<>();
        final Map<BlockPos, Integer> fixTries = new HashMap<>();
        int wrongCount, recWrong;

        // restock: chest list cache, wide route search, last error
        List<BlockPos> chestCache = List.of();
        int chestCacheTick = -9999;
        boolean wideArea;
        String lastErr = "";

        // water: water sources / waterlogged blocks are placed with a water bucket, empty buckets are refilled at an infinite source near a marked chest
        int waterWork, recWater;                 // entries that still need water (counted by recount)
        int dryOpenTick = -9999, dryOpenCache = Integer.MAX_VALUE, waterOpenTick = -9999, waterWalks, waterPauseUntil, refillCd, waterSrcTick = -9999;
        boolean waterOpenCache, refilling;
        int refillTries, lastFilled;

        // sign text: signs are written through the sign editor after they stand (front side only)
        Map<Long, String[]> viewSigns = new HashMap<>();
        List<Integer> signIdx = new ArrayList<>();
        final BitSet signDone = new BitSet();
        final Map<Integer, Integer> signClicks = new HashMap<>();
        BlockPos signAt;
        String[] signLines;
        int signWait;
        // saved placement (origin, rotation, offsets per file)
        String savedPlacement = "";
        List<BlockPos> waterSrcCache = List.of();

        public Schematic() { super("Schematic", Category.WORLD, "Auto builds a loaded .litematic file."); }

        private List<Path> dirs() {
            return List.of(FabricLoader.getInstance().getGameDir().resolve("schematics"),
                           FabricLoader.getInstance().getConfigDir().resolve("nova").resolve("schematics"));
        }

        private Path find(String name) {
            for (Path d : dirs()) { Path p = d.resolve(name); if (Files.exists(p)) return p; }
            return null;
        }

        void refresh() { refresh(true); }

        void refresh(boolean say) {
            List<String> names = new ArrayList<>();
            for (Path d : dirs()) {
                try { Files.createDirectories(d); } catch (Exception ignored) {}
                try (Stream<Path> st = Files.list(d)) {
                    st.filter(f -> f.getFileName().toString().endsWith(".litematic"))
                      .forEach(f -> { String n = f.getFileName().toString(); if (!names.contains(n)) names.add(n); });
                } catch (Exception ignored) {}
            }
            Collections.sort(names);
            if (names.isEmpty()) names.add("None");
            file.setModes(names.toArray(new String[0]));
            status = names.get(0).equals("None") ? "No .litematic files found in the schematics folder." : names.size() + " file(s) found.";
            if (say) U.chat(status);
        }

        void loadSelected() {
            Path p = file.is("None") ? null : find(file.value);
            if (p == null) { status = "Pick a file first."; U.chat(status); return; }
            try {
                schem = Litematic.load(p);
                loadedFile = file.value;
                viewRot = "";
                rebuildView();
                savedPlacement = "";
                boolean restored = restorePlacement();
                if (restored) rebuildView();   // the saved rotation may differ
                status = "Loaded " + schem.name + " (" + schem.blocks.size() + " blocks, " + schem.sx + "x" + schem.sy + "x" + schem.sz + ")";
                U.chat(status + (restored ? ". Restored the saved origin " + origin.getX() + " " + origin.getY() + " " + origin.getZ() + "." : origin == null ? ". Now set the origin." : ""));
            } catch (Throwable t) {
                schem = null; view.clear(); loadedFile = "";
                status = "Could not load: " + t.getMessage();
                U.chat(status);
            }
        }

        void unload() { chestMem.clear(); schem = null; loadedFile = ""; needTotal.clear(); layerNeed = new HashMap<>(); rateLog.clear(); view.clear(); done.clear(); nextPos = null; curIdx = -1; ghosts = List.of(); keys = new long[0]; surf.clear(); resetWalk(); removeLite(); restoreLiteLayer(); fillers.clear(); fillTarget = null; junk.clear(); ours.clear(); gaveUp.clear(); fixTries.clear(); strikes.clear(); clicks.clear(); tune.clear(); wrongCount = 0; layerMin = 0; layerCacheTick = -1; removeCacheTick = -1; layerTot = new int[0]; layerDoneN = new int[0]; resetLayerCheck(); waterWork = 0; refilling = false; waterSrcCache = List.of(); status = "Unloaded."; }

        void originHere() {
            if (mc.player == null) return;
            origin = mc.player.getBlockPos();
            resetProgress();
            U.chat("Origin set to " + origin.getX() + " " + origin.getY() + " " + origin.getZ());
        }

        void originLook() {
            if (mc.player == null) return;
            if (mc.crosshairTarget instanceof BlockHitResult h && h.getType() == HitResult.Type.BLOCK) {
                origin = h.getBlockPos().offset(h.getSide());
                resetProgress();
                U.chat("Origin set to " + origin.getX() + " " + origin.getY() + " " + origin.getZ());
            } else U.chat("Look at a block first.");
        }

        private void resetProgress() { signDone.clear(); signClicks.clear(); signAt = null; done.clear(); firstPending = 0; doneCount = 0; curIdx = -1; tick = 0; recPos = -1; ghosts = List.of(); badTargets.clear(); strikes.clear(); clicks.clear(); lastDoneSeen = -1; noPlaceTicks = 0; restockPauseUntil = 0; lastTripDone = -1; fruitlessTrips = 0; shortTick = -9999; junk.clear(); ours.clear(); gaveUp.clear(); fixTries.clear(); tune.clear(); wrongCount = 0; layerMin = 0; layerCacheTick = -1; removeCacheTick = -1; lastAct = lastBuiltTick = walkClock; stuckCount = 0; activeTicks = 0; announced = false; rangeSkipped = 0; layerTot = new int[0]; layerDoneN = new int[0]; resetLayerCheck(); resetWalk(); }

        void rebuildView() {
            if (schem == null) return;
            viewRot = rotation.value;
            int rot = Integer.parseInt(viewRot) / 90;
            BlockRotation br = switch (rot) { case 1 -> BlockRotation.CLOCKWISE_90; case 2 -> BlockRotation.CLOCKWISE_180; case 3 -> BlockRotation.COUNTERCLOCKWISE_90; default -> BlockRotation.NONE; };
            int sx = schem.sx, sz = schem.sz;
            vx = rot % 2 == 0 ? sx : sz; vy = schem.sy; vz = rot % 2 == 0 ? sz : sx;
            List<Litematic.Entry> out = new ArrayList<>(schem.blocks.size());
            Map<Long, String[]> signsNew = new HashMap<>();
            for (var se : schem.signs.entrySet()) {
                long k = se.getKey();
                int x = (int) (k & 0x1FFFFF), z = (int) ((k >> 21) & 0x1FFFFF), y = (int) (k >> 42), nx, nz;
                switch (rot) {
                    case 1 -> { nx = sz - 1 - z; nz = x; }
                    case 2 -> { nx = sx - 1 - x; nz = sz - 1 - z; }
                    case 3 -> { nx = z; nz = sx - 1 - x; }
                    default -> { nx = x; nz = z; }
                }
                signsNew.put(key(nx, y, nz), se.getValue());
            }
            viewSigns = signsNew;
            for (Litematic.Entry e : schem.blocks) {
                int x = e.x(), z = e.z(), nx, nz;
                switch (rot) {
                    case 1 -> { nx = sz - 1 - z; nz = x; }
                    case 2 -> { nx = sx - 1 - x; nz = sz - 1 - z; }
                    case 3 -> { nx = z; nz = sx - 1 - x; }
                    default -> { nx = x; nz = z; }
                }
                out.add(new Litematic.Entry(nx, e.y(), nz, rot == 0 ? e.state() : e.state().rotate(br)));
            }
            Litematic.sort(out);
            view = out;
            signIdx = new ArrayList<>();
            if (!viewSigns.isEmpty()) for (int i = 0; i < view.size(); i++) {
                Litematic.Entry e = view.get(i);
                if (e.state().getBlock() instanceof AbstractSignBlock && viewSigns.containsKey(key(e.x(), e.y(), e.z()))) signIdx.add(i);
            }
            buildIndex();
            resetProgress();
        }

        private static long key(int x, int y, int z) { return ((long) y << 42) | ((long) z << 21) | (long) x; }

        private int idxAt(int x, int y, int z) {
            if (x < 0 || y < 0 || z < 0 || x >= vx || y >= vy || z >= vz) return -1;
            int i = Arrays.binarySearch(keys, key(x, y, z));
            return i >= 0 ? i : -1;
        }

        private void buildIndex() {
            int n = view.size();
            keys = new long[n];
            for (int i = 0; i < n; i++) { Litematic.Entry e = view.get(i); keys[i] = key(e.x(), e.y(), e.z()); }
            surf.clear();
            for (int i = 0; i < n; i++) {
                Litematic.Entry e = view.get(i);
                int x = e.x(), y = e.y(), z = e.z();
                if (idxAt(x + 1, y, z) < 0 || idxAt(x - 1, y, z) < 0 || idxAt(x, y + 1, z) < 0
                        || idxAt(x, y - 1, z) < 0 || idxAt(x, y, z + 1) < 0 || idxAt(x, y, z - 1) < 0) surf.set(i);
            }
        }

        private boolean pending(BlockPos w) {
            BlockPos b = base();
            if (b == null) return false;
            int i = idxAt(w.getX() - b.getX(), w.getY() - b.getY(), w.getZ() - b.getZ());
            return i >= 0 && !done.get(i);
        }

        private BlockPos base() {
            if (origin == null) return null;
            int cx = anchor.is("Center") ? -(vx / 2) : 0, cz = anchor.is("Center") ? -(vz / 2) : 0;
            return origin.add(offX.getInt() + cx, offY.getInt(), offZ.getInt() + cz);
        }

        private BlockPos world(Litematic.Entry e) { return base().add(e.x(), e.y(), e.z()); }

        @Override public void onEnable() { lastAct = lastBuiltTick = walkClock; lastDoneSeen = -1; refresh(false); if (schem == null && !file.is("None")) loadSelected(); }
        @Override public void onDisable() { nextPos = null; curIdx = -1; ghosts = List.of(); resetWalk(); restoreLiteLayer(); }

        private int findSlot(Item it) {
            var inv = mc.player.getInventory();
            for (int i = 0; i < 36; i++) if (inv.getStack(i).isOf(it)) return i;
            return -1;
        }

        private boolean canGet(Item it) { return findSlot(it) >= 0 || (creative.get() && mc.player.isCreative()); }

        private boolean prepare(Item it) {
            var p = mc.player; var inv = p.getInventory();
            int s = findSlot(it);
            if (s < 0) {
                if (!(creative.get() && p.isCreative())) return false;
                mc.interactionManager.clickCreativeStack(new ItemStack(it), 36 + inv.getSelectedSlot());
                return true;
            }
            if (s < 9) { inv.setSelectedSlot(s); return true; }
            mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, s, inv.getSelectedSlot(), SlotActionType.SWAP, p);
            return true;
        }

        /** Is the block in the world really what the schematic wants: same block AND the orientation / half / axis we are able to control. */
        private boolean sameState(BlockState want, BlockState cur) {
            if (wantsWaterSrc(want)) return cur.isOf(Blocks.WATER) && cur.getFluidState().isStill();
            if (wantsLavaSrc(want)) return cur.isOf(Blocks.LAVA) && cur.getFluidState().isStill();
            if (!sameDry(want, cur)) return false;
            // waterlogged wanted: only counts once the water is in. (A dry wanted block that stands waterlogged is accepted, otherwise it would be broken in a loop.)
            return !wantsWaterlog(want) || !cur.contains(Properties.WATERLOGGED) || cur.get(Properties.WATERLOGGED);
        }

        /** Same block and orientation, water ignored. */
        private boolean sameDry(BlockState want, BlockState cur) {
            if (cur.getBlock() != want.getBlock()) return false;
            if (matchFacing.get() && needFacing(want) != null && cur.contains(Properties.HORIZONTAL_FACING)
                    && cur.get(Properties.HORIZONTAL_FACING) != want.get(Properties.HORIZONTAL_FACING)) return false;
            // 6-way facing (piston, observer, dropper, hopper, end rod, ...): only for blocks whose direction we can control by where we look / click
            if (matchFacing.get()) {
                if (want.contains(Properties.FACING) && cur.contains(Properties.FACING) && (needLook(want) != null || clickFace(want) != null)
                        && want.get(Properties.FACING) != cur.get(Properties.FACING)) return false;
                if (want.contains(Properties.HOPPER_FACING) && cur.contains(Properties.HOPPER_FACING)
                        && want.get(Properties.HOPPER_FACING) != cur.get(Properties.HOPPER_FACING)) return false;
            }
            // note blocks: the pitch is part of the state (0..24), it is set by right clicking after the block is placed
            // everything that is set by right clicking: note, lever on/off, door / trapdoor / gate open, repeater delay, comparator mode
            int[] cw = clickVal(want), cc = clickVal(cur);
            if (cw != null && cc != null && cw[0] != cc[0]) return false;
            // wall / floor / ceiling mounted things (levers, buttons, grindstones, ladders, wall torches, signs, ...): where they are attached
            if (matchFacing.get()) {
                Direction cf = clickFace(want);
                if (cf != null) {
                    for (Property<?> pr : want.getProperties()) {
                        if (pr.getName().equals("face") && cur.contains(pr) && !want.get(pr).equals(cur.get(pr))) return false;
                    }
                    if (cf.getAxis().isHorizontal() && want.contains(Properties.HORIZONTAL_FACING) && cur.contains(Properties.HORIZONTAL_FACING)
                            && want.get(Properties.HORIZONTAL_FACING) != cur.get(Properties.HORIZONTAL_FACING)) return false;
                }
            }
            if (want.contains(Properties.AXIS) && cur.contains(Properties.AXIS) && want.get(Properties.AXIS) != cur.get(Properties.AXIS)) return false;
            if (want.contains(Properties.BLOCK_HALF) && cur.contains(Properties.BLOCK_HALF) && want.get(Properties.BLOCK_HALF) != cur.get(Properties.BLOCK_HALF)) return false;
            if (want.contains(Properties.SLAB_TYPE) && cur.contains(Properties.SLAB_TYPE)) {
                SlabType w = want.get(Properties.SLAB_TYPE), c = cur.get(Properties.SLAB_TYPE);
                if (w == SlabType.DOUBLE ? c != SlabType.DOUBLE : (c != SlabType.DOUBLE && w != c)) return false;
            }
            return true;
        }

        /** The schematic wants a double slab and one half of it is already there: the second half goes on top of / under it (no breaking needed). */
        private static boolean wantsDouble(BlockState want, BlockState cur) {
            return want.getBlock() == cur.getBlock() && want.contains(Properties.SLAB_TYPE) && cur.contains(Properties.SLAB_TYPE)
                    && want.get(Properties.SLAB_TYPE) == SlabType.DOUBLE && cur.get(Properties.SLAB_TYPE) != SlabType.DOUBLE;
        }

        /** Items needed to build this block from nothing (a double slab is two slabs). */
        private static int itemsFor(BlockState want) {
            return want.contains(Properties.SLAB_TYPE) && want.get(Properties.SLAB_TYPE) == SlabType.DOUBLE ? 2 : 1;
        }

        private BlockState wantAt(BlockPos w) {
            BlockPos b = base();
            if (b == null) return null;
            int i = idxAt(w.getX() - b.getX(), w.getY() - b.getY(), w.getZ() - b.getZ());
            return i >= 0 ? view.get(i).state() : null;
        }

        /** A block that is not what the schematic wants stands at wp: remember it so it gets broken and placed again (a few tries, then it is accepted). */
        private void noteWrong(BlockPos wp, BlockState want, BlockState cur) {
            if (!fixWrong.get() || junk.contains(wp) || gaveUp.contains(wp) || cur.isAir() || wantsDouble(want, cur) || needsTune(want, cur) || sameDry(want, cur) || wantsFluidSrc(want)) return;
            if (!ours.contains(wp) && !fixForeign.get()) return;
            if (cur.getHardness(mc.world, wp) < 0) return;   // bedrock and the like
            Item it = want.getBlock().asItem();
            if (it == Items.AIR || !canGet(it)) return;
            BlockPos ip = wp.toImmutable();
            int t = fixTries.merge(ip, 1, Integer::sum);
            if (t > 3) { gaveUp.add(ip); return; }
            junk.add(ip);
        }

        private void pruneJunk() {
            junk.removeIf(j -> {
                BlockState c = mc.world.getBlockState(j);
                if (c.isReplaceable()) return true;
                BlockState w = wantAt(j);
                return w != null && sameState(w, c);
            });
        }

        private boolean skipState(BlockState s) {
            if (s.contains(Properties.DOUBLE_BLOCK_HALF) && s.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return true;
            if (s.contains(Properties.BED_PART) && s.get(Properties.BED_PART) == BedPart.HEAD) return true;
            return s.getBlock() instanceof FluidBlock && !wantsWaterSrc(s) && !wantsLavaSrc(s);   // flowing water / lava: never placed (it appears by itself)
        }

        /** A lava SOURCE cell of the schematic (placed with a lava bucket). Only when "Use lava buckets" is on. */
        private boolean wantsLavaSrc(BlockState s) {
            return lava.get() && s.isOf(Blocks.LAVA) && s.getFluidState().isStill();
        }

        private boolean wantsFluidSrc(BlockState s) { return wantsWaterSrc(s) || wantsLavaSrc(s); }

        private boolean lavaJob(BlockState want, BlockState cur) {
            return wantsLavaSrc(want) && !(cur.isOf(Blocks.LAVA) && cur.getFluidState().isStill());
        }

        // ------------------------------------------------------------------ water helpers
        /** A water SOURCE cell of the schematic (placed with a water bucket). Only when "Use water buckets" is on. */
        private boolean wantsWaterSrc(BlockState s) {
            return water.get() && s.isOf(Blocks.WATER) && s.getFluidState().isStill();
        }

        /** A block that has to be waterlogged (placed dry, then a water bucket is used on it). */
        private boolean wantsWaterlog(BlockState s) {
            return water.get() && s.contains(Properties.WATERLOGGED) && s.get(Properties.WATERLOGGED);
        }

        /** Kelp, seagrass and the like: they hold water themselves and can only be placed where water already is. */
        private static boolean waterPlant(BlockState s) {
            return !(s.getBlock() instanceof FluidBlock) && !s.contains(Properties.WATERLOGGED) && s.getFluidState().isStill();
        }

        /** Does the world cell hold a water source (a waterlogged block counts, its water is a source too)? */
        private static boolean stillWater(BlockState cur) {
            return cur.getFluidState().getFluid() == net.minecraft.fluid.Fluids.WATER;
        }

        private int bucketTotal() { return countOf(Items.BUCKET) + countOf(Items.WATER_BUCKET); }

        private boolean creativeFill() { return creative.get() && mc.player.isCreative(); }

        /** Entry i is water work right now: a water source that is not there yet, or a block that stands but is not waterlogged yet. */
        private boolean waterJob(BlockState want, BlockState cur) {
            if (wantsWaterSrc(want)) return !(cur.isOf(Blocks.WATER) && cur.getFluidState().isStill());
            return wantsWaterlog(want) && sameDry(want, cur) && !(cur.contains(Properties.WATERLOGGED) && cur.get(Properties.WATERLOGGED));
        }

        /** Does this pending entry keep water from being placed (a dry block below / beside it that is still missing)? */
        private boolean blocksWater(int i) {
            if (done.get(i)) return false;
            Litematic.Entry e = view.get(i);
            BlockState want = e.state();
            if (outOfRange(e.y()) || wantsFluidSrc(want) || waterPlant(want) || skipState(want) || want.getBlock().asItem() == Items.AIR) return false;
            BlockPos wp = world(e);
            if (gaveUp.contains(wp)) return false;
            BlockState cur = mc.world.getBlockState(wp);
            if (sameState(want, cur) || sameDry(want, cur)) return false;   // stands (a missing waterlog does not block)
            return true;
        }

        /** Lowest schematic layer that still has a dry block to place; water above it waits (a source placed before its walls would run away). */
        private int dryOpenY() {
            if (walkClock - dryOpenTick < 10) return dryOpenCache;
            dryOpenTick = walkClock;
            while (firstPending < view.size() && done.get(firstPending)) firstPending++;
            int res = Integer.MAX_VALUE;
            for (int i = firstPending; i < view.size(); i++) if (blocksWater(i)) { res = view.get(i).y(); break; }
            dryOpenCache = res;
            return res;
        }

        private boolean fluidReady(int i) { return view.get(i).y() < dryOpenY(); }

        /** Is some water job ready to be done (all dry blocks under / beside it exist)? Cached; decides whether a bucket trip makes sense. */
        private boolean waterOpen() {
            if (walkClock - waterOpenTick < 20) return waterOpenCache;
            waterOpenTick = walkClock;
            boolean res = false;
            if (water.get() && waterWork > 0) {
                int dry = dryOpenY(), scanned = 0;
                while (firstPending < view.size() && done.get(firstPending)) firstPending++;
                for (int i = firstPending; i < view.size(); i++) {
                    Litematic.Entry e = view.get(i);
                    if (e.y() >= dry || ++scanned > 30000) break;
                    if (done.get(i) || bad(i)) continue;
                    if (waterJob(e.state(), mc.world.getBlockState(world(e)))) { res = true; break; }
                }
            }
            waterOpenCache = res;
            return res;
        }

        private static Direction needFacing(BlockState s) {
            if (!s.contains(Properties.HORIZONTAL_FACING)) return null;
            Direction f = s.get(Properties.HORIZONTAL_FACING);
            Block b = s.getBlock();
            if (b instanceof StairsBlock || b instanceof DoorBlock || b instanceof FenceGateBlock) return f;
            if (b instanceof ChestBlock || b instanceof AbstractFurnaceBlock || b instanceof GlazedTerracottaBlock) return f.getOpposite();
            String id = Registries.BLOCK.getId(b).getPath();
            if (id.endsWith("_bed")) return f;
            if (id.equals("repeater") || id.equals("comparator") || id.equals("ender_chest") || id.equals("lectern") || id.equals("stonecutter")
                    || id.equals("loom") || id.equals("carved_pumpkin") || id.equals("jack_o_lantern")) return f.getOpposite();
            if (id.equals("anvil") || id.equals("chipped_anvil") || id.equals("damaged_anvil")) return f.rotateYCounterclockwise();
            return null;
        }

        /**
         * Blocks with a 6-way FACING that is taken from the direction the PLAYER LOOKS (pistons, observers, droppers / dispensers, barrels):
         * they get FACING = opposite of the look direction, so this returns the direction we have to look in (up / down included).
         */
        private static Direction needLook(BlockState s) {
            if (!s.contains(Properties.FACING)) return null;
            Block b = s.getBlock();
            if (b instanceof PistonBlock || b instanceof ObserverBlock || b instanceof DispenserBlock || b instanceof BarrelBlock)
                return s.get(Properties.FACING).getOpposite();
            return null;
        }

        /** The axis the look vector points along the most, or null when two axes are too close to call (the server would round either way). */
        private static Direction dominant(double dx, double dy, double dz) {
            double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
            double mx = Math.max(ax, Math.max(ay, az));
            double second = ax == mx ? Math.max(ay, az) : ay == mx ? Math.max(ax, az) : Math.max(ax, ay);
            if (mx < 0.3 || mx - second < 0.15 * mx) return null;
            if (ax == mx) return dx > 0 ? Direction.EAST : Direction.WEST;
            if (ay == mx) return dy > 0 ? Direction.UP : Direction.DOWN;
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }

        /** Direction the server will think we look in when we click `hit` from `eye` (all six directions). */
        private static Direction lookFrom3(Vec3d eye, Vec3d hit) {
            return dominant(hit.x - eye.x, hit.y - eye.y, hit.z - eye.z);
        }

        /** A note block stands at the right place but is set to another note: it must be right-clicked, not broken. */
        private static boolean needsTune(BlockState want, BlockState cur) {
            if (want.getBlock() != cur.getBlock()) return false;
            int[] a = clickVal(want), b = clickVal(cur);
            return a != null && b != null && a[0] != b[0];
        }

        private static boolean isTune(Plan pl) {
            return pl.against().equals(pl.pos()) && pl.item() != Items.WATER_BUCKET && pl.item() != Items.LAVA_BUCKET && clickVal(pl.state()) != null;
        }

        /**
         * State that a right click changes, as {value, modulus} (every click adds one): note (25), lever powered, door / trapdoor / fence gate open (2),
         * repeater delay (4), comparator mode (2). Iron doors / trapdoors do not react to clicks. null for everything else.
         */
        private static int[] clickVal(BlockState s) {
            Block b = s.getBlock();
            if (s.contains(Properties.NOTE)) return new int[]{s.get(Properties.NOTE), 25};
            if (b instanceof LeverBlock && s.contains(Properties.POWERED)) return new int[]{s.get(Properties.POWERED) ? 1 : 0, 2};
            if ((b instanceof DoorBlock || b instanceof TrapdoorBlock) && (b == Blocks.IRON_DOOR || b == Blocks.IRON_TRAPDOOR)) return null;
            if ((b instanceof DoorBlock || b instanceof TrapdoorBlock || b instanceof FenceGateBlock) && s.contains(Properties.OPEN))
                return new int[]{s.get(Properties.OPEN) ? 1 : 0, 2};
            if (b instanceof RepeaterBlock && s.contains(Properties.DELAY)) return new int[]{s.get(Properties.DELAY) - 1, 4};
            if (b instanceof ComparatorBlock && s.contains(Properties.COMPARATOR_MODE)) return new int[]{s.get(Properties.COMPARATOR_MODE).ordinal(), 2};
            return null;
        }

        /**
         * Blocks whose state depends on WHICH face is clicked: wall torches / ladders / wall signs / banners / hooks take the clicked side as their
         * facing, standing torches need the top face, levers / buttons / grindstones follow their floor / wall / ceiling attachment.
         * Returns the face of the neighbour that has to be clicked, or null when it does not matter.
         */
        private static Direction clickFace(BlockState s) {
            String id = Registries.BLOCK.getId(s.getBlock()).getPath();
            boolean hf = s.contains(Properties.HORIZONTAL_FACING);
            Block blk = s.getBlock();
            // hopper: FACING = opposite of the clicked face (a click on the top / bottom gives DOWN)
            if (blk instanceof HopperBlock && s.contains(Properties.HOPPER_FACING)) return s.get(Properties.HOPPER_FACING).getOpposite();
            // these point away from the face they are placed on
            if (s.contains(Properties.FACING) && (blk instanceof EndRodBlock || blk instanceof LightningRodBlock
                    || blk instanceof AmethystClusterBlock || blk instanceof ShulkerBoxBlock))
                return s.get(Properties.FACING);
            if (hf && (id.equals("ladder") || id.equals("tripwire_hook") || id.contains("wall_torch") || id.endsWith("wall_sign")
                    || id.endsWith("wall_banner") || id.endsWith("wall_head") || id.endsWith("wall_skull") || id.endsWith("coral_wall_fan")))
                return s.get(Properties.HORIZONTAL_FACING);
            if (id.endsWith("torch") && !id.contains("wall")) return Direction.UP;
            for (Property<?> pr : s.getProperties()) {
                if (!pr.getName().equals("face")) continue;
                String v = String.valueOf(s.get(pr)).toLowerCase();
                if (v.equals("floor")) return Direction.UP;
                if (v.equals("ceiling")) return Direction.DOWN;
                if (v.equals("wall") && hf) return s.get(Properties.HORIZONTAL_FACING);
            }
            return null;
        }

        private static boolean top(BlockState s) {
            return (s.contains(Properties.SLAB_TYPE) && s.get(Properties.SLAB_TYPE) == SlabType.TOP)
                    || (s.contains(Properties.BLOCK_HALF) && s.get(Properties.BLOCK_HALF) == BlockHalf.TOP);
        }

        private static boolean bottom(BlockState s) {
            return (s.contains(Properties.SLAB_TYPE) && s.get(Properties.SLAB_TYPE) == SlabType.BOTTOM)
                    || (s.contains(Properties.BLOCK_HALF) && s.get(Properties.BLOCK_HALF) == BlockHalf.BOTTOM);
        }

        private static Direction faceOf(BlockPos pos, Vec3d hit) {
            Vec3d d = hit.subtract(Vec3d.ofCenter(pos));
            double ax = Math.abs(d.x), ay = Math.abs(d.y), az = Math.abs(d.z);
            if (ax >= ay && ax >= az) return d.x > 0 ? Direction.EAST : Direction.WEST;
            if (ay >= az) return d.y > 0 ? Direction.UP : Direction.DOWN;
            return d.z > 0 ? Direction.SOUTH : Direction.NORTH;
        }

        private Plan plan(int i, Vec3d eye) { return plan(i, eye, false, 0); }

        private Plan plan(int i, Vec3d eye, boolean ignoreFacing) { return plan(i, eye, ignoreFacing, 0); }

        /** Blocks that open a screen / toggle when right clicked: clicking against them would not place anything. */
        private static boolean interactive(BlockState s) {
            Block b = s.getBlock();
            return b instanceof BlockWithEntity || b instanceof CraftingTableBlock || b instanceof DoorBlock || b instanceof TrapdoorBlock
                    || b instanceof FenceGateBlock || b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof AnvilBlock
                    || b instanceof NoteBlock;
        }

        /** Horizontal direction you face when looking from eye to hit; null when that is too close to a diagonal / straight up or down to be reliable. */
        private static Direction facingFrom(Vec3d eye, Vec3d hit) {
            double dx = hit.x - eye.x, dz = hit.z - eye.z, ax = Math.abs(dx), az = Math.abs(dz), mx = Math.max(ax, az);
            if (mx < 0.6 || Math.abs(ax - az) < 0.15 * mx) return null;
            return ax > az ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        }

        /** margin > 0 = planning for a spot we have not reached yet: shrink the reach so small position errors on arrival do not matter. */
        private Plan plan(int i, Vec3d eye, boolean ignoreFacing, double margin) {
            Litematic.Entry e = view.get(i);
            BlockPos wp = world(e);
            double rr = reach.get() - margin;
            if (Vec3d.ofCenter(wp).squaredDistanceTo(eye) > (rr + 1.0) * (rr + 1.0)) return null;
            if (outOfRange(e.y())) return null;
            BlockState want = e.state(), cur = mc.world.getBlockState(wp);
            if (sameState(want, cur) || gaveUp.contains(wp)) {
                // the client shows a placed block immediately, before the server agreed: do not flag it done until a few ticks have passed
                Integer pt = placedTick.get(wp);
                if (pt != null && walkClock - pt < 8 && !gaveUp.contains(wp)) return null;
                if (!done.get(i)) { done.set(i); doneCount++; }
                if (!tune.isEmpty()) tune.remove(wp);
                return null;
            }
            if (skipState(want)) { done.set(i); return null; }
            if (wantsWaterSrc(want)) return waterPlan(i, wp, want, cur, eye, rr);                       // water source: bucket
            if (wantsLavaSrc(want)) return lavaPlan(i, wp, want, cur, eye, rr, margin);                 // lava source: lava bucket
            if (wantsWaterlog(want) && sameDry(want, cur)) return waterPlan(i, wp, want, cur, eye, rr);   // block stands, still dry: bucket on the block
            if (needsTune(want, cur)) return tunePlan(i, wp, want, cur, eye, rr, margin);
            if (wantsDouble(want, cur)) return slabPlan(i, wp, want, cur, eye, rr, margin);
            if (!cur.isReplaceable()) { noteWrong(wp, want, cur); return null; }
            Item item = want.getBlock().asItem();
            if (item == Items.AIR) { done.set(i); return null; }
            if (!canGet(item)) return null;
            if (!supported(want, wp)) return null;   // nothing to stand on / attach to yet: the block below / beside it comes first
            // never place a solid block into the space we are standing in (the server refuses it and the block "ghosts")
            if (margin == 0 && !want.getCollisionShape(mc.world, wp).isEmpty() && mc.player.getBoundingBox().intersects(new Box(wp))) return null;
            return faces(i, wp, want, item, eye, ignoreFacing, rr);
        }

        /**
         * Would the game accept this block here right now? Uses the block's own placement rule (carpets, rails, redstone, plates, torches, doors, plants ...
         * need their support), and sand / gravel / concrete powder / anvils need something under them or they fall.
         */
        private boolean supported(BlockState want, BlockPos wp) {
            try {
                if (want.getBlock() instanceof FallingBlock && FallingBlock.canFallThrough(mc.world.getBlockState(wp.down()))) return false;
                return want.canPlaceAt(mc.world, wp);
            } catch (Throwable t) { return true; }   // never block building because of a rule we could not evaluate
        }

        /** Lava source with a lava bucket: click a solid neighbour so the lava lands in wp (lava never fills waterlogged blocks, it goes next to them). */
        private Plan lavaPlan(int i, BlockPos wp, BlockState want, BlockState cur, Vec3d eye, double rr, double margin) {
            if (!canGet(Items.LAVA_BUCKET) || !fluidReady(i)) return null;
            if (!cur.isReplaceable()) return null;   // something solid stands there
            if (margin == 0 && mc.player.getBoundingBox().intersects(new Box(wp).expand(0.3))) return null;   // never put lava into the space we stand in
            return faces(i, wp, want, Items.LAVA_BUCKET, eye, true, rr);
        }

        /**
         * Water with a bucket. Source water: click a solid neighbour so the water lands in wp. Waterlogged block: aim at the block itself (a bucket used on a
         * waterloggable block fills that block). Whether the click really lands on wp is checked again with the real ray in bucketPlace().
         */
        private Plan waterPlan(int i, BlockPos wp, BlockState want, BlockState cur, Vec3d eye, double rr) {
            if (!canGet(Items.WATER_BUCKET) || !fluidReady(i)) return null;
            if (wantsWaterSrc(want)) {
                if (!cur.isReplaceable()) return null;   // something solid stands there
                return faces(i, wp, want, Items.WATER_BUCKET, eye, true, rr);
            }
            var shape = cur.getOutlineShape(mc.world, wp);
            if (shape.isEmpty()) return null;
            Vec3d aim = shape.getBoundingBox().getCenter().add(wp.getX(), wp.getY(), wp.getZ());
            if (eye.distanceTo(aim) > rr) return null;
            if (ray.get()) {
                BlockHitResult rh = mc.world.raycast(new RaycastContext(eye, aim, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
                if (rh.getType() != HitResult.Type.BLOCK || !rh.getBlockPos().equals(wp)) return null;
            }
            return new Plan(i, wp, wp, Direction.UP, aim, Items.WATER_BUCKET, want);
        }

        /**
         * A block that stands right but is set wrong (note, lever, open door, repeater delay, ...): every right click advances it by one step, so it is
         * clicked (want - now) times, not broken. Notes are not predicted by the client, so we count our own clicks and only trust the world again once
         * nothing has been in flight for a while. Aimed at the middle of the block's real outline (a lever is tiny), the click itself is checked in tuneClick().
         */
        private Plan tunePlan(int i, BlockPos wp, BlockState want, BlockState cur, Vec3d eye, double rr, double margin) {
            BlockPos key = wp.toImmutable();
            int[] cv = clickVal(cur), wv = clickVal(want);
            int mod = wv[1];
            int[] t = tune.computeIfAbsent(key, k -> new int[]{cv[0], -1000, 0});
            if (walkClock - t[1] >= 30) {            // nothing in flight any more: believe the world
                if (t[1] > 0 && cv[0] != t[0]) t[2]++;   // some clicks got lost
                t[0] = cv[0];
                if (t[2] > 4) { gaveUp.add(key); tune.remove(key); return null; }
            }
            if (Math.floorMod(wv[0] - t[0], mod) == 0) return null;   // all clicks sent, waiting for the server
            // sneaking with something in the hand would place that item next to the block instead of using it
            if (margin == 0 && mc.player.isSneaking() && !mc.player.getMainHandStack().isEmpty()) return null;
            Item hand = mc.player.getMainHandStack().getItem();
            var shape = cur.getOutlineShape(mc.world, wp);
            Vec3d aim = shape.isEmpty() ? Vec3d.ofCenter(wp) : shape.getBoundingBox().getCenter().add(wp.getX(), wp.getY(), wp.getZ());
            if (eye.distanceTo(aim) > rr) return null;
            if (ray.get()) {
                BlockHitResult rh = mc.world.raycast(new RaycastContext(eye, aim, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
                if (rh.getType() != HitResult.Type.BLOCK || !rh.getBlockPos().equals(wp)) return null;
            }
            return new Plan(i, wp, wp, Direction.UP, aim, hand, want);
        }

        /** Second half of a double slab: click the free side of the half that is already there (against == the slab's own position). */
        private Plan slabPlan(int i, BlockPos wp, BlockState want, BlockState cur, Vec3d eye, double rr, double margin) {
            Item item = want.getBlock().asItem();
            if (item == Items.AIR || !canGet(item)) return null;
            if (margin == 0 && mc.player.getBoundingBox().intersects(new Box(wp))) return null;
            boolean curBottom = cur.get(Properties.SLAB_TYPE) == SlabType.BOTTOM;
            List<Direction> opts = new ArrayList<>();
            if (curBottom ? eye.y > wp.getY() + 1.0 : eye.y < wp.getY()) opts.add(curBottom ? Direction.UP : Direction.DOWN);
            opts.add(Direction.NORTH); opts.add(Direction.SOUTH); opts.add(Direction.EAST); opts.add(Direction.WEST);
            Plan best = null; double bd = Double.MAX_VALUE;
            for (Direction face : opts) {
                double yOff = face.getAxis().isHorizontal() ? (curBottom ? 0.25 : -0.25) : face.getOffsetY() * 0.5;
                Vec3d hit = Vec3d.ofCenter(wp).add(face.getOffsetX() * 0.5, yOff, face.getOffsetZ() * 0.5);
                Vec3d fv = new Vec3d(face.getOffsetX(), face.getOffsetY(), face.getOffsetZ());
                if (eye.subtract(hit).dotProduct(fv) <= 0) continue;
                double dd = eye.distanceTo(hit);
                if (dd > rr) continue;
                if (ray.get()) {
                    BlockHitResult rh = mc.world.raycast(new RaycastContext(eye, hit, RaycastContext.ShapeType.COLLIDER,
                            RaycastContext.FluidHandling.NONE, mc.player));
                    if (rh.getType() == HitResult.Type.BLOCK && !rh.getBlockPos().equals(wp)) continue;
                }
                if (dd < bd) { bd = dd; best = new Plan(i, wp, wp, face, hit, item, want); }
            }
            return best;
        }

        /** Finds the face to click (and where) so that `want` ends up at wp. i = schematic entry or -1 for filler blocks. */
        private Plan faces(int i, BlockPos wp, BlockState want, Item item, Vec3d eye, boolean ignoreFacing, double rr) {
            Direction need = needFacing(want);
            Direction look = matchFacing.get() ? needLook(want) : null;
            Direction onlyFace = clickFace(want);
            Direction.Axis axis = want.contains(Properties.AXIS) ? want.get(Properties.AXIS) : null;

            Plan best = null; double bd = Double.MAX_VALUE;
            for (Direction d : Direction.values()) {
                BlockPos n = wp.offset(d);
                BlockState ns = mc.world.getBlockState(n);
                if (ns.isReplaceable() || !ns.getFluidState().isEmpty() || interactive(ns)) continue;
                if (item == Items.WATER_BUCKET && ns.getBlock() instanceof FluidFillable) continue;
                Direction face = d.getOpposite();
                if (axis != null && face.getAxis() != axis) continue;
                if (onlyFace != null && face != onlyFace) continue;
                // clicking the top of a block always gives the bottom half, clicking the underside always the top half
                if (face == Direction.UP && top(want)) continue;
                if (face == Direction.DOWN && bottom(want)) continue;
                double yOff = face.getAxis().isHorizontal() ? (top(want) ? 0.25 : bottom(want) ? -0.25 : 0) : 0;
                Vec3d hit = Vec3d.ofCenter(n).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5 + yOff, face.getOffsetZ() * 0.5);
                if (!ignoreFacing && matchFacing.get() && need != null && facingFrom(eye, hit) != need) continue;
                if (!ignoreFacing && look != null && lookFrom3(eye, hit) != look) continue;
                Vec3d fv = new Vec3d(face.getOffsetX(), face.getOffsetY(), face.getOffsetZ());
                if (eye.subtract(hit).dotProduct(fv) <= 0) continue;
                double dd = eye.distanceTo(hit);
                if (dd > rr) continue;
                if (ray.get()) {
                    BlockHitResult rh = mc.world.raycast(new RaycastContext(eye, hit, RaycastContext.ShapeType.COLLIDER,
                            RaycastContext.FluidHandling.NONE, mc.player));
                    if (rh.getType() == HitResult.Type.BLOCK && !rh.getBlockPos().equals(n)) continue;
                }
                if (dd < bd) { bd = dd; best = new Plan(i, wp, n, face, hit, item, want); }
            }
            return best;
        }

        // ------------------------------------------------------------------ filler blocks
        private Item fillerPreferred() {
            return switch (fillerBlock.value) {
                case "Dirt" -> Items.DIRT;
                case "Stone" -> Items.STONE;
                case "Netherrack" -> Items.NETHERRACK;
                case "Cobbled Deepslate" -> Items.COBBLED_DEEPSLATE;
                case "Oak Planks" -> Items.OAK_PLANKS;
                default -> Items.COBBLESTONE;
            };
        }

        /** The filler item to use now: the chosen one, else any cheap block we have (cached per tick). Null when there is none / the feature is off. */
        private Item fillerUse() {
            if (!filler.get() || mc.player == null) return null;
            if (fillCacheTick == walkClock) return fillCache;
            Item res = null;
            Item pref = fillerPreferred();
            if (canGet(pref)) res = pref;
            else for (Item it : new Item[]{Items.COBBLESTONE, Items.DIRT, Items.STONE, Items.NETHERRACK, Items.COBBLED_DEEPSLATE, Items.OAK_PLANKS})
                if (canGet(it)) { res = it; break; }
            fillCache = res; fillCacheTick = walkClock;
            return res;
        }

        private boolean fillerOn() { return fillerUse() != null; }

        /** Is this world position one of the schematic's blocks (built or not)? */
        private boolean schemCell(BlockPos w) {
            BlockPos b = base();
            if (b == null) return false;
            return idxAt(w.getX() - b.getX(), w.getY() - b.getY(), w.getZ() - b.getZ()) >= 0;
        }

        private boolean hasSupport(BlockPos wp) {
            for (Direction d : Direction.values()) {
                BlockState ns = mc.world.getBlockState(wp.offset(d));
                if (!ns.isReplaceable() && ns.getFluidState().isEmpty()) return true;
            }
            return false;
        }

        /** Plan for putting a filler block at wp (no facing needed). Null when there is nothing to click against from that eye position. */
        private Plan fillPlan(BlockPos wp, Vec3d eye, double margin) {
            Item it = fillerUse();
            if (it == null) return null;
            BlockState cur = mc.world.getBlockState(wp);
            if (!cur.isReplaceable() || isLava(wp)) return null;
            if (margin == 0 && mc.player.getBoundingBox().intersects(new Box(wp))) return null;
            double rr = reach.get() - margin;
            if (Vec3d.ofCenter(wp).squaredDistanceTo(eye) > (rr + 1.0) * (rr + 1.0)) return null;
            return faces(-1, wp, Blocks.COBBLESTONE.getDefaultState(), it, eye, true, rr);
        }

        /** Could this feet cell be made standable by putting a filler block under it? */
        private boolean bridgeOk(BlockPos feet) {
            if (!passable(feet) || !passable(feet.up())) return false;
            if (climbable(feet) || isWater(feet)) return false;
            BlockPos fl = feet.down();
            BlockState fs = mc.world.getBlockState(fl);
            if (!fs.isReplaceable() || !fs.getFluidState().isEmpty()) return false;
            if (schemCell(fl) || isLava(fl.down())) return false;
            return !lavaNear(feet);
        }

        /** The next cell on the route has nothing under it: stand still, crouch and put a filler block there (or under that spot first). */
        private boolean bridgeStep(BlockPos wp) {
            var p = mc.player;
            if (!fillerOn() || !bridgeOk(wp)) { bridgeTries = 0; return false; }
            mc.options.forwardKey.setPressed(false); mc.options.backKey.setPressed(false);
            mc.options.leftKey.setPressed(false); mc.options.rightKey.setPressed(false);
            mc.options.jumpKey.setPressed(false); mc.options.sneakKey.setPressed(true);
            keysHeld = true;
            bridgeBusy = true;
            walkStatus = "Bridging with filler";
            if (cd > 0) return true;
            if (++bridgeTries > 80) { bridgeTries = 0; bridgeBusy = false; dropPath(200); return false; }
            BlockPos floor = wp.down();
            Plan pl = fillPlan(floor, p.getEyePos(), 0);
            if (pl == null) {
                BlockPos under = floor.down();
                BlockState us = mc.world.getBlockState(under);
                if (us.isReplaceable() && us.getFluidState().isEmpty() && !schemCell(under)) { floor = under; pl = fillPlan(floor, p.getEyePos(), 0); }
            }
            if (pl == null) {
                if (bridgeTries < 25) return true;
                bridgeBusy = false;
                return false;
            }
            if (placeOne(pl)) { fillers.add(floor.toImmutable()); cd = delay.getInt(); }
            return true;
        }

        /**
         * Nothing can be placed because the next pending block has nothing to click against (floating part, or the schematic starts in the air):
         * returns the next cell of a filler column (built bottom-up from the first solid block under it) that will give it a support.
         */
        private BlockPos nextFillTarget() {
            if (fillTarget != null && fillScan-- > 0 && mc.world.getBlockState(fillTarget).isReplaceable()) return fillTarget;
            fillScan = 20;
            if (!fillerOn()) return null;
            Vec3d pe = mc.player.getEyePos();
            while (firstPending < view.size() && done.get(firstPending)) firstPending++;
            int best = -1, layer = Integer.MIN_VALUE, scanned = 0;
            final int ly = layerOn() ? layerY() : Integer.MIN_VALUE;
            double bd = Double.MAX_VALUE;
            for (int i = firstPending; i < view.size(); i++) {
                if (done.get(i)) continue;
                Litematic.Entry e = view.get(i);
                if (ly != Integer.MIN_VALUE) { if (e.y() < ly) continue; if (e.y() > ly) break; }
                else if (layer != Integer.MIN_VALUE && order.is("Bottom-up") && e.y() > layer) break;
                if (++scanned > 30000) break;
                if (bad(i)) continue;
                BlockPos wp = world(e);
                BlockState want = e.state(), cur = mc.world.getBlockState(wp);
                if (cur.getBlock() == want.getBlock() || skipState(want) || !cur.isReplaceable()) continue;
                Item item = want.getBlock().asItem();
                if (item == Items.AIR || !canGet(item) || hasSupport(wp)) continue;
                if (layer == Integer.MIN_VALUE) layer = e.y();
                double d = Vec3d.ofCenter(wp).squaredDistanceTo(pe);
                if (d < bd) { bd = d; best = i; }
            }
            fillFor = best;
            if (best < 0) return null;
            BlockPos wp = world(view.get(best));
            int bottom = Math.max(mc.world.getBottomY(), wp.getY() - 40);
            BlockPos cell = null;
            for (int y = wp.getY() - 1; y >= bottom; y--) {
                BlockPos q = new BlockPos(wp.getX(), y, wp.getZ());
                if (!mc.world.getBlockState(q).isReplaceable()) { cell = q.up(); break; }
            }
            if (cell == null) { walkStatus = "No ground under the schematic"; park(best, 400); return null; }
            if (schemCell(cell) || isLava(cell)) { park(best, 400); return null; }
            return cell.toImmutable();
        }

        private boolean fillGoal(BlockPos ft, BlockPos c) {
            if (c.equals(ft) || c.up().equals(ft) || pending(c) || pending(c.up())) return false;
            Vec3d eye = new Vec3d(c.getX() + 0.5, c.getY() + 1.62, c.getZ() + 0.5);
            return fillPlan(ft, eye, 0.6) != null;
        }

        /** No block can be placed: walk to a spot from where the next filler cell can be placed. False when none is needed / already in reach. */
        private boolean fillWalk(BlockPos feet) {
            BlockPos ft = nextFillTarget();
            fillTarget = ft;
            if (ft == null) return false;
            if (fillPlan(ft, mc.player.getEyePos(), 0) != null) return false;   // in reach: fillerPlace() does it
            List<BlockPos> np = findPath(feet, c -> fillGoal(ft, c), 6000);
            if (np == null) { walkStatus = "No route to the filler spot"; walkCd = 40; return false; }
            if (np.isEmpty()) return false;
            path = np; pathIdx = 0; stuck = 0; pathTicks = 0; lastPos = null; walkIdx = -1; walkChest = null;
            return true;
        }

        private boolean fillerPlace() {
            if (fillTarget == null || !fillerOn()) return false;
            BlockPos ft = fillTarget;
            if (!mc.world.getBlockState(ft).isReplaceable()) { fillTarget = null; return false; }
            Plan pl = fillPlan(ft, mc.player.getEyePos(), 0);
            if (pl == null) return false;
            nextPos = ft;
            walkStatus = "Placing filler";
            if (placeOne(pl)) {
                fillers.add(ft.toImmutable()); fillFail = 0; cd = delay.getInt();
                if (tooManyClicks(~ft.hashCode())) { fillTarget = null; park(fillFor, 300); }   // the server keeps removing the filler block
            }
            else if (++fillFail > 60) { fillFail = 0; fillTarget = null; park(fillFor, 300); }
            return true;
        }

        // ---- taking blocks away again: wrong blocks right away, filler once everything is built
        private boolean allDone() { return !view.isEmpty() && done.cardinality() >= view.size(); }

        /** Everything we want to break now. */
        private Set<BlockPos> removeSet() {
            if (removeCacheTick == walkClock) return removeCache;
            pruneJunk();
            fillers.removeIf(f -> mc.world.getBlockState(f).isReplaceable());
            Set<BlockPos> r = new HashSet<>(junk);
            if (cleanFiller.get() && allDone()) r.addAll(fillers);
            else if (cleanAsYouGo.get() && chestPos == null && walkChest == null) for (BlockPos f : fillers) if (!fillerNeeded(f)) r.add(f);
            removeCache = r; removeCacheTick = walkClock;
            return r;
        }

        /** A filler block is still needed while something of the schematic is waiting near it, the route runs over it, or it is the current filler target. */
        private boolean fillerNeeded(BlockPos f) {
            if (fillTarget != null && Math.abs(fillTarget.getX() - f.getX()) + Math.abs(fillTarget.getY() - f.getY()) + Math.abs(fillTarget.getZ() - f.getZ()) <= 3) return true;
            for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -2; dz <= 2; dz++)
                if (pending(f.add(dx, dy, dz))) return true;
            for (int j = Math.max(0, pathIdx - 1); j < path.size(); j++) {
                BlockPos c = path.get(j);
                if (c.equals(f) || c.down().equals(f) || c.up().equals(f)) return true;
            }
            return false;
        }

        private boolean breakGoal(BlockPos f, BlockPos c) {
            if (c.equals(f) || c.up().equals(f) || c.down().equals(f)) return false;
            Vec3d eye = new Vec3d(c.getX() + 0.5, c.getY() + 1.62, c.getZ() + 0.5), ctr = Vec3d.ofCenter(f);
            if (eye.distanceTo(ctr) > reach.get() - 0.8) return false;
            BlockHitResult rh = mc.world.raycast(new RaycastContext(eye, ctr, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
            return rh.getType() != HitResult.Type.BLOCK || rh.getBlockPos().equals(f);
        }

        /** A block of the set that we can break from where we stand (not the one under / in us). */
        private BlockPos breakable(Collection<BlockPos> set) {
            var p = mc.player;
            Vec3d eye = p.getEyePos();
            BlockPos fb = p.getBlockPos();
            BlockPos best = null; double bd = Double.MAX_VALUE;
            for (BlockPos f : set) {
                if (f.equals(fb) || f.equals(fb.up()) || f.equals(fb.down())) continue;
                Vec3d ctr = Vec3d.ofCenter(f);
                double d = eye.squaredDistanceTo(ctr);
                if (d > (reach.get() - 0.4) * (reach.get() - 0.4) || d >= bd) continue;
                BlockHitResult rh = mc.world.raycast(new RaycastContext(eye, ctr, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
                if (rh.getType() == HitResult.Type.BLOCK && !rh.getBlockPos().equals(f)) continue;
                best = f; bd = d;
            }
            return best;
        }

        private boolean cleanWalk(BlockPos feet) {
            Set<BlockPos> rs = removeSet();
            if (rs.isEmpty()) return false;
            if (breakable(rs) != null) return false;   // fillerCleanup() takes it from here
            Vec3d eye = mc.player.getEyePos();
            List<BlockPos> list = new ArrayList<>(rs);
            list.sort(Comparator.comparingDouble(f -> Vec3d.ofCenter(f).squaredDistanceTo(eye)));
            if (list.size() > 12) list = new ArrayList<>(list.subList(0, 12));
            final List<BlockPos> targets = list;
            List<BlockPos> np = findPath(feet, c -> {
                for (BlockPos f : targets) if (breakGoal(f, c)) return true;
                return false;
            }, 6000);
            if (np == null) {   // cannot get to them: forget them (wrong blocks are accepted as they are)
                for (BlockPos t : targets) { if (junk.remove(t)) gaveUp.add(t); fillers.remove(t); }
                return false;
            }
            if (np.isEmpty()) return false;
            path = np; pathIdx = 0; stuck = 0; pathTicks = 0; lastPos = null; walkIdx = -1; walkChest = null;
            walkStatus = junk.isEmpty() ? "Walking to filler" : "Walking to wrong block";
            return true;
        }

        private void selectTool(BlockState st) {
            if (mc.player.isCreative()) return;
            var inv = mc.player.getInventory();
            int best = inv.getSelectedSlot();
            float bv = inv.getStack(best).getMiningSpeedMultiplier(st);
            for (int sl = 0; sl < 9; sl++) {
                float v = inv.getStack(sl).getMiningSpeedMultiplier(st);
                if (v > bv + 0.01f) { bv = v; best = sl; }
            }
            if (best != inv.getSelectedSlot()) inv.setSelectedSlot(best);
        }

        private boolean fillerCleanup() {
            var p = mc.player;
            Set<BlockPos> rs = removeSet();
            if (rs.isEmpty()) return false;
            BlockPos f = breakable(rs);
            if (f == null) return false;
            nextPos = f;
            lastAct = walkClock;
            walkStatus = junk.contains(f) ? "Removing wrong block" : "Removing filler";
            Vec3d eye = p.getEyePos();
            float[] t = U.rotTo(Vec3d.ofCenter(f));
            float[] r = glide(p.getYaw(), p.getPitch(), t[0], t[1], aimSpeed.get(), maxTurn.get(), humanize.get(), true);
            p.setYaw(r[0]); p.setPitch(r[1]);
            Vec3d end = eye.add(p.getRotationVec(1.0F).multiply(reach.get() + 0.5));
            BlockHitResult rh = mc.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, p));
            if (rh.getType() == HitResult.Type.BLOCK && rh.getBlockPos().equals(f)) {
                selectTool(mc.world.getBlockState(f));
                mc.interactionManager.updateBlockBreakingProgress(f, rh.getSide());
                p.swingHand(Hand.MAIN_HAND);
            }
            return true;
        }

        private Plan pick(Set<BlockPos> skip) {
            Vec3d eye = mc.player.getEyePos();
            if (curIdx >= 0 && curIdx < view.size() && !done.get(curIdx) && !bad(curIdx) && (!layerOn() || view.get(curIdx).y() == layerY())) {
                Plan pl = plan(curIdx, eye);
                if (pl != null && !skip.contains(pl.pos())) return pl;
            }
            curIdx = -1;
            while (firstPending < view.size() && done.get(firstPending)) firstPending++;
            BlockPos b = base();
            int lowY = (int) Math.floor(eye.y - reach.get() - 2) - b.getY(), highY = (int) Math.ceil(eye.y + reach.get() + 2) - b.getY();
            int lo = 0, hi = view.size();
            while (lo < hi) { int m = (lo + hi) >>> 1; if (view.get(m).y() < lowY) lo = m + 1; else hi = m; }
            Plan best = null; double bs = Double.MAX_VALUE; int bestY = Integer.MIN_VALUE;
            final int ly = layerOn() ? layerY() : Integer.MIN_VALUE;
            for (int i = Math.max(lo, firstPending); i < view.size(); i++) {
                Litematic.Entry e = view.get(i);
                if (e.y() > highY) break;
                if (ly != Integer.MIN_VALUE) { if (e.y() < ly) continue; if (e.y() > ly) break; }
                if (best != null && order.is("Bottom-up") && e.y() > bestY) break;
                if (done.get(i) || bad(i)) continue;
                double qx = b.getX() + e.x() + 0.5 - eye.x, qy = b.getY() + e.y() + 0.5 - eye.y, qz = b.getZ() + e.z() + 0.5 - eye.z, qr = reach.get() + 1.0;
                if (qx * qx + qy * qy + qz * qz > qr * qr) continue;
                Plan pl = plan(i, eye);
                if (pl == null || skip.contains(pl.pos())) continue;
                double s = Vec3d.ofCenter(pl.pos()).squaredDistanceTo(eye);
                if (best == null || s < bs) { best = pl; bs = s; bestY = e.y(); }
            }
            if (best != null) curIdx = best.idx();
            return best;
        }

        /** Recounts progress / missing items in slices of a few thousand blocks per tick (a full pass in one tick caused lag spikes). */
        private void recount() {
            if (base() == null) { recPos = -1; return; }
            if (recPos < 0) { recPos = 0; recDone = new BitSet(); recCount = 0; recWrong = 0; recNeed = new HashMap<>(); recLayerNeed = new HashMap<>(); recLayerMap = null; recSkipped = 0; recWater = 0; recLayerTot = new int[Math.max(1, vy)]; recLayerDoneN = new int[Math.max(1, vy)]; }
            int end = Math.min(view.size(), recPos + 3000);
            for (; recPos < end; recPos++) {
                Litematic.Entry e = view.get(recPos);
                BlockPos rwp = world(e);
                BlockState cur = mc.world.getBlockState(rwp);
                if (e.y() >= 0 && e.y() < recLayerTot.length) {   // per-layer counts for the layer picker (every layer, in range or not)
                    recLayerTot[e.y()]++;
                    if (sameState(e.state(), cur) || skipState(e.state()) || gaveUp.contains(rwp) || (e.state().getBlock().asItem() == Items.AIR && !wantsFluidSrc(e.state()))) recLayerDoneN[e.y()]++;
                }
                if (outOfRange(e.y())) { recDone.set(recPos); recCount++; recSkipped++; continue; }
                if (sameState(e.state(), cur) || skipState(e.state()) || gaveUp.contains(rwp)) { recDone.set(recPos); recCount++; continue; }
                if (wantsWaterSrc(e.state())) { recWater++; continue; }   // water source: needs a bucket, no item
                if (recLayerMap == null || recLayerY != e.y()) { recLayerY = e.y(); recLayerMap = recLayerNeed.computeIfAbsent(e.y(), k -> new HashMap<>()); }
                if (wantsLavaSrc(e.state())) {   // lava source: one lava bucket each (fetched from the chest like any other item)
                    recNeed.merge(Items.LAVA_BUCKET, 1, Integer::sum); recLayerMap.merge(Items.LAVA_BUCKET, 1, Integer::sum); continue;
                }
                Item it = e.state().getBlock().asItem();
                if (it == Items.AIR) { recDone.set(recPos); recCount++; continue; }   // nothing to place for it (portal, fire, ...)
                if (wantsDouble(e.state(), cur)) { recNeed.merge(it, 1, Integer::sum); recLayerMap.merge(it, 1, Integer::sum); continue; }
                if (needsTune(e.state(), cur)) continue;   // stands, only needs a right click
                if (sameDry(e.state(), cur)) { recWater++; continue; }   // block stands, only its water is missing
                if (!cur.isReplaceable()) { recWrong++; noteWrong(rwp, e.state(), cur); }
                if (wantsWaterlog(e.state())) recWater++;
                int cnt = itemsFor(e.state());
                recNeed.merge(it, cnt, Integer::sum);
                recLayerMap.merge(it, cnt, Integer::sum);
            }
            if (recPos < view.size()) return;
            recPos = -1;
            done.clear(); done.or(recDone); doneCount = recCount; firstPending = 0; wrongCount = recWrong;
            needTotal = new LinkedHashMap<>(recNeed);
            waterWork = recWater; waterOpenTick = -9999; dryOpenTick = -9999;
            layerNeed = recLayerNeed;
            rangeSkipped = recSkipped;
            layerTot = recLayerTot; layerDoneN = recLayerDoneN;
            rebuildMissing();
        }

        /**
         * The layer restocking works for: the layer being built ("Layer by layer"), or in continuous mode the lowest layer that still has a block
         * that is not parked. MIN_VALUE when "Restock current layer only" is off (whole schematic) or nothing is left.
         */
        private int restockLayer() {
            if (!layerOnly.get()) return Integer.MIN_VALUE;
            if (layerOn()) return layerY();
            while (firstPending < view.size() && done.get(firstPending)) firstPending++;
            for (int i = firstPending; i < view.size(); i++) if (!done.get(i) && !bad(i)) return view.get(i).y();
            return Integer.MIN_VALUE;
        }

        /** missing = what the builder still has to fetch: the shortfall of the current layer (or of the whole schematic when that option is off). */
        private void rebuildMissing() {
            missing.clear();
            if (mc.player == null || (creative.get() && mc.player.isCreative())) return;
            Map<Item, Integer> src = needTotal;
            if (layerOnly.get()) {
                layerCacheTick = -1;
                int ly = restockLayer();
                src = ly == Integer.MIN_VALUE ? Map.of() : layerNeed.getOrDefault(ly, Map.of());
            }
            var inv = mc.player.getInventory();
            for (var en : src.entrySet()) {
                int have = 0;
                for (int i = 0; i < 36; i++) if (inv.getStack(i).isOf(en.getKey())) have += inv.getStack(i).getCount();
                if (have < en.getValue()) missing.put(en.getKey(), en.getValue() - have);
            }
            // water jobs left and not a single bucket on you: fetch some (empty ones are filled at a water source, filled ones are used as they are)
            if (water.get() && waterWork > 0 && bucketTotal() == 0) {
                missing.put(Items.BUCKET, waterBuckets.getInt());
                missing.put(Items.WATER_BUCKET, waterBuckets.getInt());
            }
            // keep a stack of filler on you as well, otherwise bridging / support columns silently switch themselves off in survival
            if (stockFiller.get() && filler.get() && doneCount < view.size()) {
                Item fi = fillerPreferred();
                int have = countOf(fi);
                if (have < 64) missing.merge(fi, 64 - have, Integer::sum);
            }
        }

        // ------------------------------------------------------------------ Litematica bridge
        private static java.lang.reflect.Method method(Class<?> c, String name, int params) {
            for (var m : c.getMethods()) if (m.getName().equals(name) && m.getParameterCount() == params) return m;
            return null;
        }

        private static Object liteManager() throws Exception {
            return Class.forName("fi.dy.masa.litematica.data.DataManager").getMethod("getSchematicPlacementManager").invoke(null);
        }

        /**
         * Keeps Litematica's render layers in sync with the builder ("Litematica layers" setting):
         *   All layers  - Litematica renders everything.
         *   One layer   - only one layer: the one being built in Layer by layer mode, else First layer.
         *   Layer range - First layer .. Last layer (a single layer when both are equal); with no limit set Litematica is left as the user had it.
         * Done by reflection (no compile dependency); on other method names it says so once and leaves Litematica alone.
         */
        private void syncLiteLayer(int baseY) {
            try {
                String m = liteLayers.value;
                int lo = firstLayer(), hi = lastLayer();
                String want;   // ALL | SINGLE | RANGE
                if (m.equals("All layers")) want = "ALL";
                else if (m.equals("One layer")) {
                    int ly = layerOn() ? layerY() : Integer.MIN_VALUE;
                    lo = hi = ly != Integer.MIN_VALUE ? ly + 1 : lo;
                    want = "SINGLE";
                } else if (!layerLimited()) { restoreLiteLayer(); return; }
                else want = lo == hi ? "SINGLE" : "RANGE";
                String key = want + "|" + lo + "|" + hi + "|" + baseY;
                if (key.equals(liteLayerKey)) return;
                Object range = Class.forName("fi.dy.masa.litematica.data.DataManager").getMethod("getRenderLayerRange").invoke(null);
                Class<?> rc = range.getClass();
                var getMode = method(rc, "getLayerMode", 0);
                var setMode = method(rc, "setLayerMode", 1);
                if (getMode == null || setMode == null) throw new IllegalStateException("unsupported Litematica version");
                String enumName = want.equals("ALL") ? "ALL" : want.equals("SINGLE") ? "SINGLE_LAYER" : "LAYER_RANGE";
                Object mode = null;
                for (Object e : setMode.getParameterTypes()[0].getEnumConstants()) if (((Enum<?>) e).name().equals(enumName)) mode = e;
                if (mode == null) throw new IllegalStateException("no " + enumName + " mode");
                Object cur = getMode.invoke(range);
                if (!liteLayerSet) liteLayerPrevMode = cur;
                setMode.invoke(range, mode);
                int yLo = baseY + lo - 1, yHi = baseY + hi - 1;
                if (want.equals("SINGLE")) {
                    var setPos = method(rc, "setSingleBoundaryLayerPosition", 1);
                    if (setPos == null) setPos = method(rc, "setLayerSingle", 1);
                    if (setPos == null) throw new IllegalStateException("unsupported Litematica version");
                    setPos.invoke(range, yLo);
                } else if (want.equals("RANGE")) {
                    var setMin = method(rc, "setLayerRangeMin", 2); boolean two = setMin != null;
                    if (!two) setMin = method(rc, "setLayerRangeMin", 1);
                    var setMax = method(rc, two ? "setLayerRangeMax" : "setLayerRangeMax", two ? 2 : 1);
                    if (setMin == null || setMax == null) throw new IllegalStateException("unsupported Litematica version");
                    // max first, then min, then max again: each setter clamps against the other one
                    if (two) { setMax.invoke(range, yHi, true); setMin.invoke(range, yLo, true); setMax.invoke(range, yHi, true); }
                    else { setMax.invoke(range, yHi); setMin.invoke(range, yLo); setMax.invoke(range, yHi); }
                }
                liteLayerSet = true; liteLayerKey = key;
            } catch (Throwable t) {
                Throwable c = t.getCause() != null ? t.getCause() : t;
                if (!liteLayerWarned) { liteLayerWarned = true; U.chat("Could not set Litematica's render layers (" + c.getClass().getSimpleName() + ": " + c.getMessage() + "). Use its own layer controls."); }
            }
        }

        /** Gives Litematica the render mode it had before we changed it (only if we changed it). */
        private void restoreLiteLayer() {
            if (!liteLayerSet) return;
            Object prev = liteLayerPrevMode;
            liteLayerSet = false; liteLayerPrevMode = null; liteLayerKey = "";
            if (prev == null || !FabricLoader.getInstance().isModLoaded("litematica")) return;
            try {
                Object range = Class.forName("fi.dy.masa.litematica.data.DataManager").getMethod("getRenderLayerRange").invoke(null);
                var setMode = method(range.getClass(), "setLayerMode", 1);
                if (setMode != null) setMode.invoke(range, prev);
            } catch (Throwable ignored) {}
        }

        private void removeLite() {
            Object pl = litePlacement;
            litePlacement = null; liteKey = "";
            if (pl == null) return;
            try {
                Object mgr = liteManager();
                var rm = method(mgr.getClass(), "removeSchematicPlacement", 1);
                if (rm != null) rm.invoke(mgr, pl);
            } catch (Throwable ignored) {}
        }

        /** Keeps a Litematica placement in sync with the file / origin / rotation chosen here, so Litematica renders the schematic. */
        private void syncLitematica() {
            if (!useLitematica.get() || schem == null || origin == null || file.is("None")) { restoreLiteLayer(); return; }
            if (!FabricLoader.getInstance().isModLoaded("litematica")) {
                if (!liteWarned) { liteWarned = true; U.chat("Litematica is not installed - turn on \"Built-in preview\" to see the schematic."); }
                return;
            }
            if (liteWorld != mc.world) { liteWorld = mc.world; litePlacement = null; liteKey = ""; }   // Litematica drops placements when the world changes
            BlockPos b = base();
            int rot = Integer.parseInt(rotation.value) / 90;
            // Litematica rotates around the placement origin, this module rotates the box around its corner: shift the origin to match
            BlockPos lo = switch (rot) {
                case 1 -> b.add(vx - 1, 0, 0);
                case 2 -> b.add(vx - 1, 0, vz - 1);
                case 3 -> b.add(0, 0, vz - 1);
                default -> b;
            };
            String key = file.value + "|" + lo.getX() + "," + lo.getY() + "," + lo.getZ() + "|" + rot;
            syncLiteLayer(b.getY());   // the render layer is global in Litematica, so it is kept in sync even when the placement itself did not change
            if (key.equals(liteKey) || key.equals(liteFailKey)) return;
            removeLite();
            try {
                Path path = find(file.value);
                if (path == null) throw new IllegalStateException("file not found");
                Class<?> sc = Class.forName("fi.dy.masa.litematica.schematic.LitematicaSchematic");
                Object schematic = null;
                for (var m : sc.getMethods()) {
                    if (!m.getName().equals("createFromFile") || !java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                    Class<?>[] pt = m.getParameterTypes();
                    Object[] args = new Object[pt.length];
                    boolean ok = true; int strings = 0;
                    for (int k = 0; k < pt.length; k++) {
                        if (pt[k] == java.io.File.class) args[k] = path.getParent().toFile();
                        else if (pt[k] == Path.class) args[k] = path.getParent();
                        else if (pt[k] == String.class) { args[k] = strings++ == 0 ? path.getFileName().toString() : null; }
                        else if (pt[k].isPrimitive()) ok = false;
                        else args[k] = null;
                    }
                    if (!ok) continue;
                    try { schematic = m.invoke(null, args); } catch (Throwable ignored) { schematic = null; }
                    if (schematic != null) break;
                }
                if (schematic == null) throw new IllegalStateException("Litematica could not read the file (no matching createFromFile)");
                try {
                    Object holder = Class.forName("fi.dy.masa.litematica.data.SchematicHolder").getMethod("getInstance").invoke(null);
                    var add = method(holder.getClass(), "addSchematic", 2);
                    if (add != null) add.invoke(holder, schematic, true);
                } catch (Throwable ignored) {}
                Class<?> pc = Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacement");
                var create = method(pc, "createFor", 5);
                if (create == null) throw new IllegalStateException("unsupported Litematica version");
                Object placement = create.invoke(null, schematic, lo, schem.name, true, true);
                if (rot != 0) {
                    BlockRotation br = rot == 1 ? BlockRotation.CLOCKWISE_90 : rot == 2 ? BlockRotation.CLOCKWISE_180 : BlockRotation.COUNTERCLOCKWISE_90;
                    var sr = method(pc, "setRotation", 2);
                    if (sr != null) sr.invoke(placement, br, null);
                }
                Object mgr = liteManager();
                var add = method(mgr.getClass(), "addSchematicPlacement", 2);
                if (add == null) throw new IllegalStateException("unsupported Litematica version");
                add.invoke(mgr, placement, false);
                litePlacement = placement; liteKey = key; liteFailKey = "";
                U.chat("Sent " + schem.name + " to Litematica.");
            } catch (Throwable t) {
                Throwable c = t.getCause() != null ? t.getCause() : t;
                liteFailKey = key;
                U.chat("Could not hand the schematic to Litematica (" + c.getClass().getSimpleName() + ": " + c.getMessage() + "). Turn on \"Built-in preview\" instead.");
            }
        }

        // ------------------------------------------------------------------ ghost preview
        private boolean bad(int i) { Integer u = badTargets.get(i); return (u != null && u > walkClock) || outOfRange(view.get(i).y()); }

        /** Layers are 1-based for the user ("First layer" / "Last layer"); y is the 0-based schematic row. Blocks outside the range are left alone. */
        private boolean outOfRange(int y) {
            return y < firstLayer() - 1 || y > lastLayer() - 1;   // same clamping as the window (First > Last or a taller old value cannot hide everything)
        }

        /** Park a target. Every repeat doubles the time (base, 2x, 4x ... 32x) so blocks that really cannot be reached stop coming back first. */
        private void park(int idx, int baseTicks) {
            if (idx < 0) return;
            int s = strikes.merge(idx, 1, Integer::sum);
            badTargets.put(idx, walkClock + baseTicks * (1 << Math.min(s - 1, 5)));
            clicks.remove(idx);
        }

        /** Forget short parks (restock / unstick retry them) but keep the ones that already failed 3+ times. */
        private void forgetParked() { badTargets.keySet().removeIf(k -> strikes.getOrDefault(k, 0) < 3); }

        /**
         * Is restocking actually the fix? True only when a block that is still to be built needs an item you do not carry
         * (or you are completely out of filler). Before, an empty candidate list was enough to start a chest trip, so when the remaining
         * blocks were merely out of reach it walked to the chest, came back, found nothing placeable and went to the chest again.
         */
        private boolean materialShort() {
            if (walkClock - shortTick < 20) return shortCache;
            shortTick = walkClock;
            boolean res = false;
            if (filler.get() && stockFiller.get() && countOf(fillerPreferred()) == 0) res = true;
            final int ly = layerOnly.get() ? restockLayer() : (layerOn() ? layerY() : Integer.MIN_VALUE);
            while (firstPending < view.size() && done.get(firstPending)) firstPending++;
            int scanned = 0;
            for (int i = firstPending; i < view.size() && !res; i++) {
                if (done.get(i) || bad(i)) continue;
                Litematic.Entry e = view.get(i);
                if (ly != Integer.MIN_VALUE) { if (e.y() < ly) continue; if (e.y() > ly) break; }
                if (++scanned > 30000) break;
                BlockState want = e.state();
                if (skipState(want)) continue;
                BlockState cur = mc.world.getBlockState(world(e));
                if (wantsLavaSrc(want)) {   // only a lava bucket is missing
                    if (lavaJob(want, cur) && !canGet(Items.LAVA_BUCKET)) res = true;
                    continue;
                }
                if (waterJob(want, cur) && (wantsWaterSrc(want) || sameDry(want, cur))) {   // only the water is missing: a bucket is all it takes
                    if (!canGet(Items.WATER_BUCKET) && bucketTotal() == 0) res = true;
                    continue;
                }
                if (!wantsDouble(want, cur) && !cur.isReplaceable()) continue;   // a wrong block is not a material problem
                Item item = want.getBlock().asItem();
                if (item == Items.AIR) continue;
                if (!canGet(item)) res = true;
            }
            shortCache = res;
            return res;
        }

        /** True when the same key was clicked more than 5 times with less than 5 s between clicks: the server keeps refusing it (ghost block). */
        private boolean tooManyClicks(int key) {
            if (clicks.size() > 2048) clicks.values().removeIf(c -> walkClock - c[1] > 100);
            int[] ck = clicks.computeIfAbsent(key, k -> new int[2]);
            if (walkClock - ck[1] > 100) ck[0] = 0;
            ck[0]++; ck[1] = walkClock;
            return ck[0] > 5;
        }

        private int lowerBound(int y) {
            int lo = 0, hi = view.size();
            while (lo < hi) { int m = (lo + hi) >>> 1; if (view.get(m).y() < y) lo = m + 1; else hi = m; }
            return lo;
        }

        private static int colorOf(BlockState s) {
            int h = Registries.BLOCK.getId(s.getBlock()).hashCode();
            float hue = ((h >>> 2) & 1023) / 1023f * 6f, c = 0.45f + ((h >>> 14) & 7) / 7f * 0.4f;
            float x = c * (1f - Math.abs(hue % 2f - 1f)), m = 1f - c;
            float[] f = switch ((int) hue) {
                case 0 -> new float[]{c, x, 0}; case 1 -> new float[]{x, c, 0}; case 2 -> new float[]{0, c, x};
                case 3 -> new float[]{0, x, c}; case 4 -> new float[]{x, 0, c}; default -> new float[]{c, 0, x};
            };
            return 0xFF000000 | (Math.round((f[0] + m) * 255) << 16) | (Math.round((f[1] + m) * 255) << 8) | Math.round((f[2] + m) * 255);
        }

        /** Collects the nearest not-yet-built surface blocks around you; the HUD draws them as coloured outlines. */
        private void updateGhosts() {
            BlockPos b = base();
            if (b == null || view.isEmpty()) { ghosts = List.of(); return; }
            Vec3d eye = mc.player.getEyePos();
            double rng = ghostRange.get(), r2 = rng * rng;
            int lowY = (int) Math.floor(eye.y - rng) - b.getY(), highY = (int) Math.ceil(eye.y + rng) - b.getY();
            List<G> found = new ArrayList<>();
            for (int i = lowerBound(lowY); i < view.size(); i++) {
                Litematic.Entry e = view.get(i);
                if (e.y() > highY) break;
                if (done.get(i) || !surf.get(i)) continue;
                double dx = b.getX() + e.x() + 0.5 - eye.x, dy = b.getY() + e.y() + 0.5 - eye.y, dz = b.getZ() + e.z() + 0.5 - eye.z;
                double d = dx * dx + dy * dy + dz * dz;
                if (d > r2) continue;
                if (mc.world.getBlockState(world(e)).getBlock() == e.state().getBlock()) continue;
                found.add(new G(d, i));
            }
            found.sort(Comparator.comparingDouble(G::d));
            int max = ghostMax.getInt();
            List<Ghost> out = new ArrayList<>(Math.min(max, found.size()));
            for (int k = 0; k < found.size() && k < max; k++) {
                Litematic.Entry e = view.get(found.get(k).i());
                out.add(new Ghost(world(e), colorOf(e.state())));
            }
            ghosts = out;
        }

        // ------------------------------------------------------------------ walking
        private boolean isWater(BlockPos p) { return mc.world.getBlockState(p).isOf(Blocks.WATER); }

        private boolean climbable(BlockPos p) {
            BlockState s = mc.world.getBlockState(p);
            return s.isOf(Blocks.LADDER) || s.isOf(Blocks.VINE);
        }

        /** Doors and fence gates a player can open by hand (not iron). */
        private static boolean door(BlockState s) {
            return (s.getBlock() instanceof DoorBlock && !s.isOf(Blocks.IRON_DOOR)) || s.getBlock() instanceof FenceGateBlock;
        }

        /** Can the body occupy this cell? Doors (opened on the way), ladders/vines and water count; lava, fire, cobweb etc. do not. */
        private boolean passable(BlockPos p) {
            BlockState s = mc.world.getBlockState(p);
            if (door(s) || s.isOf(Blocks.LADDER) || s.isOf(Blocks.VINE) || s.isOf(Blocks.WATER)) return true;
            if (!s.getFluidState().isEmpty()) return false;
            if (s.getBlock() instanceof AbstractFireBlock || s.isOf(Blocks.COBWEB) || s.isOf(Blocks.SWEET_BERRY_BUSH) || s.isOf(Blocks.POWDER_SNOW)) return false;
            return s.getCollisionShape(mc.world, p).isEmpty();
        }

        /** Can you be at this feet cell: solid ground below, or hanging on a ladder / floating in water. */
        private boolean standable(BlockPos feet) {
            if (!passable(feet) || !passable(feet.up())) return false;
            boolean ok;
            if (climbable(feet) || isWater(feet)) ok = true;
            else {
                BlockPos g = feet.down();
                BlockState s = mc.world.getBlockState(g);
                ok = !s.getCollisionShape(mc.world, g).isEmpty() && !(s.getBlock() instanceof CactusBlock) && !s.isOf(Blocks.MAGMA_BLOCK);
            }
            return ok && !lavaNear(feet);
        }

        /** A normal walkable cell without doors, gates, ladders, vines or water: safe to cut a corner across. */
        private boolean plainCell(BlockPos f) {
            if (!standable(f) || climbable(f) || isWater(f)) return false;
            return !door(mc.world.getBlockState(f)) && !door(mc.world.getBlockState(f.up()));
        }

        private boolean isLava(BlockPos p) { return mc.world.getBlockState(p).isOf(Blocks.LAVA); }

        /** Lava beside the cell (at feet or ground level): such cells are never used on a route. */
        private boolean lavaNear(BlockPos feet) {
            for (Direction d : Direction.values()) {
                if (!d.getAxis().isHorizontal()) continue;
                BlockPos n = feet.offset(d);
                if (isLava(n) || isLava(n.down()) || isLava(n.up())) return true;
            }
            return false;
        }

        private record PN(BlockPos pos, double cost) {}

        /** Is the floor under this feet cell a block of the schematic that is already built? */
        private boolean builtFloor(BlockPos feet) {
            BlockPos b = base();
            if (b == null) return false;
            BlockPos g = feet.down();
            int i = idxAt(g.getX() - b.getX(), g.getY() - b.getY(), g.getZ() - b.getZ());
            return i >= 0 && done.get(i);
        }

        /** The search is limited to the area around the schematic (plus the area around the start) so the node budget is not wasted on the whole world. */
        private boolean inArea(BlockPos p, BlockPos start) {
            if (wideArea) {
                int rg = chestRange.getInt() + 8;
                return Math.abs(p.getX() - start.getX()) <= rg && Math.abs(p.getZ() - start.getZ()) <= rg && Math.abs(p.getY() - start.getY()) <= 40;
            }
            if (Math.abs(p.getX() - start.getX()) <= 16 && Math.abs(p.getZ() - start.getZ()) <= 16 && Math.abs(p.getY() - start.getY()) <= 12) return true;
            BlockPos b = base();
            if (b == null) return true;
            // one box around the schematic AND where we stand now, so the way back from a far chest is still inside it
            return p.getX() >= Math.min(b.getX() - 20, start.getX() - 16) && p.getX() <= Math.max(b.getX() + vx + 20, start.getX() + 16)
                    && p.getZ() >= Math.min(b.getZ() - 20, start.getZ() - 16) && p.getZ() <= Math.max(b.getZ() + vz + 20, start.getZ() + 16)
                    && p.getY() >= Math.min(b.getY() - 10, start.getY() - 12) && p.getY() <= Math.max(b.getY() + vy + 10, start.getY() + 12);
        }

        /**
         * Cheapest-route search (Dijkstra) over standable cells: flat, 1 up (jump), up to 3 down, up/down along ladders and vines, and up/down/sideways
         * through water. Standing on the part that is already built is cheap; stepping off it costs a lot, so the route stays on the built part whenever
         * that is possible. Returns the cells to walk to, empty when the start already is a goal, null when none is reachable.
         */
        private List<BlockPos> findPath(BlockPos start, Predicate<BlockPos> goal, int max) {
            final boolean bridging = fillerOn();
            PriorityQueue<PN> q = new PriorityQueue<>(Comparator.comparingDouble(PN::cost));
            Map<BlockPos, BlockPos> prev = new HashMap<>();
            Map<BlockPos, Double> best = new HashMap<>();
            q.add(new PN(start, 0)); prev.put(start, start); best.put(start, 0.0);
            int nodes = 0;
            while (!q.isEmpty() && nodes < max) {
                PN cur = q.poll();
                BlockPos c = cur.pos();
                if (cur.cost() > best.getOrDefault(c, Double.MAX_VALUE)) continue;   // outdated queue entry
                nodes++;
                if (goal.test(c)) {
                    List<BlockPos> out = new ArrayList<>();
                    for (BlockPos t = c; !t.equals(start); t = prev.get(t)) out.add(t);
                    Collections.reverse(out);
                    return out;
                }
                List<BlockPos> next = new ArrayList<>(6);
                for (Direction d : Direction.values()) {
                    if (!d.getAxis().isHorizontal()) continue;
                    BlockPos n = c.offset(d);
                    if (standable(n)) next.add(n);
                    else if (standable(n.up()) && passable(c.up(2))) next.add(n.up());
                    else if (passable(n) && passable(n.up())) {
                        for (int k = 1; k <= 3; k++) {
                            BlockPos m = n.down(k);
                            if (standable(m)) { next.add(m); break; }
                            if (!passable(m)) break;
                        }
                    }
                }
                // diagonal steps on flat ground, so a route is a straight diagonal instead of a left-right staircase. Both cells beside the corner
                // must be plain walkable cells too (no clipping a wall corner, no crossing a hole), and no doors / ladders / water are involved.
                if (diagonal.get() && !isWater(c) && !climbable(c)) {
                    for (int sx = -1; sx <= 1; sx += 2) for (int sz = -1; sz <= 1; sz += 2) {
                        BlockPos n = c.add(sx, 0, sz);
                        if (plainCell(n) && plainCell(c.add(sx, 0, 0)) && plainCell(c.add(0, 0, sz))) next.add(n);
                    }
                }
                boolean wet = isWater(c), clim = climbable(c);
                if ((wet || clim) && standable(c.up())) next.add(c.up());
                if ((wet || clim || isWater(c.down()) || climbable(c.down())) && standable(c.down())) next.add(c.down());
                // cells with nothing under them: possible when a filler block is put there (flat bridge, or a 2-block stair step up)
                Set<BlockPos> br = null;
                if (bridging) {
                    for (Direction d : Direction.values()) {
                        if (!d.getAxis().isHorizontal()) continue;
                        BlockPos n = c.offset(d), to = null;
                        if (!standable(n) && bridgeOk(n)) to = n;
                        else if (!standable(n.up()) && bridgeOk(n.up()) && passable(c.up(2))) to = n.up();
                        if (to != null) { if (br == null) br = new HashSet<>(); br.add(to); next.add(to); }
                    }
                }
                boolean onBuilt = builtFloor(c);
                for (BlockPos to : next) {
                    if (!inArea(to, start)) continue;
                    double step = (to.getX() != c.getX() && to.getZ() != c.getZ() ? 1.4142 : 1.0) + Math.abs(to.getY() - c.getY()) * 0.5;
                    if (br != null && br.contains(to)) step += to.getY() > c.getY() ? 7.0 : 4.0;
                    if (!builtFloor(to)) step += onBuilt ? 8.0 : 0.4;   // leaving the built part is expensive
                    double nc = cur.cost() + step;
                    if (nc < best.getOrDefault(to, Double.MAX_VALUE)) {
                        best.put(to, nc); prev.put(to, c);
                        q.add(new PN(to, nc));
                    }
                }
            }
            return null;
        }

        /** Can entry t be placed from standing on cell c (including the facing it needs from that angle)? */
        private boolean goalFor(int t, BlockPos c) {
            BlockPos wp = world(view.get(t));
            if (c.equals(wp) || c.up().equals(wp) || pending(c) || pending(c.up())) return false;
            Vec3d eye = new Vec3d(c.getX() + 0.5, c.getY() + 1.62, c.getZ() + 0.5);
            return plan(t, eye, false, 0.6) != null;
        }

        /** Could this block be placed at all right now (item obtainable, something to click against)? */
        private boolean wantable(int i) {
            Litematic.Entry e = view.get(i);
            BlockPos wp = world(e);
            BlockState want = e.state(), cur = mc.world.getBlockState(wp);
            if (wantsDouble(want, cur)) return canGet(want.getBlock().asItem());
            if (needsTune(want, cur)) return !gaveUp.contains(wp);
            if (wantsLavaSrc(want)) return lavaJob(want, cur) && cur.isReplaceable() && canGet(Items.LAVA_BUCKET) && fluidReady(i);
            if (wantsWaterSrc(want) || (wantsWaterlog(want) && sameDry(want, cur))) return waterJob(want, cur) && canGet(Items.WATER_BUCKET) && fluidReady(i);
            if (cur.getBlock() == want.getBlock() || skipState(want) || !cur.isReplaceable()) return false;
            Item item = want.getBlock().asItem();
            if (item == Items.AIR || !canGet(item) || !supported(want, wp)) return false;
            for (Direction d : Direction.values()) {
                BlockState ns = mc.world.getBlockState(wp.offset(d));
                if (!ns.isReplaceable() && ns.getFluidState().isEmpty()) return true;
            }
            return false;
        }

        /** The nearest placeable entries of the layer being built (up to cap): the route search tries all of them, not just one. */
        private List<Integer> candidates(int cap) {
            Vec3d pe = mc.player.getEyePos();
            while (firstPending < view.size() && done.get(firstPending)) firstPending++;
            List<G> found = new ArrayList<>();
            int layer = Integer.MIN_VALUE, scanned = 0;
            final int ly = layerOn() ? layerY() : Integer.MIN_VALUE;
            for (int i = firstPending; i < view.size(); i++) {
                if (done.get(i)) continue;
                Litematic.Entry e = view.get(i);
                if (ly != Integer.MIN_VALUE) { if (e.y() < ly) continue; if (e.y() > ly) break; }
                else if (layer != Integer.MIN_VALUE && order.is("Bottom-up") && e.y() > layer) break;
                if (++scanned > 30000) break;
                if (bad(i) || !wantable(i)) continue;
                if (layer == Integer.MIN_VALUE) layer = e.y();
                found.add(new G(Vec3d.ofCenter(world(e)).squaredDistanceTo(pe), i));
            }
            found.sort(Comparator.comparingDouble(G::d));
            List<Integer> out = new ArrayList<>();
            for (int k = 0; k < Math.min(cap, found.size()); k++) out.add(found.get(k).i());
            return out;
        }

        // ---- restock trips
        /** The other half of a double chest, or null. */
        private BlockPos partner(BlockPos bp) {
            BlockState st = mc.world.getBlockState(bp);
            if (!(st.getBlock() instanceof ChestBlock) || !st.contains(Properties.CHEST_TYPE)) return null;
            ChestType t = st.get(Properties.CHEST_TYPE);
            if (t == ChestType.SINGLE) return null;
            Direction f = st.get(Properties.HORIZONTAL_FACING);
            return bp.offset(t == ChestType.LEFT ? f.rotateYClockwise() : f.rotateYCounterclockwise());
        }

        /** One position per chest: both halves of a double chest give the same one. */
        private BlockPos canon(BlockPos bp) {
            BlockPos o = partner(bp);
            if (o == null) return bp.toImmutable();
            boolean mine = bp.getX() < o.getX() || (bp.getX() == o.getX() && bp.getZ() <= o.getZ());
            return (mine ? bp : o).toImmutable();
        }

        private void drain(BlockPos bp) { drained.put(canon(bp), walkClock + 1200); chestCacheTick = -9999; }

        private ChestMem mem(BlockPos bp) {
            BlockPos k = canon(bp);
            ChestMem m = chestMem.get(k);
            if (m == null) return null;
            if (walkClock - m.tick() > chestMemMin.getInt() * 1200) { chestMem.remove(k); return null; }   // old knowledge: look again
            return m;
        }

        private boolean memHasMissing(ChestMem m) {
            for (Item it : missing.keySet()) if (m.items().getOrDefault(it, 0) > 0) return true;
            return false;
        }

        private boolean isDrained(BlockPos bp) {
            ChestMem m = mem(bp);
            if (m != null && !missing.isEmpty() && !memHasMissing(m)) return true;   // we looked inside: it has nothing that is missing now
            BlockPos k = canon(bp);
            Integer u = drained.get(k);
            if (u == null) return false;
            if (u <= walkClock) { drained.remove(k); return false; }
            return true;
        }

        private static boolean storage(Block b) {
            return (b instanceof ChestBlock && !(b instanceof TrappedChestBlock)) || b instanceof BarrelBlock;
        }

        private int countOf(Item it) {
            var inv = mc.player.getInventory();
            int n = 0;
            for (int i = 0; i < 36; i++) if (inv.getStack(i).isOf(it)) n += inv.getStack(i).getCount();
            return n;
        }

        private int heldStacks(Item it) {
            var inv = mc.player.getInventory();
            int n = 0;
            for (int i = 0; i < 36; i++) if (inv.getStack(i).isOf(it)) n++;
            return n;
        }

        private int freeSlots() {
            var inv = mc.player.getInventory();
            int n = 0;
            for (int i = 0; i < 36; i++) if (inv.getStack(i).isEmpty()) n++;
            return n;
        }

        /** fits() plus the "Free slots to keep" reserve: topping up a partial stack never uses a new slot. */
        private boolean canTake(ItemStack st) {
            if (!fits(st)) return false;
            if (keepFree.getInt() <= 0) return true;
            var inv = mc.player.getInventory();
            for (int i = 0; i < 36; i++) { ItemStack c = inv.getStack(i); if (c.isOf(st.getItem()) && c.getCount() < c.getMaxCount()) return true; }
            return freeSlots() > keepFree.getInt();
        }

        /** Room in the inventory for (part of) this stack? */
        private boolean fits(ItemStack st) {
            var inv = mc.player.getInventory();
            for (int i = 0; i < 36; i++) {
                ItemStack c = inv.getStack(i);
                if (c.isEmpty() || (c.isOf(st.getItem()) && c.getCount() < c.getMaxCount())) return true;
            }
            return false;
        }

        /** Is there a marked chest in range that still has something (not drained)? */
        private boolean markedAvailable() {
            BlockPos pc = mc.player.getBlockPos();
            double rg = chestRange.get();
            for (BlockPos m : marked) {
                if (Math.abs(m.getX() - pc.getX()) > rg || Math.abs(m.getZ() - pc.getZ()) > rg) continue;
                if (storage(mc.world.getBlockState(m).getBlock()) && !isDrained(m)) return true;
            }
            return false;
        }

        /** May the builder take items out of the chest at bp (restock mode, marked chests, drained chests)? */
        private boolean allowedChest(BlockPos bp) {
            if (!storage(mc.world.getBlockState(bp).getBlock()) || isDrained(bp)) return false;
            if (restockFrom.is("Any nearby") || marked.contains(bp)) return true;
            if (restockFrom.is("Marked only")) return false;
            return !markedAvailable();   // "Marked, else any": other chests only when no marked chest is left
        }

        private int markedCount() {
            Set<BlockPos> s = new HashSet<>();
            for (BlockPos m : marked) s.add(canon(m));
            return s.size();
        }

        /** Puts what the schematic still needs on the clipboard (items, total, stacks) so you can paste it into a notes app or a shopping list. */
        void copyMaterials() {
            if (schem == null) { U.chat("Load a schematic first."); return; }
            if (needTotal.isEmpty()) { U.chat("Nothing left to build (or the first count is still running, try again in a second)."); return; }
            List<Map.Entry<Item, Integer>> list = new ArrayList<>(needTotal.entrySet());
            list.sort((a, b) -> b.getValue() - a.getValue());
            StringBuilder sb = new StringBuilder("Materials still needed for " + schem.name + "\n");
            long total = 0;
            for (var en : list) {
                int n = en.getValue(), ms = Math.max(1, en.getKey().getDefaultStack().getMaxCount());
                total += n;
                sb.append(en.getKey().getDefaultStack().getName().getString()).append(": ").append(n);
                if (n >= ms) sb.append("  (").append(n / ms).append(" stack").append(n / ms == 1 ? "" : "s").append(n % ms > 0 ? " + " + (n % ms) : "").append(")");
                sb.append("\n");
            }
            sb.append("Total: ").append(total).append(" items, ").append(list.size()).append(" types");
            mc.keyboard.setClipboard(sb.toString());
            U.chat("Copied the material list (" + list.size() + " item types, " + total + " items) to the clipboard.");
        }

        void markLook() {
            if (mc.player == null || mc.world == null) return;
            if (!(mc.crosshairTarget instanceof BlockHitResult h) || h.getType() != HitResult.Type.BLOCK) { U.chat("Look at a chest or barrel first."); return; }
            BlockPos bp = h.getBlockPos();
            if (!storage(mc.world.getBlockState(bp).getBlock())) { U.chat("That is not a chest or barrel."); return; }
            BlockPos o = partner(bp);
            if (marked.contains(bp)) { marked.remove(bp); if (o != null) marked.remove(o); U.chat("Unmarked chest. " + markedCount() + " marked."); }
            else { marked.add(bp.toImmutable()); if (o != null) marked.add(o.toImmutable()); U.chat("Marked chest. " + markedCount() + " marked."); }
            chestCacheTick = -9999; drained.clear();
        }

        void markNear() {
            if (mc.player == null || mc.world == null) return;
            BlockPos pc = mc.player.getBlockPos();
            int added = 0;
            for (int x = -6; x <= 6; x++) for (int y = -4; y <= 4; y++) for (int z = -6; z <= 6; z++) {
                BlockPos bp = pc.add(x, y, z);
                if (storage(mc.world.getBlockState(bp).getBlock()) && marked.add(bp.toImmutable())) added++;
            }
            chestCacheTick = -9999; drained.clear();
            U.chat(markedCount() + " chest(s) marked" + (added == 0 ? " (none new nearby)." : "."));
        }

        void clearMarks() {
            marked.clear(); markedWater.clear(); chestCacheTick = -9999; drained.clear(); waterSrcTick = -9999; waterSrcCache = List.of();
            U.chat("Marked chests and water cleared.");
        }

        /** Optional: mark the water block buckets are refilled at (look at it, press again to unmark). Without a mark the builder looks for an infinite source itself. */
        void markWater() {
            if (mc.player == null || mc.world == null) return;
            HitResult hr = mc.player.raycast(Math.max(6.0, reach.get() + 1.5), 1.0F, true);   // true = fluids count as hits
            if (!(hr instanceof BlockHitResult h) || h.getType() != HitResult.Type.BLOCK) { U.chat("Look at a water source block first."); return; }
            BlockPos bp = h.getBlockPos().toImmutable();
            if (markedWater.remove(bp)) { U.chat("Unmarked water. " + markedWater.size() + " marked."); }
            else if (!markedSource(bp)) { U.chat("That is not a still water source block (flowing water cannot be picked up)."); return; }
            else { markedWater.add(bp); U.chat("Marked water. " + markedWater.size() + " marked - buckets are refilled there."); }
            waterSrcTick = -9999; waterSrcCache = List.of();
        }

        private List<BlockPos> nearbyChests() {
            if (walkClock - chestCacheTick < 20) {
                List<BlockPos> keep = new ArrayList<>();
                for (BlockPos c : chestCache) if (!isDrained(c)) keep.add(c);
                return keep;
            }
            chestCacheTick = walkClock;
            List<BlockPos> out = new ArrayList<>();
            BlockPos pc = mc.player.getBlockPos();
            double rg = chestRange.get();
            int r = (int) Math.ceil(rg / 16.0) + 1, pcx = pc.getX() >> 4, pcz = pc.getZ() >> 4;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                WorldChunk ch = mc.world.getChunkManager().getWorldChunk(pcx + dx, pcz + dz);
                if (ch == null) continue;
                for (BlockEntity be : ch.getBlockEntities().values()) {
                    if (!(be instanceof ChestBlockEntity) && !(be instanceof BarrelBlockEntity)) continue;
                    if (be instanceof EnderChestBlockEntity || be instanceof TrappedChestBlockEntity) continue;
                    BlockPos bp = be.getPos();
                    if (Math.abs(bp.getX() - pc.getX()) > rg || Math.abs(bp.getZ() - pc.getZ()) > rg) continue;
                    if (!allowedChest(bp)) continue;
                    out.add(bp.toImmutable());
                }
            }
            Vec3d eye = mc.player.getEyePos();
            out.sort(Comparator.comparingDouble(c -> Vec3d.ofCenter(c).squaredDistanceTo(eye)));
            Set<BlockPos> seen = new HashSet<>();
            out.removeIf(c -> !seen.add(canon(c)));   // a double chest is one chest
            chestCache = out.size() > 24 ? new ArrayList<>(out.subList(0, 24)) : out;
            return chestCache;
        }

        private boolean chestGoal(BlockPos chest, BlockPos c) {
            if (c.equals(chest) || c.up().equals(chest) || pending(c) || pending(c.up())) return false;
            Vec3d eye = new Vec3d(c.getX() + 0.5, c.getY() + 1.62, c.getZ() + 0.5), ctr = Vec3d.ofCenter(chest);
            if (eye.distanceTo(ctr) > reach.get() - 0.6) return false;
            if (ray.get()) {
                BlockHitResult rh = mc.world.raycast(new RaycastContext(eye, ctr, RaycastContext.ShapeType.COLLIDER,
                        RaycastContext.FluidHandling.NONE, mc.player));
                if (rh.getType() == HitResult.Type.BLOCK && !rh.getBlockPos().equals(chest)) return false;
            }
            return true;
        }

        /** Out of material: walk to the nearest chest/barrel that has not been emptied. False when there is nothing to walk to (or you are already in reach and openChest takes over). */
        private boolean chestWalk(BlockPos feet) {
            if (layerOnly.get() && restock.get() && !mc.player.isCreative() && walkClock - missingTick >= 20) { missingTick = walkClock; rebuildMissing(); }   // the layer may have changed since the last recount
            if (!restock.get() || missing.isEmpty() || mc.player.isCreative() || mc.currentScreen != null || allDone()) return false;
            if (restockBlockedUntil > walkClock) { walkStatus = "Inventory full - make room"; return false; }
            if (restockPauseUntil > walkClock) { walkStatus = "Restock paused: nothing was built after the last chest trips"; return false; }
            if (!materialShort()) return false;   // what is left is out of reach / refused, more items would not help
            List<BlockPos> chests = nearbyChests();
            if (chests.isEmpty()) {
                walkStatus = restockFrom.is("Marked only")
                        ? (marked.isEmpty() ? "No chests marked (use Mark / unmark chest I look at)" : "No marked chest with items within " + chestRange.getInt() + " blocks")
                        : "No chest with items within " + chestRange.getInt() + " blocks";
                walkCd = 40; return false;
            }
            List<BlockPos> known = new ArrayList<>();
            for (BlockPos ch : chests) { ChestMem m = mem(ch); if (m != null && memHasMissing(m)) known.add(ch); }
            final List<BlockPos> goals = known.isEmpty() ? chests : known;   // a chest we know has it beats unexplored ones
            wideArea = true;
            List<BlockPos> np;
            try {
                np = findPath(feet, c -> {
                    for (BlockPos ch : goals) if (chestGoal(ch, c)) { foundChest = ch; return true; }
                    return false;
                }, 30000);
            } finally { wideArea = false; }
            if (np == null) { walkStatus = "No route to a chest"; walkCd = 100; return false; }
            if (np.isEmpty()) return false;
            path = np; pathIdx = 0; stuck = 0; pathTicks = 0; lastPos = null; walkIdx = -1; walkChest = foundChest;
            return true;
        }

        // ---- keys / state
        private void stopKeys() {
            if (!keysHeld) return;
            mc.options.forwardKey.setPressed(false);
            mc.options.backKey.setPressed(false);
            mc.options.leftKey.setPressed(false);
            mc.options.rightKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
            mc.options.sneakKey.setPressed(false);
            mc.options.sprintKey.setPressed(false);
            keysHeld = false;
        }

        /** True when the ground right ahead (in the walking direction) is missing: you would step off the block. */
        private boolean edgeAhead(Vec3d pos, double dx, double dz, double hd) {
            if (hd < 0.05) return false;
            double reachAhead = Math.min(0.6, hd + 0.3);
            BlockPos g = BlockPos.ofFloored(pos.x + dx / hd * reachAhead, pos.y - 0.5, pos.z + dz / hd * reachAhead);
            BlockState s = mc.world.getBlockState(g);
            return s.getCollisionShape(mc.world, g).isEmpty() && !s.isOf(Blocks.WATER) && !climbable(g);
        }

        private void resetWalk() {
            stopKeys(); path = List.of(); pathIdx = 0; walkIdx = -1; walkChest = null; stuck = 0; pathTicks = 0; walkCd = 0; doorCd = 0;
            walkStatus = ""; lastPos = null; jumpCd = 0; jumpTries = 0; sneakHold = 0; pauseTicks = 0; bestProg = 1e9;
        }

        private void dropPath(int badFor) {
            if (badFor > 0) {
                if (walkIdx >= 0) park(walkIdx, badFor);
                if (walkChest != null) drain(walkChest);   // could not get there: skip it for a minute
            }
            stopKeys(); path = List.of(); pathIdx = 0; walkIdx = -1; walkChest = null; stuck = 0; pathTicks = 0; lastPos = null;
        }

        /** Opens closed doors / fence gates on the next few cells of the route. */
        private void openDoors() {
            if (doorCd > 0) { doorCd--; return; }
            Vec3d eye = mc.player.getEyePos();
            for (int j = pathIdx; j < Math.min(path.size(), pathIdx + 3); j++) {
                BlockPos c = path.get(j);
                for (BlockPos q : new BlockPos[]{c, c.up()}) {
                    BlockState s = mc.world.getBlockState(q);
                    if (!door(s) || !s.contains(Properties.OPEN) || s.get(Properties.OPEN)) continue;
                    Vec3d ctr = Vec3d.ofCenter(q);
                    if (eye.distanceTo(ctr) > 4.2) continue;
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(ctr, faceOf(q, eye), q, false));
                    mc.player.swingHand(Hand.MAIN_HAND);
                    doorCd = 6;
                    return;
                }
            }
        }

        /** Called when nothing can be placed from where you stand. Returns true while it is busy walking. */
        private boolean walkStep() {
            var p = mc.player;
            bridgeBusy = false;
            if (!walk.get()) { resetWalk(); return false; }

            // the target got built (or the plan is stale) while we were underway
            if (!path.isEmpty() && walkIdx >= 0) {
                Litematic.Entry te = view.get(walkIdx);
                if (done.get(walkIdx) || mc.world.getBlockState(world(te)).getBlock() == te.state().getBlock()) dropPath(0);
            }

            if (path.isEmpty() || pathIdx >= path.size()) {
                stopKeys();
                if (!path.isEmpty()) { path = List.of(); pathIdx = 0; walkChest = null; }   // arrived: let pick() / openChest() work from here
                if (walkCd > 0) { walkCd--; return false; }
                walkStatus = "";
                BlockPos feet = p.getBlockPos();
                List<Integer> cand = candidates(24);
                if (cand.isEmpty()) {
                    walkIdx = -1;
                    if (fillWalk(feet)) { walkStatus = "Walking to filler spot"; return true; }
                    if (chestWalk(feet)) { walkStatus = "Walking to chest"; return true; }
                    if (waterWalk(feet)) { walkStatus = "Walking to water"; return true; }
                    if (cleanWalk(feet)) return true;
                    int skipped = (int) badTargets.values().stream().filter(u -> u > walkClock).count();
                    if (skipped > 0 && walkStatus.isEmpty()) walkStatus = skipped + " blocks skipped (cannot reach / place), retrying later";
                    if (walkCd == 0) walkCd = 4;
                    return false;
                }
                walkChest = null;
                final int[] found = {-1};
                final double rq = (reach.get() + 1.0) * (reach.get() + 1.0);
                List<BlockPos> np = findPath(feet, c -> {
                    for (int t : cand) {
                        if (Vec3d.ofCenter(world(view.get(t))).squaredDistanceTo(c.getX() + 0.5, c.getY() + 1.62, c.getZ() + 0.5) > rq) continue;
                        if (goalFor(t, c)) { found[0] = t; return true; }
                    }
                    return false;
                }, 12000);
                if (np == null) {   // none of the nearest blocks can be reached: park them and look at others next time
                    for (int t : cand) park(t, 200);
                    walkStatus = "No route to next block"; walkCd = 20; walkIdx = -1;
                    return false;
                }
                if (np.isEmpty()) {   // in reach already, yet pick() found nothing from the exact spot: park it briefly
                    if (found[0] >= 0) park(found[0], 80);
                    walkIdx = -1;
                    return false;
                }
                walkIdx = found[0]; path = np; pathIdx = 0; stuck = 0; pathTicks = 0; lastPos = null;
            }

            BlockPos wp = path.get(pathIdx);
            if (!standable(wp)) {
                if (bridgeStep(wp)) return true;   // nothing to stand on there: build a filler block first
                dropPath(0); walkCd = 1; return false;   // route changed (something got placed), plan again
            }
            openDoors();
            Vec3d pos = U.pos(p);
            double dx = wp.getX() + 0.5 - pos.x, dz = wp.getZ() + 0.5 - pos.z, hd = Math.hypot(dx, dz);
            if (hd < 0.4 && pos.y > wp.getY() - 0.3 && pos.y < wp.getY() + 1.2) {
                pathIdx++;
                stuck = 0; bestProg = 1e9; jumpTries = 0;
                if (pathIdx >= path.size()) { stopKeys(); path = List.of(); pathIdx = 0; walkChest = null; walkStatus = "Arrived"; return true; }
                wp = path.get(pathIdx);
                dx = wp.getX() + 0.5 - pos.x; dz = wp.getZ() + 0.5 - pos.z; hd = Math.hypot(dx, dz);
            }
            if (hd > 4.5 || ++pathTicks > 600) { dropPath(100); walkCd = 3; return false; }   // pushed off the route / took too long

            BlockPos fb = p.getBlockPos();
            boolean onLadder = climbable(fb) || climbable(fb.up());
            boolean ground = p.isOnGround();

            // now and then stop for a moment, like somebody glancing around (never on ladders, in water or mid-air)
            if (pauseTicks > 0) { pauseTicks--; stopKeys(); walkStatus = "Pausing"; return true; }
            if (humanize.get() && walkPauses.get() && ground && !onLadder && !p.isTouchingWater() && hd > 1.5
                    && path.size() - pathIdx > 3 && U.rand(0, 1) < 0.003) {
                pauseTicks = (int) U.rand(4, 11); stopKeys(); return true;
            }

            // Progress = getting closer to the waypoint (horizontally AND vertically). Before, only "did the position change" was
            // checked, so jumping on the spot against a wall (the Y keeps changing) never counted as stuck.
            if (lastPos == null) { bestProg = 1e9; jumpTries = 0; walkPitch = (float) U.rand(6, 18); }
            double prog = hd + Math.abs(wp.getY() - pos.y) * 0.5;
            if (prog < bestProg - 0.02) { bestProg = prog; stuck = 0; jumpTries = 0; }
            else if (++stuck > 30) { dropPath(120); walkCd = 5; return false; }
            lastPos = pos;

            // never walk into lava: stop if the next step (or what is under it) is lava
            if (hd > 1e-3) {
                BlockPos la = BlockPos.ofFloored(pos.x + dx / hd * 0.8, pos.y, pos.z + dz / hd * 0.8);
                if (isLava(la) || isLava(la.down()) || isLava(la.up())) { dropPath(300); walkCd = 10; walkStatus = "Lava ahead"; return false; }
            }

            boolean vertical = hd < 0.7 && wp.getY() != fb.getY();
            boolean up = wp.getY() > fb.getY();
            boolean drop = wp.getY() < fb.getY();

            // Jumping is a tap with a cooldown, not a held key (a held key bunny-hops for as long as the condition is true).
            // Blocked by a wall without a step to climb: at most two tries, then the stuck logic above drops the route.
            if (jumpCd > 0) jumpCd--;
            boolean hop = ground && jumpCd == 0 && ((up && hd < 1.2) || (p.horizontalCollision && !drop && jumpTries < 2));
            if (hop) { jumpCd = 9 + (int) U.rand(0, 4); jumpTries++; }
            boolean jump = hop
                    || (onLadder && up)                                   // climb
                    || (p.isTouchingWater() && wp.getY() >= fb.getY());  // swim / stay afloat

            // crouch so you do not walk off a block, unless the route itself goes down there; keep it for a few ticks so it does not flicker
            boolean edge = edgeSafety.get() && !drop && !onLadder && !p.isTouchingWater() && ground && edgeAhead(pos, dx, dz, hd);
            if (edge) sneakHold = 6;
            boolean sneak = edge || (sneakHold > 0 && ground && !drop);
            if (!edge && sneakHold > 0) sneakHold--;

            // Walking with nothing to place: turn the view gently towards where we go (instead of staring at one spot and strafing there).
            // While blocks are being placed the aim belongs to them, so this only runs when nothing is in reach.
            if (lookWalk && humanize.get() && lookWalkOpt.get() && rotate.get() && hd > 1.2 && !vertical && !onLadder && !p.isTouchingWater()) {
                float yawT = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                float[] r = glide(p.getYaw(), p.getPitch(), yawT, walkPitch, 10, 5, true, true);
                p.setYaw(r[0]); p.setPitch(r[1]);
            }

            // The keys are chosen relative to where you currently look, so aiming at a block and walking can happen at the same time.
            boolean kf = false, kb = false, kl = false, kr = false;
            if (hd > (vertical ? 0.25 : 0.12)) {
                float yawT = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                float diff = MathHelper.wrapDegrees(yawT - p.getYaw()), ad = Math.abs(diff);
                kf = ad < 67.5f;
                kb = ad > 112.5f;
                kr = diff > 22.5f && diff < 157.5f;
                kl = diff < -22.5f && diff > -157.5f;
            }
            boolean sprint = sprintWalk.get() && kf && !sneak && !onLadder && !p.isTouchingWater() && hd > 2.5
                    && path.size() - pathIdx > 4 && p.getHungerManager().getFoodLevel() > 6;
            mc.options.forwardKey.setPressed(kf);
            mc.options.backKey.setPressed(kb);
            mc.options.leftKey.setPressed(kl);
            mc.options.rightKey.setPressed(kr);
            mc.options.jumpKey.setPressed(jump);
            mc.options.sneakKey.setPressed(sneak);
            mc.options.sprintKey.setPressed(sprint);
            keysHeld = true;
            walkStatus = (walkChest != null ? "Walking to chest " : "Walking to block ") + (path.size() - pathIdx) + " steps";
            return true;
        }

        // ------------------------------------------------------------------ water: refill empty buckets at an infinite source
        /** Infinite = would grow back after taking it: two source cells beside it (N / S / E / W) and something solid or water below. */
        private boolean infiniteSource(BlockPos p) {
            BlockState s = mc.world.getBlockState(p);
            if (!s.isOf(Blocks.WATER) || !s.getFluidState().isStill()) return false;
            int n = 0;
            for (Direction d : Direction.values()) {
                if (!d.getAxis().isHorizontal()) continue;
                if (stillWater(mc.world.getBlockState(p.offset(d)))) n++;
            }
            if (n < 2) return false;
            BlockPos g = p.down();
            BlockState gs = mc.world.getBlockState(g);
            return stillWater(gs) || gs.isSideSolidFullSquare(mc.world, g, Direction.UP);
        }

        /** Infinite water sources near the marked chests (or near you when nothing is marked), nearest to the anchor first. Cached. */
        private List<BlockPos> waterSources() {
            if (walkClock - waterSrcTick < 200 && !waterSrcCache.isEmpty()) return waterSrcCache;
            waterSrcTick = walkClock;
            // water the player marked wins: used as it is (no "infinite" check, the player knows best), nearest first
            if (!markedWater.isEmpty()) {
                markedWater.removeIf(m -> !markedSource(m));   // picked up / changed since
                if (!markedWater.isEmpty()) {
                    List<BlockPos> mw = new ArrayList<>(markedWater);
                    BlockPos pp = mc.player.getBlockPos();
                    mw.sort(Comparator.comparingDouble(q -> q.getSquaredDistance(pp)));
                    waterSrcCache = mw;
                    return mw;
                }
            }
            List<BlockPos> anchors = new ArrayList<>();
            Set<BlockPos> seen = new HashSet<>();
            BlockPos pc = mc.player.getBlockPos();
            for (BlockPos m : marked) {
                if (!storage(mc.world.getBlockState(m).getBlock()) || !seen.add(canon(m))) continue;
                if (Math.abs(m.getX() - pc.getX()) > chestRange.get() || Math.abs(m.getZ() - pc.getZ()) > chestRange.get()) continue;
                anchors.add(m);
            }
            if (anchors.isEmpty()) anchors.add(pc);   // no chest marked: look around where you are
            anchors.sort(Comparator.comparingDouble(a -> a.getSquaredDistance(pc)));
            List<BlockPos> out = new ArrayList<>();
            int r = waterRange.getInt();
            for (BlockPos a : anchors.size() > 3 ? anchors.subList(0, 3) : anchors) {
                List<BlockPos> found = new ArrayList<>();
                for (int x = -r; x <= r; x++) for (int y = -r / 2 - 2; y <= r / 2 + 2; y++) for (int z = -r; z <= r; z++) {
                    BlockPos q = a.add(x, y, z);
                    if (infiniteSource(q)) found.add(q);
                }
                found.sort(Comparator.comparingDouble(q -> q.getSquaredDistance(a)));
                for (BlockPos q : found.size() > 40 ? found.subList(0, 40) : found) if (!out.contains(q)) out.add(q);
            }
            waterSrcCache = out;
            return out;
        }

        /** The ray the server uses for an empty bucket: outline shapes, only SOURCE fluids count. Returns the source block it would pick up, or null. */
        private BlockPos bucketRay(Vec3d eye, Vec3d end) {
            BlockHitResult rh = mc.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.SOURCE_ONLY, mc.player));
            if (rh.getType() != HitResult.Type.BLOCK) return null;
            BlockPos hit = rh.getBlockPos();
            return (markedWater.contains(hit) ? markedSource(hit) : infiniteSource(hit)) ? hit : null;
        }

        /** A still water source block (what a marked water cell has to be). */
        private boolean markedSource(BlockPos p) {
            BlockState s = mc.world.getBlockState(p);
            return s.isOf(Blocks.WATER) && s.getFluidState().isStill();
        }

        private boolean waterGoal(List<BlockPos> srcs, BlockPos c) {
            if (pending(c) || pending(c.up())) return false;
            Vec3d eye = new Vec3d(c.getX() + 0.5, c.getY() + 1.62, c.getZ() + 0.5);
            double rr = reach.get() - 0.6;
            for (BlockPos s : srcs) {
                Vec3d ctr = Vec3d.ofCenter(s);
                if (eye.squaredDistanceTo(ctr) > rr * rr) continue;
                if (!ray.get() || bucketRay(eye, ctr) != null) return true;
            }
            return false;
        }

        /** Is a bucket trip needed: water work is ready, there is no filled bucket and there is an empty one. */
        private boolean needRefill() {
            if (!water.get() || waterWork <= 0 || mc.player.isCreative() || mc.currentScreen != null || walkClock < waterPauseUntil) return false;
            int filled = countOf(Items.WATER_BUCKET), empty = countOf(Items.BUCKET);
            if (empty == 0 || filled >= waterBuckets.getInt()) return false;
            if (filled > 0 && !refilling) return false;   // use what is filled first
            return refilling || waterOpen();
        }

        /** Out of water: walk to an infinite source near the marked chest. False when already in reach (refillWater takes over) or there is nowhere to go. */
        private boolean waterWalk(BlockPos feet) {
            if (walkClock < waterPauseUntil || !needRefill()) return false;
            List<BlockPos> srcs = waterSources();
            if (srcs.isEmpty()) {
                walkStatus = "No infinite water source within " + waterRange.getInt() + " blocks of " + (marked.isEmpty() ? "you (mark a chest next to the water, or mark a water block)" : "a marked chest (or mark a water block)");
                walkCd = 60; return false;
            }
            wideArea = true;
            List<BlockPos> np;
            try { np = findPath(feet, c -> waterGoal(srcs, c), 30000); } finally { wideArea = false; }
            if (np == null) { walkStatus = "No route to the water source"; walkCd = 100; waterSrcTick = -9999; return false; }
            if (np.isEmpty()) return false;
            if (++waterWalks > 4) {
                waterWalks = 0; waterPauseUntil = walkClock + 1200;
                U.chat("Water: could not reach / use the water source after several tries - pausing water for 60 s.");
                return false;
            }
            path = np; pathIdx = 0; stuck = 0; pathTicks = 0; lastPos = null; walkIdx = -1; walkChest = null;
            return true;
        }

        /** Standing in reach of an infinite source: fill the empty buckets one by one. True while busy. */
        private boolean refillWater() {
            if (!needRefill()) { refilling = false; return false; }
            var p = mc.player;
            int nowFilled = countOf(Items.WATER_BUCKET);
            if (nowFilled > lastFilled) refillTries = 0;
            lastFilled = nowFilled;
            Vec3d eye = p.getEyePos();
            Vec3d aim = null; double bd = Double.MAX_VALUE;
            for (BlockPos s : waterSources()) {
                Vec3d ctr = Vec3d.ofCenter(s);
                double d = eye.distanceTo(ctr);
                if (d > reach.get() - 0.3 || d >= bd) continue;
                if (ray.get() && bucketRay(eye, ctr) == null) continue;
                bd = d; aim = ctr;
            }
            if (aim == null) {
                refilling = false;
                if (++refillNoAim > 60) {   // next to the water but no clear line to any source: do not sit here forever
                    refillNoAim = 0; waterPauseUntil = walkClock + 600; waterSrcTick = -9999;
                    U.chat("Water: no clear line to a water source from here - pausing water for 30 s. (Tip: mark a water block you can reach.)");
                }
                return false;
            }
            refillNoAim = 0;
            stopKeys();
            refilling = true; lastAct = lastBuiltTick = walkClock;
            aimAt(aim, true);   // keep turning to the water the whole time, also while waiting / switching slots; the bucket needs the real look direction
            if (refillCd > 0) { refillCd--; return true; }
            if (!p.getMainHandStack().isOf(Items.BUCKET)) {
                if (!prepare(Items.BUCKET)) { refilling = false; return false; }
                refillCd = 2; return true;
            }
            Vec3d end = eye.add(p.getRotationVec(1.0F).multiply(reach.get() + 0.5));
            if (bucketRay(eye, end) == null) {   // still turning
                walkStatus = "Water: turning to the source";
                if (++refillAim > 80) {
                    refillAim = 0; refilling = false; waterPauseUntil = walkClock + 600; waterSrcTick = -9999;
                    U.chat("Water: could not get the crosshair on the water source - pausing water for 30 s.");
                    return false;
                }
                return true;
            }
            refillAim = 0;
            mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
            p.swingHand(Hand.MAIN_HAND);
            waterWalks = 0;
            if (++refillTries > 24) {   // clicking and nothing ends up in the bucket: do not loop forever
                refillTries = 0; refilling = false; waterPauseUntil = walkClock + 1200;
                U.chat("Water: the bucket did not fill - pausing water for 60 s.");
                return false;
            }
            refillCd = 4 + (humanize.get() ? (int) Math.round(U.rand(0, 3)) : 0);
            return true;
        }

        // ------------------------------------------------------------------ restock from chests
        private boolean handleContainer() {
            if (chestPos == null || !(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler h) || mc.currentScreen == null) return false;
            lastAct = lastBuiltTick = walkClock;
            if (rcd > 0) { rcd--; return true; }
            int n = h.getRows() * 9;
            for (int i = 0; i < n; i++) {
                ItemStack st = h.getSlot(i).getStack();
                if (st.isEmpty() || !missing.containsKey(st.getItem())) continue;
                if (heldStacks(st.getItem()) >= takeStacks.getInt()) continue;   // enough of that one for now
                if (!canTake(st)) { visitFull = true; continue; }
                int cnt = st.getCount();
                Item took = st.getItem();   // read it BEFORE the click: the click empties the slot on the client and getItem() would then be air
                mc.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                visitTook++;
                Integer need = missing.get(took);
                int left = (need == null ? 0 : need) - cnt;
                if (left <= 0) missing.remove(took); else missing.put(took, left);
                if (took == Items.BUCKET || took == Items.WATER_BUCKET) {   // either kind covers the need for the other
                    Item other = took == Items.BUCKET ? Items.WATER_BUCKET : Items.BUCKET;
                    Integer o = missing.get(other);
                    if (o != null) { if (o - cnt <= 0) missing.remove(other); else missing.put(other, o - cnt); }
                }
                rcd = 2 + (humanize.get() ? (int) Math.round(U.rand(0, 2)) : 0);
                return true;
            }
            // done with this chest. It only counts as empty when it has nothing we need any more; a chest we just took from stays usable
            // (before, it was blocked right away and the builder sat idle until the list was cleared ~30 s later)
            boolean stillHas = false;
            for (int i = 0; i < n && !stillHas; i++) {
                ItemStack st = h.getSlot(i).getStack();
                if (!st.isEmpty() && visitWanted.contains(st.getItem())) stillHas = true;
            }
            Map<Item, Integer> seen = new HashMap<>();
            for (int i = 0; i < n; i++) { ItemStack st = h.getSlot(i).getStack(); if (!st.isEmpty()) seen.merge(st.getItem(), st.getCount(), Integer::sum); }
            chestMem.put(canon(chestPos), new ChestMem(seen, walkClock));
            if (visitTook == 0 || !stillHas) drain(chestPos);
            if (visitTook == 0 && visitFull) { restockBlockedUntil = walkClock + 400; U.chat("Restock: inventory is full, make some room."); }
            mc.player.closeHandledScreen();
            chestPos = null; rcd = 6; tick = 0;
            if (doneCount <= lastTripDone) fruitlessTrips++; else fruitlessTrips = 0;
            lastTripDone = doneCount;
            if (fruitlessTrips >= 2) {
                fruitlessTrips = 0; restockPauseUntil = walkClock + 1200;
                U.chat("Restock: two chest trips in a row without a block being built in between - pausing restock for 60 s. The remaining blocks are probably out of reach or refused by the server.");
            }
            forgetParked(); walkCd = 0; failCount = 0; noPlaceTicks = 0; curIdx = -1;   // blocks parked while we had no material can be tried again
            if (layerOn()) layerCacheTick = -1;
            return true;
        }

        private void openChest() {
            if (!restock.get() || missing.isEmpty() || mc.currentScreen != null || mc.player.isCreative() || allDone() || restockBlockedUntil > walkClock) return;
            if (restockPauseUntil > walkClock || !materialShort()) return;
            if (rcd > 0) { rcd--; return; }
            var p = mc.player;
            Vec3d eye = p.getEyePos();
            double rr = reach.get() - 0.3;
            BlockPos target = null; double bd = Double.MAX_VALUE;
            BlockPos pc = p.getBlockPos();
            for (int x = -5; x <= 5; x++) for (int y = -5; y <= 5; y++) for (int z = -5; z <= 5; z++) {
                BlockPos bp = pc.add(x, y, z);
                if (!allowedChest(bp)) continue;
                double d = Vec3d.ofCenter(bp).distanceTo(eye);
                if (d > rr || d >= bd) continue;
                if (ray.get()) {
                    BlockHitResult rh = mc.world.raycast(new RaycastContext(eye, Vec3d.ofCenter(bp), RaycastContext.ShapeType.COLLIDER,
                            RaycastContext.FluidHandling.NONE, p));
                    if (rh.getType() == HitResult.Type.BLOCK && !rh.getBlockPos().equals(bp)) continue;
                }
                bd = d; target = bp;
            }
            if (target == null) return;
            if (!target.equals(chestPos)) { if (layerOnly.get()) rebuildMissing(); chestPos = target; openTries = 0; visitTook = 0; visitFull = false; visitWanted = new HashSet<>(missing.keySet()); }
            if (openTries++ > 40) { drain(target); chestPos = null; return; }
            Vec3d hit = Vec3d.ofCenter(target);
            if (rotate.get()) {
                float[] t = U.rotTo(hit);
                float[] r = glide(p.getYaw(), p.getPitch(), t[0], t[1], aimSpeed.get(), maxTurn.get(), humanize.get(), true);
                p.setYaw(r[0]); p.setPitch(r[1]);
                Vec3d end = eye.add(p.getRotationVec(1.0F).multiply(reach.get() + 0.5));
                Optional<Vec3d> h = new Box(target).raycast(eye, end);
                if (h.isEmpty()) return;
                hit = h.get();
            }
            mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, faceOf(target, hit), target, false));
            p.swingHand(Hand.MAIN_HAND);
            rcd = 6;
        }

        // ------------------------------------------------------------------ layer by layer / unstick
        private boolean layerOn() { return buildMode.is("Layer by layer"); }

        /** Lowest layer (schematic y) that still has a block not flagged done, honouring layerMin. MIN_VALUE when none. */
        private int lowestOpenLayer() {
            int res = Integer.MIN_VALUE;
            for (int pass = 0; pass < 2; pass++) {
                while (firstPending < view.size() && done.get(firstPending)) firstPending++;
                for (int i = Math.max(firstPending, lowerBound(layerMin)); i < view.size(); i++)
                    if (!done.get(i)) { res = view.get(i).y(); break; }
                if (res != Integer.MIN_VALUE || layerMin == 0) break;
                layerMin = 0;   // everything above the skipped layers is finished: go back to the skipped ones
            }
            return res;
        }

        /** Layer (schematic y) that is being built in "Layer by layer" mode: the lowest one that still has something to do. MIN_VALUE when everything is done. */
        private int layerY() {
            if (layerCacheTick == walkClock) return layerCache;
            layerCacheTick = walkClock;
            int res = lowestOpenLayer();
            // About to leave a layer (or finish): look at the real world first. If blocks of it are missing, flag them again and stay on it.
            // (Not when the layer was skipped on purpose, layerMin > gateY.)
            if (gateY != Integer.MIN_VALUE && (res == Integer.MIN_VALUE || res > gateY) && layerMin <= gateY && verifyLayer(gateY) > 0) res = lowestOpenLayer();
            layerCacheTick = walkClock;
            if (res != Integer.MIN_VALUE) {
                if (res != gateY) { stuckLayerY = Integer.MIN_VALUE; stuckLayerHits = 0; }
                gateY = res;
            }
            layerCache = res;
            return res;
        }

        /**
         * Compares one layer with the real world (not with the done flags). Blocks that are wrong / missing lose their done flag so they get built
         * again, blocks that are right get it. Returns how many blocks of the layer are still missing. Also stores the result for the builder window.
         */
        private int verifyLayer(int y) {
            if (mc.world == null || base() == null || y < 0 || y >= vy || outOfRange(y)) return 0;
            int lo = lowerBound(y), hi = lowerBound(y + 1), missing = 0;
            boolean changed = false;
            for (int i = lo; i < hi; i++) {
                Litematic.Entry e = view.get(i);
                BlockPos wp = world(e);
                BlockState cur = mc.world.getBlockState(wp);
                boolean ok = sameState(e.state(), cur) || skipState(e.state()) || gaveUp.contains(wp) || (e.state().getBlock().asItem() == Items.AIR && !wantsWaterSrc(e.state()));
                if (ok) {
                    Integer pt = placedTick.get(wp);
                    if (pt != null && walkClock - pt < 8) { missing++; continue; }   // placed a moment ago, the server has not confirmed yet
                    if (!done.get(i)) { done.set(i); doneCount++; changed = true; }
                } else {
                    missing++;
                    if (done.get(i)) { done.clear(i); if (doneCount > 0) doneCount--; if (i < firstPending) firstPending = i; changed = true; }
                }
            }
            if (changed) { layerCacheTick = -1; if (missing > 0) curIdx = -1; }
            chkY = y; chkTotal = hi - lo; chkMissing = missing; chkTick = walkClock;
            return missing;
        }

        /** Layer being worked on right now (schematic y): the layer-by-layer layer, or the lowest layer with an open block. MIN_VALUE when none. */
        private int buildY() {
            if (layerOn()) return layerY();
            while (firstPending < view.size() && done.get(firstPending)) firstPending++;
            return firstPending < view.size() ? view.get(firstPending).y() : Integer.MIN_VALUE;
        }

        /** Twice a second: check the layer being built and the one below it against the world, so a refused block is noticed right away. */
        private void checkLayer() {
            int y = buildY();
            if (y == Integer.MIN_VALUE) return;
            if (y > 0) verifyLayer(y - 1);
            verifyLayer(y);   // last, so the window shows the layer being built
        }

        private void resetLayerCheck() {
            gateY = Integer.MIN_VALUE; stuckLayerY = Integer.MIN_VALUE; stuckLayerHits = 0; finalY = -1;
            chkY = Integer.MIN_VALUE; chkTotal = 0; chkMissing = 0; chkTick = -9999; placedTick.clear();
        }

        /** Nothing happened for a while: forget parked blocks / emptied chests, and in layer mode move on from a layer that cannot be finished right now. */
        private void unstuckNow() {
            stuckCount++;
            String why = walkStatus.isEmpty() ? "nothing can be placed from here" : walkStatus;
            forgetParked(); drained.clear(); chestCacheTick = -9999; restockBlockedUntil = 0;
            failCount = 0; failIdx = -1; fillTarget = null; walkCd = 0; curIdx = -1; rcd = 0; openTries = 0; chestPos = null; recPos = -1;
            dropPath(0);
            if (layerOn()) {
                int ly = layerY();
                if (ly != Integer.MIN_VALUE && ly + 1 < vy) {
                    // the parked blocks were just forgotten above: give them a real retry first, only skip the layer when it is stuck again
                    if (stuckLayerY == ly) stuckLayerHits++; else { stuckLayerY = ly; stuckLayerHits = 1; }
                    layerCacheTick = -1;
                    if (stuckLayerHits < 2) {
                        if (stuckCount <= 3) U.chat("Schematic: layer " + (ly + 1) + " is stuck (" + why + "), retrying its blocks.");
                        return;
                    }
                    int left = verifyLayer(ly);
                    stuckLayerHits = 0;
                    layerMin = ly + 1; layerCacheTick = -1;
                    if (stuckCount <= 3) U.chat("Schematic: layer " + (ly + 1) + " is still stuck (" + why + "), " + left + " block(s) left in it. Skipping it for now, it is built later.");
                    return;
                }
            }
            if (stuckCount <= 3) U.chat("Schematic: stuck (" + why + "), retrying.");
        }

        @Override public void onTick() {
            try { tickInner(); glideTail(); lastErr = ""; }
            catch (Throwable t) {
                StackTraceElement at = null;
                for (StackTraceElement el : t.getStackTrace()) if (el.getClassName().startsWith("nova.")) { at = el; break; }
                String where = at == null ? "" : " @ " + at.getFileName() + ":" + at.getLineNumber() + " in " + at.getMethodName();
                String m = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage()) + where;
                if (!m.equals(lastErr)) { lastErr = m; U.chat("Schematic error (kept running): " + m); t.printStackTrace(); }
                stopKeys(); chestPos = null; rcd = 10; cd = 10;
                if (mc.player != null && mc.currentScreen != null && mc.player.currentScreenHandler != mc.player.playerScreenHandler) mc.player.closeHandledScreen();
            }
        }

        private void tickInner() {
            walkClock++;
            trackRate();
            if (mc.world != null && markWorld != mc.world) {   // marks belong to one world / dimension
                if (markWorld != null) { marked.clear(); markedWater.clear(); drained.clear(); chestMem.clear(); chestCacheTick = -9999; }
                markWorld = mc.world;
            }
            if (schem == null || origin == null || mc.player == null || mc.world == null) { nextPos = null; if (ghosts.size() > 0) ghosts = List.of(); stopKeys(); return; }
            if (mc.currentScreen != null) stopKeys();
            if (handleContainer() || handleSign()) return;
            if (pauseHp.getInt() > 0 && mc.player.getHealth() < pauseHp.getInt()) { stopKeys(); nextPos = null; walkStatus = "Paused: low health"; return; }
            if (!rotation.value.equals(viewRot)) rebuildView();
            if (showSchem.get()) { if (ghostTick++ % 8 == 0) updateGhosts(); } else if (!ghosts.isEmpty()) ghosts = List.of();
            if (liteTick++ % 10 == 0) syncLitematica();
            if (recPos >= 0 || tick++ % 40 == 0) recount();
            if (walkClock % 10 == 0) checkLayer();
            if (walkClock % 100 == 50) savePlacement();
            if (walkClock % 200 == 0) placedTick.values().removeIf(t -> walkClock - t > 60);
            boolean all = allDone();
            if (all) {   // everything flagged done: verify every layer against the world (one per tick) before saying so
                int lastY = lastLayer() - 1;
                if (finalY < 0) finalY = firstLayer() - 1;
                if (finalY <= lastY) verifyLayer(finalY++);
                all = allDone();
            } else finalY = -1;
            if (all) {
                if (finalY > lastLayer() - 1 && !announced && activeTicks > 100) {
                    announced = true;
                    int secs = activeTicks / 20;
                    U.chat("Schematic finished: " + schem.name + " (" + (view.size() - rangeSkipped) + " blocks) in " + (secs / 60) + ":" + String.format("%02d", secs % 60) + ".");
                    if (stopWhenDone.get()) { stopKeys(); setEnabled(false); return; }
                }
            } else { announced = false; activeTicks++; }
            if (mc.currentScreen != null) return;
            if (!path.isEmpty()) lastAct = walkClock;
            if (unstick.get() && walkClock - lastAct > stuckSecs.getInt() * 20 && !allDone()) { lastAct = walkClock; unstuckNow(); }
            // second watchdog: walking around or clicking counts as "activity" above, so a build that only paces back and forth never trips it.
            // This one only looks at finished blocks (restock trips are excluded).
            if (doneCount != lastDoneSeen || walkChest != null || chestPos != null) { lastDoneSeen = doneCount; lastBuiltTick = walkClock; }
            else if (unstick.get() && walkClock - lastBuiltTick > stuckSecs.getInt() * 40 && !allDone()) {
                lastBuiltTick = walkClock;
                if (walkIdx >= 0) park(walkIdx, 400);
                if (curIdx >= 0) park(curIdx, 400);
                dropPath(0); curIdx = -1; noPlaceTicks = 0;
                stuckCount++;
                if (stuckCount <= 3) U.chat("Schematic: no block finished for a long time, skipping the current target.");
            }
            if (cd > 0) {   // placing is throttled, walking is not
                cd--;
                lookWalk = false;
                if (!path.isEmpty() && pathIdx < path.size()) walkStep();
                return;
            }

            if (!junk.isEmpty() && fixWrong.get() && fillerCleanup()) { stopKeys(); return; }   // a wrong block is in reach: break it first

            if (signWork()) return;   // a built sign with the wrong text: write it
            if (refilling && refillWater()) return;   // topping up all buckets before going back to building

            Set<BlockPos> placed = new HashSet<>();
            int limit = rotate.get() ? 1 : perTick.getInt();
            for (int n = 0; n < limit; n++) {
                Plan plan = pick(placed);
                if (n == 0) {
                    boolean pathActive = !path.isEmpty() && pathIdx < path.size();
                    if (plan != null && pathActive && plan.idx() == walkIdx) { dropPath(0); pathActive = false; }   // target is in reach already
                    boolean walking = false;
                    lookWalk = plan == null;
                    if (pathActive || plan == null) walking = walkStep();   // keep walking while blocks in reach are placed
                    else stopKeys();
                    if (walking && bridgeBusy) { nextPos = null; break; }   // busy putting a filler block down
                    if (plan == null) {
                        noPlaceTicks = 0;
                        nextPos = walking && walkIdx >= 0 && walkIdx < view.size() ? world(view.get(walkIdx)) : null;
                        if (!walking && !fillerPlace() && !fillerCleanup() && !refillWater()) openChest();
                        break;
                    }
                    if (!walking) walkStatus = "";
                }
                if (plan == null) break;
                nextPos = plan.pos();
                if (!placeOne(plan)) {
                    // a block we can never seem to click gets parked (longer every time) instead of blocking everything.
                    // The counter is not per block: it used to restart whenever pick() switched to another block, so two failing blocks could take turns forever.
                    // Ticks spent still turning towards the target (slow "Aim speed") do not count as failures, only a much longer cap applies to them.
                    boolean giveUp = aiming ? ++aimWait > 200 : ++noPlaceTicks > 40;
                    if (giveUp) { park(plan.idx(), 200); curIdx = -1; noPlaceTicks = 0; aimWait = 0; }
                    break;
                }
                failCount = 0; noPlaceTicks = 0; aimWait = 0;
                if (!isTune(plan) && tooManyClicks(plan.idx())) { park(plan.idx(), 200); curIdx = -1; }   // clicked again and again but the block never stays: server refuses it
                placed.add(plan.pos());
                cd = delay.getInt() + (humanize.get() && delay.getInt() > 0 ? (int) Math.round(U.rand(0, 1.5)) : 0);
            }
        }

        private boolean placeOne(Plan pl) {
            var p = mc.player;
            aiming = false;
            if (!p.getMainHandStack().isOf(pl.item())) {
                // switching to another block: do NOT click in the same tick, the server must see the new item / slot first
                // (otherwise it places the block that was in your hand before)
                if (!prepare(pl.item())) return false;
                cd = 2;
                return false;
            }
            if (pl.item() == Items.WATER_BUCKET || pl.item() == Items.LAVA_BUCKET) return bucketPlace(pl);
            if (isTune(pl)) return tuneClick(pl);
            Vec3d eye = p.getEyePos();
            Vec3d hit = pl.hit();
            Direction face = pl.face();
            if (rotate.get()) {
                float yaw0 = p.getYaw(), pitch0 = p.getPitch();   // = the rotation the server already knows (it was sent at the start of this tick)
                float[] t = U.rotTo(pl.hit());
                float[] r = glide(p.getYaw(), p.getPitch(), t[0], t[1], aimSpeed.get(), maxTurn.get(), humanize.get(), true);
                p.setYaw(r[0]); p.setPitch(r[1]);
                aiming = Math.abs(MathHelper.wrapDegrees(r[0] - yaw0)) > 0.3f || Math.abs(r[1] - pitch0) > 0.3f;
                Vec3d end = eye.add(p.getRotationVec(1.0F).multiply(reach.get() + 0.5));
                Optional<Vec3d> h = new Box(pl.against()).raycast(eye, end);
                if (h.isEmpty()) return false;
                hit = h.get();
                face = faceOf(pl.against(), hit);
                if (pl.against().equals(pl.pos())) {   // second half of a double slab: the click has to land on the face / half planned for it
                    if (face != pl.face()) return false;
                    BlockState cs = mc.world.getBlockState(pl.pos());
                    if (face.getAxis().isHorizontal() && cs.contains(Properties.SLAB_TYPE)) {
                        boolean upper = hit.y - pl.pos().getY() > 0.5, bottomSlab = cs.get(Properties.SLAB_TYPE) == SlabType.BOTTOM;
                        if (bottomSlab != upper) return false;
                    }
                } else if (!pl.against().offset(face).equals(pl.pos())) return false;
                Direction need = needFacing(pl.state());
                if (matchFacing.get() && need != null) {
                    if (p.getHorizontalFacing() != need) return false;
                    // blocks with a facing take it from the SERVER's idea of our rotation, which is still the old one: wait until it is (almost) there
                    if (Math.abs(MathHelper.wrapDegrees(r[0] - yaw0)) > 3f || Math.abs(r[1] - pitch0) > 3f) return false;
                }
                // pistons / observers / droppers / barrels: FACING comes from the 3D look direction as the SERVER knows it (the rotation sent last tick),
                // so both that one and the new aim must clearly point the right way (up / down included)
                Direction look = matchFacing.get() ? needLook(pl.state()) : null;
                if (look != null) {
                    Vec3d old = Vec3d.fromPolar(pitch0, yaw0), now = p.getRotationVec(1.0F);
                    if (dominant(old.x, old.y, old.z) != look || dominant(now.x, now.y, now.z) != look) return false;
                }
                // slabs / stairs / trapdoors on a side face: the half depends on where on the face we click
                if (face.getAxis().isHorizontal()) {
                    double fy = hit.y - pl.against().getY();
                    if (top(pl.state()) && fy < 0.55) return false;
                    if (bottom(pl.state()) && fy > 0.45) return false;
                }
            }
            mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, face, pl.against(), false));
            p.swingHand(Hand.MAIN_HAND);
            ours.add(pl.pos().toImmutable());
            placedTick.put(pl.pos().toImmutable(), walkClock);
            lastAct = walkClock; stuckCount = 0;
            return true;
        }


        /** Turns towards the plan and returns true once the view is (nearly) there. */
        // ------------------------------------------------------------------ smooth turning
        // Every turn of the view goes through glide(): one even step per tick (U.glideRot), and the last target is remembered so the turn keeps
        // going on ticks where nothing else aims (placement cooldown, switching the item in hand, ...). Before, the head froze on those ticks
        // and then started again, which looked like snapping.
        float glideYaw, glidePitch; double glideSpeed = 40, glideMax = 25; int glideUntil = -1, glideTick = -1;

        private float[] glide(float yaw, float pitch, float ty, float tp, double speed, double maxStep, boolean humanize, boolean mouseFix) {
            glideYaw = ty; glidePitch = tp; glideSpeed = speed; glideMax = maxStep; glideUntil = walkClock + 10; glideTick = walkClock;
            return U.glideRot(yaw, pitch, ty, tp, speed, maxStep, humanize, mouseFix);
        }

        /** End of every tick: nothing turned the view this tick but a target is still fresh - carry on towards it. */
        private void glideTail() {
            var p = mc.player;
            if (p == null || glideTick == walkClock || walkClock > glideUntil || mc.currentScreen != null) return;
            if (Math.abs(MathHelper.wrapDegrees(glideYaw - p.getYaw())) < 0.3f && Math.abs(glidePitch - p.getPitch()) < 0.3f) { glideUntil = -1; return; }
            float[] r = U.glideRot(p.getYaw(), p.getPitch(), glideYaw, glidePitch, glideSpeed, glideMax, false, true);
            p.setYaw(r[0]); p.setPitch(r[1]);
        }

        private boolean aimAt(Vec3d target) { return aimAt(target, false); }

        /** force = turn even when "Rotate" is off (buckets use the real look direction, there is no way to click a face instead). */
        private boolean aimAt(Vec3d target, boolean force) {
            var p = mc.player;
            if (!rotate.get() && !force) return true;
            float yaw0 = p.getYaw(), pitch0 = p.getPitch();
            float[] t = U.rotTo(target);
            float[] r = glide(p.getYaw(), p.getPitch(), t[0], t[1], aimSpeed.get(), maxTurn.get(), humanize.get(), true);
            p.setYaw(r[0]); p.setPitch(r[1]);
            aiming = Math.abs(MathHelper.wrapDegrees(r[0] - yaw0)) > 0.3f || Math.abs(r[1] - pitch0) > 0.3f;
            return true;
        }

        // ------------------------------------------------------------------ sign text
        private static boolean signMatches(SignBlockEntity sbe, String[] want) {
            for (int i = 0; i < 4; i++) {
                String have = sbe.getFrontText().getMessage(i, false).getString();
                if (!have.equals(want[i] == null ? "" : want[i])) return false;
            }
            return true;
        }

        /** The sign editor is open (we right clicked a sign): type the lines and close it. */
        private boolean handleSign() {
            if (signAt == null || !(mc.currentScreen instanceof AbstractSignEditScreen)) return false;
            lastAct = lastBuiltTick = walkClock;
            if (signLines != null && mc.getNetworkHandler() != null)
                mc.getNetworkHandler().sendPacket(new UpdateSignC2SPacket(signAt, true, signLines[0], signLines[1], signLines[2], signLines[3]));
            mc.setScreen(null);
            signAt = null; signWait = walkClock + 10;
            return true;
        }

        /** A built sign in reach whose text is not what the schematic says: right click it (opens the editor, handleSign() fills it). True while busy. */
        private boolean signWork() {
            if (signIdx.isEmpty() || mc.currentScreen != null || walkClock < signWait) return false;
            var p = mc.player;
            if (p.isSneaking() && !p.getMainHandStack().isEmpty()) return false;
            Vec3d eye = p.getEyePos();
            for (int i : signIdx) {
                if (signDone.get(i)) continue;
                Litematic.Entry e = view.get(i);
                if (outOfRange(e.y())) { signDone.set(i); continue; }
                BlockPos wp = world(e);
                if (Vec3d.ofCenter(wp).squaredDistanceTo(eye) > (reach.get() - 0.3) * (reach.get() - 0.3)) continue;
                if (mc.world.getBlockState(wp).getBlock() != e.state().getBlock()) continue;   // not built yet
                if (!(mc.world.getBlockEntity(wp) instanceof SignBlockEntity sbe)) continue;
                String[] want = viewSigns.get(key(e.x(), e.y(), e.z()));
                if (want == null || signMatches(sbe, want)) { signDone.set(i); continue; }
                var shape = mc.world.getBlockState(wp).getOutlineShape(mc.world, wp);
                Vec3d aim = shape.isEmpty() ? Vec3d.ofCenter(wp) : shape.getBoundingBox().getCenter().add(wp.getX(), wp.getY(), wp.getZ());
                if (ray.get()) {
                    BlockHitResult r0 = mc.world.raycast(new RaycastContext(eye, aim, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, p));
                    if (r0.getType() != HitResult.Type.BLOCK || !r0.getBlockPos().equals(wp)) continue;
                }
                stopKeys();
                aimAt(aim);
                Vec3d end = eye.add(p.getRotationVec(1.0F).multiply(reach.get() + 0.5));
                BlockHitResult rh = mc.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, p));
                lastAct = walkClock;
                if (rh.getType() != HitResult.Type.BLOCK || !rh.getBlockPos().equals(wp)) return true;   // still turning
                if (signClicks.merge(i, 1, Integer::sum) > 3) { signDone.set(i); continue; }   // waxed / refused: give up on this one
                signAt = wp.toImmutable(); signLines = want;
                mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, rh);
                p.swingHand(Hand.MAIN_HAND);
                signWait = walkClock + 12;
                return true;
            }
            return false;
        }

        // ------------------------------------------------------------------ saved placement
        private Path stateFile() { return FabricLoader.getInstance().getConfigDir().resolve("nova").resolve("schematic_state.properties"); }

        private String placementString() {
            if (origin == null || mc.world == null) return "";
            return origin.getX() + "," + origin.getY() + "," + origin.getZ() + ";" + mc.world.getRegistryKey().getValue() + ";" + rotation.value + ";" + anchor.value
                    + ";" + offX.getInt() + "," + offY.getInt() + "," + offZ.getInt();
        }

        /** Remembers origin / rotation / anchor / offsets of the loaded file so the next session (or a reload) can continue where it was. */
        private void savePlacement() {
            if (schem == null || loadedFile.isEmpty()) return;
            String v = placementString();
            if (v.isEmpty() || v.equals(savedPlacement)) return;
            savedPlacement = v;
            try {
                java.util.Properties pr = new java.util.Properties();
                Path f = stateFile();
                Files.createDirectories(f.getParent());
                if (Files.exists(f)) try (var in = Files.newInputStream(f)) { pr.load(in); }
                pr.setProperty(loadedFile, v);
                try (var out = Files.newOutputStream(f)) { pr.store(out, "Nova schematic placements"); }
            } catch (Exception ignored) {}
        }

        /** True when a saved placement for the loaded file was applied (same dimension only). */
        private boolean restorePlacement() {
            try {
                Path f = stateFile();
                if (!Files.exists(f) || mc.world == null) return false;
                java.util.Properties pr = new java.util.Properties();
                try (var in = Files.newInputStream(f)) { pr.load(in); }
                String v = pr.getProperty(loadedFile);
                if (v == null) return false;
                String[] a = v.split(";");
                if (a.length < 6 || !a[1].equals(String.valueOf(mc.world.getRegistryKey().getValue()))) return false;
                String[] o = a[0].split(","), off = a[5].split(",");
                origin = new BlockPos(Integer.parseInt(o[0]), Integer.parseInt(o[1]), Integer.parseInt(o[2]));
                rotation.value = a[2]; anchor.value = a[3];
                offX.set(Integer.parseInt(off[0])); offY.set(Integer.parseInt(off[1])); offZ.set(Integer.parseInt(off[2]));
                savedPlacement = v;
                return true;
            } catch (Exception e) { return false; }
        }

        /** Right click on a block that is set wrong (lever, door, note, ...). The click lands where the real outline ray hits that block. */
        private boolean tuneClick(Plan pl) {
            var p = mc.player;
            aiming = false;
            Vec3d eye = p.getEyePos();
            aimAt(pl.hit());
            Vec3d end = eye.add(p.getRotationVec(1.0F).multiply(reach.get() + 0.5));
            BlockHitResult rh = mc.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, p));
            if (rh.getType() != HitResult.Type.BLOCK || !rh.getBlockPos().equals(pl.pos())) return false;
            String handId = Registries.ITEM.getId(p.getMainHandStack().getItem()).getPath();
            if (rh.getSide() == Direction.UP && (handId.endsWith("_head") || handId.endsWith("_skull"))) return false;   // would place the head instead
            mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, rh);
            p.swingHand(Hand.MAIN_HAND);
            int[] t = tune.get(pl.pos().toImmutable());
            int[] wv = clickVal(pl.state());
            if (t != null && wv != null) { t[0] = (t[0] + 1) % wv[1]; t[1] = walkClock; }
            placedTick.put(pl.pos().toImmutable(), walkClock);
            lastAct = walkClock; stuckCount = 0;
            return true;
        }

        /**
         * Uses the water bucket in hand. A bucket is not a block click: the server casts its own ray from our eyes along the look direction
         * (outline shapes, fluids ignored) and puts the water into the hit block (when that block can be waterlogged) or next to the hit face.
         * So the same ray is cast here and the click is only sent when the water would end up exactly where the plan wants it.
         */
        private boolean bucketPlace(Plan pl) {
            var p = mc.player;
            aiming = false;
            Vec3d eye = p.getEyePos();
            aimAt(pl.hit(), true);   // a bucket always uses the real look direction, so it has to turn even when "Rotate" is off
            Vec3d end = eye.add(p.getRotationVec(1.0F).multiply(reach.get() + 0.5));
            BlockHitResult rh = mc.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, p));
            if (rh.getType() != HitResult.Type.BLOCK) return false;
            BlockPos hb = rh.getBlockPos();
            // only water fills a waterloggable block itself; lava goes next to the face that was hit
            BlockPos land = pl.item() == Items.WATER_BUCKET && mc.world.getBlockState(hb).getBlock() instanceof FluidFillable ? hb : hb.offset(rh.getSide());
            if (!land.equals(pl.pos())) return false;
            if (eye.distanceTo(rh.getPos()) > Math.min(reach.get(), 4.5) + 0.3) return false;
            mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
            p.swingHand(Hand.MAIN_HAND);
            placedTick.put(pl.pos().toImmutable(), walkClock);
            lastAct = walkClock; stuckCount = 0;
            return true;
        }

        // ------------------------------------------------------------------ builder window (nova.gui.SchematicScreen)
        void openBuilder() { mc.setScreen(new SchematicScreen(this)); }

        /** Blocks finished, sampled every 5 s (last minute) so the window can show a speed and an ETA. */
        private void trackRate() {
            if (walkClock % 100 != 0) return;
            if (!rateLog.isEmpty() && doneCount < rateLog.peekLast()[1]) rateLog.clear();   // went backwards: reload, new origin, a block got broken
            rateLog.addLast(new int[]{walkClock, doneCount});
            while (rateLog.size() > 13) rateLog.removeFirst();
        }

        public boolean isLoaded() { return schem != null; }
        public String loadedFile() { return loadedFile; }
        public String schemName() { return schem == null ? "" : schem.name; }
        /** Size after rotation: x, y, z. */
        public int[] size() { return new int[]{vx, vy, vz}; }
        public int totalBlocks() { return Math.max(0, view.size() - rangeSkipped); }
        public int builtBlocks() { return Math.max(0, doneCount - rangeSkipped); }
        public int wrongBlocks() { return wrongCount; }
        public BlockPos originPos() { return origin; }
        public String statusLine() { return status; }
        public String walkLine() { return walkStatus; }
        public String errorLine() { return lastErr; }
        public int markedChests() { return markedCount(); }
        public boolean finished() { return allDone() && finalY > lastLayer() - 1; }
        /** Result of the last real-world check of a layer: layer (1-based, -1 = none yet), its blocks, and how many of them are still missing. */
        public int checkedLayer() { return chkY == Integer.MIN_VALUE ? -1 : chkY + 1; }
        public int checkedTotal() { return chkTotal; }
        public int checkedMissing() { return chkMissing; }
        /** True while the final check of all layers runs (everything flagged done, each layer is compared with the world). */
        public boolean finalChecking() { return schem != null && allDone() && finalY <= lastLayer() - 1; }
        /** 1-based layer being built in "Layer by layer" mode, -1 otherwise. */
        public int currentLayer() {
            if (schem == null || origin == null || !layerOn()) return -1;
            int ly = layerY();
            return ly == Integer.MIN_VALUE ? -1 : ly + 1;
        }
        /** First / last layer (1-based) that is being built. Last layer is the real top when "Last layer" is 0 (= all). */
        public int firstLayer() { return Math.max(1, Math.min(fromLayer.getInt(), Math.max(1, vy))); }
        public int lastLayer() { int hi = toLayer.getInt(), m = Math.max(1, vy); return hi <= 0 || hi > m ? m : Math.max(hi, firstLayer()); }
        /** True when only part of the schematic is selected. */
        public boolean layerLimited() { return schem != null && (firstLayer() > 1 || lastLayer() < vy); }
        /** Blocks of a layer (1-based) and how many of them are already right in the world. 0 until the first recount has finished. */
        public int layerTotal(int layer) { int y = layer - 1; return y >= 0 && y < layerTot.length ? layerTot[y] : 0; }
        public int layerBuilt(int layer) { int y = layer - 1; return y >= 0 && y < layerDoneN.length ? layerDoneN[y] : 0; }
        /** Lowest layer (1-based) that still has something to build, 1 when unknown. */
        public int firstOpenLayer() {
            for (int y = 0; y < layerTot.length && y < layerDoneN.length; y++) if (layerDoneN[y] < layerTot[y]) return y + 1;
            return 1;
        }
        /** Choose the layers to build (1-based, to = 0 means up to the top). Re-counts straight away so progress, materials and the window follow. */
        public void setLayers(int from, int to) {
            int m = Math.max(1, vy);
            from = Math.max(1, Math.min(from, m));
            if (to > 0) to = Math.max(from, Math.min(to, m));
            if (from <= 1 && to >= m) to = 0;
            fromLayer.set(from); toLayer.set(to);
            layersChanged();
            Config.save();
        }
        private void layersChanged() {
            recPos = -1; tick = 0; layerMin = 0; layerCacheTick = -1; shortTick = -9999; missingTick = -9999;
            curIdx = -1; nextPos = null; ghosts = List.of(); badTargets.clear(); strikes.clear(); clicks.clear(); noPlaceTicks = 0; lastAct = walkClock; stuckCount = 0;
            resetLayerCheck();
            resetWalk();
        }
        public double blocksPerMinute() {
            if (rateLog.size() < 2) return 0;
            int[] a = rateLog.peekFirst(), b = rateLog.peekLast();
            int dt = b[0] - a[0];
            return dt <= 0 ? 0 : (b[1] - a[1]) * 1200.0 / dt;
        }
        /** Seconds until done at the current speed, -1 when unknown. */
        public int etaSeconds() {
            double r = blocksPerMinute();
            int left = view.size() - doneCount;
            return r <= 0.05 || left <= 0 ? -1 : (int) Math.min(99L * 3600, Math.round(left / r * 60));
        }
        /** What the whole schematic still needs (item -> count), before looking at your inventory. */
        public Map<Item, Integer> needed() { return new LinkedHashMap<>(needTotal); }
        /** What you lack right now (item -> count). Empty in creative with "Creative fill". */
        public Map<Item, Integer> lacking() { return new LinkedHashMap<>(missing); }
        public int carrying(Item it) { return mc.player == null ? 0 : countOf(it); }
        public List<String> fileNames() { return file.modes.length == 1 && file.modes[0].equals("None") ? List.of() : List.of(file.modes); }
        public void pickFile(String name) { file.value = name; }
        public String pickedFile() { return file.value; }
        public void reloadFiles() { refresh(); }
        public void loadPicked() { loadSelected(); }
        public void unloadAll() { unload(); }
        public void originAtMe() { originHere(); }
        public void originAtCrosshair() { originLook(); }

        @Override public void onHud(DrawContext ctx) {
            int acc = Theme.accent();
            Proj pr = Proj.begin();
            if (pr != null && showMarks.get()) {
                for (BlockPos m : marked) pr.wire(ctx, new Box(m).expand(0.004), 0xFF3BFF7A);
                for (BlockPos m : markedWater) pr.wire(ctx, new Box(m).expand(0.004), 0xFF3BB4FF);
            }
            if (pr != null && schem != null && origin != null) {
                BlockPos b = base();
                if (outline.get()) pr.wire(ctx, new Box(b.getX(), b.getY(), b.getZ(), b.getX() + vx, b.getY() + vy, b.getZ() + vz), Gfx.a(acc, 0.8f));
                if (showSchem.get()) {
                    for (Ghost g : ghosts) {
                        if (pr.project(Vec3d.ofCenter(g.pos())) == null) continue;
                        pr.wire(ctx, new Box(g.pos()).expand(-0.03), Gfx.a(g.color(), 0.9f));
                    }
                }
                if (showPath.get() && walk.get() && !path.isEmpty()) {
                    for (int i = Math.max(0, pathIdx - 1); i < path.size() - 1; i++) {
                        BlockPos a = path.get(i), c = path.get(i + 1);
                        pr.line3(ctx, new Vec3d(a.getX() + 0.5, a.getY() + 0.1, a.getZ() + 0.5), new Vec3d(c.getX() + 0.5, c.getY() + 0.1, c.getZ() + 0.5), Gfx.a(acc, 0.9f));
                    }
                }
                for (BlockPos j : junk) pr.wire(ctx, new Box(j).expand(0.004), 0xFFFF3B3B);
                if (showNext.get() && nextPos != null) pr.wire(ctx, new Box(nextPos).expand(0.003), 0xFFFFFFFF);
            }
            if (!progress.get()) return;
            int x = 6, w = 150;
            List<String> lines = new ArrayList<>();
            if (schem == null) lines.add("No schematic loaded");
            else if (origin == null) lines.add("Set the origin");
            for (var en : missing.entrySet()) {
                if (lines.size() >= 4) break;
                lines.add("Need " + en.getValue() + " " + Registries.ITEM.getId(en.getKey()).getPath().replace('_', ' '));
            }
            if (layerOn() && schem != null && origin != null) { int ly = layerY(); if (ly != Integer.MIN_VALUE) lines.add("Layer " + (ly + 1) + " / " + vy); }
            if (layerLimited() && origin != null) { int lo = firstLayer(), hi = lastLayer(); lines.add((lo == hi ? "Layer " + lo + " only" : "Layers " + lo + "-" + hi) + " / " + vy); }
            if (restock.get() && !marked.isEmpty()) lines.add(markedCount() + " restock chest" + (markedCount() == 1 ? "" : "s") + " marked");
            if (!markedWater.isEmpty()) lines.add(markedWater.size() + " water source" + (markedWater.size() == 1 ? "" : "s") + " marked");
            if (!lastErr.isEmpty()) lines.add("Error: " + lastErr);
            if (wrongCount > 0 && fixWrong.get()) lines.add(wrongCount + " wrong block" + (wrongCount == 1 ? "" : "s"));
            if (walk.get() && !walkStatus.isEmpty()) lines.add(walkStatus);
            int h = 28 + (schem != null ? 10 : 0) + lines.size() * 10;
            int y = mc.getWindow().getScaledHeight() - h - 40;
            Gfx.rrect(ctx, x, y, w, h, 5, 0xB0100C0E);
            Gfx.rrect(ctx, x, y + 5, 2, h - 10, 1, acc);
            U.text(ctx, schem == null ? "Schematic" : schem.name, x + 8, y + 5, 0xFFFFFFFF);
            int yy = y + 17;
            if (schem != null) {
                int total = Math.max(1, view.size() - rangeSkipped), doneNow = Math.max(0, doneCount - rangeSkipped);
                float pct = doneNow / (float) total;
                Gfx.rrect(ctx, x + 8, yy, w - 16, 4, 2, 0xFF2A2124);
                Gfx.rrect(ctx, x + 8, yy, Math.max(4, Math.round((w - 16) * pct)), 4, 2, acc);
                U.text(ctx, doneNow + " / " + total + " (" + Math.round(pct * 100) + "%)", x + 8, yy + 7, 0xFFB8AEAB);
                yy += 17;
            }
            for (String l : lines) { U.text(ctx, l, x + 8, yy, l.startsWith("Need") ? 0xFFFFA23A : 0xFFB8AEAB); yy += 10; }
        }
    }
}
