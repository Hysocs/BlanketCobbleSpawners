package com.blanketcobblespawners

import com.blanketcobblespawners.utils.*
import com.blanketcobblespawners.utils.ConfigManager.logDebug
import com.blanketcobblespawners.utils.Gui.GuiManager

import com.blanketcobblespawners.utils.ParticleUtils.activeVisualizations
import com.blanketcobblespawners.utils.ParticleUtils.spawnMonParticles
import com.blanketcobblespawners.utils.ParticleUtils.spawnSpawnerParticles
import com.blanketcobblespawners.utils.ParticleUtils.visualizationInterval
import com.blanketcobblespawners.utils.ParticleUtils.visualizeSpawnerPositions
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.stats.Stat
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.properties.UncatchableProperty
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.block.Blocks
import net.minecraft.block.SlabBlock
import net.minecraft.block.StairsBlock
import net.minecraft.block.enums.BlockHalf
import net.minecraft.block.enums.SlabType
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import net.minecraft.util.math.random.Random
import net.minecraft.world.World
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.util.Identifier
import net.minecraft.util.math.Box
import org.slf4j.LoggerFactory
import kotlin.math.ceil

object BlanketCobbleSpawners : ModInitializer {

	private val logger = LoggerFactory.getLogger("blanketcobblespawners")
	val random = Random.create()

	private val battleTracker = BattleTracker()
	private val catchingTracker = CatchingTracker()

	// Cache for storing valid spawn positions per spawner
	val spawnerValidPositions = mutableMapOf<BlockPos, List<BlockPos>>()


	override fun onInitialize() {
		logger.info("Initializing BlanketCobbleSpawners")
		ConfigManager.loadSpawnerData()
		CommandRegistrar.registerCommands()

		battleTracker.registerEvents()
		catchingTracker.registerEvents()

		// Stagger spawner timers after server has started
		ServerLifecycleEvents.SERVER_STARTED.register { server ->
			for (spawnerData in ConfigManager.spawners.values) {
				val randomOffset = random.nextBetween(0, 5).toLong()
				val firstWorld = server.overworld
				// Load last spawn tick from external storage
				val lastSpawnTick = ConfigManager.getLastSpawnTick(spawnerData.spawnerPos)
				ConfigManager.updateLastSpawnTick(
					spawnerData.spawnerPos,
					firstWorld.time + randomOffset + lastSpawnTick
				)
			}
		}

		// Clean up Pokémon on server stop if `cullSpawnerPokemonOnServerStop` is true
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

// Inside the tick event where spawns are handled
		ServerTickEvents.END_SERVER_TICK.register { server ->
			for (spawnerData in ConfigManager.spawners.values) {
				val registryKey = parseDimension(spawnerData.dimension)
				val serverWorld = server.getWorld(registryKey)
				if (serverWorld == null) {
					logger.error("World '$registryKey' not found for spawner at ${spawnerData.spawnerPos}")
					continue
				}

				val currentTick = serverWorld.time
				var shouldSpawn = false

				// Clean up despawned Pokémon
				for (uuid in SpawnerUUIDManager.getUUIDsForSpawner(spawnerData.spawnerPos)) {
					val entity = serverWorld.getEntity(uuid)
					if (entity == null || !entity.isAlive || (entity is PokemonEntity && !entity.pokemon.isWild())) {
						SpawnerUUIDManager.removePokemon(uuid)
						// Instead of spawning instantly, reset the spawn timer
						ConfigManager.updateLastSpawnTick(spawnerData.spawnerPos, currentTick)
						logDebug("Removed UUID $uuid for spawner at ${spawnerData.spawnerPos} and reset the spawn timer.")
					}
				}

				// Retrieve last spawn tick
				val lastSpawnTick = ConfigManager.getLastSpawnTick(spawnerData.spawnerPos)

				// Check if it's time to spawn new Pokémon, but skip if limit is reached
				if (currentTick - lastSpawnTick > spawnerData.spawnTimerTicks) {
					if (spawnerData.selectedPokemon.isNotEmpty()) {
						if (SpawnerUUIDManager.updateSpawnerCount(spawnerData.spawnerPos, spawnerData.spawnLimit)) {
							logDebug("Spawning Pokémon at spawner '${spawnerData.spawnerName}'.")
							spawnPokemon(serverWorld, spawnerData)
							// Update spawn timer with delay for next cycle
							val newLastSpawnTick = currentTick + random.nextBetween(1, 3).toLong()
							ConfigManager.updateLastSpawnTick(spawnerData.spawnerPos, newLastSpawnTick)
						} else {
							// If limit is reached, just update the timer for the next cycle
							ConfigManager.updateLastSpawnTick(spawnerData.spawnerPos, currentTick + spawnerData.spawnTimerTicks)
						}
					}
				}

				// Handle visualizations, particles, etc.
				activeVisualizations.forEach { (player, pair) ->
					val (spawnerPos, lastTick) = pair
					val spawnerlocationData = ConfigManager.spawners[spawnerPos] ?: return@forEach

					// Check if the time interval has passed
					if (currentTick - lastTick >= visualizationInterval) {
						// Resend particles and update last tick
						visualizeSpawnerPositions(player, spawnerlocationData)
						activeVisualizations[player] = spawnerPos to currentTick
					}
				}
			}
		}


		registerCallbacks()
	}

	private fun registerCallbacks() {
		// Register block placement, breaking, and other interactions
		registerUseBlockCallback()
		registerBlockBreakCallback()
		// You may need to register additional callbacks for block updates
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
				val spawnerData = ConfigManager.spawners[blockPos]
				if (spawnerData != null) {
					ConfigManager.spawners.remove(blockPos)
					ConfigManager.saveSpawnerData()
					SpawnerUUIDManager.clearPokemonForSpawner(blockPos)
					spawnerValidPositions.remove(blockPos) // Invalidate cached positions
					player.sendMessage(Text.literal("Custom spawner removed at $blockPos."), false)
					logDebug("Custom spawner removed at $blockPos.")
				}
			} else if (world is ServerWorld) {
				// Invalidate cached positions if a block within any spawner's radius is broken
				invalidatePositionsIfWithinRadius(world, blockPos)
			}
			true
		}
	}

	private fun invalidatePositionsIfWithinRadius(world: ServerWorld, changedBlockPos: BlockPos) {
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
		// Check if there's already a spawner (visible or invisible) at the position
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

		ConfigManager.saveSpawnerData()
		player.sendMessage(Text.literal("Custom spawner '$spawnerName' placed at $pos!"), false)

		if (!player.abilities.creativeMode) {
			itemInHand.decrement(1)
		}
	}


	private fun spawnPokemon(serverWorld: ServerWorld, spawnerData: SpawnerData) {
		if (!serverWorld.isChunkLoaded(spawnerData.spawnerPos.x shr 4, spawnerData.spawnerPos.z shr 4)) {
			logDebug("Chunk not loaded for spawner at ${spawnerData.spawnerPos}. Skipping spawn.")
			return
		}

		if (GuiManager.isSpawnerGuiOpen(spawnerData.spawnerPos)) {
			logDebug("Spawner GUI is open at ${spawnerData.spawnerPos}. Skipping spawn.")
			return
		}

		val currentWorldTime = serverWorld.timeOfDay % 24000
		val isDayTime = currentWorldTime in 0..12000
		val isNightTime = currentWorldTime in 12001..23999
		val isRaining = serverWorld.isRaining
		val isThundering = serverWorld.isThundering

		// Filter Pokémon based on both spawnTime and spawnWeather settings
		val validEntries = spawnerData.selectedPokemon.filter { entry ->
			val isValidTime = when (entry.spawnSettings.spawnTime.uppercase()) {
				"DAY" -> isDayTime
				"NIGHT" -> isNightTime
				else -> true // BOTH
			}

			val isValidWeather = when (entry.spawnSettings.spawnWeather.uppercase()) {
				"CLEAR" -> !isRaining && !isThundering
				"RAIN" -> isRaining && !isThundering
				"THUNDER" -> isThundering
				else -> true // ALL
			}

			isValidTime && isValidWeather
		}

		if (validEntries.isEmpty()) {
			logDebug("No valid Pokémon to spawn based on time and weather for spawner at ${spawnerData.spawnerPos}.")
			return
		}

		// Get valid spawn positions
		val validPositions = if (spawnerValidPositions.containsKey(spawnerData.spawnerPos)) {
			logDebug("Using cached valid spawn positions for spawner at ${spawnerData.spawnerPos}")
			spawnerValidPositions[spawnerData.spawnerPos]!!
		} else {
			logDebug("Computing valid spawn positions for spawner at ${spawnerData.spawnerPos}")
			val positions = computeValidSpawnPositions(serverWorld, spawnerData)
			spawnerValidPositions[spawnerData.spawnerPos] = positions
			positions
		}

		if (validPositions.isEmpty()) {
			logDebug("No suitable spawn position found for spawner at ${spawnerData.spawnerPos}. Skipping spawn.")
			return
		}

		val totalWeight = validEntries.sumOf { it.spawnChance }
		if (totalWeight <= 0) {
			logger.warn("Total spawn chance is zero or negative for spawner at ${spawnerData.spawnerPos}. Skipping spawn.")
			return
		}

		var attempts = 0
		val maxAttempts = 10

		while (attempts < maxAttempts) {
			val index = random.nextInt(validPositions.size)
			val spawnPos = validPositions[index]

			val randomValue = random.nextDouble() * totalWeight
			var cumulativeWeight = 0.0
			var selectedPokemon: PokemonSpawnEntry? = null

			for (pokemonEntry in validEntries) {
				cumulativeWeight += pokemonEntry.spawnChance
				if (randomValue <= cumulativeWeight) {
					selectedPokemon = pokemonEntry
					break
				}
			}

			if (selectedPokemon == null) {
				logger.warn("No Pokémon selected for spawning at spawner at ${spawnerData.spawnerPos}")
				return
			}

			val entry = selectedPokemon

			// Sanitize the Pokémon name by removing all non-alphanumeric characters
			val sanitizedPokemonName = entry.pokemonName
				.replace(Regex("[^a-zA-Z0-9]"), "")  // Keep only alphanumeric characters
				.lowercase()  // Convert the name to lowercase as Minecraft requires

			val species = PokemonSpecies.getByName(sanitizedPokemonName)

			if (species == null) {
				logger.warn("Species '$sanitizedPokemonName' not found for spawner at ${spawnerData.spawnerPos}")
				return
			}


			val level = entry.minLevel + random.nextInt(entry.maxLevel - entry.minLevel + 1)
			val isShiny = random.nextDouble() * 100 <= entry.shinyChance

			val pokemon = Pokemon().apply {
				this.species = species
				this.level = level
				this.shiny = isShiny
				this.initialize()
			}

			if (entry.ivSettings.allowCustomIvs) {
				generateValidIVs(pokemon, entry)
				logDebug("Assigned custom IVs to Pokémon '${species.name}': ${pokemon.ivs}")
			}

			if (!entry.captureSettings.isCatchable) {
				UncatchableProperty.uncatchable().apply(pokemon)
			}

			val form = pokemon.form
			val hitboxWidth = form.hitbox.width
			val hitboxHeight = form.hitbox.height

			if (!isPositionSafeForPokemon(serverWorld, spawnPos, hitboxWidth, hitboxHeight)) {
				logDebug("Spawn position at $spawnPos is not safe for Pokémon '${species.name}' due to size constraints.")
				attempts++
				continue
			}

			logDebug("Spawning Pokémon '${species.name}' at level $level at position $spawnPos (Shiny: $isShiny)")
			val pokemonEntity = pokemon.sendOut(serverWorld, Vec3d.ofCenter(spawnPos), null)
			pokemonEntity?.let {
				SpawnerUUIDManager.addPokemon(it.uuid, spawnerData.spawnerPos, entry.pokemonName)
				logDebug("Pokémon '${species.name}' spawned with UUID ${it.uuid}")
				if (spawnerData.showParticles) { // Check if particles should be shown
					spawnSpawnerParticles(serverWorld, spawnerData.spawnerPos)
					spawnMonParticles(serverWorld, spawnPos)
				}
			}
			return
		}

		logDebug("Failed to find a safe spawn position after $maxAttempts attempts for spawner at ${spawnerData.spawnerPos}")
	}




	fun computeValidSpawnPositions(serverWorld: ServerWorld, spawnerData: SpawnerData): List<BlockPos> {
		val validPositions = mutableListOf<BlockPos>()
		val spawnRadiusWidth = spawnerData.spawnRadius.width
		val spawnRadiusHeight = spawnerData.spawnRadius.height

		for (offsetX in -spawnRadiusWidth..spawnRadiusWidth) {
			for (offsetY in -spawnRadiusHeight..spawnRadiusHeight) {
				for (offsetZ in -spawnRadiusWidth..spawnRadiusWidth) {
					val potentialPos = spawnerData.spawnerPos.add(offsetX, offsetY, offsetZ)

					// Use the new helper function to check if the position is safe for spawning
					if (isPositionSafeForSpawn(serverWorld, potentialPos)) {
						validPositions.add(potentialPos)
					}
				}
			}
		}

		logDebug("Computed ${validPositions.size} valid spawn positions for spawner at ${spawnerData.spawnerPos}")
		return validPositions
	}






	private fun isPositionSafeForPokemon(world: World, spawnPos: BlockPos, width: Float, height: Float): Boolean {
		val halfWidth = width / 2.0
		val boundingBox = Box(
			spawnPos.x - halfWidth, spawnPos.y.toDouble(), spawnPos.z - halfWidth,
			spawnPos.x + halfWidth, (spawnPos.y + height).toDouble(), spawnPos.z + halfWidth
		)

		// Iterate through blocks that the bounding box overlaps with
		val blockPositions = BlockPos.iterate(
			boundingBox.minX.toInt(), boundingBox.minY.toInt(), boundingBox.minZ.toInt(),
			boundingBox.maxX.toInt(), boundingBox.maxY.toInt(), boundingBox.maxZ.toInt()
		)

		for (blockPos in blockPositions) {
			val blockState = world.getBlockState(blockPos)
			val collisionShape = blockState.getCollisionShape(world, blockPos)

			// Check if the block has any non-empty collision shape that intersects the Pokémon's bounding box
			if (!collisionShape.isEmpty) {
				for (box in collisionShape.boundingBoxes) {
					if (box.offset(blockPos.x.toDouble(), blockPos.y.toDouble(), blockPos.z.toDouble()).intersects(boundingBox)) {
						return false
					}
				}
			}
		}

		// If no collisions found, the position is safe for spawning
		return true
	}





	private fun isPositionSafeForSpawn(world: World, spawnPos: BlockPos): Boolean {
		val blockBelowPos = spawnPos.down()
		val blockBelowState = world.getBlockState(blockBelowPos)
		val blockBelow = blockBelowState.block

		// Get the collision shape of the block below
		val collisionShape = blockBelowState.getCollisionShape(world, blockBelowPos)

		// Check if the shape is empty before trying to get the bounding box
		if (collisionShape.isEmpty) {
			// If the shape is empty, the block is not solid, return false
			return false
		}

		// Get the bounding box of the collision shape and determine the max Y
		val boundingBox = collisionShape.boundingBox
		val maxY = boundingBox.maxY // This gives the height of the block's collision shape

		// Consider blocks as "solid-like" if their height is close to a full block (e.g., tilled blocks)
		val isSolidEnough = maxY >= 0.9 // 0.9 is a threshold for solid-like blocks like farmland or path blocks

		// 1. Ensure the block below is solid (or slab/stair treated as solid or has enough height)
		if (!blockBelowState.isSideSolidFullSquare(world, blockBelowPos, Direction.UP) &&
			blockBelow !is SlabBlock && blockBelow !is StairsBlock && !isSolidEnough) {
			return false
		}

		// 2. Ensure the block at the spawn position is air or has no collision shape (i.e., visual block)
		val blockAtPos = world.getBlockState(spawnPos)
		if (!blockAtPos.isAir && !blockAtPos.getCollisionShape(world, spawnPos).isEmpty) {
			return false
		}

		// 3. Ensure the block above the spawn position is air or has no collision shape (i.e., visual block)
		val blockAbovePos = world.getBlockState(spawnPos.up())
		if (!blockAbovePos.isAir && !blockAbovePos.getCollisionShape(world, spawnPos.up()).isEmpty) {
			return false
		}

		// If all conditions are met, the position is safe for spawning
		return true
	}




	// Function to generate valid IVs for a Pokémon based on spawn entry
	private fun generateValidIVs(pokemon: Pokemon, entry: PokemonSpawnEntry) {
		val ivRanges: List<Pair<Stat, IntRange>> = listOf(
			Stats.HP to (entry.ivSettings.minIVHp..entry.ivSettings.maxIVHp),
			Stats.ATTACK to (entry.ivSettings.minIVAttack..entry.ivSettings.maxIVAttack),
			Stats.DEFENCE to (entry.ivSettings.minIVDefense..entry.ivSettings.maxIVDefense),
			Stats.SPECIAL_ATTACK to (entry.ivSettings.minIVSpecialAttack..entry.ivSettings.maxIVSpecialAttack),
			Stats.SPECIAL_DEFENCE to (entry.ivSettings.minIVSpecialDefense..entry.ivSettings.maxIVSpecialDefense),
			Stats.SPEED to (entry.ivSettings.minIVSpeed..entry.ivSettings.maxIVSpeed)
		).shuffled()

		// Select stats that will receive perfect IVs
		val perfectIVStats: List<Pair<Stat, IntRange>> = ivRanges.shuffled().take(3) // Example: 3 perfect IVs

		// Assign perfect IVs
		perfectIVStats.forEach { (stat, _) ->
			pokemon.ivs[stat] = 31
		}

		// Assign random IVs for remaining stats within their respective ranges
		ivRanges.forEach { (stat, range) ->
			if (!perfectIVStats.any { it.first == stat }) {
				val randomIV = range.first + random.nextInt(range.last - range.first + 1)
				pokemon.ivs[stat] = randomIV
			}
		}
	}

	/**
	 * Helper function to parse dimension string to RegistryKey<World>
	 *
	 * @param dimensionString The dimension string in the format "namespace:path"
	 * @return The corresponding RegistryKey<World>
	 */
	private fun parseDimension(dimensionString: String): RegistryKey<World> {
		val parts = dimensionString.split(":")
		if (parts.size != 2) {
			logger.warn("Invalid dimension format: $dimensionString. Expected 'namespace:path'")
			return RegistryKey.of(RegistryKeys.WORLD, Identifier("minecraft", "overworld")) // default to overworld
		}
		val namespace = parts[0]
		val path = parts[1]
		return RegistryKey.of(RegistryKeys.WORLD, Identifier(namespace, path))
	}

	/**
	 * Helper function to convert RegistryKey<World> to string
	 *
	 * @param registryKey The RegistryKey<World> to convert
	 * @return The dimension string in the format "namespace:path"
	 */
	private fun registryKeyToString(registryKey: RegistryKey<World>): String {
		return "${registryKey.value.namespace}:${registryKey.value.path}"
	}
}
