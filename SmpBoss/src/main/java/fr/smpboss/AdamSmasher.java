package fr.smpboss;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Pillager;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntConsumer;

import static fr.smpboss.SmpBoss.parse;

/**
 * Adam Smasher : le boss de Night City, sur le toit de l'île flottante sous son dôme.
 * Un colosse cybernétique (design original, modèles du pack, environ 4 blocs de haut) animé autour d'un zombie invisible. Toutes ses attaques sont annoncées :
 * Rafale (un laser rouge te suit, puis la mitrailleuse tire dans cette ligne), Missiles (cercles rouges au sol),
 * Charge (une ligne au sol), Écrasement (il saute et retombe sur toi), IEM (phase 2 : coupe les implants),
 * Renforts (soldats) et, en phase 3, ses Sauts Kerenzikov (il apparaît derrière toi).
 */
public final class AdamSmasher implements Boss {

    private enum Attack { BURST, MISSILES, CHARGE, SLAM, EMP, TROOPS, BLINK }

    static final Color RED = Color.fromRGB(255, 30, 50), MAGENTA = Color.fromRGB(255, 42, 109), CYAN = Color.fromRGB(5, 217, 232),
            STEEL = Color.fromRGB(150, 155, 165), YELLOW = Color.fromRGB(255, 220, 60);

    private final SmpBoss plugin;
    private final Location altar;
    private final NamespacedKey bossKey, minionKey;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final Set<Entity> props = new HashSet<>();
    private final Set<Entity> minions = new HashSet<>();
    private final Map<UUID, Double> damage = new HashMap<>();

    private Zombie boss;
    private Rig rig;
    private BossBar bar;
    private DamageBoard board;
    private double hp, maxHp;
    private int fighters, phase = 1, tick, invulnerable, nextAttack = 80, lonely, lastNumber;
    private Attack last;
    private boolean ended, enraged, airborne;
    private float size;

    AdamSmasher(SmpBoss plugin, Location altar) {
        this.plugin = plugin;
        this.altar = altar.clone();
        this.bossKey = new NamespacedKey(plugin, "smasher_boss");
        this.minionKey = new NamespacedKey(plugin, "smasher_minion");
    }

    // ================================================================= Boss

    public Arena bossArena() {
        return plugin.smasherArena();
    }

    public String kind() {
        return "smasher";
    }

    public boolean isBoss(Entity e) {
        return e != null && e == boss;
    }

    @Override
    public LivingEntity mainEntity() {
        return boss;
    }

    public boolean isMinion(Entity e) {
        return e != null && minions.contains(e);
    }

    public boolean active() {
        return !ended;
    }

    public double hp() {
        return hp;
    }

    public double maxHp() {
        return maxHp;
    }

    public int phase() {
        return phase;
    }

    public int fighters() {
        return fighters;
    }

    private double cfg(String path, double def) {
        return plugin.getConfig().getDouble("smasher." + path, def);
    }

    private String msg(String key) {
        return plugin.msg("smasher." + key);
    }

    private WeaponFx fx() {
        Weapons w = plugin.weapons();
        return w == null ? null : w.ultimates.weaponFx;
    }

    // ================================================================= invocation

    public void start(Player summoner) {
        World w = altar.getWorld();
        Location top = altar.clone().add(0.5, 1, 0.5);
        String who = summoner == null ? "?" : summoner.getName();
        Bukkit.broadcast(parse(msg("summoning").replace("<player>", SmpBoss.MM.escapeTags(who))));
        sound(top, "block.beacon.activate", 3f, 0.5f);
        sound(top, "entity.warden.sonic_charge", 2f, 0.6f);
        // la ville clignote : néons rouges, alarme, puis il tombe du ciel
        repeat(8, 14, i -> {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            for (int k = 0; k < 8; k++) {
                Location l = top.clone().add(r.nextDouble(-14, 14), r.nextDouble(0, 6), r.nextDouble(-14, 14));
                w.spawnParticle(Particle.ELECTRIC_SPARK, l, 6, 0.3, 0.3, 0.3, 0.1, null, true);
            }
            w.spawnParticle(Particle.DUST, top, 30, 2 + i * 0.5, 0.1, 2 + i * 0.5, 0, new Particle.DustOptions(i % 2 == 0 ? RED : MAGENTA, 1.6f), true);
            sound(top, "block.note_block.bit", 2.5f, i % 2 == 0 ? 0.6f : 0.5f);
            if (i % 4 == 0) sound(top, "entity.iron_golem.step", 3f, 0.5f);
        }, () -> spawnBoss(top));
    }

    private void spawnBoss(Location at) {
        World w = at.getWorld();
        fighters = Math.max(1, nearby(at, 45).size());
        maxHp = hpFor(fighters);
        hp = maxHp;
        if (plugin.getConfig().getBoolean("boss.damage-sidebar", true)) board = new DamageBoard(plugin);
        size = (float) cfg("size", 2.0);
        Location sky = at.clone().add(0, 26, 0);
        boss = w.spawn(sky, Zombie.class, z -> {
            z.setPersistent(false);
            z.setRemoveWhenFarAway(false);
            z.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 1);
            z.setAdult();
            z.setShouldBurnInDay(false);
            z.setCanPickupItems(false);
            z.setAI(false);
            z.setSilent(true);
            base(z, Attribute.MAX_HEALTH, 1024);
            z.setHealth(1024);
            base(z, Attribute.SCALE, size);
            base(z, Attribute.MOVEMENT_SPEED, cfg("speed", 0.27));
            base(z, Attribute.ATTACK_DAMAGE, cfg("melee-damage", 14));
            base(z, Attribute.KNOCKBACK_RESISTANCE, 1);
            base(z, Attribute.FOLLOW_RANGE, 48);
            base(z, Attribute.SPAWN_REINFORCEMENTS, 0);
            base(z, Attribute.STEP_HEIGHT, 1.5);
            z.customName(parse(msg("name")));
            z.setCustomNameVisible(true);
            z.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false));
            EntityEquipment eq = z.getEquipment();
            if (eq != null) eq.clear();
        });
        rig = new Rig(sky);
        bar = BossBar.bossBar(parse(msg("bar-1")), 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
        airborne = true;
        repeat(1, 30, i -> {
            if (boss == null || !boss.isValid()) return;
            double u = (i + 1) / 30.0, e = u * u;
            boss.teleport(sky.clone().add(0, -26 * e, 0));
            w.spawnParticle(Particle.FLAME, boss.getLocation(), 6, 0.3, 0.1, 0.3, 0.02, null, true);
            w.spawnParticle(Particle.LARGE_SMOKE, boss.getLocation(), 3, 0.3, 0.2, 0.3, 0.01, null, true);
        }, () -> {
            airborne = false;
            if (boss == null || !boss.isValid()) return;
            boss.setAI(true);
            titleAll("title-start", "subtitle-start");
            sound(at, "entity.generic.explode", 3f, 0.6f);
            sound(at, "entity.iron_golem.damage", 3f, 0.5f);
            shockwave(at, 10, 1.2, 0, RED);
        });
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L));
    }

    // ================================================================= boucle

    private void tick() {
        if (ended) return;
        if (boss == null || !boss.isValid()) {
            leave("smasher.vanished");
            return;
        }
        tick++;
        Location c = boss.getLocation();
        World w = c.getWorld();
        rig.update(c, boss.getBodyYaw(), size / 2.0);
        if (invulnerable > 0) invulnerable--;
        if (tick % 10 == 0) {
            bar.name(parse(msg("bar-" + phase) + plugin.msg("boss.bar-hp").replace("<hp>", String.valueOf((long) Math.ceil(Math.max(0, hp))))
                    .replace("<max>", String.valueOf((long) maxHp))));
        }
        List<Player> near = nearby(c, 60);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (near.contains(p)) p.showBossBar(bar);
            else p.hideBossBar(bar);
        }
        bar.progress((float) Math.max(0, Math.min(1, hp / maxHp)));
        if (board != null && tick % 10 == 0) board.update(near, hp, maxHp, damage, minPct());
        // l'œil rouge, la vapeur du dos
        if (tick % 4 == 0) {
            for (Location ex : rig.exhausts()) w.spawnParticle(phase >= 3 ? Particle.FLAME : Particle.SMOKE, ex, 2, 0.06, 0.05, 0.06, 0.02, null, true);
        }
        if (enraged && tick % 3 == 0) w.spawnParticle(Particle.DUST, c.clone().add(0, size, 0), 5, 0.4 * size, 0.6 * size, 0.4 * size, 0,
                new Particle.DustOptions(RED, 1.6f), true);
        if (tick % 10 == 0) borderRing();
        if (tick % 10 == 0 && boss.hasAI()) {
            Player t = nearest(c, 40);
            if (t != null) boss.setTarget(t);
        }
        if (tick % 12 == 0 && boss.getVelocity().lengthSquared() > 0.003) sound(c, "entity.iron_golem.step", 1.6f, 0.6f);
        if (!airborne) {
            double arena = cfg("arena-radius", 24);
            Location mid = altar.clone().add(0.5, 0, 0.5);
            double hd = c.getWorld() == altar.getWorld() ? horizontalDist(c, mid) : Double.MAX_VALUE;
            if (hd > arena + 12 || c.getY() < altar.getY() - 10) {
                boss.teleport(altar.clone().add(0.5, 1, 0.5));
            } else if (hd > arena + 0.5 || c.getY() > altar.getY() + 6 || c.getBlock().isLiquid()) {
                Location back = keepIn(c, arena - 2);
                back.setY(altar.getY() + 1);
                boss.teleport(back);
                boss.setVelocity(new Vector());
            }
        }
        if (tick % 20 == 0) {
            lonely = near.isEmpty() ? lonely + 1 : 0;
            if (lonely >= 60) {
                leave("smasher.left");
                return;
            }
            if (tick > cfg("max-minutes", 20) * 1200) {
                leave("smasher.timeout");
                return;
            }
        }
        tickMinions();
        if (invulnerable > 0 || airborne || !boss.hasAI()) return;
        if (--nextAttack <= 0) {
            nextAttack = (int) cfg("attack-interval." + phase, phase == 1 ? 60 : phase == 2 ? 48 : 38);
            if (enraged) nextAttack = (int) (nextAttack * 0.7);
            List<Attack> pool = switch (phase) {
                case 1 -> List.of(Attack.BURST, Attack.MISSILES, Attack.CHARGE, Attack.SLAM, Attack.TROOPS, Attack.BURST);
                case 2 -> List.of(Attack.BURST, Attack.MISSILES, Attack.CHARGE, Attack.SLAM, Attack.TROOPS, Attack.EMP, Attack.MISSILES);
                default -> List.of(Attack.BURST, Attack.MISSILES, Attack.CHARGE, Attack.SLAM, Attack.EMP, Attack.BLINK, Attack.BLINK, Attack.TROOPS);
            };
            Attack a;
            int guard = 0;
            do {
                a = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
            } while (a == last && guard++ < 20);
            last = a;
            run(a);
        }
    }

    private void run(Attack a) {
        switch (a) {
            case BURST -> burst();
            case MISSILES -> missiles();
            case CHARGE -> charge();
            case SLAM -> slam();
            case EMP -> emp();
            case TROOPS -> troops();
            case BLINK -> blink();
        }
    }

    // ================================================================= dégâts reçus

    public boolean hurt(Player by, double amount) {
        if (ended || invulnerable > 0 || boss == null) return false;
        if (by != null) damage.merge(by.getUniqueId(), amount, Double::sum);
        if (by != null && damage.size() > fighters) {
            double old = maxHp;
            fighters = damage.size();
            maxHp = hpFor(fighters);
            hp = hp * maxHp / old;
            for (Player p : nearby(boss.getLocation(), 60)) {
                SmpBoss.hud(p, parse(plugin.msg("boss.new-fighter").replace("<player>", SmpBoss.MM.escapeTags(by.getName()))
                        .replace("<hp>", String.valueOf((long) (perPlayer() * extraShare())))));
            }
        }
        hp -= amount;
        damageNumber(amount);
        if (tick % 3 == 0) sound(boss.getLocation(), "entity.iron_golem.hurt", 1.2f, 0.6f);
        boss.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, boss.getLocation().add(0, size, 0), 8, 0.3 * size, 0.4 * size, 0.3 * size, 0.2, null, true);
        double ratio = hp / maxHp;
        if (hp <= 0) defeat();
        else if (phase == 1 && ratio <= cfg("phase-2-at", 0.66)) changePhase(2);
        else if (phase == 2 && ratio <= cfg("phase-3-at", 0.33)) changePhase(3);
        else if (phase == 3 && !enraged && ratio <= cfg("enrage-at", 0.12)) enrage();
        return true;
    }

    private void changePhase(int to) {
        phase = to;
        invulnerable = 50;
        boss.setAI(false);
        Location c = boss.getLocation();
        sound(c, "entity.iron_golem.death", 3f, 0.5f);
        sound(c, "block.beacon.power_select", 3f, 0.5f);
        float from = size, target = (float) cfg("size-phase-" + to, to == 2 ? 2.2 : 2.4);
        repeat(1, 40, i -> {
            if (boss == null || !boss.isValid()) return;
            size = from + (target - from) * (i + 1) / 40f;
            base(boss, Attribute.SCALE, size);
            boss.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, boss.getLocation().add(0, size, 0), 10, size * 0.4, size * 0.6, size * 0.4, 0.2, null, true);
        }, () -> {
            if (boss != null && boss.isValid()) boss.setAI(true);
            nextAttack = 25;
        });
        bar.color(to == 2 ? BossBar.Color.PINK : BossBar.Color.PURPLE);
        later(38, () -> {
            if (boss != null && boss.isValid()) shockwave(boss.getLocation(), 9, 1.5, cfg("damage.phase-shockwave", 4), to == 3 ? RED : MAGENTA);
        });
        titleAll("title-phase-" + to, "subtitle-phase-" + to);
        AttributeInstance sp = boss.getAttribute(Attribute.MOVEMENT_SPEED);
        if (sp != null) sp.setBaseValue(cfg("speed", 0.27) * (to == 3 ? 1.3 : 1.15));
    }

    private void enrage() {
        enraged = true;
        titleAll("title-enrage", "subtitle-enrage");
        Location c = boss.getLocation();
        sound(c, "entity.ravager.roar", 4f, 0.5f);
        shockwave(c, 12, 2.0, cfg("damage.phase-shockwave", 4) * 2, RED);
        bar.color(BossBar.Color.RED);
        AttributeInstance sp = boss.getAttribute(Attribute.MOVEMENT_SPEED);
        if (sp != null) sp.setBaseValue(cfg("speed", 0.27) * 1.5);
        nextAttack = 20;
    }

    // ================================================================= le corps (modèles du pack)

    /**
     * Le colosse : 15 morceaux (objets du pack posés sur du béton noir), placés chaque tick autour du zombie invisible
     * qui sert de boîte de collision. Marche (jambes et bras en alternance), canon levé (Rafale), bras levés (Écrasement, IEM),
     * genou à terre (défaite). Sans le pack : des blocs noirs en forme de colosse.
     */
    private final class Rig {
        final ItemDisplay pelvis, torso, head;
        final ItemDisplay[] shoulders = new ItemDisplay[2], arms = new ItemDisplay[2], fores = new ItemDisplay[2],
                thighs = new ItemDisplay[2], shins = new ItemDisplay[2], feet = new ItemDisplay[2];
        double clock, aim, raise, kneel;
        double aimTarget, raiseTarget, kneelTarget;
        Location last, muzzleAt;
        private Location exL, exR;

        Rig(Location at) {
            pelvis = part(at, "smasher_pelvis");
            torso = part(at, "smasher_torso");
            head = part(at, "smasher_head");
            for (int i = 0; i < 2; i++) {
                shoulders[i] = part(at, "smasher_shoulder");
                arms[i] = part(at, "smasher_arm");
                fores[i] = part(at, i == 1 ? "smasher_cannon" : "smasher_forearm");
                thighs[i] = part(at, "smasher_thigh");
                shins[i] = part(at, "smasher_shin");
                feet[i] = part(at, "smasher_foot");
            }
            last = at.clone();
        }

        private ItemDisplay part(Location at, String model) {
            ItemStack it = new ItemStack(Material.BLACK_CONCRETE);
            var meta = it.getItemMeta();
            SmpBoss.modelData(meta, model);
            it.setItemMeta(meta);
            Location l = at.clone();
            l.setYaw(0);
            l.setPitch(0);
            ItemDisplay d = at.getWorld().spawn(l, ItemDisplay.class, e -> {
                e.setPersistent(false);
                e.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 2);
                e.setItemStack(it);
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                e.setTeleportDuration(1);
                e.setInterpolationDuration(1);
                e.setBrightness(new Display.Brightness(12, 15));
                e.setViewRange(4f);
                e.setShadowRadius(0);
            });
            props.add(d);
            return d;
        }

        private void set(ItemDisplay d, Location at, Quaternionf rot, Vector3f scale) {
            if (!d.isValid()) return;
            Location l = at.clone();
            l.setYaw(0);
            l.setPitch(0);
            d.teleport(l);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(1);
            d.setTransformation(new Transformation(new Vector3f(), rot, scale, new Quaternionf()));
        }

        private void seg(ItemDisplay d, Location a, Location b, double thick) {
            Vector v = b.toVector().subtract(a.toVector());
            double len = v.length();
            if (len < 1e-3) return;
            Quaternionf q = new Quaternionf().rotationTo(new Vector3f(0, 1, 0), new Vector3f((float) v.getX(), (float) v.getY(), (float) v.getZ()).normalize());
            set(d, a.clone().add(v.multiply(0.5)), q, new Vector3f((float) thick, (float) len, (float) thick));
        }

        List<Location> exhausts() {
            List<Location> out = new ArrayList<>();
            if (exL != null) out.add(exL);
            if (exR != null) out.add(exR);
            return out;
        }

        /** base = pieds, yaw = regard, S = échelle (1 = 4 blocs de haut). */
        void update(Location base, float yaw, double S) {
            double moved = horizontalDist(base, last);
            last = base.clone();
            boolean moving = moved > 0.02 && kneel < 0.3;
            clock += moving ? Math.min(0.5, 0.12 + moved * 1.2) : 0;
            aim += (aimTarget - aim) * 0.25;
            raise += (raiseTarget - raise) * 0.2;
            kneel += (kneelTarget - kneel) * 0.12;
            double rad = Math.toRadians(yaw);
            Vector f = new Vector(-Math.sin(rad), 0, Math.cos(rad));
            Vector r = new Vector(-Math.cos(rad), 0, -Math.sin(rad));
            Vector up = new Vector(0, 1, 0);
            Quaternionf facing = new Quaternionf().rotateY((float) -rad);
            double bob = (moving ? Math.abs(Math.sin(clock)) * 0.07 : Math.sin(tick * 0.08) * 0.02) - kneel * 0.55;
            double lean = (moving ? 0.1 : 0) + kneel * 0.25;
            float s = (float) S;
            set(pelvis, at(base, f, r, S, 2.0 + bob, 0, 0), facing, new Vector3f(1.15f * s, 0.6f * s, 0.8f * s));
            set(torso, at(base, f, r, S, 2.9 + bob, 0.06 + lean * 0.4, 0), new Quaternionf(facing).rotateX((float) lean),
                    new Vector3f(1.65f * s, 1.35f * s, 1.05f * s));
            set(head, at(base, f, r, S, 3.9 + bob, 0.16 + lean * 0.9, 0), new Quaternionf(facing).rotateX((float) (-lean * 0.5)),
                    new Vector3f(1.15f * s, 1.15f * s, 1.15f * s));
            exL = at(base, f, r, S, 3.75 + bob, -0.65, -0.35);
            exR = at(base, f, r, S, 3.75 + bob, -0.65, 0.35);
            // bras : 0 = poing, 1 = canon
            for (int i = 0; i < 2; i++) {
                int side = i == 1 ? 1 : -1;
                boolean cannon = i == 1;
                Location sh = at(base, f, r, S, 3.3 + bob, 0.05 + lean * 0.5, side * 0.95);
                set(shoulders[i], sh.clone().add(up.clone().multiply(0.18 * S)).add(r.clone().multiply(side * 0.06 * S)), facing,
                        new Vector3f(0.66f * s, 0.55f * s, 0.74f * s));
                double sw = moving ? Math.sin(clock + (side > 0 ? 0 : Math.PI)) * 0.45 : Math.sin(tick * 0.05 + side) * 0.04;
                double sw2 = sw + 0.45;
                if (cannon && aim > 0.01) {
                    sw = sw * (1 - aim) + 1.5 * aim;
                    sw2 = sw2 * (1 - aim) + 1.5 * aim;
                }
                if (raise > 0.01) {
                    sw = sw * (1 - raise) + 2.7 * raise;
                    sw2 = sw2 * (1 - raise) + 2.9 * raise;
                }
                Vector out = r.clone().multiply(side * 0.1 * S);
                Location el = sh.clone().add(up.clone().multiply(-Math.cos(sw) * 0.85 * S)).add(f.clone().multiply(Math.sin(sw) * 0.85 * S)).add(out);
                double l2 = cannon ? 1.05 : 0.85;
                Location hand = el.clone().add(up.clone().multiply(-Math.cos(sw2) * l2 * S)).add(f.clone().multiply(Math.sin(sw2) * l2 * S));
                if (cannon && aim > 0.01) hand.add(r.clone().multiply(-side * 0.06 * S * aim));
                seg(arms[i], sh, el, 0.44 * S);
                seg(fores[i], el, hand, (cannon ? 0.56 : 0.48) * S);
                if (cannon) muzzleAt = hand.clone().add(hand.toVector().subtract(el.toVector()).normalize().multiply(0.1 * S));
            }
            // jambes
            for (int i = 0; i < 2; i++) {
                int side = i == 1 ? 1 : -1;
                Location hip = at(base, f, r, S, 1.92 + bob, 0, side * 0.36);
                double ph = clock + (side > 0 ? 0 : Math.PI);
                double ls = moving ? Math.sin(ph) * 0.42 : 0, bend = moving ? Math.max(0, -Math.cos(ph)) * 0.55 : 0;
                ls += kneel * (side > 0 ? 0.9 : -0.1);
                bend += kneel * (side > 0 ? 1.6 : 0.2);
                Location kn = hip.clone().add(up.clone().multiply(-Math.cos(ls) * 0.95 * S)).add(f.clone().multiply(Math.sin(ls) * 0.95 * S));
                Location an = kn.clone().add(up.clone().multiply(-Math.cos(ls - bend) * 0.8 * S)).add(f.clone().multiply(Math.sin(ls - bend) * 0.8 * S));
                seg(thighs[i], hip, kn, 0.52 * S);
                seg(shins[i], kn, an, 0.44 * S);
                set(feet[i], an.clone().add(0, -0.08 * S, 0).add(f.clone().multiply(0.12 * S)), facing, new Vector3f(0.52f * s, 0.32f * s, 0.82f * s));
            }
        }

        private Location at(Location base, Vector f, Vector r, double S, double h, double fw, double rt) {
            return base.clone().add(0, h * S, 0).add(f.clone().multiply(fw * S)).add(r.clone().multiply(rt * S));
        }

        void remove() {
            pelvis.remove();
            torso.remove();
            head.remove();
            for (ItemDisplay[] g : new ItemDisplay[][]{shoulders, arms, fores, thighs, shins, feet}) for (ItemDisplay d : g) d.remove();
        }
    }

    // ================================================================= attaques

    /** Rafale : un laser rouge suit une cible (en retard), se fige, puis la mitrailleuse tire dans la ligne. */
    private void burst() {
        Player t = randomTarget(36);
        if (t == null) return;
        announce("burst");
        boss.setAI(false);
        rig.aimTarget = 1;
        sound(boss.getLocation(), "block.beacon.ambient", 2f, 2f);
        Vector[] aim = {t.getEyeLocation().toVector().subtract(muzzle().toVector()).normalize()};
        int lock = 26;
        repeat(1, lock, i -> {
            if (boss == null || !boss.isValid() || !t.isOnline()) return;
            Location m = muzzle();
            Vector want = t.getEyeLocation().add(0, -0.4, 0).toVector().subtract(m.toVector()).normalize();
            aim[0] = aim[0].clone().multiply(0.82).add(want.multiply(0.18)).normalize();
            face(m.clone().add(aim[0]));
            laser(m, aim[0], 40, i > lock - 8 ? 1.4f : 0.7f);
            if (i % 6 == 0) sound(m, "block.note_block.hat", 1.5f, 2f);
        }, () -> {
            if (boss == null || !boss.isValid()) return;
            int shots = (int) cfg("burst-shots", 8) + phase * 2;
            repeat(2, shots, k -> {
                if (boss == null || !boss.isValid()) return;
                Location m = muzzle();
                Vector d = aim[0].clone().add(new Vector(rnd(0.03), rnd(0.03), rnd(0.03))).normalize();
                shoot(m, d, 44, cfg("damage.burst", 3));
            }, () -> {
                rig.aimTarget = 0;
                if (boss != null && boss.isValid()) boss.setAI(true);
            });
        });
    }

    private Location muzzle() {
        if (rig != null && rig.muzzleAt != null) return rig.muzzleAt.clone();
        Location c = boss.getLocation();
        double rad = Math.toRadians(boss.getBodyYaw());
        return c.clone().add(-Math.sin(rad) * 0.45 * size - Math.cos(rad) * 0.35 * size, 1.15 * size, Math.cos(rad) * 0.45 * size - Math.sin(rad) * 0.35 * size);
    }

    private void laser(Location from, Vector dir, double len, float thick) {
        RayTraceResult r = from.getWorld().rayTraceBlocks(from, dir, len, FluidCollisionMode.NEVER, true);
        double l = r == null ? len : r.getHitPosition().distance(from.toVector());
        Particle.DustOptions d = new Particle.DustOptions(RED, thick);
        for (double s = 0.5; s < l; s += 0.6) from.getWorld().spawnParticle(Particle.DUST, from.clone().add(dir.clone().multiply(s)), 1, 0, 0, 0, 0, d, true);
    }

    /** Un tir : touche le premier joueur sur la ligne (un peu large). */
    private void shoot(Location from, Vector dir, double len, double dmg) {
        World w = from.getWorld();
        sound(from, "entity.firework_rocket.blast", 2f, 0.6f);
        sound(from, "block.dispenser.launch", 1.5f, 1.8f);
        RayTraceResult blocks = w.rayTraceBlocks(from, dir, len, FluidCollisionMode.NEVER, true);
        double l = blocks == null ? len : blocks.getHitPosition().distance(from.toVector());
        Player victim = null;
        double best = l;
        for (Player p : nearby(from, len + 2)) {
            var box = p.getBoundingBox().expand(0.35);
            RayTraceResult h = box.rayTrace(from.toVector(), dir, l);
            if (h != null) {
                double dd = h.getHitPosition().distance(from.toVector());
                if (dd < best) {
                    best = dd;
                    victim = p;
                }
            }
        }
        Particle.DustOptions tr = new Particle.DustOptions(YELLOW, 0.6f);
        for (double s = 0.8; s < best; s += 1.2) w.spawnParticle(Particle.DUST, from.clone().add(dir.clone().multiply(s)), 1, 0, 0, 0, 0, tr, true);
        w.spawnParticle(Particle.FLASH, from, 1, 0, 0, 0, 0, Color.fromRGB(255, 200, 120), true);
        Location end = from.clone().add(dir.clone().multiply(best));
        w.spawnParticle(Particle.CRIT, end, 6, 0.1, 0.1, 0.1, 0.2, null, true);
        if (victim != null) hit(victim, dmg * mult());
    }

    /** Missiles : des cercles rouges au sol, puis les missiles tombent du ciel. */
    private void missiles() {
        announce("missiles");
        List<Player> ps = nearby(boss.getLocation(), 40);
        if (ps.isEmpty()) return;
        sound(boss.getLocation(), "entity.firework_rocket.launch", 3f, 0.5f);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int count = (int) cfg("missiles", 5) + phase * 2 + (enraged ? 3 : 0);
        double rad = 2.6;
        for (int k = 0; k < count; k++) {
            Player p = ps.get(k % ps.size());
            Location at = ground(p.getLocation().add(k < ps.size() ? 0 : r.nextDouble(-6, 6), 0, k < ps.size() ? 0 : r.nextDouble(-6, 6)));
            int delay = 6 + k * 4;
            later(delay, () -> {
                ring(at, rad, RED, 30);
                // le missile tombe
                Location sky = at.clone().add(r.nextDouble(-4, 4), 22, r.nextDouble(-4, 4));
                repeat(1, 30, i -> {
                    double u = (i + 1) / 30.0;
                    Location l = sky.clone().add(at.clone().subtract(sky).toVector().multiply(u));
                    if (i > 12) {
                        at.getWorld().spawnParticle(Particle.FLAME, l, 2, 0.05, 0.05, 0.05, 0.01, null, true);
                        at.getWorld().spawnParticle(Particle.SMOKE, l, 2, 0.1, 0.1, 0.1, 0.01, null, true);
                    }
                }, () -> explode(at, rad, cfg("damage.missile", 8)));
            });
        }
    }

    private void explode(Location at, double r, double dmg) {
        World w = at.getWorld();
        w.spawnParticle(Particle.EXPLOSION, at.clone().add(0, 0.5, 0), 3, r * 0.4, 0.3, r * 0.4, 0, null, true);
        w.spawnParticle(Particle.FLAME, at.clone().add(0, 0.3, 0), 25, r * 0.4, 0.2, r * 0.4, 0.06, null, true);
        sound(at, "entity.generic.explode", 2.5f, 0.9f);
        WeaponFx fx = fx();
        if (fx != null) fx.boom(at.clone().add(0, 0.8, 0), RED, (float) (r * 1.2));
        for (Player p : nearby(at, r)) {
            hit(p, dmg * mult());
            Vector kb = p.getLocation().toVector().subtract(at.toVector()).setY(0);
            if (kb.lengthSquared() < 0.01) kb = new Vector(0.1, 0, 0);
            p.setVelocity(kb.normalize().multiply(0.6).setY(0.5));
        }
    }

    /** Charge : une ligne s'allume au sol vers la cible, puis il fonce dedans. */
    private void charge() {
        Player t = randomTarget(30);
        if (t == null) return;
        announce("charge");
        boss.setAI(false);
        Location from = boss.getLocation();
        Vector dir = t.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (dir.lengthSquared() < 1) dir = from.getDirection().setY(0);
        dir.normalize();
        double len = Math.min(cfg("charge-length", 20), Math.max(8, t.getLocation().distance(from) + 5));
        face(from.clone().add(dir));
        Vector d = dir;
        Particle.DustOptions dust = new Particle.DustOptions(RED, 1.6f);
        sound(from, "entity.ravager.roar", 2f, 0.9f);
        repeat(4, 6, i -> {
            for (double s = 1; s < len; s += 0.7) {
                Location l = from.clone().add(d.clone().multiply(s));
                Vector side = new Vector(-d.getZ(), 0, d.getX());
                for (int k = -1; k <= 1; k += 2) {
                    from.getWorld().spawnParticle(Particle.DUST, ground(l.clone().add(side.clone().multiply(k * 1.3))).add(0, 0.15, 0), 1, 0, 0, 0, 0, dust, true);
                }
            }
        }, () -> {
            if (boss == null || !boss.isValid()) return;
            Set<UUID> hitSet = new HashSet<>();
            int steps = (int) Math.ceil(len / 1.4);
            sound(boss.getLocation(), "entity.ravager.attack", 3f, 0.6f);
            repeat(1, steps, i -> {
                if (boss == null || !boss.isValid()) return;
                Location l = boss.getLocation().add(d.clone().multiply(1.4));
                l.setY(altar.getY() + 1);
                l.setDirection(d);
                if (!l.clone().add(0, 1, 0).getBlock().isPassable()) return;
                boss.teleport(keepIn(l, cfg("arena-radius", 24) - 1));
                boss.getWorld().spawnParticle(Particle.LARGE_SMOKE, boss.getLocation(), 4, 0.4, 0.2, 0.4, 0.02, null, true);
                for (Player p : nearby(boss.getLocation().add(0, 1, 0), 2.6)) {
                    if (!hitSet.add(p.getUniqueId())) continue;
                    hit(p, cfg("damage.charge", 11) * mult());
                    p.setVelocity(d.clone().multiply(1.6).setY(0.7));
                    sound(p.getLocation(), "entity.player.attack.knockback", 2f, 0.6f);
                }
            }, () -> {
                if (boss != null && boss.isValid()) boss.setAI(true);
            });
        });
    }

    /** Écrasement : il saute très haut et retombe sur la position marquée. */
    private void slam() {
        Player t = randomTarget(30);
        if (t == null) return;
        announce("slam");
        boss.setAI(false);
        airborne = true;
        Location start = boss.getLocation();
        Location dest = ground(keepIn(t.getLocation(), cfg("arena-radius", 24) - 2));
        double r = 6.5;
        ring(dest, r, MAGENTA, 32);
        rig.raiseTarget = 1;
        sound(start, "entity.iron_golem.attack", 3f, 0.5f);
        int air = 32;
        repeat(1, air, i -> {
            if (boss == null || !boss.isValid()) return;
            double u = (i + 1) / (double) air;
            Location l = start.clone().add(dest.clone().subtract(start).toVector().multiply(u)).add(0, Math.sin(Math.PI * u) * 12, 0);
            boss.teleport(l);
            boss.getWorld().spawnParticle(Particle.FLAME, l, 3, 0.2, 0, 0.2, 0.02, null, true);
        }, () -> {
            airborne = false;
            if (boss == null || !boss.isValid()) return;
            boss.teleport(dest);
            rig.raiseTarget = 0;
            rig.kneelTarget = 0.35;
            later(12, () -> rig.kneelTarget = 0);
            shockwave(dest, r, 1.3, cfg("damage.slam", 13), MAGENTA);
            later(10, () -> {
                if (boss != null && boss.isValid()) boss.setAI(true);
            });
        });
    }

    /** IEM : un cercle cyan grandit autour de lui ; ceux qui y restent ont les implants coupés. */
    private void emp() {
        announce("emp");
        boss.setAI(false);
        Location c = boss.getLocation();
        double r = cfg("emp-radius", 13);
        ring(ground(c), r, CYAN, 40);
        rig.raiseTarget = 0.55;
        sound(c, "block.beacon.power_select", 3f, 1.6f);
        repeat(5, 8, i -> {
            c.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, c.clone().add(0, size, 0), 20, 0.6, 0.8, 0.6, 0.4, null, true);
            sound(c, "block.note_block.bit", 2f, 0.6f + i * 0.15f);
        }, () -> {
            if (boss == null || !boss.isValid()) return;
            rig.raiseTarget = 0;
            sound(c, "entity.warden.sonic_boom", 3f, 1.3f);
            WeaponFx fx = fx();
            if (fx != null) fx.wave(c.clone().add(0, 0.4, 0), "w_shock_flat", CYAN, 1, (float) (r * 2.2), 8, null, Display.Billboard.FIXED);
            for (int k = 0; k < 60; k++) {
                double a = Math.PI * 2 * k / 60;
                c.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, c.clone().add(Math.cos(a) * r, 0.6, Math.sin(a) * r), 2, 0.1, 0.3, 0.1, 0.05, null, true);
            }
            int secs = (int) cfg("emp-seconds", 6);
            for (Player p : nearby(c, r)) {
                hit(p, cfg("damage.emp", 5) * mult());
                if (!TojiSpear.ccImmune(p)) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, secs * 20, 1, false, true, true));
                    p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 25, 0, false, false, false));
                }
                Cyberware.emp(p, secs * 20);
                SmpBoss.hud(p, parse(msg("emp-hit").replace("<seconds>", String.valueOf(secs))));
            }
            later(12, () -> {
                if (boss != null && boss.isValid()) boss.setAI(true);
            });
        });
    }

    /** Renforts : des soldats arrivent depuis le bord du toit. */
    private void troops() {
        minions.removeIf(m -> !m.isValid() || m.isDead());
        int max = (int) cfg("troops-max", 8);
        if (minions.size() >= max) {
            burst();
            return;
        }
        announce("troops");
        int n = Math.min(max - minions.size(), (int) cfg("troops", 2) + phase);
        double ar = cfg("arena-radius", 24) - 3;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        sound(boss.getLocation(), "event.raid.horn", 3f, 1.2f);
        for (int k = 0; k < n; k++) {
            double a = r.nextDouble(Math.PI * 2);
            Location at = ground(altar.clone().add(0.5 + Math.cos(a) * ar, 1, 0.5 + Math.sin(a) * ar));
            at.getWorld().spawnParticle(Particle.PORTAL, at.clone().add(0, 1, 0), 30, 0.3, 0.8, 0.3, 0.3, null, true);
            Pillager s = at.getWorld().spawn(at, Pillager.class, z -> {
                z.setPersistent(false);
                z.setRemoveWhenFarAway(false);
                z.setPatrolLeader(false);
                z.setCanJoinRaid(false);
                z.getPersistentDataContainer().set(minionKey, PersistentDataType.BYTE, (byte) 1);
                z.customName(parse(msg("trooper")));
                z.setCustomNameVisible(false);
                base(z, Attribute.MAX_HEALTH, cfg("trooper-health", 30));
                z.setHealth(cfg("trooper-health", 30));
                z.setCanPickupItems(false);
                EntityEquipment eq = z.getEquipment();
                if (eq != null) {
                    eq.setItemInMainHand(new ItemStack(Material.CROSSBOW));
                    eq.setHelmet(new ItemStack(Material.IRON_HELMET));
                    eq.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
                    eq.setItemInMainHandDropChance(0);
                    eq.setHelmetDropChance(0);
                    eq.setChestplateDropChance(0);
                }
            });
            minions.add(s);
        }
    }

    /** Sauts Kerenzikov (phase 3) : il disparaît, réapparaît derrière un joueur, et frappe après un court signal. */
    private void blink() {
        List<Player> ps = nearby(boss.getLocation(), 30);
        if (ps.isEmpty()) return;
        announce("blink");
        boss.setAI(false);
        int jumps = enraged ? 4 : 3;
        repeat(18, jumps, i -> {
            if (boss == null || !boss.isValid()) return;
            Player t = ps.get(ThreadLocalRandom.current().nextInt(ps.size()));
            if (!t.isOnline() || t.isDead()) return;
            Location old = boss.getLocation();
            afterimage(old);
            Vector back = t.getLocation().getDirection().setY(0);
            if (back.lengthSquared() < 0.01) back = new Vector(1, 0, 0);
            Location to = ground(keepIn(t.getLocation().subtract(back.normalize().multiply(2.2)), cfg("arena-radius", 24) - 1));
            to.setDirection(t.getLocation().toVector().subtract(to.toVector()));
            boss.teleport(to);
            sound(to, "entity.enderman.teleport", 2f, 0.6f);
            boss.getWorld().spawnParticle(Particle.DUST, to.clone().add(0, size, 0), 20, 0.3 * size, 0.6 * size, 0.3 * size, 0,
                    new Particle.DustOptions(CYAN, 1.2f), true);
            ring(to, 3, RED, 9);
            later(9, () -> {
                if (boss == null || !boss.isValid()) return;
                Location c = boss.getLocation();
                sound(c, "entity.player.attack.sweep", 2f, 0.6f);
                c.getWorld().spawnParticle(Particle.SWEEP_ATTACK, c.clone().add(0, size * 0.8, 0), 4, 1.2, 0.3, 1.2, 0, null, true);
                for (Player p : nearby(c, 3.2)) {
                    hit(p, cfg("damage.blink", 9) * mult());
                    Vector kb = p.getLocation().toVector().subtract(c.toVector()).setY(0);
                    if (kb.lengthSquared() < 0.01) kb = new Vector(0.1, 0, 0);
                    p.setVelocity(kb.normalize().multiply(1.1).setY(0.45));
                }
            });
        }, () -> later(12, () -> {
            if (boss != null && boss.isValid()) boss.setAI(true);
        }));
    }

    /** Image rémanente (vert / magenta, façon Sandevistan) là où il était. */
    private void afterimage(Location at) {
        World w = at.getWorld();
        for (double y = 0.2; y < size * 1.8; y += 0.35) {
            w.spawnParticle(Particle.DUST, at.clone().add(0.15, y, 0), 2, 0.18 * size, 0.05, 0.18 * size, 0, new Particle.DustOptions(MAGENTA, 1.1f), true);
            w.spawnParticle(Particle.DUST, at.clone().add(-0.15, y, 0), 2, 0.18 * size, 0.05, 0.18 * size, 0, new Particle.DustOptions(Color.fromRGB(60, 255, 120), 1.1f), true);
        }
    }

    private void tickMinions() {
        if (tick % 20 != 0) return;
        minions.removeIf(m -> !m.isValid() || m.isDead());
        double ar = cfg("arena-radius", 24);
        for (Entity m : minions) {
            if (!(m instanceof Mob mob)) continue;
            if (horizontalDist(m.getLocation(), altar) > ar + 4 || m.getLocation().getY() < altar.getY() - 6) mob.teleport(ground(keepIn(m.getLocation(), ar - 2)));
            Player t = nearest(m.getLocation(), 32);
            if (t != null) mob.setTarget(t);
        }
    }

    // ================================================================= fin

    private void defeat() {
        if (ended) return;
        ended = true;
        Location c = boss.getLocation().clone();
        boss.setAI(false);
        titleAll("title-defeat", "subtitle-defeat");
        for (Entity m : minions) m.remove();
        minions.clear();
        rig.aimTarget = 0;
        rig.raiseTarget = 0;
        rig.kneelTarget = 1;
        sound(c, "entity.iron_golem.death", 4f, 0.4f);
        BukkitTask[] task = new BukkitTask[1];
        int[] i = {0};
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = i[0]++;
            if (k >= 50 || boss == null || !boss.isValid()) {
                task[0].cancel();
                c.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, c, 3, 1, 1, 1, 0, null, true);
                WeaponFx fx = fx();
                if (fx != null) fx.boom(c.clone().add(0, 1.5, 0), RED, 8);
                fireworks(c);
                cleanup();
                rewards();
                plugin.smasherEnded();
                return;
            }
            // il grésille, tombe à genoux, explose
            rig.update(boss.getLocation(), boss.getBodyYaw(), size / 2.0);
            Location l = boss.getLocation().add(0, size, 0);
            l.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, l, 12, 0.4 * size, 0.6 * size, 0.4 * size, 0.3, null, true);
            if (k % 6 == 0) {
                l.getWorld().spawnParticle(Particle.EXPLOSION, l, 1, 0.5, 0.5, 0.5, 0, null, true);
                sound(l, "entity.generic.explode", 1.5f, 1.4f);
            }
        }, 1L, 1L);
    }

    private void rewards() {
        double total = 0;
        for (double v : damage.values()) total += v;
        double minPct = minPct();
        List<Map.Entry<UUID, Double>> sorted = new ArrayList<>(damage.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        Bukkit.broadcast(parse(msg("defeated")));
        long secs = tick / 20;
        Bukkit.broadcast(parse(plugin.msg("boss.fight-time").replace("<time>", secs / 60 + " min " + String.format("%02d", secs % 60) + " s")));
        for (int i = 0; i < Math.min(3, sorted.size()); i++) {
            var e = sorted.get(i);
            String name = Bukkit.getOfflinePlayer(e.getKey()).getName();
            Bukkit.broadcast(parse(plugin.msg("boss.top-line").replace("<rank>", String.valueOf(i + 1))
                    .replace("<player>", SmpBoss.MM.escapeTags(name == null ? "?" : name))
                    .replace("<percent>", String.valueOf(Math.round(e.getValue() * 100 / Math.max(1, total))))));
        }
        GuildTop gt = plugin.guildTop();
        if (gt != null) gt.announce(gt.record(damage, true), total);
        questProgress(true, total * minPct);
        for (var e : sorted) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            if (e.getValue() < total * minPct) {
                p.sendMessage(parse(plugin.msg("boss.not-enough").replace("<percent>",
                        String.format(java.util.Locale.ROOT, "%.1f", e.getValue() * 100 / Math.max(1, total)))
                        .replace("<min>", String.valueOf(Math.round(minPct * 100)))));
                continue;
            }
            plugin.loot().reward(p, "smasher-loot.");
            if (Bukkit.getPluginManager().isPluginEnabled("SmpTitles")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "titre win " + p.getName() + " smasher");
            }
        }
        plugin.store().save(true);
    }

    public void leave(String msgKey) {
        if (ended) return;
        ended = true;
        if (boss != null && boss.isValid()) {
            Location c = boss.getLocation();
            c.getWorld().spawnParticle(Particle.LARGE_SMOKE, c, 80, 1.5, 2, 1.5, 0.05, null, true);
        }
        Bukkit.broadcast(parse(plugin.msg(msgKey)));
        if (!"smasher.stopped".equals(msgKey) && !damage.isEmpty() && plugin.guildTop() != null) plugin.guildTop().record(damage, false);
        if (!"smasher.stopped".equals(msgKey)) questProgress(false, 0);
        cleanup();
        plugin.smasherEnded();
    }

    /** Quêtes de SmpEvents : dégâts au boss, victoire (cible SMASHER). */
    private void questProgress(boolean won, double minDamage) {
        if (!Bukkit.getPluginManager().isPluginEnabled("SmpEvents")) return;
        for (var e : damage.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            long dmg = Math.round(e.getValue());
            if (dmg > 0) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "smpevents progress " + p.getName() + " BOSS_DAMAGE " + dmg + " SMASHER");
            if (won && e.getValue() >= minDamage) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "smpevents progress " + p.getName() + " BOSS_WIN 1 SMASHER");
        }
    }

    private double extraShare() {
        return Math.max(0, cfg("extra-player-health", 0.7));
    }

    private double hpFor(int n) {
        return perPlayer() * (1 + extraShare() * Math.max(0, n - 1));
    }

    private double perPlayer() {
        return Math.max(1, cfg("health-per-player", 4500));
    }

    private double minPct() {
        return plugin.getConfig().getDouble("loot.min-damage-percent", 5) / 100.0;
    }

    public void cleanup() {
        if (board != null) {
            board.close();
            board = null;
        }
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        if (bar != null) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
        for (Entity e : props) e.remove();
        props.clear();
        for (Entity m : minions) m.remove();
        minions.clear();
        if (rig != null) rig.remove();
        if (boss != null) boss.remove();
    }

    // ================================================================= outils

    public boolean inFight(Player p) {
        return !ended && p.getWorld() == altar.getWorld() && horizontalDist(p.getLocation(), altar) <= cfg("arena-radius", 24) + 12
                && Math.abs(p.getLocation().getY() - altar.getY()) < 30;
    }

    private double mult() {
        return cfg("damage.multiplier", 1.8) * (enraged ? 1.25 : 1.0) * (phase == 3 ? cfg("damage.phase-3-multiplier", 1.3)
                : phase == 2 ? cfg("damage.phase-2-multiplier", 1.4) : cfg("damage.phase-1-multiplier", 1.25));
    }

    private void hit(Player p, double amount) {
        if (boss == null || p.isDead()) return;
        Arena ar = bossArena();
        if (ar != null && ar.isProtected(p)) return;
        double pierce = Math.max(0, Math.min(1, cfg("damage.armor-pierce", 0.3)));
        double normal = amount * (1 - pierce);
        if (normal > 0) p.damage(normal, boss);
        if (pierce > 0 && !p.isDead()) {
            p.setNoDamageTicks(0);
            p.damage(amount * pierce, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.MAGIC)
                    .withCausingEntity(boss).withDirectEntity(boss).build());
        }
    }

    private Player randomTarget(double r) {
        if (boss == null) return null;
        List<Player> ps = nearby(boss.getLocation(), r);
        return ps.isEmpty() ? null : ps.get(ThreadLocalRandom.current().nextInt(ps.size()));
    }

    private Player nearest(Location c, double r) {
        Player best = null;
        double bd = r * r;
        for (Player p : nearby(c, r)) {
            double d = p.getLocation().distanceSquared(c);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private void face(Location target) {
        if (boss == null) return;
        Vector d = target.toVector().subtract(boss.getLocation().toVector()).setY(0);
        if (d.lengthSquared() < 0.01) return;
        boss.setRotation((float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ())), 0);
    }

    private void announce(String key) {
        if (boss == null) return;
        String m = msg("attack." + key);
        if (m.startsWith("<red>[")) return;
        for (Player p : nearby(boss.getLocation(), 50)) SmpBoss.hud(p, parse(m));
    }

    private static void base(LivingEntity z, Attribute a, double v) {
        AttributeInstance i = z.getAttribute(a);
        if (i != null) i.setBaseValue(v);
    }

    private static double rnd(double r) {
        return ThreadLocalRandom.current().nextDouble(-r, r);
    }

    private Location keepIn(Location l, double maxR) {
        Location mid = altar.clone().add(0.5, 0, 0.5);
        double dx = l.getX() - mid.getX(), dz = l.getZ() - mid.getZ();
        double d = Math.hypot(dx, dz);
        Location out = l.clone();
        if (d > maxR && d > 0.01) {
            out.setX(mid.getX() + dx / d * maxR);
            out.setZ(mid.getZ() + dz / d * maxR);
        }
        return out;
    }

    private static double horizontalDist(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    private Location ground(Location l) {
        double base = altar.getY() + 1;
        Location probe = l.clone();
        probe.setY(base + 3);
        for (int i = 0; i < 10 && probe.getBlock().isPassable(); i++) probe.add(0, -1, 0);
        Location out = l.clone();
        out.setY(!probe.getBlock().isPassable() ? probe.getBlockY() + 1 : base);
        return out;
    }

    private List<Player> nearby(Location c, double r) {
        List<Player> out = new ArrayList<>();
        for (Player p : c.getWorld().getPlayers()) {
            if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) continue;
            if (p.getLocation().distanceSquared(c) <= r * r) out.add(p);
        }
        return out;
    }

    /** Cercle d'avertissement au sol (anneau du pack + particules pour tous). */
    private void ring(Location c, double r, Color color, int ticks) {
        WeaponFx fx = fx();
        if (fx != null) fx.tpop(c.clone().add(0, 0.12, 0), "w_ring_flat", color, (float) (r * 2.25), 1, (float) (r * 2.25), 3, Math.max(1, ticks - 6), 3,
                Display.Billboard.FIXED, null);
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.5f);
        int pts = (int) Math.max(18, r * 10);
        repeat(3, Math.max(1, ticks / 3), i -> {
            for (int k = 0; k < pts; k++) {
                double a = Math.PI * 2 * k / pts;
                c.getWorld().spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * r, 0.15, Math.sin(a) * r), 1, 0, 0, 0, 0, dust, true);
            }
            double inner = r * (1 - (i + 1.0) / Math.max(1, ticks / 3));
            for (int k = 0; k < pts / 2; k++) {
                double a = Math.PI * 4 * k / pts;
                c.getWorld().spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * inner, 0.15, Math.sin(a) * inner), 1, 0, 0, 0, 0, dust, true);
            }
        }, null);
    }

    private void borderRing() {
        double arena = cfg("arena-radius", 24);
        int pts = (int) (arena * 4);
        int off = tick / 10;
        Particle.DustOptions d = new Particle.DustOptions(phase == 3 ? RED : MAGENTA, 1.0f);
        for (int k = 0; k < pts; k++) {
            if ((k + off) % 2 != 0) continue;
            double a = Math.PI * 2 * k / pts;
            Location l = ground(altar.clone().add(0.5 + Math.cos(a) * arena, 0, 0.5 + Math.sin(a) * arena)).add(0, 0.2, 0);
            altar.getWorld().spawnParticle(Particle.DUST, l, 1, 0.05, 0.1, 0.05, 0, d, true);
        }
    }

    private void shockwave(Location c, double r, double push, double dmg, Color col) {
        sound(c, "entity.generic.explode", 3f, 0.7f);
        shake(c, r * 2.5);
        WeaponFx sfx = fx();
        if (sfx != null) {
            sfx.wave(c.clone().add(0, 0.2, 0), "w_shock_flat", col, 1, (float) (r * 2.2), 10, null, Display.Billboard.FIXED);
            sfx.boom(c.clone().add(0, 1.5, 0), col, (float) Math.max(4, r));
        }
        repeat(1, 10, i -> {
            double rr = r * (i + 1) / 10.0;
            int pts = (int) Math.max(24, rr * 8);
            for (int k = 0; k < pts; k++) {
                double a = Math.PI * 2 * k / pts;
                c.getWorld().spawnParticle(Particle.CLOUD, c.clone().add(Math.cos(a) * rr, 0.3, Math.sin(a) * rr), 1, 0, 0.05, 0, 0.01, null, true);
                if (k % 3 == 0) c.getWorld().spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * rr, 0.5, Math.sin(a) * rr), 1, 0, 0.1, 0, 0,
                        new Particle.DustOptions(col, 1.2f), true);
            }
        }, null);
        for (Player p : nearby(c, r)) {
            if (dmg > 0) hit(p, dmg * mult());
            Vector kb = p.getLocation().toVector().subtract(c.toVector()).setY(0);
            if (kb.lengthSquared() < 0.01) kb = new Vector(0.1, 0, 0);
            p.setVelocity(kb.normalize().multiply(push).setY(0.55));
        }
    }

    private void shake(Location c, double r) {
        Weapons w = plugin.weapons();
        if (w != null) w.ultimates.quake(c, r, 12, 3.5f);
    }

    private void damageNumber(double amount) {
        if (boss == null || tick == lastNumber && amount < 4) return;
        lastNumber = tick;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location l = boss.getLocation().add(r.nextDouble(-0.6, 0.6) * size, size * (1.4 + r.nextDouble(0.5)), r.nextDouble(-0.6, 0.6) * size);
        String txt = (amount >= 15 ? "<gradient:#ff2a6d:#05d9e8><b>" : amount >= 8 ? "<#ff2a6d><b>" : "<#ffc1d6>")
                + "-" + (amount >= 10 ? String.valueOf(Math.round(amount)) : String.format(java.util.Locale.ROOT, "%.1f", amount));
        TextDisplay d = l.getWorld().spawn(l, TextDisplay.class, e -> {
            e.setPersistent(false);
            e.setBillboard(Display.Billboard.CENTER);
            e.text(parse(txt));
            e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            e.setShadowed(true);
            e.setTeleportDuration(14);
            float s = amount >= 15 ? 1.6f : 1.2f;
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
        });
        props.add(d);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (d.isValid()) d.teleport(l.clone().add(0, 1.4, 0));
        }, 1L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            d.remove();
            props.remove(d);
        }, 16L);
    }

    private void repeat(int period, int times, IntConsumer step, Runnable end) {
        int[] i = {0};
        BukkitTask[] self = new BukkitTask[1];
        self[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (ended) {
                self[0].cancel();
                return;
            }
            if (i[0] >= times) {
                self[0].cancel();
                if (end != null) end.run();
                return;
            }
            try {
                step.accept(i[0]);
            } catch (Throwable t) {
                plugin.getLogger().warning("Erreur pendant une attaque d'Adam Smasher : " + t);
            }
            i[0]++;
        }, 0L, period);
        tasks.add(self[0]);
    }

    private void later(int ticks, Runnable r) {
        tasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!ended) r.run();
        }, Math.max(1, ticks)));
    }

    private static void sound(Location l, String key, float volume, float pitch) {
        l.getWorld().playSound(Sound.sound(Key.key("minecraft", key), Sound.Source.HOSTILE, volume, pitch), l.getX(), l.getY(), l.getZ());
    }

    private void titleAll(String title, String sub) {
        Location c = boss != null ? boss.getLocation() : altar;
        for (Player p : nearby(c, 60)) {
            p.showTitle(Title.title(parse(msg(title)), parse(msg(sub)),
                    Title.Times.times(java.time.Duration.ofMillis(300), java.time.Duration.ofMillis(2500), java.time.Duration.ofMillis(600))));
        }
    }

    private void fireworks(Location c) {
        for (int i = 0; i < 6; i++) {
            Location l = c.clone().add(ThreadLocalRandom.current().nextDouble(-3, 3), 1, ThreadLocalRandom.current().nextDouble(-3, 3));
            c.getWorld().spawn(l, Firework.class, f -> {
                FireworkMeta m = f.getFireworkMeta();
                m.addEffect(FireworkEffect.builder().withColor(MAGENTA, CYAN).withFade(Color.BLACK)
                        .with(FireworkEffect.Type.BALL_LARGE).trail(true).flicker(true).build());
                m.setPower(1);
                f.setFireworkMeta(m);
            });
        }
    }
}
