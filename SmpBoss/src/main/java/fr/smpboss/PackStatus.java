package fr.smpboss;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static fr.smpboss.SmpBoss.parse;

/**
 * Qui a bien le resource pack du serveur. Ceux qui ne l'ont pas (refusé, téléchargement raté...) ne voient pas les
 * modèles du pack (sinon des cubes violet/noir) : ils ont une version en particules, et /pack pour réessayer.
 */
final class PackStatus implements Listener, CommandExecutor {

    private static final Map<UUID, Boolean> loaded = new ConcurrentHashMap<>();
    private final SmpBoss plugin;

    PackStatus(SmpBoss plugin) {
        this.plugin = plugin;
    }

    /**
     * Le pack du serveur (server.properties) est envoyé pendant la connexion, AVANT que le joueur soit dans le monde :
     * on ne reçoit pas toujours sa réponse. Donc : pas de réponse = on considère qu'il l'a ; seul un refus / un échec
     * connu le retire (et /pack permet de réessayer).
     */
    static boolean has(Player p) {
        Boolean b = loaded.get(p.getUniqueId());
        if (b != null) return b;
        return serverHasPack();
    }

    private static boolean serverHasPack() {
        try {
            var rp = Bukkit.getServer().getServerResourcePack();
            return rp != null && rp.getUrl() != null && !rp.getUrl().isBlank();
        } catch (RuntimeException | LinkageError e) {
            return true;
        }
    }

    private static UUID serverPackId() {
        try {
            var rp = Bukkit.getServer().getServerResourcePack();
            return rp == null ? null : rp.getId();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent e) {
        UUID id = serverPackId();
        if (id != null && e.getID() != null && !id.equals(e.getID())) return; // un autre pack (autre plugin)
        Player p = e.getPlayer();
        switch (e.getStatus()) {
            case SUCCESSFULLY_LOADED -> loaded.put(p.getUniqueId(), true);
            // DISCARDED = l'ancienne version du pack est retirée quand le serveur en envoie une nouvelle : ce n'est pas un échec
            case DISCARDED -> {
            }
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD -> {
                loaded.put(p.getUniqueId(), false);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (p.isOnline() && !has(p)) p.sendMessage(parse(plugin.msg("pack.missing")));
                }, 60L);
            }
            default -> {
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        loaded.remove(e.getPlayer().getUniqueId());
    }

    /** /pack : renvoie le pack du serveur (si le téléchargement a raté ou qu'on l'a refusé). */
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) return true;
        try {
            var rp = Bukkit.getServer().getServerResourcePack();
            if (rp == null || rp.getUrl() == null || rp.getUrl().isBlank()) {
                p.sendMessage(parse(plugin.msg("pack.none")));
                return true;
            }
            byte[] hash = rp.getHash() == null || rp.getHash().isBlank() ? null : HexFormat.of().parseHex(rp.getHash().trim());
            p.addResourcePack(rp.getId() == null ? UUID.randomUUID() : rp.getId(), rp.getUrl(), hash, null, false);
            p.sendMessage(parse(plugin.msg("pack.sent")));
        } catch (RuntimeException | LinkageError e) {
            p.sendMessage(parse(plugin.msg("pack.none")));
        }
        return true;
    }
}
