package com.lnathan.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.util.List;
import java.util.Set;

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
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import java.util.Optional;

// Clase de comportamiento del aldeano
// Gestiona huida, migración del cartógrafo y hambre en un solo @Inject
@Mixin(Villager.class)
public class VillagerMixin {

    // Cooldown de cada cuanto puede volver a huir el aldeano
    @Unique
    private int fleeCooldown = 0;

    // Destino de migración del cartógrafo
    @Unique
    private BlockPos migrationTarget = null;
    // Evita aplicar el boost de velocidad cada tick — solo se aplica una vez
    @Unique
    private boolean migrationSpeedApplied = false;
    // Caché del jugador más cercano para no buscarlo cada tick
    @Unique
    private int playerCheckCooldown = 0;
    @Unique
    private Player cachedNearestPlayer = null;
    // Tick hasta el que el cartógrafo no puede volver a migrar (cooldown de 2 días)
    @Unique
    private long migrationCooldownUntil = 0;

    // Guarda si el aldeano tenía hambre el tick anterior
    // Sirve como diff para no modificar atributos cada tick
    @Unique
    private boolean wasHungry = false;
    // Offset aleatorio para distribuir el tick de hambre entre aldeanos
    // Evita que todos evalúen al mismo tiempo al cargar el mundo
    @Unique
    private int hungerOffset = -1;
    @Unique
    private SimpleContainer inventory = null;
    @Unique
    private int pickupCooldown = 0;

    @Unique
    private static final Set<net.minecraft.world.item.Item> PICKUP_ITEMS = Set.of(
            Items.OAK_LOG,
            Items.BIRCH_LOG,
            Items.SPRUCE_LOG,
            Items.COBBLESTONE,
            Items.STONE
    );

    // Posición del cofre que hay que cerrar y en qué tick hacerlo
    @Unique
    private BlockPos chestClosePos = null;
    @Unique
    private long chestCloseTick = -1;

    // Stack pendiente de depositar y cofre destino
    // El aldeano tiene que llegar al lado antes de depositar
    @Unique
    private BlockPos pendingDepositChest = null;
    @Unique
    private ItemStack pendingDepositStack = ItemStack.EMPTY;
    // Cooldown entre cada ítem depositado — deposita 1 por 1
    @Unique
    private int depositTickCooldown = 0;

    // Único punto de entrada — todo el comportamiento corre desde aquí
    @Inject(at = @At("TAIL"), method = "customServerAiStep")
    private void onTick(ServerLevel level, CallbackInfo ci) {
        Villager self = (Villager) (Object) this;

        handleChestClose(self, level);
        handleMigration(self, level);
        handleFlee(self, level);
        handleHunger(self, level);
        handlePickup(self, level);
        handleDeposit(self, level);
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
        migrationCooldownUntil = ((Villager) (Object) this).level().getGameTime() + 48000;
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

    // Evalúa si hay ítems recogibles cerca y los recoge
    // Corre cada 40 ticks para no saturar la búsqueda de entidades
    @Unique
    private void handlePickup(Villager self, ServerLevel level) {
        if (pickupCooldown > 0) {
            pickupCooldown--;
            return;
        }
        pickupCooldown = 40;

        // Si ya hay un depósito en curso, no hacer nada más
        if (!pendingDepositStack.isEmpty()) return;

        SimpleContainer inv = getOrCreateInventory();

        // Verificar si el inventario está lleno
        boolean inventoryFull = true;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) {
                inventoryFull = false;
                break;
            }
        }

        if (inventoryFull) {
            // Buscar cofre PRIMERO — si no hay, no tocamos el inventario
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty()) continue;

                boolean found = findNearbyChest(self, level, stack.copy());
                if (found) {
                    inv.getItem(i).shrink(1);
                    System.out.println("[Inventario] Vaciando slot " + i + " hacia cofre");
                } else {
                    System.out.println("[Inventario] Lleno pero no hay cofre cercano, esperando...");
                }
                return; // deposita de a un slot por ciclo
            }
            return; // inventario lleno, sin cofre — no recoger nada
        }

        // --- Inventario tiene espacio: recoger del suelo ---
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

        // Guardar en inventario
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) {
                inv.setItem(i, stack);
                System.out.println("[Pickup] Guardó en inventario slot " + i + ": "
                        + stack.getItem().getDescriptionId() + " x" + stack.getCount());
                break;
            }
        }
    }

    // Solo busca el cofre más cercano y guarda el destino + stack pendiente
    // No deposita todavía — eso lo hace handleDeposit cuando llega al lado
    @Unique
    private boolean findNearbyChest(Villager self, ServerLevel level, ItemStack stack) {
        BlockPos origin = self.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-16, -3, -16),
                origin.offset(16, 3, 16))) {

            BlockEntity be = level.getBlockEntity(pos.immutable());
            if (!(be instanceof ChestBlockEntity chest)) continue;

            boolean hasSpace = false;
            for (int i = 0; i < chest.getContainerSize(); i++) {
                ItemStack slot = chest.getItem(i);
                if (slot.isEmpty() ||
                        (ItemStack.isSameItem(slot, stack) && slot.getCount() < slot.getMaxStackSize())) {
                    hasSpace = true;
                    break;
                }
            }
            if (!hasSpace) continue;

            pendingDepositChest = pos.immutable();
            pendingDepositStack = stack.copyWithCount(1);

            self.getNavigation().moveTo(pos.getX(), pos.getY(), pos.getZ(), 0.5f);
            System.out.println("[Cofre] Yendo a depositar en " + pos);
            return true;
        }

        System.out.println("[Cofre] No se encontró cofre disponible");
        return false;
    }

    // Corre cada tick — espera a que el aldeano esté al lado del cofre
    // y luego deposita 1 ítem cada 10 ticks
    @Unique
    private void handleDeposit(Villager self, ServerLevel level) {
        if (pendingDepositChest == null || pendingDepositStack.isEmpty()) return;

        // Comprueba que está al lado del cofre (distancia ≤ 2.5 bloques)
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

        // Está al lado — espera N ticks entre cada ítem depositado
        if (depositTickCooldown > 0) {
            depositTickCooldown--;
            return;
        }
        depositTickCooldown = 10; // 1 ítem cada 10 ticks (0.5 segundos)

        BlockEntity be = level.getBlockEntity(pendingDepositChest);
        if (!(be instanceof ChestBlockEntity chest)) {
            // El cofre desapareció mientras el aldeano caminaba hacia él
            pendingDepositChest = null;
            pendingDepositStack = ItemStack.EMPTY;
            return;
        }

        // Deposita exactamente 1 unidad del stack pendiente
        ItemStack single = pendingDepositStack.copyWithCount(1);
        ItemStack remaining = addToContainer(chest, single);

        if (remaining.isEmpty()) {
            // El ítem entró — descuenta 1 del stack pendiente
            pendingDepositStack.shrink(1);
            System.out.println("[Cofre] Depositó 1x " + single.getItem().getDescriptionId()
                    + " — quedan " + pendingDepositStack.getCount());

            // Abre el cofre visualmente en cada depósito y programa el cierre
            level.blockEvent(pendingDepositChest, chest.getBlockState().getBlock(), 1, 1);
            chestClosePos = pendingDepositChest;
            chestCloseTick = level.getGameTime() + 40;

        } else {
            // El cofre se llenó a mitad — abandonamos el depósito
            System.out.println("[Cofre] Cofre lleno, quedan " + pendingDepositStack.getCount());
            pendingDepositChest = null;
            pendingDepositStack = ItemStack.EMPTY;
        }

        // Stack agotado — depósito completado
        if (pendingDepositStack.isEmpty()) {
            pendingDepositChest = null;
            System.out.println("[Cofre] Depósito completado");
        }
    }

    // Inserta un ItemStack en cualquier Container (cofre, barril, etc.)
    // Intenta apilar primero, luego slots vacíos
    // Devuelve el remanente — stack vacío significa que todo fue depositado
    @Unique
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

    // Cierra el cofre en el tick programado
    // Evita que el cofre quede abierto si el aldeano se va antes de terminar
    @Unique
    private void handleChestClose(Villager self, ServerLevel level) {
        if (chestClosePos == null || chestCloseTick < 0) return;
        if (level.getGameTime() < chestCloseTick) return;

        level.blockEvent(chestClosePos, level.getBlockState(chestClosePos).getBlock(), 1, 0);
        chestClosePos = null;
        chestCloseTick = -1;
    }

    @Unique
    private SimpleContainer getOrCreateInventory() {
        if (inventory == null) inventory = new SimpleContainer(9);
        return inventory;
    }

    @Inject(method = "mobInteract", at = @At("HEAD"), cancellable = true)
    private void onInteract(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        Villager self = (Villager) (Object) this;

        // Solo abre el inventario con Shift + Click
        if (!player.level().isClientSide() && player.isShiftKeyDown()) {
            player.openMenu(new SimpleMenuProvider(
                    (syncId, playerInv, p) -> new ChestMenu(
                            MenuType.GENERIC_9x1,
                            syncId,
                            playerInv,
                            getOrCreateInventory(),
                            1
                    ),
                    Component.literal("Inventario del Aldeano")
            ));
            cir.setReturnValue(InteractionResult.SUCCESS); // <-- solo cancela si fue shift
        }
        // Si no es shift, deja pasar el evento normal (comercio, etc.)
    }
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void onSave(ValueOutput output, CallbackInfo ci) {
        if (inventory != null) {
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
    }

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

}