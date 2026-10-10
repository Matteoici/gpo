package fr.smpguilds;

import java.util.ArrayList;
import java.util.List;

import static fr.smpguilds.Messages.money;
import static fr.smpguilds.Messages.ph;

/**
 * Entretien des terrains (optionnel, config claims.upkeep) : une fois par jour, la trésorerie paie
 * un montant par chunk. Si elle ne peut pas tout payer, la guilde perd les chunks non payés
 * (les derniers claim en premier).
 */
public final class Upkeep {

    private static final long DAY = 24 * 3_600_000L;
    private final SmpGuilds plugin;

    Upkeep(SmpGuilds plugin) {
        this.plugin = plugin;
    }

    public void check() {
        Settings s = plugin.settings();
        if (!s.b("claims.upkeep.enabled", false)) return;
        double perClaim = s.d("claims.upkeep.per-claim-per-day", 0);
        long now = System.currentTimeMillis();
        GuildManager gm = plugin.guilds();
        for (Guild g : new ArrayList<>(gm.all())) {
            if (now - g.lastUpkeep < DAY) continue;
            g.lastUpkeep = now;
            gm.markDirty(g);
            if (perClaim <= 0 || g.claims.isEmpty()) continue;
            double cost = perClaim * g.claims.size();
            if (g.bank >= cost) {
                g.bank -= cost;
                gm.log(g, "Entretien payé : " + money(cost) + " (" + g.claims.size() + " chunks)");
                g.broadcast(plugin.messages().prefix().append(plugin.messages().get("upkeep.paid",
                        ph("amount", money(cost)), ph("count", g.claims.size()), ph("bank", money(g.bank)))));
                continue;
            }
            int affordable = (int) Math.floor(g.bank / perClaim);
            double paid = affordable * perClaim;
            g.bank -= paid;
            List<ChunkPos> list = new ArrayList<>(g.claims);
            int lost = 0;
            for (int i = list.size() - 1; i >= affordable; i--) {
                gm.unclaim(g, list.get(i));
                lost++;
            }
            gm.log(g, "Entretien impayé : " + lost + " chunk(s) perdu(s)");
            g.broadcast(plugin.messages().prefix().append(plugin.messages().get("upkeep.lost",
                    ph("count", lost), ph("amount", money(cost)))));
        }
    }
}
