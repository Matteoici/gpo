package fr.smpdrop;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;

/** Un objet possible dans la caisse. */
public final class LootEntry {

    public final Rarity rarity;
    public final Material material;
    public final int minAmount, maxAmount;
    public final double weight;
    public final String name;          // MiniMessage, peut être null
    public final List<String> lore;
    public final Map<Enchantment, int[]> enchants = new LinkedHashMap<>();
    public final PotionType potion;    // peut être null
    public final int max;              // nombre max par caisse (0 = illimité)
    public final List<ItemStack> bonus = new ArrayList<>();

    LootEntry(Rarity rarity, Material material, int[] amount, double weight, String name, List<String> lore,
              Map<String, String> enchantNames, String potionName, int max, List<String> bonusNames, Logger log) {
        this.rarity = rarity;
        this.material = material;
        this.minAmount = amount[0];
        this.maxAmount = amount[1];
        this.weight = weight;
        this.name = name;
        this.lore = lore;
        this.max = max;
        for (Map.Entry<String, String> e : enchantNames.entrySet()) {
            Enchantment ench = enchantment(e.getKey());
            if (ench == null) {
                log.warning("Enchantement inconnu : " + e.getKey() + " (" + material + ")");
                continue;
            }
            enchants.put(ench, Settings.range(e.getValue(), 1));
        }
        PotionType p = null;
        if (potionName != null && !potionName.isBlank()) {
            try {
                p = RegistryAccess.registryAccess().getRegistry(RegistryKey.POTION)
                        .get(NamespacedKey.minecraft(potionName.toLowerCase().trim()));
            } catch (Throwable ignored) {
            }
            if (p == null) log.warning("Potion inconnue : " + potionName);
        }
        this.potion = p;
        for (String b : bonusNames) {
            String[] parts = b.split(":");
            Material m = Material.matchMaterial(parts[0].trim());
            if (m == null) {
                log.warning("Objet bonus inconnu : " + b);
                continue;
            }
            int n = 1;
            try {
                if (parts.length > 1) n = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException ignored) {
            }
            bonus.add(new ItemStack(m, Math.max(1, n)));
        }
    }

    static Enchantment enchantment(String key) {
        try {
            return RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)
                    .get(NamespacedKey.minecraft(key.toLowerCase().trim()));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Nom affiché de l'objet (nom personnalisé ou nom du jeu). */
    public Component displayName() {
        if (name != null) return Texts.parse(name);
        return Component.translatable(material.translationKey());
    }

    /** Fabrique les piles d'objets (une potion = une pile, d'où la liste). */
    public List<ItemStack> create(Random r) {
        int amount = minAmount + (maxAmount > minAmount ? r.nextInt(maxAmount - minAmount + 1) : 0);
        ItemStack proto = new ItemStack(material, 1);
        // objet ordinaire (diamants, fer, nourriture...) : aucune étiquette, sinon il ne s'empile plus avec les normaux
        boolean special = name != null || !lore.isEmpty() || !enchants.isEmpty() || potion != null;
        ItemMeta meta = special ? proto.getItemMeta() : null;
        if (meta != null) {
            if (name != null) meta.displayName(Texts.parse(name));
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(Texts.parse(s));
            if (!l.isEmpty()) l.add(Component.empty());
            l.add(Texts.parse("<dark_gray>✦ </dark_gray>" + rarity.display() + " <dark_gray>• Largage aérien"));
            meta.lore(l);
            for (Map.Entry<Enchantment, int[]> e : enchants.entrySet()) {
                int[] lv = e.getValue();
                int level = lv[0] + (lv[1] > lv[0] ? r.nextInt(lv[1] - lv[0] + 1) : 0);
                meta.addEnchant(e.getKey(), level, true);
            }
            if (potion != null && meta instanceof PotionMeta pm) pm.setBasePotionType(potion);
            proto.setItemMeta(meta);
        }
        List<ItemStack> out = new ArrayList<>();
        int stack = Math.max(1, material.getMaxStackSize());
        while (amount > 0) {
            ItemStack it = proto.clone();
            int n = Math.min(stack, amount);
            it.setAmount(n);
            out.add(it);
            amount -= n;
        }
        for (ItemStack b : bonus) out.add(b.clone());
        return out;
    }
}
