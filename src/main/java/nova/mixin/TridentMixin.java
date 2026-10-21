package nova.mixin;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.TridentItem;
import nova.modules.ExtraFun;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * GodTrident: the riptide water / rain check in use() and onStoppedUsing(), and the 10 tick minimum charge.
 * All three have require = 0, so if a name does not match this Minecraft version only that part does nothing.
 */
@Mixin(TridentItem.class)
public abstract class TridentMixin {
    @Redirect(method = "use", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;isTouchingWaterOrRain()Z"), require = 0)
    private boolean nova$useWater(PlayerEntity p) { return ExtraFun.GodTrident.anywhere() || p.isTouchingWaterOrRain(); }

    @Redirect(method = "onStoppedUsing", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;isTouchingWaterOrRain()Z"), require = 0)
    private boolean nova$stopWater(PlayerEntity p) { return ExtraFun.GodTrident.anywhere() || p.isTouchingWaterOrRain(); }

    @ModifyConstant(method = "onStoppedUsing", constant = @Constant(intValue = 10), require = 0)
    private int nova$charge(int vanilla) { return ExtraFun.GodTrident.charge(vanilla); }
}
