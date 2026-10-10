package fr.smpguilds.gui;

import fr.smpguilds.Guild;
import fr.smpguilds.SmpGuilds;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static fr.smpguilds.Messages.ph;

/** Liste paginée de guildes : parcourir, classement, invitations, cibles de guerre ou d'alliance. */
public final class GuildListMenu extends Menu {

    public enum Mode { BROWSE, TOP, INVITES, WAR_TARGET, ALLY_TARGET }

    private static final int PER_PAGE = 36;
    private final Mode mode;
    private final int page;

    public GuildListMenu(SmpGuilds plugin, Player viewer, Mode mode, int page) {
        super(plugin, viewer, 6, plugin.messages().get("gui.list.title-" + mode.name().toLowerCase()));
        this.mode = mode;
        this.page = page;
    }

    private List<Guild> guilds() {
        Guild mine = myGuild();
        List<Guild> list = switch (mode) {
            case TOP -> gm().top();
            case INVITES -> gm().invitesOf(viewer.getUniqueId());
            case BROWSE -> {
                List<Guild> l = new ArrayList<>(gm().top());
                l.sort(Comparator.comparing((Guild g) -> !g.open)); // ouvertes d'abord
                yield l;
            }
            case WAR_TARGET, ALLY_TARGET -> {
                List<Guild> l = new ArrayList<>(gm().top());
                l.removeIf(g -> g == mine || (mine != null && mine.allies.contains(g.id)));
                yield l;
            }
        };
        return list;
    }

    @Override
    protected void build() {
        List<Guild> list = guilds();
        int pages = pageCount(list, PER_PAGE);
        int from = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && from + i < list.size(); i++) {
            Guild g = list.get(from + i);
            int rank = from + i + 1;
            String lore = "gui.list.lore-" + mode.name().toLowerCase();
            var icon = Menus.icon(plugin, g, lore);
            if (mode == Mode.TOP) icon.setAmount(Math.min(64, rank));
            button(9 + i, icon, t -> onPick(g));
        }
        if (list.isEmpty()) set(22, item(Material.STRUCTURE_VOID, "gui.list.empty"));
        Guild mine = myGuild();
        back(49, () -> Menus.openMain(plugin, viewer));
        pages(page, pages, p -> new GuildListMenu(plugin, viewer, mode, p).open());
        set(4, item(Material.OAK_HANGING_SIGN, "gui.list.header-" + mode.name().toLowerCase(), ph("count", list.size())));
        fill(mine != null ? mine.color : NamedTextColor.GOLD);
    }

    private void onPick(Guild g) {
        switch (mode) {
            case TOP -> {
                viewer.closeInventory();
                plugin.actions().info(viewer, g);
            }
            case BROWSE, INVITES -> {
                if (g.open || gm().isInvited(g, viewer.getUniqueId())) {
                    viewer.closeInventory();
                    plugin.actions().join(viewer, g.name);
                } else {
                    plugin.input().ask(viewer, "input.application-message", text -> plugin.actions().apply(viewer, g.name, text));
                }
            }
            case WAR_TARGET -> new ConfirmMenu(plugin, viewer, "gui.confirm.war", () -> {
                Guild mine = myGuild();
                if (mine != null) plugin.wars().declare(viewer, mine, g);
            }, () -> new GuildListMenu(plugin, viewer, mode, page).open(),
                    ph("target", g.name), ph("cost", fr.smpguilds.Messages.money(plugin.settings().d("war.declare-cost", 0)))).open();
            case ALLY_TARGET -> {
                Guild mine = plugin.actions().require(viewer, fr.smpguilds.GuildPerm.ALLY);
                if (mine == null) return;
                viewer.closeInventory();
                plugin.actions().ally(viewer, mine, g);
            }
        }
    }
}
