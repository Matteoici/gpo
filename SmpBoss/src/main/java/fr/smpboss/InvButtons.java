package fr.smpboss;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

import static fr.smpboss.SmpBoss.parse;

/**
 * Deux boutons en haut de l'inventaire, dans la grille d'artisanat 2×2 : le Sac et les Cosmétiques (/reliques).
 * Ce ne sont pas de vrais objets : ils ne peuvent ni sortir de la grille, ni être fabriqués, ni tomber.
 * Dès qu'on pose un vrai objet dans la grille, les boutons s'effacent pour laisser l'artisanat libre ;
 * ils reviennent à la fermeture de l'inventaire.
 */
final class InvButtons implements Listener {

    /** cases de la grille (1 = haut gauche, 2 = haut droite) */
    private static final int BAG = 1, COSMETICS = 2;

    private final SmpBoss plugin;
    private final NamespacedKey key;

    InvButtons(SmpBoss plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "inv_button");
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("inventory-buttons.enabled", true);
    }

    boolean isButton(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    private ItemStack button(Player p, String kind) {
        boolean bag = kind.equals("sac");
        boolean has = !bag || plugin.relics().hasBackpack(p);
        ItemStack it = new ItemStack(bag ? Material.BUNDLE : Material.TOTEM_OF_UNDYING);
        ItemMeta m = it.getItemMeta();
        String base = "inv-buttons." + (bag ? (has ? "bag" : "bag-unequipped") : "cosmetics");
        m.displayName(parse(plugin.msg(base + ".name")));
        List<Component> lore = new ArrayList<>();
        for (String s : plugin.getConfig().getStringList("messages." + base + ".lore")) lore.add(parse(s));
        if (!bag) {
            PlayerData d = plugin.store().of(p.getUniqueId());
            lore.add(parse(plugin.msg("inv-buttons.cosmetics-count").replace("<n>", String.valueOf(d.relics.size()))
                    .replace("<total>", String.valueOf(plugin.relics().all().size()))));
            if (!d.stash.isEmpty()) lore.add(parse(plugin.msg("inv-buttons.stash-waiting").replace("<n>", String.valueOf(d.stash.size()))));
            if (!d.stash.isEmpty()) m.setEnchantmentGlintOverride(true);
        }
        m.lore(lore);
        m.addItemFlags(ItemFlag.values());
        m.getPersistentDataContainer().set(key, PersistentDataType.STRING, kind);
        m.setMaxStackSize(1);
        it.setItemMeta(m);
        return it;
    }

    private static boolean usable(Player p) {
        return p.isOnline() && !p.isDead() && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE);
    }

    /** La grille 2×2 du joueur, seulement quand aucun autre menu n'est ouvert. */
    private static CraftingInventory grid(Player p) {
        if (p.getOpenInventory().getType() != InventoryType.CRAFTING) return null;
        return p.getOpenInventory().getTopInventory() instanceof CraftingInventory ci ? ci : null;
    }

    /** Pose (ou met à jour) les boutons si la grille est libre. */
    void place(Player p) {
        if (!enabled() || !usable(p)) {
            clear(p);
            return;
        }
        CraftingInventory g = grid(p);
        if (g == null) return;
        ItemStack[] matrix = g.getMatrix();
        for (ItemStack it : matrix) if (it != null && !it.getType().isAir() && !isButton(it)) return; // il fabrique quelque chose
        // le bouton Sac n'existe que pour ceux qui ont trouvé le sac (pas de spoil)
        if (plugin.relics().ownsBackpack(p)) g.setItem(BAG, button(p, "sac"));
        else if (isButton(g.getItem(BAG))) g.setItem(BAG, null);
        // le totem des reliques : désactivé par défaut (accès par /reliques ou le menu du serveur)
        if (plugin.getConfig().getBoolean("inventory-buttons.cosmetics", false)) g.setItem(COSMETICS, button(p, "cosmetiques"));
        else if (isButton(g.getItem(COSMETICS))) g.setItem(COSMETICS, null);
    }

    void clear(Player p) {
        CraftingInventory g = grid(p);
        if (g == null) return;
        for (int i = 1; i <= 4; i++) if (isButton(g.getItem(i))) g.setItem(i, null);
        if (isButton(g.getResult())) g.setResult(null);
    }

    void placeLater(Player p, long ticks) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) place(p);
        }, ticks);
    }

    /** Filet de sécurité : un bouton ne doit jamais se retrouver ailleurs que dans la grille. */
    void sanitize(Player p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) if (isButton(inv.getItem(i))) inv.setItem(i, null);
        if (isButton(p.getItemOnCursor())) p.setItemOnCursor(null);
    }

    /** Toutes les 2 s : nettoyage + boutons remis si besoin (après un /reload, un changement de monde...). */
    void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            sanitize(p);
            CraftingInventory g = grid(p);
            if (g == null) continue;
            if (!enabled() || !usable(p)) {
                clear(p);
                continue;
            }
            boolean empty = true;
            for (ItemStack it : g.getMatrix()) if (it != null && !it.getType().isAir()) empty = false;
            if (empty) place(p);
        }
    }

    // ================================================================= clics

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack current = e.getCurrentItem();
        boolean inGrid = e.getView().getType() == InventoryType.CRAFTING && e.getClickedInventory() == e.getView().getTopInventory()
                && e.getRawSlot() >= 1 && e.getRawSlot() <= 4;
        if (inGrid && isButton(current)) {
            e.setCancelled(true);
            String kind = current.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
            ItemStack cursor = e.getCursor();
            boolean emptyCursor = cursor == null || cursor.getType().isAir();
            ClickType c = e.getClick();
            if (emptyCursor && (c.isLeftClick() || c.isRightClick())) {
                Bukkit.getScheduler().runTask(plugin, () -> use(p, kind));
            } else if (!emptyCursor && (c == ClickType.LEFT || c == ClickType.RIGHT)) {
                // il veut fabriquer : on libère la grille et on pose son objet à la place du bouton
                int slot = e.getRawSlot();
                ItemStack put = cursor.clone();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    CraftingInventory g = grid(p);
                    if (g == null || !put.isSimilar(p.getItemOnCursor()) || p.getItemOnCursor().getAmount() != put.getAmount()) return;
                    clear(p);
                    if (c == ClickType.RIGHT && put.getAmount() > 1) {
                        ItemStack one = put.clone();
                        one.setAmount(1);
                        g.setItem(slot, one);
                        put.setAmount(put.getAmount() - 1);
                        p.setItemOnCursor(put);
                    } else {
                        g.setItem(slot, put);
                        p.setItemOnCursor(null);
                    }
                    p.updateInventory();
                });
            } else if (c == ClickType.NUMBER_KEY) {
                // touche 1-9 sur un bouton : on libère la grille, il pourra recommencer
                Bukkit.getScheduler().runTask(plugin, () -> {
                    clear(p);
                    p.updateInventory();
                });
            }
            return;
        }
        // un bouton hors de la grille (ne devrait jamais arriver) : supprimé
        if (isButton(current) && !inGrid) {
            e.setCancelled(true);
            e.setCurrentItem(null);
        }
        if (isButton(e.getCursor())) {
            e.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> sanitize(p));
        }
        // numéro de barre rapide qui viserait un bouton
        if (inGrid && e.getClick() == ClickType.NUMBER_KEY) {
            CraftingInventory g = grid(p);
            if (g != null && isButton(g.getItem(e.getRawSlot()))) e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || e.getView().getType() != InventoryType.CRAFTING) return;
        CraftingInventory g = grid(p);
        if (g == null) return;
        for (int raw : e.getRawSlots()) {
            if (raw >= 1 && raw <= 4 && isButton(g.getItem(raw))) {
                e.setCancelled(true);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    clear(p);
                    p.updateInventory();
                });
                return;
            }
        }
    }

    @EventHandler
    public void onPrepare(PrepareItemCraftEvent e) {
        // le livre de recettes vide la grille dans l'inventaire : on enlève aussitôt les boutons qui y seraient tombés
        for (var v : e.getViewers()) if (v instanceof Player p) Bukkit.getScheduler().runTask(plugin, () -> sanitize(p));
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (isButton(it)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    private void use(Player p, String kind) {
        if (!p.isOnline()) return;
        if ("sac".equals(kind)) {
            if (plugin.relics().hasBackpack(p)) plugin.backpacks().open(p);
            else {
                p.sendMessage(parse(plugin.msg("backpack.not-equipped")));
                p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_CHEST_LOCKED, 0.8f, 1.2f);
            }
        } else {
            new RelicMenu(plugin, p).open();
        }
    }

    // ================================================================= cycle de vie

    /** Fermeture de l'inventaire : on retire les boutons AVANT que le jeu rende le contenu de la grille au joueur. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (e.getView().getType() == InventoryType.CRAFTING) {
            Inventory top = e.getView().getTopInventory();
            for (int i = 1; i <= 4; i++) if (isButton(top.getItem(i))) top.setItem(i, null);
        }
        placeLater(p, 1);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        sanitize(e.getPlayer());
        placeLater(e.getPlayer(), 20);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        clear(e.getPlayer());
        sanitize(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent e) {
        clear(e.getEntity());
        e.getDrops().removeIf(this::isButton);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        placeLater(e.getPlayer(), 3);
    }

    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent e) {
        Player p = e.getPlayer();
        clear(p);
        placeLater(p, 2);
    }

    @EventHandler
    public void onDropItem(PlayerDropItemEvent e) {
        if (isButton(e.getItemDrop().getItemStack())) e.getItemDrop().remove();
    }
}
