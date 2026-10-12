package fr.smpboss;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

import static fr.smpboss.SmpBoss.parse;

/** Fabrication des objets du plugin (tous marqués pour être reconnus, jamais confondus avec des objets normaux). */
public final class BossItems {

    private final SmpBoss plugin;
    public final NamespacedKey seedKey, sacrificeKey, relicKey, sickleKey, lootKey;

    BossItems(SmpBoss plugin) {
        this.plugin = plugin;
        seedKey = new NamespacedKey(plugin, "seed");
        sacrificeKey = new NamespacedKey(plugin, "sacrifice");
        relicKey = new NamespacedKey(plugin, "relic");
        sickleKey = new NamespacedKey(plugin, "sickle");
        lootKey = new NamespacedKey(plugin, "loot");
    }

    private static List<Component> lines(List<String> raw) {
        List<Component> out = new ArrayList<>();
        for (String s : raw) out.add(parse(s));
        return out;
    }

    private void model(ItemMeta meta, String model) {
        if (model == null || model.isBlank()) return;
        NamespacedKey k = NamespacedKey.fromString(model);
        if (k != null) meta.setItemModel(k);
    }

    // ================================================================= invocation

    /** Graine maudite : lâchée par les zombies / squelettes. 8 + une citrouille taillée = citrouille sacrificielle. */
    public ItemStack seed(int amount) {
        ItemStack it = new ItemStack(Material.PUMPKIN_SEEDS, amount);
        ItemMeta m = it.getItemMeta();
        m.displayName(parse(plugin.msg("items.seed.name")));
        m.lore(lines(plugin.getConfig().getStringList("messages.items.seed.lore")));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(seedKey, PersistentDataType.BYTE, (byte) 1);
        model(m, plugin.getConfig().getString("models.seed"));
        it.setItemMeta(m);
        return it;
    }

    public ItemStack sacrifice() {
        ItemStack it = new ItemStack(Material.JACK_O_LANTERN);
        ItemMeta m = it.getItemMeta();
        m.displayName(parse(plugin.msg("items.sacrifice.name")));
        m.lore(lines(plugin.getConfig().getStringList("messages.items.sacrifice.lore")));
        m.setEnchantmentGlintOverride(true);
        m.setMaxStackSize(16);
        m.getPersistentDataContainer().set(sacrificeKey, PersistentDataType.BYTE, (byte) 1);
        model(m, plugin.getConfig().getString("models.sacrifice"));
        it.setItemMeta(m);
        return it;
    }

    public boolean isSeed(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(seedKey, PersistentDataType.BYTE);
    }

    public boolean isSacrifice(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(sacrificeKey, PersistentDataType.BYTE);
    }

    // ================================================================= reliques

    /** Objet "relique" : clic droit pour l'ajouter à sa collection (ensuite elle ne tombe plus jamais). */
    public ItemStack relicItem(Relic r) {
        ItemStack it = new ItemStack(r.icon());
        ItemMeta m = it.getItemMeta();
        m.displayName(parse(r.rarity().name(r.name(), 0)));
        List<Component> lore = new ArrayList<>();
        lore.add(parse(r.rarity().tag));
        lore.add(Component.empty());
        lore.addAll(lines(r.lore()));
        lore.addAll(effectLines(r));
        lore.add(Component.empty());
        lore.add(parse(plugin.msg("items.relic.claim")));
        m.lore(lore);
        m.setEnchantmentGlintOverride(true);
        m.addItemFlags(ItemFlag.values());
        m.getPersistentDataContainer().set(relicKey, PersistentDataType.STRING, r.id());
        model(m, r.itemModel());
        it.setItemMeta(m);
        return it;
    }

    public List<Component> effectLines(Relic r) {
        List<Component> out = new ArrayList<>();
        if (r.health() > 0) out.add(parse(plugin.msg("items.relic.health").replace("<n>", fmt(r.health()))));
        if (r.speed() > 0) out.add(parse(plugin.msg("items.relic.speed").replace("<n>", fmt(r.speed()))));
        if (r.regen() > 0) out.add(parse(plugin.msg("items.relic.regen").replace("<n>", fmt(r.regen() / 2))));
        if (r.trail() != null) out.add(parse(plugin.msg("items.relic.trail")));
        if (r.backpack()) out.add(parse(plugin.msg("items.relic.backpack")));
        return out;
    }

    static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    public String relicId(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(relicKey, PersistentDataType.STRING);
    }

    // ================================================================= faucille

    public ItemStack sickle() {
        ItemStack it = new ItemStack(Material.NETHERITE_HOE);
        ItemMeta m = it.getItemMeta();
        m.displayName(parse(Rarity.SECRET.name(plugin.msg("items.sickle.name"), 0)));
        List<Component> lore = new ArrayList<>();
        lore.add(parse(Rarity.SECRET.tag));
        lore.add(Component.empty());
        lore.addAll(lines(plugin.getConfig().getStringList("messages.items.sickle.lore")));
        m.lore(lore);
        var c = plugin.getConfig();
        m.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(new NamespacedKey(plugin, "sickle_damage"),
                c.getDouble("sickle.damage", 8) - 1, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        m.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(new NamespacedKey(plugin, "sickle_speed"),
                c.getDouble("sickle.attack-speed", 1.6) - 4, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        m.setUnbreakable(true);
        m.setEnchantmentGlintOverride(true);
        m.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        m.getPersistentDataContainer().set(sickleKey, PersistentDataType.BYTE, (byte) 1);
        model(m, c.getString("models.sickle"));
        it.setItemMeta(m);
        return it;
    }

    public boolean isSickle(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(sickleKey, PersistentDataType.BYTE);
    }

    // ================================================================= objets de butin simples (config)

    public ItemStack lootItem(Material mat, int amount, String name, Rarity rarity, List<String> lore, String model) {
        ItemStack it = new ItemStack(mat, Math.max(1, amount));
        ItemMeta m = it.getItemMeta();
        if (name != null) m.displayName(parse(rarity.name(name, 0)));
        List<Component> l = new ArrayList<>();
        if (name != null) {
            l.add(parse(rarity.tag));
            if (!lore.isEmpty()) l.add(Component.empty());
        }
        l.addAll(lines(lore));
        if (!l.isEmpty()) m.lore(l);
        if (rarity.ordinal() >= Rarity.EPIC.ordinal()) m.setEnchantmentGlintOverride(true);
        if (name != null) m.getPersistentDataContainer().set(lootKey, PersistentDataType.STRING, rarity.name());
        model(m, model);
        it.setItemMeta(m);
        return it;
    }
}
