package fr.smpguilds.gui;

import fr.smpguilds.Guild;
import fr.smpguilds.GuildPerm;
import fr.smpguilds.Messages;
import fr.smpguilds.SmpGuilds;
import fr.smpguilds.WarManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import static fr.smpguilds.Messages.comp;
import static fr.smpguilds.Messages.ph;

/** Guerre en cours, déclaration, abandon, bilan. */
public final class WarMenu extends Menu {

    public WarMenu(SmpGuilds plugin, Player viewer) {
        super(plugin, viewer, 3, plugin.messages().get("gui.war.title"));
    }

    @Override
    protected void build() {
        Guild g = myGuild();
        if (g == null) {
            viewer.closeInventory();
            return;
        }
        boolean allowed = gm().has(g, viewer.getUniqueId(), GuildPerm.WAR);
        WarManager.War w = plugin.wars().warOf(g);
        set(4, item(Material.TOTEM_OF_UNDYING, "gui.war.record", ph("wars_won", g.warsWon), ph("wars_lost", g.warsLost),
                ph("kills", g.kills), ph("deaths", g.deaths), ph("kdr", g.kdr())));
        if (w == null) {
            set(13, item(Material.WHITE_BANNER, "gui.war.peace"));
            button(11, item(Material.NETHERITE_SWORD, "gui.war.declare",
                    ph("cost", Messages.money(plugin.settings().d("war.declare-cost", 0))),
                    ph("duration", plugin.settings().i("war.duration-minutes", 30)),
                    ph("kills", plugin.settings().i("war.kills-to-win", 15)),
                    ph("loot", plugin.settings().i("war.loot-percent", 10))), t -> {
                if (allowed) new GuildListMenu(plugin, viewer, GuildListMenu.Mode.WAR_TARGET, 0).open();
                else msg().send(viewer, "error.no-permission");
            });
        } else {
            Guild enemy = w.enemyOf(g);
            long left = Math.max(0, ((w.active ? w.endsAt : w.startsAt) - System.currentTimeMillis()) / 1000);
            set(13, item(Material.NETHERITE_SWORD, w.active ? "gui.war.active" : "gui.war.preparing",
                    comp("enemy", enemy.nameComponent()), ph("score", w.scoreOf(g)), ph("enemy_score", w.scoreOf(enemy)),
                    ph("time", Messages.time(left))));
            button(15, item(Material.WHITE_BANNER, "gui.war.surrender"), t -> {
                if (!allowed) {
                    msg().send(viewer, "error.no-permission");
                    return;
                }
                new ConfirmMenu(plugin, viewer, "gui.confirm.surrender", () -> plugin.wars().surrender(viewer),
                        () -> new WarMenu(plugin, viewer).open()).open();
            });
        }
        back(18, () -> new MainMenu(plugin, viewer).open());
        fill(g.color);
    }
}
