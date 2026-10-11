package fr.smpadmin.gui;

import fr.smpadmin.Items;
import fr.smpadmin.SmpAdmin;
import fr.smpadmin.Texts;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static fr.smpadmin.Texts.pc;
import static fr.smpadmin.Texts.ph;

/** /admin : barre d'outils + tous les joueurs connectés. */
public final class MainMenu extends Menu {

    static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    private int page;

    public MainMenu(SmpAdmin plugin, Player viewer, int page) {
        super(plugin, viewer, 6, plugin.texts().get("gui.main.title"));
        this.page = page;
    }

    @Override
    protected void build() {
        var state = plugin.state();
        int staff = 0;
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("smpadmin.panel")) staff++;

        Component on = t().get("gui.main.on");
        Component off = t().get("gui.main.off");

        boolean vanished = plugin.vanish().is(viewer);
        button(1, item(vanished ? Material.LIME_DYE : Material.GRAY_DYE, "gui.main.vanish", pc("state", vanished ? on : off)), c -> {
            if (!viewer.hasPermission("smpadmin.vanish")) return;
            plugin.vanish().toggle(viewer);
            redraw();
        });
        boolean watching = plugin.spectate().isWatching(viewer);
        ItemStack spec = item(Material.ENDER_EYE, "gui.main.spec", ph("seconds", plugin.settings().specInterval));
        button(2, watching ? Items.glow(spec) : spec, c -> {
            if (!viewer.hasPermission("smpadmin.spec")) return;
            viewer.closeInventory();
            if (plugin.spectate().isWatching(viewer)) plugin.spectate().stop(viewer);
            else plugin.spectate().startAuto(viewer);
        });
        button(3, item(Material.FERMENTED_SPIDER_EYE, "gui.main.suspects", ph("count", state.suspects().size())),
                c -> new SuspectsMenu(plugin, viewer, 0).open());

        set(4, Items.head(viewer, t().get("gui.main.header.name"), t().list("gui.main.header.lore",
                ph("online", Bukkit.getOnlinePlayers().size()), ph("staff", staff),
                ph("bans", plugin.sanctions().activeBans().size()), ph("sus", state.suspects().size()))));

        button(5, item(Material.IRON_BARS, "gui.main.bans", ph("count", plugin.sanctions().activeBans().size())),
                c -> new BansMenu(plugin, viewer, 0).open());
        boolean sc = state.staffChat.contains(viewer.getUniqueId());
        button(6, item(Material.WRITABLE_BOOK, "gui.main.staffchat", pc("state", sc ? on : off)), c -> {
            if (!state.staffChat.remove(viewer.getUniqueId())) state.staffChat.add(viewer.getUniqueId());
            redraw();
        });
        button(7, item(Material.BELL, "gui.main.chatlock",
                pc("state", t().get(state.chatLocked ? "gui.main.locked" : "gui.main.open"))), c -> {
            if (!viewer.hasPermission("smpadmin.chat")) return;
            plugin.actions().toggleChatLock(viewer);
            redraw();
        });

        // ---- joueurs : suspects d'abord, puis ordre alphabétique
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.sort(Comparator.comparing((Player p) -> state.suspect(p.getUniqueId()) == null)
                .thenComparing(p -> p.getName().toLowerCase(Locale.ROOT)));
        int pages = pageCount(players.size(), SLOTS.length);
        page = Math.min(page, pages - 1);
        for (int i = 0; i < SLOTS.length; i++) {
            int idx = page * SLOTS.length + i;
            if (idx >= players.size()) break;
            Player target = players.get(idx);
            button(SLOTS[i], playerItem(target), c -> clickPlayer(target, c));
        }
        pages(page, pages, p -> new MainMenu(plugin, viewer, p).open());
        button(47, item(Material.SPONGE, "gui.main.clearchat"), c -> {
            if (!viewer.hasPermission("smpadmin.chat")) return;
            plugin.actions().clearChat(viewer);
        });
        // lien avec l'anticheat SmpGuard (s'il est installé)
        if (org.bukkit.Bukkit.getPluginManager().isPluginEnabled("SmpGuard") && viewer.hasPermission("smpguard.staff")) {
            button(51, item(Material.SHIELD, "gui.main.anticheat"), c -> viewer.performCommand("anticheat"));
        }
        close(49);
    }

    private ItemStack playerItem(Player p) {
        StringBuilder flags = new StringBuilder();
        if (plugin.state().suspect(p.getUniqueId()) != null) flags.append(t().raw("gui.main.flag-sus")).append(' ');
        if (plugin.actions().isFrozen(p.getUniqueId())) flags.append(t().raw("gui.main.flag-frozen")).append(' ');
        if (plugin.sanctions().activeMute(p.getUniqueId()) != null) flags.append(t().raw("gui.main.flag-muted")).append(' ');
        if (plugin.vanish().is(p)) flags.append(t().raw("gui.main.flag-vanish"));
        TagResolver[] r = {
                ph("player", p.getName()), Texts.pp("flags", flags.toString().trim()),
                ph("world", p.getWorld().getName()), ph("gamemode", p.getGameMode().name().toLowerCase(Locale.ROOT)),
                ph("health", (int) Math.ceil(p.getHealth())), ph("ping", p.getPing()),
                ph("sanctions", plugin.sanctions().count(p.getUniqueId(), null))};
        ItemStack head = Items.head(p, t().get("gui.main.player.name", r), t().list("gui.main.player.lore", r));
        return plugin.state().suspect(p.getUniqueId()) != null ? Items.glow(head) : head;
    }

    private void clickPlayer(Player target, ClickType c) {
        if (!target.isOnline()) {
            redraw();
            return;
        }
        if (c.isShiftClick()) {
            viewer.closeInventory();
            viewer.teleport(target.getLocation());
        } else if (c.isRightClick()) {
            if (!viewer.hasPermission("smpadmin.spec")) return;
            viewer.closeInventory();
            plugin.spectate().watch(viewer, target);
        } else {
            new PlayerMenu(plugin, viewer, target).open();
        }
    }
}
