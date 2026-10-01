package ru.maincraft.bunker;

import java.util.UUID;

/** Игрок в контексте Bunker: постоянные данные участника конкретной игры. */
final class GamePlayer {
    final UUID uuid;
    final String name;
    int number;

    /*
     * TODO: здесь будет статистика игрока.
     * Планируемые переменные: gamesPlayed, wins, losses, eliminations, votes.
     * Пока статистика намеренно не реализована и не сохраняется.
     */
    final Statistics statistics = new Statistics();

    GamePlayer(UUID uuid, String name, int number) {
        this.uuid = uuid;
        this.name = name;
        this.number = number;
    }

    static final class Statistics {
        // Статистика будет добавлена позже.
    }
}
