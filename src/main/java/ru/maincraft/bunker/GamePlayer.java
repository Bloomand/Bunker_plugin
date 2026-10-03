package ru.maincraft.bunker;

import java.util.UUID;

/** Игрок в контексте Bunker: постоянные данные участника конкретной игры. */
final class GamePlayer {
    enum Status {
        WAITING_FOR_GAME,
        IN_GAME
    }

    final UUID uuid;
    final String name;
    int number;
    Status status;

    /*
     * TODO: здесь будет статистика игрока.
     * Планируемые переменные: gamesPlayed, wins, losses, eliminations, votes.
     */
    final Statistics statistics = new Statistics();

    GamePlayer(UUID uuid, String name, int number) {
        this(uuid, name, number, Status.WAITING_FOR_GAME);
    }

    GamePlayer(UUID uuid, String name, int number, Status status) {
        this.uuid = uuid;
        this.name = name;
        this.number = number;
        this.status = status;
    }

    static final class Statistics {
        // Статистика будет добавлена позже.
    }
}
