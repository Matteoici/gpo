package fr.smpshop;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.block.ShulkerBox;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Prix de vente (worth.yml). Lu aussi depuis le thread réseau (affichage des prix),
 * donc la table est remplacée d'un coup au rechargement, jamais modifiée sur place.
 */
public final class Worth {

    private final SmpShop plugin;
    private volatile Map<Material, Double> prices = new EnumMap<>(Material.class);
    private volatile Map<String, Double> multipliers = new LinkedHashMap<>();
    public volatile boolean ignoreNamed = true;
    public volatile boolean ignoreDamaged = true;

    Worth(SmpShop plugin) {
        this.plugin = plugin;
    }

    void load() {
        File f = new File(plugin.getDataFolder(), "worth.yml");
        if (!f.exists()) plugin.saveResource("worth.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        Map<Material, Double> p = new EnumMap<>(Material.class);
        ConfigurationSection s = y.getConfigurationSection("prices");
        if (s != null) {
            for (String k : s.getKeys(false)) {
                Material m = Material.matchMaterial(k);
                if (m == null) {
                    plugin.getLogger().warning("worth.yml : objet inconnu « " + k + " »");
                    continue;
                }
                double v = s.getDouble(k);
                if (v > 0) p.put(m, v);
            }
        }
        prices = p;
        Map<String, Double> mult = new LinkedHashMap<>();
        ConfigurationSection ms = plugin.getConfig().getConfigurationSection("sell.multipliers");
        if (ms != null) for (String k : ms.getKeys(false)) mult.put(k.toLowerCase(Locale.ROOT), ms.getDouble(k, 1));
        multipliers = mult;
        ignoreNamed = plugin.getConfig().getBoolean("sell.ignore-named-items", true);
        ignoreDamaged = plugin.getConfig().getBoolean("sell.ignore-damaged-items", true);
        plugin.getLogger().info(p.size() + " prix de vente chargés.");
    }

    public int size() {
        return prices.size();
    }

    public double basePrice(Material m) {
        Double v = prices.get(m);
        return v == null ? 0 : v;
    }

    /**
     * Prix de vente d'UN objet de cette pile (0 = invendable).
     * Les objets renommés (clés de caisse, objets spéciaux), abîmés, ou les shulkers pleines ne se vendent pas.
     */
    public double unitPrice(ItemStack it) {
        if (it == null || it.getType().isAir()) return 0;
        Double v = prices.get(it.getType());
        if (v == null) return 0;
        if (it.hasItemMeta()) {
            ItemMeta meta = it.getItemMeta();
            if (meta.getPersistentDataContainer().has(plugin.menuKey(), PersistentDataType.BYTE)) return 0;
            if (ignoreNamed && (meta.hasDisplayName() || meta.hasItemName())) return 0;
            if (ignoreDamaged && meta instanceof Damageable d && d.hasDamage()) return 0;
            if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState() && bsm.getBlockState() instanceof ShulkerBox box
                    && !box.getInventory().isEmpty()) return 0;
        }
        return v;
    }

    public double maxMultiplier() {
        double best = 1;
        for (double v : multipliers.values()) best = Math.max(best, v);
        return best;
    }

    /** Bonus de vente selon les permissions smpshop.multiplier.<nom> (le plus grand compte). */
    public double multiplier(Player p) {
        double best = 1;
        for (var e : multipliers.entrySet()) {
            if (e.getValue() > best && p.hasPermission("smpshop.multiplier." + e.getKey())) best = e.getValue();
        }
        return best;
    }
}
