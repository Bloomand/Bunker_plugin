package ru.maincraft.bunker;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/** Загружает игровые JSON-данные из папки плагина. */
public final class ConfigManager {
    private final JavaPlugin plugin;
    private final Map<String, List<GameManager.Characteristic>> characteristicSets = new LinkedHashMap<>();
    private final List<String> catastrophes = new ArrayList<>();
    private boolean characteristicsLoaded;
    private FileConfiguration messages;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        reloadFiles();
    }

    public void reloadFiles() {
        plugin.saveDefaultConfig();
        if (!new File(plugin.getDataFolder(), "messages.yml").exists()) {
            plugin.saveResource("messages.yml", false);
        }
        plugin.reloadConfig();
        messages = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "messages.yml"));
        File characteristicsFile = prepareResource("characteristics.json");
        File catastrophesFile = prepareResource("catastrophes.json");
        loadCharacteristics(characteristicsFile);
        loadCatastrophes(catastrophesFile);
    }

    public Map<String, List<GameManager.Characteristic>> characteristicSets() {
        return characteristicSets;
    }

    public List<String> catastrophes() {
        return catastrophes;
    }

    public boolean isCharacteristicsLoaded() {
        return characteristicsLoaded;
    }

    public int integer(String path, int fallback) {
        int value = plugin.getConfig().getInt(path, fallback);
        return value > 0 ? value : fallback;
    }

    public String message(String key, Map<String, ?> placeholders) {
        String value = messages.getString(key, key);
        Map<String, Object> values = new HashMap<>();
        values.put("prefix", messages.getString("prefix", "&6[Bunker] &f"));
        if (placeholders != null) {
            values.putAll(placeholders);
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            value = value.replace("%" + entry.getKey() + "%", String.valueOf(entry.getValue()));
        }
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    private File prepareResource(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
        }
        return file;
    }

    private void loadCharacteristics(File file) {
        characteristicSets.clear();
        characteristicsLoaded = false;
        try (Reader reader = new FileReader(file)) {
            JSONObject root = (JSONObject) new JSONParser().parse(reader);
            if (root.isEmpty()) {
                throw new IllegalStateException("Файл характеристик пуст");
            }
            for (Object entry : root.entrySet()) {
                Map.Entry<?, ?> categoryEntry = (Map.Entry<?, ?>) entry;
                String category = String.valueOf(categoryEntry.getKey());
                JSONArray values = (JSONArray) categoryEntry.getValue();
                List<GameManager.Characteristic> characteristics = new ArrayList<>();
                for (Object value : values) {
                    JSONObject object = (JSONObject) value;
                    String name = String.valueOf(object.get("name"));
                    String description = String.valueOf(object.get("description"));
                    if (name.isBlank() || "null".equals(name)
                            || description.isBlank() || "null".equals(description)) {
                        throw new IllegalStateException("В категории " + category
                                + " найдена пустая характеристика");
                    }
                    characteristics.add(new GameManager.Characteristic(name, description));
                }
                if (characteristics.isEmpty()) {
                    throw new IllegalStateException("Категория " + category + " пуста");
                }
                characteristicSets.put(category, characteristics);
            }
            characteristicsLoaded = true;
            plugin.getLogger().info("Загружено категорий характеристик: " + characteristicSets.size());
        } catch (IOException | ParseException | RuntimeException exception) {
            backupBrokenJson(file);
            plugin.getLogger().severe("Не удалось загрузить characteristics.json: " + exception.getMessage());
        }
    }

    private void loadCatastrophes(File file) {
        catastrophes.clear();
        try (Reader reader = new FileReader(file)) {
            JSONArray values = (JSONArray) new JSONParser().parse(reader);
            for (Object value : values) {
                if (value != null && !String.valueOf(value).isBlank()) {
                    catastrophes.add(String.valueOf(value));
                }
            }
            if (catastrophes.isEmpty()) {
                throw new IllegalStateException("Файл катастроф пуст");
            }
            plugin.getLogger().info("Загружено описаний катастроф: " + catastrophes.size());
        } catch (IOException | ParseException | RuntimeException exception) {
            backupBrokenJson(file);
            catastrophes.add("Мировая катастрофа неизвестного происхождения.");
            plugin.getLogger().warning("Не удалось загрузить catastrophes.json, используется запасное описание: "
                    + exception.getMessage());
        }
    }

    private void backupBrokenJson(File file) {
        if (!file.isFile()) {
            return;
        }
        File backup = new File(file.getParentFile(), file.getName() + ".broken-" + System.currentTimeMillis());
        try {
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
            plugin.getLogger().warning("Повреждённый JSON сохранён в " + backup.getName());
        } catch (IOException exception) {
            plugin.getLogger().severe("Не удалось сохранить копию повреждённого JSON "
                    + file.getName() + ": " + exception.getMessage());
        }
    }
}
