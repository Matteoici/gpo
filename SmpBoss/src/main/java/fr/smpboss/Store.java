package fr.smpboss;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** players.yml : reliques, garantie, contenu du sac. */
public final class Store {

    private final SmpBoss plugin;
    private final Map<UUID, PlayerData> data = new HashMap<>();
    private final File file;
    private boolean dirty;

    Store(SmpBoss plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "players.yml");
    }

    public PlayerData of(UUID id) {
        return data.computeIfAbsent(id, PlayerData::new);
    }

    public void dirty() {
        dirty = true;
    }

    void load() {
        data.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String k : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(k);
            if (s == null) continue;
            PlayerData d;
            try {
                d = new PlayerData(UUID.fromString(k));
            } catch (IllegalArgumentException e) {
                continue;
            }
            d.relics.addAll(s.getStringList("relics"));
            d.equipped.addAll(s.getStringList("equipped"));
            d.titles.addAll(s.getStringList("titles"));
            d.pityLegendary = s.getInt("pity-legendary");
            d.pitySecret = s.getInt("pity-secret");
            d.kills = s.getLong("kills");
            d.hideCosmetics = s.getBoolean("hide-cosmetics");
            d.hideEffects = s.getBoolean("hide-effects");
            List<String> bp = s.getStringList("backpack");
            for (int i = 0; i < Math.min(27, bp.size()); i++) d.backpack[i] = decode(bp.get(i));
            for (String st : s.getStringList("stash")) {
                ItemStack it = decode(st);
                if (it != null) d.stash.add(it);
            }
            data.put(d.id, d);
        }
    }

    public void save(boolean async) {
        if (async && !dirty) return;
        dirty = false;
        YamlConfiguration y = new YamlConfiguration();
        for (PlayerData d : data.values()) {
            String k = d.id.toString();
            if (!d.relics.isEmpty()) y.set(k + ".relics", List.copyOf(d.relics));
            if (!d.equipped.isEmpty()) y.set(k + ".equipped", List.copyOf(d.equipped));
            if (!d.titles.isEmpty()) y.set(k + ".titles", List.copyOf(d.titles));
            if (d.pityLegendary > 0) y.set(k + ".pity-legendary", d.pityLegendary);
            if (d.pitySecret > 0) y.set(k + ".pity-secret", d.pitySecret);
            if (d.kills > 0) y.set(k + ".kills", d.kills);
            if (d.hideCosmetics) y.set(k + ".hide-cosmetics", true);
            if (d.hideEffects) y.set(k + ".hide-effects", true);
            boolean any = false;
            String[] bp = new String[27];
            for (int i = 0; i < 27; i++) {
                bp[i] = encode(d.backpack[i]);
                if (!bp[i].isEmpty()) any = true;
            }
            if (any) y.set(k + ".backpack", List.of(bp));
            if (!d.stash.isEmpty()) y.set(k + ".stash", d.stash.stream().map(Store::encode).filter(x -> !x.isEmpty()).toList());
        }
        String text = y.saveToString();
        Runnable w = () -> write(text);
        if (async) Bukkit.getScheduler().runTaskAsynchronously(plugin, w);
        else w.run();
    }

    private synchronized void write(String text) {
        try {
            File dir = file.getParentFile();
            if (!dir.exists()) dir.mkdirs();
            File tmp = new File(file.getPath() + ".tmp");
            Files.writeString(tmp.toPath(), text, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            plugin.getLogger().warning("Sauvegarde impossible : " + e.getMessage());
        }
    }

    static String encode(ItemStack it) {
        if (it == null || it.getType().isAir()) return "";
        return Base64.getEncoder().encodeToString(it.serializeAsBytes());
    }

    static ItemStack decode(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(s));
        } catch (Exception e) {
            return null;
        }
    }
}
