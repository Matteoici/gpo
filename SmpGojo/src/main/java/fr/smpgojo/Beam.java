package fr.smpgojo;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Une colonne de lumière géante faite de blocs : plusieurs tubes de verre teinté
 * (violet dehors, magenta, rose, blanc) autour d'un pilier blanc éclatant.
 * Elle pend depuis "top" vers le bas sur une hauteur h. Chaque panneau est placé à sa propre position
 * pour que les joueurs le voient même quand la colonne fait des centaines de blocs de large.
 */
final class Beam {

    private record Ring(double factor, float thickness, List<BlockDisplay> parts) {
    }

    private final List<Ring> rings = new ArrayList<>();
    private final List<BlockDisplay> core = new ArrayList<>();
    private final Location top;

    Beam(SmpGojo pl, Location top, double maxR) {
        this.top = top.clone();
        add(pl, maxR, 1.0, 22, Material.PURPLE_STAINED_GLASS);
        add(pl, maxR, 0.86, 20, Material.MAGENTA_STAINED_GLASS);
        add(pl, maxR, 0.68, 16, Material.PINK_STAINED_GLASS);
        add(pl, maxR, 0.48, 12, Material.WHITE_STAINED_GLASS);
        // le cœur blanc : un pilier par panneau de 12 blocs de large pour les très gros rayons
        int coreN = Math.max(1, Math.min(64, (int) Math.ceil(maxR * 0.84 / 24)));
        BlockData white = Material.WHITE_CONCRETE.createBlockData();
        for (int k = 0; k < coreN * coreN; k++) core.add(spawn(pl, white));
        shape(0.01, 0.01, 0);
    }

    private void add(SmpGojo pl, double maxR, double factor, int minPanels, Material m) {
        int panels = Math.max(minPanels, Math.min(120, (int) (2 * Math.PI * maxR * factor / 8)));
        List<BlockDisplay> parts = new ArrayList<>();
        BlockData d = m.createBlockData();
        for (int k = 0; k < panels; k++) parts.add(spawn(pl, d));
        rings.add(new Ring(factor, (float) Math.max(1, Math.min(3, maxR * 0.02)), parts));
    }

    private BlockDisplay spawn(SmpGojo pl, BlockData d) {
        return top.getWorld().spawn(top, BlockDisplay.class, e -> {
            e.setPersistent(false);
            e.getPersistentDataContainer().set(pl.tagKey, PersistentDataType.STRING, "prop");
            e.setBlock(d);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setViewRange(16f);
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.01f, 0.01f, 0.01f), new Quaternionf()));
        });
    }

    /** Rayon r, hauteur h (vers le bas depuis le haut), interpolation en ticks. */
    void shape(double r, double h, int interp) {
        float hf = (float) Math.max(0.01, h);
        for (Ring ring : rings) {
            int n = ring.parts().size();
            float rr = (float) Math.max(0.01, r * ring.factor());
            float w = (float) (2 * Math.PI * rr / n * 1.08);
            float t = (float) Math.min(ring.thickness(), Math.max(0.05, rr * 0.3));
            for (int k = 0; k < n; k++) {
                double th = k * 2 * Math.PI / n;
                Location at = top.clone().add(Math.sin(th) * rr, 0, Math.cos(th) * rr);
                BlockDisplay d = ring.parts().get(k);
                move(d, at, interp);
                set(d, new Transformation(new Vector3f(-w / 2, -hf, -t / 2).rotateY((float) th), new Quaternionf().rotateY((float) th),
                        new Vector3f(w, hf, t), new Quaternionf()), interp);
            }
        }
        // pilier central découpé en carreaux
        int g = (int) Math.round(Math.sqrt(core.size()));
        float side = (float) Math.max(0.02, r * 0.84);
        float cell = side / g;
        for (int i = 0; i < g; i++) {
            for (int j = 0; j < g; j++) {
                double ox = -side / 2 + (i + 0.5) * cell, oz = -side / 2 + (j + 0.5) * cell;
                if (ox * ox + oz * oz > (side / 2.0) * (side / 2.0) * 1.15) {
                    BlockDisplay d = core.get(i * g + j);
                    set(d, new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.001f, 0.001f, 0.001f), new Quaternionf()), interp);
                    continue;
                }
                BlockDisplay d = core.get(i * g + j);
                move(d, top.clone().add(ox, 0, oz), interp);
                set(d, new Transformation(new Vector3f(-cell / 2, -hf, -cell / 2), new Quaternionf(), new Vector3f(cell, hf, cell), new Quaternionf()), interp);
            }
        }
    }

    private static void move(BlockDisplay d, Location at, int interp) {
        if (!d.isValid()) return;
        d.setTeleportDuration(Math.min(59, Math.max(0, interp)));
        d.teleport(at);
    }

    private static void set(BlockDisplay d, Transformation t, int interp) {
        if (!d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(interp);
        d.setTransformation(t);
    }

    void remove() {
        for (Ring r : rings) for (BlockDisplay d : r.parts()) d.remove();
        for (BlockDisplay d : core) d.remove();
    }
}
