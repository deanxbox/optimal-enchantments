package dev.optimalenchantments.mixin;

import dev.optimalenchantments.OptimalEnchantments;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class ContainerScreenMixin {
    // Screen changes normally clear the local anvil inputs; keep them while its planner is open.
    @Inject(method = "removed", at = @At("HEAD"), cancellable = true)
    private void keepAnvilInputs(CallbackInfo ci) {
        if (OptimalEnchantments.openingPlanner) ci.cancel();
    }
}
