package fr.smpevents.gui;

import fr.smpevents.Items;
import fr.smpevents.Settings;
import fr.smpevents.SmpEvents;
import fr.smpevents.Texts;
import fr.smpevents.bounty.Bounty;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

import static fr.smpevents.Texts.ph;

/** /prime : les têtes mises à prix, de la plus chère à la moins chère. */
public final class BountyMenu extends Menu {

    private final int page;

    public BountyMenu(SmpEvents plugin, Player viewer, int page) {
        super(plugin, viewer, 6, plugin.texts().get("gui.bounties.title"));
        this.page = page;
    }

    @Override
    protected void build() {
        Texts t = t();
        Settings s = plugin.settings();
        List<Bounty> list = plugin.bounties().sorted();
        int pages = pageCount(list.size(), GRID.length);
        int p = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < GRID.length; i++) {
            int idx = p * GRID.length + i;
            if (idx >= list.size()) break;
            Bounty b = list.get(idx);
            List<Component> lore = new ArrayList<>(t.list("gui.bounties.entry.lore", ph("total", s.money(b.total())),
                    ph("count", b.contributions.size()), ph("since", Settings.time(System.currentTimeMillis() - b.created)),
                    ph("online", Bukkit.getPlayer(b.target) != null ? t.raw("gui.bounties.online") : t.raw("gui.bounties.offline"))));
            int shown = 0;
            for (var e : b.contributions.entrySet()) {
                if (shown++ >= 3) break;
                lore.add(t.get("gui.bounties.entry.from", ph("player", b.names.getOrDefault(e.getKey(), "?")),
                        ph("amount", s.money(e.getValue()))));
            }
            lore.addAll(t.list("gui.bounties.entry.footer", ph("player", b.targetName)));
            set(GRID[i], Items.head(Bukkit.getOfflinePlayer(b.target),
                    t.get("gui.bounties.entry.name", ph("rank", idx + 1), ph("player", b.targetName)), lore));
        }
        if (list.isEmpty()) set(22, item(Material.STRUCTURE_VOID, "gui.bounties.none"));
        Bounty mine = plugin.bounties().of(viewer.getUniqueId());
        set(4, item(Material.TARGET, "gui.bounties.header", ph("count", list.size()),
                ph("min", s.money(plugin.bounties().minAmount)), ph("fee", (int) plugin.bounties().feePercent),
                ph("days", plugin.bounties().expireDays),
                ph("mine", mine == null ? t.raw("gui.bounties.mine-none") : s.money(mine.total()))));
        button(47, item(Material.WRITABLE_BOOK, "gui.boards.to-quests"), c -> new QuestMenu(plugin, viewer).open());
        close(49);
        pages(p, pages, np -> new BountyMenu(plugin, viewer, np).open());
    }
}
