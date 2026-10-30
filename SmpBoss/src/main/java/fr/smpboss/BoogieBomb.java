package fr.smpboss;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static fr.smpboss.SmpBoss.parse;

/**
 * Relique "Boogie Bombe" : double accroupi (shift deux fois vite) = tu lances une boule disco.
 * À l'impact, tout le monde autour se met à danser : figé sur place, il tourne sur lui-même et ne peut plus RIEN faire
 * (ni taper, ni manger, ni boire, ni perle, ni commande) pendant quelques secondes. Le boss n'est pas touché.
 */
final class BoogieBomb implements Listener {

    static final String ID = "boogie_bombe";
    private static final Color[] DISCO = {Color.fromRGB(255, 60, 200), Color.fromRGB(60, 220, 255), Color.fromRGB(255, 230, 60),
            Color.fromRGB(120, 255, 90), Color.fromRGB(170, 90, 255), Color.fromRGB(255, 140, 40)};

    private final SmpBoss plugin;
    private final org.bukkit.NamespacedKey bombKey;
    private final Map<UUID, Long> lastSneak = new HashMap<>(), cooldown = new HashMap<>();
    private final Set<UUID> dancing = new HashSet<>();

    BoogieBomb(SmpBoss plugin) {
        this.plugin = plugin;
        this.bombKey = new org.bukkit.NamespacedKey(plugin, "boogie_bomb");
    }

    private double cfg(String path, double def) {
        return plugin.getConfig().getDouble("boogie-bombe." + path, def);
    }

    private boolean wears(Player p) {
        return plugin.store().of(p.getUniqueId()).equipped.contains(ID);
    }

    // ================================================================= lancer

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) return;
        Player p = e.getPlayer();
        if (!wears(p) || dancing.contains(p.getUniqueId())) return;
        long now = System.currentTimeMillis();
        Long last = lastSneak.put(p.getUniqueId(), now);
        if (last == null || now - last > cfg("double-sneak-ms", 350)) return;
        lastSneak.remove(p.getUniqueId());
        if (TojiSpear.blocked(p)) {
            TojiSpear.deny(p);
            return;
        }
        Long until = cooldown.get(p.getUniqueId());
        if (until != null && until > now) {
            p.sendActionBar(parse(plugin.msg("weapons.cooldown").replace("<seconds>", String.valueOf((until - now) / 1000 + 1))));
            return;
        }
        cooldown.put(p.getUniqueId(), now + (long) (cfg("cooldown-seconds", 60) * 1000));
        throwBomb(p);
    }

    private ItemStack ball() {
        ItemStack it = new ItemStack(Material.FIREWORK_STAR);
        ItemMeta m = it.getItemMeta();
        SmpBoss.modelData(m, "relic_" + ID);
        it.setItemMeta(m);
        return it;
    }

    private void throwBomb(Player p) {
        Snowball s = p.launchProjectile(Snowball.class, p.getEyeLocation().getDirection().multiply(cfg("throw-speed", 1.3)));
        s.setItem(ball());
        s.getPersistentDataContainer().set(bombKey, PersistentDataType.BYTE, (byte) 1);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 1f, 0.6f);
        sound(p.getLocation(), "boogie.throw", 20, 1f);
        p.sendActionBar(parse(plugin.msg("boogie-bombe.thrown")));
        // la boule laisse des paillettes
        BukkitTask[] t = new BukkitTask[1];
        int[] k = {0};
        t[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!s.isValid() || k[0]++ > 100) {
                t[0].cancel();
                return;
            }
            s.getWorld().spawnParticle(Particle.DUST, s.getLocation(), 3, 0.1, 0.1, 0.1, 0,
                    new Particle.DustOptions(DISCO[k[0] % DISCO.length], 1f), true);
        }, 1L, 1L);
    }

    @EventHandler
    public void onHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof Snowball s) || !s.getPersistentDataContainer().has(bombKey, PersistentDataType.BYTE)) return;
        if (!(s.getShooter() instanceof Player p)) return;
        e.setCancelled(true);
        Location c = s.getLocation();
        s.remove();
        burst(p, c);
    }

    // ================================================================= l'explosion disco

    private void burst(Player p, Location c) {
        World w = c.getWorld();
        double r = cfg("radius", 5);
        int dur = (int) (cfg("dance-seconds", 5) * 20);
        sound(c, "boogie.burst", 32, 1f);
        PackFx.fallbackSound(c, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.5f, 1f);
        PackFx.fallbackSound(c, Sound.BLOCK_NOTE_BLOCK_BELL, 1.5f, 1.2f);
        w.spawnParticle(Particle.FIREWORK, c, 60, 0.4, 0.4, 0.4, 0.25, null, true);
        for (int i = 0; i < 6; i++) {
            w.spawnParticle(Particle.DUST, c, 30, r * 0.4, 0.6, r * 0.4, 0, new Particle.DustOptions(DISCO[i], 1.6f), true);
        }
        WeaponFx fx = plugin.weapons().fx();
        fx.wave(c.clone().add(0, 0.1, 0), "w_ring_flat", DISCO[0], 1, (float) r * 2.2f, 8, null, Display.Billboard.FIXED);
        fx.wave(c.clone().add(0, 0.15, 0), "w_ring_flat", DISCO[1], 1, (float) r * 1.8f, 10, null, Display.Billboard.FIXED);
        fx.tpop(c.clone().add(0, 1, 0), "w_glow", DISCO[2], 5, 5, 5, 1, 2, 5, Display.Billboard.CENTER, null);
        boolean duels = plugin.getConfig().getBoolean("boogie-bombe.in-duels", true);
        Ultimates ult = plugin.weapons().ultimates;
        for (LivingEntity le : ult.targets(p, c, r, duels)) {
            Boss b = plugin.king();
            if (b != null && b.isBoss(le)) continue; // le boss ne danse pas
            dance(le, dur);
        }
    }

    /** Il danse : sur place, il tourne sur lui-même et se baisse en rythme. Plus rien d'utilisable. */
    private void dance(LivingEntity le, int dur) {
        if (!dancing.add(le.getUniqueId())) return;
        Location anchor = le.getLocation();
        boolean ai = le.hasAI();
        if (le instanceof Player p) {
            BlackFlash.stun(p, dur);
            p.sendActionBar(parse(plugin.msg("boogie-bombe.dancing")));
            p.playSound(p, "limitless:boogie.dance", SoundCategory.PLAYERS, 1f, 1f);
        } else {
            le.setAI(false);
            sound(anchor, "boogie.dance", 16, 0.7f);
        }
        int[] k = {0};
        float[] yaw = {anchor.getYaw()};
        BukkitTask[] t = new BukkitTask[1];
        t[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int i = k[0]++;
            boolean stop = !le.isValid() || le.isDead() || i >= dur || (le instanceof Player pl && !pl.isOnline());
            if (stop) {
                t[0].cancel();
                dancing.remove(le.getUniqueId());
                if (le instanceof Player pl) {
                    pl.setSneaking(false);
                    pl.stopSound("limitless:boogie.dance", SoundCategory.PLAYERS);
                } else if (le.isValid() && ai) le.setAI(true);
                return;
            }
            // tourne sur lui-même
            yaw[0] += (i / 8) % 2 == 0 ? 24 : -24;
            Location at = anchor.clone();
            at.setYaw(yaw[0]);
            at.setPitch(le.getLocation().getPitch());
            if (le instanceof Player pl) {
                Arena.allowTeleport(pl);
                pl.teleport(at);
                // il se baisse et se relève en rythme
                pl.setSneaking(i % 6 < 3);
            } else {
                le.teleport(at);
            }
            le.setVelocity(new Vector());
            le.setFallDistance(0);
            // lumières disco
            Location c = anchor.clone().add(0, le.getHeight() + 0.6, 0);
            if (i % 2 == 0) {
                double a = i * 0.4;
                for (int n = 0; n < 4; n++) {
                    double b = a + Math.PI / 2 * n;
                    le.getWorld().spawnParticle(Particle.DUST, anchor.clone().add(Math.cos(b) * 0.9, 0.2 + (i % 10) * 0.12, Math.sin(b) * 0.9), 1, 0, 0, 0, 0,
                            new Particle.DustOptions(DISCO[(n + i / 2) % DISCO.length], 1.1f), true);
                }
            }
            if (i % 5 == 0) le.getWorld().spawnParticle(Particle.NOTE, c, 1, 0.3, 0.1, 0.3, ThreadLocalRandom.current().nextDouble(), null, true);
            if (i % 10 == 0) plugin.weapons().fx().tpop(anchor.clone().add(0, 0.06, 0), "w_ring_flat", DISCO[(i / 10) % DISCO.length], 2.2f, 1, 2.2f, 1, 3, 4,
                    Display.Billboard.FIXED, new Quaternionf());
        }, 1L, 1L);
    }

    private void sound(Location c, String key, double radius, float vol) {
        for (Player o : c.getWorld().getPlayers()) {
            if (!PackStatus.has(o) || o.getLocation().distanceSquared(c) > radius * radius) continue;
            o.playSound(c, PackFx.NS + ":" + key, SoundCategory.PLAYERS, vol, 1f);
        }
    }
}
