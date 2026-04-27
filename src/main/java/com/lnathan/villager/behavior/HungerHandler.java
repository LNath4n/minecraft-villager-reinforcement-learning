package com.lnathan.villager.behavior;

import com.lnathan.villager.VillagerDataSync;
import com.lnathan.villager.VillagerState;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;

import java.util.Optional;

/**
 * Gestiona el sistema de hambre del aldeano y sus consecuencias de comportamiento.
 *
 * <p>Cuando {@link Villager#wantsMoreFood()} devuelve {@code true}, este handler:
 * <ul>
 *   <li>Aplica un modificador de velocidad de {@code -0.4} (reducción del 40%) para
 *       reflejar visualmente el estado de hambre.</li>
 *   <li>Cada 200 ticks (con offset aleatorio para distribuir la carga entre aldeanos),
 *       decide la acción de recuperación según la profesión:
 *     <ul>
 *       <li><b>Cartógrafo hambriento:</b> intenta iniciar una migración hacia la
 *           campana de otra aldea mediante {@link MigrationHandler}.</li>
 *       <li><b>Otros aldeanos:</b> navegan a su {@link MemoryModuleType#JOB_SITE}
 *           (donde el ciclo de trabajo puede proporcionarles comida).</li>
 *     </ul>
 *   </li>
 *   <li>Cuando el hambre se resuelve, elimina el modificador de velocidad y restaura
 *       el estado a {@link VillagerState#NORMAL}.</li>
 * </ul>
 *
 * <p>El modificador de velocidad solo se aplica/elimina cuando el estado de hambre
 * <em>cambia</em> (no cada tick) para minimizar el overhead de atributos.
 *
 * @see MigrationHandler
 */
public class HungerHandler {

    /**
     * Identificador del modificador de atributo de velocidad por hambre.
     * Usar un {@link Identifier} fijo garantiza que siempre se aplica y elimina
     * el mismo modificador sin duplicados.
     */
    private static final Identifier HUNGER_SLOW_ID =
            Identifier.fromNamespaceAndPath("mod", "hunger_slow");

    /**
     * Caché del estado de hambre previo. Evita modificar el atributo de velocidad
     * en cada tick; solo actúa cuando el estado cambia de {@code false} a {@code true}
     * o viceversa.
     */
    private boolean wasHungry = false;

    /**
     * Offset aleatorio de tick para este aldeano concreto, inicializado en el primer
     * tick con hambre. Distribuye la evaluación periódica entre todos los aldeanos
     * del mundo para evitar picos de CPU en el mismo tick.
     * {@code -1} indica que aún no ha sido inicializado.
     */
    private int hungerOffset = -1;

    /**
     * Referencia al handler de migración del cartógrafo. Se consulta para comprobar
     * el cooldown y para iniciar la migración si corresponde.
     */
    private final MigrationHandler migrationHandler;

    /**
     * Construye un nuevo {@code HungerHandler} enlazado al {@link MigrationHandler}
     * del mismo aldeano.
     *
     * @param migrationHandler el handler de migración compartido con este aldeano
     */
    public HungerHandler(MigrationHandler migrationHandler) {
        this.migrationHandler = migrationHandler;
    }

    /**
     * Punto de entrada del tick. Evalúa el estado de hambre, actualiza el modificador
     * de velocidad si es necesario, y cada 200 ticks dispara la acción de recuperación
     * apropiada para la profesión del aldeano.
     *
     * <p>No actúa si el aldeano está en estado {@link VillagerState#CARTOGRAPHER_MIGRATING}
     * para no interrumpir una migración en curso.
     *
     * @param self  el aldeano
     * @param level el nivel de servidor donde reside el aldeano
     */
    public void tick(Villager self, ServerLevel level) {
        boolean hungry = self.wantsMoreFood();

        // Solo modificamos el atributo cuando cambia el estado — no cada tick
        if (hungry != wasHungry) {
            wasHungry = hungry;
            AttributeInstance speedAttr = self.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speedAttr != null) {
                if (hungry) {
                    speedAttr.addOrUpdateTransientModifier(new AttributeModifier(
                            HUNGER_SLOW_ID, -0.4, AttributeModifier.Operation.ADD_VALUE
                    ));
                } else {
                    speedAttr.removeModifier(HUNGER_SLOW_ID);
                    ((VillagerDataSync) self).setVillagerState(VillagerState.NORMAL);
                }
            }
        }

        if (!hungry) return;

        // Offset aleatorio para que cada aldeano evalúe en un tick distinto
        if (hungerOffset == -1) hungerOffset = self.getRandom().nextInt(200);
        if ((self.tickCount + hungerOffset) % 200 != 0) return;

        if (((VillagerDataSync) self).getVillagerState() == VillagerState.CARTOGRAPHER_MIGRATING) return;

        boolean isCartographer = self.getVillagerData().profession()
                .is(VillagerProfession.CARTOGRAPHER);

        if (isCartographer) {
            tryStartMigration(self, level);
        } else {
            navigateToJobSite(self);
        }
    }

    /**
     * Intenta iniciar la migración del cartógrafo hacia otra aldea.
     *
     * <p>Condiciones para que la migración se inicie:
     * <ol>
     *   <li>El cooldown de migración debe haber expirado.</li>
     *   <li>Debe existir un POI de tipo {@code VILLAGE} a más de 100 bloques de
     *       distancia que no sea la campana actual del cartógrafo.</li>
     * </ol>
     *
     * <p>Si se encuentra un objetivo válido, se limpian las memorias
     * {@link MemoryModuleType#MEETING_POINT} y {@link MemoryModuleType#WALK_TARGET}
     * para que el Brain no interfiera, y el estado del aldeano cambia a
     * {@link VillagerState#CARTOGRAPHER_MIGRATING}.
     *
     * @param self  el aldeano cartógrafo
     * @param level el nivel de servidor
     */
    private void tryStartMigration(Villager self, ServerLevel level) {
        if (self.level().getGameTime() < migrationHandler.getMigrationCooldownUntil()) return;

        Optional<GlobalPos> myMeeting = self.getBrain()
                .getMemory(MemoryModuleType.MEETING_POINT);

        level.getPoiManager().findAll(
                poiType -> poiType.is(net.minecraft.tags.PoiTypeTags.VILLAGE),
                pos -> {
                    boolean notMyBell = myMeeting.isEmpty() || !pos.equals(myMeeting.get().pos());
                    double dx = pos.getX() - self.getBlockX();
                    double dz = pos.getZ() - self.getBlockZ();
                    boolean farEnough = (dx * dx + dz * dz) > 100 * 100;
                    return notMyBell && farEnough;
                },
                self.blockPosition(),
                2000,
                PoiManager.Occupancy.ANY
        ).findFirst().ifPresent(bellPos -> {
            migrationHandler.startMigration(self, bellPos);
            self.getBrain().eraseMemory(MemoryModuleType.MEETING_POINT);
            self.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            ((VillagerDataSync) self).setVillagerState(VillagerState.CARTOGRAPHER_MIGRATING);
            //System.out.println("[HungerHandle] Cartógrafo va a otra aldea: " + bellPos);
        });
    }

    /**
     * Envía al aldeano hambriento (no cartógrafo) hacia su sitio de trabajo registrado
     * en la memoria {@link MemoryModuleType#JOB_SITE}.
     *
     * <p>El sitio de trabajo es donde el ciclo de profesión puede proporcionarle comida.
     * Si la memoria no existe (aldeano sin profesión), no ocurre ningún movimiento.
     *
     * @param self el aldeano hambriento
     */
    private void navigateToJobSite(Villager self) {
        ((VillagerDataSync) self).setVillagerState(VillagerState.HUNGRY);
        self.getBrain().getMemory(MemoryModuleType.JOB_SITE).ifPresent(pos -> {
            self.getNavigation().moveTo(
                    pos.pos().getX(), pos.pos().getY(), pos.pos().getZ(), 0.5f
            );
            //System.out.println("[HungerHandle] Aldeano hambriento va a su trabajo: " + pos.pos());
        });
    }
}