package com.lnathan.quest;

import com.lnathan.client.QuestCameraController;
import com.lnathan.network.QuestResponsePacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.advancements.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.Optional;

/**
 * Quest interaction screen displayed when the player speaks to a villager
 * that has an available, in-progress, or ready-to-turn-in quest.
 *
 * <p>Styled with a vanilla Minecraft aesthetic: dark panel background,
 * pixel-style borders (light top/left, dark bottom/right), white/gray text,
 * and gold accents only for the villager name. All user-facing strings are
 * resolved through the active language file via {@link Component#translatable}.</p>
 *
 * <p>The screen shows the quest title, description, and current progress,
 * and presents action buttons that vary based on the quest's state:</p>
 * <ul>
 *   <li>{@code AVAILABLE} — <em>Accept</em> and <em>Deny</em> buttons.</li>
 *   <li>{@code IN_PROGRESS} — <em>Close</em> button only.</li>
 *   <li>{@code READY_TO_TURN_IN} — <em>Deliver</em> button to complete the quest.</li>
 * </ul>
 *
 * <p>Opening the screen activates {@link QuestCameraController} to trigger an animated
 * third-person zoom-out. Closing always sends a {@link QuestResponsePacket} to release
 * the villager's interaction lock.</p>
 */
public class QuestScreen extends Screen {

    //  Layout constants 

    /** Height of the bottom info bar in pixels. */
    private static final int BAR_HEIGHT = 110;

    /** Width of the action buttons in pixels. */
    private static final int BUTTON_WIDTH = 160;

    /** Height of the action buttons in pixels. */
    private static final int BUTTON_HEIGHT = 20;

    /**
     * Vanilla-style dark panel background color (matches the default Minecraft
     * inventory/chat background at ~80 % opacity).
     */
    private static final int COLOR_PANEL_BG     = 0xCC000000;

    /** 1-px top separator line — gold accent matching vanilla quest/advancement UI. */
    private static final int COLOR_BORDER_TOP   = 0xFFFFAA00;

    /** Villager name color — warm gold, matches the vanilla advancement toast. */
    private static final int COLOR_NAME         = 0xFFFFAA00;

    /** Gold underline beneath the villager name. */
    private static final int COLOR_NAME_LINE    = 0xFFFFAA00;

    /** Quest title color — plain white, high contrast on dark panel. */
    private static final int COLOR_TITLE        = 0xFFFFFFFF;

    /** Description body text — slightly muted white. */
    private static final int COLOR_DESC         = 0xFFCCCCCC;

    /** Progress color while the quest is {@code IN_PROGRESS}. */
    private static final int COLOR_PROGRESS     = 0xFFAAAAAA;

    /** Progress color when the quest is {@code READY_TO_TURN_IN} — bright green. */
    private static final int COLOR_PROGRESS_OK  = 0xFF55FF55;

    //  Quest data 

    /** Translation key for the quest title (resolved at render time). */
    private final String questTitle;

    /** Translation key for the quest description (resolved at render time). */
    private final String questDescription;

    /** UUID of the quest-giving villager as a String. */
    private final String villagerUuid;

    /** Current quest state ({@code AVAILABLE}, {@code IN_PROGRESS}, {@code READY_TO_TURN_IN}). */
    private final String questState;

    /** Ready-to-display progress text, e.g. {@code "3/5 Poppy"}. */
    private final String progressText;

    /** {@code true} if the player must physically return to turn in the quest. */
    private final boolean requiresReturn;

    /** Display name of the villager (shown in the bottom bar). */
    private final String villagerName;

    // 

    /**
     * Creates the quest screen with all data required for rendering and sends
     * a camera zoom-out via {@link QuestCameraController#open()}.
     *
     * @param questTitle       Translation key for the quest title.
     * @param questDescription Translation key for the quest description.
     * @param villagerUuid     The villager's UUID as a String.
     * @param questState       Current quest state as a String.
     * @param progressText     Progress text to display ({@code "current/max item"}).
     * @param requiresReturn   {@code true} if the quest requires an in-person turn-in.
     * @param villagerName     The villager's display name shown in the UI.
     */
    public QuestScreen(String questTitle, String questDescription, String villagerUuid,
                       String questState, String progressText, boolean requiresReturn,
                       String villagerName) {
        super(Component.translatable("quest.mod.journal.title"));
        this.questTitle      = questTitle;
        this.questDescription = questDescription;
        this.villagerUuid    = villagerUuid;
        this.questState      = questState;
        this.progressText    = progressText;
        this.requiresReturn  = requiresReturn;
        this.villagerName    = villagerName;
        QuestCameraController.open();
    }

    //  Widget init 

    /**
     * Adds the action button(s) appropriate for the current quest state.
     * <ul>
     *   <li>{@code IN_PROGRESS}: single <em>Close</em> button.</li>
     *   <li>{@code READY_TO_TURN_IN}: <em>Deliver</em> button — sends packet + toast.</li>
     *   <li>Otherwise ({@code AVAILABLE}): <em>Accept</em> + <em>Deny</em> buttons.</li>
     * </ul>
     */
    @Override
    protected void init() {
        int screenW = this.width;
        int screenH = this.height;
        int barY    = screenH - BAR_HEIGHT;
        int btnX    = screenW - BUTTON_WIDTH - 20;

        switch (questState) {
            case "IN_PROGRESS" -> this.addRenderableWidget(Button.builder(
                            Component.translatable("quest.mod.close"),
                            btn -> this.onClose())
                    .pos(btnX, barY + 42)
                    .size(BUTTON_WIDTH, BUTTON_HEIGHT)
                    .build());

            case "READY_TO_TURN_IN" -> this.addRenderableWidget(Button.builder(
                            Component.translatable("quest.mod.deliver"),
                            btn -> {
                                ClientPlayNetworking.send(
                                        new QuestResponsePacket(true, villagerUuid, "READY_TO_TURN_IN"));
                                showClientToast();
                                this.onClose();
                            })
                    .pos(btnX, barY + 42)
                    .size(BUTTON_WIDTH, BUTTON_HEIGHT)
                    .build());

            default -> {
                // AVAILABLE — accept or decline
                this.addRenderableWidget(Button.builder(
                                Component.translatable("quest.mod.accept"),
                                btn -> {
                                    ClientPlayNetworking.send(
                                            new QuestResponsePacket(true, villagerUuid, "AVAILABLE"));
                                    showClientToast();
                                    this.onClose();
                                })
                        .pos(btnX, barY + 30)
                        .size(BUTTON_WIDTH, BUTTON_HEIGHT)
                        .build());

                this.addRenderableWidget(Button.builder(
                                Component.translatable("quest.mod.deny"),
                                btn -> {
                                    ClientPlayNetworking.send(
                                            new QuestResponsePacket(false, villagerUuid, questState));
                                    this.onClose();
                                })
                        .pos(btnX, barY + 58)
                        .size(BUTTON_WIDTH, BUTTON_HEIGHT)
                        .build());
            }
        }
    }

    //  Rendering 

    /**
     * Renders the screen each frame using a vanilla Minecraft panel aesthetic:
     * <ul>
     *   <li>Full-screen semi-transparent overlay.</li>
     *   <li>Bottom bar with dark background and a 1-px gold top border.</li>
     *   <li>Villager name in gold with a gold underline.</li>
     *   <li>Quest title in white (right-aligned with the buttons).</li>
     *   <li>Word-wrapped description in muted white.</li>
     *   <li>Progress text in gray (active) or bright green (ready to turn in).</li>
     * </ul>
     *
     * @param graphics The GUI rendering context.
     * @param mouseX   Current X position of the mouse cursor.
     * @param mouseY   Current Y position of the mouse cursor.
     * @param delta    Partial tick fraction (unused — no animation in vanilla style).
     */
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int screenW = this.width;
        int screenH = this.height;
        int barY    = screenH - BAR_HEIGHT;
        int btnX    = screenW - BUTTON_WIDTH - 20;

        //  Background 

        // Semi-transparent full-screen overlay (lets the world show through)
        graphics.fill(0, 0, screenW, screenH, 0x55000000);

        // Bottom panel — vanilla dark background
        graphics.fill(0, barY, screenW, screenH, COLOR_PANEL_BG);

        // 1-px gold top border (vanilla advancement/chat style)
        graphics.fill(0, barY, screenW, barY + 1, COLOR_BORDER_TOP);

        //  Villager name + underline 

        graphics.text(this.font, villagerName, 20, barY + 10, COLOR_NAME, false);
        // Underline spans the name width + 4 px padding
        graphics.fill(20, barY + 20,
                20 + this.font.width(villagerName) + 4, barY + 21,
                COLOR_NAME_LINE);

        //  Quest title (right column, aligned with buttons) 

        String localTitle = Component.translatable(questTitle).getString();
        graphics.text(this.font, localTitle, btnX, barY + 10, COLOR_TITLE, false);

        //  Description (left column, word-wrapped) 

        int descX     = 20;
        int descWidth = btnX - 40;
        graphics.textWithWordWrap(this.font,
                Component.translatable(questDescription),
                descX, barY + 32,
                descWidth, COLOR_DESC, false);

        //  Progress (only while quest is active or ready) 

        if (questState.equals("IN_PROGRESS") || questState.equals("READY_TO_TURN_IN")) {
            int progressColor = questState.equals("READY_TO_TURN_IN")
                    ? COLOR_PROGRESS_OK
                    : COLOR_PROGRESS;
            graphics.text(this.font,
                    Component.translatable("quest.mod.progress").getString() + progressText,
                    descX, barY + 80,
                    progressColor, false);
        }

        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    //  Toast 

    /**
     * Shows a client-side advancement toast (Emerald icon) to confirm that the
     * quest was accepted or turned in. No server packet is sent here.
     */
    private void showClientToast() {
        Identifier id = Identifier.fromNamespaceAndPath("lnathan", "mission_toast");
        DisplayInfo display = new DisplayInfo(
                ItemStackTemplate.fromNonEmptyStack(new ItemStack(Items.EMERALD)),
                Component.translatable("quest.mod.toast.title"),
                Component.translatable(questTitle),
                Optional.empty(),
                AdvancementType.GOAL,
                true, false, false
        );
        Advancement advancement = new Advancement(
                Optional.empty(), Optional.of(display),
                AdvancementRewards.EMPTY, Map.of(),
                AdvancementRequirements.EMPTY, false
        );
        AdvancementHolder holder = new AdvancementHolder(id, advancement);
        Minecraft.getInstance().getToastManager().addToast(new AdvancementToast(holder));
    }

    //  Screen lifecycle 

    /** This screen does not pause the game. */
    @Override
    public boolean isPauseScreen() { return false; }

    /**
     * Closes the screen, deactivates the camera controller, and notifies the
     * server to release the villager's interaction lock.
     */
    @Override
    public void onClose() {
        QuestCameraController.close();
        ClientPlayNetworking.send(new QuestResponsePacket(false, villagerUuid, questState));
        super.onClose();
    }
}