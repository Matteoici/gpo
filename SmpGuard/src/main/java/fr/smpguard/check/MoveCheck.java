package fr.smpguard.check;

import fr.smpguard.Check;
import fr.smpguard.Profile;
import fr.smpguard.SmpGuard;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Déplacements (vérifiés côté serveur avec les vrais blocs, jamais avec ce que le client prétend) :
 * - FLY : en l'air depuis plus d'1,5 s sans tomber (vol, "hover", "airwalk"), ou qui monte sans rien pour le porter.
 * - SPEED : va plus vite que le maximum possible avec ses effets, ses bonus et le sol (glace...).
 * - NOFALL : dit au serveur "je touche le sol" alors qu'il tombe dans le vide (pour annuler les dégâts de chute).
 *
 * Tout ce qui peut légitimement pousser ou porter un joueur annule la mesure pendant un moment :
 * coups, explosions, charges de vent, élytres, trident, eau, échelles, toiles, slime, bateaux, effets, téléportations...
 */
public final class MoveCheck {

    private static final class State {
        int air, hover, rise, spoof, fastSeconds;
        double airStartY;
        long lastPush;
        int iceUntil;
    }

    private final SmpGuard plugin;
    private final Map<UUID, State> states = new HashMap<>();

    public MoveCheck(SmpGuard plugin) {
        this.plugin = plugin;
    }

    private State st(Player p) {
        return states.computeIfAbsent(p.getUniqueId(), k -> new State());
    }

    public void forget(Player p) {
        states.remove(p.getUniqueId());
    }

    public void reset(Player p) {
        State s = states.get(p.getUniqueId());
        if (s == null) return;
        s.air = s.hover = s.rise = s.spoof = s.fastSeconds = 0;
        s.lastPush = System.currentTimeMillis();
    }

    /** Le joueur a été poussé (coup, explosion, charge de vent, plugin...) : on le laisse tranquille un moment. */
    public void onPush(Player p) {
        st(p).lastPush = System.currentTimeMillis();
    }

    // ================================================================= chaque tick

    public void tick() {
        int now = plugin.currentTick();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Profile prof = plugin.profile(p);
            if (prof == null) continue;
            State s = st(p);
            if (exempt(p, s)) {
                s.air = s.hover = s.rise = s.spoof = 0;
                continue;
            }
            double w = plugin.lag().weight(prof);
            if (w <= 0) {
                s.air = s.hover = s.rise = s.spoof = 0;
                continue;
            }
            BoundingBox bb = p.getBoundingBox();
            boolean ground = supported(p.getWorld(), bb);
            boolean special = special(p, bb);
            if (onIce(p.getWorld(), bb)) s.iceUntil = now + 40;

            // ---------------- fly
            if (plugin.settings().limits(Check.FLY).enabled()) {
                if (!ground && !special) {
                    if (s.air++ == 0) {
                        s.airStartY = p.getLocation().getY();
                        // décolle d'un slime, d'un lit ou d'une plaque : rebond normal
                        Material under = p.getLocation().clone().add(0, -1.2, 0).getBlock().getType();
                        if (under == Material.SLIME_BLOCK || Tag.BEDS.isTagged(under) || under == Material.HONEY_BLOCK) {
                            s.lastPush = System.currentTimeMillis();
                        }
                    }
                    Vector y0 = prof.at(0), y10 = prof.at(10);
                    if (s.air > 30 && y0 != null && y10 != null && y0.getY() - y10.getY() > -0.15) {
                        if (++s.hover >= 10) {
                            s.hover = 0;
                            plugin.alerts().flag(p, prof, Check.FLY, 1.0 * w, plugin.texts().raw("detail.fly-hover")
                                    .replace("<seconds>", String.format(Locale.US, "%.1f", s.air / 20.0)));
                        }
                    } else {
                        s.hover = 0;
                    }
                    // monte plus haut qu'un saut (1,25 bloc) sans jamais toucher le sol
                    double jump = 1.35 + 0.6 * amp(p, PotionEffectType.JUMP_BOOST);
                    if (p.getLocation().getY() - s.airStartY > jump + 0.4 && s.rise++ == 0) {
                        plugin.alerts().flag(p, prof, Check.FLY, 0.8 * w, plugin.texts().raw("detail.fly-rise")
                                .replace("<height>", String.format(Locale.US, "%.1f", p.getLocation().getY() - s.airStartY)));
                    }
                } else {
                    s.air = s.hover = s.rise = 0;
                }
            }

            // ---------------- nofall : "je touche le sol" en tombant dans le vide
            if (plugin.settings().limits(Check.NOFALL).enabled()) {
                Vector a = prof.at(0), b = prof.at(2);
                boolean falling = a != null && b != null && b.getY() - a.getY() > 0.15;
                if (p.isOnGround() && !ground && !special && falling) {
                    if (++s.spoof >= 6) {
                        s.spoof = 0;
                        plugin.alerts().flag(p, prof, Check.NOFALL, 1.0 * w, plugin.texts().raw("detail.nofall"));
                    }
                } else if (ground) {
                    s.spoof = 0;
                }
            }

            // ---------------- speed (une fois par seconde)
            if (now % 20 == 0 && plugin.settings().limits(Check.SPEED).enabled() && prof.historySize() > 21) {
                double dist = prof.moved(20);
                double limit = plugin.settings().num(Check.SPEED, "max-blocks-per-second", 7.9);
                var attr = p.getAttribute(Attribute.MOVEMENT_SPEED);
                if (attr != null) limit *= Math.max(1, attr.getValue() / 0.13);
                if (now < s.iceUntil) limit *= 2.8;
                if (amp(p, PotionEffectType.DOLPHINS_GRACE) > 0) limit *= 2;
                if (dist > limit && !special) {
                    if (++s.fastSeconds >= 2) {
                        s.fastSeconds = 0;
                        plugin.alerts().flag(p, prof, Check.SPEED, Math.min(1.5, 0.5 + (dist - limit) / limit * 2) * w,
                                plugin.texts().raw("detail.speed").replace("<speed>", String.format(Locale.US, "%.1f", dist))
                                        .replace("<max>", String.format(Locale.US, "%.1f", limit)));
                    }
                } else {
                    s.fastSeconds = 0;
                }
            }
        }
    }

    private boolean exempt(Player p, State s) {
        if (!plugin.analysed(p) || p.isDead() || p.isSleeping() || p.getAllowFlight() || p.isFlying() || p.isGliding()
                || p.isRiptiding() || p.isInsideVehicle() || plugin.frozen(p) || plugin.isBedrock(p) || p.isClimbing()) return true;
        if (System.currentTimeMillis() - s.lastPush < plugin.settings().num(Check.FLY, "push-grace-ms", 2500)) return true;
        return p.hasPotionEffect(PotionEffectType.LEVITATION) || p.hasPotionEffect(PotionEffectType.SLOW_FALLING);
    }

    private static int amp(Player p, PotionEffectType t) {
        PotionEffect e = p.getPotionEffect(t);
        return e == null ? 0 : e.getAmplifier() + 1;
    }

    /** Un bloc solide sous ses pieds (vraie forme de collision : dalles, barrières, tapis...). */
    private static boolean supported(World w, BoundingBox bb) {
        BoundingBox below = new BoundingBox(bb.getMinX() + 0.001, bb.getMinY() - 0.12, bb.getMinZ() + 0.001,
                bb.getMaxX() - 0.001, bb.getMinY() + 0.01, bb.getMaxZ() - 0.001);
        for (int x = (int) Math.floor(below.getMinX()); x <= Math.floor(below.getMaxX()); x++) {
            for (int z = (int) Math.floor(below.getMinZ()); z <= Math.floor(below.getMaxZ()); z++) {
                for (int y = (int) Math.floor(below.getMinY()) - 1; y <= Math.floor(below.getMaxY()); y++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (!b.isCollidable()) continue;
                    for (BoundingBox part : b.getCollisionShape().getBoundingBoxes()) {
                        if (part.clone().shift(x, y, z).overlaps(below)) return true;
                    }
                }
            }
        }
        return false;
    }

    /** Eau, lave, échelles, toiles, slime, miel, neige poudreuse, bulles, bateaux, shulkers, minecarts autour. */
    private static boolean special(Player p, BoundingBox bb) {
        World w = p.getWorld();
        BoundingBox around = bb.clone().expand(0.4, 0.6, 0.4);
        for (int x = (int) Math.floor(around.getMinX()); x <= Math.floor(around.getMaxX()); x++) {
            for (int y = (int) Math.floor(around.getMinY()); y <= Math.floor(around.getMaxY()); y++) {
                for (int z = (int) Math.floor(around.getMinZ()); z <= Math.floor(around.getMaxZ()); z++) {
                    Block b = w.getBlockAt(x, y, z);
                    Material m = b.getType();
                    if (b.isLiquid() || Tag.CLIMBABLE.isTagged(m) || Tag.BEDS.isTagged(m) || m == Material.COBWEB || m == Material.SLIME_BLOCK
                            || m == Material.HONEY_BLOCK || m == Material.POWDER_SNOW || m == Material.BUBBLE_COLUMN
                            || m == Material.SWEET_BERRY_BUSH || m == Material.SCAFFOLDING || m == Material.WATER || m == Material.LAVA
                            || m == Material.KELP || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS) return true;
                    if (b.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged()) return true;
                }
            }
        }
        for (Entity e : p.getNearbyEntities(1.5, 2.5, 1.5)) {
            if (e instanceof Boat || e instanceof Shulker || e instanceof Minecart) return true;
        }
        return false;
    }

    private static boolean onIce(World w, BoundingBox bb) {
        Block b = w.getBlockAt((int) Math.floor((bb.getMinX() + bb.getMaxX()) / 2), (int) Math.floor(bb.getMinY() - 0.5),
                (int) Math.floor((bb.getMinZ() + bb.getMaxZ()) / 2));
        return Tag.ICE.isTagged(b.getType()) || b.getType() == Material.SOUL_SAND || b.getType() == Material.SOUL_SOIL;
    }
}
