package fr.gpotrade;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Une session d'échange entre deux joueurs.
 *
 * Disposition du coffre double (9 colonnes x 6 lignes) :
 *
 *  ligne 0 :  .  [tête A]  .  .  [statut]  .  .  [tête B]  .
 *  lignes 1-3 : [grille A 3x3] [milieu] [grille B 3x3]
 *  ligne 4 :  .  [statut A]  .  .  .  .  .  [statut B]  .
 *  ligne 5 :  .  .  .  .  .  [ACCEPT] ... [DECLINE]
 *
 * Chaque joueur voit SA grille à gauche (modifiable) et celle du partenaire à droite (lecture seule).
 */
final class TradeSession {

    static final int[] OWN = {9, 10, 11, 18, 19, 20, 27, 28, 29};
    static final int[] OTHER = {15, 16, 17, 24, 25, 26, 33, 34, 35};
    static final int ACCEPT = 47;
    static final int DECLINE = 51;
    /** Lingots d'or sous chaque grille : montant offert (le tien à gauche, celui du partenaire à droite). */
    static final int MONEY_OWN = 36;
    static final int MONEY_OTHER = 44;

    /** Délai anti-arnaque : impossible d'accepter juste après un changement d'offre. */
    private static final long ACCEPT_LOCK_MS = 1500;

    static boolean isOwn(int slot) {
        int r = slot / 9, c = slot % 9;
        return r >= 1 && r <= 3 && c <= 2;
    }

    private static boolean isOther(int slot) {
        int r = slot / 9, c = slot % 9;
        return r >= 1 && r <= 3 && c >= 6;
    }

    private final TradePlugin plugin;
    private final String id = UUID.randomUUID().toString();
    private final Player[] players = new Player[2];
    private final Inventory[] invs = new Inventory[2];
    private final boolean[] accepted = new boolean[2];
    private final long[] money = new long[2];
    private long lastChange = System.currentTimeMillis();
    private boolean finished;

    TradeSession(TradePlugin plugin, Player a, Player b) {
        this.plugin = plugin;
        this.players[0] = a;
        this.players[1] = b;
    }

    Player player(int i) {
        return players[i];
    }

    // ------------------------------------------------------------------ ouverture / affichage

    void open() {
        for (int i = 0; i < 2; i++) {
            TradeHolder holder = new TradeHolder(this, i);
            Inventory inv = Bukkit.createInventory(holder, 54,
                    Component.text("Trade with " + players[1 - i].getName()));
            holder.setInventory(inv);
            invs[i] = inv;

            ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, plain(" ", NamedTextColor.GRAY), null);
            for (int s = 0; s < 54; s++) {
                if (!isOwn(s) && !isOther(s)) inv.setItem(s, filler);
            }
        }
        render();
        for (int i = 0; i < 2; i++) players[i].openInventory(invs[i]);
    }

    /** Redessine les éléments dynamiques et recopie les offres dans la grille du partenaire. */
    void render() {
        if (finished) return;
        for (int i = 0; i < 2; i++) {
            int o = 1 - i;
            Inventory inv = invs[i];

            inv.setItem(1, head(players[i]));
            inv.setItem(7, head(players[o]));

            inv.setItem(4, item(Material.CLOCK,
                    plain("DECIDING...", NamedTextColor.GOLD).decorate(TextDecoration.BOLD),
                    List.of(plain("Transaction ID", NamedTextColor.GRAY), plain(id, NamedTextColor.DARK_GRAY))));

            inv.setItem(37, status(accepted[i]));
            inv.setItem(43, status(accepted[o]));

            if (plugin.moneyEnabled()) {
                inv.setItem(MONEY_OWN, moneyItem(money[i], true));
                inv.setItem(MONEY_OTHER, moneyItem(money[o], false));
            }

            inv.setItem(ACCEPT, item(Material.LIME_CONCRETE,
                    plain("ACCEPT", NamedTextColor.GREEN).decorate(TextDecoration.BOLD), null));
            inv.setItem(DECLINE, item(Material.RED_CONCRETE,
                    plain("DECLINE", NamedTextColor.RED).decorate(TextDecoration.BOLD), null));

            for (int k = 0; k < 9; k++) {
                ItemStack it = invs[o].getItem(OWN[k]);
                inv.setItem(OTHER[k], it == null ? null : it.clone());
            }
        }
    }

    private ItemStack status(boolean isAccepted) {
        return isAccepted
                ? item(Material.LIME_DYE, plain("ACCEPTED", NamedTextColor.GREEN), null)
                : item(Material.ORANGE_DYE, plain("DECIDING", NamedTextColor.GOLD), null);
    }

    private ItemStack moneyItem(long amount, boolean own) {
        List<Component> lore = null;
        if (own) {
            long step = plugin.moneyStep();
            lore = List.of(
                    plain("Clic gauche : +" + fmt(step), NamedTextColor.GRAY),
                    plain("Clic droit : -" + fmt(step), NamedTextColor.GRAY),
                    plain("Shift + clic : x10", NamedTextColor.GRAY),
                    plain("Touche Q : remettre à 0", NamedTextColor.GRAY));
        }
        return item(Material.GOLD_INGOT,
                plain(plugin.symbol() + " : " + fmt(amount), NamedTextColor.GOLD), lore);
    }

    private static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    /** Modifie le montant offert par le joueur i (borné par son solde Vault). Le solde n'est débité qu'à la fin. */
    void adjustMoney(int i, long delta, boolean reset) {
        if (finished || !plugin.moneyEnabled()) return;
        long balance = (long) Math.floor(plugin.vault().balance(players[i]));
        long next = reset ? 0 : Math.max(0, Math.min(balance, money[i] + delta));
        if (next == money[i]) {
            if (delta > 0) players[i].sendMessage(plain("Solde insuffisant.", NamedTextColor.RED));
            return;
        }
        money[i] = next;
        touch(i);
    }

    private ItemStack head(Player p) {
        int trades = plugin.trades(p.getUniqueId());
        int stars = Math.min(5, trades / 20); // arbitraire : 1 étoile tous les 20 trades
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(p);
        meta.displayName(plain(p.getName(), NamedTextColor.WHITE));
        meta.lore(List.of(
                plain("★".repeat(stars) + "☆".repeat(5 - stars), NamedTextColor.YELLOW),
                plain(trades + " Trades", NamedTextColor.GRAY)));
        head.setItemMeta(meta);
        return head;
    }

    // ------------------------------------------------------------------ interactions

    /** À appeler dès qu'une offre est modifiée : réinitialise les "accept" et resynchronise au tick suivant. */
    void touch(int index) {
        accepted[0] = false;
        accepted[1] = false;
        lastChange = System.currentTimeMillis();
        Bukkit.getScheduler().runTask(plugin, this::render);
    }

    void accept(int i) {
        if (finished) return;
        if (System.currentTimeMillis() - lastChange < ACCEPT_LOCK_MS) {
            players[i].sendMessage(plain("L'offre vient de changer, réessaie dans un instant.", NamedTextColor.RED));
            return;
        }
        accepted[i] = true;
        render();
        if (accepted[0] && accepted[1]) complete();
    }

    /** Ajoute (en fusionnant les piles) un item dans la grille du joueur i. Retourne la quantité restante. */
    int addToOffer(int i, ItemStack item) {
        int left = item.getAmount();
        Inventory inv = invs[i];

        for (int s : OWN) {
            ItemStack cur = inv.getItem(s);
            if (cur != null && cur.isSimilar(item) && cur.getAmount() < cur.getMaxStackSize()) {
                int add = Math.min(left, cur.getMaxStackSize() - cur.getAmount());
                ItemStack merged = cur.clone();
                merged.setAmount(cur.getAmount() + add);
                inv.setItem(s, merged);
                left -= add;
                if (left <= 0) return 0;
            }
        }
        for (int s : OWN) {
            ItemStack cur = inv.getItem(s);
            if (cur == null || cur.getType().isAir()) {
                ItemStack put = item.clone();
                int amt = Math.min(left, put.getMaxStackSize());
                put.setAmount(amt);
                inv.setItem(s, put);
                left -= amt;
                if (left <= 0) return 0;
            }
        }
        return left;
    }

    // ------------------------------------------------------------------ fin d'échange

    /**
     * Vérifie les soldes puis transfère l'argent. Retourne false (sans rien débiter) si quelque chose échoue.
     * Les soldes sont revérifiés ici car ils peuvent avoir changé depuis le moment où l'offre a été fixée.
     */
    private boolean settleMoney() {
        if (money[0] == 0 && money[1] == 0) return true;
        VaultHook v = plugin.vault();
        if (v == null || !v.available()) return abortMoney("Économie indisponible, échange non validé.");
        if (!v.has(players[0], money[0]) || !v.has(players[1], money[1])) {
            return abortMoney("Un des joueurs n'a plus assez d'argent pour son offre.");
        }
        if (!v.withdraw(players[0], money[0])) return abortMoney("Le paiement a échoué.");
        if (!v.withdraw(players[1], money[1])) {
            v.deposit(players[0], money[0]); // rembourse le premier
            return abortMoney("Le paiement a échoué.");
        }
        v.deposit(players[1], money[0]);
        v.deposit(players[0], money[1]);
        return true;
    }

    private boolean abortMoney(String message) {
        for (Player p : players) p.sendMessage(plain(message, NamedTextColor.RED));
        return false;
    }

    private void complete() {
        if (!settleMoney()) {
            accepted[0] = false;
            accepted[1] = false;
            render();
            return;
        }
        finished = true;
        ItemStack[] offerA = take(0);
        ItemStack[] offerB = take(1);

        give(players[0], offerB);
        give(players[1], offerA);

        for (int i = 0; i < 2; i++) {
            plugin.addTrade(players[i].getUniqueId());
            players[i].sendMessage(plain("Échange terminé !", NamedTextColor.GREEN));
        }
        closeBoth();
        plugin.unregister(this);
    }

    void cancel(String reason) {
        if (finished) return;
        finished = true;
        for (int i = 0; i < 2; i++) {
            give(players[i], take(i));
            players[i].sendMessage(plain(reason, NamedTextColor.RED));
        }
        closeBoth();
        plugin.unregister(this);
    }

    private ItemStack[] take(int i) {
        List<ItemStack> out = new ArrayList<>();
        for (int s : OWN) {
            ItemStack it = invs[i].getItem(s);
            if (it != null && !it.getType().isAir()) out.add(it.clone());
            invs[i].setItem(s, null);
        }
        return out.toArray(new ItemStack[0]);
    }

    private void give(Player p, ItemStack[] items) {
        if (items.length == 0) return;
        p.getInventory().addItem(items).values()
                .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
    }

    private void closeBoth() {
        Runnable r = () -> {
            for (Player p : players) {
                if (p.isOnline() && p.getOpenInventory().getTopInventory().getHolder() instanceof TradeHolder) {
                    p.closeInventory();
                }
            }
        };
        // Fermer pendant un InventoryCloseEvent est risqué : on diffère d'un tick (sauf à l'arrêt du serveur).
        if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, r);
        else r.run();
    }

    // ------------------------------------------------------------------ utilitaires

    private static Component plain(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack item(Material m, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name);
        if (lore != null) meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }
}
