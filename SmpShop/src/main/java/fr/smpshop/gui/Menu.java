package fr.smpshop.gui;

import fr.smpshop.Items;
import fr.smpshop.SmpShop;
import fr.smpshop.Texts;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static fr.smpshop.Texts.ph;

/** Base des menus : un inventaire + une action par case. */
public abstract class Menu implements InventoryHolder {

    /** Cases intérieures d'un menu de 6 lignes (28 cases). */
    protected static final int[] GRID = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    protected final SmpShop plugin;
    protected final Player viewer;
    private final Inventory inventory;
    private final Map<Integer, Consumer<ClickType>> actions = new HashMap<>();

    protected Menu(SmpShop plugin, Player viewer, int rows, Component title) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    protected abstract void build();

    public void open() {
        redraw();
        viewer.openInventory(inventory);
    }

    public void redraw() {
        inventory.clear();
        actions.clear();
        build();
        fill();
        tagAll();
    }

    /** Marque les objets du menu : jamais vendables, jamais de prix affiché dessus. */
    private void tagAll() {
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack it = inventory.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            var meta = it.getItemMeta();
            if (meta == null) continue;
            meta.getPersistentDataContainer().set(plugin.menuKey(), org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(meta);
            inventory.setItem(i, it);
        }
    }

    public void click(int slot, ClickType type) {
        Consumer<ClickType> a = actions.get(slot);
        if (a == null) return;
        viewer.playSound(Sound.sound(Key.key("minecraft", "ui.button.click"), Sound.Source.MASTER, 0.5f, 1.3f));
        a.accept(type);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    // ------------------------------------------------------------ helpers

    protected fr.smpshop.Settings s() {
        return plugin.settings();
    }

    protected String money(double v) {
        return plugin.settings().money(v);
    }

    protected Texts t() {
        return plugin.texts();
    }

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

    /** Objet dont le nom et la description viennent de messages.yml (clé.name / clé.lore). */
    protected ItemStack item(Material m, String key, TagResolver... r) {
        return Items.of(m, t().get(key + ".name", r), t().list(key + ".lore", r));
    }

    /** Bordure sombre, intérieur gris. */
    private void fill() {
        int rows = size() / 9;
        for (int i = 0; i < size(); i++) {
            if (inventory.getItem(i) != null) continue;
            int r = i / 9, c = i % 9;
            boolean edge = r == 0 || r == rows - 1 || c == 0 || c == 8;
            inventory.setItem(i, Items.filler(edge ? Material.BLACK_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE));
        }
    }

    protected void back(int slot, Runnable to) {
        button(slot, item(Material.ARROW, "gui.common.back"), t -> to.run());
    }

    protected void close(int slot) {
        button(slot, item(Material.BARRIER, "gui.common.close"), t -> viewer.closeInventory());
    }

    /** Boutons de pages en bas du menu. */
    protected void pages(int page, int total, Consumer<Integer> go) {
        int last = size() - 1;
        if (page > 0) button(last - 8, item(Material.SPECTRAL_ARROW, "gui.common.previous", ph("page", page),
                ph("pages", total)), t -> go.accept(page - 1));
        if (page < total - 1) button(last, item(Material.SPECTRAL_ARROW, "gui.common.next", ph("page", page + 2),
                ph("pages", total)), t -> go.accept(page + 1));
    }

    protected static int pageCount(int items, int perPage) {
        return Math.max(1, (items + perPage - 1) / perPage);
    }
}
