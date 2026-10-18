package fr.smpguard.check;

import fr.smpguard.Check;
import fr.smpguard.Profile;
import fr.smpguard.SmpGuard;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Déplacements, réglés pour ne signaler QUE la vraie triche (aucune alerte pour ce que le jeu permet) :
 * - FLY : reste en l'air plus de 2 s sans jamais redescendre, en disant lui-même au serveur qu'il n'est pas au sol
 *   (vol, "hover"), ou monte bien plus haut qu'un saut sans rien pour le porter.
 * - SPEED : 3 secondes d'affilée nettement au-dessus du maximum possible (effets, attributs, glace, dauphin pris en compte).
 * - NOFALL : dit "je touche le sol" pendant une vraie chute dans le vide.
 *
 * Tout ce qui peut porter ou pousser un joueur coupe les mesures pendant et APRÈS (son élan) :
 * élytres (et fusées), trident, vol, montures, lévitation, chute lente, coups, explosions, charges de vent, masse,
 * slime / lit / miel, bulles, eau, échelles, toiles, échafaudages, neige poudreuse, shulkers, bateaux, entités sous lui,
 * gravité / saut modifiés par un plugin, téléportations, joueurs ralentis par un pouvoir...
 */
public final class MoveCheck {

    private static final class State {
        int air, hover, rise, spoof, fastSeconds;
        double airStartY, fallStartY;
        long lastPush;
        int iceUntil;
        boolean rose, prevGround;
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
        s.rose = false;
        s.lastPush = System.currentTimeMillis();
    }

    /** Le joueur a été poussé (coup, explosion, charge de vent, fusée, plugin...) : on le laisse tranquille un moment. */
    public void onPush(Player p) {
        State s = st(p);
        s.lastPush = System.currentTimeMillis();
        s.air = s.hover = s.spoof = s.fastSeconds = 0;
        s.rose = false;
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
                s.rose = false;
                continue;
            }
            double w = plugin.lag().weight(prof);
            if (w < 0.6) { // connexion ou serveur pas assez stables pour conclure quoi que ce soit
                s.air = s.hover = s.rise = s.spoof = s.fastSeconds = 0;
                s.rose = false;
                continue;
            }
            BoundingBox bb = p.getBoundingBox();
            boolean ground = supported(p.getWorld(), bb);
            boolean special = special(p, bb);
            if (onIce(p.getWorld(), bb)) s.iceUntil = now + 60;
            boolean glider = wearsGlider(p);

            // ---------------- fly
            if (plugin.settings().limits(Check.FLY).enabled()) {
                if (!ground && !special && !glider) {
                    if (s.air++ == 0) {
                        s.airStartY = p.getLocation().getY();
                        // décolle d'un slime, d'un lit ou du miel : rebond normal
                        Material under = p.getLocation().clone().add(0, -1.2, 0).getBlock().getType();
                        if (under == Material.SLIME_BLOCK || Tag.BEDS.isTagged(under) || under == Material.HONEY_BLOCK) {
                            onPush(p);
                            continue;
                        }
                    }
                    // hover : plus de 2 s en l'air sans redescendre, ET le client dit lui-même qu'il est en l'air
                    Vector y0 = prof.at(0), y10 = prof.at(10);
                    boolean notFalling = y0 != null && y10 != null && y0.getY() - y10.getY() > -0.1;
                    if (s.air > 40 && notFalling && !p.isOnGround()) {
                        if (++s.hover >= 20) {
                            s.hover = 0;
                            plugin.alerts().flag(p, prof, Check.FLY, 0.8 * w, plugin.texts().raw("detail.fly-hover")
                                    .replace("<seconds>", String.format(Locale.US, "%.1f", s.air / 20.0)));
                        }
                    } else {
                        s.hover = 0;
                    }
                    // monte nettement plus haut qu'un saut (1,25 bloc) sans jamais toucher le sol (une fois par saut)
                    double jump = 1.35 + 0.6 * amp(p, PotionEffectType.JUMP_BOOST);
                    double climbed = p.getLocation().getY() - s.airStartY;
                    if (!s.rose && climbed > jump + 1.2) {
                        s.rose = true;
                        plugin.alerts().flag(p, prof, Check.FLY, 0.8 * w, plugin.texts().raw("detail.fly-rise")
                                .replace("<height>", String.format(Locale.US, "%.1f", climbed)));
                    }
                } else {
                    s.air = s.hover = 0;
                    s.rose = false;
                }
            }

            // ---------------- step : monté de plus d'un demi-bloc+ en UN tick, du sol au sol, sans sauter (StepUp)
            if (plugin.settings().limits(Check.FLY).enabled()) {
                Vector n0 = prof.at(0), n1 = prof.at(1);
                var stepAttr = p.getAttribute(Attribute.STEP_HEIGHT);
                double step = stepAttr == null ? 0.6 : stepAttr.getValue();
                if (n0 != null && n1 != null && s.prevGround && ground && !special && n0.getY() - n1.getY() > step + 0.08) {
                    plugin.alerts().flag(p, prof, Check.FLY, 0.7 * w, plugin.texts().raw("detail.step")
                            .replace("<height>", String.format(Locale.US, "%.2f", n0.getY() - n1.getY())));
                }
            }
            s.prevGround = ground;

            // ---------------- nofall : "je touche le sol" en pleine chute dans le vide (au moins 4 blocs)
            if (plugin.settings().limits(Check.NOFALL).enabled() && !glider) {
                Vector a = prof.at(0), b = prof.at(2);
                boolean falling = a != null && b != null && b.getY() - a.getY() > 0.3;
                if (!falling) s.fallStartY = p.getLocation().getY();
                boolean deep = s.fallStartY - p.getLocation().getY() > 4;
                if (p.isOnGround() && !ground && !special && falling && deep && !nearGround(p.getWorld(), bb)) {
                    if (++s.spoof >= 10) {
                        s.spoof = 0;
                        plugin.alerts().flag(p, prof, Check.NOFALL, 1.0 * w, plugin.texts().raw("detail.nofall"));
                    }
                } else if (ground) {
                    s.spoof = 0;
                }
            }

            // ---------------- speed (une fois par seconde, 3 secondes d'affilée nettement trop vite)
            if (now % 20 == 0 && plugin.settings().limits(Check.SPEED).enabled() && prof.historySize() > 21) {
                double dist = prof.moved(20);
                double limit = plugin.settings().num(Check.SPEED, "max-blocks-per-second", 8.6);
                var attr = p.getAttribute(Attribute.MOVEMENT_SPEED);
                if (attr != null) limit *= Math.max(1, attr.getValue() / 0.13);
                if (now < s.iceUntil) limit *= 3.5;
                if (amp(p, PotionEffectType.DOLPHINS_GRACE) > 0) limit *= 2.2;
                if (amp(p, PotionEffectType.SPEED) > 0) limit *= 1 + 0.25 * amp(p, PotionEffectType.SPEED);
                limit *= 1.2; // marge : seule la vraie triche dépasse
                if (dist > limit && !special) {
                    if (++s.fastSeconds >= 3) {
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
        if (!plugin.analysed(p) || p.isDead() || p.isSleeping() || plugin.frozen(p) || plugin.isBedrock(p)
                || plugin.scripted(p)) return true;
        long now = System.currentTimeMillis();
        // tout ce qui porte ou propulse : exempté pendant ET un moment après (il garde son élan)
        if (p.getAllowFlight() || p.isFlying() || p.isGliding() || p.isRiptiding() || p.isInsideVehicle() || p.isClimbing()
                || p.isSwimming() || p.isInWater() || p.isInLava()
                || p.hasPotionEffect(PotionEffectType.LEVITATION) || p.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                || alteredPhysics(p)) {
            s.lastPush = now;
            s.fastSeconds = 0;
            return true;
        }
        if (now - s.lastPush < plugin.settings().num(Check.FLY, "push-grace-ms", 3500)) {
            s.fastSeconds = 0;
            return true;
        }
        return false;
    }

    /** Gravité ou saut modifiés (pouvoir, plugin, Sandevistan...) : les règles normales ne s'appliquent plus. */
    private static boolean alteredPhysics(Player p) {
        AttributeInstance g = p.getAttribute(Attribute.GRAVITY);
        if (g != null && Math.abs(g.getValue() - 0.08) > 0.005) return true;
        AttributeInstance j = p.getAttribute(Attribute.JUMP_STRENGTH);
        return j != null && Math.abs(j.getValue() - 0.42) > 0.02;
    }

    /** Élytres (ou tout objet qui permet de planer) sur le dos : planer / tomber lentement est normal. */
    private static boolean wearsGlider(Player p) {
        ItemStack chest = p.getInventory().getChestplate();
        if (chest == null || chest.getType().isAir()) return false;
        if (chest.getType() == Material.ELYTRA) return true;
        try {
            return chest.hasData(io.papermc.paper.datacomponent.DataComponentTypes.GLIDER);
        } catch (Throwable t) {
            return false;
        }
    }

    private static int amp(Player p, PotionEffectType t) {
        PotionEffect e = p.getPotionEffect(t);
        return e == null ? 0 : e.getAmplifier() + 1;
    }

    /** Un bloc solide sous ses pieds (vraie forme de collision : dalles, barrières, tapis...). */
    private static boolean supported(World w, BoundingBox bb) {
        return solidIn(w, new BoundingBox(bb.getMinX() - 0.05, bb.getMinY() - 0.15, bb.getMinZ() - 0.05,
                bb.getMaxX() + 0.05, bb.getMinY() + 0.01, bb.getMaxZ() + 0.05));
    }

    /** Le sol est tout proche (moins d'un bloc) : un "je touche le sol" peut être juste un arrondi du client. */
    private static boolean nearGround(World w, BoundingBox bb) {
        return solidIn(w, new BoundingBox(bb.getMinX(), bb.getMinY() - 1.2, bb.getMinZ(), bb.getMaxX(), bb.getMinY(), bb.getMaxZ()));
    }

    private static boolean solidIn(World w, BoundingBox box) {
        for (int x = (int) Math.floor(box.getMinX()); x <= Math.floor(box.getMaxX()); x++) {
            for (int z = (int) Math.floor(box.getMinZ()); z <= Math.floor(box.getMaxZ()); z++) {
                for (int y = (int) Math.floor(box.getMinY()) - 1; y <= Math.floor(box.getMaxY()); y++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (!b.isCollidable()) continue;
                    for (BoundingBox part : b.getCollisionShape().getBoundingBoxes()) {
                        if (part.clone().shift(x, y, z).overlaps(box)) return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Eau, lave, échelles, toiles, slime, miel, neige poudreuse, bulles, échafaudages, shulkers (boîtes qui s'ouvrent),
     * et toute entité sur laquelle on peut tenir (bateau, minecart, shulker, monture, autre joueur...).
     */
    private static boolean special(Player p, BoundingBox bb) {
        World w = p.getWorld();
        BoundingBox around = bb.clone().expand(0.5, 0.7, 0.5);
        for (int x = (int) Math.floor(around.getMinX()); x <= Math.floor(around.getMaxX()); x++) {
            for (int y = (int) Math.floor(around.getMinY()); y <= Math.floor(around.getMaxY()); y++) {
                for (int z = (int) Math.floor(around.getMinZ()); z <= Math.floor(around.getMaxZ()); z++) {
                    Block b = w.getBlockAt(x, y, z);
                    Material m = b.getType();
                    if (b.isLiquid() || Tag.CLIMBABLE.isTagged(m) || Tag.BEDS.isTagged(m) || Tag.SHULKER_BOXES.isTagged(m)
                            || m == Material.COBWEB || m == Material.SLIME_BLOCK || m == Material.HONEY_BLOCK
                            || m == Material.POWDER_SNOW || m == Material.BUBBLE_COLUMN || m == Material.SWEET_BERRY_BUSH
                            || m == Material.SCAFFOLDING || m == Material.WATER || m == Material.LAVA || m == Material.KELP
                            || m == Material.KELP_PLANT || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS
                            || m == Material.PISTON_HEAD || m == Material.MOVING_PISTON) return true;
                    if (b.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged()) return true;
                }
            }
        }
        for (Entity e : p.getNearbyEntities(1.5, 2.5, 1.5)) {
            if (e instanceof Boat || e instanceof Shulker || e instanceof Minecart) return true;
            // debout sur une entité (monture, autre joueur, mob...)
            if (e instanceof LivingEntity && e.getBoundingBox().getMaxY() <= bb.getMinY() + 0.2
                    && e.getBoundingBox().clone().expand(0.3, 0.3, 0.3).overlaps(bb)) return true;
        }
        return false;
    }

    private static boolean onIce(World w, BoundingBox bb) {
        Block b = w.getBlockAt((int) Math.floor((bb.getMinX() + bb.getMaxX()) / 2), (int) Math.floor(bb.getMinY() - 0.5),
                (int) Math.floor((bb.getMinZ() + bb.getMaxZ()) / 2));
        return Tag.ICE.isTagged(b.getType()) || b.getType() == Material.SOUL_SAND || b.getType() == Material.SOUL_SOIL
                || b.getType() == Material.SLIME_BLOCK;
    }
}
