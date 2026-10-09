package fr.gpotrade;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Point d'entrée : configuration, demandes d'échange, sessions en cours et avis. */
public final class TradePlugin extends JavaPlugin {

    private record Request(UUID from, long time) {}

    private record PendingReview(UUID target, long time) {}

    private static final long REVIEW_TTL_MS = 5 * 60_000;

    /** Lue depuis le thread du chat (asynchrone) : ConcurrentHashMap. */
    private final Map<UUID, TradeSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Request> requests = new HashMap<>();
    /** clé = UUID du joueur qui note + ":" + id de l'échange */
    private final Map<String, PendingReview> pendingReviews = new HashMap<>();

    private PlayerData data;
    private TradeLog tradeLog;
    private PackHost pack;
    private VaultHook vault;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getDataFolder().mkdirs();
        data = new PlayerData(this);
        tradeLog = new TradeLog(this);
        pack = new PackHost(this);
        pack.start();

        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            vault = new VaultHook();
            getLogger().info("Vault détecté : la monnaie est activée dans les échanges.");
        } else {
            getLogger().info("Vault absent : les échanges se font sans monnaie.");
        }

        TradeCommand command = new TradeCommand(this);
        PluginCommand cmd = getCommand("trade");
        if (cmd != null) {
            cmd.setExecutor(command);
            cmd.setTabCompleter(command);
        }
        getServer().getPluginManager().registerEvents(new TradeListener(this), this);

        // joueurs déjà connectés (ex. après un /reload) : on leur envoie le pack
        for (Player p : Bukkit.getOnlinePlayers()) pack.send(p);
    }

    @Override
    public void onDisable() {
        for (TradeSession s : new HashSet<>(sessions.values())) s.cancel("Échange annulé (arrêt du serveur).");
        if (pack != null) pack.stop();
    }

    void reload() {
        reloadConfig();
    }

    // =============================================================== demandes d'échange

    void request(Player from, Player target) {
        if (target.equals(from)) {
            from.sendMessage(msg("Tu ne peux pas échanger avec toi-même.", NamedTextColor.RED));
            return;
        }
        if (inTrade(from) || inTrade(target)) {
            from.sendMessage(msg("L'un de vous est déjà en échange.", NamedTextColor.RED));
            return;
        }
        int max = getConfig().getInt("max-distance", 0);
        if (max > 0 && (!from.getWorld().equals(target.getWorld())
                || from.getLocation().distanceSquared(target.getLocation()) > (double) max * max)) {
            from.sendMessage(msg("Tu es trop loin de " + target.getName() + " (max " + max + " blocs).", NamedTextColor.RED));
            return;
        }
        // si l'autre m'avait déjà fait une demande, on démarre directement
        Request reverse = requests.get(from.getUniqueId());
        if (reverse != null && reverse.from().equals(target.getUniqueId()) && !expired(reverse)) {
            requests.remove(from.getUniqueId());
            start(target, from);
            return;
        }
        Request existing = requests.get(target.getUniqueId());
        if (existing != null && existing.from().equals(from.getUniqueId()) && !expired(existing)) {
            from.sendMessage(msg("Demande déjà envoyée, patiente un peu.", NamedTextColor.GRAY));
            return;
        }
        requests.put(target.getUniqueId(), new Request(from.getUniqueId(), System.currentTimeMillis()));
        from.sendMessage(msg("Demande d'échange envoyée à " + target.getName() + ".", NamedTextColor.GREEN));
        target.sendMessage(msg("⇄ " + from.getName() + " te propose un échange. ", NamedTextColor.YELLOW)
                .append(TradeSession.button("[ACCEPTER]", NamedTextColor.GREEN, "/trade accept"))
                .append(Component.text(" "))
                .append(TradeSession.button("[REFUSER]", NamedTextColor.RED, "/trade decline")));
        target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.2f);
    }

    void acceptRequest(Player p) {
        Request r = requests.remove(p.getUniqueId());
        if (r == null || expired(r)) {
            p.sendMessage(msg("Aucune demande d'échange en attente.", NamedTextColor.RED));
            return;
        }
        Player other = Bukkit.getPlayer(r.from());
        if (other == null || inTrade(other) || inTrade(p)) {
            p.sendMessage(msg("Impossible de démarrer l'échange.", NamedTextColor.RED));
            return;
        }
        start(other, p);
    }

    void declineRequest(Player p) {
        Request r = requests.remove(p.getUniqueId());
        if (r == null) {
            p.sendMessage(msg("Aucune demande d'échange en attente.", NamedTextColor.RED));
            return;
        }
        p.sendMessage(msg("Demande refusée.", NamedTextColor.GRAY));
        Player from = Bukkit.getPlayer(r.from());
        if (from != null) from.sendMessage(msg(p.getName() + " a refusé ton échange.", NamedTextColor.RED));
    }

    private boolean expired(Request r) {
        return System.currentTimeMillis() - r.time() > getConfig().getInt("request-timeout-seconds", 30) * 1000L;
    }

    private void start(Player a, Player b) {
        TradeSession s = new TradeSession(this, a, b);
        sessions.put(a.getUniqueId(), s);
        sessions.put(b.getUniqueId(), s);
        s.open();
    }

    void forgetRequests(Player p) {
        requests.remove(p.getUniqueId());
        requests.values().removeIf(r -> r.from().equals(p.getUniqueId()));
    }

    TradeSession session(Player p) {
        return sessions.get(p.getUniqueId());
    }

    boolean inTrade(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    void unregister(TradeSession s) {
        sessions.remove(s.player(0).getUniqueId());
        sessions.remove(s.player(1).getUniqueId());
    }

    // =============================================================== avis

    void offerReview(Player rater, Player target, String tradeId) {
        if (!reviewsEnabled()) return;
        cleanupReviews();
        pendingReviews.put(rater.getUniqueId() + ":" + tradeId, new PendingReview(target.getUniqueId(), System.currentTimeMillis()));
        Component line = msg("Note " + target.getName() + " : ", NamedTextColor.GRAY);
        for (int n = 1; n <= 5; n++) {
            line = line.append(TradeSession.button("★".repeat(n), NamedTextColor.YELLOW, "/trade rate " + tradeId + " " + n))
                    .append(Component.text(" "));
        }
        rater.sendMessage(line);
    }

    void rate(Player rater, String tradeId, int stars) {
        PendingReview pr = pendingReviews.remove(rater.getUniqueId() + ":" + tradeId.toUpperCase());
        if (pr == null || System.currentTimeMillis() - pr.time() > REVIEW_TTL_MS) {
            rater.sendMessage(msg("Cet avis n'est plus disponible.", NamedTextColor.RED));
            return;
        }
        data.addReview(pr.target(), Math.max(1, Math.min(5, stars)));
        rater.sendMessage(msg("Merci pour ton avis (" + "★".repeat(stars) + ") !", NamedTextColor.GREEN));
    }

    private void cleanupReviews() {
        long now = System.currentTimeMillis();
        Iterator<PendingReview> it = pendingReviews.values().iterator();
        while (it.hasNext()) if (now - it.next().time() > REVIEW_TTL_MS) it.remove();
    }

    // =============================================================== accès / configuration

    PlayerData data() {
        return data;
    }

    TradeLog tradeLog() {
        return tradeLog;
    }

    PackHost pack() {
        return pack;
    }

    VaultHook vault() {
        return vault;
    }

    /** Vrai si Vault est présent ET qu'un plugin d'économie est enregistré. */
    boolean moneyEnabled() {
        return vault != null && vault.available();
    }

    boolean reviewsEnabled() {
        return getConfig().getBoolean("reviews", true);
    }

    String symbol() {
        return getConfig().getString("currency-symbol", "₲");
    }

    long moneyStep() {
        return Math.max(1, getConfig().getLong("money-step", 100));
    }

    int countdownSeconds() {
        return Math.max(2, Math.min(60, getConfig().getInt("countdown-seconds", 10)));
    }

    long antiScamDelayMs() {
        return Math.max(0, getConfig().getLong("anti-scam-delay-ms", 1500));
    }

    long pauseTimeoutMs() {
        return Math.max(10, getConfig().getLong("pause-timeout-seconds", 120)) * 1000L;
    }

    boolean sneakClickRequest() {
        return getConfig().getBoolean("sneak-click-request", true);
    }

    static Component msg(String text, NamedTextColor color) {
        return Component.text(text, color);
    }
}
