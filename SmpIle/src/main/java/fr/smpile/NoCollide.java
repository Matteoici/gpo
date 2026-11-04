package fr.smpile;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Plus de collisions entre joueurs (personne ne peut en pousser un autre, au spawn comme ailleurs).
 * La poussée entre joueurs est calculée par le jeu de chaque joueur d'après les équipes du scoreboard qu'il voit :
 * on met « collisions : jamais » sur l'équipe de chaque joueur (grade, titre...) et on range ceux qui n'en ont pas
 * dans une équipe neutre. Rien d'autre n'est touché (préfixes, couleurs, pseudos).
 */
final class NoCollide {

    private static final String TEAM = "zz_nocollide";

    NoCollide(SmpIle plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!plugin.getConfig().getBoolean("player-collisions.disabled", true)) return;
            tick();
        }, 40L, 20L);
    }

    private static void tick() {
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        Set<Scoreboard> boards = new LinkedHashSet<>();
        boards.add(main);
        for (Player p : Bukkit.getOnlinePlayers()) boards.add(p.getScoreboard());
        for (Scoreboard sb : boards) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                Team t = sb.getEntryTeam(p.getName());
                if (t == null) {
                    // l'équipe neutre seulement sur le scoreboard principal : SmpBoard la recopie sur les siens
                    if (sb != main) continue;
                    t = sb.getTeam(TEAM);
                    if (t == null) t = sb.registerNewTeam(TEAM);
                    t.addEntry(p.getName());
                }
                if (t.getOption(Team.Option.COLLISION_RULE) != Team.OptionStatus.NEVER) {
                    t.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
                }
            }
        }
    }
}
