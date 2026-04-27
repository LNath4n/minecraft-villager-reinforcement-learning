package com.lnathan.villager;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.npc.VillagerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Capa de renderizado que dibuja un icono flotante sobre la cabeza del aldeano
 * cuando su estado es distinto de {@link VillagerState#NORMAL}.
 *
 * <p>Se registra en el {@code VillagerRenderer} durante la inicialización del mod
 * (lado cliente) añadiendo esta capa a la lista de capas del renderer. Hereda de
 * {@link RenderLayer} siguiendo el patrón estándar de Minecraft para decoraciones
 * sobre entidades (comparable a la capa de armadura o la de nombre).
 *
 * <h3>Iconos por estado</h3>
 * <ul>
 *   <li>{@link VillagerState#HUNGRY}  {@link Items#BOWL} (cuenco vacío).</li>
 *   <li>{@link VillagerState#CARTOGRAPHER_MIGRATING}  {@link Items#MAP}.</li>
 *   <li>{@link VillagerState#NORMAL}  no se dibuja nada.</li>
 * </ul>
 *
 * <h3>Optimización de distancia</h3>
 * <p>El icono solo se renderiza si la distancia al cuadrado a la cámara es
 * {@code ≤ 64} ({@code 8 bloques}), para no gastar draw calls en aldeanos
 * que el jugador apenas puede ver.
 *
 * <h3>Animación de bobbing</h3>
 * <p>El icono oscila verticalmente con una función seno suave basada en
 * {@code state.ageInTicks} para dar sensación de movimiento sin coste
 * adicional de animación.
 */
public class HungryVillagerLayer extends RenderLayer<VillagerRenderState, VillagerModel> {

    /**
     * Resolvedor de modelos de ítem inyectado desde el renderer padre.
     * Se necesita para construir el {@link ItemStackRenderState} en cada frame
     * sin acceder al {@code Minecraft} singleton en el camino crítico.
     */
    private final ItemModelResolver itemModelResolver;

    /**
     * Construye la capa y la enlaza al renderer padre.
     *
     * @param parent            el renderer de aldeano al que pertenece esta capa
     * @param itemModelResolver resolvedor de modelos de ítem del renderer
     */
    public HungryVillagerLayer(RenderLayerParent<VillagerRenderState, VillagerModel> parent,
                               ItemModelResolver itemModelResolver) {
        super(parent);
        this.itemModelResolver = itemModelResolver;
    }

    /**
     * Renderiza el icono flotante para este frame.
     *
     * <p>Secuencia de transformaciones aplicadas al {@link PoseStack}:
     * <ol>
     *   <li>Traslación vertical hasta justo sobre la cabeza del aldeano
     *       ({@code boundingBoxHeight - 2.8}).</li>
     *   <li>Rotación en Y inversa a {@code yRot} para que el icono siempre
     *       mire a la cámara.</li>
     *   <li>Rotación en X por {@code xRot} para compensar la inclinación
     *       de la cámara.</li>
     *   <li>Escala {@code 0.4×} con Y negativa para corregir la orientación
     *       del modelo de ítem en contexto {@code FIXED}.</li>
     *   <li>Traslación de bobbing basada en seno.</li>
     * </ol>
     *
     * @param poseStack           pila de transformaciones del frame actual
     * @param submitNodeCollector colector de nodos de renderizado
     * @param lightCoords         coordenadas de luz empaquetadas (sky + block)
     * @param state               render state del aldeano para este frame
     * @param yRot                rotación Y del cuerpo del aldeano en grados
     * @param xRot                rotación X del cuerpo del aldeano en grados
     */
    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
                       int lightCoords, VillagerRenderState state,
                       float yRot, float xRot) {

        VillagerState villagerState = ((VillagerRenderStateAccessor) state).getVillagerState();

        Item icon = switch (villagerState) {
            case HUNGRY -> Items.BOWL;
            case CARTOGRAPHER_MIGRATING -> Items.MAP;
            default -> null;
        };

        if (icon == null) return;
        // Solo renderizamos si el aldeano está a ≤8 bloques (64 = 8²)
        if (state.distanceToCameraSq > 64.0) return;

        poseStack.pushPose();

        // Posicionamos el icono justo sobre la cabeza del aldeano
        poseStack.translate(0, state.boundingBoxHeight - 2.8, 0);
        // Contrarotamos para que el icono siempre mire hacia la cámara
        poseStack.mulPose(Axis.YP.rotationDegrees(-yRot));
        poseStack.mulPose(Axis.XP.rotationDegrees(xRot));
        // Y negativa corrige la orientación del modelo de ítem en modo FIXED
        poseStack.scale(0.4f, -0.4f, 0.4f);

        // Animación de bobbing suave basada en la edad del aldeano
        float bob = (float) Math.sin(state.ageInTicks * 0.05f) * 0.15f;
        poseStack.translate(0, bob, 0);

        Minecraft mc = Minecraft.getInstance();
        ItemStackRenderState renderState = new ItemStackRenderState();
        itemModelResolver.updateForLiving(
                renderState,
                new ItemStack(icon),
                net.minecraft.world.item.ItemDisplayContext.FIXED,
                mc.player
        );
        renderState.submit(poseStack, submitNodeCollector, lightCoords, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
    }
}