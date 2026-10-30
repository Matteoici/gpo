package fr.smpile;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Musique d'ambiance médiévale au spawn (son du resource pack "limitless:spawn.music_village").
 * Ceux qui sont dans le rayon l'entendent en boucle ; elle s'arrête en douceur dès qu'on s'éloigne.
 * Le centre : music.center dans la config (/ile musique centre), sinon l'île, sinon le spawn du monde.
 */
final class SpawnMusic {

    private static final Key SONG = Key.key("limitless", "spawn.music_village");

    private final SmpIle plugin;
    private final Supplier<Location> island;
    private final Map<UUID, Long> started = new HashMap<>();

    SpawnMusic(SmpIle plugin, Supplier<Location> island) {
        this.plugin = plugin;
        this.island = island;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 60L, 10L);
    }

    Location center() {
        FileConfiguration c = plugin.getConfig();
        String w = c.getString("music.center.world", "");
        if (!w.isEmpty() && Bukkit.getWorld(w) != null) {
            return new Location(Bukkit.getWorld(w), c.getDouble("music.center.x"), c.getDouble("music.center.y"), c.getDouble("music.center.z"));
        }
        Location l = island.get();
        if (l != null) return l;
        World def = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        return def == null ? null : def.getSpawnLocation();
    }

    private void tick() {
        FileConfiguration c = plugin.getConfig();
        long now = System.currentTimeMillis();
        Location ctr = c.getBoolean("music.enabled", true) ? center() : null;
        double r = c.getDouble("music.radius", 90);
        long len = (long) (c.getDouble("music.seconds", 74.88) * 1000);
        float vol = (float) c.getDouble("music.volume", 0.6);
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            boolean in = ctr != null && p.getWorld().equals(ctr.getWorld())
                    && horiz(p.getLocation(), ctr) <= r * r && Math.abs(p.getLocation().getY() - ctr.getY()) <= c.getDouble("music.height", 80);
            Long s = started.get(id);
            if (!in) {
                if (s != null) {
                    p.stopSound(SoundStop.named(SONG));
                    started.remove(id);
                }
                continue;
            }
            if (s != null && now - s < len) continue;
            if (s == null) p.stopSound(SoundCategory.MUSIC); // coupe la musique du jeu en arrivant
            p.playSound(Sound.sound(SONG, Sound.Source.RECORD, vol, 1f), Sound.Emitter.self());
            started.put(id, now);
        }
        started.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    private static double horiz(Location a, Location b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    void restart(Player p) {
        p.stopSound(SoundStop.named(SONG));
        started.remove(p.getUniqueId());
    }
}
