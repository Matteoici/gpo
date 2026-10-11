package fr.smpevents.quest;

import fr.smpevents.PlayerData;
import fr.smpevents.Settings;
import fr.smpevents.SmpEvents;
import fr.smpevents.Texts;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static fr.smpevents.Texts.pc;
import static fr.smpevents.Texts.ph;

/** Quêtes du jour (par difficulté) et de la semaine, récompenses, série de jours. */
public final class Quests {

    private final SmpEvents plugin;
    private final QuestPool pool;
    private final Random random = new Random();
    private final Map<UUID, Long> lastBar = new HashMap<>();

    public boolean enabled;
    public double multiplier;
    public final Map<String, Integer> dailySlots = new LinkedHashMap<>();
    public int weeklyCount;
    public double dailyBonus;
    public double streakPercent;
    public int streakMax;
    public List<String> bonusCommands = List.of();
    public int rerollsPerDay;
    public double rerollCost;
    public boolean countSpawnerMobs;
    public long playerKillCooldownMs;
    public boolean announceWeekly;
    public boolean actionBar;

    public Quests(SmpEvents plugin, QuestPool pool) {
        this.plugin = plugin;
        this.pool = pool;
    }

    public void load(ConfigurationSection c) {
        if (c == null) c = plugin.getConfig().createSection("quests");
        enabled = c.getBoolean("enabled", true);
        multiplier = c.getDouble("rewards-multiplier", 1.0);
        dailySlots.clear();
        ConfigurationSection slots = c.getConfigurationSection("daily-slots");
        if (slots != null) for (String k : slots.getKeys(false)) dailySlots.put(k.toLowerCase(), Math.max(0, slots.getInt(k)));
        if (dailySlots.isEmpty()) {
            dailySlots.put("easy", 1);
            dailySlots.put("medium", 1);
            dailySlots.put("hard", 1);
        }
        weeklyCount = Math.max(0, c.getInt("weekly-count", 2));
        dailyBonus = c.getDouble("daily-bonus", 50000);
        streakPercent = c.getDouble("streak-bonus-percent", 10);
        streakMax = Math.max(1, c.getInt("streak-max-days", 10));
        bonusCommands = c.getStringList("daily-bonus-commands");
        rerollsPerDay = Math.max(0, c.getInt("rerolls-per-day", 2));
        rerollCost = c.getDouble("reroll-cost", 5000);
        countSpawnerMobs = c.getBoolean("count-spawner-mobs", false);
        playerKillCooldownMs = Math.max(0, c.getLong("player-kill-cooldown-hours", 2)) * 3_600_000L;
        announceWeekly = c.getBoolean("announce-weekly", true);
        actionBar = c.getBoolean("action-bar", true);
    }

    public QuestPool pool() {
        return pool;
    }

    public int dailyTotal() {
        int n = 0;
        for (int v : dailySlots.values()) n += v;
        return n;
    }

    // ================================================================= attribution

    /** Donne de nouvelles quêtes si le jour / la semaine a changé. */
    public void ensure(PlayerData d) {
        Settings s = plugin.settings();
        String today = s.dayKey();
        if (!today.equals(d.day)) {
            String yesterday = s.questDay().minusDays(1).toString();
            if (!d.lastFullDay.equals(yesterday) && !d.lastFullDay.equals(today)) d.streak = 0;
            d.day = today;
            d.daily.clear();
            d.dailyBonusGiven = false;
            d.rerollsUsed = 0;
        }
        // retire les quêtes qui n'existent plus dans quests.yml, puis complète
        d.daily.removeIf(q -> pool.get(q.id) == null || pool.get(q.id).weekly());
        Map<String, Integer> have = new HashMap<>();
        for (QuestProgress q : d.daily) have.merge(pool.get(q.id).tier(), 1, Integer::sum);
        for (var e : dailySlots.entrySet()) {
            int missing = e.getValue() - have.getOrDefault(e.getKey(), 0);
            for (int i = 0; i < missing; i++) {
                QuestDef def = pick(pool.tier(e.getKey(), false), d.daily);
                if (def != null) d.daily.add(new QuestProgress(def.id()));
            }
        }
        String week = s.weekKey();
        if (!week.equals(d.week)) {
            d.week = week;
            d.weekly.clear();
        }
        d.weekly.removeIf(q -> pool.get(q.id) == null || !pool.get(q.id).weekly());
        while (d.weekly.size() < weeklyCount) {
            QuestDef def = pick(pool.weekly(), d.weekly);
            if (def == null) break;
            d.weekly.add(new QuestProgress(def.id()));
        }
        sortDaily(d);
    }

    /** Ordre d'affichage : facile, moyen, difficile... (ordre de daily-slots). */
    private void sortDaily(PlayerData d) {
        List<String> order = new ArrayList<>(dailySlots.keySet());
        d.daily.sort((a, b) -> Integer.compare(order.indexOf(pool.get(a.id).tier()), order.indexOf(pool.get(b.id).tier())));
    }

    private QuestDef pick(List<QuestDef> candidates, List<QuestProgress> current) {
        Set<String> used = new HashSet<>();
        Set<QuestType> usedTypes = new HashSet<>();
        for (QuestProgress q : current) {
            used.add(q.id);
            QuestDef d = pool.get(q.id);
            if (d != null) usedTypes.add(d.type());
        }
        List<QuestDef> fresh = new ArrayList<>(), any = new ArrayList<>();
        for (QuestDef c : candidates) {
            if (used.contains(c.id())) continue;
            any.add(c);
            if (!usedTypes.contains(c.type())) fresh.add(c);
        }
        // on évite deux quêtes du même genre le même jour quand c'est possible
        List<QuestDef> from = fresh.isEmpty() ? any : fresh;
        return from.isEmpty() ? null : from.get(random.nextInt(from.size()));
    }

    // ================================================================= avancement

    public void progress(Player p, QuestType type, String target, long amount) {
        if (!enabled || amount <= 0 || !p.hasPermission("smpevents.quests")) return;
        PlayerData d = plugin.data().of(p);
        ensure(d);
        boolean any = false;
        for (int pass = 0; pass < 2; pass++) {
            List<QuestProgress> list = pass == 0 ? d.daily : d.weekly;
            for (QuestProgress q : list) {
                if (q.done) continue;
                QuestDef def = pool.get(q.id);
                if (def == null || def.type() != type || !def.accepts(target)) continue;
                q.progress = Math.min(def.amount(), q.progress + amount);
                any = true;
                if (q.progress >= def.amount()) complete(p, d, def, q);
                else bar(p, def, q);
            }
        }
        if (any) plugin.markDirty();
    }

    private void bar(Player p, QuestDef def, QuestProgress q) {
        if (!actionBar) return;
        long now = System.currentTimeMillis();
        Long last = lastBar.get(p.getUniqueId());
        if (last != null && now - last < 400) return;
        lastBar.put(p.getUniqueId(), now);
        Texts t = plugin.texts();
        p.sendActionBar(t.get("quests.action-bar", pc("quest", questName(def)), ph("progress", q.progress),
                ph("amount", def.amount()), Texts.pp("bar", progressBar(q.progress, def.amount(), 20))));
    }

    public double reward(QuestDef def) {
        return Math.round(def.reward() * multiplier);
    }

    private void complete(Player p, PlayerData d, QuestDef def, QuestProgress q) {
        q.done = true;
        q.progress = def.amount();
        d.questsDone++;
        double money = reward(def);
        plugin.giveMoney(p, money);
        runCommands(p, def.commands());
        Texts t = plugin.texts();
        Component name = questName(def);
        t.send(p, def.weekly() ? "quests.done-weekly" : "quests.done", pc("quest", name), ph("money", plugin.settings().money(money)));
        p.showTitle(Title.title(t.get(def.weekly() ? "quests.title-weekly" : "quests.title"), name,
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1800), Duration.ofMillis(500))));
        p.playSound(Sound.sound(Key.key("minecraft", "ui.toast.challenge_complete"), Sound.Source.MASTER, 0.7f, 1.2f));
        if (def.weekly() && announceWeekly) {
            Bukkit.broadcast(t.prefixed("quests.broadcast-weekly", ph("player", p.getName()), pc("quest", name),
                    ph("money", plugin.settings().money(money))));
        }
        if (!def.weekly()) checkDailyBonus(p, d);
    }

    private void checkDailyBonus(Player p, PlayerData d) {
        if (d.dailyBonusGiven || d.daily.isEmpty()) return;
        for (QuestProgress q : d.daily) if (!q.done) return;
        Settings s = plugin.settings();
        String today = s.dayKey();
        String yesterday = s.questDay().minusDays(1).toString();
        d.streak = d.lastFullDay.equals(yesterday) ? d.streak + 1 : 1;
        d.lastFullDay = today;
        d.dailyBonusGiven = true;
        double bonus = bonusFor(d.streak);
        plugin.giveMoney(p, bonus);
        runCommands(p, bonusCommands);
        plugin.texts().send(p, "quests.bonus", ph("money", s.money(bonus)), ph("streak", d.streak),
                ph("percent", (int) Math.round(streakExtra(d.streak) * 100)));
        p.playSound(Sound.sound(Key.key("minecraft", "entity.player.levelup"), Sound.Source.MASTER, 0.8f, 1.0f));
    }

    public double streakExtra(int streak) {
        return Math.min(Math.max(0, streak - 1), streakMax) * streakPercent / 100.0;
    }

    public double bonusFor(int streak) {
        return Math.round(dailyBonus * multiplier * (1 + streakExtra(streak)));
    }

    private void runCommands(Player p, List<String> cmds) {
        for (String c : cmds) {
            String line = c.replace("%player%", p.getName()).replace("<player>", p.getName());
            if (line.startsWith("/")) line = line.substring(1);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
        }
    }

    // ================================================================= changer une quête

    public void reroll(Player p, int index) {
        PlayerData d = plugin.data().of(p);
        ensure(d);
        if (index < 0 || index >= d.daily.size()) return;
        QuestProgress q = d.daily.get(index);
        Texts t = plugin.texts();
        if (q.done) {
            t.send(p, "quests.reroll-done");
            return;
        }
        if (d.rerollsUsed >= rerollsPerDay) {
            t.send(p, "quests.reroll-none");
            return;
        }
        QuestDef old = pool.get(q.id);
        List<QuestProgress> others = new ArrayList<>(d.daily);
        QuestDef next = pick(pool.tier(old.tier(), false), others);
        if (next == null) {
            t.send(p, "quests.reroll-empty");
            return;
        }
        if (rerollCost > 0 && !plugin.takeMoney(p, rerollCost)) {
            t.send(p, "quests.reroll-money", ph("price", plugin.settings().money(rerollCost)));
            return;
        }
        d.rerollsUsed++;
        d.daily.set(index, new QuestProgress(next.id()));
        plugin.markDirty();
        t.send(p, "quests.rerolled", pc("quest", questName(next)));
        p.playSound(Sound.sound(Key.key("minecraft", "item.book.page_turn"), Sound.Source.MASTER, 1f, 1f));
    }

    public void reset(PlayerData d) {
        d.day = "";
        d.week = "";
        d.daily.clear();
        d.weekly.clear();
        ensure(d);
        plugin.markDirty();
    }

    // ================================================================= affichage

    public Component questName(QuestDef def) {
        return Texts.parse(def.name(), ph("amount", fmt(def.amount())));
    }

    public static String fmt(long v) {
        return String.format(java.util.Locale.FRANCE, "%,d", v).replace(' ', ' ').replace(' ', ' ');
    }

    /** Barre MiniMessage (vert = fait, gris = reste). */
    public static String progressBar(long progress, long amount, int width) {
        int full = (int) Math.round(width * Math.min(1.0, progress / (double) Math.max(1, amount)));
        return "<green>" + "|".repeat(full) + "</green><dark_gray>" + "|".repeat(width - full) + "</dark_gray>";
    }

    public void forget(UUID id) {
        lastBar.remove(id);
    }
}
