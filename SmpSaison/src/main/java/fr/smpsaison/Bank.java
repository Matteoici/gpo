package fr.smpsaison;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/** Argent via Vault (classe chargée seulement si Vault est présent). */
final class Bank {

    private Bank() {
    }

    private static net.milkbowl.vault.economy.Economy eco() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return null;
        var rsp = Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }

    static boolean give(OfflinePlayer p, double amount) {
        if (amount <= 0) return true;
        try {
            var e = eco();
            return e != null && e.depositPlayer(p, amount).transactionSuccess();
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean take(OfflinePlayer p, double amount) {
        try {
            var e = eco();
            return e != null && e.has(p, amount) && e.withdrawPlayer(p, amount).transactionSuccess();
        } catch (Throwable t) {
            return false;
        }
    }
}
