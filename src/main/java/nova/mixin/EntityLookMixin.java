package nova.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import nova.core.CamState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While the camera is detached the mouse turns the camera, not the player. */
@Mixin(Entity.class)
public abstract class EntityLookMixin {
    @Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
    private void nova$look(double dx, double dy, CallbackInfo ci) {
        if (!CamState.active() || (Object) this != MinecraftClient.getInstance().player) return;
        CamState.look(dx, dy);
        ci.cancel();
    }
}
