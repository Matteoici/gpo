package fr.smpstart;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.List;
import java.util.Locale;

/** Kit de départ et récompenses en objets. */
public final class Kit {

    private final SmpStart plugin;

    Kit(SmpStart plugin) {
        this.plugin = plugin;
    }

    /** Donne le kit (les pièces d'armure sont équipées si l'emplacement est libre). */
    public void give(Player p) {
        giveItems(p, plugin.settings().kitItems());
        double money = plugin.settings().starterMoney();
        if (money > 0 && plugin.economy() != null && plugin.economy().give(p, money)) {
            plugin.texts().send(p, "kit.money", Texts.ph("amount", format(money)));
        }
        plugin.texts().send(p, "kit.received");
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
    }

    /** Ajoute des objets à l'inventaire, le reste tombe au sol. */
    public void giveItems(Player p, List<ItemStack> items) {
        PlayerInventory inv = p.getInventory();
        for (ItemStack it : items) {
            if (equip(inv, it)) continue;
            inv.addItem(it).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        }
    }

    /** "MATERIAL:quantité" -> objet. */
    public ItemStack parse(String s) {
        String[] parts = s.split(":");
        Material m = Material.matchMaterial(parts[0].trim());
        if (m == null || m.isAir()) {
            plugin.getLogger().warning("Objet de récompense inconnu : " + s);
            return null;
        }
        int amount = 1;
        if (parts.length > 1) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(parts[1].trim())));
            } catch (NumberFormatException ignored) {
                // quantité invalide : 1
            }
        }
        return new ItemStack(m, amount);
    }

    private boolean equip(PlayerInventory inv, ItemStack it) {
        String n = it.getType().name();
        if (n.endsWith("_HELMET") && inv.getHelmet() == null) {
            inv.setHelmet(it);
            return true;
        }
        if (n.endsWith("_CHESTPLATE") && inv.getChestplate() == null) {
            inv.setChestplate(it);
            return true;
        }
        if (n.endsWith("_LEGGINGS") && inv.getLeggings() == null) {
            inv.setLeggings(it);
            return true;
        }
        if (n.endsWith("_BOOTS") && inv.getBoots() == null) {
            inv.setBoots(it);
            return true;
        }
        return false;
    }

    public static String format(double v) {
        if (v == Math.floor(v)) return String.format(Locale.ROOT, "%,.0f", v).replace(',', ' ');
        return String.format(Locale.ROOT, "%,.2f", v).replace(',', ' ');
    }
}
