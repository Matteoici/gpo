package fr.smpspawners;

import fr.smpspawners.Defs.Drop;
import fr.smpspawners.Defs.Mob;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static fr.smpspawners.SmpSpawners.text;

/** Menu d'un spawner : le butin stocké (cliquer pour prendre), l'XP, tout récupérer, reprendre le spawner. */
final class SpawnerMenu implements InventoryHolder {

    private static final int PER_PAGE = 36;
    private final SmpSpawners plugin;
    private final VSpawner s;
    private final Player viewer;
    private final Inventory inv;
    private int page;
    private final List<Material> slots = new ArrayList<>();

    SpawnerMenu(SmpSpawners plugin, VSpawner s, Player viewer) {
        this.plugin = plugin;
        this.s = s;
        this.viewer = viewer;
        Mob m = plugin.mob(s.type);
        String title = (m == null ? "<white>" : m.rarity().color()) + "Spawner à " + (m == null ? s.type.name() : m.name())
                + (s.stack > 1 ? " <dark_gray>×" + s.stack : "");
        this.inv = Bukkit.createInventory(this, 54, SmpSpawners.MM.deserialize(title));
    }

    VSpawner spawner() {
        return s;
    }

    void open() {
        draw();
        viewer.openInventory(inv);
    }

    void refresh() {
        if (plugin.at(blockOf()) != s) {
            viewer.closeInventory();
            return;
        }
        draw();
    }

    private org.bukkit.block.Block blockOf() {
        var l = s.location();
        return l == null ? null : l.getBlock();
    }

    private static ItemStack icon(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(text(name));
        List<Component> l = new ArrayList<>();
        for (String x : lore) l.add(text(x));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    private void draw() {
        inv.clear();
        slots.clear();
        // butin : une case par pile de 64
        List<ItemStack> piles = new ArrayList<>();
        List<Material> mats = new ArrayList<>();
        for (Map.Entry<Material, Long> en : s.items.entrySet()) {
            long n = en.getValue();
            int max = en.getKey().getMaxStackSize();
            while (n > 0) {
                int k = (int) Math.min(max, n);
                piles.add(new ItemStack(en.getKey(), k));
                mats.add(en.getKey());
                n -= k;
            }
        }
        int pages = Math.max(1, (piles.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx >= piles.size()) break;
            ItemStack it = piles.get(idx);
            ItemMeta meta = it.getItemMeta();
            meta.lore(List.of(text("<gray>Stock : <white>" + s.items.getOrDefault(mats.get(idx), 0L)), text("<yellow>Clic : prendre cette pile")));
            it.setItemMeta(meta);
            inv.setItem(i, it);
            slots.add(mats.get(idx));
        }
        for (int i = 36; i < 45; i++) inv.setItem(i, icon(Material.BLACK_STAINED_GLASS_PANE, " ", List.of()));
        Mob m = plugin.mob(s.type);
        // infos
        List<String> info = new ArrayList<>();
        if (m != null) {
            info.add("<gray>Rareté : " + m.rarity().label());
            info.add("<gray>Spawners empilés : <white>" + s.stack + " <dark_gray>/ " + plugin.maxStack);
            info.add("<gray>Production : <white>" + s.stack + " kill(s) toutes les " + m.interval() + "s");
            info.add("<gray>XP par kill : <yellow>" + m.xp());
            info.add("");
            info.add("<gray>Butin par kill :");
            for (Drop d : m.drops()) info.add("<dark_gray>• <white>" + Names.item(d.mat()) + " <gray>" + SmpSpawners.dropText(d));
            info.add("");
            info.add("<gray>Stockage : <white>" + s.totalItems() + " <dark_gray>/ " + plugin.itemsPerStack * s.stack + " objets");
            if (plugin.full(s)) info.add("<red>Plein ! Vide-le pour qu'il continue.");
            info.add("<gray>Propriétaire : <white>" + s.ownerName);
            if (plugin.requirePlayer) info.add("<dark_gray>Produit quand un joueur est à moins de " + plugin.playerRange + " blocs.");
        }
        inv.setItem(45, icon(m == null ? Material.SPAWNER : m.egg(), (m == null ? "<white>" : m.rarity().color()) + "<b>"
                + (m == null ? s.type.name() : m.name()), info));
        inv.setItem(47, icon(Material.EXPERIENCE_BOTTLE, "<yellow><b>Récupérer l'XP", List.of("<gray>Stockée : <yellow>" + s.xp + " XP",
                "<dark_gray>Max : " + plugin.xpPerStack * s.stack, "", "<yellow>Clic : tout prendre")));
        if (page > 0) inv.setItem(48, icon(Material.ARROW, "<white>Page précédente", List.of("<gray>" + (page + 1) + " / " + pages)));
        inv.setItem(49, icon(Material.CHEST, "<green><b>Tout récupérer", List.of("<gray>Les objets vont dans ton inventaire", "<gray>(ce qui ne rentre pas reste dedans)",
                "<gray>+ l'XP", "", "<yellow>Clic : tout prendre")));
        if (page < pages - 1) inv.setItem(50, icon(Material.ARROW, "<white>Page suivante", List.of("<gray>" + (page + 1) + " / " + pages)));
        inv.setItem(51, icon(Material.SPAWNER, "<aqua>Empiler", List.of("<gray>Clic droit sur ce spawner avec un", "<gray>spawner du même mob : +1",
                "<gray>(accroupi : toute la pile)", "<gray>Chaque spawner empilé = production ×2, ×3...")));
        inv.setItem(52, icon(Material.IRON_PICKAXE, "<red><b>Reprendre le spawner", List.of("<gray>Le spawner, son butin et son XP", "<gray>vont dans ton inventaire.",
                "", "<yellow>Accroupi + clic : reprendre")));
        inv.setItem(53, icon(Material.BARRIER, "<red>Fermer", List.of()));
    }

    void click(int slot, ClickType type) {
        if (plugin.at(blockOf()) != s) {
            viewer.closeInventory();
            return;
        }
        if (slot >= 0 && slot < slots.size()) {
            Material mat = slots.get(slot);
            long have = s.items.getOrDefault(mat, 0L);
            int want = (int) Math.min(mat.getMaxStackSize(), have);
            if (want <= 0) return;
            var rest = viewer.getInventory().addItem(new ItemStack(mat, want));
            int left = rest.values().stream().mapToInt(ItemStack::getAmount).sum();
            int taken = want - left;
            if (taken <= 0) {
                plugin.msg(viewer, "<red>Ton inventaire est plein.");
                return;
            }
            s.take(mat, taken);
            plugin.markDirty();
            viewer.playSound(viewer.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
            draw();
            return;
        }
        switch (slot) {
            case 47 -> takeXp();
            case 48 -> {
                page--;
                draw();
            }
            case 49 -> {
                takeAll();
                takeXp();
            }
            case 50 -> {
                page++;
                draw();
            }
            case 52 -> {
                if (!type.isShiftClick()) {
                    plugin.msg(viewer, "<gray>Accroupis-toi (Maj + clic) pour reprendre le spawner.");
                    return;
                }
                viewer.closeInventory();
                plugin.pickup(viewer, s);
                plugin.msg(viewer, "<green>Spawner repris avec tout son contenu.");
            }
            case 53 -> viewer.closeInventory();
            default -> {
            }
        }
    }

    private void takeXp() {
        if (s.xp <= 0) return;
        int n = (int) Math.min(Integer.MAX_VALUE, s.xp);
        viewer.giveExp(n);
        s.xp -= n;
        plugin.markDirty();
        viewer.playSound(viewer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1f);
        draw();
    }

    private void takeAll() {
        boolean full = false;
        long moved = 0;
        for (Material mat : new ArrayList<>(s.items.keySet())) {
            long have = s.items.getOrDefault(mat, 0L);
            while (have > 0) {
                int want = (int) Math.min(mat.getMaxStackSize(), have);
                var rest = viewer.getInventory().addItem(new ItemStack(mat, want));
                int left = rest.values().stream().mapToInt(ItemStack::getAmount).sum();
                int taken = want - left;
                s.take(mat, taken);
                have -= taken;
                moved += taken;
                if (left > 0) {
                    full = true;
                    break;
                }
            }
            if (full) break;
        }
        if (moved > 0) {
            plugin.markDirty();
            viewer.playSound(viewer.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 0.9f);
        }
        if (full) plugin.msg(viewer, "<yellow>Ton inventaire est plein : le reste est toujours dans le spawner.");
        draw();
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }
}
