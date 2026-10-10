package fr.smpfriends.menu;

import fr.smpfriends.FriendsPlugin;
import fr.smpfriends.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Base de tous les menus : un coffre, un rendu, une gestion des clics. */
public abstract class Menu implements InventoryHolder {

    protected final FriendsPlugin plugin;
    protected final Player viewer;
    protected final Inventory inv;

    protected Menu(FriendsPlugin plugin, Player viewer, int rows, Component title) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.inv = Bukkit.createInventory(this, rows * 9, title);
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    /** Redessine le contenu. */
    public abstract void render();

    /** Clic du joueur sur un slot du menu. */
    public abstract void click(int slot, ClickType type);

    /** Rafraîchi automatiquement toutes les 2 s (positions, statut en ligne...). */
    public boolean autoRefresh() {
        return false;
    }

    public void open() {
        render();
        viewer.openInventory(inv);
    }

    /** Bordure de vitres + fond. */
    protected void frame(Material border, Material fill) {
        int size = inv.getSize();
        for (int s = 0; s < size; s++) {
            int r = s / 9, c = s % 9;
            boolean edge = r == 0 || r == size / 9 - 1 || c == 0 || c == 8;
            inv.setItem(s, Items.pane(edge ? border : fill));
        }
    }

    protected void click() {
        viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
    }

    protected void deny() {
        viewer.playSound(viewer.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
    }
}
