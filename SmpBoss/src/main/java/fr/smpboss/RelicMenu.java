package fr.smpboss;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

import static fr.smpboss.SmpBoss.parse;

/**
 * /reliques : les reliques équipées en haut, encadrées par la couleur de leur rareté
 * (cadre arc-en-ciel animé pour les légendaires, noir et blanc qui clignote pour les secrètes),
 * la collection en bas. Shift + clic = inspecter la relique en 3D devant soi.
 */
public final class RelicMenu implements InventoryHolder {

    /** Centres des emplacements équipés et leur cadre (3×3). */
    private static final int[] EQUIP = {11, 15, 13};
    private static final int[] COLLECTION = {28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    private static final Material[] RAINBOW = {Material.RED_STAINED_GLASS_PANE, Material.ORANGE_STAINED_GLASS_PANE,
            Material.YELLOW_STAINED_GLASS_PANE, Material.LIME_STAINED_GLASS_PANE, Material.LIGHT_BLUE_STAINED_GLASS_PANE,
            Material.BLUE_STAINED_GLASS_PANE, Material.PURPLE_STAINED_GLASS_PANE, Material.MAGENTA_STAINED_GLASS_PANE};

    private final SmpBoss plugin;
    private final Player viewer;
    private final Inventory inv;
    private int frame;

    public RelicMenu(SmpBoss plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.inv = Bukkit.createInventory(this, 54, parse(plugin.msg("menu.title")));
        draw();
    }

    public void open() {
        viewer.openInventory(inv);
        plugin.openMenus().add(this);
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    private int slots() {
        return Math.min(EQUIP.length, plugin.relics().maxEquipped);
    }

    // ================================================================= dessin

    public void draw() {
        inv.clear();
        PlayerData d = plugin.store().of(viewer.getUniqueId());
        for (int i = 0; i < 54; i++) inv.setItem(i, pane(Material.BLACK_STAINED_GLASS_PANE));
        for (int i = 0; i < slots(); i++) {
            String id = i < d.equipped.size() ? d.equipped.get(i) : null;
            Relic r = plugin.relics().get(id);
            inv.setItem(EQUIP[i], r == null ? emptySlot() : icon(r, true));
        }
        drawFrames();
        List<Relic> owned = plugin.relics().owned(viewer);
        for (int i = 0; i < COLLECTION.length; i++) {
            inv.setItem(COLLECTION[i], i < owned.size() ? icon(owned.get(i), d.equipped.contains(owned.get(i).id()))
                    : pane(Material.GRAY_STAINED_GLASS_PANE));
        }
        inv.setItem(4, info(d));
        if (plugin.relics().hasBackpack(viewer)) inv.setItem(45, named(Material.BUNDLE, "menu.open-backpack"));
        inv.setItem(49, named(Material.BARRIER, "menu.close"));
    }

    /** Toutes les 4 ticks : les cadres animés. */
    public void animate() {
        frame++;
        drawFrames();
        // le nom des légendaires défile aussi en arc-en-ciel
        PlayerData d = plugin.store().of(viewer.getUniqueId());
        for (int i = 0; i < slots() && i < d.equipped.size(); i++) {
            Relic r = plugin.relics().get(d.equipped.get(i));
            if (r != null && r.rarity().ordinal() >= Rarity.LEGENDARY.ordinal()) inv.setItem(EQUIP[i], icon(r, true));
        }
    }

    private void drawFrames() {
        PlayerData d = plugin.store().of(viewer.getUniqueId());
        for (int i = 0; i < slots(); i++) {
            Relic r = plugin.relics().get(i < d.equipped.size() ? d.equipped.get(i) : null);
            int c = EQUIP[i];
            int[] around = {c - 10, c - 9, c - 8, c - 1, c + 1, c + 8, c + 9, c + 10};
            for (int k = 0; k < around.length; k++) {
                int s = around[k];
                if (s < 0 || s >= 27) continue;
                inv.setItem(s, pane(frameColor(r, k)));
            }
        }
    }

    private Material frameColor(Relic r, int k) {
        if (r == null) return Material.GRAY_STAINED_GLASS_PANE;
        return switch (r.rarity()) {
            case COMMON -> Material.WHITE_STAINED_GLASS_PANE;
            case RARE -> Material.BLUE_STAINED_GLASS_PANE;
            case EPIC -> Material.PURPLE_STAINED_GLASS_PANE;
            case LEGENDARY -> RAINBOW[Math.floorMod(frame + k, RAINBOW.length)];
            case SECRET -> (frame + k) % 2 == 0 ? Material.WHITE_STAINED_GLASS_PANE : Material.BLACK_STAINED_GLASS_PANE;
        };
    }

    private ItemStack icon(Relic r, boolean equipped) {
        ItemStack it = new ItemStack(r.icon());
        ItemMeta m = it.getItemMeta();
        m.displayName(parse(r.rarity().name(r.name(), frame % 10)));
        List<Component> lore = new ArrayList<>();
        lore.add(parse(r.rarity().tag));
        lore.add(Component.empty());
        for (String s : r.lore()) lore.add(parse(s));
        lore.addAll(plugin.items().effectLines(r));
        lore.add(Component.empty());
        lore.add(parse(plugin.msg(equipped ? "menu.click-unequip" : "menu.click-equip")));
        lore.add(parse(plugin.msg("menu.click-inspect")));
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
            for (String t : d.titles) lore.add(parse("<dark_gray> - " + Rarity.SECRET.name(t, 0)));
        }
        m.lore(lore);
        it.setItemMeta(m);
        return it;
    }

    private ItemStack emptySlot() {
        return named(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "menu.empty-slot");
    }

    private ItemStack named(Material mat, String key) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.displayName(parse(plugin.msg(key + ".name")));
        List<Component> lore = new ArrayList<>();
        for (String s : plugin.getConfig().getStringList("messages." + key + ".lore")) lore.add(parse(s));
        m.lore(lore);
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
        if (slot == 49) {
            viewer.closeInventory();
            return;
        }
        if (slot == 45 && plugin.relics().hasBackpack(viewer)) {
            plugin.backpacks().open(viewer);
            return;
        }
        Relic r = null;
        for (int i = 0; i < slots(); i++) {
            if (EQUIP[i] == slot && i < d.equipped.size()) r = plugin.relics().get(d.equipped.get(i));
        }
        List<Relic> owned = plugin.relics().owned(viewer);
        for (int i = 0; i < COLLECTION.length && i < owned.size(); i++) if (COLLECTION[i] == slot) r = owned.get(i);
        if (r == null) return;
        if (type.isShiftClick()) {
            viewer.closeInventory();
            inspect(viewer, r);
            return;
        }
        String key = plugin.relics().toggle(viewer, r.id());
        viewer.sendMessage(parse(plugin.msg(key).replace("<relic>", r.rarity().name(r.name(), 0))
                .replace("<max>", String.valueOf(slots()))));
        viewer.playSound(Sound.sound(Key.key("minecraft", key.endsWith("equipped") && !key.contains("un")
                ? "item.armor.equip_netherite" : "ui.button.click"), Sound.Source.MASTER, 1f, 1.2f));
        draw();
    }

    // ================================================================= inspection 3D

    /** La relique flotte et tourne devant le joueur (visible par lui seul) pendant 8 secondes. */
    public static void inspect(SmpBoss plugin, Player p, Relic r) {
        Location eye = p.getEyeLocation();
        Location at = eye.clone().add(eye.getDirection().setY(0).normalize().multiply(2.2));
        at.setY(eye.getY() - 0.2);
        at.setYaw(eye.getYaw() + 180);
        at.setPitch(0);
        ItemStack look = plugin.items().relicItem(r);
        ItemDisplay item = p.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setVisibleByDefault(false);
            d.setItemStack(look);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setGlowing(true);
            d.setGlowColorOverride(switch (r.rarity()) {
                case RARE -> org.bukkit.Color.fromRGB(0x4aa8ff);
                case EPIC -> org.bukkit.Color.fromRGB(0xb45cff);
                case LEGENDARY -> org.bukkit.Color.fromRGB(0xffd36b);
                case SECRET -> org.bukkit.Color.WHITE;
                default -> org.bukkit.Color.WHITE;
            });
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1.2f, 1.2f, 1.2f), new Quaternionf()));
        });
        TextDisplay label = p.getWorld().spawn(at.clone().add(0, 0.95, 0), TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setVisibleByDefault(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.text(parse(r.rarity().name(r.name(), 0)).appendNewline().append(parse(r.rarity().tag)));
            d.setBackgroundColor(org.bukkit.Color.fromARGB(140, 0, 0, 0));
        });
        p.showEntity(plugin, item);
        p.showEntity(plugin, label);
        p.playSound(Sound.sound(Key.key("minecraft", "block.amethyst_block.resonate"), Sound.Source.MASTER, 1f, 1.2f));
        int[] t = {0};
        org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            t[0] += 2;
            if (t[0] > 160 || !p.isOnline() || !item.isValid()) {
                item.remove();
                label.remove();
                task[0].cancel();
                return;
            }
            item.setInterpolationDelay(0);
            item.setInterpolationDuration(2);
            float angle = (float) Math.toRadians(t[0] * 4.5);
            float bob = (float) Math.sin(t[0] * 0.08) * 0.08f;
            item.setTransformation(new Transformation(new Vector3f(0, bob, 0), new Quaternionf(new AxisAngle4f(angle, 0, 1, 0)),
                    new Vector3f(1.2f, 1.2f, 1.2f), new Quaternionf()));
            if (r.rarity() == Rarity.LEGENDARY && t[0] % 4 == 0) {
                label.text(parse(r.rarity().name(r.name(), (t[0] / 4) % 10)).appendNewline().append(parse(r.rarity().tag)));
            }
        }, 1L, 2L);
    }

    private void inspect(Player p, Relic r) {
        inspect(plugin, p, r);
    }
}
