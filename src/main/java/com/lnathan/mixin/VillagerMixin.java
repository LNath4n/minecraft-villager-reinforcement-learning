package com.lnathan.mixin;
import com.lnathan.villager.VillagerDataSync;
import com.lnathan.villager.VillagerState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.schedule.Activity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.resources.Identifier;
import java.util.Optional;

// Clase de comportamiento del aldeano
// Gestiona huida, migración del cartógrafo y hambre en un solo @Inject
@Mixin(Villager.class)
public class VillagerMixin {

    // Cooldown de cada cuanto puede volver a huir el aldeano
    @Unique private int fleeCooldown = 0;

    // Destino de migración del cartógrafo
    @Unique private BlockPos migrationTarget = null;
    // Evita aplicar el boost de velocidad cada tick — solo se aplica una vez
    @Unique private boolean migrationSpeedApplied = false;
    // Caché del jugador más cercano para no buscarlo cada tick
    @Unique private int playerCheckCooldown = 0;
    @Unique private Player cachedNearestPlayer = null;
    // Tick hasta el que el cartógrafo no puede volver a migrar (cooldown de 2 días)
    @Unique private long migrationCooldownUntil = 0;

    // Guarda si el aldeano tenía hambre el tick anterior
    // Sirve como diff para no modificar atributos cada tick
    @Unique private boolean wasHungry = false;
    // Offset aleatorio para distribuir el tick de hambre entre aldeanos
    // Evita que todos evalúen al mismo tiempo al cargar el mundo
    @Unique private int hungerOffset = -1;

    // Único punto de entrada — todo el comportamiento corre desde aquí
    @Inject(at = @At("TAIL"), method = "customServerAiStep")
    private void onTick(ServerLevel level, CallbackInfo ci) {
        Villager self = (Villager)(Object) this;

        handleMigration(self, level);
        handleFlee(self, level);
        handleHunger(self, level);
    }

    // Fuerza el path del cartógrafo cada tick hacia su destino
    // Bloquea huida y hambre mientras está en camino
    @Unique
    private void handleMigration(Villager self, ServerLevel level) {
        if (((VillagerDataSync) self).getVillagerState() != VillagerState.CARTOGRAPHER_MIGRATING
                || migrationTarget == null) return;

        // Recalculamos el jugador cercano una vez por segundo (20 ticks)
        // para no saturar la búsqueda en el nivel
        if (playerCheckCooldown <= 0) {
            cachedNearestPlayer = level.getNearestPlayer(self, 128);
            playerCheckCooldown = 20;
        } else {
            playerCheckCooldown--;
        }

        // Si el aldeano está fuera del rango de visión del jugador, lo teletransportamos
        // directamente al destino en vez de hacerlo caminar
        if (cachedNearestPlayer == null || self.distanceToSqr(cachedNearestPlayer) > 128 * 128) {
            self.teleportTo(migrationTarget.getX(), migrationTarget.getY(), migrationTarget.getZ());
            cleanMigration(self);
            return;
        }

        // Aplicamos el boost de velocidad solo la primera vez que entra aquí
        if (!migrationSpeedApplied) {
            AttributeInstance speedAttr = self.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speedAttr != null) {
                speedAttr.addOrUpdateTransientModifier(new AttributeModifier(
                        Identifier.fromNamespaceAndPath("mod", "migration_speed"),
                        1.5,
                        AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL
                ));
            }
            migrationSpeedApplied = true;
        }

        // Forzamos IDLE y borramos WALK_TARGET para que el Brain no interrumpa el path
        self.getBrain().setActiveActivityIfPossible(Activity.IDLE);
        self.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

        // Navegamos en pasos de 20 bloques — el pathfinder no puede calcular
        // rutas muy largas de una sola vez, así que avanzamos poco a poco
        double dx = migrationTarget.getX() - self.getX();
        double dz = migrationTarget.getZ() - self.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);

        if (length > 20) {
            double nx = dx / length;
            double nz = dz / length;
            self.getNavigation().moveTo(
                    self.getX() + nx * 20,
                    self.getY(),
                    self.getZ() + nz * 20,
                    0.6f
            );
        } else {
            // Ya estamos cerca — apuntamos directo al destino
            self.getNavigation().moveTo(
                    migrationTarget.getX(), self.getY(), migrationTarget.getZ(), 0.6f
            );
        }

        // Cuando llegó a menos de 10 bloques del destino, limpiamos todo
        if (self.blockPosition().closerThan(migrationTarget, 10)) {
            cleanMigration(self);
        }
    }

    // Quita el modificador de velocidad, resetea el estado y
    // aplica el cooldown de 2 días antes de poder migrar de nuevo
    @Unique
    private void cleanMigration(Villager self) {
        AttributeInstance speedAttr = self.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speedAttr != null) {
            speedAttr.removeModifier(Identifier.fromNamespaceAndPath("mod", "migration_speed"));
        }
        migrationTarget = null;
        migrationSpeedApplied = false;
        playerCheckCooldown = 0;
        cachedNearestPlayer = null;
        ((VillagerDataSync) self).setVillagerState(VillagerState.NORMAL);

        // 1 día en Minecraft = 24000 ticks, 2 días = 48000
        migrationCooldownUntil = ((Villager)(Object) this).level().getGameTime() + 48000;
        System.out.println("Cartógrafo descansando hasta tick: " + migrationCooldownUntil);
    }

    // Evalúa si el aldeano debe huir del jugador basándose en reputación
    // No actúa mientras el cartógrafo está migrando
    @Unique
    private void handleFlee(Villager self, ServerLevel level) {
        if (((VillagerDataSync) self).getVillagerState() == VillagerState.CARTOGRAPHER_MIGRATING) return;

        if (fleeCooldown > 0) {
            fleeCooldown--;
            return;
        }

        // El Brain ya calcula el jugador más cercano — solo leemos la memoria
        // en vez de recalcularla nosotros
        Optional<Player> nearestPlayer = self.getBrain()
                .getMemory(MemoryModuleType.NEAREST_VISIBLE_ATTACKABLE_PLAYER);

        if (nearestPlayer.isEmpty()) return;

        Player player = nearestPlayer.get();

        // getPlayerReputation() suma todos los tipos de gossip del jugador
        // Negativo = el jugador ha hecho cosas malas (golpear, matar aldeanos)
        int reputation = self.getPlayerReputation(player);

        // Un golpe al aldeano ya basta para bajar la reputación por debajo de -20
        if (reputation < -20) {
            flee(self, player);
            fleeCooldown = 40;
        }
    }

    // Calcula la dirección opuesta al jugador y mueve el aldeano 10 bloques en esa dirección
    @Unique
    private void flee(Villager villager, Player player) {
        double dx = villager.getX() - player.getX();
        double dz = villager.getZ() - player.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length == 0) return; // evitar división por cero

        double nx = dx / length;
        double nz = dz / length;

        // 0.6f es ligeramente más rápido que el andar normal (0.5f)
        // para que la huida sea perceptible
        villager.getNavigation().moveTo(
                villager.getX() + nx * 10,
                villager.getY(),
                villager.getZ() + nz * 10,
                0.6f
        );
    }

    // Gestiona el hambre — modifica velocidad y decide a dónde navegar
    @Unique
    private void handleHunger(Villager self, ServerLevel level) {
        boolean hungry = self.wantsMoreFood();

        // Solo modificamos el atributo de velocidad cuando cambia el estado
        // Modificarlo cada tick es innecesariamente costoso
        if (hungry != wasHungry) {
            wasHungry = hungry;
            AttributeInstance speedAttr = self.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speedAttr != null) {
                if (hungry) {
                    // Reducimos velocidad 40% cuando tiene hambre
                    speedAttr.addOrUpdateTransientModifier(new AttributeModifier(
                            Identifier.fromNamespaceAndPath("mod", "hunger_slow"),
                            -0.4,
                            AttributeModifier.Operation.ADD_VALUE
                    ));
                } else {
                    speedAttr.removeModifier(Identifier.fromNamespaceAndPath("mod", "hunger_slow"));
                    ((VillagerDataSync) self).setVillagerState(VillagerState.NORMAL);
                }
            }
        }

        if (!hungry) return;

        // Asignamos un offset aleatorio la primera vez para que cada aldeano
        // evalúe el hambre en un tick distinto y no saturen el servidor juntos
        if (hungerOffset == -1) hungerOffset = self.getRandom().nextInt(200);
        if ((self.tickCount + hungerOffset) % 200 != 0) return;

        // No reevaluamos el hambre si ya está en medio de una migración
        if (((VillagerDataSync) self).getVillagerState() == VillagerState.CARTOGRAPHER_MIGRATING) return;

        boolean isCartographer = self.getVillagerData().profession()
                .is(VillagerProfession.CARTOGRAPHER);

        if (isCartographer) {
            // No migra si aún está en el cooldown de 2 días
            if (self.level().getGameTime() < migrationCooldownUntil) return;

            Optional<GlobalPos> myMeeting = self.getBrain()
                    .getMemory(MemoryModuleType.MEETING_POINT);

            // Buscamos una campana de otra aldea a más de 100 bloques
            level.getPoiManager().findAll(
                    poiType -> poiType.is(net.minecraft.tags.PoiTypeTags.VILLAGE),
                    pos -> {
                        // Ignoramos la campana de su aldea actual
                        boolean notMyBell = myMeeting.isEmpty() ||
                                !pos.equals(myMeeting.get().pos());
                        double dx = pos.getX() - self.getBlockX();
                        double dz = pos.getZ() - self.getBlockZ();
                        boolean farEnough = (dx * dx + dz * dz) > 100 * 100;
                        return notMyBell && farEnough;
                    },
                    self.blockPosition(),
                    2000,
                    PoiManager.Occupancy.ANY
            ).findFirst().ifPresent(bellPos -> {
                migrationTarget = bellPos;
                // Borramos memorias aquí — una sola vez al iniciar la migración
                self.getBrain().eraseMemory(MemoryModuleType.MEETING_POINT);
                self.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                ((VillagerDataSync) self).setVillagerState(VillagerState.CARTOGRAPHER_MIGRATING);
                System.out.println("Cartógrafo va a otra aldea: " + bellPos);
            });
        } else {
            // Aldeanos normales van a su JOB_SITE (composter, mesa de trabajo, etc.)
            ((VillagerDataSync) self).setVillagerState(VillagerState.HUNGRY);
            self.getBrain().getMemory(MemoryModuleType.JOB_SITE).ifPresent(pos -> {
                self.getNavigation().moveTo(
                        pos.pos().getX(), pos.pos().getY(), pos.pos().getZ(), 0.5f
                );
                System.out.println("Aldeano hambriento va a su trabajo: " + pos.pos());
            });
        }
    }
}