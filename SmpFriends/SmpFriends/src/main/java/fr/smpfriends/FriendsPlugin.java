package fr.smpfriends;

import fr.smpfriends.menu.Menu;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Point d'entrée de SmpFriends. */
public final class FriendsPlugin extends JavaPlugin {

    private FriendStore store;
    private Friends friends;
    private Prompts prompts;
    private Activity activity;
    private VaultHook vault;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getDataFolder().mkdirs();
        store = new FriendStore(this);
        store.load();
        friends = new Friends(this);
        prompts = new Prompts(this);
        activity = new Activity(this);
        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            vault = new VaultHook();
        }

        FriendsCommand cmd = new FriendsCommand(this);
        register("amis", cmd);
        register("fc", cmd);
        getServer().getPluginManager().registerEvents(new FriendsListener(this), this);

        for (Player p : Bukkit.getOnlinePlayers()) activity.moved(p);
        // suivi à la boussole : toutes les 10 ticks
        Bukkit.getScheduler().runTaskTimer(this, activity::tick, 10L, 10L);
        // menus ouverts rafraîchis toutes les 2 s
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getOpenInventory().getTopInventory().getHolder() instanceof Menu m && m.autoRefresh()) m.render();
            }
        }, 40L, 40L);
        getLogger().info("SmpFriends activé (" + store.all().size() + " profils chargés).");
    }

    private void register(String name, FriendsCommand cmd) {
        PluginCommand c = getCommand(name);
        if (c != null) {
            c.setExecutor(cmd);
            c.setTabCompleter(cmd);
        }
    }

    @Override
    public void onDisable() {
        if (store != null) store.saveNow();
    }

    public FriendStore store() {
        return store;
    }

    public Friends friends() {
        return friends;
    }

    public Prompts prompts() {
        return prompts;
    }

    public Activity activity() {
        return activity;
    }

    /** null si Vault n'est pas installé. */
    public VaultHook vault() {
        return vault;
    }

    public String symbol() {
        return getConfig().getString("currency-symbol", "$");
    }
}
