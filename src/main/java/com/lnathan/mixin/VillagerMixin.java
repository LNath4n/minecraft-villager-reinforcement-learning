package com.lnathan.mixin;

import com.lnathan.villager.VillagerDataSync;
import com.lnathan.villager.behavior.DepositHandler;
import com.lnathan.villager.behavior.FleeHandler;
import com.lnathan.villager.behavior.HungerHandler;
import com.lnathan.villager.behavior.MigrationHandler;
import com.lnathan.villager.behavior.PickupHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.*;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Punto de entrada del comportamiento del aldeano modificado.
 *
 * <p>Este Mixin actúa únicamente como <em>orquestador</em>: instancia los handlers,
 * los llama en el orden correcto cada tick y gestiona el inventario personalizado
 * del aldeano. Toda la lógica de comportamiento real vive en sus handlers
 * correspondientes.
 *
 * <h3>Handlers registrados</h3>
 * <ul>
 *   <li>{@link FleeHandler} — huida cuando el jugador tiene reputación negativa.</li>
 *   <li>{@link MigrationHandler} — cartógrafo buscando la campana de otra aldea.</li>
 *   <li>{@link HungerHandler} — slowdown y decisión de destino al tener hambre.</li>
 *   <li>{@link PickupHandler} — recoge ítems del suelo hacia el inventario.</li>
 *   <li>{@link DepositHandler} — deposita ítems en cofres cercanos + cierre visual.</li>
 * </ul>
 *
 * <h3>Inventario</h3>
 * <p>Se añade un {@link SimpleContainer} de 9 slots que se puede inspeccionar
 * haciendo Shift+clic sobre el aldeano. El inventario se persiste en NBT mediante
 * los hooks {@link #onSave} y {@link #onLoad}.
 *
 * <h3>Orden de tick</h3>
 * <p>Los handlers se llaman en este orden dentro de {@code customServerAiStep}:
 * {@code deposit → migration → flee → hunger → pickup}. El depósito va primero
 * para que el inventario esté actualizado antes de que pickup decida si hay espacio.
 */
@Mixin(Villager.class)
public class VillagerMixin {

    /**
     * Inventario personalizado del aldeano. Se inicializa de forma lazy la primera
     * vez que se necesita (en el primer tick o al cargar desde NBT) para evitar
     * instanciar contenedores en aldeanos que nunca interactúan con ítems.
     */
    @Unique
    private SimpleContainer inventory = null;

    /** Gestiona el depósito de ítems en cofres cercanos. */
    @Unique private final DepositHandler depositHandler = new DepositHandler();

    /** Gestiona la migración del cartógrafo hacia otra aldea. */
    @Unique private final MigrationHandler migrationHandler = new MigrationHandler();

    /** Gestiona la huida del aldeano cuando el jugador tiene mala reputación. */
    @Unique private final FleeHandler fleeHandler = new FleeHandler();

    /**
     * Gestiona el hambre del aldeano. Recibe {@link #migrationHandler} porque
     * es quien decide y arranca la migración del cartógrafo.
     */
    @Unique private final HungerHandler hungerHandler = new HungerHandler(migrationHandler);

    /**
     * Gestiona la recogida de ítems del suelo. Recibe {@link #depositHandler} para
     * consultarle si hay un depósito activo antes de intentar recoger.
     */
    @Unique private final PickupHandler pickupHandler = new PickupHandler(depositHandler);

    /**
     * Hook principal de tick. Se inyecta al final de {@code customServerAiStep}
     * para que los handlers del mod corran después de toda la IA vanilla del aldeano.
     *
     * @param level el nivel de servidor del tick actual
     * @param ci    callback de Mixin (no usado)
     */
    @Inject(at = @At("TAIL"), method = "customServerAiStep")
    private void onTick(ServerLevel level, CallbackInfo ci) {
        Villager self = (Villager) (Object) this;
        SimpleContainer inv = getOrCreateInventory();

        depositHandler.tick(self, level);
        migrationHandler.tick(self, level);
        fleeHandler.tick(self, level);
        hungerHandler.tick(self, level);
        pickupHandler.tick(self, level, inv);
    }

    /**
     * Intercepta la interacción del jugador con el aldeano. Cuando el jugador
     * hace Shift+clic en el lado servidor, abre un menú de cofre 9×1 con el
     * inventario del aldeano y cancela el comportamiento vanilla (abrir trades).
     *
     * @param player el jugador que interactúa
     * @param hand   la mano usada en la interacción
     * @param cir    callback returnable; se usa para devolver {@link InteractionResult#SUCCESS}
     *               y cancelar el flujo vanilla
     */
    @Inject(method = "mobInteract", at = @At("HEAD"), cancellable = true)
    private void onInteract(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        Villager self = (Villager) (Object) this;
        if (!player.level().isClientSide() && player.isShiftKeyDown()) {
            player.openMenu(new SimpleMenuProvider(
                    (syncId, playerInv, p) -> new ChestMenu(
                            MenuType.GENERIC_9x1, syncId, playerInv, getOrCreateInventory(), 1
                    ),
                    Component.literal("Inventario del Aldeano")
            ));
            cir.setReturnValue(InteractionResult.SUCCESS);
        }
    }

    /**
     * Persiste el inventario del aldeano en NBT al guardar la entidad.
     * Solo serializa los slots no vacíos para minimizar el tamaño del NBT.
     * Si el inventario nunca fue inicializado (aldeano sin ítems), no escribe nada.
     *
     * @param output destino de escritura NBT proporcionado por Minecraft
     * @param ci     callback de Mixin (no usado)
     */
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void onSave(ValueOutput output, CallbackInfo ci) {
        if (inventory == null) return;
        ValueOutput.ValueOutputList list = output.childrenList("VillagerInventory");
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                ValueOutput slot = list.addChild();
                slot.putInt("Slot", i);
                slot.store("Item", ItemStack.CODEC, stack);
            }
        }
    }

    /**
     * Restaura el inventario del aldeano desde NBT al cargar la entidad.
     * Si la clave {@code "VillagerInventory"} no existe en el NBT (aldeano antiguo
     * o sin ítems guardados), no ocurre nada.
     *
     * @param input fuente de lectura NBT proporcionada por Minecraft
     * @param ci    callback de Mixin (no usado)
     */
    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void onLoad(ValueInput input, CallbackInfo ci) {
        input.childrenList("VillagerInventory").ifPresent(list -> {
            getOrCreateInventory();
            list.stream().forEach(slot -> {
                int index = slot.getIntOr("Slot", -1);
                if (index >= 0 && index < inventory.getContainerSize()) {
                    slot.read("Item", ItemStack.CODEC)
                            .ifPresent(stack -> inventory.setItem(index, stack));
                }
            });
        });
    }

    /**
     * Devuelve el inventario del aldeano, creándolo si aún no existe.
     * Se usa en todos los puntos que necesitan acceder al inventario para
     * garantizar que nunca sea {@code null}.
     *
     * @return el {@link SimpleContainer} de 9 slots del aldeano
     */
    @Unique
    private SimpleContainer getOrCreateInventory() {
        if (inventory == null) inventory = new SimpleContainer(9);
        return inventory;
    }
}