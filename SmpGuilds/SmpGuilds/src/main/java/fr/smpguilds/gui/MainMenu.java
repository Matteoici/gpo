package fr.smpguilds.gui;

import fr.smpguilds.Guild;
import fr.smpguilds.GuildActions;
import fr.smpguilds.GuildChat;
import fr.smpguilds.GuildPerm;
import fr.smpguilds.SmpGuilds;
import fr.smpguilds.WarManager;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import static fr.smpguilds.Messages.guild;
import static fr.smpguilds.Messages.ph;

/** Menu principal d'une guilde (/guild). */
public final class MainMenu extends Menu {

    public MainMenu(SmpGuilds plugin, Player viewer) {
        super(plugin, viewer, 6, plugin.messages().get("gui.main.title", guild(plugin.guilds().of(viewer))));
    }

    @Override
    protected void build() {
        Guild g = myGuild();
        if (g == null) {
            viewer.closeInventory();
            return;
        }
        GuildActions a = plugin.actions();
        TagResolver r = Menus.resolvers(plugin, g);

        set(4, Menus.icon(plugin, g, "gui.main.banner-lore"));

        button(19, item(Material.PLAYER_HEAD, "gui.main.members", r), t -> new MembersMenu(plugin, viewer, 0).open());
        button(20, item(Material.GOLD_INGOT, "gui.main.bank", r), t -> new BankMenu(plugin, viewer).open());
        button(21, item(Material.ENDER_CHEST, "gui.main.vault", r), t -> a.openVault(viewer));
        button(22, item(Material.RED_BED, "gui.main.home", r), t -> {
            viewer.closeInventory();
            if (t.isShiftClick()) a.setHome(viewer);
            else a.home(viewer);
        });
        button(23, item(Material.GRASS_BLOCK, "gui.main.claims", r), t -> {
            viewer.closeInventory();
            if (t.isShiftClick()) a.map(viewer);
            else if (t == ClickType.RIGHT) a.unclaim(viewer);
            else a.claim(viewer, false);
        });
        WarManager.War war = plugin.wars().warOf(g);
        button(24, item(war != null ? Material.NETHERITE_SWORD : Material.IRON_SWORD,
                war != null ? "gui.main.war-active" : "gui.main.war", r), t -> new WarMenu(plugin, viewer).open());
        button(25, item(Material.EXPERIENCE_BOTTLE, "gui.main.levels", r), t -> new LevelsMenu(plugin, viewer).open());

        button(29, item(Material.SHIELD, "gui.main.allies", r), t -> new AlliesMenu(plugin, viewer).open());
        button(30, item(g.applications.isEmpty() ? Material.BOOK : Material.WRITABLE_BOOK, "gui.main.applications", r),
                t -> new ApplicationsMenu(plugin, viewer).open());
        button(31, item(Material.COMPARATOR, "gui.main.settings", r), t -> new SettingsMenu(plugin, viewer).open());
        button(32, item(Material.WRITTEN_BOOK, "gui.main.log", r), t -> {
            if (gm().has(g, viewer.getUniqueId(), GuildPerm.LOG)) new LogMenu(plugin, viewer, 0).open();
            else msg().send(viewer, "error.no-permission");
        });
        button(33, item(Material.GOLD_BLOCK, "gui.main.top", r),
                t -> new GuildListMenu(plugin, viewer, GuildListMenu.Mode.TOP, 0).open());

        GuildChat.Mode mode = plugin.chat().mode(viewer);
        button(40, item(Material.OAK_SIGN, "gui.main.chat", r,
                fr.smpguilds.Messages.comp("mode", msg().get("chat.mode-name-" + mode.name().toLowerCase()))), t -> {
            plugin.chat().toggle(viewer, t == ClickType.RIGHT ? GuildChat.Mode.ALLY : GuildChat.Mode.GUILD);
            redraw();
        });

        boolean leader = g.leader.equals(viewer.getUniqueId());
        button(48, item(Material.DARK_OAK_DOOR, leader ? "gui.main.disband" : "gui.main.leave", r), t -> {
            if (leader) new ConfirmMenu(plugin, viewer, "gui.confirm.disband", () -> a.disband(viewer, true),
                    () -> new MainMenu(plugin, viewer).open()).open();
            else new ConfirmMenu(plugin, viewer, "gui.confirm.leave", () -> a.leave(viewer),
                    () -> new MainMenu(plugin, viewer).open()).open();
        });
        close(50);
        fill(g.color);
        set(49, item(Material.NETHER_STAR, "gui.main.you", r,
                ph("role_name", g.role(viewer.getUniqueId()).key()),
                fr.smpguilds.Messages.comp("role", msg().get("role." + g.role(viewer.getUniqueId()).key()))));
    }
}
