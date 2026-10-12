package fr.smpgojo;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Kento Nanami : Ratio 7:3, Heures sup', Effondrement. */
final class Nanami {

    private final SmpGojo pl;
    private final Map<UUID, Long> overtime = new HashMap<>();

    Nanami(SmpGojo pl) {
        this.pl = pl;
    }

    private boolean inOvertime(Player p) {
        return overtime.getOrDefault(p.getUniqueId(), 0L) > System.currentTimeMillis();
    }

    /** La ligne divisée en 10, avec le point faible à 7/10. */
    private void ratioLine(Location c, Vector u, double half) {
        Location a = c.clone().subtract(u.clone().multiply(half)), b = c.clone().add(u.clone().multiply(half));
        Draw.segment(a, b, Draw.YELLOW, 0.7f, 0.15);
        for (int k = 0; k <= 10; k++) {
            Location m = a.clone().add(u.clone().multiply(half * 2 * k / 10.0));
            Draw.dust(m, k == 7 ? Draw.RED : Draw.WHITE, k == 7 ? 2f : 0.9f);
        }
    }

    // ================================================================= Ratio 7:3

    void ratio(Player p) {
        LivingEntity t = pl.aim(p, 16);
        if (t == null) {
            p.sendActionBar(SmpGojo.mm("<red>Aucune cible en vue"));
            pl.resetCooldown(p, "ratio");
            return;
        }
        pl.cine.title(List.of(p), "", "<gold>7 : 3", 20);
        SmpGojo.sound(p.getLocation(), "block.note_block.hat", 2f, 1.2f);
        pl.repeat(2, 6, i -> {
            if (!t.isValid()) return;
            Location c = t.getLocation().add(0, t.getHeight() * 0.55, 0);
            Vector u = Draw.perpendicular(c.toVector().subtract(p.getEyeLocation().toVector()).normalize(), 0.5);
            ratioLine(c, u, Math.max(0.8, t.getWidth() * 0.9));
            SmpGojo.sound(c, "block.note_block.hat", 1.5f, 1.2f + i * 0.1f);
        }, () -> pl.yuji.dash(p, "ratio", target -> {
            List<Player> vs = pl.viewers(target.getLocation(), 40);
            Location c = target.getLocation().add(0, target.getHeight() * 0.55, 0);
            pl.cine.hitStop(List.of(target), 6);
            pl.cine.impact(vs, List.of(target), Cine.RED);
            double dmg = pl.cfg("damage.ratio", 24) * (inOvertime(p) ? 1.5 : 1);
            pl.yuji.strike(p, target, dmg, "ratio");
            Draw.blood(target, 70);
            Draw.fx(c, Particle.CRIT, 40, 0.4, 0.5);
            Draw.slash(c, Draw.randomDir(), 1.6, Draw.YELLOW, Draw.RED);
            SmpGojo.sound(c, "block.anvil.land", 2f, 0.7f);
            SmpGojo.sound(c, "entity.player.attack.crit", 3f, 0.6f);
            SmpGojo.sound(c, "entity.generic.explode", 1.2f, 1.6f);
            pl.cine.title(List.of(p), "", "<red><b>Coup critique", 15);
            Vector push = target.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
            if (push.lengthSquared() > 0.01) pl.later(6, () -> {
                target.setVelocity(push.normalize().multiply(1.8).setY(0.4));
                pl.smash(target, 8, 1.3);
            });
        }));
    }

    // ================================================================= Heures sup'

    void overtime(Player p) {
        int s = pl.cfgi("nanami.overtime-seconds", 40);
        overtime.put(p.getUniqueId(), System.currentTimeMillis() + s * 1000L);
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, s * 20, 2, false, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, s * 20, 1, false, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, s * 20, 1, false, false, true));
        List<Player> vs = pl.viewers(p.getLocation(), 40);
        pl.cine.title(vs, "<gold><b>HEURES SUP'", "<gray>« <white>C'est l'heure des heures sup'.</white> »", 40);
        for (Player v : vs) v.sendMessage(SmpGojo.mm("<gold>" + p.getName() + " <gray>desserre sa cravate. <white>Les heures sup' commencent."));
        SmpGojo.sound(p.getLocation(), "block.note_block.bell", 2f, 0.6f);
        pl.later(6, () -> SmpGojo.sound(p.getLocation(), "block.note_block.bell", 2f, 0.6f));
        SmpGojo.sound(p.getLocation(), "item.armor.equip_leather", 2f, 0.6f);
        Draw.fx(p.getLocation().add(0, 1, 0), Particle.WAX_ON, 30, 0.6, 0.1);
    }

    // ================================================================= Effondrement

    void collapse(Player p) {
        Location at = pl.aimPoint(p, 30);
        Block g = Ruin.surface(at.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
        if (g != null && Math.abs(g.getY() - at.getY()) < 3) at = g.getLocation().add(0.5, 1, 0.5);
        Location c = at;
        pl.cine.title(List.of(p), "", "<gold>Effondrement", 25);
        // les lignes 7:3 sur toute la structure
        pl.repeat(2, 8, i -> {
            for (int k = 0; k < 4; k++) {
                Location l = c.clone().add(Draw.randomDir().multiply(3));
                ratioLine(l, Draw.randomDir(), 1.5);
            }
            SmpGojo.sound(c, "block.note_block.hat", 2f, 1f + i * 0.1f);
        }, () -> {
            List<Player> vs = pl.viewers(c, 50);
            double r = pl.cfg("nanami.collapse-radius", 7) * (inOvertime(p) ? 1.3 : 1);
            List<LivingEntity> hit = pl.targets(p, c, r + 2);
            pl.cine.hitStop(hit, 4);
            pl.cine.impact(vs, hit, Cine.WHITE_BLACK);
            pl.cine.shake(vs, 6, 2);
            SmpGojo.sound(c, "entity.generic.explode", 4f, 0.6f);
            SmpGojo.sound(c, "block.deepslate.break", 4f, 0.5f);
            SmpGojo.sound(c, "entity.zombie.break_wooden_door", 3f, 0.5f);
            pl.ruin.crater(c, r, 0.45, 8, false);
            pl.ruin.shockwave(c, r * 2.5, 12, 0.6);
            pl.ruin.smoke(c, r * 0.5, 80);
            // les gravats retombent sur tout le monde
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            for (int k = 0; k < 40; k++) {
                Location l = c.clone().add(rnd.nextDouble(-r, r), 6 + rnd.nextDouble(6), rnd.nextDouble(-r, r));
                pl.ruin.spawnDebris(Material.STONE.createBlockData(), l, new Vector(0, -0.2, 0), null, 40);
            }
            double dmg = pl.cfg("damage.effondrement", 18);
            for (LivingEntity le : hit) {
                pl.hit(p, le, dmg, "effondrement");
                le.setVelocity(new Vector(0, 0.8, 0));
            }
        });
    }

    void quit(Player p) {
        overtime.remove(p.getUniqueId());
    }
}
