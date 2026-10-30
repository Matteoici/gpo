package fr.smpboss;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
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
import java.util.concurrent.ThreadLocalRandom;

import static fr.smpboss.SmpBoss.parse;

/**
 * Relique prestige "Projet Capricorne" (inspirée du Black Wall de Cyberpunk) : un fragment de l'IA renégate derrière le Black Wall.
 * <p>
 * Actif — accroupi + clic gauche dans le vide : "Brèche du Black Wall". À l'endroit visé, un cercle de corruption s'ouvre,
 * des monolithes du Black Wall jaillissent en anneau et enferment tout le monde dedans. Pendant 5 s, des tentacules de données
 * et des spectres d'IA frappent ceux qui sont piégés, puis les murs s'effondrent sur le centre (grosse implosion).
 * <p>
 * Passif — "Fantôme du Net" : parfois, un coup reçu ne te touche pas : tu glitches quelques blocs plus loin et celui qui a
 * frappé est corrompu (lenteur + obscurité).
 */
final class Capricorne implements Listener {

    static final String ID = "projet_capricorne";
    static final Color RED = Color.fromRGB(255, 28, 48), DEEP = Color.fromRGB(110, 0, 14), BLACK = Color.fromRGB(8, 0, 4),
            CYAN = Color.fromRGB(0, 229, 255), PALE = Color.fromRGB(255, 200, 206);
    private static final char SCREEN_DARK = '', SCREEN_RED = '';

    private final SmpBoss plugin;
    private final Map<UUID, Long> cooldown = new HashMap<>(), ghostCd = new HashMap<>(), dropTick = new HashMap<>();
    private final Set<UUID> casting = new HashSet<>();

    Capricorne(SmpBoss plugin) {
        this.plugin = plugin;
    }

    private double cfg(String path, double def) {
        return plugin.getConfig().getDouble("capricorne." + path, def);
    }

    private Ultimates ult() {
        return plugin.weapons().ultimates;
    }

    private WeaponFx fx() {
        return ult().weaponFx;
    }

    private boolean wears(Player p) {
        return plugin.store().of(p.getUniqueId()).equipped.contains(ID);
    }

    // ================================================================= déclenchement

    /** Jeter un objet fait aussi un "clic gauche dans le vide" : on l'ignore. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) {
        dropTick.put(e.getPlayer().getUniqueId(), (long) Bukkit.getCurrentTick());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.LEFT_CLICK_AIR || e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (!p.isSneaking() || !wears(p)) return;
        Long dt = dropTick.get(p.getUniqueId());
        if (dt != null && Bukkit.getCurrentTick() - dt <= 1) return;
        breach(p);
    }

    /** Le lanceur ne prend pas ses propres dégâts de chute pendant la brèche (au cas où). */
    @EventHandler(ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && casting.contains(e.getEntity().getUniqueId())
                && e.getEntity() instanceof Player) e.setDamage(e.getDamage() * 0.5);
    }

    // ================================================================= passif : Fantôme du Net

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurt(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p) || !wears(p)) return;
        if (!plugin.getConfig().getBoolean("capricorne.ghost.enabled", true)) return;
        LivingEntity attacker = null;
        if (e.getDamager() instanceof LivingEntity le) attacker = le;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof LivingEntity le) attacker = le;
        if (attacker == null || attacker.equals(p)) return;
        long now = System.currentTimeMillis();
        Long until = ghostCd.get(p.getUniqueId());
        if (until != null && until > now) return;
        if (ThreadLocalRandom.current().nextDouble(100) >= cfg("ghost.chance", 15)) return;
        Location dest = blinkSpot(p, attacker);
        if (dest == null) return;
        ghostCd.put(p.getUniqueId(), now + (long) (cfg("ghost.cooldown-seconds", 10) * 1000));
        e.setCancelled(true);
        Location from = p.getLocation();
        glitchAt(from.clone().add(0, 1, 0), 1f);
        Arena.allowTeleport(p);
        p.teleport(dest);
        glitchAt(dest.clone().add(0, 1, 0), 0.8f);
        fx().snd(from, "capricorn.glitch", 1.2f, 1.1f);
        fx().snd(dest, "capricorn.glitch", 1.2f, 0.9f);
        PackFx.fallbackSound(from, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f, 0.6f);
        int t = (int) cfg("ghost.corrupt-ticks", 60);
        attacker.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, t, 1, false, true, true));
        if (attacker instanceof Player ap) {
            ap.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, t + 20, 0, false, true, true));
            screen(ap, SCREEN_DARK, 0, 2, 4);
        }
        p.sendActionBar(parse(plugin.msg("capricorne.ghost")));
    }

    /** Un endroit libre à 4-5 blocs, sur le côté par rapport à celui qui frappe. */
    private static Location blinkSpot(Player p, LivingEntity from) {
        Location l = p.getLocation();
        Vector away = l.toVector().subtract(from.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 0.01) away = l.getDirection().setY(0).multiply(-1);
        if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
        away.normalize();
        Vector side = new Vector(-away.getZ(), 0, away.getX());
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double s0 = r.nextBoolean() ? 1 : -1;
        for (double[] tryv : new double[][]{{0.6, s0}, {0.6, -s0}, {1, 0}, {0.2, s0}, {0.2, -s0}}) {
            for (double dist = 5; dist >= 3; dist -= 1) {
                Vector off = away.clone().multiply(tryv[0]).add(side.clone().multiply(tryv[1])).normalize().multiply(dist);
                Location c = l.clone().add(off);
                for (int dy = 1; dy >= -2; dy--) {
                    Block feet = c.getBlock().getRelative(0, dy, 0);
                    if (feet.isPassable() && feet.getRelative(0, 1, 0).isPassable() && !feet.getRelative(0, -1, 0).isPassable()
                            && !feet.isLiquid()) {
                        Location d = feet.getLocation().add(0.5, 0, 0.5);
                        d.setYaw(l.getYaw());
                        d.setPitch(l.getPitch());
                        return d;
                    }
                }
            }
        }
        return null;
    }

    private void glitchAt(Location c, float s) {
        WeaponFx fx = fx();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        // silhouette glitchée laissée sur place
        fx.pop(c.clone().add(0, 0.1, 0), "bw_ghost", 1.1f * s, 2.2f * s, 1.1f * s, 1, 3, 3, Display.Billboard.VERTICAL, null);
        for (int i = 0; i < 6; i++) {
            fx.pop(c.clone().add(r.nextDouble(-0.8, 0.8), r.nextDouble(-0.9, 0.9), r.nextDouble(-0.8, 0.8)), "bw_glyph", 0.4f * s, 0.4f * s, 0.4f * s,
                    1, r.nextInt(2, 6), 2, Display.Billboard.CENTER, null);
        }
        World w = c.getWorld();
        w.spawnParticle(Particle.DUST, c, 30, 0.35, 0.9, 0.35, 0, new Particle.DustOptions(RED, 1.2f), true);
        w.spawnParticle(Particle.DUST, c, 10, 0.35, 0.9, 0.35, 0, new Particle.DustOptions(CYAN, 0.9f), true);
        w.spawnParticle(Particle.DUST, c, 14, 0.3, 0.8, 0.3, 0, new Particle.DustOptions(BLACK, 1.3f), true);
    }

    // ================================================================= actif : Brèche du Black Wall

    void breach(Player p) {
        if (casting.contains(p.getUniqueId())) return;
        long now = System.currentTimeMillis();
        Long until = cooldown.get(p.getUniqueId());
        if (until != null && until > now) {
            p.sendActionBar(parse(plugin.msg("weapons.cooldown").replace("<seconds>", String.valueOf((until - now) / 1000 + 1))));
            return;
        }
        Location center = aim(p);
        if (center == null) return;
        cooldown.put(p.getUniqueId(), now + (long) (cfg("cooldown-seconds", 150) * 1000));
        casting.add(p.getUniqueId());
        p.sendActionBar(parse(plugin.msg("capricorne.cast")));
        new Breach(p, center).start();
    }

    /** Point visé (bloc ou créature, jusqu'à 32 blocs), ramené au sol. */
    private Location aim(Player p) {
        World w = p.getWorld();
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        double range = cfg("range", 32);
        RayTraceResult hit = w.rayTrace(eye, dir, range, FluidCollisionMode.NEVER, true, 0.6,
                en -> en instanceof LivingEntity && !en.equals(p) && !(en instanceof Player o && o.getGameMode() == org.bukkit.GameMode.SPECTATOR));
        Location at;
        if (hit == null) at = eye.clone().add(dir.multiply(range));
        else if (hit.getHitEntity() != null) at = hit.getHitEntity().getLocation();
        else at = hit.getHitPosition().toLocation(w).subtract(dir.multiply(0.3));
        Location g = PackFx.groundBelow(at.clone().add(0, 0.5, 0), 24);
        if (g == null) g = at;
        return g;
    }

    /** Une brèche : un cercle, un anneau de murs, 5 s de cage, puis l'effondrement. */
    private final class Breach {
        final Player caster;
        final Location c;
        final World w;
        final double R;
        final int walls;
        final boolean duels;
        final List<ItemDisplay> wallDisp = new ArrayList<>();
        final List<Location> wallPos = new ArrayList<>();
        final Set<UUID> denied = new HashSet<>(), okSet = new HashSet<>(), screened = new HashSet<>();
        final int RISE = 20, CAGE, COLLAPSE_SOUND, IMPLODE;
        final double H;
        BukkitTask task;
        int t;

        Breach(Player caster, Location c) {
            this.caster = caster;
            this.c = c.clone();
            this.w = c.getWorld();
            this.R = cfg("radius", 7.5);
            this.walls = Math.max(6, (int) cfg("walls", 10));
            this.H = cfg("wall-height", 11);
            this.duels = plugin.getConfig().getBoolean("capricorne.in-duels", false);
            this.CAGE = RISE + 10 + (int) cfg("cage-ticks", 100);
            this.COLLAPSE_SOUND = CAGE - 23;
            this.IMPLODE = CAGE;
        }

        void start() {
            // ouverture : sons, cercle de corruption, écran glitch pour le lanceur
            fx().snd(c, "capricorn.breach", 3.5f, 1f);
            fx().snd(caster.getLocation(), "capricorn.glitch", 1f, 0.8f);
            PackFx.fallbackSound(c, Sound.BLOCK_END_PORTAL_SPAWN, 1.4f, 0.7f);
            PackFx.fallbackSound(c, Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.6f, 0.5f);
            screen(caster, SCREEN_DARK, 0, 1, 3);
            Location g = c.clone().add(0, 0.06, 0);
            ItemDisplay circle = fx().sprite(g, "bw_circle", 0.5f, 0.5f, 0.5f, Display.Billboard.FIXED, null);
            fx().later(1, () -> PackFx.tween(circle, new Vector3f(), null, new Vector3f((float) R * 2.3f, 1, (float) R * 2.3f), RISE));
            fx().kill(circle, IMPLODE + 6);
            fx().tpop(g.clone().add(0, 0.02, 0), "w_rune_flat", RED, (float) R * 1.7f, 1, (float) R * 1.7f, RISE, CAGE - RISE, 6,
                    Display.Billboard.FIXED, null);
            for (int i = 0; i < walls; i++) {
                double a = Math.PI * 2 * i / walls;
                wallPos.add(c.clone().add(Math.cos(a) * R, 0, Math.sin(a) * R));
            }
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        }

        void tick() {
            int k = t++;
            if (!caster.isOnline()) {
                end();
                return;
            }
            ThreadLocalRandom r = ThreadLocalRandom.current();
            // --- montée : des glyphes de données tournent et s'élèvent du cercle
            if (k < RISE) {
                if (k % 2 == 0) {
                    for (int i = 0; i < 3; i++) {
                        double a = r.nextDouble(Math.PI * 2), rr = r.nextDouble(0.5, R);
                        Location l = c.clone().add(Math.cos(a) * rr, 0.3, Math.sin(a) * rr);
                        ItemDisplay gl = fx().sprite(l, "bw_glyph", 0.5f, 0.5f, 0.5f, Display.Billboard.CENTER, null);
                        double up = r.nextDouble(2, 5);
                        fx().later(1, () -> {
                            if (!gl.isValid()) return;
                            gl.setTeleportDuration(14);
                            gl.teleport(gl.getLocation().add(0, up, 0));
                            PackFx.tween(gl, new Vector3f(), null, new Vector3f(0.1f, 0.1f, 0.1f), 14);
                        });
                        fx().kill(gl, 16);
                    }
                }
                w.spawnParticle(Particle.DUST, c.clone().add(0, 0.2, 0), 18, R * 0.45, 0.1, R * 0.45, 0, new Particle.DustOptions(RED, 1.1f), true);
                if (k % 4 == 0) w.spawnParticle(Particle.DUST, c.clone().add(0, 0.2, 0), 10, R * 0.45, 0.1, R * 0.45, 0,
                        new Particle.DustOptions(BLACK, 1.5f), true);
                // ceux qui sont dedans sont prévenus
                if (k == 2) for (LivingEntity le : inside(R + 1.5)) if (le instanceof Player o) o.sendActionBar(parse(plugin.msg("capricorne.warn")));
            }
            // --- les murs jaillissent l'un après l'autre
            if (k >= RISE && k < RISE + walls) {
                int i = k - RISE;
                raiseWall(i);
                if (i == 0) {
                    fx().wave(c.clone().add(0, 0.1, 0), "w_shock_flat", RED, 1, (float) R * 2.6f, 10, null, Display.Billboard.FIXED);
                    for (LivingEntity le : inside(R)) {
                        if (le instanceof Player o && ok(le)) {
                            screen(o, SCREEN_RED, 0, 1, 3);
                            screened.add(o.getUniqueId());
                            o.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 50, 0, false, false, true));
                        }
                    }
                    PackFx.fallbackSound(c, Sound.ENTITY_WARDEN_EMERGE, 1.6f, 1.4f);
                }
            }
            // --- cage : personne ne sort, les tentacules et les spectres frappent
            if (k >= RISE && k < IMPLODE) {
                cage();
                if (k % 4 == 0) ambient();
                int every = Math.max(4, (int) cfg("tendril-every", 8));
                if (k >= RISE + walls && (k - RISE) % every == 0) tendril();
                int ge = Math.max(10, (int) cfg("ghost-every", 22));
                if (k >= RISE + walls && (k - RISE) % ge == 5) ghost();
            }
            if (k == COLLAPSE_SOUND) {
                fx().snd(c, "capricorn.collapse", 3.5f, 1f);
                collapseWalls();
            }
            if (k == IMPLODE) implode();
            if (k >= IMPLODE + 8) end();
        }

        void end() {
            if (task != null) task.cancel();
            casting.remove(caster.getUniqueId());
            for (ItemDisplay d : wallDisp) if (d.isValid()) d.remove();
        }

        // -------------------------------------------------------------- les murs

        void raiseWall(int i) {
            Location base = wallPos.get(i);
            Location g = PackFx.groundBelow(base.clone().add(0, 3, 0), 10);
            if (g != null) base.setY(g.getY());
            wallPos.set(i, base);
            double a = Math.PI * 2 * i / walls;
            float yaw = (float) (Math.PI / 2 - a);
            Quaternionf rot = new Quaternionf().rotateY(yaw);
            float width = (float) (2 * Math.PI * R / walls * 1.05);
            ItemDisplay d = fx().spriteOf(base, PackFx.model("bw_wall"), width, 0.05f, 1, Display.Billboard.FIXED, rot);
            d.setViewRange(12f);
            fx().later(1, () -> PackFx.tween(d, new Vector3f(0, (float) H / 2, 0), rot, new Vector3f(width, (float) H, 1), 4));
            wallDisp.add(d);
            fx().kill(d, IMPLODE + 6 - (RISE + i));
            fx().snd(base, "capricorn.wall", 2f, 0.9f + (i % 3) * 0.08f);
            fx().tpop(base.clone().add(0, 0.08, 0), "w_crack_flat", RED, 4.5f, 1, 4.5f, 1, 40, 8, Display.Billboard.FIXED,
                    new Quaternionf().rotateY((float) ThreadLocalRandom.current().nextDouble(6.28)));
            fx().puff(base.clone().add(0, 0.8, 0), DEEP, 3.2f);
            w.spawnParticle(Particle.BLOCK, base.clone().add(0, 0.3, 0), 30, 1.2, 0.2, 1.2, 0, base.clone().add(0, -0.5, 0).getBlock().getBlockData(), true);
            w.spawnParticle(Particle.DUST, base.clone().add(0, H / 2, 0), 40, 0.8, H / 3, 0.8, 0, new Particle.DustOptions(RED, 1.5f), true);
        }

        /** Le mur pour ceux qui n'ont pas le pack : une colonne de poussière rouge/noire. */
        void ambient() {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            for (int i = 0; i < wallDisp.size(); i++) {
                Location b = wallPos.get(i);
                PackFx.fallback(b.clone().add(0, H / 2, 0), Particle.DUST, 14, 0.6, H / 2.4, 0.6, 0, new Particle.DustOptions(RED, 1.6f));
                PackFx.fallback(b.clone().add(0, H / 2, 0), Particle.DUST, 8, 0.5, H / 2.4, 0.5, 0, new Particle.DustOptions(BLACK, 1.8f));
                if (r.nextInt(5) == 0) w.spawnParticle(Particle.ELECTRIC_SPARK, b.clone().add(0, r.nextDouble(1, H), 0), 6, 0.4, 0.4, 0.4, 0.05, null, true);
            }
            // pluie de données sur le sol de la cage
            w.spawnParticle(Particle.DUST, c.clone().add(0, 0.2, 0), 10, R * 0.5, 0.05, R * 0.5, 0, new Particle.DustOptions(DEEP, 1.3f), true);
        }

        /** Ceux qui sont dedans sont repoussés vers le centre quand ils touchent le mur. */
        void cage() {
            for (LivingEntity le : inside(R + 0.8)) {
                if (!ok(le) || isBoss(le)) continue;
                Vector off = le.getLocation().toVector().subtract(c.toVector()).setY(0);
                double d = off.length();
                if (d < R - 1.3 || d < 0.01) continue;
                Vector in = off.normalize().multiply(-0.75);
                le.setVelocity(in.setY(0.18));
                if (le instanceof Player o && ThreadLocalRandom.current().nextInt(3) == 0) {
                    fx().snd(o.getLocation(), "capricorn.glitch", 0.6f, 1.4f);
                    o.sendActionBar(parse(plugin.msg("capricorne.trapped")));
                }
                w.spawnParticle(Particle.DUST, le.getLocation().add(off.normalize().multiply(0.6)).add(0, 1, 0), 12, 0.2, 0.6, 0.2, 0,
                        new Particle.DustOptions(RED, 1.2f), true);
            }
        }

        /** Un tentacule de données part d'un mur et frappe quelqu'un dans la cage. */
        void tendril() {
            List<LivingEntity> in = new ArrayList<>();
            for (LivingEntity le : inside(R)) if (ok(le)) in.add(le);
            if (in.isEmpty()) {
                // personne : un éclair fouette le sol au hasard
                ThreadLocalRandom r = ThreadLocalRandom.current();
                Location to = c.clone().add(r.nextDouble(-R, R) * 0.6, 0.2, r.nextDouble(-R, R) * 0.6);
                bolt(wallTop(r.nextInt(wallPos.size())), to);
                return;
            }
            ThreadLocalRandom r = ThreadLocalRandom.current();
            LivingEntity t = in.get(r.nextInt(in.size()));
            Location to = t.getLocation().add(0, t.getHeight() * 0.5, 0);
            int wi = nearestWall(to);
            bolt(wallTop(wi), to);
            fx().snd(to, "capricorn.zap", 1.6f, 0.9f + (float) r.nextDouble(0.25));
            PackFx.fallbackSound(to, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.7f, 1.8f);
            if (isBoss(t)) {
                Boss b = plugin.king();
                if (b != null) b.hurt(caster, cfg("boss-tendril-damage", 30));
            } else if (t instanceof Player) {
                applyDamage(caster, t, maxHealth(t) * cfg("tendril-percent", 6) / 100.0);
                t.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 40, 1, false, true, true));
            } else {
                applyDamage(caster, t, cfg("tendril-damage", 14));
            }
        }

        void bolt(Location from, Location to) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            // tentacule en 3 segments qui ondulent
            Location prev = from;
            for (int s = 1; s <= 3; s++) {
                double u = s / 3.0;
                Location p2 = from.clone().add(to.clone().subtract(from).toVector().multiply(u));
                if (s < 3) p2.add(r.nextDouble(-1.2, 1.2), r.nextDouble(-0.6, 1.2), r.nextDouble(-1.2, 1.2));
                Vector seg = p2.toVector().subtract(prev.toVector());
                Location mid = prev.clone().add(seg.clone().multiply(0.5));
                fx().tpop(mid, "w_bolt_" + r.nextInt(4) + "_x", s % 2 == 0 ? RED : PALE, 0.9f, (float) seg.length() * 1.15f, 0.9f, 1, 2, 3,
                        Display.Billboard.FIXED, WeaponFx.alongY(seg));
                fx().tpop(mid, "w_beam_x", DEEP, 0.5f, (float) seg.length(), 0.5f, 1, 3, 3, Display.Billboard.FIXED, WeaponFx.alongY(seg));
                w.spawnParticle(Particle.DUST, mid, 10, seg.length() * 0.25, 0.2, seg.length() * 0.25, 0, new Particle.DustOptions(RED, 1.3f), true);
                prev = p2;
            }
            fx().tpop(to, "w_glow", RED, 2.5f, 2.5f, 2.5f, 1, 1, 4, Display.Billboard.CENTER, null);
            fx().tpop(to, "w_spark", BLACK, 1.8f, 1.8f, 1.8f, 1, 1, 3, Display.Billboard.CENTER, new Quaternionf().rotateZ((float) r.nextDouble(6.28)));
            w.spawnParticle(Particle.ELECTRIC_SPARK, to, 12, 0.3, 0.3, 0.3, 0.2, null, true);
        }

        /** Un spectre d'IA sort d'un mur, fonce sur quelqu'un et le traverse. */
        void ghost() {
            List<LivingEntity> in = new ArrayList<>();
            for (LivingEntity le : inside(R)) if (ok(le)) in.add(le);
            if (in.isEmpty()) return;
            ThreadLocalRandom r = ThreadLocalRandom.current();
            LivingEntity t = in.get(r.nextInt(in.size()));
            Location start = wallPos.get(r.nextInt(wallPos.size())).clone().add(0, 0.2, 0);
            Location end = t.getLocation().add(0, 0.2, 0);
            Vector path = end.toVector().subtract(start.toVector());
            ItemDisplay g = fx().sprite(start, "bw_ghost", 1.6f, 3.2f, 1.6f, Display.Billboard.VERTICAL, null);
            g.setTransformation(new org.bukkit.util.Transformation(new Vector3f(0, 1.6f, 0), new Quaternionf(), new Vector3f(1.6f, 3.2f, 1.6f), new Quaternionf()));
            fx().later(1, () -> {
                if (!g.isValid()) return;
                g.setTeleportDuration(8);
                g.teleport(end);
                PackFx.tween(g, new Vector3f(0, 1.8f, 0), null, new Vector3f(2f, 3.6f, 2f), 8);
            });
            fx().later(9, () -> {
                if (!g.isValid()) return;
                g.setTeleportDuration(4);
                g.teleport(end.clone().add(path.clone().normalize().multiply(2)));
                PackFx.tween(g, new Vector3f(0, 2.2f, 0), null, new Vector3f(0.1f, 4.5f, 0.1f), 4);
            });
            fx().kill(g, 14);
            fx().snd(start, "capricorn.glitch", 1.4f, 0.6f);
            fx().later(8, () -> {
                if (!t.isValid() || t.isDead()) return;
                Location at = t.getLocation().add(0, 1, 0);
                glitchAt(at, 1.2f);
                fx().snd(at, "capricorn.zap", 1.4f, 0.6f);
                if (isBoss(t)) {
                    Boss b = plugin.king();
                    if (b != null) b.hurt(caster, cfg("boss-ghost-damage", 40));
                    return;
                }
                if (t instanceof Player o) {
                    applyDamage(caster, t, maxHealth(t) * cfg("ghost-percent", 8) / 100.0);
                    o.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, false, false, true));
                    screen(o, SCREEN_DARK, 0, 1, 2);
                } else applyDamage(caster, t, cfg("ghost-damage", 18));
                t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 2, false, true, true));
            });
        }

        /** Les murs basculent vers le centre et rétrécissent. */
        void collapseWalls() {
            for (int i = 0; i < wallDisp.size(); i++) {
                ItemDisplay d = wallDisp.get(i);
                Location b = wallPos.get(i);
                Vector toC = c.toVector().subtract(b.toVector()).setY(0).multiply(0.8);
                double a = Math.PI * 2 * i / walls;
                Quaternionf rot = new Quaternionf().rotateY((float) (Math.PI / 2 - a)).rotateX(-0.9f);
                int delay = i % 3;
                fx().later(1 + delay, () -> PackFx.tween(d, new Vector3f((float) toC.getX(), (float) H * 0.3f, (float) toC.getZ()), rot,
                        new Vector3f(0.6f, (float) H * 0.4f, 1), 22 - delay));
            }
            // l'air est aspiré vers le centre
            for (LivingEntity le : inside(R + 3)) {
                if (!ok(le) || isBoss(le)) continue;
                Vector in = c.toVector().subtract(le.getLocation().toVector()).setY(0);
                if (in.lengthSquared() > 0.25) le.setVelocity(in.normalize().multiply(0.55).setY(0.1));
            }
            for (int i = 0; i < 20; i++) {
                double a = Math.PI * 2 * i / 20;
                w.spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * R, 1.5, Math.sin(a) * R), 6, 0.3, 1, 0.3, 0, new Particle.DustOptions(RED, 1.5f), true);
            }
        }

        /** L'implosion : tout s'écrase au centre. */
        void implode() {
            Location m = c.clone().add(0, 1.4, 0);
            WeaponFx fx = fx();
            fx.boom(m, RED, 13);
            fx.boom(m.clone().add(0, 0.5, 0), BLACK, 9);
            fx.puff(m, DEEP, 9);
            fx.pop(m, "bf_spikes", 14, 14, 14, 1, 2, 4, Display.Billboard.CENTER, new Quaternionf().rotateZ(0.3f));
            fx.pop(m, "bf_star", 10, 10, 10, 1, 2, 4, Display.Billboard.CENTER, null);
            fx.wave(c.clone().add(0, 0.1, 0), "w_shock_flat", RED, 1, (float) R * 3.4f, 12, null, Display.Billboard.FIXED);
            fx.wave(m, "w_shock", Color.WHITE, 1, (float) R * 2.6f, 9, null, Display.Billboard.CENTER);
            ThreadLocalRandom r = ThreadLocalRandom.current();
            for (int i = 0; i < 4; i++) fx.tpop(c.clone().add(0, 0.09, 0), "w_crack_flat", RED, (float) R * 1.6f, 1, (float) R * 1.6f, 1, 30, 10,
                    Display.Billboard.FIXED, new Quaternionf().rotateY((float) r.nextDouble(6.28)));
            for (int i = 0; i < 14; i++) {
                Vector d = new Vector(r.nextDouble(-1, 1), r.nextDouble(-0.2, 1), r.nextDouble(-1, 1));
                if (d.lengthSquared() < 0.05) d = new Vector(0, 1, 0);
                d.normalize();
                double L = r.nextDouble(4, 10);
                fx.tpop(m.clone().add(d.clone().multiply(L / 2)), "w_bolt_" + r.nextInt(4) + "_x", i % 3 == 0 ? PALE : RED, 1.4f, (float) L, 1.4f, 1, 2, 4,
                        Display.Billboard.FIXED, WeaponFx.alongY(d));
            }
            w.spawnParticle(Particle.DUST, m, 160, R * 0.4, 1.5, R * 0.4, 0, new Particle.DustOptions(RED, 2.2f), true);
            w.spawnParticle(Particle.DUST, m, 80, R * 0.35, 1.2, R * 0.35, 0, new Particle.DustOptions(BLACK, 2f), true);
            w.spawnParticle(Particle.EXPLOSION_EMITTER, m, 2, 1, 0.5, 1, 0, null, true);
            w.spawnParticle(Particle.ELECTRIC_SPARK, m, 80, 2, 1.5, 2, 0.6, null, true);
            PackFx.fallbackSound(m, Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.5f);
            PackFx.fallbackSound(m, Sound.ENTITY_WARDEN_SONIC_BOOM, 2f, 0.6f);
            // écrans de corruption : noir puis rouge
            for (Player o : w.getPlayers()) {
                if (o.getLocation().distanceSquared(c) > (R + 6) * (R + 6)) continue;
                screen(o, SCREEN_DARK, 0, 1, 0);
                Bukkit.getScheduler().runTaskLater(plugin, () -> screen(o, SCREEN_RED, 0, 2, 4), 2L);
            }
            // dégâts
            for (LivingEntity le : inside(R + 1.5)) {
                if (!ok(le)) continue;
                if (isBoss(le)) {
                    Boss b = plugin.king();
                    if (b != null) b.hurt(caster, cfg("boss-collapse-damage", 450));
                    continue;
                }
                if (le instanceof Player) applyDamage(caster, le, maxHealth(le) * cfg("collapse-percent", 35) / 100.0);
                else applyDamage(caster, le, cfg("collapse-damage", 60));
                if (le.isValid() && !le.isDead()) {
                    le.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 80, 1, false, true, true));
                    Vector kb = le.getLocation().toVector().subtract(c.toVector()).setY(0);
                    if (kb.lengthSquared() < 0.01) kb = new Vector(r.nextDouble(-1, 1), 0, r.nextDouble(-1, 1));
                    le.setVelocity(kb.normalize().multiply(0.9).setY(1.1));
                }
            }
            if (caster.isOnline()) caster.sendActionBar(parse(plugin.msg("capricorne.collapse")));
        }

        // -------------------------------------------------------------- outils

        List<LivingEntity> inside(double rad) {
            List<LivingEntity> out = new ArrayList<>();
            for (LivingEntity le : ult().targets(caster, c.clone().add(0, 2, 0), rad + 4, duels)) {
                Location l = le.getLocation();
                double dx = l.getX() - c.getX(), dz = l.getZ() - c.getZ();
                if (dx * dx + dz * dz <= rad * rad && Math.abs(l.getY() - c.getY()) < H || isBoss(le) && dx * dx + dz * dz <= (rad + 6) * (rad + 6)) out.add(le);
            }
            return out;
        }

        /** Les protections acceptent-elles qu'on touche cette cible ? (une seule fois par cible) */
        boolean ok(LivingEntity le) {
            UUID id = le.getUniqueId();
            if (okSet.contains(id)) return true;
            if (denied.contains(id)) return false;
            boolean a = allowed(caster, le);
            (a ? okSet : denied).add(id);
            return a;
        }

        Location wallTop(int i) {
            return wallPos.get(i).clone().add(0, H * 0.85, 0);
        }

        int nearestWall(Location l) {
            int best = 0;
            double bd = Double.MAX_VALUE;
            for (int i = 0; i < wallPos.size(); i++) {
                Location p = wallPos.get(i);
                double dx = p.getX() - l.getX(), dz = p.getZ() - l.getZ();
                double d = dx * dx + dz * dz;
                if (d < bd) {
                    bd = d;
                    best = i;
                }
            }
            return best;
        }
    }

    // ================================================================= dégâts (comme le Black Flash)

    private boolean isBoss(Entity e) {
        Boss b = plugin.king();
        return b != null && b.isBoss(e);
    }

    private static double maxHealth(LivingEntity t) {
        var a = t.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20 : a.getValue();
    }

    private boolean allowed(Player caster, LivingEntity t) {
        if (isBoss(t)) return true;
        var ev = new EntityDamageByEntityEvent(caster, t, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(caster).withDirectEntity(caster).build(), 1.0);
        Bukkit.getPluginManager().callEvent(ev);
        return !ev.isCancelled();
    }

    private static void applyDamage(Player caster, LivingEntity t, double amount) {
        if (amount <= 0 || t.isDead()) return;
        double h = t.getHealth() + t.getAbsorptionAmount();
        if (h - amount <= 0.01) {
            t.setNoDamageTicks(0);
            t.damage(amount * 50, DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(caster).withDirectEntity(caster).build());
            return;
        }
        double abs = t.getAbsorptionAmount();
        double fromAbs = Math.min(abs, amount);
        t.setAbsorptionAmount(abs - fromAbs);
        t.setHealth(Math.max(0.5, t.getHealth() - (amount - fromAbs)));
        t.playHurtAnimation((float) ThreadLocalRandom.current().nextDouble(360));
        t.getWorld().playSound(t.getLocation(), t instanceof Player ? Sound.ENTITY_PLAYER_HURT : Sound.ENTITY_GENERIC_HURT, SoundCategory.PLAYERS, 1f, 0.8f);
    }

    /** Écran de corruption plein écran (pack). */
    private static void screen(Player o, char glyph, int in, int stay, int out) {
        if (!PackStatus.has(o)) return;
        var txt = net.kyori.adventure.text.Component.text(String.valueOf(glyph)).font(net.kyori.adventure.key.Key.key(PackFx.NS, "fx"))
                .color(net.kyori.adventure.text.format.NamedTextColor.WHITE).shadowColor(net.kyori.adventure.text.format.ShadowColor.shadowColor(0));
        o.showTitle(net.kyori.adventure.title.Title.title(txt, net.kyori.adventure.text.Component.empty(),
                net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(in * 50L), java.time.Duration.ofMillis(stay * 50L),
                        java.time.Duration.ofMillis(out * 50L))));
    }
}
