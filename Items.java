package fr.smpguard;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;

/** Fabrication des objets des menus. */
public final class Items {

    private Items() {
    }

    public static ItemStack of(Material m, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name);
        if (lore != null && !lore.isEmpty()) meta.lore(lore);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack head(OfflinePlayer owner, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) it.getItemMeta();
        meta.setOwningPlayer(owner);
        meta.displayName(name);
        if (lore != null && !lore.isEmpty()) meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack glow(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack filler(Material m) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setHideTooltip(true);
        it.setItemMeta(meta);
        return it;
    }
}
