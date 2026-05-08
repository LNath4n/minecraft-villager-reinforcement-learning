package com.lnathan.client;

/**
 * Fade-in animator for quest screens.
 * <p>
 * Manages a smooth opacity (alpha) transition when any mod screen is opened.
 * The {@code alpha} value goes from {@code 0.0} (invisible) to {@code 1.0}
 * (fully visible) over {@value #FADE_DURATION} seconds.
 * </p>
 *
 * <p>Example usage inside a screen class:</p>
 * <pre>{@code
 * private final ScreenAnimator animator = new ScreenAnimator();
 *
 * // In the render method, before drawing:
 * animator.tick(deltaTime);
 * graphics.fill(x, y, x + w, y + h, animator.getOverlayColor(0xFF000000));
 * }</pre>
 */
public class ScreenAnimator {

    /** Duration of the fade-in in seconds. */
    private static final float FADE_DURATION = 0.3f;

    /**
     * Current opacity level.
     * {@code 0.0} = fully transparent; {@code 1.0} = fully opaque.
     */
    private float alpha = 0f;

    /** Whether the animation is currently in the opening (fade-in) phase. */
    private boolean opening = true;

    /**
     * Advances the fade-in animation by one time step.
     * <p>
     * Increments {@code alpha} proportionally to the elapsed time.
     * Once {@code alpha} reaches {@code 1.0}, the animation stops.
     * </p>
     *
     * @param deltaTime Time elapsed since the last frame, in seconds.
     */
    public void tick(float deltaTime) {
        if (opening && alpha < 1f) {
            alpha = Math.min(1f, alpha + deltaTime / FADE_DURATION);
        }
    }

    /**
     * Returns the current opacity level of the animation.
     *
     * @return A value between {@code 0.0} (transparent) and {@code 1.0} (opaque).
     */
    public float getAlpha() {
        return alpha;
    }

    /**
     * Applies the animation alpha to the opacity channel of an ARGB color.
     * <p>
     * Scales the alpha channel of the base color by the current fade-in progress,
     * leaving the RGB channels unchanged.
     * </p>
     *
     * @param baseColor Color in {@code 0xAARRGGBB} format.
     * @return Color with the alpha channel modulated by the animation progress.
     */
    public int getOverlayColor(int baseColor) {
        int baseAlpha = (baseColor >> 24) & 0xFF;
        int fadedAlpha = (int)(baseAlpha * alpha);
        return (fadedAlpha << 24) | (baseColor & 0x00FFFFFF);
    }

    /**
     * Applies the animation alpha to a text color.
     * <p>
     * Unlike {@link #getOverlayColor(int)}, this always uses {@code 0xFF} as the
     * base alpha and scales it with the current progress, ensuring fully opaque
     * text at the end of the fade-in.
     * </p>
     *
     * @param baseColor RGB color of the text (alpha channel is ignored).
     * @return Color with animated alpha ready for text rendering.
     */
    public int getTextColor(int baseColor) {
        int fadedAlpha = (int)(0xFF * alpha);
        return (fadedAlpha << 24) | (baseColor & 0x00FFFFFF);
    }
}