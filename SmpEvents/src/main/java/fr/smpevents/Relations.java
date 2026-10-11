package fr.smpevents;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Anti-abus : retient pendant X jours qui a été dans quelle guilde, allié avec qui, ami avec qui,
 * et sur quelle connexion (adresse IP chiffrée, jamais stockée en clair).
 * Changer de guilde 5 minutes avant de tuer son pote ne marche donc pas.
 */
public final class Relations {

    public enum Link {
        SAME_GUILD, ALLIED, FRIENDS, SAME_IP
    }

    private final SmpEvents plugin;

    Relations(SmpEvents plugin) {
        this.plugin = plugin;
    }

    /** Relevé toutes les minutes (et à la connexion). */
    public void sampleAll() {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        for (Player p : online) sample(p);
        long now = System.currentTimeMillis();
        for (int i = 0; i < online.size(); i++) {
            for (int j = i + 1; j < online.size(); j++) {
                Player a = online.get(i), b = online.get(j);
                if (Hooks.friends(a.getUniqueId(), b.getUniqueId()) || Hooks.friends(b.getUniqueId(), a.getUniqueId())) {
                    plugin.data().of(a).friends.put(b.getUniqueId(), now);
                    plugin.data().of(b).friends.put(a.getUniqueId(), now);
                }
            }
        }
    }

    public void sample(Player p) {
        long now = System.currentTimeMillis();
        PlayerData d = plugin.data().of(p);
        UUID g = Hooks.guildId(p);
        if (g != null) d.guilds.put(g, now);
        for (UUID a : Hooks.allyIds(p)) d.allies.put(a, now);
        String ip = ipHash(p);
        if (ip != null) d.ips.put(ip, now);
    }

    static String ipHash(Player p) {
        InetSocketAddress addr = p.getAddress();
        if (addr == null || addr.getAddress() == null) return null;
        var ia = addr.getAddress();
        // joueurs Bedrock passés par Geyser sans transfert d'IP, proxy local... : même adresse pour tout le monde
        if (ia.isLoopbackAddress() || ia.isSiteLocalAddress() || ia.isAnyLocalAddress() || ia.isLinkLocalAddress()) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(("smpevents:" + addr.getAddress().getHostAddress()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Les deux joueurs sont-ils liés (maintenant ou dans les X derniers jours) ?
     * null = aucun lien.
     */
    public Link link(Player a, Player b) {
        sample(a);
        sample(b);
        if (Hooks.friends(a.getUniqueId(), b.getUniqueId()) || Hooks.friends(b.getUniqueId(), a.getUniqueId())) {
            return Link.FRIENDS;
        }
        PlayerData da = plugin.data().of(a), db = plugin.data().of(b);
        long limit = System.currentTimeMillis() - plugin.settings().memoryDays * 86_400_000L;
        if (shares(da.guilds, db.guilds, limit)) return Link.SAME_GUILD;
        if (shares(da.guilds, db.allies, limit) || shares(da.allies, db.guilds, limit)) return Link.ALLIED;
        if (recent(da.friends, b.getUniqueId(), limit) || recent(db.friends, a.getUniqueId(), limit)) return Link.FRIENDS;
        for (var e : da.ips.entrySet()) {
            Long t = db.ips.get(e.getKey());
            if (t != null && t >= limit && e.getValue() >= limit) return Link.SAME_IP;
        }
        return null;
    }

    private static <K> boolean shares(Map<K, Long> a, Map<K, Long> b, long limit) {
        for (var e : a.entrySet()) {
            if (e.getValue() < limit) continue;
            Long t = b.get(e.getKey());
            if (t != null && t >= limit) return true;
        }
        return false;
    }

    private static boolean recent(Map<UUID, Long> m, UUID id, long limit) {
        Long t = m.get(id);
        return t != null && t >= limit;
    }
}
