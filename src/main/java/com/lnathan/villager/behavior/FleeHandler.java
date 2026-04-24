package com.lnathan.villager.behavior;

import com.lnathan.villager.VillagerState;
import com.lnathan.villager.VillagerDataSync;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;

/**
 * Gestiona la huida del aldeano cuando detecta un jugador con reputación negativa.
 *
 * <p>La reputación se lee directamente con {@link Villager#getPlayerReputation(Player)}.
 * Cuando cae por debajo de {@code -20} (lo que ocurre, por ejemplo, tras golpear al
 * aldeano), este handler calcula la dirección opuesta al jugador y mueve al aldeano
 * 10 bloques en esa dirección a velocidad ligeramente superior a la normal.
 *
 * <p>Un cooldown de 40 ticks (2 segundos) impide que la huida se reevalúe en cada tick,
 * lo que evitaría que el aldeano reaccione a nuevos eventos mientras ya está corriendo.
 *
 * <p><b>Nota:</b> Si el aldeano está en estado {@link VillagerState#CARTOGRAPHER_MIGRATING},
 * el handler se salta por completo para no interferir con la migración en curso.
 */
public class FleeHandler {

    /**
     * Ticks restantes de cooldown tras una huida. Mientras sea mayor que 0 el handler
     * no evalúa nuevas huidas, independientemente de la reputación del jugador.
     */
    private int fleeCooldown = 0;

    /**
     * Evaluado cada tick de servidor. Comprueba si el jugador visible más cercano
     * (obtenido de la memoria {@link MemoryModuleType#NEAREST_VISIBLE_ATTACKABLE_PLAYER}
     * del Brain) tiene reputación negativa suficiente para activar la huida.
     *
     * <p>No actúa si:
     * <ul>
     *   <li>El aldeano está migrando ({@link VillagerState#CARTOGRAPHER_MIGRATING}).</li>
     *   <li>El cooldown está activo.</li>
     *   <li>No hay ningún jugador visible.</li>
     *   <li>La reputación del jugador es {@code >= -20}.</li>
     * </ul>
     *
     * @param self  el aldeano que puede huir
     * @param level el nivel de servidor donde ocurre la evaluación
     */
    public void tick(Villager self, ServerLevel level) {
        if (((VillagerDataSync) self).getVillagerState() == VillagerState.CARTOGRAPHER_MIGRATING) return;

        if (fleeCooldown > 0) {
            fleeCooldown--;
            return;
        }

        // El Brain ya calcula el jugador más cercano solo leemos la memoria
        Optional<Player> nearestPlayer = self.getBrain()
                .getMemory(MemoryModuleType.NEAREST_VISIBLE_ATTACKABLE_PLAYER);

        if (nearestPlayer.isEmpty()) return;

        Player player = nearestPlayer.get();
        int reputation = self.getPlayerReputation(player);

        if (reputation < -20) {
            flee(self, player);
            fleeCooldown = 40;
        }
    }

    /**
     * Calcula el vector unitario opuesto al jugador y ordena al aldeano moverse
     * 10 bloques en esa dirección.
     *
     * <p>La velocidad de navegación es {@code 0.6f}, ligeramente por encima del
     * andar normal ({@code 0.5f}).
     *
     * <p>Si el aldeano y el jugador están en la misma posición exacta (distancia 0),
     * no se realiza ningún movimiento para evitar una división por cero.
     *
     * @param villager el aldeano que huye
     * @param player   el jugador del que se huye
     */
    private void flee(Villager villager, Player player) {
        double dx = villager.getX() - player.getX();
        double dz = villager.getZ() - player.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length == 0) return;

        double nx = dx / length;
        double nz = dz / length;

        // 0.6f es ligeramente más rápido que el andar normal (0.5f)
        villager.getNavigation().moveTo(
                villager.getX() + nx * 10,
                villager.getY(),
                villager.getZ() + nz * 10,
                0.6f
        );
    }
}