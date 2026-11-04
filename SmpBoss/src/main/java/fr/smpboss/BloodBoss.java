package fr.smpboss;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/** Lune de sang (SmpMonde) : les boss et leurs serviteurs frappent 2x plus fort et encaissent 2x mieux. */
final class BloodBoss implements Listener {

    private final SmpBoss plugin;

    BloodBoss(SmpBoss plugin) {
        this.plugin = plugin;
    }

    private boolean ofBoss(Boss k, Entity e) {
        if (e == null) return false;
        if (e instanceof Projectile pr && pr.getShooter() instanceof Entity sh) e = sh;
        return k.isBoss(e) || k.isMinion(e);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        Boss k = plugin.king();
        if (k == null || !plugin.bloodMoon()) return;
        double m = plugin.getConfig().getDouble("blood-moon.boss-strength", 2);
        if (m <= 0) return;
        if (ofBoss(k, e.getDamager()) && !ofBoss(k, e.getEntity())) e.setDamage(e.getDamage() * m);
        else if (k.isBoss(e.getEntity())) e.setDamage(e.getDamage() / m);
    }
}
