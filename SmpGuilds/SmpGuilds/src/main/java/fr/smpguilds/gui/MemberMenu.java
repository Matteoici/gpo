package fr.smpguilds.gui;

import fr.smpguilds.Guild;
import fr.smpguilds.GuildActions;
import fr.smpguilds.Items;
import fr.smpguilds.Role;
import fr.smpguilds.SmpGuilds;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

import static fr.smpguilds.Messages.comp;
import static fr.smpguilds.Messages.ph;

/** Gestion d'un membre : promouvoir, rétrograder, expulser, transmettre la direction. */
public final class MemberMenu extends Menu {

    private final UUID target;

    public MemberMenu(SmpGuilds plugin, Player viewer, UUID target) {
        super(plugin, viewer, 3, plugin.messages().get("gui.member.title",
                ph("player", plugin.guilds().of(viewer) != null ? plugin.guilds().of(viewer).nameOf(target) : "?")));
        this.target = target;
    }

    @Override
    protected void build() {
        Guild g = myGuild();
        if (g == null || !g.isMember(target)) {
            new MembersMenu(plugin, viewer, 0).open();
            return;
        }
        GuildActions a = plugin.actions();
        String name = g.nameOf(target);
        Role role = g.role(target);
        set(4, Items.head(Bukkit.getOfflinePlayer(target), msg().get("gui.members.name", ph("player", name)),
                List.of(msg().get("role." + role.key()))));
        button(10, item(Material.LIME_DYE, "gui.member.promote", ph("player", name),
                comp("role", msg().get("role." + role.next().key()))), t -> {
            a.promote(viewer, g, target);
            redraw();
        });
        button(12, item(Material.ORANGE_DYE, "gui.member.demote", ph("player", name),
                comp("role", msg().get("role." + role.previous().key()))), t -> {
            a.demote(viewer, g, target);
            redraw();
        });
        button(14, item(Material.IRON_DOOR, "gui.member.kick", ph("player", name)), t ->
                new ConfirmMenu(plugin, viewer, "gui.confirm.kick", () -> a.kick(viewer, g, target),
                        () -> new MemberMenu(plugin, viewer, target).open(), ph("player", name)).open());
        if (g.leader.equals(viewer.getUniqueId())) {
            button(16, item(Material.GOLDEN_HELMET, "gui.member.transfer", ph("player", name)), t ->
                    new ConfirmMenu(plugin, viewer, "gui.confirm.transfer", () -> a.transfer(viewer, g, target),
                            () -> new MemberMenu(plugin, viewer, target).open(), ph("player", name)).open());
        }
        back(22, () -> new MembersMenu(plugin, viewer, 0).open());
        fill(g.color);
    }
}
