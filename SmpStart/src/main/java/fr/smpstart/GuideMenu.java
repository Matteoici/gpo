package fr.smpstart;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static fr.smpstart.Texts.comp;
import static fr.smpstart.Texts.ph;

/** Le guide : toutes les étapes avec leur état (terminée, en cours, à venir). */
public final class GuideMenu implements InventoryHolder {

    /** 4 lignes de 7 cases à l'intérieur de la bordure. */
    static final int[] SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};

    private final SmpStart plugin;
    private final Player viewer;
    private final Inventory inventory;
    private final Map<Integer, Consumer<ClickType>> actions = new HashMap<>();

    public GuideMenu(SmpStart plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, 54, plugin.texts().get("gui.title"));
    }

    public Player viewer() {
        return viewer;
    }

    public void open() {
        redraw();
        viewer.openInventory(inventory);
    }

    private void redraw() {
        inventory.clear();
        actions.clear();
        Texts t = plugin.texts();
        PlayerData d = plugin.data().get(viewer.getUniqueId());
        List<Step> steps = plugin.settings().steps();
        int done = d == null ? 0 : d.finished ? steps.size() : Math.min(d.step, steps.size());
        boolean finished = d != null && d.finished;

        // en-tête
        int percent = steps.isEmpty() ? 100 : done * 100 / steps.size();
        set(4, item(finished ? Material.NETHER_STAR : Material.WRITABLE_BOOK, t.get(finished ? "gui.header.name-done" : "gui.header.name"),
                t.list("gui.header.lore", ph("done", done), ph("total", steps.size()), ph("percent", percent),
                        comp("bar", bar(done, steps.size()))), finished));

        // étapes
        for (int i = 0; i < Math.min(steps.size(), SLOTS.length); i++) {
            Step s = steps.get(i);
            boolean isDone = finished || (d != null && i < d.step);
            boolean isCurrent = !finished && d != null && i == d.step;
            Component name = t.get(isDone ? "gui.step.done" : isCurrent ? "gui.step.current" : "gui.step.locked",
                    comp("step", t.parse(s.name())), ph("number", i + 1));
            List<Component> lore = new ArrayList<>();
            for (String line : s.description()) lore.add(t.parse("<gray>" + line));
            lore.add(Component.empty());
            if (isDone) {
                lore.add(t.get("gui.step.lore-done"));
            } else if (isCurrent) {
                lore.add(t.get("gui.step.lore-progress", ph("progress", d.progress), ph("amount", s.amount())));
            } else {
                lore.add(t.get("gui.step.lore-locked"));
            }
            lore.addAll(rewardLore(s.reward()));
            if (isCurrent) {
                if (!s.hint().isBlank()) lore.add(t.get("gui.step.lore-click"));
            }
            ItemStack it = item(s.icon(), name, lore, isCurrent);
            if (isCurrent) {
                button(SLOTS[i], it, click -> {
                    viewer.closeInventory();
                    plugin.progress().sendCurrent(viewer);
                });
            } else {
                set(SLOTS[i], it);
            }
        }

        // protection débutant
        if (d != null && d.protectionSec > 0) {
            button(50, item(Material.SHIELD, t.get("gui.protection.name"),
                    t.list("gui.protection.lore", ph("minutes", Math.max(1, d.protectionSec / 60))), true), click -> {
                if (click == ClickType.RIGHT) {
                    d.protectionSec = 0;
                    plugin.data().markDirty();
                    t.send(viewer, "protection.disabled");
                    redraw();
                }
            });
        }
        // boss bar
        if (d != null && !finished) {
            button(48, item(Material.BELL, t.get(d.bar ? "gui.bar.name-on" : "gui.bar.name-off"), t.list("gui.bar.lore"), false), click -> {
                d.bar = !d.bar;
                plugin.data().markDirty();
                plugin.bars().update(viewer);
                redraw();
            });
        }
        button(49, item(Material.BARRIER, t.get("gui.close"), List.of(), false), click -> viewer.closeInventory());

        // bordure
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        meta.setHideTooltip(true);
        filler.setItemMeta(meta);
        for (int i = 0; i < inventory.getSize(); i++) if (inventory.getItem(i) == null) inventory.setItem(i, filler.clone());
    }

    private List<Component> rewardLore(Reward r) {
        List<Component> out = new ArrayList<>();
        if (r == null || r.isEmpty()) return out;
        Texts t = plugin.texts();
        out.add(t.get("gui.step.lore-reward"));
        if (r.money() > 0) out.add(t.get("gui.step.reward-money", ph("amount", Kit.format(r.money()))));
        for (String it : r.items()) {
            ItemStack stack = plugin.kit().parse(it);
            if (stack != null) {
                String n = stack.getType().name().toLowerCase().replace('_', ' ');
                out.add(t.get("gui.step.reward-item", ph("amount", stack.getAmount()),
                        ph("item", Character.toUpperCase(n.charAt(0)) + n.substring(1))));
            }
        }
        return out;
    }

    private static Component bar(int done, int total) {
        int length = 20;
        int filled = total == 0 ? length : done * length / total;
        return Component.text("|".repeat(filled), net.kyori.adventure.text.format.NamedTextColor.GREEN)
                .append(Component.text("|".repeat(length - filled), net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY));
    }

    private ItemStack item(Material m, Component name, List<Component> lore, boolean glow) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name);
        if (!lore.isEmpty()) meta.lore(lore);
        meta.addItemFlags(ItemFlag.values());
        if (glow) meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
        return it;
    }

    private void set(int slot, ItemStack it) {
        inventory.setItem(slot, it);
    }

    private void button(int slot, ItemStack it, Consumer<ClickType> action) {
        inventory.setItem(slot, it);
        actions.put(slot, action);
    }

    void click(int slot, ClickType type) {
        Consumer<ClickType> a = actions.get(slot);
        if (a != null) a.accept(type);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
