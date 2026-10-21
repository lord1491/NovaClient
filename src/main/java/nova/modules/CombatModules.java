package nova.modules;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import nova.core.*;

import java.util.concurrent.ThreadLocalRandom;

public final class CombatModules {
    private CombatModules() {}

    static boolean allowed(Entity e, boolean players, boolean hostiles, boolean passives) {
        if (!(e instanceof LivingEntity le) || le == mcPlayer() || !le.isAlive() || le instanceof ArmorStandEntity) return false;
        if (DefenseModules.AntiBot.isBot(e) || ExtraCombat.FriendGuard.isFriend(e)) return false;
        return le instanceof PlayerEntity ? players : le instanceof Monster ? hostiles : passives;
    }

    private static PlayerEntity mcPlayer() { return net.minecraft.client.MinecraftClient.getInstance().player; }

    public static class KillAura extends Mod {
        Setting.Num range = num("Range", 4.2, 1, 6, 0.1);
        Setting.Mode priority = mode("Priority", "Distance", "Distance", "Health", "Angle");
        Setting.Bool players = bool("Players", true), hostiles = bool("Hostiles", true), passives = bool("Passives", false);
        Setting.Bool rotate = bool("Rotate", true), requireAim = bool("Require aim", true),
                cooldown = bool("Use cooldown", true), humanize = bool("Humanize", true), mouseFix = bool("Mouse fix", true);
        Setting.Num aimSpeed = num("Aim speed", 22, 5, 100, 1), maxTurn = num("Max turn / tick", 14, 2, 90, 1);
        Setting.Mode crits = mode("Crits", "Off", "Off", "Wait", "Jump");
        Setting.Num cps = num("CPS (no cooldown)", 10, 1, 20, 1);
        public static LivingEntity TARGET;
        LivingEntity target;
        long last;
        double nextDelay = 100;
        int wait = -1;

        public KillAura() { super("KillAura", Category.COMBAT, "Smoothly turns to and attacks entities in range."); }

        @Override public void onDisable() { target = null; TARGET = null; wait = -1; }

        private boolean valid(LivingEntity e) {
            return e.isAlive() && mc.player.distanceTo(e) <= range.get() + 0.3
                    && allowed(e, players.get(), hostiles.get(), passives.get());
        }

        private LivingEntity pick() {
            LivingEntity best = null; double bs = Double.MAX_VALUE;
            for (Entity e : mc.world.getEntities()) {
                if (!allowed(e, players.get(), hostiles.get(), passives.get())) continue;
                LivingEntity le = (LivingEntity) e;
                double d = mc.player.distanceTo(le);
                if (d > range.get()) continue;
                double s = priority.is("Health") ? le.getHealth() : priority.is("Angle") ? U.angleTo(U.aimPoint(le)) : d;
                if (s < bs) { bs = s; best = le; }
            }
            return best;
        }

        @Override public void onTick() {
            var p = mc.player;
            if (mc.currentScreen != null || p.isUsingItem()) { target = null; TARGET = null; return; }
            if (target == null || !valid(target)) { target = pick(); wait = -1; }
            TARGET = target;
            if (target == null) return;

            if (rotate.get()) {
                float[] t = U.rotTo(U.aimPoint(target));
                float[] r = U.smoothRot(p.getYaw(), p.getPitch(), t[0], t[1], aimSpeed.get(), maxTurn.get(), humanize.get(), mouseFix.get());
                p.setYaw(r[0]); p.setPitch(r[1]);
            }
            if (requireAim.get() && !U.lookingAt(target, range.get() + 0.3)) return;

            boolean ready = cooldown.get() ? p.getAttackCooldownProgress(0.5f) >= 0.95f
                                           : System.currentTimeMillis() - last >= nextDelay;
            if (!crits.is("Off")) {
                if (p.isOnGround()) { if (crits.is("Jump") && ready) p.jump(); return; }
                boolean falling = p.fallDistance > 0.05f && !p.isClimbing() && !p.isTouchingWater();
                if (!falling) return;
            }
            if (!ready) { wait = -1; return; }

            if (cooldown.get()) {
                if (wait < 0) wait = humanize.get() ? ThreadLocalRandom.current().nextInt(0, 3) : 0;
                if (wait > 0) { wait--; return; }
                wait = -1;
            } else {
                last = System.currentTimeMillis();
                nextDelay = (1000.0 / cps.get()) * (humanize.get() ? U.jitter(0.22) : 1.0);
            }
            mc.interactionManager.attackEntity(p, target);
            p.swingHand(Hand.MAIN_HAND);
        }
    }

    public static class AimAssist extends Mod {
        Setting.Num range = num("Range", 5, 2, 8, 0.5), fov = num("FOV", 70, 10, 180, 5),
                speed = num("Speed", 10, 1, 60, 1), maxTurn = num("Max turn / tick", 3.5, 1, 30, 0.5);
        Setting.Bool players = bool("Players", true), hostiles = bool("Hostiles", false), passives = bool("Passives", false),
                onlyAttack = bool("Only when attacking", true), vertical = bool("Vertical", true), mouseFix = bool("Mouse fix", true);

        public AimAssist() { super("AimAssist", Category.COMBAT, "Gently pulls your aim towards a target. Never snaps."); }

        @Override public void onTick() {
            var p = mc.player;
            if (mc.currentScreen != null) return;
            if (onlyAttack.get() && !mc.options.attackKey.isPressed()) return;
            LivingEntity best = null; double ba = Double.MAX_VALUE;
            for (Entity e : mc.world.getEntities()) {
                if (!allowed(e, players.get(), hostiles.get(), passives.get())) continue;
                if (p.distanceTo(e) > range.get()) continue;
                double a = U.angleTo(U.aimPoint(e));
                if (a > fov.get() / 2.0 || a >= ba) continue;
                ba = a; best = (LivingEntity) e;
            }
            if (best == null) return;
            float[] t = U.rotTo(U.aimPoint(best));
            float[] r = U.smoothRot(p.getYaw(), p.getPitch(), t[0], vertical.get() ? t[1] : p.getPitch(),
                    speed.get(), maxTurn.get(), true, mouseFix.get());
            p.setYaw(r[0]); p.setPitch(r[1]);
        }
    }

    public static class AutoTotem extends Mod {
        Setting.Num delay = num("Delay (ticks)", 2, 0, 20, 1);
        Setting.Num health = num("Health <=", 20, 1, 20, 1);
        Setting.Bool random = bool("Random extra delay", true);
        int cd;

        public AutoTotem() { super("AutoTotem", Category.COMBAT, "Keeps a totem in your offhand."); }

        @Override public void onTick() {
            var p = mc.player;
            if (cd > 0) { cd--; return; }
            if (mc.currentScreen != null) return;
            if (p.getHealth() > health.get()) return;
            if (p.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) return;
            var inv = p.getInventory();
            for (int i = 0; i < 36; i++) {
                if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) {
                    int slot = i < 9 ? i + 36 : i;
                    mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, slot, 40, SlotActionType.SWAP, p);
                    cd = delay.getInt() + (random.get() ? ThreadLocalRandom.current().nextInt(0, 3) : 0);
                    return;
                }
            }
        }
    }

    public static class AutoClicker extends Mod {
        Setting.Num cps = num("CPS", 10, 1, 20, 1);
        Setting.Bool humanize = bool("Humanize", true);
        long last;
        double nextDelay = 100;
        public AutoClicker() { super("AutoClicker", Category.COMBAT, "Attacks while you hold left click."); }

        @Override public void onTick() {
            if (mc.currentScreen != null || !mc.options.attackKey.isPressed()) return;
            if (!(mc.crosshairTarget instanceof EntityHitResult hit) || ExtraCombat.FriendGuard.isFriend(hit.getEntity())) return;
            long now = System.currentTimeMillis();
            if (now - last < nextDelay) return;
            last = now;
            double base = 1000.0 / cps.get();
            nextDelay = humanize.get() ? base * U.jitter(0.25) + (ThreadLocalRandom.current().nextInt(12) == 0 ? U.rand(30, 90) : 0) : base;
            mc.interactionManager.attackEntity(mc.player, hit.getEntity());
            mc.player.swingHand(Hand.MAIN_HAND);
        }
    }

    public static class TriggerBot extends Mod {
        Setting.Num reaction = num("Reaction (ticks)", 2, 0, 8, 1);
        Setting.Bool humanize = bool("Humanize", true);
        int seen = -1, need;
        public TriggerBot() { super("TriggerBot", Category.COMBAT, "Attacks what you aim at once the cooldown is ready."); }

        @Override public void onTick() {
            if (mc.currentScreen != null || !(mc.crosshairTarget instanceof EntityHitResult hit)
                    || !(hit.getEntity() instanceof LivingEntity) || ExtraCombat.FriendGuard.isFriend(hit.getEntity())) { seen = -1; return; }
            if (seen < 0) { seen = 0; need = reaction.getInt() + (humanize.get() ? ThreadLocalRandom.current().nextInt(0, 2) : 0); }
            seen++;
            if (seen <= need) return;
            if (mc.player.getAttackCooldownProgress(0.5f) < 0.95f) return;
            mc.interactionManager.attackEntity(mc.player, hit.getEntity());
            mc.player.swingHand(Hand.MAIN_HAND);
            seen = -1;
        }
    }

    public static class AutoWeapon extends Mod {
        public AutoWeapon() { super("AutoWeapon", Category.COMBAT, "Selects a sword or axe when you attack something."); }

        @Override public void onTick() {
            if (!mc.options.attackKey.isPressed() || !(mc.crosshairTarget instanceof EntityHitResult)) return;
            ItemStack held = mc.player.getMainHandStack();
            if (held.isIn(ItemTags.SWORDS)) return;
            if (!U.selectSlot(s -> s.isIn(ItemTags.SWORDS))) U.selectSlot(s -> s.isIn(ItemTags.AXES));
        }
    }

    public static class AutoArmor extends Mod {
        Setting.Num delay = num("Delay (ticks)", 3, 0, 10, 1);
        int cd;
        static final String[] PIECES = {"_helmet", "_chestplate", "_leggings", "_boots"};
        static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

        public AutoArmor() { super("AutoArmor", Category.COMBAT, "Equips the best armor from your inventory."); }

        static int rank(ItemStack s, String piece) {
            if (s.isEmpty()) return -1;
            String id = Registries.ITEM.getId(s.getItem()).getPath();
            if (!id.endsWith(piece)) return -1;
            if (id.startsWith("netherite")) return 6;
            if (id.startsWith("diamond")) return 5;
            if (id.startsWith("iron")) return 4;
            if (id.startsWith("chainmail")) return 3;
            if (id.startsWith("golden") || id.startsWith("copper")) return 2;
            if (id.startsWith("leather")) return 1;
            return 0;
        }

        @Override public void onTick() {
            if (cd > 0) { cd--; return; }
            if (mc.currentScreen != null) return;
            var p = mc.player; var inv = p.getInventory();
            int sync = p.playerScreenHandler.syncId;
            for (int k = 0; k < 4; k++) {
                int worn = rank(p.getEquippedStack(SLOTS[k]), PIECES[k]);
                int bestRank = worn, bestSlot = -1;
                for (int i = 0; i < 36; i++) {
                    int r = rank(inv.getStack(i), PIECES[k]);
                    if (r > bestRank) { bestRank = r; bestSlot = i; }
                }
                if (bestSlot < 0) continue;
                if (worn >= 0) mc.interactionManager.clickSlot(sync, 5 + k, 0, SlotActionType.QUICK_MOVE, p);
                else mc.interactionManager.clickSlot(sync, bestSlot < 9 ? bestSlot + 36 : bestSlot, 0, SlotActionType.QUICK_MOVE, p);
                cd = delay.getInt() + 1;
                return;
            }
        }
    }

    public static class Surround extends Mod {
        public Surround() { super("Surround", Category.COMBAT, "Places obsidian around your feet."); }

        @Override public void onTick() {
            if (!mc.player.isOnGround()) return;
            if (!U.selectSlot(s -> s.isOf(Items.OBSIDIAN))) return;
            BlockPos b = mc.player.getBlockPos();
            for (BlockPos pos : new BlockPos[]{b.north(), b.south(), b.east(), b.west()}) {
                if (U.place(pos)) return;
            }
        }
    }
}
