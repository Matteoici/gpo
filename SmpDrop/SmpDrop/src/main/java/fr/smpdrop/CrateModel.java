package fr.smpdrop;

import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * La caisse et son parachute, construits avec des "block displays" :
 * aucun bloc réel, aucune physique, le client anime lui-même les déplacements
 * (téléportation interpolée) -> chute fluide et zéro lag.
 */
final class CrateModel {

    private static final float CANOPY_LENGTH = 1.35f;
    private static final float CANOPY_ANGLE = (float) Math.toRadians(35);
    private static final int MOVE_INTERVAL = 2;

    private final SmpDrop plugin;
    private final Settings s;
    private final float size;
    private final List<Display> moving = new ArrayList<>();
    private final List<BlockDisplay> chute = new ArrayList<>();
    private final List<BlockDisplay> straps = new ArrayList<>();
    private BlockDisplay body, lamp, beam;
    private TextDisplay label;
    private Interaction hitbox;

    CrateModel(SmpDrop plugin, Settings s) {
        this.plugin = plugin;
        this.s = s;
        this.size = s.crateSize;
    }

    // ------------------------------------------------------------ construction

    void spawn(Location loc) {
        float S = size;
        body = part(loc, s.body, box(-S / 2, 0, -S / 2, S, S, S));
        if (s.glow) {
            body.setGlowing(true);
            body.setGlowColorOverride(s.glowColor);
        }
        moving.add(body);
        float w = S + 0.04f;
        straps.add(part(loc, s.straps, box(-w / 2, -0.02f, -0.09f, w, w, 0.18f)));
        straps.add(part(loc, s.straps, box(-0.09f, -0.02f, -w / 2, 0.18f, w, w)));
        moving.addAll(straps);
        lamp = part(loc, s.lamp, box(-0.16f, S + 0.02f, -0.16f, 0.32f, 0.22f, 0.32f));
        lamp.setBrightness(new Display.Brightness(15, 15));
        moving.add(lamp);

        // ---- parachute : un panneau central + 4 pans inclinés + 4 cordes
        float H = S + 3.3f;
        float L = CANOPY_LENGTH;
        float a = CANOPY_ANGLE;
        chute.add(part(loc, color(0), new Transformation(new Vector3f(-1, H, -1), new Quaternionf(),
                new Vector3f(2, 0.18f, 2), new Quaternionf())));
        chute.add(part(loc, color(1), new Transformation(new Vector3f(1, H, -1), new Quaternionf().rotationZ(-a),
                new Vector3f(L, 0.18f, 2), new Quaternionf())));
        chute.add(part(loc, color(1), new Transformation(new Vector3f(-1, H, -1),
                new Quaternionf().rotationZ((float) Math.PI + a), new Vector3f(L, 0.18f, 2), new Quaternionf())));
        chute.add(part(loc, color(2), new Transformation(new Vector3f(-1, H, 1), new Quaternionf().rotationX(a),
                new Vector3f(2, 0.18f, L), new Quaternionf())));
        chute.add(part(loc, color(2), new Transformation(new Vector3f(-1, H, -1),
                new Quaternionf().rotationX((float) Math.PI - a), new Vector3f(2, 0.18f, L), new Quaternionf())));
        float corner = S / 2 - 0.06f;
        float outX = 1 + L * (float) Math.cos(a) * 0.92f;
        float outY = H + 0.09f - L * (float) Math.sin(a) * 0.92f;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                chute.add(rope(loc, new Vector3f(sx * corner, S, sz * corner), new Vector3f(sx * outX, outY, sz * 0.94f)));
            }
        }
        moving.addAll(chute);
    }

    private Material color(int i) {
        return s.parachute.get(i % s.parachute.size());
    }

    private BlockDisplay rope(Location loc, Vector3f from, Vector3f to) {
        Vector3f dir = new Vector3f(to).sub(from);
        float len = dir.length();
        Quaternionf rot = new Quaternionf().rotationTo(new Vector3f(0, 1, 0), dir.normalize());
        return part(loc, s.ropes, new Transformation(from, rot, new Vector3f(0.05f, len, 0.05f), new Quaternionf()));
    }

    private static Transformation box(float x, float y, float z, float sx, float sy, float sz) {
        return new Transformation(new Vector3f(x, y, z), new Quaternionf(), new Vector3f(sx, sy, sz), new Quaternionf());
    }

    private BlockDisplay part(Location loc, Material mat, Transformation t) {
        World w = loc.getWorld();
        return w.spawn(loc, BlockDisplay.class, d -> {
            d.setBlock(mat.createBlockData());
            d.setTransformation(t);
            d.setTeleportDuration(MOVE_INTERVAL);
            d.setViewRange(s.viewRange);
            if (s.fullBright) d.setBrightness(new Display.Brightness(15, 15));
            tag(d);
        });
    }

    private void tag(Entity e) {
        e.setPersistent(false);
        e.getPersistentDataContainer().set(plugin.entityKey(), PersistentDataType.BYTE, (byte) 1);
    }

    // ------------------------------------------------------------ animation

    static int moveInterval() {
        return MOVE_INTERVAL;
    }

    void moveTo(Location loc, float yaw) {
        Location l = loc.clone();
        l.setYaw(yaw);
        l.setPitch(0);
        for (Display d : moving) if (d.isValid()) d.teleport(l);
    }

    /** Atterrissage : le parachute se replie et disparaît, le faisceau et le texte apparaissent. */
    void land(Location loc, Component text) {
        for (BlockDisplay d : chute) {
            Transformation t = d.getTransformation();
            Vector3f tr = new Vector3f(t.getTranslation());
            tr.y = 0.05f;
            tr.mul(1.4f, 1, 1.4f);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(14);
            d.setTransformation(new Transformation(tr, t.getLeftRotation(), new Vector3f(0.001f, 0.001f, 0.001f),
                    t.getRightRotation()));
        }
        moving.removeAll(chute);
        List<BlockDisplay> old = new ArrayList<>(chute);
        chute.clear();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> old.forEach(Entity::remove), 16L);

        World w = loc.getWorld();
        if (s.beamHeight > 0) {
            float bw = 0.34f;
            beam = w.spawn(loc, BlockDisplay.class, d -> {
                d.setBlock(s.beam.createBlockData());
                d.setTransformation(box(-bw / 2, size + 0.3f, -bw / 2, bw, s.beamHeight, bw));
                d.setBrightness(new Display.Brightness(15, 15));
                d.setViewRange(s.viewRange * 2);
                tag(d);
            });
        }
        label = w.spawn(loc.clone().add(0, size + 0.75, 0), TextDisplay.class, d -> {
            d.text(text);
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
            d.setShadowed(true);
            d.setViewRange(1.0f);
            d.setBrightness(new Display.Brightness(15, 15));
            tag(d);
        });
        float hb = size + 0.3f;
        hitbox = w.spawn(loc.clone().add(0, -0.1, 0), Interaction.class, i -> {
            i.setInteractionWidth(hb);
            i.setInteractionHeight(hb + 0.1f);
            i.setResponsive(true);
            tag(i);
        });
    }

    /** Ouverture : les sangles sautent, la lampe et le faisceau s'éteignent. */
    void open() {
        for (BlockDisplay d : straps) {
            Transformation t = d.getTransformation();
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(8);
            d.setTransformation(new Transformation(new Vector3f(t.getTranslation()).add(0, 1.2f, 0),
                    new Quaternionf().rotationY(1.2f), new Vector3f(0.001f, 0.001f, 0.001f), t.getRightRotation()));
        }
        List<BlockDisplay> old = new ArrayList<>(straps);
        straps.clear();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> old.forEach(Entity::remove), 10L);
        if (lamp != null) lamp.remove();
        if (beam != null) beam.remove();
        if (body != null) body.setGlowing(false);
    }

    void text(Component c) {
        if (label != null && label.isValid()) label.text(c);
    }

    boolean isPart(Entity e) {
        return e != null && (e.equals(hitbox) || moving.contains(e));
    }

    void remove() {
        for (Display d : moving) d.remove();
        for (BlockDisplay d : chute) d.remove();
        for (BlockDisplay d : straps) d.remove();
        moving.clear();
        chute.clear();
        straps.clear();
        if (beam != null) beam.remove();
        if (label != null) label.remove();
        if (hitbox != null) hitbox.remove();
    }
}
