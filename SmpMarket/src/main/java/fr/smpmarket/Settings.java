package fr.smpmarket;

import org.bukkit.configuration.file.FileConfiguration;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Lecture de config.yml. */
public final class Settings {

    private static final long H = 3_600_000L;

    public final String currency;
    public final long leaseMs, renewWindowMs;
    public final int maxPerPlayer;
    public final long auctionMs, antiSnipeMs;
    public final double minIncrement, minIncrementPercent;
    public final double taxPercent;
    public final int defaultShowcases, maxStock;
    public final double buyDistance, minPrice, maxPrice;
    public final boolean protectStalls, overrideProtections;
    public final double signHeight;
    public final float itemScale;
    public final boolean spin;

    Settings(FileConfiguration c) {
        currency = c.getString("currency-symbol", "$");
        leaseMs = Math.max(1, (long) (c.getDouble("lease.days", 7) * 24 * H));
        renewWindowMs = Math.max(H, (long) (c.getDouble("lease.renew-window-hours", 24) * H));
        maxPerPlayer = Math.max(1, c.getInt("lease.max-per-player", 1));
        auctionMs = Math.max(60_000L, (long) (c.getDouble("auction.duration-hours", 12) * H));
        minIncrement = Math.max(1, c.getDouble("auction.min-increment", 100));
        minIncrementPercent = Math.max(0, c.getDouble("auction.min-increment-percent", 5));
        antiSnipeMs = Math.max(0, (long) (c.getDouble("auction.anti-snipe-minutes", 5) * 60_000L));
        taxPercent = Math.max(0, Math.min(100, c.getDouble("shop.tax-percent", 5)));
        defaultShowcases = Math.max(1, c.getInt("shop.default-showcases", 6));
        maxStock = Math.max(1, c.getInt("shop.max-stock", 3456));
        buyDistance = Math.max(3, c.getDouble("shop.buy-distance", 12));
        minPrice = Math.max(0.01, c.getDouble("shop.min-price", 1));
        maxPrice = Math.max(minPrice, c.getDouble("shop.max-price", 100_000_000));
        protectStalls = c.getBoolean("protection.protect-stalls", true);
        overrideProtections = c.getBoolean("protection.override-other-protections", true);
        signHeight = c.getDouble("visuals.sign-height", 2.6);
        itemScale = (float) Math.max(0.2, Math.min(2, c.getDouble("visuals.item-scale", 0.55)));
        spin = c.getBoolean("visuals.spin", true);
    }

    /** "12 500 $" ou "12 500,50 $". */
    public String money(double v) {
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(Locale.FRANCE);
        sym.setGroupingSeparator(' ');
        DecimalFormat f = new DecimalFormat(Math.abs(v - Math.rint(v)) < 0.005 ? "#,##0" : "#,##0.00", sym);
        return f.format(v) + " " + currency;
    }

    /** Offre minimale pour une enchère. */
    public double minBid(Stall s) {
        Auction a = s.auction;
        if (a == null || !a.hasBid()) return s.startPrice;
        double inc = Math.max(minIncrement, a.bid * minIncrementPercent / 100.0);
        return Math.ceil(a.bid + inc);
    }

    /** "2j 4h", "3h 12min", "45s". */
    public static String time(long ms) {
        if (ms <= 0) return "0s";
        long sec = (ms + 999) / 1000;
        long d = sec / 86400, h = (sec % 86400) / 3600, m = (sec % 3600) / 60, s = sec % 60;
        if (d > 0) return h > 0 ? d + "j " + h + "h" : d + "j";
        if (h > 0) return m > 0 ? h + "h " + m + "min" : h + "h";
        if (m > 0) return m < 10 && s > 0 ? m + "min " + s + "s" : m + "min";
        return s + "s";
    }
}
