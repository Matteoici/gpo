package fr.smpguilds;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static fr.smpguilds.Messages.guild;
import static fr.smpguilds.Messages.money;
import static fr.smpguilds.Messages.ph;

/**
 * Guerres entre guildes : déclaration (payante), préparation, combat chronométré avec score,
 * boss bar pour les deux camps, victoire au score / au nombre de kills / par abandon, butin.
 */
public final class WarManager {

    public final class War {
        public final Guild attacker;
        public final Guild defender;
        public int scoreA;
        public int scoreD;
        public boolean active;
        public long startsAt;
        public long endsAt;
        final BossBar bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);

        War(Guild a, Guild d) {
            attacker = a;
            defender = d;
        }

        public Guild enemyOf(Guild g) {
            return g == attacker ? defender : attacker;
        }

        public int scoreOf(Guild g) {
            return g == attacker ? scoreA : scoreD;
        }
    }

    private final SmpGuilds plugin;
    private final List<War> wars = new ArrayList<>();
    private final Map<String, Long> cooldowns = new HashMap<>();

    WarManager(SmpGuilds plugin) {
        this.plugin = plugin;
    }

    private Messages m() {
        return plugin.messages();
    }

    public War warOf(Guild g) {
        if (g == null) return null;
        for (War w : wars) if (w.attacker == g || w.defender == g) return w;
        return null;
    }

    /** Vrai si les deux guildes sont en guerre active (combats autorisés). */
    public boolean atWar(Guild a, Guild b) {
        if (a == null || b == null) return false;
        War w = warOf(a);
        return w != null && w.active && w.enemyOf(a) == b;
    }

    public List<War> all() {
        return wars;
    }

    private static String pairKey(Guild a, Guild b) {
        return a.id.compareTo(b.id) < 0 ? a.id + ":" + b.id : b.id + ":" + a.id;
    }

    // ============================================================ déclaration

    public void declare(Player p, String targetName) {
        Guild g = plugin.actions().require(p, GuildPerm.WAR);
        if (g == null) return;
        Guild t = plugin.guilds().find(targetName);
        if (t == null) {
            m().send(p, "error.guild-not-found");
            return;
        }
        declare(p, g, t);
    }

    public void declare(Player p, Guild g, Guild t) {
        if (t == g) {
            m().send(p, "error.self");
            return;
        }
        if (g.allies.contains(t.id)) {
            m().send(p, "error.war-ally");
            return;
        }
        if (warOf(g) != null) {
            m().send(p, "error.already-at-war");
            return;
        }
        if (warOf(t) != null) {
            m().send(p, "error.target-at-war", guild(t));
            return;
        }
        int minOnline = plugin.settings().i("war.min-online-defenders", 1);
        if (t.online().size() < minOnline) {
            m().send(p, "error.war-not-enough-online", ph("min", minOnline));
            return;
        }
        long cd = plugin.settings().l("war.cooldown-hours", 12) * 3_600_000L;
        Long last = cooldowns.get(pairKey(g, t));
        if (last != null && System.currentTimeMillis() - last < cd) {
            long left = (cd - (System.currentTimeMillis() - last)) / 60_000 + 1;
            m().send(p, "error.war-cooldown", ph("minutes", left));
            return;
        }
        double cost = plugin.settings().d("war.declare-cost", 0);
        if (g.bank < cost) {
            m().send(p, "error.bank-insufficient", ph("amount", money(cost)), ph("bank", money(g.bank)));
            return;
        }
        g.bank -= cost;
        plugin.guilds().markDirty(g);

        War w = new War(g, t);
        int prep = plugin.settings().i("war.preparation-seconds", 120);
        w.startsAt = System.currentTimeMillis() + prep * 1000L;
        wars.add(w);
        plugin.guilds().log(g, p.getName() + " a déclaré la guerre à " + t.name);
        plugin.guilds().log(t, g.name + " vous a déclaré la guerre");

        Bukkit.broadcast(m().prefix().append(m().get("war.declared-broadcast",
                Messages.comp("attacker", g.nameComponent()), Messages.comp("defender", t.nameComponent()),
                ph("seconds", prep))));
        for (Player o : t.online()) {
            o.showTitle(Title.title(m().get("war.declared-title"), m().get("war.declared-subtitle", guild(g))));
            o.playSound(o.getLocation(), Sound.EVENT_RAID_HORN, 1f, 1f);
        }
        for (Player o : g.online()) o.playSound(o.getLocation(), Sound.EVENT_RAID_HORN, 1f, 1f);
        showBar(w);
        updateBar(w);
    }

    public void surrender(Player p) {
        Guild g = plugin.actions().require(p, GuildPerm.WAR);
        if (g == null) return;
        War w = warOf(g);
        if (w == null) {
            m().send(p, "error.not-at-war");
            return;
        }
        end(w, w.enemyOf(g), "surrender");
    }

    // ============================================================ combat

    /** Appelé quand un joueur de killer tue un joueur de victim. */
    public void onKill(Guild killer, Guild victim) {
        if (!atWar(killer, victim)) return;
        War w = warOf(killer);
        if (killer == w.attacker) w.scoreA++;
        else w.scoreD++;
        plugin.guilds().addXp(killer, plugin.settings().l("xp.war-kill", 25));
        updateBar(w);
        int target = plugin.settings().i("war.kills-to-win", 15);
        if (target > 0 && w.scoreOf(killer) >= target) end(w, killer, "kills");
    }

    /** Toutes les secondes. */
    public void tick() {
        long now = System.currentTimeMillis();
        for (War w : new ArrayList<>(wars)) {
            if (plugin.guilds().get(w.attacker.id) == null || plugin.guilds().get(w.defender.id) == null) {
                hideBar(w);
                wars.remove(w);
                continue;
            }
            if (!w.active && now >= w.startsAt) {
                w.active = true;
                w.endsAt = now + plugin.settings().l("war.duration-minutes", 30) * 60_000L;
                for (Guild g : new Guild[]{w.attacker, w.defender}) {
                    for (Player p : g.online()) {
                        p.showTitle(Title.title(m().get("war.started-title"), m().get("war.started-subtitle", guild(w.enemyOf(g)))));
                        p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1f);
                    }
                }
            } else if (w.active && now >= w.endsAt) {
                Guild winner = w.scoreA > w.scoreD ? w.attacker : w.scoreD > w.scoreA ? w.defender : null;
                end(w, winner, "time");
                continue;
            }
            updateBar(w);
        }
    }

    private void updateBar(War w) {
        long now = System.currentTimeMillis();
        if (!w.active) {
            long left = Math.max(0, (w.startsAt - now) / 1000);
            int prep = Math.max(1, plugin.settings().i("war.preparation-seconds", 120));
            w.bar.color(BossBar.Color.YELLOW);
            w.bar.progress(Math.max(0f, Math.min(1f, left / (float) prep)));
            w.bar.name(m().get("war.bar-preparation", Messages.comp("attacker", w.attacker.nameComponent()),
                    Messages.comp("defender", w.defender.nameComponent()), ph("time", Messages.time(left))));
        } else {
            long left = Math.max(0, (w.endsAt - now) / 1000);
            long total = Math.max(1, plugin.settings().l("war.duration-minutes", 30) * 60);
            w.bar.color(BossBar.Color.RED);
            w.bar.progress(Math.max(0f, Math.min(1f, left / (float) total)));
            w.bar.name(m().get("war.bar-active", Messages.comp("attacker", w.attacker.nameComponent()),
                    Messages.comp("defender", w.defender.nameComponent()),
                    ph("score_a", w.scoreA), ph("score_d", w.scoreD), ph("time", Messages.time(left))));
        }
    }

    private void showBar(War w) {
        for (Guild g : new Guild[]{w.attacker, w.defender}) for (Player p : g.online()) p.showBossBar(w.bar);
    }

    private void hideBar(War w) {
        for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(w.bar);
    }

    /** Un membre se connecte : on lui remet la boss bar de la guerre en cours. */
    public void onJoin(Player p) {
        War w = warOf(plugin.guilds().of(p));
        if (w != null) p.showBossBar(w.bar);
    }

    // ============================================================ fin

    private void end(War w, Guild winner, String reason) {
        wars.remove(w);
        hideBar(w);
        cooldowns.put(pairKey(w.attacker, w.defender), System.currentTimeMillis());
        GuildManager gm = plugin.guilds();
        if (winner == null) {
            Bukkit.broadcast(m().prefix().append(m().get("war.draw-broadcast",
                    Messages.comp("attacker", w.attacker.nameComponent()), Messages.comp("defender", w.defender.nameComponent()),
                    ph("score_a", w.scoreA), ph("score_d", w.scoreD))));
            gm.log(w.attacker, "Guerre contre " + w.defender.name + " : égalité " + w.scoreA + "-" + w.scoreD);
            gm.log(w.defender, "Guerre contre " + w.attacker.name + " : égalité " + w.scoreD + "-" + w.scoreA);
            return;
        }
        Guild loser = w.enemyOf(winner);
        double loot = Math.floor(loser.bank * plugin.settings().d("war.loot-percent", 10) / 100.0);
        loser.bank -= loot;
        winner.bank += loot;
        winner.warsWon++;
        loser.warsLost++;
        gm.addXp(winner, plugin.settings().l("xp.war-win", 500));
        gm.markDirty(winner);
        gm.markDirty(loser);
        gm.log(winner, "Victoire contre " + loser.name + " (" + w.scoreOf(winner) + "-" + w.scoreOf(loser) + "), butin " + money(loot));
        gm.log(loser, "Défaite contre " + winner.name + " (" + w.scoreOf(loser) + "-" + w.scoreOf(winner) + "), perte " + money(loot));
        Bukkit.broadcast(m().prefix().append(m().get("war.won-broadcast", Messages.comp("winner", winner.nameComponent()),
                Messages.comp("loser", loser.nameComponent()), ph("score_w", w.scoreOf(winner)), ph("score_l", w.scoreOf(loser)),
                Messages.comp("reason", m().get("war.reason-" + reason)), ph("loot", money(loot)))));
        for (Player p : winner.online()) {
            p.showTitle(Title.title(m().get("war.victory-title"), m().get("war.victory-subtitle", ph("loot", money(loot)))));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        for (Player p : loser.online()) {
            p.showTitle(Title.title(m().get("war.defeat-title"), m().get("war.defeat-subtitle", ph("loot", money(loot)))));
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_DEATH, 0.4f, 1f);
        }
    }

    /** Arrêt du serveur : les guerres en cours s'arrêtent sans vainqueur. */
    public void shutdown() {
        for (War w : wars) hideBar(w);
        wars.clear();
    }
}
