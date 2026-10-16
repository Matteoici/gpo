package fr.smpshop;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static fr.smpshop.Texts.ph;

/** La boutique (shop.yml) : catégories, objets, achat en argent ou en jetons. */
public final class Shop {

    public enum Currency {
        MONEY, TOKENS
    }

    /** Un article : un objet à donner, ou des commandes (clé de caisse, grade...). */
    public record Entry(int slot, ItemStack item, double price, List<String> commands, String name, List<String> lore,
                        int maxQuantity) {
        public boolean isCommand() {
            return !commands.isEmpty();
        }
    }

    public record Category(String id, int slot, Material icon, String name, List<String> lore, String title, int rows,
                           Currency currency, List<Entry> entries) {
    }

    private final SmpShop plugin;
    private final Map<String, Category> categories = new LinkedHashMap<>();
    public String mainTitle = "<dark_gray>Boutique";
    public int mainRows = 3;

    Shop(SmpShop plugin) {
        this.plugin = plugin;
    }

    void load() {
        categories.clear();
        File f = new File(plugin.getDataFolder(), "shop.yml");
        plugin.upgrade("shop.yml", 4);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        mainTitle = y.getString("main.title", mainTitle);
        mainRows = Math.max(1, Math.min(6, y.getInt("main.rows", 3)));
        ConfigurationSection root = y.getConfigurationSection("categories");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection c = root.getConfigurationSection(id);
            if (c == null) continue;
            Material icon = mat(c.getString("icon"), Material.CHEST);
            int rows = Math.max(2, Math.min(6, c.getInt("rows", 3)));
            Currency cur = "tokens".equalsIgnoreCase(c.getString("currency")) || "jetons".equalsIgnoreCase(c.getString("currency"))
                    ? Currency.TOKENS : Currency.MONEY;
            List<Entry> entries = new ArrayList<>();
            int auto = 10;
            for (Map<?, ?> m : c.getMapList("items")) {
                Entry e = entry(id, m, auto);
                if (e == null) continue;
                entries.add(e);
                auto = nextAuto(e.slot() + 1, rows);
            }
            categories.put(id.toLowerCase(Locale.ROOT), new Category(id, c.getInt("slot", 0), icon, c.getString("name", id),
                    c.getStringList("lore"), c.getString("title", "<dark_gray>" + id), rows, cur, entries));
        }
        Map<Material, Double> buy = new java.util.EnumMap<>(Material.class);
        for (Category c : categories.values()) {
            if (c.currency() != Currency.MONEY) continue;
            for (Entry e : c.entries()) {
                if (e.isCommand() || e.price() <= 0) continue;
                // objets spéciaux (renommés, potions) : leur prix ne doit pas fixer celui de l'objet de base
                var meta = e.item().hasItemMeta() ? e.item().getItemMeta() : null;
                if (meta != null && (meta.hasDisplayName() || meta.hasLore() || meta instanceof org.bukkit.inventory.meta.PotionMeta)) continue;
                buy.merge(e.item().getType(), e.price() / e.item().getAmount(), Math::min);
            }
        }
        plugin.worth().applyShop(buy);
        warnArbitrage();
    }

    /** Prochaine case libre à l'intérieur (pas sur les bords) pour les articles sans "slot". */
    private static int nextAuto(int from, int rows) {
        int s = from;
        while (s < (rows - 1) * 9) {
            int col = s % 9;
            if (s >= 9 && col != 0 && col != 8) return s;
            s++;
        }
        return s;
    }

    private Entry entry(String cat, Map<?, ?> m, int auto) {
        String matName = str(m.get("material"));
        String iconName = str(m.get("icon"));
        Material mat = mat(matName != null ? matName : iconName, null);
        if (mat == null || !mat.isItem()) {
            plugin.getLogger().warning("shop.yml (" + cat + ") : objet inconnu « " + (matName != null ? matName : iconName) + " »");
            return null;
        }
        int amount = (int) Math.max(1, num(m.get("amount"), 1));
        ItemStack it = new ItemStack(mat, Math.min(amount, mat.getMaxStackSize()));
        String potion = str(m.get("potion"));
        if (potion != null && it.getItemMeta() instanceof PotionMeta pm) {
            try {
                pm.setBasePotionType(PotionType.valueOf(potion.toUpperCase(Locale.ROOT)));
                it.setItemMeta(pm);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("shop.yml : potion inconnue « " + potion + " »");
            }
        }
        List<String> commands = new ArrayList<>();
        if (m.get("commands") instanceof List<?> l) for (Object o : l) commands.add(String.valueOf(o));
        List<String> lore = new ArrayList<>();
        if (m.get("lore") instanceof List<?> l) for (Object o : l) lore.add(String.valueOf(o));
        String name = str(m.get("name"));
        // objet spécial (nommé) donné tel quel
        if (commands.isEmpty() && (name != null || !lore.isEmpty())) {
            ItemMeta meta = it.getItemMeta();
            if (name != null) meta.displayName(Texts.parse(name));
            if (!lore.isEmpty()) {
                List<Component> c = new ArrayList<>();
                for (String s : lore) c.add(Texts.parse(s));
                meta.lore(c);
            }
            it.setItemMeta(meta);
        }
        int slot = m.get("slot") == null ? auto : (int) num(m.get("slot"), auto);
        int max = (int) num(m.get("max"), commands.isEmpty() ? 2304 : 64);
        return new Entry(slot, it, num(m.get("price"), 0), commands, name, lore, Math.max(1, max));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static double num(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return o == null ? def : Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Material mat(String s, Material def) {
        if (s == null) return def;
        Material m = Material.matchMaterial(s);
        return m == null ? def : m;
    }

    /** Prévient si un objet se revend plus cher qu'il ne s'achète (argent infini). */
    private void warnArbitrage() {
        for (Category c : categories.values()) {
            if (c.currency() != Currency.MONEY) continue;
            for (Entry e : c.entries()) {
                if (e.isCommand()) continue;
                double buyUnit = e.price() / e.item().getAmount() * (1 - maxDiscount());
                double sell = plugin.worth().basePrice(e.item().getType()) * plugin.worth().maxMultiplier();
                if (sell >= buyUnit && buyUnit > 0) {
                    plugin.getLogger().warning("ATTENTION : " + e.item().getType() + " s'achète " + buyUnit + " mais se revend "
                            + sell + " (argent infini !). Corrige shop.yml ou worth.yml.");
                }
            }
        }
    }

    public List<Category> categories() {
        return new ArrayList<>(categories.values());
    }

    public Category category(String id) {
        return id == null ? null : categories.get(id.toLowerCase(Locale.ROOT));
    }

    // ================================================================= achat

    /** Prix d'un lot de "packs" (un pack = la quantité "amount" de l'article), réduction de grade comprise. */
    public double cost(Player p, Category c, Entry e, int packs) {
        double base = e.price() * packs;
        if (c.currency() != Currency.MONEY) return base; // les jetons d'événement ne sont pas réduits
        return Math.ceil(base * (1 - discount(p)) * 100) / 100.0;
    }

    /** Réduction en boutique selon les permissions smpshop.discount.<nom> (la plus grande compte), entre 0 et 0,9. */
    public double discount(Player p) {
        var sec = plugin.getConfig().getConfigurationSection("shop-discounts");
        double best = 0;
        if (sec == null || p == null) return 0;
        for (String k : sec.getKeys(false)) {
            double v = sec.getDouble(k, 0) / 100.0;
            if (v > best && p.hasPermission("smpshop.discount." + k.toLowerCase(Locale.ROOT))) best = v;
        }
        return Math.min(0.9, best);
    }

    private double maxDiscount() {
        var sec = plugin.getConfig().getConfigurationSection("shop-discounts");
        double best = 0;
        if (sec != null) for (String k : sec.getKeys(false)) best = Math.max(best, sec.getDouble(k, 0) / 100.0);
        return Math.min(0.9, best);
    }

    public String priceText(Category c, double v) {
        return c.currency() == Currency.TOKENS ? plugin.settings().tokens((long) Math.ceil(v)) : plugin.settings().money(v);
    }

    public boolean buy(Player p, Category c, Entry e, int packs) {
        Texts t = plugin.texts();
        if (packs <= 0) return false;
        double cost = cost(p, c, e, packs);
        if (!e.isCommand()) {
            int total = e.item().getAmount() * packs;
            if (space(p, e.item()) < total) {
                t.send(p, "shop.no-space");
                no(p);
                return false;
            }
        }
        boolean paid = c.currency() == Currency.TOKENS
                ? plugin.tokens().take(p.getUniqueId(), (long) Math.ceil(cost))
                : plugin.takeMoney(p, cost);
        if (!paid) {
            t.send(p, c.currency() == Currency.TOKENS ? "shop.no-tokens" : "shop.no-money", ph("price", priceText(c, cost)));
            no(p);
            return false;
        }
        if (e.isCommand()) {
            for (int i = 0; i < packs; i++) {
                for (String cmd : e.commands()) {
                    String line = cmd.replace("%player%", p.getName()).replace("<player>", p.getName());
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line.startsWith("/") ? line.substring(1) : line);
                }
            }
        } else {
            int left = e.item().getAmount() * packs;
            int max = e.item().getMaxStackSize();
            while (left > 0) {
                ItemStack give = e.item().clone();
                give.setAmount(Math.min(max, left));
                left -= give.getAmount();
                for (ItemStack rest : p.getInventory().addItem(give).values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
            }
        }
        Component name = e.name() != null ? Texts.parse(e.name()) : Component.translatable(e.item().translationKey());
        t.send(p, "shop.bought", ph("count", e.isCommand() ? packs : (long) e.item().getAmount() * packs),
                Texts.pc("item", name), ph("price", priceText(c, cost)));
        p.playSound(Sound.sound(Key.key("minecraft", "entity.experience_orb.pickup"), Sound.Source.MASTER, 1f, 1.4f));
        return true;
    }

    private static void no(Player p) {
        p.playSound(Sound.sound(Key.key("minecraft", "entity.villager.no"), Sound.Source.MASTER, 1f, 1f));
    }

    /** Combien d'objets de ce type rentrent encore dans l'inventaire (sans l'armure ni la seconde main). */
    public static int space(Player p, ItemStack like) {
        int free = 0;
        int max = like.getMaxStackSize();
        ItemStack[] content = p.getInventory().getStorageContents();
        for (ItemStack it : content) {
            if (it == null || it.getType().isAir()) free += max;
            else if (it.isSimilar(like)) free += Math.max(0, max - it.getAmount());
        }
        return free;
    }
}
