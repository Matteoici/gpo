package fr.smpboss;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;

import static fr.smpboss.SmpBoss.parse;

/**
 * Texte flottant au-dessus de l'autel : comment réveiller le Roi, et s'il peut l'être maintenant.
 * Caché pendant le combat. Petites flammes d'âmes qui montent de l'autel.
 */
final class AltarHologram {

    private final SmpBoss plugin;
    private TextDisplay display;
    private int t;

    AltarHologram(SmpBoss plugin) {
        this.plugin = plugin;
    }

    /** Toutes les 10 ticks. */
    void tick() {
        t++;
        Location alt = plugin.altar();
        PumpkinKing k = plugin.king();
        boolean fight = k != null && k.active();
        if (alt == null || alt.getWorld() == null || fight || !plugin.getConfig().getBoolean("altar-hologram", true)
                || !alt.getWorld().isChunkLoaded(alt.getBlockX() >> 4, alt.getBlockZ() >> 4)) {
            remove();
            return;
        }
        Location at = alt.clone().add(0.5, 2.6, 0.5);
        if (display == null || !display.isValid() || display.getWorld() != at.getWorld() || display.getLocation().distanceSquared(at) > 0.25) {
            remove();
            display = at.getWorld().spawn(at, TextDisplay.class, d -> {
                d.setPersistent(false);
                d.setBillboard(Display.Billboard.CENTER);
                d.setShadowed(true);
                d.setBackgroundColor(Color.fromARGB(90, 10, 0, 0));
                d.setLineWidth(260);
                d.setViewRange(0.5f);
            });
        }
        if (t % 2 == 0) display.text(parse(plugin.msg("altar.hologram").replace("<status>", plugin.altarStatus())));
        // flammes d'âmes qui montent de l'autel, anneau orange
        Location top = alt.clone().add(0.5, 1.05, 0.5);
        top.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, top, 2, 0.25, 0.05, 0.25, 0.01);
        if (t % 2 == 0) {
            Particle.DustOptions o = new Particle.DustOptions(Color.fromRGB(255, 120, 0), 1.0f);
            for (int i = 0; i < 16; i++) {
                double a = Math.PI * 2 * i / 16 + t * 0.1;
                top.getWorld().spawnParticle(Particle.DUST, top.clone().add(Math.cos(a) * 0.9, 0, Math.sin(a) * 0.9), 1, 0, 0, 0, 0, o);
            }
        }
    }

    void remove() {
        if (display != null) display.remove();
        display = null;
    }
}
