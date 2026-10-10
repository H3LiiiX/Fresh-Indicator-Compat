package com.freshindicatorcompat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.Coerce;
import java.lang.reflect.Method;

@Mixin(dev.nomadswe.healthindicatornext.client.render.ArmorIndicatorRenderer.class)
public class ArmorIndicatorRendererMixin {

    @Inject(method = "renderIconsInStack(Lnet/minecraft/world/entity/LivingEntity;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/OrderedSubmitNodeCollector;DFLdev/nomadswe/healthindicatornext/client/render/IconDisplayCap$Layout;)V", 
            at = @At("HEAD"), cancellable = true)
    private static void onRenderIconsInStack(LivingEntity entity, PoseStack poseStack, OrderedSubmitNodeCollector queue, double squaredDistance, float top, @Coerce Object cap, CallbackInfo ci) {
        float spacing = dev.nomadswe.healthindicatornext.client.state.IndicatorSessionState.health.hearts.spacing;
        float layerStep = dev.nomadswe.healthindicatornext.client.render.WorldBillboardText.depthStep(squaredDistance);
        
        int displaySlots = 0;
        int slotOffset = 0;
        try {
            Method mDisplay = cap.getClass().getMethod("displaySlots");
            displaySlots = (int) mDisplay.invoke(cap);
            
            Method mOffset = cap.getClass().getMethod("slotOffset");
            slotOffset = (int) mOffset.invoke(cap);
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        displaySlots = Math.max(0, displaySlots);
        
        com.fresharmorbar.client.CompatArmorRenderer.renderCompatIcons(entity, poseStack, queue, squaredDistance, top, displaySlots, slotOffset, spacing, layerStep);
        ci.cancel();
    }
}
