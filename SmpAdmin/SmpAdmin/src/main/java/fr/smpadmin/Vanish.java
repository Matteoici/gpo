package fr.smpadmin;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import static fr.smpadmin.Texts.ph;

/** Invisibilité du staff : caché des joueurs (et de la liste TAB), visible par le staff. */
public final class Vanish {

    public static final String SEE = "smpadmin.vanish.see";
    private final SmpAdmin plugin;

    Vanish(SmpAdmin plugin) {
        this.plugin = plugin;
    }

    public boolean is(Player p) {
        return plugin.state().vanished.contains(p.getUniqueId());
    }

    public void toggle(Player p) {
        set(p, !is(p), true);
    }

    /** announce : faux message de connexion / déconnexion pour les joueurs. */
    public void set(Player p, boolean on, boolean announce) {
        Settings s = plugin.settings();
        Texts t = plugin.texts();
        if (on) plugin.state().vanished.add(p.getUniqueId());
        else plugin.state().vanished.remove(p.getUniqueId());
        plugin.state().save();
        apply(p);
        if (on) {
            t.send(p, "vanish.on");
            if (s.nightVision) p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        } else {
            t.send(p, "vanish.off");
            if (s.nightVision) p.removePotionEffect(PotionEffectType.NIGHT_VISION);
            p.sendActionBar(Component.empty());
        }
        if (announce && s.fakeMessages) {
            Component c = t.get(on ? "vanish.fake-quit" : "vanish.fake-join", ph("player", p.getName()));
            for (Player o : Bukkit.getOnlinePlayers()) if (!o.hasPermission(SEE)) o.sendMessage(c);
        }
    }

    /** Applique la visibilité de ce joueur pour tout le monde. */
    public void apply(Player p) {
        boolean on = is(p);
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o == p) continue;
            if (on && !o.hasPermission(SEE)) o.hidePlayer(plugin, p);
            else o.showPlayer(plugin, p);
        }
        p.setSleepingIgnored(on);
        p.setAffectsSpawning(!on);
        p.setCollidable(!on);
    }

    /** Un joueur arrive : on lui cache les membres du staff invisibles. */
    public void onJoin(Player joined) {
        boolean see = joined.hasPermission(SEE);
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o != joined && is(o) && !see) joined.hidePlayer(plugin, o);
        }
        if (is(joined)) apply(joined);
    }

    /** Barre d'action pour rappeler qu'on est invisible (toutes les 2 secondes). */
    public void tick() {
        Component bar = null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!is(p)) continue;
            if (bar == null) bar = plugin.texts().get("vanish.actionbar");
            if (!plugin.spectate().isWatching(p)) p.sendActionBar(bar);
        }
    }

    /** Remet tout le monde visible (arrêt du plugin). */
    public void showAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (Player o : Bukkit.getOnlinePlayers()) if (o != p) o.showPlayer(plugin, p);
        }
    }
}
