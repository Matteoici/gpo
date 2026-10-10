package fr.pausemenu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Génère un datapack de dialogues pour ajouter des boutons au menu pause (Echap). */
public final class PauseMenuPlugin extends JavaPlugin implements TabExecutor {

    private static final String PACK_NAME = "PauseMenu";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        PluginCommand cmd = getCommand("pausemenu");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        generate(Bukkit.getConsoleSender());
    }

    // ================================================================== génération

    private void generate(CommandSender who) {
        try {
            Model.Settings settings = settings();
            List<Model.Category> categories = categories();
            int buttons = categories.stream().mapToInt(c -> c.entries().size()).sum();

            Path base = datapackFolder().resolve(PACK_NAME);
            Map<String, String> files = Dialogs.build(settings, categories);

            boolean changed = false;
            // supprime les anciens dialogues (catégories retirées) puis réécrit
            Path dialogDir = base.resolve("data").resolve(Dialogs.NAMESPACE).resolve("dialog");
            if (Files.isDirectory(dialogDir)) {
                try (Stream<Path> s = Files.walk(dialogDir)) {
                    List<Path> old = s.filter(Files::isRegularFile).toList();
                    for (Path p : old) {
                        String rel = base.relativize(p).toString().replace('\\', '/');
                        if (!files.containsKey(rel)) {
                            Files.delete(p);
                            changed = true;
                        }
                    }
                }
            }
            for (Map.Entry<String, String> e : files.entrySet()) {
                changed |= write(base.resolve(e.getKey()), e.getValue());
            }

            who.sendMessage(Component.text("PauseMenu : " + buttons + " bouton(s) dans " + categories.size()
                    + " catégorie(s). Datapack : " + base, NamedTextColor.GREEN));
            if (changed) {
                who.sendMessage(Component.text("Menu modifié : REDÉMARRE le serveur, puis reconnecte-toi pour le voir.",
                        NamedTextColor.YELLOW));
            }
        } catch (IOException | RuntimeException ex) {
            getLogger().warning("Impossible de générer le menu : " + ex);
            who.sendMessage(Component.text("Erreur : " + ex.getMessage(), NamedTextColor.RED));
        }
    }

    private Path datapackFolder() {
        String custom = getConfig().getString("datapacks-folder", "auto");
        if (custom != null && !custom.isBlank() && !custom.equalsIgnoreCase("auto")) return Path.of(custom);
        return Bukkit.getWorlds().get(0).getWorldFolder().toPath().resolve("datapacks");
    }

    private static boolean write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        if (Files.exists(file) && Files.readString(file, StandardCharsets.UTF_8).equals(content)) return false;
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return true;
    }

    // ================================================================== configuration

    private Model.Settings settings() {
        return new Model.Settings(
                getConfig().getString("title", "Menu du serveur"),
                getConfig().getString("button-label", "★ Menu du serveur ★"),
                getConfig().getString("title-color", "gold"),
                getConfig().getBoolean("flat", false),
                Math.max(1, Math.min(6, getConfig().getInt("columns", 2))),
                Math.max(60, Math.min(310, getConfig().getInt("button-width", 150))),
                getConfig().getBoolean("quick-actions", true));
    }

    /** Catégories de la config, en ne gardant que les boutons dont le plugin est installé. */
    private List<Model.Category> categories() {
        List<Model.Category> out = new ArrayList<>();
        ConfigurationSection root = getConfig().getConfigurationSection("categories");
        if (root == null) return out;
        for (String ck : root.getKeys(false)) {
            ConfigurationSection cs = root.getConfigurationSection(ck);
            if (cs == null) continue;
            List<Model.Entry> entries = new ArrayList<>();
            ConfigurationSection bs = cs.getConfigurationSection("buttons");
            if (bs != null) {
                for (String bk : bs.getKeys(false)) {
                    ConfigurationSection b = bs.getConfigurationSection(bk);
                    if (b == null) continue;
                    String command = b.getString("command", "").trim();
                    if (command.isEmpty()) continue;
                    String needed = b.getString("plugin", "").trim();
                    if (!needed.isEmpty() && Bukkit.getPluginManager().getPlugin(needed) == null) continue;
                    entries.add(new Model.Entry(bk, b.getString("name", bk), command,
                            b.getString("mode", "run"), b.getString("description", "")));
                }
            }
            out.add(new Model.Category(ck, cs.getString("name", ck), cs.getString("color", "white"),
                    cs.getString("description", ""), entries));
        }
        return out;
    }

    // ================================================================== commande

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "list" : args[0].toLowerCase();
        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission("pausemenu.admin")) {
                    sender.sendMessage(Component.text("Permission manquante.", NamedTextColor.RED));
                    return true;
                }
                reloadConfig();
                generate(sender);
            }
            default -> list(sender);
        }
        return true;
    }

    /** Version « chat » du menu : utile en attendant le redémarrage, ou pour les joueurs sans le menu pause. */
    private void list(CommandSender sender) {
        sender.sendMessage(Component.text("━━ " + getConfig().getString("title", "Menu du serveur") + " ━━",
                NamedTextColor.GOLD, TextDecoration.BOLD));
        for (Model.Category c : categories()) {
            if (c.entries().isEmpty()) continue;
            sender.sendMessage(Component.text(c.name(), NamedTextColor.YELLOW, TextDecoration.BOLD));
            Component line = Component.empty();
            for (Model.Entry e : c.entries()) {
                String cmd = e.command().startsWith("/") ? e.command() : "/" + e.command();
                ClickEvent click = e.suggest() ? ClickEvent.suggestCommand(cmd) : ClickEvent.runCommand(cmd);
                line = line.append(Component.text("[" + e.name() + "] ", NamedTextColor.AQUA)
                        .clickEvent(click)
                        .hoverEvent(Component.text(e.description().isEmpty() ? cmd : e.description(), NamedTextColor.GRAY)));
            }
            sender.sendMessage(line);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> r = new ArrayList<>(List.of("list"));
            if (sender.hasPermission("pausemenu.admin")) r.add("reload");
            return r.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
    }
}
