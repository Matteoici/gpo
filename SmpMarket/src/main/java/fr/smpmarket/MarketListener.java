package fr.smpmarket;

import fr.smpmarket.gui.AuctionMenu;
import fr.smpmarket.gui.BuyMenu;
import fr.smpmarket.gui.CatalogMenu;
import fr.smpmarket.gui.Menu;
import fr.smpmarket.gui.ShowcaseMenu;
import fr.smpmarket.gui.StallMenu;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

import static fr.smpmarket.Texts.ph;

final class MarketListener implements Listener {

    private final SmpMarket plugin;

    MarketListener(SmpMarket plugin) {
        this.plugin = plugin;
    }

    // ================================================================= clics sur les panneaux et vitrines

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Visuals.Target target = plugin.visuals().target(e.getRightClicked());
        if (target == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Stall st = plugin.store().stall(target.stall());
        if (st == null) return;
        if (target.showcase() == null) {
            // panneau du stand
            if (!st.isRented()) plugin.claims().deploy(p, st);
            else if (st.isOwner(p.getUniqueId())) new StallMenu(plugin, p, st).open();
            else new CatalogMenu(plugin, p, st).open();
            return;
        }
        Showcase c = st.showcase(target.showcase());
        if (c == null) return;
        if (st.isOwner(p.getUniqueId())) new ShowcaseMenu(plugin, p, st, c).open();
        else new BuyMenu(plugin, p, st, c, null).open();
    }

    // ================================================================= protection des stands

    private boolean isShowcaseBlock(Stall st, Block b) {
        for (Showcase c : st.showcases) {
            if (c.isAt(b.getX(), b.getY(), b.getZ()) || c.isAt(b.getX(), b.getY() - 1, b.getZ())) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent e) {
        Stall st = plugin.shops().stallAt(e.getBlock());
        if (st == null) return;
        Player p = e.getPlayer();
        if (plugin.isAdminBypass(p)) return;
        if (!plugin.settings().claimBuilding) {
            // emplacements partagés : on n'y construit pas (le suivant le récupérerait)
            e.setCancelled(true);
            plugin.texts().send(p, "claim.no-build");
            return;
        }
        if (st.isRented() && st.canBuild(p.getUniqueId()) && !plugin.claims().buildable(st, e.getBlock())) {
            e.setCancelled(true); // le sol et le bord de l'emplacement ne bougent pas
            plugin.texts().send(p, "claim.v2.build-zone");
            return;
        }
        if (st.canBuild(p.getUniqueId())) {
            if (isShowcaseBlock(st, e.getBlock())) {
                e.setCancelled(true);
                plugin.texts().send(p, "protection.showcase-block");
                return;
            }
            if (plugin.settings().overrideProtections) e.setCancelled(false);
        } else if (plugin.settings().protectStalls) {
            e.setCancelled(true);
            plugin.texts().send(p, "protection.not-yours");
        }
    }

    /** Le locataire pose un coffre / tonneau / shulker... : on le note pour le lui rendre s'il perd le stand. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaceContainer(BlockPlaceEvent e) {
        Stall st = plugin.shops().stallAt(e.getBlock());
        if (st == null || !st.isRented() || !st.canBuild(e.getPlayer().getUniqueId())) return;
        if (!(e.getBlock().getState(false) instanceof org.bukkit.inventory.BlockInventoryHolder)) return;
        if (st.containers.add(Auctions.key(e.getBlock()))) plugin.store().markDirty();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent e) {
        Stall st = plugin.shops().stallAt(e.getBlock());
        if (st == null) return;
        Player p = e.getPlayer();
        if (plugin.isAdminBypass(p)) return;
        if (!plugin.settings().claimBuilding) {
            e.setCancelled(true);
            plugin.texts().send(p, "claim.no-build");
            return;
        }
        if (st.isRented() && st.canBuild(p.getUniqueId()) && !plugin.claims().buildable(st, e.getBlock())) {
            e.setCancelled(true);
            plugin.texts().send(p, "claim.v2.build-zone");
            return;
        }
        if (st.canBuild(p.getUniqueId())) {
            if (isShowcaseBlock(st, e.getBlock())) {
                e.setCancelled(true);
                plugin.texts().send(p, "protection.showcase-block");
                return;
            }
            if (plugin.settings().overrideProtections) e.setCancelled(false);
        } else if (plugin.settings().protectStalls) {
            e.setCancelled(true);
            plugin.texts().send(p, "protection.not-yours");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        // baguette de sélection (admin)
        ItemStack item = e.getItem();
        if (item != null && isWand(item) && p.hasPermission("smpmarket.admin") && e.getClickedBlock() != null) {
            e.setCancelled(true);
            if (e.getHand() != EquipmentSlot.HAND) return;
            Location[] sel = plugin.selection(p.getUniqueId());
            Location l = e.getClickedBlock().getLocation();
            int i = e.getAction() == Action.LEFT_CLICK_BLOCK ? 0 : 1;
            sel[i] = l;
            plugin.texts().send(p, i == 0 ? "admin.pos1" : "admin.pos2", ph("x", l.getBlockX()), ph("y", l.getBlockY()),
                    ph("z", l.getBlockZ()));
            return;
        }
        Block b = e.getClickedBlock();
        if (b == null || e.getAction() != Action.RIGHT_CLICK_BLOCK || !plugin.settings().protectStalls) return;
        Stall st = plugin.shops().stallAt(b);
        if (st == null || st.canBuild(p.getUniqueId()) || plugin.isAdminBypass(p)) return;
        if (b.getState(false) instanceof Container) {
            e.setCancelled(true);
            plugin.texts().send(p, "protection.container");
        }
    }

    private boolean isWand(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(plugin.wandKey(), PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> plugin.shops().stallAt(b) != null);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> plugin.shops().stallAt(b) != null);
    }

    // ================================================================= nos mannequins passent les protections

    /** WorldGuard (mob-spawning deny) et les limiteurs de mobs bloquent aussi les mannequins : on les autorise. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawn(org.bukkit.event.entity.EntitySpawnEvent e) {
        if (e.isCancelled() && e.getEntity() instanceof org.bukkit.entity.Mannequin
                && e.getEntity().getPersistentDataContainer().has(plugin.key(), PersistentDataType.BYTE)) {
            e.setCancelled(false);
        }
    }

    // ================================================================= aquariums : eau, seaux d'axolotl / de poissons

    /** Le locataire peut vider un seau (eau, lave, axolotl, poisson...) dans son stand, même si la zone est protégée. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBucketEmpty(org.bukkit.event.player.PlayerBucketEmptyEvent e) {
        Block target = e.getBlockClicked().getRelative(e.getBlockFace());
        Stall st = plugin.shops().stallAt(target);
        if (st == null) st = plugin.shops().stallAt(e.getBlock());
        if (st == null) return;
        Player p = e.getPlayer();
        if (plugin.isAdminBypass(p)) return;
        if (!plugin.settings().claimBuilding) {
            e.setCancelled(true);
            plugin.texts().send(p, "claim.no-build");
            return;
        }
        if (st.canBuild(p.getUniqueId())) {
            if (plugin.settings().overrideProtections) e.setCancelled(false);
            st.touch();
        } else if (plugin.settings().protectStalls) {
            e.setCancelled(true);
            plugin.texts().send(p, "protection.not-yours");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBucketFill(org.bukkit.event.player.PlayerBucketFillEvent e) {
        Stall st = plugin.shops().stallAt(e.getBlockClicked());
        if (st == null) return;
        Player p = e.getPlayer();
        if (plugin.isAdminBypass(p)) return;
        if (st.canBuild(p.getUniqueId())) {
            if (plugin.settings().overrideProtections) e.setCancelled(false);
        } else if (plugin.settings().protectStalls) {
            e.setCancelled(true);
            plugin.texts().send(p, "protection.not-yours");
        }
    }

    /** Personne d'autre ne peut repêcher les axolotls / poissons d'un stand. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBucketEntity(org.bukkit.event.player.PlayerBucketEntityEvent e) {
        Stall st = plugin.shops().stallAt(e.getEntity().getLocation());
        if (st == null) return;
        Player p = e.getPlayer();
        if (plugin.isAdminBypass(p)) return;
        if (st.canBuild(p.getUniqueId())) {
            if (plugin.settings().overrideProtections) e.setCancelled(false);
        } else {
            e.setCancelled(true);
            plugin.texts().send(p, "protection.not-yours");
        }
    }

    /** Les animaux sortis d'un seau ou d'un œuf dans un stand apparaissent, même si la zone interdit les mobs. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCreatureSpawn(org.bukkit.event.entity.CreatureSpawnEvent e) {
        var r = e.getSpawnReason();
        if (r != org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.BUCKET
                && r != org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SPAWNER_EGG
                && r != org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.BREEDING) return;
        if (plugin.shops().stallAt(e.getLocation()) != null && plugin.settings().overrideProtections) e.setCancelled(false);
    }

    /** L'eau / la lave d'un stand ne coule jamais dehors. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(org.bukkit.event.block.BlockFromToEvent e) {
        Stall from = plugin.shops().stallAt(e.getBlock());
        if (from == null) return;
        if (!from.region.contains(e.getToBlock())) e.setCancelled(true);
    }

    /** Les animaux d'un stand sont protégés des autres joueurs. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurtPet(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof Player || !(e.getEntity() instanceof org.bukkit.entity.LivingEntity)) return;
        Player attacker = e.getDamager() instanceof Player pl ? pl
                : e.getDamager() instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Player sh ? sh : null;
        if (attacker == null) return;
        Stall st = plugin.shops().stallAt(e.getEntity().getLocation());
        if (st == null || !st.isRented() || st.canBuild(attacker.getUniqueId()) || plugin.isAdminBypass(attacker)) return;
        e.setCancelled(true);
    }

    // ================================================================= stand privé

    private final java.util.Map<java.util.UUID, Long> privateMsg = new java.util.HashMap<>();

    private boolean blockedFrom(Player p, Stall st) {
        return st != null && st.privateStall && st.isRented() && !st.canBuild(p.getUniqueId()) && !plugin.isAdminBypass(p);
    }

    private void warnPrivate(Player p, Stall st) {
        long now = System.currentTimeMillis();
        Long last = privateMsg.get(p.getUniqueId());
        if (last != null && now - last < 3000) return;
        privateMsg.put(p.getUniqueId(), now);
        plugin.texts().send(p, "protection.private", ph("shop", st.displayName()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(org.bukkit.event.player.PlayerMoveEvent e) {
        Location to = e.getTo(), from = e.getFrom();
        if (to.getBlockX() == from.getBlockX() && to.getBlockY() == from.getBlockY() && to.getBlockZ() == from.getBlockZ()) return;
        if (!plugin.settings().privateAllowed) return;
        Player p = e.getPlayer();
        Stall st = plugin.shops().stallAt(to.getBlock());
        if (!blockedFrom(p, st) || !st.insideCore(to)) return;
        if (st.insideCore(from)) {
            // déjà dedans (le stand vient de passer en privé) : on le sort par l'entrée
            Location ent = st.entrance();
            if (ent != null) {
                ent.setPitch(p.getLocation().getPitch());
                p.teleport(ent);
            }
        } else {
            // on le bloque au seuil, sans le faire sauter : seule la position horizontale est annulée
            Location back = from.clone();
            back.setY(Math.min(from.getY(), to.getY()));
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            e.setTo(back);
            org.bukkit.util.Vector push = from.toVector().subtract(to.toVector()).setY(0);
            if (push.lengthSquared() > 1e-4) {
                org.bukkit.util.Vector v = p.getVelocity();
                p.setVelocity(push.normalize().multiply(0.2).setY(Math.min(0, v.getY())));
            }
            barrierFx(p, st, to);
        }
        warnPrivate(p, st);
    }

    /** Petit mur de lumière là où le joueur a tapé, visible par lui seul. */
    private void barrierFx(Player p, Stall st, Location at) {
        org.bukkit.Particle.DustOptions d = new org.bukkit.Particle.DustOptions(org.bukkit.Color.fromRGB(255, 80, 80), 0.9f);
        for (int i = 0; i < 14; i++) {
            double ox = (Math.random() - 0.5) * 1.6, oy = Math.random() * 2, oz = (Math.random() - 0.5) * 1.6;
            p.spawnParticle(org.bukkit.Particle.DUST, at.getX() + ox, at.getBlockY() + oy, at.getZ() + oz, 1, 0, 0, 0, 0, d);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent e) {
        if (!plugin.settings().privateAllowed) return;
        var cause = e.getCause();
        String c = cause.name();
        if (!c.equals("ENDER_PEARL") && !c.equals("CHORUS_FRUIT") && !c.equals("CONSUMABLE_EFFECT")) return;
        Stall st = plugin.shops().stallAt(e.getTo().getBlock());
        if (blockedFrom(e.getPlayer(), st)) {
            e.setCancelled(true);
            warnPrivate(e.getPlayer(), st);
        }
    }

    // ================================================================= chunks

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        String w = e.getWorld().getName();
        int cx = e.getChunk().getX();
        int cz = e.getChunk().getZ();
        Bukkit.getScheduler().runTask(plugin, () -> plugin.visuals().onChunkLoad(w, cx, cz));
    }

    // ================================================================= connexion

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            List<String> notes = plugin.store().takeNotes(p.getUniqueId());
            for (String n : notes) p.sendMessage(Texts.MM.deserialize(n));
            double[] sales = plugin.store().takeOfflineSales(p.getUniqueId());
            if (sales != null && sales[0] > 0) {
                plugin.texts().send(p, "join.sales", ph("count", (long) sales[0]),
                        ph("money", plugin.settings().money(sales[1])));
            }
            int parcels = plugin.store().parcels(p.getUniqueId()).size();
            if (parcels > 0) plugin.texts().send(p, "join.parcels", ph("count", parcels));
        }, 60L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.input().forget(e.getPlayer().getUniqueId());
        plugin.claims().onQuit(e.getPlayer());
    }

    // ================================================================= chat (saisie de prix, nom...)

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        if (plugin.input().handle(e.getPlayer(), text)) e.setCancelled(true);
    }

    // ================================================================= menus

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == top) menu.click(e.getSlot(), e.getClick());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }
}
