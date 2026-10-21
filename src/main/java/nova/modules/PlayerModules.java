package nova.modules;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.BlockItem;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.item.ItemStack;
import nova.core.*;

public final class PlayerModules {
    private PlayerModules() {}

    public static class NoFall extends Mod {
        Setting.Num minFall = num("Min fall distance", 2.5, 1, 10, 0.5);
        public NoFall() { super("NoFall", Category.PLAYER, "Sends on-ground packets while falling."); }

        @Override public void onTick() {
            var p = mc.player;
            if (p.fallDistance > minFall.get() && !p.isOnGround())
                mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(true, p.horizontalCollision));
        }
    }

    public static class Scaffold extends Mod {
        Setting.Bool autoSwitch = bool("Auto switch", true);
        public Scaffold() { super("Scaffold", Category.PLAYER, "Places blocks under your feet."); }

        @Override public void onTick() {
            var p = mc.player;
            if (!(p.getMainHandStack().getItem() instanceof BlockItem)) {
                if (!autoSwitch.get() || !U.selectSlot(s -> s.getItem() instanceof BlockItem)) return;
            }
            BlockPos under = BlockPos.ofFloored(p.getX(), p.getY() - 1, p.getZ());
            U.place(under);
        }
    }

    public static class AutoEat extends Mod {
        Setting.Num hunger = num("Eat when food <=", 14, 1, 19, 1);
        boolean eating; int ticks;
        public AutoEat() { super("AutoEat", Category.PLAYER, "Eats food when hungry."); }

        @Override public void onTick() {
            var p = mc.player;
            int food = p.getHungerManager().getFoodLevel();
            if (eating) {
                ticks++;
                if (food > hunger.get() || (ticks > 5 && !p.isUsingItem())) stop();
                return;
            }
            if (food > hunger.get() || mc.currentScreen != null) return;
            if (U.selectSlot(s -> s.contains(DataComponentTypes.FOOD))) {
                eating = true; ticks = 0;
                mc.options.useKey.setPressed(true);
            }
        }
        private void stop() { eating = false; mc.options.useKey.setPressed(false); }
        @Override public void onDisable() { if (eating) stop(); }
    }

    public static class AutoTool extends Mod {
        public AutoTool() { super("AutoTool", Category.PLAYER, "Selects the fastest tool for the block you mine."); }

        @Override public void onTick() {
            if (!mc.options.attackKey.isPressed() || !(mc.crosshairTarget instanceof BlockHitResult hit)) return;
            var state = mc.world.getBlockState(hit.getBlockPos());
            var inv = mc.player.getInventory();
            int best = inv.getSelectedSlot();
            float bs = inv.getStack(best).getMiningSpeedMultiplier(state);
            for (int i = 0; i < 9; i++) {
                float s = inv.getStack(i).getMiningSpeedMultiplier(state);
                if (s > bs) { bs = s; best = i; }
            }
            inv.setSelectedSlot(best);
        }
    }

    public static class ChestStealer extends Mod {
        Setting.Num delay = num("Delay (ticks)", 1, 0, 10, 1);
        Setting.Bool autoClose = bool("Auto close", true);
        int cd;
        public ChestStealer() { super("ChestStealer", Category.PLAYER, "Moves everything out of an open chest."); }

        @Override public void onTick() {
            if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler h)) return;
            if (cd > 0) { cd--; return; }
            int n = h.getRows() * 9;
            for (int i = 0; i < n; i++) {
                if (!h.getSlot(i).getStack().isEmpty()) {
                    mc.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                    cd = delay.getInt();
                    return;
                }
            }
            if (autoClose.get()) mc.player.closeHandledScreen();
        }
    }

    public static class AutoReplenish extends Mod {
        Setting.Num below = num("Refill below", 8, 1, 32, 1);
        int cd;
        public AutoReplenish() { super("AutoReplenish", Category.PLAYER, "Refills the held stack from your inventory."); }
        @Override public void onTick() {
            if (cd > 0) { cd--; return; }
            if (mc.currentScreen != null) return;
            var p = mc.player; var inv = p.getInventory();
            int sel = inv.getSelectedSlot();
            var held = inv.getStack(sel);
            if (held.isEmpty() || !held.isStackable() || held.getCount() >= below.get()) return;
            for (int i = 9; i < 36; i++) {
                if (ItemStack.areItemsAndComponentsEqual(inv.getStack(i), held)) {
                    mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, i, sel, SlotActionType.SWAP, p);
                    cd = 4;
                    return;
                }
            }
        }
    }
}
