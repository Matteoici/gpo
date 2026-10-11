package fr.gpotrade;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** /trade <joueur> | accept | decline | open | cancel | rate <id> <1-5> | reload */
final class TradeCommand implements TabExecutor {

    private final TradePlugin plugin;

    TradeCommand(TradePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("gpotrade.admin")) {
                sender.sendMessage(TradePlugin.msg("Permission refusée.", NamedTextColor.RED));
                return true;
            }
            plugin.reload();
            sender.sendMessage(TradePlugin.msg("Configuration de GpoTrade rechargée.", NamedTextColor.GREEN));
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Commande réservée aux joueurs.");
            return true;
        }
        if (args.length == 0) {
            help(p);
            return true;
        }
        TradeSession s = plugin.session(p);
        switch (args[0].toLowerCase()) {
            case "accept" -> plugin.acceptRequest(p);
            case "decline" -> plugin.declineRequest(p);
            case "open" -> {
                if (s == null) p.sendMessage(TradePlugin.msg("Tu n'es dans aucun échange.", NamedTextColor.RED));
                else s.reopen(s.indexOf(p));
            }
            case "cancel" -> {
                if (s == null) p.sendMessage(TradePlugin.msg("Tu n'es dans aucun échange.", NamedTextColor.RED));
                else s.cancel(p.getName() + " a annulé l'échange.");
            }
            case "argent", "montant", "money" -> {
                if (s == null) {
                    p.sendMessage(TradePlugin.msg("Tu n'es dans aucun échange.", NamedTextColor.RED));
                } else if (args.length < 2 || TradeSession.parseAmount(String.join("", java.util.Arrays.copyOfRange(args, 1, args.length))) < 0) {
                    p.sendMessage(TradePlugin.msg("Utilisation : /trade argent <montant> (ex. 1500, 2.5k)", NamedTextColor.RED));
                } else {
                    s.setMoney(s.indexOf(p), TradeSession.parseAmount(String.join("", java.util.Arrays.copyOfRange(args, 1, args.length))));
                }
            }
            case "rate" -> {
                if (args.length != 3) return true;
                try {
                    plugin.rate(p, args[1], Integer.parseInt(args[2]));
                } catch (NumberFormatException e) {
                    p.sendMessage(TradePlugin.msg("Note invalide.", NamedTextColor.RED));
                }
            }
            default -> {
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null) p.sendMessage(TradePlugin.msg("Joueur introuvable.", NamedTextColor.RED));
                else plugin.request(p, target);
            }
        }
        return true;
    }

    private void help(Player p) {
        p.sendMessage(TradePlugin.msg("—— GpoTrade ——", NamedTextColor.GOLD));
        p.sendMessage(TradePlugin.msg("/trade <joueur> : proposer un échange", NamedTextColor.YELLOW));
        if (plugin.sneakClickRequest()) {
            p.sendMessage(TradePlugin.msg("Accroupi + clic droit sur un joueur : pareil", NamedTextColor.YELLOW));
        }
        p.sendMessage(TradePlugin.msg("/trade open | cancel : rouvrir / annuler l'échange en cours", NamedTextColor.YELLOW));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length != 1) return out;
        out.addAll(List.of("accept", "decline", "open", "cancel"));
        if (sender.hasPermission("gpotrade.admin")) out.add("reload");
        for (Player p : Bukkit.getOnlinePlayers()) if (!p.equals(sender)) out.add(p.getName());
        out.removeIf(s -> !s.toLowerCase().startsWith(args[0].toLowerCase()));
        return out;
    }
}
