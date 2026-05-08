package com.lnathan.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.network.chat.Component;

/**
 * HUD toast displayed when the player returns to a village.
 * <p>
 * Appears in the bottom-left corner of the screen and shows a "Returning to"
 * label alongside the village name highlighted in gold. The toast stays visible
 * for 3 seconds before hiding itself automatically.
 * </p>
 *
 * <p>This toast is triggered server-side via {@link VillageTitlePacket} and
 * queued through Minecraft's {@link ToastManager}.</p>
 */
public class VillageToast implements Toast {

    /** Name of the village to display in the toast. */
    private final String villageName;

    /** Whether the toast has finished its display duration and should be hidden. */
    private boolean done = false;

    /**
     * Creates a new village toast with the given village name.
     *
     * @param villageName The name of the village to display.
     */
    public VillageToast(String villageName) {
        this.villageName = villageName;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link Visibility#HIDE} once the toast has been visible for 3 seconds;
     *         {@link Visibility#SHOW} otherwise.
     */
    @Override
    public Visibility getWantedVisibility() {
        return done ? Visibility.HIDE : Visibility.SHOW;
    }

    /**
     * Tracks how long the toast has been fully visible and marks it as done
     * after 3000 milliseconds.
     *
     * @param manager          The toast manager handling this toast.
     * @param fullyVisibleForMs Milliseconds the toast has been fully visible.
     */
    @Override
    public void update(ToastManager manager, long fullyVisibleForMs) {
        if (fullyVisibleForMs >= 3000) done = true;
    }

    /**
     * Renders the toast background and text content.
     * <p>
     * Draws a dark semi-transparent background, a gold top border, a grey
     * "Returning to" label, and the village name in gold below it.
     * </p>
     *
     * @param graphics         The graphics extractor used for drawing.
     * @param font             The font renderer.
     * @param fullyVisibleForMs Milliseconds the toast has been fully visible.
     */
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, Font font, long fullyVisibleForMs) {
        // Dark background
        graphics.fill(0, 0, width(), height(), 0xCC000000);

        // Gold top border
        graphics.fill(0, 0, width(), 1, 0xFFD4A017);

        // "Returning to" label in grey
        graphics.text(font,
                Component.literal("Returning to"),
                10, 5, 0xFFAAAAAA, false);

        // Village name in gold
        graphics.text(font,
                Component.literal(villageName),
                10, 16, 0xFFD4A017, false);
    }

    /**
     * Positions the toast at the left edge of the screen.
     *
     * @param screenWidth    Width of the screen in GUI pixels.
     * @param visiblePortion The portion of the toast currently visible (0.0–1.0).
     * @return {@code 0}, placing the toast at the left edge.
     */
    @Override
    public float xPos(int screenWidth, float visiblePortion) {
        return 0;
    }

    /**
     * Positions the toast near the bottom of the screen, stacking upward
     * with other toasts if multiple are shown simultaneously.
     *
     * @param firstSlotIndex Index of the toast's slot in the queue (0 = bottommost).
     * @return Y coordinate in GUI pixels, measured from the top of the screen.
     */
    @Override
    public float yPos(int firstSlotIndex) {
        int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        return screenHeight - height() - 10 - (firstSlotIndex * height());
    }

    /**
     * Returns the width of the toast in GUI pixels.
     *
     * @return {@code 180} pixels.
     */
    @Override
    public int width() { return 180; }

    /**
     * Returns the height of the toast in GUI pixels.
     *
     * @return {@code 30} pixels.
     */
    @Override
    public int height() { return 30; }
}