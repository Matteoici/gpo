package fr.smpadmin.gui;

import fr.smpadmin.Durations;
import fr.smpadmin.Items;
import fr.smpadmin.Punishment;
import fr.smpadmin.SmpAdmin;
import fr.smpadmin.StaffState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import static fr.smpadmin.Texts.pc;
import static fr.smpadmin.Texts.ph;

/** Fiche d'un joueur : toutes les actions possibles sur lui. */
public final class PlayerMenu extends Menu {

    private final OfflinePlayer target;
    private final String name;

    public PlayerMenu(SmpAdmin plugin, Player viewer, OfflinePlayer target) {
        super(plugin, viewer, 6, plugin.texts().get("gui.player.title", ph("player", nameOf(target))));
        this.target = target;
        this.name = nameOf(target);
    }

    static String nameOf(OfflinePlayer p) {
        return p.getName() == null ? "?" : p.getName();
    }

    private boolean can(String perm) {
        return viewer.hasPermission(perm);
    }

    @Override
    protected void build() {
        Player online = target.getPlayer();
        var sanctions = plugin.sanctions();
        StaffState.Suspect sus = plugin.state().suspect(target.getUniqueId());

        // ---- en-tête
        long ticks = 0;
        try {
            ticks = target.getStatistic(Statistic.PLAY_ONE_MINUTE);
        } catch (Exception ignored) {
        }
        Component susLine = sus == null ? Component.empty()
                : t().get("gui.player.sus-line", ph("reason", sus.reason()), ph("staff", sus.staff()));
        TagResolver[] hr = {ph("player", name), pc("status", t().get(online != null ? "gui.player.online" : "gui.player.offline")),
                ph("playtime", Durations.format(ticks * 50L)),
                ph("first", target.getFirstPlayed() > 0 ? plugin.actions().date(target.getFirstPlayed()) : "-"),
                ph("sanctions", sanctions.count(target.getUniqueId(), null)),
                ph("warns", sanctions.count(target.getUniqueId(), Punishment.Type.WARN)), pc("sus", susLine)};
        ItemStack head = Items.head(target, t().get("gui.player.header.name", hr), t().list("gui.player.header.lore", hr));
        set(4, sus != null ? Items.glow(head) : head);

        Component yes = t().get("gui.player.on");
        Component no = t().get("gui.player.off");

        // ---- actions qui demandent que le joueur soit en ligne
        if (online != null) {
            button(19, item(Material.ENDER_PEARL, "gui.player.tp"), c -> {
                viewer.closeInventory();
                viewer.teleport(online.getLocation());
            });
            button(20, item(Material.LEAD, "gui.player.tphere"), c -> {
                viewer.closeInventory();
                online.teleport(viewer.getLocation());
            });
            if (can("smpadmin.spec")) button(21, item(Material.ENDER_EYE, "gui.player.spec"), c -> {
                viewer.closeInventory();
                plugin.spectate().watch(viewer, online);
            });
            if (can("smpadmin.invsee")) {
                button(22, item(Material.CHEST, "gui.player.invsee"), c -> plugin.openInventory(viewer, online, false));
                button(23, item(Material.ENDER_CHEST, "gui.player.ecsee"), c -> plugin.openInventory(viewer, online, true));
            }
            if (can("smpadmin.freeze")) {
                boolean frozen = plugin.actions().isFrozen(online.getUniqueId());
                button(24, item(frozen ? Material.BLUE_ICE : Material.PACKED_ICE, "gui.player.freeze",
                        pc("state", frozen ? yes : no)), c -> {
                    plugin.actions().freeze(viewer, online, !plugin.actions().isFrozen(online.getUniqueId()));
                    redraw();
                });
            }
            if (can("smpadmin.kick")) button(31, item(Material.OAK_DOOR, "gui.player.kick"),
                    c -> new SanctionMenu(plugin, viewer, target, Punishment.Type.KICK, this::open).open());
        }

        // ---- suspect
        if (can("smpadmin.sus")) {
            button(25, item(Material.FERMENTED_SPIDER_EYE, "gui.player.sus", pc("state", sus != null ? yes : no)), c -> {
                if (c.isRightClick()) {
                    plugin.input().ask(viewer, reason -> {
                        plugin.actions().sus(viewer, target, reason);
                        open();
                    });
                    return;
                }
                if (plugin.state().suspect(target.getUniqueId()) != null) plugin.actions().unsus(viewer, target);
                else plugin.actions().sus(viewer, target, null);
                redraw();
            });
        }

        // ---- sanctions
        if (can("smpadmin.warn")) button(29, item(Material.PAPER, "gui.player.warn",
                        ph("count", sanctions.count(target.getUniqueId(), Punishment.Type.WARN))),
                c -> new SanctionMenu(plugin, viewer, target, Punishment.Type.WARN, this::open).open());

        if (can("smpadmin.mute")) {
            Punishment mute = sanctions.activeMute(target.getUniqueId());
            if (mute != null) {
                button(30, item(Material.LIME_DYE, "gui.player.unmute", plugin.actions().resolvers(mute)), c -> {
                    plugin.actions().unmute(viewer, target);
                    redraw();
                });
            } else {
                button(30, item(Material.STRING, "gui.player.mute"),
                        c -> new SanctionMenu(plugin, viewer, target, Punishment.Type.MUTE, this::open).open());
            }
        }

        Punishment ban = sanctions.activeBan(target.getUniqueId());
        if (ban != null && can("smpadmin.unban")) {
            button(32, item(Material.LIME_CONCRETE, "gui.player.unban", plugin.actions().resolvers(ban)), c -> {
                plugin.actions().unban(viewer, target);
                redraw();
            });
        } else if (ban == null && can("smpadmin.ban")) {
            button(32, item(Material.NETHERITE_AXE, "gui.player.ban"),
                    c -> new SanctionMenu(plugin, viewer, target, Punishment.Type.BAN, this::open).open());
        }

        if (can("smpadmin.history")) button(33, item(Material.WRITABLE_BOOK, "gui.player.history"),
                c -> new HistoryMenu(plugin, viewer, target, 0).open());

        // rapport anticheat SmpGuard (s'il est installé)
        if (online != null && org.bukkit.Bukkit.getPluginManager().isPluginEnabled("SmpGuard")
                && viewer.hasPermission("smpguard.staff")) {
            button(34, item(Material.SHIELD, "gui.player.anticheat"), c -> viewer.performCommand("smpguard:anticheat check " + name));
        }
        back(45, () -> new MainMenu(plugin, viewer, 0).open());
        close(49);
    }
}
