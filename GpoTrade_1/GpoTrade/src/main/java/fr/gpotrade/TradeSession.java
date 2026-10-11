package fr.gpotrade;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
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
 * Un échange entre deux joueurs.
 *
 * Fenêtre = coffre double (9 x 6). Chaque joueur voit SA grille à gauche (modifiable)
 * et celle du partenaire à droite (lecture seule) :
 *
 *   ligne 0 :  .  [tête]  .  .  [statut]  .  .  [tête]  .
 *   lignes 1-3 : [ta grille 3x3]  [minuteur 22 / chat 31]  [sa grille 3x3]
 *   ligne 4 :  [ton argent][ton statut] . . . . . [son statut][son argent]
 *   ligne 5 :  .  [ACCEPT x3]  .  [DECLINE x3]  .
 *
 * Quand les deux acceptent, un compte à rebours démarre (item central + boss bar).
 * Toute modification de l'offre l'annule.
 */
final class TradeSession {

    static final int[] OWN = {9, 10, 11, 18, 19, 20, 27, 28, 29};
    static final int[] OTHER = {15, 16, 17, 24, 25, 26, 33, 34, 35};
    static final int HEAD_OWN = 1, STATUS = 4, HEAD_OTHER = 7, TIMER = 22, CHAT = 31;
    static final int MONEY_OWN = 36, STATUS_OWN = 37, STATUS_OTHER = 43, MONEY_OTHER = 44;

    static boolean isOwn(int slot) {
        int r = slot / 9, c = slot % 9;
        return r >= 1 && r <= 3 && c <= 2;
    }

    static boolean isOther(int slot) {
        int r = slot / 9, c = slot % 9;
        return r >= 1 && r <= 3 && c >= 6;
    }

    static boolean isAccept(int s) {
        return s >= 46 && s <= 48;
    }

    static boolean isDecline(int s) {
        return s >= 50 && s <= 52;
    }

    private final TradePlugin plugin;
    final String id = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    private final Player[] players = new Player[2];
    private final Inventory[] invs = new Inventory[2];
    private final boolean[] accepted = new boolean[2];
    private final boolean[] chatting = new boolean[2];
    /** Le joueur est en train d'écrire un montant (et pas un message). */
    private final boolean[] typingMoney = new boolean[2];
    private final long[] money = new long[2];
    /** Moment où chaque joueur a fermé sa fenêtre (0 = ouverte). */
    private final long[] closedSince = new long[2];
    private final List<String> chatLog = new ArrayList<>();
    private final BossBar bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
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

    int indexOf(Player p) {
        return players[0].getUniqueId().equals(p.getUniqueId()) ? 0 : 1;
    }

    // =============================================================== ouverture / affichage

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
        for (int i = 0; i < 2; i++) {
            players[i].openInventory(invs[i]);
            players[i].playSound(players[i].getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.3f);
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private Component title(int i) {
        if (plugin.pack().fancy(players[i])) {
            return TitleArt.title(players[i].getName(), players[1 - i].getName());
        }
        return Component.text(players[i].getName() + "  ⇄  " + players[1 - i].getName());
    }

    /** Redessine tout ce qui change et recopie l'offre de chacun dans la grille de l'autre. */
    void render() {
        if (finished) return;
        for (int i = 0; i < 2; i++) {
            int o = 1 - i;
            Inventory inv = invs[i];

            inv.setItem(HEAD_OWN, head(players[i]));
            inv.setItem(HEAD_OTHER, head(players[o]));
            inv.setItem(STATUS, statusHeader());
            inv.setItem(TIMER, timerItem());
            inv.setItem(CHAT, chatItem(players[o]));
            inv.setItem(STATUS_OWN, status(i));
            inv.setItem(STATUS_OTHER, status(o));

            ItemStack accept = item(Material.LIME_CONCRETE, plain("ACCEPT", NamedTextColor.GREEN).decorate(TextDecoration.BOLD),
                    List.of(plain("Valider ton offre", NamedTextColor.GRAY)));
            ItemStack decline = item(Material.RED_CONCRETE, plain("DECLINE", NamedTextColor.RED).decorate(TextDecoration.BOLD),
                    List.of(plain("Annuler l'échange", NamedTextColor.GRAY)));
            for (int s = 46; s <= 48; s++) inv.setItem(s, accept);
            for (int s = 50; s <= 52; s++) inv.setItem(s, decline);

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

    private ItemStack statusHeader() {
        boolean confirming = countdown >= 0;
        return item(confirming ? Material.LIME_DYE : Material.CLOCK,
                plain(confirming ? "CONFIRMING..." : "DECIDING...", confirming ? NamedTextColor.GREEN : NamedTextColor.GOLD)
                        .decorate(TextDecoration.BOLD),
                List.of(plain("Transaction ID : " + id, NamedTextColor.GRAY)));
    }

    private ItemStack status(int i) {
        if (closedSince[i] > 0) {
            return item(Material.GRAY_DYE, plain(chatting[i] ? "TYPING..." : "AWAY", NamedTextColor.GRAY), null);
        }
        return accepted[i]
                ? item(Material.LIME_DYE, plain("ACCEPTED", NamedTextColor.GREEN), null)
                : item(Material.ORANGE_DYE, plain("DECIDING", NamedTextColor.GOLD), null);
    }

    /** Minuteur central : de 10 à 1, du vert au rouge (la quantité de l'item = secondes restantes). */
    private ItemStack timerItem() {
        if (countdown < 0) {
            boolean one = accepted[0] || accepted[1];
            return item(Material.CLOCK, plain("Waiting...", NamedTextColor.GRAY),
                    List.of(plain(one ? "En attente de l'autre joueur." : "Les deux joueurs doivent accepter.",
                            NamedTextColor.DARK_GRAY)));
        }
        float t = progress();
        Material m = t > 0.7f ? Material.LIME_CONCRETE
                : t > 0.4f ? Material.YELLOW_CONCRETE
                : t > 0.15f ? Material.ORANGE_CONCRETE
                : Material.RED_CONCRETE;
        ItemStack it = item(m,
                Component.text(countdown + "s", gradient(t)).decorate(TextDecoration.BOLD)
                        .decoration(TextDecoration.ITALIC, false),
                List.of(plain("L'échange se fait dans " + countdown + " s.", NamedTextColor.GRAY),
                        plain("Modifie l'offre ou clique DECLINE pour annuler.", NamedTextColor.DARK_GRAY)));
        it.setAmount(Math.max(1, Math.min(64, countdown)));
        return it;
    }

    /** 1 au début du compte à rebours, 0 à la fin. */
    private float progress() {
        return (countdown - 1) / (float) Math.max(1, countdownTotal - 1);
    }

    /** 0 = rouge, 0.5 = jaune, 1 = vert. */
    private static TextColor gradient(float t) {
        int r = (int) (255 * Math.min(1f, 2f * (1f - t)));
        int g = (int) (255 * Math.min(1f, 2f * t));
        return TextColor.color(r, g, 50);
    }

    private ItemStack chatItem(Player partner) {
        List<Component> lore = new ArrayList<>();
        lore.add(plain("Clique pour écrire à " + partner.getName() + ".", NamedTextColor.GRAY));
        lore.add(plain("La fenêtre se rouvre après ton message.", NamedTextColor.DARK_GRAY));
        lore.add(Component.empty());
        if (chatLog.isEmpty()) lore.add(plain("Aucun message", NamedTextColor.DARK_GRAY));
        for (String line : chatLog) lore.add(plain(line, NamedTextColor.WHITE));
        return item(Material.WRITABLE_BOOK, plain("Chat with " + partner.getName(), NamedTextColor.AQUA), lore);
    }

    private ItemStack moneyItem(long amount, boolean own) {
        List<Component> lore = null;
        if (own) {
            long step = plugin.moneyStep();
            lore = List.of(
                    plain("Clic gauche : +" + fmt(step), NamedTextColor.GRAY),
                    plain("Clic droit : -" + fmt(step), NamedTextColor.GRAY),
                    plain("Shift + clic : x10", NamedTextColor.GRAY),
                    plain("Clic molette ou touche F : écrire un montant", NamedTextColor.YELLOW),
                    plain("Touche Q : remettre à 0", NamedTextColor.GRAY));
        }
        return item(Material.GOLD_INGOT, plain(plugin.symbol() + " : " + fmt(amount), NamedTextColor.GOLD), lore);
    }

    private ItemStack head(Player p) {
        PlayerData data = plugin.data();
        UUID u = p.getUniqueId();
        int reviews = data.reviews(u);
        int stars = (int) Math.round(data.rating(u));
        List<Component> lore = new ArrayList<>();
        lore.add(plain("★".repeat(stars) + "☆".repeat(5 - stars), NamedTextColor.YELLOW)
                .append(plain("  " + data.trades(u) + " Trades", NamedTextColor.GRAY)));
        if (plugin.reviewsEnabled()) {
            lore.add(plain("Community Reviews : " + reviews
                    + (reviews > 0 ? " (" + String.format(Locale.ROOT, "%.1f", data.rating(u)) + "/5)" : ""),
                    NamedTextColor.DARK_GRAY));
        }
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(p);
        meta.displayName(plain(p.getName(), NamedTextColor.WHITE).decorate(TextDecoration.BOLD));
        meta.lore(lore);
        head.setItemMeta(meta);
        return head;
    }

    // =============================================================== minuteur

    private void tick() {
        if (finished) {
            stopTask();
            return;
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < 2; i++) {
            if (closedSince[i] > 0 && now - closedSince[i] > plugin.pauseTimeoutMs()) {
                cancel(players[i].getName() + " n'est pas revenu dans l'échange : annulé.");
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
            float pitch = Math.min(2f, 0.8f + (countdownTotal - countdown) * 0.1f);
            for (Player p : players) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, pitch);
            updateBar();
            render();
        }
    }

    private void startCountdown() {
        countdownTotal = plugin.countdownSeconds();
        countdown = countdownTotal;
        updateBar();
        for (Player p : players) {
            p.showBossBar(bar);
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1f);
        }
        render();
    }

    private void updateBar() {
        float t = progress();
        bar.progress(Math.max(0f, Math.min(1f, countdown / (float) countdownTotal)));
        bar.color(t > 0.5f ? BossBar.Color.GREEN : t > 0.2f ? BossBar.Color.YELLOW : BossBar.Color.RED);
        bar.name(Component.text("Échange dans ", NamedTextColor.WHITE)
                .append(Component.text(countdown + "s", gradient(t)).decorate(TextDecoration.BOLD)));
    }

    private void hideBar() {
        for (Player p : players) p.hideBossBar(bar);
    }

    private void resetAccept() {
        accepted[0] = false;
        accepted[1] = false;
        if (countdown >= 0) {
            countdown = -1;
            hideBar();
        }
    }

    private void stopTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    // =============================================================== actions des joueurs

    /** Le joueur i a modifié son offre : on annule les validations et on prévient l'autre. */
    void touch(int i) {
        boolean partnerHadAccepted = accepted[1 - i];
        resetAccept();
        lastChange = System.currentTimeMillis();
        if (partnerHadAccepted) {
            Player other = players[1 - i];
            other.sendActionBar(Component.text(players[i].getName() + " a modifié son offre !", NamedTextColor.RED));
            other.playSound(other.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
        }
        Bukkit.getScheduler().runTask(plugin, this::render); // après que l'item a bougé
    }

    void accept(int i) {
        if (finished || accepted[i]) return;
        if (System.currentTimeMillis() - lastChange < plugin.antiScamDelayMs()) {
            players[i].sendActionBar(Component.text("L'offre vient de changer, vérifie-la avant d'accepter.", NamedTextColor.RED));
            return;
        }
        accepted[i] = true;
        players[i].playSound(players[i].getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
        if (accepted[0] && accepted[1]) startCountdown();
        else render();
    }

    /** Clic sur le livre : on ferme la fenêtre le temps que le joueur écrive son message. */
    void openChat(int i) {
        if (finished) return;
        chatting[i] = true;
        Player p = players[i];
        Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory());
    }

    /** Clic molette / touche F sur le lingot : le joueur écrit le montant exact dans le chat. */
    void openMoneyInput(int i) {
        if (finished || !plugin.moneyEnabled()) return;
        typingMoney[i] = true;
        chatting[i] = true;
        Player p = players[i];
        Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory());
    }

    /** Fixe le montant exact offert par le joueur i (ramené à son solde si besoin). */
    void setMoney(int i, long amount) {
        if (finished || !plugin.moneyEnabled()) return;
        long balance = (long) Math.floor(plugin.vault().balance(players[i]));
        long next = Math.max(0, Math.min(balance, amount));
        if (next < amount) {
            players[i].sendMessage(Component.text("Solde insuffisant : montant ramené à " + fmt(next) + " " + plugin.symbol() + ".",
                    NamedTextColor.RED));
        }
        if (next != money[i]) {
            money[i] = next;
            players[i].playSound(players[i].getLocation(), Sound.BLOCK_CHAIN_PLACE, 0.6f, 1.6f);
            touch(i);
        }
    }

    /** "1500", "1 500", "2,5k", "1m". -1 si ce n'est pas un montant. */
    static long parseAmount(String text) {
        String v = text.trim().toLowerCase().replace(" ", "").replace(",", ".").replace("$", "").replace("₲", "");
        double mult = 1;
        if (v.endsWith("k")) {
            mult = 1_000;
            v = v.substring(0, v.length() - 1);
        } else if (v.endsWith("m")) {
            mult = 1_000_000;
            v = v.substring(0, v.length() - 1);
        }
        try {
            double d = Double.parseDouble(v) * mult;
            if (!Double.isFinite(d) || d < 0 || d > 1e15) return -1;
            return (long) Math.floor(d);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Fenêtre fermée (Échap ou chat) : l'échange est mis en pause, pas annulé. */
    void onClosed(int i) {
        if (finished) return;
        resetAccept();
        closedSince[i] = System.currentTimeMillis();
        Player p = players[i];
        if (typingMoney[i]) {
            p.sendMessage(Component.text("✎ Écris le montant à donner dans le chat (ex. 1500, 2.5k, 1m). "
                    + "« annuler » pour revenir.", NamedTextColor.GOLD));
        } else if (chatting[i]) {
            p.sendMessage(Component.text("✎ Écris ton message à " + players[1 - i].getName()
                    + " dans le chat, la fenêtre se rouvrira.", NamedTextColor.AQUA));
        } else {
            p.sendMessage(Component.text("Échange en pause. ", NamedTextColor.YELLOW)
                    .append(button("[Rouvrir]", NamedTextColor.GREEN, "/trade open"))
                    .append(Component.text(" "))
                    .append(button("[Annuler]", NamedTextColor.RED, "/trade cancel")));
        }
        Bukkit.getScheduler().runTask(plugin, this::render);
    }

    void reopen(int i) {
        if (finished) return;
        if (closedSince[i] == 0) return;
        closedSince[i] = 0;
        chatting[i] = false;
        typingMoney[i] = false;
        players[i].openInventory(invs[i]);
        render();
    }

    /** Message privé entre les deux joueurs (le chat public ne le voit pas). */
    void chat(Player from, String text) {
        if (finished) return;
        int i = indexOf(from);
        if (typingMoney[i]) {
            // saisie d'un montant : rien n'est envoyé au partenaire
            if (!text.trim().equalsIgnoreCase("annuler")) {
                long amount = parseAmount(text);
                if (amount < 0) {
                    from.sendMessage(Component.text("Montant invalide : " + text + " (ex. 1500, 2.5k, 1m).", NamedTextColor.RED));
                } else {
                    setMoney(i, amount);
                }
            }
            reopen(i);
            return;
        }
        String clean = text.length() > 100 ? text.substring(0, 100) : text;
        Component line = Component.text("[Trade] ", NamedTextColor.GOLD)
                .append(Component.text(from.getName() + " : ", NamedTextColor.YELLOW))
                .append(Component.text(clean, NamedTextColor.WHITE));
        for (Player p : players) p.sendMessage(line);

        Player other = players[1 - i];
        other.sendActionBar(Component.text(from.getName() + " : " + clean, NamedTextColor.AQUA));
        other.playSound(other.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 1.6f);

        String entry = from.getName() + ": " + clean;
        chatLog.add(entry.length() > 42 ? entry.substring(0, 41) + "…" : entry);
        while (chatLog.size() > 6) chatLog.remove(0);

        if (chatting[i]) reopen(i);
        else render();
    }

    /** Ajoute un item (en fusionnant les piles) dans la grille du joueur i. Retourne ce qui n'a pas pu entrer. */
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

    /** Change le montant offert par le joueur i (jamais plus que son solde). L'argent n'est débité qu'à la fin. */
    void adjustMoney(int i, long delta, boolean reset) {
        if (finished || !plugin.moneyEnabled()) return;
        long balance = (long) Math.floor(plugin.vault().balance(players[i]));
        long next = reset ? 0 : Math.max(0, Math.min(balance, money[i] + delta));
        if (next == money[i]) {
            if (delta > 0) players[i].sendActionBar(Component.text("Solde insuffisant.", NamedTextColor.RED));
            return;
        }
        money[i] = next;
        players[i].playSound(players[i].getLocation(), Sound.BLOCK_CHAIN_PLACE, 0.6f, 1.6f);
        touch(i);
    }

    // =============================================================== fin d'échange

    /** Vérifie les soldes puis transfère l'argent. Ne débite rien si quelque chose échoue. */
    private boolean settleMoney() {
        if (money[0] == 0 && money[1] == 0) return true;
        VaultHook v = plugin.vault();
        if (v == null || !v.available()) return fail("Économie indisponible, échange non validé.");
        if (!v.has(players[0], money[0]) || !v.has(players[1], money[1])) {
            return fail("Un des joueurs n'a plus assez d'argent pour son offre.");
        }
        if (!v.withdraw(players[0], money[0])) return fail("Le paiement a échoué.");
        if (!v.withdraw(players[1], money[1])) {
            v.deposit(players[0], money[0]); // rembourse le premier
            return fail("Le paiement a échoué.");
        }
        v.deposit(players[1], money[0]);
        v.deposit(players[0], money[1]);
        return true;
    }

    private boolean fail(String message) {
        for (Player p : players) p.sendMessage(Component.text(message, NamedTextColor.RED));
        return false;
    }

    private void complete() {
        if (!settleMoney()) {
            resetAccept();
            render();
            return;
        }
        finished = true;
        stopTask();
        hideBar();
        ItemStack[] offerA = take(0);
        ItemStack[] offerB = take(1);
        give(players[0], offerB);
        give(players[1], offerA);

        String sym = plugin.symbol();
        plugin.tradeLog().write("#" + id + " " + players[0].getName() + " a donné [" + TradeLog.describe(offerA, money[0], sym)
                + "] ; " + players[1].getName() + " a donné [" + TradeLog.describe(offerB, money[1], sym) + "]");

        for (int i = 0; i < 2; i++) {
            Player p = players[i];
            plugin.data().addTrade(p.getUniqueId());
            p.sendMessage(Component.text("✔ Échange terminé ! (#" + id + ")", NamedTextColor.GREEN));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
            plugin.offerReview(p, players[1 - i], id);
        }
        closeBoth();
        plugin.unregister(this);
    }

    void cancel(String reason) {
        if (finished) return;
        finished = true;
        stopTask();
        hideBar();
        for (int i = 0; i < 2; i++) {
            give(players[i], take(i));
            players[i].sendMessage(Component.text(reason, NamedTextColor.RED));
            players[i].playSound(players[i].getLocation(), Sound.BLOCK_CHEST_CLOSE, 0.6f, 0.8f);
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

    private static void give(Player p, ItemStack[] items) {
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
        // Fermer pendant un événement de fermeture est risqué : on diffère d'un tick (sauf à l'arrêt du serveur).
        if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, r);
        else r.run();
    }

    // =============================================================== utilitaires

    private static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    static Component plain(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    static Component button(String label, NamedTextColor color, String command) {
        return Component.text(label, color)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.GRAY)));
    }

    /** Case de décor : invisible avec le pack, et sans infobulle. */
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
