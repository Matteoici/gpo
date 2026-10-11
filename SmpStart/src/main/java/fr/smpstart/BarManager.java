package fr.smpstart;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Boss bar « Étape 3/17 » en haut de l'écran pendant le guide. */
public final class BarManager {

    private final SmpStart plugin;
    private final Map<UUID, BossBar> bars = new HashMap<>();

    BarManager(SmpStart plugin) {
        this.plugin = plugin;
    }

    public void update(Player p) {
        PlayerData d = plugin.data().get(p.getUniqueId());
        var steps = plugin.settings().steps();
        if (d == null || d.finished || !d.bar || d.step >= steps.size() || !p.isOnline()) {
            hide(p);
            return;
        }
        Step s = steps.get(d.step);
        float progress = Math.max(0f, Math.min(1f, d.progress / (float) s.amount()));
        var title = plugin.texts().get("bar.title", Texts.ph("number", d.step + 1), Texts.ph("total", steps.size()),
                Texts.comp("step", plugin.texts().parse(s.name())), Texts.ph("progress", d.progress), Texts.ph("amount", s.amount()));
        BossBar bar = bars.get(p.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(title, progress, BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_10);
            bars.put(p.getUniqueId(), bar);
            p.showBossBar(bar);
        } else {
            bar.name(title);
            bar.progress(progress);
        }
    }

    public void hide(Player p) {
        BossBar bar = bars.remove(p.getUniqueId());
        if (bar != null) p.hideBossBar(bar);
    }

    public void forget(UUID id) {
        bars.remove(id);
    }
}
