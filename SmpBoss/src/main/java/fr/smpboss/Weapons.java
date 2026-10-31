package fr.smpboss;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static fr.smpboss.SmpBoss.parse;

/**
 * Armes du Roi Citrouille (butin rare, gardées à la mort) :
 * - Sonic Bow : un tir chargé AU MAXIMUM envoie un rayon sonique de Warden (traverse l'armure) au lieu d'une flèche.
 * - Tranche Mace : clic droit = une longue lame verticale qui file droit devant ; ceux qu'elle touche lévitent
 *   (le joueur à la masse ne peut plus tomber sur toi).
 */
final class Weapons implements Listener {

    static final String SONIC = "sonic_bow", TRANCHE = "tranche_mace", BRISE = "brise_terre", SPECTRAL = "arbalete_spectrale",
            GIVRE = "croc_de_givre", TEMPETE = "arc_tempete", HOLLOW = "hollow_purple", FUGA = "fuga",
            LAME = "lame_citrouille", FOLLETS = "arc_feux_follets", HACHE = "hache_maudite",
            DAGUE = "dague_venin", ARC_TOILES = "arc_toiles", CROC_REINE = "croc_reine",
            FAUX_TISSEUSE = "faux_tisseuse", SCEPTRE = "sceptre_abysse", LANCE = "lance_ciel", TRIBATON = "tribaton", TENUE = "tenue_toji",
            TENUE_GOJO = "tenue_gojo", TENUE_SUKUNA = "tenue_sukuna";
    /** les tenues : toutes leurs pièces tombent ensemble */
    static final Map<String, String[]> OUTFITS = Map.of(
            TENUE, new String[]{"toji_haut", "toji_pantalon", "toji_bottes"},
            TENUE_GOJO, new String[]{"gojo_bandeau", "gojo_haut", "gojo_pantalon", "gojo_bottes"},
            TENUE_SUKUNA, new String[]{"sukuna_haut", "sukuna_pantalon", "sukuna_bottes"});

    /** "toji_haut" -> "tenue_toji" (null si ce n'est pas une pièce de tenue) */
    static String outfitOf(String pid) {
        if (pid == null) return null;
        for (Map.Entry<String, String[]> e : OUTFITS.entrySet()) for (String x : e.getValue()) if (x.equals(pid)) return e.getKey();
        return null;
    }
    /** armes du Roi Citrouille */
    static final String[] PUMPKIN = {SONIC, TRANCHE, BRISE, SPECTRAL, GIVRE, TEMPETE, HOLLOW, FUGA, LAME, FOLLETS, HACHE, TENUE_GOJO, TENUE_SUKUNA};
    /** armes de la Reine Araignée */
    static final String[] SPIDER = {DAGUE, ARC_TOILES, FAUX_TISSEUSE, TRIBATON, TENUE, CROC_REINE, LANCE, SCEPTRE};
    static final String[] ALL = {SONIC, TRANCHE, BRISE, SPECTRAL, GIVRE, TEMPETE, HOLLOW, FUGA, LAME, FOLLETS, HACHE, DAGUE, ARC_TOILES, CROC_REINE,
            FAUX_TISSEUSE, SCEPTRE, LANCE, TRIBATON, TENUE, TENUE_GOJO, TENUE_SUKUNA};

    /** Chance par victoire si le config n'en donne pas. */
    static double defChance(String id) {
        return switch (rarity(id)) {
            case PRESTIGE -> 0.01;
            case EPIC -> 3;
            case LEGENDARY -> 0.4;
            default -> 0.5;
        };
    }

    /** Section du config / des textes de chaque arme. */
    static String path(String id) {
        String of = outfitOf(id);
        if (of != null) id = of;
        return switch (id) {
            case SONIC -> "sonic-bow";
            case TRANCHE -> "tranche-mace";
            case BRISE -> "brise-terre";
            case GIVRE -> "croc-de-givre";
            case TEMPETE -> "arc-tempete";
            case HOLLOW -> "hollow-purple";
            case FUGA -> "fuga";
            case LAME -> "lame-citrouille";
            case FOLLETS -> "arc-feux-follets";
            case HACHE -> "hache-maudite";
            case DAGUE -> "dague-venin";
            case ARC_TOILES -> "arc-toiles";
            case CROC_REINE -> "croc-reine";
            case FAUX_TISSEUSE -> "faux-tisseuse";
            case SCEPTRE -> "sceptre-abysse";
            case LANCE -> "lance-ciel";
            case TRIBATON -> "tribaton";
            case TENUE -> "tenue-toji";
            case TENUE_GOJO -> "tenue-gojo";
            case TENUE_SUKUNA -> "tenue-sukuna";
            default -> "arbalete-spectrale";
        };
    }

    private static Material material(String id) {
        return switch (id) {
            case SONIC -> Material.BOW;
            case TRANCHE -> Material.NETHERITE_SWORD;
            case BRISE -> Material.NETHERITE_AXE;
            case GIVRE -> Material.DIAMOND_SWORD;
            case TEMPETE -> Material.BOW;
            case HOLLOW -> Material.AMETHYST_SHARD;
            case FUGA -> Material.BLAZE_ROD;
            case LAME -> Material.DIAMOND_SWORD;
            case FOLLETS -> Material.BOW;
            case HACHE -> Material.DIAMOND_AXE;
            case DAGUE -> Material.IRON_SWORD;
            case ARC_TOILES -> Material.BOW;
            case CROC_REINE -> Material.NETHERITE_SWORD;
            case SCEPTRE -> Material.BREEZE_ROD;
            case LANCE -> Material.NETHERITE_SWORD;
            case TRIBATON -> Material.NETHERITE_SHOVEL;
            default -> Material.NETHERITE_HOE; // faux spectrale, faux de la tisseuse
        };
    }

    /** PRESTIGE : Sonic Bow, Brise-Terre, Faux des Âmes, Violet Imaginaire, Fûga. SECRET : le reste. */
    static Rarity rarity(String id) {
        if (LAME.equals(id) || FOLLETS.equals(id) || HACHE.equals(id) || DAGUE.equals(id) || ARC_TOILES.equals(id)) return Rarity.EPIC;
        if (CROC_REINE.equals(id) || LANCE.equals(id)) return Rarity.SECRET;
        if (TRIBATON.equals(id) || OUTFITS.containsKey(id) || outfitOf(id) != null) return Rarity.LEGENDARY;
        if (FAUX_TISSEUSE.equals(id)) return Rarity.LEGENDARY;
        if (SCEPTRE.equals(id)) return Rarity.PRESTIGE;
        return TRANCHE.equals(id) || GIVRE.equals(id) || TEMPETE.equals(id) ? Rarity.SECRET : Rarity.PRESTIGE;
    }

    private static double defDamage(String id) {
        return switch (id) {
            case SONIC -> 10;
            case TRANCHE -> 12;
            case BRISE -> 16;
            case GIVRE, TEMPETE -> 10;
            case HOLLOW, FUGA -> 1000;
            case LAME -> 6;
            case FOLLETS -> 70;
            case HACHE -> 5;
            case DAGUE -> 7;
            case ARC_TOILES -> 6;
            case CROC_REINE -> 12;
            case FAUX_TISSEUSE -> 9;
            case SCEPTRE -> 18;
            case LANCE -> 160;
            case TRIBATON -> 7;
            default -> 14;
        };
    }

    private static double defCooldown(String id) {
        return switch (id) {
            case SONIC -> 10;
            case TRANCHE -> 8;
            case BRISE -> 7;
            case GIVRE, TEMPETE -> 8;
            case LAME -> 10;
            case FOLLETS -> 6;
            case HACHE -> 12;
            case DAGUE -> 9;
            case ARC_TOILES -> 8;
            case CROC_REINE -> 16;
            case FAUX_TISSEUSE -> 12;
            case SCEPTRE -> 25;
            case LANCE -> 90;
            case TRIBATON -> 10;
            default -> 2;
        };
    }

    private final SmpBoss plugin;
    final NamespacedKey key;
    private final Map<UUID, Long> drawStart = new HashMap<>();
    private final Map<String, Long> cooldown = new HashMap<>();
    /** armes gardées à la mort, rendues à la réapparition */
    private final Map<UUID, List<ItemStack>> kept = new HashMap<>();

    final Ultimates ultimates;
    private final NamespacedKey stormKey;

    Weapons(SmpBoss plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "weapon");
        this.stormKey = new NamespacedKey(plugin, "storm_arrow");
        this.ultimates = new Ultimates(plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::aura, 40L, 3L);
    }

    private double cfg(String path, double def) {
        return plugin.getConfig().getDouble("weapons." + path, def);
    }

    // ================================================================= objets

    ItemStack create(String id) {
        if (OUTFITS.containsKey(id)) return outfitPiece(OUTFITS.get(id)[TENUE_GOJO.equals(id) ? 1 : 0]);
        if (outfitOf(id) != null) return outfitPiece(id);
        boolean bow = SONIC.equals(id) || TEMPETE.equals(id) || FOLLETS.equals(id) || ARC_TOILES.equals(id);
        ItemStack it = new ItemStack(material(id));
        ItemMeta m = it.getItemMeta();
        String base = "weapons." + path(id) + ".";
        m.displayName(parse(rarity(id).name(plugin.msg("weapons." + path(id) + ".name"), 0)));
        List<Component> lore = new ArrayList<>();
        lore.add(parse(rarity(id).tag));
        lore.add(Component.empty());
        for (String l : plugin.getConfig().getStringList("messages." + base + "lore")) {
            lore.add(parse(l.replace("<damage>", fmt(cfg(path(id) + ".damage", defDamage(id)) / 2))
                    .replace("<cooldown>", fmt(cfg(path(id) + ".cooldown-seconds", defCooldown(id))))
                    .replace("<radius>", fmt(cfg(path(id) + ".radius", 10)))
                    .replace("<minutes>", fmt(cfg(path(id) + ".cooldown-minutes", 15)))));
        }
        m.lore(lore);
        if (BRISE.equals(id)) {
            // hache PRESTIGE : 18 de dégâts (netherite = 10)
            m.addAttributeModifier(org.bukkit.attribute.Attribute.ATTACK_DAMAGE, new org.bukkit.attribute.AttributeModifier(
                    new NamespacedKey(plugin, "brise_damage"), cfg("brise-terre.attack-damage", 22) - 1,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
            m.addAttributeModifier(org.bukkit.attribute.Attribute.ATTACK_SPEED, new org.bukkit.attribute.AttributeModifier(
                    new NamespacedKey(plugin, "brise_speed"), cfg("brise-terre.attack-speed", 1.1) - 4,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
        }
        if (GIVRE.equals(id)) {
            // épée SECRÈTE, dans la lignée de la Tranche Mace
            m.addAttributeModifier(org.bukkit.attribute.Attribute.ATTACK_DAMAGE, new org.bukkit.attribute.AttributeModifier(
                    new NamespacedKey(plugin, "givre_damage"), cfg("croc-de-givre.attack-damage", 15) - 1,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
            m.addAttributeModifier(org.bukkit.attribute.Attribute.ATTACK_SPEED, new org.bukkit.attribute.AttributeModifier(
                    new NamespacedKey(plugin, "givre_speed"), cfg("croc-de-givre.attack-speed", 1.6) - 4,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
        }
        if (SPECTRAL.equals(id)) {
            // faux PRESTIGE : 18 de dégâts au corps à corps
            m.addAttributeModifier(org.bukkit.attribute.Attribute.ATTACK_DAMAGE, new org.bukkit.attribute.AttributeModifier(
                    new NamespacedKey(plugin, "faux_damage"), cfg("arbalete-spectrale.attack-damage", 18) - 1,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
            m.addAttributeModifier(org.bukkit.attribute.Attribute.ATTACK_SPEED, new org.bukkit.attribute.AttributeModifier(
                    new NamespacedKey(plugin, "faux_speed"), cfg("arbalete-spectrale.attack-speed", 1.2) - 4,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
        }
        if (FAUX_TISSEUSE.equals(id) || SCEPTRE.equals(id)) {
            boolean faux = FAUX_TISSEUSE.equals(id);
            m.addAttributeModifier(org.bukkit.attribute.Attribute.ATTACK_DAMAGE, new org.bukkit.attribute.AttributeModifier(
                    new NamespacedKey(plugin, id + "_damage"), cfg(path(id) + ".attack-damage", faux ? 13 : 12) - 1,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
            m.addAttributeModifier(org.bukkit.attribute.Attribute.ATTACK_SPEED, new org.bukkit.attribute.AttributeModifier(
                    new NamespacedKey(plugin, id + "_speed"), cfg(path(id) + ".attack-speed", faux ? 1.2 : 1.4) - 4,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
        }
        if (LANCE.equals(id) || TRIBATON.equals(id)) {
            boolean lance = LANCE.equals(id);
            m.addAttributeModifier(org.bukkit.attribute.Attribute.ATTACK_DAMAGE, new org.bukkit.attribute.AttributeModifier(
                    new NamespacedKey(plugin, id + "_damage"), cfg(path(id) + ".attack-damage", lance ? 16 : 12) - 1,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
            m.addAttributeModifier(org.bukkit.attribute.Attribute.ATTACK_SPEED, new org.bukkit.attribute.AttributeModifier(
                    new NamespacedKey(plugin, id + "_speed"), cfg(path(id) + ".attack-speed", lance ? 1.8 : 1.5) - 4,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
        }
        if (DAGUE.equals(id) || CROC_REINE.equals(id)) {
            boolean croc = CROC_REINE.equals(id);
            m.addAttributeModifier(org.bukkit.attribute.Attribute.ATTACK_DAMAGE, new org.bukkit.attribute.AttributeModifier(
                    new NamespacedKey(plugin, id + "_damage"), cfg(path(id) + ".attack-damage", croc ? 15 : 8) - 1,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
            m.addAttributeModifier(org.bukkit.attribute.Attribute.ATTACK_SPEED, new org.bukkit.attribute.AttributeModifier(
                    new NamespacedKey(plugin, id + "_speed"), cfg(path(id) + ".attack-speed", croc ? 1.6 : 2.0) - 4,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
        }
        if (LAME.equals(id) || HACHE.equals(id)) {
            // armes ÉPIQUES : un peu au-dessus du diamant, sous le netherite enchanté
            boolean axe = HACHE.equals(id);
            m.addAttributeModifier(org.bukkit.attribute.Attribute.ATTACK_DAMAGE, new org.bukkit.attribute.AttributeModifier(
                    new NamespacedKey(plugin, id + "_damage"), cfg(path(id) + ".attack-damage", axe ? 10 : 8) - 1,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
            m.addAttributeModifier(org.bukkit.attribute.Attribute.ATTACK_SPEED, new org.bukkit.attribute.AttributeModifier(
                    new NamespacedKey(plugin, id + "_speed"), cfg(path(id) + ".attack-speed", axe ? 1.0 : 1.6) - 4,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
        }
        if (TRANCHE.equals(id)) {
            // plus fort qu'une épée en netherite Tranchant V (11) : 13 de base, et on peut encore l'enchanter
            m.addAttributeModifier(org.bukkit.attribute.Attribute.ATTACK_DAMAGE, new org.bukkit.attribute.AttributeModifier(
                    new NamespacedKey(plugin, "tranche_damage"), cfg("tranche-mace.attack-damage", 16) - 1,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
            m.addAttributeModifier(org.bukkit.attribute.Attribute.ATTACK_SPEED, new org.bukkit.attribute.AttributeModifier(
                    new NamespacedKey(plugin, "tranche_speed"), cfg("tranche-mace.attack-speed", 1.6) - 4,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
        }
        m.setUnbreakable(true);
        m.setEnchantmentGlintOverride(true);
        m.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        if (bow) m.addEnchant(Enchantment.INFINITY, 1, true);
        m.getPersistentDataContainer().set(key, PersistentDataType.STRING, id);
        SmpBoss.modelData(m, id); // modèle du resource pack (sans le pack : l'objet vanilla normal)
        it.setItemMeta(m);
        return it;
    }

    private static final Map<String, Integer> COLORS = Map.ofEntries(
            Map.entry("toji_haut", 0x121216), Map.entry("toji_pantalon", 0xDEDACE), Map.entry("toji_bottes", 0x18161A),
            Map.entry("gojo_bandeau", 0x0C0C10), Map.entry("gojo_haut", 0x1A1E2E), Map.entry("gojo_pantalon", 0x1A1E2E), Map.entry("gojo_bottes", 0x101014),
            Map.entry("sukuna_haut", 0xEEEAE0), Map.entry("sukuna_pantalon", 0xE6E0D2), Map.entry("sukuna_bottes", 0x2A1A22));

    /** Une pièce de tenue (Toji, Gojo, Sukuna) : cuir teint (se voit sans pack), solide comme du netherite, incassable. */
    ItemStack outfitPiece(String pid) {
        String set = outfitOf(pid);
        if (set == null) {
            pid = "toji_haut";
            set = TENUE;
        }
        String base = path(set);
        String piece = pid.substring(pid.indexOf('_') + 1);
        Material mat;
        org.bukkit.inventory.EquipmentSlotGroup slot;
        double defArmor;
        switch (piece) {
            case "bandeau" -> {
                mat = Material.LEATHER_HELMET;
                slot = org.bukkit.inventory.EquipmentSlotGroup.HEAD;
                defArmor = 3;
            }
            case "pantalon" -> {
                mat = Material.LEATHER_LEGGINGS;
                slot = org.bukkit.inventory.EquipmentSlotGroup.LEGS;
                defArmor = 6;
            }
            case "bottes" -> {
                mat = Material.LEATHER_BOOTS;
                slot = org.bukkit.inventory.EquipmentSlotGroup.FEET;
                defArmor = 3;
            }
            default -> {
                mat = Material.LEATHER_CHESTPLATE;
                slot = org.bukkit.inventory.EquipmentSlotGroup.CHEST;
                defArmor = 8;
            }
        }
        ItemStack it = new ItemStack(mat);
        org.bukkit.inventory.meta.LeatherArmorMeta m = (org.bukkit.inventory.meta.LeatherArmorMeta) it.getItemMeta();
        m.setColor(org.bukkit.Color.fromRGB(COLORS.getOrDefault(pid, 0x202020)));
        m.displayName(parse(Rarity.LEGENDARY.name(plugin.msg("weapons." + base + ".pieces." + piece), 0)));
        List<Component> lore = new ArrayList<>();
        lore.add(parse(Rarity.LEGENDARY.tag));
        lore.add(Component.empty());
        for (String l : plugin.getConfig().getStringList("messages.weapons." + base + ".lore")) lore.add(parse(l));
        m.lore(lore);
        String[] attr = {"armor", "tough", "kb", "speed"};
        double[] val = {cfg(base + ".armor." + piece, defArmor), cfg(base + ".toughness", 3), cfg(base + ".knockback-resistance", 0.1),
                cfg(base + ".speed-per-piece", 0.03)};
        org.bukkit.attribute.Attribute[] at = {org.bukkit.attribute.Attribute.ARMOR, org.bukkit.attribute.Attribute.ARMOR_TOUGHNESS,
                org.bukkit.attribute.Attribute.KNOCKBACK_RESISTANCE, org.bukkit.attribute.Attribute.MOVEMENT_SPEED};
        for (int i = 0; i < at.length; i++) {
            if (val[i] == 0) continue;
            m.addAttributeModifier(at[i], new org.bukkit.attribute.AttributeModifier(new NamespacedKey(plugin, pid + "_" + attr[i]), val[i],
                    i == 3 ? org.bukkit.attribute.AttributeModifier.Operation.ADD_SCALAR : org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, slot));
        }
        m.setUnbreakable(true);
        m.setEnchantmentGlintOverride(false);
        m.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_DYE);
        m.getPersistentDataContainer().set(key, PersistentDataType.STRING, pid);
        SmpBoss.modelData(m, pid); // icône du pack
        org.bukkit.inventory.EquipmentSlot es = slot == org.bukkit.inventory.EquipmentSlotGroup.HEAD ? org.bukkit.inventory.EquipmentSlot.HEAD
                : slot == org.bukkit.inventory.EquipmentSlotGroup.LEGS ? org.bukkit.inventory.EquipmentSlot.LEGS
                : slot == org.bukkit.inventory.EquipmentSlotGroup.FEET ? org.bukkit.inventory.EquipmentSlot.FEET : org.bukkit.inventory.EquipmentSlot.CHEST;
        equipModel(m, set.replace("tenue_", ""), es); // tenue dessinée sur le corps (pack), sur le bon emplacement
        it.setItemMeta(m);
        return it;
    }

    /** Texture portée sur le corps : composant "equippable" -> limitless:<tenue> (assets/limitless/equipment/<tenue>.json). */
    private static void equipModel(ItemMeta m, String name, org.bukkit.inventory.EquipmentSlot slot) {
        try {
            Object eq = m.getClass().getMethod("getEquippable").invoke(m);
            // sans ça, le composant créé de zéro a un emplacement par défaut (la tête) : le plastron allait sur la tête
            eq.getClass().getMethod("setSlot", org.bukkit.inventory.EquipmentSlot.class).invoke(eq, slot);
            NamespacedKey k = new NamespacedKey("limitless", name);
            java.lang.reflect.Method set = null;
            for (String n : new String[]{"setAssetId", "setModel"}) {
                try {
                    set = eq.getClass().getMethod(n, NamespacedKey.class);
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (set == null) return;
            set.invoke(eq, k);
            for (java.lang.reflect.Method mm : m.getClass().getMethods()) {
                if (mm.getName().equals("setEquippable") && mm.getParameterCount() == 1) {
                    mm.invoke(m, eq);
                    break;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }
    }

    /** Toutes les pièces de cette tenue sont portées ? */
    boolean wearsOutfit(Player p, String set) {
        String[] need = OUTFITS.get(set);
        if (need == null) return false;
        int n = 0;
        for (ItemStack a : p.getInventory().getArmorContents()) {
            String id = id(a);
            if (id != null && set.equals(outfitOf(id))) n++;
        }
        return n >= need.length;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 10) / 10.0);
    }

    WeaponFx fx() {
        return ultimates.weaponFx;
    }

    String id(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private boolean ready(Player p, String id, double seconds) {
        // relique "Restriction Céleste" : aucune arme à part la Lance du Ciel Inversé
        if (TojiSpear.itemLocked(p) && !TojiSpear.ID.equals(id)) {
            SmpBoss.hud(p, parse(plugin.msg("lance-ciel.heavenly-lock")));
            return false;
        }
        String k = p.getUniqueId() + ":" + id;
        long now = System.currentTimeMillis();
        Long until = cooldown.get(k);
        if (until != null && until > now) {
            SmpBoss.hud(p, parse(plugin.msg("weapons.cooldown").replace("<seconds>", fmt(Math.ceil((until - now) / 100.0) / 10.0))));
            return false;
        }
        cooldown.put(k, now + (long) (seconds * 1000));
        return true;
    }

    // ================================================================= Sonic Bow

    /** Met à jour une arme déjà donnée (nouveaux dégâts, nouvelle description), en gardant ses enchantements. */
    private void refresh(org.bukkit.inventory.Inventory inv) {
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            String id = id(it);
            if (id == null) continue;
            ItemStack fresh = create(id);
            ItemMeta fm = fresh.getItemMeta();
            for (Map.Entry<Enchantment, Integer> en : it.getEnchantments().entrySet()) fm.addEnchant(en.getKey(), en.getValue(), true);
            if (!fm.equals(it.getItemMeta())) {
                fresh.setItemMeta(fm);
                inv.setItem(i, fresh);
            }
        }
    }

    @EventHandler
    public void onQuitRooted(org.bukkit.event.player.PlayerQuitEvent e) {
        ultimates.releaseNow(e.getPlayer());
    }

    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) refresh(p.getInventory());
        }, 20L);
    }

    @EventHandler
    public void onHeld(org.bukkit.event.player.PlayerItemHeldEvent e) {
        ItemStack it = e.getPlayer().getInventory().getItem(e.getNewSlot());
        if (id(it) != null) Bukkit.getScheduler().runTask(plugin, () -> refresh(e.getPlayer().getInventory()));
    }

    @EventHandler
    public void onDraw(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (SONIC.equals(id(e.getItem()))) drawStart.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p) || !SONIC.equals(id(e.getBow()))) return;
        e.setCancelled(true); // pas de flèche : le rayon la remplace
        Bukkit.getScheduler().runTask(plugin, p::updateInventory);
        Long start = drawStart.remove(p.getUniqueId());
        long held = start == null ? 0 : System.currentTimeMillis() - start;
        // l'arc doit être tendu au maximum (1 s)
        if (held < cfg("sonic-bow.full-charge-ms", 950) || e.getForce() < 0.95f) {
            SmpBoss.hud(p, parse(plugin.msg("weapons.sonic-bow.not-charged")));
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_SCULK_SENSOR_CLICKING_STOP, 1f, 0.6f);
            return;
        }
        if (!ready(p, SONIC, cfg("sonic-bow.cooldown-seconds", 10))) return;
        sonic(p);
    }

    /** Nouveau style : une onde sonique violette qui file à toute vitesse, anneaux d'onde le long du rayon. */
    private void sonic(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World w = p.getWorld();
        double range = cfg("sonic-bow.range", 96);
        RayTraceResult wall = w.rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
        double len = wall == null ? range : wall.getHitPosition().distance(eye.toVector());
        double dmg = cfg("sonic-bow.damage", 10);
        Boss k = plugin.king();
        w.playSound(eye, org.bukkit.Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.2f, 1.8f);
        w.playSound(eye, org.bukkit.Sound.ENTITY_WARDEN_SONIC_BOOM, 3f, 1.2f);
        w.playSound(eye, org.bukkit.Sound.BLOCK_BEACON_POWER_SELECT, 1.2f, 2f);
        fx().sonic(eye, dir, len);
        Set<UUID> hit = new HashSet<>();
        Particle.DustOptions violet = new Particle.DustOptions(Color.fromRGB(170, 90, 255), 1.5f);
        Particle.DustOptions pale = new Particle.DustOptions(Color.fromRGB(235, 215, 255), 1.0f);
        // repère perpendiculaire au rayon (pour dessiner les anneaux)
        Vector side = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 0.01) side = new Vector(1, 0, 0);
        side.normalize();
        Vector up = side.clone().crossProduct(dir).normalize();
        Vector sd = side, ud = up;
        // l'onde avance de 12 blocs par tick (on la voit partir)
        double[] d0 = {1};
        org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            double to = Math.min(len, d0[0] + 12);
            for (double d = d0[0]; d <= to; d += 0.5) {
                Location at = eye.clone().add(dir.clone().multiply(d));
                w.spawnParticle(Particle.END_ROD, at, 1, 0, 0, 0, 0, null, true);
                w.spawnParticle(Particle.DUST, at, 1, 0.06, 0.06, 0.06, 0, violet, true);
                // anneau d'onde tous les 3 blocs, de plus en plus large
                if (Math.abs(d % 3) < 0.25) {
                    double rr = 0.45 + Math.min(1.2, d / 60.0);
                    for (int n = 0; n < 14; n++) {
                        double a = Math.PI * 2 * n / 14;
                        Location r = at.clone().add(sd.clone().multiply(Math.cos(a) * rr)).add(ud.clone().multiply(Math.sin(a) * rr));
                        w.spawnParticle(Particle.DUST, r, 1, 0, 0, 0, 0, n % 2 == 0 ? violet : pale, true);
                    }
                }
                if (Math.abs(d % 8) < 0.25) w.spawnParticle(Particle.SONIC_BOOM, at, 1, 0, 0, 0, 0, null, true);
                for (Entity en : w.getNearbyEntities(at, 0.9, 0.9, 0.9)) {
                    if (!(en instanceof LivingEntity le) || en.equals(p) || !hit.add(en.getUniqueId())) continue;
                    if (en instanceof Player t && (t.getGameMode() == org.bukkit.GameMode.SPECTATOR || t.getGameMode() == org.bukkit.GameMode.CREATIVE)) continue;
                    le.setNoDamageTicks(0);
                    le.damage(dmg, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.SONIC_BOOM)
                            .withCausingEntity(p).withDirectEntity(p).build());
                    if (k == null || !k.isBoss(en)) le.setVelocity(le.getVelocity().add(dir.clone().multiply(0.9).setY(0.3)));
                    w.spawnParticle(Particle.END_ROD, le.getLocation().add(0, 1, 0), 25, 0.2, 0.4, 0.2, 0.2, null, true);
                }
            }
            d0[0] = to + 0.5;
            if (d0[0] > len) {
                task[0].cancel();
                Location end = eye.clone().add(dir.clone().multiply(len));
                w.spawnParticle(Particle.END_ROD, end, 40, 0.1, 0.1, 0.1, 0.25, null, true);
                w.spawnParticle(Particle.DUST, end, 30, 0.6, 0.6, 0.6, 0, violet, true);
                if (wall != null) w.playSound(end, org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.5f, 0.6f);
            }
        }, 0L, 1L);
    }

    // ================================================================= Tranche Mace

    @EventHandler(priority = EventPriority.HIGH)
    public void onSlash(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND || !TRANCHE.equals(id(e.getItem()))) return;
        Block b = e.getClickedBlock();
        if (b != null && b.getType().isInteractable() && !e.getPlayer().isSneaking()) return; // ouvrir un coffre, une porte...
        Player p = e.getPlayer();
        if (!ready(p, TRANCHE, cfg("tranche-mace.cooldown-seconds", 8))) return;
        e.setCancelled(true);
        slash(p);
    }

    private void slash(Player p) {
        World w = p.getWorld();
        // la lame part exactement là où on vise (vers le sol, vers le ciel...)
        Location start = p.getEyeLocation().add(0, -0.3, 0);
        Vector fwd = start.getDirection().normalize();
        // axe de la lame : la "verticale" perpendiculaire à la visée
        Vector up = new Vector(0, 1, 0).subtract(fwd.clone().multiply(fwd.getY()));
        if (up.lengthSquared() < 0.01) {
            // visée droit vers le haut / le bas : la lame se met dans l'axe du regard horizontal
            double yaw = Math.toRadians(p.getLocation().getYaw());
            up = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        }
        Vector axis = up.normalize();
        int steps = (int) cfg("tranche-mace.length", 18);
        double dmg = cfg("tranche-mace.damage", 12);
        int lev = (int) (cfg("tranche-mace.levitation-seconds", 5) * 20);
        Boss k = plugin.king();
        Set<UUID> hit = new HashSet<>();
        // nouveau style : lame rouge sang au tranchant blanc, bords noirs, traînée de cendres
        Particle.DustOptions blood = new Particle.DustOptions(Color.fromRGB(200, 10, 30), 1.6f);
        Particle.DustOptions edge = new Particle.DustOptions(Color.fromRGB(255, 235, 235), 1.0f);
        Particle.DustOptions black = new Particle.DustOptions(Color.fromRGB(25, 0, 5), 1.8f);
        w.playSound(start, org.bukkit.Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.6f, 0.5f);
        w.playSound(start, org.bukkit.Sound.ITEM_TRIDENT_RIPTIDE_3, 1f, 1.2f);
        w.playSound(start, org.bukkit.Sound.ENTITY_BLAZE_SHOOT, 0.6f, 0.6f);
        fx().slash(start, fwd, axis, steps);
        int[] i = {0};
        org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (i[0] >= steps || !p.isOnline()) {
                task[0].cancel();
                return;
            }
            Location c = start.clone().add(fwd.clone().multiply(1.5 + i[0] * 1.1));
            if (c.getBlock().getType().isSolid()) {
                // elle s'écrase sur le sol / le mur : fissure + éclats, et touche ce qui est juste là
                w.spawnParticle(Particle.BLOCK, c, 40, 0.6, 0.3, 0.6, 0, c.getBlock().getBlockData(), true);
                w.spawnParticle(Particle.SWEEP_ATTACK, c.clone().add(0, 0.5, 0), 3, 0.6, 0.2, 0.6, 0, null, true);
                w.playSound(c, org.bukkit.Sound.ITEM_MACE_SMASH_GROUND, 1f, 1.3f);
                for (Entity en : w.getNearbyEntities(c, 1.6, 1.6, 1.6)) touch(p, en, hit, dmg, lev, k);
                task[0].cancel();
                return;
            }
            // la lame : un croissant vertical de 4 blocs de haut
            for (double t = -1; t <= 1.0001; t += 0.08) {
                Location pt = c.clone().add(axis.clone().multiply(t * 2.2)).add(fwd.clone().multiply(-0.8 * t * t));
                w.spawnParticle(Particle.DUST, pt, 1, 0.02, 0.02, 0.02, 0, Math.abs(t) > 0.85 ? black : blood, true);
                // tranchant blanc à l'avant de la lame
                w.spawnParticle(Particle.DUST, pt.clone().add(fwd.clone().multiply(0.25)), 1, 0, 0, 0, 0, edge, true);
                if (i[0] % 2 == 0 && Math.abs(t) < 0.05) w.spawnParticle(Particle.SWEEP_ATTACK, pt, 1, 0, 0, 0, 0, null, true);
            }
            // traînée de cendres derrière la lame
            Location back = c.clone().add(fwd.clone().multiply(-0.9));
            w.spawnParticle(Particle.CRIMSON_SPORE, back, 6, 0.2, 1.0, 0.2, 0, null, true);
            if (i[0] % 2 == 0) w.spawnParticle(Particle.LARGE_SMOKE, back, 2, 0.1, 0.8, 0.1, 0.01, null, true);
            // la lame touche tout ce qui est sur sa longueur (4 blocs)
            for (double t = -1; t <= 1.0001; t += 0.5) {
                Location pt = c.clone().add(axis.clone().multiply(t * 2.0));
                for (Entity en : w.getNearbyEntities(pt, 0.9, 0.9, 0.9)) touch(p, en, hit, dmg, lev, k);
            }
            i[0]++;
        }, 0L, 1L);
    }

    private void touch(Player p, Entity en, Set<UUID> hit, double dmg, int lev, Boss k) {
        if (!(en instanceof LivingEntity le) || en.equals(p) || !hit.add(en.getUniqueId())) return;
        if (en instanceof Player t && (t.getGameMode() == org.bukkit.GameMode.SPECTATOR || t.getGameMode() == org.bukkit.GameMode.CREATIVE)) return;
        le.setNoDamageTicks(0);
        le.damage(dmg, p);
        if (k != null && k.isBoss(en)) return;
        le.setFallDistance(0); // un joueur qui tombait à la masse perd son élan
        le.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, lev, (int) cfg("tranche-mace.levitation-level", 1) - 1, false, true, true));
        World w = le.getWorld();
        w.spawnParticle(Particle.DUST, le.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0, new Particle.DustOptions(Color.fromRGB(200, 10, 30), 1.5f), true);
        w.spawnParticle(Particle.CRIMSON_SPORE, le.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0, null, true);
        w.spawnParticle(Particle.CLOUD, le.getLocation(), 10, 0.3, 0.1, 0.3, 0.05, null, true);
        w.playSound(le.getLocation(), org.bukkit.Sound.ENTITY_BREEZE_JUMP, 1f, 1.0f);
        w.playSound(le.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.7f);
        fx().slashHit(le.getLocation().add(0, 1, 0));
    }

    // ================================================================= Brise-Terre (hache) : FAILLE + SÉISME

    /** Coups à la hache comptés pour le séisme (3e coup en moins de 4 s). */
    private final Map<UUID, long[]> combo = new HashMap<>();

    /**
     * Clic droit : FAILLE. La terre s'ouvre devant toi sur 24 blocs : des pics de pierre jaillissent du sol
     * l'un après l'autre et projettent en l'air ce qu'ils touchent ; une seconde plus tard, toute la ligne
     * explose une 2e fois (réplique).
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRift(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND || !BRISE.equals(id(e.getItem()))) return;
        Block b = e.getClickedBlock();
        if (b != null && b.getType().isInteractable() && !e.getPlayer().isSneaking()) return;
        Player p = e.getPlayer();
        e.setCancelled(true); // pas d'écorçage de bûche
        if (!ready(p, BRISE, cfg("brise-terre.cooldown-seconds", 7))) return;
        rift(p);
    }

    private void rift(Player p) {
        World w = p.getWorld();
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 0.01) dir = new Vector(0, 0, 1);
        dir.normalize();
        Vector fwd = dir;
        int len = (int) cfg("brise-terre.rift-length", 24);
        double dmg = cfg("brise-terre.damage", 16), after = cfg("brise-terre.aftershock-damage", 8);
        Boss k = plugin.king();
        Location origin = p.getLocation();
        List<Location> line = new ArrayList<>();
        Set<UUID> hit = new HashSet<>();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        w.playSound(origin, org.bukkit.Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.6f, 0.6f);
        w.playSound(origin, org.bukkit.Sound.ENTITY_RAVAGER_ROAR, 0.8f, 0.6f);
        fx().rift(origin, fwd, len);
        int[] i = {0};
        org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (int rep = 0; rep < 2; rep++) {
                if (i[0] >= len) {
                    task[0].cancel();
                    aftershock(p, line, after);
                    return;
                }
                i[0]++;
                Location at = origin.clone().add(fwd.clone().multiply(1.5 + i[0]));
                // suit le relief
                Location g = groundAt(at);
                if (g == null) {
                    task[0].cancel();
                    aftershock(p, line, after);
                    return;
                }
                line.add(g);
                org.bukkit.block.data.BlockData bd = g.clone().add(0, -1, 0).getBlock().getBlockData();
                if (bd.getMaterial().isAir() || !bd.getMaterial().isSolid()) bd = Material.STONE.createBlockData();
                spike(g, bd, 1.4 + rnd.nextDouble(1.2));
                if (i[0] % 2 == 0) {
                    Vector side = new Vector(-fwd.getZ(), 0, fwd.getX());
                    Location g2 = groundAt(g.clone().add(side.multiply(rnd.nextBoolean() ? 1.2 : -1.2)));
                    if (g2 != null) spike(g2, bd, 0.8 + rnd.nextDouble(0.8));
                }
                w.spawnParticle(Particle.BLOCK, g.clone().add(0, 0.3, 0), 18, 0.4, 0.2, 0.4, 0, bd, true);
                w.spawnParticle(Particle.DUST, g.clone().add(0, 0.1, 0), 6, 0.3, 0.05, 0.3, 0, new Particle.DustOptions(Color.fromRGB(255, 100, 10), 1.5f), true);
                if (i[0] % 3 == 0) w.playSound(g, org.bukkit.Sound.BLOCK_POINTED_DRIPSTONE_LAND, 1.4f, 0.6f);
                for (Entity en : w.getNearbyEntities(g, 1.6, 2.5, 1.6)) {
                    if (!(en instanceof LivingEntity le) || en.equals(p) || !hit.add(en.getUniqueId())) continue;
                    if (en instanceof Player t && (t.getGameMode() == org.bukkit.GameMode.SPECTATOR || t.getGameMode() == org.bukkit.GameMode.CREATIVE)) continue;
                    le.setNoDamageTicks(0);
                    le.damage(dmg, p);
                    if (k != null && k.isBoss(en)) continue;
                    le.setVelocity(new Vector(fwd.getX() * 0.3, 1.25, fwd.getZ() * 0.3));
                    le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 2, false, true, true));
                }
            }
        }, 0L, 1L);
    }

    /** Sol sous (ou juste au-dessus de) cette position, sinon null (vide / mur). */
    private static Location groundAt(Location l) {
        Location c = l.clone();
        c.setY(Math.floor(c.getY()));
        for (int k = 0; k < 4 && !c.getBlock().getType().isAir(); k++) c.add(0, 1, 0);
        if (!c.getBlock().getType().isAir()) return null;
        for (int k = 0; k < 5 && c.clone().add(0, -1, 0).getBlock().getType().isAir(); k++) c.add(0, -1, 0);
        if (c.clone().add(0, -1, 0).getBlock().getType().isAir()) return null;
        return c;
    }

    /** Un pic de pierre qui jaillit du sol puis y retourne. */
    private void spike(Location g, org.bukkit.block.data.BlockData bd, double height) {
        World w = g.getWorld();
        float h = (float) height;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        float tilt = (float) rnd.nextDouble(-0.25, 0.25);
        org.bukkit.entity.BlockDisplay sp = w.spawn(g.clone().add(rnd.nextDouble(-0.2, 0.2), -h, rnd.nextDouble(-0.2, 0.2)),
                org.bukkit.entity.BlockDisplay.class, d -> {
                    d.setPersistent(false);
                    d.setBlock(bd);
                    d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(-0.35f, 0, -0.35f),
                            new org.joml.Quaternionf().rotateY(rnd.nextFloat() * 3).rotateX(tilt), new org.joml.Vector3f(0.7f, h, 0.7f),
                            new org.joml.Quaternionf()));
                });
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!sp.isValid()) return;
            sp.setInterpolationDelay(0);
            sp.setInterpolationDuration(3);
            org.bukkit.util.Transformation tr = sp.getTransformation();
            sp.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(-0.35f, h, -0.35f), tr.getLeftRotation(), tr.getScale(), tr.getRightRotation()));
        }, 1L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!sp.isValid()) return;
            sp.setInterpolationDelay(0);
            sp.setInterpolationDuration(8);
            org.bukkit.util.Transformation tr = sp.getTransformation();
            sp.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(-0.35f, 0, -0.35f), tr.getLeftRotation(), tr.getScale(), tr.getRightRotation()));
        }, 22L);
        Bukkit.getScheduler().runTaskLater(plugin, sp::remove, 31L);
    }

    /** Réplique : 1 s plus tard, toute la faille explose une 2e fois. */
    private void aftershock(Player p, List<Location> line, double dmg) {
        if (line.isEmpty()) return;
        Boss k = plugin.king();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Set<UUID> hit = new HashSet<>();
            World w = line.get(0).getWorld();
            w.playSound(line.get(line.size() / 2), org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 0.7f);
            fx().aftershock(line.get(line.size() / 2));
            for (int n = 0; n < line.size(); n += 2) {
                Location g = line.get(n);
                w.spawnParticle(Particle.EXPLOSION, g.clone().add(0, 0.5, 0), 1, 0, 0, 0, 0, null, true);
                w.spawnParticle(Particle.LAVA, g, 3, 0.4, 0.1, 0.4, 0, null, true);
                w.spawnParticle(Particle.FLAME, g, 8, 0.4, 0.3, 0.4, 0.03, null, true);
                for (Entity en : w.getNearbyEntities(g, 2, 2.5, 2)) {
                    if (!(en instanceof LivingEntity le) || en.equals(p) || !hit.add(en.getUniqueId())) continue;
                    if (en instanceof Player t && (t.getGameMode() == org.bukkit.GameMode.SPECTATOR || t.getGameMode() == org.bukkit.GameMode.CREATIVE)) continue;
                    le.setNoDamageTicks(0);
                    le.damage(dmg, p);
                    le.setFireTicks(80);
                }
            }
        }, 20L);
    }

    /** SÉISME : le 3e coup de hache en moins de 4 s fait trembler la terre autour de la cible. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAxeHit(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || e.getCause() != org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!BRISE.equals(id(p.getInventory().getItemInMainHand()))) return;
        long now = System.currentTimeMillis();
        long[] c = combo.computeIfAbsent(p.getUniqueId(), x -> new long[2]);
        if (now - c[1] > 4000) c[0] = 0;
        c[1] = now;
        if (++c[0] < 3) return;
        c[0] = 0;
        Location at = e.getEntity().getLocation();
        World w = at.getWorld();
        double dmg = cfg("brise-terre.quake-damage", 10);
        Boss k = plugin.king();
        w.playSound(at, org.bukkit.Sound.ITEM_MACE_SMASH_GROUND, 1.4f, 0.6f);
        w.spawnParticle(Particle.EXPLOSION, at, 2, 0.5, 0.1, 0.5, 0, null, true);
        org.bukkit.block.data.BlockData bd = at.clone().add(0, -1, 0).getBlock().getBlockData();
        if (bd.getMaterial().isAir()) bd = Material.STONE.createBlockData();
        for (int n = 0; n < 24; n++) {
            double a = Math.PI * 2 * n / 24;
            w.spawnParticle(Particle.BLOCK, at.clone().add(Math.cos(a) * 3, 0.2, Math.sin(a) * 3), 3, 0.1, 0.1, 0.1, 0, bd, true);
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Entity en : w.getNearbyEntities(at, 4, 2, 4)) {
                if (!(en instanceof LivingEntity le) || en.equals(p)) continue;
                if (en instanceof Player t && (t.getGameMode() == org.bukkit.GameMode.SPECTATOR || t.getGameMode() == org.bukkit.GameMode.CREATIVE)) continue;
                le.setNoDamageTicks(0);
                le.damage(dmg, p);
                if (k != null && k.isBoss(en)) continue;
                Vector kb = en.getLocation().toVector().subtract(at.toVector()).setY(0);
                if (kb.lengthSquared() < 0.01) kb = new Vector(0.1, 0, 0);
                le.setVelocity(kb.normalize().multiply(0.8).setY(0.6));
            }
        });
        SmpBoss.hud(p, parse("<gold><b>⚡ SÉISME"));
    }

    // ================================================================= Faux des Âmes : LANCE D'ÂMES + MARQUE + PLUIE

    /** entité marquée -> fin de la marque (ms) */
    private final Map<UUID, Long> marked = new HashMap<>();

    /**
     * Clic droit : LANCE D'ÂMES. Une lance traverse jusqu'à 3 cibles en ligne droite et les MARQUE 6 s :
     * une cible marquée prend +50 % de dégâts de partout. Toucher une cible déjà marquée la fait EXPLOSER
     * (dégâts autour, et tu récupères de la vie) — à la lance OU d'un coup de faux.
     * Accroupi + clic droit : PLUIE D'ÂMES. Une pluie de flèches d'âmes tombe 3 s là où tu vises.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onScythe(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND || !SPECTRAL.equals(id(e.getItem()))) return;
        Player p = e.getPlayer();
        Block b = e.getClickedBlock();
        if (b != null && b.getType().isInteractable() && !p.isSneaking()) return;
        e.setCancelled(true); // pas de labour
        boolean rain = p.isSneaking();
        if (rain ? !ready(p, SPECTRAL + "_rain", cfg("arbalete-spectrale.rain-cooldown-seconds", 15))
                : !ready(p, SPECTRAL, cfg("arbalete-spectrale.cooldown-seconds", 2))) return;
        p.swingMainHand();
        if (rain) soulRain(p);
        else soulLance(p);
    }

    /** Coup de faux sur une cible marquée : son âme explose. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onScytheHit(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || e.getCause() != org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(e.getEntity() instanceof LivingEntity le) || !SPECTRAL.equals(id(p.getInventory().getItemInMainHand()))) return;
        if (!isMarked(le)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (le.isValid() && !le.isDead() && isMarked(le)) detonate(p, le);
        });
    }

    private boolean isMarked(Entity en) {
        Long until = marked.get(en.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    private void soulLance(Player p) {
        World w = p.getWorld();
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        double range = cfg("arbalete-spectrale.range", 40), dmg = cfg("arbalete-spectrale.damage", 14);
        RayTraceResult wall = w.rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
        double len = wall == null ? range : wall.getHitPosition().distance(eye.toVector());
        Boss k = plugin.king();
        w.playSound(eye, org.bukkit.Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.6f);
        w.playSound(eye, org.bukkit.Sound.PARTICLE_SOUL_ESCAPE, 2f, 0.6f);
        fx().soulLance(eye, dir, len);
        Particle.DustOptions teal = new Particle.DustOptions(Color.fromRGB(70, 235, 220), 1.3f);
        Set<UUID> hit = new HashSet<>();
        int pierced = 0;
        for (double d = 0.8; d <= len && pierced < 3; d += 0.4) {
            Location at = eye.clone().add(dir.clone().multiply(d));
            w.spawnParticle(Particle.SOUL_FIRE_FLAME, at, 1, 0.02, 0.02, 0.02, 0, null, true);
            if (((int) (d * 10)) % 8 == 0) w.spawnParticle(Particle.DUST, at, 1, 0.05, 0.05, 0.05, 0, teal, true);
            for (Entity en : w.getNearbyEntities(at, 0.6, 0.6, 0.6)) {
                if (!(en instanceof LivingEntity le) || en.equals(p) || !hit.add(en.getUniqueId())) continue;
                if (en instanceof Player t && (t.getGameMode() == org.bukkit.GameMode.SPECTATOR || t.getGameMode() == org.bukkit.GameMode.CREATIVE)) continue;
                pierced++;
                boolean wasMarked = isMarked(en);
                le.setNoDamageTicks(0);
                le.damage(dmg, p);
                if (wasMarked) detonate(p, le);
                else if (k == null || !k.isBoss(en)) {
                    marked.put(en.getUniqueId(), System.currentTimeMillis() + (long) (cfg("arbalete-spectrale.mark-seconds", 6) * 1000));
                    le.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, (int) (cfg("arbalete-spectrale.mark-seconds", 6) * 20), 0, false, false, true));
                    le.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 80, 1, false, true, true));
                    markFx(le);
                    fx().markHit(le.getLocation().add(0, 1, 0));
                    w.playSound(le.getLocation(), org.bukkit.Sound.ENTITY_VEX_CHARGE, 1f, 0.7f);
                }
            }
        }
    }

    /** Marque visible : un anneau d'âmes tourne autour de la cible tant qu'elle est marquée. */
    private void markFx(LivingEntity le) {
        int[] t = {0};
        org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            t[0] += 2;
            if (!le.isValid() || le.isDead() || !isMarked(le)) {
                task[0].cancel();
                return;
            }
            Location c = le.getLocation().add(0, le.getHeight() + 0.35, 0);
            for (int n = 0; n < 3; n++) {
                double a = t[0] * 0.25 + Math.PI * 2 * n / 3;
                le.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, c.clone().add(Math.cos(a) * 0.5, 0, Math.sin(a) * 0.5), 1, 0, 0, 0, 0, null, true);
            }
        }, 0L, 2L);
    }

    /** Une cible marquée touchée à nouveau : son âme explose. */
    private void detonate(Player p, LivingEntity le) {
        marked.remove(le.getUniqueId());
        World w = le.getWorld();
        Location c = le.getLocation().add(0, 1, 0);
        double dmg = cfg("arbalete-spectrale.burst-damage", 16), heal = cfg("arbalete-spectrale.burst-heal", 8);
        Boss k = plugin.king();
        w.playSound(c, org.bukkit.Sound.ENTITY_WARDEN_SONIC_BOOM, 1f, 1.6f);
        w.playSound(c, org.bukkit.Sound.ENTITY_WITHER_BREAK_BLOCK, 0.6f, 1.4f);
        fx().detonate(c);
        w.spawnParticle(Particle.SOUL, c, 60, 0.6, 0.8, 0.6, 0.12, null, true);
        w.spawnParticle(Particle.SCULK_SOUL, c, 40, 0.5, 0.6, 0.5, 0.12, null, true);
        w.spawnParticle(Particle.SOUL_FIRE_FLAME, c, 80, 0.2, 0.2, 0.2, 0.25, null, true);
        for (int n = 0; n < 36; n++) {
            double a = Math.PI * 2 * n / 36;
            w.spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * 3.5, -0.6, Math.sin(a) * 3.5), 1, 0, 0, 0, 0,
                    new Particle.DustOptions(Color.fromRGB(70, 235, 220), 1.6f), true);
        }
        le.setNoDamageTicks(0);
        le.damage(dmg, p);
        for (Entity en : w.getNearbyEntities(c, 3.5, 3, 3.5)) {
            if (!(en instanceof LivingEntity o) || en.equals(p) || en.equals(le)) continue;
            if (en instanceof Player t && (t.getGameMode() == org.bukkit.GameMode.SPECTATOR || t.getGameMode() == org.bukkit.GameMode.CREATIVE)) continue;
            o.setNoDamageTicks(0);
            o.damage(dmg * 0.6, p);
            if (k == null || !k.isBoss(en)) o.setVelocity(en.getLocation().toVector().subtract(c.toVector()).setY(0).normalize().multiply(0.8).setY(0.5));
        }
        var max = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        p.setHealth(Math.min(max == null ? 20 : max.getValue(), p.getHealth() + heal));
        SmpBoss.hud(p, parse("<aqua><b>☠ ÂME BRISÉE</b> <gray>+" + fmt(heal / 2) + " ❤"));
    }

    /** +50 % de dégâts sur une cible marquée, quelle que soit la source. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMarkedHurt(org.bukkit.event.entity.EntityDamageEvent e) {
        if (marked.isEmpty() || !isMarked(e.getEntity())) return;
        e.setDamage(e.getDamage() * cfg("arbalete-spectrale.mark-bonus", 1.5));
    }

    /** Accroupi : une pluie de flèches d'âmes tombe pendant 3 s là où tu vises. */
    private void soulRain(Player p) {
        World w = p.getWorld();
        RayTraceResult rt = w.rayTraceBlocks(p.getEyeLocation(), p.getEyeLocation().getDirection(), cfg("arbalete-spectrale.rain-range", 40),
                FluidCollisionMode.NEVER, true);
        Location c = rt == null ? p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(20)) : rt.getHitPosition().toLocation(w);
        Location ground = groundAt(c.clone().add(0, 1, 0));
        Location center = ground == null ? c : ground;
        double r = cfg("arbalete-spectrale.rain-radius", 5), dmg = cfg("arbalete-spectrale.rain-damage", 4);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Boss k = plugin.king();
        w.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EVOKER_CAST_SPELL, 1f, 0.6f);
        w.playSound(center, org.bukkit.Sound.ENTITY_ALLAY_ITEM_THROWN, 2f, 0.5f);
        fx().soulRain(center, r, 30);
        int[] t = {0};
        org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (++t[0] > 30) {
                task[0].cancel();
                return;
            }
            // cercle au sol
            for (int n = 0; n < 24; n++) {
                double a = Math.PI * 2 * n / 24 + t[0] * 0.05;
                w.spawnParticle(Particle.SOUL_FIRE_FLAME, center.clone().add(Math.cos(a) * r, 0.1, Math.sin(a) * r), 1, 0, 0, 0, 0, null, true);
            }
            // flèches d'âmes qui tombent
            for (int n = 0; n < 6; n++) {
                double a = rnd.nextDouble(Math.PI * 2), d = Math.sqrt(rnd.nextDouble()) * r;
                Location top = center.clone().add(Math.cos(a) * d, 10, Math.sin(a) * d);
                for (double y = 0; y < 10; y += 0.8) w.spawnParticle(Particle.SOUL, top.clone().add(0, -y, 0), 1, 0, 0, 0, 0, null, true);
                w.spawnParticle(Particle.SCULK_SOUL, top.clone().add(0, -10, 0), 3, 0.1, 0.1, 0.1, 0.03, null, true);
            }
            if (t[0] % 5 == 0) {
                w.playSound(center, org.bukkit.Sound.ENTITY_ARROW_HIT, 1.5f, 0.6f);
                for (Entity en : w.getNearbyEntities(center, r, 4, r)) {
                    if (!(en instanceof LivingEntity le) || en.equals(p) || en.getLocation().distanceSquared(center) > r * r) continue;
                    if (en instanceof Player pl && (pl.getGameMode() == org.bukkit.GameMode.SPECTATOR || pl.getGameMode() == org.bukkit.GameMode.CREATIVE)) continue;
                    le.setNoDamageTicks(0);
                    le.damage(dmg, p);
                    if (k == null || !k.isBoss(en)) {
                        le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 25, 1, false, true, true));
                        if (!isMarked(en)) {
                            marked.put(en.getUniqueId(), System.currentTimeMillis() + (long) (cfg("arbalete-spectrale.mark-seconds", 6) * 1000));
                            le.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, (int) (cfg("arbalete-spectrale.mark-seconds", 6) * 20), 0, false, false, true));
                            markFx(le);
                        }
                    }
                }
            }
        }, 0L, 2L);
    }

    // ================================================================= armes ÉPIQUES

    private boolean hittable(Player p, Entity en) {
        if (!(en instanceof LivingEntity le) || en.equals(p) || le.isDead()) return false;
        if (en.getScoreboardTags().contains("smp_pet") || en instanceof org.bukkit.entity.ArmorStand) return false;
        return !(en instanceof Player t && (t.getGameMode() == org.bukkit.GameMode.SPECTATOR || t.getGameMode() == org.bukkit.GameMode.CREATIVE));
    }

    /** Lame Citrouille : clic droit = entaille de feu en arc devant toi (5 blocs). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPumpkinSlash(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND || !LAME.equals(id(e.getItem()))) return;
        Block b = e.getClickedBlock();
        if (b != null && b.getType().isInteractable() && !e.getPlayer().isSneaking()) return;
        Player p = e.getPlayer();
        if (!ready(p, LAME, cfg("lame-citrouille.cooldown-seconds", 10))) return;
        World w = p.getWorld();
        Vector f = p.getEyeLocation().getDirection().setY(0);
        if (f.lengthSquared() < 1e-4) f = new Vector(0, 0, 1);
        f.normalize();
        double reach = cfg("lame-citrouille.reach", 5), dmg = cfg("lame-citrouille.damage", 6);
        int fire = (int) (cfg("lame-citrouille.fire-seconds", 3) * 20);
        Particle.DustOptions orange = new Particle.DustOptions(Color.fromRGB(255, 130, 20), 1.4f);
        for (int i = -6; i <= 6; i++) {
            Vector d = f.clone().rotateAroundY(Math.toRadians(i * 9));
            for (double r = 1.5; r <= reach; r += 0.7) {
                Location at = p.getLocation().add(0, 1.1, 0).add(d.clone().multiply(r));
                if (r > reach - 1) w.spawnParticle(Particle.FLAME, at, 1, 0.05, 0.05, 0.05, 0);
                else w.spawnParticle(Particle.DUST, at, 1, 0.05, 0.05, 0.05, 0, orange);
            }
        }
        w.spawnParticle(Particle.SWEEP_ATTACK, p.getLocation().add(f.clone().multiply(2)).add(0, 1.1, 0), 3, 0.8, 0.2, 0.8, 0);
        w.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.2f, 0.8f);
        w.playSound(p.getLocation(), org.bukkit.Sound.ITEM_FIRECHARGE_USE, 1f, 1.2f);
        fx().pumpkinSlash(p.getLocation(), f, reach);
        for (Entity en : w.getNearbyEntities(p.getLocation(), reach, 2.5, reach)) {
            if (!hittable(p, en)) continue;
            Vector to = en.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
            if (to.lengthSquared() > reach * reach || to.lengthSquared() < 1e-4 || to.normalize().dot(f) < 0.5) continue;
            LivingEntity le = (LivingEntity) en;
            le.setNoDamageTicks(0);
            le.damage(dmg, p);
            le.setFireTicks(Math.max(le.getFireTicks(), fire));
            w.spawnParticle(Particle.FLAME, le.getLocation().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.03);
        }
    }

    /** Arc des Feux Follets : tir tendu = 2 flèches fantômes en plus, en éventail. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWispShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p) || !FOLLETS.equals(id(e.getBow())) || e.getForce() < 0.9f) return;
        if (!(e.getProjectile() instanceof org.bukkit.entity.AbstractArrow main)) return;
        String k = p.getUniqueId() + ":" + FOLLETS;
        Long until = cooldown.get(k);
        long now = System.currentTimeMillis();
        if (until != null && until > now) return; // la flèche normale part quand même
        cooldown.put(k, now + (long) (cfg("arc-feux-follets.cooldown-seconds", 6) * 1000));
        double ratio = cfg("arc-feux-follets.damage", 70) / 100.0;
        Vector v = main.getVelocity();
        List<Entity> wisps = new ArrayList<>(List.of(main));
        for (int side = -1; side <= 1; side += 2) {
            Vector dir = v.clone().rotateAroundY(Math.toRadians(8 * side));
            org.bukkit.entity.Arrow a = p.getWorld().spawnArrow(main.getLocation(), dir, (float) v.length(), 0f);
            a.setShooter(p);
            a.setDamage(main.getDamage() * ratio);
            a.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
            a.setCritical(main.isCritical());
            a.setGlowing(true);
            a.setFireTicks(main.getFireTicks());
            wisps.add(a);
        }
        p.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, p.getEyeLocation().add(p.getLocation().getDirection()), 12, 0.3, 0.3, 0.3, 0.02);
        p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.PARTICLE_SOUL_ESCAPE, 1.5f, 1.2f);
        fx().wispShot(p.getEyeLocation(), wisps);
    }

    /** Hache Maudite : clic droit = onde de choc autour de toi (4 blocs) qui projette en l'air. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onCursedSlam(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND || !HACHE.equals(id(e.getItem()))) return;
        Block b = e.getClickedBlock();
        if (b != null && b.getType().isInteractable() && !e.getPlayer().isSneaking()) return;
        if (b != null && (b.getType().name().endsWith("_LOG") || b.getType().name().endsWith("_WOOD") || b.getType().name().endsWith("_STEM"))) return;
        Player p = e.getPlayer();
        if (!ready(p, HACHE, cfg("hache-maudite.cooldown-seconds", 12))) return;
        World w = p.getWorld();
        double r = cfg("hache-maudite.radius", 4), dmg = cfg("hache-maudite.damage", 5), up = cfg("hache-maudite.launch", 0.7);
        Location c = p.getLocation();
        org.bukkit.block.data.BlockData ground = c.clone().add(0, -1, 0).getBlock().getBlockData();
        if (ground.getMaterial().isAir()) ground = Material.DIRT.createBlockData();
        for (int i = 0; i < 24; i++) {
            double a = Math.PI * 2 * i / 24;
            w.spawnParticle(Particle.BLOCK, c.clone().add(Math.cos(a) * r * 0.8, 0.2, Math.sin(a) * r * 0.8), 6, 0.2, 0.1, 0.2, 0, ground);
        }
        w.spawnParticle(Particle.SOUL, c.clone().add(0, 0.5, 0), 20, r * 0.4, 0.2, r * 0.4, 0.02);
        w.playSound(c, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.4f);
        w.playSound(c, org.bukkit.Sound.ITEM_MACE_SMASH_GROUND, 1.2f, 0.8f);
        fx().cursedSlam(c, r);
        Boss k = plugin.king();
        for (Entity en : w.getNearbyEntities(c, r, 2.5, r)) {
            if (!hittable(p, en) || en.getLocation().distanceSquared(c) > r * r) continue;
            LivingEntity le = (LivingEntity) en;
            le.setNoDamageTicks(0);
            le.damage(dmg, p);
            if (k == null || !k.isBoss(en)) {
                Vector push = en.getLocation().toVector().subtract(c.toVector()).setY(0);
                if (push.lengthSquared() > 1e-4) push.normalize().multiply(0.4);
                le.setVelocity(push.setY(up));
            }
        }
    }

    // ================================================================= armes de la Reine Araignée

    /** Dague Venimeuse : clic droit = bond en avant à travers les ennemis, qui sont empoisonnés. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onVenomDash(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND || !DAGUE.equals(id(e.getItem()))) return;
        Block b = e.getClickedBlock();
        if (b != null && b.getType().isInteractable() && !e.getPlayer().isSneaking()) return;
        Player p = e.getPlayer();
        if (!ready(p, DAGUE, cfg("dague-venin.cooldown-seconds", 9))) return;
        World w = p.getWorld();
        Vector dir = p.getLocation().getDirection();
        dir.setY(Math.max(-0.1, Math.min(0.25, dir.getY()))).normalize();
        double power = cfg("dague-venin.dash-power", 1.8), dmg = cfg("dague-venin.damage", 7);
        int poison = (int) (cfg("dague-venin.poison-seconds", 4) * 20);
        p.setVelocity(dir.clone().multiply(power).setY(dir.getY() * power + 0.15));
        noFall.put(p.getUniqueId(), System.currentTimeMillis() + 2500);
        w.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_SPIDER_AMBIENT, 1.4f, 1.6f);
        w.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_BREEZE_WIND_BURST, 1f, 1.6f);
        fx().venomDash(p, 8);
        Boss k = plugin.king();
        Set<UUID> hit = new HashSet<>();
        int[] t = {0};
        org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (++t[0] > 8 || !p.isOnline() || p.isDead()) {
                task[0].cancel();
                return;
            }
            Location l = p.getLocation().add(0, 1, 0);
            w.spawnParticle(Particle.DUST, l, 5, 0.3, 0.5, 0.3, 0, new Particle.DustOptions(Color.fromRGB(60, 230, 120), 1.3f));
            for (Entity en : w.getNearbyEntities(l, 1.7, 1.5, 1.7)) {
                if (!hittable(p, en) || !hit.add(en.getUniqueId())) continue;
                LivingEntity le = (LivingEntity) en;
                le.setNoDamageTicks(0);
                le.damage(dmg, p);
                le.addPotionEffect(new PotionEffect(PotionEffectType.POISON, poison, 1, false, true, true));
                fx().venomHit(le.getLocation().add(0, 1, 0));
                w.playSound(le.getLocation(), org.bukkit.Sound.ENTITY_SPIDER_HURT, 1f, 1.4f);
            }
        }, 1L, 1L);
    }

    /** Arc des Toiles : tir tendu = la flèche laisse un fil et emprisonne la cible dans des toiles. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWebShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p) || !ARC_TOILES.equals(id(e.getBow())) || e.getForce() < 0.9f) return;
        if (!(e.getProjectile() instanceof org.bukkit.entity.AbstractArrow a)) return;
        if (!ready(p, ARC_TOILES, cfg("arc-toiles.cooldown-seconds", 8))) return;
        a.getPersistentDataContainer().set(webKey, PersistentDataType.BYTE, (byte) 1);
        p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.ENTITY_SPIDER_STEP, 1.5f, 0.6f);
        fx().webArrow(a);
    }

    private final NamespacedKey webKey = new NamespacedKey("smpboss", "web_arrow");
    /** toiles posées par l'arc : retirées après quelques secondes */
    private final Set<Block> webs = new HashSet<>();

    @EventHandler
    public void onWebHit(org.bukkit.event.entity.ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.AbstractArrow a) || !a.getPersistentDataContainer().has(webKey)) return;
        a.getPersistentDataContainer().remove(webKey);
        if (!(a.getShooter() instanceof Player p)) return;
        Location at = e.getHitEntity() != null ? e.getHitEntity().getLocation() : a.getLocation();
        double dmg = cfg("arc-toiles.damage", 6);
        int slow = (int) (cfg("arc-toiles.web-seconds", 4) * 20);
        Bukkit.getScheduler().runTask(plugin, () -> {
            fx().webTrap(at);
            for (Entity en : at.getWorld().getNearbyEntities(at, 2.5, 2, 2.5)) {
                if (!hittable(p, en)) continue;
                LivingEntity le = (LivingEntity) en;
                le.setNoDamageTicks(0);
                le.damage(dmg, p);
                Boss k = plugin.king();
                if (k == null || !k.isBoss(en)) le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slow, 4, false, true, true));
            }
            // vraies toiles autour des pieds (seulement dans l'air, jamais dans une zone protégée), retirées après
            if (cfg("arc-toiles.place-webs", 1) > 0 && !ultimates.protectedPlace(p, at)) {
                List<Block> placed = new ArrayList<>();
                for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                    if (Math.abs(x) + Math.abs(z) > 1) continue;
                    Block bl = at.getBlock().getRelative(x, 0, z);
                    if (bl.getType().isAir()) {
                        bl.setType(Material.COBWEB, false);
                        placed.add(bl);
                        webs.add(bl);
                    }
                }
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    for (Block bl : placed) {
                        if (bl.getType() == Material.COBWEB) bl.setType(Material.AIR, false);
                        webs.remove(bl);
                    }
                }, slow);
            }
        });
        if (e.getHitEntity() == null) Bukkit.getScheduler().runTaskLater(plugin, a::remove, 2L);
    }

    /** Les toiles de l'arc ne lâchent pas de ficelle quand on les casse. */
    @EventHandler(ignoreCancelled = true)
    public void onWebBreak(org.bukkit.event.block.BlockBreakEvent e) {
        if (webs.remove(e.getBlock())) e.setDropItems(false);
    }

    /** Croc de la Reine : clic droit = Toile Abyssale : les ennemis proches sont tirés vers toi et piégés, puis le venin explose. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onQueenFang(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND || !CROC_REINE.equals(id(e.getItem()))) return;
        Block b = e.getClickedBlock();
        if (b != null && b.getType().isInteractable() && !e.getPlayer().isSneaking()) return;
        Player p = e.getPlayer();
        if (!ready(p, CROC_REINE, cfg("croc-reine.cooldown-seconds", 16))) return;
        World w = p.getWorld();
        double r = cfg("croc-reine.radius", 9), dmg = cfg("croc-reine.damage", 12);
        Location c = p.getLocation();
        Boss k = plugin.king();
        List<LivingEntity> caught = new ArrayList<>();
        for (Entity en : w.getNearbyEntities(c, r, 4, r)) {
            if (!hittable(p, en) || en.getLocation().distanceSquared(c) > r * r) continue;
            caught.add((LivingEntity) en);
        }
        w.playSound(c, org.bukkit.Sound.ENTITY_SPIDER_AMBIENT, 2f, 0.5f);
        w.playSound(c, org.bukkit.Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.2f, 1.5f);
        fx().queenFang(c, r, caught);
        for (LivingEntity le : caught) {
            if (k != null && k.isBoss(le)) continue;
            Vector pull = c.toVector().subtract(le.getLocation().toVector());
            double d = pull.length();
            if (d > 1.5) le.setVelocity(pull.normalize().multiply(Math.min(1.6, 0.35 + d * 0.12)).setY(0.35));
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 6, false, true, true));
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            Location cc = p.getLocation();
            w.playSound(cc, org.bukkit.Sound.ENTITY_SPIDER_DEATH, 2f, 0.6f);
            w.playSound(cc, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.4f);
            fx().queenFangBurst(cc, r);
            for (LivingEntity le : caught) {
                if (!le.isValid() || le.isDead() || le.getLocation().distanceSquared(cc) > (r + 2) * (r + 2)) continue;
                le.setNoDamageTicks(0);
                le.damage(dmg, p);
                le.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 80, 1, false, true, true));
            }
        }, 14L);
    }

    /** Faux de la Tisseuse (légendaire) : clic droit = un filet géant part en cône devant toi ; ceux qu'il attrape sont figés. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onWeaverNet(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND || !FAUX_TISSEUSE.equals(id(e.getItem()))) return;
        Block b = e.getClickedBlock();
        if (b != null && b.getType().isInteractable() && !e.getPlayer().isSneaking()) return;
        e.setCancelled(true); // pas de labour avec la faux
        Player p = e.getPlayer();
        if (!ready(p, FAUX_TISSEUSE, cfg("faux-tisseuse.cooldown-seconds", 12))) return;
        World w = p.getWorld();
        double range = cfg("faux-tisseuse.range", 9), dmg = cfg("faux-tisseuse.damage", 9);
        double half = Math.toRadians(cfg("faux-tisseuse.angle", 80) / 2);
        int root = (int) (cfg("faux-tisseuse.root-seconds", 2.5) * 20);
        Location eye = p.getEyeLocation();
        Vector f = eye.getDirection().setY(0);
        if (f.lengthSquared() < 1e-4) f = new Vector(0, 0, 1);
        f.normalize();
        Boss k = plugin.king();
        List<LivingEntity> caught = new ArrayList<>();
        for (Entity en : w.getNearbyEntities(p.getLocation(), range, 4, range)) {
            if (!hittable(p, en)) continue;
            Vector to = en.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
            double d = to.length();
            if (d > range || d < 0.01) continue;
            if (to.normalize().dot(f) < Math.cos(half)) continue;
            caught.add((LivingEntity) en);
        }
        w.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_SPIDER_AMBIENT, 1.6f, 0.7f);
        w.playSound(p.getLocation(), org.bukkit.Sound.ITEM_TRIDENT_THROW, 1.2f, 0.6f);
        fx().weaverNet(p, f, range, caught);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (LivingEntity le : caught) {
                if (!le.isValid() || le.isDead()) continue;
                le.setNoDamageTicks(0);
                le.damage(dmg, p);
                if (k == null || !k.isBoss(le)) {
                    ultimates.freezeFor(le, root);
                    le.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0, false, true, true));
                }
                fx().webTrap(le.getLocation());
            }
        }, 6L);
    }

    /** Sceptre de l'Abysse (prestige) : clic droit = huit pattes géantes jaillissent du sol et s'abattent sur la zone visée. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onAbyssLegs(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND || !SCEPTRE.equals(id(e.getItem()))) return;
        Block b = e.getClickedBlock();
        if (b != null && b.getType().isInteractable() && !e.getPlayer().isSneaking()) return;
        Player p = e.getPlayer();
        World w = p.getWorld();
        double reach = cfg("sceptre-abysse.range", 28), r = cfg("sceptre-abysse.radius", 5.5), dmg = cfg("sceptre-abysse.damage", 18);
        RayTraceResult hit = w.rayTraceBlocks(p.getEyeLocation(), p.getEyeLocation().getDirection(), reach, org.bukkit.FluidCollisionMode.NEVER, true);
        Location c;
        if (hit != null && hit.getHitBlock() != null) c = hit.getHitPosition().toLocation(w);
        else {
            c = p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(reach));
            for (int i = 0; i < 30 && c.getBlock().isPassable() && c.getY() > w.getMinHeight(); i++) c.add(0, -1, 0);
        }
        c.setY(Math.floor(c.getY()) + (c.getBlock().isPassable() ? 0 : 1));
        if (!ready(p, SCEPTRE, cfg("sceptre-abysse.cooldown-seconds", 25))) return;
        Location center = c;
        w.playSound(center, org.bukkit.Sound.ENTITY_WARDEN_DIG, 2f, 0.6f);
        w.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_SPIDER_AMBIENT, 2f, 0.4f);
        fx().abyssLegsWarn(center, r);
        Boss k = plugin.king();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            fx().abyssLegs(center, r);
            w.playSound(center, org.bukkit.Sound.ENTITY_SPIDER_DEATH, 2.5f, 0.4f);
            w.playSound(center, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 1.6f, 0.7f);
            for (Entity en : w.getNearbyEntities(center, r, 4, r)) {
                if (!hittable(p, en) || en.getLocation().distanceSquared(center) > r * r) continue;
                LivingEntity le = (LivingEntity) en;
                le.setNoDamageTicks(0);
                le.damage(dmg, p);
                if (k == null || !k.isBoss(le)) {
                    le.setVelocity(new Vector(0, cfg("sceptre-abysse.knock-up", 0.9), 0));
                    le.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, false, true, true));
                    le.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 80, 1, false, true, true));
                }
            }
        }, 16L);
    }

    // ================================================================= Croc de Givre (épée SECRÈTE) : RUÉE GLACIALE

    private final Map<UUID, Long> noFall = new HashMap<>();

    /** Clic droit : une ruée de 10 blocs ; tout ce que tu traverses prend des dégâts et gèle 3 s. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onFrostDash(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND || !GIVRE.equals(id(e.getItem()))) return;
        Block b = e.getClickedBlock();
        if (b != null && b.getType().isInteractable() && !e.getPlayer().isSneaking()) return;
        Player p = e.getPlayer();
        if (!ready(p, GIVRE, cfg("croc-de-givre.cooldown-seconds", 8))) return;
        frostDash(p);
    }

    private void frostDash(Player p) {
        World w = p.getWorld();
        Vector dir = p.getLocation().getDirection();
        dir.setY(Math.max(-0.2, Math.min(0.3, dir.getY())));
        dir.normalize();
        double power = cfg("croc-de-givre.dash-power", 2.2), dmg = cfg("croc-de-givre.damage", 10);
        int freeze = (int) (cfg("croc-de-givre.freeze-seconds", 3) * 20);
        p.setVelocity(dir.clone().multiply(power).setY(dir.getY() * power + 0.2));
        noFall.put(p.getUniqueId(), System.currentTimeMillis() + 3000);
        w.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_BREEZE_WIND_BURST, 1.4f, 1.3f);
        w.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_HURT_FREEZE, 1.2f, 0.7f);
        fx().frostDash(p, 10);
        Particle.DustOptions ice = new Particle.DustOptions(Color.fromRGB(170, 230, 255), 1.4f), white = new Particle.DustOptions(Color.WHITE, 1.0f);
        org.bukkit.block.data.BlockData packed = Material.PACKED_ICE.createBlockData();
        Boss k = plugin.king();
        Set<UUID> hit = new HashSet<>();
        int[] t = {0};
        org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (++t[0] > 10 || !p.isOnline() || p.isDead()) {
                task[0].cancel();
                if (p.isOnline()) {
                    w.spawnParticle(Particle.SNOWFLAKE, p.getLocation().add(0, 1, 0), 40, 0.6, 0.6, 0.6, 0.08);
                    w.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_POWDER_SNOW_BREAK, 1.2f, 0.8f);
                }
                return;
            }
            Location l = p.getLocation().add(0, 1, 0);
            w.spawnParticle(Particle.SNOWFLAKE, l, 6, 0.35, 0.5, 0.35, 0.02);
            w.spawnParticle(Particle.DUST, l, 4, 0.3, 0.5, 0.3, 0, ice);
            w.spawnParticle(Particle.DUST, l, 2, 0.3, 0.5, 0.3, 0, white);
            w.spawnParticle(Particle.ITEM_SNOWBALL, l, 2, 0.3, 0.3, 0.3, 0.05);
            w.spawnParticle(Particle.BLOCK, p.getLocation(), 4, 0.3, 0.05, 0.3, 0, packed);
            for (Entity en : w.getNearbyEntities(l, 1.8, 1.6, 1.8)) {
                if (!(en instanceof LivingEntity le) || en.equals(p) || !hit.add(en.getUniqueId())) continue;
                if (en instanceof Player pl && (pl.getGameMode() == org.bukkit.GameMode.SPECTATOR || pl.getGameMode() == org.bukkit.GameMode.CREATIVE)) continue;
                le.setNoDamageTicks(0);
                le.damage(dmg, p);
                le.setFreezeTicks(le.getMaxFreezeTicks() + freeze);
                if (k == null || !k.isBoss(en)) {
                    le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, freeze, 3, false, true, true));
                    le.setVelocity(dir.clone().multiply(0.4).setY(0.2));
                }
                Location c = le.getLocation().add(0, 1, 0);
                w.spawnParticle(Particle.BLOCK, c, 30, 0.4, 0.6, 0.4, 0, packed);
                w.spawnParticle(Particle.SNOWFLAKE, c, 20, 0.4, 0.6, 0.4, 0.05);
                w.playSound(c, org.bukkit.Sound.BLOCK_GLASS_BREAK, 1.2f, 1.4f);
                fx().frostHit(c);
            }
        }, 1L, 1L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDashFall(org.bukkit.event.entity.EntityDamageEvent e) {
        if (e.getCause() != org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL || noFall.isEmpty()) return;
        Long until = noFall.get(e.getEntity().getUniqueId());
        if (until == null) return;
        if (until > System.currentTimeMillis()) e.setCancelled(true);
        noFall.remove(e.getEntity().getUniqueId());
    }

    // ================================================================= Arc Tempête (arc SECRET) : FLÈCHE D'ORAGE

    /** Tir tendu au maximum : la flèche porte l'orage ; à l'impact la foudre tombe et saute d'ennemi en ennemi. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStormShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p) || !TEMPETE.equals(id(e.getBow()))) return;
        if (e.getForce() < 0.95f || !(e.getProjectile() instanceof org.bukkit.entity.AbstractArrow a)) return;
        if (!ready(p, TEMPETE, cfg("arc-tempete.cooldown-seconds", 8))) return;
        a.getPersistentDataContainer().set(stormKey, PersistentDataType.BYTE, (byte) 1);
        a.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.CREATIVE_ONLY);
        p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.ITEM_TRIDENT_THUNDER, 0.5f, 1.8f);
        fx().stormArrow(a);
        Particle.DustOptions sky = new Particle.DustOptions(Color.fromRGB(120, 200, 255), 1.1f);
        int[] t = {0};
        org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (++t[0] > 100 || !a.isValid() || a.isInBlock() || !a.getPersistentDataContainer().has(stormKey)) {
                task[0].cancel();
                return;
            }
            Location l = a.getLocation();
            a.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, l, 3, 0.1, 0.1, 0.1, 0.02);
            a.getWorld().spawnParticle(Particle.DUST, l, 1, 0.05, 0.05, 0.05, 0, sky);
            if (t[0] % 2 == 0) a.getWorld().spawnParticle(Particle.END_ROD, l, 1, 0, 0, 0, 0);
        }, 1L, 1L);
    }

    @EventHandler
    public void onStormHit(org.bukkit.event.entity.ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.AbstractArrow a) || !a.getPersistentDataContainer().has(stormKey)) return;
        a.getPersistentDataContainer().remove(stormKey);
        if (!(a.getShooter() instanceof Player p)) return;
        Location at = e.getHitEntity() != null ? e.getHitEntity().getLocation() : a.getLocation();
        LivingEntity first = e.getHitEntity() instanceof LivingEntity le && !le.equals(p) ? le : nearestFoe(p, at, 4, Set.of());
        Bukkit.getScheduler().runTask(plugin, () -> {
            at.getWorld().strikeLightningEffect(first != null ? first.getLocation() : at);
            fx().skyBolt(first != null ? first.getLocation() : at);
            if (first != null) storm(p, first, first.getLocation().add(0, 1, 0), new HashSet<>(), 0, cfg("arc-tempete.damage", 10));
        });
        if (e.getHitEntity() == null) Bukkit.getScheduler().runTaskLater(plugin, a::remove, 2L);
    }

    /** Un joueur ou un monstre proche (pas les animaux, villageois, familiers). */
    private LivingEntity nearestFoe(Player p, Location c, double r, Set<UUID> skip) {
        LivingEntity best = null;
        double bd = r * r;
        Boss k = plugin.king();
        for (Entity en : c.getWorld().getNearbyEntities(c, r, r, r)) {
            if (!(en instanceof LivingEntity le) || en.equals(p) || le.isDead() || skip.contains(en.getUniqueId())) continue;
            if (en instanceof Player t) {
                if (t.getGameMode() == org.bukkit.GameMode.SPECTATOR || t.getGameMode() == org.bukkit.GameMode.CREATIVE) continue;
            } else if (!(en instanceof org.bukkit.entity.Enemy) && (k == null || !k.isBoss(en))) continue;
            double d = en.getLocation().distanceSquared(c);
            if (d < bd) {
                bd = d;
                best = le;
            }
        }
        return best;
    }

    /** La foudre saute de cible en cible (un saut toutes les 3 ticks), un peu plus faible à chaque fois. */
    private void storm(Player p, LivingEntity cur, Location from, Set<UUID> done, int jump, double dmg) {
        if (!cur.isValid() || cur.isDead()) return;
        done.add(cur.getUniqueId());
        World w = cur.getWorld();
        Location to = cur.getLocation().add(0, cur.getHeight() * 0.6, 0);
        // l'arc électrique, en zigzag
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Vector seg = to.toVector().subtract(from.toVector());
        double len = seg.length();
        Particle.DustOptions sky = new Particle.DustOptions(Color.fromRGB(150, 215, 255), 1.2f);
        for (double s = 0; s <= len; s += 0.3) {
            Location at = from.clone().add(seg.clone().multiply(s / Math.max(len, 0.01))).add(rnd.nextDouble(-0.2, 0.2), rnd.nextDouble(-0.2, 0.2), rnd.nextDouble(-0.2, 0.2));
            w.spawnParticle(Particle.ELECTRIC_SPARK, at, 1, 0, 0, 0, 0);
            if (rnd.nextInt(3) == 0) w.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, sky);
        }
        w.spawnParticle(Particle.END_ROD, to, 15, 0.3, 0.5, 0.3, 0.08);
        w.spawnParticle(Particle.ELECTRIC_SPARK, to, 25, 0.4, 0.6, 0.4, 0.2);
        w.playSound(to, org.bukkit.Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.2f, 1.4f);
        if (jump > 0) w.playSound(to, org.bukkit.Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.35f, 1.8f);
        fx().chain(from, to);
        cur.setNoDamageTicks(0);
        cur.damage(dmg, p);
        cur.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 40, 0, false, false, true));
        Boss k = plugin.king();
        if (k == null || !k.isBoss(cur)) cur.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 2, false, true, true));
        if (jump >= (int) cfg("arc-tempete.chain", 4)) return;
        LivingEntity next = nearestFoe(p, cur.getLocation(), cfg("arc-tempete.chain-range", 8), done);
        if (next == null) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> storm(p, next, to, done, jump + 1, dmg * cfg("arc-tempete.chain-falloff", 0.8)), 3L);
    }

    // ================================================================= attaques finales (Violet Imaginaire, Fûga)

    @EventHandler(priority = EventPriority.HIGH)
    public void onUltimate(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        String id = id(e.getItem());
        if (!HOLLOW.equals(id) && !FUGA.equals(id)) return;
        Block b = e.getClickedBlock();
        if (b != null && b.getType().isInteractable() && !e.getPlayer().isSneaking()) return;
        e.setCancelled(true);
        ultimates.cast(e.getPlayer(), id);
    }

    // ================================================================= aura des armes prestige

    /** Toutes les 3 ticks : une aura autour de l'arme prestige tenue en main (et un bonus passif). */
    void aura() {
        auraTick++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            String id = id(p.getInventory().getItemInMainHand());
            if (id == null) continue;
            World w = p.getWorld();
            double yaw = Math.toRadians(p.getLocation().getYaw());
            // position de la main droite
            Location hand = p.getLocation().add(-Math.cos(yaw) * 0.38 - Math.sin(yaw) * 0.25, 0.95, -Math.sin(yaw) * 0.38 + Math.cos(yaw) * 0.25);
            if (SONIC.equals(id)) {
                if (auraTick % 2 == 0) w.spawnParticle(Particle.DUST, hand, 1, 0.1, 0.18, 0.1, 0, new Particle.DustOptions(Color.fromRGB(170, 90, 255), 0.8f));
                if (auraTick % 6 == 0) w.spawnParticle(Particle.END_ROD, hand, 1, 0.08, 0.12, 0.08, 0.005);
                continue;
            }
            if (TRANCHE.equals(id)) {
                if (auraTick % 2 == 0) w.spawnParticle(Particle.DUST, hand, 1, 0.1, 0.2, 0.1, 0, new Particle.DustOptions(Color.fromRGB(200, 10, 30), 0.9f));
                if (auraTick % 3 == 0) w.spawnParticle(Particle.CRIMSON_SPORE, hand, 2, 0.15, 0.25, 0.15, 0);
                continue;
            }
            if (BRISE.equals(id)) {
                w.spawnParticle(Particle.DUST, hand, 2, 0.12, 0.2, 0.12, 0, new Particle.DustOptions(Color.fromRGB(255, 110 + (auraTick * 13) % 80, 10), 0.9f));
                if (auraTick % 3 == 0) w.spawnParticle(Particle.FALLING_LAVA, hand, 1, 0.1, 0.1, 0.1, 0);
                if (auraTick % 5 == 0) w.spawnParticle(Particle.WAX_OFF, hand.clone().add(0, 0.3, 0), 1, 0.15, 0.15, 0.15, 0);
                // passif : peau de pierre (Résistance) en main
                if (auraTick % 10 == 0) p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 40, 0, false, false, true));
                // braises autour des pieds
                double a = auraTick * 0.35;
                w.spawnParticle(Particle.FLAME, p.getLocation().add(Math.cos(a) * 0.7, 0.1, Math.sin(a) * 0.7), 1, 0, 0, 0, 0);
            } else if (GIVRE.equals(id)) {
                if (auraTick % 2 == 0) w.spawnParticle(Particle.DUST, hand, 1, 0.1, 0.2, 0.1, 0, new Particle.DustOptions(Color.fromRGB(170, 230, 255), 0.9f));
                if (auraTick % 3 == 0) w.spawnParticle(Particle.SNOWFLAKE, hand, 1, 0.12, 0.2, 0.12, 0.005);
            } else if (TEMPETE.equals(id)) {
                if (auraTick % 2 == 0) w.spawnParticle(Particle.ELECTRIC_SPARK, hand, 2, 0.12, 0.2, 0.12, 0.02);
                if (auraTick % 4 == 0) w.spawnParticle(Particle.DUST, hand, 1, 0.1, 0.15, 0.1, 0, new Particle.DustOptions(Color.fromRGB(120, 200, 255), 0.8f));
            } else if (HOLLOW.equals(id)) {
                // Bleu et Rouge qui tournent autour de la main, Violet au centre
                double a = auraTick * 0.4;
                w.spawnParticle(Particle.DUST, hand.clone().add(Math.cos(a) * 0.35, 0.1, Math.sin(a) * 0.35), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(60, 130, 255), 0.8f));
                w.spawnParticle(Particle.DUST, hand.clone().add(-Math.cos(a) * 0.35, 0.1, -Math.sin(a) * 0.35), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(255, 35, 45), 0.8f));
                if (auraTick % 3 == 0) w.spawnParticle(Particle.DUST, hand, 1, 0.05, 0.05, 0.05, 0, new Particle.DustOptions(Color.fromRGB(150, 60, 255), 1.1f));
                if (auraTick % 8 == 0) w.spawnParticle(Particle.REVERSE_PORTAL, hand, 2, 0.1, 0.1, 0.1, 0.01);
            } else if (FUGA.equals(id)) {
                w.spawnParticle(Particle.FLAME, hand, 1, 0.08, 0.15, 0.08, 0.01);
                if (auraTick % 2 == 0) w.spawnParticle(Particle.DUST, hand, 1, 0.1, 0.18, 0.1, 0, new Particle.DustOptions(Color.fromRGB(255, 120 + (auraTick * 17) % 70, 0), 0.9f));
                if (auraTick % 5 == 0) w.spawnParticle(Particle.LAVA, hand, 1, 0.05, 0.05, 0.05, 0);
            } else {
                w.spawnParticle(Particle.SOUL_FIRE_FLAME, hand, 1, 0.08, 0.15, 0.08, 0.005);
                w.spawnParticle(Particle.DUST, hand, 2, 0.12, 0.2, 0.12, 0, new Particle.DustOptions(Color.fromRGB(70, 235, 220), 0.9f));
                double a = auraTick * 0.3;
                for (int n = 0; n < 2; n++) {
                    double b = a + Math.PI * n;
                    w.spawnParticle(Particle.SOUL, p.getLocation().add(Math.cos(b) * 0.8, 1.0 + Math.sin(auraTick * 0.1) * 0.6, Math.sin(b) * 0.8), 1, 0, 0, 0, 0);
                }
                // passif : on se déplace plus vite, faux en main
                if (auraTick % 10 == 0) p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 40, 0, false, false, true));
            }
        }
    }

    private int auraTick;

    // ================================================================= gardées à la mort

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        if (e.getKeepInventory()) return;
        List<ItemStack> mine = new ArrayList<>();
        for (Iterator<ItemStack> it = e.getDrops().iterator(); it.hasNext(); ) {
            ItemStack s = it.next();
            if (id(s) != null) {
                mine.add(s.clone());
                it.remove();
            }
        }
        if (!mine.isEmpty()) kept.computeIfAbsent(e.getEntity().getUniqueId(), k -> new ArrayList<>()).addAll(mine);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        List<ItemStack> back = kept.remove(p.getUniqueId());
        if (back == null) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (ItemStack it : back) plugin.loot().give(p, it);
            p.sendMessage(parse(plugin.msg("weapons.kept")));
        }, 2L);
    }

    /** Arrêt du serveur : les armes pas encore rendues vont dans le coffre de butin. */
    void saveKept() {
        for (Map.Entry<UUID, List<ItemStack>> e : kept.entrySet()) plugin.store().of(e.getKey()).stash.addAll(e.getValue());
        if (!kept.isEmpty()) plugin.store().dirty();
        kept.clear();
    }

    // ================================================================= butin

    /** Tirage des armes après une victoire (chacune a sa chance). */
    void roll(Player p) {
        roll(p, false);
    }

    void roll(Player p, boolean spider) {
        for (String id : spider ? SPIDER : PUMPKIN) {
            double chance = cfg(path(id) + ".chance", defChance(id));
            chance *= plugin.petLuck(p);
            if (ThreadLocalRandom.current().nextDouble() * 100 >= chance) continue;
            ItemStack it = create(id);
            boolean inv = plugin.loot().give(p, it.clone());
            if (OUTFITS.containsKey(id)) {
                String first = OUTFITS.get(id)[TENUE_GOJO.equals(id) ? 1 : 0];
                for (String pc : OUTFITS.get(id)) if (!pc.equals(first)) inv &= plugin.loot().give(p, outfitPiece(pc));
            }
            Rarity rar = rarity(id);
            String name = rar.name(plugin.msg("weapons." + path(id) + ".name"), 0);
            Reveal.show(plugin, p, it, rar, name, plugin.msg(inv ? "loot.where-inventory" : "loot.where-stash"));
            if (rar == Rarity.PRESTIGE) {
                Bukkit.broadcast(parse(plugin.msg("loot.broadcast-prestige").replace("<player>", SmpBoss.MM.escapeTags(p.getName())).replace("<item>", name)));
                for (Player o : Bukkit.getOnlinePlayers()) {
                    o.showTitle(net.kyori.adventure.title.Title.title(parse(rar.tag), parse(plugin.msg("loot.prestige-subtitle")
                            .replace("<player>", SmpBoss.MM.escapeTags(p.getName())).replace("<item>", name))));
                    o.playSound(o.getLocation(), org.bukkit.Sound.ENTITY_ENDER_DRAGON_DEATH, 0.6f, 1.1f);
                }
                p.getWorld().strikeLightningEffect(p.getLocation());
            } else if (rar != Rarity.EPIC) {
                Bukkit.broadcast(parse(plugin.msg("weapons.broadcast").replace("<player>", SmpBoss.MM.escapeTags(p.getName())).replace("<item>", name)));
            }
            for (Player o : Bukkit.getOnlinePlayers()) o.playSound(o.getLocation(), org.bukkit.Sound.ENTITY_WARDEN_SONIC_BOOM, 0.5f, 1.4f);
        }
    }
}
