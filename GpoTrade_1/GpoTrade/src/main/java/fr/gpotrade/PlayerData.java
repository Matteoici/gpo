package fr.gpotrade;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/** Statistiques persistantes : nombre d'échanges et avis reçus (players.yml). */
final class PlayerData {

    private final TradePlugin plugin;
    private final File file;
    private final YamlConfiguration yml;

    PlayerData(TradePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "players.yml");
        this.yml = YamlConfiguration.loadConfiguration(file);
        migrateOldFile();
    }

    /** Reprend les compteurs de l'ancienne version (trades.yml). */
    private void migrateOldFile() {
        File old = new File(plugin.getDataFolder(), "trades.yml");
        if (!old.exists() || file.exists()) return;
        YamlConfiguration o = YamlConfiguration.loadConfiguration(old);
        if (o.isConfigurationSection("trades")) {
            for (String key : o.getConfigurationSection("trades").getKeys(false)) {
                yml.set(key + ".trades", o.getInt("trades." + key));
            }
            save();
        }
    }

    int trades(UUID id) {
        return yml.getInt(id + ".trades", 0);
    }

    int reviews(UUID id) {
        return yml.getInt(id + ".reviews", 0);
    }

    /** Note moyenne de 0 à 5 (0 = aucun avis). */
    double rating(UUID id) {
        int n = reviews(id);
        return n == 0 ? 0 : yml.getDouble(id + ".rating-sum", 0) / n;
    }

    void addTrade(UUID id) {
        yml.set(id + ".trades", trades(id) + 1);
        save();
    }

    void addReview(UUID id, int stars) {
        yml.set(id + ".reviews", reviews(id) + 1);
        yml.set(id + ".rating-sum", yml.getDouble(id + ".rating-sum", 0) + stars);
        save();
    }

    private void save() {
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Impossible de sauvegarder players.yml : " + e.getMessage());
        }
    }
}
