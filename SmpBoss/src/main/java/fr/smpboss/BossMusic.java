package fr.smpboss;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Musique d'ambiance pendant les combats de boss (sons du resource pack) :
 * ceux qui sont dans l'arène l'entendent en boucle, elle s'arrête quand on sort ou quand le combat se termine.
 */
final class BossMusic {

    private static final Key PUMPKIN = Key.key("limitless", "boss.music_pumpkin"), QUEEN = Key.key("limitless", "spider.music_queen");

    private final SmpBoss plugin;
    private final Map<UUID, Long> started = new HashMap<>();
    private final Map<UUID, Key> playing = new HashMap<>();

    BossMusic(SmpBoss plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 2L);
    }

    private void tick() {
        var c = plugin.getConfig();
        Boss k = plugin.king();
        Key want = null;
        long len = 0;
        Arena arena = null;
        if (c.getBoolean("music.enabled", true) && k != null && k.active()) {
            boolean spider = k instanceof SpiderQueen;
            want = spider ? QUEEN : PUMPKIN;
            len = (long) (c.getDouble(spider ? "music.spider-seconds" : "music.pumpkin-seconds", spider ? 67.2 : 60.48) * 1000);
            arena = k.bossArena();
        }
        long now = System.currentTimeMillis();
        Set<UUID> keep = new HashSet<>();
        if (want != null && arena != null) {
            float vol = (float) c.getDouble("music.volume", 0.75);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!arena.contains(p.getLocation()) || !PackStatus.has(p)) continue;
                UUID id = p.getUniqueId();
                keep.add(id);
                Long s = started.get(id);
                Key cur = playing.get(id);
                if (s != null && want.equals(cur) && now - s < len) continue;
                if (cur != null && !want.equals(cur)) p.stopSound(SoundStop.named(cur));
                if (s == null) p.stopSound(SoundCategory.MUSIC); // coupe la musique du jeu au début
                p.playSound(Sound.sound(want, Sound.Source.RECORD, vol, 1f), Sound.Emitter.self());
                started.put(id, now);
                playing.put(id, want);
            }
        }
        for (UUID id : new HashSet<>(started.keySet())) {
            if (keep.contains(id)) continue;
            Player p = Bukkit.getPlayer(id);
            Key cur = playing.get(id);
            if (p != null && cur != null) p.stopSound(SoundStop.named(cur));
            started.remove(id);
            playing.remove(id);
        }
    }
}
