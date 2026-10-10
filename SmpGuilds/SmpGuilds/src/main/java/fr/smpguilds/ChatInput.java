package fr.smpguilds;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Demande au joueur d'écrire une valeur dans le chat (nom, description, montant...). */
public final class ChatInput {

    private record Pending(Consumer<String> callback, long expires) {}

    private final SmpGuilds plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    ChatInput(SmpGuilds plugin) {
        this.plugin = plugin;
    }

    public void ask(Player p, String promptKey, Consumer<String> callback) {
        p.closeInventory();
        pending.put(p.getUniqueId(), new Pending(callback, System.currentTimeMillis() + 60_000));
        plugin.messages().send(p, promptKey);
        plugin.messages().send(p, "input.cancel-hint");
    }

    /** Appelé depuis le chat (thread asynchrone). Retourne true si le message a été consommé. */
    public boolean handle(Player p, String text) {
        Pending pd = pending.remove(p.getUniqueId());
        if (pd == null) return false;
        if (pd.expires() < System.currentTimeMillis()) return false;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (text.equalsIgnoreCase("annuler") || text.equalsIgnoreCase("cancel")) {
                plugin.messages().send(p, "input.cancelled");
            } else {
                pd.callback().accept(text.trim());
            }
        });
        return true;
    }

    public void forget(UUID player) {
        pending.remove(player);
    }
}
