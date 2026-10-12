package fr.smpshop;

import org.bukkit.configuration.file.FileConfiguration;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Réglages généraux (config.yml). */
public final class Settings {

    public final String symbol;
    public final String tokenName;

    Settings(FileConfiguration c) {
        symbol = c.getString("currency-symbol", "$");
        tokenName = c.getString("tokens.name", "jetons");
    }

    public String money(double v) {
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(Locale.US);
        DecimalFormat f = new DecimalFormat(Math.abs(v - Math.rint(v)) < 0.005 ? "#,##0" : "#,##0.00", sym);
        return symbol + f.format(v);
    }

    public String tokens(long v) {
        return String.format(Locale.US, "%,d", v) + " " + tokenName;
    }
}
