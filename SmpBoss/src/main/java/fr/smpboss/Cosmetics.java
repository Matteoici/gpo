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

    // palettes des nouveaux styles
    private static final Color NAVY = Color.fromRGB(11, 26, 74), NIGHT = Color.fromRGB(42, 63, 158), STAR = Color.fromRGB(109, 140, 255),
            SILVER = Color.fromRGB(227, 233, 255), PUMPKIN = Color.fromRGB(255, 130, 20), STEM = Color.fromRGB(70, 150, 40),
            VIOLET = Color.fromRGB(150, 60, 220), CRIMSON = Color.fromRGB(200, 10, 40), ASH = Color.fromRGB(90, 85, 90),
            TEAL = Color.fromRGB(60, 220, 200), CREAM = Color.fromRGB(255, 240, 210);

    private void draw(String visual, Draw d, Worn w, boolean moving) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int t = w.t;
        switch (visual) {
            // ---------------------------------------------------------- couronne citrouille : anneau orange, pointes en tiges vertes
            case "crown" -> {
                if (t % 2 != 0) return;
                double spin = t * 0.025, bob = Math.sin(t * 0.07) * 0.03;
                for (int k = 0; k < 10; k++) {
                    double a = spin + Math.PI * 2 * k / 10;
                    d.dust(true, Math.cos(a) * 0.31, 2.0 + bob, Math.sin(a) * 0.31, PUMPKIN, 0.75f);
                }
                for (int k = 0; k < 5; k++) {
                    double a = spin + Math.PI * 2 * k / 5;
                    d.dust(true, Math.cos(a) * 0.29, 2.11 + bob, Math.sin(a) * 0.29, PUMPKIN, 0.6f);
                    d.dust(true, Math.cos(a) * 0.27, 2.22 + bob, Math.sin(a) * 0.27, STEM, 0.55f);
                }
                if (t % 30 == 0) d.part(Particle.WAX_ON, true, 0, 2.15, 0, 2, 0.2, 0);
            }
            // ---------------------------------------------------------- couronne des enfers : flammes d'âmes sur des pointes noires
            case "hellcrown" -> {
                if (t % 2 != 0) return;
                double spin = -t * 0.03;
                for (int k = 0; k < 7; k++) {
                    double a = spin + Math.PI * 2 * k / 7;
                    double x = Math.cos(a) * 0.3, z = Math.sin(a) * 0.3;
                    d.dust(true, x, 1.98, z, BLACK, 0.8f);
                    d.dust(true, x * 0.92, 2.1, z * 0.92, BLACK, 0.6f);
                    d.dust(true, x * 0.85, 2.21, z * 0.85, CRIMSON, 0.5f);
                    if (t % 6 == k % 3 * 2) d.part(Particle.SOUL_FIRE_FLAME, true, x * 0.85, 2.27, z * 0.85, 1, 0, 0.005);
                }
                if (t % 20 == 0) d.part(Particle.ASH, true, 0, 2.2, 0, 3, 0.25, 0);
            }
            // ---------------------------------------------------------- cornes d'obsidienne aux veines violettes
            case "horns" -> {
                if (t % 2 != 0) return;
                double pulse = 0.5 + 0.5 * Math.sin(t * 0.12);
                for (int s = -1; s <= 1; s += 2) {
                    for (int i = 0; i <= 8; i++) {
                        double u = i / 8.0;
                        double x = s * (0.18 + 0.24 * u - 0.08 * u * u), y = 1.85 + 0.38 * u, z = 0.05 - 0.22 * u * u;
                        Color c = u > 0.85 ? (pulse > 0.5 ? VIOLET : SILVER) : i % 3 == 1 ? VIOLET : BLACK;
                        d.dust(true, x, y, z, c, (float) (0.9 - 0.5 * u));
                    }
                    if (t % 16 == 0) d.part(Particle.PORTAL, true, s * 0.38, 2.22, -0.17, 3, 0.05, 0.2);
                }
            }
            // ---------------------------------------------------------- ailes de cendre : grandes, battent, des braises s'en détachent
            case "wings" -> {
                if (t % 2 != 0) return;
                double flap = Math.sin(t * 0.18) * 0.16;
                for (int s = -1; s <= 1; s += 2) {
                    for (int i = 0; i <= 11; i++) {
                        double u = i / 11.0;
                        double x = s * (0.12 + 1.0 * u), y = 1.3 + 0.45 * Math.sin(u * 2.5) + flap * u, z = -0.22 - 0.35 * u;
                        d.dust(false, x, y, z, i > 8 ? ORANGE : ASH, 0.85f);
                    }
                    for (int f = 1; f <= 4; f++) {
                        double u = f / 4.5;
                        double topY = 1.3 + 0.45 * Math.sin(u * 2.5) + flap * u;
                        for (int i = 0; i <= 5; i++) {
                            double v = i / 5.0;
                            d.dust(false, s * (0.12 + 1.0 * u), topY - (0.55 - 0.1 * f) * v, -0.22 - 0.35 * u - 0.04 * v, v > 0.7 ? ORANGE : ASH, 0.6f);
                        }
                    }
                    if (r.nextInt(5) == 0) d.part(Particle.SMALL_FLAME, false, s * r.nextDouble(0.3, 1.1), 1.0 + r.nextDouble(0.5), -0.4, 1, 0.02, 0.005);
                }
            }
            // ---------------------------------------------------------- cape de nuit étoilée qui flotte
            case "cape" -> {
                if (t % 2 != 0) return;
                double flow = moving ? 0.4 : 0.07;
                for (int i = 0; i <= 7; i++) {
                    double v = i / 7.0;
                    double y = 1.42 - 0.95 * v;
                    double z = -0.18 - flow * v * v - 0.04 * Math.sin(t * 0.25 + v * 4);
                    for (int j = 0; j <= 4; j++) {
                        double x = (-0.28 + 0.14 * j) * (1 + 0.25 * v);
                        boolean edge = j == 0 || j == 4 || i == 7;
                        d.dust(false, x, y, z, edge ? NIGHT : NAVY, 0.8f);
                    }
                }
                // étoiles qui scintillent sur la cape
                if (r.nextInt(3) == 0) d.dust(false, r.nextDouble(-0.25, 0.25), r.nextDouble(0.5, 1.4), -0.2 - flow * 0.5, SILVER, 0.5f);
            }
            // ---------------------------------------------------------- deux feux follets (bleu et orange) qui se poursuivent
            case "wisp" -> {
                double a = t * 0.13;
                double y = 1.75 + Math.sin(t * 0.09) * 0.15;
                d.part(Particle.SOUL_FIRE_FLAME, false, Math.cos(a) * 0.7, y, Math.sin(a) * 0.7, 1, 0, 0);
                d.part(Particle.FLAME, false, Math.cos(a + Math.PI) * 0.7, y + 0.1, Math.sin(a + Math.PI) * 0.7, 1, 0, 0);
                if (t % 3 == 0) {
                    d.dust(false, Math.cos(a - 0.3) * 0.7, y, Math.sin(a - 0.3) * 0.7, TEAL, 0.5f);
                    d.dust(false, Math.cos(a + Math.PI - 0.3) * 0.7, y + 0.1, Math.sin(a + Math.PI - 0.3) * 0.7, PUMPKIN, 0.5f);
                }
            }
            // ---------------------------------------------------------- petit crâne qui flotte près de l'épaule
            case "skull" -> {
                if (t % 3 != 0) return;
                double bob = Math.sin(t * 0.1) * 0.05;
                double x0 = -0.5, y0 = 1.75 + bob;
                for (int i = 0; i < 6; i++) {
                    double a = Math.PI * 2 * i / 6;
                    d.dust(false, x0 + Math.cos(a) * 0.12, y0 + Math.sin(a) * 0.12, 0, BONE, 0.55f);
                }
                d.dust(false, x0 - 0.05, y0 + 0.02, 0.06, TEAL, 0.45f);
                d.dust(false, x0 + 0.05, y0 + 0.02, 0.06, TEAL, 0.45f);
                d.dust(false, x0, y0 - 0.14, 0.02, BONE, 0.5f);
                if (t % 24 == 0) d.part(Particle.SOUL, false, x0, y0, 0, 1, 0.05, 0.01);
            }
            // ---------------------------------------------------------- lanterne qui se balance à la hanche
            case "lantern" -> {
                if (t % 2 != 0) return;
                double swing = Math.sin(t * (moving ? 0.35 : 0.1)) * 0.06;
                d.dust(false, -0.32, 0.78, 0.02 + swing * 0.5, BLACK, 0.5f);
                d.dust(false, -0.32, 0.62, 0.03 + swing, ORANGE, 1.1f);
                d.dust(false, -0.32, 0.62, 0.03 + swing, CREAM, 0.5f);
                if (t % 10 == 0) d.part(Particle.SMALL_FLAME, false, -0.32, 0.66, 0.03 + swing, 1, 0.01, 0);
            }
            // ---------------------------------------------------------- pendentif dont la pierre change de couleur
            case "amulet" -> {
                if (t % 3 != 0) return;
                double ph = (Math.sin(t * 0.08) + 1) / 2;
                Color gem = Color.fromRGB(255, (int) (120 + 110 * ph), (int) (20 + 40 * ph));
                for (int i = 0; i <= 4; i++) {
                    double u = i / 4.0;
                    d.dust(false, (u - 0.5) * 0.28, 1.42 - 0.22 * Math.sin(Math.PI * u), 0.15, GOLD_DARK, 0.4f);
                }
                d.dust(false, 0, 1.17, 0.17, gem, 1.0f);
                if (t % 15 == 0) d.part(Particle.WAX_OFF, false, 0, 1.17, 0.2, 1, 0.05, 0);
            }
            // ---------------------------------------------------------- orbes de magma sur un anneau penché, avec une queue
            case "orbs" -> {
                for (int k = 0; k < 3; k++) {
                    double a = t * 0.11 + Math.PI * 2 * k / 3;
                    double x = Math.cos(a) * 0.85, z = Math.sin(a) * 0.85, y = 1.0 + Math.sin(a) * 0.35;
                    d.dust(false, x, y, z, ORANGE, 1.25f);
                    double b = a - 0.25;
                    if (t % 2 == 0) d.dust(false, Math.cos(b) * 0.85, 1.0 + Math.sin(b) * 0.35, Math.sin(b) * 0.85, CRIMSON, 0.7f);
                    if (t % 6 == k * 2) d.part(Particle.LAVA, false, x, y, z, 1, 0, 0);
                }
            }
            // ---------------------------------------------------------- sac : un peu de poussière sur le dos
            case "backpack" -> {
                if (t % 10 == 0) d.dust(false, 0, 1.05, -0.3, Color.fromRGB(120, 75, 35), 1.4f);
            }
            // ---------------------------------------------------------- double auréole : rouge et blanche qui tournent en sens inverse
            case "halo" -> {
                if (t % 2 != 0) return;
                for (int k = 0; k < 6; k++) {
                    double a = t * 0.1 + Math.PI * 2 * k / 6;
                    d.dust(true, Math.cos(a) * 0.33, 2.25, Math.sin(a) * 0.33, CRIMSON, 0.8f);
                    double b = -t * 0.14 + Math.PI * 2 * k / 6 + 0.5;
                    d.dust(true, Math.cos(b) * 0.24, 2.3, Math.sin(b) * 0.24, CREAM, 0.55f);
                }
                if (t % 24 == 0) d.part(Particle.CRIMSON_SPORE, true, 0, 2.25, 0, 4, 0.2, 0);
            }
            // ---------------------------------------------------------- braises qui montent en spirale autour du corps
            case "embers" -> {
                if (t % 2 != 0) return;
                for (int k = 0; k < 2; k++) {
                    double h = ((t * 0.04 + k * 0.5) % 1.0);
                    double a = t * 0.2 + Math.PI * k;
                    d.dust(false, Math.cos(a) * 0.55, 0.1 + h * 2.0, Math.sin(a) * 0.55, Color.fromRGB(255, 60 + (int) (h * 150), 0), (float) (1.0 - h * 0.6));
                }
                if (moving && t % 6 == 0) d.part(Particle.LAVA, false, 0, 0.1, 0, 1, 0.1, 0);
            }
            // ---------------------------------------------------------- empreintes de flammes gauche / droite
            case "flames" -> {
                if (moving && t % 4 == 0) {
                    double side = (t / 4) % 2 == 0 ? 0.12 : -0.12;
                    d.part(Particle.FLAME, false, side, 0.05, 0, 2, 0.03, 0.005);
                    d.dust(false, side, 0.03, 0, ORANGE, 0.9f);
                } else if (!moving && t % 8 == 0) {
                    d.part(Particle.SMALL_FLAME, false, 0, 0.05, 0, 2, 0.2, 0.01);
                }
            }
            // ---------------------------------------------------------- feuilles d'automne portées par le vent
            case "boots" -> {
                if (t % 3 != 0) return;
                double a = t * 0.07;
                double rr = moving ? 0.35 : 0.55;
                for (int k = 0; k < 2; k++) {
                    double b = a + Math.PI * k;
                    double h = 0.1 + ((t * 0.02 + k * 0.4) % 0.8);
                    d.dust(false, Math.cos(b) * rr, h, Math.sin(b) * rr - (moving ? 0.25 : 0), LEAVES[(t / 3 + k) % LEAVES.length], 0.75f);
                }
            }
            // ---------------------------------------------------------- ❂ PRESTIGE : couronne du Roi Éternel, nuit étoilée
            case "eternalcrown" -> {
                double spin = t * 0.03;
                if (t % 2 == 0) {
                    for (int k = 0; k < 14; k++) {
                        double a = spin + Math.PI * 2 * k / 14;
                        d.dust(true, Math.cos(a) * 0.34, 1.98, Math.sin(a) * 0.34, k % 2 == 0 ? NIGHT : SILVER, 0.75f);
                    }
                    for (int k = 0; k < 7; k++) {
                        double a = spin + Math.PI * 2 * k / 7;
                        for (double y = 0.07; y <= 0.28; y += 0.07) {
                            double in = 0.34 - y * 0.3;
                            d.dust(true, Math.cos(a) * in, 1.98 + y, Math.sin(a) * in, y > 0.2 ? STAR : NAVY, 0.6f);
                        }
                        d.dust(true, Math.cos(a) * 0.26, 2.32, Math.sin(a) * 0.26, SILVER, 0.7f);
                    }
                }
                // constellation au sol : 7 étoiles reliées qui tournent lentement
                if (t % 4 == 0) {
                    double[][] stars = new double[7][2];
                    for (int k = 0; k < 7; k++) {
                        double a = -t * 0.01 + Math.PI * 2 * k / 7;
                        double rr = k % 2 == 0 ? 1.15 : 0.75;
                        stars[k] = new double[]{Math.cos(a) * rr, Math.sin(a) * rr};
                        d.dust(false, stars[k][0], 0.07, stars[k][1], SILVER, 1.0f);
                    }
                    for (int k = 0; k < 7; k++) {
                        double[] p0 = stars[k], p1 = stars[(k + 1) % 7];
                        for (double u = 0.2; u < 0.9; u += 0.2) d.dust(false, p0[0] + (p1[0] - p0[0]) * u, 0.07, p0[1] + (p1[1] - p0[1]) * u, NIGHT, 0.5f);
                    }
                }
                if (t % 5 == 0) d.part(Particle.END_ROD, false, r.nextDouble(-1, 1), 0.2, r.nextDouble(-1, 1), 1, 0, 0.02);
                // étoile filante de temps en temps
                if (t % 60 == 0) for (int k = 0; k < 8; k++) d.dust(true, 0.8 - k * 0.15, 2.9 - k * 0.07, 0.3, k == 0 ? SILVER : STAR, (float) (0.9 - k * 0.08));
            }
            // ---------------------------------------------------------- ❂ PRESTIGE : ailes de la nuit et croissant de lune
            case "eclipse" -> {
                if (t % 2 == 0) {
                    double flap = Math.sin(t * 0.1) * 0.14;
                    for (int s = -1; s <= 1; s += 2) {
                        for (int i = 0; i <= 14; i++) {
                            double u = i / 14.0;
                            d.dust(false, s * (0.14 + 1.35 * u), 1.35 + 0.6 * Math.sin(u * 2.4) + flap * u, -0.22 - 0.4 * u, i > 10 ? SILVER : NIGHT, 0.95f);
                        }
                        double[][] fingers = {{0.45, 0.75}, {0.75, 0.8}, {1.05, 0.95}, {1.35, 1.15}};
                        for (double[] f : fingers) {
                            double ux = (f[0] - 0.14) / 1.35;
                            double topY = 1.35 + 0.6 * Math.sin(ux * 2.4) + flap * ux, topZ = -0.22 - 0.4 * ux;
                            for (int i = 0; i <= 6; i++) {
                                double v = i / 6.0;
                                d.dust(false, s * f[0], topY + (f[1] - topY) * v, topZ - 0.06 * v, v > 0.8 ? STAR : NAVY, 0.65f);
                            }
                        }
                        // membrane parsemée d'étoiles
                        for (int i = 0; i < 4; i++) {
                            double u = 0.05 + r.nextDouble() * 0.95;
                            double top = 1.35 + 0.6 * Math.sin(u * 2.4) + flap * u, bottom = 0.75 + 0.4 * u;
                            d.dust(false, s * (0.14 + 1.35 * u), bottom + (top - bottom) * r.nextDouble(), -0.24 - 0.4 * u, r.nextInt(4) == 0 ? SILVER : NAVY, 0.6f);
                        }
                    }
                } else {
                    // croissant de lune argenté au-dessus de la tête
                    double cy = 2.6 + Math.sin(t * 0.06) * 0.05;
                    for (int k = 0; k <= 10; k++) {
                        double a = -Math.PI / 2 + Math.PI * k / 10;
                        d.dust(false, Math.cos(a) * 0.28, cy + Math.sin(a) * 0.28, 0, SILVER, 0.65f);
                        if (k > 1 && k < 9) d.dust(false, Math.cos(a) * 0.18 + 0.05, cy + Math.sin(a) * 0.18, 0, STAR, 0.45f);
                    }
                }
                if (t % 7 == 0) d.part(Particle.END_ROD, false, r.nextDouble(-1.2, 1.2), r.nextDouble(1.0, 2.2), -0.5, 1, 0, 0.005);
                if (moving && t % 3 == 0) d.dust(false, r.nextDouble(-0.25, 0.25), 0.1, -0.3, STAR, 0.7f);
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
