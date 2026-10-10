package fr.smpguilds.gui;

import fr.smpguilds.Guild;
import fr.smpguilds.GuildManager;
import fr.smpguilds.Items;
import fr.smpguilds.Messages;
import fr.smpguilds.SmpGuilds;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Base de tous les menus : un inventaire + une action par case. */
public abstract class Menu implements InventoryHolder {

    protected final SmpGuilds plugin;
    protected final Player viewer;
    private final Inventory inventory;
    private final Map<Integer, Consumer<ClickType>> actions = new HashMap<>();

    protected Menu(SmpGuilds plugin, Player viewer, int rows, Component title) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    /** Dessine le contenu du menu. */
    protected abstract void build();

    public void open() {
        redraw();
        viewer.openInventory(inventory);
    }

    public void redraw() {
        inventory.clear();
        actions.clear();
        build();
    }

    public void click(int slot, ClickType type) {
        Consumer<ClickType> a = actions.get(slot);
        if (a == null) return;
        viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        a.accept(type);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    // ------------------------------------------------------------ helpers

    protected int size() {
        return inventory.getSize();
    }

    protected void set(int slot, ItemStack it) {
        inventory.setItem(slot, it);
    }

    protected void button(int slot, ItemStack it, Consumer<ClickType> action) {
        inventory.setItem(slot, it);
        actions.put(slot, action);
    }

    protected ItemStack item(Material m, String key, TagResolver... r) {
        return Items.of(m, msg().get(key + ".name", r), msg().list(key + ".lore", r));
    }

    /** Remplit les cases vides : bordure à la couleur de la guilde, intérieur gris. */
    protected void fill(NamedTextColor color) {
        Material border = Menus.glass(color);
        Material inner = Material.GRAY_STAINED_GLASS_PANE;
        int rows = size() / 9;
        for (int i = 0; i < size(); i++) {
            if (inventory.getItem(i) != null) continue;
            int r = i / 9, c = i % 9;
            boolean edge = r == 0 || r == rows - 1 || c == 0 || c == 8;
            inventory.setItem(i, Items.filler(edge ? border : inner));
        }
    }

    protected void back(int slot, Runnable to) {
        button(slot, item(Material.ARROW, "gui.common.back"), t -> to.run());
    }

    protected void close(int slot) {
        button(slot, item(Material.BARRIER, "gui.common.close"), t -> viewer.closeInventory());
    }

    /** Boutons de pages (bas du menu). */
    protected void pages(int page, int total, Consumer<Integer> go) {
        int last = size() - 1;
        if (page > 0) button(last - 8, item(Material.SPECTRAL_ARROW, "gui.common.previous",
                Messages.ph("page", page), Messages.ph("pages", total)), t -> go.accept(page - 1));
        if (page < total - 1) button(last, item(Material.SPECTRAL_ARROW, "gui.common.next",
                Messages.ph("page", page + 2), Messages.ph("pages", total)), t -> go.accept(page + 1));
    }

    protected Messages msg() {
        return plugin.messages();
    }

    protected GuildManager gm() {
        return plugin.guilds();
    }

    protected Guild myGuild() {
        return gm().of(viewer);
    }

    protected static int pageCount(List<?> list, int perPage) {
        return Math.max(1, (list.size() + perPage - 1) / perPage);
    }
}
