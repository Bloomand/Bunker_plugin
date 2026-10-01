package ru.maincraft.bunker;

import org.bukkit.plugin.java.JavaPlugin;

/** Точка запуска плагина и регистрации его компонентов. */
public final class BunkerPlugin extends JavaPlugin {
    private ConfigManager configManager;
    private GameManager gameManager;

    @Override
    public void onEnable() {
        configManager = new ConfigManager(this);
        configManager.load();

        gameManager = new GameManager(this, configManager);
        getCommand("bunker").setExecutor(new Commands(gameManager));
        getServer().getPluginManager().registerEvents(gameManager, this);
        getLogger().info("Bunker включён!");
    }

    @Override
    public void onDisable() {
        if (gameManager != null) {
            gameManager.shutdown();
        }
        getLogger().info("Bunker выключен.");
    }
}
