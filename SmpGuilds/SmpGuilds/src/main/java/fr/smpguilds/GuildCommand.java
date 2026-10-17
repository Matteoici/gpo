package fr.smpguilds;

import fr.smpguilds.gui.AlliesMenu;
import fr.smpguilds.gui.ApplicationsMenu;
import fr.smpguilds.gui.BankMenu;
import fr.smpguilds.gui.GuildListMenu;
import fr.smpguilds.gui.LevelsMenu;
import fr.smpguilds.gui.LogMenu;
import fr.smpguilds.gui.MembersMenu;
import fr.smpguilds.gui.Menus;
import fr.smpguilds.gui.SettingsMenu;
import fr.smpguilds.gui.WarMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static fr.smpguilds.Messages.guild;
import static fr.smpguilds.Messages.money;
import static fr.smpguilds.Messages.ph;

/** /guild (alias /g, /guilde, /clan), /gc et /ca (chat alliés). */
public final class GuildCommand implements TabExecutor {

    /** Alias français -> nom interne de la sous-commande. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("aide", "help"), Map.entry("creer", "create"), Map.entry("créer", "create"),
            Map.entry("dissoudre", "disband"), Map.entry("inviter", "invite"), Map.entry("rejoindre", "join"),
            Map.entry("postuler", "apply"), Map.entry("candidatures", "applications"), Map.entry("accepter", "accept"),
            Map.entry("refuser", "deny"), Map.entry("expulser", "kick"), Map.entry("quitter", "leave"),
            Map.entry("promouvoir", "promote"), Map.entry("retrograder", "demote"), Map.entry("transferer", "transfer"),
            Map.entry("desc", "description"), Map.entry("renommer", "rename"), Map.entry("couleur", "color"),
            Map.entry("banniere", "banner"), Map.entry("ouvrir", "open"), Map.entry("ouvert", "open"),
            Map.entry("sethome", "sethome"), Map.entry("terrain", "claim"), Map.entry("liberer", "unclaim"),
            Map.entry("carte", "map"), Map.entry("deposer", "deposit"), Map.entry("retirer", "withdraw"),
            Map.entry("tresor", "bank"), Map.entry("banque", "bank"), Map.entry("coffre", "vault"), Map.entry("chest", "vault"),
            Map.entry("allie", "ally"), Map.entry("allies", "allies"), Map.entry("guerre", "war"), Map.entry("membres", "members"),
            Map.entry("niveaux", "levels"), Map.entry("niveau", "levels"), Map.entry("journal", "log"),
            Map.entry("reglages", "settings"), Map.entry("parametres", "settings"), Map.entry("classement", "top"),
            Map.entry("liste", "list"), Map.entry("who", "info"));

    private static final List<String> SUBS = List.of("help", "create", "disband", "info", "list", "top", "invite", "join",
            "apply", "applications", "accept", "deny", "kick", "leave", "promote", "demote", "transfer", "description",
            "tag", "rename", "color", "banner", "open", "sethome", "home", "claim", "unclaim", "unclaimall", "autoclaim", "hauteur",
            "map", "deposit", "withdraw", "bank", "vault", "ally", "unally", "allies", "war", "members", "levels", "log",
            "settings", "chat", "admin");

    private static final List<String> ADMIN_SUBS = List.of("reload", "bypass", "spy", "disband", "addxp", "setxp",
            "setbank", "unclaim", "info", "save");

    private final SmpGuilds plugin;

    GuildCommand(SmpGuilds plugin) {
        this.plugin = plugin;
    }

    private Messages m() {
        return plugin.messages();
    }

    private GuildActions a() {
        return plugin.actions();
    }

    // ================================================================== exécution

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("gc") || cmd.equals("allychat")) {
            if (!(sender instanceof Player p)) {
                m().send(sender, "error.player-only");
                return true;
            }
            GuildChat.Mode mode = cmd.equals("gc") ? GuildChat.Mode.GUILD : GuildChat.Mode.ALLY;
            if (plugin.guilds().of(p) == null) {
                m().send(p, "error.no-guild");
            } else if (args.length == 0) {
                plugin.chat().toggle(p, mode);
            } else {
                plugin.chat().send(p, mode, String.join(" ", args));
            }
            return true;
        }

        if (args.length == 0) {
            if (sender instanceof Player p) Menus.openMain(plugin, p);
            else help(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        sub = ALIASES.getOrDefault(sub, sub);

        // commandes possibles depuis la console
        switch (sub) {
            case "help" -> {
                help(sender);
                return true;
            }
            case "admin" -> {
                admin(sender, args);
                return true;
            }
            case "info" -> {
                if (args.length >= 2) {
                    Guild g = plugin.guilds().find(args[1]);
                    if (g == null) {
                        Guild byPlayer = guildOfPlayerName(args[1]);
                        if (byPlayer == null) m().send(sender, "error.guild-not-found");
                        else a().info(sender, byPlayer);
                    } else {
                        a().info(sender, g);
                    }
                } else if (sender instanceof Player p) {
                    Guild g = a().require(p);
                    if (g != null) a().info(p, g);
                } else {
                    usage(sender, "/guild info <guilde>");
                }
                return true;
            }
            case "list", "top" -> {
                if (sender instanceof Player p) {
                    new GuildListMenu(plugin, p, sub.equals("top") ? GuildListMenu.Mode.TOP : GuildListMenu.Mode.BROWSE, 0).open();
                } else {
                    top(sender);
                }
                return true;
            }
            default -> {
            }
        }

        if (!(sender instanceof Player p)) {
            m().send(sender, "error.player-only");
            return true;
        }

        switch (sub) {
            case "create" -> {
                if (args.length < 3) usage(p, "/guild create <nom> <tag>");
                else a().create(p, args[1], args[2]);
            }
            case "disband" -> a().disband(p, args.length >= 2 && args[1].equalsIgnoreCase("confirm"));
            case "invite" -> {
                if (args.length < 2) usage(p, "/guild invite <joueur>");
                else a().invite(p, args[1]);
            }
            case "join" -> {
                if (args.length < 2) {
                    List<Guild> inv = plugin.guilds().invitesOf(p.getUniqueId());
                    if (inv.size() == 1) a().join(p, inv.get(0).name);
                    else new GuildListMenu(plugin, p, GuildListMenu.Mode.INVITES, 0).open();
                } else {
                    a().join(p, args[1]);
                }
            }
            case "apply" -> {
                if (args.length < 2) usage(p, "/guild apply <guilde> [message]");
                else a().apply(p, args[1], args.length > 2 ? join(args, 2) : "");
            }
            case "applications" -> {
                if (a().require(p) != null) new ApplicationsMenu(plugin, p).open();
            }
            case "accept", "deny" -> {
                if (args.length < 2) {
                    if (a().require(p) != null) new ApplicationsMenu(plugin, p).open();
                    return true;
                }
                Guild g = a().require(p, GuildPerm.APPLICATIONS);
                if (g == null) return true;
                UUID t = a().applicantByName(g, args[1]);
                if (t == null) m().send(p, "error.no-application");
                else if (sub.equals("accept")) a().acceptApplication(p, t);
                else a().denyApplication(p, t);
            }
            case "kick" -> {
                if (args.length < 2) usage(p, "/guild kick <joueur>");
                else a().kick(p, args[1]);
            }
            case "leave" -> a().leave(p);
            case "promote" -> {
                if (args.length < 2) usage(p, "/guild promote <joueur>");
                else a().promote(p, args[1]);
            }
            case "demote" -> {
                if (args.length < 2) usage(p, "/guild demote <joueur>");
                else a().demote(p, args[1]);
            }
            case "transfer" -> {
                if (args.length < 2) usage(p, "/guild transfer <joueur>");
                else a().transfer(p, args[1]);
            }
            case "description" -> {
                if (args.length < 2) usage(p, "/guild description <texte>");
                else a().setDescription(p, join(args, 1));
            }
            case "tag" -> {
                if (args.length < 2) usage(p, "/guild tag <tag>");
                else a().setTag(p, args[1]);
            }
            case "rename" -> {
                if (args.length < 2) usage(p, "/guild rename <nom>");
                else a().rename(p, args[1]);
            }
            case "color" -> {
                if (args.length < 2) usage(p, "/guild color <couleur>");
                else a().setColor(p, args[1]);
            }
            case "banner" -> a().setBanner(p);
            case "open" -> a().toggleOpen(p);
            case "sethome" -> a().setHome(p);
            case "home" -> a().home(p);
            case "claim" -> a().claim(p, false);
            case "hauteur", "height" -> a().setHeight(p, args);
            case "unclaim" -> a().unclaim(p);
            case "unclaimall" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("confirm")) {
                    a().unclaimAll(p);
                } else if (a().require(p) != null) {
                    p.sendMessage(m().prefix().append(m().get("claims.unclaimall-confirm"))
                            .append(Component.space()).append(m().button("button.confirm", "/guild unclaimall confirm")));
                }
            }
            case "autoclaim" -> {
                if (a().require(p, GuildPerm.CLAIM) == null) return true;
                boolean on = plugin.territory().toggleAutoClaim(p);
                m().send(p, on ? "claims.autoclaim-on" : "claims.autoclaim-off");
                if (on) a().claim(p, true);
            }
            case "map" -> a().map(p);
            case "deposit", "withdraw" -> {
                if (args.length < 2) {
                    if (a().require(p) != null) new BankMenu(plugin, p).open();
                    return true;
                }
                double amount = GuildActions.parseAmount(args[1]);
                if (sub.equals("deposit")) a().deposit(p, amount);
                else a().withdraw(p, amount);
            }
            case "bank" -> {
                if (a().require(p) != null) new BankMenu(plugin, p).open();
            }
            case "vault" -> a().openVault(p);
            case "ally" -> {
                if (args.length < 2) {
                    if (a().require(p) != null) new AlliesMenu(plugin, p).open();
                } else {
                    a().ally(p, args[1]);
                }
            }
            case "unally" -> {
                if (args.length < 2) usage(p, "/guild unally <guilde>");
                else a().unally(p, args[1]);
            }
            case "allies" -> {
                if (a().require(p) != null) new AlliesMenu(plugin, p).open();
            }
            case "war" -> war(p, args);
            case "members" -> {
                if (a().require(p) != null) new MembersMenu(plugin, p, 0).open();
            }
            case "levels" -> {
                if (a().require(p) != null) new LevelsMenu(plugin, p).open();
            }
            case "log" -> {
                if (a().require(p, GuildPerm.LOG) != null) new LogMenu(plugin, p, 0).open();
            }
            case "settings" -> {
                if (a().require(p) != null) new SettingsMenu(plugin, p).open();
            }
            case "chat" -> {
                if (a().require(p) == null) return true;
                boolean ally = args.length >= 2 && (args[1].equalsIgnoreCase("ally") || args[1].equalsIgnoreCase("allies"));
                plugin.chat().toggle(p, ally ? GuildChat.Mode.ALLY : GuildChat.Mode.GUILD);
            }
            default -> {
                // "/guild NomDeGuilde" = infos de cette guilde
                Guild g = plugin.guilds().find(args[0]);
                if (g != null) a().info(p, g);
                else m().send(p, "error.unknown-command");
            }
        }
        return true;
    }

    private void war(Player p, String[] args) {
        if (args.length < 2) {
            if (a().require(p) != null) new WarMenu(plugin, p).open();
            return;
        }
        String s = args[1].toLowerCase(Locale.ROOT);
        switch (s) {
            case "surrender", "abandon", "abandonner" -> plugin.wars().surrender(p);
            case "declare", "declarer" -> {
                if (args.length < 3) usage(p, "/guild war declare <guilde>");
                else plugin.wars().declare(p, args[2]);
            }
            case "status", "info" -> {
                if (a().require(p) != null) new WarMenu(plugin, p).open();
            }
            default -> plugin.wars().declare(p, args[1]);
        }
    }

    // ================================================================== admin

    private void admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("smpguilds.admin")) {
            m().send(sender, "error.no-permission");
            return;
        }
        if (args.length < 2) {
            for (Component line : m().list("help.admin")) sender.sendMessage(line);
            return;
        }
        GuildManager gm = plugin.guilds();
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reload();
                m().send(sender, "admin.reloaded");
            }
            case "save" -> {
                gm.saveAll();
                m().send(sender, "admin.saved", ph("count", gm.all().size()));
            }
            case "bypass" -> {
                if (sender instanceof Player p) m().send(p, plugin.toggleBypass(p) ? "admin.bypass-on" : "admin.bypass-off");
                else m().send(sender, "error.player-only");
            }
            case "spy" -> {
                if (sender instanceof Player p) m().send(p, plugin.toggleSpy(p) ? "admin.spy-on" : "admin.spy-off");
                else m().send(sender, "error.player-only");
            }
            case "info" -> {
                Guild g = args.length >= 3 ? gm.find(args[2]) : null;
                if (g == null) m().send(sender, "error.guild-not-found");
                else a().info(sender, g);
            }
            case "disband" -> {
                Guild g = args.length >= 3 ? gm.find(args[2]) : null;
                if (g == null) {
                    m().send(sender, "error.guild-not-found");
                    return;
                }
                g.broadcast(m().prefix().append(m().get("admin.disbanded-members", guild(g))));
                m().send(sender, "admin.disbanded", guild(g));
                gm.disband(g);
            }
            case "addxp", "setxp", "setbank" -> {
                if (args.length < 4) {
                    usage(sender, "/guild admin " + args[1].toLowerCase(Locale.ROOT) + " <guilde> <valeur>");
                    return;
                }
                Guild g = gm.find(args[2]);
                if (g == null) {
                    m().send(sender, "error.guild-not-found");
                    return;
                }
                double v = GuildActions.parseAmount(args[3]);
                if (v < 0) {
                    m().send(sender, "error.invalid-amount");
                    return;
                }
                switch (args[1].toLowerCase(Locale.ROOT)) {
                    case "addxp" -> gm.addXp(g, (long) v);
                    case "setxp" -> {
                        g.xp = (long) v;
                        gm.markDirty(g);
                    }
                    default -> {
                        g.bank = v;
                        gm.markDirty(g);
                    }
                }
                gm.log(g, "Admin " + sender.getName() + " : " + args[1] + " " + args[3]);
                m().send(sender, "admin.updated", guild(g), ph("xp", g.xp), ph("level", gm.level(g).level()),
                        ph("bank", money(g.bank)));
            }
            case "unclaim" -> {
                if (!(sender instanceof Player p)) {
                    m().send(sender, "error.player-only");
                    return;
                }
                ChunkPos pos = ChunkPos.of(p.getLocation());
                Guild g = gm.at(pos);
                if (g == null) {
                    m().send(p, "admin.not-claimed");
                    return;
                }
                gm.unclaim(g, pos);
                gm.log(g, "Admin " + p.getName() + " a libéré le chunk " + pos.x() + ", " + pos.z());
                m().send(p, "admin.unclaimed", guild(g));
            }
            default -> {
                for (Component line : m().list("help.admin")) sender.sendMessage(line);
            }
        }
    }

    // ================================================================== aide / classement

    private void help(CommandSender sender) {
        for (Component line : m().list("help.player")) sender.sendMessage(line);
        if (sender.hasPermission("smpguilds.admin")) sender.sendMessage(m().get("help.admin-hint"));
    }

    private void top(CommandSender sender) {
        m().send(sender, "top.header");
        List<Guild> top = plugin.guilds().top();
        for (int i = 0; i < Math.min(10, top.size()); i++) {
            Guild g = top.get(i);
            sender.sendMessage(m().get("top.line", guild(g), ph("rank", i + 1), ph("level", plugin.guilds().level(g).level()),
                    ph("xp", g.xp), ph("members", g.members.size())));
        }
        if (top.isEmpty()) m().send(sender, "top.empty");
    }

    private void usage(CommandSender to, String usage) {
        m().send(to, "error.usage", ph("usage", usage));
    }

    private Guild guildOfPlayerName(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return plugin.guilds().of(online);
        for (Guild g : plugin.guilds().all()) {
            for (UUID u : g.members.keySet()) if (g.nameOf(u).equalsIgnoreCase(name)) return g;
        }
        return null;
    }

    private static String join(String[] args, int from) {
        return String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }

    // ================================================================== complétion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (!cmd.equals("guild")) return List.of();
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(SUBS);
            if (!sender.hasPermission("smpguilds.admin")) subs.remove("admin");
            return filter(subs, args[0]);
        }
        String sub = ALIASES.getOrDefault(args[0].toLowerCase(Locale.ROOT), args[0].toLowerCase(Locale.ROOT));
        Guild mine = sender instanceof Player p ? plugin.guilds().of(p) : null;
        if (args.length == 2) {
            return switch (sub) {
                case "invite" -> filter(onlineWithoutGuild(), args[1]);
                case "kick", "promote", "demote", "transfer" -> mine == null ? List.of()
                        : filter(mine.members.keySet().stream().map(mine::nameOf).toList(), args[1]);
                case "accept", "deny" -> mine == null ? List.of()
                        : filter(mine.applications.keySet().stream().map(mine::nameOf).toList(), args[1]);
                case "join", "apply", "info", "ally" -> filter(guildNames(), args[1]);
                case "unally" -> mine == null ? List.of()
                        : filter(mine.allies.stream().map(plugin.guilds()::get).filter(g -> g != null).map(g -> g.name).toList(), args[1]);
                case "war" -> {
                    List<String> l = new ArrayList<>(List.of("declare", "surrender", "status"));
                    l.addAll(guildNames());
                    yield filter(l, args[1]);
                }
                case "color" -> filter(new ArrayList<>(NamedTextColor.NAMES.keys()), args[1]);
                case "chat" -> filter(List.of("guild", "ally"), args[1]);
                case "deposit", "withdraw" -> filter(List.of("100", "1000", "10k", "100k"), args[1]);
                case "disband", "unclaimall" -> filter(List.of("confirm"), args[1]);
                case "admin" -> sender.hasPermission("smpguilds.admin") ? filter(ADMIN_SUBS, args[1]) : List.of();
                default -> List.of();
            };
        }
        if (args.length == 3) {
            if (sub.equals("war") && args[1].equalsIgnoreCase("declare")) return filter(guildNames(), args[2]);
            if (sub.equals("admin") && sender.hasPermission("smpguilds.admin")
                    && List.of("disband", "addxp", "setxp", "setbank", "info").contains(args[1].toLowerCase(Locale.ROOT))) {
                return filter(guildNames(), args[2]);
            }
        }
        return List.of();
    }

    private List<String> guildNames() {
        return plugin.guilds().all().stream().map(g -> g.name).sorted().toList();
    }

    private List<String> onlineWithoutGuild() {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (plugin.guilds().of(p) == null) out.add(p.getName());
        return out;
    }

    private static List<String> filter(List<String> options, String typed) {
        String t = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(t)) out.add(o);
        return out;
    }
}
