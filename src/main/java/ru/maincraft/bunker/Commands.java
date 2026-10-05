package ru.maincraft.bunker;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;

/** Тонкий Bukkit-адаптер для игровых команд. */
public final class Commands implements CommandExecutor, TabCompleter {
    private final GameManager gameManager;

    public Commands(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return gameManager.handleCommand(sender, command, label, args);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return gameManager.tabComplete(sender, args);
    }
}
