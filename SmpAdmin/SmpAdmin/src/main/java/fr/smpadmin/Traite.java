package fr.smpadmin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * /traire : réservé au staff. Mode activé + clic droit sur un joueur avec un seau vide
 * = un seau de « Lait de <pseudo> » certifié authentique. Le staff est prévenu à chaque fois.
 */
final class Traite implements Listener, TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final SmpAdmin plugin;
    private final NamespacedKey key;
    private final Set<UUID> on = new HashSet<>();
    private final Map<UUID, Long> cd = new HashMap<>();

    Traite(SmpAdmin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "lait_de");
    }

    private static Component mm(String s) {
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static String pick(String... s) {
        return s[ThreadLocalRandom.current().nextInt(s.length)];
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player p) || !p.hasPermission("smpadmin.traire")) {
            s.sendMessage(mm("<red>Réservé au staff."));
            return true;
        }
        boolean want = a.length == 0 ? !on.contains(p.getUniqueId()) : a[0].equalsIgnoreCase("on");
        if (want) on.add(p.getUniqueId());
        else on.remove(p.getUniqueId());
        p.sendMessage(mm(want ? "<white>🥛 Mode traite <green>activé</green>. <gray>Clic droit sur un joueur avec un seau vide."
                : "<white>🥛 Mode traite <red>désactivé</red>."));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        return a.length == 1 ? List.of("on", "off") : List.of();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMilk(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !(e.getRightClicked() instanceof Player t)) return;
        Player p = e.getPlayer();
        if (!on.contains(p.getUniqueId()) || !p.hasPermission("smpadmin.traire")) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType() != Material.BUCKET) return;
        e.setCancelled(true);
        long now = System.currentTimeMillis();
        Long until = cd.get(t.getUniqueId());
        if (until != null && until > now) {
            p.sendActionBar(mm("<gray>" + t.getName() + " est à sec... encore " + ((until - now) / 1000 + 1) + " s 🐄"));
            return;
        }
        cd.put(t.getUniqueId(), now + 30_000);
        ItemStack milk = new ItemStack(Material.MILK_BUCKET);
        ItemMeta m = milk.getItemMeta();
        m.displayName(mm("<white><b>🥛 Lait de " + MM.escapeTags(t.getName()) + "</b>"));
        m.lore(List.of(
                mm("<green>✔ Certifié authentique"),
                mm("<gray>Trait par <white>" + MM.escapeTags(p.getName()) + "</white> le " + new SimpleDateFormat("dd/MM/yyyy à HH:mm", Locale.FRANCE).format(new Date())),
                mm("<dark_gray>" + pick("100 % naturel, sans additifs.", "Origine : Limitless.", "À consommer avec modération.", "Élevé en plein air.", "Édition limitée."))));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(key, PersistentDataType.STRING, t.getName());
        milk.setItemMeta(m);
        if (hand.getAmount() > 1) {
            hand.setAmount(hand.getAmount() - 1);
            for (ItemStack left : p.getInventory().addItem(milk).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        } else {
            p.getInventory().setItemInMainHand(milk);
        }
        p.playSound(p.getLocation(), Sound.ENTITY_COW_MILK, 1f, 1f);
        t.playSound(t.getLocation(), Sound.ENTITY_COW_AMBIENT, 1f, 1.2f);
        t.getWorld().spawnParticle(Particle.CLOUD, t.getLocation().add(0, 1, 0), 10, 0.3, 0.4, 0.3, 0.02);
        t.showTitle(Title.title(mm("<white>🐄 Meuh ?"), mm("<gray>" + MM.escapeTags(p.getName()) + " vient de te traire.")));
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.hasPermission("smpadmin.traire") || o.hasPermission("smpadmin.alerts"))
                o.sendMessage(mm("<dark_gray>[<white>🥛</white>]</dark_gray> <gray>" + MM.escapeTags(p.getName()) + " a trait <white>" + MM.escapeTags(t.getName()) + "</white>."));
        }
    }

    @EventHandler
    public void onDrink(PlayerItemConsumeEvent e) {
        ItemStack it = e.getItem();
        if (!it.hasItemMeta()) return;
        String of = it.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (of == null) return;
        Player p = e.getPlayer();
        String line = p.getName().equalsIgnoreCase(of)
                ? pick("boit son propre lait. On ne juge pas... enfin si.", "boit son propre lait. Circuit court.")
                : pick("a bu le lait certifié de", "s'est envoyé une gorgée du lait de", "déguste le lait authentique de") + " " + of + ".";
        for (Player o : p.getWorld().getPlayers()) {
            if (o.getLocation().distanceSquared(p.getLocation()) < 40 * 40) o.sendMessage(mm("<white>🥛 " + MM.escapeTags(p.getName()) + " <gray>" + MM.escapeTags(line)));
        }
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_BURP, 1f, 1f);
    }
}
