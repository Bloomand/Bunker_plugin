package ru.maincraft.bunker;

import org.bukkit.Location;
import org.bukkit.Material;

import java.util.Set;

/** Настройки одной игровой арены. */
final class Arena {
    final String id;
    final String name;
    final String description;
    final Material icon;
    final Location start;
    final Set<String> allowedCommands;

    Arena(String id, String name, String description, Material icon,
          Location start, Set<String> allowedCommands) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.icon = icon;
        this.start = start;
        this.allowedCommands = Set.copyOf(allowedCommands);
    }
}
