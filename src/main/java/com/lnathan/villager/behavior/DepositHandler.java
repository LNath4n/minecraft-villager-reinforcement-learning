package com.lnathan.villager.behavior;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * Gestiona el ciclo completo de depósito de ítems desde el inventario del aldeano
 * hacia un cofre cercano en el mundo.
 *
 * <p>El flujo es el siguiente:
 * <ol>
 *   <li>{@link #findNearbyChest} — localiza el cofre más cercano con espacio disponible
 *       y envía al aldeano hacia él.</li>
 *   <li>{@link #handleDeposit} — corre cada tick; espera a que el aldeano llegue y
 *       deposita 1 ítem cada 10 ticks para simular un depósito gradual.</li>
 *   <li>{@link #handleChestClose} — cierra visualmente el cofre 40 ticks después de
 *       haber depositado el último ítem.</li>
 * </ol>
 *
 * <p>Este handler es invocado por {@link PickupHandler} cuando el inventario del aldeano
 * está lleno y necesita liberar espacio antes de seguir recogiendo ítems.
 */
public class DepositHandler {

    /** Posición del cofre destino activo, o {@code null} si no hay depósito en curso. */
    private BlockPos pendingDepositChest = null;

    /**
     * Stack pendiente de depositar. Se decrementa en 1 por cada ítem depositado
     * con éxito. Cuando queda vacío, el depósito se considera completado.
     */
    private ItemStack pendingDepositStack = ItemStack.EMPTY;

    /**
     * Ticks restantes antes del próximo intento de depósito.
     * Se reinicia a 10 tras cada depósito exitoso (0.5 segundos a 20 TPS).
     */
    private int depositTickCooldown = 0;

    /** Posición del cofre que hay que cerrar visualmente, o {@code null} si no aplica. */
    private BlockPos chestClosePos = null;

    /**
     * GameTime en el que se debe enviar el evento de cierre del cofre.
     * {@code -1} indica que no hay cierre pendiente.
     */
    private long chestCloseTick = -1;

    /**
     * Punto de entrada del tick. Debe llamarse desde el Mixin del aldeano cada tick
     * de servidor. Delega en {@link #handleChestClose} y {@link #handleDeposit}
     * en ese orden para que el cofre no se cierre antes de terminar el depósito.
     *
     * @param self  el aldeano cuyo depósito se gestiona
     * @param level el nivel de servidor donde vive el aldeano
     */
    public void tick(Villager self, ServerLevel level) {
        handleChestClose(self, level);
        handleDeposit(self, level);
    }

    /**
     * Indica si hay un depósito activo pendiente de completarse.
     *
     * @return {@code true} si el stack pendiente no está vacío
     */
    public boolean hasPendingDeposit() {
        return !pendingDepositStack.isEmpty();
    }

    /**
     * Busca el cofre más cercano con espacio para {@code stack} dentro de un radio
     * de 16 bloques en XZ (±3 en Y) y registra el destino para el depósito.
     *
     * <p>El depósito real no ocurre aquí; solo se guarda la posición del cofre y se
     * inicia la navegación del aldeano hacia él. El método {@link #handleDeposit}
     * realizará el depósito cuando el aldeano llegue.
     *
     * @param self  el aldeano que necesita depositar
     * @param level el nivel de servidor
     * @param stack el ítem que se quiere depositar (se copia internamente con count=1)
     * @return {@code true} si se encontró un cofre válido y se inició la navegación;
     *         {@code false} si no hay ningún cofre con espacio en el radio de búsqueda
     */
    public boolean findNearbyChest(Villager self, ServerLevel level, ItemStack stack) {
        BlockPos origin = self.blockPosition();

        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-16, -3, -16),
                origin.offset(16, 3, 16))) {

            BlockEntity be = level.getBlockEntity(pos.immutable());
            if (!(be instanceof ChestBlockEntity chest)) continue;

            if (!hasSpace(chest, stack)) continue;

            pendingDepositChest = pos.immutable();
            pendingDepositStack = stack.copyWithCount(1);
            self.getNavigation().moveTo(pos.getX(), pos.getY(), pos.getZ(), 0.5f);
            //System.out.println("[DepositHandler] Yendo a depositar en " + pos);
            return true;
        }

        //System.out.println("[DepositHandler] No se encontró cofre disponible");
        return false;
    }

    /**
     * Lógica de depósito incremental que se ejecuta cada tick.
     *
     * <p>Si el aldeano no ha llegado aún al cofre, refuerza el pathfinding para evitar
     * que el Brain cancele la ruta. Una vez junto al cofre, deposita 1 ítem cada
     * 10 ticks hasta vaciar el stack o hasta que el cofre se llene.
     *
     * <p>Si el cofre desaparece mientras el aldeano camina, el depósito se cancela
     * limpiamente con {@link #clearDeposit()}.
     *
     * @param self  el aldeano
     * @param level el nivel de servidor
     */
    private void handleDeposit(Villager self, ServerLevel level) {
        if (pendingDepositChest == null || pendingDepositStack.isEmpty()) return;

        // Si no ha llegado, refuerza el path por si el navegador lo canceló
        if (!self.blockPosition().closerThan(pendingDepositChest, 2.5)) {
            self.getNavigation().moveTo(
                    pendingDepositChest.getX(),
                    pendingDepositChest.getY(),
                    pendingDepositChest.getZ(),
                    0.5f
            );
            return;
        }

        if (depositTickCooldown > 0) {
            depositTickCooldown--;
            return;
        }
        depositTickCooldown = 10; // 1 ítem cada 10 ticks (0.5 segundos)

        BlockEntity be = level.getBlockEntity(pendingDepositChest);
        if (!(be instanceof ChestBlockEntity chest)) {
            // El cofre desapareció mientras el aldeano caminaba
            clearDeposit();
            return;
        }

        ItemStack single = pendingDepositStack.copyWithCount(1);
        ItemStack remaining = addToContainer(chest, single);

        if (remaining.isEmpty()) {
            pendingDepositStack.shrink(1);
            //System.out.println("[DepositHandler] Depositó 1x " + single.getItem().getDescriptionId()+ " — quedan " + pendingDepositStack.getCount());

            // Abre el cofre visualmente y programa el cierre
            level.blockEvent(pendingDepositChest, chest.getBlockState().getBlock(), 1, 1);
            chestClosePos = pendingDepositChest;
            chestCloseTick = level.getGameTime() + 40;
        } else {
            // El cofre se llenó a mitad — abandonamos
            //System.out.println("[DepositHandler] Cofre lleno, quedan " + pendingDepositStack.getCount());
            clearDeposit();
        }

        if (pendingDepositStack.isEmpty()) {
            pendingDepositChest = null;
            //System.out.println("[DepositHandler] Depósito completado");
        }
    }

    /**
     * Cierra visualmente el cofre enviando el {@code blockEvent} de cierre (parámetro 0)
     * cuando el gameTime alcanza {@link #chestCloseTick}.
     *
     * <p>Se llama antes de {@link #handleDeposit} para que el cierre no se solape con
     * la animación de apertura del siguiente ciclo.
     *
     * @param self  el aldeano (no usado directamente, incluido por consistencia)
     * @param level el nivel de servidor
     */
    private void handleChestClose(Villager self, ServerLevel level) {
        if (chestClosePos == null || chestCloseTick < 0) return;
        if (level.getGameTime() < chestCloseTick) return;

        level.blockEvent(chestClosePos, level.getBlockState(chestClosePos).getBlock(), 1, 0);
        chestClosePos = null;
        chestCloseTick = -1;
    }

    /**
     * Resetea el estado del depósito activo, limpiando posición y stack pendiente.
     * Se llama cuando el cofre desaparece o se llena antes de terminar el depósito.
     */
    private void clearDeposit() {
        pendingDepositChest = null;
        pendingDepositStack = ItemStack.EMPTY;
    }

    /**
     * Comprueba si un cofre tiene al menos un slot libre o un slot apilable
     * compatible con {@code stack}.
     *
     * @param chest el cofre a examinar
     * @param stack el ítem que se quiere insertar
     * @return {@code true} si hay espacio disponible
     */
    private boolean hasSpace(ChestBlockEntity chest, ItemStack stack) {
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack slot = chest.getItem(i);
            if (slot.isEmpty()) return true;
            if (ItemStack.isSameItem(slot, stack) && slot.getCount() < slot.getMaxStackSize()) return true;
        }
        return false;
    }

    /**
     * Inserta {@code stack} en {@code container} intentando primero apilar sobre slots
     * existentes del mismo tipo y luego usando slots vacíos.
     *
     * @param container el inventario destino
     * @param stack     el stack a insertar (se opera sobre una copia interna)
     * @return el remanente que no pudo insertarse; {@link ItemStack#EMPTY} si todo
     *         se insertó correctamente
     */
    private ItemStack addToContainer(Container container, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty()) {
                container.setItem(i, remaining.copy());
                remaining = ItemStack.EMPTY;
            } else if (ItemStack.isSameItem(slot, remaining) && slot.getCount() < slot.getMaxStackSize()) {
                int toAdd = Math.min(slot.getMaxStackSize() - slot.getCount(), remaining.getCount());
                slot.grow(toAdd);
                remaining.shrink(toAdd);
            }
        }
        return remaining;
    }
}