package fr.gpotrade;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Seule classe qui touche à l'API Vault : elle n'est instanciée que si Vault est présent,
 * ce qui évite toute erreur de chargement de classe quand il est absent.
 */
final class VaultHook {

    /** Récupéré à chaque appel : le plugin d'économie peut s'enregistrer après nous. */
    private Economy eco() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }

    boolean available() {
        return eco() != null;
    }

    double balance(Player p) {
        Economy e = eco();
        return e == null ? 0 : e.getBalance(p);
    }

    boolean has(Player p, long amount) {
        if (amount <= 0) return true;
        Economy e = eco();
        return e != null && e.has(p, amount);
    }

    boolean withdraw(Player p, long amount) {
        if (amount <= 0) return true;
        Economy e = eco();
        return e != null && e.withdrawPlayer(p, amount).transactionSuccess();
    }

    boolean deposit(Player p, long amount) {
        if (amount <= 0) return true;
        Economy e = eco();
        return e != null && e.depositPlayer(p, amount).transactionSuccess();
    }
}
