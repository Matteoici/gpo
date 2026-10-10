package fr.smpguilds;

/** Statistiques d'un membre dans sa guilde. */
public final class MemberStats {
    public int kills;
    public int deaths;
    public double deposited;
    public long joined = System.currentTimeMillis();
}
