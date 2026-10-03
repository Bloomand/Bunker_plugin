package ru.maincraft.bunker;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Управляет комнатами, этапами игры, таймерами, GUI и голосованием. */
@SuppressWarnings("deprecation")
public final class GameManager implements Listener {
    private boolean gameCreationEnabled;
    private int minPlayers;
    private int maxPlayers;
    private int winnersCount;
    private int lobbySeconds;
    private int studySeconds;
    private int speechSeconds;
    private int openDiscussionSeconds;
    private int votingSeconds;
    private int hostSkipDelaySeconds;
    private int skipCount;
    private final MessageService messages;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();

    private final BunkerPlugin plugin;
    private final ConfigManager configManager;
    private final File stateFile;
    private final Map<String, Game> gamesByArena = new LinkedHashMap<>();
    private final Map<UUID, Game> playerGames = new LinkedHashMap<>();
    private final Random random = new Random();
    private BukkitTask stateSaveTask;
    private BukkitTask deferredSaveTask;
    private boolean settingsValid;

    public GameManager(BunkerPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.messages = new MessageService(configManager);
        this.stateFile = new File(plugin.getDataFolder(), "games.yml");
        reloadSettings();
    }

    public void reload() {
        configManager.reloadFiles();
        reloadSettings();
    }

    private void reloadSettings() {
        gameCreationEnabled = configManager.bool("game.creation-enabled", true);
        minPlayers = configManager.integer("game.min-players", 6);
        maxPlayers = configManager.integer("game.max-players", 12);
        winnersCount = configManager.integer("game.winners-count", 2);
        lobbySeconds = configManager.integer("game.lobby-seconds", 600);
        studySeconds = configManager.integer("game.study-seconds", 120);
        speechSeconds = configManager.integer("game.speech-seconds", 180);
        openDiscussionSeconds = configManager.integer("game.open-discussion-seconds", 120);
        votingSeconds = configManager.integer("game.voting-seconds", 60);
        hostSkipDelaySeconds = configManager.integer("game.host-skip-delay-seconds", 30);
        skipCount = configManager.integer("game.skip-count", 2);
        messages.reload();
        settingsValid = validateSettings();
        loadArenas();
        if (stateSaveTask == null) {
            stateSaveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::saveState, 20L * 30L, 20L * 30L);
        }
    }

    private boolean validateSettings() {
        boolean valid = true;
        if (minPlayers < 6 || maxPlayers < minPlayers || maxPlayers > 18) {
            plugin.getLogger().severe("Некорректные настройки игроков: min-players должен быть не меньше 6, max-players не больше 18 и min-players не больше max-players.");
            valid = false;
        }
        if (winnersCount < 1 || winnersCount >= minPlayers) {
            plugin.getLogger().severe("Некорректная настройка winners-count: победителей должно быть меньше минимального числа игроков.");
            valid = false;
        }
        if (lobbySeconds < 1 || studySeconds < 1 || speechSeconds < 1
                || openDiscussionSeconds < 1 || votingSeconds < 1
                || hostSkipDelaySeconds < 0 || skipCount < 0) {
            plugin.getLogger().severe("Некорректные настройки длительности этапов или количества пропусков.");
            valid = false;
        }
        if (configManager.characteristicSets().size() > 17) {
            plugin.getLogger().severe("Слишком много категорий характеристик: интерфейс поддерживает максимум 17.");
            valid = false;
        }
        return valid;
    }

    private void loadArenas() {
        arenas.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("arenas");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection arena = section.getConfigurationSection(id);
                if (arena == null) {
                    continue;
                }
                String normalizedId = normalizeArenaId(id);
                Arena loaded = readArena(normalizedId, arena);
                if (loaded.start == null) {
                    plugin.getLogger().warning("Арена " + id + " пропущена: не задана корректная точка start.");
                    continue;
                }
                if (arenas.putIfAbsent(normalizedId, loaded) != null) {
                    plugin.getLogger().warning("Арены " + id + " и другой арены имеют одинаковый ID без учёта регистра.");
                }
            }
        }
        // Совместимость со старым форматом одной арены.
        if (arenas.isEmpty()) {
            Location start = readLocation("arena.start");
            if (start != null) {
                arenas.put("bunker", new Arena("bunker", "Бункер", "Основная арена",
                        Material.CHEST, start,
                        readAllowedCommands("arena.allowed-commands")));
            }
        }
    }

    private Arena readArena(String id, ConfigurationSection section) {
        Location start = readLocation(section, "start");
        Material icon;
        try {
            icon = Material.valueOf(section.getString("icon", "CHEST").toUpperCase());
        } catch (IllegalArgumentException exception) {
            icon = Material.CHEST;
        }
        return new Arena(id, section.getString("name", id),
                section.getString("description", ""), icon, start,
                readAllowedCommands(section.getStringList("allowed-commands")));
    }

    private Set<String> readAllowedCommands(String path) {
        return readAllowedCommands(plugin.getConfig().getStringList(path));
    }

    private Set<String> readAllowedCommands(List<String> configured) {
        Set<String> commands = new LinkedHashSet<>();
        for (String command : configured) {
            String normalized = command.toLowerCase().replaceFirst("^/", "").trim();
            if (!normalized.isBlank()) {
                commands.add(normalized);
            }
        }
        if (commands.isEmpty()) {
            commands.add("spawn");
        }
        return commands;
    }

    private Location readLocation(String path) {
        return readLocation(plugin.getConfig(), path);
    }

    private Location readLocation(ConfigurationSection section, String path) {
        String worldName = section.getString(path + ".world");
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null || !section.contains(path + ".x") || !section.contains(path + ".y")
                || !section.contains(path + ".z")) {
            return null;
        }
        return new Location(world,
                section.getDouble(path + ".x"), section.getDouble(path + ".y"), section.getDouble(path + ".z"),
                (float) section.getDouble(path + ".yaw"), (float) section.getDouble(path + ".pitch"));
    }

    public void shutdown() {
        if (stateSaveTask != null) {
            stateSaveTask.cancel();
        }
        if (deferredSaveTask != null) {
            deferredSaveTask.cancel();
            deferredSaveTask = null;
        }
        saveState();
        for (Game game : new ArrayList<>(gamesByArena.values())) {
            game.cancelTasks();
        }
    }

    /** Восстанавливает комнаты после перезапуска сервера. */
    public void restore() {
        if (!stateFile.isFile()) {
            return;
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(stateFile);
        ConfigurationSection savedGames = data.getConfigurationSection("games");
        if (savedGames == null) {
            File backup = new File(stateFile.getParentFile(), stateFile.getName() + ".bak");
            if (backup.isFile()) {
                plugin.getLogger().warning("games.yml не содержит корректного состояния, используется резервная копия.");
                data = YamlConfiguration.loadConfiguration(backup);
                savedGames = data.getConfigurationSection("games");
            }
        }
        if (savedGames == null) {
            return;
        }
        gamesByArena.clear();
        playerGames.clear();
        for (String id : savedGames.getKeys(false)) {
            ConfigurationSection section = savedGames.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            Game game = null;
            try {
                UUID host = UUID.fromString(section.getString("host"));
                String arenaId = normalizeArenaId(section.getString("arena", "bunker"));
                Arena arena = arenas.get(arenaId);
                if (arena == null) {
                    plugin.getLogger().warning("Игра " + id + " пропущена: арена " + arenaId + " не найдена.");
                    continue;
                }
                if (gamesByArena.containsKey(arenaId)) {
                    throw new IllegalArgumentException("Для арены уже восстановлена другая игра");
                }
                game = new Game(arena.id, host, arena);
                game.started = section.getBoolean("started");
                game.phase = Phase.valueOf(section.getString("phase", Phase.LOBBY.name()));
                game.round = section.getInt("round");
                game.skipUses = section.getInt("skip-uses");
                game.catastrophe = section.getString("catastrophe");
                loadPlayers(game, section.getConfigurationSection("players"));
                loadAlive(game, section.getStringList("alive"));
                loadCards(game, section.getConfigurationSection("cards"));
                if (game.phase == Phase.FINISHED) {
                    continue;
                }
                validateRestoredPlayers(game);
                if (!game.players.containsKey(host)) {
                    throw new IllegalArgumentException("Ведущий отсутствует среди игроков");
                }
                gamesByArena.put(game.arenaId, game);
                for (UUID uuid : game.players.keySet()) {
                    playerGames.put(uuid, game);
                }
                game.restartPhaseAfterLoad();
            } catch (RuntimeException exception) {
                if (game != null) {
                    gamesByArena.remove(game.arenaId, game);
                    game.cancelTasks();
                    game.players.keySet().forEach(playerGames::remove);
                }
                plugin.getLogger().warning("Пропущена повреждённая сохранённая игра " + id + ".");
            }
        }
        plugin.getLogger().info("Восстановлено игровых комнат: " + gamesByArena.size());
    }

    private void validateRestoredPlayers(Game game) {
        Set<Integer> used = new LinkedHashSet<>();
        int next = 1;
        for (GamePlayer player : game.players.values()) {
            if (player.number > 0 && used.add(player.number)) {
                next = Math.max(next, player.number + 1);
            } else {
                while (used.contains(next)) {
                    next++;
                }
                player.number = next++;
                used.add(player.number);
            }
        }
    }

    private void loadPlayers(Game game, ConfigurationSection section) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            UUID uuid = UUID.fromString(key);
            ConfigurationSection player = section.getConfigurationSection(key);
            if (player != null) {
                GamePlayer.Status status = readPlayerStatus(player.getString("status"), game.started);
                game.players.put(uuid, new GamePlayer(uuid,
                        player.getString("name", uuid.toString()), player.getInt("number"), status));
                Set<String> playerRevealed = new LinkedHashSet<>(player.getStringList("revealed"));
                game.revealed.put(uuid, playerRevealed);
            }
        }
    }

    private GamePlayer.Status readPlayerStatus(String value, boolean started) {
        if (value != null) {
            try {
                return GamePlayer.Status.valueOf(value);
            } catch (IllegalArgumentException ignored) {
                // Старое или повреждённое значение заменяется статусом по состоянию игры.
            }
        }
        return started ? GamePlayer.Status.IN_GAME : GamePlayer.Status.WAITING_FOR_GAME;
    }

    private void loadAlive(Game game, List<String> savedAlive) {
        for (String value : savedAlive) {
            try {
                UUID uuid = UUID.fromString(value);
                if (game.players.containsKey(uuid)) {
                    game.alive.add(uuid);
                }
            } catch (IllegalArgumentException ignored) {
                // Повреждённый UUID не должен ломать остальные данные комнаты.
            }
        }
    }

    private void loadCards(Game game, ConfigurationSection section) {
        if (section == null) {
            return;
        }
        for (String uuidValue : section.getKeys(false)) {
            UUID uuid = UUID.fromString(uuidValue);
            ConfigurationSection playerCards = section.getConfigurationSection(uuidValue);
            if (playerCards == null) {
                continue;
            }
            Map<String, Characteristic> cards = new LinkedHashMap<>();
            for (String storedCategory : playerCards.getKeys(false)) {
                ConfigurationSection card = playerCards.getConfigurationSection(storedCategory);
                if (card != null) {
                    String category = restoreCategoryKey(storedCategory);
                    cards.put(category, new Characteristic(card.getString("name", "Неизвестно"),
                            card.getString("description", "Описание отсутствует")));
                }
            }
            game.cards.put(uuid, cards);
            game.revealed.putIfAbsent(uuid, new LinkedHashSet<>());
        }
    }

    private void saveState() {
        YamlConfiguration data = new YamlConfiguration();
        for (Game game : gamesByArena.values()) {
            String path = "games." + game.id;
            data.set(path + ".host", game.host.toString());
            data.set(path + ".arena", game.arenaId);
            data.set(path + ".started", game.started);
            data.set(path + ".phase", game.phase.name());
            data.set(path + ".round", game.round);
            data.set(path + ".skip-uses", game.skipUses);
            data.set(path + ".catastrophe", game.catastrophe);
            data.set(path + ".alive", game.alive.stream().map(UUID::toString).toList());
            for (Map.Entry<UUID, GamePlayer> entry : game.players.entrySet()) {
                String playerPath = path + ".players." + entry.getKey();
                GamePlayer participant = entry.getValue();
                data.set(playerPath + ".name", participant.name);
                data.set(playerPath + ".number", participant.number);
                data.set(playerPath + ".status", participant.status.name());
                data.set(playerPath + ".revealed", new ArrayList<>(game.revealed.getOrDefault(
                        entry.getKey(), new LinkedHashSet<>())));
            }
            for (Map.Entry<UUID, Map<String, Characteristic>> playerCards : game.cards.entrySet()) {
                for (Map.Entry<String, Characteristic> card : playerCards.getValue().entrySet()) {
                    String cardPath = path + ".cards." + playerCards.getKey() + "."
                            + storageCategoryKey(card.getKey());
                    data.set(cardPath + ".name", card.getValue().name());
                    data.set(cardPath + ".description", card.getValue().description());
                }
            }
        }
        try {
            backupState();
            if (gamesByArena.isEmpty()) {
                if (stateFile.isFile() && !stateFile.delete()) {
                    plugin.getLogger().warning("Не удалось удалить пустой файл games.yml.");
                }
            } else {
                File temporary = new File(stateFile.getParentFile(), stateFile.getName() + ".tmp");
                data.save(temporary);
                try {
                    Files.move(temporary.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException exception) {
                    Files.move(temporary.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException exception) {
            plugin.getLogger().severe("Не удалось сохранить games.yml: " + exception.getMessage());
        }
    }

    /** Объединяет несколько быстрых изменений состояния в одну запись на диск. */
    private void requestSave() {
        if (deferredSaveTask != null) {
            return;
        }
        deferredSaveTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            deferredSaveTask = null;
            saveState();
        }, 1L);
    }

    private void backupState() {
        if (!stateFile.isFile()) {
            return;
        }
        File backup = new File(stateFile.getParentFile(), stateFile.getName() + ".bak");
        try {
            Files.copy(stateFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            plugin.getLogger().warning("Не удалось сохранить резервную копию games.yml: " + exception.getMessage());
        }
    }

    private String storageCategoryKey(String category) {
        return category.replace(".", "\uFF0E");
    }

    private String restoreCategoryKey(String category) {
        return category.replace("\uFF0E", ".");
    }

    public boolean handleCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadCommand(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("setarena")) {
            setArenaPoint(sender, args);
            return true;
        }

        if (args[0].equalsIgnoreCase("stopall")) {
            stopAllCommand(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("deletearena") || args[0].equalsIgnoreCase("delarena")) {
            deleteArena(sender, args);
            return true;
        }

        if (args[0].equalsIgnoreCase("arena") && args.length >= 3
                && args[1].equalsIgnoreCase("delete")) {
            deleteArena(sender, new String[]{args[0], args[2]});
            return true;
        }

        Actor actor = resolveActor(sender);
        if (actor == null) {
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "create" -> create(sender, actor, args);
            case "join" -> join(sender, actor, args);
            case "leave" -> leave(sender, actor);
            case "start" -> start(sender, actor);
            case "cancel" -> cancel(sender, actor);
            case "status" -> status(sender, actor);
            case "cards" -> cards(sender, actor);
            case "players" -> players(sender, actor);
            case "open" -> open(sender, actor);
            case "vote" -> voteCommand(sender, actor, args);
            case "pass" -> pass(sender, actor);
            case "games" -> listGames(sender);
            default -> help(sender);
        }
        return true;
    }

    private void reloadCommand(CommandSender sender) {
        if (!sender.hasPermission("bunker.admin")) {
            sender.sendMessage(configManager.message("reload.no-permission", null));
            return;
        }
        reload();
        sender.sendMessage(configManager.message("reload.success", null));
    }

    private String text(String key, Map<String, ?> placeholders) {
        return messages.get(key, placeholders);
    }

    private void setArenaPoint(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(text("command.players-only", null));
            return;
        }
        if (!player.hasPermission("bunker.admin")) {
            player.sendMessage(text("arena.no-permission", null));
            return;
        }
        if (args.length < 3 || !args[2].equalsIgnoreCase("start")) {
            player.sendMessage(text("arena.usage", null));
            return;
        }

        String arenaId = normalizeArenaId(args[1]);
        if (!arenaId.matches("[\\p{L}\\p{N}_-]+")) {
            player.sendMessage(text("arena.invalid-id", null));
            return;
        }
        String configArenaId = findArenaConfigKey(arenaId);
        if (configArenaId == null) {
            configArenaId = arenaId;
            plugin.getConfig().set("arenas." + configArenaId + ".name", arenaId);
            plugin.getConfig().set("arenas." + configArenaId + ".description", "");
            plugin.getConfig().set("arenas." + configArenaId + ".icon", "CHEST");
            plugin.getConfig().set("arenas." + configArenaId + ".allowed-commands", List.of("spawn"));
        }
        String point = args[2].toLowerCase();
        String path = "arenas." + configArenaId + "." + point;
        Location location = player.getLocation();
        plugin.getConfig().set(path + ".world", location.getWorld().getName());
        plugin.getConfig().set(path + ".x", location.getX());
        plugin.getConfig().set(path + ".y", location.getY());
        plugin.getConfig().set(path + ".z", location.getZ());
        plugin.getConfig().set(path + ".yaw", location.getYaw());
        plugin.getConfig().set(path + ".pitch", location.getPitch());
        plugin.saveConfig();
        reloadSettings();
        player.sendMessage(text("arena.saved", Map.of("arena", arenaId, "point", point)));
    }

    private void deleteArena(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bunker.admin")) {
            sender.sendMessage(text("admin.no-permission", null));
            return;
        }
        if (args.length < 2 || args[1].isBlank()) {
            sender.sendMessage(text("arena.delete-usage", null));
            return;
        }
        String arenaId = normalizeArenaId(args[1]);
        if (!arenaId.matches("[\\p{L}\\p{N}_-]+")) {
            sender.sendMessage(text("arena.invalid-id", null));
            return;
        }
        String configArenaId = findArenaConfigKey(arenaId);
        if (configArenaId == null) {
            sender.sendMessage(text("arena.not-found", Map.of("id", arenaId)));
            return;
        }
        if (findGameForArena(arenaId) != null) {
            sender.sendMessage(text("arena.delete-busy", Map.of("id", arenaId)));
            return;
        }
        plugin.getConfig().set("arenas." + configArenaId, null);
        plugin.saveConfig();
        reloadSettings();
        sender.sendMessage(text("arena.deleted", Map.of("id", arenaId)));
    }

    private void stopAllCommand(CommandSender sender) {
        if (!sender.hasPermission("bunker.admin")) {
            sender.sendMessage(text("admin.no-permission", null));
            return;
        }
        int count = gamesByArena.size();
        for (Game game : new ArrayList<>(gamesByArena.values())) {
            game.cancel(text("admin.games-stopped", null));
        }
        sender.sendMessage(text("admin.stopall-success", Map.of("count", count)));
    }

    private Actor resolveActor(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.message("command.players-only", null));
            return null;
        }
        return new Actor(player.getUniqueId(), player.getName());
    }

    private void create(CommandSender sender, Actor actor, String[] args) {
        if (!gameCreationEnabled) {
            sender.sendMessage(text("game.creation-disabled", null));
            return;
        }
        if (!settingsValid) {
            sender.sendMessage(text("config.invalid", null));
            return;
        }
        if (playerGames.containsKey(actor.uuid())) {
            sender.sendMessage(configManager.message("command.already-in-game", null));
            return;
        }

        if (args.length < 2) {
            if (sender instanceof Player player) {
                openArenaMenu(player, false);
            } else {
                listArenas(sender);
            }
            return;
        }
        String arenaId = normalizeArenaId(args[1]);
        Arena arena = arenas.get(arenaId);
        if (arena == null || arena.start == null) {
            sender.sendMessage(text("arena.not-found", Map.of("id", arenaId)));
            return;
        }
        if (findGameForArena(arenaId) != null) {
            sender.sendMessage(text("arena.busy", Map.of("name", arena.name)));
            return;
        }

        Game game = new Game(arena.id, actor, arena);
        gamesByArena.put(arena.id, game);
        playerGames.put(actor.uuid(), game);
        game.lobbyTimeout = Bukkit.getScheduler().runTaskLater(plugin,
                () -> game.cancel("Игра автоматически отменена: за 10 минут не набралось готовое лобби."),
                lobbySeconds * 20L);
        game.startCountdown("Ожидание игроков", lobbySeconds);
        requestSave();

        sender.sendMessage(text("game.created", Map.of("player", actor.name(), "id", game.id, "arena", arena.name)));
        sender.sendMessage(text("game.connect", Map.of("id", game.id, "arena", arena.name)));
        sender.sendMessage(text("game.lobby-help", null));
    }

    private void join(CommandSender sender, Actor actor, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(text("join.usage", null));
            return;
        }
        if (playerGames.containsKey(actor.uuid())) {
            sender.sendMessage(text("command.already-in-game", null));
            return;
        }
        boolean randomJoin = args[1].equalsIgnoreCase("random");
        Game game = randomJoin ? randomLobby() : findGameForArena(normalizeArenaId(args[1]));
        if (game == null) {
            sender.sendMessage(text(randomJoin ? "arena.no-joinable-games" : "arena.no-active-game",
                    Map.of("id", args[1])));
            return;
        }
        if (game.started) {
            sender.sendMessage(text("game.already-started", null));
            return;
        }
        if (game.players.size() >= maxPlayers) {
            sender.sendMessage(text("game.max-players", Map.of("max_players", maxPlayers)));
            return;
        }

        game.addPlayer(actor);
        playerGames.put(actor.uuid(), game);
        requestSave();
        game.broadcast(text("game.joined", Map.of("player", actor.name(), "players", game.players.size(), "max_players", maxPlayers)));
    }

    private void leave(CommandSender sender, Actor actor) {
        Game game = playerGames.get(actor.uuid());
        if (game == null) {
            sender.sendMessage(text("command.not-in-game", null));
        } else if (game.started) {
            game.leaveDuringGame(actor.uuid());
            playerGames.remove(actor.uuid());
        } else if (game.host.equals(actor.uuid())) {
            game.cancel("Игра отменена: ведущий покинул лобби.");
        } else {
            game.removePlayer(actor.uuid());
            playerGames.remove(actor.uuid());
            game.broadcast(text("game.left", Map.of("player", actor.name(), "players", game.players.size())));
        }
    }

    private void start(CommandSender sender, Actor actor) {
        Game game = playerGames.get(actor.uuid());
        if (game == null) {
            sender.sendMessage(text("create.first", null));
        } else if (game.host.equals(actor.uuid())) {
            game.start();
        } else {
            sender.sendMessage(text("game.not-host", null));
        }
    }

    private void cancel(CommandSender sender, Actor actor) {
        Game game = playerGames.get(actor.uuid());
        if (game == null) {
            sender.sendMessage(text("command.not-in-game", null));
        } else if (game.host.equals(actor.uuid())) {
            game.cancel("Игра отменена ведущим.");
        } else {
            sender.sendMessage(text("game.not-host", null));
        }
    }

    private void status(CommandSender sender, Actor actor) {
        if (playerGames.containsKey(actor.uuid())) {
            Game game = playerGames.get(actor.uuid());
            int alivePlayers = game.started ? game.alive.size() : game.players.size();
            sender.sendMessage(text("status.info", Map.of("id", game.id, "phase", game.phaseName(),
                    "players", game.players.size(), "alive", alivePlayers)));
        } else {
            sender.sendMessage(text("status.not-in-game", Map.of("games", gamesByArena.size())));
        }
    }

    private void cards(CommandSender sender, Actor actor) {
        Game game = playerGames.get(actor.uuid());
        if (game == null || !game.started) {
            sender.sendMessage(text("command.game-not-started", null));
            return;
        }
        game.sendCards(actor.uuid(), sender);
    }

    private void players(CommandSender sender, Actor actor) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(text("command.players-only", null));
            return;
        }
        Game game = playerGames.get(actor.uuid());
        if (game == null || !game.started) {
            player.sendMessage(text("command.game-not-started", null));
            return;
        }
        game.openPlayers(player);
    }

    private void open(CommandSender sender, Actor actor) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(text("command.players-only", null));
            return;
        }
        Game game = playerGames.get(actor.uuid());
        if (game == null) {
            sender.sendMessage(text("command.not-in-game", null));
        } else {
            game.openCurrentInterface(player);
        }
    }

    private void voteCommand(CommandSender sender, Actor actor, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(text("vote.usage", null));
            return;
        }
        try {
            Game game = playerGames.get(actor.uuid());
            if (game == null) {
                sender.sendMessage(text("command.not-in-game", null));
            } else {
                int target = args[1].equalsIgnoreCase("skip") ? 0 : Integer.parseInt(args[1]);
                game.castVote(actor.uuid(), target);
            }
        } catch (NumberFormatException exception) {
            sender.sendMessage(text("vote.invalid-number", null));
        }
    }

    private void pass(CommandSender sender, Actor actor) {
        Game game = playerGames.get(actor.uuid());
        if (game == null) {
            sender.sendMessage(text("command.not-in-game", null));
            return;
        }
        game.passTurn(actor.uuid());
    }

    private void listGames(CommandSender sender) {
        if (sender instanceof Player player) {
            openArenaMenu(player, true);
            return;
        }
        if (gamesByArena.isEmpty()) {
            sender.sendMessage(text("games.empty", null));
            return;
        }
        sender.sendMessage(text("games.title", null));
        boolean hasLobbies = false;
        for (Game game : gamesByArena.values()) {
            if (game.started) {
                continue;
            }
            hasLobbies = true;
            sender.sendMessage(text("games.item", Map.of("id", game.arenaId, "name", game.arena.name,
                    "players", game.players.size(), "max_players", maxPlayers)));
        }
        if (!hasLobbies) {
            sender.sendMessage(text("games.empty", null));
        }
    }

    private void listArenas(CommandSender sender) {
        if (arenas.isEmpty()) {
            sender.sendMessage(text("arena.none", null));
            return;
        }
        sender.sendMessage(text("arena.list-title", null));
        for (Arena arena : arenas.values()) {
            String status = switch (gameStatus(arena.id)) {
                case FREE -> "свободна";
                case WAITING -> "ожидание набора игроков";
                case IN_GAME -> "идёт игра";
            };
            sender.sendMessage(text("arena.list-item", Map.of("id", arena.id, "name", arena.name,
                    "description", arena.description, "status", status)));
        }
        sender.sendMessage(text("arena.create-hint", null));
    }

    private Game findGameForArena(String arenaId) {
        return gamesByArena.get(normalizeArenaId(arenaId));
    }

    private GameStatus gameStatus(String arenaId) {
        Game game = findGameForArena(arenaId);
        return game == null ? GameStatus.FREE : game.status();
    }

    private String normalizeArenaId(String id) {
        return id.toLowerCase(java.util.Locale.ROOT);
    }

    private String findArenaConfigKey(String normalizedId) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("arenas");
        if (section == null) {
            return null;
        }
        for (String key : section.getKeys(false)) {
            if (normalizeArenaId(key).equals(normalizedId)) {
                return key;
            }
        }
        return null;
    }

    private Game randomLobby() {
        List<Game> lobbies = gamesByArena.values().stream()
                .filter(game -> !game.started && game.players.size() < maxPlayers)
                .toList();
        return lobbies.isEmpty() ? null : lobbies.get(random.nextInt(lobbies.size()));
    }

    private void openArenaMenu(Player player, boolean joinMode) {
        openArenaMenu(player, joinMode, 0);
    }

    private void openArenaMenu(Player player, boolean joinMode, int page) {
        List<String> arenaIds = new ArrayList<>();
        for (Arena arena : arenas.values()) {
            Game game = findGameForArena(arena.id);
            if (!joinMode || (game != null && !game.started)) {
                arenaIds.add(arena.id);
            }
        }
        int maxPage = Math.max(0, (arenaIds.size() - 1) / 45);
        page = Math.max(0, Math.min(page, maxPage));
        ArenaHolder holder = new ArenaHolder(joinMode, page, arenaIds);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                text(joinMode ? "arena.join-menu-title" : "arena.create-menu-title", null));
        int slot = 0;
        int start = page * 45;
        int end = Math.min(start + 45, arenaIds.size());
        int freeArenas;
        for (int index = start; index < end; index++) {
            Arena arena = arenas.get(arenaIds.get(index));
            Game game = findGameForArena(arena.id);
            holder.arenas.put(slot, arena.id);
            String status = switch (gameStatus(arena.id)) {
                case FREE -> text("arena.status-free", null);
                case WAITING -> text("arena.status-waiting", Map.of(
                        "players", game.players.size(), "max_players", maxPlayers));
                case IN_GAME -> text("arena.status-in-game", null);
            };
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "ID: " + arena.id);
            lore.add(ChatColor.WHITE + arena.description);
            lore.add(ChatColor.AQUA + status);
            if (joinMode) {
                lore.add(text("arena.host", Map.of("player", game.hostPlayer().name)));
                lore.add(text("arena.waiting", Map.of("players", game.waitingPlayers())));
            } else if (game == null) {
                lore.add(ChatColor.YELLOW + "Нажмите, чтобы создать игру");
            } else {
                lore.add(ChatColor.YELLOW + "Нажмите, чтобы присоединиться");
            }
            inventory.setItem(slot++, item(arena.icon, ChatColor.GOLD + arena.name, lore));
        }
        freeArenas = joinMode
                ? (int) gamesByArena.values().stream().filter(game -> !game.started && game.players.size() < maxPlayers).count()
                : (int) arenas.values().stream().filter(arena -> findGameForArena(arena.id) == null).count();
        if (page > 0) {
            inventory.setItem(45, item(Material.ARROW, text("arena.previous-page", null), List.of()));
        }
        if (page < maxPage) {
            inventory.setItem(53, item(Material.ARROW, text("arena.next-page", null), List.of()));
        }
        if (freeArenas > 0) {
            holder.randomSlot = 49;
            String key = joinMode ? "arena.random-join-lore" : "arena.random-lore";
            inventory.setItem(holder.randomSlot, item(Material.NETHER_STAR, text("arena.random-title", null),
                    List.of(text(key, Map.of("count", freeArenas)))));
        }
        if (slot == 0) {
            player.sendMessage(text(joinMode ? "arena.no-active-game" : "arena.none", Map.of("id", "")));
            return;
        }
        player.openInventory(inventory);
    }

    private void help(CommandSender sender) {
        sender.sendMessage(text("help.title", null));
        for (String key : List.of("create", "join", "start", "cancel", "status", "cards", "players", "open", "vote", "pass", "games", "reload", "setarena", "deletearena", "stopall")) {
            sender.sendMessage(text("help." + key, null));
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof ArenaHolder arenaHolder) {
            event.setCancelled(true);
            if (event.getRawSlot() == 45 && arenaHolder.page > 0) {
                openArenaMenu(player, arenaHolder.joinMode, arenaHolder.page - 1);
                return;
            }
            if (event.getRawSlot() == 53 && arenaHolder.page < arenaHolder.maxPage()) {
                openArenaMenu(player, arenaHolder.joinMode, arenaHolder.page + 1);
                return;
            }
            if (!arenaHolder.joinMode && event.getRawSlot() == arenaHolder.randomSlot) {
                List<String> freeArenas = arenas.values().stream()
                        .filter(arena -> findGameForArena(arena.id) == null)
                        .map(arena -> arena.id)
                        .toList();
                if (!freeArenas.isEmpty()) {
                    player.closeInventory();
                    String arenaId = freeArenas.get(random.nextInt(freeArenas.size()));
                    Actor actor = new Actor(player.getUniqueId(), player.getName());
                    create(player, actor, new String[]{"create", arenaId});
                }
                return;
            }
            if (arenaHolder.joinMode && event.getRawSlot() == arenaHolder.randomSlot) {
                player.closeInventory();
                Actor actor = new Actor(player.getUniqueId(), player.getName());
                join(player, actor, new String[]{"join", "random"});
                return;
            }
            String arenaId = arenaHolder.arenas.get(event.getRawSlot());
            if (arenaId == null) {
                return;
            }
            player.closeInventory();
            Actor actor = new Actor(player.getUniqueId(), player.getName());
            if (arenaHolder.joinMode) {
                join(player, actor, new String[]{"join", arenaId});
            } else {
                create(player, actor, new String[]{"create", arenaId});
            }
        } else if (holder instanceof SelectionHolder selection) {
            event.setCancelled(true);
            Game game = gamesByArena.get(selection.gameId);
            if (event.getRawSlot() == 26) {
                player.closeInventory();
                return;
            }
            if (game != null && game.currentSpeaker != null && game.currentSpeaker.equals(player.getUniqueId())) {
                int categoryIndex = event.getRawSlot() - 9;
                if (categoryIndex >= 0 && categoryIndex < selection.categories.size()) {
                    String category = selection.categories.get(categoryIndex);
                    game.reveal(player.getUniqueId(), category);
                }
            }
        } else if (holder instanceof VoteHolder voteHolder) {
            event.setCancelled(true);
            if (event.getRawSlot() == 26) {
                player.closeInventory();
                return;
            }
            Game game = gamesByArena.get(voteHolder.gameId);
            if (game != null) {
                Integer vote = voteHolder.choices.get(event.getRawSlot());
                if (vote != null) {
                    game.castVote(player.getUniqueId(), vote);
                }
            }
        } else if (holder instanceof PlayersHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Game game = playerGames.get(event.getPlayer().getUniqueId());
        if (game != null) {
            game.playerQuit(event.getPlayer().getUniqueId());
            if (game.started) {
                playerGames.remove(event.getPlayer().getUniqueId());
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Game game = playerGames.get(uuid);
        if (game != null && game.started && game.arena.start != null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && game.alive.contains(uuid)) {
                    player.teleport(game.spawnLocation());
                }
            });
        }
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String commandMessage = event.getMessage().toLowerCase();
        if (player.hasPermission("bunker.admin")
                && (commandMessage.startsWith("/bunker setarena")
                || commandMessage.startsWith("/bunker stopall")
                || commandMessage.startsWith("/bunker deletearena")
                || commandMessage.startsWith("/bunker delarena")
                || commandMessage.startsWith("/bunker arena delete"))) {
            return;
        }
        Game game = playerGames.get(player.getUniqueId());
        if (game == null || !game.started || game.arena.start == null) {
            return;
        }
        String commandLine = event.getMessage().substring(1).trim();
        if (commandLine.isBlank()) {
            return;
        }
        String command = commandLine.split("\\s+", 2)[0].toLowerCase();
        if (command.startsWith("bunker:")) {
            return;
        }
        if (command.equals("bunker")) {
            return;
        }
        if (command.startsWith("minecraft:")) {
            command = command.substring("minecraft:".length());
        }
        command = command.replaceFirst("^/", "");
        if (game.arena.allowedCommands.contains(command)) {
            if (command.equals("spawn") && game.alive.contains(player.getUniqueId())) {
                game.leaveDuringGame(player.getUniqueId());
                playerGames.remove(player.getUniqueId());
            }
            return;
        }
        event.setCancelled(true);
        player.sendMessage(text("arena.command-blocked", Map.of("commands",
                String.join(", ", game.arena.allowedCommands))));
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private Material materialForCategory(String category) {
        return switch (category) {
            case "Профессия" -> Material.ANVIL;
            case "Возраст" -> Material.CLOCK;
            case "Здоровье" -> Material.GOLDEN_APPLE;
            case "Хобби" -> Material.COMPASS;
            case "Багаж" -> Material.CHEST;
            case "Секрет" -> Material.ENDER_CHEST;
            case "Фобия" -> Material.SPIDER_EYE;
            default -> Material.BOOK;
        };
    }
    private final class Game {
        private final String id;
        private final String arenaId;
        private final Arena arena;
        private UUID host;
        private final LinkedHashMap<UUID, GamePlayer> players = new LinkedHashMap<>();
        private final List<UUID> alive = new ArrayList<>();
        private final Map<UUID, Integer> votes = new LinkedHashMap<>();
        private final Map<UUID, Map<String, Characteristic>> cards = new LinkedHashMap<>();
        private final Map<UUID, Set<String>> revealed = new LinkedHashMap<>();
        private final Set<Integer> runoffCandidates = new LinkedHashSet<>();
        private BukkitTask lobbyTimeout;
        private BukkitTask phaseTask;
        private BukkitTask countdownTask;
        private UUID currentSpeaker;
        private int currentSpeakerIndex;
        private boolean currentTurnRevealed;
        private int round;
        private int skipUses;
        private boolean runoffVoting;
        private boolean started;
        private Phase phase = Phase.LOBBY;
        private String catastrophe;
        private long phaseStartedAtMillis;

        private Game(String id, Actor hostPlayer, Arena arena) {
            this(id, hostPlayer.uuid(), arena);
            addPlayer(hostPlayer);
        }

        private Game(String id, UUID host, Arena arena) {
            this.id = id;
            this.host = host;
            this.arenaId = arena.id;
            this.arena = arena;
        }

        private void restartPhaseAfterLoad() {
            cancelTasks();
            phaseStartedAtMillis = System.currentTimeMillis();
            if (!started) {
                phase = Phase.LOBBY;
                lobbyTimeout = Bukkit.getScheduler().runTaskLater(plugin,
                        () -> cancel("Игра автоматически отменена: за 10 минут не набралось готовое лобби."),
                        lobbySeconds * 20L);
                startCountdown("Ожидание игроков", lobbySeconds);
                return;
            }
            if (alive.size() <= winnersCount) {
                finish();
                return;
            }
            teleportPlayersToArena();
            switch (phase) {
                case STUDY -> {
                    broadcast(text("phase.study", Map.of("seconds", studySeconds)));
                    for (UUID uuid : new ArrayList<>(alive)) {
                        sendCards(uuid, null);
                    }
                    startCountdown("Изучение характеристик", studySeconds);
                    phaseTask = Bukkit.getScheduler().runTaskLater(plugin, this::beginDiscussion,
                            studySeconds * 20L);
                }
                case DISCUSSION -> {
                    currentSpeakerIndex = 0;
                    currentSpeaker = null;
                    broadcast(text("phase.round", Map.of("round", round)));
                    nextSpeaker();
                }
                case OPEN_DISCUSSION -> beginOpenDiscussion();
                case VOTING -> beginVoting();
                case RESOLVING -> beginDiscussion();
                case LOBBY, FINISHED -> finish();
            }
        }

        private void addPlayer(Actor actor) {
            int number = 1;
            while (findPlayerByNumber(number) != null) {
                number++;
            }
            players.put(actor.uuid(), new GamePlayer(actor.uuid(), actor.name(), number));
        }

        private void removePlayer(UUID uuid) {
            players.remove(uuid);
            playerGames.remove(uuid);
            requestSave();
        }

        private GamePlayer hostPlayer() {
            return players.get(host);
        }

        private String waitingPlayers() {
            return players.values().stream()
                    .map(player -> player.name)
                    .reduce((first, second) -> first + ", " + second)
                    .orElse("—");
        }

        private GameStatus status() {
            return started ? GameStatus.IN_GAME : GameStatus.WAITING;
        }

        private void teleportPlayersToArenaAtStart() {
            teleportPlayers(players.keySet());
        }

        private void teleportPlayersToArena() {
            teleportPlayers(alive);
        }

        private void teleportPlayers(java.util.Collection<UUID> uuids) {
            if (arena.start == null) {
                return;
            }
            for (UUID uuid : uuids) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    player.teleport(spawnLocation());
                }
            }
        }

        private Location spawnLocation() {
            Location location = arena.start.clone();
            location.add((random.nextDouble() - 0.5D) * 1.6D, 0.0D,
                    (random.nextDouble() - 0.5D) * 1.6D);
            return location;
        }


        // Запускает игру после проверки состава и данных.
        private void start() {
            if (!settingsValid) {
                message(host, text("config.invalid", null));
                return;
            }
            if (started) {
                message(host, text("game.already-started", null));
                return;
            }
            if (players.size() < minPlayers) {
                message(host, text("game.not-enough-players", Map.of("min_players", minPlayers, "players", players.size())));
                return;
            }
            if (!configManager.isCharacteristicsLoaded()) {
                message(host, "§cИгра не может начаться: файл characteristics.json повреждён или не загружен.");
                return;
            }
            started = true;
            phase = Phase.STUDY;
            for (GamePlayer participant : players.values()) {
                participant.status = GamePlayer.Status.IN_GAME;
            }
            phaseStartedAtMillis = System.currentTimeMillis();
            List<String> catastrophes = configManager.catastrophes();
            catastrophe = catastrophes.get(random.nextInt(catastrophes.size()));
            cancelTask(lobbyTimeout);
            alive.clear();
            alive.addAll(players.keySet());
            teleportPlayersToArenaAtStart();
            dealCards();
            requestSave();
            broadcast(text("phase.started", Map.of("id", id, "players", players.size())));
            broadcast(text("phase.catastrophe", Map.of("catastrophe", catastrophe)));
            broadcast(text("phase.study", Map.of("seconds", studySeconds)));
            broadcast(text("phase.host-skip", Map.of("seconds", hostSkipDelaySeconds)));
            broadcast(text("phase.hint-cards", null));
            for (UUID uuid : alive) {
                sendCards(uuid, null);
            }
            startCountdown("Изучение характеристик", studySeconds);
            phaseTask = Bukkit.getScheduler().runTaskLater(plugin, this::beginDiscussion, studySeconds * 20L);
        }

        private void dealCards() {
            for (UUID uuid : alive) {
                Map<String, Characteristic> playerCards = new LinkedHashMap<>();
                Set<String> playerRevealed = new LinkedHashSet<>();
                for (Map.Entry<String, List<Characteristic>> entry : configManager.characteristicSets().entrySet()) {
                    List<Characteristic> options = entry.getValue();
                    playerCards.put(entry.getKey(), options.get(random.nextInt(options.size())));
                }
                cards.put(uuid, playerCards);
                revealed.put(uuid, playerRevealed);
            }
        }

        // Один раунд: личные ходы, общее обсуждение и голосование.
        private void beginDiscussion() {
            if (!started || alive.size() <= winnersCount) {
                finish();
                return;
            }
            phase = Phase.DISCUSSION;
            round++;
            currentSpeakerIndex = 0;
            requestSave();
            broadcast(text("phase.round", Map.of("round", round)));
            nextSpeaker();
        }

        private void nextSpeaker() {
            if (alive.size() <= winnersCount) {
                finish();
                return;
            }
            if (currentSpeakerIndex >= alive.size()) {
                beginOpenDiscussion();
                return;
            }
            currentSpeaker = alive.get(currentSpeakerIndex);
            currentTurnRevealed = false;
            GamePlayer participant = players.get(currentSpeaker);
            broadcast(text("phase.speaker", Map.of("number", participant.number, "player", participant.name)));
            cancelTask(phaseTask);
            cancelTask(countdownTask);
            if (openSelection(Bukkit.getPlayer(currentSpeaker))) {
                startCountdown("Ход игрока №" + participant.number + " | /bunker pass", speechSeconds);
                phaseTask = Bukkit.getScheduler().runTaskLater(plugin,
                        this::endTurn, speechSeconds * 20L);
            } else {
                phaseTask = Bukkit.getScheduler().runTaskLater(plugin,
                        this::endTurn, 1L);
            }
        }

        private boolean openSelection(Player player) {
            if (player == null) {
                return true;
            }
            List<String> available = new ArrayList<>();
            Map<String, Characteristic> playerCards = cards.get(player.getUniqueId());
            if (playerCards == null) {
                broadcast(text("selection.no-cards", Map.of("number", players.get(player.getUniqueId()).number)));
                return false;
            }
            for (String category : playerCards.keySet()) {
                if (!revealed.getOrDefault(player.getUniqueId(), new LinkedHashSet<>()).contains(category)) {
                    available.add(category);
                }
            }
            if (available.isEmpty()) {
                broadcast(text("selection.no-cards", Map.of("number", players.get(player.getUniqueId()).number)));
                return false;
            }
            Inventory inventory = Bukkit.createInventory(new SelectionHolder(id, available), 27,
                    text("selection.title", null));
            for (int index = 0; index < available.size(); index++) {
                String category = available.get(index);
                Characteristic card = playerCards.get(category);
                inventory.setItem(index + 9, item(materialForCategory(category), ChatColor.GOLD + category,
                        List.of(text("selection.reveal", Map.of("name", card.name())),
                                text("selection.click", null))));
            }
            inventory.setItem(26, item(Material.BARRIER, text("selection.exit", null),
                    List.of(text("selection.exit-lore", null))));
            player.openInventory(inventory);
            return true;
        }

        private void reveal(UUID uuid, String category) {
            if (phase != Phase.DISCUSSION || !uuid.equals(currentSpeaker)) {
                return;
            }
            Map<String, Characteristic> playerCards = cards.get(uuid);
            Set<String> playerRevealed = revealed.computeIfAbsent(uuid, ignored -> new LinkedHashSet<>());
            if (playerCards == null || !playerCards.containsKey(category) || !playerRevealed.add(category)) {
                return;
            }
            currentTurnRevealed = true;
            requestSave();
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.closeInventory();
            }
            Characteristic card = playerCards.get(category);
            GamePlayer participant = players.get(uuid);
            broadcast(text("selection.revealed", Map.of("number", participant.number,
                    "category", category, "name", card.name())));
        }

        private void revealFirst(UUID uuid) {
            if (!uuid.equals(currentSpeaker) || phase != Phase.DISCUSSION) {
                return;
            }
            Map<String, Characteristic> playerCards = cards.get(uuid);
            if (playerCards == null) {
                return;
            }
            for (String category : playerCards.keySet()) {
                if (!revealed.getOrDefault(uuid, new LinkedHashSet<>()).contains(category)) {
                    reveal(uuid, category);
                    return;
                }
            }
        }

        private void endTurn() {
            if (phase != Phase.DISCUSSION || currentSpeaker == null || !alive.contains(currentSpeaker)) {
                return;
            }
            revealFirst(currentSpeaker);
            currentSpeakerIndex++;
            nextSpeaker();
        }

        private void passTurn(UUID uuid) {
            if (uuid.equals(host) && (phase == Phase.STUDY || phase == Phase.OPEN_DISCUSSION)) {
                skipPhase(uuid);
                return;
            }
            if (phase != Phase.DISCUSSION || !uuid.equals(currentSpeaker)) {
                message(uuid, "§cСейчас не ваш ход.");
                return;
            }
            cancelTask(phaseTask);
            endTurn();
        }

        private void skipPhase(UUID uuid) {
            if (!uuid.equals(host)) {
                message(uuid, "§cТолько ведущий может досрочно завершать этот этап.");
                return;
            }
            if (phase != Phase.STUDY && phase != Phase.OPEN_DISCUSSION) {
                message(uuid, "§cКоманда /bunker pass доступна ведущему только во время изучения или общего обсуждения.");
                return;
            }
            long elapsedSeconds = (System.currentTimeMillis() - phaseStartedAtMillis) / 1000L;
            if (elapsedSeconds < hostSkipDelaySeconds) {
                message(uuid, "§cДосрочно завершить этап можно через "
                        + (hostSkipDelaySeconds - elapsedSeconds) + " сек.");
                return;
            }
            cancelTask(phaseTask);
            cancelTask(countdownTask);
            if (phase == Phase.STUDY) {
                broadcast(ChatColor.GOLD + "Ведущий досрочно завершил изучение характеристик.");
                beginDiscussion();
            } else {
                broadcast(ChatColor.GOLD + "Ведущий досрочно завершил общее обсуждение.");
                beginVoting();
            }
        }

        private void openCurrentInterface(Player player) {
            UUID uuid = player.getUniqueId();
            if (phase == Phase.DISCUSSION && uuid.equals(currentSpeaker) && !currentTurnRevealed) {
                openSelection(player);
                return;
            }
            if (phase == Phase.VOTING && alive.contains(uuid) && !votes.containsKey(uuid)) {
                openVoting(uuid);
                return;
            }
            message(uuid, "Сейчас для вас нет доступного интерфейса выбора.");
        }

        private void beginOpenDiscussion() {
            phase = Phase.OPEN_DISCUSSION;
            currentSpeaker = null;
            phaseStartedAtMillis = System.currentTimeMillis();
            requestSave();
            broadcast(text("phase.all-revealed", null));
            broadcast(text("phase.open-discussion", Map.of("seconds", openDiscussionSeconds)));
            broadcast(text("phase.host-skip", Map.of("seconds", hostSkipDelaySeconds)));
            startCountdown("Общее обсуждение перед голосованием", openDiscussionSeconds);
            cancelTask(phaseTask);
            phaseTask = Bukkit.getScheduler().runTaskLater(plugin, this::beginVoting, openDiscussionSeconds * 20L);
        }

        private void beginVoting() {
            phase = Phase.VOTING;
            runoffVoting = false;
            runoffCandidates.clear();
            votes.clear();
            requestSave();
            broadcast(text("phase.voting", null));
            broadcast(text("phase.vote-options", Map.of("remaining", skipCount - skipUses)));
            broadcast(text("phase.vote-hint", null));
            for (UUID uuid : new ArrayList<>(alive)) {
                openVoting(uuid);
            }
            cancelTask(phaseTask);
            startCountdown("Голосование", votingSeconds);
            phaseTask = Bukkit.getScheduler().runTaskLater(plugin, this::resolveVoting, votingSeconds * 20L);
        }

        private void openVoting(UUID voterUuid) {
            Player player = Bukkit.getPlayer(voterUuid);
            if (player == null) {
                return;
            }
            Inventory inventory = Bukkit.createInventory(new VoteHolder(id), 27, text("vote-menu.title", null));
            VoteHolder holder = (VoteHolder) inventory.getHolder();
            int slot = 0;
            for (UUID uuid : alive) {
                GamePlayer participant = players.get(uuid);
                if (!runoffVoting || runoffCandidates.contains(participant.number)) {
                    holder.choices.put(slot, participant.number);
                    inventory.setItem(slot++, item(Material.PLAYER_HEAD,
                            text("vote-menu.player", Map.of("number", participant.number, "player", participant.name)),
                            List.of(text("vote-menu.choose", null))));
                }
            }
            if (!runoffVoting && skipUses < skipCount) {
                holder.choices.put(18, 0);
                inventory.setItem(18, item(Material.BARRIER, text("vote-menu.skip", null),
                        List.of(text("vote-menu.skips-left", Map.of("remaining", skipCount - skipUses)))));
            }
            inventory.setItem(26, item(Material.BARRIER, text("vote-menu.exit", null),
                    List.of(text("vote-menu.exit-lore", null))));
            player.openInventory(inventory);
        }

        private void castVote(UUID voter, int targetNumber) {
            if (phase != Phase.VOTING || !alive.contains(voter) || votes.containsKey(voter)) {
                return;
            }
            if (runoffVoting && targetNumber == 0) {
                message(voter, "В повторном голосовании пропуск недоступен.");
                return;
            }
            if (targetNumber == 0 && skipUses >= skipCount) {
                message(voter, text("vote.no-skips", null));
                return;
            }
            UUID target = findPlayerByNumber(targetNumber);
            if (targetNumber != 0 && (target == null || !alive.contains(target)
                    || (runoffVoting && !runoffCandidates.contains(targetNumber)))) {
                message(voter, text("vote.invalid-target", null));
                return;
            }
            votes.put(voter, targetNumber);
            requestSave();
            Player player = Bukkit.getPlayer(voter);
            if (player != null) {
                player.closeInventory();
                player.sendMessage(text("vote.accepted", null));
            }
            if (votes.size() >= alive.size()) {
                resolveVoting();
            }
        }

        // Применяет голоса, пропуск или случайное выбывание.
        private void resolveVoting() {
            if (phase != Phase.VOTING) {
                return;
            }
            phase = Phase.RESOLVING;
            requestSave();
            cancelTask(phaseTask);
            int skipVotes = 0;
            Map<Integer, Integer> counts = new LinkedHashMap<>();
            for (int value : votes.values()) {
                if (value == 0) {
                    skipVotes++;
                } else {
                    counts.merge(value, 1, Integer::sum);
                }
            }
            int actualVotes = counts.values().stream().mapToInt(Integer::intValue).sum();
            int nonVotes = alive.size() - votes.size();
            int halfOrMore = (alive.size() + 1) / 2;
            boolean enoughPlayerVotes = actualVotes >= halfOrMore;
            boolean skipSupport = skipVotes + nonVotes >= halfOrMore;

            if (!runoffVoting && skipSupport && !enoughPlayerVotes && skipUses < skipCount) {
                skipUses++;
                broadcast(text("vote.skip-threshold", Map.of("used", skipUses, "total", skipCount)));
                nextRound();
                return;
            }
            int maxVotes = counts.values().stream().max(Integer::compareTo).orElse(0);
            List<Integer> winners = new ArrayList<>();
            for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
                if (entry.getValue() == maxVotes) {
                    winners.add(entry.getKey());
                }
            }
            if (winners.isEmpty()) {
                if (skipUses >= skipCount || runoffVoting) {
                    UUID eliminated = alive.get(random.nextInt(alive.size()));
                    GamePlayer participant = players.get(eliminated);
                    alive.remove(eliminated);
                    String reason = skipUses >= skipCount
                            ? "Пропуски уже использованы"
                            : "в повторном голосовании никто не проголосовал";
                    broadcast(ChatColor.RED + "Ни один игрок не получил голоса: " + reason
                            + ", поэтому случайно выбывает игрок №"
                            + participant.number + " (" + participant.name + ").");
                    if (alive.size() <= winnersCount) {
                        finish();
                    } else {
                        nextRound();
                    }
                } else {
                    broadcast(ChatColor.GOLD + "Ни один игрок не получил голосов. Никто не выбывает.");
                    nextRound();
                }
                return;
            }
            if (!runoffVoting && winners.size() > 1) {
                beginRunoff(winners);
                return;
            }
            if (runoffVoting && winners.size() > 1) {
                broadcast(ChatColor.AQUA + "В повторном голосовании снова ничья. Повторяем его.");
                beginRunoff(winners);
                return;
            }
            int eliminatedNumber = winners.get(random.nextInt(winners.size()));
            UUID eliminated = findPlayerByNumber(eliminatedNumber);
            alive.remove(eliminated);
            GamePlayer participant = players.get(eliminated);
            broadcast(ChatColor.RED + "Игрок №" + participant.number + " (" + participant.name + ") покидает бункер.");
            if (alive.size() <= winnersCount) {
                finish();
            } else {
                nextRound();
            }
        }

        private void beginRunoff(List<Integer> candidates) {
            phase = Phase.VOTING;
            runoffVoting = true;
            runoffCandidates.clear();
            runoffCandidates.addAll(candidates);
            votes.clear();
            requestSave();
            broadcast(ChatColor.AQUA + "Ничья. Запускается обязательное повторное голосование между игроками: "
                    + candidates + ". Пропуск недоступен.");
            for (UUID uuid : new ArrayList<>(alive)) {
                openVoting(uuid);
            }
            cancelTask(phaseTask);
            startCountdown("Повторное голосование", votingSeconds);
            phaseTask = Bukkit.getScheduler().runTaskLater(plugin, this::resolveVoting, votingSeconds * 20L);
        }

        private void startCountdown(String label, int seconds) {
            cancelTask(countdownTask);
            final int[] remaining = {seconds};
            sendActionBar(label, remaining[0]);
            countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                remaining[0]--;
                if (remaining[0] <= 0) {
                    cancelTask(countdownTask);
                    return;
                }
                sendActionBar(label, remaining[0]);
            }, 20L, 20L);
        }

        private void sendActionBar(String label, int seconds) {
            String message = configManager.message("phase.countdown", Map.of("label", label, "time", formatTime(seconds)));
            for (UUID uuid : players.keySet()) {
                if (started && !alive.contains(uuid)) {
                    continue;
                }
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    player.sendActionBar(message);
                }
            }
        }

        private String formatTime(int seconds) {
            return String.format("%02d:%02d", seconds / 60, seconds % 60);
        }

        private void nextRound() {
            cancelTask(phaseTask);
            cancelTask(countdownTask);
            phase = Phase.RESOLVING;
            saveState();
            phaseTask = Bukkit.getScheduler().runTaskLater(plugin, this::beginDiscussion, 40L);
        }

        private void finish() {
            if (phase == Phase.FINISHED) {
                return;
            }
            cancelTask(phaseTask);
            cancelTask(lobbyTimeout);
            cancelTask(countdownTask);
            phase = Phase.FINISHED;
            saveState();
            broadcast(ChatColor.GOLD + "Игра завершена! Победители:");
            for (UUID uuid : alive) {
                GamePlayer participant = players.get(uuid);
                broadcast(ChatColor.AQUA + "№" + participant.number + " — " + participant.name);
            }
            broadcast(ChatColor.GOLD + "Полные характеристики всех участников:");
            for (UUID uuid : players.keySet()) {
                GamePlayer participant = players.get(uuid);
                broadcast(ChatColor.AQUA + "№" + participant.number + " — " + participant.name + ":");
                Map<String, Characteristic> playerCards = cards.get(uuid);
                if (playerCards != null) {
                    for (Map.Entry<String, Characteristic> entry : playerCards.entrySet()) {
                        broadcast(ChatColor.GOLD + entry.getKey() + ": " + ChatColor.WHITE + entry.getValue().name());
                    }
                }
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> removeGame(this), 100L);
        }

        private void playerQuit(UUID uuid) {
            if (!started) {
                if (uuid.equals(host)) {
                    cancel("Игра отменена: ведущий вышел с сервера.");
                } else {
                    removePlayer(uuid);
                }
                return;
            }
            leaveDuringGame(uuid);
        }

        private void leaveDuringGame(UUID uuid) {
            if (!alive.contains(uuid) || phase == Phase.FINISHED) {
                return;
            }
            int removedIndex = alive.indexOf(uuid);
            boolean wasCurrentSpeaker = uuid.equals(currentSpeaker);
            int removedNumber = players.get(uuid).number;
            alive.remove(uuid);
            votes.remove(uuid);
            votes.values().removeIf(number -> number == removedNumber);
            GamePlayer participant = players.get(uuid);
            broadcast(ChatColor.RED + participant.name + " покинул игру и считается выбывшим.");

            if (alive.size() <= winnersCount) {
                finish();
                return;
            }
            if (uuid.equals(host)) {
                host = alive.get(0);
                GamePlayer newHost = players.get(host);
                broadcast(text("game.host-transferred", Map.of(
                        "old_host", participant.name, "number", newHost.number, "player", newHost.name)));
                requestSave();
            }
            if (phase == Phase.DISCUSSION) {
                if (!wasCurrentSpeaker && removedIndex < currentSpeakerIndex) {
                    currentSpeakerIndex--;
                }
                if (wasCurrentSpeaker) {
                    cancelTask(phaseTask);
                    nextSpeaker();
                }
            } else if (phase == Phase.VOTING && votes.size() >= alive.size()) {
                resolveVoting();
            }
        }

        private String phaseName() {
            return switch (phase) {
                case LOBBY -> "ожидание игроков";
                case STUDY -> "изучение характеристик";
                case DISCUSSION -> "обсуждение";
                case OPEN_DISCUSSION -> "общее обсуждение";
                case VOTING -> "голосование";
                case RESOLVING -> "обработка голосования";
                case FINISHED -> "завершена";
            };
        }

        private void sendCards(UUID uuid, CommandSender commandSender) {
            Map<String, Characteristic> playerCards = cards.get(uuid);
            if (playerCards == null) {
                return;
            }
            CommandSender target = commandSender;
            if (target == null) {
                target = Bukkit.getPlayer(uuid);
            }
            if (target == null) {
                return;
            }
            target.sendMessage(withPrefix(ChatColor.AQUA + "Характеристики игрока " + players.get(uuid).name + ":"));
            for (Map.Entry<String, Characteristic> entry : playerCards.entrySet()) {
                Characteristic card = entry.getValue();
                target.sendMessage(withPrefix(ChatColor.GOLD + entry.getKey() + ": " + ChatColor.WHITE + card.name()));
                target.sendMessage(withPrefix(ChatColor.GRAY + "  " + card.description()));
            }
        }

        private void openPlayers(Player viewer) {
            Inventory inventory = Bukkit.createInventory(new PlayersHolder(), 54,
                    ChatColor.DARK_AQUA + "Игроки и раскрытые карты");
            int slot = 0;
            for (UUID uuid : players.keySet()) {
                GamePlayer participant = players.get(uuid);
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Номер: " + participant.number);
                if (!alive.contains(uuid)) {
                    lore.add(ChatColor.RED + "Игрок покинул бункер");
                }

                Set<String> playerRevealed = revealed.get(uuid);
                Map<String, Characteristic> playerCards = cards.get(uuid);
                if (playerRevealed == null || playerRevealed.isEmpty()) {
                    lore.add(ChatColor.DARK_GRAY + "Нет раскрытых характеристик");
                } else {
                    lore.add(ChatColor.AQUA + "Раскрытые характеристики:");
                    for (String category : playerRevealed) {
                        Characteristic card = playerCards.get(category);
                        lore.add(ChatColor.GOLD + category + ": " + ChatColor.WHITE + card.name());
                    }
                }

                ItemStack head = item(Material.PLAYER_HEAD,
                        (alive.contains(uuid) ? ChatColor.AQUA : ChatColor.GRAY)
                                + "№" + participant.number + " — " + participant.name,
                        lore);
                SkullMeta skullMeta = (SkullMeta) head.getItemMeta();
                skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
                head.setItemMeta(skullMeta);
                inventory.setItem(slot++, head);
            }
            viewer.openInventory(inventory);
        }

        private UUID findPlayerByNumber(int number) {
            for (Map.Entry<UUID, GamePlayer> entry : players.entrySet()) {
                if (entry.getValue().number == number) {
                    return entry.getKey();
                }
            }
            return null;
        }

        private void broadcast(String message) {
            for (UUID uuid : players.keySet()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    player.sendMessage(withPrefix(message));
                }
            }
        }

        private void message(UUID uuid, String message) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.sendMessage(withPrefix(message));
            }
        }

        private String withPrefix(String message) {
            return messages.withPrefix(message);
        }

        private void cancel(String reason) {
            cancelTasks();
            broadcast(ChatColor.RED + reason);
            removeGame(this);
        }

        private void cancelTasks() {
            cancelTask(lobbyTimeout);
            cancelTask(phaseTask);
            cancelTask(countdownTask);
        }

        private void cancelTask(BukkitTask task) {
            if (task != null) {
                task.cancel();
            }
        }
    }
    private void removeGame(Game game) {
        gamesByArena.remove(game.arenaId, game);
        for (UUID uuid : game.players.keySet()) {
            playerGames.remove(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && (player.getOpenInventory().getTopInventory().getHolder() instanceof SelectionHolder
                    || player.getOpenInventory().getTopInventory().getHolder() instanceof VoteHolder
                    || player.getOpenInventory().getTopInventory().getHolder() instanceof PlayersHolder)) {
                player.closeInventory();
            }
        }
        requestSave();
    }

    private enum GameStatus { FREE, WAITING, IN_GAME }

    private enum Phase { LOBBY, STUDY, DISCUSSION, OPEN_DISCUSSION, VOTING, RESOLVING, FINISHED }

    private record Actor(UUID uuid, String name) {
    }

    private static final class SelectionHolder implements InventoryHolder {
        private final String gameId;
        private final List<String> categories;

        private SelectionHolder(String gameId, List<String> categories) {
            this.gameId = gameId;
            this.categories = categories;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static final class ArenaHolder implements InventoryHolder {
        private final boolean joinMode;
        private final int page;
        private final List<String> arenaIds;
        private final Map<Integer, String> arenas = new LinkedHashMap<>();
        private int randomSlot = -1;

        private ArenaHolder(boolean joinMode, int page, List<String> arenaIds) {
            this.joinMode = joinMode;
            this.page = page;
            this.arenaIds = arenaIds;
        }

        private int maxPage() {
            return Math.max(0, (arenaIds.size() - 1) / 45);
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static final class VoteHolder implements InventoryHolder {
        private final String gameId;
        private final Map<Integer, Integer> choices = new LinkedHashMap<>();

        private VoteHolder(String gameId) {
            this.gameId = gameId;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static final class PlayersHolder implements InventoryHolder {
        private PlayersHolder() {
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
