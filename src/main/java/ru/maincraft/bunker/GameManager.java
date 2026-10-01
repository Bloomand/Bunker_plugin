package ru.maincraft.bunker;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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

/** Управляет комнатами, этапами игры, таймерами, GUI и голосованием. */
@SuppressWarnings("deprecation")
public final class GameManager implements Listener {
    private static final int MIN_PLAYERS = 6;
    private static final int MAX_PLAYERS = 12;
    private static final int WINNERS_COUNT = 2;
    private static final int LOBBY_SECONDS = 10 * 60;
    private static final int STUDY_SECONDS = 2 * 60;
    private static final int SPEECH_SECONDS = 3 * 60;
    private static final int OPEN_DISCUSSION_SECONDS = 2 * 60;
    private static final int VOTING_SECONDS = 60;
    private static final int HOST_SKIP_DELAY_SECONDS = 30;
    private static final String PREFIX = ChatColor.GOLD + "[Bunker] " + ChatColor.WHITE;

    private final BunkerPlugin plugin;
    private final ConfigManager configManager;
    private final Map<String, Game> games = new LinkedHashMap<>();
    private final Map<UUID, Game> playerGames = new LinkedHashMap<>();
    private final Random random = new Random();
    private int nextGameId = 1;

    public GameManager(BunkerPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    public void shutdown() {
        for (Game game : new ArrayList<>(games.values())) {
            game.cancelTasks();
        }
    }

    public boolean handleCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }

        Actor actor = resolveActor(sender);
        if (actor == null) {
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "create" -> create(sender, actor);
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

    private Actor resolveActor(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PREFIX + "Команды игры доступны игроку.");
            return null;
        }
        return new Actor(player.getUniqueId(), player.getName());
    }

    private void create(CommandSender sender, Actor actor) {
        if (playerGames.containsKey(actor.uuid())) {
            sender.sendMessage(PREFIX + "Этот игрок уже состоит в игре.");
            return;
        }

        String id = String.valueOf(nextGameId++);
        Game game = new Game(id, actor);
        games.put(id, game);
        playerGames.put(actor.uuid(), game);
        game.lobbyTimeout = Bukkit.getScheduler().runTaskLater(plugin,
                () -> game.cancel("Игра автоматически отменена: за 10 минут не набралось готовое лобби."),
                LOBBY_SECONDS * 20L);
        game.startCountdown("Ожидание игроков", LOBBY_SECONDS);

        sender.sendMessage(PREFIX + ChatColor.GOLD + "Игра создана для " + actor.name() + ". ID: " + ChatColor.YELLOW + id);
        sender.sendMessage(PREFIX + "Подключение: /bunker join " + id);
        sender.sendMessage(PREFIX + "Начать: /bunker start. Отменить: /bunker cancel.");
    }

    private void join(CommandSender sender, Actor actor, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(PREFIX + "Укажите ID игры: /bunker join <ID>");
            return;
        }
        if (playerGames.containsKey(actor.uuid())) {
            sender.sendMessage(PREFIX + "Этот игрок уже состоит в игре.");
            return;
        }
        Game game = games.get(args[1]);
        if (game == null) {
            sender.sendMessage(PREFIX + "Игра с таким ID не найдена.");
            return;
        }
        if (game.started) {
            sender.sendMessage(PREFIX + "Эта игра уже началась.");
            return;
        }
        if (game.players.size() >= MAX_PLAYERS) {
            sender.sendMessage(PREFIX + "В игре уже максимум игроков: " + MAX_PLAYERS + ".");
            return;
        }

        game.addPlayer(actor);
        playerGames.put(actor.uuid(), game);
        game.broadcast(actor.name() + " присоединился. Игроков: " + game.players.size() + "/" + MAX_PLAYERS + ".");
    }

    private void leave(CommandSender sender, Actor actor) {
        Game game = playerGames.get(actor.uuid());
        if (game == null) {
            sender.sendMessage(PREFIX + "Этот игрок не состоит ни в одной игре.");
        } else if (game.started) {
            game.leaveDuringGame(actor.uuid());
            playerGames.remove(actor.uuid());
        } else if (game.host.equals(actor.uuid())) {
            game.cancel("Игра отменена: ведущий покинул лобби.");
        } else {
            game.removePlayer(actor.uuid());
            playerGames.remove(actor.uuid());
            game.broadcast(actor.name() + " покинул игру. Игроков: " + game.players.size() + ".");
        }
    }

    private void start(CommandSender sender, Actor actor) {
        Game game = playerGames.get(actor.uuid());
        if (game == null) {
            sender.sendMessage(PREFIX + "Сначала создайте игру: /bunker create");
        } else if (game.host.equals(actor.uuid())) {
            game.start();
        } else {
            sender.sendMessage(PREFIX + "Только ведущий может начать игру.");
        }
    }

    private void cancel(CommandSender sender, Actor actor) {
        Game game = playerGames.get(actor.uuid());
        if (game == null) {
            sender.sendMessage(PREFIX + "Этот игрок не состоит ни в одной игре.");
        } else if (game.host.equals(actor.uuid())) {
            game.cancel("Игра отменена ведущим.");
        } else {
            sender.sendMessage(PREFIX + "Только ведущий может отменить игру.");
        }
    }

    private void status(CommandSender sender, Actor actor) {
        if (playerGames.containsKey(actor.uuid())) {
            Game game = playerGames.get(actor.uuid());
            int alivePlayers = game.started ? game.alive.size() : game.players.size();
            sender.sendMessage(PREFIX + "Игра " + game.id + ": " + game.phaseName()
                    + ". Всего игроков: " + game.players.size()
                    + ". Выживших: " + alivePlayers + ".");
        } else {
            sender.sendMessage(PREFIX + "Вы не состоите в игре. Открытых игр: " + games.size() + ".");
        }
    }

    private void cards(CommandSender sender, Actor actor) {
        Game game = playerGames.get(actor.uuid());
        if (game == null || !game.started) {
            sender.sendMessage(PREFIX + "Игра ещё не началась.");
            return;
        }
        game.sendCards(actor.uuid(), sender);
    }

    private void players(CommandSender sender, Actor actor) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PREFIX + "Интерфейс игроков доступен только игроку.");
            return;
        }
        Game game = playerGames.get(actor.uuid());
        if (game == null || !game.started) {
            player.sendMessage(PREFIX + "Интерфейс игроков доступен после начала игры.");
            return;
        }
        game.openPlayers(player);
    }

    private void open(CommandSender sender, Actor actor) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PREFIX + "Интерфейс доступен только игроку.");
            return;
        }
        Game game = playerGames.get(actor.uuid());
        if (game == null) {
            sender.sendMessage(PREFIX + "Этот игрок не состоит в игре.");
        } else {
            game.openCurrentInterface(player);
        }
    }

    private void voteCommand(CommandSender sender, Actor actor, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(PREFIX + "Использование: /bunker vote <номер игрока или skip>");
            return;
        }
        try {
            Game game = playerGames.get(actor.uuid());
            if (game == null) {
                sender.sendMessage(PREFIX + "Этот игрок не состоит в игре.");
            } else {
                int target = args[1].equalsIgnoreCase("skip") ? 0 : Integer.parseInt(args[1]);
                game.castVote(actor.uuid(), target);
            }
        } catch (NumberFormatException exception) {
            sender.sendMessage(PREFIX + "Укажите номер игрока или слово skip для пропуска голосования.");
        }
    }

    private void pass(CommandSender sender, Actor actor) {
        Game game = playerGames.get(actor.uuid());
        if (game == null) {
            sender.sendMessage(PREFIX + "Этот игрок не состоит в игре.");
            return;
        }
        game.passTurn(actor.uuid());
    }

    private void listGames(CommandSender sender) {
        if (games.isEmpty()) {
            sender.sendMessage(PREFIX + "Сейчас нет открытых игр.");
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "Открытые игры:");
        for (Game game : games.values()) {
            sender.sendMessage(ChatColor.YELLOW + game.id + ChatColor.WHITE + " — " + game.players.size() + "/" + MAX_PLAYERS);
        }
    }

    private void help(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "--- Bunker ---");
        sender.sendMessage(ChatColor.YELLOW + "/bunker create" + ChatColor.WHITE + " — создать игру и стать ведущим");
        sender.sendMessage(ChatColor.YELLOW + "/bunker join <ID>" + ChatColor.WHITE + " — присоединиться к игре");
        sender.sendMessage(ChatColor.YELLOW + "/bunker start" + ChatColor.WHITE + " — начать свою игру");
        sender.sendMessage(ChatColor.YELLOW + "/bunker cancel" + ChatColor.WHITE + " — отменить свою игру");
        sender.sendMessage(ChatColor.YELLOW + "/bunker status" + ChatColor.WHITE + " — статус своей игры");
        sender.sendMessage(ChatColor.YELLOW + "/bunker cards" + ChatColor.WHITE + " — посмотреть свои карты");
        sender.sendMessage(ChatColor.YELLOW + "/bunker players" + ChatColor.WHITE + " — раскрытые карты игроков");
        sender.sendMessage(ChatColor.YELLOW + "/bunker open" + ChatColor.WHITE + " — открыть доступный интерфейс выбора или голосования");
        sender.sendMessage(ChatColor.YELLOW + "/bunker vote <номер или skip>" + ChatColor.WHITE + " — проголосовать или пропустить");
        sender.sendMessage(ChatColor.YELLOW + "/bunker pass" + ChatColor.WHITE + " — закончить свой ход досрочно");
        sender.sendMessage(ChatColor.YELLOW + "/bunker games" + ChatColor.WHITE + " — список открытых игр");
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof SelectionHolder selection) {
            event.setCancelled(true);
            Game game = games.get(selection.gameId);
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
            Game game = games.get(voteHolder.gameId);
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
        private final UUID host;
        private final LinkedHashMap<UUID, Participant> players = new LinkedHashMap<>();
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

        private Game(String id, Actor hostPlayer) {
            this.id = id;
            this.host = hostPlayer.uuid();
            addPlayer(hostPlayer);
        }

        private void addPlayer(Actor actor) {
            int number = 1;
            while (findPlayerByNumber(number) != null) {
                number++;
            }
            players.put(actor.uuid(), new Participant(number, actor.name()));
        }

        private void removePlayer(UUID uuid) {
            players.remove(uuid);
            playerGames.remove(uuid);
        }

        // Запускает игру после проверки состава и данных.
        private void start() {
            if (started) {
                message(host, "Игра уже началась.");
                return;
            }
            if (players.size() < MIN_PLAYERS) {
                message(host, "Нужно минимум " + MIN_PLAYERS + " игроков. Сейчас: " + players.size() + ".");
                return;
            }
            if (!configManager.isCharacteristicsLoaded()) {
                message(host, "Игра не может начаться: файл characteristics.json повреждён или не загружен.");
                return;
            }
            started = true;
            phase = Phase.STUDY;
            phaseStartedAtMillis = System.currentTimeMillis();
            List<String> catastrophes = configManager.catastrophes();
            catastrophe = catastrophes.get(random.nextInt(catastrophes.size()));
            cancelTask(lobbyTimeout);
            alive.clear();
            alive.addAll(players.keySet());
            dealCards();
            broadcast(ChatColor.GOLD + "Игра " + id + " началась! Участников: " + players.size() + ".");
            broadcast(ChatColor.GOLD + "Катастрофа: " + catastrophe);
            broadcast(ChatColor.YELLOW + "У вас есть 2 минуты на изучение характеристик.");
            broadcast(ChatColor.GRAY + "Ведущий сможет завершить этап через 30 секунд командой /bunker pass.");
            broadcast(ChatColor.GRAY + "Подсказка: /bunker cards — ваши карты, /bunker status — текущий этап.");
            for (UUID uuid : alive) {
                sendCards(uuid, null);
            }
            startCountdown("Изучение характеристик", STUDY_SECONDS);
            phaseTask = Bukkit.getScheduler().runTaskLater(plugin, this::beginDiscussion, STUDY_SECONDS * 20L);
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
            if (!started || alive.size() <= WINNERS_COUNT) {
                finish();
                return;
            }
            phase = Phase.DISCUSSION;
            round++;
            currentSpeakerIndex = 0;
            broadcast(ChatColor.AQUA + "Раунд " + round + ". Начинается этап обсуждения.");
            nextSpeaker();
        }

        private void nextSpeaker() {
            if (alive.size() <= WINNERS_COUNT) {
                finish();
                return;
            }
            if (currentSpeakerIndex >= alive.size()) {
                beginOpenDiscussion();
                return;
            }
            currentSpeaker = alive.get(currentSpeakerIndex);
            currentTurnRevealed = false;
            Participant participant = players.get(currentSpeaker);
            broadcast(ChatColor.YELLOW + "Сейчас говорит игрок №" + participant.number + " (" + participant.name + "). Выберите характеристику или используйте /bunker open и /bunker pass.");
            cancelTask(phaseTask);
            cancelTask(countdownTask);
            if (openSelection(Bukkit.getPlayer(currentSpeaker))) {
                startCountdown("Ход игрока №" + participant.number + " | /bunker pass", SPEECH_SECONDS);
                phaseTask = Bukkit.getScheduler().runTaskLater(plugin,
                        this::endTurn, SPEECH_SECONDS * 20L);
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
            for (String category : cards.get(player.getUniqueId()).keySet()) {
                if (!revealed.get(player.getUniqueId()).contains(category)) {
                    available.add(category);
                }
            }
            if (available.isEmpty()) {
                broadcast(ChatColor.GRAY + "У игрока №" + players.get(player.getUniqueId()).number
                        + " больше нет нераскрытых характеристик. Ход передаётся дальше.");
                return false;
            }
            Inventory inventory = Bukkit.createInventory(new SelectionHolder(id, available), 27,
                    ChatColor.DARK_AQUA + "Выберите характеристику");
            for (int index = 0; index < available.size(); index++) {
                String category = available.get(index);
                Characteristic card = cards.get(player.getUniqueId()).get(category);
                inventory.setItem(index + 9, item(materialForCategory(category), ChatColor.GOLD + category,
                        List.of(ChatColor.WHITE + card.name(), ChatColor.GRAY + "Нажмите, чтобы раскрыть")));
            }
            inventory.setItem(26, item(Material.BARRIER, ChatColor.RED + "Выйти из интерфейса",
                    List.of(ChatColor.GRAY + "Ваш выбор не будет отменён")));
            player.openInventory(inventory);
            return true;
        }

        private void reveal(UUID uuid, String category) {
            if (phase != Phase.DISCUSSION || !uuid.equals(currentSpeaker)) {
                return;
            }
            if (!cards.get(uuid).containsKey(category) || !revealed.get(uuid).add(category)) {
                return;
            }
            currentTurnRevealed = true;
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.closeInventory();
            }
            Characteristic card = cards.get(uuid).get(category);
            Participant participant = players.get(uuid);
            broadcast(ChatColor.AQUA + "Игрок №" + participant.number + " раскрыл характеристику "
                    + category + ": " + card.name());
        }

        private void revealFirst(UUID uuid) {
            if (!uuid.equals(currentSpeaker) || phase != Phase.DISCUSSION) {
                return;
            }
            for (String category : cards.get(uuid).keySet()) {
                if (!revealed.get(uuid).contains(category)) {
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
                message(uuid, "Сейчас не ваш ход.");
                return;
            }
            cancelTask(phaseTask);
            endTurn();
        }

        private void skipPhase(UUID uuid) {
            if (!uuid.equals(host)) {
                message(uuid, "Только ведущий может досрочно завершать этот этап.");
                return;
            }
            if (phase != Phase.STUDY && phase != Phase.OPEN_DISCUSSION) {
                message(uuid, "Команда /bunker pass доступна ведущему только во время изучения или общего обсуждения.");
                return;
            }
            long elapsedSeconds = (System.currentTimeMillis() - phaseStartedAtMillis) / 1000L;
            if (elapsedSeconds < HOST_SKIP_DELAY_SECONDS) {
                message(uuid, "Досрочно завершить этап можно через "
                        + (HOST_SKIP_DELAY_SECONDS - elapsedSeconds) + " сек.");
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
            broadcast(ChatColor.AQUA + "Все характеристики этого раунда раскрыты.");
            broadcast(ChatColor.YELLOW + "2 минуты общего обсуждения перед голосованием.");
            broadcast(ChatColor.GRAY + "Ведущий сможет завершить этап через 30 секунд командой /bunker pass.");
            startCountdown("Общее обсуждение перед голосованием", OPEN_DISCUSSION_SECONDS);
            cancelTask(phaseTask);
            phaseTask = Bukkit.getScheduler().runTaskLater(plugin, this::beginVoting, OPEN_DISCUSSION_SECONDS * 20L);
        }

        private void beginVoting() {
            phase = Phase.VOTING;
            runoffVoting = false;
            runoffCandidates.clear();
            votes.clear();
            broadcast(ChatColor.GOLD + "Этап голосования начался.");
            broadcast(ChatColor.GRAY + "Выберите номер игрока или skip для пропуска. Доступно пропусков: " + (2 - skipUses) + ".");
            broadcast(ChatColor.GRAY + "Подсказка: проголосовать можно через меню или /bunker vote <номер>.");
            for (UUID uuid : new ArrayList<>(alive)) {
                openVoting(uuid);
            }
            cancelTask(phaseTask);
            startCountdown("Голосование", VOTING_SECONDS);
            phaseTask = Bukkit.getScheduler().runTaskLater(plugin, this::resolveVoting, VOTING_SECONDS * 20L);
        }

        private void openVoting(UUID voterUuid) {
            Player player = Bukkit.getPlayer(voterUuid);
            if (player == null) {
                return;
            }
            Inventory inventory = Bukkit.createInventory(new VoteHolder(id), 27, ChatColor.DARK_AQUA + "Голосование");
            VoteHolder holder = (VoteHolder) inventory.getHolder();
            int slot = 0;
            for (UUID uuid : alive) {
                Participant participant = players.get(uuid);
                if (!runoffVoting || runoffCandidates.contains(participant.number)) {
                    holder.choices.put(slot, participant.number);
                    inventory.setItem(slot++, item(Material.PLAYER_HEAD,
                            ChatColor.GOLD + "№" + participant.number + " — " + participant.name,
                            List.of(ChatColor.WHITE + "Нажмите, чтобы проголосовать")));
                }
            }
            if (!runoffVoting && skipUses < 2) {
                holder.choices.put(18, 0);
                inventory.setItem(18, item(Material.BARRIER, ChatColor.AQUA + "SKIP — пропустить голосование",
                        List.of(ChatColor.WHITE + "Осталось пропусков: " + (2 - skipUses))));
            }
            inventory.setItem(26, item(Material.BARRIER, ChatColor.RED + "Выйти из интерфейса",
                    List.of(ChatColor.GRAY + "Ваш голос не будет засчитан")));
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
            if (targetNumber == 0 && skipUses >= 2) {
                message(voter, "Все 2 пропуска уже использованы.");
                return;
            }
            UUID target = findPlayerByNumber(targetNumber);
            if (targetNumber != 0 && (target == null || !alive.contains(target)
                    || (runoffVoting && !runoffCandidates.contains(targetNumber)))) {
                message(voter, "Нельзя проголосовать за этот номер.");
                return;
            }
            votes.put(voter, targetNumber);
            Player player = Bukkit.getPlayer(voter);
            if (player != null) {
                player.closeInventory();
                player.sendMessage(PREFIX + "Ваш голос принят.");
            }
            if (votes.size() >= alive.size()) {
                resolveVoting();
            }
        }

        // Применяет голоса, пропуск или случайное выбывание.
        private void resolveVoting() {
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

            if (!runoffVoting && skipSupport && !enoughPlayerVotes && skipUses < 2) {
                skipUses++;
                broadcast(ChatColor.AQUA + "Половина или более участников выбрали 0 или не проголосовали. Голосование пропущено (" + skipUses + "/2).");
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
                if (skipUses >= 2 || runoffVoting) {
                    UUID eliminated = alive.get(random.nextInt(alive.size()));
                    Participant participant = players.get(eliminated);
                    alive.remove(eliminated);
                    String reason = skipUses >= 2
                            ? "Пропуски уже использованы"
                            : "в повторном голосовании никто не проголосовал";
                    broadcast(ChatColor.RED + "Ни один игрок не получил голоса: " + reason
                            + ", поэтому случайно выбывает игрок №"
                            + participant.number + " (" + participant.name + ").");
                    if (alive.size() <= WINNERS_COUNT) {
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
            Participant participant = players.get(eliminated);
            broadcast(ChatColor.RED + "Игрок №" + participant.number + " (" + participant.name + ") покидает бункер.");
            if (alive.size() <= WINNERS_COUNT) {
                finish();
            } else {
                nextRound();
            }
        }

        private void beginRunoff(List<Integer> candidates) {
            runoffVoting = true;
            runoffCandidates.clear();
            runoffCandidates.addAll(candidates);
            votes.clear();
            broadcast(ChatColor.AQUA + "Ничья. Запускается обязательное повторное голосование между игроками: "
                    + candidates + ". Пропуск недоступен.");
            for (UUID uuid : new ArrayList<>(alive)) {
                openVoting(uuid);
            }
            cancelTask(phaseTask);
            startCountdown("Повторное голосование", VOTING_SECONDS);
            phaseTask = Bukkit.getScheduler().runTaskLater(plugin, this::resolveVoting, VOTING_SECONDS * 20L);
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
            String message = ChatColor.YELLOW + label + ChatColor.WHITE + " | Осталось: " + formatTime(seconds);
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
            phaseTask = Bukkit.getScheduler().runTaskLater(plugin, this::beginDiscussion, 40L);
        }

        private void finish() {
            cancelTask(phaseTask);
            cancelTask(lobbyTimeout);
            cancelTask(countdownTask);
            phase = Phase.FINISHED;
            broadcast(ChatColor.GOLD + "Игра завершена! Победители:");
            for (UUID uuid : alive) {
                Participant participant = players.get(uuid);
                broadcast(ChatColor.AQUA + "№" + participant.number + " — " + participant.name);
            }
            broadcast(ChatColor.GOLD + "Полные характеристики всех участников:");
            for (UUID uuid : players.keySet()) {
                Participant participant = players.get(uuid);
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
            Participant participant = players.get(uuid);
            broadcast(ChatColor.RED + participant.name + " покинул игру и считается выбывшим.");

            if (alive.size() <= WINNERS_COUNT) {
                finish();
                return;
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
            target.sendMessage(ChatColor.AQUA + "Характеристики игрока " + players.get(uuid).name + ":");
            for (Map.Entry<String, Characteristic> entry : playerCards.entrySet()) {
                Characteristic card = entry.getValue();
                target.sendMessage(ChatColor.GOLD + entry.getKey() + ": " + ChatColor.WHITE + card.name());
                target.sendMessage(ChatColor.GRAY + "  " + card.description());
            }
        }

        private void openPlayers(Player viewer) {
            Inventory inventory = Bukkit.createInventory(new PlayersHolder(), 54,
                    ChatColor.DARK_AQUA + "Игроки и раскрытые карты");
            int slot = 0;
            for (UUID uuid : players.keySet()) {
                Participant participant = players.get(uuid);
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
            for (Map.Entry<UUID, Participant> entry : players.entrySet()) {
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
                    player.sendMessage(PREFIX + message);
                }
            }
        }

        private void message(UUID uuid, String message) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.sendMessage(PREFIX + message);
            }
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
        games.remove(game.id);
        for (UUID uuid : game.players.keySet()) {
            playerGames.remove(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && (player.getOpenInventory().getTopInventory().getHolder() instanceof SelectionHolder
                    || player.getOpenInventory().getTopInventory().getHolder() instanceof VoteHolder
                    || player.getOpenInventory().getTopInventory().getHolder() instanceof PlayersHolder)) {
                player.closeInventory();
            }
        }
    }

    private enum Phase { LOBBY, STUDY, DISCUSSION, OPEN_DISCUSSION, VOTING, FINISHED }

    private static final class Participant {
        private final int number;
        private final String name;

        private Participant(int number, String name) {
            this.number = number;
            this.name = name;
        }
    }

    private record Actor(UUID uuid, String name) {
    }

    static record Characteristic(String name, String description) {
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
