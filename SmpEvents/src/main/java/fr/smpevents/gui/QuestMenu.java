package fr.smpevents.gui;

import fr.smpevents.Items;
import fr.smpevents.PlayerData;
import fr.smpevents.Settings;
import fr.smpevents.SmpEvents;
import fr.smpevents.Texts;
import fr.smpevents.quest.QuestDef;
import fr.smpevents.quest.QuestProgress;
import fr.smpevents.quest.Quests;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

import static fr.smpevents.Texts.pc;
import static fr.smpevents.Texts.ph;
import static fr.smpevents.Texts.pp;

/** /quetes : quêtes du jour, bonus de série, quêtes de la semaine. */
public final class QuestMenu extends Menu {

    public QuestMenu(SmpEvents plugin, org.bukkit.entity.Player viewer) {
        super(plugin, viewer, 6, plugin.texts().get("gui.quests.title"));
    }

    /** Cases centrées sur une ligne pour n objets. */
    static int[] centered(int row, int n) {
        int[] out = new int[n];
        if (n <= 0) return out;
        if (n <= 4) {
            int start = 4 - (n - 1);
            for (int i = 0; i < n; i++) out[i] = row * 9 + start + i * 2;
        } else {
            int start = 4 - (Math.min(n, 7) - 1) / 2;
            for (int i = 0; i < n; i++) out[i] = row * 9 + Math.min(7, start + i);
        }
        return out;
    }

    @Override
    protected void build() {
        Quests q = plugin.quests();
        PlayerData d = plugin.data().of(viewer);
        q.ensure(d);
        Settings s = plugin.settings();
        Texts t = t();

        int doneDaily = 0;
        for (QuestProgress p : d.daily) if (p.done) doneDaily++;
        int rerollsLeft = Math.max(0, q.rerollsPerDay - d.rerollsUsed);

        set(4, Items.head(viewer, t.get("gui.quests.header.name", ph("player", viewer.getName())),
                t.list("gui.quests.header.lore", ph("streak", d.streak), ph("done", d.questsDone),
                        ph("reset", Settings.time(s.msToNextDay())), ph("week-reset", Settings.time(s.msToNextWeek())),
                        ph("rerolls", rerollsLeft))));

        // ---- quêtes du jour
        int[] slots = centered(2, d.daily.size());
        for (int i = 0; i < d.daily.size(); i++) {
            QuestProgress p = d.daily.get(i);
            QuestDef def = q.pool().get(p.id);
            if (def == null) continue;
            final int index = i;
            button(slots[i], questItem(def, p, true, rerollsLeft), click -> {
                if (click == ClickType.SHIFT_RIGHT || click == ClickType.SHIFT_LEFT) {
                    q.reroll(viewer, index);
                    redraw();
                }
            });
        }

        // ---- bonus de la journée
        int total = d.daily.size();
        boolean ready = d.dailyBonusGiven;
        int nextStreak = ready ? d.streak : (d.lastFullDay.equals(s.questDay().minusDays(1).toString()) ? d.streak + 1 : 1);
        double bonus = q.bonusFor(nextStreak);
        TagResolver r = TagResolver.resolver(ph("done", doneDaily), ph("total", total), ph("money", s.money(bonus)),
                ph("streak", nextStreak), ph("percent", (int) Math.round(q.streakExtra(nextStreak) * 100)),
                pp("bar", Quests.progressBar(doneDaily, Math.max(1, total), 20)));
        ItemStack chest = item(ready ? Material.ENDER_CHEST : Material.CHEST, ready ? "gui.quests.bonus-done" : "gui.quests.bonus", r);
        if (ready) Items.glow(chest);
        set(31, chest);

        // ---- quêtes de la semaine
        int[] wslots = centered(4, d.weekly.size());
        for (int i = 0; i < d.weekly.size(); i++) {
            QuestProgress p = d.weekly.get(i);
            QuestDef def = q.pool().get(p.id);
            if (def == null) continue;
            set(wslots[i], questItem(def, p, false, 0));
        }

        set(18, item(Material.SUNFLOWER, "gui.quests.daily-label", ph("reset", Settings.time(s.msToNextDay()))));
        set(36, item(Material.CLOCK, "gui.quests.weekly-label", ph("reset", Settings.time(s.msToNextWeek()))));
        button(45, item(Material.GOLDEN_HELMET, "gui.quests.to-boards"), c -> new BoardMenu(plugin, viewer).open());
        close(49);
        button(53, item(Material.TARGET, "gui.quests.to-bounties"), c -> new BountyMenu(plugin, viewer, 0).open());
    }

    private ItemStack questItem(QuestDef def, QuestProgress p, boolean daily, int rerollsLeft) {
        Quests q = plugin.quests();
        Texts t = t();
        Component name = q.questName(def);
        String tierKey = "gui.quests.tier." + def.tier();
        Component tier = t.get(tierKey);
        int pct = (int) Math.floor(100.0 * p.progress / Math.max(1, def.amount()));
        TagResolver r = TagResolver.resolver(pc("quest", name), pc("tier", tier), ph("progress", Quests.fmt(p.progress)),
                ph("amount", Quests.fmt(def.amount())), ph("percent", pct), ph("money", plugin.settings().money(q.reward(def))),
                pp("bar", Quests.progressBar(p.progress, def.amount(), 20)), ph("rerolls", rerollsLeft),
                ph("price", plugin.settings().money(q.rerollCost)));
        List<Component> lore = new ArrayList<>(t.list(p.done ? "gui.quests.quest.lore-done" : "gui.quests.quest.lore", r));
        if (!p.done && !def.commands().isEmpty()) lore.addAll(t.list("gui.quests.quest.lore-extra", r));
        if (daily && !p.done && q.rerollsPerDay > 0) lore.addAll(t.list(rerollsLeft > 0 ? "gui.quests.quest.reroll" : "gui.quests.quest.no-reroll", r));
        ItemStack it = Items.of(p.done ? Material.LIME_DYE : def.icon(), t.get(p.done ? "gui.quests.quest.name-done" : "gui.quests.quest.name", r), lore);
        if (p.done) Items.glow(it);
        return it;
    }
}
