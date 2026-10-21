package nova.modules;

import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import nova.core.*;

public final class MovementModules {
    private MovementModules() {}

    public static class Flight extends Mod {
        Setting.Num speed = num("Speed", 1.0, 0.1, 5, 0.1);
        Setting.Num vertical = num("Vertical", 0.5, 0.1, 3, 0.1);
        public Flight() { super("Flight", Category.MOVEMENT, "Velocity based flight."); }

        @Override public void onTick() {
            var p = mc.player;
            Vec3d d = U.moveDir(speed.get());
            double vy = U.jump() ? vertical.get() : U.sneak() ? -vertical.get() : 0;
            p.setVelocity(d.x, vy, d.z);
        }
    }

    public static class Speed extends Mod {
        Setting.Num speed = num("Speed", 0.4, 0.1, 3, 0.05);
        Setting.Bool groundOnly = bool("Ground only", false);
        public Speed() { super("Speed", Category.MOVEMENT, "Sets horizontal velocity."); }

        @Override public void onTick() {
            var p = mc.player;
            if (!U.moving()) return;
            if (groundOnly.get() && !p.isOnGround()) return;
            Vec3d d = U.moveDir(speed.get());
            p.setVelocity(d.x, p.getVelocity().y, d.z);
        }
    }

    public static class Sprint extends Mod {
        public Sprint() { super("Sprint", Category.MOVEMENT, "Always sprint while moving forward."); }

        @Override public void onTick() {
            var p = mc.player;
            if (U.fwd() && !p.horizontalCollision && !p.isSneaking() && p.getHungerManager().getFoodLevel() > 6)
                p.setSprinting(true);
        }
    }

    public static class AutoJump extends Mod {
        public AutoJump() { super("AutoJump", Category.MOVEMENT, "Jumps automatically while moving."); }
        @Override public void onTick() { if (mc.player.isOnGround() && U.moving()) mc.player.jump(); }
    }

}
