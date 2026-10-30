package fr.smppatch;

import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Patch notes en livre. Les notes sont dans le jar (patchnotes.yml, la plus récente en premier) :
 * à chaque mise à jour, le livre s'ouvre une fois tout seul à la connexion. /patchnote pour le relire.
 */
public final class SmpPatchNotes extends JavaPlugin implements Listener, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    record Note(String id, String title, String date, List<Component> pages) {
    }

    private final List<Note> notes = new ArrayList<>();
    private NamespacedKey seenKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        seenKey = new NamespacedKey(this, "seen");
        load();
        getServer().getPluginManager().registerEvents(this, this);
        var c = getCommand("patchnote");
        if (c != null) {
            c.setExecutor(this);
            c.setTabCompleter(this);
        }
        getLogger().info(notes.size() + " patch note(s), dernière : " + (notes.isEmpty() ? "aucune" : notes.get(0).title()));
    }

    private void load() {
        reloadConfig();
        notes.clear();
        try (InputStream in = getResource("patchnotes.yml")) {
            if (in == null) return;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (Map<?, ?> m : y.getMapList("notes")) {
                Object id = m.get("id"), title = m.get("title"), date = m.get("date"), pages = m.get("pages");
                if (id == null || !(pages instanceof List<?> pl)) continue;
                List<Component> comps = new ArrayList<>();
                for (Object p : pl) comps.add(MM.deserialize(String.valueOf(p).stripTrailing()));
                notes.add(new Note(String.valueOf(id), title == null ? "Mise à jour" : String.valueOf(title), date == null ? "" : String.valueOf(date), comps));
            }
        } catch (Exception e) {
            getLogger().warning("Lecture des patch notes impossible : " + e.getMessage());
        }
    }

    private Book book(Note n) {
        return Book.book(MM.deserialize(n.title()), MM.deserialize("Limitless"), n.pages());
    }

    private void open(Player p, Note n) {
        p.openBook(book(n));
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (notes.isEmpty() || !getConfig().getBoolean("open-on-join", true)) return;
        Player p = e.getPlayer();
        Note latest = notes.get(0);
        String seen = p.getPersistentDataContainer().get(seenKey, PersistentDataType.STRING);
        if (latest.id().equals(seen)) return;
        long delay = Math.max(1, getConfig().getLong("delay-seconds", 4)) * 20L;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            p.getPersistentDataContainer().set(seenKey, PersistentDataType.STRING, latest.id());
            open(p, latest);
            p.sendMessage(MM.deserialize("<gold>✦ <yellow>Nouvelle mise à jour : <white><b>" + latest.title() + "</b></white> ! "
                    + "<gray><click:run_command:'/patchnote'><hover:show_text:'<gray>Relire le patch note'>[<yellow>Relire</yellow>]</hover></click>"));
        }, delay);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        if (a.length >= 1 && a[0].equalsIgnoreCase("reload") && sender.hasPermission("smppatch.admin")) {
            load();
            sender.sendMessage(MM.deserialize("<green>Patch notes rechargées (" + notes.size() + ")."));
            return true;
        }
        if (a.length >= 1 && a[0].equalsIgnoreCase("tous") && sender.hasPermission("smppatch.admin")) {
            // rouvre le dernier livre à tout le monde en ligne
            if (!notes.isEmpty()) for (Player p : Bukkit.getOnlinePlayers()) open(p, notes.get(0));
            return true;
        }
        if (notes.isEmpty()) {
            sender.sendMessage(MM.deserialize("<gray>Aucun patch note pour l'instant."));
            return true;
        }
        if (a.length >= 1 && (a[0].equalsIgnoreCase("liste") || a[0].equalsIgnoreCase("list"))) {
            sender.sendMessage(MM.deserialize("<gold><b>Patch notes</b> <gray>(clique pour lire)"));
            for (int i = 0; i < notes.size(); i++) {
                Note n = notes.get(i);
                sender.sendMessage(MM.deserialize("<gray> " + (i + 1) + ". <click:run_command:'/patchnote " + (i + 1) + "'><hover:show_text:'<gray>Lire'>"
                        + "<yellow>" + n.title() + "</yellow></hover></click> <dark_gray>" + n.date()));
            }
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Commande réservée aux joueurs (ou : /patchnote liste | reload | tous).");
            return true;
        }
        int idx = 0;
        if (a.length >= 1) {
            try {
                idx = Math.max(0, Math.min(notes.size() - 1, Integer.parseInt(a[0]) - 1));
            } catch (NumberFormatException ignored) {
                // la dernière
            }
        }
        open(p, notes.get(idx));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.add("liste");
            for (int i = 1; i <= notes.size(); i++) out.add(String.valueOf(i));
            if (sender.hasPermission("smppatch.admin")) out.addAll(List.of("reload", "tous"));
            out.removeIf(s -> !s.startsWith(a[0].toLowerCase(java.util.Locale.ROOT)));
        }
        return out;
    }

    ConfigurationSection cfg() {
        return getConfig();
    }
}
