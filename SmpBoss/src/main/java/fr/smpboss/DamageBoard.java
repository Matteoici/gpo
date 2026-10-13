package fr.smpboss;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static fr.smpboss.SmpBoss.parse;

/**
 * Tableau sur le côté de l'écran pendant le combat (comme sur les boss de raid) :
 * vie du boss, puis la part des dégâts de chaque joueur — en vert ceux qui ont assez tapé pour le butin.
 * Le tableau d'avant du joueur (autre plugin) lui est rendu dès qu'il s'éloigne ou que le combat finit.
 */
final class DamageBoard {

    private static final int LINES = 15;

    private final SmpBoss plugin;
    private final Scoreboard board;
    private final Objective obj;
    private final Map<UUID, Scoreboard> previous = new HashMap<>();
    private final Set<UUID> viewers = new HashSet<>();

    DamageBoard(SmpBoss plugin) {
        this.plugin = plugin;
        this.board = Bukkit.getScoreboardManager().getNewScoreboard();
        this.obj = board.registerNewObjective("smpboss", Criteria.DUMMY, parse(plugin.msg("board.title")));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        obj.numberFormat(NumberFormat.blank());
    }

    /** Met à jour les lignes et qui les voit. */
    void update(Collection<Player> near, double hp, double maxHp, Map<UUID, Double> damage, double minPct) {
        // qui voit le tableau
        Set<UUID> now = new HashSet<>();
        for (Player p : near) {
            now.add(p.getUniqueId());
            if (viewers.add(p.getUniqueId())) {
                previous.put(p.getUniqueId(), p.getScoreboard());
                p.setScoreboard(board);
            }
        }
        for (UUID id : new ArrayList<>(viewers)) if (!now.contains(id)) restore(id);

        // lignes
        List<Component> lines = new ArrayList<>();
        lines.add(parse(plugin.msg("board.hp").replace("<hp>", String.valueOf((long) Math.ceil(Math.max(0, hp))))
                .replace("<max>", String.valueOf((long) maxHp))));
        lines.add(parse(plugin.msg("board.fighters").replace("<n>", String.valueOf(damage.size()))));
        lines.add(Component.empty());
        lines.add(parse(plugin.msg("board.header")));
        double total = 0;
        for (double v : damage.values()) total += v;
        List<Map.Entry<UUID, Double>> sorted = new ArrayList<>(damage.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        int shown = 0;
        for (var e : sorted) {
            if (shown >= 9) break;
            String name = Bukkit.getOfflinePlayer(e.getKey()).getName();
            double pct = total <= 0 ? 0 : e.getValue() * 100 / total;
            String key = pct >= minPct * 100 ? "board.line-ok" : "board.line-low";
            lines.add(parse(plugin.msg(key).replace("<player>", SmpBoss.MM.escapeTags(name == null ? "?" : name))
                    .replace("<percent>", String.format(Locale.ROOT, "%.2f", pct))));
            shown++;
        }
        if (sorted.isEmpty()) lines.add(parse(plugin.msg("board.nobody")));
        lines.add(Component.text(" "));
        lines.add(parse(plugin.msg("board.footer").replace("<min>", fmt(minPct * 100))));

        for (int i = 0; i < LINES; i++) {
            String entry = "§" + Integer.toHexString(i) + "§r";
            if (i < lines.size()) {
                Score s = obj.getScore(entry);
                s.setScore(LINES - i);
                s.customName(lines.get(i));
            } else {
                board.resetScores(entry);
            }
        }
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private void restore(UUID id) {
        viewers.remove(id);
        Scoreboard prev = previous.remove(id);
        Player p = Bukkit.getPlayer(id);
        if (p == null || p.getScoreboard() != board) return;
        p.setScoreboard(prev != null ? prev : Bukkit.getScoreboardManager().getMainScoreboard());
    }

    /** Fin du combat : chacun retrouve son tableau d'avant. */
    void close() {
        for (UUID id : new ArrayList<>(viewers)) restore(id);
        obj.unregister();
    }
}
