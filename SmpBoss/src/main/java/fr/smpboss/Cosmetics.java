package fr.smpboss;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Les cosmétiques des reliques équipées, entièrement en particules (aucune entité, rien qui puisse traîner
 * ou tomber par terre) : couronnes, cornes, ailes, cape, feu follet, orbes, halo, braises...
 * Les formes sont dessinées dans le repère du joueur (x = gauche, y = haut, z = avant) et tournent avec lui.
 */
final class Cosmetics {

    private static final class Worn {
        List<String> visuals = new ArrayList<>();
        float bodyYaw;
        Location last;
        int t;
    }

    private static final Color[] LEAVES = {Color.fromRGB(0xd9771f), Color.fromRGB(0xb5481c), Color.fromRGB(0xe8b23a),
            Color.fromRGB(0x8a3b12), Color.fromRGB(0xf0c75e)};
    private static final Color GOLD = Color.fromRGB(255, 200, 40), GOLD_DARK = Color.fromRGB(200, 140, 20),
            BLACK = Color.fromRGB(25, 20, 25), BLOOD = Color.fromRGB(150, 0, 10), RED = Color.fromRGB(220, 30, 20),
            BONE = Color.fromRGB(235, 228, 205), ORANGE = Color.fromRGB(255, 120, 10);

    private final SmpBoss plugin;
    private final Map<UUID, Worn> worn = new HashMap<>();
    private int clock;

    Cosmetics(SmpBoss plugin) {
        this.plugin = plugin;
    }

    /** Gardé pour /boss reload (les anciens modèles 3D n'existent plus). */
    void loadModels() {
    }

    // ================================================================= quels visuels

    private List<String> wanted(Player p) {
        List<String> out = new ArrayList<>();
        if (!plugin.getConfig().getBoolean("cosmetics.enabled", true)) return out;
        if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR || p.isInvisible()) return out;
        if (p.isSleeping()) return out;
        if (plugin.store().of(p.getUniqueId()).hideCosmetics) return out;
        for (String id : plugin.store().of(p.getUniqueId()).equipped) {
            Relic r = plugin.relics().get(id);
            if (r == null || r.visual() == null || r.visual().isBlank()) continue;
            if (r.backpack() && !plugin.getConfig().getBoolean("backpack.show-on-back", true)) continue;
            if (!out.contains(r.visual())) out.add(r.visual());
        }
        return out;
    }

    void refresh(Player p) {
        List<String> want = wanted(p);
        if (want.isEmpty()) {
            worn.remove(p.getUniqueId());
            return;
        }
        Worn w = worn.computeIfAbsent(p.getUniqueId(), k -> {
            Worn n = new Worn();
            n.bodyYaw = p.getYaw();
            n.last = p.getLocation();
            return n;
        });
        w.visuals = want;
    }

    void remove(UUID id) {
        worn.remove(id);
    }

    void removeAll() {
        worn.clear();
    }

    // ================================================================= chaque tick

    void tick() {
        if (++clock % 10 == 0) for (Player p : Bukkit.getOnlinePlayers()) refresh(p);
        for (var e : new ArrayList<>(worn.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            Worn w = e.getValue();
            if (p == null || !p.isOnline() || p.isDead()) {
                worn.remove(e.getKey());
                continue;
            }
            w.t++;
            Location now = p.getLocation();
            if (w.last.getWorld() != now.getWorld()) w.last = now;
            Vector delta = now.toVector().subtract(w.last.toVector());
            boolean moving = delta.clone().setY(0).lengthSquared() > 0.0004;
            // rotation du corps : suit la tête en marchant, sinon reste à moins de 50° de la tête
            float diff = wrap(now.getYaw() - w.bodyYaw);
            if (moving) w.bodyYaw += diff * 0.35f;
            else if (Math.abs(diff) > 50) w.bodyYaw = now.getYaw() - Math.signum(diff) * 50;
            w.bodyYaw = wrap(w.bodyYaw);
            w.last = now;
            // un tick d'avance : les particules restent sur le joueur même quand il court
            Location at = now.clone();
            if (delta.lengthSquared() < 1) at.add(delta);
            List<Player> viewers = viewers(p);
            if (viewers.isEmpty()) continue;
            Draw d = new Draw(at, w.bodyYaw, now.getYaw(), p.isSneaking() ? -0.3 : 0, viewers);
            for (String v : w.visuals) {
                try {
                    draw(v, d, w, moving);
                } catch (RuntimeException ex) {
                    // une particule refusée par cette version : on ignore ce visuel
                }
            }
        }
    }

    /** Ceux qui voient le joueur (lui compris), à moins de 48 blocs. */
    private static List<Player> viewers(Player p) {
        List<Player> out = new ArrayList<>();
        for (Player v : p.getWorld().getPlayers()) {
            if (!v.equals(p) && !v.canSee(p)) continue;
            if (v.getLocation().distanceSquared(p.getLocation()) > 48 * 48) continue;
            out.add(v);
        }
        return out;
    }

    // ================================================================= dessin

    /** Outil de dessin dans le repère du joueur. */
    private static final class Draw {
        final Location feet;
        final double bodyRad, headRad, sneak;
        final List<Player> viewers;

        Draw(Location feet, float bodyYaw, float headYaw, double sneak, List<Player> viewers) {
            this.feet = feet;
            this.bodyRad = Math.toRadians(bodyYaw);
            this.headRad = Math.toRadians(headYaw);
            this.sneak = sneak;
            this.viewers = viewers;
        }

        Location at(boolean head, double x, double y, double z) {
            double rad = head ? headRad : bodyRad;
            double fx = -Math.sin(rad), fz = Math.cos(rad), lx = Math.cos(rad), lz = Math.sin(rad);
            return feet.clone().add(lx * x + fx * z, y + (y > 1.0 ? sneak : 0), lz * x + fz * z);
        }

        void dust(boolean head, double x, double y, double z, Color c, float size) {
            Location l = at(head, x, y, z);
            Particle.DustOptions o = new Particle.DustOptions(c, size);
            for (Player v : viewers) v.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, o);
        }

        void part(Particle p, boolean head, double x, double y, double z, int n, double spread, double speed) {
            Location l = at(head, x, y, z);
            for (Player v : viewers) v.spawnParticle(p, l, n, spread, spread, spread, speed);
        }
    }

    private void draw(String visual, Draw d, Worn w, boolean moving) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int t = w.t;
        switch (visual) {
            // ---------------------------------------------------------- couronne dorée posée sur la tête
            case "crown" -> {
                if (t % 3 != 0) return;
                double spin = t * 0.02;
                for (int k = 0; k < 12; k++) {
                    double a = spin + Math.PI * 2 * k / 12;
                    d.dust(true, Math.cos(a) * 0.3, 1.98, Math.sin(a) * 0.3, GOLD, 0.7f);
                }
                for (int k = 0; k < 5; k++) {
                    double a = spin + Math.PI * 2 * k / 5;
                    d.dust(true, Math.cos(a) * 0.3, 2.1, Math.sin(a) * 0.3, GOLD_DARK, 0.6f);
                }
                d.dust(true, 0, 1.98, 0.31, RED, 0.75f);
                if (t % 24 == 0) d.part(Particle.WAX_OFF, true, 0, 2.05, 0, 1, 0.2, 0);
            }
            // ---------------------------------------------------------- couronne des enfers : noire, pointes en feu
            case "hellcrown" -> {
                if (t % 3 != 0) return;
                double spin = -t * 0.02;
                for (int k = 0; k < 12; k++) {
                    double a = spin + Math.PI * 2 * k / 12;
                    d.dust(true, Math.cos(a) * 0.3, 1.98, Math.sin(a) * 0.3, k % 3 == 0 ? RED : BLACK, 0.75f);
                }
                for (int k = 0; k < 6; k++) {
                    double a = spin + Math.PI * 2 * k / 6;
                    d.dust(true, Math.cos(a) * 0.3, 2.09, Math.sin(a) * 0.3, BLACK, 0.6f);
                    if (t % 6 == 0) d.part(Particle.SMALL_FLAME, true, Math.cos(a) * 0.3, 2.18, Math.sin(a) * 0.3, 1, 0, 0);
                }
            }
            // ---------------------------------------------------------- cornes de démon
            case "horns" -> {
                if (t % 2 != 0) return;
                for (int s = -1; s <= 1; s += 2) {
                    for (int i = 0; i <= 7; i++) {
                        double u = i / 7.0;
                        double x = s * (0.2 + 0.2 * u - 0.05 * u * u);
                        double y = 1.86 + 0.34 * u;
                        double z = 0.04 - 0.18 * u * u;
                        Color c = u > 0.85 ? BONE : u > 0.45 ? RED : BLACK;
                        d.dust(true, x, y, z, c, (float) (0.85 - 0.45 * u));
                    }
                }
            }
            // ---------------------------------------------------------- ailes de démon (battent doucement)
            case "wings" -> {
                if (t % 3 != 0) return;
                double flap = Math.sin(t * 0.12) * 0.07;
                for (int s = -1; s <= 1; s += 2) {
                    // os principal, de l'omoplate vers le haut et l'extérieur
                    for (int i = 0; i <= 10; i++) {
                        double u = i / 10.0;
                        d.dust(false, s * (0.12 + 0.85 * u), 1.3 + 0.38 * Math.sin(u * 2.6) + flap * u, -0.2 - 0.32 * u, BLACK, 0.85f);
                    }
                    // trois doigts qui descendent depuis l'os
                    double[][] fingers = {{0.35, 0.85}, {0.6, 0.95}, {0.85, 1.12}};
                    for (double[] f : fingers) {
                        double ux = (f[0] - 0.12) / 0.85;
                        double topY = 1.3 + 0.38 * Math.sin(ux * 2.6) + flap * ux, topZ = -0.2 - 0.32 * ux;
                        for (int i = 0; i <= 5; i++) {
                            double v = i / 5.0;
                            d.dust(false, s * f[0], topY + (f[1] - topY) * v, topZ - 0.05 * v, BLACK, 0.6f);
                        }
                    }
                    // membrane rouge entre les os
                    for (int i = 0; i < 9; i++) {
                        double u = 0.05 + r.nextDouble() * 0.9;
                        double top = 1.3 + 0.38 * Math.sin(u * 2.6) + flap * u;
                        double bottom = 0.85 + 0.3 * u;
                        double y = bottom + (top - bottom) * r.nextDouble();
                        d.dust(false, s * (0.12 + 0.85 * u), y, -0.22 - 0.32 * u, BLOOD, 0.7f);
                    }
                }
            }
            // ---------------------------------------------------------- cape noire doublée de rouge, flotte en courant
            case "cape" -> {
                if (t % 3 != 0) return;
                double flow = moving ? 0.35 : 0.05;
                for (int i = 0; i <= 6; i++) {
                    double v = i / 6.0; // 0 = épaules, 1 = genoux
                    double y = 1.4 - 0.9 * v;
                    double z = -0.18 - flow * v * v - 0.03 * Math.sin(t * 0.2 + v * 3);
                    for (int j = 0; j <= 4; j++) {
                        double x = -0.26 + 0.13 * j;
                        boolean edge = j == 0 || j == 4 || i == 6;
                        d.dust(false, x, y, z, edge ? RED : BLACK, 0.8f);
                    }
                }
            }
            // ---------------------------------------------------------- feu follet d'âme qui tourne autour de la tête
            case "wisp" -> {
                double a = t * 0.12;
                d.part(Particle.SOUL_FIRE_FLAME, false, Math.cos(a) * 0.65, 1.85 + Math.sin(t * 0.1) * 0.1, Math.sin(a) * 0.65, 1, 0, 0);
                if (t % 12 == 0) d.part(Particle.SOUL, false, Math.cos(a) * 0.65, 1.85, Math.sin(a) * 0.65, 1, 0.05, 0.01);
            }
            // ---------------------------------------------------------- crâne maudit : âmes sur l'épaule droite
            case "skull" -> {
                if (t % 4 == 0) d.part(Particle.SOUL, false, -0.38, 1.55, 0, 1, 0.08, 0.01);
                if (t % 3 == 0) {
                    double a = t * 0.15;
                    d.dust(false, -0.38 + Math.cos(a) * 0.18, 1.6, Math.sin(a) * 0.18, Color.fromRGB(60, 60, 60), 0.7f);
                    d.dust(false, -0.38, 1.62, 0.12, Color.fromRGB(120, 255, 200), 0.5f);
                }
            }
            // ---------------------------------------------------------- lanterne à la ceinture
            case "lantern" -> {
                if (t % 3 == 0) d.dust(false, -0.3, 0.62, 0.02, ORANGE, 1.1f);
                if (t % 8 == 0) d.part(Particle.SMALL_FLAME, false, -0.3, 0.66, 0.02, 1, 0.02, 0);
            }
            // ---------------------------------------------------------- amulette sur le torse
            case "amulet" -> {
                if (t % 4 != 0) return;
                d.dust(false, 0, 1.25, 0.16, GOLD, 0.6f);
                d.dust(false, 0.07, 1.15, 0.16, GOLD, 0.6f);
                d.dust(false, -0.07, 1.15, 0.16, GOLD, 0.6f);
                d.dust(false, 0, 1.05, 0.16, GOLD, 0.6f);
                d.dust(false, 0, 1.15, 0.17, ORANGE, 0.8f);
            }
            // ---------------------------------------------------------- orbes de magma en orbite
            case "orbs" -> {
                for (int k = 0; k < 3; k++) {
                    double a = t * 0.1 + Math.PI * 2 * k / 3;
                    double y = 1.0 + Math.sin(t * 0.08 + k) * 0.1;
                    d.dust(false, Math.cos(a) * 0.8, y, Math.sin(a) * 0.8, ORANGE, 1.2f);
                    if (t % 4 == k) d.part(Particle.FLAME, false, Math.cos(a) * 0.8, y, Math.sin(a) * 0.8, 1, 0, 0);
                }
            }
            // ---------------------------------------------------------- sac : un peu de poussière sur le dos
            case "backpack" -> {
                if (t % 10 == 0) d.dust(false, 0, 1.05, -0.3, Color.fromRGB(120, 75, 35), 1.4f);
            }
            // ---------------------------------------------------------- halo rouge au-dessus de la tête
            case "halo" -> {
                if (t % 2 != 0) return;
                for (int k = 0; k < 4; k++) {
                    double a = t * 0.1 + Math.PI * 2 * k / 4;
                    d.dust(true, Math.cos(a) * 0.32, 2.25, Math.sin(a) * 0.32, Color.fromRGB(220, 10, 20), 0.9f);
                }
                if (t % 20 == 0) d.part(Particle.CRIMSON_SPORE, true, 0, 2.2, 0, 3, 0.2, 0);
            }
            // ---------------------------------------------------------- braises qui tournent autour du joueur
            case "embers" -> {
                if (t % 2 != 0) return;
                for (int k = 0; k < 2; k++) {
                    double a = t * 0.18 + Math.PI * k;
                    d.dust(false, Math.cos(a) * 0.55, 0.8 + Math.sin(t * 0.05 + k) * 0.5, Math.sin(a) * 0.55,
                            Color.fromRGB(255, 70 + r.nextInt(60), 0), 0.8f);
                }
                if (moving && t % 4 == 0) d.part(Particle.LAVA, false, 0, 0.1, 0, 1, 0.1, 0);
            }
            // ---------------------------------------------------------- empreintes de flammes
            case "flames" -> {
                if (moving && t % 2 == 0) {
                    d.part(Particle.FLAME, false, r.nextDouble(-0.15, 0.15), 0.05, 0, 1, 0.02, 0.005);
                    d.part(Particle.SMALL_FLAME, false, 0, 0.1, 0, 1, 0.12, 0.01);
                } else if (!moving && t % 10 == 0) {
                    d.part(Particle.SMALL_FLAME, false, 0, 0.05, 0, 1, 0.2, 0.015);
                }
            }
            // ---------------------------------------------------------- feuilles d'automne
            case "boots" -> {
                if (moving && t % 2 == 0) {
                    d.dust(false, r.nextDouble(-0.2, 0.2), 0.15, r.nextDouble(-0.2, 0.2), LEAVES[r.nextInt(LEAVES.length)],
                            (float) r.nextDouble(0.6, 1.0));
                } else if (!moving && t % 4 == 0) {
                    for (int k = 0; k < 2; k++) {
                        double a = t * 0.25 + Math.PI * k;
                        d.dust(false, Math.cos(a) * 0.45, 0.15 + Math.sin(t * 0.1) * 0.08, Math.sin(a) * 0.45,
                                LEAVES[(t / 4 + k) % LEAVES.length], 0.7f);
                    }
                }
            }
            // ---------------------------------------------------------- ❂ PRESTIGE : couronne du Roi Éternel
            // grande couronne d'or à pointes et rubis, cercle de runes doré qui tourne au sol, étincelles qui montent
            case "eternalcrown" -> {
                double spin = t * 0.03;
                if (t % 2 == 0) {
                    for (int k = 0; k < 16; k++) {
                        double a = spin + Math.PI * 2 * k / 16;
                        d.dust(true, Math.cos(a) * 0.34, 1.97, Math.sin(a) * 0.34, k % 2 == 0 ? GOLD : GOLD_DARK, 0.75f);
                    }
                    for (int k = 0; k < 8; k++) {
                        double a = spin + Math.PI * 2 * k / 8;
                        double h = k % 2 == 0 ? 0.26 : 0.16;
                        for (double y = 0.06; y <= h; y += 0.06) {
                            double in = 0.34 - y * 0.25;
                            d.dust(true, Math.cos(a) * in, 1.97 + y, Math.sin(a) * in, GOLD, 0.6f);
                        }
                        d.dust(true, Math.cos(a) * 0.3, 1.97 + h + 0.05, Math.sin(a) * 0.3, k % 2 == 0 ? RED : Color.WHITE, 0.7f);
                    }
                }
                if (t % 3 == 0) {
                    // cercle de runes au sol (deux anneaux qui tournent en sens inverse)
                    for (int k = 0; k < 24; k++) {
                        double a = t * 0.04 + Math.PI * 2 * k / 24;
                        d.dust(false, Math.cos(a) * 1.1, 0.06, Math.sin(a) * 1.1, GOLD, 0.8f);
                    }
                    for (int k = 0; k < 6; k++) {
                        double a1 = -t * 0.04 + Math.PI * 2 * k / 6, a2 = a1 + Math.PI * 2 * 2 / 6;
                        for (double u = 0; u <= 1; u += 0.2) {
                            double x = Math.cos(a1) * 0.8 * (1 - u) + Math.cos(a2) * 0.8 * u, z = Math.sin(a1) * 0.8 * (1 - u) + Math.sin(a2) * 0.8 * u;
                            d.dust(false, x, 0.06, z, RED, 0.6f);
                        }
                    }
                }
                if (t % 4 == 0) d.part(Particle.END_ROD, false, r.nextDouble(-0.9, 0.9), 0.1, r.nextDouble(-0.9, 0.9), 1, 0, 0.03);
                if (t % 30 == 0) d.part(Particle.TOTEM_OF_UNDYING, false, 0, 1.2, 0, 8, 0.4, 0.15);
                if (moving && t % 2 == 0) d.part(Particle.WAX_OFF, false, 0, 0.1, -0.2, 1, 0.1, 0);
            }
            // ---------------------------------------------------------- ❂ PRESTIGE : ailes de l'Éclipse
            // grandes ailes noires aux bords de feu, éclipse noire à couronne de flammes au-dessus de la tête
            case "eclipse" -> {
                if (t % 2 == 0) {
                    double flap = Math.sin(t * 0.1) * 0.12;
                    for (int s = -1; s <= 1; s += 2) {
                        for (int i = 0; i <= 14; i++) {
                            double u = i / 14.0;
                            double x = s * (0.14 + 1.35 * u), y = 1.35 + 0.6 * Math.sin(u * 2.4) + flap * u, z = -0.22 - 0.4 * u;
                            d.dust(false, x, y, z, i > 10 ? ORANGE : BLACK, 0.95f);
                        }
                        double[][] fingers = {{0.45, 0.75}, {0.75, 0.8}, {1.05, 0.95}, {1.35, 1.15}};
                        for (double[] f : fingers) {
                            double ux = (f[0] - 0.14) / 1.35;
                            double topY = 1.35 + 0.6 * Math.sin(ux * 2.4) + flap * ux, topZ = -0.22 - 0.4 * ux;
                            for (int i = 0; i <= 6; i++) {
                                double v = i / 6.0;
                                d.dust(false, s * f[0], topY + (f[1] - topY) * v, topZ - 0.06 * v, v > 0.8 ? RED : BLACK, 0.65f);
                            }
                        }
                        for (int i = 0; i < 6; i++) {
                            double u = 0.05 + r.nextDouble() * 0.95;
                            double top = 1.35 + 0.6 * Math.sin(u * 2.4) + flap * u, bottom = 0.75 + 0.4 * u;
                            d.dust(false, s * (0.14 + 1.35 * u), bottom + (top - bottom) * r.nextDouble(), -0.24 - 0.4 * u, BLOOD, 0.7f);
                        }
                    }
                }
                // l'éclipse au-dessus de la tête
                if (t % 2 == 1) {
                    double cy = 2.55 + Math.sin(t * 0.06) * 0.06;
                    for (int k = 0; k < 7; k++) {
                        double a = Math.PI * 2 * k / 7;
                        d.dust(false, Math.cos(a) * 0.1, cy + Math.sin(a) * 0.1, 0, BLACK, 1.1f);
                    }
                    d.dust(false, 0, cy, 0, BLACK, 1.6f);
                    for (int k = 0; k < 14; k++) {
                        double a = t * 0.08 + Math.PI * 2 * k / 14;
                        d.dust(false, Math.cos(a) * 0.3, cy + Math.sin(a) * 0.3, 0, k % 2 == 0 ? RED : ORANGE, 0.6f);
                    }
                }
                if (t % 5 == 0) d.part(Particle.FLAME, false, r.nextDouble(-0.3, 0.3), 2.55, 0, 1, 0.05, 0.01);
                if (moving && t % 2 == 0) d.part(Particle.SOUL_FIRE_FLAME, false, r.nextDouble(-0.2, 0.2), 0.08, -0.2, 1, 0.05, 0.01);
                if (t % 40 == 0) d.part(Particle.SOUL, false, 0, 1.2, -0.4, 3, 0.4, 0.02);
            }
            default -> {
            }
        }
    }

    private static float wrap(float a) {
        a %= 360f;
        if (a >= 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }
}
