package fr.smpguard.gui;

import fr.smpguard.Alerts;
import fr.smpguard.Check;
import fr.smpguard.Items;
import fr.smpguard.Lag;
import fr.smpguard.Profile;
import fr.smpguard.Settings;
import fr.smpguard.SmpGuard;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static fr.smpguard.Texts.ph;
import static fr.smpguard.Texts.pp;

/** /anticheat : tous les joueurs, les plus suspects en premier. */
public final class PanelMenu extends Menu {

    private int page;

    public PanelMenu(SmpGuard plugin, Player viewer, int page) {
        super(plugin, viewer, 6, plugin.texts().get("gui.panel.title"));
        this.page = page;
    }

    /** Lignes de score d'un joueur (une par détection). */
    static List<Component> scoreLines(Menu m, SmpGuard plugin, Profile prof) {
        List<Component> out = new ArrayList<>();
        for (Check c : Check.values()) {
            Settings.Limits lim = plugin.settings().limits(c);
            double vl = prof.vl[c.ordinal()];
            out.add(m.t().get("gui.score-line", pp("check", m.t().raw("checks." + c.key)),
                    pp("bar", Bars.bar(vl, lim.alertVl())), ph("vl", String.format(Locale.US, "%.1f", vl)),
                    ph("confidence", Alerts.confidence(vl, lim.alertVl()))));
        }
        return out;
    }

    @Override
    protected void build() {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.removeIf(p -> plugin.profile(p) == null);
        players.sort(Comparator.comparingDouble((Player p) -> -plugin.profile(p).maxVl())
                .thenComparing(p -> p.getName().toLowerCase(Locale.ROOT)));
        int alerts = 0;
        for (Profile p : plugin.profiles()) alerts += p.alerts;
        set(4, item(Material.SHIELD, "gui.panel.header", ph("online", players.size()), ph("alerts", alerts),
                ph("tps", String.format(Locale.US, "%.1f", Lag.tps())),
                ph("culling", plugin.settings().hideBehindWalls ? plugin.culling().hiddenPairs() + "" : "-")));

        int pages = pageCount(players.size(), GRID.length);
        page = Math.min(page, pages - 1);
        for (int i = 0; i < GRID.length; i++) {
            int idx = page * GRID.length + i;
            if (idx >= players.size()) break;
            Player p = players.get(idx);
            Profile prof = plugin.profile(p);
            int weight = (int) Math.round(plugin.lag().weight(prof) * 100);
            TagResolver[] r = {ph("player", p.getName()), ph("ping", prof.avgPing()), ph("jitter", Math.round(prof.jitter())),
                    ph("weight", weight), ph("alerts", prof.alerts)};
            List<Component> lore = new ArrayList<>(t().list("gui.panel.player.lore-top", r));
            String cn = fr.smpguard.ClientInfo.name(plugin, p);
            lore.add(fr.smpguard.Texts.parse("<gray>Client : " + fr.smpguard.ClientInfo.color(cn) + cn));
            lore.addAll(scoreLines(this, plugin, prof));
            lore.addAll(t().list("gui.panel.player.lore-bottom", r));
            ItemStack head = Items.head(p, t().get("gui.panel.player.name", r), lore);
            if (prof.maxVl() > 0.5) Items.glow(head);
            button(GRID[i], head, c -> {
                if (!p.isOnline()) {
                    redraw();
                    return;
                }
                if (c.isRightClick()) {
                    viewer.closeInventory();
                    viewer.performCommand("spec " + p.getName());
                } else {
                    new ReportMenu(plugin, viewer, p).open();
                }
            });
        }
        pages(page, pages, p -> new PanelMenu(plugin, viewer, p).open());
        button(47, item(Material.BELL, "gui.panel.alerts"), c -> {
            boolean on = plugin.alerts().toggle(viewer);
            t().send(viewer, on ? "alerts-on" : "alerts-off");
        });
        set(51, item(plugin.settings().hideBehindWalls ? Material.ENDER_EYE : Material.ENDER_PEARL, "gui.panel.anti-esp",
                pp("state", t().raw(plugin.settings().hideBehindWalls ? "gui.on" : "gui.off"))));
        if (Bukkit.getPluginManager().getPlugin("SmpAdmin") != null) {
            button(45, item(Material.NETHER_STAR, "gui.panel.admin"), c -> viewer.performCommand("admin"));
        }
        close(49);
    }
}
