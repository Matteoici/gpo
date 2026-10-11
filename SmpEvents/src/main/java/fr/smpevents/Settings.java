package fr.smpevents;

import org.bukkit.configuration.file.FileConfiguration;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.logging.Logger;

/** Réglages généraux (config.yml). Chaque module lit aussi sa propre section. */
public final class Settings {

    public final String symbol;
    public final boolean compact;
    public final ZoneId zone;
    public final int resetHour;
    public final int memoryDays;

    Settings(FileConfiguration c, Logger log) {
        symbol = c.getString("currency-symbol", "$");
        compact = c.getBoolean("compact-money", false);
        ZoneId z;
        try {
            z = ZoneId.of(c.getString("timezone", "Europe/Paris"));
        } catch (Exception e) {
            log.warning("timezone invalide, Europe/Paris utilisé.");
            z = ZoneId.of("Europe/Paris");
        }
        zone = z;
        resetHour = Math.max(0, Math.min(23, c.getInt("reset-hour", 0)));
        memoryDays = Math.max(1, c.getInt("anti-abuse.memory-days", 14));
    }

    // ------------------------------------------------------------------ argent

    public String money(double v) {
        if (compact) return symbol + compact(v);
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(Locale.US);
        DecimalFormat f = new DecimalFormat("#,##0", sym);
        return symbol + f.format(Math.floor(v));
    }

    public static String compact(double v) {
        double a = Math.abs(v);
        String[] units = {"", "k", "M", "Md", "T"};
        int i = 0;
        while (a >= 1000 && i < units.length - 1) {
            a /= 1000;
            i++;
        }
        String n = a >= 100 || i == 0 ? String.format(Locale.US, "%.0f", a) : String.format(Locale.US, "%.2f", a)
                .replaceAll("0+$", "").replaceAll("\\.$", "");
        return (v < 0 ? "-" : "") + n + units[i];
    }

    public static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        long d = s / 86400, h = s % 86400 / 3600, m = s % 3600 / 60, sec = s % 60;
        if (d > 0) return d + "j " + h + "h";
        if (h > 0) return h + "h " + String.format("%02d", m) + "m";
        if (m > 0) return m + "m " + String.format("%02d", sec) + "s";
        return sec + "s";
    }

    // ------------------------------------------------------------------ jours / semaines

    /** "Aujourd'hui" pour les quêtes (la journée commence à reset-hour). */
    public LocalDate questDay() {
        return ZonedDateTime.now(zone).minusHours(resetHour).toLocalDate();
    }

    public String dayKey() {
        return questDay().toString();
    }

    public String weekKey() {
        LocalDate d = questDay();
        return d.get(IsoFields.WEEK_BASED_YEAR) + "-W" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    /** Millisecondes avant le prochain changement de jour des quêtes. */
    public long msToNextDay() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime next = questDay().plusDays(1).atStartOfDay(zone).plusHours(resetHour);
        return Math.max(0, next.toInstant().toEpochMilli() - now.toInstant().toEpochMilli());
    }

    public long msToNextWeek() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        LocalDate monday = questDay().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        ZonedDateTime next = monday.atStartOfDay(zone).plusHours(resetHour);
        return Math.max(0, next.toInstant().toEpochMilli() - now.toInstant().toEpochMilli());
    }
}
