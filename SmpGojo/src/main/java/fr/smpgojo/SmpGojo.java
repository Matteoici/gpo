package fr.smpgojo;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntConsumer;

/**
 * Techniques de Gojo pour les admins : Infini, Bleu, Rouge, Violet creux, Violet creux (nuke),
 * Extension du territoire : Sphère de l'infini.
 * Rien n'est cassé par défaut ; le dôme du territoire n'existe que dans l'affichage des joueurs.
 */
public final class SmpGojo extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Color BLUE = Color.fromRGB(60, 140, 255), RED = Color.fromRGB(255, 40, 40), PURPLE = Color.fromRGB(150, 40, 255);

    private NamespacedKey abilityKey, frozenKey;
    private final Set<UUID> infinity = new HashSet<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    /** joueurs pris dans un territoire : position figée */
    private final Map<UUID, Location> frozen = new HashMap<>();
    private final Map<Location, BlockData> toRestore = new LinkedHashMap<>();
    private Sukuna sukuna;
    /** vrai pendant un coup qui traverse l'Infini (Taille du monde) */
    private boolean piercing;

    static final List<String> GOJO = List.of("infini", "bleu", "rouge", "violet", "nuke", "domaine");
    static final List<String> SUKUNA = List.of("demantelement", "clivage", "fuga", "taille", "sanctuaire");

    static Component mm(String s) {
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        abilityKey = new NamespacedKey(this, "ability");
        frozenKey = new NamespacedKey(this, "frozen_jump");
        sukuna = new Sukuna(this);
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tickInfinity, 1L, 1L);
    }

    @Override
    public void onDisable() {
        // on remet tout ce qui a été effacé
        for (var e : toRestore.entrySet()) e.getKey().getBlock().setBlockData(e.getValue(), false);
        toRestore.clear();
    }

    // ================================================================= commande / kit

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (!(sender instanceof Player p)) return true;
        boolean isSukuna = cmd.getName().equalsIgnoreCase("sukuna");
        List<String> set = isSukuna ? SUKUNA : GOJO;
        String sub = a.length == 0 ? "kit" : a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("kit")) {
            for (String id : set) p.getInventory().addItem(item(id));
            p.sendMessage(mm(isSukuna ? "<dark_red>Les " + set.size() + " techniques de Sukuna sont dans ton inventaire. <gray>Clic droit pour lancer."
                    : "<white>Les " + set.size() + " techniques de Gojo sont dans ton inventaire. <gray>Clic droit pour lancer."));
        } else if (set.contains(sub) || sub.equals("domain") || sub.equals("territoire")) {
            use(p, sub);
        } else {
            p.sendMessage(mm("<red>/" + cmd.getName().toLowerCase(Locale.ROOT) + " [kit|" + String.join("|", set) + "]"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length != 1) return List.of();
        List<String> out = new ArrayList<>();
        out.add("kit");
        out.addAll(c.getName().equalsIgnoreCase("sukuna") ? SUKUNA : GOJO);
        return out.stream().filter(x -> x.startsWith(a[0].toLowerCase(Locale.ROOT))).toList();
    }

    private ItemStack item(String id) {
        record Def(Material m, String name, String lore) {
        }
        Def d = switch (id) {
            case "infini" -> new Def(Material.WHITE_DYE, "<gradient:#ffffff:#9ad7ff><b>∞ Infini", "Rien ne peut te toucher (on / off)");
            case "bleu" -> new Def(Material.LIGHT_BLUE_DYE, "<gradient:#3c8cff:#9ad7ff><b>Technique : Bleu", "Attire tout vers un point");
            case "rouge" -> new Def(Material.RED_DYE, "<gradient:#ff2828:#ff8a8a><b>Inversion : Rouge", "Repousse tout violemment");
            case "violet" -> new Def(Material.PURPLE_DYE, "<gradient:#9628ff:#e0a8ff><b>Violet creux", "Efface tout sur son passage");
            case "nuke" -> new Def(Material.AMETHYST_CLUSTER, "<gradient:#5a00a8:#ff4dff><b>Violet creux : Nuke", "Une sphère qui engloutit la zone");
            case "demantelement" -> new Def(Material.IRON_SWORD, "<gradient:#ffffff:#aa0000><b>Démantèlement",
                    "Trois lames invisibles qui tranchent à distance");
            case "clivage" -> new Def(Material.NETHERITE_SWORD, "<gradient:#aa0000:#2a0000><b>Clivage",
                    "Découpe la cible visée (plus elle est solide, plus ça coupe)");
            case "fuga" -> new Def(Material.BLAZE_ROD, "<gradient:#ff3c00:#ffb000><b>⟢ Ouverture : Fuga",
                    "Une flèche de feu qui embrase toute la zone");
            case "taille" -> new Def(Material.ECHO_SHARD, "<gradient:#ffffff:#000000><b>Taille du monde",
                    "Une entaille qui coupe l'espace (traverse l'Infini)");
            case "sanctuaire" -> new Def(Material.WITHER_SKELETON_SKULL, "<gradient:#8b0000:#ff2a2a:#8b0000><b>Extension du territoire",
                    "Sanctuaire Démoniaque : tout est tranché dans la zone");
            default -> new Def(Material.ENDER_EYE, "<gradient:#000000:#ffffff:#000000><b>Extension du territoire",
                    "Sphère de l'infini : tout le monde est figé");
        };
        ItemStack it = new ItemStack(d.m());
        ItemMeta m = it.getItemMeta();
        m.displayName(mm(d.name()));
        m.lore(List.of(mm("<gray>" + d.lore()), mm("<dark_gray>Clic droit")));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(abilityKey, PersistentDataType.STRING, id);
        it.setItemMeta(m);
        return it;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null || !it.hasItemMeta()) return;
        String id = it.getItemMeta().getPersistentDataContainer().get(abilityKey, PersistentDataType.STRING);
        if (id == null) return;
        e.setCancelled(true);
        if (!e.getPlayer().hasPermission("smpgojo.use")) return;
        use(e.getPlayer(), id);
    }

    private void use(Player p, String id) {
        if (!id.equals("infini")) {
            String k = p.getUniqueId() + ":" + id;
            long cd = (long) (getConfig().getDouble("cooldowns." + id, 3) * 1000);
            long now = System.currentTimeMillis();
            Long last = cooldowns.get(k);
            if (last != null && now - last < cd) {
                p.sendActionBar(mm("<gray>Encore <white>" + ((cd - (now - last)) / 1000 + 1) + "s"));
                return;
            }
            cooldowns.put(k, now);
        }
        switch (id) {
            case "infini" -> toggleInfinity(p);
            case "bleu" -> blue(p);
            case "rouge" -> red(p);
            case "violet" -> purple(p);
            case "nuke" -> nuke(p);
            case "domaine", "domain", "territoire" -> domain(p);
            case "demantelement" -> sukuna.dismantle(p);
            case "clivage" -> sukuna.cleave(p);
            case "fuga" -> sukuna.fuga(p);
            case "taille" -> sukuna.worldSlash(p);
            case "sanctuaire" -> sukuna.shrine(p);
            default -> p.sendMessage(mm("<red>/gojo kit · /sukuna kit"));
        }
    }

    // ================================================================= Infini

    private void toggleInfinity(Player p) {
        if (infinity.remove(p.getUniqueId())) {
            p.sendActionBar(mm("<gray>Infini désactivé"));
            sound(p.getLocation(), "block.beacon.deactivate", 1f, 1.5f);
        } else {
            infinity.add(p.getUniqueId());
            p.sendActionBar(mm("<gradient:#ffffff:#9ad7ff><b>∞ Infini activé"));
            sound(p.getLocation(), "block.beacon.activate", 1f, 1.8f);
        }
    }

    private int ticks;

    private void tickInfinity() {
        ticks++;
        for (UUID id : infinity) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            Location c = p.getLocation().add(0, 1, 0);
            // aura discrète
            if (ticks % 3 == 0) {
                double a = ticks * 0.3;
                p.getWorld().spawnParticle(Particle.END_ROD, c.clone().add(Math.cos(a) * 1.1, Math.sin(ticks * 0.1) * 0.6, Math.sin(a) * 1.1),
                        1, 0, 0, 0, 0);
            }
            for (Entity e : p.getNearbyEntities(3.5, 3.5, 3.5)) {
                Vector away = e.getLocation().toVector().subtract(c.toVector());
                double d = away.length();
                if (e instanceof Projectile pr) {
                    if (pr.getShooter() == p) continue;
                    // les projectiles ralentissent à l'infini et tombent
                    pr.setVelocity(pr.getVelocity().multiply(0.25));
                    p.getWorld().spawnParticle(Particle.ENCHANT, e.getLocation(), 3, 0.1, 0.1, 0.1, 0);
                } else if (e instanceof LivingEntity && d < 2.2 && d > 0.01) {
                    e.setVelocity(e.getVelocity().add(away.normalize().multiply(0.25).setY(0.05)));
                }
            }
        }
    }

    /** Rien ne touche un joueur sous Infini. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurt(EntityDamageByEntityEvent e) {
        if (piercing) return;
        if (e.getEntity() instanceof Player p && infinity.contains(p.getUniqueId())) {
            e.setCancelled(true);
            p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1, 0), 8, 0.5, 0.6, 0.5, 0.02);
        }
    }

    // ================================================================= Bleu (attraction)

    private void blue(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        Location orb = eye.clone().add(dir.clone().multiply(6));
        sound(eye, "entity.illusioner.cast_spell", 2f, 1.4f);
        double dmg = getConfig().getDouble("damage.bleu", 4);
        repeat(1, 60, i -> {
            orb.add(dir.clone().multiply(0.25));
            sphere(orb, 0.9, BLUE, 1.6f, 24);
            orb.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, orb, 4, 0.2, 0.2, 0.2, 0.01);
            for (Entity e : orb.getWorld().getNearbyEntities(orb, 9, 9, 9)) {
                if (e == p || !(e instanceof LivingEntity || e instanceof org.bukkit.entity.Item)) continue;
                Vector pull = orb.toVector().subtract(e.getLocation().toVector());
                double d = pull.length();
                if (d < 0.5) continue;
                e.setVelocity(pull.normalize().multiply(Math.min(1.2, 0.35 + 3 / d)));
                if (d < 1.6 && i % 10 == 0 && e instanceof LivingEntity le) le.damage(dmg, p);
            }
            if (i % 8 == 0) sound(orb, "block.respawn_anchor.charge", 0.7f, 1.6f);
        }, () -> {
            orb.getWorld().spawnParticle(Particle.EXPLOSION, orb, 2, 0.3, 0.3, 0.3, 0);
            sound(orb, "entity.generic.explode", 1f, 1.6f);
        });
    }

    // ================================================================= Rouge (répulsion)

    private void red(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Location orb = eye.clone().add(dir.clone().multiply(1.5));
        sound(eye, "entity.blaze.shoot", 2f, 0.5f);
        double dmg = getConfig().getDouble("damage.rouge", 8);
        boolean[] done = {false};
        repeat(1, 25, i -> {
            if (done[0]) return;
            for (int s = 0; s < 3; s++) {
                orb.add(dir.clone().multiply(0.6));
                sphere(orb, 0.5, RED, 1.4f, 14);
                if (!orb.getBlock().isPassable() || !orb.getWorld().getNearbyLivingEntities(orb, 1.2, e -> e != p).isEmpty()) {
                    done[0] = true;
                    redBurst(p, orb, dir, dmg);
                    return;
                }
            }
        }, () -> {
            if (!done[0]) redBurst(p, orb, dir, dmg);
        });
    }

    private void redBurst(Player p, Location at, Vector dir, double dmg) {
        at.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, at, 1, 0, 0, 0, 0);
        sphere(at, 2.5, RED, 2f, 80);
        sound(at, "entity.generic.explode", 2f, 0.7f);
        for (Entity e : at.getWorld().getNearbyEntities(at, 6, 6, 6)) {
            if (e == p || !(e instanceof LivingEntity le)) continue;
            Vector push = e.getLocation().toVector().subtract(at.toVector()).add(dir.clone().multiply(1.5));
            if (push.lengthSquared() < 0.01) push = dir.clone();
            le.damage(dmg, p);
            e.setVelocity(push.normalize().multiply(3.2).setY(0.9));
        }
    }

    // ================================================================= Violet creux

    private void purple(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Vector side = dir.clone().crossProduct(new Vector(0, 1, 0)).normalize();
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 4, false, false, false));
        sound(eye, "block.beacon.power_select", 2f, 0.6f);
        // le bleu et le rouge se rejoignent devant lui
        repeat(1, 30, i -> {
            Location base = p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(1.6));
            double off = 1.6 * (1 - i / 30.0);
            sphere(base.clone().add(side.clone().multiply(-off)), 0.35, BLUE, 1.2f, 10);
            sphere(base.clone().add(side.clone().multiply(off)), 0.35, RED, 1.2f, 10);
            if (i % 6 == 0) sound(base, "block.amethyst_block.resonate", 1.5f, 0.5f + i / 30f);
        }, () -> {
            Location start = p.getEyeLocation();
            Vector d = start.getDirection().normalize();
            Location orb = start.clone().add(d.clone().multiply(2));
            sound(start, "entity.warden.sonic_boom", 3f, 0.6f);
            Set<UUID> hit = new HashSet<>();
            double dmg = getConfig().getDouble("damage.violet", 30);
            double r = 3.2;
            repeat(1, 45, i -> {
                for (int s = 0; s < 2; s++) {
                    orb.add(d.clone().multiply(0.8));
                    sphere(orb, r, PURPLE, 2.6f, 50);
                    orb.getWorld().spawnParticle(Particle.REVERSE_PORTAL, orb, 25, r * 0.4, r * 0.4, r * 0.4, 0.05);
                    orb.getWorld().spawnParticle(Particle.END_ROD, orb, 6, 0.4, 0.4, 0.4, 0.02);
                    for (LivingEntity le : orb.getWorld().getNearbyLivingEntities(orb, r)) {
                        if (le == p || !hit.add(le.getUniqueId())) continue;
                        le.damage(dmg, p);
                        le.setVelocity(d.clone().multiply(1.5).setY(0.4));
                    }
                    if (getConfig().getBoolean("destroy-blocks", false)) erase(orb, r - 0.5);
                }
                if (i % 5 == 0) sound(orb, "entity.ender_dragon.flap", 2f, 0.5f);
            }, () -> {
                orb.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, orb, 2, 1, 1, 1, 0);
                sound(orb, "entity.generic.explode", 3f, 0.5f);
            });
        });
    }

    // ================================================================= Violet creux : nuke

    private void nuke(Player p) {
        double dist = getConfig().getDouble("nuke.distance", 30);
        double maxR = getConfig().getDouble("nuke.radius", 22);
        Block target = p.getTargetBlockExact((int) dist + 30);
        Location at = target != null ? target.getLocation().add(0.5, 1, 0.5)
                : p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(dist));
        Location above = p.getLocation().add(0, 4, 0);
        for (Player o : p.getWorld().getPlayers()) {
            if (o.getLocation().distanceSquared(at) < 150 * 150) {
                o.showTitle(Title.title(mm("<gradient:#5a00a8:#ff4dff><b>VIOLET CREUX"), mm("<gray>« ... »"),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1800), Duration.ofMillis(400))));
            }
        }
        sound(above, "block.end_portal.spawn", 3f, 0.5f);
        // charge : deux soleils au-dessus de lui qui se rejoignent
        repeat(1, 50, i -> {
            double off = 4 * (1 - i / 50.0);
            sphere(above.clone().add(-off, 0, 0), 0.8 + i * 0.02, BLUE, 2f, 24);
            sphere(above.clone().add(off, 0, 0), 0.8 + i * 0.02, RED, 2f, 24);
            if (i % 10 == 0) sound(above, "block.beacon.ambient", 3f, 0.5f);
        }, () -> {
            // la sphère violette part vers la cible
            Location orb = above.clone();
            Vector to = at.toVector().subtract(orb.toVector());
            int steps = Math.max(8, (int) (to.length() / 2.5));
            Vector step = to.multiply(1.0 / steps);
            sound(orb, "entity.warden.sonic_boom", 4f, 0.4f);
            repeat(1, steps, i -> {
                orb.add(step);
                sphere(orb, 2.5, PURPLE, 3f, 50);
                orb.getWorld().spawnParticle(Particle.REVERSE_PORTAL, orb, 30, 1, 1, 1, 0.1);
            }, () -> detonate(p, at, maxR));
        });
    }

    private void detonate(Player p, Location at, double maxR) {
        double dmg = getConfig().getDouble("damage.nuke", 40);
        Set<UUID> hit = new HashSet<>();
        sound(at, "entity.generic.explode", 5f, 0.4f);
        sound(at, "entity.wither.death", 3f, 0.6f);
        at.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, at, 6, 2, 2, 2, 0);
        int grow = 30;
        repeat(1, grow + 30, i -> {
            double r = i < grow ? maxR * (i + 1) / grow : maxR * (1 - (i - grow) / 30.0);
            if (r <= 0.5) return;
            sphere(at, r, PURPLE, 4f, (int) Math.min(220, 30 + r * 8));
            at.getWorld().spawnParticle(Particle.END_ROD, at, 30, r * 0.4, r * 0.4, r * 0.4, 0.2);
            if (i < grow) {
                for (LivingEntity le : at.getWorld().getNearbyLivingEntities(at, r)) {
                    if (le == p || !hit.add(le.getUniqueId())) continue;
                    le.damage(dmg, p);
                    Vector push = le.getLocation().toVector().subtract(at.toVector());
                    if (push.lengthSquared() < 0.01) push = new Vector(0, 1, 0);
                    le.setVelocity(push.normalize().multiply(2.5).setY(1.2));
                }
                if (getConfig().getBoolean("destroy-blocks", false) && i % 3 == 0) erase(at, r * 0.8);
                if (i % 6 == 0) sound(at, "entity.generic.explode", 3f, 0.5f + i / 60f);
            }
        }, null);
    }

    // ================================================================= Extension du territoire

    private void domain(Player p) {
        int r = getConfig().getInt("domain.radius", 12);
        int seconds = getConfig().getInt("domain.seconds", 10);
        Location c = p.getLocation().getBlock().getLocation();
        World w = c.getWorld();
        // dôme (seulement dans l'affichage des joueurs : le monde n'est jamais modifié)
        List<BlockState> fake = new ArrayList<>();
        List<Location> touched = new ArrayList<>();
        BlockData black = Material.BLACK_CONCRETE.createBlockData();
        BlockData star = Material.WHITE_CONCRETE.createBlockData();
        BlockData glow = Material.SEA_LANTERN.createBlockData();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int x = -r; x <= r; x++) {
            for (int y = -1; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    double d = Math.sqrt(x * x + y * y + z * z);
                    boolean shell = d <= r && d > r - 1.1;
                    boolean floor = y == -1 && d <= r;
                    if (!shell && !floor) continue;
                    Block b = w.getBlockAt(c.getBlockX() + x, c.getBlockY() + y, c.getBlockZ() + z);
                    BlockState st = b.getState();
                    double roll = rnd.nextDouble();
                    st.setBlockData(floor ? (roll < 0.04 ? glow : black) : roll < 0.08 ? star : roll < 0.11 ? glow : black);
                    fake.add(st);
                    touched.add(b.getLocation());
                }
            }
        }
        List<Player> viewers = new ArrayList<>();
        for (Player o : w.getPlayers()) if (o.getLocation().distanceSquared(c) < 96 * 96) viewers.add(o);
        for (Player o : viewers) {
            o.sendBlockChanges(fake);
            o.showTitle(Title.title(mm("<gradient:#ffffff:#000000:#ffffff><b>EXTENSION DU TERRITOIRE"), mm("<white>Sphère de l'infini"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(500))));
        }
        sound(c, "block.end_portal.spawn", 4f, 0.7f);
        sound(c, "entity.wither.spawn", 2f, 1.6f);

        // victimes : figées, submergées
        List<LivingEntity> victims = new ArrayList<>();
        for (LivingEntity le : w.getNearbyLivingEntities(c, r - 1)) {
            if (le == p) continue;
            victims.add(le);
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, seconds * 20, 255, false, false, false));
            le.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, seconds * 20, 255, false, false, false));
            le.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, seconds * 20, 255, false, false, false));
            if (le instanceof Mob m) m.setAware(false);
            AttributeInstance j = le.getAttribute(Attribute.JUMP_STRENGTH);
            if (j != null) {
                j.removeModifier(frozenKey);
                j.addTransientModifier(new AttributeModifier(frozenKey, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
            }
            if (le instanceof Player v) {
                frozen.put(v.getUniqueId(), v.getLocation());
                v.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, seconds * 20, 1, false, false, false));
            }
        }
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, seconds * 20, 2, false, false, false));
        repeat(4, seconds * 5, i -> {
            // flux d'informations infini
            for (LivingEntity le : victims) {
                if (!le.isValid()) continue;
                Location l = le.getLocation().add(0, 1.6, 0);
                w.spawnParticle(Particle.END_ROD, l, 10, 0.6, 0.6, 0.6, 0.15);
                w.spawnParticle(Particle.ENCHANT, l, 20, 0.5, 0.5, 0.5, 1);
                if (le instanceof Player v && i % 5 == 0) {
                    v.sendActionBar(mm("<obf>|||||||||||||||</obf> <white>∞</white> <obf>|||||||||||||||"));
                }
            }
            w.spawnParticle(Particle.END_ROD, c.clone().add(0.5, r * 0.6, 0.5), 15, r * 0.5, r * 0.3, r * 0.5, 0.01);
        }, () -> {
            // fin : on rend le vrai monde et on libère tout le monde
            List<BlockState> real = new ArrayList<>();
            for (Location l : touched) real.add(l.getBlock().getState());
            for (Player o : viewers) if (o.isOnline()) o.sendBlockChanges(real);
            for (LivingEntity le : victims) {
                if (!le.isValid()) continue;
                if (le instanceof Mob m) m.setAware(true);
                AttributeInstance j = le.getAttribute(Attribute.JUMP_STRENGTH);
                if (j != null) j.removeModifier(frozenKey);
                if (le instanceof Player v) frozen.remove(v.getUniqueId());
            }
            sound(c, "block.beacon.deactivate", 3f, 0.6f);
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location f = frozen.get(e.getPlayer().getUniqueId());
        if (f == null) return;
        Location to = e.getTo();
        if (to.getX() != f.getX() || to.getZ() != f.getZ() || to.getY() > f.getY() + 0.1) {
            Location back = f.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            e.setTo(back);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        infinity.remove(e.getPlayer().getUniqueId());
        frozen.remove(e.getPlayer().getUniqueId());
        AttributeInstance j = e.getPlayer().getAttribute(Attribute.JUMP_STRENGTH);
        if (j != null) j.removeModifier(frozenKey);
    }

    // ================================================================= outils

    /** Un coup qui ignore l'Infini. */
    void pierce(Runnable r) {
        piercing = true;
        try {
            r.run();
        } finally {
            piercing = false;
        }
    }

    void later(long delay, Runnable r) {
        Bukkit.getScheduler().runTaskLater(this, r, Math.max(0, delay));
    }

    void resetCooldown(Player p, String id) {
        cooldowns.remove(p.getUniqueId() + ":" + id);
    }

    /** Efface les blocs dans une sphère (remis après restore-seconds). Jamais la bedrock ni les conteneurs. */
    void erase(Location c, double r) {
        int ri = (int) Math.ceil(r);
        long delay = getConfig().getLong("restore-seconds", 20) * 20L;
        List<Location> erased = new ArrayList<>();
        for (int x = -ri; x <= ri; x++) {
            for (int y = -ri; y <= ri; y++) {
                for (int z = -ri; z <= ri; z++) {
                    if (x * x + y * y + z * z > r * r) continue;
                    Block b = c.getWorld().getBlockAt(c.getBlockX() + x, c.getBlockY() + y, c.getBlockZ() + z);
                    Material m = b.getType();
                    if (m.isAir() || m == Material.BEDROCK || b.getState() instanceof org.bukkit.block.Container
                            || m.getHardness() < 0) continue;
                    Location key = b.getLocation();
                    toRestore.putIfAbsent(key, b.getBlockData());
                    b.setType(Material.AIR, false);
                    erased.add(key);
                }
            }
        }
        if (erased.isEmpty()) return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (Location l : erased) {
                BlockData d = toRestore.remove(l);
                if (d != null && l.getBlock().getType().isAir()) l.getBlock().setBlockData(d, false);
            }
        }, delay);
    }

    private void sphere(Location c, double r, Color color, float size, int points) {
        Particle.DustOptions dust = new Particle.DustOptions(color, size);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < points; i++) {
            double u = rnd.nextDouble() * 2 - 1, t = rnd.nextDouble() * Math.PI * 2, s = Math.sqrt(1 - u * u);
            c.getWorld().spawnParticle(Particle.DUST, c.clone().add(s * Math.cos(t) * r, u * r, s * Math.sin(t) * r), 1, 0, 0, 0, 0, dust, true);
        }
    }

    void repeat(int period, int times, IntConsumer step, Runnable end) {
        int[] i = {0};
        BukkitTask[] self = new BukkitTask[1];
        self[0] = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (i[0] >= times) {
                self[0].cancel();
                if (end != null) end.run();
                return;
            }
            step.accept(i[0]++);
        }, 0L, period);
    }

    static void sound(Location l, String key, float volume, float pitch) {
        l.getWorld().playSound(Sound.sound(Key.key("minecraft", key), Sound.Source.PLAYER, volume, pitch), l.getX(), l.getY(), l.getZ());
    }
}
