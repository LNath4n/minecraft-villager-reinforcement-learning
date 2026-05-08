package com.lnathan.villager;

import java.util.UUID;

/**
 * Client-side manager that tracks which villager is currently locked
 * (i.e. has an open interaction menu with the player).
 *
 * <p>Only one villager can be locked at a time. Locking is intended to prevent
 * other systems from interacting with a villager while the player has its
 * menu open. The lock must be explicitly released by calling {@link #unlock()}
 * when the menu is closed.
 *
 * <p>This class uses static state and is not thread-safe; it should only be
 * accessed from the client thread.
 */
public class VillagerLockManager {

    /** UUID of the currently locked villager, or {@code null} if none is locked. */
    private static UUID lockedVillager = null;

    /**
     * Locks the villager with the given UUID, marking it as currently in use.
     *
     * @param uuid the string representation of the villager's UUID to lock
     */
    public static void lock(String uuid) {
        lockedVillager = UUID.fromString(uuid);
    }

    /**
     * Releases the current lock, allowing other villagers to be interacted with.
     */
    public static void unlock() {
        lockedVillager = null;
    }

    /**
     * Checks whether the villager with the given UUID is currently locked.
     *
     * @param uuid the string representation of the UUID to check
     * @return {@code true} if this villager is the one currently locked
     */
    public static boolean isLocked(String uuid) {
        return lockedVillager != null && lockedVillager.toString().equals(uuid);
    }

    /**
     * Returns the UUID of the currently locked villager.
     *
     * @return the locked villager's {@link UUID}, or {@code null} if none is locked
     */
    public static UUID getLockedUuid() {
        return lockedVillager;
    }
}