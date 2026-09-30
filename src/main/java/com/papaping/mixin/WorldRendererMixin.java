package com.papaping.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.papaping.render.BeamRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.memory.ObjectAllocator;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws PapaPing's in-world vertical beams at the very end of the world render pass, using the
 * live camera + model-view matrix (same hook the reference mod uses on 1.21.11).
 */
@Mixin(WorldRenderer.class)
public class WorldRendererMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void papaping$renderBeams(ObjectAllocator allocator, RenderTickCounter tickCounter,
                                      boolean renderBlockOutline, Camera camera,
                                      Matrix4f modelViewMatrix, Matrix4f matrix2,
                                      Matrix4f projectionMatrix, GpuBufferSlice fog,
                                      Vector4f fogColor, boolean bl, CallbackInfo ci) {
        BeamRenderer.render(camera, modelViewMatrix);
    }
}
