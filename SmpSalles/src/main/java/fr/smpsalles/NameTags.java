package fr.smpsalles;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Pseudos au-dessus de la tête cachés pour ceux qui sont dans une salle (on ne les voit plus à travers les murs).
 * Si le joueur a déjà son équipe à lui (titre, grade...), on y met juste "pseudo caché" le temps qu'il est dedans.
 * Sinon on le met dans une petite équipe à lui qui garde le même préfixe / la même couleur, puis on le remet
 * dans son équipe d'avant en sortant. Fait sur chaque tableau de scores utilisé par les joueurs en ligne.
 */
final class NameTags {

    private record Saved(String team, boolean own, Team.OptionStatus option) {
    }

    /** tableau -> (pseudo -> ce qu'il y avait avant) */
    private final Map<Scoreboard, Map<String, Saved>> saved = new HashMap<>();

    private static String hideTeam(String name) {
        return "salle_" + Integer.toHexString(name.toLowerCase().hashCode());
    }

    private Set<Scoreboard> boards() {
        Set<Scoreboard> out = new HashSet<>();
        out.add(Bukkit.getScoreboardManager().getMainScoreboard());
        for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getScoreboard());
        return out;
    }

    /** inside = pseudos des joueurs actuellement dans une salle. */
    void update(Set<String> inside) {
        for (Scoreboard sb : boards()) {
            Map<String, Saved> m = saved.computeIfAbsent(sb, k -> new HashMap<>());
            for (String name : inside) if (!m.containsKey(name)) hide(sb, m, name);
            for (String name : new HashSet<>(m.keySet())) if (!inside.contains(name)) show(sb, m, name);
        }
        saved.values().removeIf(Map::isEmpty);
    }

    private void hide(Scoreboard sb, Map<String, Saved> m, String name) {
        Team cur = sb.getEntryTeam(name);
        if (cur != null && cur.getEntries().size() == 1) {
            m.put(name, new Saved(cur.getName(), true, cur.getOption(Team.Option.NAME_TAG_VISIBILITY)));
            cur.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            return;
        }
        String tn = hideTeam(name);
        Team t = sb.getTeam(tn);
        if (t == null) t = sb.registerNewTeam(tn);
        if (cur != null) {
            t.prefix(cur.prefix());
            t.suffix(cur.suffix());
            try {
                t.color(cur.color());
            } catch (RuntimeException ignored) {
            }
        }
        t.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        t.addEntry(name);
        m.put(name, new Saved(cur == null ? null : cur.getName(), false, null));
    }

    private void show(Scoreboard sb, Map<String, Saved> m, String name) {
        Saved s = m.remove(name);
        if (s == null) return;
        if (s.own()) {
            Team t = sb.getTeam(s.team());
            if (t != null) t.setOption(Team.Option.NAME_TAG_VISIBILITY, s.option() == null ? Team.OptionStatus.ALWAYS : s.option());
            return;
        }
        Team t = sb.getTeam(hideTeam(name));
        if (t != null) t.unregister();
        if (s.team() != null) {
            Team back = sb.getTeam(s.team());
            if (back != null) back.addEntry(name);
        }
    }

    void restoreAll() {
        for (var e : saved.entrySet()) for (String name : new HashSet<>(e.getValue().keySet())) show(e.getKey(), e.getValue(), name);
        saved.clear();
    }
}
