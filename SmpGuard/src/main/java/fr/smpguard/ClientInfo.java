package fr.smpguard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/** Quel client utilise chaque joueur : nom lisible + mods qui s'annoncent (canaux). */
public final class ClientInfo {

    private ClientInfo() {
    }

    /** Nom lisible du client (Vanilla, Fabric, Forge, Lunar Client...). */
    public static String name(SmpGuard plugin, Player p) {
        if (plugin.isBedrock(p)) return "Bedrock (Geyser)";
        String b = p.getClientBrandName();
        if (b == null || b.isBlank()) return "inconnu";
        String l = b.toLowerCase(Locale.ROOT);
        if (l.contains("lunar")) return "Lunar Client";
        if (l.contains("feather")) return "Feather";
        if (l.contains("badlion")) return "Badlion";
        if (l.contains("labymod")) return "LabyMod";
        if (l.contains("neoforge")) return "NeoForge";
        if (l.contains("forge")) return "Forge";
        if (l.contains("quilt")) return "Quilt";
        if (l.contains("fabric")) return "Fabric";
        if (l.equals("vanilla")) return "Vanilla";
        return b;
    }

    /** Couleur selon le client : vert = normal, jaune = moddé, rouge = inconnu / suspect. */
    public static String color(String name) {
        return switch (name) {
            case "Vanilla", "Lunar Client", "Feather", "Badlion", "LabyMod", "Bedrock (Geyser)" -> "<green>";
            case "Fabric", "Forge", "NeoForge", "Quilt" -> "<yellow>";
            default -> "<red>";
        };
    }

    /** Mods qui s'annoncent au serveur (espaces de noms des canaux, sans minecraft). */
    public static List<String> mods(Player p) {
        TreeSet<String> ns = new TreeSet<>();
        for (String c : p.getListeningPluginChannels()) {
            int i = c.indexOf(':');
            String n = (i > 0 ? c.substring(0, i) : c).toLowerCase(Locale.ROOT);
            if (!n.equals("minecraft") && !n.equals("c") && !n.equals("bungeecord")) ns.add(n);
        }
        return new ArrayList<>(ns);
    }

    /** Une ligne : "Pseudo — Fabric (5 mods)" avec la liste des mods au survol. */
    public static Component line(SmpGuard plugin, Player p) {
        String n = name(plugin, p);
        List<String> mods = mods(p);
        String brand = p.getClientBrandName();
        Component hover = Texts.parse("<gray>Client : " + color(n) + n + "\n<gray>Marque envoyée : <white>"
                + (brand == null ? "?" : brand.replace("<", "")) + "\n<gray>Mods annoncés : <white>"
                + (mods.isEmpty() ? "aucun" : String.join(", ", mods))
                + "\n<dark_gray>(beaucoup de mods ne s'annoncent pas)");
        return Texts.parse(" <dark_gray>▸ <white>" + p.getName() + " <dark_gray>— " + color(n) + n
                        + (mods.isEmpty() ? "" : " <gray>(" + mods.size() + " mod" + (mods.size() > 1 ? "s" : "") + ")"))
                .hoverEvent(HoverEvent.showText(hover));
    }

    /** Petit message au staff quand quelqu'un se connecte. */
    public static void announce(SmpGuard plugin, Player p) {
        if (!plugin.settings().raw().getBoolean("client-info.join-notice", true)) return;
        Component msg = Texts.parse("<dark_gray>[<aqua>Client</aqua>]</dark_gray>").append(line(plugin, p));
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o != p && o.hasPermission("smpguard.alerts")) o.sendMessage(msg);
        }
    }
}
