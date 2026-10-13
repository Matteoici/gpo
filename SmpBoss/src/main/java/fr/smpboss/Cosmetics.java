package fr.smpboss;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Les cosmétiques visibles des reliques équipées, construits en blocs (aucun pack de textures requis) :
 * sac à dos de la Récolte, couronne flottante, lanterne à la ceinture, feu follet d'âme sur l'épaule,
 * amulette sur le torse, feuilles d'automne sous les bottes, empreintes de flammes.
 *
 * Chaque modèle est un groupe d'affichages qui suivent le joueur à chaque tick (position prédite pour ne pas
 * traîner derrière lui) et tournent avec son corps (rotation du corps estimée comme dans le jeu).
 * Repère d'un modèle : +Z = devant le joueur, +Y = haut, +X = sa gauche.
 */
final class Cosmetics {

    private enum Anchor {
        BACK, CHEST, HIP, HEAD, SHOULDER
    }

    private record Model(String visual, Anchor anchor, List<Display> parts) {
    }

    private static final class Worn {
        final List<Model> models = new ArrayList<>();
        String key = "";
        float bodyYaw;
        Location last;
        int t;
        UUID world;
    }

    private static final Color[] LEAVES = {Color.fromRGB(0xd9771f), Color.fromRGB(0xb5481c), Color.fromRGB(0xe8b23a),
            Color.fromRGB(0x8a3b12), Color.fromRGB(0xf0c75e)};

    private final SmpBoss plugin;
    private final NamespacedKey tag;
    private final Map<UUID, Worn> worn = new HashMap<>();

    Cosmetics(SmpBoss plugin) {
        this.plugin = plugin;
        this.tag = plugin.items().relicKey;
    }

    // ================================================================= quels visuels

    /** Les visuels que le joueur doit porter maintenant. */
    private List<String> wanted(Player p) {
        List<String> out = new ArrayList<>();
        if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR || p.isInvisible()) return out;
        if (plugin.store().of(p.getUniqueId()).hideCosmetics) return out;
        for (String id : plugin.store().of(p.getUniqueId()).equipped) {
            Relic r = plugin.relics().get(id);
            if (r == null || r.visual() == null || r.visual().isBlank()) continue;
            if (r.backpack() && !plugin.getConfig().getBoolean("backpack.show-on-back", true)) continue;
            if (!out.contains(r.visual())) out.add(r.visual());
        }
        return out;
    }

    /** À appeler quand les reliques équipées changent (ou mort, monde, mode de jeu...). */
    void refresh(Player p) {
        List<String> want = wanted(p);
        String key = String.join(",", want);
        Worn w = worn.get(p.getUniqueId());
        if (w != null && w.key.equals(key) && p.getWorld().getUID().equals(w.world) && valid(w)) return;
        remove(p.getUniqueId());
        if (want.isEmpty()) return;
        Worn n = new Worn();
        n.key = key;
        n.bodyYaw = p.getYaw();
        n.last = p.getLocation();
        n.world = p.getWorld().getUID();
        for (String v : want) {
            Model m = build(p, v);
            if (m != null) n.models.add(m);
        }
        worn.put(p.getUniqueId(), n);
        updateVisibility(p, n);
    }

    private static boolean valid(Worn w) {
        for (Model m : w.models) for (Display d : m.parts()) if (!d.isValid()) return false;
        return true;
    }

    void remove(UUID id) {
        Worn w = worn.remove(id);
        if (w == null) return;
        for (Model m : w.models) for (Display d : m.parts()) d.remove();
    }

    void removeAll() {
        for (UUID id : new ArrayList<>(worn.keySet())) remove(id);
    }

    // ================================================================= suivi

    /** Toutes les ticks. */
    void tick() {
        for (var e : new ArrayList<>(worn.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            Worn w = e.getValue();
            if (p == null || !p.isOnline()) {
                remove(e.getKey());
                continue;
            }
            if (!p.getWorld().getUID().equals(w.world) || p.isDead() || !valid(w)) {
                remove(e.getKey());
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (p.isOnline()) refresh(p);
                }, 5L);
                continue;
            }
            w.t++;
            Location now = p.getLocation();
            Vector delta = now.toVector().subtract(w.last.toVector());
            boolean moving = delta.clone().setY(0).lengthSquared() > 0.0004;
            // rotation du corps : suit la tête en marchant, sinon reste à moins de 50° de la tête
            float head = now.getYaw();
            float diff = wrap(head - w.bodyYaw);
            if (moving) w.bodyYaw += diff * 0.35f;
            else if (Math.abs(diff) > 50) w.bodyYaw = head - Math.signum(diff) * 50;
            w.bodyYaw = wrap(w.bodyYaw);
            // position prédite d'un tick (sinon les modèles traînent derrière le joueur)
            Location base = now.clone();
            if (delta.lengthSquared() < 1) base.add(delta);
            w.last = now;
            for (Model m : w.models) {
                Location at = anchor(p, base, m.anchor(), w);
                for (Display d : m.parts()) d.teleport(at);
            }
            effects(p, w, moving, now);
            if (w.t % 20 == 0) updateVisibility(p, w);
        }
    }

    /** Un joueur caché (vanish) : ses cosmétiques le sont aussi. */
    private void updateVisibility(Player p, Worn w) {
        for (Player v : Bukkit.getOnlinePlayers()) {
            boolean see = v.equals(p) || v.canSee(p);
            for (Model m : w.models) {
                for (Display d : m.parts()) {
                    if (see) v.showEntity(plugin, d);
                    else v.hideEntity(plugin, d);
                }
            }
        }
    }

    private Location anchor(Player p, Location feet, Anchor a, Worn w) {
        boolean sneak = p.isSneaking();
        float yaw = w.bodyYaw;
        double rad = Math.toRadians(yaw);
        Vector fwd = new Vector(-Math.sin(rad), 0, Math.cos(rad));
        Vector left = new Vector(Math.cos(rad), 0, Math.sin(rad));
        Location out;
        float pitch = 0;
        switch (a) {
            case BACK -> {
                out = feet.clone().add(0, sneak ? 0.93 : 1.1, 0).add(fwd.clone().multiply(sneak ? -0.3 : -0.15));
                pitch = sneak ? 28 : 0;
            }
            case CHEST -> {
                out = feet.clone().add(0, sneak ? 1.0 : 1.2, 0).add(fwd.clone().multiply(sneak ? 0.32 : 0.14));
                pitch = sneak ? 28 : 0;
            }
            case HIP -> out = feet.clone().add(0, sneak ? 0.62 : 0.78, 0).add(fwd.clone().multiply(sneak ? 0.1 : 0.02));
            case HEAD -> {
                yaw = feet.getYaw();
                out = feet.clone().add(0, p.getEyeHeight() + 0.36 + Math.sin(w.t * 0.08) * 0.025, 0);
            }
            case SHOULDER -> {
                double bob = Math.sin(w.t * 0.1) * 0.08;
                double circle = w.t * 0.05;
                out = feet.clone().add(0, (sneak ? 1.6 : 1.9) + bob, 0)
                        .add(left.clone().multiply(0.58 + Math.cos(circle) * 0.08))
                        .add(fwd.clone().multiply(-0.3 + Math.sin(circle) * 0.08));
                yaw = (w.t * 3f) % 360f; // le feu follet tourne sur lui-même
            }
            default -> out = feet.clone();
        }
        out.setYaw(yaw);
        out.setPitch(pitch);
        return out;
    }

    private void effects(Player p, Worn w, boolean moving, Location now) {
        World world = p.getWorld();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (Model m : w.models) {
            switch (m.visual()) {
                case "flames" -> {
                    if (moving && w.t % 2 == 0) {
                        world.spawnParticle(Particle.FLAME, now.clone().add(r.nextDouble(-0.15, 0.15), 0.05, r.nextDouble(-0.15, 0.15)), 1,
                                0.02, 0, 0.02, 0.005);
                        world.spawnParticle(Particle.SMALL_FLAME, now.clone().add(0, 0.1, 0), 1, 0.15, 0.02, 0.15, 0.01);
                    } else if (!moving && w.t % 10 == 0) {
                        world.spawnParticle(Particle.SMALL_FLAME, now.clone().add(0, 0.05, 0), 1, 0.25, 0, 0.25, 0.015);
                    }
                }
                case "boots" -> {
                    if (moving && w.t % 2 == 0) {
                        Particle.DustOptions leaf = new Particle.DustOptions(LEAVES[r.nextInt(LEAVES.length)], (float) r.nextDouble(0.6, 1.0));
                        world.spawnParticle(Particle.DUST, now.clone().add(r.nextDouble(-0.2, 0.2), 0.15, r.nextDouble(-0.2, 0.2)), 1, 0.05,
                                0.05, 0.05, 0, leaf);
                        if (w.t % 6 == 0) world.spawnParticle(Particle.WAX_ON, now.clone().add(0, 0.1, 0), 1, 0.2, 0.05, 0.2, 0);
                    } else if (!moving && w.t % 4 == 0) {
                        // feuilles qui tournoient autour des chevilles
                        double a = w.t * 0.25;
                        for (int k = 0; k < 2; k++) {
                            double aa = a + Math.PI * k;
                            world.spawnParticle(Particle.DUST, now.clone().add(Math.cos(aa) * 0.45, 0.15 + Math.sin(w.t * 0.1) * 0.08,
                                    Math.sin(aa) * 0.45), 1, 0, 0, 0, 0, new Particle.DustOptions(LEAVES[(w.t / 4 + k) % LEAVES.length], 0.7f));
                        }
                    }
                }
                case "wisp" -> {
                    if (w.t % 4 == 0 && !m.parts().isEmpty()) {
                        Location l = m.parts().get(0).getLocation().add(0, 0.15, 0);
                        world.spawnParticle(Particle.SOUL_FIRE_FLAME, l, 1, 0.05, 0.05, 0.05, 0.003);
                    }
                    if (w.t % 13 == 0 && !m.parts().isEmpty()) {
                        world.spawnParticle(Particle.SOUL, m.parts().get(0).getLocation(), 1, 0.1, 0.1, 0.1, 0.01);
                    }
                }
                case "crown" -> {
                    if (w.t % 25 == 0) {
                        world.spawnParticle(Particle.WAX_OFF, now.clone().add(r.nextDouble(-0.25, 0.25), p.getEyeHeight() + 0.45,
                                r.nextDouble(-0.25, 0.25)), 1, 0, 0, 0, 0);
                    }
                }
                case "lantern" -> {
                    if (w.t % 30 == 0 && !m.parts().isEmpty()) {
                        world.spawnParticle(Particle.SMALL_FLAME, m.parts().get(0).getLocation().add(0, -0.45, 0), 1, 0.05, 0.02, 0.05, 0.002);
                    }
                }
                default -> {
                }
            }
        }
    }

    private static float wrap(float a) {
        a %= 360f;
        if (a >= 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    // ================================================================= modèles

    private Model build(Player p, String visual) {
        Location at = p.getLocation();
        List<Display> parts = new ArrayList<>();
        Anchor anchor;
        switch (visual) {
            case "backpack" -> {
                anchor = Anchor.BACK;
                String model = plugin.getConfig().getString("models.backpack", "");
                if (model != null && !model.isBlank()) {
                    // modèle du pack de textures (si un jour il y en a un)
                    ItemStack it = new ItemStack(Material.BUNDLE);
                    NamespacedKey k = NamespacedKey.fromString(model);
                    if (k != null) {
                        ItemMeta meta = it.getItemMeta();
                        meta.setItemModel(k);
                        it.setItemMeta(meta);
                    }
                    parts.add(item(at, it, 0, 0, -0.15f, 0.55f, null));
                } else {
                    // corps en tissu, rabat orange, boucle dorée, poche, couverture roulée, bretelles, mini citrouille
                    parts.add(block(at, Material.BROWN_WOOL, 0, -0.02f, -0.13f, 0.46f, 0.5f, 0.24f, null));
                    parts.add(block(at, Material.ORANGE_WOOL, 0, 0.245f, -0.13f, 0.48f, 0.05f, 0.26f, null));
                    parts.add(block(at, Material.ORANGE_WOOL, 0, 0.15f, -0.262f, 0.48f, 0.18f, 0.03f, null));
                    parts.add(block(at, Material.GOLD_BLOCK, 0, 0.085f, -0.282f, 0.07f, 0.06f, 0.02f, null));
                    parts.add(block(at, Material.BROWN_TERRACOTTA, 0, -0.15f, -0.27f, 0.32f, 0.15f, 0.06f, null));
                    parts.add(block(at, Material.RED_WOOL, 0, 0.33f, -0.12f, 0.56f, 0.12f, 0.12f, null));
                    parts.add(block(at, Material.BLACK_WOOL, 0.18f, 0.33f, -0.12f, 0.03f, 0.135f, 0.135f, null));
                    parts.add(block(at, Material.BLACK_WOOL, -0.18f, 0.33f, -0.12f, 0.03f, 0.135f, 0.135f, null));
                    for (float x : new float[]{0.15f, -0.15f}) {
                        parts.add(block(at, Material.BROWN_TERRACOTTA, x, 0.325f, 0.12f, 0.07f, 0.02f, 0.27f, null));
                        parts.add(block(at, Material.BROWN_TERRACOTTA, x, 0.17f, 0.262f, 0.07f, 0.3f, 0.02f, null));
                    }
                    parts.add(block(at, Material.JACK_O_LANTERN, 0.28f, -0.12f, -0.12f, 0.15f, 0.15f, 0.15f, null));
                }
            }
            case "crown" -> {
                anchor = Anchor.HEAD;
                float h = 0.255f;
                parts.add(block(at, Material.GOLD_BLOCK, 0, 0, h, 0.54f, 0.1f, 0.03f, null));
                parts.add(block(at, Material.GOLD_BLOCK, 0, 0, -h, 0.54f, 0.1f, 0.03f, null));
                parts.add(block(at, Material.GOLD_BLOCK, h, 0, 0, 0.03f, 0.1f, 0.48f, null));
                parts.add(block(at, Material.GOLD_BLOCK, -h, 0, 0, 0.03f, 0.1f, 0.48f, null));
                // pointes : losanges dorés (coins + milieu de chaque côté, celui de devant plus grand)
                Quaternionf diamondZ = new Quaternionf().rotateZ((float) Math.toRadians(45));
                Quaternionf diamondX = new Quaternionf().rotateX((float) Math.toRadians(45));
                parts.add(block(at, Material.GOLD_BLOCK, 0, 0.1f, h, 0.1f, 0.1f, 0.03f, diamondZ));
                parts.add(block(at, Material.GOLD_BLOCK, 0, 0.08f, -h, 0.07f, 0.07f, 0.03f, diamondZ));
                parts.add(block(at, Material.GOLD_BLOCK, h, 0.08f, 0, 0.03f, 0.07f, 0.07f, diamondX));
                parts.add(block(at, Material.GOLD_BLOCK, -h, 0.08f, 0, 0.03f, 0.07f, 0.07f, diamondX));
                for (float x : new float[]{h, -h}) {
                    for (float z : new float[]{h, -h}) {
                        parts.add(block(at, Material.GOLD_BLOCK, x, 0.075f, z, 0.06f, 0.06f, 0.06f,
                                new Quaternionf().rotateY((float) Math.toRadians(45)).rotateX((float) Math.toRadians(35))));
                    }
                }
                // pierres : rubis devant, émeraudes sur les côtés, saphir derrière
                parts.add(block(at, Material.REDSTONE_BLOCK, 0, 0, h + 0.022f, 0.06f, 0.06f, 0.02f, null));
                parts.add(block(at, Material.EMERALD_BLOCK, h + 0.022f, 0, 0, 0.02f, 0.05f, 0.05f, null));
                parts.add(block(at, Material.EMERALD_BLOCK, -h - 0.022f, 0, 0, 0.02f, 0.05f, 0.05f, null));
                parts.add(block(at, Material.LAPIS_BLOCK, 0, 0, -h - 0.022f, 0.05f, 0.05f, 0.02f, null));
            }
            case "lantern" -> {
                anchor = Anchor.HIP;
                Lantern data = (Lantern) Material.LANTERN.createBlockData();
                data.setHanging(true);
                // le bloc de lanterne fait 6 px de large : on centre le modèle sur la hanche droite
                BlockDisplay d = blockData(at, data, -0.33f, -0.12f, 0.03f, 0.4f, 0.4f, 0.4f, null);
                d.setBrightness(new Display.Brightness(15, 15));
                parts.add(d);
                parts.add(block(at, Material.BROWN_TERRACOTTA, -0.3f, 0.08f, 0.03f, 0.08f, 0.05f, 0.14f, null));
            }
            case "wisp" -> {
                anchor = Anchor.SHOULDER;
                BlockDisplay d = blockData(at, Material.SOUL_LANTERN.createBlockData(), 0, 0, 0, 0.42f, 0.42f, 0.42f, null);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setGlowing(true);
                d.setGlowColorOverride(Color.fromRGB(0x5ff3ff));
                parts.add(d);
            }
            case "amulet" -> {
                anchor = Anchor.CHEST;
                parts.add(block(at, Material.GOLD_BLOCK, 0.065f, 0.1f, 0, 0.02f, 0.24f, 0.02f,
                        new Quaternionf().rotateZ((float) Math.toRadians(24))));
                parts.add(block(at, Material.GOLD_BLOCK, -0.065f, 0.1f, 0, 0.02f, 0.24f, 0.02f,
                        new Quaternionf().rotateZ((float) Math.toRadians(-24))));
                parts.add(block(at, Material.GOLD_BLOCK, 0, -0.04f, 0.005f, 0.1f, 0.1f, 0.025f,
                        new Quaternionf().rotateZ((float) Math.toRadians(45))));
                parts.add(block(at, Material.ORANGE_STAINED_GLASS, 0, -0.04f, 0.02f, 0.06f, 0.06f, 0.02f,
                        new Quaternionf().rotateZ((float) Math.toRadians(45))));
                parts.add(item(at, new ItemStack(Material.GOLDEN_CARROT), 0, -0.04f, 0.035f, 0.13f, null));
            }
            default -> {
                // "boots", "flames" : uniquement des particules
                anchor = Anchor.HIP;
            }
        }
        return new Model(visual, anchor, parts);
    }

    // ================================================================= briques

    private void common(Display d) {
        d.setPersistent(false);
        d.getPersistentDataContainer().set(tag, PersistentDataType.STRING, "cosmetic");
        d.setTeleportDuration(1);
        d.setViewRange(0.6f);
        d.setShadowRadius(0);
    }

    private BlockDisplay block(Location at, Material m, float cx, float cy, float cz, float sx, float sy, float sz, Quaternionf rot) {
        return blockData(at, m.createBlockData(), cx, cy, cz, sx, sy, sz, rot);
    }

    /** Un pavé centré en (cx, cy, cz), de taille (sx, sy, sz), tourné de rot autour de son centre. */
    private BlockDisplay blockData(Location at, BlockData data, float cx, float cy, float cz, float sx, float sy, float sz, Quaternionf rot) {
        Quaternionf q = rot == null ? new Quaternionf() : rot;
        Vector3f half = new Vector3f(sx / 2, sy / 2, sz / 2);
        q.transform(half);
        Vector3f translation = new Vector3f(cx, cy, cz).sub(half);
        return at.getWorld().spawn(at, BlockDisplay.class, d -> {
            common(d);
            d.setBlock(data);
            d.setTransformation(new Transformation(translation, q, new Vector3f(sx, sy, sz), new Quaternionf()));
        });
    }

    private ItemDisplay item(Location at, ItemStack it, float cx, float cy, float cz, float scale, Quaternionf rot) {
        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            common(d);
            d.setItemStack(it);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setTransformation(new Transformation(new Vector3f(cx, cy, cz), rot == null ? new Quaternionf() : rot,
                    new Vector3f(scale, scale, scale), new Quaternionf()));
        });
    }
}
