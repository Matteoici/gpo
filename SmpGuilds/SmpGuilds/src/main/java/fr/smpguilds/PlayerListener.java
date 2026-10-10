package fr.smpguilds;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import static fr.smpguilds.Messages.ph;

/** Connexion / déconnexion / chat (saisie dans le chat + chat de guilde). */
public final class PlayerListener implements Listener {

    private final SmpGuilds plugin;

    PlayerListener(SmpGuilds plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        plugin.territory().remember(p);
        plugin.wars().onJoin(p);
        Guild g = plugin.guilds().of(p);
        if (g == null) return;
        g.names.put(p.getUniqueId(), p.getName()); // le pseudo a pu changer
        plugin.guilds().markDirty(g);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            for (Player o : g.online()) {
                if (!o.equals(p)) plugin.messages().send(o, "members.online", ph("player", p.getName()));
            }
            if (!g.applications.isEmpty() && plugin.guilds().has(g, p.getUniqueId(), GuildPerm.APPLICATIONS)) {
                plugin.messages().send(p, "applications.pending", ph("count", g.applications.size()));
            }
        }, 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        plugin.homes().cancel(p, false);
        plugin.input().forget(p.getUniqueId());
        plugin.territory().forget(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        if (plugin.input().handle(p, text)) {
            e.setCancelled(true);
            return;
        }
        GuildChat.Mode mode = plugin.chat().mode(p);
        if (mode == GuildChat.Mode.PUBLIC) return;
        e.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> plugin.chat().send(p, mode, text));
    }
}
