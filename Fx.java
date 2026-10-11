package fr.smpmarket;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.entity.Player;

/** Petits effets sonores. */
public final class Fx {

    private Fx() {
    }

    public static void sound(Player p, String key, float pitch) {
        p.playSound(Sound.sound(Key.key("minecraft", key), Sound.Source.MASTER, 0.8f, pitch));
    }
}
