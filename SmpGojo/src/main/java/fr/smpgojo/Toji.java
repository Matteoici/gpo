package fr.smpgojo;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Toji Fushiguro : Lance céleste inversée, Nuage capricieux, Restriction céleste, Pas éclair. */
final class Toji {

    private final SmpGojo pl;
    private final Map<UUID, Integer> restriction = new HashMap<>();
    private final Map<UUID, Location> lastPos = new HashMap<>();
    private int now;

    Toji(SmpGojo pl) {
        this.pl = pl;
    }

    void tick() {
        now++;
        restriction.entrySet().removeIf(e -> e.getValue() <= now);
        if (now % 2 != 0) return;
        for (UUID id : restriction.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            Location prev = lastPos.put(id, p.getLocation());
            if (prev != null && prev.getWorld() == p.getWorld() && prev.distanceSquared(p.getLocation()) > 0.15) {
                // images rémanentes : il va trop vite pour l'œil
                Location l = prev.clone();
                for (double y = 0.2; y < 1.9; y += 0.35) Draw.dust(l.clone().add(0, y, 0), Draw.BLACK, 1.1f);
                Draw.fx(l.clone().add(0, 1, 0), Particle.CLOUD, 1, 0.2, 0.01);
            }
        }
    }

    // ================================================================= Lance céleste inversée

    void spear(Player p) {
        pl.yuji.dash(p, "lance", t -> {
            List<Player> vs = pl.viewers(t.getLocation(), 40);
            pl.cine.hitStop(List.of(t), 5);
            pl.cine.impact(vs, List.of(t), Cine.WHITE_BLACK);
            Location c = t.getLocation().add(0, t.getHeight() * 0.55, 0);
            Draw.segment(SmpGojo.hand(p), c, Draw.BLACK, 1.2f, 0.15);
            Draw.segment(SmpGojo.hand(p).add(0, 0.05, 0), c, Draw.LIGHT_BLUE, 0.5f, 0.3);
            Draw.fx(c, Particle.CRIT, 30, 0.4, 0.4);
            Draw.fx(c, Particle.ENCHANTED_HIT, 20, 0.3, 0.3);
            Draw.blood(t, 40);
            SmpGojo.sound(c, "item.trident.hit", 3f, 0.6f);
            SmpGojo.sound(c, "block.chain.break", 2f, 0.6f);
            SmpGojo.sound(c, "entity.player.attack.crit", 2f, 0.5f);
            // la lance annule les techniques : elle traverse l'Infini et le brise
            if (t instanceof Player tp && pl.gojo.hasInfinity(tp)) {
                pl.gojo.breakInfinity(tp, pl.cfgi("toji.infinity-break-seconds", 10) * 20);
                pl.cine.title(vs, "", "<gray>« <white>La Lance céleste inversée annule toute technique.</white> »", 40);
            }
            pl.pierceHit(p, t, pl.cfg("damage.lance", 22), "lance");
            Vector push = t.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
            if (push.lengthSquared() > 0.01) pl.later(5, () -> t.setVelocity(push.normalize().multiply(1.6).setY(0.4)));
        });
    }

    // ================================================================= Nuage capricieux

    void cloud(Player p) {
        Set<UUID> hitOnce = new HashSet<>();
        double dmg = pl.cfg("damage.nuage", 6);
        SmpGojo.sound(p.getLocation(), "block.chain.fall", 2f, 0.6f);
        pl.repeat(2, 8, i -> {
            Location c = p.getLocation().add(0, 1.1, 0);
            double base = i * 1.4;
            // les trois sections du bâton qui tournent
            for (int s = 0; s < 3; s++) {
                double a = base + s * 0.5;
                Location from = c.clone().add(Math.cos(a) * 0.6, 0, Math.sin(a) * 0.6);
                Location to = c.clone().add(Math.cos(a + 0.4) * 3.6, Math.sin(i + s) * 0.4, Math.sin(a + 0.4) * 3.6);
                Draw.segment(from, to, s == 1 ? Draw.RED : Draw.BLACK, 1.2f, 0.25);
            }
            Draw.ring(c, 3.4, Particle.SWEEP_ATTACK, 8);
            SmpGojo.sound(c, "entity.player.attack.sweep", 2f, 0.6f + i * 0.08f);
            SmpGojo.sound(c, "block.chain.hit", 1.5f, 0.8f);
            for (LivingEntity le : pl.targets(p, c, 4)) {
                pl.hit(p, le, dmg, "nuage");
                Vector out = le.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
                if (out.lengthSquared() > 0.01) le.setVelocity(out.normalize().multiply(hitOnce.add(le.getUniqueId()) ? 0.6 : 0.3).setY(0.3));
                Draw.blood(le, 10);
            }
        }, () -> {
            // dernier coup : le bâton s'abat devant lui
            Location front = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(2.5));
            List<LivingEntity> hit = pl.targets(p, front, 3.5);
            pl.cine.impact(pl.viewers(front, 30), hit, new Cine.Frame[]{Cine.f(230, Draw.BLACK, 1), Cine.f(150, Draw.RED, 1)});
            SmpGojo.sound(front, "item.mace.smash_ground_heavy", 2.5f, 0.7f);
            pl.ruin.crater(front.clone().add(0, -0.5, 0), 2.5, 0.4, 4, false);
            pl.ruin.shockwave(front, 8, 8, 0.5);
            for (LivingEntity le : hit) {
                pl.hit(p, le, dmg * 2, "nuage");
                le.setVelocity(new Vector(0, 1, 0));
            }
        });
    }

    // ================================================================= Restriction céleste

    void restriction(Player p) {
        int t = pl.cfgi("toji.restriction-seconds", 30) * 20;
        restriction.put(p.getUniqueId(), now + t);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, t, 3, false, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, t, 2, false, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, t, 2, false, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, t, 1, false, false, true));
        pl.cine.title(pl.viewers(p.getLocation(), 30), "<dark_gray><b>RESTRICTION CÉLESTE", "<gray>Aucune énergie occulte. Que du corps.", 40);
        SmpGojo.sound(p.getLocation(), "entity.warden.heartbeat", 2f, 1.4f);
        SmpGojo.sound(p.getLocation(), "entity.player.breath", 2f, 0.6f);
        pl.ruin.shockwave(p.getLocation(), 6, 6, 0.4);
    }

    // ================================================================= Pas éclair

    void flash(Player p) {
        Location eye = p.getEyeLocation();
        Vector d = eye.getDirection().normalize();
        double range = pl.cfg("toji.flash-range", 14);
        RayTraceResult r = p.getWorld().rayTraceBlocks(eye, d, range);
        double dist = r == null ? range : Math.max(0, r.getHitPosition().distance(eye.toVector()) - 1);
        Location start = p.getLocation();
        Location end = start.clone().add(d.clone().multiply(dist));
        end.setYaw(start.getYaw());
        end.setPitch(start.getPitch());
        Set<UUID> hit = new HashSet<>();
        double dmg = pl.cfg("damage.pas-eclair", 10);
        for (double t = 0; t <= dist; t += 0.7) {
            Location l = start.clone().add(d.clone().multiply(t)).add(0, 1, 0);
            Draw.dust(l, Draw.BLACK, 1.2f);
            for (LivingEntity le : pl.targets(p, l, 1.6)) {
                if (!hit.add(le.getUniqueId())) continue;
                pl.hit(p, le, dmg, "pas-eclair");
                Draw.blood(le, 25);
                Draw.slash(le.getLocation().add(0, 1, 0), Draw.randomDir(), 1.2, Draw.WHITE, Draw.BLACK);
            }
        }
        for (double y = 0.2; y < 1.9; y += 0.3) Draw.dust(start.clone().add(0, y, 0), Draw.BLACK, 1.4f);
        p.teleport(end);
        p.setFallDistance(0);
        pl.cine.impact(List.of(p), null, new Cine.Frame[]{Cine.f(120, Draw.WHITE, 1)});
        SmpGojo.sound(start, "entity.breeze.wind_burst", 2f, 1.6f);
        SmpGojo.sound(end, "entity.player.attack.sweep", 2f, 0.6f);
        pl.ruin.pop(start, 1.5, 8, 0.3);
    }

    void quit(Player p) {
        restriction.remove(p.getUniqueId());
        lastPos.remove(p.getUniqueId());
    }
}
