package fr.smpboss;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

import static fr.smpboss.SmpBoss.parse;

/**
 * Armurerie (depuis /reliques) : on y range ses armes de boss et ses tenues, à l'abri.
 * Clic sur un objet de ton inventaire = le ranger (une relique s'ajoute directement à ta collection).
 * Clic sur un objet rangé = le reprendre.
 */
public final class ArmoryMenu implements InventoryHolder {

    private static final int PER_PAGE = 45, PREV = 45, BACK = 48, INFO = 49, NEXT = 53;

    private final SmpBoss plugin;
    private final Player viewer;
    private final Inventory inv;
    private int page;

    public ArmoryMenu(SmpBoss plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.inv = Bukkit.createInventory(this, 54, parse(plugin.msg("armory.title")));
        draw();
    }

    public void open() {
        viewer.openInventory(inv);
        viewer.playSound(Sound.sound(Key.key("minecraft", "block.chest.open"), Sound.Source.MASTER, 0.6f, 1.1f));
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    private int max() {
        return Math.max(9, plugin.getConfig().getInt("armory.max-items", 90));
    }

    void draw() {
        inv.clear();
        PlayerData d = plugin.store().of(viewer.getUniqueId());
        int pages = Math.max(1, (d.armory.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx < d.armory.size()) inv.setItem(i, d.armory.get(idx).clone());
        }
        for (int i = 45; i < 54; i++) inv.setItem(i, pane());
        if (page > 0) inv.setItem(PREV, named(Material.ARROW, "menu.prev"));
        if (page < pages - 1) inv.setItem(NEXT, named(Material.ARROW, "menu.next"));
        inv.setItem(BACK, named(Material.NETHER_STAR, "armory.back"));
        ItemStack info = named(Material.ARMOR_STAND, "armory.info");
        ItemMeta m = info.getItemMeta();
        List<Component> lore = new ArrayList<>();
        for (String s : plugin.getConfig().getStringList("messages.armory.info.lore"))
            lore.add(parse(s.replace("<n>", String.valueOf(d.armory.size())).replace("<max>", String.valueOf(max()))));
        m.lore(lore);
        info.setItemMeta(m);
        inv.setItem(INFO, info);
    }

    /** Clic dans le menu (haut). */
    public void clickTop(int slot) {
        PlayerData d = plugin.store().of(viewer.getUniqueId());
        if (slot == BACK) {
            new RelicMenu(plugin, viewer).open();
            return;
        }
        if (slot == PREV && page > 0) {
            page--;
            draw();
            return;
        }
        if (slot == NEXT) {
            page++;
            draw();
            return;
        }
        if (slot >= PER_PAGE) return;
        int idx = page * PER_PAGE + slot;
        if (idx >= d.armory.size()) return;
        if (viewer.getInventory().firstEmpty() == -1) {
            viewer.sendMessage(parse(plugin.msg("armory.full-inventory")));
            sound("block.chest.locked", 1f);
            return;
        }
        ItemStack it = d.armory.remove(idx);
        viewer.getInventory().addItem(it);
        plugin.store().dirty();
        sound("entity.item.pickup", 1f);
        draw();
    }

    /** Clic sur un objet de son inventaire : on le range. */
    public void clickBottom(int slot) {
        store(plugin, viewer, slot);
        draw();
    }

    /**
     * Range l'objet de cette case de l'inventaire : relique -> collection, arme / tenue -> armurerie.
     * Utilisé aussi depuis /reliques. Renvoie vrai si quelque chose a été rangé.
     */
    static boolean store(SmpBoss plugin, Player p, int slot) {
        ItemStack it = p.getInventory().getItem(slot);
        if (it == null || it.getType().isAir()) return false;
        PlayerData d = plugin.store().of(p.getUniqueId());
        // relique -> collection (comme un clic droit avec)
        String relicId = plugin.items().relicId(it);
        if (relicId != null && !relicId.equals("backpack-display")) {
            Relic r = plugin.relics().get(relicId);
            if (r != null && plugin.relics().claim(p, r)) {
                it.setAmount(it.getAmount() - 1);
                p.getInventory().setItem(slot, it.getAmount() <= 0 ? null : it);
                p.sendMessage(parse(plugin.msg("relics.claimed").replace("<relic>", r.rarity().name(r.name(), 0))));
                p.playSound(Sound.sound(Key.key("minecraft", "item.armor.equip_netherite"), Sound.Source.MASTER, 1f, 1.2f));
                return true;
            }
            // déjà dans la collection : on range l'objet en double dans l'armurerie
        }
        boolean weapon = plugin.weapons().id(it) != null;
        if (!weapon && relicId == null) {
            p.sendMessage(parse(plugin.msg("armory.not-storable")));
            p.playSound(Sound.sound(Key.key("minecraft", "block.chest.locked"), Sound.Source.MASTER, 0.7f, 1.3f));
            return false;
        }
        int max = Math.max(9, plugin.getConfig().getInt("armory.max-items", 90));
        if (d.armory.size() >= max) {
            p.sendMessage(parse(plugin.msg("armory.full").replace("<max>", String.valueOf(max))));
            return false;
        }
        d.armory.add(it.clone());
        p.getInventory().setItem(slot, null);
        plugin.store().dirty();
        p.sendMessage(parse(plugin.msg("armory.stored")));
        p.playSound(Sound.sound(Key.key("minecraft", "item.armor.equip_chain"), Sound.Source.MASTER, 1f, 1f));
        return true;
    }

    private void sound(String k, float pitch) {
        viewer.playSound(Sound.sound(Key.key("minecraft", k), Sound.Source.MASTER, 1f, pitch));
    }

    private ItemStack named(Material mat, String key) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.displayName(parse(plugin.msg(key + ".name")));
        List<Component> lore = new ArrayList<>();
        for (String s : plugin.getConfig().getStringList("messages." + key + ".lore")) lore.add(parse(s));
        m.lore(lore);
        m.addItemFlags(ItemFlag.values());
        it.setItemMeta(m);
        return it;
    }

    private static ItemStack pane() {
        ItemStack it = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = it.getItemMeta();
        meta.setHideTooltip(true);
        it.setItemMeta(meta);
        return it;
    }
}
