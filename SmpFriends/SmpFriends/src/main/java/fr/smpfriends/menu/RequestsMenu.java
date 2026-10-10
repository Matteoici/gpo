package fr.smpfriends.menu;

import fr.smpfriends.Friends;
import fr.smpfriends.FriendsPlugin;
import fr.smpfriends.Items;
import fr.smpfriends.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Demandes d'ami reçues : clic gauche = accepter, clic droit = refuser. */
public final class RequestsMenu extends Menu {

    private static final int BACK = 49, ACCEPT_ALL = 47, DENY_ALL = 51;
    private final UUID[] shown = new UUID[54];

    public RequestsMenu(FriendsPlugin plugin, Player viewer) {
        super(plugin, viewer, 6, Component.text("✉ Demandes d'ami", NamedTextColor.DARK_GRAY).decorate(TextDecoration.BOLD));
    }

    @Override
    public void render() {
        frame(Material.ORANGE_STAINED_GLASS_PANE, Material.BLACK_STAINED_GLASS_PANE);
        java.util.Arrays.fill(shown, null);
        List<UUID> reqs = new ArrayList<>(plugin.friends().profile(viewer.getUniqueId()).requests);
        for (int i = 0; i < MainMenu.SLOTS.length && i < reqs.size(); i++) {
            UUID id = reqs.get(i);
            OfflinePlayer op = FriendInfo.player(id);
            shown[MainMenu.SLOTS[i]] = id;
            inv.setItem(MainMenu.SLOTS[i], Items.head(op, Text.t(Friends.name(id), NamedTextColor.WHITE).decorate(TextDecoration.BOLD),
                    List.of(FriendInfo.status(plugin, op), Component.empty(),
                            Text.t("Clic gauche : accepter", NamedTextColor.GREEN),
                            Text.t("Clic droit : refuser", NamedTextColor.RED))));
        }
        if (reqs.isEmpty()) {
            inv.setItem(22, Items.of(Material.PAPER, Text.t("Aucune demande pour le moment", NamedTextColor.GRAY), null));
        } else {
            inv.setItem(ACCEPT_ALL, Items.of(Material.LIME_DYE, Text.t("Tout accepter", NamedTextColor.GREEN), null));
            inv.setItem(DENY_ALL, Items.of(Material.RED_DYE, Text.t("Tout refuser", NamedTextColor.RED), null));
        }
        inv.setItem(BACK, Items.of(Material.ARROW, Text.t("◀ Retour", NamedTextColor.WHITE), null));
    }

    @Override
    public void click(int slot, ClickType type) {
        UUID id = slot >= 0 && slot < shown.length ? shown[slot] : null;
        if (id != null) {
            if (type.isRightClick()) plugin.friends().deny(viewer, id);
            else plugin.friends().accept(viewer, id);
            render();
            return;
        }
        switch (slot) {
            case BACK -> { click(); new MainMenu(plugin, viewer).open(); }
            case ACCEPT_ALL -> {
                for (UUID u : new ArrayList<>(plugin.friends().profile(viewer.getUniqueId()).requests)) plugin.friends().accept(viewer, u);
                render();
            }
            case DENY_ALL -> {
                for (UUID u : new ArrayList<>(plugin.friends().profile(viewer.getUniqueId()).requests)) plugin.friends().deny(viewer, u);
                render();
            }
            default -> { }
        }
    }
}
