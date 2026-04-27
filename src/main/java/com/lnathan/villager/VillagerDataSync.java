package com.lnathan.villager;

/**
 * Interfaz de sincronización del estado del aldeano en el <b>lado servidor</b>.
 *
 * <p>Implementada por {@code VillagerDataMixin}, que la inyecta en la clase
 * {@link net.minecraft.world.entity.npc.villager.Villager} mediante Mixin.
 * Permite que cualquier handler de comportamiento (ej. {@code HungerHandler},
 * {@code FleeHandler}) lea y escriba el estado del aldeano sin depender
 * de un cast directo al Mixin concreto.
 *
 * <p>El estado se almacena en un {@link net.minecraft.network.syncher.SynchedEntityData}
 * canal, lo que garantiza que los cambios se propagan automáticamente a todos
 * los clientes conectados.
 *
 * @see VillagerRenderStateAccessor contraparte en el lado cliente
 * @see VillagerState valores posibles del estado
 */
public interface VillagerDataSync {

    /**
     * Devuelve el estado actual del aldeano tal como está almacenado en el
     * canal de datos sincronizados.
     *
     * @return estado actual; nunca {@code null} (el valor por defecto es
     *         {@link VillagerState#NORMAL})
     */
    VillagerState getVillagerState();

    /**
     * Actualiza el estado del aldeano y lo propaga a los clientes a través
     * del canal de {@code SynchedEntityData}.
     *
     * <p>Solo debe llamarse desde el hilo del servidor (dentro de
     * {@code customServerAiStep} o equivalente).
     *
     * @param state el nuevo estado; no debe ser {@code null}
     */
    void setVillagerState(VillagerState state);
}