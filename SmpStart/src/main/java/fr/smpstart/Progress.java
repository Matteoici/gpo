package fr.smpstart;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

import static fr.smpstart.Texts.comp;
import static fr.smpstart.Texts.ph;

/** Avancement dans les étapes : comptage, récompenses, passage à l'étape suivante, fin du guide. */
public final class Progress {

    private final SmpStart plugin;

    Progress(SmpStart plugin) {
        this.plugin = plugin;
    }

    /** Étape en cours du joueur, ou null s'il a terminé / n'a pas de données. */
    public Step current(Player p) {
        PlayerData d = plugin.data().get(p.getUniqueId());
        if (d == null || d.finished) return null;
        var steps = plugin.settings().steps();
        return d.step < steps.size() ? steps.get(d.step) : null;
    }

    /**
     * Un événement de jeu s'est produit. Seule l'étape en cours peut avancer :
     * le guide est volontairement dans l'ordre.
     */
    public void add(Player p, StepType type, String subject, boolean hostile, int amount) {
        PlayerData d = plugin.data().get(p.getUniqueId());
        if (d == null || d.finished) return;
        Step s = current(p);
        if (s == null || s.type() != type || !s.accepts(subject, hostile)) return;
        d.progress += amount;
        plugin.data().markDirty();
        if (d.progress >= s.amount()) {
            complete(p, d, s);
        } else {
            plugin.bars().update(p);
        }
    }

    private void complete(Player p, PlayerData d, Step s) {
        var steps = plugin.settings().steps();
        Texts t = plugin.texts();
        d.step++;
        d.progress = 0;
        d.reminderMin = 0;
        plugin.data().markDirty();

        boolean last = d.step >= steps.size();
        p.showTitle(Title.title(t.get("step.title"), t.get("step.subtitle", comp("step", t.parse(s.name())),
                ph("number", d.step), ph("total", steps.size()))));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
        t.send(p, "step.done", comp("step", t.parse(s.name())), ph("number", d.step), ph("total", steps.size()));
        giveReward(p, s.reward());

        if (last) {
            finish(p, d);
            return;
        }
        Step next = steps.get(d.step);
        sendNext(p, next, d.step + 1, steps.size());
        plugin.bars().update(p);
    }

    /** Annonce l'étape suivante avec sa consigne cliquable. */
    public void sendNext(Player p, Step next, int number, int total) {
        Texts t = plugin.texts();
        Component line = t.prefix().append(t.get("step.next", comp("step", t.parse(next.name())),
                ph("number", number), ph("total", total)));
        p.sendMessage(line);
        for (String d : next.description()) p.sendMessage(t.parse("  <gray>" + d));
        if (!next.hint().isBlank()) {
            String cmd = next.hint().startsWith("/") ? next.hint() : "/" + next.hint();
            p.sendMessage(t.get("step.hint", ph("command", cmd)).clickEvent(ClickEvent.suggestCommand(cmd)));
        }
    }

    /** Rappel de l'étape en cours (menu, rappel périodique, /guide). */
    public void sendCurrent(Player p) {
        PlayerData d = plugin.data().get(p.getUniqueId());
        Step s = current(p);
        if (d == null || s == null) return;
        sendNext(p, s, d.step + 1, plugin.settings().steps().size());
    }

    private void finish(Player p, PlayerData d) {
        Texts t = plugin.texts();
        d.finished = true;
        plugin.data().markDirty();
        plugin.bars().hide(p);
        p.showTitle(Title.title(t.get("finished.title"), t.get("finished.subtitle")));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        t.sendLines(p, "finished.message", ph("player", p.getName()));
        giveReward(p, plugin.settings().completion());
        if (plugin.settings().broadcastFinish()) {
            Bukkit.broadcast(t.prefix().append(t.get("finished.broadcast", ph("player", p.getName()))));
        }
    }

    /** Termine le guide d'un joueur d'un coup (commande admin). */
    public void forceFinish(Player p) {
        PlayerData d = plugin.data().get(p.getUniqueId());
        if (d == null) d = plugin.data().create(p.getUniqueId());
        if (d.finished) return;
        d.step = plugin.settings().steps().size();
        d.progress = 0;
        finish(p, d);
    }

    public void giveReward(Player p, Reward r) {
        if (r == null || r.isEmpty()) return;
        Texts t = plugin.texts();
        if (r.money() > 0 && plugin.economy() != null && plugin.economy().give(p, r.money())) {
            t.send(p, "reward.money", ph("amount", Kit.format(r.money())));
        }
        List<ItemStack> items = new ArrayList<>();
        for (String s : r.items()) {
            ItemStack it = plugin.kit().parse(s);
            if (it != null) items.add(it);
        }
        if (!items.isEmpty()) {
            plugin.kit().giveItems(p, items);
            for (ItemStack it : items) {
                t.send(p, "reward.item", ph("amount", it.getAmount()), ph("item", niceName(it)));
            }
        }
        for (String cmd : r.commands()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", p.getName()));
        }
    }

    private static String niceName(ItemStack it) {
        String n = it.getType().name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
