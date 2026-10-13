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
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
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
 * Le Roi Citrouille. Une créature agrandie (vie gérée par le plugin, au-delà de la limite de Minecraft),
 * des citrouilles en orbite, 3 phases, des attaques toujours annoncées par un cercle au sol.
 */
public final class PumpkinKing {

    private enum Attack {
        BARRAGE, VINES, FLAME_WAVE, MINIONS, SEED_RAIN, VORTEX, SPIKES, SCYTHE, CHARGE, METEOR
    }

    private final SmpBoss plugin;
    private final Location altar;
    private final NamespacedKey bossKey, minionKey, rootKey;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final List<ItemDisplay> orbit = new ArrayList<>();
    /** lanternes qui marquent le bord de l'arène */
    private final List<ItemDisplay> border = new ArrayList<>();
    /** affichages temporaires des attaques (retirés à la fin quoi qu'il arrive) */
    private final Set<Entity> props = new HashSet<>();
    private ItemDisplay crown;
    private int lastNumber;
    private final Set<Entity> minions = new HashSet<>();
    private final Map<UUID, Double> damage = new HashMap<>();

    private Zombie boss;
    private BossBar bar;
    private double hp, maxHp;
    /** nombre de combattants pris en compte pour la vie (ne fait qu'augmenter) */
    private int fighters;
    private DamageBoard board;
    private int phase = 1;
    private int tick;
    private int invulnerable;
    private int nextAttack = 80;
    private Attack last;
    private int lonely;
    private boolean ended;
    private float scale;

    PumpkinKing(SmpBoss plugin, Location altar) {
        this.plugin = plugin;
        this.altar = altar.clone();
        this.bossKey = new NamespacedKey(plugin, "boss");
        this.minionKey = new NamespacedKey(plugin, "minion");
        this.rootKey = new NamespacedKey(plugin, "vines_root");
    }

    private double cfg(String path, double def) {
        return plugin.getConfig().getDouble("boss." + path, def);
    }

    public boolean isBoss(Entity e) {
        return e != null && e == boss;
    }

    public boolean isMinion(Entity e) {
        return e != null && minions.contains(e);
    }

    public boolean active() {
        return !ended;
    }

    // ================================================================= invocation

    void start(Player summoner) {
        World w = altar.getWorld();
        Location top = altar.clone().add(0.5, 1, 0.5);
        String who = summoner == null ? "?" : summoner.getName();
        Bukkit.broadcast(parse(plugin.msg("boss.summoning").replace("<player>", SmpBoss.MM.escapeTags(who))));
        // 5 secondes d'invocation : la nuit s'épaissit, les lanternes du bord de l'arène s'allument une à une,
        // une colonne de flammes d'âmes monte de l'autel, éclairs, battements de cœur
        for (Player p : nearby(top, 50)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 110, 0, false, false, false));
        }
        double arena = cfg("arena-radius", 18);
        int lanterns = (int) Math.max(12, Math.min(28, arena * 1.1));
        repeat(4, lanterns, i -> {
            double a = Math.PI * 2 * i / lanterns;
            Location l = ground(altar.clone().add(0.5 + Math.cos(a) * arena, 0, 0.5 + Math.sin(a) * arena)).add(0, 1.6, 0);
            ItemDisplay d = w.spawn(l, ItemDisplay.class, e -> {
                e.setPersistent(false);
                e.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 3);
                e.setItemStack(new ItemStack(Material.JACK_O_LANTERN));
                e.setBrightness(new Display.Brightness(15, 15));
                e.setTeleportDuration(3);
                e.setViewRange(2f);
                e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.75f, 0.75f, 0.75f), new Quaternionf()));
            });
            border.add(d);
            w.spawnParticle(Particle.SOUL_FIRE_FLAME, l, 15, 0.2, 0.3, 0.2, 0.03, null, true);
            sound(l, "item.firecharge.use", 1f, 0.7f + i * 0.03f);
        }, null);
        repeat(10, 10, i -> {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            w.strikeLightningEffect(top.clone().add(r.nextDouble(-8, 8), 0, r.nextDouble(-8, 8)));
            for (double y = 0; y < 3 + i * 2.5; y += 0.5) {
                w.spawnParticle(Particle.SOUL_FIRE_FLAME, top.clone().add(0, y, 0), 3, 0.35, 0.1, 0.35, 0.01, null, true);
            }
            w.spawnParticle(Particle.LARGE_SMOKE, top, 30, 1, 0.3, 1, 0.02, null, true);
            w.spawnParticle(Particle.BLOCK, top, 40, 2 + i * 0.4, 0.1, 2 + i * 0.4, 0, Material.DIRT.createBlockData(), true);
            sound(top, "entity.warden.heartbeat", 2f, 0.6f);
            shake(top, 25);
        }, () -> spawnBoss(top));
    }

    private void spawnBoss(Location at) {
        World w = at.getWorld();
        // vie proportionnelle au nombre de combattants : X PV par joueur (ceux qui sont là au réveil,
        // puis chaque nouveau joueur qui le frappe en rajoute)
        fighters = Math.max(1, nearby(at, 45).size());
        maxHp = perPlayer() * fighters;
        hp = maxHp;
        if (plugin.getConfig().getBoolean("boss.damage-sidebar", true)) board = new DamageBoard(plugin);
        scale = (float) cfg("scale", 3.0);
        boss = w.spawn(at, Zombie.class, z -> {
            z.setPersistent(false);
            z.setRemoveWhenFarAway(false);
            z.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 1);
            z.setAdult();
            z.setShouldBurnInDay(false);
            z.setCanPickupItems(false);
            z.setAI(false);
            base(z, Attribute.MAX_HEALTH, 1024);
            z.setHealth(1024);
            base(z, Attribute.SCALE, 0.4);
            base(z, Attribute.MOVEMENT_SPEED, cfg("speed", 0.24));
            base(z, Attribute.ATTACK_DAMAGE, cfg("melee-damage", 9));
            base(z, Attribute.KNOCKBACK_RESISTANCE, 1);
            base(z, Attribute.FOLLOW_RANGE, 48);
            EntityEquipment eq = z.getEquipment();
            eq.setHelmet(new ItemStack(Material.JACK_O_LANTERN));
            eq.setChestplate(leather(Material.LEATHER_CHESTPLATE, 0x2f4a1e));
            eq.setLeggings(leather(Material.LEATHER_LEGGINGS, 0x3a2a14));
            eq.setBoots(leather(Material.LEATHER_BOOTS, 0x2a1a0c));
            eq.setItemInMainHand(new ItemStack(Material.NETHERITE_HOE));
            for (EquipmentSlot s : EquipmentSlot.values()) {
                try {
                    eq.setDropChance(s, 0f);
                } catch (IllegalArgumentException ignored) {
                }
            }
            z.customName(parse(plugin.msg("boss.name")));
            z.setCustomNameVisible(true);
        });
        // citrouilles en orbite
        int n = (int) cfg("orbit-count", 4);
        for (int i = 0; i < n; i++) {
            orbit.add(w.spawn(at, ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 2);
                d.setItemStack(new ItemStack(Material.JACK_O_LANTERN));
                d.setTeleportDuration(2);
                d.setInterpolationDuration(2);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setViewRange(3f);
            }));
        }
        // couronne dorée qui flotte au-dessus de sa tête
        crown = w.spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 2);
            d.setItemStack(new ItemStack(Material.GOLDEN_HELMET));
            d.setTeleportDuration(2);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setGlowing(true);
            d.setGlowColorOverride(Color.fromRGB(0xffc83d));
            d.setViewRange(3f);
        });
        bar = BossBar.bossBar(parse(plugin.msg("boss.bar-1")), 1f, BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_10);
        // il sort de terre en grandissant
        sound(at, "entity.wither.spawn", 3f, 0.7f);
        repeat(1, 40, i -> {
            if (boss == null || !boss.isValid()) return;
            setScale(0.4f + (scale - 0.4f) * (i + 1) / 40f);
            at.getWorld().spawnParticle(Particle.BLOCK, at, 25, 1.2, 0.2, 1.2, 0, Material.DIRT.createBlockData(), true);
        }, () -> {
            if (boss != null && boss.isValid()) boss.setAI(true);
            titleAll("boss.title-start", "boss.subtitle-start");
            shockwave(at, 12, 1.1, 0);
        });
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L));
    }

    private static void base(Zombie z, Attribute a, double v) {
        AttributeInstance i = z.getAttribute(a);
        if (i != null) i.setBaseValue(v);
    }

    private void setScale(float s) {
        if (boss == null) return;
        AttributeInstance i = boss.getAttribute(Attribute.SCALE);
        if (i != null) i.setBaseValue(s);
    }

    private static ItemStack leather(Material m, int rgb) {
        ItemStack it = new ItemStack(m);
        if (it.getItemMeta() instanceof LeatherArmorMeta lm) {
            lm.setColor(Color.fromRGB(rgb));
            it.setItemMeta(lm);
        }
        return it;
    }

    // ================================================================= boucle

    private void tick() {
        if (ended) return;
        if (boss == null || !boss.isValid()) {
            leave("boss.vanished");
            return;
        }
        tick++;
        Location c = boss.getLocation();
        World w = c.getWorld();
        if (invulnerable > 0) invulnerable--;

        // orbite des citrouilles
        double radius = 1.1 * scale, height = 0.75 * scale;
        for (int i = 0; i < orbit.size(); i++) {
            ItemDisplay d = orbit.get(i);
            if (!d.isValid()) continue;
            double a = tick * 0.06 * (phase == 3 ? 1.8 : 1) + Math.PI * 2 * i / orbit.size();
            Location l = c.clone().add(Math.cos(a) * radius, height + Math.sin(tick * 0.08 + i) * 0.3, Math.sin(a) * radius);
            l.setYaw((float) Math.toDegrees(-a));
            d.teleport(l);
        }
        // couronne
        if (crown != null && crown.isValid()) {
            Location cl = c.clone().add(0, scale * 1.95 + Math.sin(tick * 0.07) * 0.12, 0);
            cl.setYaw(tick * 2f);
            crown.teleport(cl);
            float cs = scale * 0.55f;
            if (Math.abs(crown.getTransformation().getScale().x - cs) > 0.01f) {
                crown.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(cs, cs, cs), new Quaternionf()));
            }
        }
        // bord de l'arène : lanternes qui flottent + cercle de flammes
        if (tick % 2 == 0) {
            for (int i = 0; i < border.size(); i++) {
                ItemDisplay d = border.get(i);
                if (!d.isValid()) continue;
                Location l = d.getLocation();
                l.setY(l.getY() + Math.sin(tick * 0.06 + i) * 0.03);
                l.setYaw(l.getYaw() + 4f);
                d.teleport(l);
            }
        }
        if (tick % 10 == 0) borderRing();
        if (tick % 10 == 0) {
            bar.name(parse(plugin.msg("boss.bar-" + phase) + plugin.msg("boss.bar-hp").replace("<hp>", String.valueOf((long) Math.ceil(Math.max(0, hp))))
                    .replace("<max>", String.valueOf((long) maxHp))));
        }
        if (tick % 5 == 0) {
            w.spawnParticle(phase == 3 ? Particle.SOUL_FIRE_FLAME : Particle.FLAME, c.clone().add(0, scale * 1.6, 0),
                    6, 0.4 * scale, 0.3, 0.4 * scale, 0.01, null, true);
            w.spawnParticle(Particle.SMOKE, c.clone().add(0, scale, 0), 4, 0.5, 0.5, 0.5, 0.01, null, true);
        }
        // barre de vie (visible par ceux qui sont proches)
        List<Player> near = nearby(c, 60);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (near.contains(p)) p.showBossBar(bar);
            else p.hideBossBar(bar);
        }
        bar.progress((float) Math.max(0, Math.min(1, hp / maxHp)));
        if (board != null && tick % 10 == 0) board.update(near, hp, maxHp, damage, minPct());

        // laisse : il reste dans son arène
        double arena = cfg("arena-radius", 18);
        if (c.getWorld() != altar.getWorld() || c.distanceSquared(altar) > (arena + 8) * (arena + 8)) {
            boss.teleport(altar.clone().add(0.5, 1, 0.5));
        }
        // personne autour pendant 60 s : il repart
        if (tick % 20 == 0) {
            lonely = near.isEmpty() ? lonely + 1 : 0;
            if (lonely >= 60) {
                leave("boss.left");
                return;
            }
            if (tick > cfg("max-minutes", 20) * 1200) {
                leave("boss.timeout");
                return;
            }
        }
        tickMinions();

        // attaques
        if (invulnerable > 0 || !boss.hasAI()) return;
        if (--nextAttack <= 0) {
            nextAttack = (int) cfg("attack-interval." + phase, phase == 1 ? 110 : phase == 2 ? 90 : 70);
            List<Attack> pool = switch (phase) {
                case 1 -> List.of(Attack.BARRAGE, Attack.VINES, Attack.SPIKES);
                case 2 -> List.of(Attack.BARRAGE, Attack.VINES, Attack.FLAME_WAVE, Attack.MINIONS, Attack.SPIKES, Attack.SCYTHE, Attack.CHARGE);
                default -> List.of(Attack.FLAME_WAVE, Attack.MINIONS, Attack.SEED_RAIN, Attack.VORTEX, Attack.SPIKES, Attack.SCYTHE,
                        Attack.CHARGE, Attack.METEOR, Attack.METEOR);
            };
            Attack a;
            do {
                a = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
            } while (a == last && pool.size() > 1);
            last = a;
            switch (a) {
                case BARRAGE -> barrage();
                case VINES -> vines();
                case FLAME_WAVE -> flameWave();
                case MINIONS -> minions();
                case SEED_RAIN -> seedRain();
                case VORTEX -> vortex();
                case SPIKES -> spikes();
                case SCYTHE -> scythe();
                case CHARGE -> charge();
                case METEOR -> meteor();
            }
        }
    }

    // ================================================================= dégâts reçus

    /** Un joueur frappe le boss. Renvoie false si le coup est ignoré (invulnérable). */
    boolean hurt(Player by, double amount) {
        if (ended || invulnerable > 0 || boss == null) return false;
        if (by != null) damage.merge(by.getUniqueId(), amount, Double::sum);
        // un joueur de plus sur le boss : sa vie grandit d'autant (le % de vie reste le même)
        if (damage.size() > fighters) {
            double old = maxHp;
            fighters = damage.size();
            maxHp = perPlayer() * fighters;
            hp = hp * maxHp / old;
            for (Player p : nearby(boss.getLocation(), 60)) {
                p.sendActionBar(parse(plugin.msg("boss.new-fighter").replace("<player>", SmpBoss.MM.escapeTags(by.getName()))
                        .replace("<hp>", String.valueOf((long) perPlayer()))));
            }
        }
        hp -= amount;
        damageNumber(amount);
        if (tick % 3 == 0) sound(boss.getLocation(), "block.wood.break", 1.2f, 0.6f);
        boss.getWorld().spawnParticle(Particle.BLOCK, boss.getLocation().add(0, scale, 0), 12, 0.4 * scale, 0.5 * scale,
                0.4 * scale, 0, Material.PUMPKIN.createBlockData(), true);
        double ratio = hp / maxHp;
        if (hp <= 0) {
            defeat();
        } else if (phase == 1 && ratio <= cfg("phase-2-at", 0.66)) {
            changePhase(2);
        } else if (phase == 2 && ratio <= cfg("phase-3-at", 0.33)) {
            changePhase(3);
        }
        return true;
    }

    private void changePhase(int to) {
        phase = to;
        invulnerable = 70;
        boss.setAI(false);
        Location c = boss.getLocation();
        sound(c, "entity.ravager.roar", 3f, 0.6f);
        sound(c, "entity.generic.explode", 3f, 0.7f);
        c.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, c.clone().add(0, scale, 0), 2, 0.5, 0.5, 0.5, 0, null, true);
        float from = scale;
        float target = (float) cfg("scale-phase-" + to, to == 2 ? 3.6 : 4.2);
        repeat(1, 50, i -> {
            if (boss == null || !boss.isValid()) return;
            scale = from + (target - from) * (i + 1) / 50f;
            setScale(scale);
            boss.getWorld().spawnParticle(to == 3 ? Particle.SOUL_FIRE_FLAME : Particle.FLAME, boss.getLocation().add(0, scale, 0),
                    20, scale * 0.5, scale * 0.6, scale * 0.5, 0.05, null, true);
        }, () -> {
            if (boss != null && boss.isValid()) boss.setAI(true);
            nextAttack = 30;
        });
        bar.color(to == 2 ? BossBar.Color.RED : BossBar.Color.PURPLE);
        for (Player p : nearby(c, 40)) p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 50, 0, false, false, false));
        for (int i = 0; i < 3; i++) {
            ThreadLocalRandom rr = ThreadLocalRandom.current();
            c.getWorld().strikeLightningEffect(c.clone().add(rr.nextDouble(-6, 6), 0, rr.nextDouble(-6, 6)));
        }
        later(48, () -> {
            if (boss != null && boss.isValid()) shockwave(boss.getLocation(), 10, 1.5, cfg("damage.phase-shockwave", 4));
        });
        if (to == 3) {
            for (ItemDisplay d : orbit) if (d.isValid()) d.setItemStack(new ItemStack(Material.SOUL_LANTERN));
            for (ItemDisplay d : border) if (d.isValid()) d.setItemStack(new ItemStack(Material.SOUL_LANTERN));
            if (crown != null && crown.isValid()) crown.setGlowColorOverride(Color.fromRGB(0x7ff3ff));
        }
        titleAll("boss.title-phase-" + to, "boss.subtitle-phase-" + to);
        if (to == 3) {
            AttributeInstance sp = boss.getAttribute(Attribute.MOVEMENT_SPEED);
            if (sp != null) sp.setBaseValue(cfg("speed", 0.24) * 1.25);
        }
    }

    // ================================================================= attaques

    /** Citrouilles explosives lancées en cloche sur 3 joueurs. */
    private void barrage() {
        List<Player> targets = nearby(boss.getLocation(), 30);
        java.util.Collections.shuffle(targets);
        sound(boss.getLocation(), "entity.witch.throw", 2f, 0.6f);
        for (Player p : targets.subList(0, Math.min(3 + (phase - 1), targets.size()))) {
            Location dest = p.getLocation().clone();
            double r = 3;
            ring(dest, r, Color.fromRGB(255, 120, 0), 34);
            Location from = boss.getLocation().add(0, scale * 1.7, 0);
            ItemDisplay ball = boss.getWorld().spawn(from, ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.setItemStack(new ItemStack(Material.JACK_O_LANTERN));
                d.setTeleportDuration(1);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1.3f, 1.3f, 1.3f),
                        new Quaternionf()));
            });
            int flight = 34;
            repeat(1, flight, i -> {
                double t = (i + 1) / (double) flight;
                Vector pos = from.toVector().multiply(1 - t).add(dest.toVector().multiply(t));
                pos.setY(pos.getY() + Math.sin(Math.PI * t) * 8);
                Location l = pos.toLocation(dest.getWorld());
                l.setYaw(i * 20f);
                ball.teleport(l);
                dest.getWorld().spawnParticle(Particle.FLAME, l, 3, 0.15, 0.15, 0.15, 0.01, null, true);
            }, () -> {
                ball.remove();
                explode(dest, r, cfg("damage.barrage", 8), true);
            });
        }
    }

    /** Lianes : un cercle sous chaque joueur ; ceux qui y sont encore sont immobilisés. */
    private void vines() {
        sound(boss.getLocation(), "block.vine.step", 2f, 0.5f);
        for (Player p : nearby(boss.getLocation(), 25)) {
            Location at = p.getLocation().clone();
            double r = 1.8;
            ring(at, r, Color.fromRGB(40, 160, 40), 26);
            later(26, () -> {
                at.getWorld().spawnParticle(Particle.BLOCK, at.clone().add(0, 0.5, 0), 60, r * 0.5, 0.6, r * 0.5, 0,
                        Material.MOSS_BLOCK.createBlockData(), true);
                sound(at, "block.big_dripleaf.tilt_down", 1.5f, 0.6f);
                for (Player v : nearby(at, r + 0.3)) {
                    v.damage(cfg("damage.vines", 4) * mult(), boss);
                    v.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 45, 6, false, false, true));
                    root(v, 45);
                }
            });
        }
    }

    /** Empêche de sauter pendant x ticks (lianes). */
    private void root(Player p, int ticks) {
        AttributeInstance j = p.getAttribute(Attribute.JUMP_STRENGTH);
        if (j == null) return;
        j.removeModifier(rootKey);
        j.addTransientModifier(new AttributeModifier(rootKey, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
        later(ticks, () -> j.removeModifier(rootKey));
    }

    /** Onde de flammes qui s'élargit : il faut sauter par-dessus. */
    private void flameWave() {
        Location c = boss.getLocation().clone();
        sound(c, "entity.blaze.shoot", 3f, 0.5f);
        ring(c, 2.5, Color.fromRGB(255, 60, 0), 16);
        Set<UUID> hit = new HashSet<>();
        int steps = 26;
        double maxR = cfg("arena-radius", 18);
        later(16, () -> repeat(1, steps, i -> {
            double r = 1.5 + (maxR - 1.5) * (i + 1) / steps;
            int pts = (int) Math.max(24, r * 7);
            for (int k = 0; k < pts; k++) {
                double a = Math.PI * 2 * k / pts;
                c.getWorld().spawnParticle(Particle.FLAME, c.clone().add(Math.cos(a) * r, 0.25, Math.sin(a) * r), 1, 0, 0.05, 0, 0.005,
                        null, true);
            }
            for (Player p : nearby(c, r + 1)) {
                double d = Math.hypot(p.getLocation().getX() - c.getX(), p.getLocation().getZ() - c.getZ());
                if (Math.abs(d - r) > 0.9 || hit.contains(p.getUniqueId())) continue;
                if (!p.isOnGround()) continue; // sauté par-dessus
                hit.add(p.getUniqueId());
                p.damage(cfg("damage.flame-wave", 7) * mult(), boss);
                p.setFireTicks(60);
            }
        }, null));
    }

    /** Mini-citrouilles kamikazes. */
    private void minions() {
        Location c = boss.getLocation();
        sound(c, "entity.evoker.prepare_summon", 2f, 0.8f);
        int n = (int) cfg("minions", 4) + (phase == 3 ? 2 : 0);
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            Location at = c.clone().add(Math.cos(a) * 3, 0.2, Math.sin(a) * 3);
            Zombie m = c.getWorld().spawn(at, Zombie.class, z -> {
                z.setPersistent(false);
                z.getPersistentDataContainer().set(minionKey, PersistentDataType.LONG, System.currentTimeMillis());
                z.setAdult();
                z.setShouldBurnInDay(false);
                z.setCanPickupItems(false);
                base(z, Attribute.SCALE, 0.55);
                base(z, Attribute.MAX_HEALTH, 12);
                z.setHealth(12);
                base(z, Attribute.MOVEMENT_SPEED, 0.33);
                z.getEquipment().setHelmet(new ItemStack(Material.CARVED_PUMPKIN));
                z.getEquipment().setHelmetDropChance(0f);
                z.customName(parse(plugin.msg("boss.minion-name")));
            });
            minions.add(m);
            c.getWorld().spawnParticle(Particle.LARGE_SMOKE, at, 15, 0.3, 0.3, 0.3, 0.02, null, true);
        }
    }

    private void tickMinions() {
        long now = System.currentTimeMillis();
        minions.removeIf(m -> {
            if (!m.isValid()) return true;
            Long born = m.getPersistentDataContainer().get(minionKey, PersistentDataType.LONG);
            if (born != null && now - born > 25000) {
                m.remove();
                return true;
            }
            for (Player p : nearby(m.getLocation(), 1.7)) {
                explode(m.getLocation(), 2.8, cfg("damage.minion", 6), false);
                m.remove();
                return true;
            }
            return false;
        });
    }

    /** Pluie de graines de feu dans toute l'arène. */
    private void seedRain() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double arena = cfg("arena-radius", 18);
        sound(boss.getLocation(), "entity.ghast.warn", 3f, 0.6f);
        List<Location> spots = new ArrayList<>();
        for (Player p : nearby(boss.getLocation(), 30)) spots.add(p.getLocation().clone());
        while (spots.size() < cfg("seed-rain-count", 14)) {
            double a = r.nextDouble() * Math.PI * 2, d = Math.sqrt(r.nextDouble()) * arena;
            Location l = altar.clone().add(0.5 + Math.cos(a) * d, 0, 0.5 + Math.sin(a) * d);
            l.setY(l.getWorld().getHighestBlockYAt(l) + 1);
            if (Math.abs(l.getY() - altar.getY()) > 8) l.setY(altar.getY() + 1);
            spots.add(l);
        }
        int i = 0;
        for (Location l : spots) {
            int delay = 4 * (i++ % 6);
            later(delay, () -> {
                ring(l, 2, Color.fromRGB(255, 160, 0), 30);
                later(30, () -> {
                    l.getWorld().spawnParticle(Particle.LAVA, l, 12, 0.6, 0.2, 0.6, 0, null, true);
                    l.getWorld().spawnParticle(Particle.FLAME, l.clone().add(0, 2, 0), 30, 0.2, 2, 0.2, 0.02, null, true);
                    sound(l, "entity.blaze.hurt", 1.2f, 0.5f);
                    for (Player p : nearby(l, 2.2)) {
                        p.damage(cfg("damage.seed-rain", 6) * mult(), boss);
                        p.setFireTicks(40);
                    }
                });
            });
        }
    }

    /** Aspiration vers le boss, puis frappe au sol. */
    private void vortex() {
        Location c = boss.getLocation().clone();
        boss.setAI(false);
        sound(c, "entity.warden.sonic_charge", 3f, 0.6f);
        double slamR = 5.5;
        ring(c, slamR, Color.fromRGB(200, 0, 0), 40);
        repeat(1, 32, i -> {
            for (Player p : nearby(c, 16)) {
                Vector pull = c.toVector().subtract(p.getLocation().toVector()).setY(0);
                if (pull.lengthSquared() < 1) continue;
                p.setVelocity(p.getVelocity().add(pull.normalize().multiply(0.09)));
            }
            c.getWorld().spawnParticle(Particle.PORTAL, c.clone().add(0, 1, 0), 30, 4, 1, 4, 0.5, null, true);
        }, () -> later(8, () -> {
            sound(c, "entity.warden.sonic_boom", 3f, 0.8f);
            c.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, c, 1, 0, 0, 0, 0, null, true);
            for (Player p : nearby(c, slamR)) {
                p.damage(cfg("damage.slam", 12) * mult(), boss);
                p.setVelocity(p.getLocation().toVector().subtract(c.toVector()).setY(0).normalize().multiply(1.2).setY(0.8));
            }
            if (boss != null && boss.isValid() && !ended) boss.setAI(true);
        }));
    }

    private void explode(Location at, double r, double dmg, boolean fire) {
        at.getWorld().spawnParticle(Particle.EXPLOSION, at.clone().add(0, 0.5, 0), 3, 0.6, 0.3, 0.6, 0, null, true);
        at.getWorld().spawnParticle(Particle.FLAME, at.clone().add(0, 0.5, 0), 40, r * 0.4, 0.3, r * 0.4, 0.05, null, true);
        sound(at, "entity.generic.explode", 2f, 0.9f);
        for (Player p : nearby(at, r)) {
            p.damage(dmg * mult(), boss);
            if (fire) p.setFireTicks(40);
            Vector kb = p.getLocation().toVector().subtract(at.toVector()).setY(0);
            if (kb.lengthSquared() > 0.01) p.setVelocity(kb.normalize().multiply(0.6).setY(0.35));
        }
    }

    private double mult() {
        return cfg("damage.multiplier", 1.0) * (phase == 3 ? 1.2 : 1.0);
    }

    /** Lignes de citrouilles qui jaillissent du sol vers chaque joueur. */
    private void spikes() {
        Location c = boss.getLocation().clone();
        sound(c, "block.rooted_dirt.break", 2f, 0.5f);
        sound(c, "entity.evoker.prepare_attack", 2f, 0.7f);
        List<Player> targets = nearby(c, 26);
        java.util.Collections.shuffle(targets);
        int lines = Math.min(targets.size(), 2 + phase);
        Set<UUID> hit = new HashSet<>();
        for (int n = 0; n < Math.max(1, lines); n++) {
            Vector dir = targets.isEmpty() ? Vector.getRandom().subtract(new Vector(0.5, 0.5, 0.5)).setY(0)
                    : targets.get(n).getLocation().toVector().subtract(c.toVector()).setY(0);
            if (dir.lengthSquared() < 0.01) dir = new Vector(1, 0, 0);
            dir.normalize();
            Vector d = dir;
            int len = 18;
            // avertissement : une traînée rouge au sol
            repeat(3, 7, i -> {
                for (int k = 1; k <= len; k++) {
                    Location l = ground(c.clone().add(d.clone().multiply(k))).add(0, 0.15, 0);
                    c.getWorld().spawnParticle(Particle.DUST, l, 2, 0.25, 0, 0.25, 0, new Particle.DustOptions(Color.fromRGB(200, 40, 0), 1.4f), true);
                }
            }, () -> repeat(1, len, k -> {
                Location l = ground(c.clone().add(d.clone().multiply(k + 1)));
                spawnSpike(l);
                c.getWorld().spawnParticle(Particle.BLOCK, l.clone().add(0, 0.3, 0), 18, 0.4, 0.2, 0.4, 0, Material.ROOTED_DIRT.createBlockData(), true);
                if (k % 2 == 0) sound(l, "block.big_dripleaf.break", 1.4f, 0.5f);
                for (Player p : nearby(l, 1.6)) {
                    if (!hit.add(p.getUniqueId())) continue;
                    p.damage(cfg("damage.spikes", 7) * mult(), boss);
                    p.setVelocity(p.getVelocity().setY(0.95));
                }
            }, null));
        }
    }

    private void spawnSpike(Location l) {
        BlockDisplay d = l.getWorld().spawn(l, BlockDisplay.class, e -> {
            e.setPersistent(false);
            e.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 4);
            e.setBlock((ThreadLocalRandom.current().nextBoolean() ? Material.CARVED_PUMPKIN : Material.JACK_O_LANTERN).createBlockData());
            e.setTransformation(new Transformation(new Vector3f(-0.45f, -1.1f, -0.45f), new Quaternionf(), new Vector3f(0.9f, 0.9f, 0.9f),
                    new Quaternionf()));
        });
        props.add(d);
        float yaw = (float) ThreadLocalRandom.current().nextDouble(-0.4, 0.4);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!d.isValid()) return;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(3);
            d.setTransformation(new Transformation(new Vector3f(-0.55f, 0.1f, -0.55f), new Quaternionf().rotateY(yaw).rotateX(yaw * 0.5f),
                    new Vector3f(1.1f, 1.1f, 1.1f), new Quaternionf()));
        }, 2L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!d.isValid()) return;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(10);
            d.setTransformation(new Transformation(new Vector3f(-0.45f, -1.2f, -0.45f), new Quaternionf(), new Vector3f(0.9f, 0.9f, 0.9f),
                    new Quaternionf()));
        }, 30L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            d.remove();
            props.remove(d);
        }, 42L);
    }

    /** Il lève sa faux puis balaie tout autour de lui : il faut sortir du cercle. */
    private void scythe() {
        Location c = boss.getLocation().clone();
        boss.setAI(false);
        double r = cfg("scythe-radius", 6.5);
        sound(c, "item.trident.riptide_3", 2f, 0.5f);
        sound(c, "entity.ravager.roar", 1.5f, 1.2f);
        ring(c, r, Color.fromRGB(230, 20, 20), 26);
        later(26, () -> {
            if (boss == null || !boss.isValid()) return;
            sound(c, "entity.player.attack.sweep", 3f, 0.5f);
            sound(c, "entity.wither.shoot", 2f, 0.6f);
            Set<UUID> hit = new HashSet<>();
            repeat(1, 10, i -> {
                if (boss == null || !boss.isValid()) return;
                float yaw = boss.getYaw() + 36f;
                boss.setRotation(yaw, 0);
                double a = Math.toRadians(i * 36);
                for (double d = 1.2; d <= r; d += 0.8) {
                    Location l = c.clone().add(Math.cos(a) * d, scale * 0.5, Math.sin(a) * d);
                    c.getWorld().spawnParticle(Particle.SWEEP_ATTACK, l, 1, 0, 0, 0, 0, null, true);
                    c.getWorld().spawnParticle(Particle.DUST, l, 2, 0.1, 0.2, 0.1, 0,
                            new Particle.DustOptions(phase == 3 ? Color.fromRGB(110, 230, 255) : Color.fromRGB(255, 140, 20), 1.6f), true);
                }
                for (Player p : nearby(c, r)) {
                    if (!hit.add(p.getUniqueId())) continue;
                    p.damage(cfg("damage.scythe", 10) * mult(), boss);
                    Vector kb = p.getLocation().toVector().subtract(c.toVector()).setY(0);
                    if (kb.lengthSquared() > 0.01) p.setVelocity(kb.normalize().multiply(1.3).setY(0.5));
                }
            }, () -> {
                if (boss != null && boss.isValid() && !ended && invulnerable <= 0) boss.setAI(true);
            });
        });
    }

    /** Il fixe un joueur, puis fonce en ligne droite. S'il percute un mur, il reste sonné. */
    private void charge() {
        List<Player> targets = nearby(boss.getLocation(), 24);
        if (targets.isEmpty()) return;
        Player target = targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
        Location c = boss.getLocation().clone();
        Vector dir = target.getLocation().toVector().subtract(c.toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) return;
        dir.normalize();
        Vector d = dir;
        boss.setAI(false);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
        boss.setRotation(yaw, 0);
        sound(c, "entity.ravager.roar", 2.5f, 0.8f);
        int len = 16;
        Vector side = new Vector(-d.getZ(), 0, d.getX());
        repeat(3, 8, i -> {
            for (int k = 1; k <= len; k++) {
                for (int s = -1; s <= 1; s++) {
                    Location l = ground(c.clone().add(d.clone().multiply(k)).add(side.clone().multiply(s * 0.9))).add(0, 0.15, 0);
                    c.getWorld().spawnParticle(Particle.DUST, l, 1, 0.1, 0, 0.1, 0, new Particle.DustOptions(Color.fromRGB(255, 60, 0), 1.3f), true);
                }
            }
            c.getWorld().spawnParticle(Particle.CLOUD, c.clone().add(0, 0.3, 0), 6, 0.6, 0.1, 0.6, 0.02, null, true);
        }, () -> {
            if (boss == null || !boss.isValid()) return;
            Set<UUID> hit = new HashSet<>();
            boolean[] stopped = {false};
            sound(c, "entity.horse.gallop", 3f, 0.5f);
            repeat(1, 12, i -> {
                if (stopped[0] || boss == null || !boss.isValid()) return;
                Location cur = boss.getLocation();
                Location next = cur.clone().add(d.clone().multiply(1.4));
                next = ground(next);
                Location head = next.clone().add(0, 1.2, 0);
                double arena = cfg("arena-radius", 18);
                if (!head.getBlock().isPassable() || Math.abs(next.getY() - cur.getY()) > 2.5 || next.distanceSquared(altar) > arena * arena) {
                    stopped[0] = true;
                    if (!head.getBlock().isPassable()) stun(cur);
                    return;
                }
                next.setYaw(yaw);
                boss.teleport(next);
                cur.getWorld().spawnParticle(Particle.FLAME, cur.clone().add(0, 0.5, 0), 12, 0.6, 0.4, 0.6, 0.02, null, true);
                cur.getWorld().spawnParticle(Particle.BLOCK, cur, 15, 0.8, 0.1, 0.8, 0, Material.DIRT.createBlockData(), true);
                for (Player p : nearby(next, 1.4 + scale * 0.35)) {
                    if (!hit.add(p.getUniqueId())) continue;
                    p.damage(cfg("damage.charge", 9) * mult(), boss);
                    p.setVelocity(d.clone().multiply(1.2).add(new Vector(0, 0.7, 0)));
                    sound(p.getLocation(), "entity.player.attack.knockback", 1.5f, 0.7f);
                }
            }, () -> later(stopped[0] ? 40 : 6, () -> {
                if (boss != null && boss.isValid() && !ended && invulnerable <= 0) boss.setAI(true);
            }));
        });
    }

    private void stun(Location at) {
        sound(at, "block.anvil.land", 2f, 0.5f);
        sound(at, "entity.zombie.attack_wooden_door", 2f, 0.6f);
        shake(at, 14);
        repeat(4, 10, i -> {
            if (boss == null || !boss.isValid()) return;
            Location h = boss.getLocation().add(0, scale * 2.1, 0);
            for (int k = 0; k < 5; k++) {
                double a = i * 0.6 + k * Math.PI * 2 / 5;
                h.getWorld().spawnParticle(Particle.CRIT, h.clone().add(Math.cos(a) * 0.7, 0, Math.sin(a) * 0.7), 1, 0, 0, 0, 0, null, true);
            }
        }, null);
        for (Player p : nearby(at, 25)) p.sendActionBar(parse(plugin.msg("boss.stunned")));
    }

    /** Une citrouille géante tombe du ciel sur le joueur qui fait le plus de dégâts. */
    private void meteor() {
        List<Player> near = nearby(boss.getLocation(), 30);
        if (near.isEmpty()) return;
        Player target = near.get(0);
        double best = -1;
        for (Player p : near) {
            double v = damage.getOrDefault(p.getUniqueId(), 0.0);
            if (v > best) {
                best = v;
                target = p;
            }
        }
        Location dest = ground(target.getLocation().clone());
        double r = 5;
        sound(boss.getLocation(), "entity.ghast.warn", 3f, 0.4f);
        sound(dest, "entity.wither.ambient", 2f, 0.5f);
        ring(dest, r, Color.fromRGB(140, 0, 0), 40);
        target.sendActionBar(parse(plugin.msg("boss.meteor-warning")));
        float sz = 4f;
        Location sky = dest.clone().add(0, 32, 0);
        BlockDisplay rock = dest.getWorld().spawn(sky, BlockDisplay.class, e -> {
            e.setPersistent(false);
            e.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 4);
            e.setBlock(Material.JACK_O_LANTERN.createBlockData());
            e.setBrightness(new Display.Brightness(15, 15));
            e.setTeleportDuration(1);
            e.setViewRange(4f);
            e.setTransformation(new Transformation(new Vector3f(-sz / 2, -sz / 2, -sz / 2), new Quaternionf(), new Vector3f(sz, sz, sz),
                    new Quaternionf()));
        });
        props.add(rock);
        repeat(1, 40, i -> {
            double t = (i + 1) / 40.0;
            Location l = sky.clone().add(0, -32 * t * t, 0).add(0, sz / 2, 0);
            l.setYaw(i * 9f);
            rock.teleport(l);
            dest.getWorld().spawnParticle(Particle.FLAME, l, 25, sz * 0.4, sz * 0.4, sz * 0.4, 0.05, null, true);
            dest.getWorld().spawnParticle(Particle.LARGE_SMOKE, l.clone().add(0, sz * 0.6, 0), 8, 1, 1, 1, 0.02, null, true);
            if (i % 8 == 0) sound(l, "entity.blaze.burn", 2f, 0.5f);
        }, () -> {
            rock.remove();
            props.remove(rock);
            dest.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, dest, 2, 1, 0.5, 1, 0, null, true);
            dest.getWorld().spawnParticle(Particle.BLOCK, dest.clone().add(0, 0.5, 0), 200, 2.5, 0.8, 2.5, 0,
                    Material.ORANGE_TERRACOTTA.createBlockData(), true);
            dest.getWorld().spawnParticle(Particle.LAVA, dest, 40, 3, 0.5, 3, 0, null, true);
            sound(dest, "entity.generic.explode", 4f, 0.5f);
            sound(dest, "entity.dragon_fireball.explode", 3f, 0.6f);
            shake(dest, 22);
            for (Player p : nearby(dest, r)) {
                p.damage(cfg("damage.meteor", 14) * mult(), boss);
                p.setFireTicks(80);
                Vector kb = p.getLocation().toVector().subtract(dest.toVector()).setY(0);
                if (kb.lengthSquared() < 0.01) kb = new Vector(0.1, 0, 0);
                p.setVelocity(kb.normalize().multiply(1.4).setY(0.9));
            }
            // braises qui restent au sol un moment
            repeat(5, 12, i -> {
                for (int k = 0; k < 18; k++) {
                    double a = Math.PI * 2 * k / 18;
                    dest.getWorld().spawnParticle(Particle.FLAME, dest.clone().add(Math.cos(a) * r * 0.8, 0.2, Math.sin(a) * r * 0.8), 1, 0.1, 0.1,
                            0.1, 0.01, null, true);
                }
            }, null);
        });
    }

    /** Onde de choc qui repousse tout le monde (invocation, changements de phase). */
    private void shockwave(Location c, double r, double push, double dmg) {
        sound(c, "entity.generic.explode", 3f, 0.6f);
        sound(c, "entity.warden.sonic_boom", 2f, 0.7f);
        shake(c, r * 2.5);
        repeat(1, 10, i -> {
            double rr = r * (i + 1) / 10.0;
            int pts = (int) Math.max(24, rr * 8);
            for (int k = 0; k < pts; k++) {
                double a = Math.PI * 2 * k / pts;
                c.getWorld().spawnParticle(Particle.CLOUD, c.clone().add(Math.cos(a) * rr, 0.3, Math.sin(a) * rr), 1, 0, 0.05, 0, 0.01, null, true);
                if (k % 3 == 0) {
                    c.getWorld().spawnParticle(phase == 3 ? Particle.SOUL_FIRE_FLAME : Particle.FLAME,
                            c.clone().add(Math.cos(a) * rr, 0.5, Math.sin(a) * rr), 1, 0, 0.1, 0, 0.01, null, true);
                }
            }
        }, null);
        for (Player p : nearby(c, r)) {
            if (dmg > 0) p.damage(dmg * mult(), boss);
            Vector kb = p.getLocation().toVector().subtract(c.toVector()).setY(0);
            if (kb.lengthSquared() < 0.01) kb = new Vector(0.1, 0, 0);
            p.setVelocity(kb.normalize().multiply(push).setY(0.55));
        }
    }

    /** Tremblement de caméra pour les joueurs proches. */
    private void shake(Location c, double r) {
        for (Player p : nearby(c, r)) p.playHurtAnimation((float) ThreadLocalRandom.current().nextDouble(360));
    }

    /** Cercle de flammes au sol qui marque le bord de l'arène. */
    private void borderRing() {
        double arena = cfg("arena-radius", 18);
        int pts = (int) (arena * 4);
        int off = tick / 10;
        for (int k = 0; k < pts; k++) {
            if ((k + off) % 2 != 0) continue;
            double a = Math.PI * 2 * k / pts;
            Location l = ground(altar.clone().add(0.5 + Math.cos(a) * arena, 0, 0.5 + Math.sin(a) * arena)).add(0, 0.2, 0);
            altar.getWorld().spawnParticle(phase == 3 ? Particle.SOUL_FIRE_FLAME : Particle.SMALL_FLAME, l, 1, 0.05, 0.1, 0.05, 0.005, null, true);
        }
    }

    /** Sol le plus proche (pour poser les effets à hauteur des pieds). */
    private Location ground(Location l) {
        World w = l.getWorld();
        int top = w.getHighestBlockYAt(l.getBlockX(), l.getBlockZ());
        double base = altar.getY() + 1;
        double y = Math.abs(top + 1 - base) <= 5 ? top + 1 : base;
        // sous un plafond (grotte, arène couverte) : on cherche le sol sous la position
        if (top + 1 - base > 5) {
            Location probe = l.clone();
            probe.setY(base + 3);
            for (int i = 0; i < 10 && probe.getBlock().isPassable(); i++) probe.add(0, -1, 0);
            if (!probe.getBlock().isPassable()) y = probe.getBlockY() + 1;
        }
        Location out = l.clone();
        out.setY(y);
        return out;
    }

    /** Chiffres de dégâts qui s'envolent au-dessus du boss. */
    private void damageNumber(double amount) {
        if (boss == null || tick == lastNumber && amount < 4) return;
        lastNumber = tick;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location l = boss.getLocation().add(r.nextDouble(-0.6, 0.6) * scale, scale * (1.2 + r.nextDouble(0.6)), r.nextDouble(-0.6, 0.6) * scale);
        String txt = (amount >= 15 ? "<gradient:#ffe259:#ffa751><b>" : amount >= 8 ? "<#ffb347><b>" : "<#ffd9a0>")
                + "-" + (amount >= 10 ? String.valueOf(Math.round(amount)) : String.format(java.util.Locale.ROOT, "%.1f", amount));
        TextDisplay d = l.getWorld().spawn(l, TextDisplay.class, e -> {
            e.setPersistent(false);
            e.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 4);
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

    /** À sa mort, il éclate en citrouilles qui volent dans tous les sens. */
    private void burst(Location c) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int n = 0; n < 18; n++) {
            Vector v = new Vector(r.nextDouble(-1, 1), r.nextDouble(0.6, 1.4), r.nextDouble(-1, 1)).multiply(0.55);
            ItemDisplay d = c.getWorld().spawn(c, ItemDisplay.class, e -> {
                e.setPersistent(false);
                e.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 4);
                e.setItemStack(new ItemStack(r.nextInt(3) == 0 ? Material.JACK_O_LANTERN : Material.PUMPKIN));
                e.setBrightness(new Display.Brightness(15, 15));
                e.setTeleportDuration(1);
            });
            props.add(d);
            Location[] pos = {c.clone()};
            Vector vel = v.clone();
            int[] t = {0};
            BukkitTask[] task = new BukkitTask[1];
            task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                t[0]++;
                if (t[0] > 40 || !d.isValid()) {
                    if (d.isValid()) d.getWorld().spawnParticle(Particle.BLOCK, d.getLocation(), 10, 0.2, 0.2, 0.2, 0,
                            Material.PUMPKIN.createBlockData(), true);
                    d.remove();
                    props.remove(d);
                    task[0].cancel();
                    return;
                }
                vel.setY(vel.getY() - 0.06);
                pos[0].add(vel);
                Location gl = ground(pos[0]);
                if (pos[0].getY() < gl.getY()) {
                    pos[0].setY(gl.getY());
                    vel.setY(-vel.getY() * 0.45);
                    vel.multiply(0.7);
                }
                pos[0].setYaw(t[0] * 18f);
                d.teleport(pos[0]);
                if (t[0] % 3 == 0) d.getWorld().spawnParticle(Particle.FLAME, pos[0], 1, 0.05, 0.05, 0.05, 0.01, null, true);
            }, 1L, 1L);
            tasks.add(task[0]);
        }
    }

    // ================================================================= fin

    private void defeat() {
        if (ended) return;
        ended = true;
        Location c = boss.getLocation().clone();
        boss.setAI(false);
        sound(c, "entity.wither.death", 3f, 0.8f);
        titleAll("boss.title-defeat", "boss.subtitle-defeat");
        for (Entity m : minions) m.remove();
        minions.clear();
        float from = scale;
        burst(c.clone().add(0, scale, 0));
        repeat(1, 50, i -> {
            if (boss == null || !boss.isValid()) return;
            if (i % 5 == 0) {
                for (double y = 0; y < 30; y += 1) {
                    c.getWorld().spawnParticle(Particle.END_ROD, c.clone().add(0, y, 0), 1, 0.15, 0.2, 0.15, 0, null, true);
                }
            }
            setScale(from * (1 - (i + 1) / 55f));
            boss.setRotation(i * 15f, 0);
            boss.getWorld().spawnParticle(Particle.FLAME, boss.getLocation().add(0, 1, 0), 15, 1, 1, 1, 0.08, null, true);
        }, () -> {
            c.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, c, 3, 1, 1, 1, 0, null, true);
            fireworks(c);
            cleanup();
            rewards();
            plugin.bossEnded();
        });
    }

    private void rewards() {
        double total = 0;
        for (double v : damage.values()) total += v;
        double minPct = minPct();
        List<Map.Entry<UUID, Double>> sorted = new ArrayList<>(damage.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        Bukkit.broadcast(parse(plugin.msg("boss.defeated")));
        for (int i = 0; i < Math.min(3, sorted.size()); i++) {
            var e = sorted.get(i);
            String name = Bukkit.getOfflinePlayer(e.getKey()).getName();
            Bukkit.broadcast(parse(plugin.msg("boss.top-line").replace("<rank>", String.valueOf(i + 1))
                    .replace("<player>", SmpBoss.MM.escapeTags(name == null ? "?" : name))
                    .replace("<percent>", String.valueOf(Math.round(e.getValue() * 100 / Math.max(1, total))))));
        }
        for (var e : sorted) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            if (e.getValue() < total * minPct) {
                p.sendMessage(parse(plugin.msg("boss.not-enough").replace("<percent>",
                        String.format(java.util.Locale.ROOT, "%.1f", e.getValue() * 100 / Math.max(1, total)))
                        .replace("<min>", String.valueOf(Math.round(minPct * 100)))));
                continue;
            }
            plugin.loot().reward(p);
        }
        plugin.store().save(true);
    }

    void leave(String msgKey) {
        if (ended) return;
        ended = true;
        if (boss != null && boss.isValid()) {
            Location c = boss.getLocation();
            c.getWorld().spawnParticle(Particle.LARGE_SMOKE, c, 80, 1.5, 2, 1.5, 0.05, null, true);
        }
        Bukkit.broadcast(parse(plugin.msg(msgKey)));
        cleanup();
        plugin.bossEnded();
    }

    private double perPlayer() {
        return Math.max(1, cfg("health-per-player", 1200));
    }

    private double minPct() {
        return plugin.getConfig().getDouble("loot.min-damage-percent", 5) / 100.0;
    }

    void cleanup() {
        if (board != null) {
            board.close();
            board = null;
        }
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        if (bar != null) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
        for (ItemDisplay d : orbit) d.remove();
        orbit.clear();
        for (ItemDisplay d : border) d.remove();
        border.clear();
        for (Entity e : props) e.remove();
        props.clear();
        if (crown != null) crown.remove();
        for (Entity m : minions) m.remove();
        minions.clear();
        if (boss != null) boss.remove();
    }

    // ================================================================= outils

    private List<Player> nearby(Location c, double r) {
        List<Player> out = new ArrayList<>();
        for (Player p : c.getWorld().getPlayers()) {
            if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) continue;
            if (p.getLocation().distanceSquared(c) <= r * r) out.add(p);
        }
        return out;
    }

    /** Cercle d'avertissement au sol. */
    private void ring(Location c, double r, Color color, int ticks) {
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.5f);
        int pts = (int) Math.max(18, r * 10);
        repeat(3, Math.max(1, ticks / 3), i -> {
            for (int k = 0; k < pts; k++) {
                double a = Math.PI * 2 * k / pts;
                c.getWorld().spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * r, 0.15, Math.sin(a) * r), 1, 0, 0, 0, 0, dust, true);
            }
            // remplissage qui se resserre : on voit quand ça va taper
            double inner = r * (1 - (i + 1.0) / Math.max(1, ticks / 3));
            for (int k = 0; k < pts / 2; k++) {
                double a = Math.PI * 4 * k / pts;
                c.getWorld().spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * inner, 0.15, Math.sin(a) * inner), 1, 0, 0, 0, 0,
                        dust, true);
            }
        }, null);
    }

    private void repeat(int period, int times, IntConsumer step, Runnable end) {
        int[] i = {0};
        BukkitTask[] self = new BukkitTask[1];
        self[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (i[0] >= times) {
                self[0].cancel();
                if (end != null) end.run();
                return;
            }
            try {
                step.accept(i[0]);
            } catch (Throwable t) {
                plugin.getLogger().warning("Erreur pendant une attaque du boss : " + t);
            }
            i[0]++;
        }, 0L, period);
        tasks.add(self[0]);
    }

    private void later(int ticks, Runnable r) {
        tasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!ended) r.run();
        }, ticks));
    }

    private static void sound(Location l, String key, float volume, float pitch) {
        l.getWorld().playSound(Sound.sound(Key.key("minecraft", key), Sound.Source.HOSTILE, volume, pitch), l.getX(), l.getY(), l.getZ());
    }

    private void titleAll(String title, String sub) {
        if (boss == null) return;
        for (Player p : nearby(boss.getLocation(), 60)) {
            p.showTitle(Title.title(parse(plugin.msg(title)), parse(plugin.msg(sub)),
                    Title.Times.times(java.time.Duration.ofMillis(300), java.time.Duration.ofMillis(2500), java.time.Duration.ofMillis(600))));
        }
    }

    private void fireworks(Location c) {
        for (int i = 0; i < 6; i++) {
            Location l = c.clone().add(ThreadLocalRandom.current().nextDouble(-3, 3), 1, ThreadLocalRandom.current().nextDouble(-3, 3));
            c.getWorld().spawn(l, Firework.class, f -> {
                FireworkMeta m = f.getFireworkMeta();
                m.addEffect(FireworkEffect.builder().withColor(Color.ORANGE, Color.fromRGB(80, 0, 120)).withFade(Color.BLACK)
                        .with(FireworkEffect.Type.BALL_LARGE).trail(true).flicker(true).build());
                m.setPower(1);
                f.setFireworkMeta(m);
            });
        }
    }

    @SuppressWarnings("unused")
    private static Quaternionf spin(float deg) {
        return new Quaternionf(new AxisAngle4f((float) Math.toRadians(deg), 0, 1, 0));
    }
}
