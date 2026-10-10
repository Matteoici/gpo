package fr.smpfriends;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Seule classe qui touche à Vault (créée uniquement si Vault est installé). */
public final class VaultHook {

    private Economy eco() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }

    public boolean available() {
        return eco() != null;
    }

    public double balance(OfflinePlayer p) {
        Economy e = eco();
        return e == null ? 0 : e.getBalance(p);
    }

    /** Transfère l'argent ; ne débite rien si le dépôt échoue. */
    public boolean transfer(OfflinePlayer from, OfflinePlayer to, double amount) {
        Economy e = eco();
        if (e == null || amount <= 0 || !e.has(from, amount)) return false;
        if (!e.withdrawPlayer(from, amount).transactionSuccess()) return false;
        if (!e.depositPlayer(to, amount).transactionSuccess()) {
            e.depositPlayer(from, amount);
            return false;
        }
        return true;
    }
}
