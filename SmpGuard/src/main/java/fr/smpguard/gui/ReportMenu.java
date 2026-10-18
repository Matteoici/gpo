package fr.smpguard.gui;

import fr.smpguard.Check;
import fr.smpguard.Items;
import fr.smpguard.Profile;
import fr.smpguard.Settings;
import fr.smpguard.SmpGuard;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static fr.smpguard.Texts.ph;
import static fr.smpguard.Texts.pp;

/** Rapport détaillé d'un joueur + outils de vérification. */
public final class ReportMenu extends Menu {


    private final Player target;

    public ReportMenu(SmpGuard plugin, Player viewer, Player target) {
        super(plugin, viewer, 6, plugin.texts().get("gui.report.title", ph("player", target.getName())));
        this.target = target;
    }

    @Override
    protected void build() {
        Profile prof = plugin.profile(target);
        if (prof == null || !target.isOnline()) {
            viewer.closeInventory();
            return;
        }
        TagResolver[] r = {ph("player", target.getName()), ph("ping", prof.avgPing()), ph("jitter", Math.round(prof.jitter())),
                ph("weight", Math.round(plugin.lag().weight(prof) * 100)), ph("alerts", prof.alerts),
                ph("gamemode", target.getGameMode().name().toLowerCase(Locale.ROOT))};
        set(4, Items.head(target, t().get("gui.report.header.name", r), t().list("gui.report.header.lore", r)));

        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        for (Check c : Check.values()) {
            if (c.ordinal() >= slots.length) break;
            Settings.Limits lim = plugin.settings().limits(c);
            double vl = prof.vl[c.ordinal()];
            TagResolver[] cr = {pp("check", t().raw("checks." + c.key)), pp("bar", Bars.bar(vl, lim.alertVl())),
                    ph("vl", String.format(Locale.US, "%.1f", vl)), ph("peak", String.format(Locale.US, "%.1f", prof.peak[c.ordinal()])),
                    ph("confidence", fr.smpguard.Alerts.confidence(vl, lim.alertVl())),
                    ph("alert", String.format(Locale.US, "%.1f", lim.alertVl())),
                    pp("explain", t().raw("explain." + c.key))};
            var it = item(c.icon, "gui.report.check", cr);
            set(slots[c.ordinal()], vl >= lim.alertVl() ? Items.glow(it) : it);
        }

        // client du joueur (Vanilla, Fabric, Lunar...) et mods qui s'annoncent
        String cn = fr.smpguard.ClientInfo.name(plugin, target);
        java.util.List<String> mods = fr.smpguard.ClientInfo.mods(target);
        java.util.List<net.kyori.adventure.text.Component> lore = new java.util.ArrayList<>();
        lore.add(fr.smpguard.Texts.parse("<gray>Marque : <white>" + String.valueOf(target.getClientBrandName()).replace("<", "")));
        lore.add(fr.smpguard.Texts.parse("<gray>Mods annoncés : <white>" + mods.size()));
        for (int i = 0; i < Math.min(12, mods.size()); i++) lore.add(fr.smpguard.Texts.parse(" <dark_gray>- <white>" + mods.get(i)));
        if (mods.size() > 12) lore.add(fr.smpguard.Texts.parse(" <dark_gray>... +" + (mods.size() - 12)));
        set(49, Items.of(Material.COMPASS, fr.smpguard.Texts.parse("<aqua><b>Client :</b> " + fr.smpguard.ClientInfo.color(cn) + cn), lore));

        boolean admin = Bukkit.getPluginManager().getPlugin("SmpAdmin") != null;
        if (admin) {
            button(37, item(Material.ENDER_EYE, "gui.report.spec"), c -> {
                viewer.closeInventory();
                viewer.performCommand("spec " + target.getName());
            });
        }
        boolean running = plugin.bait(target.getUniqueId()) != null;
        button(39, item(running ? Material.CLOCK : Material.ZOMBIE_HEAD, running ? "gui.report.bait-running" : "gui.report.bait",
                ph("seconds", plugin.settings().baitSeconds)), c -> {
            if (plugin.bait(target.getUniqueId()) != null) return;
            plugin.startBait(viewer, target, plugin.settings().baitSeconds);
            redraw();
        });
        if (admin) {
            button(41, item(Material.NETHER_STAR, "gui.report.sheet"), c -> viewer.performCommand("admin " + target.getName()));
            button(43, item(Material.FERMENTED_SPIDER_EYE, "gui.report.sus"), c -> {
                viewer.performCommand("sus " + target.getName() + " [Anticheat] vérification manuelle");
                redraw();
            });
        }

        List<Component> history = new ArrayList<>();
        for (String line : prof.recent) history.add(Component.text(line, net.kyori.adventure.text.format.NamedTextColor.GRAY));
        if (history.isEmpty()) history.add(t().get("gui.report.no-history"));
        set(31, Items.of(Material.WRITABLE_BOOK, t().get("gui.report.history"), history));

        if (viewer.hasPermission("smpguard.admin")) {
            button(53, item(Material.LAVA_BUCKET, "gui.report.reset"), c -> {
                if (!c.isShiftClick()) return;
                java.util.Arrays.fill(prof.vl, 0);
                prof.susSent = false;
                t().send(viewer, "reset", ph("player", target.getName()));
                redraw();
            });
        }
        back(45, () -> new PanelMenu(plugin, viewer, 0).open());
        close(49);
    }
}
