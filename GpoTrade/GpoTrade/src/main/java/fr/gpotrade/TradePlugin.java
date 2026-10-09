package fr.gpotrade;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TradePlugin extends JavaPlugin implements Listener {

    private record Request(UUID from, long time) {}

    private static final long REQUEST_TTL_MS = 30_000;

    private final Map<UUID, Request> requests = new HashMap<>();
    /** ConcurrentHashMap : lue depuis le thread du chat (asynchrone). */
    private final Map<UUID, TradeSession> sessions = new ConcurrentHashMap<>();
    private File dataFile;
    private YamlConfiguration data;
    private VaultHook vault;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            vault = new VaultHook();
            getLogger().info("Vault détecté : la monnaie est activée dans les échanges.");
        } else {
            getLogger().info("Vault absent : les échanges se font sans monnaie.");
        }

        getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "trades.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("trade").setExecutor(this::onTradeCommand);
        getCommand("trade").setTabCompleter((sender, cmd, label, args) -> {
            List<String> out = new ArrayList<>(List.of("accept", "decline", "open", "cancel"));
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            if (args.length == 1) out.removeIf(s -> !s.toLowerCase().startsWith(args[0].toLowerCase()));
            else out.clear();
            return out;
        });
    }

    @Override
    public void onDisable() {
        for (TradeSession s : new HashSet<>(sessions.values())) s.cancel("Échange annulé (arrêt du serveur).");
    }

    // ------------------------------------------------------------------ commandes

    private boolean onTradeCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Commande réservée aux joueurs.");
            return true;
        }
        if (args.length != 1) {
            p.sendMessage(msg("Usage : /trade <joueur>  (pendant un échange : /trade open | /trade cancel)",
                    NamedTextColor.RED));
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "accept" -> acceptRequest(p);
            case "decline" -> declineRequest(p);
            case "open" -> reopen(p);
            case "cancel" -> cancelTrade(p);
            default -> sendRequest(p, args[0]);
        }
        return true;
    }

    private void sendRequest(Player from, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || target.equals(from)) {
            from.sendMessage(msg("Joueur introuvable.", NamedTextColor.RED));
            return;
        }
        if (sessions.containsKey(from.getUniqueId()) || sessions.containsKey(target.getUniqueId())) {
            from.sendMessage(msg("L'un de vous est déjà en échange.", NamedTextColor.RED));
            return;
        }
        requests.put(target.getUniqueId(), new Request(from.getUniqueId(), System.currentTimeMillis()));
        from.sendMessage(msg("Demande envoyée à " + target.getName() + ".", NamedTextColor.GREEN));
        target.sendMessage(
                msg(from.getName() + " te propose un échange. ", NamedTextColor.YELLOW)
                        .append(Component.text("[ACCEPTER]", NamedTextColor.GREEN)
                                .clickEvent(ClickEvent.runCommand("/trade accept")))
                        .append(Component.text(" "))
                        .append(Component.text("[REFUSER]", NamedTextColor.RED)
                                .clickEvent(ClickEvent.runCommand("/trade decline"))));
    }

    private void acceptRequest(Player p) {
        Request r = requests.remove(p.getUniqueId());
        if (r == null || System.currentTimeMillis() - r.time() > REQUEST_TTL_MS) {
            p.sendMessage(msg("Aucune demande d'échange en attente.", NamedTextColor.RED));
            return;
        }
        Player other = Bukkit.getPlayer(r.from());
        if (other == null || sessions.containsKey(other.getUniqueId()) || sessions.containsKey(p.getUniqueId())) {
            p.sendMessage(msg("Impossible de démarrer l'échange.", NamedTextColor.RED));
            return;
        }
        TradeSession s = new TradeSession(this, other, p);
        sessions.put(other.getUniqueId(), s);
        sessions.put(p.getUniqueId(), s);
        s.open();
    }

    private void declineRequest(Player p) {
        Request r = requests.remove(p.getUniqueId());
        if (r == null) {
            p.sendMessage(msg("Aucune demande d'échange en attente.", NamedTextColor.RED));
            return;
        }
        p.sendMessage(msg("Demande refusée.", NamedTextColor.GRAY));
        Player from = Bukkit.getPlayer(r.from());
        if (from != null) from.sendMessage(msg(p.getName() + " a refusé ton échange.", NamedTextColor.RED));
    }

    private void reopen(Player p) {
        TradeSession s = sessions.get(p.getUniqueId());
        if (s == null) {
            p.sendMessage(msg("Tu n'es dans aucun échange.", NamedTextColor.RED));
            return;
        }
        s.reopen(s.indexOf(p));
    }

    private void cancelTrade(Player p) {
        TradeSession s = sessions.get(p.getUniqueId());
        if (s == null) {
            p.sendMessage(msg("Tu n'es dans aucun échange.", NamedTextColor.RED));
            return;
        }
        s.cancel(p.getName() + " a annulé l'échange.");
    }

    // ------------------------------------------------------------------ événements GUI

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof TradeHolder h)) return;

        TradeSession s = h.session();
        InventoryAction action = e.getAction();

        // Le double-clic "rassemble" les items de tout l'inventaire, y compris les nôtres : on bloque.
        if (action == InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true);
            return;
        }
        if (e.getClickedInventory() == null) return; // clic hors fenêtre : laissé tel quel

        if (e.getClickedInventory() == top) {
            int slot = e.getRawSlot();
            if (TradeSession.isAccept(slot)) {
                e.setCancelled(true);
                s.accept(h.index());
            } else if (TradeSession.isDecline(slot)) {
                e.setCancelled(true);
                s.cancel(s.player(h.index()).getName() + " a refusé l'échange.");
            } else if (slot == TradeSession.MONEY_OWN) {
                e.setCancelled(true);
                if (!moneyEnabled()) return;
                long step = moneyStep();
                switch (e.getClick()) {
                    case LEFT -> s.adjustMoney(h.index(), step, false);
                    case RIGHT -> s.adjustMoney(h.index(), -step, false);
                    case SHIFT_LEFT -> s.adjustMoney(h.index(), step * 10, false);
                    case SHIFT_RIGHT -> s.adjustMoney(h.index(), -step * 10, false);
                    case DROP, CONTROL_DROP -> s.adjustMoney(h.index(), 0, true);
                    default -> { }
                }
            } else if (TradeSession.isOwn(slot)) {
                s.touch(h.index()); // modification autorisée de notre propre grille
            } else {
                e.setCancelled(true); // décor, grille du partenaire, etc.
            }
        } else if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            // Shift-clic depuis l'inventaire du joueur : on place l'item nous-mêmes dans NOTRE grille uniquement.
            e.setCancelled(true);
            ItemStack it = e.getCurrentItem();
            if (it == null || it.getType().isAir()) return;
            int left = s.addToOffer(h.index(), it);
            if (left != it.getAmount()) {
                ItemStack rest = it.clone();
                rest.setAmount(left);
                e.setCurrentItem(left <= 0 ? null : rest);
                s.touch(h.index());
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof TradeHolder h)) return;
        for (int raw : e.getRawSlots()) {
            if (raw < top.getSize() && !TradeSession.isOwn(raw)) {
                e.setCancelled(true);
                return;
            }
        }
        h.session().touch(h.index());
    }

    /** Échap : l'échange est mis en pause (et non annulé) pour pouvoir écrire dans le chat. */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof TradeHolder h) {
            h.session().onClosed(h.index());
        }
    }

    /** Pendant un échange, ce que le joueur écrit va uniquement à son partenaire. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        TradeSession s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> s.chat(p, text));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        TradeSession s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) s.cancel("Échange annulé : un joueur s'est déconnecté.");
        requests.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ configuration / données

    VaultHook vault() {
        return vault;
    }

    /** Vrai si Vault est présent ET qu'un plugin d'économie (EssentialsX, etc.) est enregistré. */
    boolean moneyEnabled() {
        return vault != null && vault.available();
    }

    String symbol() {
        return getConfig().getString("currency-symbol", "₲");
    }

    long moneyStep() {
        return Math.max(1, getConfig().getLong("money-step", 100));
    }

    boolean usePack() {
        return getConfig().getBoolean("use-resource-pack", false);
    }

    int countdownSeconds() {
        return Math.max(2, Math.min(60, getConfig().getInt("countdown-seconds", 10)));
    }

    long pauseTimeoutMs() {
        return Math.max(10, getConfig().getLong("pause-timeout-seconds", 120)) * 1000L;
    }

    void unregister(TradeSession s) {
        sessions.remove(s.player(0).getUniqueId());
        sessions.remove(s.player(1).getUniqueId());
    }

    int trades(UUID id) {
        return data.getInt("trades." + id, 0);
    }

    void addTrade(UUID id) {
        data.set("trades." + id, trades(id) + 1);
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Impossible de sauvegarder trades.yml : " + ex.getMessage());
        }
    }

    private static Component msg(String text, NamedTextColor color) {
        return Component.text(text, color);
    }
}
