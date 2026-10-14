package fr.smpguard.check;

import fr.smpguard.Check;
import fr.smpguard.Lag;
import fr.smpguard.Profile;
import fr.smpguard.Sight;
import fr.smpguard.SmpGuard;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Locale;

/**
 * Freecam / ESP de coffres / "à travers les murs" : le joueur ouvre un coffre ou casse un bloc
 * qu'il ne peut pas voir depuis ses yeux (un mur plein entre les deux).
 *
 * C'est exactement ce que fait un joueur en caméra libre qui pille un coffre ou mine derrière un mur :
 * sa caméra voit le bloc, mais son corps (la seule chose que le serveur connaît) ne le voit pas.
 * Par défaut, l'ouverture d'un coffre invisible est en plus ANNULÉE : le pillage en freecam devient impossible.
 *
 * Seuls les blocs pleins et opaques bloquent la vue (verre, feuilles, dalles, coffres... ne comptent pas) :
 * en cas de doute, le joueur a raison.
 */
public final class WallCheck {

    private final SmpGuard plugin;

    public WallCheck(SmpGuard plugin) {
        this.plugin = plugin;
    }

    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        if (!(b.getState(false) instanceof Container) && b.getType() != Material.ENDER_CHEST) return;
        Player p = e.getPlayer();
        if (!plugin.settings().limits(Check.WALL).enabled() || !plugin.analysed(p)) return;
        Profile prof = plugin.profile(p);
        if (prof == null) return;
        double w = plugin.lag().weight(prof);
        String name = b.getType().name().toLowerCase(Locale.ROOT);
        if (!visible(p, b)) {
            if (plugin.settings().flag(Check.WALL, "cancel-containers", true)) e.setCancelled(true);
            if (w > 0.2) plugin.alerts().flag(p, prof, Check.WALL, plugin.settings().num(Check.WALL, "points-container", 1.0) * Math.max(0.5, w),
                    plugin.texts().raw("detail.wall-container").replace("<block>", name));
            return;
        }
        // il voit le bloc, mais regarde à l'opposé (la caméra est ailleurs que la tête)
        double angle = Sight.angle(p.getEyeLocation(), b.getLocation().toCenterLocation().toVector());
        if (angle > plugin.settings().num(Check.WALL, "max-angle", 80) && w > 0.3) {
            plugin.alerts().flag(p, prof, Check.WALL, 0.5 * w, plugin.texts().raw("detail.wall-angle").replace("<block>", name)
                    .replace("<angle>", String.valueOf(Math.round(angle))));
        }
    }

    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (!plugin.settings().limits(Check.WALL).enabled() || !plugin.analysed(p)) return;
        Profile prof = plugin.profile(p);
        if (prof == null) return;
        Block b = e.getBlock();
        if (visible(p, b)) return;
        double w = plugin.lag().weight(prof);
        if (plugin.settings().flag(Check.WALL, "cancel-breaks", true)) e.setCancelled(true);
        if (w > 0.2) plugin.alerts().flag(p, prof, Check.WALL, plugin.settings().num(Check.WALL, "points-break", 0.5) * w,
                plugin.texts().raw("detail.wall-break").replace("<block>", b.getType().name().toLowerCase(Locale.ROOT)));
    }

    /** Le bloc est-il visible depuis les yeux du joueur ? (au moins un de ses points) */
    public static boolean visible(Player p, Block b) {
        Location eyeL = p.getEyeLocation();
        Vector eye = eyeL.toVector();
        World w = b.getWorld();
        if (eyeL.getBlockX() == b.getX() && eyeL.getBlockY() == b.getY() && eyeL.getBlockZ() == b.getZ()) return true;
        double[] c = {0.12, 0.5, 0.88};
        for (double x : c) {
            for (double y : c) {
                for (double z : c) {
                    Vector target = new Vector(b.getX() + x, b.getY() + y, b.getZ() + z);
                    if (reaches(w, eye, target, b)) return true;
                }
            }
        }
        return false;
    }

    /** Un rayon depuis les yeux atteint-il le bloc cible sans traverser de bloc plein opaque ? */
    private static boolean reaches(World w, Vector from, Vector to, Block target) {
        Vector dir = to.clone().subtract(from);
        double len = dir.length();
        if (len < 0.05) return true;
        dir.multiply(1 / len);
        Vector pos = from.clone();
        double done = 0;
        for (int i = 0; i < 12; i++) {
            RayTraceResult r = w.rayTraceBlocks(pos.toLocation(w), dir, len - done + 0.2, FluidCollisionMode.NEVER, true);
            if (r == null || r.getHitBlock() == null) return true;
            Block hit = r.getHitBlock();
            if (hit.equals(target) || sameContainer(hit, target)) return true;
            if (hit.getType().isOccluding()) return false;
            Vector hp = r.getHitPosition();
            Vector next = hp.clone();
            int guard = 0;
            while (guard++ < 40 && next.getBlockX() == hit.getX() && next.getBlockY() == hit.getY() && next.getBlockZ() == hit.getZ()) {
                next.add(dir.clone().multiply(0.1));
            }
            done += next.clone().subtract(pos).length();
            if (done >= len) return true;
            pos = next;
        }
        return true;
    }

    /** Les deux moitiés d'un grand coffre. */
    private static boolean sameContainer(Block a, Block b) {
        if (a.getType() != b.getType()) return false;
        if (a.getType() != Material.CHEST && a.getType() != Material.TRAPPED_CHEST) return false;
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ()) == 1 && a.getY() == b.getY();
    }

    @SuppressWarnings("unused")
    private static int delay(Profile p) {
        return Lag.delayTicks(p);
    }
}
