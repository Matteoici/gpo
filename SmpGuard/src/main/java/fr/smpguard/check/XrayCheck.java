package fr.smpguard.check;

import fr.smpguard.Check;
import fr.smpguard.Profile;
import fr.smpguard.Settings;
import fr.smpguard.SmpGuard;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.Set;

/**
 * X-ray / ESP de minerais : on compte, sur une fenêtre de temps, combien de blocs le joueur mine
 * pour trouver chaque filon rare, et si ces filons étaient cachés (aucune grotte, eau ou lave autour,
 * seulement des trous creusés par le joueur lui-même).
 *
 * Un mineur normal trouve beaucoup de filons sur les parois de grottes (visibles) et doit creuser
 * longtemps pour les autres. Un tricheur va droit sur des filons totalement enfermés dans la roche.
 * Les filons dégagés par une explosion (TNT, lit dans le Nether) comptent comme "visibles" : pas de faux positif.
 */
public final class XrayCheck {

    private static final Set<Material> STONE_OVERWORLD = EnumSet.of(Material.STONE, Material.DEEPSLATE, Material.TUFF,
            Material.GRANITE, Material.DIORITE, Material.ANDESITE, Material.CALCITE);
    private static final Set<Material> STONE_NETHER = EnumSet.of(Material.NETHERRACK, Material.BASALT, Material.BLACKSTONE,
            Material.SOUL_SOIL, Material.SOUL_SAND);
    private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH,
            BlockFace.EAST, BlockFace.WEST};

    private final SmpGuard plugin;

    public XrayCheck(SmpGuard plugin) {
        this.plugin = plugin;
    }

    public void onBreak(Player p, Block b) {
        Settings s = plugin.settings();
        if (!s.limits(Check.XRAY).enabled() || p.getGameMode() != GameMode.SURVIVAL || !plugin.analysed(p)) return;
        Profile prof = plugin.profile(p);
        if (prof == null) return;
        World.Environment env = b.getWorld().getEnvironment();
        boolean nether = env == World.Environment.NETHER;
        if (!nether && env != World.Environment.NORMAL) return;
        long now = System.currentTimeMillis();
        prune(prof, now - s.xrayWindowMs);

        Material m = b.getType();
        if (nether ? STONE_NETHER.contains(m) : (STONE_OVERWORLD.contains(m) && b.getY() <= s.xrayMaxY)) {
            prof.stone.addLast(new Profile.Mined(now, nether));
        }
        Double weight = s.xrayOres.get(m);
        if (weight != null && (nether || b.getY() <= s.xrayMaxY + 16) && isNewVein(prof, b, m, now)) {
            boolean hidden = !naturallyExposed(prof, b);
            prof.veins.addLast(new Profile.Vein(now, nether, weight, hidden, b.getX(), b.getY(), b.getZ(), m.name()));
            evaluate(p, prof, nether, weight);
        }
        prof.rememberBroken(b.getX(), b.getY(), b.getZ());
    }

    private static void prune(Profile prof, long since) {
        while (!prof.stone.isEmpty() && prof.stone.peekFirst().time() < since) prof.stone.removeFirst();
        while (!prof.veins.isEmpty() && prof.veins.peekFirst().time() < since) prof.veins.removeFirst();
    }

    /** Un minerai du même type à 3 blocs ou moins d'un minerai cassé dans les 5 dernières minutes = même filon. */
    private static boolean isNewVein(Profile prof, Block b, Material m, long now) {
        boolean fresh = true;
        for (Profile.Ore o : prof.ores) {
            if (!o.type().equals(m.name()) || now - o.time() > 300_000) continue;
            int dx = o.x() - b.getX(), dy = o.y() - b.getY(), dz = o.z() - b.getZ();
            if (dx * dx + dy * dy + dz * dz <= 9) {
                fresh = false;
                break;
            }
        }
        prof.ores.addLast(new Profile.Ore(b.getX(), b.getY(), b.getZ(), now, m.name()));
        while (prof.ores.size() > 300) prof.ores.removeFirst();
        return fresh;
    }

    /** Le minerai touchait-il un vide qui n'a PAS été creusé par le joueur (grotte, eau, lave, explosion) ? */
    private static boolean naturallyExposed(Profile prof, Block b) {
        for (BlockFace f : FACES) {
            Block n = b.getRelative(f);
            if (n.getType().isOccluding()) continue;
            if (!prof.broken.contains(Profile.pack(n.getX(), n.getY(), n.getZ()))) return true;
        }
        return false;
    }

    private void evaluate(Player p, Profile prof, boolean nether, double lastWeight) {
        Settings s = plugin.settings();
        int veins = 0;
        double wsum = 0, hiddenW = 0;
        for (Profile.Vein v : prof.veins) {
            if (v.nether() != nether) continue;
            veins++;
            wsum += v.weight();
            if (v.hidden()) hiddenW += v.weight();
        }
        if (veins < s.xrayMinVeins || wsum <= 0) return;
        int stone = 0;
        for (Profile.Mined m : prof.stone) if (m.nether() == nether) stone++;
        double perVein = stone / wsum;
        double legit = nether ? s.xrayBlocksNether : s.xrayBlocksOverworld;
        double hiddenRatio = hiddenW / wsum;
        if (perVein >= legit || hiddenRatio < s.xrayHiddenRatio) return;
        double severity = Math.min(3, legit / Math.max(5, perVein));
        String detail = plugin.texts().raw("detail.xray")
                .replace("<veins>", String.valueOf(veins))
                .replace("<stone>", String.valueOf(stone))
                .replace("<hidden>", String.valueOf(Math.round(hiddenRatio * 100)))
                .replace("<where>", plugin.texts().raw(nether ? "detail.nether" : "detail.overworld"));
        plugin.alerts().flag(p, prof, Check.XRAY, 0.5 * severity * lastWeight, detail);
    }
}
