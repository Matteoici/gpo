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

    /** Prix écrits à la main dans worth.yml (jamais recalculés). */
    private volatile java.util.Set<Material> explicit = java.util.EnumSet.noneOf(Material.class);

    void load() {
        File f = new File(plugin.getDataFolder(), "worth.yml");
        plugin.upgrade("worth.yml", 2);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        Map<Material, Double> p = new EnumMap<>(Material.class);
        java.util.Set<Material> fixed = java.util.EnumSet.noneOf(Material.class);
        java.util.Set<Material> never = java.util.EnumSet.noneOf(Material.class);
        ConfigurationSection s = y.getConfigurationSection("prices");
        if (s != null) {
            for (String k : s.getKeys(false)) {
                Material m = Material.matchMaterial(k);
                if (m == null) {
                    plugin.getLogger().warning("worth.yml : objet inconnu « " + k + " »");
                    continue;
                }
                double v = s.getDouble(k);
                fixed.add(m);
                if (v > 0) p.put(m, v);
                else never.add(m); // 0 = ne se vend jamais
            }
        }
        int manual = p.size();
        var c = plugin.getConfig();
        if (c.getBoolean("auto-price.enabled", true)) {
            AutoPrice.fill(p, never, c.getDouble("auto-price.craft-factor", 0.9), c.getDouble("auto-price.smelt-factor", 1.0),
                    c.getDouble("auto-price.default", 1.0), c.getStringList("auto-price.never-sell"));
        }
        prices = p;
        explicit = fixed;
        Map<String, Double> mult = new LinkedHashMap<>();
        ConfigurationSection ms = c.getConfigurationSection("sell.multipliers");
        if (ms != null) for (String k : ms.getKeys(false)) mult.put(k.toLowerCase(Locale.ROOT), ms.getDouble(k, 1));
        multipliers = mult;
        ignoreNamed = c.getBoolean("sell.ignore-named-items", true);
        ignoreDamaged = c.getBoolean("sell.ignore-damaged-items", true);
        plugin.getLogger().info(manual + " prix écrits à la main, " + (p.size() - manual) + " calculés automatiquement.");
    }

    /**
     * Objets vendus dans la boutique : prix de revente = prix d'achat × ratio (sell.shop-items-ratio),
     * pour que /sell reste proportionnel à /shop. Ratio 0 = on garde worth.yml, mais un prix calculé
     * automatiquement ne dépasse jamais la moitié du prix d'achat.
     */
    void applyShop(Map<Material, Double> buyUnit) {
        Map<Material, Double> p = new EnumMap<>(Material.class);
        p.putAll(prices);
        double maxMult = maxMultiplier();
        double ratio = plugin.getConfig().getDouble("sell.shop-items-ratio", 0.3);
        // jamais plus que ce qu'on paie, même avec le meilleur bonus de grade
        ratio = Math.min(ratio, 0.9 / maxMult);
        for (var e : buyUnit.entrySet()) {
            Material m = e.getKey();
            if (ratio > 0) {
                // plafond : jamais plus de ratio × prix d'achat (un prix plus bas dans worth.yml est gardé)
                Double cur = p.get(m);
                double cap = Math.floor(e.getValue() * ratio * 100) / 100.0;
                if (cur != null && cur > cap) p.put(m, cap);
                continue;
            }
            Double v = p.get(m);
            if (v == null || explicit.contains(m)) continue;
            double cap = e.getValue() * 0.5 / maxMult;
            if (v > cap) p.put(m, Math.floor(cap * 100) / 100.0);
        }
        prices = p;
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
