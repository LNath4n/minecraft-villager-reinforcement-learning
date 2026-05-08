package com.lnathan;

import com.lnathan.network.*;
import com.lnathan.village.VillageDetector;
import com.lnathan.village.VillageRegistry;
import com.lnathan.world.ModWorldGen;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server-side mod initializer.
 *
 * <p>Registers all server-side systems during {@link #onInitialize()}:
 * <ul>
 *   <li>Procedural world generation ({@link ModWorldGen}).</li>
 *   <li>All network packet types and their server-side handlers.</li>
 *   <li>Per-tick quest progress checks for all nearby villagers.</li>
 *   <li>Village detection ticks for each connected player.</li>
 * </ul>
 *
 * <h3>Packet handlers registered here</h3>
 * <ul>
 *   <li>{@link QuestResponsePacket} — player accepted, declined, or turned in a quest.</li>
 *   <li>{@link RequestJournalPacket} — player opened the journal; server replies with
 *       {@link JournalDataPacket} containing all active quest entries nearby.</li>
 *   <li>{@link RequestMapPacket} — player opened the map; server replies with
 *       {@link MapDataPacket} containing village positions, journal entries, and
 *       rendered map colors centered on the player.</li>
 * </ul>
 */
public class Mod implements ModInitializer {

	/** Fabric mod ID used for resource keys and logging. */
	public static final String MOD_ID = "mod";

	/** Shared logger for all server-side mod systems. */
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/**
	 * Called once by Fabric when the mod is initialized on the server.
	 * Registers world generation, network packets, and tick listeners.
	 */
	@Override
	public void onInitialize() {
		System.out.print("Hello Fabric world!");
		ModWorldGen.register();

		OpenQuestPacket.register();
		QuestResponsePacket.register();
		RequestJournalPacket.register();
		JournalDataPacket.register();
		VillageTitlePacket.register();
		RequestMapPacket.register();
		MapDataPacket.register();

		// QuestResponsePacket — player accepted, declined, or turned in a quest.
		// Resolves the target villager by UUID and delegates to LockableVillager methods.
		ServerPlayNetworking.registerGlobalReceiver(QuestResponsePacket.TYPE, (payload, context) -> {
			context.server().execute(() -> {
				ServerPlayer player = context.player();
				ServerLevel level = (ServerLevel) player.level();
				UUID uuid = UUID.fromString(payload.villagerUuid());

				level.getEntities(EntityType.VILLAGER, v -> v.getUUID().equals(uuid))
						.stream().findFirst().ifPresent(villager -> {
							LockableVillager lv = (LockableVillager) villager;

							if (!payload.accepted()) {
								lv.lnathan$unlock();
								return;
							}

							if (payload.questState().equals("READY_TO_TURN_IN")) {
								lv.lnathan$turnInQuest(player);
							} else if (payload.questState().equals("AVAILABLE")) {
								lv.lnathan$acceptQuest(player);
							} else {
								lv.lnathan$unlock();
							}
						});
			});
		});

		// RequestJournalPacket — player opened the journal.
		// Collects active quest entries from all LockableVillagers within 256 blocks
		// and sends them back as a JournalDataPacket.
		ServerPlayNetworking.registerGlobalReceiver(RequestJournalPacket.TYPE, (payload, context) -> {
			context.server().execute(() -> {
				ServerPlayer player = context.player();
				ServerLevel level = (ServerLevel) player.level();

				List<String> entries = new ArrayList<>();
				level.getEntitiesOfClass(
						Villager.class,
						player.getBoundingBox().inflate(256)
				).forEach(villager -> {
					if (villager instanceof LockableVillager lv) {
						String entry = lv.lnathan$getActiveQuestEntry();
						if (entry != null) entries.add(entry);
					}
				});

				ServerPlayNetworking.send(player, new JournalDataPacket(entries));
			});
		});

		// RequestMapPacket — player opened the map.
		// Collects village data, journal entries, and renders a 128×128 map
		// centered on the player, then sends everything as a MapDataPacket.
		ServerPlayNetworking.registerGlobalReceiver(RequestMapPacket.TYPE, (payload, context) -> {
			context.server().execute(() -> {
				ServerPlayer player = context.player();
				ServerLevel level = (ServerLevel) player.level();

				List<String> villageEntries = VillageRegistry.getVillages(player).stream()
						.map(v -> v.getName() + "|" + v.getCenter().getX() + "|" + v.getCenter().getZ())
						.toList();

				List<String> journalEntries = new ArrayList<>();
				level.getEntitiesOfClass(
						Villager.class,
						player.getBoundingBox().inflate(256)
				).forEach(villager -> {
					if (villager instanceof LockableVillager lv) {
						String entry = lv.lnathan$getActiveQuestEntry();
						if (entry != null) journalEntries.add(entry);
					}
				});

				// Render a 128×128 map centered on the player by reading block map colors
				// from the heightmap. Only loaded chunks are sampled.
				byte[] mapColors = new byte[128 * 128];
				try {
					int scale = 1;
					int centerX = player.blockPosition().getX();
					int centerZ = player.blockPosition().getZ();
					int halfSize = 64 * (1 << scale);

					int nonZero = 0;
					int loaded = 0;
					for (int px = 0; px < 128; px++) {
						for (int pz = 0; pz < 128; pz++) {
							int worldX = centerX + (px - 64) * (1 << scale);
							int worldZ = centerZ + (pz - 64) * (1 << scale);

							if (!level.isLoaded(new net.minecraft.core.BlockPos(worldX, 0, worldZ))) continue;
							loaded++;

							int worldY = level.getHeight(
									net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
									worldX, worldZ
							) - 1;

							net.minecraft.world.level.block.state.BlockState state =
									level.getBlockState(new net.minecraft.core.BlockPos(worldX, worldY, worldZ));

							int colorId = state.getMapColor(level,
									new net.minecraft.core.BlockPos(worldX, worldY, worldZ)).id;
							mapColors[pz * 128 + px] = (byte)(colorId * 4 + 2);
							if (colorId > 0) nonZero++;
						}
					}
					LOGGER.info("Loaded: " + loaded + " | Con color: " + nonZero + " | center=" + (mapColors[8192] & 0xFF));
				} catch (Exception e) {
					LOGGER.warn("Error: " + e.getMessage());
					e.printStackTrace();
				}

				ServerPlayNetworking.send(player, new MapDataPacket(
						villageEntries,
						player.blockPosition().getX(),
						player.blockPosition().getZ(),
						journalEntries,
						mapColors
				));
			});
		});

		// Per-tick quest progress check for all players.
		// Iterates over every LockableVillager within 256 blocks of each player
		// and also advances the village detection system.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				ServerLevel level = (ServerLevel) player.level();

				level.getEntitiesOfClass(
						Villager.class,
						player.getBoundingBox().inflate(256)
				).forEach(villager -> {
					if (villager instanceof LockableVillager lv) {
						lv.lnathan$checkQuestProgress(player, level);
					}
				});

				VillageDetector.tick(player);
			}
		});
	}
}