package fr.smpgojo;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Kinji Hakari : Jeu de la mort oisive (jackpot = immortel), portes du train. */
final class Hakari {

    private final SmpGojo pl;
    /** joueur -> fin du jackpot (ms) : pendant ce temps il ne peut pas mourir */
    private final Map<UUID, Long> jackpot = new HashMap<>();

    Hakari(SmpGojo pl) {
        this.pl = pl;
    }

    boolean inJackpot(Player p) {
        return jackpot.getOrDefault(p.getUniqueId(), 0L) > System.currentTimeMillis();
    }

    /** Pendant le jackpot, l'énergie occulte infinie le soigne de tout : il ne peut pas mourir. */
    void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !inJackpot(p)) return;
        if (e.getFinalDamage() >= p.getHealth()) {
            e.setCancelled(true);
            var max = p.getAttribute(Attribute.MAX_HEALTH);
            p.setHealth(max == null ? 20 : max.getValue());
            Draw.fx(p.getLocation().add(0, 1, 0), Particle.TOTEM_OF_UNDYING, 40, 0.5, 0.3);
            SmpGojo.sound(p.getLocation(), "block.amethyst_block.chime", 2f, 1.6f);
            p.sendActionBar(SmpGojo.mm("<gold><b>JACKPOT</b> <yellow>- régénération infinie"));
        }
    }

    // ================================================================= Jeu de la mort oisive

    void domain(Player p) {
        int r = pl.cfgi("hakari.radius", 14), seconds = pl.cfgi("hakari.seconds", 25);
        Location c = p.getLocation().getBlock().getLocation();
        World w = c.getWorld();
        List<Player> vs = pl.viewers(c, 100);
        pl.cine.impact(vs, null, new Cine.Frame[]{Cine.f(200, Draw.GOLD, 2), Cine.f(120, Draw.WHITE, 1)});
        pl.cine.title(vs, "<gradient:#ffd700:#00ff7f:#ffd700><b>JEU DE LA MORT OISIVE", "<yellow>Extension du territoire", 50);
        SmpGojo.sound(c, "block.bell.use", 3f, 1.4f);
        SmpGojo.sound(c, "entity.player.levelup", 3f, 0.8f);
        // le décor : un sol de salle de jeux et des machines lumineuses tout autour
        BlockData[] floor = {Material.BLACK_CONCRETE.createBlockData(), Material.GREEN_CONCRETE.createBlockData(),
                Material.GOLD_BLOCK.createBlockData(), Material.SEA_LANTERN.createBlockData()};
        BlockData glow = Material.GLOWSTONE.createBlockData(), bars = Material.IRON_BARS.createBlockData(),
                red = Material.REDSTONE_LAMP.createBlockData(), shroom = Material.SHROOMLIGHT.createBlockData();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<BlockState> fake = new ArrayList<>();
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (x * x + z * z > r * r) continue;
                Block top = Ruin.surface(w, c.getBlockX() + x, c.getBlockY(), c.getBlockZ() + z);
                if (top == null) continue;
                BlockState st = top.getState();
                boolean stripe = (Math.abs(x) + Math.abs(z)) % 4 == 0;
                st.setBlockData(stripe ? floor[2] : (rnd.nextInt(12) == 0 ? floor[3] : floor[(x + z) % 2 == 0 ? 0 : 1]));
                fake.add(st);
            }
        }
        for (int k = 0; k < 12; k++) {
            double a = k * Math.PI * 2 / 12;
            int x = (int) Math.round(Math.cos(a) * (r - 1)), z = (int) Math.round(Math.sin(a) * (r - 1));
            Block base = Ruin.surface(w, c.getBlockX() + x, c.getBlockY(), c.getBlockZ() + z);
            if (base == null) continue;
            for (int y = 1; y <= 5; y++) {
                BlockState st = base.getRelative(0, y, 0).getState();
                st.setBlockData(y == 5 ? glow : y == 3 ? shroom : (k % 2 == 0 ? bars : red));
                fake.add(st);
            }
        }
        fake.sort(Comparator.comparingDouble(s -> s.getLocation().distanceSquared(c)));
        int waves = 8, per = (fake.size() + waves - 1) / waves;
        for (int k = 0; k < waves; k++) {
            List<BlockState> part = new ArrayList<>(fake.subList(Math.min(fake.size(), k * per), Math.min(fake.size(), (k + 1) * per)));
            pl.later(k, () -> {
                pl.fakeBlocks(vs, part);
            });
        }
        Runnable[] close = new Runnable[1];
        close[0] = () -> {
            pl.pending.remove(close[0]);
            List<BlockState> real = new ArrayList<>();
            for (BlockState s : fake) real.add(s.getBlock().getState());
            pl.fakeBlocks(vs, real);
            if (pl.isEnabled()) SmpGojo.sound(c, "block.iron_door.close", 3f, 0.6f);
        };
        pl.pending.add(close[0]);

        double chance = pl.cfg("hakari.jackpot-chance", 0.35);
        boolean[] won = {false};
        int spinEvery = 100;
        int spins = Math.max(1, seconds * 20 / spinEvery);
        pl.repeat(2, seconds * 10, i -> {
            if (!pl.pending.contains(close[0])) return;
            int tick = i * 2;
            // billes de pachinko qui tombent sur les ennemis
            if (i % 5 == 0) {
                for (LivingEntity le : pl.targets(p, c, r)) {
                    pl.ruin.spawnDebris(Material.WHITE_CONCRETE.createBlockData(), le.getLocation().add(rnd.nextDouble(-1, 1), 4, rnd.nextDouble(-1, 1)),
                            new Vector(0, -0.4, 0), null, 15);
                    pl.hit(p, le, pl.cfg("damage.billes", 1.5), "billes");
                }
            }
            if (i % 3 == 0) Draw.fx(c.clone().add(rnd.nextDouble(-r, r), 2 + rnd.nextDouble(3), rnd.nextDouble(-r, r)), Particle.WAX_ON, 3, 0.3, 0.1);
            // une partie toutes les 5 secondes
            int spin = tick / spinEvery, inSpin = tick % spinEvery;
            if (won[0] || spin >= spins) return;
            if (inSpin < 30) {
                String a = String.valueOf(rnd.nextInt(10)), b = String.valueOf(rnd.nextInt(10)), d = String.valueOf(rnd.nextInt(10));
                pl.cine.title(List.of(p), "<yellow>[ <white>" + a + " <yellow>| <white>" + b + " <yellow>| <white>" + d + " <yellow>]", "<gray>Ça tourne...", 3);
                SmpGojo.sound(p.getLocation(), "block.note_block.pling", 1.5f, 0.8f + inSpin * 0.03f);
            } else if (inSpin == 30) {
                if (rnd.nextDouble() < chance) {
                    won[0] = true;
                    jackpotWin(p, vs);
                    pl.later(40, () -> {
                        if (pl.pending.contains(close[0])) close[0].run();
                    });
                } else {
                    int x = rnd.nextInt(10), y = (x + 1 + rnd.nextInt(9)) % 10;
                    pl.cine.title(List.of(p), "<gray>[ " + x + " | " + x + " | " + y + " ]", "<red>Raté... <gray>on relance", 25);
                    SmpGojo.sound(p.getLocation(), "entity.villager.no", 1.5f, 0.8f);
                }
            }
        }, () -> {
            if (pl.pending.contains(close[0])) close[0].run();
        });
    }

    private void jackpotWin(Player p, List<Player> vs) {
        int s = pl.cfgi("hakari.jackpot-seconds", 60);
        jackpot.put(p.getUniqueId(), System.currentTimeMillis() + s * 1000L);
        pl.cine.title(vs, "<rainbow><b>JACKPOT !", "<yellow>[ 7 | 7 | 7 ]  <white>Énergie occulte infinie", 60);
        for (Player v : vs) v.sendMessage(SmpGojo.mm("<rainbow><b>JACKPOT !</b></rainbow> <yellow>" + p.getName()
                + " <white>est immortel pendant <gold>" + s + " s<white>."));
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, s * 20, 4, false, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, s * 20, 1, false, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, s * 20, 1, false, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, s * 20, 1, false, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, s * 20, 4, false, false, true));
        // petite musique de victoire
        float[] notes = {1.0f, 1.26f, 1.5f, 2.0f, 1.5f, 2.0f};
        for (int k = 0; k < notes.length; k++) {
            float n = notes[k];
            pl.later(k * 3L, () -> {
                SmpGojo.sound(p.getLocation(), "block.note_block.bell", 2f, n);
                SmpGojo.sound(p.getLocation(), "block.note_block.chime", 1.5f, n);
            });
        }
        SmpGojo.sound(p.getLocation(), "entity.firework_rocket.twinkle", 3f, 1f);
        SmpGojo.sound(p.getLocation(), "ui.toast.challenge_complete", 2f, 1f);
        pl.repeat(4, s * 5, i -> {
            if (!p.isOnline()) return;
            Location l = p.getLocation().add(0, 1, 0);
            Draw.orbit(l, 1.1, i * 0.4, 0.4, Draw.GOLD, 1.1f, 10);
            Draw.fx(l, Particle.TOTEM_OF_UNDYING, 2, 0.4, 0.1);
        }, null);
    }

    // ================================================================= Portes du train

    void doors(Player p) {
        LivingEntity t = pl.aim(p, 24);
        if (t == null) {
            p.sendActionBar(SmpGojo.mm("<red>Aucune cible en vue"));
            pl.resetCooldown(p, "portes");
            return;
        }
        Location c = t.getLocation();
        Vector side = Draw.perpendicular(c.toVector().subtract(p.getLocation().toVector()).setY(0).normalize(), 0).setY(0).normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(-side.getX(), side.getZ()));
        List<BlockDisplay> doors = new ArrayList<>();
        for (int s = -1; s <= 1; s += 2) {
            Location at = c.clone().add(side.clone().multiply(s * 3.5));
            at.setYaw(yaw);
            at.setPitch(0);
            doors.add(c.getWorld().spawn(at, BlockDisplay.class, e -> {
                e.setPersistent(false);
                e.getPersistentDataContainer().set(pl.tagKey, PersistentDataType.STRING, "prop");
                e.setBlock(Material.IRON_BLOCK.createBlockData());
                e.setTeleportDuration(3);
                e.setTransformation(new Transformation(new Vector3f(-0.15f, 0, -1.2f), new Quaternionf(), new Vector3f(0.3f, 3.2f, 2.4f),
                        new Quaternionf()));
            }));
        }
        SmpGojo.sound(c, "block.iron_door.open", 2f, 0.6f);
        pl.later(6, () -> {
            for (int k = 0; k < doors.size(); k++) {
                Location at = c.clone().add(side.clone().multiply((k == 0 ? -1 : 1) * 0.3));
                at.setYaw(yaw);
                doors.get(k).teleport(at);
            }
        });
        pl.later(9, () -> {
            List<Player> vs = pl.viewers(c, 30);
            if (t.isValid()) {
                pl.cine.hitStop(List.of(t), 4);
                pl.cine.impact(vs, List.of(t), Cine.WHITE_BLACK);
                pl.hit(p, t, pl.cfg("damage.portes", 14), "portes");
                Draw.blood(t, 40);
                pl.later(4, () -> t.setVelocity(new Vector(0, 1.1, 0)));
            }
            SmpGojo.sound(c, "block.iron_door.close", 3f, 0.5f);
            SmpGojo.sound(c, "block.anvil.land", 2f, 0.6f);
            Draw.fx(c.clone().add(0, 1.5, 0), Particle.CRIT, 40, 0.3, 0.4);
            pl.cine.shake(vs, 2, 2);
        });
        pl.later(30, () -> {
            for (BlockDisplay d : doors) d.remove();
        });
    }

    void quit(Player p) {
        jackpot.remove(p.getUniqueId());
    }
}
