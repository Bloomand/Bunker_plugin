package ru.maincraft.bunker;

import java.util.Map;

/** Единая точка форматирования сообщений и префикса плагина. */
final class MessageService {
    private final ConfigManager config;
    private String prefix = "";

    MessageService(ConfigManager config) {
        this.config = config;
        reload();
    }

    void reload() {
        prefix = config.message("prefix", null);
    }

    String get(String key, Map<String, ?> placeholders) {
        // Префикс хранится в messages.yml у конкретного сообщения.
        // Это позволяет использовать короткие подписи без префикса в GUI и action bar.
        return config.message(key, placeholders);
    }

    String withPrefix(String message) {
        if (message == null || message.isEmpty() || message.startsWith(prefix)) {
            return message;
        }
        return prefix + message;
    }
}
