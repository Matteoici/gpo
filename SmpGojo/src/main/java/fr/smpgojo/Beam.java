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
 * (violet dehors, rose, puis blanc) autour d'un pilier blanc éclatant.
 * Elle pend depuis "top" vers le bas sur une hauteur h.
 */
final class Beam {

    private record Ring(double factor, int panels, float thickness, List<BlockDisplay> parts) {
    }

    private final List<Ring> rings = new ArrayList<>();
    private final BlockDisplay core;
    private final Location top;

    Beam(SmpGojo pl, Location top) {
        this.top = top.clone();
        add(pl, 1.0, 22, 1.4f, Material.PURPLE_STAINED_GLASS);
        add(pl, 0.86, 20, 1.2f, Material.MAGENTA_STAINED_GLASS);
        add(pl, 0.68, 16, 1.0f, Material.PINK_STAINED_GLASS);
        add(pl, 0.48, 12, 1.0f, Material.WHITE_STAINED_GLASS);
        core = spawn(pl, Material.WHITE_CONCRETE.createBlockData());
        shape(0.01, 0.01, 0);
    }

    private void add(SmpGojo pl, double factor, int panels, float thickness, Material m) {
        List<BlockDisplay> parts = new ArrayList<>();
        BlockData d = m.createBlockData();
        for (int k = 0; k < panels; k++) parts.add(spawn(pl, d));
        rings.add(new Ring(factor, panels, thickness, parts));
    }

    private BlockDisplay spawn(SmpGojo pl, BlockData d) {
        return top.getWorld().spawn(top, BlockDisplay.class, e -> {
            e.setPersistent(false);
            e.getPersistentDataContainer().set(pl.tagKey, PersistentDataType.STRING, "prop");
            e.setBlock(d);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setViewRange(8f);
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.01f, 0.01f, 0.01f), new Quaternionf()));
        });
    }

    /** Rayon r, hauteur h (vers le bas depuis le haut), interpolation en ticks. */
    void shape(double r, double h, int interp) {
        float hf = (float) Math.max(0.01, h);
        for (Ring ring : rings) {
            float rr = (float) Math.max(0.01, r * ring.factor());
            float w = (float) (2 * Math.PI * rr / ring.panels() * 1.1);
            float t = (float) Math.min(ring.thickness(), Math.max(0.05, rr * 0.3));
            for (int k = 0; k < ring.panels(); k++) {
                float th = (float) (k * 2 * Math.PI / ring.panels());
                Vector3f off = new Vector3f(-w / 2, -hf, rr - t / 2).rotateY(th);
                set(ring.parts().get(k), new Transformation(off, new Quaternionf().rotateY(th), new Vector3f(w, hf, t), new Quaternionf()), interp);
            }
        }
        float s = (float) Math.max(0.01, r * 0.42 * 2);
        set(core, new Transformation(new Vector3f(-s / 2, -hf, -s / 2), new Quaternionf(), new Vector3f(s, hf, s), new Quaternionf()), interp);
    }

    private static void set(BlockDisplay d, Transformation t, int interp) {
        if (!d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(interp);
        d.setTransformation(t);
    }

    void remove() {
        for (Ring r : rings) for (BlockDisplay d : r.parts()) d.remove();
        core.remove();
    }
}
