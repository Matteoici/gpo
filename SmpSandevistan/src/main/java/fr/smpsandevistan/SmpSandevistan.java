package fr.smpsandevistan;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sandevistan : dans un rayon autour du joueur, le temps s'arrête (joueurs, monstres, flèches, TNT, objets...).
 * Lui se déplace à toute vitesse, laisse des images rémanentes, et ses coups tombent tous quand le temps reprend.
 */
public final class SmpSandevistan extends JavaPlugin implements Listener, TabExecutor {

    static final MiniMessage MM = MiniMessage.miniMessage();

    NamespacedKey itemKey, freezeKey, attackKey;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<Session, Long> durations = new HashMap<>();
    private final Set<UUID> snapping = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "implant");
        freezeKey = new NamespacedKey(this, "freeze");
        attackKey = new NamespacedKey(this, "attack_speed");
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("sandevistan");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        for (Player p : getServer().getOnlinePlayers()) cleanup(p);
    }

    @Override
    public void onDisable() {
        for (Session s : new ArrayList<>(sessions.values())) end(s);
    }

    Component mm(String s) {
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private void send(CommandSender to, String key, String... repl) {
        String s = getConfig().getString("messages." + key, key);
        for (int i = 0; i + 1 < repl.length; i += 2) s = s.replace("<" + repl[i] + ">", repl[i + 1]);
        to.sendMessage(mm(getConfig().getString("messages.prefix", "") + s));
    }

    // ================================================================= sessions

    boolean isUser(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    long durationMs(Session s) {
        return durations.getOrDefault(s, 10_000L);
    }

    /** L'entité est-elle déjà figée par le Sandevistan d'un autre joueur ? */
    boolean frozenElsewhere(Entity e, Session self) {
        for (Session s : sessions.values()) if (s != self && s.isFrozen(e)) return true;
        return false;
    }

    private Session freezer(Entity e) {
        for (Session s : sessions.values()) if (s.isFrozen(e)) return s;
        return null;
    }

    private boolean isImage(Entity e) {
        for (Session s : sessions.values()) if (s.isImage(e)) return true;
        return false;
    }

    private void toggle(Player p, Integer seconds) {
        Session s = sessions.get(p.getUniqueId());
        if (s != null) {
            end(s);
            return;
        }
        if (!p.hasPermission("smpsandevistan.nocooldown")) {
            long left = cooldowns.getOrDefault(p.getUniqueId(), 0L) - System.currentTimeMillis();
            if (left > 0) {
                send(p, "cooldown", "s", String.valueOf((left + 999) / 1000));
                return;
            }
        }
        int max = Math.max(1, getConfig().getInt("max-duration-seconds", 60));
        int sec = Math.max(1, Math.min(max, seconds != null ? seconds : getConfig().getInt("duration-seconds", 10)));
        Session n = new Session(this, p, sec);
        sessions.put(p.getUniqueId(), n);
        durations.put(n, sec * 1000L);
        int count;
        try {
            count = n.start();
        } catch (RuntimeException ex) {
            getLogger().warning("Sandevistan : " + ex);
            end(n);
            return;
        }
        send(p, "on", "n", String.valueOf(count), "r", String.valueOf(n.radius));
    }

    void end(Session s) {
        if (sessions.get(s.user.getUniqueId()) != s) return;
        sessions.remove(s.user.getUniqueId());
        durations.remove(s);
        int hits = s.stop();
        cooldowns.put(s.user.getUniqueId(), System.currentTimeMillis() + getConfig().getLong("cooldown-seconds", 20) * 1000L);
        if (s.user.isOnline()) send(s.user, "off", "hits", String.valueOf(hits));
    }

    /** Remet une entité figée à sa place sans que notre propre blocage de téléportation l'en empêche. */
    void snapBack(Entity e, Location to) {
        snapping.add(e.getUniqueId());
        try {
            e.teleport(to);
        } finally {
            snapping.remove(e.getUniqueId());
        }
    }

    /** Un joueur qui se reconnecte après un arrêt brutal ne doit pas rester figé. */
    private void cleanup(Player p) {
        Session.removeModifier(p, Attribute.MOVEMENT_SPEED, freezeKey);
        Session.removeModifier(p, Attribute.JUMP_STRENGTH, freezeKey);
        Session.removeModifier(p, Attribute.GRAVITY, freezeKey);
        Session.removeModifier(p, Attribute.ATTACK_SPEED, attackKey);
        if (!p.hasGravity()) p.setGravity(true);
    }

    // ================================================================= implant

    private ItemStack implant() {
        ItemStack it = new ItemStack(Material.ECHO_SHARD);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(mm("<gradient:#00ff9c:#00e5ff:#ff2bd6><b>Implant Sandevistan</b></gradient>"));
        meta.lore(List.of(mm("<gray>Clic droit : le temps s'arrête autour de toi."),
                mm("<gray>Clic droit encore : il reprend.")));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    private boolean isImplant(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUseImplant(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isImplant(e.getItem())) return;
        e.setCancelled(true);
        if (!e.getPlayer().hasPermission("smpsandevistan.use")) return;
        toggle(e.getPlayer(), null);
    }

    // ================================================================= commande

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            send(sender, "reload");
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("En jeu seulement.");
            return true;
        }
        if (a.length > 0 && a[0].equalsIgnoreCase("item")) {
            p.getInventory().addItem(implant()).values().forEach(r -> p.getWorld().dropItemNaturally(p.getLocation(), r));
            send(p, "item");
            return true;
        }
        Integer sec = null;
        if (a.length > 0) {
            try {
                sec = Integer.parseInt(a[0].replace("s", ""));
            } catch (NumberFormatException ignored) {
            }
        }
        toggle(p, sec);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length != 1) return List.of();
        List<String> out = new ArrayList<>(List.of("item", "reload", "5", "10", "20", "30"));
        out.removeIf(x -> !x.startsWith(a[0].toLowerCase()));
        return out;
    }

    // ================================================================= le temps est figé

    private boolean frozenPlayer(Player p) {
        return freezer(p) != null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent e) {
        Session s = freezer(e.getPlayer());
        if (s == null) return;
        Location from = e.getFrom(), to = e.getTo();
        boolean lockHead = getConfig().getBoolean("freeze-camera", true);
        boolean moved = from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ();
        boolean turned = from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch();
        if (!moved && !(lockHead && turned)) return;
        Location spot = s.frozenSpot(e.getPlayer());
        Location back = (spot != null ? spot : from).clone();
        if (!lockHead) {
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
        }
        e.setTo(back);
    }

    // ================================================================= commandes de l'utilisateur pendant l'effet

    /** Touche F (changer de main) : dash. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDash(org.bukkit.event.player.PlayerSwapHandItemsEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null || !getConfig().getBoolean("dash.enabled", true)) return;
        e.setCancelled(true);
        s.dash();
    }

    /** Coup dans le vide près d'un projectile figé : il est renvoyé. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(org.bukkit.event.player.PlayerAnimationEvent e) {
        if (e.getAnimationType() != org.bukkit.event.player.PlayerAnimationType.ARM_SWING) return;
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null && getConfig().getBoolean("deflect.enabled", true)) s.deflect();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleport(PlayerTeleportEvent e) {
        if (snapping.contains(e.getPlayer().getUniqueId())) return;
        if (frozenPlayer(e.getPlayer())) e.setCancelled(true); // ni /spawn, ni perle, ni /home pour s'enfuir
    }

    /** Tes coups sur une cible figée sont mis en attente ; tout autre dégât est annulé (le temps est arrêté). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageBy(EntityDamageByEntityEvent e) {
        Entity d = e.getDamager();
        Player attacker = d instanceof Player p ? p : d instanceof Projectile pr && pr.getShooter() instanceof Player p ? p : null;
        if (attacker != null && frozenPlayer(attacker)) {
            e.setCancelled(true);
            return;
        }
        if (d instanceof Projectile pr && pr.getShooter() instanceof Entity sh && freezer(sh) != null) {
            e.setCancelled(true);
            return;
        }
        Session s = freezer(e.getEntity());
        if (s == null || s.releasing) return;
        e.setCancelled(true);
        if (attacker != null && attacker == s.user && e.getEntity() instanceof LivingEntity) {
            s.hit(e.getEntity(), e.getDamage());
            if (d instanceof Projectile pr) pr.remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e instanceof EntityDamageByEntityEvent) return;
        Session s = freezer(e.getEntity());
        if (s != null && !s.releasing) e.setCancelled(true);
        if (isImage(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (freezer(e.getEntity()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (frozenPlayer(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (frozenPlayer(e.getPlayer()) || isImage(e.getRightClicked())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (frozenPlayer(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (frozenPlayer(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (frozenPlayer(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (frozenPlayer(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (e.getEntity().getShooter() instanceof Entity sh && freezer(sh) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p && frozenPlayer(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) end(s);
        // un joueur figé qui part : il repart libre
        for (Session o : sessions.values()) o.forget(e.getPlayer().getUniqueId());
        cleanup(e.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        cleanup(e.getPlayer());
    }
}
