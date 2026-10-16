package fr.smpguilds;

import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static fr.smpguilds.Messages.guild;

/** Entrée/sortie de territoire, auto-claim, annulation du home, bonus de niveau dans ses terrains. */
public final class TerritoryListener implements Listener {

    private final SmpGuilds plugin;
    private final Map<UUID, UUID> lastTerritory = new HashMap<>();
    private final Set<UUID> autoClaim = new HashSet<>();

    TerritoryListener(SmpGuilds plugin) {
        this.plugin = plugin;
    }

    public boolean toggleAutoClaim(Player p) {
        if (!autoClaim.remove(p.getUniqueId())) {
            autoClaim.add(p.getUniqueId());
            return true;
        }
        return false;
    }

    /** À la connexion : on mémorise le territoire actuel sans afficher de titre. */
    public void remember(Player p) {
        Guild g = plugin.guilds().at(p.getLocation());
        lastTerritory.put(p.getUniqueId(), g == null ? null : g.id);
    }

    public void forget(UUID player) {
        lastTerritory.remove(player);
        autoClaim.remove(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom(), to = e.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) return;
        plugin.homes().onMove(e.getPlayer(), to);
        if ((from.getBlockX() >> 4) != (to.getBlockX() >> 4) || (from.getBlockZ() >> 4) != (to.getBlockZ() >> 4)
                || from.getWorld() != to.getWorld()) {
            changedChunk(e.getPlayer(), to);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getTo() != null) changedChunk(e.getPlayer(), e.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) plugin.homes().cancel(p, true);
    }

    private void changedChunk(Player p, Location to) {
        Guild owner = plugin.guilds().at(to);
        if (owner == null && autoClaim.contains(p.getUniqueId())) {
            if (!plugin.actions().claim(p, true)) {
                // limite atteinte, plus d'argent... : on coupe l'auto-claim
                autoClaim.remove(p.getUniqueId());
                plugin.messages().send(p, "claims.autoclaim-off");
            }
            owner = plugin.guilds().at(to);
        }
        // Les autres joueurs ne sont pas prévenus qu'ils entrent chez une guilde : sinon une base enterrée
        // se trouve en marchant au-dessus. Seuls ses membres (et alliés) voient leurs terrains.
        if (owner != null && !plugin.settings().b("claims.notify-outsiders", false)) {
            Guild mine = plugin.guilds().of(p);
            if (mine == null || (mine != owner && !plugin.guilds().areAllies(mine, owner))) owner = null;
        }
        UUID now = owner == null ? null : owner.id;
        boolean known = lastTerritory.containsKey(p.getUniqueId());
        UUID before = lastTerritory.get(p.getUniqueId());
        lastTerritory.put(p.getUniqueId(), now);
        if (known && Objects.equals(before, now)) return; // même territoire
        if (!known && now == null) return;                 // première position en zone sauvage : rien à dire
        notifyEnter(p, owner);
    }

    private void notifyEnter(Player p, Guild owner) {
        String mode = plugin.settings().s("claims.enter-notification", "title");
        if (mode.equalsIgnoreCase("none")) return;
        Messages m = plugin.messages();
        var title = owner == null ? m.get("territory.wilderness") : m.get("territory.enter", guild(owner));
        var sub = owner == null ? m.get("territory.wilderness-sub")
                : m.get("territory.enter-sub", Messages.ph("description", owner.description.isEmpty() ? "" : owner.description));
        if (mode.equalsIgnoreCase("actionbar")) {
            p.sendActionBar(title);
        } else {
            p.showTitle(Title.title(title, sub, Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1500), Duration.ofMillis(400))));
        }
    }

    /** Toutes les 3 secondes : bonus de niveau aux membres qui sont dans leurs propres terrains. */
    public void applyPerks() {
        for (Guild g : plugin.guilds().all()) {
            var effects = plugin.guilds().level(g).effects();
            if (effects.isEmpty()) continue;
            for (Player p : g.online()) {
                if (plugin.guilds().at(p.getLocation()) != g) continue;
                for (Level.Effect ef : effects) {
                    p.addPotionEffect(new PotionEffect(ef.type(), 100, ef.amplifier(), true, false, true));
                }
            }
        }
    }
}
