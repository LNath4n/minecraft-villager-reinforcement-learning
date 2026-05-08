package com.lnathan.villager.brian;

/**
 * Acciones que el aldeano puede elegir a través de su Q-Learning brain.
 *
 * <p>El orden del enum determina el índice en el array de valores Q, por lo que
 * NO debe reordenarse una vez que haya Q-tables guardadas en disco — hacerlo
 * invalidaría todos los aprendizajes previos.
 *
 * <p>Cada acción tiene una recompensa definida en {@link VillagerBrain#executeAction}
 * que depende del estado actual del aldeano y su entorno.
 */
public enum VillagerAction {

    /** No hacer nada. Penalizado si hay tareas urgentes pendientes. */
    IDLE,

    /** Consumir comida del inventario. Recompensado si tenía hambre. */
    EAT,

    /** Ir a recolectar madera del entorno. */
    GATHER_WOOD,

    /** Ir a recolectar piedra del entorno. */
    GATHER_STONE,

    /** Depositar materiales en un cofre cercano. */
    STORE_ITEMS,

    /** Alejarse de enemigos cercanos. */
    FLEE,

    /** Acercarse a otros aldeanos (base para comportamiento social). */
    SOCIALIZE,

    /** Descansar. Recompensado de noche, penalizado de día. */
    REST,

    /**
     * Colocar un bloque en una posición de construcción válida.
     * Actualmente es un placeholder — la lógica real de construcción
     * se implementará en una fase posterior.
     */
    BUILD,

    /** Explorar el entorno moviéndose a una posición aleatoria cercana. */
    EXPLORE
}