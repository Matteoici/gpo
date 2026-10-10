package fr.smpguilds;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static fr.smpguilds.Messages.ph;

/** Téléportation au home avec temps d'attente (annulé si on bouge ou prend des dégâts) et délai entre deux usages. */
public final class HomeTeleport {

    private final SmpGuilds plugin;
    private final Map<UUID, BukkitTask> warmups = new HashMap<>();
    private final Map<UUID, Location> starts = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    HomeTeleport(SmpGuilds plugin) {
        this.plugin = plugin;
    }

    public void start(Player p, Location target) {
        long cd = plugin.settings().l("home.cooldown-seconds", 30) * 1000L;
        Long last = cooldowns.get(p.getUniqueId());
        if (last != null && System.currentTimeMillis() - last < cd && !p.hasPermission("smpguilds.bypass")) {
            plugin.messages().send(p, "home.cooldown", ph("seconds", (cd - (System.currentTimeMillis() - last)) / 1000 + 1));
            return;
        }
        cancel(p, false);
        int warmup = plugin.settings().i("home.warmup-seconds", 5);
        if (warmup <= 0 || p.hasPermission("smpguilds.bypass")) {
            teleport(p, target);
            return;
        }
        plugin.messages().send(p, "home.warmup", ph("seconds", warmup));
        starts.put(p.getUniqueId(), p.getLocation().clone());
        warmups.put(p.getUniqueId(), plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            warmups.remove(p.getUniqueId());
            starts.remove(p.getUniqueId());
            if (p.isOnline()) teleport(p, target);
        }, warmup * 20L));
    }

    private void teleport(Player p, Location target) {
        p.teleportAsync(target).thenAccept(ok -> {
            if (ok) {
                cooldowns.put(p.getUniqueId(), System.currentTimeMillis());
                plugin.messages().send(p, "home.teleported");
                p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.2f);
            }
        });
    }

    /** Annule l'attente si le joueur a bougé d'un bloc. */
    public void onMove(Player p, Location to) {
        Location s = starts.get(p.getUniqueId());
        if (s == null || to == null) return;
        if (s.getWorld() != to.getWorld() || s.distanceSquared(to) > 0.5) cancel(p, true);
    }

    public void cancel(Player p, boolean notify) {
        BukkitTask t = warmups.remove(p.getUniqueId());
        starts.remove(p.getUniqueId());
        if (t != null) {
            t.cancel();
            if (notify) plugin.messages().send(p, "home.cancelled");
        }
    }
}
