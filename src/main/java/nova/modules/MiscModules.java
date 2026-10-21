package nova.modules;

import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.item.Items;
import nova.core.*;

public final class MiscModules {
    private MiscModules() {}

    public static class AutoRespawn extends Mod {
        public AutoRespawn() { super("AutoRespawn", Category.MISC, "Respawns instantly."); }
        @Override public void onTick() { if (mc.player.isDead()) mc.player.requestRespawn(); }
    }

    public static class AntiAFK extends Mod {
        int t;
        public AntiAFK() { super("AntiAFK", Category.MISC, "Swings and jumps now and then."); }

        @Override public void onTick() {
            t++;
            if (t % 100 == 0) mc.player.swingHand(Hand.MAIN_HAND);
            if (t % 300 == 0 && mc.player.isOnGround()) mc.player.jump();
        }
    }

}
