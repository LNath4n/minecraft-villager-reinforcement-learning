package com.lnathan.mixin.client;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Accessor Mixin for {@link Camera} that exposes the otherwise inaccessible
 * {@code move} method so client-side code can adjust the camera position
 * programmatically (e.g. for cinematic zoom effects during quests).
 */
@Mixin(Camera.class)
public interface CameraAccessor {

    /**
     * Invokes the private {@code Camera#move} method, which shifts the camera
     * along its local axes by the given offsets.
     *
     * @param distanceOffset   forward/backward offset along the camera's look direction
     * @param verticalOffset   up/down offset along the camera's up axis
     * @param horizontalOffset left/right offset along the camera's right axis
     */
    @Invoker("move")
    void invokeMove(float distanceOffset, float verticalOffset, float horizontalOffset);
}