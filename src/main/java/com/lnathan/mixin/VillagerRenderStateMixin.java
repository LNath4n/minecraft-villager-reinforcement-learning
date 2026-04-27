package com.lnathan.mixin;

import com.lnathan.villager.VillagerRenderStateAccessor;
import com.lnathan.villager.VillagerState;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Mixin que añade el campo {@link VillagerState} al {@link VillagerRenderState}
 * de Minecraft e implementa {@link VillagerRenderStateAccessor} para exponerlo.
 *
 * <p>{@code VillagerRenderState} es una clase de datos inmutable que Minecraft
 * crea cada frame para aislar el render del hilo principal. Como no podemos
 * modificar esa clase directamente, usamos Mixin para añadirle el campo
 * {@link #villagerState} que {@code HungryVillagerLayer} necesita leer.
 *
 * <p>El valor por defecto es {@link VillagerState#NORMAL} para que, en el
 * primer frame antes de que {@code VillagerRendererMixin} copie el estado,
 * no se muestre ningún icono incorrecto.
 */
@Mixin(VillagerRenderState.class)
public class VillagerRenderStateMixin implements VillagerRenderStateAccessor {

    /**
     * Estado del aldeano para el frame actual. Copiado desde la entidad por
     * {@code VillagerRendererMixin#extractState} una vez por frame.
     * Valor por defecto: {@link VillagerState#NORMAL}.
     */
    @Unique
    public VillagerState villagerState = VillagerState.NORMAL;

    /**
     * {@inheritDoc}
     *
     * @return el estado almacenado para este frame; nunca {@code null}
     */
    @Override
    public VillagerState getVillagerState() { return this.villagerState; }

    /**
     * {@inheritDoc}
     *
     * @param state el estado a almacenar para este frame
     */
    @Override
    public void setVillagerState(VillagerState state) { this.villagerState = state; }
}