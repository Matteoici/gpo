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
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.WitherSkeleton;
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
        BARRAGE, VINES, FLAME_WAVE, MINIONS, SEED_RAIN, VORTEX, SPIKES, SCYTHE, CHARGE, METEOR,
        SWORD_COMBO, FIRE_FISSURE, ECLIPSE, SOUL_BLADE, CARILLON, LEAP, FEAST,
        NOVA, HELL_CROSS, CAGE, TORNADO, HANDS, GUARDS
    }

    private final SmpBoss plugin;
    private final Location altar;
    private final NamespacedKey bossKey, minionKey, rootKey, guardKey;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final List<ItemDisplay> orbit = new ArrayList<>();
    /** lanternes qui marquent le bord de l'arène */
    private final List<ItemDisplay> border = new ArrayList<>();
    /** affichages temporaires des attaques (retirés à la fin quoi qu'il arrive) */
    private final Set<Entity> props = new HashSet<>();
    private ItemDisplay crown;
    private int lastNumber;
    private final Set<Entity> minions = new HashSet<>();
    /** Gardes du Roi : tant qu'il en reste un, le Roi est protégé par un bouclier */
    private final Set<Mob> guards = new HashSet<>();
    private boolean enraged;
    /** Jugement Dernier : l'attaque ultime de la phase 3, une seule fois par combat */
    private boolean judgmentDone;
    private final Map<UUID, Double> damage = new HashMap<>();

    private Mob boss;
    /** pendant la fausse mort / résurrection : barre de vie animée à la main */
    private boolean reviving;
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
        this.guardKey = new NamespacedKey(plugin, "guard");
    }

    /** Agrandit toutes les zones d'attaque (1 = taille d'origine). */
    private double zs() {
        return Math.max(0.5, cfg("zone-scale", 1.5));
    }

    private double cfg(String path, double def) {
        return plugin.getConfig().getDouble("boss." + path, def);
    }

    public boolean isBoss(Entity e) {
        return e != null && e == boss;
    }

    public boolean isMinion(Entity e) {
        return e != null && (minions.contains(e) || guards.contains(e));
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
        // un squelette du Wither géant : il marche vraiment (jambes, bras) et frappe à l'épée
        boss = w.spawn(at, WitherSkeleton.class, z -> {
            z.setPersistent(false);
            z.setRemoveWhenFarAway(false);
            z.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 1);
            z.setCanPickupItems(false);
            z.setAI(false);
            base(z, Attribute.MAX_HEALTH, 1024);
            z.setHealth(1024);
            base(z, Attribute.SCALE, 0.4);
            base(z, Attribute.MOVEMENT_SPEED, cfg("speed", 0.27));
            // l'épée en netherite ajoute +7 : on retire ce bonus pour garder melee-damage au total
            base(z, Attribute.ATTACK_DAMAGE, Math.max(1, cfg("melee-damage", 12) - 7));
            base(z, Attribute.KNOCKBACK_RESISTANCE, 1);
            base(z, Attribute.FOLLOW_RANGE, 48);
            EntityEquipment eq = z.getEquipment();
            eq.setHelmet(new ItemStack(Material.JACK_O_LANTERN));
            eq.setChestplate(leather(Material.LEATHER_CHESTPLATE, 0x2f4a1e));
            eq.setLeggings(leather(Material.LEATHER_LEGGINGS, 0x3a2a14));
            eq.setBoots(leather(Material.LEATHER_BOOTS, 0x2a1a0c));
            eq.setItemInMainHand(blade());
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

    /** L'épée du Roi (brillante). */
    private static ItemStack blade() {
        ItemStack it = new ItemStack(Material.NETHERITE_SWORD);
        var m = it.getItemMeta();
        m.setEnchantmentGlintOverride(true);
        it.setItemMeta(m);
        return it;
    }

    /** Animation de coup d'épée (bras qui frappe). */
    private void swing() {
        if (boss != null && boss.isValid()) boss.swingMainHand();
    }

    private void face(Location target) {
        if (boss == null) return;
        Vector d = target.toVector().subtract(boss.getLocation().toVector()).setY(0);
        if (d.lengthSquared() < 0.01) return;
        boss.setRotation((float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ())), 0);
    }

    /** Annonce le nom de l'attaque (en bas de l'écran) aux joueurs proches. */
    private void announce(String key) {
        if (boss == null) return;
        String m = plugin.msg("boss.attack." + key);
        if (m.startsWith("<red>[")) return;
        for (Player p : nearby(boss.getLocation(), 50)) p.sendActionBar(parse(m));
    }

    private static void base(LivingEntity z, Attribute a, double v) {
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
        if (tick % 10 == 0 && !reviving) {
            bar.name(parse(plugin.msg(phase == 3 ? "boss.bar-headless" : "boss.bar-" + phase) + plugin.msg("boss.bar-hp").replace("<hp>", String.valueOf((long) Math.ceil(Math.max(0, hp))))
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
        if (!reviving) bar.progress((float) Math.max(0, Math.min(1, hp / maxHp)));
        // l'épée s'embrase en phase 2, flammes d'âmes en phase 3
        if (phase >= 2 && tick % 2 == 0) {
            double yaw = Math.toRadians(boss.getBodyYaw());
            Vector right = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
            Location hand = c.clone().add(right.multiply(0.42 * scale)).add(0, scale * 0.95, 0);
            w.spawnParticle(phase == 3 ? Particle.SOUL_FIRE_FLAME : Particle.FLAME, hand, 3, 0.15 * scale, 0.35 * scale, 0.15 * scale, 0.01,
                    null, true);
        }
        if (phase == 3 && tick % 3 == 0) {
            // halo de flammes d'âmes autour de la tête
            for (int k = 0; k < 6; k++) {
                double a = tick * 0.15 + k * Math.PI / 3;
                w.spawnParticle(Particle.SOUL_FIRE_FLAME, c.clone().add(Math.cos(a) * 0.45 * scale, scale * 1.85, Math.sin(a) * 0.45 * scale), 1,
                        0, 0.05, 0, 0.01, null, true);
            }
        }
        if (enraged && tick % 3 == 0) {
            w.spawnParticle(Particle.DUST, c.clone().add(0, scale, 0), 6, 0.5 * scale, 0.7 * scale, 0.5 * scale, 0,
                    new Particle.DustOptions(Color.fromRGB(255, 0, 0), 2.2f), true);
        }
        if (board != null && tick % 10 == 0) board.update(near, hp, maxHp, damage, minPct());

        // laisse : il reste dans son arène
        double arena = cfg("arena-radius", 18);
        if (c.getWorld() != altar.getWorld() || horizontalDist(c, altar) > arena + 8 || c.getY() < altar.getY() - 12) {
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
        tickGuards(c);

        // attaques
        if (invulnerable > 0 || !boss.hasAI()) return;
        if (--nextAttack <= 0) {
            nextAttack = (int) (phase == 3 ? cfg("attack-interval-phase-3", 48) : cfg("attack-interval." + phase, phase == 1 ? 75 : 60));
            if (enraged) nextAttack = (int) (nextAttack * 0.7);
            List<Attack> pool = switch (phase) {
                // La Récolte : coups d'épée, citrouilles, lianes, pics, mains des damnés
                case 1 -> List.of(Attack.SWORD_COMBO, Attack.SWORD_COMBO, Attack.BARRAGE, Attack.VINES, Attack.SPIKES, Attack.HANDS,
                        Attack.SEED_RAIN, Attack.NOVA);
                // La Lanterne : tout s'embrase
                case 2 -> List.of(Attack.SWORD_COMBO, Attack.FIRE_FISSURE, Attack.FIRE_FISSURE, Attack.FLAME_WAVE, Attack.CHARGE,
                        Attack.SCYTHE, Attack.MINIONS, Attack.METEOR, Attack.BARRAGE, Attack.HELL_CROSS, Attack.CAGE, Attack.TORNADO,
                        Attack.GUARDS, Attack.VORTEX, Attack.NOVA, Attack.HANDS);
                // Le Roi Sans Tête : tout ce qu'il a, en plus fort et enchaîné
                default -> List.of(Attack.ECLIPSE, Attack.SOUL_BLADE, Attack.CARILLON, Attack.LEAP, Attack.FEAST, Attack.SWORD_COMBO,
                        Attack.CHARGE, Attack.METEOR, Attack.FIRE_FISSURE, Attack.SPIKES, Attack.HELL_CROSS, Attack.CAGE, Attack.TORNADO,
                        Attack.HANDS, Attack.NOVA, Attack.GUARDS, Attack.VORTEX);
            };
            Attack a;
            int guard = 0;
            do {
                a = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
            } while ((a == last || a == Attack.GUARDS && !guards.isEmpty()) && pool.size() > 1 && guard++ < 20);
            last = a;
            run(a);
            // phase 2 parfois, phase 3 souvent (toujours s'il est enragé) : il enchaîne une deuxième attaque
            double combo = phase == 3 ? (enraged ? 1.0 : cfg("phase-3-combo-chance", 0.5)) : phase == 2 ? cfg("phase-2-combo-chance", 0.25) : 0;
            if (ThreadLocalRandom.current().nextDouble() < combo) {
                Attack first = a;
                later(30, () -> {
                    if (ended || reviving || invulnerable > 0 || boss == null || !boss.isValid()) return;
                    List<Attack> quick = List.of(Attack.SWORD_COMBO, Attack.SPIKES, Attack.METEOR, Attack.FIRE_FISSURE, Attack.CHARGE,
                            Attack.HANDS, Attack.TORNADO, Attack.BARRAGE);
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
                case SWORD_COMBO -> swordCombo();
                case FIRE_FISSURE -> fireFissure();
                case ECLIPSE -> eclipse();
                case SOUL_BLADE -> soulBlade();
                case CARILLON -> carillon();
                case LEAP -> leap();
                case FEAST -> feast();
                case NOVA -> nova();
                case HELL_CROSS -> hellCross();
                case CAGE -> cage();
                case TORNADO -> tornado();
                case HANDS -> hands();
                case GUARDS -> summonGuards();
            }
    }

    // ================================================================= dégâts reçus

    /** Un joueur frappe le boss. Renvoie false si le coup est ignoré (invulnérable). */
    boolean hurt(Player by, double amount) {
        if (ended || reviving || invulnerable > 0 || boss == null) return false;
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
        guards.removeIf(g -> !g.isValid() || g.isDead());
        if (!guards.isEmpty()) {
            // bouclier des gardes : il encaisse beaucoup moins
            amount *= cfg("guard-shield", 0.3);
            if (by != null && tick % 10 == 0) by.sendActionBar(parse(plugin.msg("boss.shield")));
        }
        hp -= amount;
        damageNumber(amount);
        if (tick % 3 == 0) sound(boss.getLocation(), "block.wood.break", 1.2f, 0.6f);
        boss.getWorld().spawnParticle(Particle.BLOCK, boss.getLocation().add(0, scale, 0), 12, 0.4 * scale, 0.5 * scale,
                0.4 * scale, 0, Material.PUMPKIN.createBlockData(), true);
        double ratio = hp / maxHp;
        if (hp <= 0) {
            if (phase < 3) resurrect();
            else defeat();
        } else if (phase == 1 && ratio <= cfg("phase-2-at-v2", 0.7)) {
            changePhase(2);
        } else if (phase == 3 && !judgmentDone && ratio <= cfg("judgment-at", 0.55)) {
            judgment();
        } else if (phase == 3 && !enraged && ratio <= cfg("enrage-at", 0.3)) {
            enrage();
        }
        return true;
    }

    private void changePhase(int to) {
        phase = to;
        if (to == 2) {
            boss.getEquipment().setChestplate(leather(Material.LEATHER_CHESTPLATE, 0x5a1a0a));
            announce("ignite");
        }
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
            if (sp != null) sp.setBaseValue(cfg("speed", 0.27) * 1.25);
        }
    }

    // ================================================================= attaques

    /** Citrouilles explosives lancées en cloche sur 3 joueurs. */
    private void barrage() {
        swing();
        announce("barrage");
        List<Player> targets = nearby(boss.getLocation(), 30);
        java.util.Collections.shuffle(targets);
        sound(boss.getLocation(), "entity.witch.throw", 2f, 0.6f);
        for (Player p : targets.subList(0, Math.min(4 + phase, targets.size()))) {
            Location dest = p.getLocation().clone();
            double r = 3 * zs();
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
            double r = 1.8 * zs();
            ring(at, r, Color.fromRGB(40, 160, 40), 26);
            later(26, () -> {
                at.getWorld().spawnParticle(Particle.BLOCK, at.clone().add(0, 0.5, 0), 60, r * 0.5, 0.6, r * 0.5, 0,
                        Material.MOSS_BLOCK.createBlockData(), true);
                sound(at, "block.big_dripleaf.tilt_down", 1.5f, 0.6f);
                for (Player v : nearby(at, r + 0.3)) {
                    hit(v, cfg("damage.vines", 4) * mult());
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
                hit(p, cfg("damage.flame-wave", 7) * mult());
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
        announce("seed-rain");
        while (spots.size() < cfg("seed-rain-count", 22)) {
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
                ring(l, 2 * zs(), Color.fromRGB(255, 160, 0), 30);
                later(30, () -> {
                    l.getWorld().spawnParticle(Particle.LAVA, l, 12, 0.6, 0.2, 0.6, 0, null, true);
                    l.getWorld().spawnParticle(Particle.FLAME, l.clone().add(0, 2, 0), 30, 0.2, 2, 0.2, 0.02, null, true);
                    sound(l, "entity.blaze.hurt", 1.2f, 0.5f);
                    for (Player p : nearby(l, 2.2 * zs())) {
                        hit(p, cfg("damage.seed-rain", 6) * mult());
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
        announce("vortex");
        sound(c, "entity.warden.sonic_charge", 3f, 0.6f);
        double slamR = 5.5 * zs();
        ring(c, slamR, Color.fromRGB(200, 0, 0), 40);
        repeat(1, 32, i -> {
            for (Player p : nearby(c, 16 * zs())) {
                Vector pull = c.toVector().subtract(p.getLocation().toVector()).setY(0);
                if (pull.lengthSquared() < 1) continue;
                p.setVelocity(p.getVelocity().add(pull.normalize().multiply(0.09)));
            }
            c.getWorld().spawnParticle(Particle.PORTAL, c.clone().add(0, 1, 0), 30, 4, 1, 4, 0.5, null, true);
        }, () -> later(8, () -> {
            sound(c, "entity.warden.sonic_boom", 3f, 0.8f);
            c.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, c, 1, 0, 0, 0, 0, null, true);
            for (Player p : nearby(c, slamR)) {
                hit(p, cfg("damage.slam", 12) * mult());
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
            hit(p, dmg * mult());
            if (fire) p.setFireTicks(40);
            Vector kb = p.getLocation().toVector().subtract(at.toVector()).setY(0);
            if (kb.lengthSquared() > 0.01) p.setVelocity(kb.normalize().multiply(0.6).setY(0.35));
        }
    }

    private double mult() {
        return cfg("damage.multiplier", 1.35) * (enraged ? 1.25 : 1.0) * (phase == 3 ? cfg("damage.phase-3-multiplier", 1.5)
                : phase == 2 ? cfg("damage.phase-2-multiplier", 1.45) : cfg("damage.phase-1-multiplier", 1.3));
    }

    /**
     * Un coup du boss. Une partie traverse l'armure (dégâts magiques : seuls les enchantements Protection comptent),
     * sinon une armure en netherite enchantée ne prend presque rien.
     */
    private void hit(Player p, double amount) {
        if (boss == null || p.isDead()) return;
        if (plugin.arena() != null && plugin.arena().isProtected(p)) return; // protection d'arrivée
        double pierce = Math.max(0, Math.min(1, phase == 3 ? cfg("damage.armor-pierce-phase-3", 0.3) : cfg("damage.armor-pierce", 0.3)));
        double normal = amount * (1 - pierce);
        if (normal > 0) p.damage(normal, boss);
        if (pierce > 0 && !p.isDead()) {
            p.setNoDamageTicks(0); // sinon le 2e morceau du coup serait ignoré (temps d'invulnérabilité)
            p.damage(amount * pierce, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.MAGIC)
                    .withCausingEntity(boss).withDirectEntity(boss).build());
        }
    }

    /** Lignes de citrouilles qui jaillissent du sol vers chaque joueur. */
    private void spikes() {
        Location c = boss.getLocation().clone();
        swing();
        announce("spikes");
        sound(c, "block.rooted_dirt.break", 2f, 0.5f);
        sound(c, "entity.evoker.prepare_attack", 2f, 0.7f);
        List<Player> targets = nearby(c, 26);
        java.util.Collections.shuffle(targets);
        int lines = Math.min(targets.size(), 3 + phase);
        Set<UUID> hit = new HashSet<>();
        for (int n = 0; n < Math.max(1, lines); n++) {
            Vector dir = targets.isEmpty() ? Vector.getRandom().subtract(new Vector(0.5, 0.5, 0.5)).setY(0)
                    : targets.get(n).getLocation().toVector().subtract(c.toVector()).setY(0);
            if (dir.lengthSquared() < 0.01) dir = new Vector(1, 0, 0);
            dir.normalize();
            Vector d = dir;
            int len = (int) (cfg("arena-radius", 18) + 4);
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
                for (Player p : nearby(l, 1.6 * Math.sqrt(zs()))) {
                    if (!hit.add(p.getUniqueId())) continue;
                    hit(p, cfg("damage.spikes", 7) * mult());
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
        announce("whirl");
        double r = cfg("scythe-radius", 6.5) * zs();
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
                if (i % 3 == 0) swing();
                double a = Math.toRadians(i * 36);
                for (double d = 1.2; d <= r; d += 0.8) {
                    Location l = c.clone().add(Math.cos(a) * d, scale * 0.5, Math.sin(a) * d);
                    c.getWorld().spawnParticle(Particle.SWEEP_ATTACK, l, 1, 0, 0, 0, 0, null, true);
                    c.getWorld().spawnParticle(Particle.DUST, l, 2, 0.1, 0.2, 0.1, 0,
                            new Particle.DustOptions(phase == 3 ? Color.fromRGB(110, 230, 255) : Color.fromRGB(255, 140, 20), 1.6f), true);
                }
                for (Player p : nearby(c, r)) {
                    if (!hit.add(p.getUniqueId())) continue;
                    hit(p, cfg("damage.scythe", 10) * mult());
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
                for (Player p : nearby(next, (1.4 + scale * 0.35) * Math.sqrt(zs()))) {
                    if (!hit.add(p.getUniqueId())) continue;
                    hit(p, cfg("damage.charge", 9) * mult());
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
        announce("meteor");
        swing();
        // phase 2 : 2 citrouilles, phase 3 : une par joueur (jusqu'à 5)
        if (phase >= 2) {
            List<Player> near3 = nearby(boss.getLocation(), 40);
            java.util.Collections.shuffle(near3);
            for (int i = 1; i < Math.min(phase == 3 ? 5 : 2, near3.size()); i++) {
                Player extra = near3.get(i);
                later(i * 12, () -> meteorOn(extra));
            }
        }
        meteorTop();
    }

    private void meteorTop() {
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
        meteorOn(target);
    }

    private void meteorOn(Player target) {
        if (ended || boss == null || !target.isOnline()) return;
        Location dest = ground(target.getLocation().clone());
        double r = 5 * zs();
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
                hit(p, cfg("damage.meteor", 14) * mult());
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

    // ================================================================= épée

    private static Player closest(List<Player> list, Location c) {
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : list) {
            double d = p.getLocation().distanceSquared(c);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private void wake(int ticks) {
        later(ticks, () -> {
            if (boss != null && boss.isValid() && !ended && !reviving && invulnerable <= 0) boss.setAI(true);
        });
    }

    /** Arc de particules devant le boss (angle total en degrés), à la hauteur h. */
    private void arc(Location c, Vector dir, double r, double angle, Color col, double h) {
        int pts = Math.max(4, (int) (angle / 6));
        Particle.DustOptions dust = new Particle.DustOptions(col, 1.4f);
        for (int i = 0; i <= pts; i++) {
            Vector v = dir.clone().rotateAroundY(Math.toRadians(-angle / 2 + i * angle / pts));
            for (double d = r * 0.4; d <= r + 0.01; d += r * 0.2) {
                c.getWorld().spawnParticle(Particle.DUST, c.clone().add(v.clone().multiply(d)).add(0, h, 0), 1, 0, 0, 0, 0, dust, true);
            }
        }
    }

    /** Combo de coups d'épée : balayages devant lui, le dernier fend le sol en ligne droite. */
    private void swordCombo() {
        List<Player> near = nearby(boss.getLocation(), 16);
        if (near.isEmpty()) {
            barrage();
            return;
        }
        Player target = closest(near, boss.getLocation());
        boss.setAI(false);
        announce("combo");
        int hits = phase == 3 ? 4 : 3;
        int gap = phase == 1 ? 15 : phase == 2 ? 13 : 10;
        double reach = (2.4 + scale * 0.9) * Math.sqrt(zs());
        Color col = phase == 3 ? Color.fromRGB(110, 230, 255) : phase == 2 ? Color.fromRGB(255, 110, 20) : Color.fromRGB(255, 205, 90);
        for (int k = 0; k < hits; k++) {
            boolean finisher = k == hits - 1;
            boolean left = k % 2 == 0;
            // préparation : il se tourne, fait un pas, un arc rouge montre la zone
            later(k * gap, () -> {
                if (boss == null || !boss.isValid()) return;
                if (target.isOnline() && target.getWorld() == boss.getWorld()) {
                    face(target.getLocation());
                    Location step = boss.getLocation().add(boss.getLocation().getDirection().setY(0).normalize().multiply(0.9));
                    if (step.clone().add(0, 1, 0).getBlock().isPassable()) boss.teleport(ground(step));
                }
                Location c = boss.getLocation();
                Vector dir = c.getDirection().setY(0).normalize();
                if (finisher) {
                    for (int d = 1; d <= 10; d++) {
                        Location l = ground(c.clone().add(dir.clone().multiply(d * 1.2))).add(0, 0.15, 0);
                        c.getWorld().spawnParticle(Particle.DUST, l, 3, 0.3, 0, 0.3, 0, new Particle.DustOptions(Color.fromRGB(200, 20, 20), 1.5f), true);
                    }
                    sound(c, "item.trident.riptide_1", 2f, 0.5f);
                } else {
                    arc(c, dir, reach, 150, Color.fromRGB(190, 30, 30), 0.15);
                    sound(c, "item.armor.equip_netherite", 1.5f, 0.6f);
                }
            });
            later(k * gap + gap / 2 + 2, () -> {
                if (boss == null || !boss.isValid()) return;
                swing();
                Location c = boss.getLocation();
                Vector dir = c.getDirection().setY(0).normalize();
                if (finisher) {
                    sound(c, "entity.generic.explode", 2f, 0.8f);
                    sound(c, "entity.zombie.attack_iron_door", 2f, 0.5f);
                    shake(c, 16);
                    Set<UUID> hit = new HashSet<>();
                    for (int d = 1; d <= 10; d++) {
                        int dd = d;
                        later(d, () -> {
                            Location l = ground(c.clone().add(dir.clone().multiply(dd * 1.2)));
                            org.bukkit.block.data.BlockData under = l.clone().add(0, -1, 0).getBlock().getBlockData();
                            c.getWorld().spawnParticle(Particle.BLOCK, l.clone().add(0, 0.3, 0), 30, 0.5, 0.4, 0.5, 0, under, true);
                            c.getWorld().spawnParticle(phase == 3 ? Particle.SOUL_FIRE_FLAME : Particle.FLAME, l.clone().add(0, 0.6, 0), 10, 0.3, 0.7,
                                    0.3, 0.02, null, true);
                            for (Player p : nearby(l, 1.8)) {
                                if (!hit.add(p.getUniqueId())) continue;
                                hit(p, cfg("damage.sword-finisher", 11) * mult());
                                p.setVelocity(new Vector(0, 1.05, 0));
                            }
                        });
                    }
                } else {
                    arc(c, dir, reach, 150, col, scale * 0.45);
                    for (int i = -3; i <= 3; i++) {
                        Vector v = dir.clone().rotateAroundY(Math.toRadians(i * 22 * (left ? 1 : -1))).multiply(reach * 0.75);
                        c.getWorld().spawnParticle(Particle.SWEEP_ATTACK, c.clone().add(v).add(0, scale * 0.45, 0), 1, 0, 0, 0, 0, null, true);
                    }
                    sound(c, "entity.player.attack.sweep", 2f, 0.6f);
                    for (Player p : nearby(c, reach + 0.6)) {
                        Vector to = p.getLocation().toVector().subtract(c.toVector()).setY(0);
                        if (to.lengthSquared() > 0.5 && to.normalize().dot(dir) < 0.2) continue;
                        hit(p, cfg("damage.sword", 7) * mult());
                        p.setVelocity(dir.clone().multiply(0.9).setY(0.35));
                    }
                }
            });
        }
        wake(hits * gap + 14);
    }

    /** Il plante son épée en feu : des fissures de flammes partent en éventail vers les joueurs. */
    private void fireFissure() {
        Location c = boss.getLocation().clone();
        List<Player> near = nearby(c, 26);
        Vector b = near.isEmpty() ? c.getDirection().setY(0) : closest(near, c).getLocation().toVector().subtract(c.toVector()).setY(0);
        if (b.lengthSquared() < 0.01) b = new Vector(1, 0, 0);
        Vector base = b.normalize();
        boss.setAI(false);
        face(c.clone().add(base));
        announce("fissure");
        swing();
        sound(c, "item.firecharge.use", 2f, 0.5f);
        sound(c, "entity.blaze.shoot", 2f, 0.6f);
        boolean soul = phase == 3;
        int lines = soul ? 7 : 5;
        int flen = (int) cfg("arena-radius", 18) + 2;
        Set<UUID> hit = new HashSet<>();
        for (int n = 0; n < lines; n++) {
            Vector d = base.clone().rotateAroundY(Math.toRadians((n - (lines - 1) / 2.0) * (soul ? 22 : 26)));
            repeat(3, 6, i -> {
                for (int k = 2; k <= flen; k++) {
                    Location l = ground(c.clone().add(d.clone().multiply(k))).add(0, 0.12, 0);
                    c.getWorld().spawnParticle(Particle.DUST, l, 1, 0.15, 0, 0.15, 0, new Particle.DustOptions(Color.fromRGB(255, 80, 0), 1.2f), true);
                }
            }, null);
            later(20, () -> {
                swing();
                repeat(1, flen - 1, k -> {
                    Location l = ground(c.clone().add(d.clone().multiply(k + 2)));
                    c.getWorld().spawnParticle(soul ? Particle.SOUL_FIRE_FLAME : Particle.FLAME, l.clone().add(0, 1, 0), 14, 0.25, 1.0, 0.25, 0.03,
                            null, true);
                    c.getWorld().spawnParticle(Particle.LAVA, l, 2, 0.2, 0.1, 0.2, 0, null, true);
                    if (k % 3 == 0) sound(l, "block.fire.extinguish", 1f, 0.6f);
                    for (Player p : nearby(l, 1.5 * Math.sqrt(zs()))) {
                        if (!hit.add(p.getUniqueId())) continue;
                        hit(p, cfg("damage.fissure", 8) * mult());
                        p.setFireTicks(80);
                        p.setVelocity(p.getVelocity().setY(0.75));
                    }
                }, null);
            });
        }
        wake(42);
    }

    // ================================================================= phase 3

    private static Transformation centered(float size, Quaternionf rot) {
        Vector3f half = new Vector3f(size / 2, size / 2, size / 2);
        rot.transform(half);
        return new Transformation(new Vector3f(-half.x, -half.y, -half.z), rot, new Vector3f(size, size, size), new Quaternionf());
    }

    /** Lune de sang : une lune-citrouille géante se lève au-dessus de l'arène et tire des rayons d'âmes. */
    private void eclipse() {
        announce("eclipse");
        swing();
        Location center = altar.clone().add(0.5, 22, 0.5);
        sound(boss.getLocation(), "entity.wither.spawn", 3f, 0.5f);
        float sz = 7f;
        BlockDisplay moon = center.getWorld().spawn(center, BlockDisplay.class, e -> {
            e.setPersistent(false);
            e.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 4);
            e.setBlock(Material.JACK_O_LANTERN.createBlockData());
            e.setBrightness(new Display.Brightness(15, 15));
            e.setGlowing(true);
            e.setGlowColorOverride(Color.fromRGB(0x5ff3ff));
            e.setViewRange(6f);
            e.setTransformation(centered(0.1f, new Quaternionf()));
        });
        props.add(moon);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!moon.isValid()) return;
            moon.setInterpolationDelay(0);
            moon.setInterpolationDuration(25);
            moon.setTransformation(centered(sz, new Quaternionf().rotateY(0.6f)));
        }, 2L);
        for (Player p : nearby(center, 70)) p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, false, false, false));
        int volleys = 8;
        repeat(13, volleys, i -> {
            if (moon.isValid()) {
                moon.setInterpolationDelay(0);
                moon.setInterpolationDuration(13);
                moon.setTransformation(centered(sz, new Quaternionf().rotateY(0.6f + (i + 1) * 0.5f).rotateX((float) Math.sin(i) * 0.2f)));
            }
            if (i == 0) return; // le temps qu'elle se lève
            sound(center, "block.beacon.power_select", 2f, 1.6f);
            for (Player p : nearby(altar, cfg("arena-radius", 18) + 6)) {
                Location target = ground(p.getLocation().clone());
                ring(target, 2.2 * zs(), Color.fromRGB(80, 220, 255), 14);
                later(14, () -> {
                    Vector dv = target.toVector().subtract(center.toVector());
                    double len = dv.length();
                    dv.normalize();
                    for (double t = sz * 0.5; t < len; t += 0.5) {
                        Location l = center.clone().add(dv.clone().multiply(t));
                        center.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, l, 2, 0.12, 0.12, 0.12, 0.005, null, true);
                        if (((int) (t * 2)) % 3 == 0) center.getWorld().spawnParticle(Particle.END_ROD, l, 1, 0.05, 0.05, 0.05, 0, null, true);
                    }
                    target.getWorld().spawnParticle(Particle.EXPLOSION, target.clone().add(0, 0.5, 0), 2, 0.5, 0.2, 0.5, 0, null, true);
                    target.getWorld().spawnParticle(Particle.SOUL, target.clone().add(0, 0.4, 0), 20, 1, 0.2, 1, 0.03, null, true);
                    sound(target, "entity.warden.sonic_boom", 1.2f, 1.7f);
                    for (Player v : nearby(target, 2.3 * zs())) hit(v, cfg("damage.eclipse", 8) * mult());
                });
            }
        }, () -> later(18, () -> {
            Location mc = center.clone();
            mc.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, mc, 3, 2, 2, 2, 0, null, true);
            mc.getWorld().spawnParticle(Particle.BLOCK, mc, 200, 3, 3, 3, 0, Material.PUMPKIN.createBlockData(), true);
            sound(mc, "entity.generic.explode", 4f, 0.6f);
            moon.remove();
            props.remove(moon);
        }));
    }

    /** Lame des âmes : une épée géante balaie la moitié de l'arène, puis l'autre. Il faut changer de côté à temps. */
    private void soulBlade() {
        announce("soul-blade");
        boss.setAI(false);
        swing();
        Location c = ground(altar.clone().add(0.5, 0, 0.5));
        double arena = cfg("arena-radius", 18);
        double a0 = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        Vector n = new Vector(Math.cos(a0), 0, Math.sin(a0));
        sound(c, "block.respawn_anchor.charge", 3f, 0.5f);
        sweepHalf(c, n, arena + 1, 0);
        sweepHalf(c, n.clone().multiply(-1), arena + 1, 46);
        wake(46 + 48);
    }

    private void sweepHalf(Location c, Vector n, double radius, int delay) {
        later(delay, () -> {
            // avertissement : la moitié visée se couvre de rouge
            repeat(4, 8, i -> {
                for (double x = -radius; x <= radius; x += 2) {
                    for (double z = -radius; z <= radius; z += 2) {
                        if (x * x + z * z > radius * radius || x * n.getX() + z * n.getZ() < 0) continue;
                        Location l = ground(c.clone().add(x, 0, z)).add(0, 0.15, 0);
                        c.getWorld().spawnParticle(Particle.DUST, l, 1, 0.4, 0, 0.4, 0, new Particle.DustOptions(Color.fromRGB(170, 10, 30), 1.6f), true);
                    }
                }
            }, null);
            later(32, () -> {
                swing();
                float s = (float) (radius / 1.25);
                ItemDisplay sword = c.getWorld().spawn(c.clone().add(0, 1.6, 0), ItemDisplay.class, e -> {
                    e.setPersistent(false);
                    e.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 4);
                    e.setItemStack(blade());
                    e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                    e.setBrightness(new Display.Brightness(15, 15));
                    e.setGlowing(true);
                    e.setGlowColorOverride(Color.fromRGB(0x5ff3ff));
                    e.setTeleportDuration(1);
                    e.setViewRange(6f);
                    e.setTransformation(new Transformation(new Vector3f((float) radius * 0.5f, 0, 0),
                            new Quaternionf().rotateZ((float) Math.toRadians(-45)), new Vector3f(s, s, s), new Quaternionf()));
                });
                props.add(sword);
                double phiN = Math.toDegrees(Math.atan2(n.getZ(), n.getX()));
                Set<UUID> hit = new HashSet<>();
                sound(c, "item.trident.riptide_3", 3f, 0.5f);
                repeat(1, 10, i -> {
                    double ang = phiN - 90 + 180.0 * (i + 1) / 10;
                    Location l = c.clone().add(0, 1.6, 0);
                    l.setYaw((float) ang);
                    sword.teleport(l);
                    double rad = Math.toRadians(ang);
                    Vector along = new Vector(Math.cos(rad), 0, Math.sin(rad));
                    for (double d = 2; d <= radius; d += 1.5) {
                        Location p = c.clone().add(along.clone().multiply(d)).add(0, 1, 0);
                        c.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, p, 2, 0.2, 0.4, 0.2, 0.01, null, true);
                        if (d % 4.5 < 1.5) c.getWorld().spawnParticle(Particle.SWEEP_ATTACK, p, 1, 0, 0, 0, 0, null, true);
                    }
                    if (i % 3 == 0) sound(c, "entity.player.attack.sweep", 3f, 0.5f);
                    for (Player p : nearby(c, radius + 1)) {
                        Vector to = p.getLocation().toVector().subtract(c.toVector()).setY(0);
                        if (to.getX() * n.getX() + to.getZ() * n.getZ() < -0.5 || !hit.add(p.getUniqueId())) continue;
                        hit(p, cfg("damage.soul-blade", 15) * mult());
                        p.setVelocity(n.clone().multiply(1.1).setY(0.6));
                    }
                }, () -> {
                    sword.remove();
                    props.remove(sword);
                });
            });
        });
    }

    /** Carillon : des lanternes tombent sur une case sur deux (damier), puis sur les autres. */
    private void carillon() {
        announce("carillon");
        swing();
        Location c = ground(altar.clone().add(0.5, 0, 0.5));
        double arena = cfg("arena-radius", 18);
        int cell = 3;
        int rounds = 3;
        boolean start = ThreadLocalRandom.current().nextBoolean();
        for (int round = 0; round < rounds; round++) {
            boolean parity = (round % 2 == 0) == start;
            later(round * 34, () -> {
                List<Location> cells = new ArrayList<>();
                for (int ix = (int) -Math.floor(arena / cell); ix <= arena / cell; ix++) {
                    for (int iz = (int) -Math.floor(arena / cell); iz <= arena / cell; iz++) {
                        double x = ix * cell, z = iz * cell;
                        if (x * x + z * z > arena * arena) continue;
                        if (((Math.abs(ix) + Math.abs(iz)) % 2 == 0) != parity) continue;
                        cells.add(ground(c.clone().add(x, 0, z)));
                    }
                }
                sound(c, "block.bell.use", 3f, 0.6f);
                repeat(4, 6, i -> {
                    for (Location l : cells) {
                        for (int k = -1; k <= 1; k++) {
                            for (int[] e : new int[][]{{k, -1}, {k, 1}, {-1, k}, {1, k}}) {
                                c.getWorld().spawnParticle(Particle.DUST, l.clone().add(e[0] * 1.4, 0.12, e[1] * 1.4), 1, 0, 0, 0, 0,
                                        new Particle.DustOptions(Color.fromRGB(255, 120, 0), 1.1f), true);
                            }
                        }
                    }
                }, null);
                later(24, () -> {
                    Set<UUID> hit = new HashSet<>();
                    for (Location l : cells) {
                        BlockDisplay lan = l.getWorld().spawn(l.clone().add(0, 9, 0), BlockDisplay.class, e -> {
                            e.setPersistent(false);
                            e.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 4);
                            e.setBlock(Material.SOUL_LANTERN.createBlockData());
                            e.setBrightness(new Display.Brightness(15, 15));
                            e.setTeleportDuration(1);
                            e.setTransformation(centered(1.8f, new Quaternionf()));
                        });
                        props.add(lan);
                        repeat(1, 6, i -> lan.teleport(l.clone().add(0, 9 - 9 * (i + 1) / 6.0 + 0.9, 0)), () -> {
                            lan.remove();
                            props.remove(lan);
                            l.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, l.clone().add(0, 0.3, 0), 10, 0.8, 0.1, 0.8, 0.03, null, true);
                            for (Player p : l.getWorld().getPlayers()) {
                                Location pl = p.getLocation();
                                if (Math.abs(pl.getX() - l.getX()) <= 1.6 && Math.abs(pl.getZ() - l.getZ()) <= 1.6 && Math.abs(pl.getY() - l.getY()) < 3
                                        && hit.add(p.getUniqueId()) && !nearby(pl, 0.1).isEmpty()) {
                                    hit(p, cfg("damage.carillon", 9) * mult());
                                }
                            }
                        });
                    }
                    later(6, () -> {
                        sound(c, "block.bell.resonate", 3f, 0.7f);
                        sound(c, "block.anvil.land", 2f, 0.5f);
                        shake(c, cfg("arena-radius", 18) + 4);
                    });
                });
            });
        }
    }

    /** Il bondit dans le ciel, son ombre suit un joueur, puis il s'écrase avec trois ondes de choc. */
    private void leap() {
        List<Player> near = nearby(boss.getLocation(), 28);
        if (near.isEmpty()) return;
        Player target = near.get(ThreadLocalRandom.current().nextInt(near.size()));
        announce("leap");
        boss.setAI(false);
        swing();
        Location start = boss.getLocation().clone();
        sound(start, "entity.ravager.roar", 3f, 0.6f);
        start.getWorld().spawnParticle(Particle.CLOUD, start, 40, 1.5, 0.2, 1.5, 0.1, null, true);
        double h = 17;
        repeat(1, 10, i -> {
            Location l = start.clone().add(0, h * (i + 1) / 10.0, 0);
            l.setYaw(boss.getYaw());
            boss.teleport(l);
        }, () -> {
            Location[] shadow = {ground(target.getLocation().clone())};
            repeat(2, 18, i -> {
                if (target.isOnline() && target.getWorld() == altar.getWorld()
                        && horizontalDist(target.getLocation(), altar) <= cfg("arena-radius", 18) + 4) {
                    shadow[0] = ground(target.getLocation().clone());
                }
                Location up = shadow[0].clone().add(0, h, 0);
                up.setYaw(boss.getYaw());
                boss.teleport(up);
                for (int k = 0; k < 32; k++) {
                    double a = Math.PI * 2 * k / 32;
                    shadow[0].getWorld().spawnParticle(Particle.DUST, shadow[0].clone().add(Math.cos(a) * 4.5 * zs(), 0.15, Math.sin(a) * 4.5 * zs()), 1, 0, 0, 0, 0,
                            new Particle.DustOptions(Color.fromRGB(20, 20, 30), 1.6f), true);
                }
                shadow[0].getWorld().spawnParticle(Particle.LARGE_SMOKE, shadow[0].clone().add(0, 0.2, 0), 6, 1.5, 0, 1.5, 0, null, true);
            }, () -> {
                Location lock = shadow[0].clone();
                ring(lock, 4.5 * zs(), Color.fromRGB(220, 20, 20), 12);
                sound(lock, "entity.wither.shoot", 2f, 0.5f);
                later(12, () -> repeat(1, 4, i -> {
                    Location l = lock.clone().add(0, h * (1 - (i + 1) / 4.0), 0);
                    l.setYaw(boss.getYaw());
                    boss.teleport(l);
                }, () -> {
                    swing();
                    lock.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, lock, 2, 1, 0.3, 1, 0, null, true);
                    lock.getWorld().spawnParticle(Particle.BLOCK, lock.clone().add(0, 0.5, 0), 160, 3, 0.6, 3, 0,
                            lock.clone().add(0, -1, 0).getBlock().getBlockData(), true);
                    sound(lock, "entity.generic.explode", 4f, 0.5f);
                    sound(lock, "block.anvil.land", 3f, 0.5f);
                    shake(lock, 30);
                    for (Player p : nearby(lock, 4.5 * zs())) {
                        hit(p, cfg("damage.leap", 14) * mult());
                        Vector kb = p.getLocation().toVector().subtract(lock.toVector()).setY(0);
                        if (kb.lengthSquared() < 0.01) kb = new Vector(0.1, 0, 0);
                        p.setVelocity(kb.normalize().multiply(1.3).setY(0.9));
                    }
                    // trois anneaux qui s'élargissent : il faut sauter par-dessus
                    for (int w = 0; w < 3; w++) {
                        int ww = w;
                        later(w * 9, () -> {
                            Set<UUID> hit = new HashSet<>();
                            repeat(1, 18, i -> {
                                double r = 2 + i * 1.1;
                                int pts = (int) Math.max(24, r * 7);
                                for (int k = 0; k < pts; k++) {
                                    double a = Math.PI * 2 * k / pts;
                                    lock.getWorld().spawnParticle(ww == 1 ? Particle.SOUL_FIRE_FLAME : Particle.FLAME,
                                            lock.clone().add(Math.cos(a) * r, 0.3, Math.sin(a) * r), 1, 0, 0.05, 0, 0.005, null, true);
                                }
                                for (Player p : nearby(lock, r + 1)) {
                                    double d = horizontalDist(p.getLocation(), lock);
                                    if (Math.abs(d - r) > 0.9 || !p.isOnGround() || !hit.add(p.getUniqueId())) continue;
                                    hit(p, cfg("damage.leap-ring", 6) * mult());
                                    p.setVelocity(p.getVelocity().setY(0.5));
                                }
                            }, null);
                        });
                    }
                    wake(30);
                }));
            });
        });
    }

    /** Le joueur est dans le combat (dans l'arène du boss). */
    boolean inFight(Player p) {
        return !ended && p.getWorld() == altar.getWorld() && horizontalDist(p.getLocation(), altar) <= cfg("arena-radius", 18) + 12
                && Math.abs(p.getLocation().getY() - altar.getY()) < 30;
    }

    private static double horizontalDist(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    /** Festin : des chaînes d'âmes le relient aux joueurs proches et lui rendent de la vie. Il faut s'éloigner pour les briser. */
    private void feast() {
        Location c = boss.getLocation().clone();
        List<Player> near = nearby(c, 11);
        if (near.isEmpty()) {
            leap();
            return;
        }
        announce("feast");
        boss.setAI(false);
        swing();
        sound(c, "block.sculk_shrieker.shriek", 3f, 0.6f);
        sound(c, "entity.warden.heartbeat", 3f, 0.5f);
        double range = 15;
        Set<UUID> linked = new HashSet<>();
        for (Player p : near) {
            linked.add(p.getUniqueId());
            p.sendActionBar(parse(plugin.msg("boss.feast-warning")));
        }
        repeat(2, 45, i -> {
            if (boss == null || !boss.isValid()) return;
            Location heart = boss.getLocation().add(0, scale * 1.2, 0);
            for (UUID id : new ArrayList<>(linked)) {
                Player p = Bukkit.getPlayer(id);
                if (p == null || p.isDead() || p.getWorld() != heart.getWorld() || p.getLocation().distance(heart) > range) {
                    linked.remove(id);
                    if (p != null && p.isOnline()) {
                        sound(p.getLocation(), "block.chain.break", 1.5f, 0.6f);
                        p.getWorld().spawnParticle(Particle.SOUL, p.getLocation().add(0, 1, 0), 15, 0.4, 0.4, 0.4, 0.05, null, true);
                    }
                    continue;
                }
                Location from = p.getLocation().add(0, 1, 0);
                Vector dv = heart.toVector().subtract(from.toVector());
                double len = dv.length();
                dv.normalize();
                for (double t = (i % 3) * 0.3; t < len; t += 0.9) {
                    Location l = from.clone().add(dv.clone().multiply(t));
                    heart.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, l, 1, 0.03, 0.03, 0.03, 0, null, true);
                    heart.getWorld().spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(60, 200, 230), 0.9f), true);
                }
                if (i % 5 == 0) {
                    hit(p, cfg("damage.feast", 3) * mult());
                    // plus de soin pour le Roi (réglable : feast-heal-percent)
                    double heal = cfg("feast-heal-percent", 0.0);
                    if (heal > 0) {
                        hp = Math.min(maxHp, hp + maxHp * heal / 100.0);
                        heart.getWorld().spawnParticle(Particle.HEART, heart, 2, 0.6, 0.6, 0.6, 0, null, true);
                    }
                }
            }
        }, null);
        wake(92);
    }


    // ================================================================= attaques avancées

    private double arena() {
        return cfg("arena-radius", 18);
    }

    /** Point au hasard sur le sol de l'arène, entre min et max du centre. */
    private Location randomSpot(double min, double max) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double a = r.nextDouble(Math.PI * 2), d = min + Math.sqrt(r.nextDouble()) * (max - min);
        return ground(altar.clone().add(0.5 + Math.cos(a) * d, 0, 0.5 + Math.sin(a) * d));
    }

    /** Disque de particules (zones de danger / zones sûres). */
    private void disc(Location c, double r, Color col, int density) {
        Particle.DustOptions dust = new Particle.DustOptions(col, 1.7f);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < density; i++) {
            double a = rnd.nextDouble(Math.PI * 2), d = Math.sqrt(rnd.nextDouble()) * r;
            c.getWorld().spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * d, 0.15, Math.sin(a) * d), 1, 0, 0, 0, 0, dust, true);
        }
    }

    /**
     * Jugement Infernal : il retourne au centre et charge une explosion qui couvre TOUTE l'arène.
     * Seuls quelques cercles verts protègent (moins il y en a, plus la phase est avancée).
     */
    private void nova() {
        announce("nova");
        boss.setAI(false);
        swing();
        Location c = ground(altar.clone().add(0.5, 0, 0.5));
        Location from = boss.getLocation();
        from.getWorld().spawnParticle(Particle.LARGE_SMOKE, from.clone().add(0, scale, 0), 60, 1, scale, 1, 0.05, null, true);
        Location to = c.clone();
        to.setYaw(boss.getYaw());
        boss.teleport(to);
        sound(c, "entity.wither.spawn", 3f, 0.5f);
        double arena = arena();
        int safeN = Math.max(1, (phase == 1 ? 4 : phase == 2 ? 3 : 2) + (nearby(c, arena + 6).size() >= 6 ? 1 : 0));
        double safeR = 3.2;
        List<Location> safe = new ArrayList<>();
        int tries = 0;
        while (safe.size() < safeN && tries++ < 200) {
            Location s = randomSpot(6, arena - safeR);
            boolean ok = true;
            for (Location o : safe) if (horizontalDist(o, s) < safeR * 3) ok = false;
            if (ok) safe.add(s);
        }
        int charge = (int) cfg("nova-warning-ticks", 70);
        Particle.DustOptions green = new Particle.DustOptions(Color.fromRGB(60, 255, 90), 1.8f);
        repeat(4, charge / 4, i -> {
            // les zones sûres en vert, tout le reste se couvre de rouge
            for (Location s : safe) {
                int pts = 30;
                for (int k = 0; k < pts; k++) {
                    double a = Math.PI * 2 * k / pts;
                    c.getWorld().spawnParticle(Particle.DUST, s.clone().add(Math.cos(a) * safeR, 0.2, Math.sin(a) * safeR), 1, 0, 0, 0, 0, green, true);
                }
                c.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, s.clone().add(0, 0.4, 0), 4, safeR * 0.5, 0.1, safeR * 0.5, 0, null, true);
            }
            disc(c, arena + 2, Color.fromRGB(200, 10, 10), 160);
            for (double y = 0; y < 3 + i * 0.8; y += 0.6) {
                c.getWorld().spawnParticle(Particle.FLAME, c.clone().add(0, y, 0), 3, 0.5, 0.1, 0.5, 0.02, null, true);
            }
            sound(c, "block.note_block.basedrum", 3f, 0.5f + i * 0.08f);
            if (i % 4 == 0) for (Player p : nearby(c, arena + 8)) p.sendActionBar(parse(plugin.msg("boss.nova-warning")));
        }, () -> {
            Set<UUID> spared = new HashSet<>();
            for (Player p : nearby(c, arena + 10)) {
                for (Location s : safe) if (horizontalDist(p.getLocation(), s) <= safeR + 0.3) spared.add(p.getUniqueId());
            }
            sound(c, "entity.generic.explode", 5f, 0.4f);
            sound(c, "entity.dragon_fireball.explode", 4f, 0.5f);
            c.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, c.clone().add(0, 1, 0), 4, 3, 1, 3, 0, null, true);
            ThreadLocalRandom r = ThreadLocalRandom.current();
            for (int k = 0; k < 60; k++) {
                Location l = randomSpot(0, arena + 2);
                c.getWorld().spawnParticle(Particle.FLAME, l.clone().add(0, 0.5, 0), 12, 0.6, 1.2, 0.6, 0.08, null, true);
                if (r.nextInt(4) == 0) c.getWorld().spawnParticle(Particle.EXPLOSION, l, 1, 0, 0, 0, 0, null, true);
            }
            shake(c, arena + 15);
            for (Player p : nearby(c, arena + 10)) {
                if (spared.contains(p.getUniqueId())) continue;
                hit(p, cfg("damage.nova", 22) * mult());
                p.setFireTicks(100);
                Vector kb = p.getLocation().toVector().subtract(c.toVector()).setY(0);
                if (kb.lengthSquared() < 0.01) kb = new Vector(0.1, 0, 0);
                p.setVelocity(kb.normalize().multiply(0.8).setY(1.0));
            }
            wake(15);
        });
    }

    /** Croix Infernale : des rayons de feu partent du Roi et tournent autour de lui ; il faut courir avec eux. */
    private void hellCross() {
        announce("cross");
        boss.setAI(false);
        swing();
        Location c = boss.getLocation().clone();
        int arms = phase >= 3 ? 6 : 4;
        double len = arena() + 6, half = 1.0 * zs();
        double start = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        double spin = (ThreadLocalRandom.current().nextBoolean() ? 1 : -1) * Math.toRadians(phase >= 3 ? 150 : 110);
        int warn = 26, dur = 80;
        sound(c, "block.respawn_anchor.charge", 3f, 0.6f);
        Particle.DustOptions red = new Particle.DustOptions(Color.fromRGB(220, 30, 0), 1.6f);
        repeat(3, warn / 3, i -> {
            for (int k = 0; k < arms; k++) {
                double a = start + Math.PI * 2 * k / arms;
                for (double d = 1.5; d <= len; d += 1.0) {
                    c.getWorld().spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * d, 0.15, Math.sin(a) * d), 1, 0, 0, 0, 0, red, true);
                }
            }
        }, null);
        Map<UUID, Integer> cd = new HashMap<>();
        later(warn, () -> {
            sound(c, "entity.blaze.shoot", 3f, 0.4f);
            repeat(1, dur, i -> {
                double base = start + spin * (i + 1) / dur;
                for (int k = 0; k < arms; k++) {
                    double a = base + Math.PI * 2 * k / arms;
                    double ca = Math.cos(a), sa = Math.sin(a);
                    if (i % 2 == 0) {
                        for (double d = 1.5; d <= len; d += 1.1) {
                            Location l = c.clone().add(ca * d, 0.6, sa * d);
                            c.getWorld().spawnParticle(phase >= 3 ? Particle.SOUL_FIRE_FLAME : Particle.FLAME, l, 2, half * 0.3, 0.4, half * 0.3, 0.01,
                                    null, true);
                        }
                    }
                    for (Player p : nearby(c, len + 1)) {
                        Vector to = p.getLocation().toVector().subtract(c.toVector());
                        double along = to.getX() * ca + to.getZ() * sa;
                        double perp = Math.abs(-to.getX() * sa + to.getZ() * ca);
                        if (along < 0.5 || along > len || perp > half + 0.35) continue;
                        Integer last = cd.get(p.getUniqueId());
                        if (last != null && i - last < 12) continue;
                        cd.put(p.getUniqueId(), i);
                        hit(p, cfg("damage.cross", 7) * mult());
                        p.setFireTicks(60);
                    }
                }
                if (i % 10 == 0) sound(c, "block.fire.ambient", 3f, 0.6f);
                if (boss != null && boss.isValid()) boss.setRotation((float) Math.toDegrees(base) - 90, 0);
            }, null);
        });
        wake(warn + dur + 8);
    }

    /** Cage de Citrouilles : un cercle de lanternes se referme sur un joueur ; il faut sortir par la brèche verte. */
    private void cage() {
        List<Player> near = nearby(boss.getLocation(), arena() + 6);
        if (near.isEmpty()) return;
        java.util.Collections.shuffle(near);
        announce("cage");
        swing();
        int cages = Math.min(near.size(), phase >= 3 ? 3 : 2);
        for (int n = 0; n < cages; n++) cageOn(near.get(n));
    }

    private void cageOn(Player target) {
        Location c = ground(target.getLocation().clone());
        double r0 = 6.5 * zs();
        int count = 22, gap = 3, dur = 75;
        double gapStart = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        target.sendActionBar(parse(plugin.msg("boss.cage-warning")));
        sound(c, "block.chain.place", 2f, 0.5f);
        List<BlockDisplay> lan = new ArrayList<>();
        for (int k = 0; k < count - gap; k++) {
            BlockDisplay d = c.getWorld().spawn(c, BlockDisplay.class, e -> {
                e.setPersistent(false);
                e.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 4);
                e.setBlock(Material.JACK_O_LANTERN.createBlockData());
                e.setBrightness(new Display.Brightness(15, 15));
                e.setTeleportDuration(1);
                e.setTransformation(centered(1.1f, new Quaternionf()));
            });
            lan.add(d);
            props.add(d);
        }
        Map<UUID, Integer> cd = new HashMap<>();
        Particle.DustOptions green = new Particle.DustOptions(Color.fromRGB(60, 255, 90), 1.8f);
        repeat(1, dur, i -> {
            double r = Math.max(0.8, r0 * (1 - (i + 1.0) / dur));
            double rot = gapStart + i * 0.012;
            double step = Math.PI * 2 / count;
            for (int k = 0; k < lan.size(); k++) {
                double a = rot + step * (k + gap);
                Location l = c.clone().add(Math.cos(a) * r, 0.6, Math.sin(a) * r);
                l.setYaw((float) Math.toDegrees(a));
                lan.get(k).teleport(l);
            }
            // la brèche
            for (int g = 0; g < gap; g++) {
                double a = rot + step * g;
                c.getWorld().spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * r, 0.3, Math.sin(a) * r), 2, 0.1, 0.3, 0.1, 0, green, true);
            }
            if (i % 5 == 0) c.getWorld().spawnParticle(Particle.FLAME, c.clone().add(0, 0.3, 0), (int) (r * 6), r * 0.5, 0, r * 0.5, 0.005, null, true);
            for (Player p : nearby(c, r + 1.5)) {
                double d = horizontalDist(p.getLocation(), c);
                if (Math.abs(d - r) > 0.85) continue;
                double a = Math.atan2(p.getLocation().getZ() - c.getZ(), p.getLocation().getX() - c.getX());
                double rel = ((a - rot) % (Math.PI * 2) + Math.PI * 2) % (Math.PI * 2);
                if (rel < step * gap) continue; // dans la brèche
                Integer last = cd.get(p.getUniqueId());
                if (last != null && i - last < 15) continue;
                cd.put(p.getUniqueId(), i);
                hit(p, cfg("damage.cage-touch", 5) * mult());
                p.setFireTicks(40);
                Vector in = c.toVector().subtract(p.getLocation().toVector()).setY(0);
                if (in.lengthSquared() > 0.01) p.setVelocity(in.normalize().multiply(0.7).setY(0.3));
            }
            if (i % 15 == 0) sound(c, "block.chain.step", 2f, 0.6f + i * 0.01f);
        }, () -> {
            for (BlockDisplay d : lan) {
                d.remove();
                props.remove(d);
            }
            c.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, c, 1, 0, 0, 0, 0, null, true);
            c.getWorld().spawnParticle(Particle.BLOCK, c.clone().add(0, 0.5, 0), 120, 1.5, 0.6, 1.5, 0, Material.PUMPKIN.createBlockData(), true);
            sound(c, "entity.generic.explode", 3f, 0.6f);
            for (Player p : nearby(c, 2.6 * Math.sqrt(zs()))) {
                hit(p, cfg("damage.cage", 16) * mult());
                p.setVelocity(new Vector(0, 1.1, 0));
                p.setFireTicks(80);
            }
        });
    }

    /** Tornades de flammes qui poursuivent les joueurs et les projettent en l'air. */
    private void tornado() {
        announce("tornado");
        swing();
        sound(boss.getLocation(), "entity.breeze.wind_burst", 3f, 0.5f);
        int n = 1 + phase;
        double speed = phase >= 3 ? 0.26 : 0.2;
        double rad = 1.8 * Math.sqrt(zs());
        for (int t = 0; t < n; t++) {
            Location start = randomSpot(4, arena() - 2);
            ring(start, rad + 0.5, Color.fromRGB(255, 90, 0), 24);
            Location[] pos = {start.clone()};
            Map<UUID, Integer> cd = new HashMap<>();
            later(24, () -> repeat(1, 150, i -> {
                List<Player> near = nearby(pos[0], arena() * 2);
                Player chase = closest(near, pos[0]);
                if (chase != null) {
                    Vector v = chase.getLocation().toVector().subtract(pos[0].toVector()).setY(0);
                    if (v.lengthSquared() > 0.04) pos[0].add(v.normalize().multiply(speed));
                }
                if (horizontalDist(pos[0], altar) > arena() + 1) {
                    Vector back = altar.toVector().subtract(pos[0].toVector()).setY(0).normalize().multiply(speed * 2);
                    pos[0].add(back);
                }
                pos[0] = ground(pos[0]);
                for (int k = 0; k < 7; k++) {
                    double h = k * 1.1, rr = 0.5 + k * 0.32;
                    double a = i * 0.55 + k * 1.3;
                    pos[0].getWorld().spawnParticle(Particle.FLAME, pos[0].clone().add(Math.cos(a) * rr, h, Math.sin(a) * rr), 2, 0.1, 0.15, 0.1,
                            0.01, null, true);
                    if (k % 2 == 0) pos[0].getWorld().spawnParticle(Particle.LARGE_SMOKE, pos[0].clone().add(-Math.cos(a) * rr, h, -Math.sin(a) * rr),
                            1, 0.1, 0.1, 0.1, 0.01, null, true);
                }
                if (i % 12 == 0) sound(pos[0], "entity.blaze.burn", 1.5f, 0.5f);
                for (Player p : nearby(pos[0], rad + 1)) {
                    if (horizontalDist(p.getLocation(), pos[0]) > rad || p.getLocation().getY() - pos[0].getY() > 8) continue;
                    Integer last = cd.get(p.getUniqueId());
                    if (last != null && i - last < 18) continue;
                    cd.put(p.getUniqueId(), i);
                    hit(p, cfg("damage.tornado", 6) * mult());
                    p.setFireTicks(60);
                    ThreadLocalRandom r = ThreadLocalRandom.current();
                    p.setVelocity(new Vector(r.nextDouble(-0.5, 0.5), 1.25, r.nextDouble(-0.5, 0.5)));
                }
            }, null));
        }
    }

    /** Mains des Damnés : des éruptions frappent plusieurs fois de suite là où se trouve chaque joueur. */
    private void hands() {
        List<Player> targets = nearby(boss.getLocation(), arena() + 8);
        if (targets.isEmpty()) return;
        announce("hands");
        swing();
        sound(boss.getLocation(), "entity.warden.dig", 3f, 0.6f);
        java.util.Collections.shuffle(targets);
        int strikes = phase >= 3 ? 6 : phase == 2 ? 5 : 4;
        int gapT = phase >= 3 ? 10 : 13;
        double r = 2.2 * Math.sqrt(zs());
        for (Player p : targets.subList(0, Math.min(8, targets.size()))) {
            for (int s = 0; s < strikes; s++) {
                later(s * gapT, () -> {
                    if (!p.isOnline() || p.isDead()) return;
                    Location at = ground(p.getLocation().clone());
                    ring(at, r, Color.fromRGB(150, 0, 0), 15);
                    later(15, () -> {
                        at.getWorld().spawnParticle(Particle.BLOCK, at.clone().add(0, 0.4, 0), 50, r * 0.4, 0.5, r * 0.4, 0,
                                Material.NETHERRACK.createBlockData(), true);
                        at.getWorld().spawnParticle(Particle.FLAME, at.clone().add(0, 1, 0), 25, r * 0.3, 1.2, r * 0.3, 0.05, null, true);
                        at.getWorld().spawnParticle(Particle.LAVA, at, 6, r * 0.4, 0.2, r * 0.4, 0, null, true);
                        sound(at, "entity.evoker_fangs.attack", 1.5f, 0.6f);
                        for (Player v : nearby(at, r)) {
                            hit(v, cfg("damage.hands", 8) * mult());
                            v.setVelocity(v.getVelocity().setY(0.85));
                        }
                    });
                });
            }
        }
    }

    /** Gardes du Roi : tant qu'il en reste un en vie, le Roi encaisse beaucoup moins. */
    private void summonGuards() {
        if (!guards.isEmpty()) {
            hellCross();
            return;
        }
        announce("guards");
        swing();
        Location c = boss.getLocation();
        sound(c, "entity.evoker.prepare_summon", 3f, 0.5f);
        sound(c, "block.bell.resonate", 3f, 0.5f);
        int n = Math.min(5, 2 + fighters / 2);
        double hpG = cfg("guard-health", 40) + cfg("guard-health-per-player", 15) * fighters;
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            Location at = ground(c.clone().add(Math.cos(a) * 4, 0, Math.sin(a) * 4));
            ring(at, 1.4, Color.fromRGB(120, 0, 0), 20);
            later(20, () -> {
                if (ended || boss == null || !boss.isValid()) return;
                WitherSkeleton g = at.getWorld().spawn(at, WitherSkeleton.class, z -> {
                    z.setPersistent(false);
                    z.setRemoveWhenFarAway(false);
                    z.getPersistentDataContainer().set(guardKey, PersistentDataType.BYTE, (byte) 1);
                    z.setCanPickupItems(false);
                    base(z, Attribute.SCALE, 1.35);
                    base(z, Attribute.MAX_HEALTH, hpG);
                    z.setHealth(hpG);
                    base(z, Attribute.MOVEMENT_SPEED, 0.3);
                    base(z, Attribute.ATTACK_DAMAGE, Math.max(1, cfg("guard-damage", 9) - 6));
                    base(z, Attribute.KNOCKBACK_RESISTANCE, 0.6);
                    base(z, Attribute.ARMOR, 10);
                    base(z, Attribute.FOLLOW_RANGE, 40);
                    EntityEquipment eq = z.getEquipment();
                    eq.setHelmet(new ItemStack(Material.CARVED_PUMPKIN));
                    eq.setChestplate(new ItemStack(Material.NETHERITE_CHESTPLATE));
                    eq.setItemInMainHand(new ItemStack(Material.NETHERITE_AXE));
                    for (EquipmentSlot s : EquipmentSlot.values()) {
                        try {
                            eq.setDropChance(s, 0f);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    z.customName(parse(plugin.msg("boss.guard-name")));
                    z.setCustomNameVisible(true);
                    z.setGlowing(true);
                });
                Player t = closest(nearby(at, 40), at);
                if (t != null) g.setTarget(t);
                guards.add(g);
                at.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, at.clone().add(0, 1, 0), 40, 0.5, 1, 0.5, 0.05, null, true);
                sound(at, "entity.wither_skeleton.ambient", 2f, 0.5f);
            });
        }
        later(22, () -> {
            for (Player p : nearby(c, 60)) p.sendActionBar(parse(plugin.msg("boss.shield")));
        });
    }

    private void tickGuards(Location c) {
        guards.removeIf(g -> !g.isValid() || g.isDead());
        if (guards.isEmpty()) return;
        // bouclier visible + liens vers les gardes
        if (tick % 4 == 0) {
            double r = scale * 0.9;
            for (int k = 0; k < 16; k++) {
                double a = tick * 0.1 + Math.PI * 2 * k / 16;
                c.getWorld().spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * r, scale * (0.3 + (k % 4) * 0.4), Math.sin(a) * r), 1, 0, 0, 0, 0,
                        new Particle.DustOptions(Color.fromRGB(255, 30, 30), 1.4f), true);
            }
            for (Mob g : guards) {
                Location from = g.getLocation().add(0, 1.5, 0);
                Vector v = c.clone().add(0, scale, 0).toVector().subtract(from.toVector());
                double len = v.length();
                v.normalize();
                for (double t = 0; t < len; t += 1.4) {
                    c.getWorld().spawnParticle(Particle.DUST, from.clone().add(v.clone().multiply(t)), 1, 0, 0, 0, 0,
                            new Particle.DustOptions(Color.fromRGB(150, 0, 0), 0.9f), true);
                }
                // les gardes restent dans l'arène
                if (horizontalDist(g.getLocation(), altar) > arena() + 4) g.teleport(ground(altar.clone().add(0.5, 0, 0.5)));
                if (g.getTarget() == null || !(g.getTarget() instanceof Player)) {
                    Player t = closest(nearby(g.getLocation(), 40), g.getLocation());
                    if (t != null) g.setTarget(t);
                }
            }
        }
    }

    /**
     * JUGEMENT DERNIER (phase 3, une seule fois) : le Roi s'élève, fait grossir une citrouille géante au-dessus de
     * lui, la lance sur le groupe de joueurs. Un immense cercle rouge montre où elle tombe : ceux qui sont dedans
     * à l'impact meurent sur le coup (même avec un totem). ~6 secondes pour fuir. Le Roi est intouchable pendant.
     */
    private void judgment() {
        judgmentDone = true;
        Location base = ground(altar.clone().add(0.5, 0, 0.5));
        World w = base.getWorld();
        double radius = cfg("judgment-radius", 10);
        int lockAt = 40, impact = 135;
        invulnerable = impact + 40;
        boss.setAI(false);
        swing();
        titleAll("boss.title-judgment", "boss.subtitle-judgment");
        announce("judgment");
        sound(base, "entity.wither.spawn", 5f, 0.4f);
        sound(base, "entity.ender_dragon.growl", 5f, 0.5f);
        for (Player p : nearby(base, 70)) p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 50, 0, false, false, false));
        // le Roi s'élève au-dessus de l'arène
        Location start = boss.getLocation().clone();
        Location high = base.clone().add(0, 9, 0);
        high.setYaw(start.getYaw());
        repeat(1, 25, i -> {
            if (boss == null || !boss.isValid()) return;
            double t = (i + 1) / 25.0;
            Location l = start.clone().add(high.clone().subtract(start).toVector().multiply(t * t * (3 - 2 * t)));
            l.setYaw(start.getYaw() + i * 6);
            boss.teleport(l);
            w.spawnParticle(Particle.SOUL_FIRE_FLAME, l.clone().add(0, 0.5, 0), 6, 0.6, 0.3, 0.6, 0.02, null, true);
        }, null);
        // la citrouille géante grossit au-dessus de lui
        Location orb = high.clone().add(0, scale * 2.4 + 6, 0);
        float size = (float) cfg("judgment-size", 9);
        BlockDisplay pumpkin = w.spawn(orb, BlockDisplay.class, e -> {
            e.setPersistent(false);
            e.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 4);
            e.setBlock(Material.JACK_O_LANTERN.createBlockData());
            e.setBrightness(new Display.Brightness(15, 15));
            e.setGlowing(true);
            e.setGlowColorOverride(Color.fromRGB(0xff3b00));
            e.setTeleportDuration(1);
            e.setViewRange(8f);
            e.setTransformation(centered(0.2f, new Quaternionf()));
        });
        props.add(pumpkin);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!pumpkin.isValid()) return;
            pumpkin.setInterpolationDelay(0);
            pumpkin.setInterpolationDuration(lockAt);
            pumpkin.setTransformation(centered(size, new Quaternionf().rotateY(1.2f).rotateX(0.3f)));
        }, 2L);
        // les âmes de l'arène affluent dans la citrouille
        repeat(2, lockAt / 2, i -> {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            double arena = cfg("arena-radius", 18);
            for (int k = 0; k < 10; k++) {
                double a = r.nextDouble(Math.PI * 2);
                Location src = ground(altar.clone().add(0.5 + Math.cos(a) * arena, 0, 0.5 + Math.sin(a) * arena)).add(0, 1, 0);
                Vector v = orb.toVector().subtract(src.toVector());
                w.spawnParticle(i % 2 == 0 ? Particle.SOUL_FIRE_FLAME : Particle.FLAME, src, 0, v.getX(), v.getY(), v.getZ(), 0.07, null, true);
            }
            if (i % 5 == 0) w.strikeLightningEffect(ground(altar.clone().add(0.5 + r.nextDouble(-arena, arena), 0, 0.5 + r.nextDouble(-arena, arena))));
            if (i % 4 == 0) sound(orb, "entity.warden.heartbeat", 4f, 0.5f + i * 0.03f);
        }, null);
        // cible : là où il y a le plus de joueurs
        later(lockAt, () -> {
            List<Player> in = nearby(base, cfg("arena-radius", 18) + 8);
            Location target = base.clone();
            if (!in.isEmpty()) {
                Player best = in.get(0);
                int bestCount = -1;
                for (Player p : in) {
                    int c = 0;
                    for (Player o : in) if (horizontalDist(o.getLocation(), p.getLocation()) <= radius) c++;
                    if (c > bestCount) {
                        bestCount = c;
                        best = p;
                    }
                }
                target = ground(best.getLocation().clone());
            }
            Location lock = target;
            int fall = impact - lockAt;
            ring(lock, radius, Color.fromRGB(255, 0, 0), fall);
            sound(lock, "block.respawn_anchor.charge", 5f, 0.5f);
            Particle.DustOptions dark = new Particle.DustOptions(Color.fromRGB(60, 0, 0), 2.2f);
            repeat(1, fall, i -> {
                double t = (i + 1) / (double) fall;
                // la citrouille avance au-dessus de la cible puis s'écrase (accélère)
                Location from = orb.clone();
                Location over = lock.clone().add(0, 26, 0);
                Location pos;
                if (t < 0.45) {
                    double u = t / 0.45;
                    pos = from.clone().add(over.clone().subtract(from).toVector().multiply(u * u * (3 - 2 * u)));
                    pos.add(0, Math.sin(Math.PI * u) * 6, 0);
                } else {
                    double u = (t - 0.45) / 0.55;
                    pos = over.clone().add(0, -(26 - size / 2.0) * u * u, 0);
                }
                pos.setYaw(i * 7f);
                pumpkin.teleport(pos);
                w.spawnParticle(Particle.FLAME, pos, 30, size * 0.35, size * 0.35, size * 0.35, 0.06, null, true);
                w.spawnParticle(Particle.LARGE_SMOKE, pos.clone().add(0, size * 0.5, 0), 10, 1.5, 1.5, 1.5, 0.03, null, true);
                // l'ombre qui grandit
                double sr = radius * Math.min(1, 0.3 + t);
                for (int k = 0; k < 40; k++) {
                    double a = Math.PI * 2 * k / 40 + i * 0.05;
                    w.spawnParticle(Particle.DUST, lock.clone().add(Math.cos(a) * sr * ThreadLocalRandom.current().nextDouble(), 0.12,
                            Math.sin(a) * sr * ThreadLocalRandom.current().nextDouble()), 1, 0, 0, 0, 0, dark, true);
                }
                if (i % 20 == 0) {
                    int left = (fall - i) / 20 + 1;
                    for (Player p : nearby(lock, radius + 12)) {
                        boolean inside = horizontalDist(p.getLocation(), lock) <= radius;
                        p.sendActionBar(parse(plugin.msg(inside ? "boss.judgment-inside" : "boss.judgment-outside")
                                .replace("<seconds>", String.valueOf(left))));
                        if (inside) p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BELL, 2f, 0.5f + 0.15f * (5 - left));
                    }
                }
                if (i % 10 == 0) sound(pos, "entity.blaze.burn", 4f, 0.4f);
            }, () -> {
                pumpkin.remove();
                props.remove(pumpkin);
                judgmentImpact(lock, radius, size);
            });
        });
        // le Roi redescend
        later(impact + 25, () -> {
            if (boss == null || !boss.isValid()) return;
            Location from = boss.getLocation().clone();
            Location down = base.clone();
            down.setYaw(from.getYaw());
            repeat(1, 12, i -> {
                if (boss == null || !boss.isValid()) return;
                double t = (i + 1) / 12.0;
                boss.teleport(from.clone().add(down.clone().subtract(from).toVector().multiply(t * t)));
            }, () -> {
                shockwave(down, 8, 1.2, 0);
                invulnerable = 0;
                if (boss != null && boss.isValid() && !ended) boss.setAI(true);
                nextAttack = 40;
            });
        });
    }

    private void judgmentImpact(Location c, double radius, float size) {
        World w = c.getWorld();
        sound(c, "entity.generic.explode", 6f, 0.4f);
        sound(c, "entity.dragon_fireball.explode", 6f, 0.5f);
        sound(c, "entity.wither.death", 4f, 0.6f);
        sound(c, "entity.warden.sonic_boom", 5f, 0.5f);
        try {
            Object flash = Particle.FLASH.getDataType() == Color.class ? Color.fromRGB(255, 120, 40) : null;
            w.spawnParticle(Particle.FLASH, c.clone().add(0, 2, 0), 3, 1, 1, 1, 0, flash, true);
        } catch (RuntimeException ignored) {
            // particule différente selon la version : pas grave
        }
        for (int k = 0; k < 10; k++) {
            double a = Math.PI * 2 * k / 10;
            w.spawnParticle(Particle.EXPLOSION_EMITTER, c.clone().add(Math.cos(a) * radius * 0.6, 1, Math.sin(a) * radius * 0.6), 1, 0, 0, 0, 0, null, true);
        }
        w.spawnParticle(Particle.EXPLOSION_EMITTER, c.clone().add(0, 2, 0), 4, 1.5, 1, 1.5, 0, null, true);
        w.spawnParticle(Particle.BLOCK, c.clone().add(0, 1, 0), 600, radius * 0.5, 1.5, radius * 0.5, 0, Material.PUMPKIN.createBlockData(), true);
        w.spawnParticle(Particle.LAVA, c, 120, radius * 0.5, 0.5, radius * 0.5, 0, null, true);
        // colonne de feu
        for (double y = 0; y < 22; y += 0.7) {
            w.spawnParticle(Particle.FLAME, c.clone().add(0, y, 0), 12, 1.2 + y * 0.05, 0.2, 1.2 + y * 0.05, 0.04, null, true);
            w.spawnParticle(Particle.SOUL_FIRE_FLAME, c.clone().add(0, y, 0), 4, 0.6, 0.2, 0.6, 0.02, null, true);
        }
        // trois anneaux de flammes qui balaient l'arène
        for (int wave = 0; wave < 3; wave++) {
            int ww = wave;
            later(wave * 6, () -> repeat(1, 16, i -> {
                double r = 1 + (radius + 8) * (i + 1) / 16.0;
                int pts = (int) Math.max(30, r * 8);
                for (int k = 0; k < pts; k++) {
                    double a = Math.PI * 2 * k / pts;
                    w.spawnParticle(ww == 1 ? Particle.SOUL_FIRE_FLAME : Particle.FLAME, c.clone().add(Math.cos(a) * r, 0.4 + ww * 0.6, Math.sin(a) * r),
                            1, 0, 0.1, 0, 0.01, null, true);
                }
            }, null));
        }
        for (Player p : nearby(c, 80)) {
            p.playHurtAnimation((float) ThreadLocalRandom.current().nextDouble(360));
            p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 60, 0, false, false, false));
        }
        burst(c.clone().add(0, size / 2.0, 0));
        // dans le cercle : mort instantanée (même avec un totem) ; juste à côté : projeté
        for (Player p : nearby(c, radius + 8)) {
            double d = horizontalDist(p.getLocation(), c);
            if (d <= radius && Math.abs(p.getLocation().getY() - c.getY()) < 8) {
                p.setNoDamageTicks(0);
                p.damage(100000, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC_KILL)
                        .withCausingEntity(boss).withDirectEntity(boss).build());
                if (!p.isDead()) p.setHealth(0);
                Bukkit.broadcast(parse(plugin.msg("boss.judgment-kill").replace("<player>", SmpBoss.MM.escapeTags(p.getName()))));
            } else {
                Vector kb = p.getLocation().toVector().subtract(c.toVector()).setY(0);
                if (kb.lengthSquared() < 0.01) kb = new Vector(0.1, 0, 0);
                p.setVelocity(kb.normalize().multiply(1.6).setY(0.8));
                hit(p, cfg("damage.judgment-blast", 4) * mult());
            }
        }
    }

    /** Enragé : sous 30 % de vie en phase 3, il frappe plus fort, plus vite et enchaîne toujours. */
    private void enrage() {
        enraged = true;
        titleAll("boss.title-enrage", "boss.subtitle-enrage");
        Location c = boss.getLocation();
        sound(c, "entity.ender_dragon.growl", 4f, 0.5f);
        sound(c, "entity.ravager.roar", 4f, 0.5f);
        shockwave(c, 12 * zs(), 2.0, cfg("damage.phase-shockwave", 4) * 2);
        bar.color(BossBar.Color.RED);
        AttributeInstance sp = boss.getAttribute(Attribute.MOVEMENT_SPEED);
        if (sp != null) sp.setBaseValue(cfg("speed", 0.27) * 1.6);
        for (Player p : nearby(c, 60)) p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 40, 0, false, false, false));
        nextAttack = 20;
    }

    // ================================================================= fausse mort → Roi Sans Tête

    /** Fin de la phase 2 : il s'effondre... puis se relève, toute sa vie revenue. */
    private void resurrect() {
        reviving = true;
        hp = 0;
        invulnerable = Integer.MAX_VALUE / 4;
        Location c = boss.getLocation().clone();
        boss.setAI(false);
        for (Entity m : minions) m.remove();
        minions.clear();
        for (Entity g : guards) g.remove();
        guards.clear();
        bar.name(parse(plugin.msg("boss.bar-dead")));
        bar.progress(0f);
        titleAll("boss.title-fake-death", "boss.subtitle-fake-death");
        sound(c, "entity.wither.death", 3f, 0.7f);
        float from = scale;
        repeat(1, 30, i -> {
            if (boss == null || !boss.isValid()) return;
            setScale(from - (from - 1.6f) * (i + 1) / 30f);
            boss.getWorld().spawnParticle(Particle.LARGE_SMOKE, boss.getLocation().add(0, 1, 0), 8, 0.8, 0.8, 0.8, 0.02, null, true);
        }, null);
        for (ItemDisplay d : border) if (d.isValid()) d.setItemStack(new ItemStack(Material.CARVED_PUMPKIN));
        for (ItemDisplay d : orbit) if (d.isValid()) d.setItemStack(new ItemStack(Material.CARVED_PUMPKIN));
        if (crown != null && crown.isValid()) crown.setGlowing(false);
        // silence... puis le cœur se remet à battre, les âmes de l'arène affluent vers lui
        later(50, () -> {
            for (Player p : nearby(c, 60)) p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 120, 0, false, false, false));
        });
        later(60, () -> repeat(2, 45, i -> {
            if (boss == null || !boss.isValid()) return;
            Location b = boss.getLocation().add(0, 1, 0);
            double arena = cfg("arena-radius", 18);
            ThreadLocalRandom r = ThreadLocalRandom.current();
            for (int k = 0; k < 8; k++) {
                double a = r.nextDouble(Math.PI * 2);
                Location src = ground(altar.clone().add(0.5 + Math.cos(a) * arena, 0, 0.5 + Math.sin(a) * arena)).add(0, 1, 0);
                Vector v = b.toVector().subtract(src.toVector());
                b.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, src, 0, v.getX(), v.getY(), v.getZ(), 0.06, null, true);
            }
            bar.progress((float) Math.min(1, (i + 1) / 45.0));
            if (i % 8 == 0) sound(b, "entity.warden.heartbeat", 3f, 0.5f + i * 0.02f);
            if (i % 15 == 0) shake(b, 30);
        }, null));
        later(155, () -> {
            if (boss == null || !boss.isValid()) return;
            phase = 3;
            maxHp = perPlayer() * fighters;
            hp = maxHp;
            Location h = boss.getLocation().add(0, scale * 1.8, 0);
            h.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, h, 2, 0.5, 0.5, 0.5, 0, null, true);
            h.getWorld().spawnParticle(Particle.BLOCK, h, 160, 1, 1, 1, 0, Material.PUMPKIN.createBlockData(), true);
            h.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, h, 120, 1.5, 1.5, 1.5, 0.15, null, true);
            sound(h, "entity.generic.explode", 4f, 0.6f);
            sound(h, "entity.wither.spawn", 4f, 0.8f);
            EntityEquipment eq = boss.getEquipment();
            eq.setHelmet(new ItemStack(Material.CARVED_PUMPKIN));
            eq.setChestplate(leather(Material.LEATHER_CHESTPLATE, 0x1a0f2e));
            eq.setLeggings(leather(Material.LEATHER_LEGGINGS, 0x120a20));
            eq.setBoots(leather(Material.LEATHER_BOOTS, 0x0a0612));
            for (ItemDisplay d : orbit) if (d.isValid()) d.setItemStack(new ItemStack(Material.SOUL_LANTERN));
            for (ItemDisplay d : border) if (d.isValid()) d.setItemStack(new ItemStack(Material.SOUL_LANTERN));
            if (crown != null && crown.isValid()) {
                crown.setGlowing(true);
                crown.setGlowColorOverride(Color.fromRGB(0x7ff3ff));
            }
            boss.customName(parse(plugin.msg("boss.name-headless")));
            double arena = cfg("arena-radius", 18);
            for (int k = 0; k < 10; k++) {
                double a = Math.PI * 2 * k / 10;
                altar.getWorld().strikeLightningEffect(ground(altar.clone().add(0.5 + Math.cos(a) * arena, 0, 0.5 + Math.sin(a) * arena)));
            }
            titleAll("boss.title-headless", "boss.subtitle-headless");
            bar.color(BossBar.Color.BLUE);
            shockwave(boss.getLocation(), 14, 1.8, cfg("damage.phase-shockwave", 4) * 1.5);
            float to = (float) cfg("scale-phase-3-v2", 4.6);
            repeat(1, 25, i -> {
                if (boss == null || !boss.isValid()) return;
                scale = 1.6f + (to - 1.6f) * (i + 1) / 25f;
                setScale(scale);
            }, null);
            AttributeInstance sp = boss.getAttribute(Attribute.MOVEMENT_SPEED);
            if (sp != null) sp.setBaseValue(cfg("speed", 0.27) * 1.4);
        });
        later(185, () -> {
            reviving = false;
            invulnerable = 0;
            if (boss != null && boss.isValid()) boss.setAI(true);
            nextAttack = 25;
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
            if (dmg > 0) hit(p, dmg * mult());
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
        for (Entity g : guards) g.remove();
        guards.clear();
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
            if (Bukkit.getPluginManager().isPluginEnabled("SmpTitles")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "titre win " + p.getName() + " boss");
            }
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
        // un combat perdu compte quand même pour la participation des guildes (pas un arrêt du staff)
        if (!"boss.stopped".equals(msgKey) && !damage.isEmpty() && plugin.guildTop() != null) plugin.guildTop().record(damage, false);
        cleanup();
        plugin.bossEnded();
    }

    private double perPlayer() {
        return Math.max(1, cfg("health-per-player", 2400)) * (phase == 3 ? cfg("phase-3-health-multiplier", 0.6) : 1.0);
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
        for (Entity g : guards) g.remove();
        guards.clear();
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
