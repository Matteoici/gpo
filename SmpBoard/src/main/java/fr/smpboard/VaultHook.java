package fr.smpboard;

import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Chargée seulement si Vault est là (sinon ses classes n'existent pas). */
final class VaultHook {

    private Economy eco;
    private Chat chat;

    void refresh() {
        RegisteredServiceProvider<Economy> e = Bukkit.getServicesManager().getRegistration(Economy.class);
        eco = e == null ? null : e.getProvider();
        RegisteredServiceProvider<Chat> c = Bukkit.getServicesManager().getRegistration(Chat.class);
        chat = c == null ? null : c.getProvider();
    }

    double money(Player p) {
        if (eco == null) refresh();
        try {
            return eco == null ? 0 : eco.getBalance(p);
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    String prefix(Player p) {
        if (chat == null) refresh();
        try {
            String s = chat == null ? null : chat.getPlayerPrefix(p);
            return s == null ? "" : s;
        } catch (RuntimeException ex) {
            return "";
        }
    }

    String group(Player p) {
        if (chat == null) refresh();
        try {
            String s = chat == null ? null : chat.getPrimaryGroup(p);
            return s == null ? "" : s;
        } catch (RuntimeException ex) {
            return "";
        }
    }
}
