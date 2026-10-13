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
    public final long auctionMs, antiSnipeMs, inactiveMs;
    public final double minIncrement, minIncrementPercent;
    public final double taxPercent;
    /** vente d'au moins X -> impôt de Y % (impôt progressif) */
    public final java.util.TreeMap<Double, Double> taxBrackets;

    /** Impôt (en %) sur une vente de ce montant. */
    public double taxFor(double total) {
        var e = taxBrackets.floorEntry(total);
        return e == null ? taxPercent : Math.max(taxPercent, e.getValue());
    }
    public final int defaultShowcases, maxStock;
    public final double buyDistance, minPrice, maxPrice;
    public final boolean protectStalls, overrideProtections;
    public final double signHeight;
    public final float itemScale;
    public final boolean spin;
    public final float labelScale;
    public final int labelWidth;
    public final boolean labelStagger;
    public final boolean labelShortPrices;
    public final double showcaseBasePrice, showcasePriceGrowth, catalogBasePrice, catalogPriceGrowth;
    public final int maxExtraShowcases, maxCatalogSlots;
    public final boolean mannequins, privateAllowed;
    public final double mannequinDistance;

    Settings(FileConfiguration c) {
        currency = c.getString("currency-symbol", "$");
        leaseMs = Math.max(1, (long) (c.getDouble("lease.days", 7) * 24 * H));
        renewWindowMs = Math.max(H, (long) (c.getDouble("lease.renew-window-hours", 24) * H));
        maxPerPlayer = Math.max(1, c.getInt("lease.max-per-player", 1));
        auctionMs = Math.max(60_000L, (long) (c.getDouble("auction.duration-hours", 2) * H));
        inactiveMs = Math.max(0, (long) (c.getDouble("lease.inactive-hours", 24) * H));
        minIncrement = Math.max(1, c.getDouble("auction.min-increment", 100));
        minIncrementPercent = Math.max(0, c.getDouble("auction.min-increment-percent", 5));
        antiSnipeMs = Math.max(0, (long) (c.getDouble("auction.anti-snipe-minutes", 5) * 60_000L));
        taxPercent = Math.max(0, Math.min(100, c.getDouble("shop.tax-percent", 5)));
        taxBrackets = new java.util.TreeMap<>();
        var br = c.getConfigurationSection("shop.tax-brackets");
        if (br != null) {
            for (String k : br.getKeys(false)) {
                try {
                    taxBrackets.put(Double.parseDouble(k.replace("_", "").replace(" ", "")),
                            Math.max(0, Math.min(100, br.getDouble(k))));
                } catch (NumberFormatException ignored) {
                }
            }
        }
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
        labelScale = (float) Math.max(0.2, Math.min(1.5, c.getDouble("visuals.label-scale", 0.5)));
        labelWidth = Math.max(40, c.getInt("visuals.label-width", 70));
        labelStagger = c.getBoolean("visuals.label-stagger", true);
        labelShortPrices = c.getBoolean("visuals.label-short-prices", true);
        showcaseBasePrice = Math.max(0, c.getDouble("upgrades.showcase.base-price", 25000));
        showcasePriceGrowth = Math.max(1, c.getDouble("upgrades.showcase.price-growth", 1.5));
        maxExtraShowcases = Math.max(0, c.getInt("upgrades.showcase.max-extra", 12));
        catalogBasePrice = Math.max(0, c.getDouble("upgrades.catalog.base-price", 15000));
        catalogPriceGrowth = Math.max(1, c.getDouble("upgrades.catalog.price-growth", 1.4));
        maxCatalogSlots = Math.max(0, c.getInt("upgrades.catalog.max-slots", 21));
        mannequins = c.getBoolean("mannequins.enabled", true);
        mannequinDistance = Math.max(0, c.getDouble("mannequins.owner-distance", 100));
        privateAllowed = c.getBoolean("protection.allow-private-stalls", true);
    }

    /** "12 500 $" ou "12 500,50 $". */
    public String money(double v) {
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(Locale.FRANCE);
        sym.setGroupingSeparator(' ');
        DecimalFormat f = new DecimalFormat(Math.abs(v - Math.rint(v)) < 0.005 ? "#,##0" : "#,##0.00", sym);
        return f.format(v) + " " + currency;
    }

    /** Prix court pour les étiquettes : "950 $", "12,5k $", "1M $", "2,3Md $". */
    public String moneyShort(double v) {
        double a = Math.abs(v);
        if (a < 10_000) return money(v);
        String[] u = {"k", "M", "Md"};
        double[] d = {1_000, 1_000_000, 1_000_000_000};
        int i = a >= 1e9 ? 2 : a >= 1e6 ? 1 : 0;
        double x = v / d[i];
        String n = x >= 100 ? String.valueOf(Math.round(x))
                : String.format(Locale.FRANCE, "%.1f", x).replaceAll(",0$", "");
        return n + u[i] + " " + currency;
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
