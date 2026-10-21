package nova.modules;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.Potions;
import net.minecraft.util.Hand;
import nova.core.*;

import java.util.regex.Pattern;

/** Fight support: bot filter, shield, bow, golden apples, potions. */
public final class DefenseModules {
    private DefenseModules() {}

    /** Filters fake players (not in the tab list, odd names) out of KillAura / ESP / AimAssist. */
    public static class AntiBot extends Mod {
        static AntiBot INSTANCE;
        Setting.Bool tab = bool("Must be in tab list", true), name = bool("Valid name only", true);
        static final Pattern NAME = Pattern.compile("^\\w{3,16}$");

        public AntiBot() { super("AntiBot", Category.COMBAT, "Ignores fake players when targeting."); INSTANCE = this; }

        public static boolean isBot(Entity e) {
            if (INSTANCE == null || !INSTANCE.enabled || !(e instanceof PlayerEntity p)) return false;
            var h = net.minecraft.client.MinecraftClient.getInstance().getNetworkHandler();
            if (INSTANCE.tab.get() && (h == null || h.getPlayerListEntry(p.getUuid()) == null)) return true;
            return INSTANCE.name.get() && !NAME.matcher(p.getName().getString()).matches();
        }
    }

    /** Raises a shield while an enemy is close. */
    public static class AutoShield extends Mod {
        Setting.Num range = num("Range", 4, 2, 8, 0.5);
        boolean holding;
        public AutoShield() { super("AutoShield", Category.COMBAT, "Blocks with your shield when a player is near."); }

        @Override public void onTick() {
            var p = mc.player;
            boolean has = p.getOffHandStack().isOf(Items.SHIELD) || p.getMainHandStack().isOf(Items.SHIELD);
            boolean near = false;
            if (has && mc.currentScreen == null)
                for (PlayerEntity o : mc.world.getPlayers())
                    if (o != p && !DefenseModules.AntiBot.isBot(o) && p.distanceTo(o) <= range.get()) { near = true; break; }
            if (near != holding) { mc.options.useKey.setPressed(near); holding = near; }
        }
        @Override public void onDisable() { if (holding) mc.options.useKey.setPressed(false); holding = false; }
    }

    /** Eats a golden apple when health is low. */
    public static class AutoGapple extends Mod {
        Setting.Num health = num("Health <=", 10, 1, 20, 1);
        boolean eating; int ticks, back = -1;
        public AutoGapple() { super("AutoGapple", Category.COMBAT, "Eats a golden apple when your health is low."); }

        @Override public void onTick() {
            var p = mc.player;
            if (eating) {
                ticks++;
                if (!p.isUsingItem() && ticks > 4 || ticks > 60) stop();
                return;
            }
            if (p.getHealth() > health.get() || mc.currentScreen != null) return;
            var inv = p.getInventory();
            back = inv.getSelectedSlot();
            if (U.selectSlot(s -> s.isOf(Items.GOLDEN_APPLE) || s.isOf(Items.ENCHANTED_GOLDEN_APPLE))) {
                eating = true; ticks = 0;
                mc.options.useKey.setPressed(true);
            }
        }
        private void stop() {
            eating = false; mc.options.useKey.setPressed(false);
            if (back >= 0 && mc.player != null) mc.player.getInventory().setSelectedSlot(back);
        }
        @Override public void onDisable() { if (eating) stop(); }
    }

    /** Throws a healing splash potion at your feet when health is low. */
    public static class AutoPotion extends Mod {
        Setting.Num health = num("Health <=", 8, 1, 20, 1), cooldown = num("Cooldown (ticks)", 20, 5, 100, 1);
        int cd;
        public AutoPotion() { super("AutoPotion", Category.COMBAT, "Throws healing splash potions when hurt."); }

        static boolean healing(ItemStack s) {
            if (!s.isOf(Items.SPLASH_POTION)) return false;
            PotionContentsComponent c = s.get(DataComponentTypes.POTION_CONTENTS);
            return c != null && (c.matches(Potions.HEALING) || c.matches(Potions.STRONG_HEALING));
        }

        @Override public void onTick() {
            var p = mc.player;
            if (cd > 0) { cd--; return; }
            if (p.getHealth() > health.get() || mc.currentScreen != null) return;
            var inv = p.getInventory();
            int old = inv.getSelectedSlot();
            if (!U.selectSlot(DefenseModules.AutoPotion::healing)) return;
            float oldPitch = p.getPitch();
            p.setPitch(90f);
            mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
            p.setPitch(oldPitch);
            inv.setSelectedSlot(old);
            cd = cooldown.getInt();
        }
    }
}
