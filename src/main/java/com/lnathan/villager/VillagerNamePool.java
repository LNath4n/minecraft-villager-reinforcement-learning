package com.lnathan.villager;

import java.util.List;
import java.util.Random;

/**
 * Pool of names that can be randomly assigned to villagers.
 *
 * <p>To add more names, simply append entries to the {@code NAMES} list.
 * Names are selected uniformly at random via {@link #getRandom()}.
 */
public class VillagerNamePool {

    /** Immutable list of available villager names. */
    private static final List<String> NAMES = List.of(
            "Nathan", "Josue"
    );

    /** Random number generator for name selection. */
    private static final Random RANDOM = new Random();

    /**
     * Returns a randomly selected name from the pool.
     *
     * @return a villager name chosen at random
     */
    public static String getRandom() {
        return NAMES.get(RANDOM.nextInt(NAMES.size()));
    }
}