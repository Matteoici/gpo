package fr.smpguilds;

import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.HashMap;
import java.util.Map;

/** Tir ami, kills, morts, XP de guilde et score des guerres. */
public final class CombatListener implements Listener {

    private final SmpGuilds plugin;
    /** "tueur:victime" -> moment du dernier kill qui a rapporté de l'XP */
    private final Map<String, Long> recentKills = new HashMap<>();

    CombatListener(SmpGuilds plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = ProtectionListener.attacker(e.getDamager());
        if (attacker == null || attacker.equals(victim)) return;
        Guild a = plugin.guilds().of(attacker);
        Guild v = plugin.guilds().of(victim);
        if (a == null || v == null) return;
        if (a == v && !a.friendlyFire) {
            e.setCancelled(true);
            attacker.sendActionBar(plugin.messages().get("protection.friendly-fire"));
        } else if (a != v && plugin.guilds().areAllies(a, v) && !(a.allyFire && v.allyFire)) {
            e.setCancelled(true);
            attacker.sendActionBar(plugin.messages().get("protection.ally-fire"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        GuildManager gm = plugin.guilds();
        Guild v = gm.of(victim);
        if (v != null) {
            v.deaths++;
            v.stats(victim.getUniqueId()).deaths++;
            gm.markDirty(v);
        }
        if (killer == null || killer.equals(victim)) return;
        Guild k = gm.of(killer);
        if (k == null) return;
        k.kills++;
        k.stats(killer.getUniqueId()).kills++;
        gm.markDirty(k);
        if (v == k || gm.areAllies(k, v)) return;

        plugin.wars().onKill(k, v);

        // anti-farm : pas d'XP si on retue la même personne trop vite
        String key = killer.getUniqueId() + ":" + victim.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = recentKills.get(key);
        long cd = plugin.settings().l("xp.kill-cooldown-minutes", 10) * 60_000L;
        if (last != null && now - last < cd) return;
        recentKills.put(key, now);
        if (recentKills.size() > 5000) recentKills.entrySet().removeIf(en -> now - en.getValue() > cd);
        gm.addXp(k, plugin.settings().l("xp.player-kill", 10));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMobDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof Monster)) return;
        Player killer = e.getEntity().getKiller();
        if (killer == null) return;
        Guild g = plugin.guilds().of(killer);
        if (g != null) plugin.guilds().addXp(g, plugin.settings().l("xp.mob-kill", 1));
    }
}
