package fr.smpmarket;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SmpMarket extends JavaPlugin {

    private Settings settings;
    private final Styles styles = new Styles();
    private Texts texts;
    private Economy economy;
    private MarketStore store;
    private Auctions auctions;
    private Claims claims;
    private Shops shops;
    private Visuals visuals;
    private Mannequins mannequins;
    private ChatInput input;
    private NamespacedKey key, wandKey;
    /** Sélection de zone des admins : [coin 1, coin 2]. */
    private final Map<UUID, Location[]> selections = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getConfig().getInt("config-version", 1) < 2) {
            // enchères de 2 h et stands inactifs remboursés au bout de 24 h
            getConfig().set("auction.duration-hours", 2);
            getConfig().set("lease.inactive-hours", 24);
            getConfig().set("config-version", 2);
            saveConfig();
        }
        key = new NamespacedKey(this, "market");
        wandKey = new NamespacedKey(this, "wand");
        settings = new Settings(getConfig());
        styles.load(getConfig());
        texts = new Texts(this);
        texts.load();
        economy = new Economy();
        if (!economy.available()) getLogger().warning("Aucune économie Vault trouvée : les achats et enchères ne marcheront pas.");
        store = new MarketStore(this);
        store.load();
        auctions = new Auctions(this);
        claims = new Claims(this);
        shops = new Shops(this);
        visuals = new Visuals(this);
        mannequins = new Mannequins(this);
        input = new ChatInput(this);
        claims.migrate();

        getServer().getPluginManager().registerEvents(new MarketListener(this), this);
        getServer().getPluginManager().registerEvents(new SpawnerFix(this), this);
        PluginCommand cmd = getCommand("marche");
        if (cmd != null) {
            MarketCommand mc = new MarketCommand(this);
            cmd.setExecutor(mc);
            cmd.setTabCompleter(mc);
        }

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            claims.tick();
        }, 40L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, () -> visuals.spin(40), 40L, 40L);
        Bukkit.getScheduler().runTaskTimer(this, () -> visuals.ensureAll(), 20L, 100L);
        Bukkit.getScheduler().runTaskTimer(this, () -> mannequins.tick(), 60L, 40L);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            store.saveIfDirty();
            visuals.prune();
        }, 600L, 600L);
        getLogger().info(store.stalls().size() + " stand(s) chargé(s).");
    }

    @Override
    public void onDisable() {
        if (visuals != null) visuals.removeAll();
        if (mannequins != null) mannequins.removeAll();
        if (store != null) store.save();
    }

    void reload() {
        reloadConfig();
        settings = new Settings(getConfig());
        styles.load(getConfig());
        texts.load();
        for (Stall st : store.stalls()) visuals.restyle(st);
    }

    /** Message à un joueur : tout de suite s'il est connecté, sinon à sa prochaine connexion. */
    public void notify(UUID player, Component body) {
        if (player == null) return;
        Component full = Texts.MM.deserialize(texts.raw("prefix")).append(body);
        Player p = Bukkit.getPlayer(player);
        if (p != null) p.sendMessage(full);
        else store.note(player, Texts.MM.serialize(full));
    }

    /** Un admin en créatif peut construire partout dans le marché (pour bâtir les stands). */
    public boolean isAdminBypass(Player p) {
        return p.hasPermission("smpmarket.admin") && p.getGameMode() == org.bukkit.GameMode.CREATIVE;
    }

    public Location[] selection(UUID id) {
        return selections.computeIfAbsent(id, k -> new Location[2]);
    }

    public Styles styles() {
        return styles;
    }

    public Settings settings() {
        return settings;
    }

    public Texts texts() {
        return texts;
    }

    public Economy economy() {
        return economy;
    }

    public MarketStore store() {
        return store;
    }

    public Auctions auctions() {
        return auctions;
    }

    public Claims claims() {
        return claims;
    }

    public Shops shops() {
        return shops;
    }

    public Mannequins mannequins() {
        return mannequins;
    }

    public Visuals visuals() {
        return visuals;
    }

    public ChatInput input() {
        return input;
    }

    public NamespacedKey key() {
        return key;
    }

    public NamespacedKey wandKey() {
        return wandKey;
    }
}
