package fr.smpboss;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Un joueur figé / étourdi par une capacité (Black Flash, Boogie Bombe, gel, lévitation...) peut rester en l'air :
 * ni le jeu (« Flying is not enabled ») ni l'anti-triche ne doivent le prendre pour un tricheur et l'expulser.
 * Pendant le contrôle et 4 s après, il est protégé.
 */
final class CcGuard implements Listener {

    private static final Map<UUID, Long> until = new HashMap<>();
    private static final NamespacedKey GUARD_EXEMPT = new NamespacedKey("smpguard", "exempt_until");

    /** À appeler quand une capacité fige / soulève un joueur pendant `ticks`. */
    static void mark(Entity e, int ticks) {
        if (!(e instanceof Player p)) return;
        long end = System.currentTimeMillis() + ticks * 50L + 4000;
        until.merge(p.getUniqueId(), end, Math::max);
        Long cur = p.getPersistentDataContainer().get(GUARD_EXEMPT, PersistentDataType.LONG);
        if (cur == null || cur < end) p.getPersistentDataContainer().set(GUARD_EXEMPT, PersistentDataType.LONG, end);
    }

    static boolean protectedNow(Player p) {
        Long u = until.get(p.getUniqueId());
        return u != null && u > System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onKick(PlayerKickEvent e) {
        if (e.getCause() == PlayerKickEvent.Cause.FLYING_PLAYER && protectedNow(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        until.remove(e.getPlayer().getUniqueId());
    }
}
