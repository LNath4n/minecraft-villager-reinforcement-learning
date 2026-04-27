package com.lnathan.villager;

/**
 * Interfaz de acceso al estado del aldeano en el lado cliente.
 *
 * <p>Implementada por {@code VillagerRenderStateMixin}, que la inyecta en
 * {@link net.minecraft.client.renderer.entity.state.VillagerRenderState}.
 * Permite que {@code HungryVillagerLayer} lea el estado del aldeano durante
 * el renderizado sin acceder directamente a la entidad servidora ni a sus
 * datos sincronizados.
 *
 * <p>El estado se copia desde la entidad al {@code RenderState} una vez
 * por frame en {@code VillagerRendererMixin#extractState}, siguiendo el
 * patrón estándar de Minecraft 1.21+ de separar datos de entidad y datos
 * de renderizado.
 *
 * @see VillagerDataSync contraparte en el lado servidor
 * @see VillagerState valores posibles del estado
 */
public interface VillagerRenderStateAccessor {

    /**
     * Devuelve el estado del aldeano que fue copiado desde la entidad
     * durante la última llamada a {@code extractRenderState}.
     *
     * @return estado actual en el render state; nunca {@code null}
     */
    VillagerState getVillagerState();

    /**
     * Escribe el estado en el render state. Solo debe llamarse desde
     * {@code VillagerRendererMixin#extractState} durante la preparación
     * del frame, nunca desde la lógica de renderizado propiamente dicha.
     *
     * @param state el estado a almacenar; no debe ser {@code null}
     */
    void setVillagerState(VillagerState state);
}