// File: BlanketCobbleSpawners.kt
package com.blanketcobblespawners

import com.blanketcobblespawners.utils.*
import com.blanketcobblespawners.utils.ConfigManager.logDebug
import com.blanketcobblespawners.utils.ParticleUtils.activeVisualizations
import com.blanketcobblespawners.utils.ParticleUtils.visualizationInterval
import com.blanketcobblespawners.utils.ParticleUtils.visualizeSpawnerPositions
import com.blanketcobblespawners.utils.gui.GuiManager
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.stats.Stat
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.Pokemon
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.block.Blocks
import net.minecraft.block.SlabBlock
import net.minecraft.block.StairsBlock
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.Identifier
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.random.Random
import net.minecraft.world.World
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.math.min
import kotlin.math.roundToInt
import net.minecraft.registry.BuiltinRegistries
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

object BlanketCobbleSpawners : ModInitializer {

	private val logger = LoggerFactory.getLogger("blanketcobblespawners")
	val random = Random.create()
	private val battleTracker = BattleTracker()
	private val catchingTracker = CatchingTracker()
	val spawnerValidPositions = ConcurrentHashMap<BlockPos, List<BlockPos>>()

	override fun onInitialize() {
		logger.info("Initializing BlanketCobbleSpawners")
		ConfigManager.loadSpawnerData()
		CommandRegistrar.registerCommands()
		CommandRegistrar.registerEntityClickEvent()
		battleTracker.registerEvents()
		catchingTracker.registerEvents()
		ServerLifecycleEvents.SERVER_STARTED.register { server ->
			for (spawnerData in ConfigManager.spawners.values) {
				val randomOffset = random.nextBetween(0, 5).toLong()
				val firstWorld = server.overworld
				val lastSpawnTick = ConfigManager.getLastSpawnTick(spawnerData.spawnerPos)
				ConfigManager.updateLastSpawnTick(
					spawnerData.spawnerPos,
					firstWorld.time + randomOffset + lastSpawnTick
				)
			}
		}
		ServerLifecycleEvents.SERVER_STOPPING.register { server ->
			for (spawnerData in ConfigManager.spawners.values) {
				if (ConfigManager.configData.globalConfig.cullSpawnerPokemonOnServerStop) {
					val registryKey = parseDimension(spawnerData.dimension)
					val serverWorld = server.getWorld(registryKey)
					serverWorld?.let {
						SpawnerUUIDManager.getUUIDsForSpawner(spawnerData.spawnerPos).forEach { uuid ->
							val entity = serverWorld.getEntity(uuid)
							if (entity is PokemonEntity) {
								entity.discard()
								SpawnerUUIDManager.removePokemon(uuid)
								logDebug("Despawned Pokémon with UUID $uuid from spawner at ${spawnerData.spawnerPos}")
							}
						}
					}
				}
			}
		}
		ServerTickEvents.END_SERVER_TICK.register { server ->
			for (spawnerData in ConfigManager.spawners.values) {
				val registryKey = parseDimension(spawnerData.dimension)
				val serverWorld = server.getWorld(registryKey)
				if (serverWorld == null) {
					logger.error("World '$registryKey' not found for spawner at ${spawnerData.spawnerPos}")
					continue
				}
				val currentTick = serverWorld.time
				val lastSpawnTick = ConfigManager.getLastSpawnTick(spawnerData.spawnerPos)

				// **Add cleanup of dead entities before checking spawn counts**
				SpawnerUUIDManager.cleanupStaleEntriesForSpawner(serverWorld, spawnerData.spawnerPos)

				// Check if enough time has passed since last spawn
				if (currentTick - lastSpawnTick > spawnerData.spawnTimerTicks) {
					val currentCount = SpawnerUUIDManager.getUUIDsForSpawner(spawnerData.spawnerPos).size
					if (currentCount < spawnerData.spawnLimit) {
						logDebug("Spawning Pokémon at spawner '${spawnerData.spawnerName}'.")
						spawnPokemon(serverWorld, spawnerData)
						ConfigManager.updateLastSpawnTick(spawnerData.spawnerPos, currentTick)
					} else {
						logDebug("Spawn limit reached for spawner '${spawnerData.spawnerName}'. No spawn.")
						// **Update lastSpawnTick even when spawn limit is reached**
						ConfigManager.updateLastSpawnTick(spawnerData.spawnerPos, currentTick)
					}
				}

				val playersToRemove = mutableListOf<UUID>()
				activeVisualizations.forEach { (playerUUID, pair) ->
					val player = server.playerManager.getPlayer(playerUUID) ?: run {
						playersToRemove.add(playerUUID)
						return@forEach
					}
					val (spawnerPos, lastTick) = pair
					val spawnerlocationData = ConfigManager.spawners[spawnerPos] ?: run {
						playersToRemove.add(playerUUID)
						return@forEach
					}
					if (currentTick - lastTick >= visualizationInterval) {
						visualizeSpawnerPositions(player, spawnerlocationData)
						activeVisualizations[playerUUID] = spawnerPos to currentTick
					}
				}
				playersToRemove.forEach { activeVisualizations.remove(it) }
			}
		}

		// Register the new periodic cleanup callback (handled within the tick event)
		registerCallbacks()
	}

	private fun registerCallbacks() {
		registerUseBlockCallback()
		registerBlockBreakCallback()
	}

	private fun registerUseBlockCallback() {
		UseBlockCallback.EVENT.register { player, world, hand, hitResult ->
			if (player is ServerPlayerEntity && hand == Hand.MAIN_HAND && hitResult is BlockHitResult) {
				val blockPos = hitResult.blockPos
				val blockState = world.getBlockState(blockPos)
				if (blockState.block == Blocks.SPAWNER && ConfigManager.spawners.containsKey(blockPos)) {
					if (player.hasPermissionLevel(4)) {
						GuiManager.openSpawnerGui(player, blockPos)
						return@register ActionResult.SUCCESS
					} else {
						player.sendMessage(Text.literal("You don't have permission to manage this spawner."), false)
					}
				} else {
					val itemInHand = player.getStackInHand(hand)
					if (itemInHand.item == Items.SPAWNER && itemInHand.nbt?.getString("CustomSpawner") == "true") {
						val blockPosToPlace = hitResult.blockPos.offset(hitResult.side)
						val blockAtPlacement = world.getBlockState(blockPosToPlace)
						if (blockAtPlacement.isAir || blockAtPlacement.block.defaultState.isReplaceable) {
							placeCustomSpawner(player, world, blockPosToPlace, itemInHand)
							return@register ActionResult.SUCCESS
						}
					}
				}
			}
			ActionResult.PASS
		}
	}

	private fun registerBlockBreakCallback() {
		PlayerBlockBreakEvents.BEFORE.register { world, player, blockPos, blockState, _ ->
			if (world is ServerWorld && blockState.block == Blocks.SPAWNER) {
				if (ConfigManager.removeSpawner(blockPos)) {
					SpawnerUUIDManager.clearPokemonForSpawner(blockPos)
					spawnerValidPositions.remove(blockPos)
					player.sendMessage(Text.literal("Custom spawner removed at $blockPos."), false)
					logDebug("Custom spawner removed at $blockPos.")
				}
			} else if (world is ServerWorld) {
				invalidatePositionsIfWithinRadius(world, blockPos)
			}
			true
		}
	}

	private fun invalidatePositionsIfWithinRadius(world: World, changedBlockPos: BlockPos) {
		for (spawnerPos in ConfigManager.spawners.keys) {
			val spawnerData = ConfigManager.spawners[spawnerPos] ?: continue
			val distanceSquared = spawnerPos.getSquaredDistance(changedBlockPos)
			val maxDistanceSquared = (spawnerData.spawnRadius.width * spawnerData.spawnRadius.width).toDouble()
			if (distanceSquared <= maxDistanceSquared) {
				spawnerValidPositions.remove(spawnerPos)
				logDebug("Invalidated cached spawn positions for spawner at $spawnerPos due to block change at $changedBlockPos")
			}
		}
	}

	private fun placeCustomSpawner(
		player: ServerPlayerEntity,
		world: World,
		pos: BlockPos,
		itemInHand: ItemStack
	) {
		if (ConfigManager.spawners.containsKey(pos)) {
			player.sendMessage(Text.literal("A spawner already exists at this location!"), false)
			return
		}
		val blockState = world.getBlockState(pos)
		if (blockState.block == Blocks.WATER || blockState.block == Blocks.LAVA) {
			world.setBlockState(pos, Blocks.AIR.defaultState)
		}
		world.setBlockState(pos, Blocks.SPAWNER.defaultState)
		val spawnerName = "spawner_${ConfigManager.spawners.size + 1}"
		val dimensionString = "${world.registryKey.value.namespace}:${world.registryKey.value.path}"
		ConfigManager.spawners[pos] = SpawnerData(
			spawnerPos = pos,
			spawnerName = spawnerName,
			dimension = dimensionString
		)
		spawnerValidPositions.remove(pos)
		ConfigManager.saveSpawnerData()
		player.sendMessage(Text.literal("Custom spawner '$spawnerName' placed at $pos!"), false)
		if (!player.abilities.creativeMode) {
			itemInHand.decrement(1)
		}
	}

	private fun spawnPokemon(serverWorld: ServerWorld, spawnerData: SpawnerData) {
		if (GuiManager.isSpawnerGuiOpen(spawnerData.spawnerPos)) {
			logDebug("GUI is open for spawner at ${spawnerData.spawnerPos}. Skipping spawn.")
			return
		}

		// Get current spawn positions
		val validPositions = spawnerValidPositions.getOrPut(spawnerData.spawnerPos) {
			val positions = computeValidSpawnPositions(serverWorld, spawnerData)
			if (positions.isEmpty()) {
				val retryPositions = computeValidSpawnPositions(serverWorld, spawnerData)
				if (retryPositions.isEmpty()) {
					logger.error("No valid spawn positions found for spawner at ${spawnerData.spawnerPos} after two attempts.")
					emptyList()
				} else retryPositions
			} else positions
		}

		if (validPositions.isEmpty()) {
			logDebug("No suitable spawn position found for spawner at ${spawnerData.spawnerPos}. Skipping spawn.")
			return
		}

		// **Filter Pokémon based on their individual spawn conditions**
		val eligiblePokemon = spawnerData.selectedPokemon.filter { entry ->
			val condition = checkIndividualSpawnConditions(serverWorld, entry)
			if (condition != null) {
				logDebug("Spawn conditions not met for Pokémon '${entry.pokemonName}' at spawner '${spawnerData.spawnerName}': $condition. Skipping spawn.")
				false
			} else {
				true
			}
		}

		if (eligiblePokemon.isEmpty()) {
			logDebug("No eligible Pokémon to spawn for spawner '${spawnerData.spawnerName}'.")
			return
		}

		// Calculate total spawn weight based on eligible Pokémon
		val totalWeight = eligiblePokemon.sumOf { it.spawnChance }
		if (totalWeight <= 0) {
			logger.warn("Total spawn chance is zero or negative for spawner at ${spawnerData.spawnerPos}. Skipping spawn.")
			return
		}

		val currentSpawned = SpawnerUUIDManager.getUUIDsForSpawner(spawnerData.spawnerPos).size
		val maxSpawnable = spawnerData.spawnLimit - currentSpawned
		if (maxSpawnable <= 0) {
			logDebug("Spawn limit reached for spawner '${spawnerData.spawnerName}'. No Pokémon will be spawned.")
			return
		}

		val spawnAmount = min(spawnerData.spawnAmountPerSpawn, maxSpawnable)
		logDebug("Attempting to spawn $spawnAmount Pokémon(s) for spawner at ${spawnerData.spawnerPos}")
		val maxAttemptsPerSpawn = 5
		var totalAttempts = 0
		var spawnedCount = 0

		while (spawnedCount < spawnAmount && totalAttempts < spawnAmount * maxAttemptsPerSpawn) {
			val index = random.nextInt(validPositions.size)
			val spawnPos = validPositions[index]
			if (!serverWorld.isChunkLoaded(spawnPos)) {
				logDebug("Chunk not loaded at spawn position $spawnPos. Skipping spawn.")
				totalAttempts++
				continue
			}

			val randomValue = random.nextDouble() * totalWeight
			var cumulativeWeight = 0.0
			var selectedPokemon: PokemonSpawnEntry? = null
			for (pokemonEntry in eligiblePokemon) {
				cumulativeWeight += pokemonEntry.spawnChance
				if (randomValue <= cumulativeWeight) {
					selectedPokemon = pokemonEntry
					break
				}
			}

			if (selectedPokemon == null) {
				logger.warn("No Pokémon selected for spawning at spawner at ${spawnerData.spawnerPos}")
				totalAttempts++
				continue
			}

			val entry = selectedPokemon
			val sanitizedPokemonName = entry.pokemonName.replace(Regex("[^a-zA-Z0-9]"), "").lowercase()
			val species = PokemonSpecies.getByName(sanitizedPokemonName)
			if (species == null) {
				logger.warn("Species '$sanitizedPokemonName' not found for spawner at ${spawnerData.spawnerPos}")
				totalAttempts++
				continue
			}
			val level = entry.minLevel + random.nextInt(entry.maxLevel - entry.minLevel + 1)
			val isShiny = random.nextDouble() * 100 <= entry.shinyChance
			val propertiesStringBuilder = StringBuilder(sanitizedPokemonName)
			propertiesStringBuilder.append(" level=$level")
			if (isShiny) {
				propertiesStringBuilder.append(" shiny=true")
			}
			if (!entry.formName.isNullOrEmpty() && !entry.formName.equals("normal", ignoreCase = true) && !entry.formName.equals("default", ignoreCase = true)) {
				val normalizedEntryFormName = entry.formName!!.lowercase().replace(Regex("[^a-z0-9]"), "")
				val availableForms = species.forms
				val matchedForm = availableForms.find { form ->
					val normalizedFormId = form.formOnlyShowdownId().lowercase().replace(Regex("[^a-z0-9]"), "")
					normalizedFormId == normalizedEntryFormName
				} ?: run {
					// If no exact match is found, attempt a fuzzy match or default to the normal form
					logger.warn("Form '${entry.formName}' not found for species '${species.name}'. Defaulting to normal form.")
					null
				}
				if (matchedForm != null) {
					if (matchedForm.aspects.isNotEmpty()) {
						for (aspect in matchedForm.aspects) {
							propertiesStringBuilder.append(" ").append("$aspect=true")
						}
					} else {
						propertiesStringBuilder.append(" form=${matchedForm.formOnlyShowdownId()}")
					}
				}
			}

			val properties = PokemonProperties.parse(propertiesStringBuilder.toString())
			val pokemonEntity = properties.createEntity(serverWorld)

			// Set the size of the Pokémon if custom sizes are allowed
			val pokemon = pokemonEntity.pokemon
			if (entry.sizeSettings.allowCustomSize) {
				val minSize = entry.sizeSettings.minSize
				val maxSize = entry.sizeSettings.maxSize
				val sizeModifier = if (minSize >= maxSize) minSize else minSize + random.nextFloat() * (maxSize - minSize)
				pokemon.scaleModifier = roundToOneDecimal(sizeModifier)
				logDebug("Assigned custom size modifier ${pokemon.scaleModifier} to Pokémon '${species.name}'")
			} else {
				//pokemon.scaleModifier = 1.0f
				logDebug("Assigned default size modifier to Pokémon '${species.name}'")
			}

			// Generate IVs if custom IVs are allowed
			if (entry.ivSettings.allowCustomIvs) {
				generateValidIVs(pokemon, entry)
				logDebug("Custom IVs assigned to Pokémon '${species.name}'")
			}

			// Check if held items are allowed on spawn
			if (entry.heldItemsOnSpawn.allowHeldItemsOnSpawn) {
				entry.heldItemsOnSpawn.itemsWithChance.forEach { (itemName, chance) ->
					val itemIdentifier = Identifier.tryParse(itemName)
					if (itemIdentifier != null) {
						// Retrieve the item if the identifier is valid
						val item = Registries.ITEM.get(itemIdentifier)

						// Compare with chance / 100 to represent percentage directly
						if (item != Items.AIR && random.nextDouble() * 100 <= chance) {
							pokemon.swapHeldItem(ItemStack(item))
							logDebug("Assigned held item '$itemName' with a $chance% chance to Pokémon '${species.name}'")
							return@forEach // Exit after assigning the first successful item
						} else if (item == Items.AIR) {
							logDebug("Item '$itemName' is invalid and defaulted to AIR. Skipping.")
						}
					} else {
						logDebug("Invalid item identifier format: '$itemName'. Skipping.")
					}
				}
			} else {
				logDebug("Held items on spawn are disabled for Pokémon '${species.name}'.")
			}

			pokemonEntity.refreshPositionAndAngles(
				spawnPos.x + 0.5,
				spawnPos.y.toDouble(),
				spawnPos.z + 0.5,
				pokemonEntity.yaw,
				pokemonEntity.pitch
			)
			if (serverWorld.spawnEntity(pokemonEntity)) {
				SpawnerUUIDManager.addPokemon(pokemonEntity.uuid, spawnerData.spawnerPos, entry.pokemonName)
				logDebug("Pokémon '${species.name}' spawned with UUID ${pokemonEntity.uuid}")
				spawnedCount++
			} else {
				logger.warn("Failed to spawn Pokémon '${species.name}' at position $spawnPos")
			}
			totalAttempts++
		}
		if (spawnedCount > 0) {
			logDebug("Spawned $spawnedCount Pokémon(s) for spawner at ${spawnerData.spawnerPos}")
		} else {
			logDebug("No Pokémon were spawned for spawner at ${spawnerData.spawnerPos}")
		}
	}


	private fun roundToOneDecimal(value: Float): Float {
		return (value * 10).roundToInt() / 10f
	}

	// Function to generate valid IVs for a Pokémon based on spawn entry with a total IV budget
	private fun generateValidIVs(pokemon: Pokemon, entry: PokemonSpawnEntry, maxIVBudget: Int = 186) {
		// Define IV ranges for each stat based on the entry configuration
		val ivRanges = mapOf(
			Stats.HP to (entry.ivSettings.minIVHp..entry.ivSettings.maxIVHp),
			Stats.ATTACK to (entry.ivSettings.minIVAttack..entry.ivSettings.maxIVAttack),
			Stats.DEFENCE to (entry.ivSettings.minIVDefense..entry.ivSettings.maxIVDefense),
			Stats.SPECIAL_ATTACK to (entry.ivSettings.minIVSpecialAttack..entry.ivSettings.maxIVSpecialAttack),
			Stats.SPECIAL_DEFENCE to (entry.ivSettings.minIVSpecialDefense..entry.ivSettings.maxIVSpecialDefense),
			Stats.SPEED to (entry.ivSettings.minIVSpeed..entry.ivSettings.maxIVSpeed)
		)

		// Calculate the maximum possible IV total if each stat were set to the upper bound of its range
		val maxPossibleIVTotal = ivRanges.values.sumOf { it.last }

		// Calculate the scaling factor based on the max budget if needed
		val scalingFactor = if (maxPossibleIVTotal > maxIVBudget) {
			maxIVBudget.toDouble() / maxPossibleIVTotal
		} else {
			1.0
		}

		// Apply the scaling factor to each stat's range to fit within the budget
		ivRanges.forEach { (stat, range) ->
			val scaledMax = (range.last * scalingFactor).toInt().coerceAtLeast(range.first)
			val scaledRange = range.first..scaledMax

			// Generate a random IV within the dynamically scaled range
			pokemon.ivs[stat] = scaledRange.random()
		}
	}

	private fun checkIndividualSpawnConditions(world: ServerWorld, entry: PokemonSpawnEntry): String? {
		val spawnSettings = entry.spawnSettings

		// **Check time of day for the individual Pokémon**
		if (spawnSettings.spawnTime != "ALL") {
			val timeOfDay = world.timeOfDay % 24000
			val isDay = timeOfDay in 0..12000
			val isNight = timeOfDay in 13000..23000

			when (spawnSettings.spawnTime.uppercase()) {
				"DAY" -> if (!isDay) return "Time of day is ${if (isNight) "NIGHT" else "UNKNOWN"}, but Pokémon requires DAY."
				"NIGHT" -> if (!isNight) return "Time of day is ${if (isDay) "DAY" else "UNKNOWN"}, but Pokémon requires NIGHT."
			}
		}

		// **Check weather for the individual Pokémon**
		if (spawnSettings.spawnWeather != "ALL") {
			val isRaining = world.isRaining
			val isThunderstorm = world.isThundering

			when (spawnSettings.spawnWeather.uppercase()) {
				"CLEAR" -> if (isRaining || isThunderstorm) return "Weather is ${if (isThunderstorm) "THUNDER" else "RAIN"}, but Pokémon requires CLEAR weather."
				"RAIN" -> if (!isRaining || isThunderstorm) return "Weather is ${if (isThunderstorm) "THUNDER" else "CLEAR"}, but Pokémon requires RAIN without thunderstorms."
				"THUNDER" -> if (!isThunderstorm) return "Weather is ${if (isRaining) "RAIN" else "CLEAR"}, but Pokémon requires THUNDER."
			}
		}

		return null // All conditions are met for this Pokémon
	}



	fun computeValidSpawnPositions(serverWorld: ServerWorld, spawnerData: SpawnerData): List<BlockPos> {
		val validPositions = mutableListOf<BlockPos>()
		val spawnRadiusWidth = spawnerData.spawnRadius.width
		val spawnRadiusHeight = spawnerData.spawnRadius.height
		for (offsetX in -spawnRadiusWidth..spawnRadiusWidth) {
			for (offsetY in -spawnRadiusHeight..spawnRadiusHeight) {
				for (offsetZ in -spawnRadiusWidth..spawnRadiusWidth) {
					val potentialPos = spawnerData.spawnerPos.add(offsetX, offsetY, offsetZ)
					if (isPositionSafeForSpawn(serverWorld, potentialPos)) {
						validPositions.add(potentialPos)
					}
				}
			}
		}
		logDebug("Computed ${validPositions.size} valid spawn positions for spawner at ${spawnerData.spawnerPos}")
		return validPositions
	}

	private fun isPositionSafeForSpawn(world: World, spawnPos: BlockPos): Boolean {
		val blockBelowPos = spawnPos.down()
		val blockBelowState = world.getBlockState(blockBelowPos)
		val blockBelow = blockBelowState.block
		val collisionShape = blockBelowState.getCollisionShape(world, blockBelowPos)
		if (collisionShape.isEmpty) {
			return false
		}
		val boundingBox = collisionShape.boundingBox
		val maxY = boundingBox.maxY
		val isSolidEnough = maxY >= 0.9
		if (!blockBelowState.isSideSolidFullSquare(world, blockBelowPos, Direction.UP) &&
			blockBelow !is SlabBlock && blockBelow !is StairsBlock && !isSolidEnough
		) {
			return false
		}
		val blockAtPos = world.getBlockState(spawnPos)
		if (!blockAtPos.isAir && !blockAtPos.getCollisionShape(world, spawnPos).isEmpty) {
			return false
		}
		val blockAbovePos = world.getBlockState(spawnPos.up())
		if (!blockAbovePos.isAir && !blockAbovePos.getCollisionShape(world, spawnPos.up()).isEmpty) {
			return false
		}
		return true
	}

	fun parseDimension(dimensionString: String): RegistryKey<World> {
		val parts = dimensionString.split(":")
		if (parts.size != 2) {
			logger.warn("Invalid dimension format: $dimensionString. Expected 'namespace:path'")
			return RegistryKey.of(RegistryKeys.WORLD, Identifier("minecraft", "overworld"))
		}
		val namespace = parts[0]
		val path = parts[1]
		return RegistryKey.of(RegistryKeys.WORLD, Identifier(namespace, path))
	}

	private fun registryKeyToString(registryKey: RegistryKey<World>): String {
		return "${registryKey.value.namespace}:${registryKey.value.path}"
	}
	fun normalizeName(name: String?): String? {
		return name?.replace(Regex("[^a-zA-Z0-9]"), "")?.lowercase()
	}
}
