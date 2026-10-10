package fr.smpadmin;

import java.util.UUID;

/** Une sanction enregistrée. duration : 0 = définitive, -1 = sans durée (kick, avertissement). */
public final class Punishment {

    public enum Type { BAN, MUTE, KICK, WARN }

    public final int id;
    public final Type type;
    public final UUID target;
    public final String targetName;
    public final String staff;
    public final String reason;
    public final long created;
    public final long duration;
    public boolean revoked;
    public String revokedBy;
    public long revokedAt;

    public Punishment(int id, Type type, UUID target, String targetName, String staff, String reason, long created, long duration) {
        this.id = id;
        this.type = type;
        this.target = target;
        this.targetName = targetName;
        this.staff = staff;
        this.reason = reason;
        this.created = created;
        this.duration = duration;
    }

    public boolean hasDuration() {
        return type == Type.BAN || type == Type.MUTE;
    }

    public boolean isPermanent() {
        return hasDuration() && duration == 0;
    }

    public long expires() {
        return duration > 0 ? created + duration : 0;
    }

    public boolean isActive(long now) {
        if (!hasDuration() || revoked) return false;
        return duration == 0 || now < created + duration;
    }

    /** Temps restant en ms (Long.MAX_VALUE si définitive). */
    public long remaining(long now) {
        if (duration == 0) return Long.MAX_VALUE;
        return Math.max(0, created + duration - now);
    }
}
