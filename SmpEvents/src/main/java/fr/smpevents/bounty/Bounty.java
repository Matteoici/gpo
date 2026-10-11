package fr.smpevents.bounty;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Prime sur la tête d'un joueur : plusieurs joueurs peuvent y contribuer. */
public final class Bounty {

    public final UUID target;
    public String targetName;
    /** contributeur -> montant mis (après frais) */
    public final Map<UUID, Double> contributions = new LinkedHashMap<>();
    public final Map<UUID, String> names = new LinkedHashMap<>();
    public long created;
    public long updated;
    public int blockedTries;

    public Bounty(UUID target, String targetName) {
        this.target = target;
        this.targetName = targetName;
        this.created = System.currentTimeMillis();
        this.updated = created;
    }

    public double total() {
        double t = 0;
        for (double v : contributions.values()) t += v;
        return t;
    }
}
