package fr.smpnocrystal;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Pas de crystal PvP ni d'anchor PvP :
 * - les cristaux de l'End ne se posent que dans l'End (pour réinvoquer le dragon), et leur explosion ne blesse pas les joueurs ;
 * - les ancres de réapparition ne s'utilisent que dans le Nether (là où elles n'explosent pas), et leur explosion ne blesse pas ;
 * - option : pareil pour les lits dans le Nether / l'End.
 */
public final class SmpNoCrystal extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final Map<UUID, Long> lastMsg = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
    }

    private boolean bypass(Player p) {
        return p != null && p.hasPermission("smpnocrystal.bypass");
    }

    /** Un message toutes les 2 s au maximum (sinon le clic droit maintenu spamme). */
    private void tell(Player p, String key) {
        long now = System.currentTimeMillis();
        Long last = lastMsg.get(p.getUniqueId());
        if (last != null && now - last < 2000) return;
        lastMsg.put(p.getUniqueId(), now);
        p.sendActionBar(MM.deserialize(getConfig().getString("messages." + key, "<red>Interdit.")));
    }

    // ================================================================= cristaux

    private boolean crystalAllowed(World w) {
        List<String> allowed = getConfig().getStringList("crystals.allowed-in");
        return allowed.stream().anyMatch(s -> s.equalsIgnoreCase(w.getEnvironment().name()));
    }

    /** Pose d'un cristal (joueur) hors des dimensions autorisées. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(EntityPlaceEvent e) {
        if (!(e.getEntity() instanceof EnderCrystal)) return;
        if (crystalAllowed(e.getEntity().getWorld()) || bypass(e.getPlayer())) return;
        e.setCancelled(true);
        if (e.getPlayer() != null) tell(e.getPlayer(), "crystal");
    }

    /** Filet de sécurité : le clic droit avec un cristal sur de l'obsidienne / bedrock. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onUseCrystal(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        ItemStack it = e.getItem();
        if (it == null || it.getType() != Material.END_CRYSTAL) return;
        Material m = e.getClickedBlock().getType();
        if (m != Material.OBSIDIAN && m != Material.BEDROCK) return;
        if (crystalAllowed(e.getClickedBlock().getWorld()) || bypass(e.getPlayer())) return;
        e.setCancelled(true);
        tell(e.getPlayer(), "crystal");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrystalDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        if (e.getDamager() instanceof EnderCrystal && getConfig().getBoolean("crystals.no-player-damage", true)) e.setCancelled(true);
    }

    // ================================================================= ancres et lits

    @EventHandler(priority = EventPriority.HIGH)
    public void onUseBlock(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        World.Environment env = b.getWorld().getEnvironment();
        Player p = e.getPlayer();
        if (b.getType() == Material.RESPAWN_ANCHOR && env != World.Environment.NETHER
                && getConfig().getBoolean("anchors.block-outside-nether", true) && !bypass(p)) {
            // accroupi avec un bloc en main : on peut quand même poser un bloc contre l'ancre
            if (p.isSneaking() && e.getItem() != null && e.getItem().getType().isBlock() && e.getItem().getType() != Material.GLOWSTONE) return;
            e.setCancelled(true);
            tell(p, "anchor");
            return;
        }
        if (Tag.BEDS.isTagged(b.getType()) && env != World.Environment.NORMAL
                && getConfig().getBoolean("beds.block-outside-overworld", false) && !bypass(p)) {
            if (p.isSneaking() && e.getItem() != null && e.getItem().getType().isBlock()) return;
            e.setCancelled(true);
            tell(p, "bed");
        }
    }

    /** Dégâts d'une explosion d'ancre ou de lit (filet de sécurité). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDamage(EntityDamageByBlockEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        BlockState st = e.getDamagerBlockState();
        Material m = st != null ? st.getType() : e.getDamager() != null ? e.getDamager().getType() : null;
        if (m == null) return;
        if (m == Material.RESPAWN_ANCHOR && getConfig().getBoolean("anchors.no-player-damage", true)) e.setCancelled(true);
        else if (Tag.BEDS.isTagged(m) && getConfig().getBoolean("beds.no-player-damage", false)) e.setCancelled(true);
    }

    // ================================================================= commande

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].toLowerCase(Locale.ROOT).equals("reload")) {
            reloadConfig();
            sender.sendMessage(MM.deserialize(getConfig().getString("messages.reloaded", "<green>OK")));
            return true;
        }
        sender.sendMessage(MM.deserialize("<gray>/nocrystal reload"));
        return true;
    }
}
