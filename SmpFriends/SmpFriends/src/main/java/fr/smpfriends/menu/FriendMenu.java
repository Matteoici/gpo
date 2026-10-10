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

/** Fiche d'un ami : infos détaillées + actions (téléportation, message, échange, argent, suivi...). */
public final class FriendMenu extends Menu {

    private static final int HEAD = 13, LOCATION = 20, MONEY = 21, STATS = 22, DOING = 23, ACTIVITY = 24;
    private static final int FAVORITE = 29, REMOVE = 33;
    private static final int TP = 38, MSG = 39, TRADE = 40, PAY = 41, TRACK = 42, BACK = 49;

    private final UUID friend;
    private boolean confirmRemove;

    public FriendMenu(FriendsPlugin plugin, Player viewer, UUID friend) {
        super(plugin, viewer, 6, Component.text("✦ ", Text.PREFIX_COLOR)
                .append(Component.text(Friends.name(friend), NamedTextColor.DARK_GRAY).decorate(TextDecoration.BOLD)));
        this.friend = friend;
    }

    @Override
    public boolean autoRefresh() {
        return true;
    }

    @Override
    public void render() {
        if (!plugin.friends().areFriends(viewer.getUniqueId(), friend)) {
            new MainMenu(plugin, viewer).open();
            return;
        }
        frame(Material.PINK_STAINED_GLASS_PANE, Material.BLACK_STAINED_GLASS_PANE);
        OfflinePlayer op = FriendInfo.player(friend);
        Player online = op.getPlayer();
        boolean fav = plugin.friends().profile(viewer.getUniqueId()).favorites.contains(friend);

        inv.setItem(HEAD, Items.head(op, Text.t((fav ? "★ " : "") + Friends.name(friend), Text.ACCENT).decorate(TextDecoration.BOLD),
                List.of(FriendInfo.status(plugin, op))));

        inv.setItem(LOCATION, Items.of(Material.COMPASS, Text.t("Position", NamedTextColor.AQUA), FriendInfo.location(plugin, op)));
        Component money = FriendInfo.money(plugin, op);
        inv.setItem(MONEY, Items.of(Material.GOLD_INGOT, Text.t("Argent", NamedTextColor.GOLD),
                money == null ? List.of(Text.t("Économie non installée", NamedTextColor.DARK_GRAY)) : List.of(money)));
        inv.setItem(STATS, Items.of(Material.EXPERIENCE_BOTTLE, Text.t("Statistiques", NamedTextColor.LIGHT_PURPLE),
                FriendInfo.stats(plugin, op)));
        inv.setItem(DOING, Items.of(Material.SPYGLASS, Text.t("Ce qu'il fait", NamedTextColor.YELLOW),
                online != null ? FriendInfo.doing(plugin, online) : List.of(Text.t("Hors ligne", NamedTextColor.DARK_GRAY))));
        List<Component> act = new ArrayList<>();
        act.add(FriendInfo.status(plugin, op));
        if (op.getFirstPlayed() > 0) {
            act.add(Text.line("Joue ici depuis :", Text.duration(System.currentTimeMillis() - op.getFirstPlayed())));
        }
        inv.setItem(ACTIVITY, Items.of(Material.CLOCK, Text.t("Activité", NamedTextColor.GREEN), act));

        inv.setItem(FAVORITE, fav
                ? Items.glow(Items.of(Material.NETHER_STAR, Text.t("★ Favori", NamedTextColor.GOLD),
                List.of(Text.t("Clique pour retirer des favoris.", NamedTextColor.GRAY))))
                : Items.of(Material.NETHER_STAR, Text.t("☆ Mettre en favori", NamedTextColor.YELLOW),
                List.of(Text.t("Les favoris apparaissent en premier.", NamedTextColor.GRAY))));
        inv.setItem(REMOVE, confirmRemove
                ? Items.glow(Items.of(Material.TNT, Text.t("⚠ Clique encore pour confirmer", NamedTextColor.RED).decorate(TextDecoration.BOLD), null))
                : Items.of(Material.BARRIER, Text.t("Retirer de mes amis", NamedTextColor.RED),
                List.of(Text.t("Demande une confirmation.", NamedTextColor.GRAY))));

        boolean on = online != null;
        action(TP, Material.ENDER_PEARL, "Se téléporter", "Envoie une demande de téléportation.", on && plugin.friends().commandEnabled("teleport"));
        action(MSG, Material.PAPER, "Envoyer un message", "Message privé.", on);
        action(TRADE, Material.EMERALD, "Proposer un échange", "Ouvre un échange avec lui.", on && plugin.friends().commandEnabled("trade"));
        boolean eco = plugin.vault() != null && plugin.vault().available();
        action(PAY, Material.GOLD_NUGGET, "Envoyer de l'argent", "Même s'il est hors ligne.", eco);
        action(TRACK, Material.RECOVERY_COMPASS, "Le suivre", "Une flèche te guide vers lui.",
                on && plugin.friends().allows(friend, fr.smpfriends.Setting.SHOW_LOCATION));
        inv.setItem(BACK, Items.of(Material.ARROW, Text.t("◀ Retour", NamedTextColor.WHITE), null));
    }

    private void action(int slot, Material m, String name, String desc, boolean enabled) {
        if (enabled) {
            inv.setItem(slot, Items.of(m, Text.t(name, NamedTextColor.GREEN).decorate(TextDecoration.BOLD),
                    List.of(Text.t(desc, NamedTextColor.GRAY))));
        } else {
            inv.setItem(slot, Items.of(Material.GRAY_DYE, Text.t(name, NamedTextColor.DARK_GRAY),
                    List.of(Text.t("Indisponible pour le moment.", NamedTextColor.DARK_GRAY))));
        }
    }

    @Override
    public void click(int slot, ClickType type) {
        Player online = FriendInfo.player(friend).getPlayer();
        String name = Friends.name(friend);
        if (slot != REMOVE && confirmRemove) {
            confirmRemove = false;
        }
        switch (slot) {
            case BACK -> { click(); new MainMenu(plugin, viewer).open(); }
            case FAVORITE -> { plugin.friends().toggleFavorite(viewer, friend); render(); }
            case REMOVE -> {
                if (!confirmRemove) {
                    confirmRemove = true;
                    deny();
                    render();
                } else {
                    plugin.friends().remove(viewer, friend);
                    new MainMenu(plugin, viewer).open();
                }
            }
            case TP -> {
                if (online == null || !plugin.friends().commandEnabled("teleport")) { deny(); return; }
                plugin.friends().runCommand(viewer, "teleport", name);
            }
            case MSG -> {
                if (online == null) { deny(); return; }
                plugin.prompts().ask(viewer, "Écris ton message pour " + name + " :",
                        text -> plugin.friends().message(viewer, friend, text));
            }
            case TRADE -> {
                if (online == null || !plugin.friends().commandEnabled("trade")) { deny(); return; }
                plugin.friends().runCommand(viewer, "trade", name);
            }
            case PAY -> {
                if (plugin.vault() == null || !plugin.vault().available()) { deny(); return; }
                click();
                new PayMenu(plugin, viewer, friend).open();
            }
            case TRACK -> {
                if (online == null || !plugin.friends().allows(friend, fr.smpfriends.Setting.SHOW_LOCATION)) { deny(); return; }
                viewer.closeInventory();
                plugin.activity().track(viewer, online);
            }
            default -> { }
        }
    }
}
