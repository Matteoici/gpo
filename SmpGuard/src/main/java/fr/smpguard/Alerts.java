package fr.smpguard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static fr.smpguard.Texts.pc;
import static fr.smpguard.Texts.ph;

/** Score (VL), alertes au staff, journal et lien avec SmpAdmin. */
public final class Alerts {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final SmpGuard plugin;
    /** Membres du staff qui ont coupé les alertes. */
    private final Set<UUID> muted = new HashSet<>();

    Alerts(SmpGuard plugin) {
        this.plugin = plugin;
    }

    public boolean toggle(Player p) {
        if (!muted.remove(p.getUniqueId())) {
            muted.add(p.getUniqueId());
            return false;
        }
        return true;
    }

    /** Confiance affichée (%) à partir du score. */
    public static int confidence(double vl, double alertVl) {
        double c = 1 - Math.exp(-vl / Math.max(0.5, alertVl));
        return (int) Math.round(Math.min(0.99, c) * 100);
    }

    /**
     * Ajoute des points de suspicion. amount est déjà multiplié par la confiance (lag).
     * detail : explication courte affichée au staff.
     */
    public void flag(Player p, Profile prof, Check check, double amount, String detail) {
        Settings.Limits lim = plugin.settings().limits(check);
        if (lim == null || !lim.enabled() || amount <= 0) return;
        int i = check.ordinal();
        long now = System.currentTimeMillis();
        // un incident = un moment distinct (plusieurs signaux dans la même seconde comptent pour un)
        var ev = prof.events[i];
        if (ev.isEmpty() || now - ev.peekLast() > 1000) ev.addLast(now);
        while (!ev.isEmpty() && now - ev.peekFirst() > lim.windowMs()) ev.pollFirst();
        while (ev.size() > 64) ev.pollFirst();
        prof.vl[i] += amount;
        prof.peak[i] = Math.max(prof.peak[i], prof.vl[i]);
        double vl = prof.vl[i];
        // alerte seulement si c'est sérieux : score élevé ET répété plusieurs fois récemment
        boolean serious = vl >= lim.alertVl() && ev.size() >= lim.minEvents();
        if (serious && now - prof.lastAlert[i] >= plugin.settings().alertCooldownMs) {
            prof.lastAlert[i] = now;
            prof.alerts++;
            alert(p, prof, check, vl, lim, detail);
        }
        if (serious && vl >= lim.susVl() && !prof.susSent && plugin.settings().autoSus) {
            prof.susSent = true;
            Hooks.markSuspect(p.getName(), "[Anticheat] " + plugin.texts().raw("checks." + check.key) + " (VL "
                    + String.format(Locale.US, "%.1f", vl) + ")");
        }
    }

    private void alert(Player p, Profile prof, Check check, double vl, Settings.Limits lim, String detail) {
        Texts t = plugin.texts();
        int conf = confidence(vl, lim.alertVl());
        String vlText = String.format(Locale.US, "%.1f", vl);
        String lag = Lag.describe(prof) + " | TPS " + String.format(Locale.US, "%.1f", Lag.tps());
        Component spec = t.get("alert.spec").clickEvent(ClickEvent.runCommand("/spec " + p.getName()))
                .hoverEvent(HoverEvent.showText(t.get("alert.spec-hover")));
        Component sheet = t.get("alert.sheet").clickEvent(ClickEvent.runCommand("/admin " + p.getName()))
                .hoverEvent(HoverEvent.showText(t.get("alert.sheet-hover")));
        Component report = t.get("alert.report").clickEvent(ClickEvent.runCommand("/anticheat check " + p.getName()))
                .hoverEvent(HoverEvent.showText(t.get("alert.report-hover")));
        TagResolver r = TagResolver.resolver(ph("player", p.getName()), Texts.pp("check", t.raw("checks." + check.key)),
                ph("detail", detail), ph("confidence", conf), ph("vl", vlText), ph("lag", lag),
                pc("spec", spec), pc("sheet", sheet), pc("report", report));
        Component msg = t.prefixed("alert.format", r);
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.hasPermission("smpguard.alerts") && !muted.contains(o.getUniqueId())) o.sendMessage(msg);
        }
        Bukkit.getConsoleSender().sendMessage(msg);
        String plain = LocalTime.now().format(TIME) + " " + p.getName() + " | " + check.key + " | VL " + vlText
                + " | " + conf + "% | " + detail + " | " + lag;
        prof.note(plain);
        log(plain);
    }

    /** Message au staff (résultat du test appât, etc.). */
    public void staff(Component msg) {
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.hasPermission("smpguard.alerts") && !muted.contains(o.getUniqueId())) o.sendMessage(msg);
        }
        Bukkit.getConsoleSender().sendMessage(msg);
    }

    public void log(String line) {
        if (!plugin.settings().logToFile) return;
        String date = LocalDate.now().toString();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            synchronized (this) {
                File dir = new File(plugin.getDataFolder(), "logs");
                if (!dir.exists() && !dir.mkdirs()) return;
                try (PrintWriter w = new PrintWriter(new FileWriter(new File(dir, date + ".log"), true))) {
                    w.println(line);
                } catch (IOException ignored) {
                }
            }
        });
    }

    /** Baisse des scores avec le temps (appelé toutes les secondes). */
    public void decay(Profile p) {
        for (Check c : Check.values()) {
            Settings.Limits lim = plugin.settings().limits(c);
            if (lim == null) continue;
            p.vl[c.ordinal()] = Math.max(0, p.vl[c.ordinal()] - lim.decayPerMinute() / 60.0);
        }
    }
}
