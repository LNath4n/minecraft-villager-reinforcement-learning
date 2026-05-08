package com.lnathan.client;

import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * Registry of custom keybindings for the mod.
 * <p>
 * Defines and registers the keyboard shortcuts that players can use
 * to interact with the mod's features. Must be initialized during
 * client startup (Fabric's keybind registration event).
 * </p>
 */
public class ModKeybinds {

    /**
     * Keybind to open the quest journal.
     * Defaults to the {@code J} key under the {@code GAMEPLAY} category.
     */
    public static KeyMapping OPEN_JOURNAL;

    /**
     * Keybind to open the world map.
     * Defaults to the {@code M} key under the {@code GAMEPLAY} category.
     */
    public static KeyMapping OPEN_MAP;

    /**
     * Registers all mod keybindings.
     * <p>
     * Must be called during client initialization (Fabric's keybind registration
     * event) so that Minecraft recognizes the shortcuts.
     * </p>
     */
    public static void register() {
        OPEN_JOURNAL = new KeyMapping(
                "key.mod.journal",
                GLFW.GLFW_KEY_J,
                KeyMapping.Category.GAMEPLAY
        );

        OPEN_MAP = new KeyMapping(
                "key.mod.map",
                GLFW.GLFW_KEY_M,
                KeyMapping.Category.GAMEPLAY
        );
    }
}