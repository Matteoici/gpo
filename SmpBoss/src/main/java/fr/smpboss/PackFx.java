package fr.smpboss;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Outils communs des effets du resource pack (modèles, animations image par image, sons, flashs, version sans pack). */
abstract class PackFx {

    static final String NS = "limitless";
    static final char WHITE = '\ue001', ORANGE = '\ue002';

    protected final SmpBoss plugin;
    protected final Ultimates ult;
    protected final Set<Entity> live = new HashSet<>();

    PackFx(SmpBoss plugin, Ultimates ult) {
        this.plugin = plugin;
        this.ult = ult;
    }

    void removeAll() {
        for (Entity e : live) e.remove();
        live.clear();
    }

    // ================================================================= outils

    protected static ItemStack model(String name) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(new NamespacedKey(NS, "fx/" + name));
        it.setItemMeta(m);
        return it;
    }

    protected ItemDisplay sprite(Location at, String name, float sx, float sy, float sz, Display.Billboard bb, Quaternionf rot) {
        Location l = at.clone();
        l.setYaw(0);
        l.setPitch(0);
        ItemDisplay d = at.getWorld().spawn(l, ItemDisplay.class, e -> {
            e.setPersistent(false);
            e.setItemStack(model(name));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setBillboard(bb);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setViewRange(8f);
            e.setShadowRadius(0);
            e.setTeleportDuration(1);
            e.setTransformation(new Transformation(new Vector3f(), rot == null ? new Quaternionf() : rot, new Vector3f(sx, sy, sz), new Quaternionf()));
            e.setVisibleByDefault(false);
        });
        for (Player o : at.getWorld().getPlayers()) if (PackStatus.has(o)) o.showEntity(plugin, d);
        live.add(d);
        return d;
    }

    /** Particules vanilla vues par tout le monde (avec ou sans pack). */
    protected static void fxParticle(World w, Particle pa, Location at, int n, double ox, double oy, double oz, double speed) {
        w.spawnParticle(pa, at, n, ox, oy, oz, speed, null, true);
    }

    /** Joueurs proches qui n'ont PAS le pack : ils ont une version en particules à la place des modèles. */
    protected static List<Player> noPack(Location c, double r) {
        List<Player> out = new ArrayList<>();
        for (Player o : c.getWorld().getPlayers()) if (!PackStatus.has(o) && o.getLocation().distanceSquared(c) <= r * r) out.add(o);
        return out;
    }

    protected static void fallback(Location at, Particle pa, int n, double ox, double oy, double oz, double speed, Object data) {
        List<Player> to = noPack(at, 160);
        if (to.isEmpty()) return;
        var pb = new com.destroystokyo.paper.ParticleBuilder(pa).receivers(to).location(at).count(n).offset(ox, oy, oz).extra(speed).force(true);
        if (data != null) pb.data(data);
        pb.spawn();
    }

    protected static void fallbackSound(Location at, org.bukkit.Sound s, float vol, float pitch) {
        for (Player o : noPack(at, 250)) o.playSound(at, s, vol, pitch);
    }

    protected static void tween(ItemDisplay d, Vector3f tr, Quaternionf rot, Vector3f scale, int ticks) {
        if (!d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(new Transformation(tr, rot == null ? new Quaternionf() : rot, scale, new Quaternionf()));
    }

    protected void later(int ticks, Runnable r) {
        Bukkit.getScheduler().runTaskLater(plugin, r, Math.max(1, ticks));
    }

    protected void kill(ItemDisplay d, int ticks) {
        later(ticks, () -> {
            d.remove();
            live.remove(d);
        });
    }

    /** Apparaît, grossit, reste un peu, puis rétrécit et disparaît. */
    protected ItemDisplay pop(Location at, String name, float sx, float sy, float sz, int grow, int hold, int shrink, Display.Billboard bb, Quaternionf rot) {
        ItemDisplay d = sprite(at, name, sx * 0.05f, sy * 0.05f, sz * 0.05f, bb, rot);
        later(1, () -> tween(d, new Vector3f(), rot, new Vector3f(sx, sy, sz), grow));
        later(1 + grow + hold, () -> tween(d, new Vector3f(), rot, new Vector3f(sx * 0.02f, sy * 0.02f, sz * 0.02f), shrink));
        kill(d, 2 + grow + hold + shrink);
        return d;
    }

    /** Animation image par image (explosions en style anime). */
    protected void flip(Location at, String prefix, int frames, int every, float s0, float s1) {
        ItemDisplay d = sprite(at, prefix + "0", s0, s0, s0, Display.Billboard.CENTER, null);
        later(1, () -> tween(d, new Vector3f(), null, new Vector3f(s1, s1, s1), frames * every));
        for (int f = 1; f < frames; f++) {
            int ff = f;
            later(f * every, () -> {
                if (d.isValid()) d.setItemStack(model(prefix + ff));
            });
        }
        kill(d, frames * every + 1);
    }

    /** Son du pack (stéréo) : chaque joueur proche l'entend, moins fort de loin. */
    protected void sound(Location c, String key, double radius) {
        for (Player o : c.getWorld().getPlayers()) {
            double d = o.getLocation().distance(c);
            if (d > radius || !PackStatus.has(o)) continue;
            o.playSound(o, key, SoundCategory.PLAYERS, (float) Math.max(0.25, 1 - d / radius), 1f);
        }
    }

    protected void stopSound(Location c, String key, double radius) {
        for (Player o : c.getWorld().getPlayers()) if (o.getLocation().distanceSquared(c) <= radius * radius) o.stopSound(key, SoundCategory.PLAYERS);
    }

    /** Flash plein écran (image du pack affichée en titre). */
    protected void flash(Location c, double radius, char glyph, int in, int stay, int out) {
        Component t = Component.text(String.valueOf(glyph)).font(Key.key(NS, "fx")).color(NamedTextColor.WHITE).shadowColor(ShadowColor.shadowColor(0));
        Title title = Title.title(t, Component.empty(), Title.Times.times(Duration.ofMillis(in * 50L), Duration.ofMillis(stay * 50L), Duration.ofMillis(out * 50L)));
        for (Player o : c.getWorld().getPlayers()) if (PackStatus.has(o) && o.getLocation().distanceSquared(c) <= radius * radius) o.showTitle(title);
    }

    protected void dayAgain(Set<UUID> dark) {
        for (UUID id : dark) {
            Player o = Bukkit.getPlayer(id);
            if (o != null) o.resetPlayerTime();
            ult.night.remove(id);
        }
        dark.clear();
    }

    protected static Vector right(Vector dir) {
        Vector r = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (r.lengthSquared() < 1e-4) r = new Vector(1, 0, 0);
        return r.normalize();
    }

    /** Animation image par image, qui peut aussi monter (dy) pendant qu'elle joue. */
    protected void flip(Location at, String prefix, int frames, int every, float s0, float s1, Display.Billboard bb, float dy) {
        ItemDisplay d = sprite(at, prefix + "0", s0, s0, s0, bb, null);
        later(1, () -> tween(d, new Vector3f(0, dy, 0), null, new Vector3f(s1, s1, s1), frames * every));
        for (int f = 1; f < frames; f++) {
            int ff = f;
            later(f * every, () -> {
                if (d.isValid()) d.setItemStack(model(prefix + ff));
            });
        }
        kill(d, frames * every + 1);
    }


    /** Un bout de ruban entre deux points (un peu plus long pour que les bouts se chevauchent), qui s'amincit puis disparaît. */
    protected void ribbon(Location a, Location b, String model) {
        ribbon(a, b, model, 0.75f, 13);
    }

    /** Un bout de ruban de largeur et de durée choisies. */
    protected void ribbon(Location a, Location b, String model, float width, int life) {
        Vector seg = b.toVector().subtract(a.toVector());
        double len = seg.length();
        if (len < 0.05) return;
        Location mid = a.clone().add(seg.clone().multiply(0.5));
        Vector n = seg.clone().normalize();
        Quaternionf q = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), new Vector3f((float) n.getX(), (float) n.getY(), (float) n.getZ()));
        float l = (float) (len * 1.9);
        ItemDisplay d = sprite(mid, model, width, width, l, Display.Billboard.FIXED, q);
        later(1, () -> tween(d, new Vector3f(), q, new Vector3f(0.05f, 0.05f, l), life));
        kill(d, life + 2);
    }

    protected void ribbonOld(Location a, Location b, String model) {
        Vector seg = b.toVector().subtract(a.toVector());
        double len = seg.length();
        if (len < 0.05) return;
        Location mid = a.clone().add(seg.clone().multiply(0.5));
        Vector n = seg.clone().normalize();
        Quaternionf q = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), new Vector3f((float) n.getX(), (float) n.getY(), (float) n.getZ()));
        float l = (float) (len * 1.9);
        ItemDisplay d = sprite(mid, model, 0.75f, 0.75f, l, Display.Billboard.FIXED, q);
        later(1, () -> tween(d, new Vector3f(), q, new Vector3f(0.05f, 0.05f, l), 13));
        kill(d, 15);
    }

    /** Le premier bloc solide sous ce point (jusqu'à "max" blocs), ou null. */
    protected static Location groundBelow(Location c, double max) {
        RayTraceResult r = c.getWorld().rayTraceBlocks(c, new Vector(0, -1, 0), max, FluidCollisionMode.NEVER, true);
        if (r == null) return null;
        Vector h = r.getHitPosition();
        return new Location(c.getWorld(), h.getX(), h.getY(), h.getZ());
    }

    protected static double rnd(double r) {
        return ThreadLocalRandom.current().nextDouble(-r, r);
    }
}
