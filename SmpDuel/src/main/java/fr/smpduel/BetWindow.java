package fr.smpduel;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

import static fr.smpduel.SmpDuel.mm;

/**
 * Fenêtre de mise (duel pari), partagée par les deux joueurs : chacun pose ses objets de son côté
 * (clic sur un objet de son inventaire), voit ceux de l'autre, puis clique "Prêt". Si quelque chose change,
 * les deux "Prêt" sautent. Quand les deux sont prêts : 3 s, puis les mises partent au coffre et le duel commence.
 * Tous les clics sont annulés et refaits à la main : pas de duplication possible.
 */
final class BetWindow implements InventoryHolder {

    private static final int READY_A = 45, HEAD_A = 47, CANCEL = 49, HEAD_B = 51, READY_B = 53;

    private final SmpDuel plugin;
    final UUID ida, idb;
    private final String na, nb;
    private final Inventory inv;
    private boolean readyA, readyB, closed;
    private BukkitTask countdown;
    private final BiConsumer<List<ItemStack>, List<ItemStack>> onStart;
    private final Runnable onCancel;
    /** celui qui a annulé (pour remettre l'autre dans la file) */
    UUID cancelledBy;

    BetWindow(SmpDuel plugin, Player a, Player b, BiConsumer<List<ItemStack>, List<ItemStack>> onStart, Runnable onCancel) {
        this.plugin = plugin;
        this.ida = a.getUniqueId();
        this.idb = b.getUniqueId();
        this.na = a.getName();
        this.nb = b.getName();
        this.onStart = onStart;
        this.onCancel = onCancel;
        this.inv = Bukkit.createInventory(this, 54, mm(plugin.msg("bet-title").replace("<a>", na).replace("<b>", nb)));
        ItemStack bars = pane(Material.IRON_BARS), black = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int r = 0; r < 5; r++) inv.setItem(r * 9 + 4, bars);
        for (int i = 45; i < 54; i++) inv.setItem(i, black);
        inv.setItem(HEAD_A, head(a));
        inv.setItem(HEAD_B, head(b));
        inv.setItem(CANCEL, button(Material.BARRIER, plugin.msg("bet-cancel"), List.of()));
        draw();
        a.openInventory(inv);
        b.openInventory(inv);
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    private static boolean sideA(int slot) {
        return slot < 45 && slot % 9 < 4;
    }

    private static boolean sideB(int slot) {
        return slot < 45 && slot % 9 > 4;
    }

    private void draw() {
        inv.setItem(READY_A, readyButton(readyA, na));
        inv.setItem(READY_B, readyButton(readyB, nb));
    }

    private ItemStack readyButton(boolean ready, String who) {
        return button(ready ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                plugin.msg(ready ? "bet-ready" : "bet-not-ready").replace("<player>", who), List.of(plugin.msg("bet-ready-lore")));
    }

    private void unready() {
        readyA = readyB = false;
        if (countdown != null) {
            countdown.cancel();
            countdown = null;
        }
        draw();
    }

    private List<ItemStack> side(boolean a) {
        List<ItemStack> out = new ArrayList<>();
        for (int s = 0; s < 45; s++) {
            if (a ? !sideA(s) : !sideB(s)) continue;
            ItemStack it = inv.getItem(s);
            if (it != null && !it.getType().isAir()) out.add(it.clone());
        }
        return out;
    }

    // ================================================================= clics

    void click(InventoryClickEvent e) {
        e.setCancelled(true);
        if (closed || !(e.getWhoClicked() instanceof Player p)) return;
        ClickType ct = e.getClick();
        if (ct != ClickType.LEFT && ct != ClickType.RIGHT && ct != ClickType.SHIFT_LEFT && ct != ClickType.SHIFT_RIGHT) return;
        boolean isA = p.getUniqueId().equals(ida);
        if (e.getRawSlot() < 0) return;
        if (e.getRawSlot() < inv.getSize()) {
            int slot = e.getRawSlot();
            if (slot == CANCEL) {
                cancel(p.getUniqueId());
                return;
            }
            if (slot == (isA ? READY_A : READY_B)) {
                if (side(isA).isEmpty()) {
                    p.sendMessage(mm(plugin.msg("bet-empty")));
                    return;
                }
                if (isA) readyA = !readyA;
                else readyB = !readyB;
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                draw();
                tryStart();
                return;
            }
            // reprendre un objet de son côté
            if (isA ? sideA(slot) : sideB(slot)) {
                ItemStack it = inv.getItem(slot);
                if (it == null || it.getType().isAir()) return;
                if (!fits(p, it)) {
                    p.sendMessage(mm(plugin.msg("bet-full-inv")));
                    return;
                }
                inv.setItem(slot, null);
                p.getInventory().addItem(it);
                unready();
            }
            return;
        }
        // poser un objet de son inventaire de son côté
        if (e.getClickedInventory() != null && e.getClickedInventory().getType() == org.bukkit.event.inventory.InventoryType.PLAYER) {
            ItemStack it = e.getCurrentItem();
            if (it == null || it.getType().isAir()) return;
            int free = -1;
            for (int s = 0; s < 45 && free < 0; s++) if ((isA ? sideA(s) : sideB(s)) && (inv.getItem(s) == null || inv.getItem(s).getType().isAir())) free = s;
            if (free < 0) {
                p.sendMessage(mm(plugin.msg("bet-side-full")));
                return;
            }
            ItemStack moved = it.clone();
            p.getInventory().setItem(e.getSlot(), null);
            inv.setItem(free, moved);
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.4f);
            unready();
        }
    }

    private static boolean fits(Player p, ItemStack it) {
        if (p.getInventory().firstEmpty() >= 0) return true;
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (s != null && s.isSimilar(it) && s.getAmount() + it.getAmount() <= s.getMaxStackSize()) return true;
        }
        return false;
    }

    private void tryStart() {
        if (!readyA || !readyB || countdown != null) return;
        if (side(true).isEmpty() || side(false).isEmpty()) {
            unready();
            return;
        }
        int[] n = {3};
        countdown = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (closed || !readyA || !readyB) return;
            if (n[0] <= 0) {
                countdown.cancel();
                countdown = null;
                start();
                return;
            }
            for (UUID id : new UUID[]{ida, idb}) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f + (3 - n[0]) * 0.2f);
                p.sendActionBar(mm(plugin.msg("bet-countdown").replace("<n>", String.valueOf(n[0]))));
            }
            inv.setItem(CANCEL, button(Material.CLOCK, plugin.msg("bet-countdown").replace("<n>", String.valueOf(n[0])), List.of()));
            n[0]--;
        }, 0L, 20L);
    }

    private void start() {
        List<ItemStack> a = side(true), b = side(false);
        closed = true;
        inv.clear();
        for (UUID id : new UUID[]{ida, idb}) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.getOpenInventory().getTopInventory() == inv) p.closeInventory();
        }
        onStart.accept(a, b);
    }

    /** Fermeture, déconnexion ou bouton : chacun récupère sa mise. */
    void cancel(UUID by) {
        if (closed) return;
        closed = true;
        cancelledBy = by;
        if (countdown != null) countdown.cancel();
        List<ItemStack> a = side(true), b = side(false);
        inv.clear();
        plugin.escrow().give(ida, a);
        plugin.escrow().give(idb, b);
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (UUID id : new UUID[]{ida, idb}) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                if (p.getOpenInventory().getTopInventory() == inv) p.closeInventory();
                p.sendMessage(mm(plugin.msg("bet-cancelled").replace("<player>", by.equals(ida) ? na : nb)));
            }
        });
        onCancel.run();
    }

    boolean closed() {
        return closed;
    }

    // ================================================================= objets

    private static ItemStack pane(Material m) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setHideTooltip(true);
        it.setItemMeta(meta);
        return it;
    }

    private static ItemStack button(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(mm(name).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(mm(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack head(Player p) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta m = (SkullMeta) it.getItemMeta();
        m.setOwningPlayer(p);
        m.displayName(mm("<white><b>" + p.getName()).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        Stats.Entry s = plugin.stats().of(p.getUniqueId());
        m.lore(List.of(mm("<gray>ELO : <gold>" + s.elo).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE),
                mm("<gray>Paris gagnés : <green>" + s.of(Mode.PARI)[0] + " <dark_gray>· <gray>perdus : <red>" + s.of(Mode.PARI)[1])
                        .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)));
        it.setItemMeta(m);
        return it;
    }
}
