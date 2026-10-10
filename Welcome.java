package fr.smpstart;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static fr.smpstart.Texts.ph;

/** Connexion d'un joueur : accueil du nouveau, retour d'un ancien, et récompense pour ceux qui lui disent bienvenue. */
public final class Welcome {

    private static final class Greet {
        final String name;
        final long time = System.currentTimeMillis();
        final Set<UUID> greeters = new HashSet<>();

        Greet(String name) {
            this.name = name;
        }
    }

    private final SmpStart plugin;
    private final Map<UUID, Greet> greets = new ConcurrentHashMap<>();

    Welcome(SmpStart plugin) {
        this.plugin = plugin;
    }

    public void onJoin(Player p) {
        DataStore store = plugin.data();
        PlayerData d = store.get(p.getUniqueId());
        if (d == null) {
            if (p.hasPlayedBefore() && !plugin.settings().includeExisting()) {
                // joueur déjà présent avant l'installation : pas de guide, pas de kit
                PlayerData old = store.create(p.getUniqueId());
                old.finished = true;
                old.kitGiven = true;
                old.protectionSec = 0;
                return;
            }
            PlayerData fresh = store.create(p.getUniqueId());
            fresh.protectionSec = plugin.settings().protectionMinutes() * 60;
            fresh.number = store.nextNumber();
            Bukkit.getScheduler().runTaskLater(plugin, () -> firstWelcome(p, fresh), 30L);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, () -> returning(p, d), 40L);
        }
    }

    private void firstWelcome(Player p, PlayerData d) {
        if (!p.isOnline()) return;
        Texts t = plugin.texts();
        p.showTitle(Title.title(t.get("first-join.title", ph("player", p.getName())), t.get("first-join.subtitle")));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1f);
        t.sendLines(p, "first-join.message", ph("player", p.getName()), ph("number", d.number),
                ph("minutes", plugin.settings().protectionMinutes()));

        if (plugin.settings().kitEnabled() && !d.kitGiven) {
            d.kitGiven = true;
            plugin.kit().give(p);
        }
        for (String cmd : plugin.settings().firstJoinCommands()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", p.getName()));
        }
        if (d.protectionSec > 0) {
            t.send(p, "protection.on", ph("minutes", d.protectionSec / 60));
        }
        if (plugin.settings().broadcastFirstJoin()) {
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (o.equals(p)) continue;
                o.sendMessage(t.prefix().append(t.get("first-join.broadcast", ph("player", p.getName()), ph("number", d.number))));
            }
        }
        if (plugin.settings().greetEnabled()) greets.put(p.getUniqueId(), new Greet(p.getName()));
        plugin.data().markDirty();
        plugin.bars().update(p);
        plugin.progress().sendCurrent(p);
    }

    private void returning(Player p, PlayerData d) {
        if (!p.isOnline()) return;
        plugin.bars().update(p);
        if (!d.finished && plugin.progress().current(p) != null) {
            plugin.texts().send(p, "returning", ph("number", d.step + 1), ph("total", plugin.settings().steps().size()));
        }
    }

    /** Appelé depuis le thread du chat. Retourne vrai si le message a rapporté une récompense. */
    public void onChat(Player speaker, String message) {
        if (!plugin.settings().greetEnabled() || greets.isEmpty()) return;
        String low = message.toLowerCase();
        boolean match = false;
        for (String w : plugin.settings().greetWords()) {
            if (!w.isBlank() && low.contains(w.toLowerCase())) {
                match = true;
                break;
            }
        }
        if (!match) return;
        long window = plugin.settings().greetWindowSeconds() * 1000L;
        long now = System.currentTimeMillis();
        greets.values().removeIf(g -> now - g.time > window);
        for (Map.Entry<UUID, Greet> e : greets.entrySet()) {
            Greet g = e.getValue();
            if (e.getKey().equals(speaker.getUniqueId())) continue;
            synchronized (g.greeters) {
                if (g.greeters.size() >= plugin.settings().greetMaxPlayers() || !g.greeters.add(speaker.getUniqueId())) continue;
            }
            double reward = plugin.settings().greetReward();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (reward > 0 && plugin.economy() != null && plugin.economy().give(speaker, reward)) {
                    plugin.texts().send(speaker, "greet.reward", ph("amount", Kit.format(reward)), ph("newbie", g.name));
                }
            });
            return; // une seule récompense par message
        }
    }

    public void forget(UUID id) {
        greets.remove(id);
    }
}
