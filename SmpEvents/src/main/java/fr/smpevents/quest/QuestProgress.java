package fr.smpevents.quest;

/** Avancement d'un joueur sur une quête. */
public final class QuestProgress {

    public final String id;
    public long progress;
    public boolean done;

    public QuestProgress(String id) {
        this.id = id;
    }

    public String serialize() {
        return id + ";" + progress + ";" + done;
    }

    public static QuestProgress parse(String s) {
        String[] p = s.split(";");
        if (p.length < 3) return null;
        QuestProgress q = new QuestProgress(p[0]);
        try {
            q.progress = Long.parseLong(p[1]);
        } catch (NumberFormatException e) {
            q.progress = 0;
        }
        q.done = Boolean.parseBoolean(p[2]);
        return q;
    }
}
