package fr.smpmarket;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Demande au joueur d'écrire une valeur dans le chat (prix, nom, montant...). */
public final class ChatInput {

    private record Pending(Consumer<String> callback, long expires) {
    }

    private final SmpMarket plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    ChatInput(SmpMarket plugin) {
        this.plugin = plugin;
    }

    public void ask(Player p, String promptKey, Consumer<String> callback) {
        p.closeInventory();
        pending.put(p.getUniqueId(), new Pending(callback, System.currentTimeMillis() + 120_000));
        plugin.texts().send(p, promptKey);
        plugin.texts().send(p, "input.cancel-hint");
    }

    /** Appelé depuis le chat (thread asynchrone). true = message utilisé. */
    public boolean handle(Player p, String text) {
        Pending pd = pending.remove(p.getUniqueId());
        if (pd == null || pd.expires() < System.currentTimeMillis()) return false;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (text.equalsIgnoreCase("annuler") || text.equalsIgnoreCase("cancel")) plugin.texts().send(p, "input.cancelled");
            else pd.callback().accept(text.trim());
        });
        return true;
    }

    public void forget(UUID id) {
        pending.remove(id);
    }
}
