package com.lnathan.mixin.client;

import com.lnathan.client.QuestCameraController;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client-side Mixin that applies quest camera effects to {@link Camera} every frame.
 *
 * <p>After each {@code Camera#update} call, this Mixin checks whether the
 * {@link QuestCameraController} has an active animation in progress. If so, it
 * reads the current zoom and drop offsets from the controller and displaces the
 * camera accordingly via {@link CameraAccessor#invokeMove}.
 *
 * <p>When {@link QuestCameraController#getProgress()} returns {@code 0}, the
 * hook is a no-op and vanilla camera behaviour is unaffected.
 */
@Mixin(Camera.class)
public class CameraMixin {

    /**
     * Applies quest camera offsets at the end of each {@code Camera#update} call.
     *
     * <p>The zoom offset moves the camera backward (negative forward axis) and the
     * drop offset shifts it downward, creating a pull-back cinematic effect during
     * quest presentation. Both offsets are driven by {@link QuestCameraController}.
     *
     * @param deltaTracker Minecraft's delta time tracker for this frame (unused here)
     * @param ci           Mixin callback (unused)
     */
    @Inject(method = "update", at = @At("TAIL"))
    private void onUpdate(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (QuestCameraController.getProgress() == 0f) return;

        Camera self = (Camera)(Object)this;
        float zoom = QuestCameraController.getZoomOffset();
        float drop = QuestCameraController.getDropOffset();

        ((CameraAccessor) self).invokeMove(-zoom, drop, 0);
    }
}