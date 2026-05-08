package com.lnathan.quest;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.MapColor;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Interactive world map screen displaying villages, the player's position,
 * and associated quest information.
 *
 * <p>Styled with a vanilla Minecraft aesthetic: dark inventory-style panel,
 * pixel-border boxes (light top/left, dark bottom/right — matching the
 * standard {@code GuiGraphics.renderTooltip} look), and no fade animations.
 * All user-facing strings are resolved through the active language file via
 * {@link Component#translatable}.</p>
 *
 * <p>The map is rendered from a 128×128 {@code byte[]} of packed
 * {@link MapColor} IDs uploaded to a {@link DynamicTexture} at init time
 * and released on close.</p>
 *
 * <p>Clicking a village dot opens a side panel (left of the map) showing
 * the village name, world coordinates, distance, and its active quests.
 * The map shifts smoothly right to make room using exponential interpolation.</p>
 */
public class MapScreen extends Screen {

    //  Layout 

    /** Blocks-per-pixel scale factor for projecting world coords onto the map. */
    private static final int SCALE = 2;

    /** Width of the village detail side panel in pixels. */
    private static final int PANEL_WIDTH = 180;

    //  Vanilla color palette 

    /**
     * Map outer border — light edge of a vanilla "raised" pixel border.
     * Used for the top and left edges of bordered boxes.
     */
    private static final int COLOR_BORDER_LIGHT = 0xFFFFFFFF;

    /**
     * Map outer border — dark edge of a vanilla "raised" pixel border.
     * Used for the bottom and right edges of bordered boxes.
     */
    private static final int COLOR_BORDER_DARK  = 0xFF555555;

    /** Dark panel background matching vanilla inventory/tooltip dark fill. */
    private static final int COLOR_PANEL_BG     = 0xFF1D1D1D;

    /** Slightly lighter panel used for the side detail panel. */
    private static final int COLOR_PANEL_SIDE   = 0xFF2A2A2A;

    /** Separator line inside panels — mid-gray. */
    private static final int COLOR_SEPARATOR    = 0xFF555555;

    /** Player dot on the map — bright white. */
    private static final int COLOR_PLAYER_DOT   = 0xFFFFFFFF;

    /** Unselected village dot — vanilla gold. */
    private static final int COLOR_VILLAGE_DOT  = 0xFFFFAA00;

    /** Selected village dot — brighter yellow. */
    private static final int COLOR_VILLAGE_SEL  = 0xFFFFFF55;

    /** Map title / selected village name — vanilla gold. */
    private static final int COLOR_GOLD         = 0xFFFFAA00;

    /** Primary text — pure white. */
    private static final int COLOR_TEXT         = 0xFFFFFFFF;

    /** Secondary text — muted gray. */
    private static final int COLOR_TEXT_MUTED   = 0xFFAAAAAA;

    /** Coordinate / detail text — lighter gray. */
    private static final int COLOR_TEXT_DIM     = 0xFFCCCCCC;

    /** Quest progress color when ready to turn in — bright green. */
    private static final int COLOR_READY        = 0xFF55FF55;

    /** Legend player dot color — white. */
    private static final int COLOR_LEGEND_PLAYER = 0xFFFFFFFF;

    /** Legend village dot color — gold. */
    private static final int COLOR_LEGEND_VILLAGE = 0xFFFFAA00;

    //  Texture 

    /** Identifier used to register / release the dynamic map texture. */
    private static final Identifier MAP_TEXTURE_ID =
            Identifier.fromNamespaceAndPath("lnathan", "dynamic_map");

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger("lnathan");

    //  Data 

    /** Village entries in the format {@code "name|x|z"}. */
    private final List<String> villages;

    /** Player's world X coordinate (map center). */
    private final int playerX;

    /** Player's world Z coordinate (map center). */
    private final int playerZ;

    /** Journal entries for the village quest panel ({@code "title|progress|state|villagerName|villageName"}). */
    private final List<String> journalEntries;

    /** Raw packed map color bytes (128×128). */
    private final byte[] mapColors;

    //  Selection / animation state 

    /** Name of the currently selected village, or {@code null}. */
    private String selectedVillage = null;

    /** World X of the selected village. */
    private int selectedX = 0;

    /** World Z of the selected village. */
    private int selectedZ = 0;

    /** Current rendered X offset of the map (interpolated). */
    private float currentMapOffsetX = 0f;

    /** Target X offset the map animates toward. */
    private float targetMapOffsetX = 0f;

    /** Current rendered map scale (interpolated). */
    private float currentMapScale = 0.85f;

    /** Target map scale depending on selection state. */
    private float targetMapScale = 0.85f;

    /** GPU texture built from {@link #mapColors}; {@code null} on failure. */
    private DynamicTexture mapTexture = null;

    /** Base map size in pixels (clamped to screen). */
    private int mapSize = 300;

    // 

    /**
     * Creates the map screen with all data needed for rendering.
     *
     * @param villages       Village entries in the format {@code "name|x|z"}.
     * @param playerX        Player's current world X coordinate.
     * @param playerZ        Player's current world Z coordinate.
     * @param journalEntries Quest journal entries for the village panel.
     * @param mapColors      128×128 packed {@link MapColor} byte array.
     */
    public MapScreen(List<String> villages, int playerX, int playerZ,
                     List<String> journalEntries, byte[] mapColors) {
        super(Component.translatable("quest.mod.map.title"));
        this.villages       = villages;
        this.playerX        = playerX;
        this.playerZ        = playerZ;
        this.journalEntries = journalEntries;
        this.mapColors      = mapColors;
    }

    //  Init 

    /**
     * Calculates the map display size, uploads the terrain texture, and adds
     * the close button.
     */
    @Override
    protected void init() {
        int maxByHeight = this.height - 60;
        int maxByWidth  = this.width  - 40;
        mapSize = Math.min(maxByHeight, maxByWidth);
        mapSize = Math.min(mapSize, 420);
        mapSize = Math.max(mapSize, 180);

        // Close button — bottom-center of the screen
        this.addRenderableWidget(Button.builder(
                        Component.translatable("quest.mod.close"),
                        btn -> this.onClose())
                .pos(this.width / 2 - 50, this.height - 24)
                .size(100, 20)
                .build());

        // Upload terrain texture
        try {
            NativeImage image = new NativeImage(NativeImage.Format.RGBA, 128, 128, false);
            for (int i = 0; i < 128 * 128; i++) {
                int packedColor = mapColors[i] & 0xFF;
                if (packedColor < 4) {
                    image.setPixel(i % 128, i / 128, 0xFF000000);
                    continue;
                }
                int argb = MapColor.getColorFromPackedId(packedColor);
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8)  & 0xFF;
                int b =  argb        & 0xFF;
                // NativeImage pixel format is ABGR
                image.setPixel(i % 128, i / 128, (0xFF << 24) | (r << 16) | (g << 8) | b);
            }
            mapTexture = new DynamicTexture(() -> "lnathan_dynamic_map", image);
            Minecraft.getInstance().getTextureManager().register(MAP_TEXTURE_ID, mapTexture);
        } catch (Exception e) {
            LOGGER.warn("Error creando textura del mapa: " + e.getMessage());
        }
    }

    //  Lifecycle 

    /** Releases the dynamic map texture from GPU memory. */
    @Override
    public void onClose() {
        if (mapTexture != null) {
            Minecraft.getInstance().getTextureManager().release(MAP_TEXTURE_ID);
            mapTexture.close();
            mapTexture = null;
        }
        super.onClose();
    }

    /** This screen does not pause the game. */
    @Override
    public boolean isPauseScreen() { return false; }

    //  Rendering 

    /**
     * Renders the map screen each frame with vanilla Minecraft styling:
     * dark background, pixel-border map frame, village dots, blinking player
     * indicator, legend, and an animated side panel when a village is selected.
     *
     * @param graphics The GUI rendering context.
     * @param mouseX   Current X position of the mouse cursor.
     * @param mouseY   Current Y position of the mouse cursor.
     * @param delta    Partial tick fraction for animation interpolation.
     */
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        float dt = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaTicks() / 20f;

        // Animate map position / scale toward targets
        targetMapOffsetX  = selectedVillage != null ? (PANEL_WIDTH / 2f + 10f) : 0f;
        currentMapOffsetX += (targetMapOffsetX  - currentMapOffsetX)  * 0.15f;

        targetMapScale   = selectedVillage != null ? 0.78f : 0.85f;
        currentMapScale  += (targetMapScale   - currentMapScale)   * 0.15f;

        int animatedSize = (int)(mapSize * currentMapScale);
        int mapX  = (int)(this.width  / 2 - animatedSize / 2 + currentMapOffsetX);
        int mapY  = this.height / 2 - animatedSize / 2;
        int ctrX  = mapX + animatedSize / 2;
        int ctrZ  = mapY + animatedSize / 2;

        //  Full-screen dark background 

        graphics.fill(0, 0, this.width, this.height, 0xAA000000);

        //  Map title 

        graphics.centeredText(this.font,
                Component.translatable("quest.mod.map.title"),
                mapX + animatedSize / 2, mapY - 14,
                COLOR_GOLD);

        //  Terrain texture (or dark fallback) 

        if (mapTexture != null) {
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    MAP_TEXTURE_ID,
                    mapX, mapY,
                    0f, 0f,
                    animatedSize, animatedSize,
                    animatedSize, animatedSize
            );
        } else {
            graphics.fill(mapX, mapY, mapX + animatedSize, mapY + animatedSize, COLOR_PANEL_BG);
        }

        //  Vanilla pixel border (raised) 
        // Top edge (light)
        graphics.fill(mapX, mapY, mapX + animatedSize, mapY + 1, COLOR_BORDER_LIGHT);
        // Left edge (light)
        graphics.fill(mapX, mapY, mapX + 1, mapY + animatedSize, COLOR_BORDER_LIGHT);
        // Bottom edge (dark)
        graphics.fill(mapX, mapY + animatedSize - 1, mapX + animatedSize, mapY + animatedSize, COLOR_BORDER_DARK);
        // Right edge (dark)
        graphics.fill(mapX + animatedSize - 1, mapY, mapX + animatedSize, mapY + animatedSize, COLOR_BORDER_DARK);

        //  Village dots + labels 

        for (String entry : villages) {
            String[] parts = entry.split("\\|");
            if (parts.length < 3) continue;

            String name = parts[0];
            int vx = Integer.parseInt(parts[1]);
            int vz = Integer.parseInt(parts[2]);

            int svx = ctrX + (vx - playerX) / SCALE;
            int svz = ctrZ + (vz - playerZ) / SCALE;

            if (svx < mapX || svx > mapX + animatedSize || svz < mapY || svz > mapY + animatedSize) continue;

            boolean isSelected = name.equals(selectedVillage);
            int dotColor = isSelected ? COLOR_VILLAGE_SEL : COLOR_VILLAGE_DOT;
            int dotSize  = isSelected ? 4 : 3;

            // Dot
            graphics.fill(svx - dotSize, svz - dotSize, svx + dotSize, svz + dotSize, dotColor);

            // Label — white text with a 1-px dark shadow (vanilla style)
            int labelX = svx - this.font.width(name) / 2;
            graphics.text(this.font, name, labelX + 1, svz - 11, 0xFF000000, false); // shadow
            graphics.text(this.font, name, labelX,     svz - 12, COLOR_TEXT,    false);
        }

        //  Player indicator (blinking cross, vanilla style) 

        long time = System.currentTimeMillis();
        if ((time / 500) % 2 == 0) {
            // Center square
            graphics.fill(ctrX - 2, ctrZ - 2, ctrX + 2, ctrZ + 2, COLOR_PLAYER_DOT);
            // Horizontal bar
            graphics.fill(ctrX - 4, ctrZ - 1, ctrX + 4, ctrZ + 1, 0xAAFFFFFF);
            // Vertical bar
            graphics.fill(ctrX - 1, ctrZ - 4, ctrX + 1, ctrZ + 4, 0xAAFFFFFF);
        }

        //  Legend (bottom-left of map) 

        int legendY = mapY + animatedSize - 20;
        graphics.text(this.font,
                Component.translatable("quest.mod.map.legend.player").getString(),
                mapX + 6, legendY,
                COLOR_LEGEND_PLAYER, false);
        graphics.text(this.font,
                Component.translatable("quest.mod.map.legend.village").getString(),
                mapX + 6, legendY + 10,
                COLOR_LEGEND_VILLAGE, false);

        //  Village detail panel 

        if (selectedVillage != null || currentMapOffsetX > 1f) {
            float panelAlpha = currentMapOffsetX / (PANEL_WIDTH / 2f + 10f);
            drawVillagePanel(graphics, mapX - PANEL_WIDTH - 8, mapY, animatedSize, panelAlpha);
        }

        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    /**
     * Draws the village detail panel with a vanilla dark-panel aesthetic.
     *
     * @param graphics    Rendering context.
     * @param px          Panel top-left X.
     * @param py          Panel top-left Y.
     * @param panelHeight Height to match the animated map height.
     * @param alpha       Animation progress [0,1]; text hidden below 0.5.
     */
    private void drawVillagePanel(GuiGraphicsExtractor graphics,
                                  int px, int py, int panelHeight, float alpha) {
        List<String> villageMissions = journalEntries.stream()
                .filter(e -> {
                    String[] p = e.split("\\|");
                    return p.length > 4 && !p[4].isEmpty()
                            && p[4].split(":")[0].equals(selectedVillage);
                })
                .toList();

        int contentHeight = 100 + Math.max(1, villageMissions.size()) * 22;
        int ph = Math.min(contentHeight, panelHeight);

        // Panel background — slightly lighter than pure black
        graphics.fill(px, py, px + PANEL_WIDTH, py + ph, COLOR_PANEL_SIDE);

        // Vanilla raised pixel border
        graphics.fill(px, py, px + PANEL_WIDTH, py + 1, COLOR_BORDER_LIGHT);
        graphics.fill(px, py, px + 1, py + ph, COLOR_BORDER_LIGHT);
        graphics.fill(px, py + ph - 1, px + PANEL_WIDTH, py + ph, COLOR_BORDER_DARK);
        graphics.fill(px + PANEL_WIDTH - 1, py, px + PANEL_WIDTH, py + ph, COLOR_BORDER_DARK);

        // Wait until the panel is visible enough to render text
        if (alpha < 0.5f) return;

        // Village name — centered, gold
        graphics.centeredText(this.font,
                Component.literal(selectedVillage != null ? selectedVillage : ""),
                px + PANEL_WIDTH / 2, py + 8,
                COLOR_GOLD);

        // Separator under name
        graphics.fill(px + 4, py + 20, px + PANEL_WIDTH - 4, py + 21, COLOR_SEPARATOR);

        // Coordinates
        graphics.text(this.font,
                "X: " + selectedX + "  Z: " + selectedZ,
                px + 8, py + 27,
                COLOR_TEXT_DIM, false);

        // Distance
        int dist = (int) Math.sqrt(
                Math.pow(selectedX - playerX, 2) + Math.pow(selectedZ - playerZ, 2));
        graphics.text(this.font,
                Component.translatable("quest.mod.map.distance", dist).getString(),
                px + 8, py + 38,
                COLOR_TEXT_MUTED, false);

        // Separator before quest list
        graphics.fill(px + 4, py + 52, px + PANEL_WIDTH - 4, py + 53, COLOR_SEPARATOR);

        // Quest count header
        graphics.text(this.font,
                Component.translatable("quest.mod.map.missions_count", villageMissions.size()).getString(),
                px + 8, py + 58,
                COLOR_TEXT, false);

        if (villageMissions.isEmpty()) {
            graphics.text(this.font,
                    Component.translatable("quest.mod.map.no_missions").getString(),
                    px + 8, py + 72,
                    COLOR_TEXT_MUTED, false);
        } else {
            int mY = py + 72;
            for (String entry : villageMissions) {
                String[] parts   = entry.split("\\|");
                String title     = Component.translatable(parts[0]).getString();
                String progress  = parts[1];
                String state     = parts[2];

                int stateColor = state.equals("READY_TO_TURN_IN") ? COLOR_READY : COLOR_TEXT_MUTED;

                String display = title.length() > 20 ? title.substring(0, 17) + "..." : title;
                graphics.text(this.font, "- " + display, px + 8, mY,     COLOR_TEXT,   false);
                graphics.text(this.font, progress,        px + 8, mY + 10, stateColor, false);
                mY += 22;
            }
        }
    }

    //  Input 

    /**
     * Selects or deselects a village by mouse click.
     * Converts physical mouse pixels to GUI-scaled coordinates before hit-testing.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        double mouseX   = Minecraft.getInstance().mouseHandler.xpos() / guiScale;
        double mouseY   = Minecraft.getInstance().mouseHandler.ypos() / guiScale;

        int animatedSize = (int)(mapSize * currentMapScale);
        int mapX  = (int)(this.width  / 2 - animatedSize / 2 + currentMapOffsetX);
        int mapY  = this.height / 2 - animatedSize / 2;
        int ctrX  = mapX + animatedSize / 2;
        int ctrZ  = mapY + animatedSize / 2;

        for (String entry : villages) {
            String[] parts = entry.split("\\|");
            if (parts.length < 3) continue;

            String name = parts[0];
            int vx = Integer.parseInt(parts[1]);
            int vz = Integer.parseInt(parts[2]);

            int svx = ctrX + (vx - playerX) / SCALE;
            int svz = ctrZ + (vz - playerZ) / SCALE;

            if (Math.abs(mouseX - svx) <= 8 && Math.abs(mouseY - svz) <= 8) {
                selectedVillage = name;
                selectedX = vx;
                selectedZ = vz;
                return true;
            }
        }

        selectedVillage = null;
        return super.mouseClicked(event, doubleClick);
    }
}