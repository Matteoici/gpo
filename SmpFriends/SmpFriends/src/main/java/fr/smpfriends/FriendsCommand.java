package fr.smpfriends;

import fr.smpfriends.menu.MainMenu;
import fr.smpfriends.menu.RequestsMenu;
import fr.smpfriends.menu.SettingsMenu;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** /amis [add|remove|accept|deny|msg|requests|settings|list|reload] [joueur]  et  /fc <message> */
final class FriendsCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("add", "remove", "accept", "deny", "msg", "requests", "settings", "list");

    private final FriendsPlugin plugin;

    FriendsCommand(FriendsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("amis") && args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("smpfriends.admin")) return true;
            plugin.reloadConfig();
            sender.sendMessage(Text.msg("Configuration rechargée.", NamedTextColor.GREEN));
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Commande réservée aux joueurs.");
            return true;
        }
        Friends f = plugin.friends();

        if (command.getName().equalsIgnoreCase("fc")) {
            if (args.length == 0) p.sendMessage(Text.msg("Usage : /fc <message>", NamedTextColor.RED));
            else f.friendChat(p, String.join(" ", args));
            return true;
        }
        if (args.length == 0) {
            new MainMenu(plugin, p).open();
            return true;
        }
        String sub = args[0].toLowerCase();
        String target = args.length > 1 ? args[1] : null;
        switch (sub) {
            case "requests", "demandes" -> new RequestsMenu(plugin, p).open();
            case "settings", "reglages" -> new SettingsMenu(plugin, p).open();
            case "list" -> list(p);
            case "add", "ajouter" -> {
                if (target == null) usage(p);
                else f.request(p, target);
            }
            case "remove", "retirer" -> withPlayer(p, target, id -> f.remove(p, id));
            case "accept" -> withPlayer(p, target, id -> f.accept(p, id));
            case "deny" -> withPlayer(p, target, id -> f.deny(p, id));
            case "msg" -> {
                if (target == null) { usage(p); return true; }
                OfflinePlayer op = Friends.find(target);
                if (op == null || !f.areFriends(p.getUniqueId(), op.getUniqueId())) {
                    p.sendMessage(Text.msg("Ce joueur n'est pas ton ami.", NamedTextColor.RED));
                } else if (args.length > 2) {
                    f.message(p, op.getUniqueId(), String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                } else {
                    UUID id = op.getUniqueId();
                    plugin.prompts().ask(p, "Écris ton message pour " + op.getName() + " :", text -> f.message(p, id, text));
                }
            }
            default -> {
                // /amis <pseudo> = raccourci pour ajouter
                f.request(p, args[0]);
            }
        }
        return true;
    }

    private void withPlayer(Player p, String name, java.util.function.Consumer<UUID> action) {
        if (name == null) {
            usage(p);
            return;
        }
        OfflinePlayer op = Friends.find(name);
        if (op == null) {
            p.sendMessage(Text.msg("Joueur introuvable.", NamedTextColor.RED));
            return;
        }
        action.accept(op.getUniqueId());
    }

    private void list(Player p) {
        List<UUID> all = plugin.friends().sorted(p, false);
        p.sendMessage(Text.msg("Tes amis (" + all.size() + ") :", NamedTextColor.WHITE));
        for (UUID u : all) {
            boolean on = Bukkit.getPlayer(u) != null;
            p.sendMessage(net.kyori.adventure.text.Component.text((on ? " ● " : " ○ ") + Friends.name(u),
                    on ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        }
    }

    private void usage(Player p) {
        p.sendMessage(Text.msg("/amis : ouvrir le menu", NamedTextColor.YELLOW));
        p.sendMessage(Text.msg("/amis add|remove|accept|deny <joueur>", NamedTextColor.YELLOW));
        p.sendMessage(Text.msg("/amis msg <joueur> [message]  •  /fc <message>", NamedTextColor.YELLOW));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!(sender instanceof Player p) || command.getName().equalsIgnoreCase("fc")) return out;
        if (args.length == 1) {
            out.addAll(SUBS);
        } else if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "add" -> Bukkit.getOnlinePlayers().forEach(o -> { if (!o.equals(p)) out.add(o.getName()); });
                case "remove", "msg" -> plugin.friends().profile(p.getUniqueId()).friends.forEach(u -> out.add(Friends.name(u)));
                case "accept", "deny" -> plugin.friends().profile(p.getUniqueId()).requests.forEach(u -> out.add(Friends.name(u)));
                default -> { }
            }
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase();
        out.removeIf(s -> !s.toLowerCase().startsWith(last));
        return out;
    }
}
