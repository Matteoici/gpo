package fr.smpgojo;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mahoraga : un colosse blanc avec la roue du Dharma au-dessus de la tête.
 * Chaque technique qui le touche fait tourner la roue ; au bout de quelques coups il s'y adapte
 * (plus aucun dégât de cette technique) et se soigne. Il finit même par traverser l'Infini.
 */
final class Mahoraga {

    private static final float R = 1.05f;

    final SmpGojo pl;
    final Megumi meg;
    final UUID owner;
    final IronGolem body;
    final List<BlockDisplay> wheel = new ArrayList<>();
    final Map<String, Double> progress = new HashMap<>();
    final Set<String> adapted = new HashSet<>();
    private float angle;
    private int age, slamCd = 120, slamTicks;
    private boolean ready, slamming, leaving;

    Mahoraga(SmpGojo pl, Megumi meg, Player owner, Location spot) {
        this.pl = pl;
        this.meg = meg;
        this.owner = owner.getUniqueId();
        double scale = pl.cfg("mahoraga.scale", 1.7);
        int life = pl.cfgi("mahoraga.seconds", 120) * 20;
        body = meg.rise(owner, spot, IronGolem.class, g -> {
            g.setPlayerCreated(true);
            g.setRemoveWhenFarAway(false);
            g.customName(SmpGojo.mm("<white><b>Mahoraga"));
            g.setCustomNameVisible(true);
            Megumi.attr(g, Attribute.SCALE, scale);
            Megumi.attr(g, Attribute.MAX_HEALTH, pl.cfg("mahoraga.health", 500));
            g.setHealth(pl.cfg("mahoraga.health", 500));
            Megumi.attr(g, Attribute.ATTACK_DAMAGE, pl.cfg("damage.mahoraga", 22));
            Megumi.attr(g, Attribute.MOVEMENT_SPEED, 0.34);
            Megumi.attr(g, Attribute.KNOCKBACK_RESISTANCE, 1);
            Megumi.attr(g, Attribute.FOLLOW_RANGE, 40);
        }, life, "mahoraga", 40);
        // la roue sort de l'ombre en premier
        BlockData gold = Material.GOLD_BLOCK.createBlockData(), knob = Material.RAW_GOLD_BLOCK.createBlockData();
        for (int k = 0; k < 8; k++) wheel.add(part(gold));
        for (int k = 0; k < 8; k++) wheel.add(part(knob));
        for (int k = 0; k < 8; k++) wheel.add(part(gold));
        wheel.add(part(knob));
        shape(0);
        List<Player> vs = pl.viewers(spot, 100);
        pl.repeat(1, 40, i -> {
            Draw.fx(spot, Particle.SQUID_INK, 10, 1.5, 0.05);
            Draw.fx(spot, Particle.END_ROD, 2, 1, 0.05);
            if (i % 10 == 0) SmpGojo.sound(spot, "block.bell.resonate", 3f, 0.5f);
        }, () -> {
            if (!body.isValid()) return;
            ready = true;
            pl.cine.impact(vs, List.of(body), Cine.WHITE_BLACK);
            pl.cine.shake(vs, 6, 2);
            pl.cine.title(vs, "<white><b>MAHORAGA", "<gold>Général Divin aux Huit Poignées", 50);
            SmpGojo.sound(spot, "entity.ravager.roar", 4f, 0.5f);
            SmpGojo.sound(spot, "entity.warden.emerge", 4f, 0.6f);
            SmpGojo.sound(spot, "block.bell.use", 4f, 0.5f);
            pl.ruin.shockwave(spot, 12, 12, 0.7);
            pl.ruin.crater(spot.clone().add(0, -1, 0), 4, 0.4, 6, false);
        });
    }

    private BlockDisplay part(BlockData data) {
        return body.getWorld().spawn(wheelCenter(), BlockDisplay.class, e -> {
            e.setPersistent(false);
            e.getPersistentDataContainer().set(pl.tagKey, PersistentDataType.STRING, "prop");
            e.setBlock(data);
            e.setTeleportDuration(1);
            e.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
        });
    }

    private Location wheelCenter() {
        Location l = body.getLocation();
        return l.clone().add(0, body.getHeight() + 0.55, 0);
    }

    /** Place les 25 morceaux de la roue pour l'angle courant. */
    private void shape(int interp) {
        float step = (float) (Math.PI / 4);
        for (int k = 0; k < 8; k++) {
            float th = angle + k * step;
            Quaternionf q = new Quaternionf().rotateY(th);
            // rayon
            set(wheel.get(k), new Vector3f(0, -0.04f, -0.04f).rotateY(th), q, new Vector3f(R, 0.08f, 0.08f), interp);
            // bout du rayon
            set(wheel.get(8 + k), new Vector3f(R - 0.11f, -0.11f, -0.11f).rotateY(th), q, new Vector3f(0.22f, 0.22f, 0.22f), interp);
            // jante
            float ph = th + step / 2;
            float chord = (float) (2 * R * Math.sin(step / 2));
            Quaternionf q2 = new Quaternionf().rotateY(ph);
            set(wheel.get(16 + k), new Vector3f((float) (R * Math.cos(step / 2)) - 0.035f, -0.035f, -chord / 2).rotateY(ph), q2,
                    new Vector3f(0.07f, 0.07f, chord), interp);
        }
        set(wheel.get(24), new Vector3f(-0.17f, -0.17f, -0.17f).rotateY(angle), new Quaternionf().rotateY(angle),
                new Vector3f(0.34f, 0.34f, 0.34f), interp);
    }

    private static void set(BlockDisplay d, Vector3f t, Quaternionf q, Vector3f s, int interp) {
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(interp);
        d.setTransformation(new Transformation(t, q, s, new Quaternionf()));
    }

    /** La roue tourne d'une fraction de cran. */
    private void turn(double fraction, boolean full) {
        angle += (float) (Math.PI / 4 * fraction);
        shape(full ? 10 : 4);
        Location c = wheelCenter();
        SmpGojo.sound(c, full ? "block.bell.use" : "block.chain.place", full ? 4f : 2f, full ? 0.7f : 1.4f);
        if (full) {
            SmpGojo.sound(c, "block.amethyst_block.chime", 3f, 0.5f);
            Draw.ring(c, R + 0.5, Draw.GOLD, 1.5f, 40);
            Draw.fx(c, Particle.END_ROD, 30, 0.8, 0.1);
        }
    }

    private static String label(String key) {
        return switch (key) {
            case "infini" -> "l'Infini";
            case "bleu" -> "Bleu";
            case "rouge" -> "Rouge";
            case "violet", "nuke" -> "Violet creux";
            case "demantelement" -> "Démantèlement";
            case "clivage" -> "Clivage";
            case "fuga" -> "Fuga";
            case "sanctuaire" -> "Sanctuaire";
            case "taille" -> "Taille du monde";
            case "blackflash" -> "Black Flash";
            case "sang" -> "Sang perforant";
            case "ENTITY_ATTACK", "ENTITY_SWEEP_ATTACK" -> "tes coups";
            case "PROJECTILE" -> "les projectiles";
            case "FIRE", "FIRE_TICK", "LAVA" -> "le feu";
            case "ENTITY_EXPLOSION", "BLOCK_EXPLOSION" -> "les explosions";
            default -> key.toLowerCase().replace('_', ' ');
        };
    }

    private void adaptStep(String key, Entity source) {
        int needed = key.equals("infini") ? pl.cfgi("mahoraga.hits-to-adapt-infinity", 3)
                : key.equals(key.toUpperCase()) ? pl.cfgi("mahoraga.hits-to-adapt-normal", 6) : pl.cfgi("mahoraga.hits-to-adapt-technique", 2);
        double p = progress.merge(key, 1.0, Double::sum);
        if (p >= needed) {
            adapted.add(key);
            turn(1.0 - (needed - 1.0) / needed, true);
            var max = body.getAttribute(Attribute.MAX_HEALTH);
            if (max != null) body.setHealth(Math.min(max.getValue(), body.getHealth() + max.getValue() * 0.35));
            List<Player> vs = pl.viewers(body.getLocation(), 50);
            pl.cine.impact(vs, List.of(body), new Cine.Frame[]{Cine.f(160, Draw.GOLD, 1), Cine.f(90, Draw.WHITE, 1)});
            for (Player v : vs) v.sendMessage(SmpGojo.mm("<gold>⚙ <white>Mahoraga s'est adapté à <gold>" + label(key) + "<white>."));
        } else {
            turn(1.0 / needed, false);
        }
    }

    // ================================================================= combat

    void onDamaged(EntityDamageEvent e) {
        if (!ready || leaving) {
            e.setCancelled(true);
            return;
        }
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL || e.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION) {
            e.setCancelled(true);
            return;
        }
        String key = pl.technique != null ? pl.technique : e.getCause().name();
        if (key.equals("ENTITY_SWEEP_ATTACK")) key = "ENTITY_ATTACK";
        if (adapted.contains(key)) {
            e.setCancelled(true);
            Location c = body.getLocation().add(0, body.getHeight() / 2, 0);
            Draw.sphere(c, body.getWidth() * 0.8, Draw.GOLD, 0.8f, 20);
            SmpGojo.sound(c, "block.anvil.place", 1f, 1.8f);
            if (e instanceof EntityDamageByEntityEvent be) {
                Entity src = be.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Entity sh ? sh : be.getDamager();
                if (src instanceof Player p) p.sendActionBar(SmpGojo.mm("<gold>⚙ Mahoraga s'est adapté à " + label(key)));
            }
            return;
        }
        Entity src = e instanceof EntityDamageByEntityEvent be ? be.getDamager() : null;
        String k = key;
        pl.later(1, () -> {
            if (body.isValid() && !body.isDead()) adaptStep(k, src);
        });
    }

    /** Mahoraga frappe un Infini : il finit par s'y adapter, puis le traverse. */
    boolean infinityCheck(Player p) {
        if (adapted.contains("infini")) {
            Draw.sphere(p.getLocation().add(0, 1, 0), 1.2, Draw.GOLD, 1f, 30);
            return true;
        }
        adaptStep("infini", p);
        return false;
    }

    void onStrike(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof LivingEntity victim)) return;
        List<Player> vs = pl.viewers(victim.getLocation(), 30);
        List<Player> close = new ArrayList<>();
        for (Player v : vs) if (v.getLocation().distanceSquared(victim.getLocation()) < 144) close.add(v);
        pl.cine.impact(close, List.of(victim), new Cine.Frame[]{Cine.f(255, Draw.BLACK, 1), Cine.f(200, Draw.WHITE, 1)});
        pl.cine.shake(vs, 2, 2);
        pl.cine.hitStop(List.of(victim), 3);
        SmpGojo.sound(victim.getLocation(), "block.anvil.land", 2f, 0.5f);
        SmpGojo.sound(victim.getLocation(), "entity.generic.explode", 1.5f, 1.3f);
        Draw.blood(victim, 25);
        pl.ruin.pop(victim.getLocation(), 2, 10, 0.4);
        Vector push = victim.getLocation().toVector().subtract(body.getLocation().toVector()).setY(0);
        if (push.lengthSquared() < 0.01) push = body.getLocation().getDirection();
        Vector v = push.normalize().multiply(2.6).setY(0.7);
        pl.later(3, () -> {
            if (victim.isValid()) {
                victim.setVelocity(v);
                pl.smash(victim, 10, 1.4);
            }
        });
    }

    // ================================================================= tick

    void tick() {
        if (!body.isValid()) {
            removeNow();
            meg.mahoragas.remove(body.getUniqueId());
            return;
        }
        age++;
        Location c = wheelCenter();
        for (BlockDisplay d : wheel) if (d.isValid()) d.teleport(c);
        if (!ready || leaving) return;
        if (age % 4 == 0) Draw.fx(body.getLocation().add(0, body.getHeight() * 0.6, 0), Particle.WHITE_ASH, 6, body.getWidth() * 0.6, 0);
        Player owner = org.bukkit.Bukkit.getPlayer(this.owner);
        if (owner == null) return;
        LivingEntity target = body.getTarget();
        if (age % 10 == 0) {
            LivingEntity t = meg.pickTarget(owner, body.getLocation(), 40);
            if (t != null && t != target) body.setTarget(t);
            target = t;
        }
        if (slamming) {
            slamTicks++;
            if (slamTicks > 6 && (body.isOnGround() || slamTicks > 40)) {
                slamming = false;
                slam(owner);
            }
            return;
        }
        if (--slamCd <= 0 && target != null && target.getWorld() == body.getWorld() && body.isOnGround()) {
            double d = target.getLocation().distance(body.getLocation());
            if (d > 5 && d < 26) {
                slamCd = pl.cfgi("mahoraga.leap-seconds", 7) * 20;
                Vector to = target.getLocation().toVector().subtract(body.getLocation().toVector()).setY(0);
                double len = to.length();
                body.setVelocity(to.normalize().multiply(Math.min(2.2, len * 0.09)).setY(1.15));
                slamming = true;
                slamTicks = 0;
                SmpGojo.sound(body.getLocation(), "entity.ravager.roar", 3f, 0.8f);
                pl.ruin.pop(body.getLocation(), 2.5, 12, 0.5);
            }
        }
    }

    private void slam(Player owner) {
        Location at = body.getLocation();
        List<Player> vs = pl.viewers(at, 40);
        List<LivingEntity> hit = pl.targets(owner, at, 5.5);
        hit.remove(body);
        pl.cine.impact(vs, hit, Cine.WHITE_BLACK);
        pl.cine.shake(vs, 4, 2);
        SmpGojo.sound(at, "item.mace.smash_ground_heavy", 4f, 0.5f);
        SmpGojo.sound(at, "entity.generic.explode", 3f, 0.8f);
        pl.ruin.crater(at.clone().add(0, -0.5, 0), 4.5, 0.4, 7, false);
        pl.ruin.shockwave(at, 14, 12, 0.7);
        pl.ruin.smoke(at, 3, 60);
        for (LivingEntity le : hit) {
            if (le == body) continue;
            pl.hit(owner, le, pl.cfg("damage.mahoraga-saut", 18), "mahoraga");
            le.setVelocity(new Vector(0, 1.2, 0));
        }
    }

    // ================================================================= fin

    /** Repart dans l'ombre. */
    void dismiss() {
        if (leaving) return;
        leaving = true;
        body.setAware(false);
        body.setInvulnerable(true);
        body.setGravity(false);
        meg.puddle(body.getLocation(), 3, 50);
        SmpGojo.sound(body.getLocation(), "block.bell.use", 3f, 0.5f);
        Location start = body.getLocation();
        pl.repeat(1, 45, i -> {
            if (body.isValid()) body.teleport(start.clone().subtract(0, 6.0 * (i + 1) / 45, 0));
        }, () -> {
            removeNow();
            meg.mahoragas.remove(body.getUniqueId());
            meg.summons.remove(body.getUniqueId());
        });
    }

    /** Tué : la roue tombe en morceaux. */
    void shatter() {
        Location c = wheelCenter();
        for (BlockDisplay d : wheel) {
            if (!d.isValid()) continue;
            pl.ruin.spawnDebris(d.getBlock(), c.clone().add(Draw.randomDir().multiply(0.8)), Draw.randomDir().multiply(0.3).setY(0.4), null, 50);
            d.remove();
        }
        wheel.clear();
        List<Player> vs = pl.viewers(c, 50);
        pl.cine.impact(vs, null, Cine.FLASH);
        SmpGojo.sound(c, "block.bell.use", 4f, 0.4f);
        SmpGojo.sound(c, "entity.iron_golem.death", 3f, 0.6f);
        Draw.fx(c, Particle.END_ROD, 60, 1.5, 0.2);
        for (Player v : vs) v.sendMessage(SmpGojo.mm("<gray>Mahoraga a été vaincu."));
    }

    void removeNow() {
        for (BlockDisplay d : wheel) d.remove();
        wheel.clear();
        if (body.isValid()) body.remove();
    }
}
