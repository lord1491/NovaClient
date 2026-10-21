package nova.modules;

import net.minecraft.block.Blocks;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import nova.core.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * MaceBot: a full mace fighter (with spear lunge swap).
 *
 * Mace loop:  run at the target -> (axe the shield if it is up) -> jump + wind charge at your feet ->
 *             spear lunge if you are still far away -> steer onto a predicted intercept point -> smash on the way down.
 * Pearls:     arc is simulated (walls, safe landing spot), used to close gaps and to escape when you are low.
 * Healing:    low HP -> pearl away -> splash potion + gapples -> back into the fight.
 * Combo:      target far above you -> pearl next to him, wind charge launch, spear lunge, smash (rolled, not every time).
 * Attr swap:  the smash hit is sent with a hotbar swap around it (rolled, not every time).
 * Support:    pulls items from the inventory into the hotbar, keeps a totem in the offhand, water clutch after a miss.
 *
 * Tick based, no mixins. Aiming is snapped on purpose (wind charge and pearls need exact angles).
 */
public class MaceBot extends Mod {
    Setting.Mode weapon = mode("Weapon", "Auto", "Auto", "Mace", "Spear");
    Setting.Num range = num("Target range", 48, 8, 128, 4);
    Setting.Bool lockCrosshair = bool("Lock player in crosshair", true);
    Setting.Bool respectFriends = bool("Respect FriendGuard", false);

    Setting.Bool wind = bool("Wind charge launch", true);
    Setting.Num launchRange = num("Launch distance", 7, 2, 14, 0.5);
    Setting.Num minFall = num("Min fall to smash", 3.0, 1.5, 20, 0.5);
    Setting.Num reach = num("Reach", 2.95, 2.5, 3.5, 0.05);
    Setting.Bool calibrate = bool("Auto-calibrate wind timing", true);
    Setting.Num windDelaySet = num("Wind charge delay (ticks)", 0, 0, 3, 1);

    Setting.Bool breakShield = bool("Break shield first", true);
    Setting.Bool pearls = bool("Use pearls", true);
    Setting.Num pearlMin = num("Pearl above distance", 18, 8, 60, 1);
    Setting.Num pearlMax = num("Pearl below distance", 70, 20, 120, 5);

    Setting.Bool predict = bool("Predict movement", true);
    Setting.Num maxLead = num("Max lead (ticks)", 14, 0, 24, 1);

    Setting.Bool airBoost = bool("Air boost off walls/floor", true);
    Setting.Mode smashDefense = mode("Defense vs their smash", "Auto", "Auto", "Dodge", "Shield", "Off");
    Setting.Bool lunge = bool("Spear lunge boost", true);
    Setting.Num lungeMin = num("Lunge only if farther than", 5.0, 2, 14, 0.5);

    Setting.Bool heal = bool("Heal + pearl away", true);
    Setting.Num healBelow = num("Heal below HP", 8, 2, 19, 1), healUntil = num("Heal until HP", 17, 4, 20, 1),
            safeDist = num("Safe distance to heal", 14, 8, 40, 1), panicDist = num("Panic distance", 5, 2, 10, 0.5);

    Setting.Bool refill = bool("Pull items from inventory", true);
    Setting.Bool totem = bool("Keep totem in offhand", true);
    Setting.Bool clutch = bool("Water clutch after a miss", true);

    Setting.Bool spearCharge = bool("Spear: hold charge", true);
    Setting.Num spearReach = num("Spear reach", 3.8, 2.5, 4.8, 0.1);
    Setting.Num stopHealth = num("Stop below HP", 0, 0, 19, 1);

    Setting.Bool combo = bool("Combo: pearl + wind + lunge (target above)", true);
    Setting.Num comboHeight = num("Combo when target higher by", 5, 2, 20, 1);
    Setting.Num comboChance = num("Combo chance %", 60, 0, 100, 5);
    Setting.Bool attrSwap = bool("Attribute swap on smash", true);
    Setting.Num swapChance = num("Attribute swap chance %", 60, 0, 100, 5);
    Setting.Num swapSlot = num("Attribute swap slot (0 = other mace)", 0, 0, 9, 1);

    private enum St { SEEK, JUMP, AIR }
    private enum Heal { NONE, RETREAT, EAT }
    private record Sol(float pitch, Vec3d land, boolean hit, double score) {}

    private PlayerEntity target, lastTarget;
    private St st = St.SEEK;
    private Heal phase = Heal.NONE;
    // strafe tracking
    private final ArrayDeque<Vec3d> hist = new ArrayDeque<>();
    private boolean strafing;
    private Vec3d centroid = Vec3d.ZERO;
    // self calibration (wind charge timing, lunge)
    private final double[] gainSum = new double[3];
    private final int[] gainN = new int[3];
    private int launches, curDelay, bestDelay, lungeCheck, lungeFails;
    private boolean calibrated, thrown, apexDone, lungeBroken, lungeOk, shielding;
    private double launchY, apexY, lungeSpeed;
    private int popTicks, pops;
    private double lastAbs;
    private boolean airBoosted;
    private int comboTicks, comboRollCd;
    private int stTicks, pearlCd, axeCd, windCd, lungeCd, refillCd, solveCd, healCd, healTicks, retreatTicks, hitCd, clutchWait;
    private boolean warned, holdingUse, lungeDone, clutchPlaced;

    // target tracking (remote players have no reliable client velocity, so it is measured)
    private Vec3d lastPos = Vec3d.ZERO, tvel = Vec3d.ZERO;
    private double decay = 0.9, conf = 1.0, groundY = -64;

    public MaceBot() {
        super("MaceBot", Category.COMBAT,
                "Fights for you with a mace (wind charge launch, spear lunge, pearls, shield break, healing). Look at your opponent and switch it on.");
    }

    // ------------------------------------------------------------------ lifecycle

    @Override public void onEnable() {
        target = lastTarget = null; st = St.SEEK; phase = Heal.NONE;
        popTicks = pops = 0; lastAbs = 0; airBoosted = false;
        comboTicks = comboRollCd = 0;
        stTicks = pearlCd = axeCd = windCd = lungeCd = refillCd = solveCd = healCd = healTicks = retreatTicks = hitCd = clutchWait = 0;
        warned = false; lungeDone = false; clutchPlaced = false;
        var p = mc.player;
        if (p != null && lockCrosshair.get() && mc.crosshairTarget instanceof net.minecraft.util.hit.EntityHitResult h
                && h.getEntity() instanceof PlayerEntity pl && pl != p) target = pl;
    }

    @Override public void onDisable() {
        release();
        if (CombatModules.KillAura.TARGET == target) CombatModules.KillAura.TARGET = null;
        target = null;
    }

    private void release() {
        if (mc.options == null) return;
        mc.options.forwardKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        stopEating();
    }

    private void stopEating() {
        shielding = false;
        if (holdingUse) { mc.options.useKey.setPressed(false); holdingUse = false; }
    }

    // ------------------------------------------------------------------ item helpers

    private static boolean isSpear(ItemStack s) {
        return !s.isEmpty() && Registries.ITEM.getId(s.getItem()).getPath().endsWith("_spear");
    }
    private static boolean isHeal(ItemStack s) { return s.isOf(Items.GOLDEN_APPLE) || s.isOf(Items.ENCHANTED_GOLDEN_APPLE); }
    private static boolean isAxe(ItemStack s) { return s.isIn(ItemTags.AXES); }

    private static boolean hotbarHas(Predicate<ItemStack> f) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) if (f.test(inv.getStack(i))) return true;
        return false;
    }
    private static boolean has(Predicate<ItemStack> f) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 36; i++) if (f.test(inv.getStack(i))) return true;
        return false;
    }
    private static boolean select(Predicate<ItemStack> f) {
        if (f.test(mc.player.getMainHandStack())) return true;
        return U.selectSlot(f);
    }

    private List<Predicate<ItemStack>> wanted() {
        List<Predicate<ItemStack>> w = new ArrayList<>();
        if (!weapon.is("Spear")) w.add(s -> s.isOf(Items.MACE));
        if (weapon.is("Spear") || lunge.get() || weapon.is("Auto")) w.add(MaceBot::isSpear);
        if (wind.get()) w.add(s -> s.isOf(Items.WIND_CHARGE));
        if (breakShield.get()) w.add(MaceBot::isAxe);
        if (pearls.get() || heal.get()) w.add(s -> s.isOf(Items.ENDER_PEARL));
        if (heal.get()) { w.add(MaceBot::isHeal); w.add(DefenseModules.AutoPotion::healing); }
        if (clutch.get()) w.add(s -> s.isOf(Items.WATER_BUCKET));
        return w;
    }

    /** Moves one missing item from the inventory into the hotbar (never over another wanted item). */
    private boolean maintain() {
        var p = mc.player;
        if (refillCd > 0) return false;
        var inv = p.getInventory();
        int sync = p.playerScreenHandler.syncId;

        if (totem.get() && !p.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            for (int i = 0; i < 36; i++) {
                if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) {
                    mc.interactionManager.clickSlot(sync, i < 9 ? i + 36 : i, 40, SlotActionType.SWAP, p);
                    refillCd = 4;
                    return true;
                }
            }
        }
        if (!refill.get()) return false;
        List<Predicate<ItemStack>> want = wanted();
        for (Predicate<ItemStack> pred : want) {
            if (hotbarHas(pred)) continue;
            int src = -1;
            for (int i = 9; i < 36; i++) if (pred.test(inv.getStack(i))) { src = i; break; }
            if (src < 0) continue;
            int dst = -1;
            for (int h = 0; h < 9 && dst < 0; h++) if (inv.getStack(h).isEmpty()) dst = h;
            for (int h = 0; h < 9 && dst < 0; h++) {
                boolean prot = false;
                for (Predicate<ItemStack> w : want) if (w.test(inv.getStack(h))) { prot = true; break; }
                if (!prot) dst = h;
            }
            if (dst < 0) continue;
            mc.interactionManager.clickSlot(sync, src, dst, SlotActionType.SWAP, p);
            refillCd = 4;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ geometry

    private PlayerEntity pick() {
        PlayerEntity best = null; double bd = range.get();
        for (PlayerEntity o : mc.world.getPlayers()) {
            if (o == mc.player || !o.isAlive() || o.isSpectator() || DefenseModules.AntiBot.isBot(o)) continue;
            if (respectFriends.get() && ExtraCombat.FriendGuard.isFriend(o)) continue;
            double d = mc.player.distanceTo(o);
            if (d <= bd) { bd = d; best = o; }
        }
        return best;
    }

    private static double closest(Vec3d eye, Box b) {
        double x = MathHelper.clamp(eye.x, b.minX, b.maxX);
        double y = MathHelper.clamp(eye.y, b.minY, b.maxY);
        double z = MathHelper.clamp(eye.z, b.minZ, b.maxZ);
        return eye.distanceTo(new Vec3d(x, y, z));
    }

    /** Eye to hitbox distance right now. */
    private double reachTo(Entity e) { return closest(mc.player.getEyePos(), e.getBoundingBox()); }

    /** Same distance one tick ahead (where both of you will be when the server handles the hit). */
    private double reachPred() {
        Vec3d eye = mc.player.getEyePos().add(mc.player.getVelocity());
        return closest(eye, target.getBoundingBox().offset(tvel.x, tvel.y, tvel.z));
    }

    private double horiz(Entity e) { return Math.hypot(e.getX() - mc.player.getX(), e.getZ() - mc.player.getZ()); }

    private double groundBelow(Vec3d from) {
        var rh = mc.world.raycast(new RaycastContext(from.add(0, 0.2, 0), from.subtract(0, 48, 0),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
        return rh.getType() == HitResult.Type.MISS ? -64 : rh.getPos().y;
    }

    // ------------------------------------------------------------------ prediction

    /** Measures the target's velocity, how fast it slows down, and how steady its direction is. */
    private void track() {
        Vec3d cur = U.pos(target);
        if (target != lastTarget) { tvel = Vec3d.ZERO; decay = 0.9; conf = 1.0; }
        else {
            Vec3d v1 = cur.subtract(lastPos), old = tvel;
            tvel = old.multiply(0.4).add(v1.multiply(0.6));
            double s1 = Math.hypot(v1.x, v1.z), so = Math.hypot(old.x, old.z);
            if (so > 0.04) decay = decay * 0.7 + MathHelper.clamp(s1 / so, 0.3, 1.1) * 0.3;
            else decay = decay * 0.9 + 0.09;
            if (s1 > 0.03 && so > 0.03) {
                double c = (v1.x * old.x + v1.z * old.z) / (s1 * so);                  // 1 = same direction, -1 = reversed
                double cc = c >= 0.9 ? 1.0 : MathHelper.clamp((c + 0.2) / 1.1, 0.25, 1.0);
                conf = conf * 0.5 + cc * 0.5;                                          // strafing target -> smaller lead
            } else conf = conf * 0.8 + 0.2;
        }
        if (target != lastTarget) hist.clear();
        hist.addLast(cur);
        if (hist.size() > 14) hist.removeFirst();
        strafing = false;
        if (hist.size() >= 8) {
            Vec3d[] h = hist.toArray(new Vec3d[0]);
            int rev = 0; double cx = 0, cz = 0;
            for (int i = 1; i < h.length; i++) {
                cx += h[i].x; cz += h[i].z;
                if (i < 2) continue;
                double ax = h[i].x - h[i - 1].x, az = h[i].z - h[i - 1].z, bx = h[i - 1].x - h[i - 2].x, bz = h[i - 1].z - h[i - 2].z;
                if (Math.hypot(ax, az) > 0.03 && Math.hypot(bx, bz) > 0.03 && ax * bx + az * bz < 0) rev++;
            }
            if (rev >= 2) { strafing = true; centroid = new Vec3d(cx / (h.length - 1), cur.y, cz / (h.length - 1)); }
        }
        groundY = groundBelow(cur);
        // Totem pop: health drops to ~1 and a fresh ~8 absorption shows up at the same moment (gapples never do that).
        double abs = target.getAbsorptionAmount();
        if (target == lastTarget && abs >= 7 && lastAbs < 2 && target.getHealth() <= 2.5f) {
            popTicks = 100; pops++;
            U.chat(target.getName().getString() + " popped a totem (" + pops + ").");
            if (phase != Heal.NONE && mc.player.getHealth() > 4) endHeal(60);   // finish him instead of healing
        }
        lastAbs = abs;
        lastTarget = target; lastPos = cur;
    }

    /** Round trip delay in ticks: you see the target late and your action arrives late, so the lead has to cover both. */
    private double pingTicks() {
        var h = mc.getNetworkHandler();
        if (h == null) return 0;
        var e = h.getPlayerListEntry(mc.player.getUuid());
        return e == null ? 0 : MathHelper.clamp(e.getLatency() / 50.0, 0, 6);
    }

    /** Body centre of the target `t` ticks from now: friction-aware horizontally, gravity-aware vertically. */
    private Vec3d at(double t) {
        Vec3d body = U.pos(target).add(0, target.getHeight() * 0.5, 0);
        if (!predict.get()) return body;
        t = MathHelper.clamp(t + pingTicks(), 0, maxLead.get());
        double r = MathHelper.clamp(target.isOnGround() ? decay : Math.max(decay, 0.9), 0.6, 1.0);
        double k = r >= 0.97 ? t : (1 - Math.pow(r, t)) / (1 - r);
        double dx = tvel.x * k * conf, dz = tvel.z * k * conf, dy = 0;
        if (!target.isOnGround()) {
            double y = 0, vy = tvel.y;
            for (int i = 0; i < (int) Math.floor(t); i++) {
                y += vy; vy = (vy - 0.08) * 0.98;
                if (target.getY() + y < groundY) { y = groundY - target.getY(); break; }
            }
            dy = y;
        }
        if (strafing && target.isOnGround()) {            // oscillating target: linear extrapolation is wrong, aim at the middle of the wiggle
            double lx = body.x + dx, lz = body.z + dz;
            dx = (lx * 0.35 + centroid.x * 0.65) - body.x;
            dz = (lz * 0.35 + centroid.z * 0.65) - body.z;
        }
        return body.add(dx, dy, dz);
    }

    /** Where to aim so that you meet the target, given how fast you are moving (solved by iteration). */
    private Vec3d intercept() {
        var p = mc.player;
        double vme = Math.max(0.25, Math.hypot(p.getVelocity().x, p.getVelocity().z));
        double t = horiz(target) / vme;
        Vec3d pt = at(t);
        for (int i = 0; i < 3; i++) {
            t = Math.hypot(pt.x - p.getX(), pt.z - p.getZ()) / vme;
            pt = at(t);
        }
        return pt;
    }

    private double gapTo(Vec3d pt) { return Math.hypot(pt.x - mc.player.getX(), pt.z - mc.player.getZ()); }

    // ------------------------------------------------------------------ aiming / movement

    private void turn(Vec3d at, boolean snap) {
        var p = mc.player;
        float[] t = U.rotTo(at);
        if (snap) { p.setYaw(t[0]); p.setPitch(MathHelper.clamp(t[1], -90f, 90f)); return; }
        float[] r = U.smoothRot(p.getYaw(), p.getPitch(), t[0], t[1], 60, 40, true, true);
        p.setYaw(r[0]); p.setPitch(r[1]);
    }

    private void run(boolean jumpOnWall) {
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(true);
        mc.player.setSprinting(true);
        if (jumpOnWall && mc.player.isOnGround() && mc.player.horizontalCollision) mc.player.jump();
    }

    private void stopRun() {
        mc.options.forwardKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
    }

    private void hit(Entity e) {
        mc.interactionManager.attackEntity(mc.player, e);
        mc.player.swingHand(Hand.MAIN_HAND);
        hitCd = 4;
    }

    // ------------------------------------------------------------------ pearls

    private boolean safeLanding(Vec3d land) {
        var p = mc.player;
        var rh = mc.world.raycast(new RaycastContext(land.add(0, 0.3, 0), land.subtract(0, 3.2, 0),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
        if (rh.getType() == HitResult.Type.MISS) return false;                       // void / big drop
        var floor = mc.world.getBlockState(rh.getBlockPos());
        if (!floor.getFluidState().isEmpty()) return false;
        if (floor.isOf(Blocks.MAGMA_BLOCK) || floor.isOf(Blocks.CACTUS) || floor.isOf(Blocks.CAMPFIRE)
                || floor.isOf(Blocks.SOUL_CAMPFIRE) || floor.isOf(Blocks.FIRE) || floor.isOf(Blocks.SWEET_BERRY_BUSH)
                || floor.isOf(Blocks.POWDER_SNOW)) return false;
        BlockPos at = BlockPos.ofFloored(land.x, land.y, land.z);
        if (!mc.world.getBlockState(at).getFluidState().isEmpty() || !mc.world.getBlockState(at.up()).getFluidState().isEmpty()) return false;
        return land.y - rh.getPos().y <= 2.5;
    }

    private Sol simPitch(float yaw, float pitch, Vec3d dest) {
        var p = mc.player;
        Vec3d pv = p.getVelocity();
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        Vec3d vel = new Vec3d(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr))
                .multiply(1.5).add(pv.x, p.isOnGround() ? 0 : pv.y, pv.z);
        Vec3d pos = p.getEyePos().subtract(0, 0.1, 0), land = null;
        double best = Double.MAX_VALUE; int bt = 0; boolean hit = false;
        for (int t = 1; t <= 160; t++) {
            Vec3d next = pos.add(vel);
            var rh = mc.world.raycast(new RaycastContext(pos, next, RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, p));
            if (rh.getType() != HitResult.Type.MISS) { next = rh.getPos(); hit = true; }
            double d = next.distanceTo(dest);
            if (d < best) { best = d; bt = t; }
            if (hit) { land = next; break; }
            pos = next;
            vel = vel.multiply(0.99).subtract(0, 0.03, 0);
            if (pos.y < dest.y - 12) break;
        }
        return new Sol(pitch, land, hit, best + bt * 0.01);
    }

    private static boolean fits(Sol s, Vec3d dest, boolean safe, MaceBot self) {
        if (!safe) return true;
        return s.hit() && s.land() != null && s.land().distanceTo(dest) < 6 && self.safeLanding(s.land());
    }

    /** Coarse search over the pitch, then a fine search around the best one. Null when no clear, safe arc exists. */
    private Sol pearlSolve(float yaw, Vec3d dest) {
        Sol best = null;
        for (float pit = 20f; pit >= -80f; pit -= 2f) {
            Sol s = simPitch(yaw, pit, dest);
            if (fits(s, dest, true, this) && (best == null || s.score() < best.score())) best = s;
        }
        if (best == null) return null;
        Sol fine = best;
        for (float pit = best.pitch() - 1.75f; pit <= best.pitch() + 1.75f; pit += 0.25f) {
            Sol s = simPitch(yaw, pit, dest);
            if (fits(s, dest, true, this) && s.score() < fine.score()) fine = s;
        }
        return fine.score() < 3.0 ? fine : null;
    }

    private boolean pearlSafeHealth() { return mc.player.getHealth() + mc.player.getAbsorptionAmount() > 5.5f; }

    private boolean throwPearl(float yaw, float pitch) {
        var p = mc.player;
        int old = p.getInventory().getSelectedSlot();
        if (!U.selectSlot(s -> s.isOf(Items.ENDER_PEARL))) return false;
        stopEating();
        p.setYaw(yaw); p.setPitch(pitch);
        mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
        p.swingHand(Hand.MAIN_HAND);
        p.getInventory().setSelectedSlot(old);
        pearlCd = 45;
        return true;
    }

    /** Closing pearl: lands next to where the target is going. */
    private boolean tryPearl(double dist) {
        var p = mc.player;
        if (!pearls.get() || pearlCd > 0 || solveCd > 0 || dist < pearlMin.get() || dist > pearlMax.get()) return false;
        if (!pearlSafeHealth() || p.getHealth() <= 6 || p.isUsingItem() || !hotbarHas(s -> s.isOf(Items.ENDER_PEARL))) return false;
        Vec3d dest = at(8).subtract(0, target.getHeight() * 0.5 - 0.2, 0);
        float yaw = U.rotTo(dest)[0];
        Sol s = pearlSolve(yaw, dest);
        if (s == null) { solveCd = 10; return false; }
        return throwPearl(yaw, s.pitch());
    }

    /** Escape pearl: straight away from the target, only onto a safe landing spot. */
    private boolean pearlAway() {
        var p = mc.player;
        if (pearlCd > 0 || solveCd > 0 || p.isUsingItem() || !pearlSafeHealth() || !hotbarHas(s -> s.isOf(Items.ENDER_PEARL))) return false;
        float away = U.rotTo(U.pos(target))[0] + 180f;
        double want = Math.max(safeDist.get() + 10, 26);
        for (float off : new float[]{0, 25, -25, 50, -50, 80, -80, 120, -120}) {
            float yaw = away + off;
            double yr = Math.toRadians(yaw);
            Vec3d dest = U.pos(p).add(-Math.sin(yr) * want, 0, Math.cos(yr) * want);
            Sol s = pearlSolve(yaw, dest);
            if (s != null) return throwPearl(yaw, s.pitch());
        }
        solveCd = 10;
        return false;
    }

    // ------------------------------------------------------------------ wind charge timing calibration

    /** Delay (ticks after the jump) before the charge is thrown. Tries 0, 1, 2 twice each, keeps the one that gave the most height. */
    private int windDelay() {
        if (!calibrate.get()) return windDelaySet.getInt();
        if (calibrated) return bestDelay;
        return Math.min(2, launches / 2);
    }

    private void recordGain(int d, double gain) {
        if (!calibrate.get() || calibrated || gain < 1.5 || d < 0 || d > 2) return;   // tiny gain = the charge did nothing, do not learn from it
        gainSum[d] += gain; gainN[d]++; launches++;
        if (launches >= 6) {
            double best = -1;
            for (int i = 0; i < 3; i++) {
                double avg = gainN[i] == 0 ? 0 : gainSum[i] / gainN[i];
                if (avg > best) { best = avg; bestDelay = i; }
            }
            calibrated = true;
            U.chat(String.format("MaceBot: wind charge timing calibrated: throw %d tick(s) after the jump (avg +%.1f blocks).", bestDelay, best));
        }
    }

    // ------------------------------------------------------------------ air boost (replaces "stacked" charges)

    /**
     * The wind charge item has a 10 tick cooldown, so a second charge only fires when you are already several blocks up,
     * where the floor is too far for the blast to reach you. What does work: a charge into a wall or the floor within ~3 blocks.
     * An explosion pushes you away from itself, so a surface behind you pushes you towards the target.
     */
    private boolean airBoostLogic(double gap) {
        var p = mc.player;
        if (!airBoost.get() || airBoosted || windCd > 0 || gap < 6 || !hotbarHas(s -> s.isOf(Items.WIND_CHARGE))) return false;
        Vec3d eye = p.getEyePos();
        float[] toward = U.rotTo(U.pos(target));
        Vec3d tdir = new Vec3d(-Math.sin(Math.toRadians(toward[0])), 0, Math.cos(Math.toRadians(toward[0])));
        float bestYaw = 0, bestPitch = 0; double bestScore = 0.3; boolean found = false;
        for (float off : new float[]{0, 40, -40}) {
            float yaw = toward[0] + 180f + off;
            double yr = Math.toRadians(yaw);
            for (float pitch : new float[]{0, 25, 50, 75, 90}) {
                double pr = Math.toRadians(pitch);
                Vec3d dir = new Vec3d(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
                var rh = mc.world.raycast(new RaycastContext(eye, eye.add(dir.multiply(3.2)), RaycastContext.ShapeType.COLLIDER,
                        RaycastContext.FluidHandling.NONE, p));
                if (rh.getType() == HitResult.Type.MISS) continue;
                double d = rh.getPos().distanceTo(eye);
                if (d > 3.0) continue;
                Vec3d push = eye.subtract(rh.getPos());
                double len = Math.hypot(push.x, push.z);
                double dot = len < 0.01 ? 0 : (push.x * tdir.x + push.z * tdir.z) / len;
                if (pitch == 90f && d <= 2.4) dot = Math.max(dot, 0.5);        // straight down: extra height instead
                double score = dot * 2 - d / 3.2;
                if (dot >= 0.35 && score > bestScore) { bestScore = score; bestYaw = yaw; bestPitch = pitch; found = true; }
            }
        }
        if (!found || !select(s -> s.isOf(Items.WIND_CHARGE))) return false;
        p.setYaw(bestYaw); p.setPitch(bestPitch);
        mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
        p.swingHand(Hand.MAIN_HAND);
        windCd = 10; airBoosted = true;
        select(s -> s.isOf(Items.MACE));
        return true;
    }

    // ------------------------------------------------------------------ defense vs their smash

    /** {landing x, landing z, ticks} when the target is diving at you, else null. */
    private double[] diveThreat() {
        var p = mc.player;
        if (!p.isOnGround() || target.isOnGround() || target.getY() - p.getY() < 2.5 || tvel.y > -0.25) return null;
        double y = target.getY(), v = tvel.y;
        int t = 0;
        while (y > p.getY() + 0.5 && t < 30) { y += v; v = (v - 0.08) * 0.98; t++; }
        if (t > 14) return null;
        double lx = target.getX() + tvel.x * t, lz = target.getZ() + tvel.z * t;
        if (Math.hypot(lx - p.getX(), lz - p.getZ()) > 4.2) return null;
        return new double[]{lx, lz, t};
    }

    private void dropShield() {
        if (!shielding) return;
        mc.options.useKey.setPressed(false);
        holdingUse = false; shielding = false;
    }

    /** Shield (if one is in your offhand) or sidestep. Returns true while it is handling a dive. */
    private boolean defenseLogic() {
        var p = mc.player;
        if (smashDefense.is("Off") || phase != Heal.NONE) { dropShield(); return false; }
        double[] th = diveThreat();
        if (th == null) { dropShield(); return false; }
        boolean shield = p.getOffHandStack().isOf(Items.SHIELD);
        boolean useShield = !smashDefense.is("Dodge") && shield;
        if (useShield) {
            stopRun();
            turn(U.pos(target).add(0, target.getHeight(), 0), true);
            if (!holdingUse) { mc.options.useKey.setPressed(true); holdingUse = true; shielding = true; }
            return true;
        }
        dropShield();
        if (smashDefense.is("Shield")) return false;                    // wants a shield but has none
        double ax = p.getX() - th[0], az = p.getZ() - th[1];
        if (Math.hypot(ax, az) < 0.1) { ax = -tvel.z; az = tvel.x; }     // right under him: sidestep across his path
        p.setYaw((float) (Math.toDegrees(Math.atan2(az, ax)) - 90.0));
        p.setPitch(0f);
        run(false);
        return true;
    }

    // ------------------------------------------------------------------ spear lunge (attribute swap)

    /** Swaps to the spear and jabs once so its lunge pushes you forward. The mace is back in hand next tick. */
    private boolean lungeBoost() {
        if (!lunge.get() || lungeBroken || lungeCd > 0 || !hotbarHas(MaceBot::isSpear)) return false;
        if (mc.player.getHungerManager().getFoodLevel() < 7) return false;           // lunge costs hunger
        select(MaceBot::isSpear);
        if (reachTo(target) <= spearReach.get()) hit(target);
        else {                                                                        // nothing in reach: a left click still lunges
            KeyBinding.onKeyPressed(InputUtil.fromTranslationKey(mc.options.attackKey.getBoundKeyTranslationKey()));
            mc.player.swingHand(Hand.MAIN_HAND);
        }
        lungeCd = 25;
        lungeCheck = 2; lungeSpeed = Math.hypot(mc.player.getVelocity().x, mc.player.getVelocity().z);
        return true;
    }

    // ------------------------------------------------------------------ healing / retreat

    private static boolean hasPearl() { return has(s -> s.isOf(Items.ENDER_PEARL)); }
    private boolean hasHealing() { return has(MaceBot::isHeal) || has(DefenseModules.AutoPotion::healing); }
    private void endHeal(int cooldown) { phase = Heal.NONE; healCd = cooldown; stopEating(); stopRun(); }

    /** True while healing / escaping (the fight logic is skipped this tick). */
    private boolean healLogic(double dist) {
        var p = mc.player;
        if (!heal.get()) return false;
        float hp = p.getHealth() + p.getAbsorptionAmount();
        if (phase == Heal.NONE) {
            if (healCd > 0 || hp > healBelow.get() || !hasHealing()) return false;
            phase = dist < safeDist.get() ? Heal.RETREAT : Heal.EAT;
            healTicks = 0; retreatTicks = 0;
        }
        healTicks++;
        if (p.getHealth() >= healUntil.get() || !hasHealing() || healTicks > 500) { endHeal(0); return false; }

        // the enemy reached us while eating: pearl out again, or give up healing and fight (no flip-flopping)
        if (phase == Heal.EAT && dist < panicDist.get()) {
            if (hasPearl() && pearlSafeHealth() && pearlCd == 0) { phase = Heal.RETREAT; retreatTicks = 0; }
            else { endHeal(100); return false; }
        }

        if (phase == Heal.RETREAT) {
            retreatTicks++;
            if (dist >= safeDist.get()) phase = Heal.EAT;
            else if (retreatTicks > 80) { endHeal(100); return false; }
            else {
                stopEating();
                if (pearlAway()) return true;
                boolean canPearl = hasPearl() && pearlSafeHealth();
                if (!canPearl && dist <= panicDist.get() * 1.6) { endHeal(100); return false; }   // no way out: fight
                if (!canPearl) phase = Heal.EAT;                                                  // far enough to risk eating
                else {                                                                            // pearl on cooldown: run away
                    float[] r = U.rotTo(U.pos(target));
                    p.setYaw(r[0] + 180f); p.setPitch(0f);
                    run(true);
                    return true;
                }
            }
        }

        // eat: splash potion at your feet first (instant), then gapples
        stopRun();
        if (p.getHealth() < healUntil.get() - 4 && windCd == 0 && hotbarHas(DefenseModules.AutoPotion::healing)) {
            int old = p.getInventory().getSelectedSlot();
            if (U.selectSlot(DefenseModules.AutoPotion::healing)) {
                float op = p.getPitch();
                p.setPitch(90f);
                mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
                p.setPitch(op);
                p.getInventory().setSelectedSlot(old);
                windCd = 12;
                return true;
            }
        }
        if (U.selectSlot(MaceBot::isHeal) && !holdingUse) { mc.options.useKey.setPressed(true); holdingUse = true; }
        return true;
    }

    // ------------------------------------------------------------------ water clutch

    /** After a miss from height: place water just before the ground, pick it up again after landing. */
    private boolean clutchLogic() {
        if (!clutch.get()) return false;
        var p = mc.player;
        if (clutchPlaced) {
            if (!(p.isOnGround() || p.isTouchingWater())) return false;
            if (++clutchWait < 2) return false;
            if (select(s -> s.isOf(Items.BUCKET))) {
                p.setPitch(90f);
                mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
            }
            clutchPlaced = false; clutchWait = 0;
            return true;
        }
        if (p.isOnGround() || p.isTouchingWater() || p.fallDistance < 7 || p.getVelocity().y > -0.5) return false;
        if (p.distanceTo(target) < 5 || !hotbarHas(s -> s.isOf(Items.WATER_BUCKET))) return false;   // a smash cancels fall damage
        double gap = U.pos(p).y - groundBelow(U.pos(p));
        if (gap > 3.4) return false;
        if (!select(s -> s.isOf(Items.WATER_BUCKET))) return false;
        p.setPitch(90f);
        mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
        clutchPlaced = true; clutchWait = 0;
        return true;
    }

    // ------------------------------------------------------------------ attribute swap + combo

    private static boolean roll(double percent) { return ThreadLocalRandom.current().nextInt(100) < percent; }

    /** Hotbar slot to swap to for the hit: the chosen slot, or any other mace. -1 when there is nothing to swap to. */
    private int swapTarget(int current) {
        var inv = mc.player.getInventory();
        int want = swapSlot.getInt();
        if (want >= 1) {
            int i = want - 1;
            return i != current && !inv.getStack(i).isEmpty() ? i : -1;
        }
        for (int i = 0; i < 9; i++) if (i != current && inv.getStack(i).isOf(Items.MACE)) return i;
        return -1;
    }

    /**
     * Attack with an attribute swap: the slot change is sent right before the attack packet and reverted right after,
     * so the server computes this one hit with the other item's attributes. Only some of the time (swapChance).
     */
    private void smashHit(Entity e) {
        var inv = mc.player.getInventory();
        int orig = inv.getSelectedSlot();
        if (attrSwap.get() && roll(swapChance.get())) {
            int alt = swapTarget(orig);
            if (alt >= 0) {
                inv.setSelectedSlot(alt);
                hit(e);
                inv.setSelectedSlot(orig);
                return;
            }
        }
        hit(e);
    }

    private boolean comboActive() { return combo.get() && comboTicks > 0; }

    /**
     * Target is much higher than you (wind charge users): pearl next to him first, so the wind charge launch + spear lunge
     * start from his position. Rolled once in a while, not every time.
     */
    private boolean tryComboPearl(double dist) {
        var p = mc.player;
        if (!combo.get() || !pearls.get() || comboRollCd > 0 || pearlCd > 0 || solveCd > 0) return false;
        if (target.getY() - p.getY() < comboHeight.get() || dist < 6 || dist > pearlMax.get()) return false;
        if (!pearlSafeHealth() || p.getHealth() <= 6 || p.isUsingItem() || !hotbarHas(s -> s.isOf(Items.ENDER_PEARL))) return false;
        comboRollCd = 80;                                       // one roll per attempt
        if (!roll(comboChance.get())) return false;
        Vec3d dest = at(8).subtract(0, target.getHeight() * 0.5 - 0.2, 0);
        float yaw = U.rotTo(dest)[0];
        Sol s = pearlSolve(yaw, dest);
        if (s == null) { solveCd = 10; return false; }
        if (!throwPearl(yaw, s.pitch())) return false;
        comboTicks = 200;                                       // pearl flight + launch + lunge window
        return true;
    }

    // ------------------------------------------------------------------ adaptive numbers

    private static double smash(double f) { return 6 + 4 * Math.min(f, 3) + 2 * Math.min(Math.max(f - 3, 0), 5) + Math.max(f - 8, 0); }

    /** Fall needed before swinging: the setting, but lower when a shorter fall already kills (armour assumed). */
    private double needFall() {
        if (popTicks > 0) return 1.6;                       // 1 HP + 8 absorption: any smash kills, be fast
        double hp = target.getHealth() + target.getAbsorptionAmount();
        for (double f = 1.6; f < minFall.get(); f += 0.25) if (smash(f) >= hp * 1.6) return f;
        return minFall.get();
    }

    private double effLaunch() {
        return Math.min(14, launchRange.get() + Math.hypot(tvel.x, tvel.z) * 8);
    }

    private boolean ceilingClear() {
        Vec3d eye = mc.player.getEyePos();
        return mc.world.raycast(new RaycastContext(eye, eye.add(0, 7, 0), RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, mc.player)).getType() == HitResult.Type.MISS;
    }

    // ------------------------------------------------------------------ main loop

    @Override public void onTick() {
        var p = mc.player;
        if (mc.currentScreen != null || p.isDead()) { release(); return; }
        if (pearlCd > 0) pearlCd--;
        if (axeCd > 0) axeCd--;
        if (windCd > 0) windCd--;
        if (lungeCd > 0) lungeCd--;
        if (refillCd > 0) refillCd--;
        if (solveCd > 0) solveCd--;
        if (healCd > 0) healCd--;
        if (hitCd > 0) hitCd--;
        if (popTicks > 0) popTicks--;
        if (comboTicks > 0) comboTicks--;
        if (comboRollCd > 0) comboRollCd--;
        if (lungeCheck > 0 && --lungeCheck == 0) {                 // did the lunge actually push us?
            double sp = Math.hypot(p.getVelocity().x, p.getVelocity().z);
            if (sp > lungeSpeed + 0.12) {
                lungeFails = 0;
                if (!lungeOk) { lungeOk = true; U.chat("MaceBot: spear lunge boost works."); }
            } else if (++lungeFails >= 3) {
                lungeBroken = true;
                U.chat("MaceBot: the spear lunge never boosted you (3 tries), so it is switched off for this session.");
            }
        }
        if (stopHealth.get() > 0 && p.getHealth() <= stopHealth.get()) { release(); return; }

        if (target == null || !target.isAlive() || target.isRemoved() || p.distanceTo(target) > range.get()
                || (respectFriends.get() && ExtraCombat.FriendGuard.isFriend(target))) {
            target = pick();
            st = St.SEEK; stTicks = 0; phase = Heal.NONE;
        }
        if (target == null) { release(); return; }
        CombatModules.KillAura.TARGET = target;
        track();

        if (maintain()) return;
        if (clutchLogic()) return;
        if (healLogic(p.distanceTo(target))) return;

        boolean useMace = weapon.is("Mace") || (weapon.is("Auto") && has(s -> s.isOf(Items.MACE)));
        boolean useSpear = weapon.is("Spear") || (weapon.is("Auto") && !useMace && has(MaceBot::isSpear));
        if (!useMace && !useSpear) {
            if (!warned) { U.chat("MaceBot: no mace or spear in your inventory."); warned = true; }
            release();
            return;
        }
        if (useMace && !hotbarHas(s -> s.isOf(Items.MACE))) return;       // still being moved into the hotbar
        if (useSpear && !hotbarHas(MaceBot::isSpear)) return;

        if (useMace) mace(); else spear();
    }

    // ------------------------------------------------------------------ mace

    private void mace() {
        var p = mc.player;
        double dist = p.distanceTo(target);
        Vec3d body = U.pos(target).add(0, target.getHeight() * 0.5, 0);
        Vec3d aim = intercept();
        double hd = horiz(target), gap = gapTo(aim);
        stTicks++;

        // ---- in the air: lunge if far, steer onto the intercept point, smash
        if (st == St.AIR) {
            if (stTicks > 2 && p.isOnGround()) { st = St.SEEK; stTicks = 0; return; }
            if (stTicks > 80) { st = St.SEEK; stTicks = 0; return; }

            apexY = Math.max(apexY, p.getY());
            if (!apexDone && stTicks >= 2 && p.getVelocity().y < 0) {
                apexDone = true;
                if (thrown && !airBoosted) recordGain(curDelay, apexY - launchY);
            }
            // spear lunge only while the gap is big; once close it stays put so you stay on him
            double lungeAt = comboActive() || target.getY() - p.getY() >= comboHeight.get() ? Math.min(lungeMin.get(), 2.5) : lungeMin.get();
            if (!lungeDone && stTicks >= 2 && gap >= lungeAt && gap < 16 && p.getVelocity().y < 0.3 && lungeBoost()) {
                lungeDone = true;
                turn(aim, true);
                return;
            }
            if (stTicks > 12) lungeDone = true;
            if (stTicks >= 6 && airBoostLogic(gap)) return;

            select(s -> s.isOf(Items.MACE));
            boolean near = reachTo(target) <= reach.get() + 0.6;
            turn(near ? body : aim, true);                                  // real body for the hit, intercept point while closing
            if (gap > 0.9) run(false); else stopRun();

            boolean falling = p.getVelocity().y < -0.05 && p.fallDistance >= needFall();
            if (falling && hitCd == 0 && near && reachPred() <= reach.get()) {
                if (breakShield.get() && target.isBlocking() && axeCd == 0 && hotbarHas(MaceBot::isAxe)) {
                    select(MaceBot::isAxe);                                 // last-second shield break, then straight back
                    hit(target);
                    axeCd = 12;
                    select(s -> s.isOf(Items.MACE));
                    return;
                }
                select(s -> s.isOf(Items.MACE));                            // no axe / already axed: smash anyway
                smashHit(target);
                comboTicks = 0;
            }
            return;
        }

        // ---- jump happened, now the wind charge at the floor
        if (st == St.JUMP) {
            if ((!p.isOnGround() && stTicks > curDelay) || stTicks >= curDelay + 3) {
                if (select(s -> s.isOf(Items.WIND_CHARGE))) {
                    thrown = true;
                    p.setPitch(90f);
                    p.setYaw(U.rotTo(body)[0]);
                    mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
                    p.swingHand(Hand.MAIN_HAND);
                    windCd = 10;
                }
                select(s -> s.isOf(Items.MACE));
                st = St.AIR; stTicks = 0; lungeDone = false; airBoosted = false;
            }
            return;
        }

        // ---- on the ground
        if (defenseLogic()) return;
        if (tryComboPearl(dist) || tryPearl(dist)) return;
        turn(aim, false);

        boolean axe = hotbarHas(MaceBot::isAxe);
        if (breakShield.get() && axe && target.isBlocking() && dist <= 4.5) {
            stopRun();                                                      // pre-break, launch follows with the shield on cooldown
            if (reachTo(target) <= 3.0 && axeCd == 0) {
                select(MaceBot::isAxe);
                turn(body, true);
                if (p.getAttackCooldownProgress(0.5f) >= 0.9f) { hit(target); axeCd = 12; }
            } else if (hd > 1.0) run(true);
            return;
        }

        boolean canWind = wind.get() && windCd == 0 && hotbarHas(s -> s.isOf(Items.WIND_CHARGE));
        if (canWind && p.isOnGround() && gap <= effLaunch() && !p.isUsingItem() && ceilingClear()) {
            select(s -> s.isOf(Items.WIND_CHARGE));
            dropShield();
            curDelay = windDelay();
            launchY = p.getY(); apexY = launchY; apexDone = false; thrown = false;
            p.jump();
            st = St.JUMP; stTicks = 0;
            return;
        }

        // no wind charge (or no room above): plain mace fighting
        if ((!canWind || !ceilingClear()) && reachTo(target) <= reach.get()) {
            select(s -> s.isOf(Items.MACE));
            turn(body, true);
            if (p.getAttackCooldownProgress(0.5f) >= 0.95f) smashHit(target);
            stopRun();
            return;
        }

        if (gap > effLaunch() + 2 && gap >= lungeMin.get() && gap < 14 && p.isOnGround() && lungeBoost()) return;
        select(s -> s.isOf(Items.MACE));
        run(true);
    }

    // ------------------------------------------------------------------ spear

    private void spear() {
        var p = mc.player;
        double dist = p.distanceTo(target);
        Vec3d body = U.pos(target).add(0, target.getHeight() * 0.5, 0);
        if (tryPearl(dist)) return;
        turn(intercept(), false);

        boolean axe = hotbarHas(MaceBot::isAxe);
        boolean shieldUp = breakShield.get() && axe && target.isBlocking();
        if (shieldUp && reachTo(target) <= 3.0 && axeCd == 0) {
            stopEating();
            select(MaceBot::isAxe);
            turn(body, true);
            if (p.getAttackCooldownProgress(0.5f) >= 0.9f) { hit(target); axeCd = 12; }
            return;
        }

        select(MaceBot::isSpear);
        run(true);

        boolean charge = spearCharge.get() && dist >= 3.5 && dist <= 14 && !shieldUp;
        if (charge != holdingUse) { mc.options.useKey.setPressed(charge); holdingUse = charge; }
        if (charge) return;

        if (reachTo(target) <= spearReach.get() && !p.isUsingItem()
                && p.getAttackCooldownProgress(0.5f) >= 0.95f && !shieldUp) {
            turn(body, true);
            hit(target);
        }
        if (reachTo(target) < 1.2) stopRun();
    }
}
