package fr.smpgojo;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Frog;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** Megumi Fushiguro : les Dix Ombres. Chiens de jade, Nue, Crapaud, Grand Serpent, Mahoraga, Jardin des ombres. */
final class Megumi {

    static final class Summon {
        UUID owner;
        LivingEntity e;
        int expire;
        String kind;
        boolean leaving;
    }

    final SmpGojo pl;
    final Map<UUID, Summon> summons = new HashMap<>();
    final Map<UUID, UUID> focus = new HashMap<>();
    final Map<UUID, Mahoraga> mahoragas = new HashMap<>();
    private final Set<Entity> props = new HashSet<>();
    int now;

    Megumi(SmpGojo pl) {
        this.pl = pl;
    }

    // ================================================================= suivi des invocations

    boolean isSummon(Entity e) {
        return e != null && summons.containsKey(e.getUniqueId());
    }

    boolean ownedBy(Entity e, Player p) {
        Summon s = summons.get(e.getUniqueId());
        return s != null && s.owner.equals(p.getUniqueId());
    }

    private UUID ownerOf(Entity e) {
        Summon s = summons.get(e.getUniqueId());
        return s == null ? null : s.owner;
    }

    /** Une invocation ne blesse ni son invocateur ni ses autres invocations. */
    boolean protectOwner(EntityDamageByEntityEvent e) {
        UUID owner = ownerOf(e.getDamager());
        if (owner == null) return false;
        Entity v = e.getEntity();
        return v.getUniqueId().equals(owner) || owner.equals(ownerOf(v));
    }

    void onHit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && e.getEntity() instanceof LivingEntity le && !isSummon(le)) {
            focus.put(p.getUniqueId(), le.getUniqueId());
        }
        Mahoraga m = mahoragas.get(e.getDamager().getUniqueId());
        if (m != null) m.onStrike(e);
        Summon s = summons.get(e.getDamager().getUniqueId());
        if (s != null && s.kind.equals("chien")) {
            Draw.blood(e.getEntity(), 12);
            Draw.fx(e.getEntity().getLocation().add(0, 0.8, 0), Particle.SQUID_INK, 6, 0.3, 0.02);
        }
    }

    boolean piercesInfinity(Entity damager, Player p) {
        Mahoraga m = mahoragas.get(damager.getUniqueId());
        return m != null && m.infinityCheck(p);
    }

    boolean adaptedToInfinity(Entity e) {
        Mahoraga m = mahoragas.get(e.getUniqueId());
        return m != null && m.adapted.contains("infini");
    }

    void onDamaged(EntityDamageEvent e) {
        Mahoraga m = mahoragas.get(e.getEntity().getUniqueId());
        if (m != null) m.onDamaged(e);
    }

    void onTarget(EntityTargetLivingEntityEvent e) {
        UUID owner = ownerOf(e.getEntity());
        if (owner == null || e.getTarget() == null) return;
        if (e.getTarget().getUniqueId().equals(owner) || owner.equals(ownerOf(e.getTarget()))) e.setCancelled(true);
    }

    void onDeath(EntityDeathEvent e) {
        Summon s = summons.remove(e.getEntity().getUniqueId());
        if (s == null) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        Mahoraga m = mahoragas.remove(e.getEntity().getUniqueId());
        if (m != null) m.shatter();
        Draw.fx(e.getEntity().getLocation().add(0, 0.5, 0), Particle.SQUID_INK, 30, 0.5, 0.05);
    }

    void tick() {
        now++;
        for (Summon s : new ArrayList<>(summons.values())) {
            if (s.e == null || !s.e.isValid()) {
                summons.remove(s.e == null ? null : s.e.getUniqueId());
                continue;
            }
            Player owner = Bukkit.getPlayer(s.owner);
            if (!s.leaving && (owner == null || now >= s.expire || owner.getWorld() != s.e.getWorld())) {
                Mahoraga m = mahoragas.get(s.e.getUniqueId());
                if (m != null) m.dismiss();
                else dismiss(s);
                continue;
            }
            if (s.leaving || !(s.e instanceof Mob mob) || !mob.isAware()) continue;
            if (now % 10 == 0 && owner != null) {
                LivingEntity t = pickTarget(owner, s.e.getLocation(), 24);
                if (t != null && mob.getTarget() != t) mob.setTarget(t);
                if (t == null && s.e.getLocation().distanceSquared(owner.getLocation()) > 100) {
                    mob.getPathfinder().moveTo(owner.getLocation(), 1.3);
                }
            }
        }
        for (Mahoraga m : new ArrayList<>(mahoragas.values())) m.tick();
    }

    LivingEntity pickTarget(Player owner, Location from, double range) {
        UUID f = focus.get(owner.getUniqueId());
        if (f != null) {
            Entity e = Bukkit.getEntity(f);
            if (e instanceof LivingEntity le && pl.valid(owner, le) && le.getWorld() == from.getWorld()
                    && le.getLocation().distanceSquared(from) < range * range * 2.25) return le;
            focus.remove(owner.getUniqueId());
        }
        LivingEntity best = null;
        double bd = range * range;
        for (LivingEntity le : pl.targets(owner, from, range)) {
            if (!(le instanceof Monster) && !(le instanceof Player)) continue;
            double d = le.getLocation().distanceSquared(from);
            if (d < bd) {
                bd = d;
                best = le;
            }
        }
        return best;
    }

    /** Fait sortir une créature de l'ombre (elle monte du sol). */
    <T extends LivingEntity> T rise(Player owner, Location at, Class<T> cls, Consumer<T> setup, int lifeTicks, String kind, int riseTicks) {
        Location start = at.clone().subtract(0, 2.2, 0);
        T e = at.getWorld().spawn(start, cls, x -> {
            x.setPersistent(false);
            x.getPersistentDataContainer().set(pl.tagKey, PersistentDataType.STRING, "summon");
            x.getPersistentDataContainer().set(pl.ownerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
            x.setInvulnerable(true);
            x.setGravity(false);
            if (x instanceof Mob m) m.setAware(false);
            setup.accept(x);
        });
        Summon s = new Summon();
        s.owner = owner.getUniqueId();
        s.e = e;
        s.expire = now + lifeTicks;
        s.kind = kind;
        summons.put(e.getUniqueId(), s);
        puddle(at, Math.max(1.2, e.getWidth()), riseTicks + 6);
        SmpGojo.sound(at, "entity.warden.emerge", 1f, 1.6f);
        pl.repeat(1, riseTicks, i -> {
            if (!e.isValid()) return;
            Location l = start.clone().add(0, 2.2 * (i + 1) / riseTicks, 0);
            l.setYaw(at.getYaw());
            e.teleport(l);
            Draw.fx(at, Particle.SQUID_INK, 6, e.getWidth() * 0.4, 0.03);
        }, () -> {
            if (!e.isValid()) return;
            e.setGravity(true);
            e.setInvulnerable(false);
            if (e instanceof Mob m && !kind.equals("decor")) m.setAware(true);
        });
        return e;
    }

    void dismiss(Summon s) {
        if (s.leaving) return;
        s.leaving = true;
        LivingEntity e = s.e;
        if (e instanceof Mob m) m.setAware(false);
        e.setInvulnerable(true);
        e.setGravity(false);
        puddle(e.getLocation(), Math.max(1.2, e.getWidth()), 18);
        SmpGojo.sound(e.getLocation(), "entity.warden.dig", 1f, 1.4f);
        Location start = e.getLocation();
        pl.repeat(1, 16, i -> {
            if (e.isValid()) e.teleport(start.clone().subtract(0, 2.4 * (i + 1) / 16.0, 0));
        }, () -> {
            summons.remove(e.getUniqueId());
            e.remove();
        });
    }

    void puddle(Location at, double r, int ticks) {
        Location c = at.clone();
        Block g = Ruin.surface(c.getWorld(), c.getBlockX(), c.getBlockY(), c.getBlockZ());
        if (g != null) c.setY(g.getY() + 1.05);
        pl.repeat(2, Math.max(1, ticks / 2), i -> {
            for (double rr = 0.3; rr <= r; rr += 0.35) Draw.ring(c, rr, Draw.BLACK, 1.3f, (int) (6 + rr * 8));
            Draw.ring(c, r, Draw.SHADOW, 1f, (int) (10 + r * 8));
            Draw.fx(c, Particle.SQUID_INK, 3, r * 0.4, 0.01);
        }, null);
    }

    static void attr(LivingEntity e, Attribute a, double base) {
        AttributeInstance i = e.getAttribute(a);
        if (i != null) i.setBaseValue(base);
    }

    // ================================================================= Chiens de jade

    void dogs(Player p) {
        SmpGojo.sound(p.getLocation(), "entity.wolf.howl", 2f, 0.7f);
        pl.cine.title(pl.viewers(p.getLocation(), 30), "", "<gray>« <white>Chiens de jade</white> »", 25);
        int life = pl.cfgi("chiens.seconds", 30) * 20;
        Vector side = SmpGojo.right(p);
        for (int k = 0; k < 2; k++) {
            boolean white = k == 0;
            Location at = p.getLocation().add(side.clone().multiply(white ? 1.6 : -1.6));
            at.setYaw(p.getLocation().getYaw());
            rise(p, at, Wolf.class, w -> {
                w.setVariant(white ? Wolf.Variant.SNOWY : Wolf.Variant.BLACK);
                w.setAngry(true);
                w.customName(SmpGojo.mm(white ? "<white>Chien de jade blanc" : "<dark_gray>Chien de jade noir"));
                w.setCustomNameVisible(false);
                attr(w, Attribute.SCALE, 1.5);
                attr(w, Attribute.MAX_HEALTH, 40);
                w.setHealth(40);
                attr(w, Attribute.ATTACK_DAMAGE, pl.cfg("damage.chiens", 8));
                attr(w, Attribute.MOVEMENT_SPEED, 0.42);
            }, life, "chien", 14);
        }
    }

    // ================================================================= Nue

    void nue(Player p) {
        LivingEntity target = pl.aim(p, 60);
        Location fallback = pl.aimPoint(p, 60);
        SmpGojo.sound(p.getLocation(), "entity.phantom.ambient", 2f, 0.6f);
        pl.cine.title(pl.viewers(p.getLocation(), 40), "", "<gray>« <yellow>Nue</yellow> »", 25);
        Location start = p.getLocation().add(0, 2.5, 0);
        puddle(p.getLocation(), 2, 20);
        Phantom ph = p.getWorld().spawn(start, Phantom.class, e -> {
            e.setPersistent(false);
            e.getPersistentDataContainer().set(pl.tagKey, PersistentDataType.STRING, "summon");
            e.setAI(false);
            e.setSize(5);
            e.setShouldBurnInDay(false);
            e.setInvulnerable(true);
            e.customName(SmpGojo.mm("<yellow>Nue"));
            e.setCustomNameVisible(false);
        });
        props.add(ph);
        Summon s = new Summon();
        s.owner = p.getUniqueId();
        s.e = ph;
        s.expire = now + 200;
        s.kind = "nue";
        s.leaving = true;
        summons.put(ph.getUniqueId(), s);
        Location base = p.getLocation();
        // il tourne au-dessus de Megumi en montant
        pl.repeat(1, 22, i -> {
            double a = i * 0.32;
            Location l = base.clone().add(Math.cos(a) * 3.5, 3 + i * 0.3, Math.sin(a) * 3.5);
            l.setDirection(new Vector(-Math.sin(a), 0.2, Math.cos(a)));
            ph.teleport(l);
            Draw.fx(l, Particle.ELECTRIC_SPARK, 8, 0.8, 0.1);
            if (i % 6 == 0) SmpGojo.sound(l, "entity.phantom.flap", 2f, 0.7f);
        }, () -> {
            Location from = ph.getLocation();
            SmpGojo.sound(from, "entity.phantom.swoop", 3f, 0.6f);
            pl.repeat(1, 12, i -> {
                Location to = target != null && target.isValid() ? target.getLocation().add(0, 1, 0) : fallback;
                double t = Math.pow((i + 1) / 12.0, 1.6);
                Location l = from.clone().add(to.toVector().subtract(from.toVector()).multiply(t));
                l.setDirection(to.toVector().subtract(from.toVector()));
                ph.teleport(l);
                Draw.fx(l, Particle.ELECTRIC_SPARK, 15, 0.6, 0.2);
                Draw.bolt(l, Draw.randomDir(), 1.5, Draw.YELLOW, Draw.WHITE, 0.8f);
            }, () -> {
                Location at = target != null && target.isValid() ? target.getLocation() : fallback;
                nueStrike(p, at);
                Location up = ph.getLocation();
                pl.repeat(1, 20, i -> {
                    Location l = up.clone().add(i * 0.4, 1 + i * 0.6, i * 0.2);
                    l.setDirection(new Vector(0.4, 0.6, 0.2));
                    ph.teleport(l);
                }, () -> {
                    Draw.fx(ph.getLocation(), Particle.SQUID_INK, 40, 1, 0.05);
                    summons.remove(ph.getUniqueId());
                    props.remove(ph);
                    ph.remove();
                });
            });
        });
    }

    private void nueStrike(Player p, Location at) {
        World w = at.getWorld();
        List<Player> vs = pl.viewers(at, 40);
        w.strikeLightningEffect(at);
        pl.later(2, () -> w.strikeLightningEffect(at.clone().add(0.7, 0, -0.5)));
        pl.cine.impact(vs, null, new Cine.Frame[]{Cine.f(230, Draw.WHITE, 1), Cine.f(150, Draw.YELLOW, 1), Cine.f(90, Draw.WHITE, 1)});
        pl.cine.shake(vs, 2, 2);
        Draw.fx(at.clone().add(0, 1, 0), Particle.ELECTRIC_SPARK, 120, 1.5, 0.4);
        for (int k = 0; k < 6; k++) Draw.bolt(at.clone().add(0, 0.5, 0), Draw.randomDir().setY(0.2), 3.5, Draw.YELLOW, Draw.WHITE, 1f);
        SmpGojo.sound(at, "entity.lightning_bolt.impact", 3f, 1f);
        pl.ruin.crater(at, 3, 0.4, 5, false);
        pl.ruin.scorch(at, 5, 0.5);
        pl.ruin.shockwave(at, 6, 6, 0.5);
        double dmg = pl.cfg("damage.nue", 16);
        for (LivingEntity le : pl.targets(p, at, 3.5)) {
            pl.hit(p, le, dmg, "nue");
            le.setVelocity(new Vector(0, 1, 0));
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 3, false, false, true));
        }
    }

    // ================================================================= Crapaud

    void toad(Player p) {
        LivingEntity target = pl.aim(p, 28);
        if (target == null) {
            p.sendActionBar(SmpGojo.mm("<red>Aucune cible en vue"));
            pl.resetCooldown(p, "crapaud");
            return;
        }
        Location spot = p.getLocation().add(SmpGojo.right(p).multiply(1.8));
        spot.setDirection(target.getLocation().toVector().subtract(spot.toVector()).setY(0));
        pl.cine.title(pl.viewers(p.getLocation(), 30), "", "<gray>« <green>Crapaud</green> »", 20);
        Frog frog = rise(p, spot, Frog.class, f -> {
            f.setAI(false);
            attr(f, Attribute.SCALE, 3);
            f.customName(SmpGojo.mm("<dark_green>Crapaud"));
            f.setCustomNameVisible(false);
        }, 120, "decor", 8);
        pl.later(10, () -> {
            if (!frog.isValid() || !target.isValid()) return;
            SmpGojo.sound(frog.getLocation(), "entity.frog.tongue", 3f, 0.5f);
            pl.repeat(1, 6, i -> {
                Location mouth = mouth(frog);
                Location to = target.getLocation().add(0, target.getHeight() / 2, 0);
                Location tip = mouth.clone().add(to.toVector().subtract(mouth.toVector()).multiply((i + 1) / 6.0));
                Draw.segment(mouth, tip, Draw.TONGUE, 1.5f, 0.2);
            }, () -> {
                if (!target.isValid()) return;
                SmpGojo.sound(target.getLocation(), "entity.slime.squish", 2f, 0.6f);
                pl.hit(p, target, pl.cfg("damage.crapaud", 4), "crapaud");
                pl.repeat(1, 12, i -> {
                    if (!target.isValid() || !frog.isValid()) return;
                    Location mouth = mouth(frog);
                    Vector pull = mouth.toVector().subtract(target.getLocation().toVector());
                    if (pull.length() > 1.5) target.setVelocity(pull.multiply(0.3).setY(Math.max(0.1, pull.getY() * 0.3)));
                    Draw.segment(mouth, target.getLocation().add(0, target.getHeight() / 2, 0), Draw.TONGUE, 1.5f, 0.2);
                }, () -> {
                    Summon s = summons.get(frog.getUniqueId());
                    pl.later(15, () -> {
                        if (s != null) dismiss(s);
                    });
                });
            });
        });
    }

    private static Location mouth(LivingEntity frog) {
        Location l = frog.getLocation();
        return l.clone().add(l.getDirection().setY(0).normalize().multiply(frog.getWidth() * 0.5)).add(0, frog.getHeight() * 0.45, 0);
    }

    // ================================================================= Grand Serpent

    void serpent(Player p) {
        LivingEntity target = pl.aim(p, 35);
        Location at = target != null ? target.getLocation() : pl.aimPoint(p, 35);
        Block g = Ruin.surface(at.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
        if (g != null) at.setY(g.getY() + 1);
        pl.cine.title(pl.viewers(p.getLocation(), 40), "", "<gray>« <gold>Grand Serpent</gold> »", 25);
        puddle(at, 2.5, 18);
        SmpGojo.sound(at, "entity.warden.dig", 2f, 0.5f);
        SmpGojo.sound(at, "block.rooted_dirt.break", 2f, 0.5f);
        pl.repeat(3, 5, i -> pl.ruin.pop(at, 1 + i * 0.4, 6, 0.25), null);
        Vector side = SmpGojo.right(p).multiply(2.5);
        pl.later(15, () -> {
            List<Player> vs = pl.viewers(at, 40);
            pl.cine.shake(vs, 3, 2);
            pl.ruin.crater(at.clone().add(0, -0.5, 0), 3.8, 0.45, 6, false);
            SmpGojo.sound(at, "entity.ravager.roar", 3f, 0.6f);
            SmpGojo.sound(at, "entity.generic.explode", 2f, 1.2f);
            int n = 16;
            BlockData body = Material.BROWN_TERRACOTTA.createBlockData(), belly = Material.PACKED_MUD.createBlockData(),
                    head = Material.MUD_BRICKS.createBlockData(), eye = Material.SHROOMLIGHT.createBlockData();
            List<BlockDisplay> segs = new ArrayList<>();
            for (int k = 0; k < n; k++) {
                BlockData d = k == 0 ? head : k % 3 == 0 ? belly : body;
                float s = k == 0 ? 1.9f : (float) (1.6 - k * 0.03);
                segs.add(display(at.clone().add(0, -3, 0), d, s));
            }
            BlockDisplay eyeL = display(at.clone().add(0, -3, 0), eye, 0.35f), eyeR = display(at.clone().add(0, -3, 0), eye, 0.35f);
            props.addAll(segs);
            props.add(eyeL);
            props.add(eyeR);
            boolean[] grabbed = {false};
            double dmg = pl.cfg("damage.serpent", 14);
            int up = 16, hold = 20, down = 14;
            pl.repeat(1, up + hold + down, i -> {
                double prog = i < up ? (i + 1) / (double) up : i < up + hold ? 1 : 1 - (i - up - hold + 1) / (double) down;
                for (int k = 0; k < n; k++) {
                    double s = Math.max(0, prog * 1.15 - k * 0.07);
                    Location l = path(at, side, s);
                    segs.get(k).teleport(l);
                }
                Location headAt = path(at, side, Math.max(0, prog * 1.15));
                Vector look = path(at, side, Math.max(0, prog * 1.15) + 0.02).toVector().subtract(headAt.toVector());
                Vector ear = look.clone().crossProduct(new Vector(0, 1, 0));
                if (ear.lengthSquared() < 0.001) ear = new Vector(1, 0, 0);
                ear.normalize().multiply(0.6);
                eyeL.teleport(headAt.clone().add(ear).add(0, 0.6, 0));
                eyeR.teleport(headAt.clone().subtract(ear).add(0, 0.6, 0));
                if (i < up) Draw.fx(at, Particle.BLOCK, 10, 1, 0.1);
                if (target != null && target.isValid()) {
                    if (!grabbed[0] && i < up && headAt.distanceSquared(target.getLocation().add(0, 1, 0)) < 6) {
                        grabbed[0] = true;
                        pl.hit(p, target, dmg * 0.4, "serpent");
                        Draw.blood(target, 40);
                        SmpGojo.sound(headAt, "entity.evoker_fangs.attack", 3f, 0.6f);
                    }
                    if (grabbed[0] && i < up + hold) {
                        Location carry = headAt.clone().add(0, 1.3, 0);
                        carry.setYaw(target.getLocation().getYaw());
                        carry.setPitch(target.getLocation().getPitch());
                        target.teleport(carry);
                        target.setFallDistance(0);
                    }
                    if (grabbed[0] && i == up + hold) {
                        pl.hit(p, target, dmg * 0.6, "serpent");
                        target.setVelocity(new Vector(0, -1.6, 0));
                        SmpGojo.sound(headAt, "entity.player.attack.knockback", 3f, 0.5f);
                    }
                }
                if (i == up) {
                    pl.cine.impact(vs, target == null ? null : List.of(target), Cine.SHADOW);
                    for (LivingEntity le : pl.targets(p, at, 3)) {
                        if (le == target) continue;
                        pl.hit(p, le, dmg * 0.5, "serpent");
                        le.setVelocity(new Vector(0, 1.1, 0));
                    }
                }
            }, () -> {
                for (BlockDisplay d : segs) d.remove();
                eyeL.remove();
                eyeR.remove();
                props.removeAll(segs);
                props.remove(eyeL);
                props.remove(eyeR);
                pl.ruin.shockwave(at, 5, 6, 0.4);
            });
        });
    }

    private static Location path(Location base, Vector side, double s) {
        s = Math.min(1.2, s);
        double y = -3 + s * 12;
        Vector off = side.clone().multiply(Math.sin(Math.min(1, s) * Math.PI) * 0.8 + (s > 1 ? (s - 1) * 2 : 0));
        return base.clone().add(off).add(0, y, 0);
    }

    BlockDisplay display(Location at, BlockData data, float s) {
        return at.getWorld().spawn(at, BlockDisplay.class, e -> {
            e.setPersistent(false);
            e.getPersistentDataContainer().set(pl.tagKey, PersistentDataType.STRING, "prop");
            e.setBlock(data);
            e.setTeleportDuration(2);
            e.setTransformation(new Transformation(new Vector3f(-s / 2, -s / 2, -s / 2), new Quaternionf(), new Vector3f(s, s, s),
                    new Quaternionf()));
        });
    }

    // ================================================================= Mahoraga

    void mahoraga(Player p) {
        for (Mahoraga m : mahoragas.values()) {
            if (m.owner.equals(p.getUniqueId())) {
                p.sendActionBar(SmpGojo.mm("<gray>Mahoraga est déjà là."));
                pl.resetCooldown(p, "mahoraga");
                return;
            }
        }
        List<String> lines = List.of("<gray>« <white>Avec ce trésor...</white> »", "<gray>« <white>je convoque...</white> »",
                "<gradient:#ffffff:#ffd040:#ffffff><b>« Général Divin aux Huit Poignées : Mahoraga ! »");
        Location spot = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(5));
        spot.setYaw(p.getLocation().getYaw() + 180);
        Block g = Ruin.surface(spot.getWorld(), spot.getBlockX(), spot.getBlockY(), spot.getBlockZ());
        if (g != null) spot.setY(g.getY() + 1);
        puddle(spot, 4, 60);
        pl.repeat(4, 12, i -> SmpGojo.sound(spot, "block.bell.resonate", 2f, 0.5f), null);
        pl.cine.chant(p, 100, lines, 18, "block.bell.use", () -> {
            if (!p.isOnline()) return;
            Mahoraga m = new Mahoraga(pl, this, p, spot);
            mahoragas.put(m.body.getUniqueId(), m);
        });
    }

    // ================================================================= Jardin des ombres chimériques

    void garden(Player p) {
        List<Player> vs0 = pl.viewers(p.getLocation(), 100);
        pl.cine.letterbox(vs0, 30);
        pl.cine.title(vs0, "", "<dark_purple>« <white>Extension du territoire...</white> »", 22);
        SmpGojo.sound(p.getLocation(), "entity.warden.heartbeat", 3f, 0.5f);
        pl.repeat(1, 22, i -> {
            Draw.converge(p.getLocation().add(0, 1, 0), 4, Draw.SHADOW, 1.2f, 10);
            puddle(p.getLocation(), 0.5 + i * 0.15, 2);
        }, () -> openGarden(p));
    }

    private void openGarden(Player p) {
        int r = pl.cfgi("jardin.radius", 16), seconds = pl.cfgi("jardin.seconds", 15);
        Location c = p.getLocation().getBlock().getLocation();
        World w = c.getWorld();
        List<Player> vs = pl.viewers(c, 100);
        pl.cine.impact(vs, null, Cine.SHADOW);
        pl.cine.title(vs, "<gradient:#7a40c0:#1a0030:#7a40c0><b>JARDIN DES OMBRES CHIMÉRIQUES", "<dark_purple>Extension du territoire", 50);
        SmpGojo.sound(c, "entity.warden.emerge", 3f, 0.6f);
        SmpGojo.sound(c, "block.sculk_shrieker.shriek", 3f, 0.5f);
        BlockData black = Material.BLACK_CONCRETE.createBlockData(), ink = Material.BLACK_STAINED_GLASS.createBlockData(),
                crying = Material.CRYING_OBSIDIAN.createBlockData();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<BlockState> floor = new ArrayList<>();
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (x * x + z * z > r * r) continue;
                Block top = Ruin.surface(w, c.getBlockX() + x, c.getBlockY(), c.getBlockZ() + z);
                if (top == null) continue;
                BlockState st = top.getState();
                double roll = rnd.nextDouble();
                st.setBlockData(roll < 0.05 ? crying : roll < 0.2 ? ink : black);
                floor.add(st);
            }
        }
        floor.sort(Comparator.comparingDouble(s -> s.getLocation().distanceSquared(c)));
        int waves = 8, per = (floor.size() + waves - 1) / waves;
        for (int k = 0; k < waves; k++) {
            List<BlockState> part = new ArrayList<>(floor.subList(Math.min(floor.size(), k * per), Math.min(floor.size(), (k + 1) * per)));
            pl.later(k, () -> {
                pl.fakeBlocks(vs, part);
            });
        }
        int ticks = seconds * 20;
        List<LivingEntity> victims = pl.targets(p, c, r);
        for (LivingEntity le : victims) {
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 2, false, false, false));
            le.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, ticks, 0, false, false, false));
            AttributeInstance j = le.getAttribute(Attribute.JUMP_STRENGTH);
            if (j != null) {
                j.removeModifier(pl.jumpKey);
                j.addTransientModifier(new AttributeModifier(pl.jumpKey, -0.8, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
            }
        }
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ticks, 2, false, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, ticks, 1, false, false, false));
        for (int k = 0; k < 3; k++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            Location at = c.clone().add(0.5 + Math.cos(a) * r * 0.5, 0, 0.5 + Math.sin(a) * r * 0.5);
            Block top = Ruin.surface(w, at.getBlockX(), at.getBlockY(), at.getBlockZ());
            if (top != null) at.setY(top.getY() + 1);
            rise(p, at, Wolf.class, wf -> {
                wf.setVariant(Wolf.Variant.BLACK);
                wf.setAngry(true);
                attr(wf, Attribute.SCALE, 1.3);
                attr(wf, Attribute.ATTACK_DAMAGE, pl.cfg("damage.chiens", 8));
                attr(wf, Attribute.MOVEMENT_SPEED, 0.45);
            }, ticks, "chien", 12);
        }
        Runnable[] close = new Runnable[1];
        close[0] = () -> {
            pl.pending.remove(close[0]);
            List<BlockState> real = new ArrayList<>();
            for (BlockState s : floor) real.add(s.getBlock().getState());
            real.sort(Comparator.comparingDouble((BlockState s) -> s.getLocation().distanceSquared(c)).reversed());
            int n = (real.size() + 5) / 6;
            for (int k = 0; k < 6; k++) {
                List<BlockState> part = new ArrayList<>(real.subList(Math.min(real.size(), k * n), Math.min(real.size(), (k + 1) * n)));
                pl.later(k, () -> {
                    pl.fakeBlocks(vs, part);
                });
            }
            for (LivingEntity le : victims) {
                AttributeInstance j = le.getAttribute(Attribute.JUMP_STRENGTH);
                if (j != null) j.removeModifier(pl.jumpKey);
            }
            if (pl.isEnabled()) SmpGojo.sound(c, "entity.warden.dig", 3f, 0.6f);
        };
        pl.pending.add(close[0]);
        double dmg = pl.cfg("damage.jardin", 2);
        pl.repeat(2, seconds * 10, i -> {
            if (!pl.pending.contains(close[0])) return;
            for (int k = 0; k < 12; k++) {
                Location at = c.clone().add(rnd.nextDouble(-r, r), 1.1, rnd.nextDouble(-r, r));
                Draw.fx(at, Particle.SQUID_INK, 1, 0.1, 0.01);
            }
            Draw.fx(c, Particle.SCULK_SOUL, 4, r * 0.4, 0.02);
            if (i % 5 != 0) return;
            for (LivingEntity le : pl.targets(p, c, r)) {
                Location l = le.getLocation();
                for (int k = 0; k < 4; k++) {
                    double a = k * Math.PI / 2 + i * 0.3;
                    Location hand = l.clone().add(Math.cos(a) * 0.7, 0, Math.sin(a) * 0.7);
                    Draw.segment(hand, hand.clone().add(-Math.cos(a) * 0.4, 1.4, -Math.sin(a) * 0.4), Draw.BLACK, 1.4f, 0.15);
                }
                pl.hit(p, le, dmg, "jardin");
                le.setVelocity(le.getVelocity().setY(-0.4));
            }
            SmpGojo.sound(c, "entity.warden.heartbeat", 2f, 0.6f);
        }, () -> {
            if (pl.pending.contains(close[0])) close[0].run();
        });
    }

    // ================================================================= nettoyage

    void quit(Player p) {
        for (Summon s : new ArrayList<>(summons.values())) {
            if (!s.owner.equals(p.getUniqueId())) continue;
            Mahoraga m = mahoragas.remove(s.e.getUniqueId());
            if (m != null) m.removeNow();
            summons.remove(s.e.getUniqueId());
            s.e.remove();
        }
        focus.remove(p.getUniqueId());
    }

    void cleanup() {
        for (Mahoraga m : new ArrayList<>(mahoragas.values())) m.removeNow();
        mahoragas.clear();
        for (Summon s : summons.values()) if (s.e != null) s.e.remove();
        summons.clear();
        for (Entity e : props) e.remove();
        props.clear();
        focus.clear();
    }
}
