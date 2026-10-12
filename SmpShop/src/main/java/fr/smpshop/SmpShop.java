package fr.smpshop;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static fr.smpshop.Texts.ph;

public final class SmpShop extends JavaPlugin {

    private Settings settings;
    private Texts texts;
    private Economy economy;
    private Worth worth;
    private Seller seller;
    private Shop shop;
    private Tokens tokens;
    private Cleaner cleaner;
    private NamespacedKey menuKey;
    private volatile boolean display;
    private volatile String displayLine1 = "", displayLine2 = "";
    private volatile boolean showStack;
    private boolean tokenDrops;
    private Map<EntityType, double[]> drops = new EnumMap<>(EntityType.class);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        menuKey = new NamespacedKey(this, "menu");
        texts = new Texts(this);
        worth = new Worth(this);
        seller = new Seller(this);
        shop = new Shop(this);
        tokens = new Tokens(this);
        cleaner = new Cleaner(this);
        if (getServer().getPluginManager().getPlugin("Vault") != null) economy = new Economy();
        else getLogger().warning("Vault absent : l'argent ne marchera pas !");
        loadAll();
        tokens.load();

        getServer().getPluginManager().registerEvents(new ShopListener(this), this);
        Commands c = new Commands(this);
        for (String n : new String[]{"shop", "sell", "worth", "tokens", "shopadmin"}) {
            PluginCommand pc = getCommand(n);
            if (pc != null) {
                pc.setExecutor(c);
                pc.setTabCompleter(c);
            }
        }
        if (getServer().getPluginManager().getPlugin("ProtocolLib") != null) {
            try {
                PriceDisplay.register(this);
                getLogger().info("Prix de vente affichés sur les objets (sans les modifier) grâce à ProtocolLib.");
            } catch (Throwable t) {
                getLogger().warning("ProtocolLib incompatible, prix non affichés sur les objets : " + t);
            }
        } else {
            getLogger().warning("ProtocolLib absent : les prix ne s'affichent pas sur les objets (/worth marche quand même).");
        }
        Bukkit.getScheduler().runTaskTimer(this, () -> tokens.save(true), 1200L, 1200L);
        Bukkit.getScheduler().runTask(this, () -> {
            for (String other : new String[]{"DonutSell", "DonutShop"}) {
                if (getServer().getPluginManager().getPlugin(other) != null) {
                    getLogger().warning(other + " est encore installé : retire-le pour éviter les conflits sur /shop et /sell.");
                }
            }
            for (var p : Bukkit.getOnlinePlayers()) p.updateInventory();
        });
    }

    @Override
    public void onDisable() {
        if (tokens != null) tokens.save(false);
        for (var p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof fr.smpshop.gui.SellMenu
                    || p.getOpenInventory().getTopInventory().getHolder(false) instanceof fr.smpshop.gui.Menu) {
                p.closeInventory();
            }
        }
    }

    void loadAll() {
        reloadConfig();
        settings = new Settings(getConfig());
        texts.load();
        worth.load();
        shop.load();
        cleaner.load();
        display = getConfig().getBoolean("display.enabled", true);
        showStack = getConfig().getBoolean("display.show-stack-total", true);
        displayLine1 = texts.raw("display.line");
        displayLine2 = texts.raw("display.stack");
        tokenDrops = getConfig().getBoolean("tokens.mob-drops.enabled", false);
        Map<EntityType, double[]> d = new EnumMap<>(EntityType.class);
        ConfigurationSection mobs = getConfig().getConfigurationSection("tokens.mob-drops.mobs");
        if (mobs != null) {
            for (String k : mobs.getKeys(false)) {
                try {
                    EntityType t = EntityType.valueOf(k.toUpperCase(Locale.ROOT));
                    d.put(t, new double[]{mobs.getDouble(k + ".chance", 5), Math.max(1, mobs.getDouble(k + ".amount", 1))});
                } catch (IllegalArgumentException e) {
                    getLogger().warning("tokens.mob-drops : créature inconnue « " + k + " »");
                }
            }
        }
        drops = d;
    }

    void reload() {
        loadAll();
        for (var p : Bukkit.getOnlinePlayers()) p.updateInventory();
    }

    // ================================================================= affichage du prix (thread réseau)

    boolean displayEnabled() {
        return display;
    }

    List<Component> displayLines(double unit, int amount) {
        List<Component> out = new ArrayList<>(2);
        out.add(Component.empty());
        out.add(Texts.parse(displayLine1, ph("price", settings.money(unit))));
        if (showStack && amount > 1) out.add(Texts.parse(displayLine2, ph("price", settings.money(unit * amount)), ph("amount", amount)));
        return out;
    }

    // ================================================================= argent

    public double balance(OfflinePlayer p) {
        return economy == null ? 0 : economy.balance(p);
    }

    public boolean takeMoney(OfflinePlayer p, double amount) {
        if (amount <= 0) return true;
        return economy != null && economy.has(p, amount) && economy.take(p, amount);
    }

    public void giveMoney(OfflinePlayer p, double amount) {
        if (amount > 0 && economy != null && !economy.give(p, amount)) getLogger().warning("Paiement impossible pour " + p.getName());
    }

    // ================================================================= jetons sur les monstres

    boolean tokenDrops() {
        return tokenDrops;
    }

    double[] tokenDrop(EntityType t) {
        return drops.get(t);
    }

    // ================================================================= accès

    public Settings settings() {
        return settings;
    }

    public Texts texts() {
        return texts;
    }

    public Worth worth() {
        return worth;
    }

    public Seller seller() {
        return seller;
    }

    public Shop shop() {
        return shop;
    }

    public Tokens tokens() {
        return tokens;
    }

    public Cleaner cleaner() {
        return cleaner;
    }

    public NamespacedKey menuKey() {
        return menuKey;
    }
}
