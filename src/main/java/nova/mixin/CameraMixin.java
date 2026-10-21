package nova.mixin;

import net.minecraft.client.render.Camera;
import nova.core.CamState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Moves / turns the camera for Freecam and Freelook. The handler takes no target arguments on purpose. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow protected abstract void setPos(double x, double y, double z);
    @Shadow protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "update", at = @At("TAIL"))
    private void nova$update(CallbackInfo ci) {
        if (!CamState.active()) return;
        CamState.step();
        if (CamState.freecam) setPos(CamState.fx, CamState.fy, CamState.fz);
        setRotation(CamState.yaw, CamState.pitch);
    }
}
