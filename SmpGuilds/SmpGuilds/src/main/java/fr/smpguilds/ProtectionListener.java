package fr.smpguilds;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static fr.smpguilds.Messages.guild;

/** Protection des terrains : blocs, conteneurs, portes, seaux, explosions, pistons, animaux, cadres. */
public final class ProtectionListener implements Listener {

    private static final Set<Material> USABLE = Set.of(Material.LEVER, Material.REPEATER, Material.COMPARATOR,
            Material.NOTE_BLOCK, Material.JUKEBOX, Material.DAYLIGHT_DETECTOR, Material.CAKE, Material.RESPAWN_ANCHOR,
            Material.BEACON, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.COMPOSTER, Material.LECTERN,
            Material.CHISELED_BOOKSHELF, Material.DECORATED_POT, Material.FLOWER_POT);

    private final SmpGuilds plugin;
    private final Map<UUID, Long> lastWarning = new HashMap<>();

    ProtectionListener(SmpGuilds plugin) {
        this.plugin = plugin;
    }

    /** Le joueur peut-il modifier cet endroit ? */
    public boolean canBuild(Player p, Location loc) {
        Guild owner = plugin.guilds().at(loc);
        if (owner == null) return true;
        if (plugin.isBypassing(p)) return true;
        Guild mine = plugin.guilds().of(p);
        if (mine == owner) return plugin.guilds().has(owner, p.getUniqueId(), GuildPerm.BUILD);
        // les alliés : seulement si les officiers / le chef de la guilde l'ont autorisé
        if (mine != null && owner.allies.contains(mine.id) && owner.alliesAccess) return true;
        return mine != null && plugin.settings().b("war.allow-raiding", false) && plugin.wars().atWar(mine, owner);
    }

    private boolean deny(Player p, Location loc) {
        if (canBuild(p, loc)) return false;
        long now = System.currentTimeMillis();
        Long last = lastWarning.get(p.getUniqueId());
        if (last == null || now - last > 1500) {
            lastWarning.put(p.getUniqueId(), now);
            p.sendActionBar(plugin.messages().get("protection.denied", guild(plugin.guilds().at(loc))));
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (deny(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (deny(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (deny(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (deny(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null) return;
        if (e.getAction() == Action.PHYSICAL) {
            // piétiner les cultures / plaques de pression
            if ((b.getType() == Material.FARMLAND || Tag.PRESSURE_PLATES.isTagged(b.getType()))
                    && !canBuild(e.getPlayer(), b.getLocation())) e.setCancelled(true);
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (plugin.guilds().at(b.getLocation()) == null) return;
        Material t = b.getType();
        boolean usable = USABLE.contains(t) || Tag.DOORS.isTagged(t) || Tag.TRAPDOORS.isTagged(t)
                || Tag.FENCE_GATES.isTagged(t) || Tag.BUTTONS.isTagged(t) || Tag.BEDS.isTagged(t)
                || Tag.ANVIL.isTagged(t) || Tag.SIGNS.isTagged(t) || b.getState(false) instanceof InventoryHolder;
        if (usable && deny(e.getPlayer(), b.getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Entity t = e.getRightClicked();
        if (t instanceof Hanging || t instanceof ArmorStand || (t instanceof Animals && plugin.settings().b("claims.protect-animals", true))) {
            if (t instanceof Villager) return;
            if (deny(e.getPlayer(), t.getLocation())) e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (deny(e.getPlayer(), e.getRightClicked().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        Player p = attacker(e.getRemover());
        if (p != null && deny(p, e.getEntity().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageEntity(EntityDamageByEntityEvent e) {
        Entity victim = e.getEntity();
        if (victim instanceof Player) return; // géré par CombatListener
        boolean protectedEntity = victim instanceof Hanging || victim instanceof ArmorStand
                || (victim instanceof Animals && plugin.settings().b("claims.protect-animals", true));
        if (!protectedEntity) return;
        Player p = attacker(e.getDamager());
        if (p != null && deny(p, victim.getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (plugin.settings().b("claims.block-explosions", true)) strip(e.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (plugin.settings().b("claims.block-explosions", true)) strip(e.blockList());
    }

    private void strip(List<Block> blocks) {
        blocks.removeIf(b -> plugin.guilds().at(b.getLocation()) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (crossesBorder(e.getBlock(), e.getBlocks(), e.getDirection().getModX(), e.getDirection().getModZ())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (crossesBorder(e.getBlock(), e.getBlocks(), 0, 0)) e.setCancelled(true);
    }

    /** Un piston ne peut pas déplacer de blocs dans (ou depuis) le terrain d'une autre guilde. */
    private boolean crossesBorder(Block piston, List<Block> moved, int dx, int dz) {
        Guild from = plugin.guilds().at(piston.getLocation());
        for (Block b : moved) {
            if (plugin.guilds().at(b.getLocation()) != from) return true;
            if (plugin.guilds().at(b.getLocation().add(dx, 0, dz)) != from) return true;
        }
        return false;
    }

    static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }
}
