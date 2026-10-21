package nova.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import nova.modules.ExtraMore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingSwingMixin {
    @Inject(method = "getHandSwingDuration", at = @At("RETURN"), cancellable = true)
    private void nova$swing(CallbackInfoReturnable<Integer> cir) {
        if (ExtraMore.SlowSwing.active() && (Object) this == MinecraftClient.getInstance().player)
            cir.setReturnValue(ExtraMore.SlowSwing.duration(cir.getReturnValue()));
    }
}
