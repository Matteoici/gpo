package fr.smpboss;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.CaveSpider;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Spider;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
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
 * La Reine Araignée : deuxième boss d'Halloween, dans sa propre arène noir / bleu.
 * Un corps animé (abdomen, céphalothorax, 8 pattes en 2 segments qui marchent vraiment) posé sur une vraie araignée
 * invisible (la boîte de collision). 3 phases, attaques toujours annoncées au sol, et deux attaques spéciales :
 * la Toile Abyssale (phase 2 : briser 4 cocons avant l'Étreinte) et l'Éclipse des Mille Yeux (phase 3).
 */
public final class SpiderQueen implements Boss {

    private enum Attack { WEB_SHOT, POUNCE, VENOM_RAIN, BROOD, LEG_SWEEP, SILK_PULL, SHADOW_DASH, CEILING_DROP, FANG_NOVA }

    static final Color WEB = Color.fromRGB(225, 235, 255), ABYSS = Color.fromRGB(40, 90, 255), VENOM = Color.fromRGB(70, 235, 130),
            DEEP = Color.fromRGB(20, 30, 90), EYE = Color.fromRGB(90, 200, 255), RED_EYE = Color.fromRGB(255, 50, 70);

    private final SmpBoss plugin;
    private final Location altar;
    private final NamespacedKey bossKey, minionKey;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final Set<Entity> props = new HashSet<>();
    private final Set<Entity> minions = new HashSet<>();
    private final Map<UUID, Double> damage = new HashMap<>();
    private final Set<Block> webs = new HashSet<>();

    private Spider boss;
    private Rig rig;
    private BossBar bar;
    private DamageBoard board;
    private double hp, maxHp;
    private int fighters, phase = 1, tick, invulnerable, nextAttack = 90, lonely, lastNumber;
    private Attack last;
    private boolean ended, enraged, webDone, eclipseDone;
    /** pendant une attaque "aérienne" : la laisse au sol est coupée, le corps est déplacé à la main */
    private boolean airborne;
    /** coups encaissés x1,5 (la toile a cédé) */
    private int stunned;
    /** Toile Abyssale en cours : la Reine ne prend aucun dégât tant qu'il reste un cocon */
    private boolean shielded;
    private final Map<Entity, int[]> anchors = new HashMap<>();
    private float size;

    SpiderQueen(SmpBoss plugin, Location altar) {
        this.plugin = plugin;
        this.altar = altar.clone();
        this.bossKey = new NamespacedKey(plugin, "spider_boss");
        this.minionKey = new NamespacedKey(plugin, "spider_minion");
    }

    // ================================================================= Boss

    public Arena bossArena() {
        return plugin.spiderArena();
    }

    public String kind() {
        return "reine";
    }

    public boolean isBoss(Entity e) {
        return e != null && (e == boss || hitboxes.contains(e));
    }

    @Override
    public LivingEntity mainEntity() {
        return boss;
    }

    // ================================================================= zones de coups (tête + abdomen)

    /** Deux slimes invisibles collés à la tête et à l'abdomen : on touche la Reine là où on la voit (mêlée et flèches). */
    private final List<Slime> hitboxes = new ArrayList<>();

    private Slime hitbox(Location at) {
        return at.getWorld().spawn(at, Slime.class, z -> {
            z.setPersistent(false);
            z.setSize(4);
            z.setAI(false);
            z.setSilent(true);
            z.setGravity(false);
            z.setCollidable(false);
            z.setRemoveWhenFarAway(false);
            z.addScoreboardTag("smp_boss_hitbox");
            z.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 3);
            z.customName(parse(msg("name")));
            z.setCustomNameVisible(false);
            base(z, Attribute.MAX_HEALTH, 1024);
            z.setHealth(1024);
            base(z, Attribute.KNOCKBACK_RESISTANCE, 1);
            z.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        });
    }

    /** Chaque tick : les zones suivent la tête et l'abdomen, à leur taille. */
    private void moveHitboxes() {
        if (rig == null || rig.headAt == null || boss == null) return;
        if (hitboxes.isEmpty()) {
            hitboxes.add(hitbox(rig.abdAt));
            hitboxes.add(hitbox(rig.headAt));
        }
        double S = size;
        double[] widths = {1.25 * S, 0.9 * S};
        Location[] at = {rig.abdAt, rig.headAt};
        for (int i = 0; i < 2; i++) {
            Slime z = hitboxes.get(i);
            if (!z.isValid()) {
                z = hitbox(at[i]);
                hitboxes.set(i, z);
            }
            double scale = Math.min(16, widths[i] / 2.08);
            AttributeInstance a = z.getAttribute(Attribute.SCALE);
            if (a != null && Math.abs(a.getBaseValue() - scale) > 0.01) a.setBaseValue(scale);
            double h = 2.08 * scale;
            Location l = at[i].clone().add(0, -h / 2, 0);
            if (rig.hidden) l.add(0, -200, 0); // éclipse : elle a disparu
            l.setYaw(0);
            l.setPitch(0);
            z.teleport(l);
            z.setFireTicks(0);
        }
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

    boolean isAnchor(Entity e) {
        return e != null && anchors.containsKey(e);
    }

    private double cfg(String path, double def) {
        return plugin.getConfig().getDouble("spider." + path, def);
    }

    private String msg(String key) {
        return plugin.msg("spider." + key);
    }

    private double zs() {
        return Math.max(0.5, cfg("zone-scale", 1.4));
    }

    private WeaponFx fx() {
        Weapons w = plugin.weapons();
        return w == null ? null : w.ultimates.weaponFx;
    }

    // ================================================================= le corps (modèle animé)

    /**
     * 2 + 16 + 2 affichages : céphalothorax, abdomen, 8 pattes en 2 segments, 2 yeux (seulement sans le pack).
     * Objets vanilla (béton noir, lanterne marine) avec un custom_model_data : le pack les remplace par les vrais modèles.
     */
    private final class Rig {
        final ItemDisplay head, abdomen;
        final ItemDisplay[] legs = new ItemDisplay[16];
        final ItemDisplay[] eyes = new ItemDisplay[2];
        double clock;
        Location last;
        boolean hidden;
        /** -1 = accroupie, 0 = normal, 1 = cabrée (pattes avant levées) */
        double pose;
        double poseTarget;
        Color eyeColor = EYE;
        /** centres de la tête et de l'abdomen (pour les zones de coups) */
        Location headAt, abdAt;

        Rig(Location at) {
            head = part(at, Material.BLACK_CONCRETE, "spider_head");
            abdomen = part(at, Material.BLACK_CONCRETE, "spider_abdomen");
            for (int i = 0; i < 16; i++) legs[i] = part(at, Material.BLACK_CONCRETE, i % 2 == 0 ? "spider_leg_upper" : "spider_leg_lower");
            for (int i = 0; i < 2; i++) eyes[i] = part(at, Material.SEA_LANTERN, "spider_hide");
            last = at.clone();
        }

        private ItemDisplay part(Location at, Material m, String model) {
            ItemStack it = new ItemStack(m);
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
                e.setBrightness(new Display.Brightness(13, 15));
                e.setViewRange(4f);
                e.setShadowRadius(0);
            });
            props.add(d);
            return d;
        }

        void set(ItemDisplay d, Location at, Quaternionf rot, Vector3f scale) {
            if (!d.isValid()) return;
            Location l = at.clone();
            l.setYaw(0);
            l.setPitch(0);
            d.teleport(l);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(1);
            d.setTransformation(new Transformation(new Vector3f(), rot, hidden ? new Vector3f(0.001f, 0.001f, 0.001f) : scale, new Quaternionf()));
        }

        /** Place tout le corps : base = sous l'araignée, yaw = direction du regard, S = taille. */
        void update(Location base, float yaw, double S) {
            double moved = horizontalDist(base, last);
            last = base.clone();
            boolean moving = moved > 0.02;
            clock += moving ? Math.min(0.6, 0.18 + moved * 1.6) : 0.05;
            pose += (poseTarget - pose) * 0.25;
            double rad = Math.toRadians(yaw);
            Vector f = new Vector(-Math.sin(rad), 0, Math.cos(rad));
            Vector r = new Vector(-Math.cos(rad), 0, -Math.sin(rad));
            Vector up = new Vector(0, 1, 0);
            Quaternionf facing = new Quaternionf().rotateY((float) -rad);
            double crouch = Math.min(0, pose) * 0.45 * S, rear = Math.max(0, pose);
            Location B = base.clone().add(0, 0.8 * S + crouch + Math.sin(clock * 2) * 0.03 * S, 0);
            // céphalothorax (penché vers le haut quand elle se cabre), abdomen qui respire
            Quaternionf headRot = new Quaternionf(facing).rotateX((float) (-0.5 * rear));
            // la tête est collée au corps (elle chevauche l'abdomen) : plus de trou entre les deux, même à grande taille
            headAt = B.clone().add(f.clone().multiply(0.3 * S)).add(0, rear * 0.22 * S, 0);
            set(head, headAt, headRot, new Vector3f((float) (0.9 * S), (float) (0.66 * S), (float) (0.95 * S)));
            double breathe = 1 + Math.sin(tick * 0.12) * 0.03;
            abdAt = B.clone().add(f.clone().multiply(-0.74 * S)).add(0, 0.18 * S, 0);
            set(abdomen, abdAt, new Quaternionf(facing).rotateX(0.15f),
                    new Vector3f((float) (1.2 * S * breathe), (float) (1.0 * S * breathe), (float) (1.45 * S)));
            // yeux (sans pack : deux cubes lumineux devant)
            for (int s = 0; s < 2; s++) {
                Location e = B.clone().add(f.clone().multiply(0.78 * S)).add(r.clone().multiply((s == 0 ? 0.18 : -0.18) * S)).add(0, 0.15 * S + rear * 0.3 * S, 0);
                set(eyes[s], e, facing, new Vector3f((float) (0.16 * S), (float) (0.16 * S), (float) (0.08 * S)));
            }
            // pattes : 4 de chaque côté, marche en alternance
            int i = 0;
            for (int side = -1; side <= 1; side += 2) {
                for (int j = 0; j < 4; j++) {
                    Location hip = B.clone().add(f.clone().multiply((0.5 - 0.3 * j) * 0.6 * S)).add(r.clone().multiply(side * 0.32 * S));
                    double ph = clock + ((j % 2 == 0) ? 0 : Math.PI) + (side > 0 ? 0 : Math.PI);
                    double stride = moving ? 0.45 * S : 0.0, lift = moving ? Math.max(0, Math.cos(ph)) * 0.35 * S : 0;
                    double twitch = moving ? 0 : Math.sin(tick * 0.2 + j * 1.7 + side) * 0.04 * S;
                    Location foot = base.clone().add(f.clone().multiply((1.35 - 0.9 * j) * S + Math.sin(ph) * stride + twitch))
                            .add(r.clone().multiply(side * (1.95 + (j == 0 || j == 3 ? -0.15 : 0.1)) * S)).add(0, lift, 0);
                    // pattes avant levées quand elle se cabre
                    if (j == 0 && rear > 0.05) foot = foot.add(f.clone().multiply(0.3 * S * rear)).add(0, 1.4 * S * rear, 0);
                    if (j == 1 && rear > 0.05) foot = foot.add(0, 0.6 * S * rear, 0);
                    Location knee = hip.clone().add(foot.toVector().subtract(hip.toVector()).multiply(0.4)).add(up.clone().multiply(0.95 * S));
                    segment(legs[i++], hip, knee, 0.17 * S);
                    segment(legs[i++], knee, foot, 0.13 * S);
                }
            }
        }

        private void segment(ItemDisplay d, Location a, Location b, double thick) {
            Vector v = b.toVector().subtract(a.toVector());
            double len = v.length();
            if (len < 1e-3) return;
            Quaternionf q = new Quaternionf().rotationTo(new Vector3f(0, 1, 0), new Vector3f((float) v.getX(), (float) v.getY(), (float) v.getZ()).normalize());
            Location mid = a.clone().add(v.multiply(0.5));
            set(d, mid, q, new Vector3f((float) thick, (float) len, (float) thick));
        }

        void eyes(Color c) {
            eyeColor = c;
        }

        void remove() {
            head.remove();
            abdomen.remove();
            for (ItemDisplay d : legs) d.remove();
            for (ItemDisplay d : eyes) d.remove();
        }
    }

    // ================================================================= invocation

    public void start(Player summoner) {
        World w = altar.getWorld();
        Location top = altar.clone().add(0.5, 1, 0.5);
        String who = summoner == null ? "?" : summoner.getName();
        Bukkit.broadcast(parse(msg("summoning").replace("<player>", SmpBoss.MM.escapeTags(who))));
        for (Player p : nearby(top, 50)) p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 140, 0, false, false, false));
        csound(top, "spider.screech", 3f, 0.6f, "entity.spider.ambient");
        WeaponFx fx = fx();
        if (fx != null) {
            double ar = cfg("arena-radius", 18);
            ItemDisplay web = fx.t(top.clone().add(0, 0.05, 0), "w_web_flat", WEB, 0.5f, 1, 0.5f, Display.Billboard.FIXED, null);
            props.add(web);
            float s = (float) (ar * 2.1);
            later(1, () -> WeaponFx.tween(web, new Vector3f(), new Quaternionf().rotateY(0.8f), new Vector3f(s, 1, s), 80));
            later(150, () -> WeaponFx.tween(web, new Vector3f(), null, new Vector3f(0.05f, 1, 0.05f), 20));
            later(172, web::remove);
        }
        // fils de soie qui descendent du ciel, cœurs qui battent
        repeat(10, 12, i -> {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            for (int k = 0; k < 6; k++) {
                Location l = top.clone().add(r.nextDouble(-12, 12), 0, r.nextDouble(-12, 12));
                for (double y = 0; y < 14; y += 0.6) w.spawnParticle(Particle.DUST, l.clone().add(0, y, 0), 1, 0, 0, 0, 0, new Particle.DustOptions(WEB, 0.7f), true);
            }
            w.spawnParticle(Particle.SOUL, top, 20, 2 + i * 0.4, 0.2, 2 + i * 0.4, 0.02, null, true);
            sound(top, "entity.warden.heartbeat", 2f, 0.7f);
            if (i % 3 == 0) csound(top, "spider.skitter", 2f, 0.8f, "entity.spider.step");
            shake(top, 25);
        }, () -> spawnBoss(top));
    }

    private void spawnBoss(Location at) {
        World w = at.getWorld();
        fighters = Math.max(1, nearby(at, 45).size());
        maxHp = hpFor(fighters);
        hp = maxHp;
        if (plugin.getConfig().getBoolean("boss.damage-sidebar", true)) board = new DamageBoard(plugin);
        size = (float) cfg("size", 5.7);
        // elle descend du ciel au bout d'un fil
        Location sky = at.clone().add(0, 22, 0);
        boss = w.spawn(sky, Spider.class, z -> {
            z.setPersistent(false);
            z.setRemoveWhenFarAway(false);
            z.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 1);
            z.setAI(false);
            z.setSilent(true);
            z.setGravity(false);
            base(z, Attribute.MAX_HEALTH, 1024);
            z.setHealth(1024);
            base(z, Attribute.SCALE, size * cfg("body-hitbox", 0.45));
            base(z, Attribute.MOVEMENT_SPEED, cfg("speed", 0.3));
            base(z, Attribute.ATTACK_DAMAGE, cfg("melee-damage", 16));
            base(z, Attribute.KNOCKBACK_RESISTANCE, 1);
            base(z, Attribute.FOLLOW_RANGE, 48);
            z.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false));
            z.customName(parse(msg("name")));
            z.setCustomNameVisible(false);
        });
        rig = new Rig(sky);
        bar = BossBar.bossBar(parse(msg("bar-1")), 1f, BossBar.Color.BLUE, BossBar.Overlay.NOTCHED_10);
        csound(at, "spider.screech", 4f, 0.5f, "entity.spider.death");
        sound(at, "entity.wither.spawn", 2f, 1.4f);
        airborne = true;
        repeat(1, 50, i -> {
            if (boss == null || !boss.isValid()) return;
            double u = (i + 1) / 50.0, e = 1 - Math.pow(1 - u, 3);
            Location l = sky.clone().add(0, -22 * e, 0);
            boss.teleport(l);
            if (i % 2 == 0) thread(l.clone().add(0, size * 1.2, 0), sky.clone().add(0, 8, 0));
        }, () -> {
            airborne = false;
            if (boss == null || !boss.isValid()) return;
            boss.setGravity(true);
            boss.setAI(true);
            titleAll("title-start", "subtitle-start");
            shockwave(at, 12, 1.1, 0);
        });
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L));
    }

    /** Fil de soie entre deux points (blanc, visible par tous). */
    private void thread(Location a, Location b) {
        Vector v = b.toVector().subtract(a.toVector());
        double len = v.length();
        WeaponFx fx = fx();
        if (fx != null && len > 0.5) {
            fx.tpop(a.clone().add(v.clone().multiply(0.5)), "w_beam_x", WEB, 0.12f, (float) len, 0.12f, 1, 1, 1, Display.Billboard.FIXED, WeaponFx.alongY(v));
        }
        Particle.DustOptions d = new Particle.DustOptions(WEB, 0.6f);
        for (double s = 0; s < len; s += 0.8) {
            fallback(a.clone().add(v.clone().multiply(s / len)), Particle.DUST, 1, 0, 0, 0, 0, d);
        }
    }

    // ================================================================= boucle

    private void tick() {
        if (ended) return;
        if (boss == null || !boss.isValid()) {
            leave("spider.vanished");
            return;
        }
        tick++;
        Location c = boss.getLocation();
        World w = c.getWorld();
        if (invulnerable > 0) invulnerable--;
        if (stunned > 0) stunned--;
        float yaw = boss.getBodyYaw();
        rig.update(c, yaw, size);
        moveHitboxes();
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
        // yeux qui brillent, gouttes de venin
        if (tick % 4 == 0 && !rig.hidden) {
            double rad = Math.toRadians(yaw);
            Location eye = c.clone().add(-Math.sin(rad) * 0.9 * size, 0.95 * size, Math.cos(rad) * 0.9 * size);
            w.spawnParticle(Particle.DUST, eye, 2, 0.15 * size, 0.06, 0.15 * size, 0, new Particle.DustOptions(rig.eyeColor, 1.0f), true);
            if (phase >= 2) w.spawnParticle(Particle.FALLING_DRIPSTONE_WATER, eye.add(0, -0.3 * size, 0), 1, 0.1, 0, 0.1, 0, null, true);
        }
        if (enraged && tick % 3 == 0) {
            w.spawnParticle(Particle.DUST, c.clone().add(0, size, 0), 5, 0.6 * size, 0.4 * size, 0.6 * size, 0, new Particle.DustOptions(RED_EYE, 1.8f), true);
        }
        if (tick % 10 == 0) borderRing();
        // elle regarde sa cible, et en a toujours une (les araignées sont calmes le jour)
        if (tick % 10 == 0 && boss.hasAI()) {
            Player t = nearest(c, 40);
            if (t != null) boss.setTarget(t);
        }
        if (tick % 14 == 0 && boss.getVelocity().lengthSquared() > 0.005) csound(c, "spider.skitter", 1.2f, 0.9f, "entity.spider.step");
        // laisse : sur le sol de son arène (pas sur les murs du dôme)
        if (!airborne) {
            double arena = cfg("arena-radius", 18);
            Location mid = altar.clone().add(0.5, 0, 0.5);
            double hd = c.getWorld() == altar.getWorld() ? horizontalDist(c, mid) : Double.MAX_VALUE;
            if (hd > arena + 12 || c.getY() < altar.getY() - 12) {
                boss.teleport(altar.clone().add(0.5, 1, 0.5));
            } else if (hd > arena + 0.5 || c.getY() > altar.getY() + 5 || c.getBlock().isLiquid()) {
                Location back = keepIn(c, arena - 2);
                back.setY(ground(back).getY());
                boss.teleport(back);
                boss.setVelocity(new Vector());
            }
        }
        if (tick % 20 == 0) {
            lonely = near.isEmpty() ? lonely + 1 : 0;
            if (lonely >= 60) {
                leave("spider.left");
                return;
            }
            if (tick > cfg("max-minutes", 20) * 1200) {
                leave("spider.timeout");
                return;
            }
        }
        tickMinions();
        // les deux attaques spéciales
        double ratio = hp / maxHp;
        if (!airborne && invulnerable <= 0 && phase == 2 && !webDone && ratio <= cfg("web-at", 0.5)) {
            abyssalWeb();
            return;
        }
        if (!airborne && invulnerable <= 0 && phase == 3 && !eclipseDone && ratio <= cfg("eclipse-at", 0.2)) {
            eclipse();
            return;
        }
        if (invulnerable > 0 || airborne || !boss.hasAI()) return;
        if (--nextAttack <= 0) {
            nextAttack = (int) cfg("attack-interval." + phase, phase == 1 ? 55 : phase == 2 ? 45 : 36);
            if (enraged) nextAttack = (int) (nextAttack * 0.7);
            List<Attack> pool = switch (phase) {
                case 1 -> List.of(Attack.WEB_SHOT, Attack.POUNCE, Attack.LEG_SWEEP, Attack.VENOM_RAIN, Attack.BROOD, Attack.SILK_PULL);
                case 2 -> List.of(Attack.WEB_SHOT, Attack.POUNCE, Attack.LEG_SWEEP, Attack.VENOM_RAIN, Attack.BROOD, Attack.SILK_PULL,
                        Attack.SHADOW_DASH, Attack.CEILING_DROP, Attack.SHADOW_DASH);
                default -> List.of(Attack.POUNCE, Attack.LEG_SWEEP, Attack.VENOM_RAIN, Attack.SILK_PULL, Attack.SHADOW_DASH,
                        Attack.CEILING_DROP, Attack.FANG_NOVA, Attack.FANG_NOVA, Attack.WEB_SHOT, Attack.BROOD);
            };
            Attack a;
            int guard = 0;
            do {
                a = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
            } while (a == last && guard++ < 20);
            last = a;
            run(a);
            double combo = phase == 3 ? (enraged ? 0.55 : 0.3) : phase == 2 ? 0.2 : 0;
            if (ThreadLocalRandom.current().nextDouble() < combo) {
                Attack first = a;
                later(32, () -> {
                    if (ended || airborne || invulnerable > 0 || boss == null || !boss.isValid() || shielded) return;
                    List<Attack> quick = List.of(Attack.WEB_SHOT, Attack.LEG_SWEEP, Attack.VENOM_RAIN, Attack.SILK_PULL);
                    Attack b;
                    do {
                        b = quick.get(ThreadLocalRandom.current().nextInt(quick.size()));
                    } while (b == first);
                    run(b);
                });
            }
        }
    }

    private void run(Attack a) {
        switch (a) {
            case WEB_SHOT -> webShot();
            case POUNCE -> pounce();
            case VENOM_RAIN -> venomRain();
            case BROOD -> brood();
            case LEG_SWEEP -> legSweep();
            case SILK_PULL -> silkPull();
            case SHADOW_DASH -> shadowDash();
            case CEILING_DROP -> ceilingDrop();
            case FANG_NOVA -> fangNova();
        }
    }

    // ================================================================= dégâts reçus

    public boolean hurt(Player by, double amount) {
        if (ended || invulnerable > 0 || boss == null) return false;
        if (shielded) {
            if (by != null && tick % 8 == 0) SmpBoss.hud(by, parse(msg("shielded")));
            return false;
        }
        if (by != null) damage.merge(by.getUniqueId(), amount, Double::sum);
        if (damage.size() > fighters) {
            double old = maxHp;
            fighters = damage.size();
            maxHp = hpFor(fighters);
            hp = hp * maxHp / old;
            for (Player p : nearby(boss.getLocation(), 60)) {
                SmpBoss.hud(p, parse(plugin.msg("boss.new-fighter").replace("<player>", SmpBoss.MM.escapeTags(by.getName()))
                        .replace("<hp>", String.valueOf((long) (perPlayer() * extraShare())))));
            }
        }
        if (stunned > 0) amount *= cfg("stun-multiplier", 1.5);
        hp -= amount;
        damageNumber(amount);
        if (tick % 3 == 0) csound(boss.getLocation(), "spider.hiss", 1.2f, 1.2f, "entity.spider.hurt");
        boss.getWorld().spawnParticle(Particle.DUST, boss.getLocation().add(0, size * 0.8, 0), 10, 0.5 * size, 0.4 * size, 0.5 * size, 0,
                new Particle.DustOptions(VENOM, 1.2f), true);
        double ratio = hp / maxHp;
        if (hp <= 0) defeat();
        else if (phase == 1 && ratio <= cfg("phase-2-at", 0.66)) changePhase(2);
        else if (phase == 2 && webDone && ratio <= cfg("phase-3-at", 0.33)) changePhase(3);
        else if (phase == 3 && !enraged && ratio <= cfg("enrage-at", 0.12)) enrage();
        return true;
    }

    private void changePhase(int to) {
        phase = to;
        invulnerable = 60;
        boss.setAI(false);
        Location c = boss.getLocation();
        csound(c, "spider.screech", 4f, to == 3 ? 0.5f : 0.7f, "entity.spider.death");
        sound(c, "entity.generic.explode", 3f, 0.7f);
        rig.poseTarget = 1;
        float from = size, target = (float) cfg("size-phase-" + to, to == 2 ? 6.75 : 7.8);
        repeat(1, 40, i -> {
            if (boss == null || !boss.isValid()) return;
            size = from + (target - from) * (i + 1) / 40f;
            setScale(size);
            boss.getWorld().spawnParticle(Particle.DUST, boss.getLocation().add(0, size, 0), 12, size * 0.6, size * 0.4, size * 0.6, 0,
                    new Particle.DustOptions(to == 3 ? RED_EYE : ABYSS, 1.6f), true);
        }, () -> {
            rig.poseTarget = 0;
            if (boss != null && boss.isValid()) boss.setAI(true);
            nextAttack = 30;
        });
        if (to == 3) rig.eyes(RED_EYE);
        bar.color(to == 2 ? BossBar.Color.PURPLE : BossBar.Color.RED);
        for (Player p : nearby(c, 40)) p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 50, 0, false, false, false));
        later(38, () -> {
            if (boss != null && boss.isValid()) shockwave(boss.getLocation(), 10, 1.5, cfg("damage.phase-shockwave", 4));
        });
        titleAll("title-phase-" + to, "subtitle-phase-" + to);
        AttributeInstance sp = boss.getAttribute(Attribute.MOVEMENT_SPEED);
        if (sp != null) sp.setBaseValue(cfg("speed", 0.3) * (to == 3 ? 1.3 : 1.15));
    }

    private void enrage() {
        enraged = true;
        titleAll("title-enrage", "subtitle-enrage");
        Location c = boss.getLocation();
        csound(c, "spider.screech", 4f, 0.4f, "entity.spider.death");
        shockwave(c, 12 * zs(), 2.0, cfg("damage.phase-shockwave", 4) * 2);
        bar.color(BossBar.Color.RED);
        AttributeInstance sp = boss.getAttribute(Attribute.MOVEMENT_SPEED);
        if (sp != null) sp.setBaseValue(cfg("speed", 0.3) * 1.55);
        nextAttack = 20;
    }

    // ================================================================= attaques

    /** Boules de toile lancées en cloche : là où elles tombent, toiles collantes + dégâts. */
    private void webShot() {
        announce("web-shot");
        rig.poseTarget = 1;
        later(14, () -> rig.poseTarget = 0);
        List<Player> targets = nearby(boss.getLocation(), 32);
        java.util.Collections.shuffle(targets);
        csound(boss.getLocation(), "spider.web", 2f, 1f, "entity.spider.ambient");
        for (Player p : targets.subList(0, Math.min(2 + phase, targets.size()))) {
            Location dest = ground(p.getLocation());
            double r = 2.2 * zs();
            ring(dest, r, WEB, 26);
            Location from = boss.getLocation().add(0, size * 1.1, 0);
            ItemDisplay ball = boss.getWorld().spawn(from, ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.setItemStack(new ItemStack(Material.COBWEB));
                d.setBillboard(Display.Billboard.CENTER);
                d.setTeleportDuration(1);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1.6f, 1.6f, 1.6f), new Quaternionf()));
            });
            props.add(ball);
            int flight = 24;
            repeat(1, flight, i -> {
                double t = (i + 1) / (double) flight;
                Location l = from.clone().add(dest.clone().subtract(from).toVector().multiply(t)).add(0, Math.sin(Math.PI * t) * 7, 0);
                ball.teleport(l);
                if (i % 2 == 0) l.getWorld().spawnParticle(Particle.DUST, l, 2, 0.1, 0.1, 0.1, 0, new Particle.DustOptions(WEB, 1f), true);
            }, () -> {
                ball.remove();
                props.remove(ball);
                WeaponFx fx = fx();
                if (fx != null) fx.webTrap(dest);
                dest.getWorld().spawnParticle(Particle.BLOCK, dest.clone().add(0, 0.5, 0), 40, r * 0.4, 0.3, r * 0.4, 0, Material.COBWEB.createBlockData(), true);
                for (Player t : nearby(dest, r)) {
                    hit(t, cfg("damage.web", 6) * mult());
                    t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 3, false, true, true));
                }
                placeWebs(dest, phase >= 2 ? 1 : 0, 100);
            });
        }
    }

    /** Toiles temporaires dans l'arène (seulement dans l'air au ras du sol). */
    private void placeWebs(Location at, int rad, int ticks) {
        List<Block> placed = new ArrayList<>();
        for (int x = -rad; x <= rad; x++) for (int z = -rad; z <= rad; z++) {
            if (Math.abs(x) + Math.abs(z) > rad) continue;
            Block b = at.getBlock().getRelative(x, 0, z);
            if (b.getType().isAir()) {
                b.setType(Material.COBWEB, false);
                placed.add(b);
                webs.add(b);
            }
        }
        later(ticks, () -> {
            for (Block b : placed) {
                if (b.getType() == Material.COBWEB) b.setType(Material.AIR, false);
                webs.remove(b);
            }
        });
    }

    /** Elle s'accroupit puis bondit sur un joueur. */
    private void pounce() {
        Player p = randomTarget(30);
        if (p == null) return;
        announce("pounce");
        Location dest = ground(keepIn(p.getLocation(), cfg("arena-radius", 18) - 2));
        double r = 3.6 * zs();
        ring(dest, r, ABYSS, 24);
        face(dest);
        rig.poseTarget = -1;
        boss.setAI(false);
        csound(boss.getLocation(), "spider.hiss", 2f, 0.7f, "entity.spider.ambient");
        later(22, () -> {
            if (boss == null || !boss.isValid()) return;
            rig.poseTarget = 0.6;
            airborne = true;
            Location from = boss.getLocation();
            int n = 12;
            repeat(1, n, i -> {
                if (boss == null || !boss.isValid()) return;
                double t = (i + 1) / (double) n;
                Location l = from.clone().add(dest.clone().subtract(from).toVector().multiply(t)).add(0, Math.sin(Math.PI * t) * 6, 0);
                l.setYaw(boss.getLocation().getYaw());
                boss.teleport(l);
            }, () -> {
                airborne = false;
                rig.poseTarget = 0;
                if (boss == null || !boss.isValid()) return;
                boss.setAI(true);
                shockwave(dest, r, 1.3, cfg("damage.pounce", 10));
                placeWebs(dest, 1, 60);
            });
        });
    }

    /** Pluie de venin : flaques qui empoisonnent. */
    private void venomRain() {
        announce("venom-rain");
        csound(boss.getLocation(), "spider.hiss", 2.5f, 0.6f, "entity.spider.ambient");
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<Player> ps = nearby(boss.getLocation(), 32);
        int n = 6 + phase * 3;
        double rad = 1.8 * zs();
        for (int k = 0; k < n && !ps.isEmpty(); k++) {
            Player p = ps.get(r.nextInt(ps.size()));
            Location dest = ground(keepIn(p.getLocation().add(r.nextDouble(-4, 4), 0, r.nextDouble(-4, 4)), cfg("arena-radius", 18) - 1));
            int delay = 26 + r.nextInt(14);
            ring(dest, rad, VENOM, delay);
            later(delay, () -> {
                WeaponFx fx = fx();
                if (fx != null) {
                    Location top = dest.clone().add(0, 10, 0);
                    ItemDisplay drop = fx.t(top, "w_spike_x", VENOM, 0.9f, 3f, 0.9f, Display.Billboard.FIXED, WeaponFx.alongY(new Vector(0, -1, 0)));
                    later(1, () -> WeaponFx.tween(drop, new Vector3f(0, -9.5f, 0), WeaponFx.alongY(new Vector(0, -1, 0)), new Vector3f(0.9f, 3f, 0.9f), 4));
                    later(6, drop::remove);
                }
            });
            later(delay + 5, () -> {
                dest.getWorld().spawnParticle(Particle.DUST, dest.clone().add(0, 0.3, 0), 40, rad * 0.5, 0.2, rad * 0.5, 0, new Particle.DustOptions(VENOM, 1.4f), true);
                sound(dest, "block.slime_block.fall", 1.5f, 0.6f);
                for (Player t : nearby(dest, rad)) hit(t, cfg("damage.venom", 5) * mult());
                WeaponFx fx = fx();
                if (fx != null) {
                    fx.tpop(dest.clone().add(0, 0.12, 0), "w_ring_flat", VENOM, (float) (rad * 2.2), 1, (float) (rad * 2.2), 2, 90, 6, Display.Billboard.FIXED, null);
                    fx.tflip(dest.clone().add(0, 0.8, 0), "w_burst_", 12, 1, VENOM, 0.6f, 2.6f, Display.Billboard.CENTER);
                }
                // la flaque reste 5 s
                repeat(10, 10, i -> {
                    dest.getWorld().spawnParticle(Particle.DUST, dest.clone().add(0, 0.2, 0), 8, rad * 0.5, 0.05, rad * 0.5, 0, new Particle.DustOptions(VENOM, 1.1f), true);
                    for (Player t : nearby(dest, rad)) {
                        if (Math.abs(t.getLocation().getY() - dest.getY()) > 2) continue;
                        t.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 40, 1, false, true, true));
                    }
                }, null);
            });
        }
    }

    /** Deux cocons d'œufs : ils éclosent en petites araignées. */
    private void brood() {
        announce("brood");
        csound(boss.getLocation(), "spider.screech", 2f, 1.3f, "entity.spider.ambient");
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int k = 0; k < 2; k++) {
            double a = r.nextDouble(Math.PI * 2), d = r.nextDouble(4, 8);
            Location at = ground(keepIn(boss.getLocation().add(Math.cos(a) * d, 0, Math.sin(a) * d), cfg("arena-radius", 18) - 2));
            ItemStack it = new ItemStack(Material.WHITE_WOOL);
            var m = it.getItemMeta();
            SmpBoss.modelData(m, "spider_eggsac");
            it.setItemMeta(m);
            ItemDisplay sac = at.getWorld().spawn(at.clone().add(0, 0.6, 0), ItemDisplay.class, e -> {
                e.setPersistent(false);
                e.setItemStack(it);
                e.setBrightness(new Display.Brightness(14, 15));
                e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.4f, 0.4f, 0.4f), new Quaternionf()));
            });
            props.add(sac);
            ring(at, 2.2, WEB, 40);
            repeat(2, 20, i -> {
                if (!sac.isValid()) return;
                float s = 1.1f + (float) Math.sin(i * 0.9) * 0.12f + i * 0.02f;
                sac.setInterpolationDelay(0);
                sac.setInterpolationDuration(2);
                sac.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s * 1.2f, s), new Quaternionf()));
                if (i % 5 == 0) sound(at, "block.sniffer_egg.crack", 1.2f, 0.8f);
            }, () -> {
                sac.remove();
                props.remove(sac);
                sound(at, "block.sniffer_egg.hatch", 1.5f, 0.7f);
                WeaponFx fx = fx();
                if (fx != null) fx.puff(at.clone().add(0, 0.8, 0), WEB, 3f);
                at.getWorld().spawnParticle(Particle.BLOCK, at.clone().add(0, 0.6, 0), 30, 0.5, 0.4, 0.5, 0, Material.WHITE_WOOL.createBlockData(), true);
                int count = (int) cfg("brood-size", 2) + phase - 1;
                for (int i = 0; i < count; i++) {
                    CaveSpider cs = at.getWorld().spawn(at.clone().add(r.nextDouble(-0.8, 0.8), 0.2, r.nextDouble(-0.8, 0.8)), CaveSpider.class, e -> {
                        e.setPersistent(false);
                        e.getPersistentDataContainer().set(minionKey, PersistentDataType.BYTE, (byte) 1);
                        base(e, Attribute.MAX_HEALTH, cfg("brood-health", 8));
                        e.setHealth(cfg("brood-health", 8));
                        e.customName(parse(msg("spiderling")));
                    });
                    minions.add(cs);
                }
            });
        }
    }

    private void tickMinions() {
        if (tick % 20 != 0) return;
        minions.removeIf(m -> !m.isValid() || m.isDead());
        for (Entity m : minions) {
            if (!(m instanceof Mob mob)) continue;
            if (horizontalDist(m.getLocation(), altar) > cfg("arena-radius", 18) + 4) mob.teleport(ground(keepIn(m.getLocation(), cfg("arena-radius", 18) - 2)));
            Player t = nearest(m.getLocation(), 30);
            if (t != null) mob.setTarget(t);
        }
    }

    /** Elle se cabre et balaie tout autour d'elle avec ses pattes. */
    private void legSweep() {
        announce("leg-sweep");
        Location c = boss.getLocation();
        double r = 6.5 * zs();
        ring(ground(c), r, ABYSS, 20);
        rig.poseTarget = 1;
        boss.setAI(false);
        csound(c, "spider.hiss", 2f, 0.8f, "entity.spider.ambient");
        later(20, () -> {
            rig.poseTarget = -0.4;
            if (boss == null || !boss.isValid()) return;
            Location cc = boss.getLocation();
            csound(cc, "boss.whoosh", 2.5f, 0.8f, "entity.player.attack.sweep");
            WeaponFx fx = fx();
            if (fx != null) {
                for (int k = 0; k < 3; k++) {
                    Quaternionf q = new Quaternionf().rotateY((float) (k * 2.1));
                    fx.tpop(cc.clone().add(0, 0.8, 0), "w_slash_flat", k == 1 ? WEB : ABYSS, (float) (r * 2), 1, (float) (r * 2), 2, 2, 4, Display.Billboard.FIXED, q);
                }
                fx.wave(cc.clone().add(0, 0.2, 0), "w_shock_flat", ABYSS, 1, (float) (r * 2.2), 7, null, Display.Billboard.FIXED);
            }
            shake(cc, r * 2);
            for (Player p : nearby(cc, r)) {
                hit(p, cfg("damage.sweep", 9) * mult());
                Vector kb = p.getLocation().toVector().subtract(cc.toVector()).setY(0);
                if (kb.lengthSquared() < 0.01) kb = new Vector(0.1, 0, 0);
                p.setVelocity(kb.normalize().multiply(1.4).setY(0.5));
            }
            later(10, () -> {
                rig.poseTarget = 0;
                if (boss != null && boss.isValid()) boss.setAI(true);
            });
        });
    }

    /** Des fils de soie attrapent les joueurs loin d'elle et les ramènent... dans sa gueule. */
    private void silkPull() {
        announce("silk-pull");
        List<Player> far = new ArrayList<>();
        for (Player p : nearby(boss.getLocation(), 30)) if (p.getLocation().distance(boss.getLocation()) > 6) far.add(p);
        if (far.isEmpty()) {
            legSweep();
            return;
        }
        java.util.Collections.shuffle(far);
        List<Player> caught = far.subList(0, Math.min(1 + phase, far.size()));
        csound(boss.getLocation(), "spider.web", 2f, 0.7f, "entity.spider.ambient");
        boss.setAI(false);
        rig.poseTarget = 1;
        repeat(2, 14, i -> {
            if (boss == null || !boss.isValid()) return;
            Location mouth = boss.getLocation().add(0, size, 0);
            for (Player p : caught) {
                if (!p.isOnline() || p.isDead()) continue;
                thread(mouth, p.getLocation().add(0, 1, 0));
                if (i >= 5) {
                    Vector pull = mouth.toVector().subtract(p.getLocation().toVector());
                    double d = pull.length();
                    if (d > 2.5) p.setVelocity(pull.normalize().multiply(Math.min(1.3, 0.4 + d * 0.06)).setY(0.25));
                }
            }
        }, () -> {
            rig.poseTarget = 0;
            if (boss == null || !boss.isValid()) return;
            boss.setAI(true);
            Location mouth = boss.getLocation();
            for (Player p : caught) {
                if (p.getLocation().distance(mouth) > 4.5 * size / 1.9) continue;
                hit(p, cfg("damage.bite", 8) * mult());
                p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 80, 1, false, true, true));
                csound(p.getLocation(), "spider.hiss", 1.5f, 1.5f, "entity.spider.hurt");
                WeaponFx fx = fx();
                if (fx != null) fx.venomHit(p.getLocation().add(0, 1, 0));
            }
        });
    }

    /** Elle traverse l'arène en ligne droite comme une ombre. */
    private void shadowDash() {
        Player p = randomTarget(30);
        if (p == null) return;
        announce("shadow-dash");
        Location from = boss.getLocation();
        Vector dir = p.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (dir.lengthSquared() < 0.1) dir = new Vector(1, 0, 0);
        dir.normalize();
        double len = Math.min(22, cfg("arena-radius", 18) * 1.6);
        Location to = keepIn(from.clone().add(dir.clone().multiply(len)), cfg("arena-radius", 18) - 1.5);
        Vector seg = to.toVector().subtract(from.toVector());
        double L = seg.length();
        face(to);
        boss.setAI(false);
        rig.poseTarget = -1;
        // la ligne s'allume au sol
        WeaponFx fx = fx();
        for (double s = 0; s < L; s += 2) {
            Location at = ground(from.clone().add(seg.clone().multiply(s / Math.max(0.1, L))));
            if (fx != null) fx.tpop(at.clone().add(0, 0.12, 0), "w_ring_flat", ABYSS, 2.6f, 1, 2.6f, 2, 18, 3, Display.Billboard.FIXED, null);
            Particle.DustOptions d = new Particle.DustOptions(ABYSS, 1.4f);
            repeat(4, 6, i -> at.getWorld().spawnParticle(Particle.DUST, at.clone().add(0, 0.2, 0), 3, 0.6, 0, 0.6, 0, d, true), null);
        }
        csound(from, "spider.hiss", 2f, 0.6f, "entity.spider.ambient");
        later(24, () -> {
            if (boss == null || !boss.isValid()) return;
            airborne = true;
            rig.poseTarget = 0;
            csound(from, "boss.whoosh", 2.5f, 1.2f, "entity.player.attack.sweep");
            Set<UUID> done = new HashSet<>();
            int n = 8;
            repeat(1, n, i -> {
                if (boss == null || !boss.isValid()) return;
                Location l = from.clone().add(seg.clone().multiply((i + 1) / (double) n));
                l.setYaw(from.getYaw());
                l.setY(ground(l).getY());
                boss.teleport(l);
                if (fx != null) fx.tpop(l.clone().add(0, size * 0.8, 0), "w_spike_x", ABYSS, 1.2f, 3.5f, 1.2f, 1, 0, 5, Display.Billboard.FIXED, WeaponFx.alongY(seg.clone().multiply(-1)));
                for (Player t : nearby(l, 2.6 * size / 1.9)) {
                    if (!done.add(t.getUniqueId())) continue;
                    hit(t, cfg("damage.dash", 10) * mult());
                    Vector side = new Vector(-seg.getZ(), 0, seg.getX()).normalize().multiply(ThreadLocalRandom.current().nextBoolean() ? 1 : -1);
                    t.setVelocity(side.multiply(1.1).setY(0.45));
                }
            }, () -> {
                airborne = false;
                if (boss != null && boss.isValid()) boss.setAI(true);
            });
        });
    }

    /** Elle remonte sur un fil, puis retombe de tout son poids sur un joueur. */
    private void ceilingDrop() {
        Player p = randomTarget(30);
        if (p == null) return;
        announce("ceiling-drop");
        Location from = boss.getLocation();
        Location sky = from.clone().add(0, 14, 0);
        boss.setAI(false);
        boss.setGravity(false);
        airborne = true;
        invulnerable = 70;
        csound(from, "spider.web", 2.5f, 0.6f, "entity.spider.ambient");
        repeat(1, 22, i -> {
            if (boss == null || !boss.isValid()) return;
            double t = (i + 1) / 22.0;
            Location l = from.clone().add(0, 14 * t * t * (3 - 2 * t), 0);
            boss.teleport(l);
            if (i % 2 == 0) thread(l.clone().add(0, size, 0), sky.clone().add(0, 10, 0));
        }, null);
        Location[] dest = {null};
        double r = 5 * zs();
        later(24, () -> {
            if (!p.isOnline()) return;
            dest[0] = ground(keepIn(p.getLocation(), cfg("arena-radius", 18) - 2));
            ring(dest[0], r, ABYSS, 30);
            csound(dest[0], "spider.screech", 3f, 0.8f, "entity.spider.ambient");
        });
        later(54, () -> {
            if (boss == null || !boss.isValid() || dest[0] == null) {
                airborne = false;
                if (boss != null && boss.isValid()) {
                    boss.setGravity(true);
                    boss.setAI(true);
                }
                return;
            }
            Location start = sky.clone();
            start.setX(dest[0].getX());
            start.setZ(dest[0].getZ());
            boss.teleport(start);
            repeat(1, 6, i -> {
                if (boss == null || !boss.isValid()) return;
                double t = (i + 1) / 6.0;
                boss.teleport(start.clone().add(0, -(start.getY() - dest[0].getY()) * t * t, 0));
            }, () -> {
                airborne = false;
                invulnerable = 0;
                if (boss == null || !boss.isValid()) return;
                boss.setGravity(true);
                boss.setAI(true);
                shockwave(dest[0], r, 1.6, cfg("damage.drop", 14));
                placeWebs(dest[0], 2, 80);
                WeaponFx fx = fx();
                if (fx != null) fx.boom(dest[0].clone().add(0, 1, 0), ABYSS, 7);
            });
        });
    }

    /** Phase 3 : des vagues de crocs de venin jaillissent du sol autour d'elle. */
    private void fangNova() {
        announce("fang-nova");
        Location c = ground(boss.getLocation());
        boss.setAI(false);
        rig.poseTarget = -1;
        csound(c, "spider.screech", 3f, 0.9f, "entity.spider.ambient");
        ring(c, 3, VENOM, 18);
        WeaponFx fx = fx();
        for (int wave = 0; wave < 3; wave++) {
            int ww = wave;
            for (int k = 1; k <= 4; k++) {
                double rr = k * 3.2 * zs();
                int delay = 18 + wave * 22 + k * 4;
                later(delay, () -> {
                    int pts = (int) (8 + rr * 2.5);
                    double off = ww * 0.3;
                    for (int i = 0; i < pts; i++) {
                        double a = off + Math.PI * 2 * i / pts;
                        Location at = ground(c.clone().add(Math.cos(a) * rr, 0, Math.sin(a) * rr));
                        if (fx != null && i % 2 == 0) fx.tpop(at.clone().add(0, 1.2, 0), "w_spike_x", VENOM, 0.9f, 2.6f, 0.9f, 1, 1, 4, Display.Billboard.FIXED, null);
                        at.getWorld().spawnParticle(Particle.DUST, at.clone().add(0, 0.4, 0), 2, 0.2, 0.4, 0.2, 0, new Particle.DustOptions(VENOM, 1.3f), true);
                    }
                    sound(c, "entity.evoker_fangs.attack", 1.5f, 0.6f + (float) rr * 0.03f);
                    for (Player p : nearby(c, rr + 1.3)) {
                        if (p.getLocation().distance(c) < rr - 1.3) continue;
                        hit(p, cfg("damage.nova", 7) * mult());
                        p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 1, false, true, true));
                        p.setVelocity(new Vector(0, 0.6, 0));
                    }
                });
            }
        }
        later(18 + 3 * 22 + 10, () -> {
            rig.poseTarget = 0;
            if (boss != null && boss.isValid()) boss.setAI(true);
        });
    }

    // ================================================================= attaque spéciale 1 : la Toile Abyssale

    /**
     * Elle monte au centre et tisse une toile géante : 4 cocons retiennent la toile. Tant qu'il en reste, elle est intouchable.
     * Cassés à temps : elle tombe, sonnée (dégâts x1,5). Sinon : l'Étreinte (gros dégâts + poison pour toute l'arène).
     */
    private void abyssalWeb() {
        webDone = true;
        shielded = true;
        airborne = true;
        boss.setAI(false);
        boss.setGravity(false);
        boss.setVelocity(new Vector());
        titleAll("title-web", "subtitle-web");
        Location center = altar.clone().add(0.5, 1, 0.5);
        Location top = center.clone().add(0, 11, 0);
        Location from = boss.getLocation();
        csound(center, "spider.screech", 4f, 0.6f, "entity.spider.death");
        repeat(1, 30, i -> {
            if (boss == null || !boss.isValid()) return;
            double t = (i + 1) / 30.0, e = t * t * (3 - 2 * t);
            Location l = from.clone().add(top.clone().subtract(from).toVector().multiply(e));
            boss.teleport(l);
            if (i % 3 == 0) thread(l.clone().add(0, size, 0), l.clone().add(0, 20, 0));
        }, null);
        double ar = cfg("arena-radius", 18);
        WeaponFx fx = fx();
        ItemDisplay[] floor = {null};
        if (fx != null) {
            floor[0] = fx.t(center.clone().add(0, -0.85, 0), "w_web_flat", WEB, 0.5f, 1, 0.5f, Display.Billboard.FIXED, null);
            props.add(floor[0]);
            float s = (float) (ar * 2.15);
            later(1, () -> WeaponFx.tween(floor[0], new Vector3f(), null, new Vector3f(s, 1, s), 30));
        }
        // les 4 cocons
        int need = (int) (cfg("web-hits", 5) + Math.max(0, fighters - 1) * cfg("web-hits-per-player", 2));
        anchors.clear();
        for (int k = 0; k < 4; k++) {
            double a = Math.PI / 4 + k * Math.PI / 2;
            Location at = ground(center.clone().add(Math.cos(a) * ar * 0.62, 0, Math.sin(a) * ar * 0.62));
            ArmorStand st = at.getWorld().spawn(at, ArmorStand.class, e -> {
                e.setPersistent(false);
                e.setInvisible(true);
                e.setGravity(false);
                e.setInvulnerable(false);
                e.setSilent(true);
                e.setBasePlate(false);
                e.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 5);
            });
            ItemStack it = new ItemStack(Material.WHITE_WOOL);
            var m = it.getItemMeta();
            SmpBoss.modelData(m, "spider_eggsac");
            it.setItemMeta(m);
            ItemDisplay vis = at.getWorld().spawn(at.clone().add(0, 1.1, 0), ItemDisplay.class, e -> {
                e.setPersistent(false);
                e.setItemStack(it);
                e.setBrightness(new Display.Brightness(14, 15));
                e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1.8f, 2.4f, 1.8f), new Quaternionf()));
            });
            TextDisplay label = at.getWorld().spawn(at.clone().add(0, 3.1, 0), TextDisplay.class, e -> {
                e.setPersistent(false);
                e.setBillboard(Display.Billboard.CENTER);
                e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                e.setShadowed(true);
            });
            props.add(st);
            props.add(vis);
            props.add(label);
            anchors.put(st, new int[]{need, need});
            anchorParts.put(st, new Entity[]{vis, label});
            label.text(parse(anchorText(need, need)));
        }
        int secs = (int) cfg("web-seconds", 25);
        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (ended) {
                task[0].cancel();
                return;
            }
            int k = t[0]++;
            // fils entre la Reine et les cocons
            if (k % 3 == 0 && boss != null && boss.isValid()) {
                for (Entity st : anchors.keySet()) thread(boss.getLocation().add(0, size * 0.5, 0), st.getLocation().add(0, 1.6, 0));
            }
            // compte à rebours
            int left = secs - k / 20;
            if (k % 20 == 0) for (Player p : nearby(center, 60)) SmpBoss.hud(p, parse(msg("web-timer").replace("<s>", String.valueOf(left))
                    .replace("<n>", String.valueOf(anchors.size()))));
            // gouttes de venin sur les joueurs
            if (k % 40 == 20) {
                Player p = randomTarget(40);
                if (p != null) {
                    Location d = ground(p.getLocation());
                    ring(d, 2.2, VENOM, 20);
                    later(20, () -> {
                        for (Player x : nearby(d, 2.2)) {
                            hit(x, cfg("damage.web-drip", 4) * mult());
                            x.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0, false, true, true));
                        }
                        d.getWorld().spawnParticle(Particle.DUST, d.clone().add(0, 0.3, 0), 30, 1, 0.2, 1, 0, new Particle.DustOptions(VENOM, 1.4f), true);
                        if (fx() != null) fx().tflip(d.clone().add(0, 0.8, 0), "w_burst_", 12, 1, VENOM, 0.6f, 2.6f, Display.Billboard.CENTER);
                    });
                }
            }
            if (anchors.isEmpty()) {
                task[0].cancel();
                webBroken(center, floor[0]);
                return;
            }
            if (k >= secs * 20) {
                task[0].cancel();
                embrace(center, floor[0]);
            }
        }, 30L, 1L);
        tasks.add(task[0]);
    }

    private final Map<Entity, Entity[]> anchorParts = new HashMap<>();

    private static String anchorText(int left, int need) {
        StringBuilder sb = new StringBuilder("<white>🕸 ");
        int full = (int) Math.ceil(10.0 * left / Math.max(1, need));
        for (int i = 0; i < 10; i++) sb.append(i < full ? "<#5aa0ff>▮" : "<dark_gray>▮");
        return sb.toString();
    }

    /** Un joueur frappe un cocon de la Toile Abyssale. */
    void hitAnchor(Entity st, Player by) {
        int[] v = anchors.get(st);
        if (v == null || by == null) return;
        Arena ar = bossArena();
        if (ar != null && ar.defined() && !ar.onFightFloor(by.getLocation())) return;
        v[0]--;
        damage.merge(by.getUniqueId(), cfg("web-hit-credit", 15), Double::sum);
        Location l = st.getLocation().add(0, 1.2, 0);
        l.getWorld().spawnParticle(Particle.BLOCK, l, 15, 0.5, 0.6, 0.5, 0, Material.COBWEB.createBlockData(), true);
        sound(l, "block.wool.break", 1.5f, 0.6f);
        Entity[] parts = anchorParts.get(st);
        if (parts != null && parts[1] instanceof TextDisplay td) td.text(parse(anchorText(v[0], v[1])));
        if (v[0] <= 0) {
            anchors.remove(st);
            if (parts != null) for (Entity e : parts) e.remove();
            anchorParts.remove(st);
            st.remove();
            csound(l, "spider.web", 2.5f, 1.4f, "block.wool.break");
            WeaponFx fx = fx();
            if (fx != null) fx.boom(l, WEB, 4);
            for (Player p : nearby(l, 60)) SmpBoss.hud(p, parse(msg("web-anchor-broken").replace("<player>", SmpBoss.MM.escapeTags(by.getName()))
                    .replace("<n>", String.valueOf(anchors.size()))));
        }
    }

    private void webBroken(Location center, ItemDisplay floor) {
        titleAll("title-web-broken", "subtitle-web-broken");
        shielded = false;
        if (floor != null) {
            WeaponFx.tween(floor, new Vector3f(), new Quaternionf().rotateY(1f), new Vector3f(0.05f, 1, 0.05f), 10);
            later(12, floor::remove);
        }
        fall(center, () -> {
            stunned = (int) (cfg("stun-seconds", 8) * 20);
            rig.poseTarget = -1;
            boss.setAI(false);
            for (Player p : nearby(center, 60)) SmpBoss.hud(p, parse(msg("stunned")));
            later(stunned, () -> {
                rig.poseTarget = 0;
                if (boss != null && boss.isValid()) boss.setAI(true);
                nextAttack = 20;
            });
        });
    }

    private void embrace(Location center, ItemDisplay floor) {
        titleAll("title-embrace", "subtitle-embrace");
        for (Entity st : new ArrayList<>(anchors.keySet())) {
            Entity[] parts = anchorParts.remove(st);
            if (parts != null) for (Entity e : parts) e.remove();
            st.remove();
        }
        anchors.clear();
        csound(center, "spider.screech", 4f, 0.4f, "entity.spider.death");
        double ar = cfg("arena-radius", 18) + 4;
        for (Player p : nearby(center, ar)) {
            thread(boss.getLocation(), p.getLocation().add(0, 1, 0));
            Vector pull = center.toVector().subtract(p.getLocation().toVector());
            if (pull.lengthSquared() > 4) p.setVelocity(pull.normalize().multiply(1.2).setY(0.5));
        }
        later(14, () -> {
            for (Player p : nearby(center, ar)) {
                hit(p, cfg("damage.embrace", 16) * mult());
                p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 120, 1, false, true, true));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 2, false, true, true));
            }
            shake(center, ar * 2);
            WeaponFx fx = fx();
            if (fx != null) fx.boom(center.clone().add(0, 2, 0), ABYSS, 12);
            if (floor != null) {
                WeaponFx.tween(floor, new Vector3f(), null, new Vector3f(0.05f, 1, 0.05f), 10);
                later(12, floor::remove);
            }
            shielded = false;
            fall(center, () -> {
                if (boss != null && boss.isValid()) boss.setAI(true);
                nextAttack = 30;
            });
        });
    }

    /** Elle redescend au centre (fil coupé). */
    private void fall(Location center, Runnable then) {
        if (boss == null || !boss.isValid()) return;
        Location from = boss.getLocation();
        Location to = ground(center);
        repeat(1, 8, i -> {
            if (boss == null || !boss.isValid()) return;
            double t = (i + 1) / 8.0;
            boss.teleport(from.clone().add(to.clone().subtract(from).toVector().multiply(t * t)));
        }, () -> {
            airborne = false;
            if (boss == null || !boss.isValid()) return;
            boss.setGravity(true);
            shockwave(to, 8, 1.2, cfg("damage.phase-shockwave", 4));
            then.run();
        });
    }

    // ================================================================= attaque spéciale 2 : l'Éclipse des Mille Yeux

    /**
     * Le noir complet : elle disparaît, des centaines d'yeux s'ouvrent autour de l'arène. Elle traverse l'arène 6 fois
     * (la ligne s'allume juste avant), puis réapparaît au centre : la Morsure de l'Abysse touche tout ce qui est près d'elle.
     */
    private void eclipse() {
        eclipseDone = true;
        airborne = true;
        boss.setAI(false);
        boss.setGravity(false);
        invulnerable = 400;
        titleAll("title-eclipse", "subtitle-eclipse");
        Location center = ground(altar.clone().add(0.5, 1, 0.5));
        double ar = cfg("arena-radius", 18);
        for (Player p : nearby(center, 60)) p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 300, 0, false, false, false));
        csound(center, "spider.screech", 4f, 0.5f, "entity.spider.death");
        rig.hidden = true;
        WeaponFx fx = fx();
        // les yeux autour de l'arène
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (fx != null) {
            for (int k = 0; k < 36; k++) {
                double a = r.nextDouble(Math.PI * 2), d = ar + r.nextDouble(1, 6), y = r.nextDouble(1, 9);
                Location l = center.clone().add(Math.cos(a) * d, y, Math.sin(a) * d);
                int dl = r.nextInt(40);
                later(dl, () -> {
                    fx.tpop(l, "w_glow", k(r) ? RED_EYE : EYE, 1.2f, 1.2f, 1.2f, 3, 200 - dl, 6, Display.Billboard.CENTER, null);
                    fx.tpop(l.clone().add(0.35, 0, 0), "w_glow", EYE, 0.8f, 0.8f, 0.8f, 3, 200 - dl, 6, Display.Billboard.CENTER, null);
                });
            }
        }
        int strikes = (int) cfg("eclipse-strikes", 6);
        for (int s = 0; s < strikes; s++) {
            int base = 40 + s * 26;
            later(base, () -> {
                Player p = randomTarget(ar + 8);
                if (p == null) return;
                double a = r.nextDouble(Math.PI * 2);
                Location target = p.getLocation();
                Location from = ground(center.clone().add(Math.cos(a) * ar, 0, Math.sin(a) * ar));
                Vector dir = target.toVector().subtract(from.toVector()).setY(0);
                if (dir.lengthSquared() < 0.1) dir = new Vector(1, 0, 0);
                dir.normalize();
                Location to = ground(keepIn(from.clone().add(dir.clone().multiply(ar * 2)), ar - 1));
                Vector seg = to.toVector().subtract(from.toVector());
                double L = seg.length();
                for (double x = 0; x < L; x += 2.2) {
                    Location at = ground(from.clone().add(seg.clone().multiply(x / Math.max(0.1, L))));
                    if (fx != null) fx.tpop(at.clone().add(0, 0.12, 0), "w_ring_flat", RED_EYE, 2.4f, 1, 2.4f, 2, 12, 3, Display.Billboard.FIXED, null);
                    at.getWorld().spawnParticle(Particle.DUST, at.clone().add(0, 0.3, 0), 4, 0.5, 0, 0.5, 0, new Particle.DustOptions(RED_EYE, 1.5f), true);
                }
                csound(from, "spider.hiss", 3f, 0.5f, "entity.spider.ambient");
                later(16, () -> {
                    if (boss == null || !boss.isValid()) return;
                    rig.hidden = false;
                    Set<UUID> done = new HashSet<>();
                    csound(from, "boss.whoosh", 3f, 1.1f, "entity.player.attack.sweep");
                    Location fl = from.clone();
                    fl.setYaw((float) Math.toDegrees(Math.atan2(-seg.getX(), seg.getZ())));
                    boss.teleport(fl);
                    repeat(1, 7, i -> {
                        if (boss == null || !boss.isValid()) return;
                        Location l = from.clone().add(seg.clone().multiply((i + 1) / 7.0));
                        l.setYaw(fl.getYaw());
                        boss.teleport(l);
                        if (fx != null) fx.tpop(l.clone().add(0, size * 0.8, 0), "w_spike_x", RED_EYE, 1.4f, 4f, 1.4f, 1, 0, 5, Display.Billboard.FIXED, WeaponFx.alongY(seg.clone().multiply(-1)));
                        for (Player t : nearby(l, 2.4 * size / 1.9)) {
                            if (!done.add(t.getUniqueId())) continue;
                            hit(t, cfg("damage.eclipse", 9) * mult());
                            t.setVelocity(seg.clone().normalize().multiply(0.8).setY(0.6));
                        }
                    }, () -> rig.hidden = true);
                });
            });
        }
        // la Morsure de l'Abysse
        int end = 40 + strikes * 26 + 30;
        later(end, () -> {
            if (boss == null || !boss.isValid()) return;
            boss.teleport(center);
            rig.hidden = false;
            rig.poseTarget = 1;
            double rr = cfg("bite-radius", 9) * zs() / 1.4;
            ring(center, rr, RED_EYE, 40);
            for (Player p : nearby(center, 60)) SmpBoss.hud(p, parse(msg("bite-warning")));
            csound(center, "spider.screech", 4f, 0.7f, "entity.spider.ambient");
            later(40, () -> {
                rig.poseTarget = 0;
                shockwave(center, rr, 2.2, cfg("damage.abyss-bite", 16));
                WeaponFx f2 = fx();
                if (f2 != null) f2.boom(center.clone().add(0, 2, 0), RED_EYE, (float) rr);
                for (Player p : nearby(center, rr)) p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 100, 1, false, true, true));
                airborne = false;
                invulnerable = 0;
                if (boss != null && boss.isValid()) {
                    boss.setGravity(true);
                    boss.setAI(true);
                }
                nextAttack = 30;
            });
        });
    }

    private static boolean k(ThreadLocalRandom r) {
        return r.nextInt(4) == 0;
    }

    // ================================================================= fin

    private void defeat() {
        if (ended) return;
        ended = true;
        Location c = boss.getLocation().clone();
        boss.setAI(false);
        csound(c, "spider.screech", 4f, 0.4f, "entity.spider.death");
        titleAll("title-defeat", "subtitle-defeat");
        for (Entity m : minions) m.remove();
        minions.clear();
        rig.poseTarget = -1;
        float from = size;
        // elle se recroqueville (les pattes se replient), la soie part en morceaux
        BukkitTask[] task = new BukkitTask[1];
        int[] i = {0};
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = i[0]++;
            if (k >= 50 || boss == null || !boss.isValid()) {
                task[0].cancel();
                c.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, c, 3, 1, 1, 1, 0, null, true);
                WeaponFx fx = fx();
                if (fx != null) fx.boom(c.clone().add(0, 1.5, 0), ABYSS, 8);
                fireworks(c);
                cleanup();
                rewards();
                plugin.spiderEnded();
                return;
            }
            size = from * (1 - k / 70f);
            rig.update(boss.getLocation(), boss.getBodyYaw() + k * 6, size);
            if (k % 5 == 0) c.getWorld().spawnParticle(Particle.BLOCK, c.clone().add(0, 1, 0), 20, 1.5, 1, 1.5, 0, Material.COBWEB.createBlockData(), true);
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
            plugin.loot().reward(p, true);
            if (Bukkit.getPluginManager().isPluginEnabled("SmpTitles")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "titre win " + p.getName() + " reine");
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
        if (!"spider.stopped".equals(msgKey) && !damage.isEmpty() && plugin.guildTop() != null) plugin.guildTop().record(damage, false);
        if (!"spider.stopped".equals(msgKey)) questProgress(false, 0);
        cleanup();
        plugin.spiderEnded();
    }

    /** Quêtes de SmpEvents : dégâts à la Reine, victoire (cible REINE). */
    private void questProgress(boolean won, double minDamage) {
        if (!Bukkit.getPluginManager().isPluginEnabled("SmpEvents")) return;
        for (var e : damage.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            long dmg = Math.round(e.getValue());
            if (dmg > 0) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "smpevents progress " + p.getName() + " BOSS_DAMAGE " + dmg + " REINE");
            if (won && e.getValue() >= minDamage) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "smpevents progress " + p.getName() + " BOSS_WIN 1 REINE");
        }
    }

    private double extraShare() {
        return Math.max(0, cfg("extra-player-health", 0.7));
    }

    private double hpFor(int n) {
        return perPlayer() * (1 + extraShare() * Math.max(0, n - 1));
    }

    private double perPlayer() {
        return Math.max(1, cfg("health-per-player", 4000));
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
        if (rig != null) rig.remove();
        for (Slime z : hitboxes) z.remove();
        hitboxes.clear();
        for (Entity e : props) e.remove();
        props.clear();
        for (Entity e : anchors.keySet()) e.remove();
        anchors.clear();
        for (Entity m : minions) m.remove();
        minions.clear();
        for (Block b : webs) if (b.getType() == Material.COBWEB) b.setType(Material.AIR, false);
        webs.clear();
        if (boss != null) boss.remove();
    }

    // ================================================================= outils

    public boolean inFight(Player p) {
        return !ended && p.getWorld() == altar.getWorld() && horizontalDist(p.getLocation(), altar) <= cfg("arena-radius", 18) + 12
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

    private void setScale(float s) {
        if (boss == null) return;
        AttributeInstance i = boss.getAttribute(Attribute.SCALE);
        if (i != null) i.setBaseValue(Math.min(16, s * cfg("body-hitbox", 0.45)));
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
        World w = l.getWorld();
        double base = altar.getY() + 1;
        Location probe = l.clone();
        probe.setY(base + 3);
        for (int i = 0; i < 10 && probe.getBlock().isPassable(); i++) probe.add(0, -1, 0);
        Location out = l.clone();
        out.setY(!probe.getBlock().isPassable() ? probe.getBlockY() + 1 : base);
        if (w == null) out.setY(base);
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

    /** Cercle d'avertissement au sol (cercle magique + anneau avec le pack, particules pour tous). */
    private void ring(Location c, double r, Color color, int ticks) {
        WeaponFx fx = fx();
        if (fx != null) {
            fx.tpop(c.clone().add(0, 0.12, 0), "w_ring_flat", color, (float) (r * 2.25), 1, (float) (r * 2.25), 3, Math.max(1, ticks - 6), 3, Display.Billboard.FIXED, null);
            fx.tpop(c.clone().add(0, 0.1, 0), "w_web_flat", color, (float) (r * 1.9), 1, (float) (r * 1.9), 4, Math.max(1, ticks - 7), 3, Display.Billboard.FIXED, null);
        }
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
        double arena = cfg("arena-radius", 18);
        int pts = (int) (arena * 4);
        int off = tick / 10;
        Particle.DustOptions d = new Particle.DustOptions(phase == 3 ? RED_EYE : ABYSS, 1.0f);
        for (int k = 0; k < pts; k++) {
            if ((k + off) % 2 != 0) continue;
            double a = Math.PI * 2 * k / pts;
            Location l = ground(altar.clone().add(0.5 + Math.cos(a) * arena, 0, 0.5 + Math.sin(a) * arena)).add(0, 0.2, 0);
            altar.getWorld().spawnParticle(Particle.DUST, l, 1, 0.05, 0.1, 0.05, 0, d, true);
        }
    }

    /** Onde de choc qui repousse tout le monde. */
    private void shockwave(Location c, double r, double push, double dmg) {
        sound(c, "entity.generic.explode", 3f, 0.7f);
        csound(c, "spider.screech", 2f, 0.8f, "entity.spider.ambient");
        shake(c, r * 2.5);
        WeaponFx sfx = fx();
        if (sfx != null) {
            Color col = phase == 3 ? RED_EYE : ABYSS;
            sfx.wave(c.clone().add(0, 0.2, 0), "w_shock_flat", col, 1, (float) (r * 2.2), 10, null, Display.Billboard.FIXED);
            sfx.wave(c.clone().add(0, 0.3, 0), "w_web_flat", WEB, 1, (float) (r * 1.8), 9, null, Display.Billboard.FIXED);
            sfx.boom(c.clone().add(0, 1.5, 0), col, (float) Math.max(4, r));
        }
        repeat(1, 10, i -> {
            double rr = r * (i + 1) / 10.0;
            int pts = (int) Math.max(24, rr * 8);
            for (int k = 0; k < pts; k++) {
                double a = Math.PI * 2 * k / pts;
                c.getWorld().spawnParticle(Particle.CLOUD, c.clone().add(Math.cos(a) * rr, 0.3, Math.sin(a) * rr), 1, 0, 0.05, 0, 0.01, null, true);
                if (k % 3 == 0) c.getWorld().spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * rr, 0.5, Math.sin(a) * rr), 1, 0, 0.1, 0, 0,
                        new Particle.DustOptions(ABYSS, 1.2f), true);
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
        if (plugin.getConfig().getBoolean("effects.screen-shake-everything", false))
            for (Player p : nearby(c, r)) p.playHurtAnimation((float) ThreadLocalRandom.current().nextDouble(360));
        Weapons w = plugin.weapons();
        if (w != null) w.ultimates.quake(c, r, 12, 3.5f);
    }

    private void damageNumber(double amount) {
        if (boss == null || tick == lastNumber && amount < 4) return;
        lastNumber = tick;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location l = boss.getLocation().add(r.nextDouble(-0.6, 0.6) * size, size * (1.0 + r.nextDouble(0.6)), r.nextDouble(-0.6, 0.6) * size);
        String txt = (amount >= 15 ? "<gradient:#7fd3ff:#3a6bff><b>" : amount >= 8 ? "<#7fb6ff><b>" : "<#bcd6ff>")
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
                plugin.getLogger().warning("Erreur pendant une attaque de la Reine : " + t);
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

    /** Son perso du pack (ceux qui l'ont), son vanilla pour les autres. */
    private static void csound(Location l, String custom, float volume, float pitch, String vanilla) {
        double r = 16 * Math.max(1, volume);
        for (Player o : l.getWorld().getPlayers()) {
            if (o.getLocation().distanceSquared(l) > r * r) continue;
            if (PackStatus.has(o)) o.playSound(l, "limitless:" + custom, org.bukkit.SoundCategory.HOSTILE, Math.min(volume, 3f), pitch);
            else if (vanilla != null) o.playSound(Sound.sound(Key.key("minecraft", vanilla), Sound.Source.HOSTILE, volume, pitch), l.getX(), l.getY(), l.getZ());
        }
    }

    /** Particules seulement pour ceux qui n'ont pas le pack. */
    private static void fallback(Location at, Particle pa, int n, double ox, double oy, double oz, double speed, Object data) {
        List<Player> to = new ArrayList<>();
        for (Player o : at.getWorld().getPlayers()) if (!PackStatus.has(o) && o.getLocation().distanceSquared(at) <= 160 * 160) to.add(o);
        if (to.isEmpty()) return;
        var pb = new com.destroystokyo.paper.ParticleBuilder(pa).receivers(to).location(at).count(n).offset(ox, oy, oz).extra(speed).force(true);
        if (data != null) pb.data(data);
        pb.spawn();
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
                m.addEffect(FireworkEffect.builder().withColor(Color.fromRGB(40, 90, 255), Color.WHITE).withFade(Color.BLACK)
                        .with(FireworkEffect.Type.BALL_LARGE).trail(true).flicker(true).build());
                m.setPower(1);
                f.setFireworkMeta(m);
            });
        }
    }
}
