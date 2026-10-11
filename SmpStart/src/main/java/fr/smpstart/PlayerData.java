package fr.smpstart;

/** Avancement d'un joueur dans le guide. */
public final class PlayerData {
    public long first = System.currentTimeMillis();
    public int number;
    /** Secondes de protection débutant restantes (décomptées en jeu). */
    public int protectionSec;
    public int step;
    public int progress;
    public boolean finished;
    public boolean kitGiven;
    public boolean bar = true;
    public int reminderMin;
}
