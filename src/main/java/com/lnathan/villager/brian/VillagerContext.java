package com.lnathan.villager.brian;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Set;

/**
 * Contexto situacional del aldeano — evaluado una vez por tick en el DQNNode.
 *
 * <p>Cada flag es independiente: varios pueden estar activos al mismo tiempo.
 * Eso permite que el sistema de reward penalice o premie combinaciones específicas
 * (ej. FLEE mientras combat=true y hungry=true es correcto; IDLE en esa misma
 * situación debería ser castigado por ambos flags).</p>
 *
 * <p>El método {@link #toFlagArray()} exporta los 5 flags como floats 0/1
 * listos para concatenar al state vector del DQN.</p>
 *
 * <h3>Flags actuales</h3>
 * <ul>
 *   <li>{@link #combat}       — hay al menos un Monster en radio 16</li>
 *   <li>{@link #hungry}       — {@code wantsMoreFood()} es true</li>
 *   <li>{@link #inventoryFull}— todos los slots del mod (8+) están ocupados</li>
 *   <li>{@link #night}        — reloj del overworld > 13000</li>
 *   <li>{@link #wasHurtRecently} — el tracker de daño reporta evento reciente</li>
 * </ul>
 *
 * <p>Para agregar un flag nuevo: añade el campo public boolean, inclúyelo en
 * {@link #toFlagArray()} y actualiza INPUT_SIZE en {@link VillagerBrain}.</p>
 */
public class VillagerContext {

    //  Flags 

    /** Hay al menos un Monster en radio 16. */
    public final boolean combat;

    /** El aldeano quiere más comida ({@code wantsMoreFood()}). */
    public final boolean hungry;

    /** Todos los slots del mod (8+) están ocupados. */
    public final boolean inventoryFull;

    /** El reloj del overworld supera 13 000 (equivale a noche). */
    public final boolean night;

    /** El tracker de daño reporta un hit en los últimos 60 ticks. */
    public final boolean wasHurtRecently;

    //  Items de comida conocidos 

    private static final Set<Item> FOOD_ITEMS = Set.of(
            Items.BREAD, Items.APPLE, Items.CARROT, Items.POTATO
    );

    //  Constructor privado — usar build() 

    private VillagerContext(boolean combat, boolean hungry, boolean inventoryFull,
                            boolean night, boolean wasHurtRecently) {
        this.combat            = combat;
        this.hungry            = hungry;
        this.inventoryFull     = inventoryFull;
        this.night             = night;
        this.wasHurtRecently   = wasHurtRecently;
    }

    //  Factory 

    /**
     * Evalúa el estado del mundo y construye el contexto para este tick.
     *
     * @param self        el aldeano
     * @param level       el nivel servidor
     * @param inventory   inventario del mod (slots 8+)
     * @param hurtTracker tracker de daño del aldeano
     * @return contexto listo para pasarle al DQN
     */
    public static VillagerContext build(Villager self, ServerLevel level,
                                        SimpleContainer inventory,
                                        VillagerHurtTracker hurtTracker,
                                        List<Monster> nearbyMonsters) {
        boolean combat = !nearbyMonsters.isEmpty();


        boolean hungry = self.wantsMoreFood();

        boolean invFull = isInventoryFull(inventory);

        boolean night = (level.getOverworldClockTime() % 24000) > 13000;

        boolean hurt = hurtTracker.wasHurtRecently() == 1.0f;

        if (combat || hungry || invFull || night || hurt) {/*
            System.out.println("[Ctx " + self.getUUID().toString().substring(0, 8) + "] " +
                    (combat    ? "COMBAT "  : "") +
                    (hungry    ? "HUNGRY " : "") +
                    (invFull   ? "INV_FULL " : "") +
                    (night     ? "NIGHT "  : "") +
                    (hurt      ? "HURT "   : "")
            );*/
        }

        return new VillagerContext(combat, hungry, invFull, night, hurt);
    }

    //  Export al state vector 

    /**
     * Exporta los flags como array de floats 0.0/1.0, en el mismo orden que
     * están declarados arriba. Este array se concatena al state vector base
     * en {@link VillagerBrain#buildStateVector}.
     *
     * <p>Orden: [combat, hungry, inventoryFull, night, wasHurtRecently]</p>
     *
     * @return float[5] con los flags codificados
     */
    public float[] toFlagArray() {
        return new float[]{
                combat          ? 1.0f : 0.0f,
                hungry          ? 1.0f : 0.0f,
                inventoryFull   ? 1.0f : 0.0f,
                night           ? 1.0f : 0.0f,
                wasHurtRecently ? 1.0f : 0.0f
        };
    }

    /**
     * Cuántos flags están activos simultáneamente.
     * Útil para escalar penalizaciones en el cálculo de reward.
     */
    public int activeFlags() {
        int count = 0;
        if (combat)          count++;
        if (hungry)          count++;
        if (inventoryFull)   count++;
        if (night)           count++;
        if (wasHurtRecently) count++;
        return count;
    }

    //  Helper privado 

    private static boolean isInventoryFull(SimpleContainer inv) {
        for (int i = 8; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) return false;
        }
        return true;
    }

    //  Debug 

    @Override
    public String toString() {
        return "[combat=" + combat +
                " hungry=" + hungry +
                " invFull=" + inventoryFull +
                " night=" + night +
                " hurt=" + wasHurtRecently + "]";
    }
}
