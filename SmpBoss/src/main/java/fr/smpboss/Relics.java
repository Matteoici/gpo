package fr.smpboss;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Catalogue des reliques, leurs effets, et le sac visible sur le dos. */
public final class Relics {

    private final SmpBoss plugin;
    private final Map<String, Relic> catalog = new LinkedHashMap<>();
    private final NamespacedKey healthKey, speedKey, damageKey, armorKey;
    private final Map<UUID, Long> lastHurt = new HashMap<>();
    public int maxEquipped, implantSlots;
    private final java.util.Set<String> implantIds = new java.util.HashSet<>();

    Relics(SmpBoss plugin) {
        this.plugin = plugin;
        healthKey = new NamespacedKey(plugin, "relic_health");
        speedKey = new NamespacedKey(plugin, "relic_speed");
        damageKey = new NamespacedKey(plugin, "relic_damage");
        armorKey = new NamespacedKey(plugin, "relic_armor");
    }

    void load() {
        catalog.clear();
        var c = plugin.getConfig();
        maxEquipped = Math.max(1, c.getInt("relics.max-equipped", 2));
        implantSlots = Math.max(0, Math.min(3, c.getInt("relics.implant-slots", 3)));
        implantIds.clear();
        implantIds.addAll(Cyberware.IMPLANTS);
        ConfigurationSection s = c.getConfigurationSection("relics.list");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection r = s.getConfigurationSection(id);
            if (r == null) continue;
            Material icon = Material.matchMaterial(r.getString("icon", "NETHER_STAR"));
            catalog.put(id.toLowerCase(Locale.ROOT), new Relic(id.toLowerCase(Locale.ROOT), r.getString("name", id),
                    Rarity.of(r.getString("rarity")), icon == null ? Material.NETHER_STAR : icon, r.getString("item-model", ""),
                    r.getStringList("lore"), r.getDouble("health"), r.getDouble("speed"), r.getDouble("regen"),
                    r.getString("trail"), r.getBoolean("backpack"), r.getString("visual", defaultVisual(id, r, icon)),
                    r.getDouble("damage"), r.getDouble("armor")));
            if (r.getBoolean("implant", false)) implantIds.add(id.toLowerCase(Locale.ROOT));
        }
    }

    /** Visuel par défaut (anciens config.yml sans "visual:"). */
    private static String defaultVisual(String id, ConfigurationSection r, Material icon) {
        if (r.getBoolean("backpack")) return "backpack";
        String trail = r.getString("trail");
        if (trail != null && trail.toUpperCase(Locale.ROOT).contains("FLAME")) return "flames";
        if (icon == null) return "";
        return switch (icon) {
            case GOLDEN_HELMET -> "crown";
            case LANTERN -> "lantern";
            case SOUL_LANTERN -> "wisp";
            case GOLDEN_BOOTS -> "boots";
            case GOLDEN_CARROT -> "amulet";
            default -> "";
        };
    }

    public Relic get(String id) {
        return id == null ? null : catalog.get(id.toLowerCase(Locale.ROOT));
    }

    public Map<String, Relic> all() {
        return catalog;
    }

    // ================================================================= collection

    /** Un implant (Night City) : il va dans les emplacements d'implants, pas dans ceux des reliques. */
    public boolean isImplant(String id) {
        return implantSlots > 0 && id != null && implantIds.contains(id.toLowerCase(Locale.ROOT));
    }

    /** Reliques équipées (sans les implants), dans l'ordre. */
    public List<String> equippedRelics(PlayerData d) {
        List<String> out = new java.util.ArrayList<>();
        for (String id : d.equipped) if (!isImplant(id)) out.add(id);
        return out;
    }

    /** Implants équipés, dans l'ordre. */
    public List<String> equippedImplants(PlayerData d) {
        List<String> out = new java.util.ArrayList<>();
        for (String id : d.equipped) if (isImplant(id)) out.add(id);
        return out;
    }

    private static boolean sande(String id) {
        return Cyberware.SANDE.equals(id) || Cyberware.PROTO.equals(id);
    }

    /** null = on peut l'équiper, sinon la clé du message qui dit pourquoi pas. */
    private String cannotEquip(PlayerData d, String id) {
        if (isImplant(id)) {
            if (equippedImplants(d).size() >= implantSlots) return "relics.implants-full";
            if (sande(id)) for (String o : d.equipped) if (sande(o)) return "relics.one-sandevistan";
            return null;
        }
        return equippedRelics(d).size() >= maxEquipped ? "relics.full" : null;
    }

    /** Ajoute la relique à la collection du joueur. false = il l'a déjà. */
    public boolean claim(Player p, Relic r) {
        PlayerData d = plugin.store().of(p.getUniqueId());
        if (!d.relics.add(r.id())) return false;
        if (cannotEquip(d, r.id()) == null) d.equipped.add(r.id());
        plugin.store().dirty();
        apply(p);
        return true;
    }

    /** Le joueur possède le sac (équipé ou non). Sinon on n'en parle jamais : pas de spoil. */
    public boolean ownsBackpack(Player p) {
        for (String id : plugin.store().of(p.getUniqueId()).relics) {
            Relic r = get(id);
            if (r != null && r.backpack()) return true;
        }
        return false;
    }

    public boolean hasBackpack(Player p) {
        for (String id : plugin.store().of(p.getUniqueId()).equipped) {
            Relic r = get(id);
            if (r != null && r.backpack()) return true;
        }
        return false;
    }

    /** Équiper / déséquiper. Renvoie la clé du message à afficher. */
    public String toggle(Player p, String id) {
        PlayerData d = plugin.store().of(p.getUniqueId());
        if (!d.relics.contains(id)) return "relics.not-owned";
        String key;
        if (d.equipped.remove(id)) {
            key = "relics.unequipped";
        } else {
            String why = cannotEquip(d, id);
            if (why != null) return why;
            d.equipped.add(id);
            key = "relics.equipped";
        }
        plugin.store().dirty();
        apply(p);
        return key;
    }

    // ================================================================= effets

    /** Remet les bonus (vie, vitesse) selon les reliques équipées. */
    public void apply(Player p) {
        double health = 0, speed = 0, damage = 0, armor = 0;
        // Restriction Céleste (Lance du Ciel Inversé) : aucune relique
        if (!TojiSpear.restricted(p)) for (String id : plugin.store().of(p.getUniqueId()).equipped) {
            Relic r = get(id);
            if (r == null) continue;
            health += r.health() * 2;
            speed += r.speed() / 100.0;
            damage += r.damage() / 100.0;
            armor += r.armor();
        }
        set(p, Attribute.MAX_HEALTH, healthKey, health, AttributeModifier.Operation.ADD_NUMBER);
        set(p, Attribute.MOVEMENT_SPEED, speedKey, speed, AttributeModifier.Operation.ADD_SCALAR);
        set(p, Attribute.ATTACK_DAMAGE, damageKey, damage, AttributeModifier.Operation.ADD_SCALAR);
        set(p, Attribute.ARMOR, armorKey, armor, AttributeModifier.Operation.ADD_NUMBER);
        double max = p.getAttribute(Attribute.MAX_HEALTH) == null ? 20 : p.getAttribute(Attribute.MAX_HEALTH).getValue();
        if (p.getHealth() > max) p.setHealth(max);
        updateBackpack(p);
    }

    private static void set(Player p, Attribute a, NamespacedKey key, double amount, AttributeModifier.Operation op) {
        AttributeInstance inst = p.getAttribute(a);
        if (inst == null) return;
        inst.removeModifier(key);
        if (amount != 0) inst.addTransientModifier(new AttributeModifier(key, amount, op, EquipmentSlotGroup.ANY));
    }

    public void hurt(Player p) {
        lastHurt.put(p.getUniqueId(), System.currentTimeMillis());
    }

    /** Toutes les 4 secondes : régénération hors combat. Toutes les 5 ticks : particules. */
    public void tickRegen() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            double regen = 0;
            if (TojiSpear.restricted(p)) continue;
            for (String id : plugin.store().of(p.getUniqueId()).equipped) {
                Relic r = get(id);
                if (r != null) regen += r.regen();
            }
            if (regen <= 0 || p.isDead()) continue;
            Long last = lastHurt.get(p.getUniqueId());
            if (last != null && now - last < 8000) continue;
            var max = p.getAttribute(Attribute.MAX_HEALTH);
            if (max == null) continue;
            p.setHealth(Math.min(max.getValue(), p.getHealth() + regen));
        }
    }

    public void tickTrails() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isInvisible() || p.getGameMode() == org.bukkit.GameMode.SPECTATOR || TojiSpear.restricted(p)) continue;
            for (String id : plugin.store().of(p.getUniqueId()).equipped) {
                Relic r = get(id);
                if (r == null || r.trail() == null || "flames".equals(r.visual())) continue;
                Particle part;
                try {
                    part = Particle.valueOf(r.trail().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (part.getDataType() != Void.class) continue;
                Location l = p.getLocation().add(0, 0.1, 0);
                for (Player v : p.getWorld().getPlayers()) {
                    if (plugin.store().of(v.getUniqueId()).hideEffects || (!v.equals(p) && !v.canSee(p))) continue;
                    if (v.getLocation().distanceSquared(l) > 48 * 48) continue;
                    v.spawnParticle(part, l, 2, 0.2, 0.05, 0.2, 0.01);
                }
            }
        }
    }

    // ================================================================= cosmétiques (voir Cosmetics)

    public void updateBackpack(Player p) {
        plugin.cosmetics().refresh(p);
        if (plugin.invButtons() != null) plugin.invButtons().place(p);
    }

    public void tickBackpacks() {
        plugin.cosmetics().tick();
    }

    public void forget(Player p) {
        plugin.cosmetics().remove(p.getUniqueId());
        lastHurt.remove(p.getUniqueId());
    }

    public void removeAll() {
        plugin.cosmetics().removeAll();
    }

    public List<Relic> owned(Player p) {
        return plugin.store().of(p.getUniqueId()).relics.stream().map(this::get).filter(r -> r != null).toList();
    }
}
