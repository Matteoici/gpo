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
    private final NamespacedKey healthKey, speedKey;
    private final Map<UUID, Long> lastHurt = new HashMap<>();
    public int maxEquipped;

    Relics(SmpBoss plugin) {
        this.plugin = plugin;
        healthKey = new NamespacedKey(plugin, "relic_health");
        speedKey = new NamespacedKey(plugin, "relic_speed");
    }

    void load() {
        catalog.clear();
        var c = plugin.getConfig();
        maxEquipped = Math.max(1, c.getInt("relics.max-equipped", 2));
        ConfigurationSection s = c.getConfigurationSection("relics.list");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection r = s.getConfigurationSection(id);
            if (r == null) continue;
            Material icon = Material.matchMaterial(r.getString("icon", "NETHER_STAR"));
            catalog.put(id.toLowerCase(Locale.ROOT), new Relic(id.toLowerCase(Locale.ROOT), r.getString("name", id),
                    Rarity.of(r.getString("rarity")), icon == null ? Material.NETHER_STAR : icon, r.getString("item-model", ""),
                    r.getStringList("lore"), r.getDouble("health"), r.getDouble("speed"), r.getDouble("regen"),
                    r.getString("trail"), r.getBoolean("backpack"), r.getString("visual", defaultVisual(id, r, icon))));
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

    /** Ajoute la relique à la collection du joueur. false = il l'a déjà. */
    public boolean claim(Player p, Relic r) {
        PlayerData d = plugin.store().of(p.getUniqueId());
        if (!d.relics.add(r.id())) return false;
        if (d.equipped.size() < maxEquipped) d.equipped.add(r.id());
        plugin.store().dirty();
        apply(p);
        return true;
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
            if (d.equipped.size() >= maxEquipped) return "relics.full";
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
        double health = 0, speed = 0;
        for (String id : plugin.store().of(p.getUniqueId()).equipped) {
            Relic r = get(id);
            if (r == null) continue;
            health += r.health() * 2;
            speed += r.speed() / 100.0;
        }
        set(p, Attribute.MAX_HEALTH, healthKey, health, AttributeModifier.Operation.ADD_NUMBER);
        set(p, Attribute.MOVEMENT_SPEED, speedKey, speed, AttributeModifier.Operation.ADD_SCALAR);
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
            if (p.isInvisible() || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
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
                p.getWorld().spawnParticle(part, l, 2, 0.2, 0.05, 0.2, 0.01);
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
