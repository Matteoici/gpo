package fr.smpgojo;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * Techniques de Jujutsu Kaisen pour les admins : Gojo, Sukuna, Megumi (+ Mahoraga), Yuji.
 * Le terrain détruit se reconstruit tout seul. Les territoires n'existent que dans l'affichage des joueurs.
 */
public final class SmpGojo extends JavaPlugin implements Listener {

    static final MiniMessage MM = MiniMessage.miniMessage();

    record Tech(String id, String kit, Material icon, String name, String lore) {
    }

    static final Map<String, Tech> TECHS = new LinkedHashMap<>();
    static final Map<String, List<String>> KITS = new LinkedHashMap<>();

    private static void tech(String kit, String id, Material icon, String name, String lore) {
        TECHS.put(id, new Tech(id, kit, icon, name, lore));
        KITS.computeIfAbsent(kit, k -> new ArrayList<>()).add(id);
    }

    static {
        tech("gojo", "infini", Material.WHITE_DYE, "<gradient:#ffffff:#9ad7ff><b>∞ Infini", "Rien ne peut t'atteindre (on / off)");
        tech("gojo", "bleu", Material.LIGHT_BLUE_DYE, "<gradient:#2878ff:#9ad7ff><b>Amplification : Bleu", "Un trou qui aspire tout, terrain compris");
        tech("gojo", "rouge", Material.RED_DYE, "<gradient:#ff1e1e:#ff9a9a><b>Inversion : Rouge", "Une répulsion qui fait exploser la zone");
        tech("gojo", "violet", Material.PURPLE_DYE, "<gradient:#9628ff:#e0a8ff><b>Violet creux", "Incantation complète : efface tout devant toi");
        tech("gojo", "nuke", Material.AMETHYST_CLUSTER, "<gradient:#5a00a8:#ff4dff><b>Violet creux : 200 %", "Depuis le ciel, une sphère qui rase la zone");
        tech("gojo", "domaine", Material.ENDER_EYE, "<gradient:#ffffff:#000000:#ffffff><b>Extension du territoire",
                "Sphère de l'infini : tout le monde est figé");
        tech("sukuna", "demantelement", Material.IRON_SWORD, "<gradient:#ffffff:#aa0000><b>Démantèlement", "Trois lames qui découpent tout, sol compris");
        tech("sukuna", "clivage", Material.NETHERITE_SWORD, "<gradient:#aa0000:#2a0000><b>Clivage", "Découpe la cible visée en morceaux");
        tech("sukuna", "fuga", Material.BLAZE_ROD, "<gradient:#ff3c00:#ffb000><b>⟢ Ouverture : Fuga", "Une flèche de feu qui calcine la zone");
        tech("sukuna", "taille", Material.ECHO_SHARD, "<gradient:#ffffff:#000000><b>Taille du monde", "Coupe l'espace lui-même (traverse l'Infini)");
        tech("sukuna", "sanctuaire", Material.WITHER_SKELETON_SKULL, "<gradient:#8b0000:#ff2a2a:#8b0000><b>Extension du territoire",
                "Sanctuaire Démoniaque : tout est haché, puis brûlé");
        tech("megumi", "chiens", Material.BONE, "<gradient:#ffffff:#222222><b>Chiens de jade", "Deux chiens surgissent de ton ombre");
        tech("megumi", "nue", Material.PHANTOM_MEMBRANE, "<gradient:#ffe066:#4040ff><b>Nue", "L'oiseau-tonnerre plonge sur ta cible");
        tech("megumi", "crapaud", Material.SLIME_BALL, "<gradient:#4fbf4f:#204020><b>Crapaud", "Sa langue attrape la cible et la ramène");
        tech("megumi", "serpent", Material.BROWN_DYE, "<gradient:#8a5a3a:#2a1a10><b>Grand Serpent", "Jaillit du sol et emporte la cible");
        tech("megumi", "mahoraga", Material.CLOCK, "<gradient:#ffffff:#ffd040:#ffffff><b>Mahoraga",
                "Le Général Divin aux Huit Poignées : il s'adapte à tout");
        tech("megumi", "jardin", Material.INK_SAC, "<gradient:#2a0040:#000000><b>Extension du territoire",
                "Jardin des ombres chimériques : la zone devient ton ombre");
        tech("yuji", "poing", Material.IRON_INGOT, "<gradient:#4f9bff:#ffffff><b>Poing divergent",
                "Clic droit : fonce et frappe. Tes coups ont une chance de Black Flash");
        tech("yuji", "blackflash", Material.NETHERITE_INGOT, "<gradient:#000000:#ff1e1e:#000000><b>Black Flash  黒閃",
                "Le coup parfait : distorsion noire, ×2.5");
        tech("yuji", "manji", Material.FEATHER, "<gradient:#ffffff:#4f9bff><b>Coup de pied Manji", "Saut, puis écrasement au sol");
        tech("yuji", "rafale", Material.BLAZE_POWDER, "<gradient:#ffb000:#ff4000><b>Rafale", "Une pluie de coups, puis un uppercut");
        tech("yuji", "sang", Material.REDSTONE, "<gradient:#ff0000:#600000><b>Sang perforant", "Convergence, puis un rayon qui perce tout");
        tech("yuji", "flash8", Material.NETHER_STAR, "<gradient:#000000:#ff1e1e:#000000><b>黒閃 ×8",
                "8 Black Flash d'affilée autour de la cible");
        tech("toji", "lance", Material.TRIDENT, "<gradient:#9ad7ff:#3a3a3a><b>Lance céleste inversée",
                "Fonce et transperce : traverse et brise l'Infini");
        tech("toji", "nuage", Material.LEAD, "<gradient:#ff4040:#2a2a2a><b>Nuage capricieux", "Le bâton à trois sections balaie tout autour");
        tech("toji", "restriction", Material.ROTTEN_FLESH, "<gradient:#7a7a7a:#1a1a1a><b>Restriction céleste",
                "Corps surhumain pendant 30 s : vitesse, force, sauts");
        tech("toji", "pas-eclair", Material.SUGAR, "<gradient:#ffffff:#4a4a4a><b>Pas éclair", "Traverse 14 blocs d'un coup en tranchant tout");
        tech("todo", "boogie", Material.GOLD_NUGGET, "<gradient:#ffd040:#4f9bff><b>Boogie Woogie", "👏 Échange ta place avec la cible");
        tech("todo", "boogie2", Material.GOLD_INGOT, "<gradient:#4f9bff:#ffd040><b>Boogie Woogie : échange",
                "👏 Deux ennemis proches échangent leur place");
        tech("todo", "todo-poing", Material.NETHERITE_SCRAP, "<gradient:#000000:#ff1e1e><b>Poing de Todo", "Fonce et frappe : Black Flash garanti");
        tech("nanami", "ratio", Material.GOLDEN_SWORD, "<gradient:#ffd040:#ff4040><b>Ratio 7:3", "Le point faible à 7/10 : coup critique");
        tech("nanami", "heures-sup", Material.CLOCK, "<gradient:#ffd040:#ffffff><b>Heures sup'", "40 s de force décuplée");
        tech("nanami", "effondrement", Material.COBBLESTONE, "<gradient:#a0a0a0:#ffd040><b>Effondrement",
                "Le ratio appliqué au décor : tout s'écroule");
        tech("hakari", "pachinko", Material.EMERALD, "<gradient:#ffd700:#00ff7f><b>Extension du territoire",
                "Jeu de la mort oisive : jackpot = immortel");
        tech("bombe", "tsar", Material.TNT, "<gradient:#ff4000:#ffd000><b>☢ Tsar Bomba",
                "Larguée en parachute : la plus grosse bombe de l'histoire");
        tech("bombe", "satan", Material.FIREWORK_ROCKET, "<gradient:#40c040:#ff2020><b>☢ RS-28 Satan",
                "Un missile qui se divise en ogives au-dessus de la cible");
        tech("hakari", "portes", Material.IRON_DOOR, "<gradient:#c0c0c0:#ffd700><b>Portes du train", "Deux portes claquent sur la cible");
    }

    NamespacedKey abilityKey, tagKey, ownerKey, jumpKey;
    Cine cine;
    Ruin ruin;
    Gojo gojo;
    Sukuna sukuna;
    Megumi megumi;
    Yuji yuji;
    Toji toji;
    Todo todo;
    Nanami nanami;
    Hakari hakari;
    Bombes bombes;
    private final Map<String, Long> cooldowns = new HashMap<>();
    final Set<UUID> noFall = new HashSet<>();
    /** pendant un coup de technique : laquelle (pour l'adaptation de Mahoraga) */
    String technique;
    boolean piercing;
    /** nettoyages à faire si le plugin s'arrête pendant une technique (territoires...) */
    final Set<Runnable> pending = new HashSet<>();

    static Component mm(String s) {
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        abilityKey = new NamespacedKey(this, "ability");
        tagKey = new NamespacedKey(this, "jjk");
        ownerKey = new NamespacedKey(this, "owner");
        jumpKey = new NamespacedKey(this, "frozen_jump");
        cine = new Cine(this);
        ruin = new Ruin(this);
        ruin.recover();
        gojo = new Gojo(this);
        sukuna = new Sukuna(this);
        megumi = new Megumi(this);
        yuji = new Yuji(this);
        toji = new Toji(this);
        todo = new Todo(this);
        nanami = new Nanami(this);
        hakari = new Hakari(this);
        bombes = new Bombes(this);
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            cine.tick();
            ruin.tick();
            gojo.tick();
            megumi.tick();
            yuji.tick();
            toji.tick();
        }, 1L, 1L);
        // restes d'un ancien arrêt (affichages perdus)
        for (var w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) if (e.getPersistentDataContainer().has(tagKey)) e.remove();
        }
    }

    @Override
    public void onDisable() {
        for (Runnable r : new ArrayList<>(pending)) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
            }
        }
        pending.clear();
        if (gojo != null) gojo.cleanup();
        if (megumi != null) megumi.cleanup();
        if (yuji != null) yuji.cleanup();
        if (cine != null) cine.cleanup();
        if (ruin != null) ruin.restoreAll();
    }

    // ================================================================= commandes

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (name.equals("jjk")) return jjk(sender, a);
        if (!(sender instanceof Player p)) return true;
        List<String> set = KITS.get(name);
        if (set == null) return true;
        String sub = a.length == 0 ? "kit" : a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("kit")) giveKit(p, name);
        else if (set.contains(sub)) use(p, sub);
        else p.sendMessage(mm("<red>/" + name + " [kit|" + String.join("|", set) + "]"));
        return true;
    }

    private void giveKit(Player p, String kit) {
        for (String id : KITS.get(kit)) p.getInventory().addItem(item(id));
        String who = switch (kit) {
            case "gojo" -> "<gradient:#ffffff:#9ad7ff>Satoru Gojo";
            case "sukuna" -> "<gradient:#ff2a2a:#5a0000>Ryomen Sukuna";
            case "megumi" -> "<gradient:#8a8aff:#1a1a2a>Megumi Fushiguro";
            case "toji" -> "<gradient:#9a9a9a:#1a1a1a>Toji Fushiguro";
            case "todo" -> "<gradient:#ffd040:#4f9bff>Aoi Todo";
            case "nanami" -> "<gradient:#ffd040:#ffffff>Kento Nanami";
            case "hakari" -> "<gradient:#ffd700:#00ff7f>Kinji Hakari";
            case "bombe" -> "<gradient:#ff4000:#ffd000>l'arsenal nucléaire";
            default -> "<gradient:#ff7a7a:#4f9bff>Yuji Itadori";
        };
        p.sendMessage(mm("<gray>Techniques de " + who + "</gray> <gray>dans ton inventaire. <dark_gray>Clic droit pour lancer."));
    }

    private boolean jjk(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "kit" -> {
                if (!(s instanceof Player p)) return true;
                String kit = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                if (kit.equals("tout") || kit.equals("all")) for (String k : KITS.keySet()) giveKit(p, k);
                else if (KITS.containsKey(kit)) giveKit(p, kit);
                else s.sendMessage(mm("<red>/jjk kit <gojo|sukuna|megumi|yuji|toji|todo|nanami|hakari|tout>"));
            }
            case "reparer", "restore" -> s.sendMessage(mm("<green>" + ruin.restoreAll() + " blocs remis en place."));
            case "stop" -> {
                for (Runnable r : new ArrayList<>(pending)) r.run();
                pending.clear();
                megumi.cleanup();
                cine.cleanup();
                s.sendMessage(mm("<green>Tout est arrêté (invocations, territoires, effets). <gray>/jjk reparer pour le terrain."));
            }
            case "reload" -> {
                reloadConfig();
                s.sendMessage(mm("<green>Config rechargée."));
            }
            default -> {
                s.sendMessage(mm("<gradient:#9628ff:#ff2a2a><b>Jujutsu Kaisen"));
                s.sendMessage(mm("<aqua>/gojo · /sukuna · /megumi · /yuji · /toji · /todo · /nanami · /hakari</aqua> <gray>(+ kit)"));
                s.sendMessage(mm("<aqua>/jjk kit tout</aqua> <gray>- tous les kits"));
                s.sendMessage(mm("<aqua>/jjk reparer</aqua> <gray>- reconstruire le terrain tout de suite"));
                s.sendMessage(mm("<aqua>/jjk stop</aqua> <gray>- arrêter invocations et territoires · <aqua>/jjk reload"));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        String name = c.getName().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (name.equals("jjk")) {
            if (a.length == 1) out.addAll(List.of("kit", "reparer", "stop", "reload"));
            else if (a.length == 2 && a[0].equalsIgnoreCase("kit")) {
                out.addAll(KITS.keySet());
                out.add("tout");
            }
        } else if (a.length == 1) {
            out.add("kit");
            out.addAll(KITS.getOrDefault(name, List.of()));
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.startsWith(last));
        return out;
    }

    ItemStack item(String id) {
        Tech t = TECHS.get(id);
        ItemStack it = new ItemStack(t.icon());
        ItemMeta m = it.getItemMeta();
        m.displayName(mm(t.name()));
        m.lore(List.of(mm("<gray>" + t.lore()), mm("<dark_gray>Clic droit")));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(abilityKey, PersistentDataType.STRING, id);
        it.setItemMeta(m);
        return it;
    }

    String abilityOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(abilityKey, PersistentDataType.STRING);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        String id = abilityOf(e.getItem());
        if (id == null) return;
        e.setCancelled(true);
        if (!e.getPlayer().hasPermission("smpgojo.use")) return;
        use(e.getPlayer(), id);
    }

    void use(Player p, String id) {
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
            case "infini" -> gojo.toggleInfinity(p);
            case "bleu" -> gojo.blue(p);
            case "rouge" -> gojo.red(p);
            case "violet" -> gojo.purple(p);
            case "nuke" -> gojo.nuke(p);
            case "domaine" -> gojo.domain(p);
            case "demantelement" -> sukuna.dismantle(p);
            case "clivage" -> sukuna.cleave(p);
            case "fuga" -> sukuna.fuga(p);
            case "taille" -> sukuna.worldSlash(p);
            case "sanctuaire" -> sukuna.shrine(p);
            case "chiens" -> megumi.dogs(p);
            case "nue" -> megumi.nue(p);
            case "crapaud" -> megumi.toad(p);
            case "serpent" -> megumi.serpent(p);
            case "mahoraga" -> megumi.mahoraga(p);
            case "jardin" -> megumi.garden(p);
            case "poing" -> yuji.dashPunch(p);
            case "blackflash" -> yuji.blackFlashDash(p);
            case "manji" -> yuji.manji(p);
            case "rafale" -> yuji.barrage(p);
            case "sang" -> yuji.piercingBlood(p);
            case "flash8" -> yuji.flurry(p);
            case "lance" -> toji.spear(p);
            case "nuage" -> toji.cloud(p);
            case "restriction" -> toji.restriction(p);
            case "pas-eclair" -> toji.flash(p);
            case "boogie" -> todo.clap(p);
            case "boogie2" -> todo.swapTwo(p);
            case "todo-poing" -> todo.punch(p);
            case "ratio" -> nanami.ratio(p);
            case "heures-sup" -> nanami.overtime(p);
            case "effondrement" -> nanami.collapse(p);
            case "pachinko" -> hakari.domain(p);
            case "portes" -> hakari.doors(p);
            case "tsar" -> bombes.tsar(p);
            case "satan" -> bombes.satan(p);
            default -> {
            }
        }
    }

    void resetCooldown(Player p, String id) {
        cooldowns.remove(p.getUniqueId() + ":" + id);
    }

    // ================================================================= combat

    double cfg(String path, double def) {
        return getConfig().getDouble(path, def);
    }

    int cfgi(String path, int def) {
        return getConfig().getInt(path, def);
    }

    /** Peut être touché par une technique de ce joueur ? */
    boolean valid(Player caster, Entity e) {
        if (!(e instanceof LivingEntity le) || le.isDead() || e == caster) return false;
        if (e instanceof ArmorStand || e.isInvulnerable()) return false;
        if (e.getType().name().equals("MANNEQUIN")) return false;
        if (e instanceof Player p && (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE)) return false;
        return !megumi.ownedBy(e, caster);
    }

    List<LivingEntity> targets(Player caster, Location c, double r) {
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity le : c.getWorld().getNearbyLivingEntities(c, r)) if (valid(caster, le)) out.add(le);
        return out;
    }

    List<Player> viewers(Location c, double r) {
        List<Player> out = new ArrayList<>();
        for (Player p : c.getWorld().getPlayers()) if (p.getLocation().distanceSquared(c) <= r * r) out.add(p);
        return out;
    }

    /** L'entité visée (ou null). */
    LivingEntity aim(Player p, double range) {
        RayTraceResult r = p.getWorld().rayTrace(p.getEyeLocation(), p.getEyeLocation().getDirection(), range,
                org.bukkit.FluidCollisionMode.NEVER, true, 0.9, e -> valid(p, e));
        return r != null && r.getHitEntity() instanceof LivingEntity le ? le : null;
    }

    /** Le point visé (bloc ou entité), sinon au bout de la portée. */
    Location aimPoint(Player p, double range) {
        RayTraceResult r = p.getWorld().rayTrace(p.getEyeLocation(), p.getEyeLocation().getDirection(), range,
                org.bukkit.FluidCollisionMode.NEVER, true, 0.5, e -> valid(p, e));
        if (r != null) return r.getHitPosition().toLocation(p.getWorld());
        return p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(range));
    }

    /** Un coup de technique (connu de Mahoraga). */
    void hit(Player src, LivingEntity t, double dmg, String tech) {
        String prev = technique;
        technique = tech;
        try {
            t.setNoDamageTicks(0);
            t.damage(dmg, src);
        } finally {
            technique = prev;
        }
    }

    /** Un coup qui traverse l'Infini. */
    void pierceHit(Player src, LivingEntity t, double dmg, String tech) {
        boolean prev = piercing;
        piercing = true;
        try {
            hit(src, t, dmg, tech);
        } finally {
            piercing = prev;
        }
    }

    /** La cible projetée défonce tout sur son passage, puis s'écrase en faisant un petit cratère. */
    void smash(LivingEntity t, int ticks, double radius) {
        if (!ruin.enabled()) return;
        repeat(1, ticks, i -> {
            if (!t.isValid()) return;
            Vector v = t.getVelocity();
            if (v.lengthSquared() < 0.04 && i > 3) return;
            Location l = t.getLocation().add(0, t.getHeight() / 2, 0);
            Location ahead = l.clone().add(v.clone().normalize().multiply(Math.min(1.2, v.length())));
            ruin.carve(ahead, radius, 0, 0.35, v.clone().multiply(0.4), null, 40);
            Draw.fx(l, Particle.CLOUD, 3, 0.3, 0.02);
            Draw.fx(l, Particle.CRIT, 4, 0.3, 0.2);
        }, () -> {
            if (t.isValid()) {
                ruin.crater(t.getLocation(), radius + 1, 0.3, 3, false);
                ruin.shockwave(t.getLocation(), radius * 3, 6, 0.5);
            }
        });
    }

    static Vector right(Player p) {
        Vector d = p.getLocation().getDirection().setY(0);
        if (d.lengthSquared() < 0.01) d = new Vector(0, 0, 1);
        return d.normalize().crossProduct(new Vector(0, 1, 0)).multiply(-1);
    }

    /** Devant la main droite. */
    static Location hand(Player p) {
        Location eye = p.getEyeLocation();
        return eye.clone().add(eye.getDirection().multiply(0.8)).add(right(p).multiply(0.35)).add(0, -0.3, 0);
    }

    // ================================================================= outils

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
        }, 0L, Math.max(1, period));
    }

    void later(long delay, Runnable r) {
        if (delay <= 0) {
            r.run();
            return;
        }
        Bukkit.getScheduler().runTaskLater(this, r, delay);
    }

    static void sound(Location l, String key, float volume, float pitch) {
        l.getWorld().playSound(Sound.sound(Key.key("minecraft", key), Sound.Source.PLAYER, volume, pitch), l.getX(), l.getY(), l.getZ());
    }

    // ================================================================= événements

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawn(EntitySpawnEvent e) {
        if (e.isCancelled() && e.getEntity().getPersistentDataContainer().has(tagKey)) e.setCancelled(false);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurtBy(EntityDamageByEntityEvent e) {
        if (megumi.protectOwner(e)) {
            e.setCancelled(true);
            return;
        }
        if (e.getEntity() instanceof Player p && gojo.hasInfinity(p) && !piercing && !megumi.piercesInfinity(e.getDamager(), p)) {
            e.setCancelled(true);
            gojo.shimmer(p, e.getDamager());
            return;
        }
        megumi.onHit(e);
        yuji.onMelee(e);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL
                && (noFall.contains(e.getEntity().getUniqueId()) || megumi.isSummon(e.getEntity()))) {
            e.setCancelled(true);
            return;
        }
        megumi.onDamaged(e);
        hakari.onDamage(e);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        megumi.onTarget(e);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        megumi.onDeath(e);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (megumi.isSummon(e.getRightClicked())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHanging(HangingBreakEvent e) {
        ruin.onHanging(e);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location f = cine.locked(e.getPlayer());
        if (f == null || f.getWorld() != e.getTo().getWorld()) return;
        Location to = e.getTo();
        if (Math.abs(to.getX() - f.getX()) > 0.01 || Math.abs(to.getZ() - f.getZ()) > 0.01 || Math.abs(to.getY() - f.getY()) > 0.01) {
            Location back = f.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            e.setTo(back);
        }
    }

    /** Envoie le pack de sons (si configuré) à chaque connexion. */
    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        String url = getConfig().getString("resource-pack.url", "");
        if (url == null || url.isBlank()) return;
        String sha = getConfig().getString("resource-pack.sha1", "");
        byte[] hash = null;
        if (sha != null && sha.matches("[0-9a-fA-F]{40}")) {
            hash = new byte[20];
            for (int i = 0; i < 20; i++) hash[i] = (byte) Integer.parseInt(sha.substring(i * 2, i * 2 + 2), 16);
        }
        Player p = e.getPlayer();
        byte[] h = hash;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            p.addResourcePack(java.util.UUID.nameUUIDFromBytes(url.getBytes(java.nio.charset.StandardCharsets.UTF_8)), url, h,
                    mm("<gray>Sons des techniques (Black Flash...)"), getConfig().getBoolean("resource-pack.required", false));
        }, 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        gojo.quit(e.getPlayer());
        cine.quit(e.getPlayer());
        megumi.quit(e.getPlayer());
        yuji.quit(e.getPlayer());
        toji.quit(e.getPlayer());
        nanami.quit(e.getPlayer());
        hakari.quit(e.getPlayer());
        noFall.remove(e.getPlayer().getUniqueId());
    }
}
