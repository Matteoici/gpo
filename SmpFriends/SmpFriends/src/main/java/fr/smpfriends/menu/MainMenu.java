package fr.smpfriends.menu;

import fr.smpfriends.Friends;
import fr.smpfriends.FriendsPlugin;
import fr.smpfriends.Items;
import fr.smpfriends.Profile;
import fr.smpfriends.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Menu principal : la liste des amis (28 par page). */
public final class MainMenu extends Menu {

    static final int[] SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};
    private static final int ME = 4, PREV = 45, ADD = 47, REQUESTS = 48, CLOSE = 49, SETTINGS = 50, FILTER = 51, NEXT = 53;

    private final UUID[] shown = new UUID[54];
    private int page;
    private boolean onlineOnly;

    public MainMenu(FriendsPlugin plugin, Player viewer) {
        super(plugin, viewer, 6, Component.text("✦ ", Text.PREFIX_COLOR)
                .append(Component.text("Mes amis", NamedTextColor.DARK_GRAY).decorate(TextDecoration.BOLD)));
    }

    @Override
    public boolean autoRefresh() {
        return true;
    }

    @Override
    public void render() {
        frame(Material.PINK_STAINED_GLASS_PANE, Material.BLACK_STAINED_GLASS_PANE);
        java.util.Arrays.fill(shown, null);
        Friends f = plugin.friends();
        Profile me = f.profile(viewer.getUniqueId());
        List<UUID> list = f.sorted(viewer, onlineOnly);
        int pages = Math.max(1, (list.size() + SLOTS.length - 1) / SLOTS.length);
        page = Math.min(page, pages - 1);

        // moi
        inv.setItem(ME, Items.head(viewer, Text.t(viewer.getName(), Text.ACCENT).decorate(TextDecoration.BOLD), List.of(
                Text.line("Amis :", me.friends.size() + (viewer.hasPermission("smpfriends.unlimited") ? ""
                        : " / " + plugin.getConfig().getInt("max-friends", 30))),
                Text.line("En ligne :", String.valueOf(f.onlineCount(viewer))),
                Text.line("Demandes reçues :", String.valueOf(me.requests.size())))));

        for (int i = 0; i < SLOTS.length; i++) {
            int idx = page * SLOTS.length + i;
            if (idx >= list.size()) {
                inv.setItem(SLOTS[i], null);
                continue;
            }
            UUID id = list.get(idx);
            shown[SLOTS[i]] = id;
            inv.setItem(SLOTS[i], icon(id, me.favorites.contains(id)));
        }
        if (list.isEmpty()) {
            inv.setItem(22, Items.of(Material.OAK_SIGN, Text.t(onlineOnly ? "Aucun ami en ligne" : "Tu n'as pas encore d'amis",
                    NamedTextColor.GRAY), List.of(Text.t("Clique sur « Ajouter un ami » en bas.", NamedTextColor.DARK_GRAY))));
        }

        if (page > 0) inv.setItem(PREV, Items.of(Material.ARROW, Text.t("◀ Page précédente", NamedTextColor.WHITE), null));
        if (page < pages - 1) inv.setItem(NEXT, Items.of(Material.ARROW, Text.t("Page suivante ▶", NamedTextColor.WHITE), null));
        inv.setItem(ADD, Items.of(Material.LIME_DYE, Text.t("✚ Ajouter un ami", NamedTextColor.GREEN).decorate(TextDecoration.BOLD),
                List.of(Text.t("Clique puis écris son pseudo.", NamedTextColor.GRAY))));
        ItemStack req = Items.of(Material.WRITABLE_BOOK, Text.t("✉ Demandes reçues (" + me.requests.size() + ")", NamedTextColor.GOLD),
                List.of(Text.t(me.requests.isEmpty() ? "Aucune demande." : "Clique pour les voir.", NamedTextColor.GRAY)));
        if (!me.requests.isEmpty()) {
            Items.glow(req);
            req.setAmount(Math.min(64, me.requests.size()));
        }
        inv.setItem(REQUESTS, req);
        inv.setItem(CLOSE, Items.of(Material.BARRIER, Text.t("Fermer", NamedTextColor.RED), null));
        inv.setItem(SETTINGS, Items.of(Material.COMPARATOR, Text.t("⚙ Réglages", NamedTextColor.AQUA),
                List.of(Text.t("Confidentialité et notifications.", NamedTextColor.GRAY))));
        inv.setItem(FILTER, Items.of(Material.HOPPER, Text.t("Filtre : " + (onlineOnly ? "en ligne" : "tous"), NamedTextColor.YELLOW),
                List.of(Text.t("Clique pour changer.", NamedTextColor.GRAY))));
        inv.setItem(52, Items.of(Material.PAPER, Text.t("Page " + (page + 1) + " / " + pages, NamedTextColor.GRAY), null));
    }

    private ItemStack icon(UUID id, boolean favorite) {
        OfflinePlayer op = FriendInfo.player(id);
        List<Component> lore = new ArrayList<>();
        lore.add(FriendInfo.status(plugin, op));
        Player online = op.getPlayer();
        if (online != null) lore.addAll(FriendInfo.location(plugin, op));
        Component money = FriendInfo.money(plugin, op);
        if (money != null) lore.add(money);
        lore.add(Component.empty());
        lore.add(Text.t("Clic gauche : détails et actions", NamedTextColor.YELLOW));
        if (online != null && plugin.friends().commandEnabled("teleport")) {
            lore.add(Text.t("Clic droit : demande de téléportation", NamedTextColor.YELLOW));
        }
        lore.add(Text.t("Shift + clic : " + (favorite ? "retirer des favoris" : "mettre en favori"), NamedTextColor.YELLOW));
        Component name = Text.t((favorite ? "★ " : "") + Friends.name(id), favorite ? NamedTextColor.GOLD
                : online != null ? NamedTextColor.GREEN : NamedTextColor.GRAY).decorate(TextDecoration.BOLD);
        ItemStack head = Items.head(op, name, lore);
        return favorite ? Items.glow(head) : head;
    }

    @Override
    public void click(int slot, ClickType type) {
        UUID id = slot >= 0 && slot < shown.length ? shown[slot] : null;
        if (id != null) {
            click();
            if (type.isShiftClick()) {
                plugin.friends().toggleFavorite(viewer, id);
                render();
            } else if (type.isRightClick() && plugin.friends().commandEnabled("teleport")) {
                if (FriendInfo.player(id).getPlayer() == null) deny();
                else plugin.friends().runCommand(viewer, "teleport", Friends.name(id));
            } else {
                new FriendMenu(plugin, viewer, id).open();
            }
            return;
        }
        switch (slot) {
            case PREV -> { page = Math.max(0, page - 1); click(); render(); }
            case NEXT -> { page++; click(); render(); }
            case ADD -> plugin.prompts().ask(viewer, "Écris le pseudo du joueur à ajouter :",
                    name -> plugin.friends().request(viewer, name));
            case REQUESTS -> { click(); new RequestsMenu(plugin, viewer).open(); }
            case CLOSE -> viewer.closeInventory();
            case SETTINGS -> { click(); new SettingsMenu(plugin, viewer).open(); }
            case FILTER -> { onlineOnly = !onlineOnly; page = 0; click(); render(); }
            default -> { }
        }
    }
}
