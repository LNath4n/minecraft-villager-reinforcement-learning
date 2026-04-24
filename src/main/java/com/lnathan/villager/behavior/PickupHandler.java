package com.lnathan.villager.behavior;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Set;

/**
 * Gestiona la recogida automática de ítems del suelo hacia el inventario del aldeano.
 *
 * <p>El handler evalúa el estado del inventario y el entorno cada 40 ticks (2 segundos)
 * para no saturar la búsqueda de entidades. El ciclo de decisión es el siguiente:
 * <ol>
 *   <li>Si hay un depósito activo en {@link DepositHandler}, se salta completamente
 *       para no interferir con la navegación hacia el cofre.</li>
 *   <li>Si el inventario está lleno, llama a {@link DepositHandler#findNearbyChest}
 *       para vaciar un slot antes de seguir recogiendo.</li>
 *   <li>Si hay espacio, busca el ítem recogible más cercano dentro de 8 bloques,
 *       navega hacia él y lo recoge al llegar a ≤2.5 bloques de distancia.</li>
 * </ol>
 *
 * <p>Solo se recogen los ítems definidos en {@link #PICKUP_ITEMS}. El conjunto puede
 * ampliarse para soportar más recursos sin modificar la lógica del handler.
 *
 * @see DepositHandler
 */
public class PickupHandler {

    /**
     * Conjunto de ítems que el aldeano puede y quiere recoger del suelo.
     * La búsqueda de entidades filtra únicamente por estos tipos para evitar
     * recoger ítems irrelevantes (herramientas rotas, flechas, etc.).
     */
    public static final Set<Item> PICKUP_ITEMS = Set.of(
            Items.OAK_LOG,
            Items.BIRCH_LOG,
            Items.SPRUCE_LOG,
            Items.COBBLESTONE,
            Items.STONE
    );

    /**
     * Ticks restantes hasta la próxima evaluación del handler.
     * Cada evaluación resetea este contador a 40 (2 segundos a 20 TPS).
     */
    private int pickupCooldown = 0;

    /**
     * Referencia al handler de depósito. Se consulta para saber si hay un depósito
     * activo y para iniciar el vaciado del inventario cuando está lleno.
     */
    private final DepositHandler depositHandler;

    /**
     * Construye un nuevo {@code PickupHandler} enlazado al {@link DepositHandler}
     * del mismo aldeano.
     *
     * @param depositHandler el handler de depósito compartido con este aldeano
     */
    public PickupHandler(DepositHandler depositHandler) {
        this.depositHandler = depositHandler;
    }

    /**
     * Punto de entrada del tick. Evalúa cada 40 ticks el estado del inventario
     * y del entorno y decide si recoger, depositar o esperar.
     *
     * @param self      el aldeano
     * @param level     el nivel de servidor donde se buscan las entidades de ítem
     * @param inventory el inventario simple del aldeano (generalmente {@link SimpleContainer}
     *                  de 8 slots gestionado por el Mixin)
     */
    public void tick(Villager self, ServerLevel level, SimpleContainer inventory) {
        if (pickupCooldown > 0) {
            pickupCooldown--;
            return;
        }
        pickupCooldown = 40;

        // Si ya hay un depósito en curso, no interferimos
        if (depositHandler.hasPendingDeposit()) return;

        boolean inventoryFull = isInventoryFull(inventory);

        if (inventoryFull) {
            tryEmptyOneSlot(self, level, inventory);
            return;
        }

        pickupNearestItem(self, level, inventory);
    }

    /**
     * Comprueba si todos los slots del inventario están ocupados.
     *
     * @param inventory el inventario a examinar
     * @return {@code true} si no queda ningún slot vacío
     */
    private boolean isInventoryFull(SimpleContainer inventory) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).isEmpty()) return false;
        }
        return true;
    }

    /**
     * Intenta vaciar un único slot del inventario hacia un cofre cercano.
     *
     * <p>Se procesa solo el primer slot no vacío encontrado por ciclo para evitar
     * saturar el {@link DepositHandler} con múltiples destinos simultáneos.
     * Si no hay cofre disponible, no ocurre ningún movimiento y se espera al
     * siguiente ciclo de 40 ticks.
     *
     * @param self      el aldeano
     * @param level     el nivel de servidor
     * @param inventory el inventario del aldeano
     */
    private void tryEmptyOneSlot(Villager self, ServerLevel level, SimpleContainer inventory) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;

            boolean found = depositHandler.findNearbyChest(self, level, stack.copy());
            if (found) {
                inventory.getItem(i).shrink(1);
                //System.out.println("[PickupHandler Inventario] Vaciando slot " + i + " hacia cofre");
            } else {
                //System.out.println("[PickupHandler Inventario] Lleno pero no hay cofre cercano, esperando...");
            }
            return; // un slot por ciclo
        }
    }

    /**
     * Busca el ítem recogible más cercano dentro de 8 bloques, navega hacia él
     * y lo almacena en el inventario al llegar a ≤2.5 bloques de distancia.
     *
     * <p>Si el ítem está suficientemente cerca, se descarta la entidad del mundo
     * ({@link ItemEntity#discard()}) y se guarda el stack en el primer slot vacío.
     * Si no hay slot vacío en este punto (condición de carrera improbable), el ítem
     * se pierde; esto se evita normalmente porque el inventario se comprueba antes
     * de llegar aquí.
     *
     * @param self      el aldeano
     * @param level     el nivel de servidor
     * @param inventory el inventario donde se almacenará el ítem recogido
     */
    private void pickupNearestItem(Villager self, ServerLevel level, SimpleContainer inventory) {
        List<ItemEntity> nearby = level.getEntitiesOfClass(
                ItemEntity.class,
                self.getBoundingBox().inflate(8.0),
                itemEntity -> PICKUP_ITEMS.contains(itemEntity.getItem().getItem())
        );
        if (nearby.isEmpty()) return;

        ItemEntity target = nearby.stream()
                .min((a, b) -> Double.compare(a.distanceToSqr(self), b.distanceToSqr(self)))
                .orElse(null);

        if (target == null || !target.isAlive()) return;

        if (target.distanceToSqr(self) > 2.5 * 2.5) {
            self.getNavigation().moveTo(target, 0.6f);
            return;
        }

        ItemStack stack = target.getItem().copy();
        target.discard();

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).isEmpty()) {
                inventory.setItem(i, stack);
                //System.out.println("[PickupHandler] Guardó en inventario slot " + i + ": " + stack.getItem().getDescriptionId() + " x" + stack.getCount());
                break;
            }
        }
    }
}