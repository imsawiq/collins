package org.sawiq.collins.paper.store;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.sawiq.collins.paper.model.Playlist;

import java.io.File;
import java.util.List;
import java.util.logging.Level;

/**
 * Сохраняет и загружает плейлисты из playlists.yml
 */
public final class PlaylistStore {

    private final JavaPlugin plugin;
    private final File file;

    public PlaylistStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "playlists.yml");
    }

    public void load() {
        Playlist.clearAll();

        if (!file.exists()) {
            plugin.getLogger().info("No playlists.yml found, starting with empty playlists");
            return;
        }

        try {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            int count = 0;

            for (String screenName : cfg.getKeys(false)) {
                ConfigurationSection sec = cfg.getConfigurationSection(screenName);
                if (sec == null) continue;

                Playlist playlist = Playlist.getOrCreate(screenName);
                
                playlist.setEnabled(sec.getBoolean("enabled", true));
                playlist.setLoop(sec.getBoolean("loop", true));

                List<?> entries = sec.getList("entries");
                if (entries != null) {
                    for (Object entry : entries) {
                        if (entry instanceof java.util.Map) {
                            @SuppressWarnings("unchecked")
                            java.util.Map<String, Object> map = (java.util.Map<String, Object>) entry;
                            String url = (String) map.get("url");
                            String title = (String) map.get("title");
                            if (url != null && !url.isEmpty()) {
                                if (title != null && !title.isEmpty()) {
                                    playlist.add(url, title);
                                } else {
                                    playlist.add(url);
                                }
                            }
                        } else if (entry instanceof String) {
                            String url = (String) entry;
                            if (!url.isEmpty()) {
                                playlist.add(url);
                            }
                        }
                    }
                }

                int currentIndex = sec.getInt("currentIndex", 1);
                if (currentIndex > 0 && currentIndex <= playlist.size()) {
                    playlist.jumpTo(currentIndex);
                }

                count++;
            }

            plugin.getLogger().info("Loaded " + count + " playlists from playlists.yml");

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load playlists.yml", e);
        }
    }

    public void save() {
        try {
            YamlConfiguration cfg = new YamlConfiguration();

            for (String screenName : Playlist.getScreenNames()) {
                Playlist playlist = Playlist.get(screenName);
                if (playlist == null || playlist.isEmpty()) continue;

                ConfigurationSection sec = cfg.createSection(screenName);
                sec.set("enabled", playlist.isEnabled());
                sec.set("loop", playlist.isLoop());
                sec.set("currentIndex", playlist.getCurrentIndex());

                java.util.List<java.util.Map<String, String>> entries = new java.util.ArrayList<>();
                for (Playlist.PlaylistEntry entry : playlist.getEntries()) {
                    java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
                    map.put("url", entry.url());
                    map.put("title", entry.title());
                    entries.add(map);
                }
                sec.set("entries", entries);
            }

            cfg.save(file);
            plugin.getLogger().fine("Saved playlists to playlists.yml");

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save playlists.yml", e);
        }
    }
}
