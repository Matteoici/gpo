package fr.gpotrade;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Lien avec SmpBoss (sans dépendance) : mettre une relique de sa collection dans l'échange,
 * et la ranger directement dans la collection de celui qui la reçoit.
 */
final class RelicHook {

    private RelicHook() {
    }

    private static Plugin boss() {
        Plugin p = Bukkit.getPluginManager().getPlugin("SmpBoss");
        return p != null && p.isEnabled() ? p : null;
    }

    static boolean available() {
        return boss() != null;
    }

    @SuppressWarnings("unchecked")
    static List<String> ids(Player p) {
        try {
            Plugin b = boss();
            if (b == null) return List.of();
            return (List<String>) b.getClass().getMethod("tradeableRelics", Player.class).invoke(b, p);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return List.of();
        }
    }

    static ItemStack display(String id) {
        try {
            Plugin b = boss();
            return b == null ? null : (ItemStack) b.getClass().getMethod("relicDisplay", String.class).invoke(b, id);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    static ItemStack take(Player p, String id) {
        try {
            Plugin b = boss();
            return b == null ? null : (ItemStack) b.getClass().getMethod("takeRelicForTrade", Player.class, String.class).invoke(b, p, id);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Les reliques reçues (ou rendues) vont directement dans la collection, si le joueur ne les a pas déjà. */
    static void claimAll(Player p) {
        try {
            Plugin b = boss();
            if (b != null) b.getClass().getMethod("claimRelicItems", Player.class).invoke(b, p);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    /** Fenêtre de choix d'une relique pendant un échange. */
    static final class Picker implements InventoryHolder {
        final TradeSession session;
        final int index;
        final List<String> ids = new ArrayList<>();
        Inventory inventory;
        boolean picked;

        Picker(TradeSession session, int index) {
            this.session = session;
            this.index = index;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
