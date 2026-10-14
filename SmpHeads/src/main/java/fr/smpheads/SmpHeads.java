package fr.smpheads;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Quand un joueur est tué par un autre joueur, il lâche sa tête (avec son skin) parmi ses objets.
 * Anti-farm : le même tueur n'obtient pas deux fois la tête du même joueur en peu de temps.
 */
public final class SmpHeads extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    /** "tueur:victime" -> heure du dernier drop */
    private final Map<String, Long> lastDrop = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
    }

    private static Component mm(String s) {
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;
        if (victim.hasPermission("smpheads.nodrop")) return;
        if (getConfig().getStringList("disabled-worlds").contains(victim.getWorld().getName())) return;
        if (ThreadLocalRandom.current().nextDouble() * 100 >= getConfig().getDouble("chance", 100)) return;

        String key = killer.getUniqueId() + ":" + victim.getUniqueId();
        long now = System.currentTimeMillis();
        long cd = getConfig().getLong("same-victim-cooldown-minutes", 10) * 60_000L;
        Long last = lastDrop.get(key);
        if (last != null && now - last < cd) return;
        lastDrop.put(key, now);
        lastDrop.values().removeIf(t -> now - t > Math.max(cd, 60_000L));

        String v = MM.escapeTags(victim.getName()), k = MM.escapeTags(killer.getName());
        String date = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(new Date(now));

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        // le joueur est en ligne : son profil contient déjà son skin (aucune requête à Mojang)
        meta.setPlayerProfile(victim.getPlayerProfile());
        meta.displayName(mm(getConfig().getString("head.name", "<gold><b>Tête de <victim>").replace("<victim>", v).replace("<killer>", k)));
        List<Component> lore = new ArrayList<>();
        for (String line : getConfig().getStringList("head.lore")) {
            lore.add(mm(line.replace("<victim>", v).replace("<killer>", k).replace("<date>", date)));
        }
        meta.lore(lore);
        head.setItemMeta(meta);

        // keepInventory : on la fait tomber au sol à part, sinon elle part avec les objets du mort
        if (e.getKeepInventory()) victim.getWorld().dropItemNaturally(victim.getLocation(), head);
        else e.getDrops().add(head);

        String msg = getConfig().getString("broadcast", "");
        if (msg != null && !msg.isBlank()) Bukkit.broadcast(mm(msg.replace("<victim>", v).replace("<killer>", k)));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage(mm("<green>SmpHeads rechargé."));
            return true;
        }
        sender.sendMessage(mm("<gray>/smpheads reload"));
        return true;
    }
}
