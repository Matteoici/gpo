package fr.smpboss;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static fr.smpboss.SmpBoss.parse;

/**
 * L'objet gagné apparaît en 3D devant le joueur : il jaillit en tournant, explose de la couleur de sa rareté,
 * flotte quelques secondes avec son nom, puis file dans le joueur. Les révélations d'un même joueur
 * passent l'une après l'autre.
 */
final class Reveal {

    private static final Map<UUID, Long> busyUntil = new HashMap<>();

    private Reveal() {
    }

    static Color color(Rarity r) {
        return switch (r) {
            case RARE -> Color.fromRGB(0x4aa8ff);
            case EPIC -> Color.fromRGB(0xb45cff);
            case LEGENDARY -> Color.fromRGB(0xffd36b);
            case SECRET -> Color.WHITE;
            case PRESTIGE -> Color.fromRGB(0xffd700);
            default -> Color.fromRGB(0xdddddd);
        };
    }

    /** where = petite ligne sous le nom (ex. "Ajoutée à tes reliques"). */
    static void show(SmpBoss plugin, Player p, ItemStack look, Rarity rarity, String nameMini, String where) {
        long now = System.currentTimeMillis();
        long start = Math.max(now, busyUntil.getOrDefault(p.getUniqueId(), 0L));
        int life = 150;
        busyUntil.put(p.getUniqueId(), start + (life + 10) * 50L);
        long delay = (start - now) / 50;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) run(plugin, p, look, rarity, nameMini, where, life);
        }, Math.max(1, delay));
    }

    private static Location front(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().setY(0);
        if (dir.lengthSquared() < 0.01) dir = new Vector(0, 0, 1);
        Location at = eye.clone().add(dir.normalize().multiply(2.2));
        at.setY(eye.getY() - 0.15);
        at.setYaw(eye.getYaw() + 180);
        at.setPitch(0);
        return at;
    }

    private static void run(SmpBoss plugin, Player p, ItemStack look, Rarity rarity, String nameMini, String where, int life) {
        Location at = front(p);
        Color col = color(rarity);
        ItemDisplay item = p.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.getPersistentDataContainer().set(plugin.items().relicKey, PersistentDataType.STRING, "reveal");
            d.setItemStack(look);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setGlowing(rarity.ordinal() >= Rarity.RARE.ordinal());
            d.setGlowColorOverride(col);
            d.setTeleportDuration(2);
            d.setTransformation(new Transformation(new Vector3f(0, -0.8f, 0), new Quaternionf(), new Vector3f(0.01f, 0.01f, 0.01f),
                    new Quaternionf()));
        });
        Component label = parse(nameMini).appendNewline().append(parse(rarity.tag));
        if (where != null && !where.isBlank()) label = label.appendNewline().append(parse(where));
        Component finalLabel = label;
        TextDisplay text = p.getWorld().spawn(at.clone().add(0, 0.95, 0), TextDisplay.class, d -> {
            d.setPersistent(false);
            d.getPersistentDataContainer().set(plugin.items().relicKey, PersistentDataType.STRING, "reveal");
            d.setBillboard(Display.Billboard.CENTER);
            d.text(finalLabel);
            d.setBackgroundColor(Color.fromARGB(150, 10, 6, 14));
            d.setTeleportDuration(2);
            d.setTextOpacity((byte) 0);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.01f, 0.01f, 0.01f), new Quaternionf()));
        });
        sound(p, rarity.ordinal() >= Rarity.LEGENDARY.ordinal() ? "block.beacon.activate" : "block.amethyst_block.chime", 1f, 0.8f);

        int[] t = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            t[0]++;
            int i = t[0];
            if (i > life || !p.isOnline() || !item.isValid()) {
                item.remove();
                text.remove();
                task[0].cancel();
                return;
            }
            // reste devant le joueur
            Location l = front(p);
            item.teleport(l);
            text.teleport(l.clone().add(0, 0.95, 0));
            var w = p.getWorld();
            if (i <= 14) {
                // jaillit en tournant vite, des étincelles se resserrent
                float k = i / 14f;
                float s = 1.4f * (float) (1 - Math.pow(1 - k, 3));
                transform(item, -0.8f * (1 - k), i * 55f, s, 1);
                for (int n = 0; n < 6; n++) {
                    Vector v = Vector.getRandom().subtract(new Vector(0.5, 0.5, 0.5)).normalize().multiply(1.4 * (1 - k) + 0.2);
                    w.spawnParticle(Particle.DUST, l.clone().add(v), 1, 0, 0, 0, 0, new Particle.DustOptions(col, 1.1f), true);
                }
                if (i % 3 == 0) sound(p, "block.note_block.chime", 0.6f, 0.6f + k);
            } else if (i == 15) {
                // éclat de la couleur de la rareté
                if (Particle.FLASH.getDataType() == Color.class) w.spawnParticle(Particle.FLASH, l, 1, 0, 0, 0, 0, Color.WHITE, true);
                else if (Particle.FLASH.getDataType() == Void.class) w.spawnParticle(Particle.FLASH, l, 1, 0, 0, 0, 0, null, true);
                w.spawnParticle(Particle.END_ROD, l, 40, 0.1, 0.1, 0.1, 0.18, null, true);
                w.spawnParticle(Particle.DUST, l, 60, 0.7, 0.7, 0.7, 0, new Particle.DustOptions(col, 1.6f), true);
                if (rarity == Rarity.PRESTIGE) {
                    w.spawnParticle(Particle.TOTEM_OF_UNDYING, l, 250, 0.6, 0.6, 0.6, 0.9, null, true);
                    w.spawnParticle(Particle.FLAME, l, 120, 0.2, 0.2, 0.2, 0.35, null, true);
                    w.spawnParticle(Particle.SOUL_FIRE_FLAME, l, 60, 0.2, 0.2, 0.2, 0.3, null, true);
                    w.strikeLightningEffect(p.getLocation());
                    sound(p, "entity.ender_dragon.death", 1f, 1.2f);
                }
                if (rarity == Rarity.LEGENDARY || rarity == Rarity.SECRET) {
                    w.spawnParticle(Particle.TOTEM_OF_UNDYING, l, 80, 0.4, 0.4, 0.4, 0.6, null, true);
                    sound(p, "entity.firework_rocket.twinkle", 1f, 1f);
                }
                sound(p, switch (rarity) {
                    case SECRET -> "entity.wither.spawn";
                    case PRESTIGE -> "ui.toast.challenge_complete";
                    case LEGENDARY -> "ui.toast.challenge_complete";
                    case EPIC -> "entity.player.levelup";
                    default -> "entity.experience_orb.pickup";
                }, 1f, rarity == Rarity.SECRET ? 0.7f : 1.1f);
                text.setTextOpacity((byte) -1);
                text.setInterpolationDelay(0);
                text.setInterpolationDuration(5);
                text.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1, 1, 1), new Quaternionf()));
            } else if (i < life - 12) {
                // flotte et tourne doucement
                float bob = (float) Math.sin(i * 0.09) * 0.08f;
                transform(item, bob, 825f + (i - 15) * 4.5f, 1.4f, 2);
                if (i % 6 == 0) {
                    w.spawnParticle(Particle.DUST, l.clone().add(0, -0.1, 0), 3, 0.35, 0.35, 0.35, 0,
                            new Particle.DustOptions(col, 0.8f), true);
                }
                if (rarity == Rarity.PRESTIGE && i % 3 == 0) {
                    w.spawnParticle(Particle.DUST, l, 6, 0.6, 0.6, 0.6, 0, new Particle.DustOptions(i % 6 == 0 ? Color.RED : Color.fromRGB(0xffd700), 1.3f), true);
                    w.spawnParticle(Particle.END_ROD, l, 1, 0.5, 0.5, 0.5, 0.02, null, true);
                }
                if ((rarity == Rarity.LEGENDARY || rarity == Rarity.PRESTIGE) && i % 4 == 0) {
                    Component lb = parse(rarity.name(stripTags(nameMini), (i / 4) % 10)).appendNewline().append(parse(rarity.tag));
                    if (where != null && !where.isBlank()) lb = lb.appendNewline().append(parse(where));
                    text.text(lb);
                }
            } else if (i == life - 12) {
                // file dans le joueur
                item.setInterpolationDelay(0);
                item.setInterpolationDuration(10);
                item.setTransformation(new Transformation(new Vector3f(0, -0.4f, 0), new Quaternionf(new AxisAngle4f(6f, 0, 1, 0)),
                        new Vector3f(0.01f, 0.01f, 0.01f), new Quaternionf()));
                text.setInterpolationDelay(0);
                text.setInterpolationDuration(8);
                text.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.01f, 0.01f, 0.01f), new Quaternionf()));
                sound(p, "entity.item.pickup", 1f, 0.7f);
                w.spawnParticle(Particle.DUST, l, 20, 0.3, 0.3, 0.3, 0, new Particle.DustOptions(col, 1.2f), true);
            }
        }, 1L, 1L);
    }

    /** Un nom déjà coloré par Rarity.name(...) : on ne garde que le texte pour recolorer l'arc-en-ciel. */
    private static String stripTags(String mini) {
        return SmpBoss.MM.stripTags(mini);
    }

    private static void transform(ItemDisplay d, float y, float angleDeg, float scale, int interp) {
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(interp);
        d.setTransformation(new Transformation(new Vector3f(0, y, 0),
                new Quaternionf(new AxisAngle4f((float) Math.toRadians(angleDeg), 0, 1, 0)),
                new Vector3f(scale, scale, scale), new Quaternionf()));
    }

    private static void sound(Player p, String key, float vol, float pitch) {
        p.playSound(Sound.sound(Key.key("minecraft", key), Sound.Source.MASTER, vol, pitch));
    }
}
