package fr.smppets;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Argent du jeu via Vault. Cette classe n'est chargée que si Vault est présent. */
final class Money {
    private Money() {
    }

    private static Economy eco() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }

    static boolean ready() {
        return eco() != null;
    }

    static double balance(Player p) {
        Economy e = eco();
        return e == null ? 0 : e.getBalance(p);
    }

    static boolean take(Player p, double amount) {
        Economy e = eco();
        return e != null && e.has(p, amount) && e.withdrawPlayer(p, amount).transactionSuccess();
    }

    static String format(double amount) {
        Economy e = eco();
        return e == null ? String.valueOf((long) amount) : e.format(amount);
    }
}
