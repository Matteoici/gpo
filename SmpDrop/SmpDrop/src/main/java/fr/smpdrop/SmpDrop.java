package fr.smpdrop;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class SmpDrop extends JavaPlugin {

    private Settings settings;
    private Texts texts;
    private DropManager manager;
    private NamespacedKey entityKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrate();
        entityKey = new NamespacedKey(this, "airdrop");
        settings = new Settings(getConfig(), getLogger());
        texts = new Texts(this);
        texts.load();
        manager = new DropManager(this);
        manager.load();

        getServer().getPluginManager().registerEvents(new DropListener(this), this);
        getServer().getPluginManager().registerEvents(new Banknotes(), this);
        PluginCommand cmd = getCommand("largage");
        if (cmd != null) {
            DropCommand dc = new DropCommand(this);
            cmd.setExecutor(dc);
            cmd.setTabCompleter(dc);
        }
        Bukkit.getScheduler().runTaskTimer(this, () -> manager.tickSecond(), 40L, 20L);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                new DropExpansion(this).register();
                getLogger().info("Placeholders PlaceholderAPI activés (%smpdrop_...%).");
            } catch (Throwable t) {
                getLogger().warning("PlaceholderAPI : " + t.getMessage());
            }
        }
        getLogger().info(settings.loot.entries.size() + " objets de butin chargés. Prochain largage dans "
                + DropEvent.time(manager.nextDrop() - System.currentTimeMillis()) + ".");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.stopAll();
            manager.save();
        }
    }

    /** Ajoute au config déjà installé ce qui est nouveau (argent, plus d'objets) sans toucher au reste. */
    private void migrate() {
        var c = getConfig();
        int v = c.getInt("config-version", 1);
        if (v < 2) {
            java.util.List<java.util.Map<?, ?>> items = new java.util.ArrayList<>(c.getMapList("loot.items"));
            var def = c.getDefaults();
            if (def != null) {
                for (java.util.Map<?, ?> m : def.getMapList("loot.items")) {
                    if (m.get("money") != null || Boolean.TRUE.equals(m.get("v2"))) items.add(m);
                }
            }
            c.set("loot.items", items);
            if ("5-7".equals(String.valueOf(c.get("loot.rolls")))) c.set("loot.rolls", "7-9");
            c.set("loot.money-symbol", c.getString("loot.money-symbol", "$"));
            c.set("config-version", 2);
            saveConfig();
            getLogger().info("Largages : billets et nouveaux objets ajoutés au butin.");
        }
    }

    void reload() {
        reloadConfig();
        settings = new Settings(getConfig(), getLogger());
        texts.load();
    }

    public Settings settings() {
        return settings;
    }

    public Texts texts() {
        return texts;
    }

    public DropManager manager() {
        return manager;
    }

    public NamespacedKey entityKey() {
        return entityKey;
    }
}
