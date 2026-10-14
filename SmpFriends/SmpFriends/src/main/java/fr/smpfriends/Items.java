package fr.smpfriends;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;

/** Fabrique d'items pour les menus. */
public final class Items {

    private Items() {
    }

    public static ItemStack of(Material m, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name);
        if (lore != null) meta.lore(lore);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack glow(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack head(OfflinePlayer p, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) it.getItemMeta();
        // Pas de setOwningPlayer : pour un joueur hors ligne, le serveur interrogerait Mojang à chaque menu
        // ouvert (erreurs 429 "Couldn't look up profile properties" quand il y a beaucoup de têtes).
        // En ligne : son profil avec skin ; hors ligne : juste son nom, et c'est le jeu du joueur qui affiche le skin.
        org.bukkit.entity.Player online = p.getPlayer();
        meta.setPlayerProfile(online != null ? online.getPlayerProfile()
                : org.bukkit.Bukkit.createProfile(p.getUniqueId(), p.getName()));
        meta.displayName(name);
        if (lore != null) meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    /** Vitre de décor, sans infobulle. */
    public static ItemStack pane(Material m) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setHideTooltip(true);
        it.setItemMeta(meta);
        return it;
    }
}
