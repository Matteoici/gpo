package fr.smpfriends;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Données d'un joueur : amis, favoris, demandes reçues, réglages. */
public final class Profile {

    public final UUID id;
    public String name;
    public final Set<UUID> friends = new LinkedHashSet<>();
    public final Set<UUID> favorites = new LinkedHashSet<>();
    /** Demandes d'ami reçues (UUID de l'expéditeur). Gardées même si le joueur est hors ligne. */
    public final Set<UUID> requests = new LinkedHashSet<>();
    final Map<Setting, Boolean> settings = new EnumMap<>(Setting.class);

    Profile(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    public boolean get(Setting s, boolean def) {
        return settings.getOrDefault(s, def);
    }

    public void set(Setting s, boolean value) {
        settings.put(s, value);
    }
}
