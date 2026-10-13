package fr.smpadmin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * /refund <joueur> [n] : rend au joueur tout son stuff tel qu'il était à sa mort
 * (inventaire, armure, seconde main, niveaux d'XP). Les 5 dernières morts sont gardées.
 * Une mort déjà remboursée ne peut pas l'être deux fois (sauf avec "force").
 * Si le joueur est hors ligne, il le reçoit à sa prochaine connexion.
 */
final class Refunds implements Listener, TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int KEEP = 5;

    private final SmpAdmin plugin;
    private final File file;
    private YamlConfiguration data;

    Refunds(SmpAdmin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "deaths.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    private static Component mm(String s) {
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("deaths.yml : " + e.getMessage());
        }
    }

    // ================================================================= enregistrement des morts

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (e.getKeepInventory()) return;
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getContents();
        boolean empty = true;
        for (ItemStack it : contents) if (it != null && !it.getType().isAir()) empty = false;
        if (empty && p.getLevel() == 0) return;
        String base = "players." + p.getUniqueId();
        // décale les anciennes morts (1 = la plus récente)
        for (int i = KEEP; i >= 2; i--) {
            ConfigurationSection prev = data.getConfigurationSection(base + "." + (i - 1));
            data.set(base + "." + i, prev == null ? null : prev.getValues(true));
        }
        String k = base + ".1";
        data.set(k, null);
        data.set(k + ".time", System.currentTimeMillis());
        data.set(k + ".world", p.getWorld().getName());
        data.set(k + ".x", p.getLocation().getBlockX());
        data.set(k + ".y", p.getLocation().getBlockY());
        data.set(k + ".z", p.getLocation().getBlockZ());
        Component msg = e.deathMessage();
        data.set(k + ".cause", msg == null ? "?" : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(msg));
        data.set(k + ".level", p.getLevel());
        data.set(k + ".exp", (double) p.getExp());
        data.set(k + ".refunded", false);
        List<String> items = new ArrayList<>();
        for (ItemStack it : contents) {
            items.add(it == null || it.getType().isAir() ? "" : Base64.getEncoder().encodeToString(it.serializeAsBytes()));
        }
        data.set(k + ".items", items);
        data.set("names." + p.getName().toLowerCase(Locale.ROOT), p.getUniqueId().toString());
        save();
    }

    // ================================================================= commande

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("smpadmin.refund")) {
            s.sendMessage(mm("<red>Tu n'as pas la permission."));
            return true;
        }
        if (a.length == 0) {
            s.sendMessage(mm("<gold>/refund <joueur></gold> <gray>- rend le stuff de sa dernière mort"));
            s.sendMessage(mm("<gold>/refund <joueur> liste</gold> <gray>- ses 5 dernières morts"));
            s.sendMessage(mm("<gold>/refund <joueur> <1-5> [force]</gold> <gray>- rend une mort précise (force = même si déjà rendue)"));
            return true;
        }
        UUID id = uuidOf(a[0]);
        if (id == null) {
            s.sendMessage(mm("<red>Aucune mort enregistrée pour <white>" + a[0] + "</white>."));
            return true;
        }
        String base = "players." + id;
        if (a.length >= 2 && (a[1].equalsIgnoreCase("liste") || a[1].equalsIgnoreCase("list"))) {
            s.sendMessage(mm("<gold>Morts de <white>" + a[0] + "</white> :"));
            SimpleDateFormat f = new SimpleDateFormat("dd/MM HH:mm");
            for (int i = 1; i <= KEEP; i++) {
                ConfigurationSection d = data.getConfigurationSection(base + "." + i);
                if (d == null) continue;
                int count = 0;
                for (String it : d.getStringList("items")) if (!it.isEmpty()) count++;
                String state = d.getBoolean("refunded") ? "<green>✔ rendu" : "<yellow>pas rendu";
                s.sendMessage(mm("<gray>" + i + ". <white>" + f.format(new Date(d.getLong("time"))) + " <gray>- " + count + " objets, niv. "
                        + d.getInt("level") + " - " + state + " <dark_gray>(" + d.getString("cause") + ")"));
            }
            return true;
        }
        int n = 1;
        boolean force = false;
        for (int i = 1; i < a.length; i++) {
            if (a[i].equalsIgnoreCase("force")) force = true;
            else {
                try {
                    n = Math.max(1, Math.min(KEEP, Integer.parseInt(a[i])));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        String k = base + "." + n;
        ConfigurationSection d = data.getConfigurationSection(k);
        if (d == null) {
            s.sendMessage(mm("<red>Pas de mort n°" + n + " pour <white>" + a[0] + "</white>."));
            return true;
        }
        if (d.getBoolean("refunded") && !force) {
            s.sendMessage(mm("<yellow>Cette mort a déjà été rendue. <gray>Ajoute <white>force</white> pour la rendre quand même."));
            return true;
        }
        Player target = Bukkit.getPlayer(id);
        data.set(k + ".refunded", true);
        if (target == null) {
            data.set("pending." + id, k);
            save();
            s.sendMessage(mm("<green>" + a[0] + " est hors ligne : il recevra son stuff à sa prochaine connexion."));
            return true;
        }
        int given = give(target, d);
        save();
        s.sendMessage(mm("<green>Stuff rendu à <white>" + target.getName() + "</white> <gray>(" + given + " objets, niveau " + d.getInt("level") + ")."));
        return true;
    }

    /** Rend le stuff : chaque objet reprend sa case si elle est libre, sinon va ailleurs, sinon tombe à ses pieds. */
    private int give(Player p, ConfigurationSection d) {
        List<String> items = d.getStringList("items");
        PlayerInventory inv = p.getInventory();
        List<ItemStack> extra = new ArrayList<>();
        int n = 0;
        for (int i = 0; i < items.size(); i++) {
            String b64 = items.get(i);
            if (b64.isEmpty()) continue;
            ItemStack it;
            try {
                it = ItemStack.deserializeBytes(Base64.getDecoder().decode(b64));
            } catch (RuntimeException ex) {
                continue;
            }
            n++;
            ItemStack cur = i < inv.getSize() ? inv.getItem(i) : null;
            if (i < inv.getSize() && (cur == null || cur.getType().isAir())) inv.setItem(i, it);
            else extra.add(it);
        }
        for (ItemStack it : extra) {
            for (ItemStack left : inv.addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
        int lvl = d.getInt("level");
        if (lvl > p.getLevel()) {
            p.setLevel(lvl);
            p.setExp((float) d.getDouble("exp"));
        }
        p.sendMessage(mm("<green>✔ Ton stuff de ta mort a été rendu par le staff."));
        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        return n;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String k = data.getString("pending." + p.getUniqueId());
        data.set("names." + p.getName().toLowerCase(Locale.ROOT), p.getUniqueId().toString());
        if (k == null) return;
        ConfigurationSection d = data.getConfigurationSection(k);
        data.set("pending." + p.getUniqueId(), null);
        save();
        if (d != null) Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) give(p, d);
        }, 40L);
    }

    private UUID uuidOf(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        String s = data.getString("names." + name.toLowerCase(Locale.ROOT));
        if (s != null) return UUID.fromString(s);
        OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(name);
        return op == null ? null : op.getUniqueId();
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        else if (a.length == 2) out.addAll(List.of("liste", "1", "2", "3", "4", "5"));
        else if (a.length == 3) out.add("force");
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
