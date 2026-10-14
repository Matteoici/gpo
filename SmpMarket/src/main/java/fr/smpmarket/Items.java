package fr.smpmarket;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Base64;
import java.util.List;

/** Fabrication d'items pour les menus + (dé)sérialisation des items en texte. */
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

    public static ItemStack decorate(ItemStack base, Component name, List<Component> lore) {
        ItemStack it = base.clone();
        it.setAmount(1);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack head(OfflinePlayer owner, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) it.getItemMeta();
        // Pas de setOwningPlayer : pour un joueur hors ligne, le serveur interrogerait Mojang à chaque menu
        // ouvert (erreurs 429 "Couldn't look up profile properties" quand il y a beaucoup de têtes).
        // En ligne : son profil avec skin ; hors ligne : juste son nom, et c'est le jeu du joueur qui affiche le skin.
        org.bukkit.entity.Player online = owner.getPlayer();
        meta.setPlayerProfile(online != null ? online.getPlayerProfile()
                : org.bukkit.Bukkit.createProfile(owner.getUniqueId(), owner.getName()));
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

    public static String encode(ItemStack it) {
        if (it == null || it.getType().isAir()) return "";
        return Base64.getEncoder().encodeToString(it.serializeAsBytes());
    }

    public static ItemStack decode(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(s));
        } catch (Exception e) {
            return null;
        }
    }
}
