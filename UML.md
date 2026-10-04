# UML-диаграммы проекта Bunker

Диаграммы отражают текущую реализацию проекта. Они используют Mermaid и отображаются в GitHub, GitLab, многих IDE и Markdown-просмотрщиках.

## 1. Диаграмма классов

```mermaid
classDiagram
    class JavaPlugin {
        <<Paper API>>
    }
    class Listener {
        <<Bukkit API>>
    }
    class CommandExecutor {
        <<Bukkit API>>
    }

    class BunkerPlugin {
        -ConfigManager configManager
        -GameManager gameManager
        +onEnable()
        +onDisable()
    }
    class Commands {
        -GameManager gameManager
        +onCommand(sender, command, label, args) boolean
    }
    class ConfigManager {
        -Map characteristicSets
        -List catastrophes
        -FileConfiguration messages
        +load()
        +reloadFiles()
        +integer(path, fallback) int
        +bool(path, fallback) boolean
        +message(key, placeholders) String
        +characteristicSets() Map
        +catastrophes() List
    }
    class MessageService {
        -ConfigManager config
        -String prefix
        +reload()
        +get(key, placeholders) String
        +withPrefix(message) String
    }
    class GameManager {
        -Map arenas
        -Map gamesByArena
        -Map playerGames
        +handleCommand(sender, command, label, args) boolean
        +reload()
        +restore()
        +shutdown()
    }
    class Game {
        -String id
        -String arenaId
        -Arena arena
        -UUID host
        -Map players
        -List alive
        -Map cards
        -Map revealed
        -Phase phase
        -UUID currentSpeaker
        -boolean currentTurnRevealed
        -boolean runoffVoting
        +start()
        +cancel(reason)
        +castVote(voter, targetNumber)
        +passTurn(uuid)
        +leaveDuringGame(uuid)
    }
    class Arena {
        +String id
        +String name
        +String description
        +Material icon
        +Location start
        +Set allowedCommands
    }
    class GamePlayer {
        +UUID uuid
        +String name
        +int number
        +Status status
        +Statistics statistics
    }
    class Characteristic {
        +String name
        +String description
    }
    class Phase {
        <<enumeration>>
        LOBBY
        STUDY
        DISCUSSION
        OPEN_DISCUSSION
        VOTING
        RESOLVING
        FINISHED
    }

    BunkerPlugin --|> JavaPlugin
    GameManager ..|> Listener
    Commands ..|> CommandExecutor
    BunkerPlugin *-- ConfigManager
    BunkerPlugin *-- GameManager
    Commands --> GameManager
    GameManager --> ConfigManager
    GameManager *-- MessageService
    GameManager o-- Arena
    GameManager o-- Game
    Game *-- GamePlayer
    Game *-- Characteristic
    Game --> Arena
    Game --> Phase
    MessageService --> ConfigManager
```

## 2. Компоненты и зависимости

```mermaid
flowchart LR
    player[Player or Console]
    paper[Paper Bukkit API]
    plugin[BunkerPlugin]
    commands[Commands]
    manager[GameManager]
    config[ConfigManager]
    messages[MessageService]
    scheduler[Bukkit Scheduler]
    configFile[(config.yml)]
    messagesFile[(messages.yml)]
    characteristics[(characteristics.json)]
    catastrophes[(catastrophes.json)]
    state[(games.yml)]
    backup[(games.yml.bak)]

    player --> paper
    paper --> commands
    paper --> manager
    plugin --> commands
    plugin --> manager
    commands --> manager
    manager --> config
    manager --> messages
    manager --> scheduler
    config --> configFile
    config --> messagesFile
    config --> characteristics
    config --> catastrophes
    manager --> state
    manager --> backup
```

## 3. Жизненный цикл игровой комнаты

```mermaid
stateDiagram-v2
    [*] --> LOBBY: create
    LOBBY --> LOBBY: join / leave
    LOBBY --> LOBBY: lobby timeout resets room
    LOBBY --> STUDY: host start and min players reached
    LOBBY --> [*]: host cancel or host quit

    STUDY --> DISCUSSION: timer or host pass
    DISCUSSION --> DISCUSSION: next living speaker
    DISCUSSION --> OPEN_DISCUSSION: all living speakers finished
    OPEN_DISCUSSION --> VOTING: timer or host pass
    VOTING --> RESOLVING: timer or all votes received
    RESOLVING --> DISCUSSION: no elimination or next round
    RESOLVING --> VOTING: tie starts runoff
    RESOLVING --> FINISHED: living players <= winners count

    STUDY --> FINISHED: exits leave too few players
    DISCUSSION --> FINISHED: exits leave too few players
    OPEN_DISCUSSION --> FINISHED: exits leave too few players
    VOTING --> FINISHED: exits leave too few players
    FINISHED --> [*]: delayed room removal
```

## 4. Создание игры и подключение игроков

```mermaid
sequenceDiagram
    actor Player
    participant Paper as Paper API
    participant Commands
    participant Manager as GameManager
    participant Config as ConfigManager
    participant Scheduler

    Player->>Paper: /bunker create <arenaId>
    Paper->>Commands: onCommand(...)
    Commands->>Manager: handleCommand(...)
    Manager->>Config: read creation-enabled and settings
    Manager->>Manager: validate ID, arena, start point, free arena
    Manager->>Manager: create Game and host GamePlayer
    Manager->>Scheduler: schedule lobby timeout and countdown
    Manager-->>Player: created, connect and lobby messages

    Player->>Paper: /bunker join <arenaId>
    Paper->>Commands: onCommand(...)
    Commands->>Manager: handleCommand(...)
    Manager->>Manager: check lobby, capacity and player membership
    Manager->>Manager: add GamePlayer and playerGames entry
    Manager-->>Player: joined message
```

## 5. Запуск, ход и голосование

```mermaid
sequenceDiagram
    actor Host
    actor Player
    participant Game
    participant Scheduler
    participant Storage as games.yml

    Host->>Game: /bunker start
    Game->>Game: validate players and characteristics
    Game->>Game: set STUDY, deal cards, fill alive
    Game->>Scheduler: schedule study timeout
    Game->>Storage: save state

    Scheduler->>Game: beginDiscussion
    loop Each living speaker
        Game->>Player: open characteristic selection
        Player->>Game: reveal one category or /bunker pass
        Game->>Game: mark category revealed
        Game->>Storage: deferred save
    end

    Game->>Game: beginOpenDiscussion
    Scheduler->>Game: beginVoting
    loop Each living voter
        Player->>Game: vote number or skip
        Game->>Storage: deferred save
    end
    Game->>Game: resolve votes
    alt Tie
        Game->>Game: beginRunoff for tied candidates
    else Elimination
        Game->>Game: remove one living player
    end
    alt Enough living players remain
        Game->>Game: nextRound
    else Two or fewer remain
        Game->>Game: finish and show final cards
    end
```

## 6. Рестарт и восстановление текущего хода

```mermaid
sequenceDiagram
    participant GameManager
    participant Game
    participant Storage as games.yml
    participant Paper as Paper API

    GameManager->>Game: periodic or deferred save
    Game->>Storage: write phase, players, cards and revealed
    Game->>Storage: write current-speaker, index, turn-revealed

    Note over Paper: Server restarts
    Paper->>GameManager: onEnable
    GameManager->>Storage: load games.yml or backup
    GameManager->>Game: restore saved room
    GameManager->>Game: restartPhaseAfterLoad

    alt DISCUSSION
        Game->>Game: find saved living current speaker
        Game->>Game: preserve revealed state
        Game->>Game: resume current turn and timer
    else Other phase
        Game->>Game: restart phase with full configured timer
    end
```

## 7. Сохранение данных комнаты

```mermaid
classDiagram
    class SavedGame {
        +String host
        +String arena
        +boolean started
        +Phase phase
        +int round
        +int skipUses
        +String catastrophe
        +String currentSpeaker
        +int currentSpeakerIndex
        +boolean currentTurnRevealed
        +List alive
    }
    class SavedPlayer {
        +String name
        +int number
        +Status status
        +List revealed
    }
    class SavedCard {
        +String name
        +String description
    }
    class GamesFile {
        +Map games
    }

    GamesFile "1" *-- "many" SavedGame
    SavedGame "1" *-- "many" SavedPlayer
    SavedGame "1" *-- "many" SavedCard
    SavedGame --> Phase
```

Фактически эти данные записываются в YAML-раздел `games`. `games.yml.bak` содержит предыдущую успешно сохранённую версию. При загрузке повреждённые комнаты отбрасываются, а раскрытые категории без соответствующей карты очищаются.

## 8. Обработка команд во время игры

```mermaid
flowchart TD
    command[PlayerCommandPreprocessEvent]
    admin{bunker.admin and admin command}
    game{Player has started game}
    internal{Command is bunker}
    allowed{Command in arena allowed-commands}
    spawn{Command is spawn and player alive}
    eliminate[Mark voluntary elimination]
    allow[Allow command]
    block[Cancel event and send blocked message]

    command --> admin
    admin -->|yes| allow
    admin -->|no| game
    game -->|no| allow
    game -->|yes| internal
    internal -->|yes| allow
    internal -->|no| allowed
    allowed -->|yes| spawn
    spawn -->|yes| eliminate
    spawn -->|no| allow
    eliminate --> allow
    allowed -->|no| block
```

## 9. Развёртывание

```mermaid
flowchart TB
    source[Java 17 source]
    gradle[Gradle build]
    jar[Bunker-0.1.0.jar]
    server[Paper server]
    pluginFolder[plugins/Bunker]
    runtimeConfig[config.yml and messages.yml]
    runtimeData[JSON data and games.yml]
    clients[ Minecraft players ]

    source --> gradle
    gradle --> jar
    jar --> pluginFolder
    pluginFolder --> server
    server --> runtimeConfig
    server --> runtimeData
    clients --> server
```

## Ограничения текущих диаграмм

- `runoffVoting` и список кандидатов повторного голосования не сохраняются и после рестарта начинают обычное голосование.
- Таймер текущей фазы после рестарта начинается заново с полным значением.
- Изменение настроек арены через reload не переписывает объект арены уже созданной комнаты.
