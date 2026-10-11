package fr.smpmarket;

import java.util.UUID;

/**
 * Enchère sur un stand.
 * renewal = enchère pour la location suivante d'un stand déjà loué.
 * endsAt  = 0 tant que personne n'a fait d'offre (stand libre).
 */
public final class Auction {

    public boolean renewal;
    public long endsAt;
    public UUID bidder;
    public String bidderName;
    public double bid;
    public int bids;

    public boolean hasBid() {
        return bidder != null;
    }
}
