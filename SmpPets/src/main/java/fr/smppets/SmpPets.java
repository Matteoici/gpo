package fr.smppets;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Breedable;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.ThrowableProjectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class SmpPets extends JavaPlugin implements Listener, TabExecutor {

    static final MiniMessage MM = MiniMessage.miniMessage();
    static final String TAG = "smp_pet";

    // ================================================================= modèles

    record Rarity(String id, String name, String style, Color color, Material pane, boolean announce, int order) {
    }

    record Eff(PotionEffectType type, int amp) {
    }

    record Attr(Attribute attr, String name, double amount, AttributeModifier.Operation op) {
    }

    static final class PetDef {
        String id, name, rarity, variant, particleName;
        /** familier donné automatiquement à ceux qui ont cette permission (et retiré s'ils la perdent) */
        String permission;
        /** compétence active (ex : "caca") et ses réglages */
        String ability;
        ConfigurationSection abilityCfg;
        EntityType type; // null = objet qui flotte
        Material item, icon;
        boolean baby, glow;
        double scale, height, weight;
        Particle particle;
        List<String> desc = new ArrayList<>();
        List<Eff> effects = new ArrayList<>();
        List<Attr> attrs = new ArrayList<>();
        Map<String, Double> bonus = new LinkedHashMap<>();
    }

    static final class Egg {
        String id, name, rarity;
        Material material;
        double price;
        List<String> lore = new ArrayList<>();
        /** familier -> chance en % (total 100), du plus probable au plus rare */
        final LinkedHashMap<PetDef, Double> chances = new LinkedHashMap<>();
        final LinkedHashMap<String, Double> rarityChances = new LinkedHashMap<>();

        PetDef roll() {
            double r = ThreadLocalRandom.current().nextDouble(100), acc = 0;
            PetDef last = null;
            for (Map.Entry<PetDef, Double> e : chances.entrySet()) {
                acc += e.getValue();
                last = e.getKey();
                if (r < acc) return last;
            }
            return last;
        }
    }

    static final class PData {
        final LinkedHashMap<String, Integer> owned = new LinkedHashMap<>();
        final List<String> equipped = new ArrayList<>();
        boolean hidden;
        int version;
    }

    /** Totaux des bonus des familiers équipés. */
    static final class Stats {
        double xp = 1, pve = 1, prot = 1, magnet, heal, ore, leech, bossLuck;
    }

    static final class Follower {
        final PetDef def;
        final int stars;
        Entity ent;
        TextDisplay label;
        Location pos;
        /** la compétence fait voler le familier ailleurs (au-dessus de la cible) jusqu'à overrideUntil */
        Entity chase;
        long overrideUntil;
        long retryAt;

        Follower(PetDef def, int stars) {
            this.def = def;
            this.stars = stars;
        }
    }

    final Map<String, Rarity> rarities = new LinkedHashMap<>();
    final Map<String, PetDef> pets = new LinkedHashMap<>();
    final Map<String, Egg> eggs = new LinkedHashMap<>();
    final Map<UUID, PData> data = new HashMap<>();
    final Map<UUID, Stats> stats = new HashMap<>();
    final Map<UUID, List<Follower>> active = new HashMap<>();
    final Map<UUID, Integer> activeVersion = new HashMap<>();
    final Set<UUID> opening = new HashSet<>();
    /** effets donnés par les familiers (pour les enlever quand on retire le familier) */
    final Map<UUID, Set<PotionEffectType>> applied = new HashMap<>();
    NamespacedKey eggKey, petKey;
    long tick;
    boolean vault;

    private static final double[][] OFFSETS = {{0.85, 0.55}, {-0.85, 0.55}, {0, 1.2}, {1.5, 1.1}, {-1.5, 1.1}};

    // ================================================================= démarrage

    @Override
    public void onEnable() {
        eggKey = new NamespacedKey(this, "egg");
        petKey = new NamespacedKey(this, "pet");
        saveDefaultConfig();
        if (!new File(getDataFolder(), "pets.yml").exists()) saveResource("pets.yml", false);
        migrate();
        vault = Bukkit.getPluginManager().getPlugin("Vault") != null;
        load();
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (e.getScoreboardTags().contains(TAG)) e.remove();
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("pets");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        var loky = getCommand("loky");
        if (loky != null) {
            loky.setExecutor((s, c, l, a) -> {
                if (s instanceof Player p) useAbility(p, a.length >= 1 ? a[0] : null);
                return true;
            });
            loky.setTabCompleter((s, c, l, a) -> {
                List<String> out = new ArrayList<>();
                if (a.length == 1) for (Player p : Bukkit.getOnlinePlayers()) if (!p.equals(s) && p.getName().toLowerCase(Locale.ROOT).startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(p.getName());
                return out;
            });
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            loadData(p.getUniqueId());
            syncPermissionPets(p);
            refresh(p);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tickAll, 1L, 1L);
        loadStations();
        Bukkit.getScheduler().runTaskTimer(this, this::stationAmbience, 20L, 2L);
    }

    @Override
    public void onDisable() {
        for (List<Follower> fs : active.values()) for (Follower f : fs) despawn(f);
        active.clear();
        for (String k : new ArrayList<>(shows.keySet())) removeShow(k);
        for (Player p : Bukkit.getOnlinePlayers()) {
            clearAttributes(p);
            saveData(p.getUniqueId());
        }
    }

    /** Ajoute les nouveautés aux fichiers déjà installés sans écraser les réglages. */
    private void migrate() {
        int v = getConfig().getInt("config-version", 1);
        if (v < 2) {
            if (!getConfig().isConfigurationSection("raretes.modo")) {
                getConfig().set("raretes.modo.nom", "<#ff3b3b><b>MODO");
                getConfig().set("raretes.modo.style", "<#ff5c5c>");
                getConfig().set("raretes.modo.couleur", "#ff3b3b");
                getConfig().set("raretes.modo.vitre", "RED_STAINED_GLASS_PANE");
                getConfig().set("raretes.modo.annonce", false);
            }
            File pf = new File(getDataFolder(), "pets.yml");
            YamlConfiguration pc = YamlConfiguration.loadConfiguration(pf);
            if (!pc.isConfigurationSection("pets.loky")) {
                var in = getResource("pets.yml");
                if (in != null) {
                    YamlConfiguration def = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                    ConfigurationSection loky = def.getConfigurationSection("pets.loky");
                    if (loky != null) {
                        for (String k : loky.getKeys(true)) if (!loky.isConfigurationSection(k)) pc.set("pets.loky." + k, loky.get(k));
                        try {
                            pc.save(pf);
                        } catch (IOException ex) {
                            getLogger().warning("pets.yml : " + ex.getMessage());
                        }
                    }
                }
            }
            getConfig().set("config-version", 2);
            saveConfig();
        }
        if (getConfig().getInt("config-version", 1) < 3) {
            // l'œuf mythique devient achetable
            if (getConfig().isConfigurationSection("oeufs.mythique") && getConfig().getDouble("oeufs.mythique.prix", 0) <= 0) {
                getConfig().set("oeufs.mythique.prix", 750000);
            }
            if (!getConfig().isSet("echange.active")) getConfig().set("echange.active", true);
            getConfig().set("config-version", 3);
            saveConfig();
        }
        if (getConfig().getInt("config-version", 1) < 4) {
            File pf = new File(getDataFolder(), "pets.yml");
            YamlConfiguration pc = YamlConfiguration.loadConfiguration(pf);
            // les gros familiers plus petits (moins de hitbox)
            if (pc.isConfigurationSection("pets.bebe-sniffer") && !pc.isSet("pets.bebe-sniffer.taille")) pc.set("pets.bebe-sniffer.taille", 0.55);
            if (pc.isConfigurationSection("pets.ghastling") && !pc.isSet("pets.ghastling.taille")) pc.set("pets.ghastling.taille", 0.6);
            if (pc.isConfigurationSection("pets.panda") && !pc.isSet("pets.panda.taille")) pc.set("pets.panda.taille", 0.75);
            // le creeper chargé : son aura fait trop d'effets
            if ("CHARGED".equalsIgnoreCase(pc.getString("pets.mini-creeper.variante", ""))) {
                pc.set("pets.mini-creeper.variante", null);
                if ("Mini Creeper chargé".equals(pc.getString("pets.mini-creeper.nom"))) pc.set("pets.mini-creeper.nom", "Mini Creeper");
            }
            try {
                pc.save(pf);
            } catch (IOException ex) {
                getLogger().warning("pets.yml : " + ex.getMessage());
            }
            if (!getConfig().isSet("particules-toutes-les-ticks")) getConfig().set("particules-toutes-les-ticks", 20);
            getConfig().set("config-version", 4);
            saveConfig();
        }
        if (getConfig().getInt("config-version", 1) < 5) {
            // rééquilibrage : les boosts des familiers de base sont remplacés par la nouvelle version (moins d'avantage en combat)
            File pf = new File(getDataFolder(), "pets.yml");
            YamlConfiguration pc = YamlConfiguration.loadConfiguration(pf);
            var in = getResource("pets.yml");
            if (in != null) {
                YamlConfiguration def = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                ConfigurationSection dp = def.getConfigurationSection("pets");
                if (dp != null) for (String id : dp.getKeys(false)) {
                    if (id.equals("loky") || !pc.isConfigurationSection("pets." + id)) continue;
                    for (String k : List.of("description", "effets", "attributs", "bonus")) pc.set("pets." + id + "." + k, null);
                    for (String k : List.of("description", "effets", "attributs")) if (dp.isSet(id + "." + k)) pc.set("pets." + id + "." + k, dp.getStringList(id + "." + k));
                    ConfigurationSection b = dp.getConfigurationSection(id + ".bonus");
                    if (b != null) for (String k : b.getKeys(false)) pc.set("pets." + id + ".bonus." + k, b.getDouble(k));
                }
                try {
                    pc.save(pf);
                } catch (IOException ex) {
                    getLogger().warning("pets.yml : " + ex.getMessage());
                }
            }
            if (getConfig().getDouble("etoiles.bonus-par-etoile", 0.25) == 0.25) getConfig().set("etoiles.bonus-par-etoile", 0.15);
            getConfig().set("config-version", 5);
            saveConfig();
        }
        if (getConfig().getInt("config-version", 1) < 6) {
            // chance au butin du boss : un peu moins forte
            File pf = new File(getDataFolder(), "pets.yml");
            YamlConfiguration pc = YamlConfiguration.loadConfiguration(pf);
            Map<String, Double> luck = Map.of("chaton", 0.02, "allay", 0.03, "bebe-sniffer", 0.03, "mini-warden", 0.04, "mini-wither", 0.05, "axolotl-bleu", 0.05, "etoile", 0.06);
            for (var e : luck.entrySet()) if (pc.isSet("pets." + e.getKey() + ".bonus.chance-boss")) pc.set("pets." + e.getKey() + ".bonus.chance-boss", e.getValue());
            try {
                pc.save(pf);
            } catch (IOException ex) {
                getLogger().warning("pets.yml : " + ex.getMessage());
            }
            getConfig().set("config-version", 6);
            saveConfig();
        }
    }

    void load() {
        reloadConfig();
        rarities.clear();
        pets.clear();
        eggs.clear();
        ConfigurationSection rs = getConfig().getConfigurationSection("raretes");
        int order = 0;
        if (rs != null) for (String id : rs.getKeys(false)) {
            ConfigurationSection s = rs.getConfigurationSection(id);
            if (s == null) continue;
            rarities.put(id, new Rarity(id, s.getString("nom", id), s.getString("style", "<white>"), hex(s.getString("couleur", "#ffffff")),
                    mat(s.getString("vitre"), Material.WHITE_STAINED_GLASS_PANE), s.getBoolean("annonce"), order++));
        }
        YamlConfiguration pc = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "pets.yml"));
        ConfigurationSection ps = pc.getConfigurationSection("pets");
        if (ps != null) for (String id : ps.getKeys(false)) {
            ConfigurationSection s = ps.getConfigurationSection(id);
            if (s == null) continue;
            PetDef d = new PetDef();
            d.id = id;
            d.name = s.getString("nom", id);
            d.rarity = s.getString("rarete", "rare");
            if (!rarities.containsKey(d.rarity)) {
                getLogger().warning("Familier " + id + " : rareté inconnue '" + d.rarity + "'");
                continue;
            }
            String type = s.getString("type", "FOX").toUpperCase(Locale.ROOT);
            if (type.equals("ITEM")) {
                d.item = mat(s.getString("item"), Material.NETHER_STAR);
            } else {
                try {
                    d.type = EntityType.valueOf(type);
                } catch (IllegalArgumentException ex) {
                    getLogger().warning("Familier " + id + " : mob inconnu '" + type + "'");
                    continue;
                }
                if (d.type.getEntityClass() == null || !LivingEntity.class.isAssignableFrom(d.type.getEntityClass())) {
                    getLogger().warning("Familier " + id + " : '" + type + "' n'est pas un mob");
                    continue;
                }
            }
            d.baby = s.getBoolean("bebe");
            d.glow = s.getBoolean("brille");
            d.scale = s.getDouble("taille", 1);
            d.height = s.getDouble("hauteur", 1.3);
            d.weight = Math.max(0, s.getDouble("poids", 1));
            d.variant = s.getString("variante", "");
            d.particleName = s.getString("particules", "");
            if (!d.particleName.isBlank()) {
                try {
                    Particle pa = Particle.valueOf(d.particleName.toUpperCase(Locale.ROOT));
                    if (pa.getDataType() == Void.class) d.particle = pa;
                    else getLogger().warning("Familier " + id + " : la particule " + d.particleName + " a besoin de données, choisis-en une autre");
                } catch (IllegalArgumentException ex) {
                    getLogger().warning("Familier " + id + " : particule inconnue '" + d.particleName + "'");
                }
            }
            Material defIcon = d.item != null ? d.item : Material.matchMaterial(type + "_SPAWN_EGG");
            d.icon = mat(s.getString("icone"), defIcon == null ? Material.EGG : defIcon);
            d.desc = s.getStringList("description");
            for (String e : s.getStringList("effets")) {
                String[] parts = e.split(":");
                PotionEffectType t = reg(RegistryKey.MOB_EFFECT, parts[0].trim());
                if (t == null) {
                    getLogger().warning("Familier " + id + " : effet inconnu '" + parts[0] + "'");
                    continue;
                }
                int lvl = parts.length > 1 ? parseInt(parts[1].trim(), 1) : 1;
                d.effects.add(new Eff(t, Math.max(0, lvl - 1)));
            }
            for (String a : s.getStringList("attributs")) {
                int c = a.indexOf(':');
                if (c < 0) continue;
                String name = a.substring(0, c).trim().toLowerCase(Locale.ROOT);
                String v = a.substring(c + 1).trim();
                Attribute at = reg(RegistryKey.ATTRIBUTE, name);
                if (at == null) {
                    getLogger().warning("Familier " + id + " : attribut inconnu '" + name + "'");
                    continue;
                }
                boolean pct = v.endsWith("%");
                double amount = parseDouble(pct ? v.substring(0, v.length() - 1) : v, 0);
                if (amount == 0) continue;
                // pour les attributs qui valent 1 de base (multiplicateurs), "%" = ajout direct de la fraction
                AttributeModifier.Operation op = pct && !BASE_ONE.contains(name) ? AttributeModifier.Operation.ADD_SCALAR : AttributeModifier.Operation.ADD_NUMBER;
                d.attrs.add(new Attr(at, name, pct ? amount / 100 : amount, op));
            }
            ConfigurationSection b = s.getConfigurationSection("bonus");
            if (b != null) for (String k : b.getKeys(false)) d.bonus.put(k.toLowerCase(Locale.ROOT), b.getDouble(k));
            d.permission = s.getString("permission", "");
            d.abilityCfg = s.getConfigurationSection("competence");
            if (d.abilityCfg != null) d.ability = d.abilityCfg.getString("type", "").toLowerCase(Locale.ROOT);
            pets.put(id, d);
        }
        ConfigurationSection es = getConfig().getConfigurationSection("oeufs");
        if (es != null) for (String id : es.getKeys(false)) {
            ConfigurationSection s = es.getConfigurationSection(id);
            if (s == null) continue;
            Egg g = new Egg();
            g.id = id;
            g.name = s.getString("nom", id);
            g.material = mat(s.getString("materiau"), Material.EGG);
            g.rarity = s.getString("rarete", "rare");
            g.price = s.getDouble("prix", 0);
            g.lore = s.getStringList("lore");
            Map<PetDef, Double> w = new LinkedHashMap<>();
            ConfigurationSection direct = s.getConfigurationSection("pets");
            if (direct != null && !direct.getKeys(false).isEmpty()) {
                for (String pid : direct.getKeys(false)) {
                    PetDef d = pets.get(pid);
                    if (d != null && direct.getDouble(pid) > 0) w.merge(d, direct.getDouble(pid), Double::sum);
                }
            } else {
                ConfigurationSection rw = s.getConfigurationSection("raretes");
                if (rw != null) for (String rid : rw.getKeys(false)) {
                    double weight = rw.getDouble(rid);
                    List<PetDef> pool = pets.values().stream().filter(d -> d.rarity.equals(rid) && d.weight > 0).toList();
                    double sum = pool.stream().mapToDouble(d -> d.weight).sum();
                    if (weight <= 0 || sum <= 0) continue;
                    for (PetDef d : pool) w.merge(d, weight * d.weight / sum, Double::sum);
                }
            }
            double total = w.values().stream().mapToDouble(Double::doubleValue).sum();
            if (total <= 0) {
                getLogger().warning("Œuf " + id + " : aucun familier dedans");
                continue;
            }
            w.entrySet().stream().sorted(Map.Entry.<PetDef, Double>comparingByValue().reversed())
                    .forEach(e -> g.chances.put(e.getKey(), e.getValue() * 100 / total));
            for (Rarity r : rarities.values()) {
                double c = g.chances.entrySet().stream().filter(e -> e.getKey().rarity.equals(r.id)).mapToDouble(Map.Entry::getValue).sum();
                if (c > 0) g.rarityChances.put(r.id, c);
            }
            eggs.put(id, g);
        }
        getLogger().info(pets.size() + " familiers, " + eggs.size() + " œufs.");
    }

    private static final Set<String> BASE_ONE = Set.of("fall_damage_multiplier", "burning_time", "block_break_speed", "movement_efficiency",
            "water_movement_efficiency", "submerged_mining_speed", "mining_efficiency", "sneaking_speed", "explosion_knockback_resistance");

    // ================================================================= données joueurs

    PData data(UUID id) {
        return data.computeIfAbsent(id, this::readData);
    }

    void loadData(UUID id) {
        data.put(id, readData(id));
    }

    private PData readData(UUID id) {
        PData d = new PData();
        File f = new File(getDataFolder(), "joueurs/" + id + ".yml");
        if (!f.exists()) return d;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection s = y.getConfigurationSection("pets");
        if (s != null) for (String k : s.getKeys(false)) d.owned.put(k, Math.max(1, s.getInt(k, 1)));
        for (String e : y.getStringList("equipes")) if (d.owned.containsKey(e) && !d.equipped.contains(e)) d.equipped.add(e);
        d.hidden = y.getBoolean("cache");
        return d;
    }

    void saveData(UUID id) {
        PData d = data.get(id);
        if (d == null) return;
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, Integer> e : d.owned.entrySet()) y.set("pets." + e.getKey(), e.getValue());
        y.set("equipes", d.equipped);
        y.set("cache", d.hidden);
        try {
            File dir = new File(getDataFolder(), "joueurs");
            dir.mkdirs();
            y.save(new File(dir, id + ".yml"));
        } catch (IOException ex) {
            getLogger().warning("Sauvegarde impossible pour " + id + " : " + ex.getMessage());
        }
    }

    int slots(Player p) {
        int n = Math.max(1, getConfig().getInt("emplacements", 1));
        for (int i = 2; i <= 5; i++) if (p.hasPermission("smppets.slots." + i)) n = Math.max(n, i);
        return n;
    }

    int maxStars() {
        return Math.max(1, getConfig().getInt("etoiles.max", 5));
    }

    double starMult(int stars) {
        return 1 + getConfig().getDouble("etoiles.bonus-par-etoile", 0.25) * (Math.max(1, stars) - 1);
    }

    /** Liste des familiers équipés (et existants), dans l'ordre. */
    List<PetDef> equipped(PData d) {
        List<PetDef> out = new ArrayList<>();
        for (String id : d.equipped) {
            PetDef def = pets.get(id);
            if (def != null) out.add(def);
        }
        return out;
    }

    /** Recalcule bonus + attributs, et fait réapparaître les familiers. */
    void refresh(Player p) {
        PData d = data(p.getUniqueId());
        while (d.equipped.size() > slots(p)) d.equipped.remove(d.equipped.size() - 1);
        applyStats(p);
        d.version++;
        applyEffects(p);
    }

    // ================================================================= équilibrage : rien qui aide en combat

    /** Ce qui est coupé quand le joueur est EN DANGER (SmpCombat) : pas d'avantage pour fuir ou se battre. */
    boolean combatOnly(String kind, String key) {
        List<String> l = getConfig().getStringList("coupe-en-combat." + kind);
        if (l.isEmpty() && !getConfig().isSet("coupe-en-combat." + kind)) l = switch (kind) {
            case "effets" -> List.of("speed", "dolphins_grace", "jump_boost", "resistance", "fire_resistance", "regeneration", "strength",
                    "health_boost", "absorption", "slow_falling", "invisibility", "levitation");
            case "attributs" -> List.of("movement_speed", "max_health", "armor", "armor_toughness", "knockback_resistance", "attack_damage",
                    "attack_speed", "attack_knockback", "jump_strength", "fall_damage_multiplier", "safe_fall_distance", "entity_interaction_range",
                    "block_interaction_range", "scale", "movement_efficiency", "water_movement_efficiency", "sneaking_speed", "step_height",
                    "explosion_knockback_resistance", "burning_time");
            default -> List.of("soin");
        };
        return l.contains(key.toLowerCase(Locale.ROOT));
    }

    private java.lang.reflect.Method combatDanger;
    private boolean combatLooked;
    final Map<UUID, Boolean> combatState = new HashMap<>();

    boolean inCombat(Player p) {
        try {
            var pl = Bukkit.getPluginManager().getPlugin("SmpCombat");
            if (pl == null || !pl.isEnabled()) return false;
            if (!combatLooked) {
                combatLooked = true;
                combatDanger = pl.getClass().getMethod("inDanger", Player.class);
            }
            return combatDanger != null && Boolean.TRUE.equals(combatDanger.invoke(pl, p));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /** Bonus d'un joueur pour les autres plugins (ex. SmpBoss : "chance-boss"). */
    public static double bonus(UUID id, String key) {
        SmpPets pl = JavaPlugin.getPlugin(SmpPets.class);
        Stats s = pl.stats.get(id);
        if (s == null) return 0;
        return switch (key) {
            case "chance-boss" -> s.bossLuck;
            case "xp" -> s.xp;
            default -> 0;
        };
    }

    void applyStats(Player p) {
        PData d = data(p.getUniqueId());
        boolean fight = inCombat(p);
        combatState.put(p.getUniqueId(), fight);
        Stats s = new Stats();
        clearAttributes(p);
        for (PetDef def : equipped(d)) {
            double m = starMult(d.owned.getOrDefault(def.id, 1));
            for (Map.Entry<String, Double> b : def.bonus.entrySet()) {
                double v = b.getValue();
                switch (b.getKey()) {
                    case "xp" -> s.xp *= 1 + (v - 1) * m;
                    case "degats-pve" -> s.pve *= 1 + (v - 1) * m;
                    case "protection-pve" -> s.prot *= Math.max(0.05, 1 - (1 - v) * m);
                    case "aimant" -> s.magnet = Math.max(s.magnet, v * m);
                    case "soin" -> {
                        if (!fight || !combatOnly("bonus", "soin")) s.heal += v * m;
                    }
                    case "chance-boss" -> s.bossLuck += v * m;
                    case "double-minerai" -> s.ore = Math.min(1, s.ore + v * m);
                    case "vol-de-vie" -> s.leech += v * m;
                    default -> {
                    }
                }
            }
            int i = 0;
            for (Attr a : def.attrs) {
                AttributeInstance inst = p.getAttribute(a.attr());
                NamespacedKey key = new NamespacedKey(this, def.id + "_" + (i++));
                if (inst == null || (fight && combatOnly("attributs", a.name()))) continue;
                inst.addTransientModifier(new AttributeModifier(key, a.amount() * m, a.op(), EquipmentSlotGroup.ANY));
            }
        }
        stats.put(p.getUniqueId(), s);
    }

    void clearAttributes(Player p) {
        for (Attribute a : RegistryAccess.registryAccess().getRegistry(RegistryKey.ATTRIBUTE)) {
            AttributeInstance inst = p.getAttribute(a);
            if (inst == null) continue;
            for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) {
                if (m.getKey().getNamespace().equals(getName().toLowerCase(Locale.ROOT))) inst.removeModifier(m);
            }
        }
    }

    Stats stats(Player p) {
        return stats.getOrDefault(p.getUniqueId(), new Stats());
    }

    /** 15 s, rafraîchi toutes les 2 s : jamais sous 10 s, donc la vision nocturne ne clignote pas. */
    static final int EFFECT_TICKS = 300;

    void applyEffects(Player p) {
        Set<PotionEffectType> now = new HashSet<>();
        boolean fight = Boolean.TRUE.equals(combatState.get(p.getUniqueId()));
        for (PetDef def : equipped(data(p.getUniqueId()))) for (Eff e : def.effects) {
            if (!fight || !combatOnly("effets", e.type().getKey().getKey())) now.add(e.type());
        }
        Set<PotionEffectType> before = applied.getOrDefault(p.getUniqueId(), Set.of());
        for (PotionEffectType t : before) {
            if (now.contains(t)) continue;
            PotionEffect cur = p.getPotionEffect(t);
            if (cur != null && cur.isAmbient() && !cur.hasParticles() && cur.getDuration() <= EFFECT_TICKS) p.removePotionEffect(t);
        }
        applied.put(p.getUniqueId(), now);
        if (p.isDead()) return;
        for (PetDef def : equipped(data(p.getUniqueId()))) {
            for (Eff e : def.effects) if (now.contains(e.type())) p.addPotionEffect(new PotionEffect(e.type(), EFFECT_TICKS, e.amp(), true, false, true));
        }
    }

    // ================================================================= familiers dans le monde

    boolean visible(Player p) {
        PData d = data(p.getUniqueId());
        return !d.hidden && p.isValid() && !p.isDead() && p.getGameMode() != GameMode.SPECTATOR && !vanished(p) && !inDuel(p);
    }

    private java.lang.reflect.Method duelIn, duelArena;
    private boolean duelLooked;

    /** En duel (SmpDuel) ou dans une arène de duel : les familiers disparaissent (les boosts restent). */
    boolean inDuel(Player p) {
        if (!getConfig().getBoolean("cacher-en-duel", true)) return false;
        try {
            if (!duelLooked) {
                duelLooked = true;
                var pl = Bukkit.getPluginManager().getPlugin("SmpDuel");
                if (pl != null) {
                    duelIn = pl.getClass().getMethod("inDuel", UUID.class);
                    duelArena = pl.getClass().getMethod("isArena", Location.class);
                }
            }
            if (duelIn == null) return false;
            return Boolean.TRUE.equals(duelIn.invoke(null, p.getUniqueId())) || Boolean.TRUE.equals(duelArena.invoke(null, p.getLocation()));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            return false;
        }
    }

    static boolean vanished(Player p) {
        for (org.bukkit.metadata.MetadataValue v : p.getMetadata("vanished")) if (v.asBoolean()) return true;
        return false;
    }

    static boolean isPet(Entity e) {
        return e != null && e.getScoreboardTags().contains(TAG);
    }

    private void tickAll() {
        tick++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            PData d = data(id);
            List<Follower> fs = active.get(id);
            boolean show = visible(p);
            if (fs != null && (!show || activeVersion.getOrDefault(id, -1) != d.version)) {
                for (Follower f : fs) despawn(f);
                active.remove(id);
                fs = null;
            }
            if (fs == null && show && !d.equipped.isEmpty()) {
                fs = new ArrayList<>();
                for (PetDef def : equipped(d)) fs.add(new Follower(def, d.owned.getOrDefault(def.id, 1)));
                active.put(id, fs);
                activeVersion.put(id, d.version);
            }
            if (fs != null) for (int i = 0; i < fs.size(); i++) move(p, fs.get(i), i);
            Stats s = stats(p);
            if (s.magnet > 0 && tick % 3 == 0 && !p.isDead()) magnet(p, s.magnet);
            if (tick % 10 == 0 && inCombat(p) != Boolean.TRUE.equals(combatState.get(id))) {
                applyStats(p);
                applyEffects(p);
            }
            if (tick % 40 == 0) {
                applyEffects(p);
                if (s.heal > 0 && !p.isDead()) heal(p, s.heal);
            }
        }
        // familiers dont le joueur est parti
        if (tick % 20 == 0) active.keySet().removeIf(id -> {
            if (Bukkit.getPlayer(id) != null) return false;
            for (Follower f : active.get(id)) despawn(f);
            return true;
        });
    }

    private void move(Player p, Follower f, int idx) {
        Location base = p.getLocation();
        double yaw = Math.toRadians(base.getYaw());
        double[] off = OFFSETS[Math.min(idx, OFFSETS.length - 1)];
        Vector fw = new Vector(-Math.sin(yaw), 0, Math.cos(yaw)), rt = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
        double bob = Math.sin((tick + idx * 17) * 0.09) * 0.14;
        Location target = base.clone().add(rt.multiply(off[0])).add(fw.multiply(-off[1])).add(0, f.def.height + bob, 0);
        boolean chasing = tick < f.overrideUntil && f.chase != null && f.chase.isValid() && f.chase.getWorld() == base.getWorld();
        if (chasing) target = f.chase.getLocation().add(0, f.chase.getHeight() + 1.1 + bob * 0.5, 0);
        double snap = chasing ? 60 * 60 : 144;
        if (f.pos == null || f.pos.getWorld() != target.getWorld() || f.pos.distanceSquared(target) > snap) {
            f.pos = target.clone();
            if (f.ent != null && f.ent.isValid() && f.ent.getWorld() != target.getWorld()) despawn(f);
        } else {
            f.pos.add(target.toVector().subtract(f.pos.toVector()).multiply(chasing ? 0.22 : 0.28));
        }
        float faceYaw = base.getYaw();
        if (f.ent == null || !f.ent.isValid()) {
            if (tick < f.retryAt) return;
            f.retryAt = tick + 40;
            spawn(p, f);
            return;
        }
        Location at = f.pos.clone();
        if (f.def.type == null) {
            at.setYaw((float) ((tick * 4) % 360));
            at.setPitch(0);
            f.ent.teleport(at);
            if (f.label != null && f.label.isValid()) f.label.teleport(at.clone().add(0, 0.55 * f.def.scale + 0.25, 0));
        } else {
            at.setYaw(faceYaw);
            at.setPitch(0);
            f.ent.teleport(at);
            f.ent.setRotation(faceYaw, 0);
        }
        if (f.def.particle != null && tick % Math.max(1, getConfig().getInt("particules-toutes-les-ticks", 20)) == 0) {
            f.ent.getWorld().spawnParticle(f.def.particle, f.pos.clone().add(0, 0.3, 0), 1, 0.2, 0.2, 0.2, 0.01);
        }
    }

    private void spawn(Player p, Follower f) {
        PetDef def = f.def;
        Location at = f.pos.clone();
        at.setPitch(0);
        at.setYaw(p.getLocation().getYaw());
        World w = at.getWorld();
        Component name = petName(def, f.stars);
        boolean showName = getConfig().getBoolean("nom-visible", true);
        try {
            if (def.type == null) {
                at.setYaw(0);
                f.ent = w.spawn(at, ItemDisplay.class, d -> {
                    mark(d, p);
                    d.setItemStack(new ItemStack(def.item));
                    float s = (float) def.scale;
                    d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
                    d.setBillboard(Display.Billboard.FIXED);
                    d.setTeleportDuration(2);
                    d.setBrightness(new Display.Brightness(15, 15));
                    d.setViewRange(1.2f);
                    if (def.glow) {
                        d.setGlowing(true);
                        d.setGlowColorOverride(rarities.get(def.rarity).color());
                    }
                });
                if (showName) {
                    f.label = w.spawn(at.clone().add(0, 0.55 * def.scale + 0.25, 0), TextDisplay.class, t -> {
                        mark(t, p);
                        t.text(name);
                        t.setBillboard(Display.Billboard.CENTER);
                        t.setTeleportDuration(2);
                        t.setShadowed(true);
                        t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                        t.setViewRange(0.6f);
                        t.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.8f, 0.8f, 0.8f), new Quaternionf()));
                    });
                }
            } else {
                Class<? extends Entity> cls = def.type.getEntityClass();
                java.util.function.Consumer<Entity> setup = e -> {
                    mark(e, p);
                    if (e instanceof LivingEntity le) {
                        le.setAI(false);
                        le.setSilent(true);
                        le.setInvulnerable(true);
                        le.setCollidable(false);
                        le.setGravity(false);
                        le.setRemoveWhenFarAway(false);
                        le.setCanPickupItems(false);
                        Attribute sc = reg(RegistryKey.ATTRIBUTE, "scale");
                        if (sc != null && def.scale != 1) {
                            AttributeInstance inst = le.getAttribute(sc);
                            if (inst != null) inst.setBaseValue(def.scale);
                        }
                    }
                    if (e instanceof Mob m) m.setAware(false);
                    if (e instanceof Ageable a) {
                        if (def.baby) a.setBaby();
                        else a.setAdult();
                        if (e instanceof Breedable b) b.setAgeLock(true);
                    }
                    variant(e, def.variant);
                    e.customName(name);
                    e.setCustomNameVisible(showName);
                    if (def.glow) e.setGlowing(true);
                };
                f.ent = w.spawn(at, cls, setup);
                if (f.ent instanceof Boss b && b.getBossBar() != null) {
                    b.getBossBar().setVisible(false);
                    b.getBossBar().removeAll();
                }
            }
        } catch (RuntimeException ex) {
            getLogger().warning("Impossible de faire apparaître " + def.id + " : " + ex.getMessage());
            f.ent = null;
        }
        if (f.ent != null && !f.ent.isValid()) f.ent = null;
    }

    private void mark(Entity e, Player owner) {
        e.addScoreboardTag(TAG);
        e.setPersistent(false);
        e.getPersistentDataContainer().set(petKey, PersistentDataType.STRING, owner.getUniqueId().toString());
    }

    private void despawn(Follower f) {
        if (f.ent != null) f.ent.remove();
        if (f.label != null) f.label.remove();
        f.ent = null;
        f.label = null;
    }

    /** Couleur / race du mob ("RED", "SNOW", "BLUE", "ALL_BLACK", taille d'un slime "2", "CHARGED"...). */
    private static void variant(Entity e, String v) {
        if (v == null || v.isBlank()) return;
        if (e instanceof Creeper c && v.equalsIgnoreCase("CHARGED")) {
            c.setPowered(true);
            return;
        }
        for (String m : List.of("setVariant", "setFoxType", "setCatType", "setRabbitType", "setColor", "setMainGene", "setSize")) {
            for (Method me : e.getClass().getMethods()) {
                if (!me.getName().equals(m) || me.getParameterCount() != 1) continue;
                Object val = convert(me.getParameterTypes()[0], v);
                if (val == null) continue;
                try {
                    me.invoke(e, val);
                    if (m.equals("setMainGene")) {
                        try {
                            e.getClass().getMethod("setHiddenGene", me.getParameterTypes()[0]).invoke(e, val);
                        } catch (ReflectiveOperationException ignored) {
                        }
                    }
                    return;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object convert(Class<?> pt, String v) {
        String up = v.trim().toUpperCase(Locale.ROOT);
        if (pt == int.class || pt == Integer.class) {
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        if (pt.isEnum()) {
            try {
                return Enum.valueOf((Class) pt, up);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
        try {
            Field f = pt.getField(up);
            if (Modifier.isStatic(f.getModifiers()) && pt.isAssignableFrom(f.getType())) return f.get(null);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return null;
    }

    private void magnet(Player p, double r) {
        Location c = p.getLocation().add(0, 0.8, 0);
        for (Entity e : p.getNearbyEntities(r, r, r)) {
            if (!(e instanceof Item it) || it.isDead()) continue;
            if (p.getUniqueId().equals(it.getThrower())) continue;
            if (it.getOwner() != null && !p.getUniqueId().equals(it.getOwner())) continue;
            if (it.getPickupDelay() > 10) continue;
            Vector dir = c.toVector().subtract(it.getLocation().toVector());
            double dist = dir.length();
            if (dist < 0.8 || dist > r) continue;
            it.setVelocity(dir.normalize().multiply(Math.min(0.55, 0.18 + dist * 0.05)));
            if (tick % 9 == 0) it.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, it.getLocation(), 1, 0.1, 0.1, 0.1, 0);
        }
    }

    private void heal(Player p, double amount) {
        Attribute mh = reg(RegistryKey.ATTRIBUTE, "max_health");
        AttributeInstance inst = mh == null ? null : p.getAttribute(mh);
        double max = inst == null ? 20 : inst.getValue();
        if (p.getHealth() < max) p.setHealth(Math.min(max, p.getHealth() + amount));
    }

    // ================================================================= œufs

    Egg eggOf(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return null;
        String id = it.getItemMeta().getPersistentDataContainer().get(eggKey, PersistentDataType.STRING);
        return id == null ? null : eggs.get(id);
    }

    ItemStack eggItem(Egg g, int amount) {
        ItemStack it = new ItemStack(g.material, Math.max(1, amount));
        ItemMeta m = it.getItemMeta();
        m.displayName(parse(g.name).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String l : g.lore) lore.add(line(l));
        lore.add(Component.empty());
        lore.add(line("<gray>Chances :"));
        for (Map.Entry<String, Double> e : g.rarityChances.entrySet()) {
            Rarity r = rarities.get(e.getKey());
            lore.add(line("  " + r.name() + "<reset> <white>" + pct(e.getValue())));
        }
        lore.add(Component.empty());
        lore.add(line("<yellow>Clic droit <gray>pour l'ouvrir"));
        lore.add(line("<yellow>Accroupi + clic droit <gray>pour voir les familiers"));
        m.lore(lore);
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(eggKey, PersistentDataType.STRING, g.id);
        it.setItemMeta(m);
        return it;
    }

    void giveEggs(Player p, Egg g, int n) {
        ItemStack it = eggItem(g, 1);
        int max = Math.max(1, it.getMaxStackSize());
        while (n > 0) {
            int k = Math.min(n, max);
            n -= k;
            for (ItemStack left : p.getInventory().addItem(eggItem(g, k)).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
    }

    /** Ajoute le familier : nouveau, +1 étoile, ou déjà au max. Retourne 1 (nouveau), les étoiles (doublon) ou -1 (max). */
    int grant(Player p, PetDef def) {
        PData d = data(p.getUniqueId());
        int cur = d.owned.getOrDefault(def.id, 0);
        int result;
        if (cur == 0) {
            d.owned.put(def.id, 1);
            if (getConfig().getBoolean("equiper-auto", true) && d.equipped.size() < slots(p)) d.equipped.add(def.id);
            result = 1;
        } else if (cur < maxStars()) {
            d.owned.put(def.id, cur + 1);
            result = cur + 1;
        } else {
            for (String c : getConfig().getStringList("etoiles.doublon-max-commandes")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("%player%", p.getName()).replace("%pet%", def.id));
            }
            result = -1;
        }
        saveData(p.getUniqueId());
        refresh(p);
        return result;
    }

    void open(Player p, Egg g) {
        if (opening.contains(p.getUniqueId())) {
            send(p, msg("already-opening"));
            return;
        }
        PetDef win = g.roll();
        if (win == null) return;
        if (!getConfig().getBoolean("animation", true)) {
            deliver(p, g, win);
            return;
        }
        opening.add(p.getUniqueId());
        int len = 56, winAt = 50;
        List<PetDef> strip = new ArrayList<>();
        for (int i = 0; i < len; i++) strip.add(i == winAt ? win : g.roll());
        PetMenu m = new PetMenu("spin");
        m.inv = Bukkit.createInventory(m, 27, parse(g.name + " <dark_gray>· éclosion..."));
        Rarity er = rarities.getOrDefault(g.rarity, rarities.values().iterator().next());
        boolean[] done = {false};
        Runnable give = () -> {
            if (done[0]) return;
            done[0] = true;
            opening.remove(p.getUniqueId());
            deliver(p, g, win);
        };
        m.finish = give;
        int steps = winAt - 4;
        int[] pos = {0};
        Runnable[] next = new Runnable[1];
        next[0] = () -> {
            if (done[0] || !p.isOnline()) {
                if (!p.isOnline()) give.run();
                return;
            }
            for (int i = 0; i < 9; i++) {
                Material pane = (i + pos[0]) % 2 == 0 ? er.pane() : Material.BLACK_STAINED_GLASS_PANE;
                m.inv.setItem(i, i == 4 ? named(Material.LIME_STAINED_GLASS_PANE, "<green><b>▼", List.of()) : named(pane, " ", List.of()));
                m.inv.setItem(18 + i, i == 4 ? named(Material.LIME_STAINED_GLASS_PANE, "<green><b>▲", List.of()) : named(pane, " ", List.of()));
                PetDef d = strip.get(pos[0] + i);
                m.inv.setItem(9 + i, named(d.icon, rarities.get(d.rarity).style() + d.name, List.of(rarities.get(d.rarity).name())));
            }
            float pitch = 0.6f + Math.min(1.4f, pos[0] / (float) steps * 1.4f);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.9f, pitch);
            if (pos[0] % 4 == 0) p.playSound(p.getLocation(), Sound.ENTITY_TURTLE_EGG_CRACK, 0.5f, 1.2f);
            if (pos[0] >= steps) {
                reveal(p, m, win, give);
                return;
            }
            int i = pos[0]++;
            int delay = i < 24 ? 1 : i < 32 ? 2 : i < 37 ? 3 : i < 41 ? 5 : i < 44 ? 7 : 10;
            Bukkit.getScheduler().runTaskLater(this, next[0], delay);
        };
        p.openInventory(m.inv);
        p.playSound(p.getLocation(), Sound.ENTITY_TURTLE_EGG_HATCH, 1f, 0.8f);
        next[0].run();
    }

    private void reveal(Player p, PetMenu m, PetDef win, Runnable give) {
        Rarity r = rarities.get(win.rarity);
        int[] f = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(this, () -> {
            f[0]++;
            for (int i = 0; i < 27; i++) {
                if (i == 13) continue;
                m.inv.setItem(i, named(f[0] % 2 == 0 ? r.pane() : Material.WHITE_STAINED_GLASS_PANE, " ", List.of()));
            }
            if (f[0] >= 6) {
                task[0].cancel();
                m.finish = null;
                give.run();
            }
        }, 0L, 4L);
        ItemStack shown = named(win.icon, r.style() + win.name, List.of(r.name()));
        ItemMeta meta = shown.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        shown.setItemMeta(meta);
        m.inv.setItem(13, shown);
        boolean big = r.order() >= 2;
        p.playSound(p.getLocation(), big ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 1f, big ? 1f : 1.3f);
        if (r.order() >= 3) p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.4f);
    }

    private void deliver(Player p, Egg g, PetDef win) {
        Rarity r = rarities.get(win.rarity);
        int res = grant(p, win);
        String petMini = r.style() + win.name + "<reset>";
        if (res == 1) send(p, msg("new-pet").replace("<pet>", petMini));
        else if (res > 1) send(p, msg("star-up").replace("<pet>", petMini).replace("<stars>", "★".repeat(res)));
        else send(p, msg("max-stars").replace("<pet>", petMini));
        p.showTitle(Title.title(parse(r.name()), parse(petMini + (res > 1 ? " <gold>" + "★".repeat(res) : "")),
                Title.Times.times(java.time.Duration.ofMillis(150), java.time.Duration.ofMillis(2000), java.time.Duration.ofMillis(500))));
        Location fx = p.getLocation().add(0, 1, 0);
        World w = fx.getWorld();
        w.spawnParticle(Particle.DUST, fx, 70, 0.6, 0.8, 0.6, 0, new Particle.DustOptions(r.color(), 1.6f));
        w.spawnParticle(Particle.END_ROD, fx, 30, 0.2, 0.3, 0.2, 0.15);
        w.playSound(fx, Sound.ENTITY_TURTLE_EGG_HATCH, 1f, 1f);
        if (r.order() >= 2) {
            w.spawnParticle(Particle.TOTEM_OF_UNDYING, fx, 120, 0.4, 0.5, 0.4, 0.6);
            w.spawnParticle(Particle.FIREWORK, fx, 60, 0.3, 0.3, 0.3, 0.2);
        }
        if (r.announce()) {
            Bukkit.broadcast(parse(msg("prefix") + msg("broadcast").replace("<player>", MM.escapeTags(p.getName()))
                    .replace("<pet>", petMini).replace("<rarity>", r.name() + "<reset>").replace("<egg>", g.name + "<reset>")));
        }
    }

    // ================================================================= menus

    static final class PetMenu implements InventoryHolder {
        final String type;
        Inventory inv;
        int page;
        Egg egg;
        String editId;
        Runnable finish;
        final Map<Integer, String> slots = new HashMap<>();

        PetMenu(String type) {
            this.type = type;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    void openMain(Player p, int page) {
        PData d = data(p.getUniqueId());
        List<PetDef> owned = new ArrayList<>();
        for (String id : d.owned.keySet()) if (pets.containsKey(id)) owned.add(pets.get(id));
        owned.sort(Comparator.<PetDef>comparingInt(x -> -rarities.get(x.rarity).order()).thenComparing(x -> x.name));
        int pages = Math.max(1, (owned.size() + 44) / 45);
        page = Math.max(0, Math.min(page, pages - 1));
        PetMenu m = new PetMenu("main");
        m.page = page;
        m.inv = Bukkit.createInventory(m, 54, parse("<dark_gray>Tes familiers <gray>(" + d.equipped.size() + "/" + slots(p) + ")"));
        for (int i = 0; i < 45 && page * 45 + i < owned.size(); i++) {
            PetDef def = owned.get(page * 45 + i);
            boolean on = d.equipped.contains(def.id);
            List<String> extra = new ArrayList<>();
            extra.add("");
            extra.add(on ? "<green>✔ Équipé <gray>- clic pour le retirer" : "<yellow>Clic pour l'équiper");
            if (tradeable(def)) extra.add("<gold>Shift + clic <gray>: le transformer en objet pour l'échanger");
            m.inv.setItem(i, petIcon(def, d.owned.get(def.id), on, extra));
            m.slots.put(i, def.id);
        }
        if (owned.isEmpty()) {
            m.inv.setItem(22, named(Material.EGG, "<gray>Aucun familier pour l'instant", List.of("<gray>Ouvre un œuf pour en avoir un !", "", "<yellow>Clic : voir les œufs")));
            m.slots.put(22, "#eggs");
        }
        ItemStack pane = named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 45; i < 54; i++) m.inv.setItem(i, pane);
        if (page > 0) {
            m.inv.setItem(45, named(Material.ARROW, "<yellow>← Page précédente", List.of()));
            m.slots.put(45, "#prev");
        }
        if (page < pages - 1) {
            m.inv.setItem(53, named(Material.ARROW, "<yellow>Page suivante →", List.of()));
            m.slots.put(53, "#next");
        }
        m.inv.setItem(47, named(Material.SNIFFER_EGG, "<gold><b>Œufs", List.of("<gray>Les œufs, ce qu'il y a dedans", "<gray>et leurs chances.", "", "<yellow>Clic pour voir")));
        m.slots.put(47, "#eggs");
        List<String> info = new ArrayList<>();
        info.add("<gray>Équipés : <white>" + d.equipped.size() + "/" + slots(p));
        info.add("<gray>Familiers : <white>" + owned.size() + "/" + pets.size());
        info.add("");
        info.addAll(totalLines(p));
        m.inv.setItem(49, named(Material.NETHER_STAR, "<gradient:#ffd23c:#ff7ad9><b>Tes bonus", info));
        m.inv.setItem(51, d.hidden ? named(Material.ENDER_PEARL, "<gray>Familiers cachés", List.of("<gray>Les boosts restent actifs.", "", "<yellow>Clic pour les montrer"))
                : named(Material.ENDER_EYE, "<green>Familiers visibles", List.of("", "<yellow>Clic pour les cacher")));
        m.slots.put(51, "#hide");
        p.openInventory(m.inv);
    }

    void openEggs(Player p) {
        PetMenu m = new PetMenu("eggs");
        m.inv = Bukkit.createInventory(m, 27, parse("<dark_gray>Œufs de familiers"));
        ItemStack pane = named(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 27; i++) m.inv.setItem(i, pane);
        List<Egg> list = new ArrayList<>(eggs.values());
        int[] pos = list.size() <= 4 ? new int[]{10, 12, 14, 16} : new int[]{9, 10, 11, 12, 13, 14, 15, 16, 17};
        for (int i = 0; i < list.size() && i < pos.length; i++) {
            Egg g = list.get(i);
            ItemStack it = eggItem(g, 1);
            ItemMeta meta = it.getItemMeta();
            List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
            lore = lore.subList(0, Math.max(0, lore.size() - 2));
            lore.add(line(g.price > 0 && vault ? "<gray>Prix : <gold>" + Money.format(g.price) : "<gray>Pas en vente <dark_gray>(caisses, events...)"));
            lore.add(Component.empty());
            if (g.price > 0 && vault) lore.add(line("<yellow>Clic gauche <gray>: acheter"));
            lore.add(line("<yellow>Clic droit <gray>: voir les familiers"));
            meta.lore(lore);
            it.setItemMeta(meta);
            m.inv.setItem(pos[i], it);
            m.slots.put(pos[i], g.id);
        }
        m.inv.setItem(22, named(Material.ARROW, "<yellow>← Tes familiers", List.of()));
        m.slots.put(22, "#back");
        p.openInventory(m.inv);
    }

    void openEgg(Player p, Egg g) {
        PetMenu m = new PetMenu("egg");
        m.egg = g;
        m.inv = Bukkit.createInventory(m, 54, parse(g.name + " <dark_gray>· contenu"));
        PData d = data(p.getUniqueId());
        int i = 0;
        for (Map.Entry<PetDef, Double> e : g.chances.entrySet()) {
            if (i >= 45) break;
            PetDef def = e.getKey();
            List<String> extra = new ArrayList<>();
            extra.add("");
            extra.add("<gray>Chance : <white>" + pct(e.getValue()));
            if (d.owned.containsKey(def.id)) extra.add("<green>✔ Tu l'as déjà <gold>" + "★".repeat(d.owned.get(def.id)));
            m.inv.setItem(i++, petIcon(def, d.owned.getOrDefault(def.id, 1), false, extra));
        }
        ItemStack pane = named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int k = 45; k < 54; k++) m.inv.setItem(k, pane);
        m.inv.setItem(45, named(Material.ARROW, "<yellow>← Les œufs", List.of()));
        m.slots.put(45, "#eggs");
        int have = countEggs(p, g);
        boolean sale = g.price > 0 && vault;
        List<String> openLore = new ArrayList<>();
        if (have > 0) openLore.addAll(List.of("<gray>Tu as <white>" + have + "</white> œuf(s) comme ça.", "", "<yellow>Clic pour en ouvrir un"));
        else if (sale) openLore.addAll(List.of("<gray>Tu n'en as pas : il sera payé", "<gold>" + Money.format(g.price) + "<gray> puis ouvert tout de suite.", "", "<yellow>Clic pour payer et ouvrir"));
        else openLore.addAll(List.of("<gray>Il te faut cet œuf pour l'ouvrir.", "<dark_gray>(caisses, events...)"));
        m.inv.setItem(48, named(have > 0 || sale ? Material.LIME_DYE : Material.GRAY_DYE, "<green><b>Ouvrir", openLore));
        m.slots.put(48, "#open");
        if (sale) {
            m.inv.setItem(50, named(Material.GOLD_INGOT, "<gold><b>Acheter l'œuf", List.of("<gray>Prix : <gold>" + Money.format(g.price), "<gray>(tu le gardes pour plus tard)", "", "<yellow>Clic pour acheter")));
            m.slots.put(50, "#buy");
        }
        if (p.hasPermission("smppets.admin")) {
            m.inv.setItem(53, named(Material.WRITABLE_BOOK, "<red><b>Modifier cet œuf", List.of("<gray>Admin : nom, prix, apparence, chances...", "", "<yellow>Clic")));
            m.slots.put(53, "#edit");
        }
        p.openInventory(m.inv);
    }

    int countEggs(Player p, Egg g) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) if (eggOf(it) == g) n += it.getAmount();
        return n;
    }

    /** Ouvre un œuf depuis le menu : prend un œuf de l'inventaire, sinon paie le prix. */
    void openFromMenu(Player p, Egg g) {
        if (opening.contains(p.getUniqueId())) {
            send(p, msg("already-opening"));
            return;
        }
        ItemStack[] inv = p.getInventory().getContents();
        for (int i = 0; i < inv.length; i++) {
            if (eggOf(inv[i]) != g) continue;
            ItemStack it = inv[i];
            if (it.getAmount() <= 1) p.getInventory().setItem(i, null);
            else {
                it.setAmount(it.getAmount() - 1);
                p.getInventory().setItem(i, it);
            }
            open(p, g);
            return;
        }
        if (g.price <= 0 || !vault || !Money.ready()) {
            send(p, msg("need-egg").replace("<egg>", g.name + "<reset>"));
            p.closeInventory();
            return;
        }
        if (!Money.take(p, g.price)) {
            send(p, msg("no-money").replace("<price>", Money.format(g.price)).replace("<balance>", Money.format(Money.balance(p))));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }
        open(p, g);
    }

    void buy(Player p, Egg g) {
        if (g.price <= 0 || !vault || !Money.ready()) {
            send(p, msg("egg-not-for-sale"));
            return;
        }
        if (!Money.take(p, g.price)) {
            send(p, msg("no-money").replace("<price>", Money.format(g.price)).replace("<balance>", Money.format(Money.balance(p))));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }
        giveEggs(p, g, 1);
        send(p, msg("bought").replace("<price>", Money.format(g.price)));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
    }

    ItemStack petIcon(PetDef def, int stars, boolean glint, List<String> extra) {
        Rarity r = rarities.get(def.rarity);
        List<String> lore = new ArrayList<>();
        lore.add(r.name() + (stars > 0 ? " <gold>" + "★".repeat(stars) + "<dark_gray>" + "★".repeat(Math.max(0, maxStars() - stars)) : ""));
        lore.addAll(def.desc);
        List<String> b = bonusLines(def, starMult(stars));
        if (!b.isEmpty()) {
            lore.add("");
            lore.addAll(b);
        }
        lore.addAll(extra);
        ItemStack it = named(def.icon, r.style() + def.name, lore);
        if (glint) {
            ItemMeta m = it.getItemMeta();
            m.setEnchantmentGlintOverride(true);
            it.setItemMeta(m);
        }
        return it;
    }

    Component petName(PetDef def, int stars) {
        return parse(rarities.get(def.rarity).style() + def.name + "<reset> <gold>" + "★".repeat(Math.max(1, stars)));
    }

    List<String> bonusLines(PetDef def, double m) {
        List<String> out = new ArrayList<>();
        String off = " <dark_gray>(hors combat)";
        for (Eff e : def.effects) out.add("<aqua>✦ " + effName(e.type()) + " " + roman(e.amp() + 1) + (combatOnly("effets", e.type().getKey().getKey()) ? off : ""));
        for (Attr a : def.attrs) out.add("<green>✦ " + attrLine(a, a.amount() * m) + (combatOnly("attributs", a.name()) ? off : ""));
        for (Map.Entry<String, Double> e : def.bonus.entrySet()) {
            String l = bonusLine(e.getKey(), e.getValue(), m);
            if (l != null) out.add("<light_purple>✦ " + l + (combatOnly("bonus", e.getKey()) ? off : ""));
        }
        return out;
    }

    List<String> totalLines(Player p) {
        Stats s = stats(p);
        List<String> out = new ArrayList<>();
        if (s.xp != 1) out.add("<light_purple>Expérience ×" + num(s.xp));
        if (s.pve != 1) out.add("<light_purple>Dégâts contre les monstres " + signedPct(s.pve - 1));
        if (s.prot != 1) out.add("<light_purple>Dégâts reçus des monstres " + signedPct(s.prot - 1));
        if (s.magnet > 0) out.add("<light_purple>Aimant à objets : " + num(s.magnet) + " blocs");
        if (s.heal > 0) out.add("<light_purple>Soin : +" + num(s.heal) + " PV / 2 s");
        if (s.ore > 0) out.add("<light_purple>Minerais doublés : " + num(s.ore * 100) + " %");
        if (s.leech > 0) out.add("<light_purple>Vol de vie (monstres) : " + num(s.leech * 100) + " %");
        if (s.bossLuck > 0) out.add("<light_purple>Chance au butin du boss : +" + num(Math.min(1, s.bossLuck) * 100) + " %");
        Set<String> effs = new java.util.LinkedHashSet<>();
        for (PetDef def : equipped(data(p.getUniqueId()))) for (Eff e : def.effects) effs.add(effName(e.type()) + " " + roman(e.amp() + 1));
        for (String e : effs) out.add("<aqua>" + e);
        if (out.isEmpty()) out.add("<gray>Aucun familier équipé.");
        return out;
    }

    private static String bonusLine(String k, double v, double m) {
        return switch (k) {
            case "xp" -> "Expérience ×" + num(1 + (v - 1) * m);
            case "degats-pve" -> "Dégâts contre les monstres " + signedPct((v - 1) * m);
            case "protection-pve" -> "Dégâts reçus des monstres " + signedPct(-(1 - v) * m);
            case "aimant" -> "Aimant à objets (" + num(v * m) + " blocs)";
            case "soin" -> "Soin +" + num(v * m) + " PV toutes les 2 s";
            case "double-minerai" -> num(Math.min(1, v * m) * 100) + " % de minerais doublés";
            case "vol-de-vie" -> "Vol de vie sur les monstres " + num(v * m * 100) + " %";
            case "chance-boss" -> "Chance au butin du boss +" + num(v * m * 100) + " %";
            default -> null;
        };
    }

    private static final Map<String, String> ATTR_FR = Map.ofEntries(
            Map.entry("max_health", "PV max"), Map.entry("attack_damage", "Dégâts"), Map.entry("movement_speed", "Vitesse"),
            Map.entry("armor", "Armure"), Map.entry("armor_toughness", "Solidité d'armure"), Map.entry("knockback_resistance", "Résistance au recul"),
            Map.entry("luck", "Chance"), Map.entry("safe_fall_distance", "Chute sans dégâts (blocs)"), Map.entry("fall_damage_multiplier", "Dégâts de chute"),
            Map.entry("jump_strength", "Saut"), Map.entry("block_break_speed", "Vitesse de minage"), Map.entry("mining_efficiency", "Efficacité de minage"),
            Map.entry("oxygen_bonus", "Oxygène"), Map.entry("water_movement_efficiency", "Nage"), Map.entry("block_interaction_range", "Portée (blocs)"),
            Map.entry("entity_interaction_range", "Portée d'attaque"), Map.entry("attack_speed", "Vitesse d'attaque"), Map.entry("scale", "Taille"),
            Map.entry("burning_time", "Durée de brûlure"), Map.entry("sneaking_speed", "Vitesse accroupi"), Map.entry("movement_efficiency", "Vitesse en terrain difficile"),
            Map.entry("submerged_mining_speed", "Minage sous l'eau"), Map.entry("step_height", "Hauteur de pas"), Map.entry("attack_knockback", "Recul infligé"),
            Map.entry("explosion_knockback_resistance", "Résistance au souffle"));

    private static String attrLine(Attr a, double v) {
        String n = ATTR_FR.getOrDefault(a.name(), a.name().replace('_', ' '));
        if (a.op() == AttributeModifier.Operation.ADD_SCALAR || BASE_ONE.contains(a.name())) return n + " " + signedPct(v);
        if (a.name().equals("max_health")) return (v >= 0 ? "+" : "") + num(v / 2) + " ❤ max";
        return n + " " + (v >= 0 ? "+" : "") + num(v);
    }

    private static final Map<String, String> EFF_FR = Map.ofEntries(
            Map.entry("speed", "Vitesse"), Map.entry("haste", "Célérité"), Map.entry("strength", "Force"), Map.entry("jump_boost", "Saut"),
            Map.entry("regeneration", "Régénération"), Map.entry("resistance", "Résistance"), Map.entry("fire_resistance", "Résistance au feu"),
            Map.entry("water_breathing", "Apnée"), Map.entry("night_vision", "Vision nocturne"), Map.entry("health_boost", "Bonus de vie"),
            Map.entry("absorption", "Absorption"), Map.entry("luck", "Chance"), Map.entry("slow_falling", "Chute lente"),
            Map.entry("conduit_power", "Force de conduit"), Map.entry("dolphins_grace", "Grâce du dauphin"), Map.entry("hero_of_the_village", "Héros du village"),
            Map.entry("saturation", "Saturation"), Map.entry("invisibility", "Invisibilité"), Map.entry("glowing", "Surbrillance"));

    private static String effName(PotionEffectType t) {
        String k = t.getKey().getKey();
        return EFF_FR.getOrDefault(k, k.replace('_', ' '));
    }

    // ================================================================= événements

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        loadData(e.getPlayer().getUniqueId());
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!e.getPlayer().isOnline()) return;
            syncPermissionPets(e.getPlayer());
            refresh(e.getPlayer());
        }, 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        Trade tr = trades.get(id);
        if (tr != null) cancelTrade(tr, e.getPlayer().getName());
        List<Follower> fs = active.remove(id);
        if (fs != null) for (Follower f : fs) despawn(f);
        clearAttributes(e.getPlayer());
        saveData(id);
        data.remove(id);
        stats.remove(id);
        applied.remove(id);
        combatState.remove(id);
        opening.remove(id);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUseEgg(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Egg g = eggOf(e.getItem());
        if (g == null || e.getHand() == null) return;
        if (e.getClickedBlock() != null && stations.containsKey(key(e.getClickedBlock()))) return; // la station s'en occupe
        e.setCancelled(true);
        e.setUseItemInHand(Event.Result.DENY);
        e.setUseInteractedBlock(Event.Result.DENY);
        Player p = e.getPlayer();
        if (p.isSneaking()) {
            openEgg(p, g);
            return;
        }
        if (opening.contains(p.getUniqueId())) {
            send(p, msg("already-opening"));
            return;
        }
        EquipmentSlot hand = e.getHand();
        ItemStack inHand = p.getInventory().getItem(hand);
        if (eggOf(inHand) != g) return;
        if (inHand.getAmount() <= 1) p.getInventory().setItem(hand, null);
        else {
            inHand.setAmount(inHand.getAmount() - 1);
            p.getInventory().setItem(hand, inHand);
        }
        open(p, g);
    }

    // ================================================================= échange direct entre deux joueurs (/pets trade)

    static final class Trade {
        final UUID a, b;
        final List<String> offerA = new ArrayList<>(), offerB = new ArrayList<>();
        boolean readyA, readyB, done;

        Trade(UUID a, UUID b) {
            this.a = a;
            this.b = b;
        }

        List<String> offer(UUID id) {
            return id.equals(a) ? offerA : offerB;
        }

        UUID other(UUID id) {
            return id.equals(a) ? b : a;
        }
    }

    final Map<UUID, Trade> trades = new HashMap<>();
    /** demandes : cible -> (demandeur -> heure) */
    final Map<UUID, Map<UUID, Long>> tradeAsks = new HashMap<>();
    /** joueurs qui changent de menu pendant l'échange (le menu qui se ferme n'annule pas l'échange) */
    final Set<UUID> tradeSwitch = new HashSet<>();

    void tradeCommand(Player p, String[] a) {
        if (!getConfig().getBoolean("echange.active", true)) {
            send(p, msg("not-tradeable"));
            return;
        }
        if (a.length >= 3 && (a[1].equalsIgnoreCase("accepter") || a[1].equalsIgnoreCase("accept"))) {
            Player from = Bukkit.getPlayerExact(a[2]);
            Map<UUID, Long> asks = tradeAsks.get(p.getUniqueId());
            Long t = from == null || asks == null ? null : asks.get(from.getUniqueId());
            if (t == null || System.currentTimeMillis() - t > 60_000) {
                send(p, msg("trade-no-request"));
                return;
            }
            asks.remove(from.getUniqueId());
            if (trades.containsKey(p.getUniqueId()) || trades.containsKey(from.getUniqueId())) {
                send(p, msg("trade-busy"));
                return;
            }
            Trade tr = new Trade(from.getUniqueId(), p.getUniqueId());
            trades.put(tr.a, tr);
            trades.put(tr.b, tr);
            openTrade(from, tr);
            openTrade(p, tr);
            return;
        }
        if (a.length < 2) {
            send(p, "<red>/pets trade <joueur>");
            return;
        }
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null || t.equals(p) || vanished(t)) {
            send(p, msg("unknown-player"));
            return;
        }
        if (trades.containsKey(p.getUniqueId()) || trades.containsKey(t.getUniqueId())) {
            send(p, msg("trade-busy"));
            return;
        }
        // il m'avait déjà demandé : on ouvre direct
        Map<UUID, Long> mine = tradeAsks.get(p.getUniqueId());
        if (mine != null && mine.containsKey(t.getUniqueId()) && System.currentTimeMillis() - mine.get(t.getUniqueId()) <= 60_000) {
            tradeCommand(p, new String[]{"trade", "accepter", t.getName()});
            return;
        }
        tradeAsks.computeIfAbsent(t.getUniqueId(), k -> new HashMap<>()).put(p.getUniqueId(), System.currentTimeMillis());
        send(p, msg("trade-sent").replace("<player>", t.getName()));
        send(t, msg("trade-request").replace("<player>", p.getName()));
        t.playSound(t.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.2f);
    }

    private static final int[] LEFT = {0, 1, 2, 3, 9, 10, 11, 12, 18, 19, 20, 21, 27, 28, 29, 30};
    private static final int[] RIGHT = {5, 6, 7, 8, 14, 15, 16, 17, 23, 24, 25, 26, 32, 33, 34, 35};

    void openTrade(Player p, Trade t) {
        Player o = Bukkit.getPlayer(t.other(p.getUniqueId()));
        PetMenu m = new PetMenu("trade");
        m.inv = Bukkit.createInventory(m, 54, parse("<dark_gray>Échange avec " + (o == null ? "?" : o.getName())));
        drawTrade(p, m, t);
        tradeSwitch.add(p.getUniqueId());
        p.openInventory(m.inv);
        tradeSwitch.remove(p.getUniqueId());
    }

    void drawTrade(Player p, PetMenu m, Trade t) {
        m.slots.clear();
        UUID me = p.getUniqueId(), them = t.other(me);
        ItemStack sep = named(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 54; i++) m.inv.setItem(i, null);
        for (int r = 0; r < 4; r++) m.inv.setItem(r * 9 + 4, sep);
        for (int i = 36; i < 45; i++) m.inv.setItem(i, sep);
        PData mine = data(me), theirs = data(them);
        List<String> my = t.offer(me), their = t.offer(them);
        for (int i = 0; i < my.size() && i < LEFT.length; i++) {
            PetDef d = pets.get(my.get(i));
            if (d == null) continue;
            m.inv.setItem(LEFT[i], petIcon(d, mine.owned.getOrDefault(d.id, 1), false, List.of("", "<red>Clic pour le retirer de l'échange")));
            m.slots.put(LEFT[i], "-" + d.id);
        }
        for (int i = 0; i < their.size() && i < RIGHT.length; i++) {
            PetDef d = pets.get(their.get(i));
            if (d == null) continue;
            int have = mine.owned.getOrDefault(d.id, 0);
            m.inv.setItem(RIGHT[i], petIcon(d, theirs.owned.getOrDefault(d.id, 1), false,
                    have > 0 ? List.of("", "<yellow>Tu l'as déjà : ses étoiles s'ajouteront aux tiennes") : List.of()));
        }
        m.inv.setItem(45, named(Material.LIME_DYE, "<green><b>+ Proposer un familier", List.of("<gray>Choisis un de tes familiers", "<gray>à mettre dans l'échange.")));
        m.slots.put(45, "#add");
        boolean myReady = me.equals(t.a) ? t.readyA : t.readyB, theirReady = me.equals(t.a) ? t.readyB : t.readyA;
        m.inv.setItem(47, named(myReady ? Material.LIME_CONCRETE : Material.RED_CONCRETE, myReady ? "<green><b>✔ Tu es prêt" : "<red><b>Pas prêt",
                List.of("<gray>Quand les deux sont prêts,", "<gray>l'échange se fait.", "", "<yellow>Clic pour changer")));
        m.slots.put(47, "#ready");
        m.inv.setItem(49, named(Material.BARRIER, "<red>Annuler l'échange", List.of()));
        m.slots.put(49, "#cancel");
        Player o = Bukkit.getPlayer(them);
        m.inv.setItem(51, named(theirReady ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE,
                (theirReady ? "<green>✔ " : "<gray>") + (o == null ? "?" : o.getName()) + (theirReady ? " est prêt" : " n'est pas prêt"), List.of()));
        m.inv.setItem(53, named(Material.BOOK, "<white>Comment ça marche", List.of("<gray>À gauche : ce que tu donnes.", "<gray>À droite : ce que tu reçois.",
                "<gray>Un changement remet les deux à \"pas prêt\".", "<gray>Les étoiles partent avec le familier.")));
    }

    void redrawTrade(Trade t) {
        for (UUID id : List.of(t.a, t.b)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            if (!(p.getOpenInventory().getTopInventory().getHolder() instanceof PetMenu pm)) openTrade(p, t);
            else if (pm.type.equals("trade")) drawTrade(p, pm, t);
            else if (!pm.type.equals("tradepick")) openTrade(p, t);
        }
    }

    void openTradePicker(Player p, Trade t) {
        PetMenu m = new PetMenu("tradepick");
        m.inv = Bukkit.createInventory(m, 54, parse("<dark_gray>Quel familier proposer ?"));
        PData d = data(p.getUniqueId());
        List<String> offered = t.offer(p.getUniqueId());
        int i = 0;
        for (Map.Entry<String, Integer> e : d.owned.entrySet()) {
            PetDef def = pets.get(e.getKey());
            if (def == null || !tradeable(def) || offered.contains(def.id) || i >= 45) continue;
            m.inv.setItem(i, petIcon(def, e.getValue(), false, List.of("", "<yellow>Clic pour le proposer")));
            m.slots.put(i, def.id);
            i++;
        }
        if (i == 0) m.inv.setItem(22, named(Material.BARRIER, "<gray>Aucun familier à proposer", List.of()));
        m.inv.setItem(49, named(Material.ARROW, "<yellow>← Retour à l'échange", List.of()));
        m.slots.put(49, "#back");
        tradeSwitch.add(p.getUniqueId());
        p.openInventory(m.inv);
        tradeSwitch.remove(p.getUniqueId());
    }

    void tradeClick(Player p, PetMenu m, String k) {
        Trade t = trades.get(p.getUniqueId());
        if (t == null || t.done) {
            p.closeInventory();
            return;
        }
        UUID me = p.getUniqueId();
        if (m.type.equals("tradepick")) {
            if (!k.equals("#back")) {
                PetDef def = pets.get(k);
                if (def != null && data(me).owned.containsKey(k) && tradeable(def) && !t.offer(me).contains(k) && t.offer(me).size() < LEFT.length) {
                    t.offer(me).add(k);
                    t.readyA = t.readyB = false;
                }
            }
            openTrade(p, t);
            Player o = Bukkit.getPlayer(t.other(me));
            if (o != null && o.getOpenInventory().getTopInventory().getHolder() instanceof PetMenu pm && pm.type.equals("trade")) drawTrade(o, pm, t);
            return;
        }
        switch (k) {
            case "#add" -> openTradePicker(p, t);
            case "#cancel" -> cancelTrade(t, p.getName());
            case "#ready" -> {
                if (me.equals(t.a)) t.readyA = !t.readyA;
                else t.readyB = !t.readyB;
                if (t.readyA && t.readyB) finishTrade(t);
                else redrawTrade(t);
            }
            default -> {
                if (k.startsWith("-")) {
                    t.offer(me).remove(k.substring(1));
                    t.readyA = t.readyB = false;
                    redrawTrade(t);
                }
            }
        }
    }

    void cancelTrade(Trade t, String by) {
        if (t.done) return;
        t.done = true;
        trades.remove(t.a);
        trades.remove(t.b);
        for (UUID id : List.of(t.a, t.b)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            send(p, msg("trade-cancelled").replace("<player>", by));
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof PetMenu pm && (pm.type.equals("trade") || pm.type.equals("tradepick"))) {
                tradeSwitch.add(id);
                p.closeInventory();
                tradeSwitch.remove(id);
            }
        }
    }

    void finishTrade(Trade t) {
        Player pa = Bukkit.getPlayer(t.a), pb = Bukkit.getPlayer(t.b);
        if (pa == null || pb == null) {
            cancelTrade(t, "?");
            return;
        }
        PData da = data(t.a), db = data(t.b);
        // tout est encore là ?
        for (String id : t.offerA) if (!da.owned.containsKey(id)) {
            cancelTrade(t, pa.getName());
            return;
        }
        for (String id : t.offerB) if (!db.owned.containsKey(id)) {
            cancelTrade(t, pb.getName());
            return;
        }
        Map<String, Integer> fromA = new LinkedHashMap<>(), fromB = new LinkedHashMap<>();
        for (String id : t.offerA) fromA.put(id, da.owned.remove(id));
        for (String id : t.offerB) fromB.put(id, db.owned.remove(id));
        da.equipped.removeAll(t.offerA);
        db.equipped.removeAll(t.offerB);
        receive(pb, db, fromA);
        receive(pa, da, fromB);
        saveData(t.a);
        saveData(t.b);
        t.done = true;
        trades.remove(t.a);
        trades.remove(t.b);
        for (Player p : List.of(pa, pb)) {
            refresh(p);
            tradeSwitch.add(p.getUniqueId());
            p.closeInventory();
            tradeSwitch.remove(p.getUniqueId());
            send(p, msg("trade-done"));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.3f);
        }
    }

    private void receive(Player p, PData d, Map<String, Integer> got) {
        for (Map.Entry<String, Integer> e : got.entrySet()) {
            int cur = d.owned.getOrDefault(e.getKey(), 0);
            d.owned.put(e.getKey(), Math.min(maxStars(), cur + e.getValue()));
            if (cur == 0 && getConfig().getBoolean("equiper-auto", true) && d.equipped.size() < slots(p)) d.equipped.add(e.getKey());
        }
    }

    // ================================================================= échange : familier <-> objet

    NamespacedKey tokenKey() {
        return new NamespacedKey(this, "token");
    }

    boolean tradeable(PetDef def) {
        return getConfig().getBoolean("echange.active", true) && (def.permission == null || def.permission.isBlank());
    }

    /** Objet "familier" : id;étoiles (+ un identifiant unique pour qu'ils ne s'empilent pas). */
    ItemStack token(PetDef def, int stars) {
        Rarity r = rarities.get(def.rarity);
        List<String> lore = new ArrayList<>();
        lore.add(r.name() + " <gold>" + "★".repeat(stars) + "<dark_gray>" + "★".repeat(Math.max(0, maxStars() - stars)));
        lore.addAll(def.desc);
        List<String> b = bonusLines(def, starMult(stars));
        if (!b.isEmpty()) {
            lore.add("");
            lore.addAll(b);
        }
        lore.add("");
        lore.add("<gold>Familier en objet <gray>: échange-le, vends-le...");
        lore.add("<yellow>Clic droit <gray>pour l'ajouter à tes familiers");
        ItemStack it = named(Material.PAPER, r.style() + def.name + " <gold>" + "★".repeat(stars), lore);
        ItemMeta m = it.getItemMeta();
        try {
            m.setItemModel(def.type == null ? def.item.getKey() : def.icon.getKey());
        } catch (Throwable ignored) {
        }
        m.setEnchantmentGlintOverride(true);
        m.setMaxStackSize(1);
        m.getPersistentDataContainer().set(tokenKey(), PersistentDataType.STRING, def.id + ";" + stars + ";" + UUID.randomUUID());
        it.setItemMeta(m);
        return it;
    }

    /** {familier, étoiles} ou null */
    Object[] tokenOf(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return null;
        String v = it.getItemMeta().getPersistentDataContainer().get(tokenKey(), PersistentDataType.STRING);
        if (v == null) return null;
        String[] p = v.split(";");
        PetDef def = pets.get(p[0]);
        if (def == null) return null;
        return new Object[]{def, Math.max(1, p.length > 1 ? parseInt(p[1], 1) : 1)};
    }

    void extract(Player p, PetDef def) {
        PData d = data(p.getUniqueId());
        if (!tradeable(def)) {
            send(p, msg("not-tradeable"));
            return;
        }
        Integer stars = d.owned.get(def.id);
        if (stars == null) return;
        if (p.getInventory().firstEmpty() < 0) {
            send(p, msg("inventory-full"));
            return;
        }
        d.owned.remove(def.id);
        d.equipped.remove(def.id);
        saveData(p.getUniqueId());
        refresh(p);
        p.getInventory().addItem(token(def, stars));
        send(p, msg("extracted").replace("<pet>", rarities.get(def.rarity).style() + def.name + "<reset>"));
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
        openMain(p, 0);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUseToken(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Object[] t = tokenOf(e.getItem());
        if (t == null || e.getHand() == null) return;
        e.setCancelled(true);
        e.setUseItemInHand(Event.Result.DENY);
        Player p = e.getPlayer();
        PetDef def = (PetDef) t[0];
        int stars = (int) t[1];
        EquipmentSlot hand = e.getHand();
        ItemStack inHand = p.getInventory().getItem(hand);
        if (tokenOf(inHand) == null) return;
        PData d = data(p.getUniqueId());
        int cur = d.owned.getOrDefault(def.id, 0);
        if (cur >= maxStars()) {
            send(p, msg("token-max").replace("<pet>", rarities.get(def.rarity).style() + def.name + "<reset>"));
            return;
        }
        p.getInventory().setItem(hand, null);
        int now = Math.min(maxStars(), cur + stars);
        d.owned.put(def.id, now);
        if (cur == 0 && getConfig().getBoolean("equiper-auto", true) && d.equipped.size() < slots(p)) d.equipped.add(def.id);
        saveData(p.getUniqueId());
        refresh(p);
        String n = rarities.get(def.rarity).style() + def.name + "<reset>";
        if (cur == 0) send(p, msg("new-pet").replace("<pet>", n));
        else send(p, msg("star-up").replace("<pet>", n).replace("<stars>", "★".repeat(now)));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
        p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 40, 0.5, 0.7, 0.5, 0,
                new Particle.DustOptions(rarities.get(def.rarity).color(), 1.5f));
    }

    /** Un familier en objet ne sert pas d'ingrédient (papier) ni d'objet à poser/lancer. */
    @EventHandler
    public void onCraftToken(org.bukkit.event.inventory.PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) if (tokenOf(it) != null) {
            e.getInventory().setResult(null);
            return;
        }
    }

    @EventHandler
    public void onPrepareToken(com.destroystokyo.paper.event.inventory.PrepareResultEvent e) {
        for (ItemStack it : e.getInventory().getContents()) if (tokenOf(it) != null) {
            e.setResult(null);
            return;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (eggOf(e.getItemInHand()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        if (eggOf(e.getItem()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (e.getEntity() instanceof ThrowableProjectile t && eggOf(t.getItem()) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof PetMenu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= m.inv.getSize()) return;
        String k = m.slots.get(slot);
        if (k == null) return;
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.3f);
        switch (m.type) {
            case "main" -> {
                switch (k) {
                    case "#prev" -> openMain(p, m.page - 1);
                    case "#next" -> openMain(p, m.page + 1);
                    case "#eggs" -> openEggs(p);
                    case "#hide" -> {
                        PData d = data(p.getUniqueId());
                        d.hidden = !d.hidden;
                        saveData(p.getUniqueId());
                        send(p, msg(d.hidden ? "hidden" : "shown"));
                        openMain(p, m.page);
                    }
                    default -> {
                        PetDef def = pets.get(k);
                        if (def == null) return;
                        if (e.isShiftClick()) extract(p, def);
                        else toggle(p, def);
                        openMain(p, m.page);
                    }
                }
            }
            case "eggs" -> {
                if (k.equals("#back")) {
                    openMain(p, 0);
                    return;
                }
                Egg g = eggs.get(k);
                if (g == null) return;
                if (e.isLeftClick() && g.price > 0 && vault) buy(p, g);
                else openEgg(p, g);
            }
            case "egg" -> {
                if (k.equals("#eggs")) openEggs(p);
                else if (k.equals("#buy") && m.egg != null) buy(p, m.egg);
                else if (k.equals("#open") && m.egg != null) openFromMenu(p, m.egg);
                else if (k.equals("#edit") && m.egg != null && p.hasPermission("smppets.admin")) openEditor(p, m.egg.id);
            }
            case "edit", "editch", "editpets" -> editClick(p, m, k, e.isShiftClick() && e.isRightClick());
            case "trade", "tradepick" -> tradeClick(p, m, k);
            default -> {
            }
        }
    }

    void toggle(Player p, PetDef def) {
        PData d = data(p.getUniqueId());
        String n = rarities.get(def.rarity).style() + def.name + "<reset>";
        if (d.equipped.remove(def.id)) {
            send(p, msg("unequipped").replace("<pet>", n));
        } else {
            if (d.equipped.size() >= slots(p)) {
                send(p, msg("slots-full").replace("<slots>", String.valueOf(slots(p))));
                return;
            }
            d.equipped.add(def.id);
            send(p, msg("equipped").replace("<pet>", n));
            p.playSound(p.getLocation(), Sound.ENTITY_ALLAY_ITEM_GIVEN, 1f, 1.2f);
        }
        saveData(p.getUniqueId());
        refresh(p);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof PetMenu) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof PetMenu tm && (tm.type.equals("trade") || tm.type.equals("tradepick"))
                && !tradeSwitch.contains(e.getPlayer().getUniqueId())) {
            Trade t = trades.get(e.getPlayer().getUniqueId());
            if (t != null && !t.done) cancelTrade(t, e.getPlayer().getName());
        }
        if (e.getInventory().getHolder() instanceof PetMenu m && m.finish != null) {
            Runnable r = m.finish;
            m.finish = null;
            r.run();
        }
    }

    // ---- les familiers sont intouchables

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPetSpawn(EntitySpawnEvent e) {
        if (isPet(e.getEntity())) e.setCancelled(false);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPetDamage(EntityDamageEvent e) {
        if (isPet(e.getEntity())) e.setCancelled(true);
    }

    /**
     * Les familiers ne bloquent pas les coups : taper un familier tape ce qu'il y a derrière lui
     * (le joueur qu'il suit, un monstre...), comme s'il n'était pas là.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttackPet(io.papermc.paper.event.player.PrePlayerAttackEntityEvent e) {
        if (!isPet(e.getAttacked())) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Attribute range = reg(RegistryKey.ATTRIBUTE, "entity_interaction_range");
        AttributeInstance ri = range == null ? null : p.getAttribute(range);
        double reach = ri == null ? 3 : ri.getValue();
        if (p.getGameMode() == GameMode.CREATIVE) reach += 2;
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        var hit = p.getWorld().rayTraceEntities(eye, dir, reach, 0.1, en -> !en.equals(p) && !isPet(en) && en instanceof LivingEntity
                && !(en instanceof org.bukkit.entity.ArmorStand as && as.isMarker()));
        if (hit == null || hit.getHitEntity() == null) return;
        var block = p.getWorld().rayTraceBlocks(eye, dir, reach, org.bukkit.FluidCollisionMode.NEVER, true);
        if (block != null && block.getHitPosition().distanceSquared(eye.toVector()) < hit.getHitPosition().distanceSquared(eye.toVector())) return;
        p.attack(hit.getHitEntity());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPetInteract(PlayerInteractEntityEvent e) {
        if (isPet(e.getRightClicked())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPetInteractAt(PlayerInteractAtEntityEvent e) {
        if (isPet(e.getRightClicked())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPetTarget(EntityTargetEvent e) {
        if (isPet(e.getEntity()) || isPet(e.getTarget())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPetHit(ProjectileHitEvent e) {
        if (isPet(e.getHitEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPetBurn(EntityCombustEvent e) {
        if (isPet(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPetDrop(EntityDropItemEvent e) {
        if (isPet(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPetTransform(EntityTransformEvent e) {
        if (isPet(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPetPortal(EntityPortalEvent e) {
        if (isPet(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPetBlock(EntityChangeBlockEvent e) {
        if (isPet(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPetForm(EntityBlockFormEvent e) {
        if (isPet(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler
    public void onPetDeath(EntityDeathEvent e) {
        if (!isPet(e.getEntity())) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
    }

    // ---- les bonus

    @EventHandler(ignoreCancelled = true)
    public void onExp(PlayerExpChangeEvent e) {
        double m = stats(e.getPlayer()).xp;
        if (m == 1 || e.getAmount() <= 0) return;
        double v = e.getAmount() * m;
        int n = (int) Math.floor(v);
        if (ThreadLocalRandom.current().nextDouble() < v - n) n++;
        e.setAmount(n);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        Entity victim = e.getEntity();
        if (isPet(victim)) return;
        Player atk = playerSource(e.getDamager());
        if (atk != null && !(victim instanceof Player) && victim instanceof LivingEntity) {
            Stats s = stats(atk);
            if (s.pve != 1) e.setDamage(e.getDamage() * s.pve);
            if (s.leech > 0) {
                double amount = e.getDamage() * s.leech;
                Bukkit.getScheduler().runTask(this, () -> {
                    if (atk.isOnline() && !atk.isDead()) heal(atk, amount);
                });
            }
        } else if (victim instanceof Player v && atk == null && mobSource(e.getDamager())) {
            Stats s = stats(v);
            if (s.prot != 1) e.setDamage(e.getDamage() * s.prot);
        }
    }

    private static Player playerSource(Entity d) {
        if (d instanceof Player p) return p;
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    private static boolean mobSource(Entity d) {
        if (d instanceof LivingEntity && !(d instanceof Player)) return true;
        if (d instanceof Projectile pr) {
            ProjectileSource s = pr.getShooter();
            return s instanceof LivingEntity && !(s instanceof Player);
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Stats s = stats(p);
        if (s.ore <= 0 || p.getGameMode() == GameMode.CREATIVE || !e.isDropItems()) return;
        Block b = e.getBlock();
        String n = b.getType().name();
        if (!n.endsWith("_ORE") && b.getType() != Material.ANCIENT_DEBRIS) return;
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (tool.containsEnchantment(Enchantment.SILK_TOUCH)) return;
        if (ThreadLocalRandom.current().nextDouble() >= s.ore) return;
        Location at = b.getLocation().add(0.5, 0.5, 0.5);
        for (ItemStack drop : b.getDrops(tool, p)) b.getWorld().dropItemNaturally(at, drop);
        b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at, 8, 0.3, 0.3, 0.3, 0);
        p.playSound(at, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.6f);
    }

    // ================================================================= stations d'œufs (posées dans le monde)

    final Map<String, String> stations = new LinkedHashMap<>();
    final Map<String, List<Entity>> shows = new HashMap<>();
    private final NamespacedKey stationKey = new NamespacedKey("smppets", "station");
    long clock;

    static String key(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    static Location loc(String k) {
        String[] p = k.split(";");
        World w = p.length == 4 ? Bukkit.getWorld(p[0]) : null;
        if (w == null) return null;
        return new Location(w, parseInt(p[1], 0), parseInt(p[2], 0), parseInt(p[3], 0));
    }

    void loadStations() {
        stations.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "stations.yml"));
        ConfigurationSection s = y.getConfigurationSection("stations");
        if (s != null) for (String k : s.getKeys(false)) stations.put(k.replace('|', '.'), s.getString(k));
    }

    void saveStations() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, String> e : stations.entrySet()) y.set("stations." + e.getKey().replace('.', '|'), e.getValue());
        try {
            y.save(new File(getDataFolder(), "stations.yml"));
        } catch (IOException ex) {
            getLogger().warning("stations.yml : " + ex.getMessage());
        }
    }

    private void stationAmbience() {
        clock++;
        for (Map.Entry<String, String> e : stations.entrySet()) {
            Egg g = eggs.get(e.getValue());
            Location b = loc(e.getKey());
            if (g == null || b == null || !b.getWorld().isChunkLoaded(b.getBlockX() >> 4, b.getBlockZ() >> 4)) {
                removeShow(e.getKey());
                continue;
            }
            boolean near = false;
            for (Player p : b.getWorld().getPlayers()) if (p.getLocation().distanceSquared(b) < 48 * 48) near = true;
            if (!near) continue;
            Rarity r = rarities.getOrDefault(g.rarity, rarities.values().iterator().next());
            ensureShow(e.getKey(), g, r, b);
            World w = b.getWorld();
            Location mid = b.clone().add(0.5, 0, 0.5);
            Particle.DustOptions dust = new Particle.DustOptions(r.color(), 1.1f);
            for (int k = 0; k < 3; k++) {
                double a = clock * 0.15 + Math.PI * 2 * k / 3;
                w.spawnParticle(Particle.DUST, mid.clone().add(Math.cos(a) * 0.85, 1.05 + (Math.sin(clock * 0.1 + k) + 1) * 0.35, Math.sin(a) * 0.85), 1, 0, 0, 0, 0, dust);
            }
            if (clock % 4 == 0) w.spawnParticle(Particle.END_ROD, mid.clone().add(0, 1.5, 0), 1, 0.3, 0.25, 0.3, 0.01);
            if (r.order() >= 2 && clock % 3 == 0) w.spawnParticle(Particle.WAX_OFF, mid.clone().add(0, 1.7, 0), 1, 0.4, 0.3, 0.4, 0);
            if (r.order() >= 3 && clock % 2 == 0) w.spawnParticle(Particle.SOUL_FIRE_FLAME, mid.clone().add(Math.cos(clock * 0.2) * 0.6, 1.1, Math.sin(clock * 0.2) * 0.6), 1, 0, 0.02, 0, 0.005);
        }
    }

    private void ensureShow(String k, Egg g, Rarity r, Location b) {
        List<Entity> l = shows.get(k);
        if (l != null && l.stream().allMatch(Entity::isValid)) {
            if (l.get(1) instanceof ItemDisplay d) {
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(4);
                float s = 0.75f;
                d.setTransformation(new Transformation(new Vector3f(0, (float) Math.sin(clock * 0.08) * 0.08f, 0),
                        new Quaternionf().rotateY(clock * 0.09f), new Vector3f(s, s, s), new Quaternionf()));
            }
            return;
        }
        if (l != null) for (Entity e : l) e.remove();
        World w = b.getWorld();
        String price = g.price > 0 && vault ? "<gray>Prix : <gold>" + Money.format(g.price) : "<gray>Avec un œuf seulement";
        TextDisplay text = w.spawn(b.clone().add(0.5, 2.35, 0.5), TextDisplay.class, t -> {
            t.setPersistent(false);
            t.getPersistentDataContainer().set(stationKey, PersistentDataType.STRING, k);
            t.setBillboard(Display.Billboard.CENTER);
            t.setShadowed(true);
            t.setBackgroundColor(Color.fromARGB(110, 8, 6, 14));
            t.text(parse(g.name + "\n" + price + "\n<dark_gray>Clic : voir les familiers et ouvrir"));
        });
        ItemDisplay item = w.spawn(b.clone().add(0.5, 1.55, 0.5), ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.getPersistentDataContainer().set(stationKey, PersistentDataType.STRING, k);
            d.setItemStack(new ItemStack(g.material));
            d.setBrightness(new Display.Brightness(15, 15));
            d.setGlowing(true);
            d.setGlowColorOverride(r.color());
            d.setTeleportDuration(2);
        });
        org.bukkit.entity.Interaction click = w.spawn(b.clone().add(0.5, 1.0, 0.5), org.bukkit.entity.Interaction.class, i -> {
            i.setPersistent(false);
            i.getPersistentDataContainer().set(stationKey, PersistentDataType.STRING, k);
            i.setInteractionWidth(1.3f);
            i.setInteractionHeight(2.2f);
            i.setResponsive(true);
        });
        shows.put(k, new ArrayList<>(List.of(text, item, click)));
    }

    void removeShow(String k) {
        List<Entity> l = shows.remove(k);
        if (l != null) for (Entity e : l) e.remove();
    }

    private void stationClick(Player p, String k) {
        Egg g = eggs.get(stations.get(k));
        if (g == null) return;
        // avec cet œuf en main : on l'ouvre direct
        EquipmentSlot hand = EquipmentSlot.HAND;
        ItemStack inHand = p.getInventory().getItemInMainHand();
        if (eggOf(inHand) == g && !p.isSneaking()) {
            if (opening.contains(p.getUniqueId())) {
                send(p, msg("already-opening"));
                return;
            }
            if (inHand.getAmount() <= 1) p.getInventory().setItem(hand, null);
            else {
                inHand.setAmount(inHand.getAmount() - 1);
                p.getInventory().setItem(hand, inHand);
            }
            open(p, g);
            return;
        }
        openEgg(p, g);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onStationBlock(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.LEFT_CLICK_BLOCK)) return;
        String k = key(b);
        if (!stations.containsKey(k)) return;
        e.setCancelled(true);
        e.setUseItemInHand(Event.Result.DENY);
        if (e.getHand() != EquipmentSlot.HAND) return;
        stationClick(e.getPlayer(), k);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onStationHolo(PlayerInteractEntityEvent e) {
        String k = e.getRightClicked().getPersistentDataContainer().get(stationKey, PersistentDataType.STRING);
        if (k == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (stations.containsKey(k)) stationClick(e.getPlayer(), k);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onStationHit(EntityDamageByEntityEvent e) {
        String k = e.getEntity().getPersistentDataContainer().get(stationKey, PersistentDataType.STRING);
        if (k == null) return;
        e.setCancelled(true);
        if (e.getDamager() instanceof Player p && stations.containsKey(k)) stationClick(p, k);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStationBreak(BlockBreakEvent e) {
        if (!stations.containsKey(key(e.getBlock()))) return;
        e.setCancelled(true);
        if (e.getPlayer().hasPermission("smppets.admin")) send(e.getPlayer(), "<gray>C'est une station d'œuf : <white>/pets station retirer</white> en la regardant.");
    }

    // ================================================================= éditeur en jeu (admins)

    private final Map<UUID, java.util.function.Consumer<String>> asking = new HashMap<>();

    void ask(Player p, String question, java.util.function.Consumer<String> then) {
        p.closeInventory();
        asking.put(p.getUniqueId(), then);
        send(p, question + " <dark_gray>(tape <white>annuler</white> pour arrêter)");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChatAnswer(io.papermc.paper.event.player.AsyncChatEvent e) {
        java.util.function.Consumer<String> then = asking.remove(e.getPlayer().getUniqueId());
        if (then == null) return;
        e.setCancelled(true);
        String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Bukkit.getScheduler().runTask(this, () -> {
            if (text.equalsIgnoreCase("annuler") || text.equalsIgnoreCase("cancel")) {
                send(e.getPlayer(), "<gray>Annulé.");
                return;
            }
            then.accept(text);
        });
    }

    void saveAndReload() {
        saveConfig();
        for (String k : new ArrayList<>(shows.keySet())) removeShow(k);
        for (List<Follower> fs : active.values()) for (Follower f : fs) despawn(f);
        active.clear();
        load();
        for (Player pl : Bukkit.getOnlinePlayers()) refresh(pl);
    }

    static double money(String t) {
        String s = t.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace(",", ".");
        double mult = 1;
        if (s.endsWith("k")) {
            mult = 1_000;
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("m")) {
            mult = 1_000_000;
            s = s.substring(0, s.length() - 1);
        }
        try {
            return Double.parseDouble(s) * mult;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    void openEditor(Player p, String id) {
        String base = "oeufs." + id + ".";
        PetMenu m = new PetMenu("edit");
        m.editId = id;
        m.inv = Bukkit.createInventory(m, 27, parse("<dark_gray>✎ Œuf : " + id));
        ItemStack pane = named(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 27; i++) m.inv.setItem(i, pane);
        String name = getConfig().getString(base + "nom", id);
        double price = getConfig().getDouble(base + "prix", 0);
        Material mat = mat(getConfig().getString(base + "materiau"), Material.EGG);
        String rar = getConfig().getString(base + "rarete", "rare");
        Rarity r = rarities.get(rar);
        m.inv.setItem(10, named(Material.NAME_TAG, "<white><b>Nom", List.of("<gray>Actuel : " + name, "", "<yellow>Clic : écrire le nouveau nom",
                "<dark_gray>couleurs : <red>, <#ff7a00>, <gradient:#fff:#888>, <b>")));
        m.slots.put(10, "name");
        m.inv.setItem(11, named(Material.GOLD_INGOT, "<white><b>Prix", List.of("<gray>Actuel : <gold>" + (price > 0 ? (vault ? Money.format(price) : num(price)) : "pas en vente"),
                "", "<yellow>Clic : écrire le prix (ex. 250000 ou 250k)", "<dark_gray>0 = pas en vente (seulement caisses / events)")));
        m.slots.put(11, "price");
        m.inv.setItem(12, named(mat, "<white><b>Apparence", List.of("<gray>Actuelle : <white>" + mat.name().toLowerCase(Locale.ROOT), "",
                "<yellow>Clic avec un objet en main", "<yellow>pour qu'il devienne l'œuf")));
        m.slots.put(12, "look");
        List<String> lore = new ArrayList<>(List.of("<gray>Actuelle :"));
        for (String l : getConfig().getStringList(base + "lore")) lore.add("  " + l);
        lore.addAll(List.of("", "<yellow>Clic : écrire la description", "<dark_gray>sépare les lignes avec |"));
        m.inv.setItem(13, named(Material.WRITABLE_BOOK, "<white><b>Description", lore));
        m.slots.put(13, "lore");
        m.inv.setItem(14, named(r == null ? Material.WHITE_STAINED_GLASS_PANE : r.pane(), "<white><b>Couleur de la roulette",
                List.of("<gray>Actuelle : " + (r == null ? rar : r.name()), "", "<yellow>Clic pour changer")));
        m.slots.put(14, "rarity");
        Egg g = eggs.get(id);
        List<String> ch = new ArrayList<>();
        if (g != null) for (Map.Entry<String, Double> e : g.rarityChances.entrySet()) ch.add(rarities.get(e.getKey()).name() + "<reset> <white>" + pct(e.getValue()));
        ch.addAll(List.of("", "<yellow>Clic pour changer les chances"));
        m.inv.setItem(16, named(Material.CHEST, "<white><b>Chances", ch));
        m.slots.put(16, "chances");
        m.inv.setItem(22, named(Material.SPAWNER, "<white><b>Familiers", List.of("<gray>Changer le poids de chaque familier", "<gray>(plus rare ou plus fréquent dans sa rareté)", "", "<yellow>Clic")));
        m.slots.put(22, "pets");
        m.inv.setItem(18, named(Material.ARROW, "<yellow>← Les œufs", List.of()));
        m.slots.put(18, "back");
        m.inv.setItem(26, named(Material.EGG, "<green><b>Me donner cet œuf", List.of("<gray>Pour le tester.", "", "<yellow>Clic")));
        m.slots.put(26, "give");
        p.openInventory(m.inv);
    }

    void openChances(Player p, String id) {
        String base = "oeufs." + id + ".";
        PetMenu m = new PetMenu("editch");
        m.editId = id;
        m.inv = Bukkit.createInventory(m, 27, parse("<dark_gray>✎ Chances : " + id));
        ItemStack pane = named(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 27; i++) m.inv.setItem(i, pane);
        double total = 0;
        for (String r : rarities.keySet()) total += Math.max(0, getConfig().getDouble(base + "raretes." + r, 0));
        int slot = 10;
        for (Rarity r : rarities.values()) {
            if (slot > 16) break;
            double w = Math.max(0, getConfig().getDouble(base + "raretes." + r.id(), 0));
            long count = pets.values().stream().filter(d -> d.rarity.equals(r.id()) && d.weight > 0).count();
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Poids : <white>" + num(w));
            lore.add("<gray>Chance : <white>" + (total > 0 ? pct(w * 100 / total) : "0 %"));
            lore.add("<gray>Familiers de cette rareté : <white>" + count);
            lore.add("");
            lore.add("<yellow>Clic : écrire le poids <dark_gray>(0 = jamais)");
            m.inv.setItem(slot, named(r.pane(), r.name(), lore));
            m.slots.put(slot, r.id());
            slot++;
        }
        if (getConfig().isConfigurationSection(base + "pets")) {
            m.inv.setItem(4, named(Material.BARRIER, "<red>Liste précise de familiers active", List.of("<gray>Cet œuf utilise \"pets\" dans config.yml.", "<gray>Changer un poids ici l'enlève et", "<gray>repasse aux chances par rareté.")));
        }
        m.inv.setItem(18, named(Material.ARROW, "<yellow>← Retour", List.of()));
        m.slots.put(18, "back");
        p.openInventory(m.inv);
    }

    void openPetsEditor(Player p, int page) {
        List<PetDef> list = new ArrayList<>(pets.values());
        list.sort(Comparator.<PetDef>comparingInt(x -> rarities.get(x.rarity).order()).thenComparing(x -> x.name));
        int pages = Math.max(1, (list.size() + 44) / 45);
        page = Math.max(0, Math.min(page, pages - 1));
        PetMenu m = new PetMenu("editpets");
        m.page = page;
        m.inv = Bukkit.createInventory(m, 54, parse("<dark_gray>✎ Familiers (poids)"));
        for (int i = 0; i < 45 && page * 45 + i < list.size(); i++) {
            PetDef d = list.get(page * 45 + i);
            List<String> extra = new ArrayList<>(List.of("", "<gray>Poids : <white>" + num(d.weight) + (d.weight <= 0 ? " <red>(dans aucun œuf)" : ""),
                    "<dark_gray>2 = deux fois plus souvent que les autres de sa rareté", "", "<yellow>Clic : écrire le poids"));
            m.inv.setItem(i, petIcon(d, 1, false, extra));
            m.slots.put(i, d.id);
        }
        ItemStack pane = named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 45; i < 54; i++) m.inv.setItem(i, pane);
        if (page > 0) {
            m.inv.setItem(45, named(Material.ARROW, "<yellow>← Page précédente", List.of()));
            m.slots.put(45, "#prev");
        }
        if (page < pages - 1) {
            m.inv.setItem(53, named(Material.ARROW, "<yellow>Page suivante →", List.of()));
            m.slots.put(53, "#next");
        }
        m.inv.setItem(49, named(Material.BOOK, "<white>Le reste (effets, boosts, apparence)", List.of("<gray>se règle dans <white>pets.yml</white>", "<gray>puis <white>/pets reload")));
        p.openInventory(m.inv);
    }

    void editClick(Player p, PetMenu m, String k, boolean shiftRight) {
        if (!p.hasPermission("smppets.admin")) return;
        String id = m.editId;
        String base = "oeufs." + id + ".";
        switch (m.type) {
            case "edit" -> {
                switch (k) {
                    case "name" -> ask(p, "<gray>Nouveau nom de l'œuf :", t -> {
                        getConfig().set(base + "nom", t);
                        saveAndReload();
                        send(p, "<green>Nom : " + t);
                        openEditor(p, id);
                    });
                    case "price" -> ask(p, "<gray>Nouveau prix (ex. <white>250000</white> ou <white>250k</white>, 0 = pas en vente) :", t -> {
                        double v = money(t);
                        if (v < 0) {
                            send(p, "<red>Prix invalide.");
                            return;
                        }
                        getConfig().set(base + "prix", v);
                        saveAndReload();
                        send(p, "<green>Prix : <gold>" + (v > 0 ? num(v) : "pas en vente"));
                        openEditor(p, id);
                    });
                    case "look" -> {
                        ItemStack hand = p.getInventory().getItemInMainHand();
                        if (hand.getType().isAir()) {
                            send(p, "<red>Prends en main l'objet qui doit devenir l'œuf, puis clique.");
                            return;
                        }
                        getConfig().set(base + "materiau", hand.getType().name());
                        saveAndReload();
                        send(p, "<green>Apparence : <white>" + hand.getType().name().toLowerCase(Locale.ROOT));
                        openEditor(p, id);
                    }
                    case "lore" -> ask(p, "<gray>Description (sépare les lignes avec <white>|</white>) :", t -> {
                        List<String> lines = new ArrayList<>();
                        for (String l : t.split("\\|")) if (!l.isBlank()) lines.add(l.trim().startsWith("<") ? l.trim() : "<gray>" + l.trim());
                        getConfig().set(base + "lore", lines);
                        saveAndReload();
                        send(p, "<green>Description changée.");
                        openEditor(p, id);
                    });
                    case "rarity" -> {
                        List<String> ids = new ArrayList<>(rarities.keySet());
                        if (ids.isEmpty()) return;
                        int i = ids.indexOf(getConfig().getString(base + "rarete", ids.get(0)));
                        getConfig().set(base + "rarete", ids.get((i + 1) % ids.size()));
                        saveAndReload();
                        openEditor(p, id);
                    }
                    case "chances" -> openChances(p, id);
                    case "pets" -> openPetsEditor(p, 0);
                    case "back" -> openEggs(p);
                    case "give" -> {
                        Egg g = eggs.get(id);
                        if (g != null) giveEggs(p, g, 1);
                    }
                    default -> {
                    }
                }
            }
            case "editch" -> {
                if (k.equals("back")) {
                    openEditor(p, id);
                    return;
                }
                Rarity r = rarities.get(k);
                if (r == null) return;
                ask(p, "<gray>Poids de " + r.name() + "<reset> <gray>dans cet œuf (ex. <white>85</white>, <white>1.5</white>, <white>0</white> = jamais) :", t -> {
                    double v = parseDouble(t, -1);
                    if (v < 0) {
                        send(p, "<red>Il faut un nombre (0 ou plus).");
                        return;
                    }
                    getConfig().set(base + "pets", null);
                    getConfig().set(base + "raretes." + r.id(), v);
                    saveAndReload();
                    send(p, "<green>Poids de " + r.name() + "<reset> <green>: " + num(v));
                    openChances(p, id);
                });
            }
            case "editpets" -> {
                if (k.equals("#prev")) {
                    openPetsEditor(p, m.page - 1);
                    return;
                }
                if (k.equals("#next")) {
                    openPetsEditor(p, m.page + 1);
                    return;
                }
                PetDef d = pets.get(k);
                if (d == null) return;
                int page = m.page;
                ask(p, "<gray>Poids de " + rarities.get(d.rarity).style() + d.name + "<reset> <gray>(1 = normal, 2 = deux fois plus, 0.5 = deux fois moins, 0 = dans aucun œuf) :", t -> {
                    double v = parseDouble(t, -1);
                    if (v < 0) {
                        send(p, "<red>Il faut un nombre (0 ou plus).");
                        return;
                    }
                    File pf = new File(getDataFolder(), "pets.yml");
                    YamlConfiguration pc = YamlConfiguration.loadConfiguration(pf);
                    pc.set("pets." + d.id + ".poids", v);
                    try {
                        pc.save(pf);
                    } catch (IOException ex) {
                        send(p, "<red>Impossible d'écrire pets.yml : " + ex.getMessage());
                        return;
                    }
                    saveAndReload();
                    send(p, "<green>Poids de " + d.name + " : " + num(v));
                    openPetsEditor(p, page);
                });
            }
            default -> {
            }
        }
    }

    // ================================================================= familiers réservés + compétences

    /** Familiers avec "permission" : donnés à ceux qui l'ont, retirés aux autres. */
    void syncPermissionPets(Player p) {
        PData d = data(p.getUniqueId());
        boolean changed = false;
        for (PetDef def : pets.values()) {
            if (def.permission == null || def.permission.isBlank()) continue;
            boolean has = p.hasPermission(def.permission);
            if (has && !d.owned.containsKey(def.id)) {
                d.owned.put(def.id, 1);
                if (d.equipped.size() < slots(p)) d.equipped.add(def.id);
                changed = true;
            } else if (!has && d.owned.containsKey(def.id)) {
                d.owned.remove(def.id);
                d.equipped.remove(def.id);
                changed = true;
            }
        }
        if (changed) saveData(p.getUniqueId());
    }

    final Map<UUID, Long> abilityCooldown = new HashMap<>();

    /** Lance la compétence du familier équipé (ex : Loky chie à la gueule de quelqu'un). */
    void useAbility(Player p, String targetName) {
        PData d = data(p.getUniqueId());
        PetDef def = null;
        for (PetDef x : equipped(d)) if (x.ability != null && !x.ability.isBlank()) {
            def = x;
            break;
        }
        if (def == null) {
            send(p, msg("no-ability"));
            return;
        }
        if (def.permission != null && !def.permission.isBlank() && !p.hasPermission(def.permission)) {
            send(p, msg("no-permission"));
            return;
        }
        ConfigurationSection c = def.abilityCfg;
        double range = c.getDouble("portee", 25);
        Player t;
        if (targetName != null) {
            t = Bukkit.getPlayerExact(targetName);
            if (t == null || !t.getWorld().equals(p.getWorld()) || t.getLocation().distance(p.getLocation()) > range || vanished(t)) {
                send(p, msg("ability-no-target").replace("<range>", num(range)));
                return;
            }
        } else {
            Entity e = p.getTargetEntity((int) Math.ceil(range));
            t = e instanceof Player tp && !vanished(tp) ? tp : null;
            if (t == null) {
                send(p, msg("ability-no-target").replace("<range>", num(range)));
                return;
            }
        }
        if (t.equals(p)) {
            send(p, msg("ability-self"));
            return;
        }
        long now = System.currentTimeMillis();
        long cd = (long) (c.getDouble("recharge", 15) * 1000);
        Long last = abilityCooldown.get(p.getUniqueId());
        if (last != null && now - last < cd) {
            send(p, msg("ability-cooldown").replace("<time>", String.valueOf((cd - (now - last)) / 1000 + 1)));
            return;
        }
        Follower f = null;
        List<Follower> fs = active.get(p.getUniqueId());
        if (fs != null) for (Follower x : fs) if (x.def == def && x.ent != null && x.ent.isValid()) f = x;
        if (f == null) {
            send(p, msg("ability-hidden"));
            return;
        }
        abilityCooldown.put(p.getUniqueId(), now);
        if ("caca".equals(def.ability)) poop(p, f, t, c);
    }

    private void poop(Player owner, Follower f, Player t, ConfigurationSection c) {
        String petMini = rarities.get(f.def.rarity).style() + f.def.name + "<reset>";
        f.chase = t;
        f.overrideUntil = tick + 60;
        owner.playSound(owner.getLocation(), Sound.ENTITY_CAT_PURREOW, 1f, 1.2f);
        Particle.DustOptions brown = new Particle.DustOptions(Color.fromRGB(92, 58, 28), 1.6f), dark = new Particle.DustOptions(Color.fromRGB(58, 34, 14), 2.2f);
        int[] k = {0};
        boolean[] done = {false};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(this, () -> {
            int i = k[0]++;
            if (!t.isOnline() || f.ent == null || !f.ent.isValid() || i > 60) {
                task[0].cancel();
                return;
            }
            Location cat = f.ent.getLocation();
            Location head = t.getEyeLocation().add(0, 0.25, 0);
            boolean above = cat.getWorld() == head.getWorld() && cat.distanceSquared(head.clone().add(0, 1, 0)) < 2.5;
            if (!done[0] && (above || i >= 40)) {
                done[0] = true;
                f.overrideUntil = tick + 30; // il reste un peu au-dessus, puis revient
                cat.getWorld().playSound(cat, Sound.ENTITY_CAT_HISS, 1f, 1.4f);
                // la chute
                for (int j = 0; j < 6; j++) {
                    int jj = j;
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        if (!t.isOnline()) return;
                        Location from = f.ent != null && f.ent.isValid() ? f.ent.getLocation() : t.getEyeLocation().add(0, 1.3, 0);
                        Location to = t.getEyeLocation().add(0, 0.25, 0);
                        Vector step = to.toVector().subtract(from.toVector()).multiply(1 / 6.0);
                        Location at = from.clone().add(step.clone().multiply(jj));
                        t.getWorld().spawnParticle(Particle.DUST, at, 6, 0.08, 0.08, 0.08, 0, jj % 2 == 0 ? brown : dark);
                        if (jj == 0) t.getWorld().playSound(at, Sound.ENTITY_SLIME_SQUISH_SMALL, 1.2f, 0.5f);
                    }, j);
                }
                Bukkit.getScheduler().runTaskLater(this, () -> splat(owner, f, t, c, petMini, brown, dark), 6L);
            }
        }, 0L, 1L);
    }

    private void splat(Player owner, Follower f, Player t, ConfigurationSection c, String petMini, Particle.DustOptions brown, Particle.DustOptions dark) {
        if (!t.isOnline()) return;
        Location face = t.getEyeLocation();
        World w = face.getWorld();
        w.spawnParticle(Particle.DUST, face, 50, 0.35, 0.3, 0.35, 0, brown);
        w.spawnParticle(Particle.DUST, face, 30, 0.3, 0.25, 0.3, 0, dark);
        w.spawnParticle(Particle.BLOCK, face, 40, 0.3, 0.3, 0.3, 0, Material.MUD.createBlockData());
        w.spawnParticle(Particle.FALLING_DUST, face, 25, 0.35, 0.2, 0.35, 0, Material.BROWN_CONCRETE_POWDER.createBlockData());
        w.playSound(face, Sound.BLOCK_MUD_BREAK, 1.4f, 0.7f);
        w.playSound(face, Sound.BLOCK_HONEY_BLOCK_SLIDE, 1.2f, 0.6f);
        w.playSound(face, Sound.ENTITY_SLIME_SQUISH, 1.2f, 0.6f);
        // l'écran de la victime plein de caca (juste pour elle)
        Location screen = face.clone().add(face.getDirection().multiply(0.9));
        t.spawnParticle(Particle.DUST, screen, 60, 0.5, 0.35, 0.5, 0, dark);
        t.spawnParticle(Particle.DUST, screen, 40, 0.5, 0.35, 0.5, 0, brown);
        int nausea = (int) (c.getDouble("nausee", 6) * 20), blind = (int) (c.getDouble("aveuglement", 2) * 20);
        if (nausea > 0) t.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, nausea, 0, false, false, true));
        if (blind > 0) t.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, blind, 0, false, false, true));
        t.showTitle(Title.title(parse(msg("poop-title")), parse(msg("poop-subtitle").replace("<pet>", petMini).replace("<owner>", MM.escapeTags(owner.getName()))),
                Title.Times.times(java.time.Duration.ofMillis(100), java.time.Duration.ofMillis(1600), java.time.Duration.ofMillis(400))));
        // un petit tas sur la tête pendant quelques secondes
        int stay = (int) (c.getDouble("sur-la-tete", 5) * 20);
        if (stay > 0) {
            ItemDisplay pile = w.spawn(t.getLocation().add(0, t.getHeight() + 0.1, 0), ItemDisplay.class, d -> {
                d.addScoreboardTag(TAG);
                d.setPersistent(false);
                d.setItemStack(new ItemStack(Material.COCOA_BEANS));
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.7f, 0.7f, 0.7f), new Quaternionf()));
                d.setBillboard(Display.Billboard.VERTICAL);
                d.setTeleportDuration(1);
            });
            int[] k = {0};
            BukkitTask[] task = new BukkitTask[1];
            task[0] = Bukkit.getScheduler().runTaskTimer(this, () -> {
                if (k[0]++ >= stay || !t.isOnline() || !pile.isValid() || pile.getWorld() != t.getWorld()) {
                    pile.remove();
                    task[0].cancel();
                    return;
                }
                pile.teleport(t.getLocation().add(0, t.getHeight() + 0.15, 0));
                if (k[0] % 6 == 0) t.getWorld().spawnParticle(Particle.DUST, t.getLocation().add(0, t.getHeight() + 0.2, 0), 2, 0.15, 0.1, 0.15, 0, brown);
                if (k[0] % 10 == 0) t.getWorld().spawnParticle(Particle.FALLING_DUST, t.getLocation().add(0, t.getHeight(), 0), 2, 0.2, 0.05, 0.2, 0,
                        Material.BROWN_CONCRETE_POWDER.createBlockData());
            }, 1L, 1L);
        }
        String line = msg("poop-broadcast").replace("<pet>", petMini).replace("<owner>", MM.escapeTags(owner.getName()))
                .replace("<target>", MM.escapeTags(t.getName()));
        double r = c.getDouble("message-rayon", 40);
        for (Player o : face.getWorld().getPlayers()) if (o.getLocation().distanceSquared(face) <= r * r) send(o, line);
    }

    // ================================================================= commandes

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        Player p = s instanceof Player pl ? pl : null;
        switch (sub) {
            case "" -> {
                if (p != null) openMain(p, 0);
            }
            case "oeufs", "oeuf", "œufs", "œuf", "eggs" -> {
                if (p != null) openEggs(p);
            }
            case "trade", "echanger", "échanger", "echange" -> {
                if (p != null) tradeCommand(p, a);
            }
            case "competence", "compétence", "caca", "loky" -> {
                if (p != null) useAbility(p, a.length >= 2 ? a[1] : null);
            }
            case "cacher", "montrer", "hide" -> {
                if (p == null) return true;
                PData d = data(p.getUniqueId());
                d.hidden = !d.hidden;
                saveData(p.getUniqueId());
                send(p, msg(d.hidden ? "hidden" : "shown"));
            }
            case "donner", "give" -> {
                if (!admin(s)) return true;
                if (a.length < 3) {
                    sendRaw(s, "<red>/pets donner <joueur> <oeuf> [nombre]");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                Egg g = eggs.get(a[2].toLowerCase(Locale.ROOT));
                if (t == null) {
                    sendRaw(s, msg("unknown-player"));
                    return true;
                }
                if (g == null) {
                    sendRaw(s, msg("unknown-egg").replace("<list>", String.join(", ", eggs.keySet())));
                    return true;
                }
                int n = a.length >= 4 ? Math.max(1, Math.min(640, parseInt(a[3], 1))) : 1;
                giveEggs(t, g, n);
                send(t, msg("egg-given").replace("<amount>", String.valueOf(n)).replace("<egg>", g.name + "<reset>"));
                if (s != t) sendRaw(s, msg("egg-given-admin").replace("<amount>", String.valueOf(n)).replace("<egg>", g.name + "<reset>").replace("<player>", t.getName()));
            }
            case "pet", "familier" -> {
                if (!admin(s)) return true;
                if (a.length < 3) {
                    sendRaw(s, "<red>/pets pet <joueur> <familier>");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                PetDef def = pets.get(a[2].toLowerCase(Locale.ROOT));
                if (t == null) {
                    sendRaw(s, msg("unknown-player"));
                    return true;
                }
                if (def == null) {
                    sendRaw(s, msg("unknown-pet"));
                    return true;
                }
                int res = grant(t, def);
                String n = rarities.get(def.rarity).style() + def.name + "<reset>";
                if (res == 1) send(t, msg("new-pet").replace("<pet>", n));
                else if (res > 1) send(t, msg("star-up").replace("<pet>", n).replace("<stars>", "★".repeat(res)));
                sendRaw(s, msg("pet-given-admin").replace("<pet>", n).replace("<player>", t.getName()));
            }
            case "retirer", "remove" -> {
                if (!admin(s)) return true;
                if (a.length < 3) {
                    sendRaw(s, "<red>/pets retirer <joueur> <familier>");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) {
                    sendRaw(s, msg("unknown-player"));
                    return true;
                }
                PData d = data(t.getUniqueId());
                String id = a[2].toLowerCase(Locale.ROOT);
                if (d.owned.remove(id) == null) {
                    sendRaw(s, msg("unknown-pet"));
                    return true;
                }
                d.equipped.remove(id);
                saveData(t.getUniqueId());
                refresh(t);
                PetDef def = pets.get(id);
                sendRaw(s, msg("pet-removed-admin").replace("<pet>", def == null ? id : rarities.get(def.rarity).style() + def.name + "<reset>").replace("<player>", t.getName()));
            }
            case "liste", "list" -> {
                if (!admin(s)) return true;
                for (Rarity r : rarities.values()) {
                    StringBuilder b = new StringBuilder();
                    for (PetDef d : pets.values()) if (d.rarity.equals(r.id())) b.append(b.length() == 0 ? "" : "<gray>, ").append(r.style()).append(d.id);
                    sendRaw(s, r.name() + "<reset><gray> : " + b);
                }
                sendRaw(s, "<gray>Œufs : <white>" + String.join(", ", eggs.keySet()));
            }
            case "station" -> {
                if (!admin(s) || p == null) return true;
                Block b = p.getTargetBlockExact(6);
                if (a.length >= 2 && (a[1].equalsIgnoreCase("retirer") || a[1].equalsIgnoreCase("remove"))) {
                    if (b == null || stations.remove(key(b)) == null) {
                        send(p, "<red>Regarde une station d'œuf (à moins de 6 blocs).");
                        return true;
                    }
                    removeShow(key(b));
                    saveStations();
                    send(p, "<yellow>Station retirée.");
                    return true;
                }
                Egg g = a.length >= 2 ? eggs.get(a[1].toLowerCase(Locale.ROOT)) : null;
                if (g == null) {
                    send(p, "<red>/pets station <oeuf> <gray>(en regardant un bloc) <dark_gray>· <red>/pets station retirer <gray>· œufs : <white>" + String.join(", ", eggs.keySet()));
                    return true;
                }
                if (b == null) {
                    send(p, "<red>Regarde un bloc (à moins de 6 blocs) : il devient la station de l'œuf.");
                    return true;
                }
                removeShow(key(b));
                stations.put(key(b), g.id);
                saveStations();
                send(p, "<green>Station de " + g.name + "<reset> <green>posée ! <gray>Clic droit dessus = voir, ouvrir, acheter. <white>/pets station retirer</white> <gray>pour l'enlever.");
            }
            case "editer", "edit", "modifier" -> {
                if (!admin(s) || p == null) return true;
                if (a.length < 2) {
                    openEggs(p);
                    send(p, "<gray>Choisis un œuf (bouton <red>Modifier</red> dans son contenu), ou <white>/pets editer <oeuf></white>. <gray>Familiers : <white>/pets editer familiers");
                    return true;
                }
                if (a[1].equalsIgnoreCase("familiers") || a[1].equalsIgnoreCase("pets")) {
                    openPetsEditor(p, 0);
                    return true;
                }
                if (!eggs.containsKey(a[1].toLowerCase(Locale.ROOT)) && !getConfig().isConfigurationSection("oeufs." + a[1].toLowerCase(Locale.ROOT))) {
                    send(p, msg("unknown-egg").replace("<list>", String.join(", ", eggs.keySet())));
                    return true;
                }
                openEditor(p, a[1].toLowerCase(Locale.ROOT));
            }
            case "creer", "créer", "create" -> {
                if (!admin(s) || p == null) return true;
                String id = a.length >= 2 ? a[1].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "") : "";
                if (id.isEmpty() || getConfig().isConfigurationSection("oeufs." + id)) {
                    send(p, "<red>/pets creer <id> <gray>(un id qui n'existe pas encore, ex : premium)");
                    return true;
                }
                String base = "oeufs." + id + ".";
                getConfig().set(base + "nom", "<white><b>Œuf " + id);
                getConfig().set(base + "materiau", "EGG");
                getConfig().set(base + "rarete", rarities.isEmpty() ? "rare" : rarities.keySet().iterator().next());
                getConfig().set(base + "prix", 0);
                getConfig().set(base + "lore", List.of("<gray>Un œuf tout neuf."));
                for (String r : rarities.keySet()) if (!r.equals("modo")) getConfig().set(base + "raretes." + r, 1);
                saveAndReload();
                send(p, "<green>Œuf <white>" + id + "</white> créé. Règle-le dans le menu !");
                openEditor(p, id);
            }
            case "supprimer", "delete" -> {
                if (!admin(s)) return true;
                String id = a.length >= 2 ? a[1].toLowerCase(Locale.ROOT) : "";
                if (!getConfig().isConfigurationSection("oeufs." + id)) {
                    sendRaw(s, msg("unknown-egg").replace("<list>", String.join(", ", eggs.keySet())));
                    return true;
                }
                getConfig().set("oeufs." + id, null);
                stations.values().removeIf(id::equals);
                saveStations();
                saveAndReload();
                sendRaw(s, "<yellow>Œuf " + id + " supprimé (les œufs déjà donnés ne marchent plus).");
            }
            case "reload" -> {
                if (!admin(s)) return true;
                for (String k : new ArrayList<>(shows.keySet())) removeShow(k);
                for (List<Follower> fs : active.values()) for (Follower f : fs) despawn(f);
                active.clear();
                load();
                for (Player pl : Bukkit.getOnlinePlayers()) {
                    syncPermissionPets(pl);
                    refresh(pl);
                }
                sendRaw(s, msg("reloaded").replace("<pets>", String.valueOf(pets.size())).replace("<eggs>", String.valueOf(eggs.size())));
            }
            default -> {
                sendRaw(s, "<gold><b>Familiers");
                sendRaw(s, "<aqua>/pets</aqua> <gray>- tes familiers · <aqua>/pets oeufs</aqua> <gray>- les œufs · <aqua>/pets cacher");
                if (s.hasPermission("smppets.admin")) {
                    sendRaw(s, "<aqua>/pets donner <joueur> <oeuf> [n]</aqua> <gray>· <aqua>/pets pet <joueur> <familier>");
                    sendRaw(s, "<aqua>/pets retirer <joueur> <familier></aqua> <gray>· <aqua>/pets liste</aqua> <gray>· <aqua>/pets reload");
                }
            }
        }
        return true;
    }

    private boolean admin(CommandSender s) {
        if (s.hasPermission("smppets.admin")) return true;
        sendRaw(s, msg("no-permission"));
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        boolean admin = s.hasPermission("smppets.admin");
        if (a.length == 1) {
            out.addAll(List.of("oeufs", "cacher", "trade", "competence"));
            if (admin) out.addAll(List.of("donner", "pet", "retirer", "liste", "station", "editer", "creer", "supprimer", "reload"));
        } else if (a.length == 2 && admin && List.of("donner", "pet", "retirer").contains(a[0].toLowerCase(Locale.ROOT))) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (a.length == 3 && admin) {
            String sub = a[0].toLowerCase(Locale.ROOT);
            if (sub.equals("donner")) out.addAll(eggs.keySet());
            else if (sub.equals("station")) {
                out.addAll(eggs.keySet());
                out.add("retirer");
            } else if (sub.equals("editer") || sub.equals("supprimer")) {
                out.addAll(eggs.keySet());
                if (sub.equals("editer")) out.add("familiers");
            }
            else if (sub.equals("pet")) out.addAll(pets.keySet());
            else if (sub.equals("retirer")) {
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t != null) out.addAll(data(t.getUniqueId()).owned.keySet());
            }
        } else if (a.length == 2 && admin && List.of("station", "editer", "supprimer").contains(a[0].toLowerCase(Locale.ROOT))) {
            out.addAll(eggs.keySet());
            if (a[0].equalsIgnoreCase("station")) out.add("retirer");
            if (a[0].equalsIgnoreCase("editer")) out.add("familiers");
        } else if (a.length == 2 && a[0].equalsIgnoreCase("trade")) {
            for (Player p : Bukkit.getOnlinePlayers()) if (!p.equals(s)) out.add(p.getName());
        } else if (a.length == 2 && a[0].equalsIgnoreCase("competence")) {
            for (Player p : Bukkit.getOnlinePlayers()) if (!p.equals(s)) out.add(p.getName());
        } else if (a.length == 4 && admin && a[0].equalsIgnoreCase("donner")) {
            out.addAll(List.of("1", "5", "10"));
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    // ================================================================= outils

    String msg(String k) {
        // sans valeur par défaut ici : les nouveaux messages du plugin marchent même avec un ancien config.yml
        String s = getConfig().getString("messages." + k);
        return s == null ? k : s;
    }

    void send(CommandSender s, String mini) {
        s.sendMessage(parse(msg("prefix") + mini));
    }

    void sendRaw(CommandSender s, String mini) {
        s.sendMessage(parse(mini));
    }

    static Component parse(String mini) {
        return MM.deserialize(mini == null ? "" : mini);
    }

    static Component line(String mini) {
        return parse(mini).decoration(TextDecoration.ITALIC, false);
    }

    static ItemStack named(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m == null || m.isAir() ? Material.BARRIER : m);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;
        meta.displayName(line(name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(line(s));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    static <T extends Keyed> T reg(RegistryKey<T> key, String name) {
        try {
            return RegistryAccess.registryAccess().getRegistry(key).get(NamespacedKey.minecraft(name.trim().toLowerCase(Locale.ROOT)));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    static Material mat(String s, Material def) {
        if (s == null || s.isBlank()) return def;
        Material m = Material.matchMaterial(s.trim());
        return m == null || !m.isItem() ? def : m;
    }

    static Color hex(String s) {
        try {
            return Color.fromRGB(Integer.parseInt(s.replace("#", "").trim(), 16));
        } catch (RuntimeException ex) {
            return Color.WHITE;
        }
    }

    static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException ex) {
            return def;
        }
    }

    static double parseDouble(String s, double def) {
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (RuntimeException ex) {
            return def;
        }
    }

    static String pct(double v) {
        if (v >= 10) return String.format(Locale.ROOT, "%.1f %%", v);
        if (v >= 1) return String.format(Locale.ROOT, "%.2f %%", v);
        if (v >= 0.01) return String.format(Locale.ROOT, "%.2f %%", v);
        return String.format(Locale.ROOT, "%.3f %%", v);
    }

    static String num(double v) {
        if (Math.abs(v - Math.round(v)) < 1e-6) return String.valueOf(Math.round(v));
        return String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    static String signedPct(double v) {
        return (v >= 0 ? "+" : "") + num(v * 100) + " %";
    }

    static String roman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
    }
}
