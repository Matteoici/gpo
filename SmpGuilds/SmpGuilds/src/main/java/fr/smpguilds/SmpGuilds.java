package fr.smpguilds;

import fr.smpguilds.gui.GuiListener;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Point d'entrée de SmpGuilds : crée tous les modules, les commandes et les tâches répétées. */
public final class SmpGuilds extends JavaPlugin {

    private Settings settings;
    private Messages messages;
    private GuildManager guilds;
    private GuildActions actions;
    private WarManager wars;
    private GuildVaults vaults;
    private HomeTeleport homes;
    private ChatInput input;
    private GuildChat chat;
    private TerritoryListener territory;
    private Upkeep upkeep;
    private Economy economy;

    private final Set<UUID> bypass = ConcurrentHashMap.newKeySet();
    private final Set<UUID> spies = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new Settings(this);
        settings.load();
        messages = new Messages(this);
        messages.load();

        PluginManager pm = getServer().getPluginManager();
        if (pm.getPlugin("Vault") != null) {
            economy = new Economy();
            getLogger().info("Vault détecté : trésorerie, coûts et butins de guerre activés.");
        } else {
            getLogger().warning("Vault absent : la trésorerie est désactivée et tout est gratuit.");
        }

        vaults = new GuildVaults(this);
        guilds = new GuildManager(this);
        guilds.loadAll();
        actions = new GuildActions(this);
        wars = new WarManager(this);
        homes = new HomeTeleport(this);
        input = new ChatInput(this);
        chat = new GuildChat(this);
        territory = new TerritoryListener(this);
        upkeep = new Upkeep(this);

        pm.registerEvents(new PlayerListener(this), this);
        pm.registerEvents(territory, this);
        pm.registerEvents(new ProtectionListener(this), this);
        pm.registerEvents(new CombatListener(this), this);
        pm.registerEvents(new GuiListener(this), this);

        GuildCommand cmd = new GuildCommand(this);
        for (String name : new String[]{"guild", "gc", "ac"}) {
            PluginCommand c = getCommand(name);
            if (c != null) {
                c.setExecutor(cmd);
                c.setTabCompleter(cmd);
            }
        }

        // tâches répétées
        Bukkit.getScheduler().runTaskTimer(this, wars::tick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, territory::applyPerks, 60L, 60L);
        Bukkit.getScheduler().runTaskTimer(this, upkeep::check, 20L * 60, 20L * 60 * 10);
        long autosave = Math.max(1, settings.l("autosave-minutes", 5)) * 60 * 20;
        Bukkit.getScheduler().runTaskTimer(this, guilds::saveDirty, autosave, autosave);

        if (pm.getPlugin("PlaceholderAPI") != null) {
            GuildExpansion.hook(this);
            getLogger().info("PlaceholderAPI détecté : placeholders %smpguilds_...% disponibles.");
        }

        for (Player p : Bukkit.getOnlinePlayers()) territory.remember(p);
        getLogger().info("SmpGuilds activé.");
    }

    @Override
    public void onDisable() {
        if (vaults != null) vaults.closeAll();
        if (wars != null) wars.shutdown();
        if (guilds != null) guilds.saveAll();
    }

    /** /guild admin reload */
    public void reload() {
        settings.load();
        messages.load();
    }

    // ------------------------------------------------------------ modes admin

    public boolean isBypassing(Player p) {
        return bypass.contains(p.getUniqueId());
    }

    public boolean toggleBypass(Player p) {
        if (bypass.remove(p.getUniqueId())) return false;
        bypass.add(p.getUniqueId());
        return true;
    }

    public boolean isSpying(Player p) {
        return spies.contains(p.getUniqueId());
    }

    public boolean toggleSpy(Player p) {
        if (spies.remove(p.getUniqueId())) return false;
        spies.add(p.getUniqueId());
        return true;
    }

    // ------------------------------------------------------------ accès aux modules

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public GuildManager guilds() {
        return guilds;
    }

    public GuildActions actions() {
        return actions;
    }

    public WarManager wars() {
        return wars;
    }

    public GuildVaults vaults() {
        return vaults;
    }

    public HomeTeleport homes() {
        return homes;
    }

    public ChatInput input() {
        return input;
    }

    public GuildChat chat() {
        return chat;
    }

    public TerritoryListener territory() {
        return territory;
    }

    /** null si Vault n'est pas installé. */
    public Economy economy() {
        return economy;
    }
}
