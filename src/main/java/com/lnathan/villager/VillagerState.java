package com.lnathan.villager;

import org.jetbrains.annotations.NotNull;

/**
 * Possible states of a villager modified by this mod.
 *
 * <p>This enum is used as a synchronized server→client value through
 * {@link VillagerDataSync} and {@link VillagerRenderStateAccessor}, allowing
 * the render layer ({@code HungryVillagerLayer}) to display the correct icon
 * without requiring additional logic on the client side.
 *
 * <ul>
 *   <li>{@link #NORMAL} — default state, no icons or modifiers.</li>
 *   <li>{@link #HUNGRY} — the villager is hungry; reduced speed and bowl icon.</li>
 *   <li>{@link #CARTOGRAPHER_MIGRATING} — cartographer in transit to another village; map icon.</li>
 *   <li>{@link #GATHERING} — picking up materials from the ground.</li>
 *   <li>{@link #DEPOSITING} — walking toward a chest to deposit items.</li>
 *   <li>{@link #FLEEING} — actively fleeing from a threat.</li>
 *   <li>{@link #SOCIALIZING} — approaching other villagers.</li>
 *   <li>{@link #RESTING} — resting (at night or with no urgent tasks).</li>
 *   <li>{@link #BUILDING} — placing blocks.</li>
 *   <li>{@link #EXPLORING} — moving to explore the surroundings.</li>
 *   <li>{@link #IDLE} — no active task. The brain learns to avoid this state.</li>
 * </ul>
 */
public enum VillagerState {

    /** Base state. No speed modifiers or visible icons. */
    NORMAL,

    /**
     * The villager is hungry ({@code foodLevel < 12}).
     * Activates a speed modifier of {@code -0.4} and displays an icon above the head.
     */
    HUNGRY,

    /**
     * The cartographer villager is migrating toward another village's bell.
     * Activates a speed boost of {@code +1.5×} and displays an icon above the head.
     * Fleeing due to negative reputation is suspended during this state.
     */
    CARTOGRAPHER_MIGRATING,

    /**
     * The villager is picking up materials from the ground.
     * Set by {@code VillagerBrain} when choosing {@code GATHER_WOOD} or
     * {@code GATHER_STONE}. Useful for showing a pickaxe icon in the render layer.
     */
    GATHERING,

    /**
     * The villager is walking toward a chest to deposit items.
     * Set by {@code VillagerBrain} when choosing {@code STORE_ITEMS}.
     */
    DEPOSITING,

    /**
     * The villager is actively fleeing from a nearby threat.
     * Complements the {@code FleeHandler}: the handler executes the flee,
     * the brain decides if it was the correct action and assigns a reward.
     */
    FLEEING,

    /**
     * The villager is approaching other villagers.
     * Foundation for cooperative social behavior in future phases
     * (inter-villager trading, collaborative building, etc.).
     */
    SOCIALIZING,

    /**
     * The villager is resting.
     * Rewarded by the brain at night; penalized during the day.
     */
    RESTING,

    /**
     * The villager is placing blocks at a construction position.
     * Currently a placeholder — the actual building logic will be implemented
     * once the brain has converged on its base behaviors.
     */
    BUILDING,

    /**
     * The villager is exploring the environment by moving to random positions.
     * Allows the brain to discover resources and chests it was not aware of.
     */
    EXPLORING,

    /**
     * No active task. The brain learns to avoid this state when more useful
     * actions are available (hunger, threats, full inventory, etc.).
     * A small positive reward in pure idle prevents the villager from
     * panicking and always searching for something to do.
     */
    IDLE;

    /** Safe default state used to avoid {@code null} references. */
    public static final VillagerState DEFAULT = NORMAL;

    /**
     * Returns a guaranteed non-null state.
     *
     * @param state a potentially {@code null} state
     * @return {@code state} if not {@code null}, otherwise {@link #DEFAULT}
     */
    public static @NotNull VillagerState safe(VillagerState state) {
        return state == null ? DEFAULT : state;
    }
}