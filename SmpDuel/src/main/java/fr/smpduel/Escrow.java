package fr.smpduel;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Les mises des duels "pari" gardées en lieu sûr (fichier) : si le serveur plante pendant un duel, chacun récupère
 * sa mise ; si un joueur est hors ligne quand il doit recevoir des objets, ils l'attendent ici.
 */
final class Escrow {

    private final File file;
    /** mises en jeu, par propriétaire */
    private final Map<UUID, List<ItemStack>> inPlay = new HashMap<>();
    /** objets à rendre à la prochaine connexion */
    private final Map<UUID, List<ItemStack>> pending = new HashMap<>();

    Escrow(File file) {
        this.file = file;
        load();
        // démarrage : ce qui était "en jeu" pendant un crash revient à son propriétaire
        for (Map.Entry<UUID, List<ItemStack>> e : inPlay.entrySet()) pending.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
        inPlay.clear();
        save();
    }

    void lock(UUID owner, List<ItemStack> items) {
        inPlay.put(owner, new ArrayList<>(items));
        save();
    }

    void unlock(UUID owner) {
        inPlay.remove(owner);
        save();
    }

    /** Donne des objets (ou les met de côté si le joueur est hors ligne). Le surplus tombe à ses pieds. */
    void give(UUID to, List<ItemStack> items) {
        Player p = Bukkit.getPlayer(to);
        if (p == null || !p.isOnline() || p.isDead()) {
            pending.computeIfAbsent(to, k -> new ArrayList<>()).addAll(items);
            save();
            return;
        }
        for (ItemStack it : items) {
            if (it == null || it.getType().isAir()) continue;
            for (ItemStack left : p.getInventory().addItem(it.clone()).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
    }

    /** À la connexion / réapparition. */
    boolean deliver(Player p) {
        List<ItemStack> l = pending.remove(p.getUniqueId());
        if (l == null || l.isEmpty()) return false;
        save();
        give(p.getUniqueId(), l);
        return true;
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        read(y, "in-play", inPlay);
        read(y, "pending", pending);
    }

    @SuppressWarnings("unchecked")
    private static void read(YamlConfiguration y, String sec, Map<UUID, List<ItemStack>> into) {
        var s = y.getConfigurationSection(sec);
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            try {
                List<ItemStack> items = new ArrayList<>();
                for (Object o : s.getList(k, List.of())) if (o instanceof ItemStack it) items.add(it);
                into.put(UUID.fromString(k), items);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, List<ItemStack>> e : inPlay.entrySet()) y.set("in-play." + e.getKey(), e.getValue());
        for (Map.Entry<UUID, List<ItemStack>> e : pending.entrySet()) y.set("pending." + e.getKey(), e.getValue());
        try {
            y.save(file);
        } catch (IOException ignored) {
        }
    }
}
