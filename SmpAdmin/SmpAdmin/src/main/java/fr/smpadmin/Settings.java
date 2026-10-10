package fr.smpadmin;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** Lecture de config.yml. */
public final class Settings {

    /** Raison proposée dans le menu. duration : -1 = pas de durée par défaut. */
    public record Reason(String name, long duration, Material icon) {
    }

    public final ZoneId zone;
    public final boolean broadcastBan, broadcastMute, broadcastKick, broadcastWarn;
    public final boolean fakeMessages, nightVision, vanishGod, noPickup;
    public final boolean banOnFreezeQuit;
    public final long freezeBanDuration;
    public final String freezeBanReason;
    public final Set<String> freezeAllowed = new HashSet<>();
    public final long muteDefault, banDefault;
    public final Set<String> muteBlocked = new HashSet<>();
    public final int specInterval;
    public final boolean specAutoVanish;
    public final String staffChatShortcut;
    public final List<Long> durations = new ArrayList<>();
    public final Map<Punishment.Type, List<Reason>> reasons = new EnumMap<>(Punishment.Type.class);

    Settings(FileConfiguration c, Logger log) {
        ZoneId z;
        try {
            z = ZoneId.of(c.getString("timezone", "Europe/Paris"));
        } catch (Exception e) {
            z = ZoneId.systemDefault();
        }
        zone = z;
        broadcastBan = c.getBoolean("broadcast.ban", true);
        broadcastMute = c.getBoolean("broadcast.mute", false);
        broadcastKick = c.getBoolean("broadcast.kick", true);
        broadcastWarn = c.getBoolean("broadcast.warn", false);

        fakeMessages = c.getBoolean("vanish.fake-messages", true);
        nightVision = c.getBoolean("vanish.night-vision", true);
        vanishGod = c.getBoolean("vanish.god", true);
        noPickup = c.getBoolean("vanish.no-pickup", true);

        banOnFreezeQuit = c.getBoolean("freeze.ban-on-quit", true);
        long fd = Durations.parse(c.getString("freeze.ban-duration", "30d"));
        freezeBanDuration = fd == Durations.INVALID ? 30L * 86_400_000L : fd;
        freezeBanReason = c.getString("freeze.ban-reason", "Déconnexion pendant une vérification");
        for (String s : c.getStringList("freeze.allowed-commands")) freezeAllowed.add(s.toLowerCase(Locale.ROOT));

        long md = Durations.parse(c.getString("mute.default-duration", "1h"));
        muteDefault = md == Durations.INVALID ? 3_600_000L : md;
        long bd = Durations.parse(c.getString("ban.default-duration", "perm"));
        banDefault = bd == Durations.INVALID ? Durations.PERMANENT : bd;
        for (String s : c.getStringList("mute.blocked-commands")) muteBlocked.add(s.toLowerCase(Locale.ROOT));

        specInterval = Math.max(3, c.getInt("spec.interval-seconds", 12));
        specAutoVanish = c.getBoolean("spec.auto-vanish", true);
        staffChatShortcut = c.getString("staffchat.shortcut", "#");

        for (String s : c.getStringList("durations")) {
            long d = Durations.parse(s);
            if (d == Durations.INVALID) log.warning("Durée invalide dans durations : " + s);
            else if (durations.size() < 9) durations.add(d);
        }
        if (durations.isEmpty()) durations.addAll(List.of(3_600_000L, 86_400_000L, 7 * 86_400_000L, 0L));

        for (Punishment.Type type : Punishment.Type.values()) {
            List<Reason> list = new ArrayList<>();
            for (Map<?, ?> m : c.getMapList("reasons." + type.name().toLowerCase(Locale.ROOT))) {
                Object name = m.get("name");
                if (name == null) continue;
                long d = -1;
                if (m.get("duration") != null) {
                    d = Durations.parse(String.valueOf(m.get("duration")));
                    if (d == Durations.INVALID) d = -1;
                }
                Material icon = m.get("icon") == null ? null : Material.matchMaterial(String.valueOf(m.get("icon")));
                if (icon == null || !icon.isItem()) icon = Material.PAPER;
                if (list.size() < 9) list.add(new Reason(String.valueOf(name), d, icon));
            }
            reasons.put(type, list);
        }
    }

    public boolean broadcast(Punishment.Type t) {
        return switch (t) {
            case BAN -> broadcastBan;
            case MUTE -> broadcastMute;
            case KICK -> broadcastKick;
            case WARN -> broadcastWarn;
        };
    }
}
