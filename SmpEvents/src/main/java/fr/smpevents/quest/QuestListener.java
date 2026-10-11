package fr.smpevents.quest;

import fr.smpevents.SmpEvents;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Fait avancer les quêtes selon ce que font les joueurs, avec des protections contre la triche. */
public final class QuestListener implements Listener {

    private static final int MAX_PLACED = 400_000;

    private final SmpEvents plugin;
    /** Blocs posés par des joueurs (ne comptent pas quand on les casse : pas de pose / casse en boucle). */
    private final Set<String> placed = new LinkedHashSet<>();
    private final Map<UUID, Location> lastPos = new HashMap<>();
    private final Map<UUID, Double> walked = new HashMap<>();
    private final Map<UUID, Long> lastActive = new HashMap<>();
    /** "tueur:victime" -> dernière fois comptée */
    private final Map<String, Long> playerKills = new HashMap<>();

    public QuestListener(SmpEvents plugin) {
        this.plugin = plugin;
    }

    private Quests q() {
        return plugin.quests();
    }

    private static boolean counts(Player p) {
        GameMode g = p.getGameMode();
        return g == GameMode.SURVIVAL || g == GameMode.ADVENTURE;
    }

    private static String key(Block b) {
        return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    // ================================================================= blocs

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        placed.add(key(e.getBlockPlaced()));
        if (placed.size() > MAX_PLACED) {
            var it = placed.iterator();
            for (int i = 0; i < 10_000 && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
        if (counts(p)) q().progress(p, QuestType.PLACE, e.getBlockPlaced().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        boolean wasPlaced = placed.remove(key(b));
        if (!counts(p)) return;
        String type = b.getType().name();
        if (b.getBlockData() instanceof Ageable age && isCrop(b.getType())) {
            if (age.getAge() >= age.getMaximumAge()) q().progress(p, QuestType.HARVEST, type, 1);
            return;
        }
        if (b.getType() == Material.MELON || b.getType() == Material.PUMPKIN || b.getType() == Material.SUGAR_CANE
                || b.getType() == Material.CACTUS) {
            if (!wasPlaced) q().progress(p, QuestType.HARVEST, type, 1);
            return;
        }
        if (!wasPlaced) q().progress(p, QuestType.MINE, type, 1);
    }

    private static boolean isCrop(Material m) {
        return switch (m) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS, NETHER_WART, COCOA, SWEET_BERRY_BUSH, TORCHFLOWER_CROP,
                 PITCHER_CROP -> true;
            default -> false;
        };
    }

    // ================================================================= créatures

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        CreatureSpawnEvent.SpawnReason r = e.getSpawnReason();
        if (r == CreatureSpawnEvent.SpawnReason.SPAWNER || r == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG
                || r == CreatureSpawnEvent.SpawnReason.TRIAL_SPAWNER) {
            e.getEntity().getPersistentDataContainer().set(plugin.spawnerKey(), PersistentDataType.BYTE, (byte) 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity();
        Player killer = dead.getKiller();
        if (killer == null || !counts(killer)) return;
        if (dead instanceof Player victim) {
            if (victim.equals(killer)) return;
            String k = killer.getUniqueId() + ":" + victim.getUniqueId();
            long now = System.currentTimeMillis();
            Long last = playerKills.get(k);
            if (last != null && now - last < q().playerKillCooldownMs) return;
            if (plugin.relations().link(killer, victim) != null) return;
            playerKills.put(k, now);
            q().progress(killer, QuestType.KILL_PLAYER, "PLAYER", 1);
            return;
        }
        if (!q().countSpawnerMobs && dead.getPersistentDataContainer().has(plugin.spawnerKey(), PersistentDataType.BYTE)) return;
        q().progress(killer, QuestType.KILL_MOB, dead.getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent e) {
        if (e.getBreeder() instanceof Player p && counts(p)) {
            q().progress(p, QuestType.BREED, e.getEntity().getType().name(), 1);
        }
    }

    // ================================================================= objets

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH || !counts(e.getPlayer())) return;
        Entity c = e.getCaught();
        String type = c instanceof Item it ? it.getItemStack().getType().name() : "FISH";
        q().progress(e.getPlayer(), QuestType.FISH, type, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSmelt(FurnaceExtractEvent e) {
        if (counts(e.getPlayer())) q().progress(e.getPlayer(), QuestType.SMELT, e.getItemType().name(), e.getItemAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent e) {
        if (counts(e.getEnchanter())) q().progress(e.getEnchanter(), QuestType.ENCHANT, e.getItem().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent e) {
        if (counts(e.getPlayer())) q().progress(e.getPlayer(), QuestType.VILLAGER_TRADE, e.getVillager().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent e) {
        if (counts(e.getPlayer())) q().progress(e.getPlayer(), QuestType.EAT, e.getItem().getType().name(), 1);
    }

    // ================================================================= marche / temps de jeu

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location f = e.getFrom(), t = e.getTo();
        if (f.getYaw() != t.getYaw() || f.getPitch() != t.getPitch()) {
            lastActive.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
        }
    }

    /** Toutes les secondes : distance parcourue à pied. */
    public void tickSecond() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location now = p.getLocation();
            Location before = lastPos.put(p.getUniqueId(), now);
            if (before == null || before.getWorld() != now.getWorld() || !counts(p)) continue;
            if (p.isInsideVehicle() || p.isGliding() || p.isFlying() || p.isSwimming() || p.isInWater()
                    || p.isRiptiding() || p.isDead()) continue;
            double dx = now.getX() - before.getX(), dz = now.getZ() - before.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 0.5 || dist > 12) continue; // immobile, ou téléporté
            double sum = walked.merge(p.getUniqueId(), dist, Double::sum);
            if (sum >= 10) {
                walked.put(p.getUniqueId(), sum - Math.floor(sum));
                q().progress(p, QuestType.WALK, "WALK", (long) Math.floor(sum));
            }
        }
    }

    /** Toutes les minutes : temps de jeu, seulement si le joueur n'est pas AFK (a tourné la tête dans les 3 dernières minutes). */
    public void tickMinute() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Long active = lastActive.get(p.getUniqueId());
            if (active == null || now - active > 180_000) continue;
            q().progress(p, QuestType.PLAYTIME, "PLAYTIME", 1);
        }
        playerKills.values().removeIf(t -> now - t > q().playerKillCooldownMs);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        lastPos.remove(id);
        walked.remove(id);
        lastActive.remove(id);
        q().forget(id);
    }
}
