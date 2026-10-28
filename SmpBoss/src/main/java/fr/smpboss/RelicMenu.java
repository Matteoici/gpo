package fr.smpboss;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static fr.smpboss.SmpBoss.parse;

/**
 * /reliques — trois zones bien séparées par des bandes orange :
 * <pre>
 *  ┌ bande orange ─────── ★ infos ─────────────────┐
 *  │ ÉQUIPÉES : [cadre][relique][cadre] [sac] [cadre][relique][cadre] │
 *  ├ bande orange ─────── ▼ COLLECTION ▼ ──────────┤
 *  │ toutes les reliques du serveur (trouvées en couleur, les autres grisées) │
 *  └ bande orange : ◀  cosmétiques on/off  ✕  coffre de butin  ▶ ┘
 * </pre>
 * Cadres animés : arc-en-ciel pour les légendaires, noir et blanc qui clignote pour les secrètes.
 * Clic = équiper / retirer · Shift + clic = voir la relique en 3D devant soi.
 */
public final class RelicMenu implements InventoryHolder {

    private static final int INFO = 4, BAG = 13, LABEL = 22, PREV = 45, TOGGLE = 47, CLOSE = 49, STASH = 51, NEXT = 53;
    private static final int[] COLLECTION = {28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    private static final Material BAND = Material.ORANGE_STAINED_GLASS_PANE;
    private static final Material[] RAINBOW = {Material.RED_STAINED_GLASS_PANE, Material.ORANGE_STAINED_GLASS_PANE,
            Material.YELLOW_STAINED_GLASS_PANE, Material.LIME_STAINED_GLASS_PANE, Material.LIGHT_BLUE_STAINED_GLASS_PANE,
            Material.BLUE_STAINED_GLASS_PANE, Material.PURPLE_STAINED_GLASS_PANE, Material.MAGENTA_STAINED_GLASS_PANE};

    private final SmpBoss plugin;
    private final Player viewer;
    private final Inventory inv;
    private int frame;
    private int page;

    public RelicMenu(SmpBoss plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.inv = Bukkit.createInventory(this, 54, parse(plugin.msg("menu.title-v2")));
        draw();
    }

    public void open() {
        viewer.openInventory(inv);
        plugin.openMenus().add(this);
        viewer.playSound(Sound.sound(Key.key("minecraft", "block.chest.open"), Sound.Source.MASTER, 0.6f, 1.3f));
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    private int slots() {
        return Math.min(3, plugin.relics().maxEquipped);
    }

    /** Emplacements équipés (centre) selon le nombre autorisé. */
    private int[] equipSlots() {
        return switch (slots()) {
            case 1 -> new int[]{11};
            case 2 -> new int[]{11, 15};
            default -> new int[]{10, 13, 16};
        };
    }

    private boolean bagInRow() {
        return slots() <= 2;
    }

    /** Tout le catalogue, trié : d'abord ce qu'on a, puis par rareté. */
    private List<Relic> catalog() {
        PlayerData d = plugin.store().of(viewer.getUniqueId());
        List<Relic> all = new ArrayList<>(plugin.relics().all().values());
        all.sort(Comparator.comparing((Relic r) -> !d.relics.contains(r.id())).thenComparing(r -> r.rarity().ordinal()));
        return all;
    }

    private int pages() {
        return Math.max(1, (int) Math.ceil(plugin.relics().all().size() / (double) COLLECTION.length));
    }

    // ================================================================= dessin

    public void draw() {
        inv.clear();
        PlayerData d = plugin.store().of(viewer.getUniqueId());
        // fond : bandes orange en haut, au milieu et en bas ; noir ailleurs
        for (int i = 0; i < 54; i++) {
            int row = i / 9;
            inv.setItem(i, pane(row == 0 || row == 2 || row == 5 ? BAND : Material.BLACK_STAINED_GLASS_PANE));
        }
        // zone équipée
        int[] eq = equipSlots();
        for (int i = 0; i < eq.length; i++) {
            Relic r = plugin.relics().get(i < d.equipped.size() ? d.equipped.get(i) : null);
            inv.setItem(eq[i], r == null ? emptySlot() : icon(r, true, true));
        }
        drawFrames();
        if (bagInRow()) {
            // le sac n'apparaît que pour ceux qui l'ont (pas de spoil)
            if (plugin.relics().hasBackpack(viewer)) inv.setItem(BAG, named(Material.BUNDLE, "menu.open-backpack", null));
            else if (plugin.relics().ownsBackpack(viewer)) inv.setItem(BAG, named(Material.BUNDLE, "menu.bag-unequipped", null));
        }
        // collection
        List<Relic> all = catalog();
        page = Math.max(0, Math.min(page, pages() - 1));
        for (int i = 0; i < COLLECTION.length; i++) {
            int idx = page * COLLECTION.length + i;
            if (idx >= all.size()) {
                inv.setItem(COLLECTION[i], pane(Material.GRAY_STAINED_GLASS_PANE));
                continue;
            }
            Relic r = all.get(idx);
            inv.setItem(COLLECTION[i], d.relics.contains(r.id()) ? icon(r, d.equipped.contains(r.id()), false) : locked(r));
        }
        inv.setItem(INFO, info(d));
        inv.setItem(LABEL, label(d));
        // barre du bas
        if (page > 0) inv.setItem(PREV, named(Material.ARROW, "menu.prev", null));
        if (page < pages() - 1) inv.setItem(NEXT, named(Material.ARROW, "menu.next", null));
        inv.setItem(TOGGLE, named(d.hideCosmetics ? Material.ENDER_EYE : Material.ENDER_PEARL,
                d.hideCosmetics ? "menu.cosmetics-off" : "menu.cosmetics-on", null));
        inv.setItem(CLOSE, named(Material.BARRIER, "menu.close", null));
        ItemStack stash = named(d.stash.isEmpty() ? Material.BARREL : Material.CHEST, d.stash.isEmpty() ? "menu.stash-empty" : "menu.stash",
                String.valueOf(d.stash.stream().mapToInt(ItemStack::getAmount).sum()));
        if (!d.stash.isEmpty()) {
            ItemMeta m = stash.getItemMeta();
            m.setEnchantmentGlintOverride(true);
            stash.setItemMeta(m);
        }
        inv.setItem(STASH, stash);
    }

    /** Toutes les 4 ticks : les cadres animés et le nom arc-en-ciel des légendaires. */
    public void animate() {
        frame++;
        drawFrames();
        PlayerData d = plugin.store().of(viewer.getUniqueId());
        int[] eq = equipSlots();
        for (int i = 0; i < eq.length && i < d.equipped.size(); i++) {
            Relic r = plugin.relics().get(d.equipped.get(i));
            if (r != null && r.rarity().ordinal() >= Rarity.LEGENDARY.ordinal()) inv.setItem(eq[i], icon(r, true, true));
        }
    }

    private void drawFrames() {
        PlayerData d = plugin.store().of(viewer.getUniqueId());
        int[] eq = equipSlots();
        for (int i = 0; i < eq.length; i++) {
            Relic r = plugin.relics().get(i < d.equipped.size() ? d.equipped.get(i) : null);
            int c = eq[i];
            int[] around = {c - 1, c + 1};
            for (int k = 0; k < around.length; k++) {
                int s = around[k];
                if (s < 9 || s > 17 || (bagInRow() && s == BAG)) continue;
                inv.setItem(s, pane(frameColor(r, k + i)));
            }
        }
    }

    private Material frameColor(Relic r, int k) {
        if (r == null) return Material.GRAY_STAINED_GLASS_PANE;
        return switch (r.rarity()) {
            case COMMON -> Material.WHITE_STAINED_GLASS_PANE;
            case RARE -> Material.LIGHT_BLUE_STAINED_GLASS_PANE;
            case EPIC -> Material.PURPLE_STAINED_GLASS_PANE;
            case LEGENDARY -> RAINBOW[Math.floorMod(frame + k * 3, RAINBOW.length)];
            case SECRET -> (frame + k) % 2 == 0 ? Material.WHITE_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
            case PRESTIGE -> switch (Math.floorMod(frame + k, 3)) {
                case 0 -> Material.BLUE_STAINED_GLASS_PANE;
                case 1 -> Material.BLACK_STAINED_GLASS_PANE;
                default -> Material.LIGHT_BLUE_STAINED_GLASS_PANE;
            };
        };
    }

    private ItemStack icon(Relic r, boolean equipped, boolean inEquipRow) {
        ItemStack it = new ItemStack(r.icon());
        ItemMeta m = it.getItemMeta();
        m.displayName(parse(r.rarity().name(r.name(), frame % 10)));
        List<Component> lore = new ArrayList<>();
        lore.add(parse(r.rarity().tag + (equipped ? "  <green>✔ " + plugin.msg("menu.equipped-tag") : "")));
        lore.add(Component.empty());
        for (String s : r.lore()) lore.add(parse(s));
        lore.addAll(plugin.items().effectLines(r));
        String look = plugin.msg("menu.visual." + (r.visual() == null || r.visual().isBlank() ? "none" : r.visual()));
        if (!look.startsWith("<red>[")) lore.add(parse(look));
        lore.add(Component.empty());
        lore.add(parse(plugin.msg(equipped ? "menu.click-unequip" : "menu.click-equip")));
        lore.add(parse(plugin.msg("menu.click-inspect")));
        if (!r.backpack()) lore.add(parse(plugin.msg("menu.click-trade")));
        m.lore(lore);
        if (equipped) m.setEnchantmentGlintOverride(true);
        m.addItemFlags(ItemFlag.values());
        if (r.itemModel() != null && !r.itemModel().isBlank()) {
            var k = org.bukkit.NamespacedKey.fromString(r.itemModel());
            if (k != null) m.setItemModel(k);
        }
        it.setItemMeta(m);
        return it;
    }

    private ItemStack locked(Relic r) {
        // relique pas encore trouvée : ni nom, ni rareté, ni apparence (pas de spoil)
        ItemStack it = new ItemStack(Material.GRAY_DYE);
        ItemMeta m = it.getItemMeta();
        m.displayName(parse("<dark_gray><b>???"));
        List<Component> lore = new ArrayList<>();
        lore.add(parse(plugin.msg("menu.locked-v2")));
        m.lore(lore);
        m.addItemFlags(ItemFlag.values());
        it.setItemMeta(m);
        return it;
    }

    private ItemStack info(PlayerData d) {
        ItemStack it = new ItemStack(Material.NETHER_STAR);
        ItemMeta m = it.getItemMeta();
        m.displayName(parse(plugin.msg("menu.info-name")));
        List<Component> lore = new ArrayList<>();
        lore.add(parse(plugin.msg("menu.info-equipped").replace("<n>", String.valueOf(d.equipped.size()))
                .replace("<max>", String.valueOf(slots()))));
        lore.add(parse(plugin.msg("menu.info-owned").replace("<n>", String.valueOf(d.relics.size()))));
        lore.add(parse(plugin.msg("menu.info-kills").replace("<n>", String.valueOf(d.kills))));
        int pl = plugin.getConfig().getInt("loot.pity.legendary", 150), ps = plugin.getConfig().getInt("loot.pity.secret", 300);
        if (pl > 0) lore.add(parse(plugin.msg("menu.info-pity-legendary").replace("<n>", String.valueOf(Math.max(0, pl - d.pityLegendary)))));
        if (ps > 0) lore.add(parse(plugin.msg("menu.info-pity-secret").replace("<n>", String.valueOf(Math.max(0, ps - d.pitySecret)))));
        if (!d.titles.isEmpty()) {
            lore.add(Component.empty());
            lore.add(parse(plugin.msg("menu.info-titles")));
            for (String t : d.titles) {
                boolean pr = plugin.getConfig().getMapList("loot.pools.prestige").stream().anyMatch(pm -> t.equals(pm.get("title")));
                lore.add(parse("<dark_gray> - " + (pr ? Rarity.PRESTIGE : Rarity.SECRET).name(t, 0)));
            }
        }
        m.lore(lore);
        it.setItemMeta(m);
        return it;
    }

    private ItemStack label(PlayerData d) {
        ItemStack it = new ItemStack(Material.JACK_O_LANTERN);
        ItemMeta m = it.getItemMeta();
        m.displayName(parse(plugin.msg("menu.collection-label").replace("<n>", String.valueOf(d.relics.size()))
                .replace("<total>", String.valueOf(plugin.relics().all().size()))));
        List<Component> lore = new ArrayList<>();
        for (String s : plugin.getConfig().getStringList("messages.menu.collection-lore")) lore.add(parse(s));
        if (pages() > 1) lore.add(parse("<dark_gray>Page " + (page + 1) + "/" + pages()));
        m.lore(lore);
        it.setItemMeta(m);
        return it;
    }

    private ItemStack emptySlot() {
        return named(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "menu.empty-slot", null);
    }

    private ItemStack named(Material mat, String key, String n) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.displayName(parse(plugin.msg(key + ".name").replace("<n>", n == null ? "" : n)));
        List<Component> lore = new ArrayList<>();
        for (String s : plugin.getConfig().getStringList("messages." + key + ".lore")) lore.add(parse(s.replace("<n>", n == null ? "" : n)));
        m.lore(lore);
        m.addItemFlags(ItemFlag.values());
        it.setItemMeta(m);
        return it;
    }

    private static ItemStack pane(Material m) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setHideTooltip(true);
        it.setItemMeta(meta);
        return it;
    }

    // ================================================================= clics

    public void click(int slot, ClickType type) {
        PlayerData d = plugin.store().of(viewer.getUniqueId());
        switch (slot) {
            case CLOSE -> {
                viewer.closeInventory();
                return;
            }
            case PREV -> {
                if (page > 0) {
                    page--;
                    tick();
                    draw();
                }
                return;
            }
            case NEXT -> {
                if (page < pages() - 1) {
                    page++;
                    tick();
                    draw();
                }
                return;
            }
            case TOGGLE -> {
                d.hideCosmetics = !d.hideCosmetics;
                plugin.store().dirty();
                plugin.cosmetics().refresh(viewer);
                tick();
                draw();
                return;
            }
            case STASH -> {
                if (d.stash.isEmpty()) return;
                int got = plugin.loot().takeStash(viewer);
                viewer.sendMessage(parse(got > 0 ? plugin.msg("loot.stash-taken").replace("<n>", String.valueOf(got)) : plugin.msg("loot.stash-full")));
                viewer.playSound(Sound.sound(Key.key("minecraft", got > 0 ? "entity.item.pickup" : "block.chest.locked"), Sound.Source.MASTER, 1f, 1f));
                draw();
                return;
            }
            default -> {
            }
        }
        if (slot == BAG && bagInRow()) {
            if (plugin.relics().hasBackpack(viewer)) plugin.backpacks().open(viewer);
            return;
        }
        Relic r = null;
        boolean owned = true;
        int[] eq = equipSlots();
        for (int i = 0; i < eq.length; i++) if (eq[i] == slot && i < d.equipped.size()) r = plugin.relics().get(d.equipped.get(i));
        List<Relic> all = catalog();
        for (int i = 0; i < COLLECTION.length; i++) {
            int idx = page * COLLECTION.length + i;
            if (COLLECTION[i] == slot && idx < all.size()) {
                r = all.get(idx);
                owned = d.relics.contains(r.id());
            }
        }
        if (r == null) return;
        if (!owned) {
            viewer.playSound(Sound.sound(Key.key("minecraft", "block.chest.locked"), Sound.Source.MASTER, 0.7f, 1.3f));
            return;
        }
        if (type == ClickType.DROP || type == ClickType.CONTROL_DROP) {
            extract(d, r);
            return;
        }
        if (type.isShiftClick()) {
            viewer.closeInventory();
            inspect(plugin, viewer, r);
            return;
        }
        if (!owned) {
            viewer.playSound(Sound.sound(Key.key("minecraft", "block.chest.locked"), Sound.Source.MASTER, 0.7f, 1.3f));
            return;
        }
        String key = plugin.relics().toggle(viewer, r.id());
        viewer.sendMessage(parse(plugin.msg(key).replace("<relic>", r.rarity().name(r.name(), 0))
                .replace("<max>", String.valueOf(slots()))));
        viewer.playSound(Sound.sound(Key.key("minecraft", key.equals("relics.equipped")
                ? "item.armor.equip_netherite" : "ui.button.click"), Sound.Source.MASTER, 1f, 1.2f));
        draw();
    }

    private String pendingExtract;
    private long pendingUntil;

    /** Touche Q sur une relique : elle redevient un objet (pour l'échanger, la vendre, la donner). */
    private void extract(PlayerData d, Relic r) {
        if (r.backpack()) {
            viewer.sendMessage(parse(plugin.msg("relics.no-trade-backpack")));
            return;
        }
        long now = System.currentTimeMillis();
        String name = r.rarity().name(r.name(), 0);
        if (!r.id().equals(pendingExtract) || now > pendingUntil) {
            pendingExtract = r.id();
            pendingUntil = now + 5000;
            viewer.sendMessage(parse(plugin.msg("relics.extract-confirm").replace("<relic>", name)));
            viewer.playSound(Sound.sound(Key.key("minecraft", "block.note_block.pling"), Sound.Source.MASTER, 0.8f, 0.8f));
            return;
        }
        pendingExtract = null;
        if (viewer.getInventory().firstEmpty() == -1) {
            viewer.sendMessage(parse(plugin.msg("relics.extract-full")));
            return;
        }
        if (!d.relics.remove(r.id())) return;
        d.equipped.remove(r.id());
        plugin.store().dirty();
        plugin.relics().apply(viewer);
        viewer.getInventory().addItem(plugin.items().relicItem(r));
        viewer.sendMessage(parse(plugin.msg("relics.extracted").replace("<relic>", name)));
        viewer.playSound(Sound.sound(Key.key("minecraft", "entity.item.pickup"), Sound.Source.MASTER, 1f, 0.8f));
        draw();
    }

    private void tick() {
        viewer.playSound(Sound.sound(Key.key("minecraft", "ui.button.click"), Sound.Source.MASTER, 0.6f, 1.4f));
    }

    // ================================================================= inspection 3D

    /** La relique apparaît en 3D devant le joueur (même animation que le butin). */
    public static void inspect(SmpBoss plugin, Player p, Relic r) {
        PlayerData d = plugin.store().of(p.getUniqueId());
        String where = d.relics.contains(r.id()) ? null : plugin.msg("menu.preview-tag");
        Reveal.show(plugin, p, plugin.items().relicItem(r), r.rarity(), r.rarity().name(r.name(), 0), where);
    }
}
