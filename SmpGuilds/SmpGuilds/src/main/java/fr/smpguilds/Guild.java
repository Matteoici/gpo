package fr.smpguilds;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Une guilde et toutes ses données. */
public final class Guild {

    public record Application(String message, long time) {}

    public final UUID id;
    public String name;
    public String tag;
    public String description = "";
    public NamedTextColor color = NamedTextColor.GOLD;
    public ItemStack banner;
    public boolean open;
    public long created = System.currentTimeMillis();
    public UUID leader;
    public final Map<UUID, Role> members = new LinkedHashMap<>();
    public final Map<UUID, String> names = new HashMap<>();
    public final Map<UUID, MemberStats> stats = new HashMap<>();
    public Location home;
    public double bank;
    public long xp;
    public int kills;
    public int deaths;
    public int warsWon;
    public int warsLost;
    public long lastUpkeep = System.currentTimeMillis();
    public final Set<ChunkPos> claims = new LinkedHashSet<>();
    public final Set<UUID> allies = new LinkedHashSet<>();
    public final Map<UUID, Application> applications = new LinkedHashMap<>();
    public ItemStack[] vault = new ItemStack[54];
    public final List<String> log = new ArrayList<>();

    public Guild(UUID id) {
        this.id = id;
    }

    public Role role(UUID player) {
        return members.get(player);
    }

    public boolean isMember(UUID player) {
        return members.containsKey(player);
    }

    public MemberStats stats(UUID player) {
        return stats.computeIfAbsent(player, k -> new MemberStats());
    }

    public String nameOf(UUID player) {
        String n = names.get(player);
        if (n != null) return n;
        n = Bukkit.getOfflinePlayer(player).getName();
        return n != null ? n : player.toString().substring(0, 8);
    }

    public List<Player> online() {
        List<Player> out = new ArrayList<>();
        for (UUID u : members.keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) out.add(p);
        }
        return out;
    }

    public void broadcast(Component c) {
        for (Player p : online()) p.sendMessage(c);
    }

    public Component tagComponent() {
        return Component.text("[" + tag + "]", color);
    }

    public Component nameComponent() {
        return Component.text(name, color);
    }

    public double kdr() {
        return deaths == 0 ? kills : Math.round(kills * 100.0 / deaths) / 100.0;
    }
}
