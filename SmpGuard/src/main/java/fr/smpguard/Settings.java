package fr.smpguard;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/** Lecture de config.yml. */
public final class Settings {

    /** Seuils d'une détection. */
    public record Limits(boolean enabled, double alertVl, double susVl, double decayPerMinute) {
    }

    // lag
    public final int goodPing, maxPing, maxJitter;
    public final double minTps;
    public final long graceJoin, graceTeleport, graceRespawn, graceWorld;
    // alertes
    public final long alertCooldownMs;
    public final boolean logToFile, autoSus;
    public final Map<Check, Limits> limits = new EnumMap<>(Check.class);
    // esp-aim
    public final double aimMinDist, aimMaxDist, aimAngle, aimMinMove;
    public final int aimTrackSamples;
    public final long aimHiddenBeforeMs;
    // xray
    public final int xrayMaxY, xrayMinVeins;
    public final long xrayWindowMs;
    public final double xrayBlocksOverworld, xrayBlocksNether, xrayHiddenRatio;
    public final Map<Material, Double> xrayOres = new HashMap<>();
    // freecam
    public final double freecamMinSeconds;
    // auto-totem
    public final double totemMinReactionMs;
    public final boolean totemBlockImpossible;
    /** config brute, pour les réglages des nouvelles détections */
    private final FileConfiguration cfg;
    // anti-esp
    public final boolean hideBehindWalls;
    public final double hideDistance;
    public final int raysPerTick;
    // appât
    public final int baitSeconds;
    public final double baitRadius;

    Settings(FileConfiguration c, Logger log) {
        goodPing = c.getInt("lag.good-ping", 120);
        maxPing = Math.max(goodPing + 10, c.getInt("lag.max-ping", 350));
        maxJitter = Math.max(10, c.getInt("lag.max-jitter", 120));
        minTps = c.getDouble("lag.min-tps", 17.0);
        graceJoin = c.getLong("lag.grace-seconds.join", 15) * 1000;
        graceTeleport = c.getLong("lag.grace-seconds.teleport", 4) * 1000;
        graceRespawn = c.getLong("lag.grace-seconds.respawn", 5) * 1000;
        graceWorld = c.getLong("lag.grace-seconds.world-change", 6) * 1000;

        alertCooldownMs = c.getLong("alerts.cooldown-seconds", 30) * 1000;
        logToFile = c.getBoolean("alerts.log-to-file", true);
        autoSus = c.getBoolean("alerts.smpadmin-auto-sus", true);

        for (Check ch : Check.values()) {
            String b = "checks." + ch.key + ".";
            limits.put(ch, new Limits(c.getBoolean(b + "enabled", true), c.getDouble(b + "alert-vl", 3),
                    c.getDouble(b + "sus-vl", 6), c.getDouble(b + "decay-per-minute", 0.1)));
        }
        // le test appât est lancé à la main : ses seuils sont fixes
        limits.put(Check.BAIT, new Limits(true, 1, 2, 0.02));

        aimMinDist = c.getDouble("checks.esp-aim.min-distance", 6);
        aimMaxDist = c.getDouble("checks.esp-aim.max-distance", 40);
        aimAngle = c.getDouble("checks.esp-aim.angle", 3.5);
        aimTrackSamples = Math.max(3, c.getInt("checks.esp-aim.track-samples", 6));
        aimMinMove = c.getDouble("checks.esp-aim.min-target-move", 1.5);
        aimHiddenBeforeMs = (long) (c.getDouble("checks.esp-aim.hidden-before-seconds", 1.5) * 1000);

        xrayMaxY = c.getInt("checks.xray.max-y", 40);
        xrayWindowMs = (long) (c.getDouble("checks.xray.window-minutes", 20) * 60_000);
        xrayMinVeins = Math.max(2, c.getInt("checks.xray.min-veins", 4));
        xrayBlocksOverworld = c.getDouble("checks.xray.blocks-per-vein.overworld", 70);
        xrayBlocksNether = c.getDouble("checks.xray.blocks-per-vein.nether", 90);
        xrayHiddenRatio = c.getDouble("checks.xray.hidden-ratio", 0.6);
        var ores = c.getConfigurationSection("checks.xray.ores");
        if (ores != null) {
            for (String k : ores.getKeys(false)) {
                Material m = Material.matchMaterial(k);
                if (m == null) log.warning("Minerai inconnu dans checks.xray.ores : " + k);
                else xrayOres.put(m, ores.getDouble(k, 1));
            }
        }

        freecamMinSeconds = Math.max(2, c.getDouble("checks.freecam.min-seconds", 4));

        totemMinReactionMs = c.getDouble("checks.auto-totem.min-reaction-ms", 90);
        totemBlockImpossible = c.getBoolean("checks.auto-totem.block-impossible", true);
        this.cfg = c;

        hideBehindWalls = c.getBoolean("anti-esp.hide-players-behind-walls", false);
        hideDistance = Math.max(8, c.getDouble("anti-esp.distance", 48));
        raysPerTick = Math.max(50, c.getInt("anti-esp.budget-per-tick", 400));

        baitSeconds = Math.max(10, c.getInt("bait.default-seconds", 30));
        baitRadius = Math.max(6, c.getDouble("bait.radius", 12));
    }

    /** Réglage numérique d'une détection : checks.<check>.<path> */
    public double num(Check c, String path, double def) {
        return cfg.getDouble("checks." + c.key + "." + path, def);
    }

    public boolean flag(Check c, String path, boolean def) {
        return cfg.getBoolean("checks." + c.key + "." + path, def);
    }

    public FileConfiguration raw() {
        return cfg;
    }

    public Limits limits(Check c) {
        return limits.get(c);
    }
}
