package ru.maincraft.bunker;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/** Тонкий Bukkit-адаптер для игровых команд. */
public final class Commands implements CommandExecutor {
    private final GameManager gameManager;

    public Commands(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return gameManager.handleCommand(sender, command, label, args);
    }
}
