package fr.smpdrop;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Liasses de billets du largage : un vrai objet (on peut se le faire voler !), clic droit pour encaisser l'argent (Vault).
 */
final class Banknotes implements Listener {

    static final NamespacedKey KEY = new NamespacedKey("smpdrop", "money");

    static String fmt(long v) {
        return String.format(java.util.Locale.FRANCE, "%,d", v).replace('\u202f', ' ').replace('\u00a0', ' ');
    }

    static ItemStack note(long value, Rarity rarity) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.displayName(Texts.parse("<green><b>💵 Liasse de billets</b> <white>" + fmt(value) + " " + LootEntry.symbol));
        List<Component> lore = new ArrayList<>();
        lore.add(Texts.parse("<gray>Clic droit pour encaisser l'argent."));
        lore.add(Texts.parse("<dark_gray>On peut te la voler tant que tu ne l'as pas encaissée !"));
        lore.add(Component.empty());
        lore.add(Texts.parse("<dark_gray>✦ </dark_gray>" + rarity.display() + " <dark_gray>• Largage aérien"));
        m.lore(lore);
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(KEY, PersistentDataType.LONG, value);
        it.setItemMeta(m);
        return it;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null || it.getType() != Material.PAPER || !it.hasItemMeta()) return;
        Long v = it.getItemMeta().getPersistentDataContainer().get(KEY, PersistentDataType.LONG);
        if (v == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        long total = v * it.getAmount();
        if (Bukkit.getPluginManager().getPlugin("Vault") == null || !Bank.deposit(p, total)) {
            p.sendMessage(Texts.parse("<red>Impossible d'encaisser pour l'instant (économie indisponible). Garde ta liasse."));
            return;
        }
        p.getInventory().setItemInMainHand(null);
        p.sendMessage(Texts.parse("<green>💵 +" + fmt(total) + " " + LootEntry.symbol + " <gray>encaissés !"));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.6f);
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.4f);
    }

    /** Vault (chargé seulement si le plugin est là). */
    static final class Bank {
        static boolean deposit(Player p, long amount) {
            var rsp = Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
            if (rsp == null) return false;
            return rsp.getProvider().depositPlayer(p, amount).transactionSuccess();
        }
    }
}
