### BlanketCobbleSpawners

**BlanketCobbleSpawners** is a Minecraft Fabric mod designed as a Cobblemon addon, giving map creators and admins the ability to place custom Cobble spawners in specific areas. It provides full control over Cobblemon spawning, including species selection, spawn rates, shiny chances, and custom stats, all managed through an easy-to-use GUI. It also supports spawning Pokémon in regions where natural Cobblemon spawning is disabled, such as Flan-protected areas.

---

### Screenshots

![Screenshot 1](https://i.imgur.com/cnu6VUm.png)

*Preview of a spawners gui with 5 mons selected*

![Screenshot 1](https://i.imgur.com/wcMkLXd.png)

*Preview of shown stats for selected mon*

![Screenshot 1](https://i.imgur.com/wWU2yKy.gif)

*Preview of editing a mons spawn values*

![Screenshot 2](https://i.imgur.com/0kcjxwx.jpeg)
*Example of how spawners could be placed around spawn with holograms above them from another mod*


---

### Features
- **Custom Pokémon Spawners**: Place spawners that can spawn specific Pokémon with customized settings.
  
- **GUI Management**: Open a detailed GUI to select and configure the Pokémon spawned by each spawner.
  
- **Advanced Spawn Control**: Customize spawn rates, shiny chances, and individual Pokémon stats like IVs.
  
- **Custom Spawn Logic**: Determines Pokémon size and ensures they spawn safely based on their dimensions and available space.
  
- **Spawner Visibility**: Toggle the visibility of spawners without removing their functionality.
  
- **Custom EV Rewards**: Configure spawners to provide custom EVs when defeating Pokémon, allowing for tailored EV training from spawner battles.

---

### Dependencies
- [Kotlin](https://modrinth.com/mod/fabric-language-kotlin)
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [Cobblemon Mod](https://modrinth.com/mod/cobblemon)

---

### Commands
- `/blanketcobblespawners`  
  - **Description**: Shows the mod version and allows for other subcommands.
  - **Permission**: `spawner.manage`

- `/blanketcobblespawners reload`  
  - **Description**: Reloads the mod’s configuration and spawner data.
  - **Permission**: `spawner.manage`

- `/blanketcobblespawners givespawner`  
  - **Description**: Gives the player a custom Pokémon spawner item.
  - **Permission**: `spawner.manage`

- `/blanketcobblespawners killspawned [spawnerName]`  
  - **Description**: Removes all spawned Pokémon from the specified spawner.
  - **Permission**: `spawner.manage`

- `/blanketcobblespawners removespawner [spawnerName]`  
  - **Description**: Removes the specified spawner and its associated Pokémon.
  - **Permission**: `spawner.manage`

- `/blanketcobblespawners opengui [spawnerName]`  
  - **Description**: Opens the GUI for managing the specified spawner.
  - **Permission**: `spawner.manage`

- `/blanketcobblespawners togglevisibility [spawnerName]`  
  - **Description**: Toggles the visibility of the specified spawner.
  - **Permission**: `spawner.manage`

---

### Compatibility
- **Flan/Flan Cobblemon Extension**: This mod supports spawning Pokémon in regions where natural Cobblemon spawning is disabled, such as Flan-protected areas, allowing for custom spawner functionality even in restricted zones.

---

### Permissions
- `spawner.manage` - Allows you to use all the spawner-related commands.

---

### How to Use
1. **Placing a Custom Spawner**:
   - Use `/blanketcobblespawners givespawner` to get a spawner item and place it in the world.

2. **Opening the Spawner GUI**:
   - Right-click on the placed spawner to open its GUI and manage its settings.
   - Alternatively, use `/blanketcobblespawners opengui [spawnerName]` to open the spawner’s GUI for remote management.

3. **Managing Pokémon Spawns**:
   - Inside the GUI, you can configure the Pokémon spawn settings, including species, shiny chances, and stats.

4. **Removing Spawns**:
   - Use `/blanketcobblespawners killspawned [spawnerName]` to remove all Pokémon spawned by a specific spawner.

5. **Reloading the Config**:
   - If you need to reload the config, use `/blanketcobblespawners reload` without restarting the server.

---

### Config Setup
Check out the `config/BlanketCobbleSpawners` folder to tweak settings such as global options, individual spawners, and Pokémon stats.

---

### Discord
- [Discord Link](https://discord.gg/nrENPTmQKt)
