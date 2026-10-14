package fr.smpevents;

import fr.smpevents.afk.AfkZones;
import fr.smpevents.board.Boards;
import fr.smpevents.board.Holograms;
import fr.smpevents.bounty.Bounties;
import fr.smpevents.koth.Koth;
import fr.smpevents.quest.QuestListener;
import fr.smpevents.quest.QuestPool;
import fr.smpevents.quest.Quests;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class SmpEvents extends JavaPlugin {

    private Settings settings;
    private Texts texts;
    private Economy economy;
    private DataStore data;
    private Relations relations;
    private QuestPool pool;
    private Quests quests;
    private QuestListener questListener;
    private Boards boards;
    private Holograms holograms;
    private Koth koth;
    private Bounties bounties;
    private AfkZones afk;
    private NamespacedKey key, spawnerKey;
    private boolean dirty;
    private int ticks;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        key = new NamespacedKey(this, "display");
        spawnerKey = new NamespacedKey(this, "spawner");
        texts = new Texts(this);
        data = new DataStore(this);
        relations = new Relations(this);
        pool = new QuestPool(this);
        quests = new Quests(this, pool);
        boards = new Boards(this);
        holograms = new Holograms(this);
        koth = new Koth(this);
        bounties = new Bounties(this);
        afk = new AfkZones(this);
        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            economy = new Economy();
        } else {
            getLogger().warning("Vault absent : aucune récompense en argent ne sera donnée.");
        }
        loadAll();
        data.load();
        holograms.load();
        koth.load();
        bounties.load();
        afk.load();

        questListener = new QuestListener(this);
        var pm = getServer().getPluginManager();
        pm.registerEvents(new MainListener(this), this);
        pm.registerEvents(questListener, this);
        pm.registerEvents(bounties, this);
        pm.registerEvents(afk, this);

        Commands c = new Commands(this);
        for (String name : new String[]{"quetes", "classement", "koth", "prime", "zoneafk", "smpevents"}) {
            PluginCommand pc = getCommand(name);
            if (pc != null) {
                pc.setExecutor(c);
                pc.setTabCompleter(c);
            }
        }

        // premier démarrage : on lit les joueurs hors ligne pour remplir les classements
        int toScan = boards.startScan(false);
        if (toScan > 0) getLogger().info("Classements : lecture de " + toScan + " joueur(s) hors ligne en arrière-plan...");
        boards.updateOnline();
        relations.sampleAll();
        for (var p : Bukkit.getOnlinePlayers()) quests.ensure(data.of(p));
        boards.recompute();

        Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        getLogger().info("SmpEvents actif : quêtes, classements, roi de la colline, primes.");
    }

    private void loadAll() {
        reloadConfig();
        settings = new Settings(getConfig(), getLogger());
        Hooks.configure(getConfig().getString("anti-abuse.guild-placeholder", ""));
        texts.load();
        pool.load();
        quests.load(getConfig().getConfigurationSection("quests"));
        boards.load(getConfig().getConfigurationSection("leaderboards"));
        holograms.loadSettings(getConfig().getConfigurationSection("leaderboards"));
        koth.loadSettings(getConfig().getConfigurationSection("koth"));
        bounties.loadSettings(getConfig().getConfigurationSection("bounties"));
        afk.loadSettings(getConfig().getConfigurationSection("afk-zone"));
    }

    void reload() {
        loadAll();
        holograms.removeAll();
        holograms.ensureAll();
        for (var p : Bukkit.getOnlinePlayers()) quests.ensure(data.of(p));
        boards.recompute();
        holograms.renderAll();
        afk.refreshLabel();
    }

    @Override
    public void onDisable() {
        if (koth != null) koth.stop(false);
        if (afk != null) afk.shutdown();
        if (holograms != null) holograms.removeAll();
        if (boards != null) boards.updateOnline();
        if (data != null) data.save(false);
        if (bounties != null) bounties.save();
    }

    private void tick() {
        ticks++;
        boards.tickScan();
        if (ticks % 20 == 0) {
            questListener.tickSecond();
            koth.tickSecond();
            afk.tickSecond();
        }
        if (ticks % 100 == 5) {
            holograms.ensureAll();
            afk.ensureLabel();
        }
        if (ticks % (holograms.rotateSeconds * 20) == 0) holograms.rotate();
        if (ticks % (boards.refreshSeconds * 20) == 10) {
            boards.updateOnline();
            boards.recompute();
            holograms.renderAll();
        }
        if (ticks % 1200 == 15) {
            questListener.tickMinute();
            relations.sampleAll();
            bounties.expire();
        }
        if (boards.rescanMinutes > 0 && ticks % (boards.rescanMinutes * 1200) == 600) boards.startMoneyRefresh();
        if (ticks % 6000 == 20) {
            data.save(true);
            dirty = false;
            holograms.prune();
            afk.save();
        } else if (dirty && ticks % 1200 == 30) {
            data.save(true);
            dirty = false;
        }
    }

    // ================================================================= argent

    public void giveMoney(OfflinePlayer p, double amount) {
        if (amount <= 0 || economy == null) return;
        if (!economy.give(p, amount)) getLogger().warning("Impossible de donner " + amount + " à " + p.getName());
    }

    public boolean takeMoney(OfflinePlayer p, double amount) {
        if (amount <= 0) return true;
        if (economy == null || !economy.has(p, amount)) return false;
        return economy.take(p, amount);
    }

    public void markDirty() {
        dirty = true;
    }

    // ================================================================= accès

    public Settings settings() {
        return settings;
    }

    public Texts texts() {
        return texts;
    }

    public Economy economy() {
        return economy;
    }

    public DataStore data() {
        return data;
    }

    public Relations relations() {
        return relations;
    }

    public Quests quests() {
        return quests;
    }

    public Boards boards() {
        return boards;
    }

    public Holograms holograms() {
        return holograms;
    }

    public Koth koth() {
        return koth;
    }

    public Bounties bounties() {
        return bounties;
    }

    public AfkZones afk() {
        return afk;
    }

    public NamespacedKey key() {
        return key;
    }

    public NamespacedKey spawnerKey() {
        return spawnerKey;
    }

    /** Lien avec SmpTitles : compte une victoire (débloque les titres) si le plugin est installé. */
    public static void titleWin(org.bukkit.entity.Player p, String counter) {
        if (p == null || !org.bukkit.Bukkit.getPluginManager().isPluginEnabled("SmpTitles")) return;
        org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), "titre win " + p.getName() + " " + counter);
    }
}
