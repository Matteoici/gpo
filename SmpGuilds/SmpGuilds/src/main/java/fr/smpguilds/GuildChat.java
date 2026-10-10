package fr.smpguilds;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static fr.smpguilds.Messages.comp;
import static fr.smpguilds.Messages.guild;
import static fr.smpguilds.Messages.ph;

/** Chat de guilde (/gc) et chat des alliés (/ac), en message unique ou en mode permanent. */
public final class GuildChat {

    public enum Mode { PUBLIC, GUILD, ALLY }

    private final SmpGuilds plugin;
    private final Map<UUID, Mode> modes = new ConcurrentHashMap<>();

    GuildChat(SmpGuilds plugin) {
        this.plugin = plugin;
    }

    public Mode mode(Player p) {
        return modes.getOrDefault(p.getUniqueId(), Mode.PUBLIC);
    }

    public void toggle(Player p, Mode target) {
        Mode now = mode(p) == target ? Mode.PUBLIC : target;
        if (now == Mode.PUBLIC) modes.remove(p.getUniqueId());
        else modes.put(p.getUniqueId(), now);
        plugin.messages().send(p, "chat.mode-" + now.name().toLowerCase());
    }

    public void forget(UUID player) {
        modes.remove(player);
    }

    public void send(Player p, Mode mode, String text) {
        Guild g = plugin.guilds().of(p);
        if (g == null) {
            plugin.messages().send(p, "error.no-guild");
            modes.remove(p.getUniqueId());
            return;
        }
        Messages m = plugin.messages();
        Component role = m.get("role." + g.role(p.getUniqueId()).key());
        if (mode == Mode.GUILD) {
            Component line = m.get("chat.guild-format", guild(g), comp("role", role), ph("player", p.getName()), ph("message", text));
            g.broadcast(line);
            spy(line);
        } else {
            Component line = m.get("chat.ally-format", guild(g), comp("role", role), ph("player", p.getName()), ph("message", text));
            g.broadcast(line);
            for (UUID id : g.allies) {
                Guild a = plugin.guilds().get(id);
                if (a != null) a.broadcast(line);
            }
            spy(line);
        }
    }

    /** Les admins qui ont activé l'espion voient les chats de guilde. */
    private void spy(Component line) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.isSpying(p) && plugin.guilds().of(p) == null) p.sendMessage(plugin.messages().get("chat.spy-prefix").append(line));
        }
    }
}
