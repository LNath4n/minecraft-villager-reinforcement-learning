package com.lnathan.villager;

/**
 * Estados posibles del aldeano modificado por este mod.
 *
 * <p>Este enum se usa como valor sincronizado servidor→cliente a través de
 * {@link VillagerDataSync} y {@link VillagerRenderStateAccessor}, lo que permite
 * que la capa de renderizado ({@code HungryVillagerLayer}) muestre el icono
 * correcto sin necesidad de lógica adicional en el cliente.
 *
 * <ul>
 *   <li>{@link #NORMAL} — estado por defecto, sin iconos ni modificadores.</li>
 *   <li>{@link #HUNGRY} — el aldeano tiene hambre; velocidad reducida e icono de cuenco.</li>
 *   <li>{@link #CARTOGRAPHER_MIGRATING} — cartógrafo en tránsito hacia otra aldea; icono de mapa.</li>
 * </ul>
 */
public enum VillagerState {

    /** Estado base. No hay modificadores de velocidad ni iconos visibles. */
    NORMAL,

    /**
     * El aldeano tiene hambre ({@code foodLevel < 12}).
     * Activa un modificador de velocidad de {@code -0.4} y muestra un icono sobre la cabeza.
     */
    HUNGRY,

    /**
     * El aldeano cartógrafo está migrando hacia la campana de otra aldea.
     * Activa un boost de velocidad {@code +1.5×} y muestra un icono sobre la cabeza.
     * La huida por reputación negativa se suspende durante este estado.
     */
    CARTOGRAPHER_MIGRATING
}