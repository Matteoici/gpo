package fr.smpmonde;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static fr.smpmonde.SmpMonde.mm;

/**
 * 🌑 Lune de sang : la nuit tombe, les monstres sont plus forts, leur butin et leur XP doublent,
 * il en apparaît en plus autour des joueurs et des Champions de sang rôdent. Les meilleurs chasseurs sont récompensés.
 */
final class BloodMoon implements Listener {

    private final SmpMonde plugin;
    private final NamespacedKey tag, champ, hp, dmg, spd;
    private BukkitTask task;
    private BossBar bar;
    private long end;
    private final Map<UUID, Integer> kills = new HashMap<>();
    private final List<UUID> champions = new ArrayList<>();
    private int tick;

    BloodMoon(SmpMonde plugin) {
        this.plugin = plugin;
        tag = new NamespacedKey(plugin, "blood");
        champ = new NamespacedKey(plugin, "blood_champion");
        hp = new NamespacedKey(plugin, "blood_hp");
        dmg = new NamespacedKey(plugin, "blood_dmg");
        spd = new NamespacedKey(plugin, "blood_speed");
    }

    boolean running() {
        return task != null;
    }

    private double c(String k, double def) {
        return plugin.getConfig().getDouble("blood-moon." + k, def);
    }

    void start() {
        if (running()) return;
        World w = plugin.world();
        end = System.currentTimeMillis() + (long) (c("duration-minutes", 20) * 60_000);
        kills.clear();
        tick = 0;
        bar = BossBar.bossBar(mm("<dark_red><b>🌑 LUNE DE SANG"), 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showBossBar(bar);
            p.showTitle(Title.title(mm("<dark_red><b>🌑 LUNE DE SANG"), mm("<red>Les monstres sont x" + (int) c("health", 10) + " plus forts... survis."),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(4), Duration.ofSeconds(1))));
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 0.5f);
        }
        Bukkit.broadcast(mm("<dark_red><b>🌑 La Lune de sang se lève !</b> <gray>Pendant <white>" + (int) c("duration-minutes", 20)
                + " min</white> : les monstres sont <red><b>x" + (int) c("health", 10) + " plus forts</b></red>, il en sort de partout et des Champions de sang rôdent. Les meilleurs chasseurs gagnent gros."));
        for (LivingEntity e : w.getLivingEntities()) if (e instanceof Enemy && !(e instanceof Player)) buff(e);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::loop, 20L, 20L);
    }

    void stop(boolean natural) {
        if (task == null) return;
        task.cancel();
        task = null;
        for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
        for (UUID id : champions) {
            var e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        champions.clear();
        World w = plugin.world();
        w.setTime(23500); // l'aube
        for (LivingEntity e : w.getLivingEntities()) if (e.getPersistentDataContainer().has(tag)) unbuff(e);
        if (!natural) return;
        List<Map.Entry<UUID, Integer>> top = new ArrayList<>(kills.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        Bukkit.broadcast(mm("<dark_red><b>🌑 La Lune de sang se couche.</b> <gray>Meilleurs chasseurs :"));
        for (int i = 0; i < Math.min(3, top.size()); i++) {
            var off = Bukkit.getOfflinePlayer(top.get(i).getKey());
            Bukkit.broadcast(mm("  " + (i == 0 ? "<gold>🥇" : i == 1 ? "<gray>🥈" : "<#cd7f32>🥉") + " <white>" + off.getName() + " <dark_gray>— <red>" + top.get(i).getValue() + " monstres"));
            plugin.reward(off, plugin.getConfig().getStringList("blood-moon.top-rewards." + (i + 1)));
        }
        if (top.isEmpty()) Bukkit.broadcast(mm("<gray>  Personne... les monstres ont gagné cette nuit."));
    }

    private void loop() {
        long left = end - System.currentTimeMillis();
        if (left <= 0) {
            stop(true);
            return;
        }
        tick++;
        World w = plugin.world();
        w.setTime(18000); // minuit tout le long
        double total = c("duration-minutes", 20) * 60_000;
        bar.progress((float) Math.max(0, Math.min(1, left / total)));
        bar.name(mm("<dark_red><b>🌑 LUNE DE SANG</b> <gray>— <white>" + (left / 60000) + ":" + String.format("%02d", left / 1000 % 60)
                + " <dark_gray>· <red>monstres x" + (int) c("health", 10)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showBossBar(bar);
            if (p.getWorld().equals(w) && tick % 4 == 0)
                w.spawnParticle(Particle.DUST, p.getLocation().add(0, 6, 0), 30, 12, 3, 12, 0, new Particle.DustOptions(Color.fromRGB(150, 0, 0), 2f), false);
        }
        if (tick % 45 == 0) for (Player p : w.getPlayers()) p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, 0.7f, 0.6f);
        // monstres en plus
        int every = (int) Math.max(5, c("extra-every-seconds", 20));
        if (tick % every == 0) {
            List<String> types = plugin.getConfig().getStringList("blood-moon.extra-mobs");
            if (types.isEmpty()) types = List.of("ZOMBIE", "SKELETON", "SPIDER");
            for (Player p : w.getPlayers()) {
                if (p.getGameMode() != GameMode.SURVIVAL || !plugin.allowed(p.getLocation())) continue;
                for (int i = 0; i < (int) c("extra-per-player", 2); i++) {
                    Location l = plugin.around(p.getLocation(), 12, 26);
                    if (l == null) continue;
                    try {
                        EntityType t = EntityType.valueOf(types.get(ThreadLocalRandom.current().nextInt(types.size())).toUpperCase());
                        var e = w.spawnEntity(l, t, CreatureSpawnEvent.SpawnReason.CUSTOM);
                        if (e instanceof LivingEntity le) {
                            buff(le);
                            if (le instanceof Mob m) m.setTarget(p);
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
        // Champion de sang
        int champEvery = (int) Math.max(60, c("champion-every-minutes", 4) * 60);
        if (tick % champEvery == 30) champion();
    }

    private void champion() {
        List<Player> ok = new ArrayList<>();
        for (Player p : plugin.world().getPlayers()) if (p.getGameMode() == GameMode.SURVIVAL && plugin.allowed(p.getLocation())) ok.add(p);
        if (ok.isEmpty()) return;
        Player target = ok.get(ThreadLocalRandom.current().nextInt(ok.size()));
        Location l = plugin.around(target.getLocation(), 8, 16);
        if (l == null) return;
        WitherSkeleton ws = l.getWorld().spawn(l, WitherSkeleton.class, e -> {
            e.customName(mm("<dark_red><b>☠ Champion de sang"));
            e.setCustomNameVisible(true);
            e.setRemoveWhenFarAway(false);
            e.getPersistentDataContainer().set(champ, PersistentDataType.BYTE, (byte) 1);
            e.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            AttributeInstance mh = e.getAttribute(Attribute.MAX_HEALTH);
            if (mh != null) mh.setBaseValue(Math.min(1024, c("champion-health", 300)));
            e.setHealth(Math.min(1024, c("champion-health", 300)));
            AttributeInstance ad = e.getAttribute(Attribute.ATTACK_DAMAGE);
            if (ad != null) ad.setBaseValue(4); // x10 avec la lune = 40
            AttributeInstance kb = e.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
            if (kb != null) kb.setBaseValue(0.8);
            e.getEquipment().setItem(EquipmentSlot.HAND, new ItemStack(Material.NETHERITE_SWORD));
            e.getEquipment().setItem(EquipmentSlot.HEAD, red(Material.LEATHER_HELMET));
            e.getEquipment().setItem(EquipmentSlot.CHEST, red(Material.LEATHER_CHESTPLATE));
            e.getEquipment().setDropChance(EquipmentSlot.HAND, 0);
            e.getEquipment().setDropChance(EquipmentSlot.HEAD, 0);
            e.getEquipment().setDropChance(EquipmentSlot.CHEST, 0);
            e.setGlowing(true);
            e.setTarget(target);
        });
        champions.add(ws.getUniqueId());
        l.getWorld().strikeLightningEffect(l);
        Bukkit.broadcast(mm("<dark_red>☠ Un <b>Champion de sang</b> traque <white>" + target.getName() + "</white> <gray>(près de " + l.getBlockX() + " " + l.getBlockZ() + ")"));
    }

    private static ItemStack red(Material m) {
        ItemStack it = new ItemStack(m);
        LeatherArmorMeta meta = (LeatherArmorMeta) it.getItemMeta();
        meta.setColor(Color.fromRGB(120, 0, 0));
        it.setItemMeta(meta);
        return it;
    }

    private void buff(LivingEntity e) {
        if (e.getPersistentDataContainer().has(tag) && !e.getPersistentDataContainer().has(champ)) return;
        e.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
        mod(e, Attribute.MAX_HEALTH, hp, c("health", 10) - 1);
        mod(e, Attribute.ATTACK_DAMAGE, dmg, c("damage", 10) - 1);
        mod(e, Attribute.MOVEMENT_SPEED, spd, c("speed", 1.25) - 1);
        AttributeInstance mh = e.getAttribute(Attribute.MAX_HEALTH);
        if (mh != null) e.setHealth(mh.getValue());
    }

    private void unbuff(LivingEntity e) {
        for (Attribute a : new Attribute[]{Attribute.MAX_HEALTH, Attribute.ATTACK_DAMAGE, Attribute.MOVEMENT_SPEED}) {
            AttributeInstance i = e.getAttribute(a);
            if (i != null) for (NamespacedKey k : new NamespacedKey[]{hp, dmg, spd}) i.removeModifier(k);
        }
        e.getPersistentDataContainer().remove(tag);
    }

    private static void mod(LivingEntity e, Attribute a, NamespacedKey k, double v) {
        AttributeInstance i = e.getAttribute(a);
        if (i == null || v == 0) return;
        i.removeModifier(k);
        i.addModifier(new AttributeModifier(k, v, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
    }

    /** Flèches, boules de feu et explosions des monstres de la lune : x10 aussi (le corps à corps passe déjà par l'attribut). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurt(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (!running() || !(e.getEntity() instanceof Player)) return;
        org.bukkit.entity.Entity d = e.getDamager();
        LivingEntity src = null;
        if (d instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof LivingEntity le) src = le;
        else if (d instanceof org.bukkit.entity.Creeper cr) src = cr;
        if (src == null || !src.getPersistentDataContainer().has(tag)) return;
        e.setDamage(e.getDamage() * c("damage", 10));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!running() || !(e.getEntity() instanceof Enemy) || !e.getEntity().getWorld().equals(plugin.world())) return;
        if (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER) return; // pas les spawners
        buff(e.getEntity());
    }

    /** Seul le vrai butin de monstre peut être doublé : jamais un objet qu'on lui a fait ramasser (sinon duplication). */
    static final java.util.Set<org.bukkit.Material> MOB_LOOT = java.util.EnumSet.of(
            org.bukkit.Material.ROTTEN_FLESH, org.bukkit.Material.BONE, org.bukkit.Material.ARROW, org.bukkit.Material.STRING,
            org.bukkit.Material.SPIDER_EYE, org.bukkit.Material.GUNPOWDER, org.bukkit.Material.ENDER_PEARL, org.bukkit.Material.BLAZE_ROD,
            org.bukkit.Material.GHAST_TEAR, org.bukkit.Material.SLIME_BALL, org.bukkit.Material.MAGMA_CREAM, org.bukkit.Material.PHANTOM_MEMBRANE,
            org.bukkit.Material.PRISMARINE_SHARD, org.bukkit.Material.PRISMARINE_CRYSTALS, org.bukkit.Material.COAL, org.bukkit.Material.GLOWSTONE_DUST,
            org.bukkit.Material.REDSTONE, org.bukkit.Material.SUGAR, org.bukkit.Material.GLASS_BOTTLE, org.bukkit.Material.STICK,
            org.bukkit.Material.GOLD_NUGGET, org.bukkit.Material.COPPER_INGOT, org.bukkit.Material.LEATHER, org.bukkit.Material.FEATHER,
            org.bukkit.Material.INK_SAC, org.bukkit.Material.BREEZE_ROD, org.bukkit.Material.EMERALD, org.bukkit.Material.RABBIT_HIDE);

    static boolean mobLoot(org.bukkit.inventory.ItemStack it) {
        return it != null && MOB_LOOT.contains(it.getType()) && !it.hasItemMeta();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity d = e.getEntity();
        if (!d.getPersistentDataContainer().has(tag)) return;
        boolean isChamp = d.getPersistentDataContainer().has(champ);
        champions.remove(d.getUniqueId());
        if (!running()) return;
        int mult = (int) Math.max(1, c("drops", 1));
        List<ItemStack> extra = new ArrayList<>();
        for (int i = 1; i < mult; i++) for (ItemStack it : e.getDrops()) if (mobLoot(it)) extra.add(it.clone());
        e.getDrops().addAll(extra);
        e.setDroppedExp((int) (e.getDroppedExp() * c("xp", 1)));
        Player k = d.getKiller();
        if (k == null) return;
        kills.merge(k.getUniqueId(), isChamp ? 10 : 1, Integer::sum);
        if (isChamp) {
            plugin.reward(k, plugin.getConfig().getStringList("blood-moon.champion-rewards"));
            Bukkit.broadcast(mm("<dark_red>☠ <white>" + k.getName() + "</white> a abattu un <b>Champion de sang</b> !"));
            e.getDrops().add(new ItemStack(Material.NETHERITE_SCRAP, 1 + ThreadLocalRandom.current().nextInt(2)));
        }
    }
}
