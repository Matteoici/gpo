package fr.gpotrade;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;

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
 *  lignes 1-3 : [grille A 3x3] [milieu : minuteur (22), chat (31)] [grille B 3x3]
 *  ligne 4 :  [argent A][statut A] . . . . . [statut B][argent B]
 *  ligne 5 :  . [ACCEPT x3] . [DECLINE x3] .
 *
 * Chaque joueur voit SA grille à gauche (modifiable) et celle du partenaire à droite (lecture seule).
 * Quand les deux ont accepté, un compte à rebours démarre ; toute modification l'annule.
 */
final class TradeSession {

    static final int[] OWN = {9, 10, 11, 18, 19, 20, 27, 28, 29};
    static final int[] OTHER = {15, 16, 17, 24, 25, 26, 33, 34, 35};

    static final int HEAD_OWN = 1, STATUS = 4, HEAD_OTHER = 7;
    static final int TIMER = 22, CHAT = 31;
    static final int MONEY_OWN = 36, STATUS_OWN = 37, STATUS_OTHER = 43, MONEY_OTHER = 44;

    static boolean isAccept(int s) {
        return s >= 46 && s <= 48;
    }

    static boolean isDecline(int s) {
        return s >= 50 && s <= 52;
    }

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
    /** Horodatage de fermeture de la fenêtre de chaque joueur (0 = fenêtre ouverte). */
    private final long[] closedSince = new long[2];
    private final List<String> chatLog = new ArrayList<>();
    private int countdown = -1;
    private int countdownTotal = 10;
    private BukkitTask task;
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
            Inventory inv = Bukkit.createInventory(holder, 54, title(i));
            holder.setInventory(inv);
            invs[i] = inv;

            for (int s = 0; s < 54; s++) {
                if (!isOwn(s) && !isOther(s)) inv.setItem(s, filler());
            }
        }
        render();
        for (int i = 0; i < 2; i++) players[i].openInventory(invs[i]);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private Component title(int i) {
        if (plugin.usePack()) {
            // \uF808 = décalage de -8 px, \uE000 = image de fond (voir le resource pack)
            return Component.text("\uF808\uE000", NamedTextColor.WHITE).font(Key.key("gpotrade", "trade"));
        }
        return Component.text("Trade with " + players[1 - i].getName());
    }

    /** Redessine les éléments dynamiques et recopie les offres dans la grille du partenaire. */
    void render() {
        if (finished) return;
        for (int i = 0; i < 2; i++) {
            int o = 1 - i;
            Inventory inv = invs[i];

            inv.setItem(HEAD_OWN, head(players[i]));
            inv.setItem(HEAD_OTHER, head(players[o]));

            boolean confirming = countdown >= 0;
            inv.setItem(STATUS, item(Material.CLOCK,
                    plain(confirming ? "CONFIRMING..." : "DECIDING...",
                            confirming ? NamedTextColor.GREEN : NamedTextColor.GOLD).decorate(TextDecoration.BOLD),
                    List.of(plain("Transaction ID", NamedTextColor.GRAY), plain(id, NamedTextColor.DARK_GRAY))));

            inv.setItem(TIMER, timerItem());
            inv.setItem(CHAT, chatItem(players[o]));

            inv.setItem(STATUS_OWN, status(i));
            inv.setItem(STATUS_OTHER, status(o));

            ItemStack accept = item(Material.LIME_CONCRETE,
                    plain("ACCEPT", NamedTextColor.GREEN).decorate(TextDecoration.BOLD), null);
            ItemStack decline = item(Material.RED_CONCRETE,
                    plain("DECLINE", NamedTextColor.RED).decorate(TextDecoration.BOLD), null);
            for (int s = 46; s <= 48; s++) inv.setItem(s, accept.clone());
            for (int s = 50; s <= 52; s++) inv.setItem(s, decline.clone());

            if (plugin.moneyEnabled()) {
                inv.setItem(MONEY_OWN, moneyItem(money[i], true));
                inv.setItem(MONEY_OTHER, moneyItem(money[o], false));
            }

            for (int k = 0; k < 9; k++) {
                ItemStack it = invs[o].getItem(OWN[k]);
                inv.setItem(OTHER[k], it == null ? null : it.clone());
            }
        }
    }

    private ItemStack status(int i) {
        if (closedSince[i] > 0) {
            return item(Material.GRAY_DYE, plain("AWAY", NamedTextColor.GRAY), null);
        }
        return accepted[i]
                ? item(Material.LIME_DYE, plain("ACCEPTED", NamedTextColor.GREEN), null)
                : item(Material.ORANGE_DYE, plain("DECIDING", NamedTextColor.GOLD), null);
    }

    /** Minuteur central : le chiffre (et la quantité de l'item) va de 10 à 1, du vert au rouge. */
    private ItemStack timerItem() {
        if (countdown < 0) {
            boolean oneAccepted = accepted[0] || accepted[1];
            return item(Material.CLOCK,
                    plain("Waiting...", NamedTextColor.GRAY),
                    List.of(plain(oneAccepted ? "En attente de l'autre joueur." : "Les deux joueurs doivent accepter.",
                            NamedTextColor.DARK_GRAY)));
        }
        int s = countdown;
        float t = (s - 1) / (float) Math.max(1, countdownTotal - 1); // 1 = début (vert), 0 = fin (rouge)
        Material m = t >= 0.7f ? Material.LIME_CONCRETE
                : t >= 0.4f ? Material.YELLOW_CONCRETE
                : t >= 0.15f ? Material.ORANGE_CONCRETE
                : Material.RED_CONCRETE;
        ItemStack it = item(m,
                Component.text(s + "s", gradient(t)).decorate(TextDecoration.BOLD)
                        .decoration(TextDecoration.ITALIC, false),
                List.of(plain("L'échange se termine dans " + s + " s.", NamedTextColor.GRAY),
                        plain("Modifie l'offre ou clique sur DECLINE pour annuler.", NamedTextColor.DARK_GRAY)));
        it.setAmount(Math.max(1, Math.min(64, s)));
        return it;
    }

    /** 0 = rouge, 0.5 = jaune, 1 = vert. */
    private static TextColor gradient(float t) {
        int r = (int) (255 * Math.min(1f, 2f * (1f - t)));
        int g = (int) (255 * Math.min(1f, 2f * t));
        return TextColor.color(r, g, 60);
    }

    private ItemStack chatItem(Player partner) {
        List<Component> lore = new ArrayList<>();
        lore.add(plain("Ferme la fenêtre (Échap) et écris dans le chat :", NamedTextColor.GRAY));
        lore.add(plain("seul " + partner.getName() + " te lira.", NamedTextColor.GRAY));
        lore.add(plain(" ", NamedTextColor.GRAY));
        if (chatLog.isEmpty()) {
            lore.add(plain("Aucun message", NamedTextColor.DARK_GRAY));
        } else {
            for (String line : chatLog) lore.add(plain(shorten(line, 44), NamedTextColor.WHITE));
        }
        return item(Material.WRITABLE_BOOK, plain("Chat with " + partner.getName(), NamedTextColor.AQUA), lore);
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
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

    // ------------------------------------------------------------------ minuteur / pause

    private void tick() {
        if (finished) {
            stop();
            return;
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < 2; i++) {
            if (closedSince[i] > 0 && now - closedSince[i] > plugin.pauseTimeoutMs()) {
                cancel(players[i].getName() + " a quitté la fenêtre trop longtemps : échange annulé.");
                return;
            }
        }
        if (countdown > 0) {
            countdown--;
            if (countdown == 0) {
                countdown = -1;
                complete();
                return;
            }
            float pitch = Math.min(2f, 0.8f + (countdownTotal - countdown) * 0.08f);
            for (Player p : players) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, pitch);
            render();
        }
    }

    private void startCountdown() {
        countdownTotal = plugin.countdownSeconds();
        countdown = countdownTotal;
        for (Player p : players) {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1f);
            p.sendMessage(plain("Les deux joueurs ont accepté : échange dans " + countdownTotal
                    + " s. Modifie l'offre ou clique sur DECLINE pour annuler.", NamedTextColor.GREEN));
        }
        render();
    }

    private void resetAccept() {
        accepted[0] = false;
        accepted[1] = false;
        countdown = -1;
    }

    private void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    // ------------------------------------------------------------------ interactions

    /** À appeler dès qu'une offre est modifiée : annule les "accept" et le minuteur, puis resynchronise. */
    void touch(int index) {
        resetAccept();
        lastChange = System.currentTimeMillis();
        Bukkit.getScheduler().runTask(plugin, this::render);
    }

    void accept(int i) {
        if (finished || accepted[i]) return;
        if (System.currentTimeMillis() - lastChange < ACCEPT_LOCK_MS) {
            players[i].sendMessage(plain("L'offre vient de changer, réessaie dans un instant.", NamedTextColor.RED));
            return;
        }
        accepted[i] = true;
        if (accepted[0] && accepted[1]) startCountdown();
        else render();
    }

    /** Fermeture de la fenêtre (Échap) : l'échange est mis en pause, pas annulé. */
    void onClosed(int i) {
        if (finished) return;
        resetAccept();
        closedSince[i] = System.currentTimeMillis();
        Player p = players[i];
        p.sendMessage(plain("Fenêtre fermée : l'échange est en pause. ", NamedTextColor.YELLOW)
                .append(Component.text("[Rouvrir]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/trade open")))
                .append(Component.text(" "))
                .append(Component.text("[Annuler]", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/trade cancel"))));
        players[1 - i].sendMessage(plain(p.getName() + " a fermé la fenêtre (échange en pause).", NamedTextColor.GRAY));
        Bukkit.getScheduler().runTask(plugin, this::render);
    }

    void reopen(int i) {
        if (finished) return;
        if (closedSince[i] == 0) {
            players[i].sendMessage(plain("Ta fenêtre d'échange est déjà ouverte.", NamedTextColor.GRAY));
            return;
        }
        closedSince[i] = 0;
        players[i].openInventory(invs[i]);
        render();
    }

    /** Message du chat privé d'échange : visible uniquement par les deux joueurs. */
    void chat(Player from, String text) {
        if (finished) return;
        String clean = text.length() > 120 ? text.substring(0, 120) : text;
        Component line = Component.text("[Trade] ", NamedTextColor.GOLD)
                .append(Component.text(from.getName() + ": ", NamedTextColor.YELLOW))
                .append(Component.text(clean, NamedTextColor.WHITE));
        for (Player p : players) p.sendMessage(line);
        chatLog.add(from.getName() + ": " + clean);
        while (chatLog.size() > 8) chatLog.remove(0);
        render();
    }

    int indexOf(Player p) {
        return players[0].equals(p) ? 0 : 1;
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
            resetAccept();
            render();
            return;
        }
        finished = true;
        stop();
        ItemStack[] offerA = take(0);
        ItemStack[] offerB = take(1);

        give(players[0], offerB);
        give(players[1], offerA);

        for (int i = 0; i < 2; i++) {
            plugin.addTrade(players[i].getUniqueId());
            players[i].sendMessage(plain("Échange terminé !", NamedTextColor.GREEN));
            players[i].playSound(players[i].getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        }
        closeBoth();
        plugin.unregister(this);
    }

    void cancel(String reason) {
        if (finished) return;
        finished = true;
        stop();
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

    /** Case de décor : sans nom ni infobulle (et cachée par l'image de fond si le resource pack est actif). */
    private static ItemStack filler() {
        ItemStack it = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = it.getItemMeta();
        meta.setHideTooltip(true);
        it.setItemMeta(meta);
        return it;
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
