package fr.smpevents;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Accès à l'argent des joueurs via Vault (classe chargée uniquement si Vault est présent). */
public final class Economy {

    private net.milkbowl.vault.economy.Economy eco() {
        RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> rsp =
                Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }

    public boolean available() {
        return eco() != null;
    }

    public double balance(OfflinePlayer p) {
        var e = eco();
        return e == null ? 0 : e.getBalance(p);
    }

    public boolean has(OfflinePlayer p, double amount) {
        if (amount <= 0) return true;
        var e = eco();
        return e != null && e.has(p, amount);
    }

    public boolean take(OfflinePlayer p, double amount) {
        if (amount <= 0) return true;
        var e = eco();
        return e != null && e.withdrawPlayer(p, amount).transactionSuccess();
    }

    public boolean give(OfflinePlayer p, double amount) {
        if (amount <= 0) return true;
        var e = eco();
        return e != null && e.depositPlayer(p, amount).transactionSuccess();
    }
}
