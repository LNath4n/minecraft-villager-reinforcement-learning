package com.lnathan.quest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The player's quest journal screen.
 *
 * <p>Styled with a vanilla Minecraft aesthetic: dark inventory-style background,
 * pixel-border boxes (light top/left, dark bottom/right), and no fade animations.
 * All user-facing strings — tab labels, status text, button labels, empty messages —
 * are resolved through the active language file ({@code en_us.json} / {@code es_mx.json})
 * via {@link Component#translatable}. Quest titles are also resolved by translation key
 * so they correctly reflect the player's selected language.</p>
 *
 * <p>Three tabs:</p>
 * <ul>
 *   <li><b>Active</b> ({@code quest.mod.journal.active}) — {@code IN_PROGRESS} quests.</li>
 *   <li><b>Ready</b> ({@code quest.mod.journal.ready}) — {@code READY_TO_TURN_IN} quests.</li>
 *   <li><b>Completed</b> ({@code quest.mod.journal.completed}) — {@code TURNED_IN} quests.</li>
 * </ul>
 *
 * <p>Up to {@value #MISSIONS_PER_PAGE} quests per page, navigable with left/right arrows
 * or the number keys 1, 2, 3.</p>
 *
 * <p>Data arrives from the server as a list of Strings in the format
 * {@code "titleKey|progress|state|villagerName"} via
 * {@link com.lnathan.network.JournalDataPacket}. The first field ({@code titleKey})
 * is a translation key (e.g. {@code quest.mod.madera_invierno.title}) that is resolved
 * at render time, so the journal always displays in the player's active language.</p>
 */
public class JournalScreen extends Screen {

    //  Layout 

    /** Width of the journal panel in pixels. */
    private static final int GUI_WIDTH  = 240;

    /** Height of the journal panel in pixels. */
    private static final int GUI_HEIGHT = 240;

    /** Maximum number of quests displayed per page. */
    private static final int MISSIONS_PER_PAGE = 3;

    //  Vanilla color palette 

    /** Dark panel background — vanilla inventory/tooltip dark. */
    private static final int COLOR_BG           = 0xFF1D1D1D;

    /** Slightly lighter shade for the tab header area. */
    private static final int COLOR_BG_HEADER    = 0xFF2A2A2A;

    /** Active tab highlight — vanilla selection blue. */
    private static final int COLOR_TAB_ACTIVE   = 0xFF3A3A7A;

    /** Light edge for vanilla raised pixel borders (top/left). */
    private static final int COLOR_BORDER_LIGHT = 0xFFFFFFFF;

    /** Dark edge for vanilla raised pixel borders (bottom/right). */
    private static final int COLOR_BORDER_DARK  = 0xFF555555;

    /** Separator line inside the panel. */
    private static final int COLOR_SEPARATOR    = 0xFF555555;

    /** Villager name color — warm gold. */
    private static final int COLOR_NAME         = 0xFFFFAA00;

    /** Quest title color — white. */
    private static final int COLOR_TITLE        = 0xFFFFFFFF;

    /** "In progress" progress text — muted gray. */
    private static final int COLOR_PROGRESS_ACTIVE    = 0xFFAAAAAA;

    /** "Ready to turn in" progress text — bright green. */
    private static final int COLOR_PROGRESS_READY     = 0xFF55FF55;

    /** "Completed" progress text — dark gray. */
    private static final int COLOR_PROGRESS_COMPLETED = 0xFF888888;

    /** Empty-state text — muted gray. */
    private static final int COLOR_EMPTY        = 0xFF888888;

    /** Pagination text — muted gray. */
    private static final int COLOR_PAGE         = 0xFFAAAAAA;

    //  Tab data 

    /**
     * Translation keys for the three tab labels.
     * Resolved at render time — automatically reflects the player's language.
     */
    private static final String[] TAB_KEYS = {
            "quest.mod.journal.active",
            "quest.mod.journal.ready",
            "quest.mod.journal.completed"
    };

    /**
     * {@code QuestState} string values associated with each tab.
     * Must match the values sent by the server in the pipe-delimited entry format.
     */
    private static final String[] TAB_STATES = {
            "IN_PROGRESS", "READY_TO_TURN_IN", "TURNED_IN"
    };

    /**
     * Progress text color for each tab, indexed to {@link #TAB_STATES}.
     * <ul>
     *   <li>0 — Active: muted gray.</li>
     *   <li>1 — Ready: bright green.</li>
     *   <li>2 — Completed: dark gray.</li>
     * </ul>
     */
    private static final int[] TAB_COLORS = {
            COLOR_PROGRESS_ACTIVE,
            COLOR_PROGRESS_READY,
            COLOR_PROGRESS_COMPLETED
    };

    //  State 

    /** Full list of journal entries received from the server. */
    private final List<String> allEntries;

    /** Index of the currently active tab (0 = Active, 1 = Ready, 2 = Completed). */
    private int currentTab  = 0;

    /** Current page within the active tab (zero-based). */
    private int currentPage = 0;

    // 

    /**
     * Creates a new journal screen populated with server-provided entries.
     *
     * @param entries List of quest entries in the format
     *                {@code "titleKey|progress|state|villagerName"}.
     *                {@code titleKey} must be a valid translation key present in
     *                {@code en_us.json} / {@code es_mx.json}.
     */
    public JournalScreen(List<String> entries) {
        super(Component.translatable("quest.mod.journal.title"));
        this.allEntries = entries;
    }

    //  Helpers 

    /**
     * Returns the entries belonging to a specific tab, filtered by their state field.
     *
     * @param tab Tab index (0–2).
     * @return Entries whose {@code state} field matches {@link #TAB_STATES}[tab].
     */
    private List<String> getEntriesForTab(int tab) {
        return allEntries.stream()
                .filter(e -> e.split("\\|")[2].equals(TAB_STATES[tab]))
                .collect(Collectors.toList());
    }

    /**
     * Returns the total number of pages for a tab (minimum 1).
     *
     * @param tab Tab index (0–2).
     * @return Ceil(entries / {@link #MISSIONS_PER_PAGE}), or 1 if no entries.
     */
    private int getTotalPages(int tab) {
        int size = getEntriesForTab(tab).size();
        return Math.max(1, (int) Math.ceil((double) size / MISSIONS_PER_PAGE));
    }

    //  Init 

    /**
     * Adds tab buttons and the close button.
     * Called automatically on first open and whenever {@link #rebuildWidgets()} fires.
     */
    @Override
    protected void init() {
        int x = (this.width  - GUI_WIDTH)  / 2;
        int y = (this.height - GUI_HEIGHT) / 2;

        int tabWidth = 75;
        for (int i = 0; i < 3; i++) {
            final int tabIndex = i;
            this.addRenderableWidget(Button.builder(
                            Component.translatable(TAB_KEYS[i]),
                            btn -> {
                                currentTab  = tabIndex;
                                currentPage = 0;
                                rebuildWidgets();
                            })
                    .pos(x + 5 + i * (tabWidth + 2), y + 35)
                    .size(tabWidth, 16)
                    .build());
        }

        this.addRenderableWidget(Button.builder(
                        Component.translatable("quest.mod.close"),
                        btn -> this.onClose())
                .pos(x + GUI_WIDTH / 2 - 35, y + 210)
                .size(70, 16)
                .build());
    }

    //  Input 

    /**
     * Keyboard navigation:
     * <ul>
     *   <li>Left arrow (263) / Right arrow (262): previous / next page.</li>
     *   <li>1 / 2 / 3 (49–51): switch tabs.</li>
     * </ul>
     */
    @Override
    public boolean keyPressed(KeyEvent event) {
        switch (event.key()) {
            case 263 -> { // Left arrow
                if (currentPage > 0) { currentPage--; rebuildWidgets(); }
                return true;
            }
            case 262 -> { // Right arrow
                if (currentPage < getTotalPages(currentTab) - 1) { currentPage++; rebuildWidgets(); }
                return true;
            }
            case 49 -> { currentTab = 0; currentPage = 0; rebuildWidgets(); return true; }
            case 50 -> { currentTab = 1; currentPage = 0; rebuildWidgets(); return true; }
            case 51 -> { currentTab = 2; currentPage = 0; rebuildWidgets(); return true; }
        }
        return super.keyPressed(event);
    }

    //  Rendering 

    /**
     * Renders the journal panel with a vanilla Minecraft aesthetic:
     * dark background, pixel-border box, tab highlight, quest entries with
     * translated titles, and a page indicator.
     *
     * <p>All text is resolved through {@link Component#translatable} so it
     * automatically reflects the player's active language ({@code en_us} /
     * {@code es_mx}). In particular, quest title keys (e.g.
     * {@code quest.mod.madera_invierno.title}) are resolved to their localized
     * strings at render time.</p>
     *
     * @param graphics The GUI rendering context.
     * @param mouseX   Current X position of the mouse cursor.
     * @param mouseY   Current Y position of the mouse cursor.
     * @param delta    Partial tick fraction (unused — no animation).
     */
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int x = (this.width  - GUI_WIDTH)  / 2;
        int y = (this.height - GUI_HEIGHT) / 2;

        //  Panel background 

        graphics.fill(x, y, x + GUI_WIDTH, y + GUI_HEIGHT, COLOR_BG);

        // Vanilla raised pixel border
        graphics.fill(x, y, x + GUI_WIDTH, y + 1, COLOR_BORDER_LIGHT);           // top
        graphics.fill(x, y, x + 1, y + GUI_HEIGHT, COLOR_BORDER_LIGHT);          // left
        graphics.fill(x, y + GUI_HEIGHT - 1, x + GUI_WIDTH, y + GUI_HEIGHT, COLOR_BORDER_DARK); // bottom
        graphics.fill(x + GUI_WIDTH - 1, y, x + GUI_WIDTH, y + GUI_HEIGHT, COLOR_BORDER_DARK);  // right

        //  Title 

        // Resolved from quest.mod.journal.title in the active language file
        graphics.centeredText(this.font,
                Component.translatable("quest.mod.journal.title"),
                this.width / 2, y + 8,
                COLOR_BORDER_LIGHT);

        // Separator below title
        graphics.fill(x + 4, y + 22, x + GUI_WIDTH - 4, y + 23, COLOR_SEPARATOR);

        //  Tab highlight 

        int tabWidth = 75;
        int tabX = x + 5 + currentTab * (tabWidth + 2);
        graphics.fill(tabX, y + 35, tabX + tabWidth, y + 51, COLOR_TAB_ACTIVE);

        // Separator below tabs
        graphics.fill(x + 4, y + 53, x + GUI_WIDTH - 4, y + 54, COLOR_SEPARATOR);

        //  Quest entries 

        List<String> tabEntries = getEntriesForTab(currentTab);
        int start = currentPage * MISSIONS_PER_PAGE;
        int end   = Math.min(start + MISSIONS_PER_PAGE, tabEntries.size());

        if (tabEntries.isEmpty()) {
            // Resolved from quest.mod.journal.empty in the active language file
            graphics.centeredText(this.font,
                    Component.translatable("quest.mod.journal.empty"),
                    this.width / 2, y + 120,
                    COLOR_EMPTY);
        } else {
            int entryY = y + 60;
            for (int i = start; i < end; i++) {
                String[] parts = tabEntries.get(i).split("\\|");

                // parts[0] is a translation key (e.g. quest.mod.madera_invierno.title)
                // — resolved here so it reflects the player's active language.
                String title    = Component.translatable(parts[0]).getString();
                String progress = parts[1];
                String name     = parts.length > 3 ? parts[3] : "???";
                int progressColor = TAB_COLORS[currentTab];

                // Villager name — gold
                graphics.text(this.font, name,
                        x + 10, entryY,
                        COLOR_NAME, false);

                // Quest title — white, with entry number
                graphics.text(this.font, (i + 1) + ". " + title,
                        x + 10, entryY + 11,
                        COLOR_TITLE, false);

                // Progress — color depends on tab (gray / green / dark gray)
                // Label resolved from quest.mod.progress in the active language file
                graphics.text(this.font,
                        Component.translatable("quest.mod.progress").getString() + progress,
                        x + 10, entryY + 22,
                        progressColor, false);

                // Entry separator
                graphics.fill(x + 8, entryY + 33, x + GUI_WIDTH - 8, entryY + 34, COLOR_SEPARATOR);

                entryY += 42;
            }
        }

        //  Pagination 

        int totalPages = getTotalPages(currentTab);
        graphics.centeredText(this.font,
                Component.literal("< " + (currentPage + 1) + "/" + totalPages + " >"),
                this.width / 2, y + 195,
                COLOR_PAGE);

        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    /** This screen does not pause the game. */
    @Override
    public boolean isPauseScreen() { return false; }
}