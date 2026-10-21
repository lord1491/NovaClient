package nova.modules;

import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import nova.core.*;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Combat modules ported from the Anubis module list (tick based, no mixins). */
public final class ExtraCombat {
    private ExtraCombat() {}

    /** Friends are skipped by KillAura, AimAssist, TriggerBot, AutoClicker, ShieldBreaker and CrystalAura. */
    public static class FriendGuard extends Mod {
        public static final Set<UUID> FRIENDS = new HashSet<>();
        static FriendGuard INSTANCE;
        Setting.Bool middle = bool("Middle click toggles friend", true);
        Setting.Action clear = action("Clear friends", FRIENDS::clear);
        boolean down;

        public FriendGuard() {
            super("FriendGuard", Category.COMBAT, "Never attacks friends. Middle click a player to add or remove a friend (kept until the game closes).");
            INSTANCE = this;
        }

        public static boolean isFriend(Entity e) {
            return INSTANCE != null && INSTANCE.enabled && e instanceof PlayerEntity && FRIENDS.contains(e.getUuid());
        }

        @Override public void onTick() {
            if (!middle.get() || mc.currentScreen != null) { down = false; return; }
            boolean d = GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS;
            if (d && !down && mc.crosshairTarget instanceof EntityHitResult hit && hit.getEntity() instanceof PlayerEntity pl) {
                UUID id = pl.getUuid();
                String n = pl.getName().getString();
                if (FRIENDS.remove(id)) U.chat(n + " removed from friends.");
                else { FRIENDS.add(id); U.chat(n + " added to friends."); }
            }
            down = d;
        }
    }

    /** Swaps to a mace and smashes what you are aiming at while falling. */
    public static class Mace extends Mod {
        Setting.Num minFall = num("Min fall distance", 4, 1, 30, 0.5), range = num("Range", 3.0, 2, 6, 0.1);
        Setting.Bool swap = bool("Auto swap to mace", true), swapBack = bool("Swap back", true);
        int prev = -1;

        public Mace() { super("Mace", Category.COMBAT, "Smash attack: swaps to a mace and hits the target in your crosshair while falling."); }

        @Override public void onDisable() { restore(); }

        private void restore() {
            if (prev >= 0 && swapBack.get() && mc.player != null) mc.player.getInventory().setSelectedSlot(prev);
            prev = -1;
        }

        @Override public void onTick() {
            var p = mc.player;
            if (mc.currentScreen != null) return;
            boolean falling = !p.isOnGround() && p.fallDistance >= minFall.get() && !p.isClimbing() && !p.isTouchingWater();
            LivingEntity t = null;
            if (mc.crosshairTarget instanceof EntityHitResult h && h.getEntity() instanceof LivingEntity le
                    && CombatModules.allowed(le, true, true, false)) t = le;
            if (!falling || t == null || p.distanceTo(t) > range.get()) { restore(); return; }
            if (!p.getMainHandStack().isOf(Items.MACE)) {
                if (!swap.get()) return;
                int cur = p.getInventory().getSelectedSlot();
                if (!U.selectSlot(s -> s.isOf(Items.MACE))) return;
                if (prev < 0) prev = cur;
            }
            if (p.getAttackCooldownProgress(0.5f) < 0.95f) return;
            mc.interactionManager.attackEntity(p, t);
            p.swingHand(Hand.MAIN_HAND);
        }
    }

    /** Resets your sprint right after you land a hit, so the next hit knocks back again. */
    public static class WTap extends Mod {
        Setting.Mode mode = mode("Mode", "W Tap", "W Tap", "S Tap");
        Setting.Num chance = num("Chance %", 100, 10, 100, 5);
        int lastId = -1, lastHurt;

        public WTap() { super("WTap", Category.COMBAT, "Sprint reset after every hit (W Tap), or a short slow-down as well (S Tap)."); }

        @Override public void onTick() {
            var p = mc.player;
            LivingEntity t = CombatModules.KillAura.TARGET;
            if (t == null && mc.crosshairTarget instanceof EntityHitResult h && h.getEntity() instanceof LivingEntity le) t = le;
            if (t == null || !t.isAlive()) { lastId = -1; lastHurt = 0; return; }
            boolean newTarget = t.getId() != lastId;
            boolean fresh = (newTarget ? t.hurtTime >= t.maxHurtTime - 1 && t.hurtTime > 0 : t.hurtTime > lastHurt)
                    && p.getAttackCooldownProgress(0f) < 0.6f && p.distanceTo(t) < 6;
            lastId = t.getId(); lastHurt = t.hurtTime;
            if (!fresh || ThreadLocalRandom.current().nextInt(100) >= chance.getInt()) return;
            p.setSprinting(false);
            if (mode.is("S Tap")) {
                Vec3d v = p.getVelocity();
                p.setVelocity(v.x * 0.5, v.y, v.z * 0.5);
            }
        }
    }

    /** Jumps the moment you get hit, so the knockback is smaller. */
    public static class JumpReset extends Mod {
        Setting.Num chance = num("Chance %", 100, 10, 100, 5);

        public JumpReset() { super("JumpReset", Category.COMBAT, "Jumps when you take a hit while on the ground (less knockback)."); }

        @Override public void onTick() {
            var p = mc.player;
            if (mc.currentScreen != null || !p.isOnGround()) return;
            if (p.hurtTime > 0 && p.hurtTime >= p.maxHurtTime - 1 && ThreadLocalRandom.current().nextInt(100) < chance.getInt()) p.jump();
        }
    }

    /** Swaps to an axe and hits a player who is blocking with a shield. */
    public static class ShieldBreaker extends Mod {
        Setting.Bool swapBack = bool("Swap back", true);
        int prev = -1;

        public ShieldBreaker() { super("ShieldBreaker", Category.COMBAT, "Axe swap + hit when the player in your crosshair raises a shield."); }

        @Override public void onDisable() { restore(); }

        private void restore() {
            if (prev >= 0 && swapBack.get() && mc.player != null) mc.player.getInventory().setSelectedSlot(prev);
            prev = -1;
        }

        @Override public void onTick() {
            var p = mc.player;
            if (mc.currentScreen != null) return;
            if (!(mc.crosshairTarget instanceof EntityHitResult h) || !(h.getEntity() instanceof PlayerEntity t)
                    || !t.isBlocking() || FriendGuard.isFriend(t) || DefenseModules.AntiBot.isBot(t) || p.distanceTo(t) > 4.5) {
                restore();
                return;
            }
            if (!p.getMainHandStack().isIn(ItemTags.AXES)) {
                int cur = p.getInventory().getSelectedSlot();
                if (!U.selectSlot(s -> s.isIn(ItemTags.AXES))) return;
                if (prev < 0) prev = cur;
            }
            if (p.getAttackCooldownProgress(0.5f) < 0.95f) return;
            mc.interactionManager.attackEntity(p, t);
            p.swingHand(Hand.MAIN_HAND);
        }
    }

    /** Places end crystals on obsidian / bedrock next to the nearest player and breaks them. */
    public static class CrystalAura extends Mod {
        Setting.Num range = num("Range", 4.5, 2, 6, 0.1), targetRange = num("Target range", 10, 4, 16, 1),
                delay = num("Delay (ticks)", 2, 0, 10, 1), minHealth = num("Stop below HP", 6, 0, 20, 1);
        Setting.Bool place = bool("Place", true), breakC = bool("Break", true), swap = bool("Auto swap to crystals", true);
        int cd;

        public CrystalAura() { super("CrystalAura", Category.COMBAT, "Places and breaks end crystals around the nearest player."); }

        private PlayerEntity nearest() {
            PlayerEntity best = null; double bd = targetRange.get();
            for (PlayerEntity o : mc.world.getPlayers()) {
                if (o == mc.player || !o.isAlive() || FriendGuard.isFriend(o) || DefenseModules.AntiBot.isBot(o)) continue;
                double d = mc.player.distanceTo(o);
                if (d <= bd) { bd = d; best = o; }
            }
            return best;
        }

        @Override public void onTick() {
            var p = mc.player;
            if (cd > 0) { cd--; return; }
            if (mc.currentScreen != null || p.getHealth() <= minHealth.get()) return;
            PlayerEntity target = nearest();
            if (target == null) return;

            if (breakC.get()) {
                EndCrystalEntity best = null; double bd = Double.MAX_VALUE;
                for (Entity e : mc.world.getEntities()) {
                    if (!(e instanceof EndCrystalEntity c) || !c.isAlive()) continue;
                    if (p.distanceTo(c) > range.get() || c.distanceTo(target) > 7) continue;
                    double d = c.distanceTo(target);
                    if (d < bd) { bd = d; best = c; }
                }
                if (best != null) {
                    mc.interactionManager.attackEntity(p, best);
                    p.swingHand(Hand.MAIN_HAND);
                    cd = delay.getInt();
                    return;
                }
            }

            if (!place.get()) return;
            Hand hand = p.getOffHandStack().isOf(Items.END_CRYSTAL) ? Hand.OFF_HAND : Hand.MAIN_HAND;
            if (hand == Hand.MAIN_HAND && !p.getMainHandStack().isOf(Items.END_CRYSTAL)) {
                if (!swap.get() || !U.selectSlot(s -> s.isOf(Items.END_CRYSTAL))) return;
            }
            BlockPos base = findBase(target);
            if (base == null) return;
            Vec3d hit = Vec3d.ofCenter(base).add(0, 0.5, 0);
            mc.interactionManager.interactBlock(p, hand, new BlockHitResult(hit, Direction.UP, base, false));
            p.swingHand(hand);
            cd = delay.getInt();
        }

        private BlockPos findBase(PlayerEntity target) {
            var p = mc.player;
            Vec3d eye = p.getEyePos(), self = U.pos(p), foe = U.pos(target);
            BlockPos origin = target.getBlockPos();
            BlockPos best = null; double bs = Double.MAX_VALUE;
            for (int x = -3; x <= 3; x++) for (int y = -2; y <= 0; y++) for (int z = -3; z <= 3; z++) {
                BlockPos b = origin.add(x, y, z);
                var st = mc.world.getBlockState(b);
                if (!st.isOf(Blocks.OBSIDIAN) && !st.isOf(Blocks.BEDROCK)) continue;
                if (!mc.world.getBlockState(b.up()).isAir() || !mc.world.getBlockState(b.up(2)).isAir()) continue;
                Vec3d c = Vec3d.ofCenter(b);
                if (c.distanceTo(eye) > range.get() || c.distanceTo(self) < 3.0) continue;
                if (!mc.world.getOtherEntities(null, new Box(b.up()).stretch(0, 1, 0)).isEmpty()) continue;
                double s = c.distanceTo(foe) - c.distanceTo(self) * 0.25;
                if (s < bs) { bs = s; best = b; }
            }
            return best;
        }
    }
}
