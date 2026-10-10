package fr.smpstart;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/** SmpStart : début de partie guidé (kit, protection débutant, étapes avec récompenses, accueil). */
public final class SmpStart extends JavaPlugin {

    private Texts texts;
    private Settings settings;
    private DataStore store;
    private Economy economy;
    private Kit kit;
    private BarManager bars;
    private Progress progress;
    private Welcome welcome;
    private NamespacedKey guideKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        guideKey = new NamespacedKey(this, "guide");
        texts = new Texts(this);
        texts.load();
        kit = new Kit(this);
        settings = new Settings(this);
        settings.load();
        store = new DataStore(this);
        store.load();

        PluginManager pm = getServer().getPluginManager();
        if (pm.getPlugin("Vault") != null) {
            economy = new Economy();
            getLogger().info("Vault détecté : l'argent des récompenses est activé.");
        } else {
            getLogger().warning("Vault absent : les récompenses en argent sont désactivées.");
        }

        bars = new BarManager(this);
        progress = new Progress(this);
        welcome = new Welcome(this);

        pm.registerEvents(new StartListener(this), this);
        pm.registerEvents(new GuiListener(this), this);
        PluginCommand cmd = getCommand("guide");
        if (cmd != null) {
            StartCommand handler = new StartCommand(this);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        Bukkit.getScheduler().runTaskTimer(this, this::minuteTick, 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(this, store::saveIfDirty, 2400L, 2400L);

        // joueurs déjà connectés (ex. après un /reload)
        for (Player p : Bukkit.getOnlinePlayers()) bars.update(p);
    }

    @Override
    public void onDisable() {
        if (store != null) store.save();
    }

    void reloadAll() {
        texts.load();
        settings.load();
        for (Player p : Bukkit.getOnlinePlayers()) bars.update(p);
    }

    /** Toutes les minutes : décompte de la protection, étapes « temps de jeu », rappels. */
    private void minuteTick() {
        int reminder = settings.reminderMinutes();
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = store.get(p.getUniqueId());
            if (d == null) continue;
            if (d.protectionSec > 0) {
                d.protectionSec = Math.max(0, d.protectionSec - 60);
                store.markDirty();
                if (d.protectionSec == 0) texts.send(p, "protection.ended");
            }
            if (d.finished) continue;
            progress.add(p, StepType.PLAYTIME, "", false, 1);
            if (d.finished || progress.current(p) == null) continue; // le guide vient de se terminer
            if (reminder > 0 && ++d.reminderMin >= reminder) {
                d.reminderMin = 0;
                texts.send(p, "reminder");
                progress.sendCurrent(p);
            }
        }
    }

    public NamespacedKey guideKey() {
        return guideKey;
    }

    public Texts texts() {
        return texts;
    }

    public Settings settings() {
        return settings;
    }

    public DataStore data() {
        return store;
    }

    public Kit kit() {
        return kit;
    }

    public BarManager bars() {
        return bars;
    }

    public Progress progress() {
        return progress;
    }

    public Welcome welcome() {
        return welcome;
    }

    /** null si Vault n'est pas installé. */
    public Economy economy() {
        return economy;
    }
}
