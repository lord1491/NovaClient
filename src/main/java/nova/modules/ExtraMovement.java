package nova.modules;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.vehicle.VehicleEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import nova.core.*;

/** Movement modules ported from the Anubis module list (tick based, no mixins). */
public final class ExtraMovement {
    private ExtraMovement() {}

    public static class ElytraFly extends Mod {
        Setting.Mode mode = mode("Mode", "Control", "Control", "Boost");
        Setting.Num speed = num("Speed", 1.6, 0.5, 4, 0.1), vertical = num("Vertical", 0.8, 0.1, 2, 0.1);
        Setting.Bool autoStart = bool("Auto start", true);
        int cd;

        public ElytraFly() { super("ElytraFly", Category.MOVEMENT, "Control: fly where you look, hover when idle. Boost: hold forward to speed up."); }

        @Override public void onTick() {
            var p = mc.player;
            if (!p.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) return;
            if (cd > 0) cd--;
            if (!p.isGliding()) {
                if (autoStart.get() && cd == 0 && !p.isOnGround() && !p.isTouchingWater() && p.getVelocity().y < -0.1) {
                    mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(p, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
                    cd = 10;
                }
                return;
            }
            Vec3d look = p.getRotationVec(1.0F);
            if (mode.is("Boost")) {
                if (!U.fwd()) return;
                Vec3d v = p.getVelocity().add(look.multiply(0.05 * speed.get()));
                double cap = 3.0;
                if (v.length() > cap) v = v.normalize().multiply(cap);
                p.setVelocity(v);
                return;
            }
            double vy = U.jump() ? vertical.get() : U.sneak() ? -vertical.get() : 0;
            if (U.fwd()) {
                Vec3d v = look.multiply(speed.get());
                p.setVelocity(v.x, v.y + vy, v.z);
            } else {
                p.setVelocity(0, vy, 0);
            }
        }
    }

    /** Boats and minecarts fly where you steer them. */
    public static class BoatFly extends Mod {
        Setting.Num speed = num("Speed", 1.0, 0.1, 5, 0.1), vertical = num("Vertical", 0.5, 0.1, 3, 0.1);
        Setting.Bool noGravity = bool("Hover", true);
        Entity vehicle;

        public BoatFly() { super("BoatFly", Category.MOVEMENT, "Jump = up, sprint key = down, hovers when idle. Works in any boat / minecart."); }

        private void release() {
            if (vehicle != null) { vehicle.setNoGravity(false); vehicle = null; }
        }

        @Override public void onDisable() { release(); }

        @Override public void onTick() {
            var p = mc.player;
            Entity v = p.getVehicle();
            if (!(v instanceof VehicleEntity)) { release(); return; }
            vehicle = v;
            v.setNoGravity(noGravity.get());
            v.setYaw(p.getYaw());
            Vec3d d = U.moveDir(speed.get());
            double vy = U.jump() ? vertical.get() : mc.options.sprintKey.isPressed() ? -vertical.get()
                    : noGravity.get() ? 0 : v.getVelocity().y;
            v.setVelocity(d.x, vy, d.z);
        }
    }

    /** Faster horses, pigs, striders and other ridden mobs. */
    public static class EntitySpeed extends Mod {
        Setting.Num speed = num("Speed", 0.6, 0.1, 3, 0.05);

        public EntitySpeed() { super("EntitySpeed", Category.MOVEMENT, "Sets the horizontal speed of the mob you are riding."); }

        @Override public void onTick() {
            Entity v = mc.player.getVehicle();
            if (!(v instanceof LivingEntity) || !U.moving()) return;
            Vec3d d = U.moveDir(speed.get());
            v.setVelocity(d.x, v.getVelocity().y, d.z);
        }
    }

    /** Right click with an empty hand to teleport to the block you look at (sent in steps of 8 blocks). */
    public static class ClickTP extends Mod {
        Setting.Num dist = num("Max distance", 30, 5, 100, 1);
        Setting.Bool emptyHand = bool("Only with empty hand", true);
        boolean down;

        public ClickTP() { super("ClickTP", Category.MOVEMENT, "Right click a block to teleport next to it."); }

        @Override public void onTick() {
            var p = mc.player;
            boolean d = mc.options.useKey.isPressed();
            boolean fire = d && !down && mc.currentScreen == null && (!emptyHand.get() || p.getMainHandStack().isEmpty());
            down = d;
            if (!fire) return;
            HitResult hr = p.raycast(dist.get(), 1.0F, false);
            if (!(hr instanceof BlockHitResult bhr) || hr.getType() != HitResult.Type.BLOCK) return;
            Vec3d dest = Vec3d.ofBottomCenter(bhr.getBlockPos().offset(bhr.getSide()));
            Vec3d from = U.pos(p);
            int steps = Math.max(1, (int) Math.ceil(from.distanceTo(dest) / 8.0));
            for (int i = 1; i <= steps; i++) {
                Vec3d s = from.lerp(dest, i / (double) steps);
                mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(s.x, s.y, s.z, false, false));
            }
            p.setPosition(dest.x, dest.y, dest.z);
            p.setVelocity(Vec3d.ZERO);
        }
    }

    /** Multiplies the push of a riptide trident. */
    public static class TridentBoost extends Mod {
        Setting.Num boost = num("Boost", 1.5, 1.0, 4, 0.1);
        boolean was;

        public TridentBoost() { super("TridentBoost", Category.MOVEMENT, "Stronger riptide launch."); }

        @Override public void onTick() {
            var p = mc.player;
            boolean r = p.isUsingRiptide();
            if (r && !was) p.setVelocity(p.getVelocity().multiply(boost.get()));
            was = r;
        }
    }

    /** Freezes you in place (movement and knockback) while it is on. */
    public static class AirStuck extends Mod {
        Vec3d at;

        public AirStuck() { super("AirStuck", Category.MOVEMENT, "Holds your position and cancels velocity."); }

        @Override public void onEnable() { at = mc.player == null ? null : U.pos(mc.player); }

        @Override public void onTick() {
            if (at == null) at = U.pos(mc.player);
            mc.player.setVelocity(Vec3d.ZERO);
            mc.player.setPosition(at.x, at.y, at.z);
        }
    }
}
