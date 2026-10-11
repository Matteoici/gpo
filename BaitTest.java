package fr.smpguard.check;

import fr.smpguard.Check;
import fr.smpguard.Lag;
import fr.smpguard.Profile;
import fr.smpguard.Sight;
import fr.smpguard.SmpGuard;
import fr.smpguard.Texts;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static fr.smpguard.Texts.ph;

/**
 * Test appât (lancé par le staff) : un faux joueur, visible UNIQUEMENT par le suspect, se promène
 * dans la roche autour de lui, toujours caché derrière des blocs pleins.
 *
 * Sans triche, le suspect ne peut pas le voir : sa visée ne tombe dessus qu'au hasard.
 * Avec un ESP (qui affiche les entités à travers les murs), il le remarque et le regarde.
 * On compare le temps passé à viser l'appât avec le temps passé à viser un point "témoin"
 * (la position symétrique, où il n'y a rien) : le hasard touche les deux pareil, l'ESP seulement l'appât.
 */
public final class BaitTest {

    private final SmpGuard plugin;
    private final CommandSender staff;
    private final Player target;
    private final int durationTicks;
    private final List<Vector> path;
    private final Vector[] history = new Vector[Profile.HISTORY];
    private Zombie bait;
    private BukkitTask task;
    private int tick, samples, locks, controlLocks, hiddenFails;
    private boolean finished;

    private BaitTest(SmpGuard plugin, CommandSender staff, Player target, int seconds, List<Vector> path) {
        this.plugin = plugin;
        this.staff = staff;
        this.target = target;
        this.durationTicks = seconds * 20;
        this.path = path;
    }

    public Player target() {
        return target;
    }

    /** Prépare le parcours et lance le test. null si impossible (joueur à découvert, etc.). */
    public static BaitTest start(SmpGuard plugin, CommandSender staff, Player target, int seconds) {
        List<Vector> path = buildPath(target, plugin.settings().baitRadius);
        if (path.size() < 8) {
            plugin.texts().send(staff, "bait.no-path", ph("player", target.getName()));
            return null;
        }
        BaitTest t = new BaitTest(plugin, staff, target, seconds, path);
        t.spawn();
        plugin.texts().send(staff, "bait.started", ph("player", target.getName()), ph("seconds", seconds));
        return t;
    }

    /** Points tout autour du joueur, dans la roche, invisibles depuis ses yeux. */
    private static List<Vector> buildPath(Player p, double radius) {
        World w = p.getWorld();
        Vector eye = p.getEyeLocation().toVector();
        Vector feet = p.getLocation().toVector();
        int[] dys = {-6, -4, -8, -2, 3, 0};
        List<Vector> out = new ArrayList<>();
        for (int a = 0; a < 360; a += 12) {
            double rad = Math.toRadians(a);
            for (int dy : dys) {
                Vector pos = feet.clone().add(new Vector(Math.cos(rad) * radius, dy, Math.sin(rad) * radius));
                Block foot = pos.toLocation(w).getBlock();
                Block head = foot.getRelative(0, 1, 0);
                if (!foot.getType().isOccluding() || !head.getType().isOccluding()) continue;
                if (pos.getY() < w.getMinHeight() + 2) continue;
                if (Sight.anyClear(w, new Vector[]{eye}, Sight.body(pos, 1.9))) continue;
                out.add(new Vector(foot.getX() + 0.5, foot.getY(), foot.getZ() + 0.5));
                break;
            }
        }
        return out;
    }

    private void spawn() {
        World w = target.getWorld();
        Location at = path.get(0).toLocation(w);
        Player look = null;
        for (Player o : Bukkit.getOnlinePlayers()) if (o != target) look = o;
        final Player skin = look == null ? target : look;
        bait = w.spawn(at, Zombie.class, z -> {
            z.setVisibleByDefault(false);
            z.setPersistent(false);
            z.setAI(false);
            z.setSilent(true);
            z.setInvulnerable(true);
            z.setGravity(false);
            z.setCollidable(false);
            z.setRemoveWhenFarAway(false);
            z.setShouldBurnInDay(false);
            EntityEquipment eq = z.getEquipment();
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(skin);
            head.setItemMeta(meta);
            eq.setHelmet(head);
            eq.setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
            eq.setLeggings(new ItemStack(Material.IRON_LEGGINGS));
            eq.setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                try {
                    eq.setDropChance(slot, 0f);
                } catch (IllegalArgumentException ignored) {
                }
            }
            z.getPersistentDataContainer().set(plugin.key(), org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        });
        target.showEntity(plugin, bait);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Position de l'appât au tick donné : il glisse d'un point au suivant toutes les 15 ticks. */
    private Vector positionAt(int t) {
        int seg = 15;
        int i = (t / seg) % path.size();
        int j = (i + 1) % path.size();
        double f = (t % seg) / (double) seg;
        Vector a = path.get(i), b = path.get(j);
        return a.clone().add(b.clone().subtract(a).multiply(f));
    }

    private void tick() {
        if (finished) return;
        if (!target.isOnline() || bait == null || !bait.isValid() || target.getWorld() != bait.getWorld()) {
            finish(true);
            return;
        }
        tick++;
        Vector pos = positionAt(tick);
        history[tick % history.length] = pos;
        if (tick % 2 == 0) {
            Location l = pos.toLocation(bait.getWorld());
            l.setYaw((tick * 6) % 360);
            bait.teleport(l);
        }
        Profile prof = plugin.profile(target);
        if (prof != null && tick > 30 && tick % 2 == 0 && plugin.lag().weight(prof) >= 0.3) {
            int delay = Lag.delayTicks(prof);
            Vector seen = history[Math.floorMod(tick - delay, history.length)];
            if (seen != null) {
                Location eye = target.getEyeLocation();
                Vector chest = seen.clone().add(new Vector(0, 1.1, 0));
                double dist = chest.distance(eye.toVector());
                // le joueur s'est peut-être déplacé et voit l'appât : cette mesure ne compte pas
                boolean visible = tick % 10 == 0
                        && Sight.anyClear(target.getWorld(), new Vector[]{eye.toVector()}, Sight.body(seen, 1.9));
                if (visible) {
                    hiddenFails++;
                } else if (dist > 2) {
                    double tol = 5 + Math.toDegrees(Math.atan(0.4 / dist));
                    samples++;
                    if (Sight.angle(eye, chest) <= tol) locks++;
                    // point témoin : symétrique de l'appât par rapport au joueur (même distance, rien à voir)
                    Vector center = target.getLocation().toVector();
                    Vector control = center.clone().multiply(2).subtract(seen);
                    control.setY(seen.getY() + 1.1);
                    if (Sight.angle(eye, control) <= tol) controlLocks++;
                }
            }
        }
        if (tick >= durationTicks) finish(false);
    }

    public void cancel() {
        finish(true);
    }

    private void finish(boolean aborted) {
        if (finished) return;
        finished = true;
        if (task != null) task.cancel();
        if (bait != null) bait.remove();
        plugin.baitEnded(this);
        Texts t = plugin.texts();
        if (aborted) {
            t.send(staff, "bait.aborted", ph("player", target.getName()));
            return;
        }
        if (samples < 40) {
            t.send(staff, "bait.not-enough", ph("player", target.getName()), ph("samples", samples));
            return;
        }
        double rate = locks / (double) samples;
        double ctrl = controlLocks / (double) samples;
        String verdictKey;
        double points = 0;
        if (rate >= 0.25 && rate >= ctrl * 4 + 0.05) {
            verdictKey = "bait.verdict-strong";
            points = 2;
        } else if (rate >= 0.12 && rate >= ctrl * 3 + 0.03) {
            verdictKey = "bait.verdict-medium";
            points = 1;
        } else {
            verdictKey = "bait.verdict-clean";
        }
        Profile prof = plugin.profile(target);
        String pct = String.format(Locale.US, "%.0f", rate * 100);
        String pctCtrl = String.format(Locale.US, "%.0f", ctrl * 100);
        Component result = t.prefixed("bait.result", ph("player", target.getName()), ph("rate", pct), ph("control", pctCtrl),
                ph("samples", samples), ph("lag", prof == null ? "?" : Lag.describe(prof)),
                fr.smpguard.Texts.pc("verdict", t.get(verdictKey)));
        staff.sendMessage(result);
        plugin.alerts().staff(result);
        if (prof != null) {
            prof.note("Test appât : " + pct + "% sur l'appât, " + pctCtrl + "% sur le témoin (" + samples + " mesures)");
            if (points > 0) {
                plugin.alerts().flag(target, prof, Check.BAIT, points * Math.max(0.5, plugin.lag().weight(prof)),
                        t.raw("detail.bait").replace("<rate>", pct).replace("<control>", pctCtrl));
            }
        }
        plugin.alerts().log(target.getName() + " | bait | " + pct + "% / témoin " + pctCtrl + "% | " + samples + " mesures");
    }
}
