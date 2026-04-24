package com.lnathan.villager.behavior;

import com.lnathan.villager.VillagerDataSync;
import com.lnathan.villager.VillagerState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.schedule.Activity;

/**
 * Gestiona la migración del cartógrafo hacia otra aldea cuando tiene hambre.
 *
 * <p>La migración es iniciada por {@link HungerHandler} al detectar que el aldeano
 * es cartógrafo y su cooldown ha expirado. Una vez activa, este handler toma el
 * control de la navegación hasta que el aldeano llega al destino.
 *
 * <h3>Comportamiento durante la migración</h3>
 * <ul>
 *   <li><b>Boost de velocidad:</b> se aplica un multiplicador {@code +1.5×} la primera
 *       vez que el handler toma el control, para que el cartógrafo llegue con fluidez
 *       sin repetir el seteo cada tick.</li>
 *   <li><b>Pathfinding incremental:</b> el pathfinder de Minecraft no puede calcular
 *       rutas de cientos de bloques. Por eso se avanza en tramos de 20 bloques hacia
 *       el destino final.</li>
 *   <li><b>Teletransporte fuera de rango:</b> si ningún jugador está a menos de
 *       128 bloques del cartógrafo, se teletransporta directamente al destino para
 *       no malgastar ciclos de CPU en pathfinding invisible.</li>
 *   <li><b>Supresión del Brain:</b> en cada tick se fuerza la actividad {@link Activity#IDLE}
 *       y se borra {@link MemoryModuleType#WALK_TARGET} para que el Brain no cancele
 *       la ruta de migración con sus propias tareas.</li>
 * </ul>
 *
 * <h3>Fin de la migración</h3>
 * <p>Cuando el aldeano entra en un radio de 10 bloques del destino (o se teletransporta),
 * {@link #cleanMigration} elimina el boost, restaura el estado a
 * {@link VillagerState#NORMAL} y aplica un cooldown de 48 000 ticks (2 días de juego)
 * para evitar migraciones continuas.
 *
 * @see HungerHandler
 */
public class MigrationHandler {

    /**
     * Identificador del modificador de velocidad de migración.
     * Usar un {@link Identifier} fijo garantiza que el modificador se aplica una sola
     * vez y puede eliminarse de forma fiable al finalizar la migración.
     */
    private static final Identifier MIGRATION_SPEED_ID = Identifier.fromNamespaceAndPath("mod", "migration_speed");

    /**
     * Posición objetivo de la migración (campana de la aldea destino).
     * {@code null} cuando no hay migración activa.
     */
    private BlockPos migrationTarget = null;

    /**
     * Indica si el boost de velocidad ya fue aplicado para la migración actual.
     * Evita añadir el modificador en cada tick; se resetea al terminar la migración.
     */
    private boolean migrationSpeedApplied = false;

    /**
     * Ticks restantes hasta la próxima actualización del jugador cercano cacheado.
     * Se recalcula cada 20 ticks (1 segundo) para balancear precisión y rendimiento.
     */
    private int playerCheckCooldown = 0;

    /**
     * Jugador más cercano cacheado para este ciclo de 20 ticks.
     * {@code null} si no hay ningún jugador en un radio de 128 bloques.
     */
    private Player cachedNearestPlayer = null;

    /**
     * GameTime a partir del cual el cartógrafo puede volver a migrar.
     * Corresponde a 48 000 ticks (2 días de juego) desde el fin de la última migración.
     */
    private long migrationCooldownUntil = 0;

    /**
     * Punto de entrada del tick. Solo actúa si el aldeano está en estado
     * {@link VillagerState#CARTOGRAPHER_MIGRATING} y hay un destino registrado.
     *
     * <p>Cada llamada realiza (en orden):
     * <ol>
     *   <li>Refresco del jugador cercano cacheado (cada 20 ticks).</li>
     *   <li>Teletransporte directo si el cartógrafo está fuera del rango de visión
     *       de todos los jugadores.</li>
     *   <li>Aplicación del boost de velocidad (primera vez solamente).</li>
     *   <li>Supresión del Brain para evitar cancelaciones de ruta.</li>
     *   <li>Avance incremental de 20 bloques hacia el destino.</li>
     *   <li>Comprobación de llegada y limpieza si el cartógrafo está a ≤10 bloques.</li>
     * </ol>
     *
     * @param self  el aldeano cartógrafo en migración
     * @param level el nivel de servidor
     */
    public void tick(Villager self, ServerLevel level) {
        if (((VillagerDataSync) self).getVillagerState() != VillagerState.CARTOGRAPHER_MIGRATING || migrationTarget == null)
            return;

        // Recalculamos el jugador cercano una vez por segundo (20 ticks)
        if (playerCheckCooldown <= 0) {
            cachedNearestPlayer = level.getNearestPlayer(self, 128);
            playerCheckCooldown = 20;
        } else {
            playerCheckCooldown--;
        }

        // Si el aldeano está fuera del rango de visión, lo teletransportamos directamente
        if (cachedNearestPlayer == null || self.distanceToSqr(cachedNearestPlayer) > 128 * 128) {
            self.teleportTo(migrationTarget.getX(), migrationTarget.getY(), migrationTarget.getZ());
            cleanMigration(self);
            return;
        }

        // Aplicamos el boost de velocidad solo la primera vez
        if (!migrationSpeedApplied) {
            AttributeInstance speedAttr = self.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speedAttr != null) {
                speedAttr.addOrUpdateTransientModifier(new AttributeModifier(MIGRATION_SPEED_ID, 1.5, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
            migrationSpeedApplied = true;
        }

        // Forzamos IDLE y borramos WALK_TARGET para que el Brain no interrumpa el path
        self.getBrain().setActiveActivityIfPossible(Activity.IDLE);
        self.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

        // Navegamos en pasos de 20 bloques — el pathfinder no puede calcular
        // rutas muy largas de una sola vez
        double dx = migrationTarget.getX() - self.getX();
        double dz = migrationTarget.getZ() - self.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);

        if (length > 20) {
            double nx = dx / length;
            double nz = dz / length;
            self.getNavigation().moveTo(self.getX() + nx * 20, self.getY(), self.getZ() + nz * 20, 0.6f);
        } else {
            self.getNavigation().moveTo(migrationTarget.getX(), self.getY(), migrationTarget.getZ(), 0.6f);
        }

        if (self.blockPosition().closerThan(migrationTarget, 10)) {
            cleanMigration(self);
        }
    }

    /**
     * Registra el destino de la migración. Llamado por {@link HungerHandler} al
     * confirmar que existe una aldea válida a la que migrar.
     *
     * @param self   el aldeano (no usado aquí, incluido para coherencia con otros métodos)
     * @param target la posición de la campana de la aldea destino
     */
    public void startMigration(Villager self, BlockPos target) {
        this.migrationTarget = target;
    }

    /**
     * Devuelve el GameTime a partir del cual el cartógrafo puede volver a migrar.
     * {@link HungerHandler} consulta este valor antes de intentar una nueva migración.
     *
     * @return tick de fin de cooldown; {@code 0} si nunca ha migrado
     */
    public long getMigrationCooldownUntil() {
        return migrationCooldownUntil;
    }

    /**
     * Finaliza la migración activa: elimina el boost de velocidad, resetea todas las
     * variables internas, restaura el estado del aldeano a {@link VillagerState#NORMAL}
     * y aplica un cooldown de 48 000 ticks (2 días de juego) antes de permitir la
     * siguiente migración.
     *
     * @param self el aldeano cuya migración ha terminado
     */
    private void cleanMigration(Villager self) {
        AttributeInstance speedAttr = self.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speedAttr != null) {
            speedAttr.removeModifier(MIGRATION_SPEED_ID);
        }
        migrationTarget = null;
        migrationSpeedApplied = false;
        playerCheckCooldown = 0;
        cachedNearestPlayer = null;
        ((VillagerDataSync) self).setVillagerState(VillagerState.NORMAL);

        // 1 día = 24000 ticks, 2 días = 48000
        migrationCooldownUntil = self.level().getGameTime() + 48000;
        //System.out.println("[MigrationHandler] Cartógrafo descansando hasta tick: " + migrationCooldownUntil);
    }
}