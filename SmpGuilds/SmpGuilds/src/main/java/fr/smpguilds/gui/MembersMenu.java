package fr.smpguilds.gui;

import fr.smpguilds.Guild;
import fr.smpguilds.GuildPerm;
import fr.smpguilds.Items;
import fr.smpguilds.MemberStats;
import fr.smpguilds.Messages;
import fr.smpguilds.Role;
import fr.smpguilds.SmpGuilds;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static fr.smpguilds.Messages.comp;
import static fr.smpguilds.Messages.ph;

/** Liste des membres (têtes), triés par rang. Clic = gérer le membre. */
public final class MembersMenu extends Menu {

    private static final int PER_PAGE = 36;
    private final int page;

    public MembersMenu(SmpGuilds plugin, Player viewer, int page) {
        super(plugin, viewer, 6, plugin.messages().get("gui.members.title"));
        this.page = page;
    }

    @Override
    protected void build() {
        Guild g = myGuild();
        if (g == null) {
            viewer.closeInventory();
            return;
        }
        List<UUID> list = new ArrayList<>(g.members.keySet());
        list.sort(Comparator.comparing((UUID u) -> g.role(u)).reversed().thenComparing(g::nameOf));
        SimpleDateFormat df = new SimpleDateFormat("dd/MM/yyyy");
        int from = page * PER_PAGE;
        Role mine = g.role(viewer.getUniqueId());
        for (int i = 0; i < PER_PAGE && from + i < list.size(); i++) {
            UUID u = list.get(from + i);
            MemberStats s = g.stats(u);
            Role role = g.role(u);
            boolean online = Bukkit.getPlayer(u) != null;
            boolean manageable = mine.higherThan(role) && (gm().has(g, viewer.getUniqueId(), GuildPerm.KICK)
                    || gm().has(g, viewer.getUniqueId(), GuildPerm.PROMOTE) || mine == Role.LEADER);
            var r = new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[]{
                    ph("player", g.nameOf(u)), comp("role", msg().get("role." + role.key())),
                    comp("status", msg().get(online ? "gui.members.online" : "gui.members.offline")),
                    ph("kills", s.kills), ph("deaths", s.deaths), ph("deposited", Messages.money(s.deposited)),
                    ph("joined", df.format(new Date(s.joined)))};
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>(msg().list("gui.members.lore", r));
            if (manageable) lore.addAll(msg().list("gui.members.lore-manage", r));
            var head = Items.head(Bukkit.getOfflinePlayer(u), msg().get("gui.members.name", r), lore);
            button(9 + i, head, t -> {
                if (manageable) new MemberMenu(plugin, viewer, u).open();
            });
        }
        set(4, item(Material.PLAYER_HEAD, "gui.members.header", ph("members", g.members.size()),
                ph("max_members", gm().level(g).maxMembers()), ph("online", g.online().size())));
        if (gm().has(g, viewer.getUniqueId(), GuildPerm.INVITE)) {
            button(47, item(Material.LIME_DYE, "gui.members.invite"), t ->
                    plugin.input().ask(viewer, "input.invite-player", name -> plugin.actions().invite(viewer, name)));
        }
        back(49, () -> new MainMenu(plugin, viewer).open());
        pages(page, pageCount(list, PER_PAGE), p -> new MembersMenu(plugin, viewer, p).open());
        fill(g.color);
    }
}
