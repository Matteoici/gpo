package fr.smpfriends;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Détection AFK (dernier mouvement) et suivi d'un ami avec une flèche dans la barre d'action. */
public final class Activity {

    private record Track(UUID target, long until) {}

    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    private final FriendsPlugin plugin;
    private final Map<UUID, Long> lastMove = new ConcurrentHashMap<>();
    private final Map<UUID, Track> tracks = new ConcurrentHashMap<>();

    Activity(FriendsPlugin plugin) {
        this.plugin = plugin;
    }

    void moved(Player p) {
        lastMove.put(p.getUniqueId(), System.currentTimeMillis());
    }

    void forget(Player p) {
        lastMove.remove(p.getUniqueId());
        tracks.remove(p.getUniqueId());
    }

    /** Temps depuis le dernier mouvement (ms). */
    public long idleMs(Player p) {
        return System.currentTimeMillis() - lastMove.getOrDefault(p.getUniqueId(), System.currentTimeMillis());
    }

    public boolean isAfk(Player p) {
        return idleMs(p) > plugin.getConfig().getInt("afk-minutes", 5) * 60_000L;
    }

    public void track(Player viewer, Player target) {
        long secs = Math.max(5, plugin.getConfig().getInt("tracking-seconds", 60));
        tracks.put(viewer.getUniqueId(), new Track(target.getUniqueId(), System.currentTimeMillis() + secs * 1000));
        viewer.sendMessage(Text.msg("Suivi de " + target.getName() + " pendant " + secs + " s. Regarde ta barre d'action !",
                NamedTextColor.GREEN));
    }

    public void stopTracking(Player viewer) {
        tracks.remove(viewer.getUniqueId());
    }

    /** Appelé toutes les 10 ticks. */
    void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Track> e : tracks.entrySet()) {
            Player viewer = Bukkit.getPlayer(e.getKey());
            Player target = Bukkit.getPlayer(e.getValue().target());
            if (viewer == null || target == null || now > e.getValue().until()) {
                tracks.remove(e.getKey());
                if (viewer != null) viewer.sendActionBar(Component.text("Suivi terminé", NamedTextColor.GRAY));
                continue;
            }
            viewer.sendActionBar(bar(viewer, target));
        }
    }

    private Component bar(Player viewer, Player target) {
        Component name = Component.text(target.getName() + " ", Text.ACCENT).decorate(TextDecoration.BOLD);
        if (!viewer.getWorld().equals(target.getWorld())) {
            return name.append(Component.text("est dans le " + Text.world(target.getWorld()), NamedTextColor.GRAY));
        }
        Location a = viewer.getLocation(), b = target.getLocation();
        double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = ((targetYaw - a.getYaw()) % 360 + 540) % 360 - 180; // -180..180
        int idx = (int) Math.round(rel / 45.0);
        idx = ((idx % 8) + 8) % 8;
        NamedTextColor c = dist < 20 ? NamedTextColor.GREEN : dist < 200 ? NamedTextColor.YELLOW : NamedTextColor.GOLD;
        return name.append(Component.text(ARROWS[idx] + " ", c).decorate(TextDecoration.BOLD))
                .append(Component.text((int) dist + " blocs", c))
                .append(Component.text("  (Y " + b.getBlockY() + ")", NamedTextColor.DARK_GRAY));
    }
}
