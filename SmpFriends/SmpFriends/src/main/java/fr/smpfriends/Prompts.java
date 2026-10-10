package fr.smpfriends;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Questions posées dans le chat (« écris le pseudo… ») : le prochain message du joueur y répond. */
public final class Prompts {

    private record Prompt(Consumer<String> action, long expires) {}

    private static final long TIMEOUT_MS = 30_000;

    private final FriendsPlugin plugin;
    private final Map<UUID, Prompt> pending = new ConcurrentHashMap<>();

    Prompts(FriendsPlugin plugin) {
        this.plugin = plugin;
    }

    public void ask(Player p, String question, Consumer<String> action) {
        pending.put(p.getUniqueId(), new Prompt(action, System.currentTimeMillis() + TIMEOUT_MS));
        p.closeInventory();
        p.sendMessage(Text.msg(question, Text.ACCENT)
                .append(Component.text("  (écris « annuler » pour annuler)", NamedTextColor.DARK_GRAY)));
    }

    /** Appelé depuis le chat (thread asynchrone). Retourne true si le message répondait à une question. */
    boolean handle(Player p, String message) {
        Prompt prompt = pending.remove(p.getUniqueId());
        if (prompt == null) return false;
        if (System.currentTimeMillis() > prompt.expires()) return false;
        String text = message.trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (text.equalsIgnoreCase("annuler") || text.equalsIgnoreCase("cancel")) {
                p.sendMessage(Text.msg("Annulé.", NamedTextColor.GRAY));
            } else {
                prompt.action().accept(text);
            }
        });
        return true;
    }

    void forget(Player p) {
        pending.remove(p.getUniqueId());
    }
}
