package fr.smpguilds;

import java.util.Locale;

/** Rangs d'une guilde, du plus bas au plus haut. */
public enum Role {
    RECRUIT, MEMBER, OFFICER, LEADER;

    public boolean higherThan(Role o) {
        return ordinal() > o.ordinal();
    }

    public Role next() {
        return this == LEADER ? LEADER : values()[ordinal() + 1];
    }

    public Role previous() {
        return this == RECRUIT ? RECRUIT : values()[ordinal() - 1];
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
