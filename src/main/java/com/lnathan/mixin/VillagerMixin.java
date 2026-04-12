package com.lnathan.mixin;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.core.BlockPos;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
//Clase de comportamiento del aldeano
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


    @Inject(at = @At("TAIL"), method = "customServerAiStep")
    private void onHungerTick(ServerLevel level, CallbackInfo ci) {
        Villager self = (Villager)(Object) this;

        if (!self.wantsMoreFood()) return;
        if (self.tickCount % 200 != 0) return;

        boolean isCartographer = self.getVillagerData().profession()
                .is(VillagerProfession.CARTOGRAPHER);

        if (isCartographer) {
            Optional<GlobalPos> myMeeting = self.getBrain()
                    .getMemory(MemoryModuleType.MEETING_POINT);

            PoiManager poiManager = level.getPoiManager();
            poiManager.findAll(
                    poiType -> poiType.is(net.minecraft.tags.PoiTypeTags.VILLAGE),
                    pos -> {
                        // Ignorar su campana actual
                        boolean notMyBell = myMeeting.isEmpty() ||
                                !pos.equals(myMeeting.get().pos());
                        // Más de 100 bloques usando coordenadas directas
                        double dx = pos.getX() - self.getBlockX();
                        double dz = pos.getZ() - self.getBlockZ();
                        boolean farEnough = (dx * dx + dz * dz) > 100 * 100;
                        return notMyBell && farEnough;
                    },
                    self.blockPosition(),
                    2000,
                    PoiManager.Occupancy.ANY
            ).findFirst().ifPresent(bellPos -> {
                self.getBrain().eraseMemory(MemoryModuleType.MEETING_POINT);
                self.getBrain().setActiveActivityIfPossible(Activity.IDLE);
                self.getNavigation().moveTo(
                        bellPos.getX(), bellPos.getY(), bellPos.getZ(), 0.6f
                );
                System.out.println("Cartógrafo va a otra aldea: " + bellPos);
            });
        } else {
            // Los demás van a su JOB_SITE (composter, etc.)
            Optional<GlobalPos> jobSite = self.getBrain()
                    .getMemory(MemoryModuleType.JOB_SITE);

            jobSite.ifPresent(pos -> {
                self.getNavigation().moveTo(pos.pos().getX(), pos.pos().getY(), pos.pos().getZ(), 0.5f);
                System.out.println("Aldeano hambriento va a su trabajo: " + pos.pos());
            });
        }
    }

    @Unique
    private boolean wasHungry = false;

    @Inject(at = @At("TAIL"), method = "customServerAiStep")
    private void onHungerSpeed(ServerLevel level, CallbackInfo ci) {
        Villager self = (Villager)(Object) this;
        boolean hungry = self.wantsMoreFood();

        // Solo actualizamos si cambió el estado para no hacerlo cada tick
        if (hungry == wasHungry) return;
        wasHungry = hungry;

        AttributeInstance speedAttr = self.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speedAttr == null) return;

        if (hungry) {
            // Añadimos un modificador que reduce la velocidad 30%
            speedAttr.addOrUpdateTransientModifier(new AttributeModifier(
                    Identifier.fromNamespaceAndPath("mod", "hunger_slow"),
                    -0.4,
                    AttributeModifier.Operation.ADD_VALUE
            ));
            System.out.println("Aldeano hambriento, va más lento");
        } else {
            // Quitamos el modificador cuando ya no tiene hambre
            speedAttr.removeModifier(
                    Identifier.fromNamespaceAndPath("mod", "hunger_slow")
            );
            System.out.println("Aldeano satisfecho, velocidad normal");
        }
    }
}
