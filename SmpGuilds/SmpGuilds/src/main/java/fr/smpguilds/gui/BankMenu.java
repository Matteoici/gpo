package fr.smpguilds.gui;

import fr.smpguilds.Economy;
import fr.smpguilds.Guild;
import fr.smpguilds.GuildActions;
import fr.smpguilds.Messages;
import fr.smpguilds.SmpGuilds;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import static fr.smpguilds.Messages.ph;

/** Trésorerie : déposer / retirer des montants rapides ou personnalisés. */
public final class BankMenu extends Menu {

    private static final double[] AMOUNTS = {100, 1_000, 10_000};

    public BankMenu(SmpGuilds plugin, Player viewer) {
        super(plugin, viewer, 4, plugin.messages().get("gui.bank.title"));
    }

    @Override
    protected void build() {
        Guild g = myGuild();
        if (g == null) {
            viewer.closeInventory();
            return;
        }
        GuildActions a = plugin.actions();
        Economy eco = plugin.economy();
        double wallet = eco != null && eco.available() ? eco.balance(viewer) : 0;
        set(4, item(Material.GOLD_BLOCK, "gui.bank.balance", ph("bank", Messages.money(g.bank)),
                ph("wallet", Messages.money(wallet))));
        for (int i = 0; i < AMOUNTS.length; i++) {
            double amt = AMOUNTS[i];
            button(10 + i, item(Material.LIME_STAINED_GLASS, "gui.bank.deposit", ph("amount", Messages.money(amt))), t -> {
                a.deposit(viewer, amt);
                redraw();
            });
            button(14 + i, item(Material.RED_STAINED_GLASS, "gui.bank.withdraw", ph("amount", Messages.money(amt))), t -> {
                a.withdraw(viewer, amt);
                redraw();
            });
        }
        button(19, item(Material.EMERALD, "gui.bank.deposit-custom"), t ->
                plugin.input().ask(viewer, "input.amount", s -> a.deposit(viewer, GuildActions.parseAmount(s))));
        button(20, item(Material.EMERALD_BLOCK, "gui.bank.deposit-all", ph("amount", Messages.money(Math.floor(wallet)))), t -> {
            if (wallet >= 1) a.deposit(viewer, Math.floor(wallet));
            redraw();
        });
        button(24, item(Material.REDSTONE, "gui.bank.withdraw-custom"), t ->
                plugin.input().ask(viewer, "input.amount", s -> a.withdraw(viewer, GuildActions.parseAmount(s))));
        back(31, () -> new MainMenu(plugin, viewer).open());
        fill(g.color);
    }
}
