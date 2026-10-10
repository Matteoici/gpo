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
        entityKey = new NamespacedKey(this, "airdrop");
        settings = new Settings(getConfig(), getLogger());
        texts = new Texts(this);
        texts.load();
        manager = new DropManager(this);
        manager.load();

        getServer().getPluginManager().registerEvents(new DropListener(this), this);
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
