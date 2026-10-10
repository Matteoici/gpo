package fr.smpadmin;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Durées lisibles : "30m", "2h", "7d" (ou "7j"), "1mo", "1d12h", "perm". */
public final class Durations {

    /** Valeur renvoyée pour une durée définitive. */
    public static final long PERMANENT = 0;
    /** Valeur renvoyée pour un texte qui n'est pas une durée. */
    public static final long INVALID = -1;

    private static final Set<String> PERM = Set.of("perm", "permanent", "def", "definitif", "définitif", "forever", "p", "-1");
    private static final Pattern TOKEN = Pattern.compile("(\\d+)(mo|min|y|a|w|sem|d|j|h|m|s)");

    private static final long S = 1000, M = 60 * S, H = 60 * M, D = 24 * H;

    private Durations() {
    }

    /** Durée en millisecondes, PERMANENT (0) ou INVALID (-1). */
    public static long parse(String text) {
        if (text == null) return INVALID;
        String s = text.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return INVALID;
        if (PERM.contains(s)) return PERMANENT;
        Matcher m = TOKEN.matcher(s);
        long total = 0;
        int pos = 0;
        while (m.find()) {
            if (m.start() != pos) return INVALID;
            pos = m.end();
            long n;
            try {
                n = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                return INVALID;
            }
            total += n * switch (m.group(2)) {
                case "y", "a" -> 365 * D;
                case "mo" -> 30 * D;
                case "w", "sem" -> 7 * D;
                case "d", "j" -> D;
                case "h" -> H;
                case "m", "min" -> M;
                default -> S;
            };
        }
        if (pos != s.length() || total <= 0) return INVALID;
        return total;
    }

    public static boolean isDuration(String text) {
        return parse(text) != INVALID;
    }

    /** "6j 23h", "3h 20min", "45s". Les deux plus grandes unités seulement. */
    public static String format(long ms) {
        if (ms <= 0) return "0s";
        long sec = (ms + 999) / 1000;
        long d = sec / 86400, h = (sec % 86400) / 3600, min = (sec % 3600) / 60, s = sec % 60;
        if (d > 0) return h > 0 ? d + "j " + h + "h" : d + "j";
        if (h > 0) return min > 0 ? h + "h " + min + "min" : h + "h";
        if (min > 0) return s > 0 && min < 10 ? min + "min " + s + "s" : min + "min";
        return s + "s";
    }
}
