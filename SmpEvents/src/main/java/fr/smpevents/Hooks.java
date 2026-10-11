package fr.smpevents;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Liens avec les autres plugins, sans dépendance de compilation (réflexion) :
 * SmpGuilds (guilde + alliés), SmpFriends (amis), SmpAdmin / Essentials (vanish),
 * et en secours un placeholder PlaceholderAPI pour une autre plugin de guildes.
 */
public final class Hooks {

    private static String guildPlaceholder = "";

    private Hooks() {
    }

    static void configure(String placeholder) {
        guildPlaceholder = placeholder == null ? "" : placeholder.trim();
    }

    private static Plugin enabled(String name) {
        Plugin p = Bukkit.getPluginManager().getPlugin(name);
        return p != null && p.isEnabled() ? p : null;
    }

    // ------------------------------------------------------------------ guildes

    private static Object smpGuild(UUID player) {
        Plugin p = enabled("SmpGuilds");
        if (p == null) return null;
        try {
            Object gm = p.getClass().getMethod("guilds").invoke(p);
            return gm.getClass().getMethod("of", UUID.class).invoke(gm, player);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Identifiant de la guilde du joueur (null = sans guilde). */
    public static UUID guildId(OfflinePlayer player) {
        Object g = smpGuild(player.getUniqueId());
        if (g != null) {
            try {
                return (UUID) g.getClass().getField("id").get(g);
            } catch (Throwable ignored) {
            }
        }
        String ph = placeholderGuild(player);
        return ph == null ? null : UUID.nameUUIDFromBytes(("ph:" + ph.toLowerCase()).getBytes(StandardCharsets.UTF_8));
    }

    /** Guildes alliées à celle du joueur. */
    public static Set<UUID> allyIds(OfflinePlayer player) {
        Set<UUID> out = new HashSet<>();
        Object g = smpGuild(player.getUniqueId());
        if (g == null) return out;
        try {
            Object a = g.getClass().getField("allies").get(g);
            if (a instanceof Collection<?> c) for (Object o : c) if (o instanceof UUID u) out.add(u);
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** Nom affiché de la guilde (null = sans guilde). */
    public static String guildName(OfflinePlayer player) {
        Object g = smpGuild(player.getUniqueId());
        if (g != null) {
            try {
                return String.valueOf(g.getClass().getField("name").get(g));
            } catch (Throwable ignored) {
            }
        }
        return placeholderGuild(player);
    }

    private static String placeholderGuild(OfflinePlayer player) {
        if (guildPlaceholder.isEmpty() || enabled("PlaceholderAPI") == null) return null;
        try {
            Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            String v = (String) api.getMethod("setPlaceholders", OfflinePlayer.class, String.class)
                    .invoke(null, player, guildPlaceholder);
            if (v == null) return null;
            v = v.replaceAll("§.", "").trim();
            if (v.isEmpty() || v.equalsIgnoreCase(guildPlaceholder) || v.equals("-") || v.equalsIgnoreCase("aucune")
                    || v.equalsIgnoreCase("none")) return null;
            return v;
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean sameOrAlliedGuild(OfflinePlayer a, OfflinePlayer b) {
        UUID ga = guildId(a), gb = guildId(b);
        if (ga == null || gb == null) return false;
        return ga.equals(gb) || allyIds(a).contains(gb) || allyIds(b).contains(ga);
    }

    // ------------------------------------------------------------------ amis

    public static boolean friends(UUID a, UUID b) {
        Plugin p = enabled("SmpFriends");
        if (p == null) return false;
        try {
            Object f = p.getClass().getMethod("friends").invoke(p);
            Object r = f.getClass().getMethod("areFriends", UUID.class, UUID.class).invoke(f, a, b);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ vanish

    @SuppressWarnings("unchecked")
    public static boolean vanished(Player p) {
        for (MetadataValue v : p.getMetadata("vanished")) if (v.asBoolean()) return true;
        Plugin admin = enabled("SmpAdmin");
        if (admin == null) return false;
        try {
            Object st = admin.getClass().getMethod("state").invoke(admin);
            Object set = st.getClass().getField("vanished").get(st);
            return set instanceof Set<?> s && ((Set<Object>) s).contains(p.getUniqueId());
        } catch (Throwable t) {
            return false;
        }
    }
}
