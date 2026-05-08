package com.lnathan.client;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

/**
 * Camera controller used when a quest screen is opened.
 * <p>
 * Manages a smooth zoom-out and camera drop animation when the player
 * opens a quest screen in third-person mode. Animation progress ranges
 * from {@code 0.0} (normal state) to {@code 1.0} (full zoom-out).
 * </p>
 *
 * <p>Typical usage:</p>
 * <pre>{@code
 * // When opening the screen:
 * QuestCameraController.open();
 *
 * // Every client tick:
 * QuestCameraController.tick(deltaTime);
 *
 * // When closing the screen:
 * QuestCameraController.close();
 * }</pre>
 */
public class QuestCameraController {

    /** Total duration of the animation in seconds. */
    private static final float ANIMATION_DURATION = 0.5f;

    /** Zoom-out distance (backwards) in blocks. */
    private static final float ZOOM_OUT = 2.5f;

    /** Downward camera drop distance in blocks. */
    private static final float DROP_DOWN = 1.5f;

    /** Whether the controller is currently active (quest screen is open). */
    private static boolean active = false;

    /**
     * Current animation progress.
     * {@code 0.0} = normal position; {@code 1.0} = full zoom-out.
     */
    private static float progress = 0f;

    /** Original zoom value before the controller was activated (reserved for future use). */
    private static float originalZoom = 0f;

    /**
     * Activates the camera controller to begin the zoom-out animation.
     * <p>
     * Only takes effect if the player is currently in third-person view
     * (checked via {@link #isThirdPerson()}).
     * </p>
     */
    public static void open() {
        if (!isThirdPerson()) return;
        active = true;
    }

    /**
     * Deactivates the camera controller and starts the return animation.
     */
    public static void close() {
        active = false;
    }

    /**
     * Returns whether the controller is active and the player is still in third-person view.
     *
     * @return {@code true} if the camera animation is in progress.
     */
    public static boolean isActive() {
        return active && isThirdPerson();
    }

    /**
     * Returns the current animation progress.
     *
     * @return A value between {@code 0.0} and {@code 1.0}.
     */
    public static float getProgress() {
        return progress;
    }

    /**
     * Advances the camera animation by one time step.
     * <p>
     * Must be called every client tick. Increments or decrements {@code progress}
     * depending on whether the controller is active or not.
     * </p>
     *
     * @param deltaTime Time elapsed since the last tick, in seconds.
     */
    public static void tick(float deltaTime) {
        if (active && progress < 1f) {
            progress = Math.min(1f, progress + deltaTime / ANIMATION_DURATION);
        } else if (!active && progress > 0f) {
            progress = Math.max(0f, progress - deltaTime / ANIMATION_DURATION);
        }
    }

    /**
     * Calculates the zoom-out offset to apply based on the current progress.
     * <p>
     * Uses an {@link #easeInOut(float)} function to smooth the animation.
     * </p>
     *
     * @return Offset in blocks backwards (0.0 – {@value ZOOM_OUT}).
     */
    public static float getZoomOffset() {
        return easeInOut(progress) * ZOOM_OUT;
    }

    /**
     * Calculates the downward camera drop offset based on the current progress.
     * <p>
     * Uses an {@link #easeInOut(float)} function to smooth the animation.
     * </p>
     *
     * @return Offset in blocks downward (0.0 – {@value DROP_DOWN}).
     */
    public static float getDropOffset() {
        return easeInOut(progress) * DROP_DOWN;
    }

    /**
     * Checks whether the player is currently in third-person camera mode.
     *
     * @return {@code true} if the camera is not in first-person mode.
     */
    public static boolean isThirdPerson() {
        return Minecraft.getInstance().options.getCameraType() != CameraType.FIRST_PERSON;
    }

    /**
     * Quadratic ease-in/ease-out smoothing function.
     * <p>
     * Produces a smooth acceleration at the start and deceleration at the end,
     * avoiding abrupt camera changes.
     * </p>
     *
     * @param t Input value in the range [0, 1].
     * @return Smoothed output value in the range [0, 1].
     */
    private static float easeInOut(float t) {
        return t < 0.5f ? 2 * t * t : -1 + (4 - 2 * t) * t;
    }
}