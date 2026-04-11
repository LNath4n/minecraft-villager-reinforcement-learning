package com.lnathan.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
@Mixin(Villager.class) //Inyeccion dentro de la clase Villager
public class VillagerMixin {
    // Cooldown de cada cuanto ataca un aldeado espantado
    private int fleeCooldown  = 0;
    @Inject(at = @At("TAIL"), method = "customServerAiStep") //Tail es el final del metodo, osea mi metodo al final
    private void onAiTick(ServerLevel level, CallbackInfo ci) {
        //Level es donde estamos en este momento (Mundo Normal/Nether)
        //CI es de los metadatos de Mixin

        //"this" es el propio villager, pero como estamos en un Mixin
        //necesitamos castearlo para acceder a sus métodos
        Villager self = (Villager)(Object) this;

        if (fleeCooldown  > 0) {
            fleeCooldown --;
            return;
        }


        // Accedemos al cerebro del aldeano
        // El Brain del villager ya tiene una memoria con el jugador más cercano.
        // Solo la leemos — no recalculamos nada.
        Optional<Player> nearestPlayer = self.getBrain()
                .getMemory(MemoryModuleType.NEAREST_VISIBLE_ATTACKABLE_PLAYER); //A su cerebro le sacamos la memoria de el jugador mas carcano

        // Si no hay jugador cerca, no hacemos nada
        if (nearestPlayer.isEmpty()) return;

        Player player = nearestPlayer.get();

        // Consultamos el sistema de gossip vanilla.
        // getPlayerReputation() ya suma todos los tipos de gossip del jugador.
        // Negativo = el jugador ha hecho cosas malas (golpear, matar villagers).
        int reputation = self.getPlayerReputation(player);

        // Umbral si la reputación es peor que -20, el villager huye (Un golpe basta)
        if (reputation < -20) {
            flee(self, player, level);
            fleeCooldown = 40;
        }
    }

    private void flee(Villager villager, Player player, ServerLevel level) {
        // Calculamos la dirección opuesta al jugador
        // Posición del villager menos posición del jugador = vector "alejarse"
        double dx = villager.getX() - player.getX();
        double dz = villager.getZ() - player.getZ();

        // Normalizamos para que la distancia sea siempre la misma sin importar
        // qué tan lejos esté el jugador
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length == 0) return; // evitar división por cero

        double nx = dx / length;
        double nz = dz / length;

        // Punto de destino: 10 bloques en dirección contraria al jugador
        double targetX = villager.getX() + nx * 10;
        double targetZ = villager.getZ() + nz * 10;

        // System.out.println("Estaba en "+villager.getX()+ " y voy hacia "+targetX);
        // Le decimos al pathfinder que vaya hacia ese punto
        // El 0.6f es la velocidad (un poco más rápido que su andar normal (0.5f))
        villager.getNavigation().moveTo(targetX, villager.getY(), targetZ, 0.6f);
    }
}
