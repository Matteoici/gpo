package fr.smpshop.gui;

import fr.smpshop.Items;
import fr.smpshop.SellHistory;
import fr.smpshop.SmpShop;
import fr.smpshop.Texts;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static fr.smpshop.Texts.ph;

/** Historique des ventes d'un joueur : un objet par vente (la plus récente en premier), avec le détail. */
public final class HistoryMenu extends Menu {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM 'à' HH:mm");
    private final OfflinePlayer target;
    private int page;

    public HistoryMenu(SmpShop plugin, Player viewer, OfflinePlayer target, int page) {
        super(plugin, viewer, 6, Texts.parse(plugin.texts().raw("history.title").replace("<player>",
                Texts.MM.escapeTags(target.getName() == null ? "?" : target.getName()))));
        this.target = target;
        this.page = page;
    }

    private ZoneId zone() {
        try {
            return ZoneId.of(plugin.getConfig().getString("historique.fuseau", "Europe/Paris"));
        } catch (Exception e) {
            return ZoneId.of("Europe/Paris");
        }
    }

    @Override
    protected void build() {
        List<SellHistory.Sale> sales = plugin.history().of(target.getUniqueId());
        // ---- résumé
        double total = 0, today = 0;
        long items = 0;
        Map<Material, double[]> best = new HashMap<>();
        LocalDate now = LocalDate.now(zone());
        for (SellHistory.Sale s : sales) {
            total += s.money();
            items += s.count();
            if (Instant.ofEpochMilli(s.time()).atZone(zone()).toLocalDate().equals(now)) today += s.money();
            for (SellHistory.Line l : s.lines()) {
                double[] d = best.computeIfAbsent(l.material(), k -> new double[2]);
                d[0] += l.count();
                d[1] += l.money();
            }
        }
        Material top = null;
        double topMoney = 0;
        for (var e : best.entrySet()) if (e.getValue()[1] > topMoney) {
            topMoney = e.getValue()[1];
            top = e.getKey();
        }
        List<Component> lore = new ArrayList<>(t().list("history.summary.lore", ph("sales", sales.size()), ph("items", items),
                ph("total", money(total)), ph("today", money(today))));
        if (top != null) {
            lore.add(Component.empty());
            lore.add(Texts.parse(t().raw("history.summary.top").replace("<money>", money(topMoney)).replace("<count>", fmt((long) best.get(top)[0])))
                    .append(Component.text(" ")).append(Component.translatable(top.translationKey()).color(NamedTextColor.WHITE)
                            .decoration(TextDecoration.ITALIC, false)));
        }
        set(4, Items.head(target, t().get("history.summary.name", ph("player", target.getName() == null ? "?" : target.getName())), lore));
        if (sales.isEmpty()) {
            set(22, item(Material.PAPER, "history.empty"));
        }
        // ---- une case par vente
        int per = GRID.length;
        int pages = pageCount(sales.size(), per);
        page = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < per; i++) {
            int idx = page * per + i;
            if (idx >= sales.size()) break;
            set(GRID[i], saleItem(sales.get(idx)));
        }
        pages(page, pages, p -> {
            page = p;
            redraw();
        });
        close(49);
    }

    private org.bukkit.inventory.ItemStack saleItem(SellHistory.Sale s) {
        Material icon = s.lines().isEmpty() ? Material.GOLD_INGOT : s.lines().get(0).material();
        if (!icon.isItem() || icon.isAir()) icon = Material.GOLD_INGOT;
        String when = DATE.format(Instant.ofEpochMilli(s.time()).atZone(zone()));
        Component name = t().get("history.sale.name", ph("money", money(s.money())), ph("date", when));
        List<Component> lore = new ArrayList<>(t().list("history.sale.lore", ph("count", fmt(s.count())), ph("date", when),
                ph("bonus", Math.round((s.multiplier() - 1) * 100))));
        if (s.multiplier() <= 1.0001) lore.removeIf(c -> Texts.MM.serialize(c).contains("%"));
        int shown = 0;
        for (SellHistory.Line l : s.lines()) {
            if (shown++ >= 10) {
                lore.add(Texts.parse(t().raw("history.sale.more").replace("<n>", String.valueOf(s.lines().size() - 10))));
                break;
            }
            Component itemName = l.name().isEmpty() ? Component.translatable(l.material().translationKey()) : Component.text(l.name());
            lore.add(Texts.parse("<dark_gray>• <white>" + fmt(l.count()) + "×</white> ").append(itemName.color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)).append(Texts.parse("  <gold>" + money(l.money()))));
        }
        var it = Items.of(icon, name, lore);
        it.setAmount((int) Math.max(1, Math.min(64, s.count())));
        return it;
    }

    private static String fmt(long n) {
        return String.format(java.util.Locale.FRANCE, "%,d", n).replace(' ', ' ').replace(' ', ' ');
    }
}
