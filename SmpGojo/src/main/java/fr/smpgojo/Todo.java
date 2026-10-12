package fr.smpgojo;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.List;

/** Aoi Todo : Boogie Woogie (échange de place), échange de deux ennemis, poing Black Flash. */
final class Todo {

    private final SmpGojo pl;

    Todo(SmpGojo pl) {
        this.pl = pl;
    }

    private void clapFx(Player p) {
        Location h = SmpGojo.hand(p);
        SmpGojo.sound(h, "entity.player.attack.knockback", 3f, 2f);
        SmpGojo.sound(h, "block.note_block.snare", 3f, 1.4f);
        SmpGojo.sound(h, "entity.firework_rocket.blast", 2f, 1.6f);
        Draw.fx(h, Particle.CLOUD, 10, 0.2, 0.08);
        Draw.ring(h, 0.6, Draw.WHITE, 1f, 16);
    }

    private void swapFx(Location a) {
        Location c = a.clone().add(0, 1, 0);
        Draw.fx(c, Particle.CLOUD, 25, 0.4, 0.06);
        Draw.sphere(c, 0.9, Draw.WHITE, 1f, 30);
        Draw.ring(a.clone().add(0, 0.2, 0), 1.2, Draw.CURSED, 1.2f, 24);
    }

    /** Clap : Todo échange sa place avec la cible. */
    void clap(Player p) {
        LivingEntity t = pl.aim(p, pl.cfg("todo.range", 40));
        if (t == null) {
            p.sendActionBar(SmpGojo.mm("<red>Aucune cible en vue"));
            pl.resetCooldown(p, "boogie");
            return;
        }
        clapFx(p);
        Location a = p.getLocation(), b = t.getLocation();
        Location pa = b.clone(), tb = a.clone();
        pa.setYaw(a.getYaw());
        pa.setPitch(a.getPitch());
        tb.setYaw(b.getYaw());
        tb.setPitch(b.getPitch());
        p.teleport(pa);
        t.teleport(tb);
        swapFx(a);
        swapFx(b);
        pl.cine.impact(List.of(p), null, new Cine.Frame[]{Cine.f(110, Draw.WHITE, 1)});
        if (t instanceof Player tp) {
            pl.cine.impact(List.of(tp), null, new Cine.Frame[]{Cine.f(110, Draw.WHITE, 1)});
            tp.sendActionBar(SmpGojo.mm("<gold>👏 Boogie Woogie"));
        }
    }

    /** Clap : deux ennemis échangent leur place (pour les perdre). */
    void swapTwo(Player p) {
        List<LivingEntity> near = pl.targets(p, p.getLocation(), pl.cfg("todo.range", 40) * 0.6);
        near.sort((x, y) -> Double.compare(x.getLocation().distanceSquared(p.getLocation()), y.getLocation().distanceSquared(p.getLocation())));
        if (near.size() < 2) {
            p.sendActionBar(SmpGojo.mm("<red>Il faut deux cibles autour de toi"));
            pl.resetCooldown(p, "boogie2");
            return;
        }
        clapFx(p);
        LivingEntity x = near.get(0), y = near.get(1);
        Location a = x.getLocation(), b = y.getLocation();
        Location xb = b.clone(), ya = a.clone();
        xb.setYaw(a.getYaw());
        ya.setYaw(b.getYaw());
        x.teleport(xb);
        y.teleport(ya);
        swapFx(a);
        swapFx(b);
    }

    /** Fonce et frappe : un Black Flash garanti. */
    void punch(Player p) {
        pl.yuji.dash(p, "todo-poing", t -> pl.yuji.blackFlash(p, t));
    }
}
