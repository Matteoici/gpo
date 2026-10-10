package fr.smpadmin;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

import static fr.smpadmin.Texts.ph;
import static fr.smpadmin.Texts.pp;

public final class SmpAdmin extends JavaPlugin {

    private static final List<String> COMMANDS = List.of("admin", "vanish", "ban", "unban", "banlist", "mute", "unmute",
            "kick", "warn", "history", "freeze", "sus", "spec", "invsee", "ecsee", "staffchat", "clearchat", "chatlock");

    private Settings settings;
    private Texts texts;
    private Sanctions sanctions;
    private StaffState state;
    private Actions actions;
    private Vanish vanish;
    private Spectate spectate;
    private ChatInput input;
    private AdminListener listener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new Settings(getConfig(), getLogger());
        texts = new Texts(this);
        texts.load();
        sanctions = new Sanctions(this);
        sanctions.load();
        state = new StaffState(this);
        state.load();
        actions = new Actions(this);
        vanish = new Vanish(this);
        spectate = new Spectate(this);
        input = new ChatInput(this);
        listener = new AdminListener(this);
        getServer().getPluginManager().registerEvents(listener, this);

        AdminCommand cmd = new AdminCommand(this);
        for (String name : COMMANDS) {
            PluginCommand pc = getCommand(name);
            if (pc == null) continue;
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }

        // après un /reload : réappliquer le vanish
        for (Player p : Bukkit.getOnlinePlayers()) vanish.onJoin(p);

        Bukkit.getScheduler().runTaskTimer(this, () -> vanish.tick(), 40L, 40L);
        Bukkit.getScheduler().runTaskTimer(this, () -> spectate.tick(), 10L, 10L);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) if (actions.isFrozen(p.getUniqueId())) actions.showFreeze(p);
        }, 60L, 60L);
    }

    @Override
    public void onDisable() {
        if (spectate != null) spectate.stopAll();
        if (vanish != null) vanish.showAll();
        if (state != null) state.save();
        if (sanctions != null) sanctions.save();
    }

    void reload() {
        reloadConfig();
        settings = new Settings(getConfig(), getLogger());
        texts.load();
    }

    /** Ouvre l'inventaire (ou le coffre de l'Ender) d'un joueur. */
    public void openInventory(Player viewer, Player target, boolean ender) {
        viewer.openInventory(ender ? target.getEnderChest() : target.getInventory());
        if (!viewer.hasPermission("smpadmin.invsee.modify")) {
            listener.markReadOnly(viewer);
            texts.send(viewer, "invsee.readonly", ph("player", target.getName()));
        }
    }

    /** Une ligne du casier (commande /history depuis la console). */
    public Component historyLine(Punishment p) {
        return texts.get("history.line", actions.resolvers(p), pp("type", texts.raw("types." + p.type.name().toLowerCase())),
                Texts.pc("status", status(p)));
    }

    public Component status(Punishment p) {
        if (p.revoked) return texts.get("status.revoked", ph("revoker", p.revokedBy == null ? "?" : p.revokedBy));
        if (!p.hasDuration()) return texts.get("status.none");
        return texts.get(p.isActive(System.currentTimeMillis()) ? "status.active" : "status.expired");
    }

    public Settings settings() {
        return settings;
    }

    public Texts texts() {
        return texts;
    }

    public Sanctions sanctions() {
        return sanctions;
    }

    public StaffState state() {
        return state;
    }

    public Actions actions() {
        return actions;
    }

    public Vanish vanish() {
        return vanish;
    }

    public Spectate spectate() {
        return spectate;
    }

    public ChatInput input() {
        return input;
    }
}
