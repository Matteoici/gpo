package fr.smpdrop;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** Lecture de config.yml. */
public final class Settings {

    // planning
    public final long intervalMs, retryMs, startupDelayMs;
    public final int minPlayers, announceSeconds;
    public final List<Integer> warningsMinutes = new ArrayList<>();
    public final Set<Integer> countdown = new HashSet<>();
    public final boolean countdownTitles;
    // lieu
    public final String world;
    public final double centerX, centerZ, minRadius, maxRadius;
    public final boolean avoidClaims;
    public final Set<Material> avoidBlocks = EnumSet.noneOf(Material.class);
    public final int maxAttempts;
    // chute
    public final int fallHeight, fallTicks;
    public final float spin;
    public final boolean lightning;
    // caisse
    public final float crateSize, viewRange;
    public final Material body, straps, lamp, beam, ropes;
    public final int beamHeight;
    public final boolean glow, fullBright;
    public final Color glowColor;
    public final List<Material> parachute = new ArrayList<>();
    // ouverture
    public final int openTicks, despawnSeconds;
    public final double maxDistance, alertRadius;
    public final boolean anyDamage, attackInterrupts, blockNewbies;
    // traqueur
    public final boolean bossbar, compass;
    // butin
    public final boolean spillMode;
    public final int chestSeconds;
    public final LootTable loot = new LootTable();

    Settings(FileConfiguration c, Logger log) {
        intervalMs = Math.max(1, c.getLong("schedule.interval-minutes", 180)) * 60_000L;
        retryMs = Math.max(1, c.getLong("schedule.retry-minutes", 15)) * 60_000L;
        startupDelayMs = Math.max(0, c.getLong("schedule.startup-delay-minutes", 10)) * 60_000L;
        minPlayers = Math.max(0, c.getInt("schedule.min-players", 2));
        announceSeconds = Math.max(5, c.getInt("schedule.announce-seconds", 300));
        warningsMinutes.addAll(c.getIntegerList("schedule.warnings-minutes"));
        countdown.addAll(c.getIntegerList("schedule.countdown"));
        countdownTitles = c.getBoolean("schedule.countdown-titles", true);

        world = c.getString("location.world", "world");
        centerX = c.getDouble("location.center-x", 0);
        centerZ = c.getDouble("location.center-z", 0);
        double min = Math.max(0, c.getDouble("location.min-radius", 400));
        double max = Math.max(min + 16, c.getDouble("location.max-radius", 2500));
        minRadius = min;
        maxRadius = max;
        avoidClaims = c.getBoolean("location.avoid-guild-claims", true);
        for (String s : c.getStringList("location.avoid-blocks")) {
            Material m = Material.matchMaterial(s);
            if (m != null) avoidBlocks.add(m);
            else log.warning("Bloc inconnu dans avoid-blocks : " + s);
        }
        maxAttempts = Math.max(1, c.getInt("location.max-attempts", 40));

        fallHeight = Math.max(10, c.getInt("fall.height", 90));
        fallTicks = Math.max(40, (int) Math.round(c.getDouble("fall.seconds", 24) * 20));
        spin = (float) c.getDouble("fall.spin", 1.2);
        lightning = c.getBoolean("fall.lightning", true);

        crateSize = (float) Math.max(0.5, Math.min(3, c.getDouble("crate.size", 1.1)));
        viewRange = (float) Math.max(0.5, c.getDouble("crate.view-range", 4));
        body = mat(c, "crate.body", Material.LIGHT_BLUE_CONCRETE, log);
        straps = mat(c, "crate.straps", Material.GOLD_BLOCK, log);
        lamp = mat(c, "crate.lamp", Material.SEA_LANTERN, log);
        beam = mat(c, "crate.beam", Material.LIGHT_BLUE_STAINED_GLASS, log);
        ropes = mat(c, "crate.ropes", Material.WHITE_WOOL, log);
        beamHeight = Math.max(0, c.getInt("crate.beam-height", 120));
        glow = c.getBoolean("crate.glow", true);
        fullBright = c.getBoolean("crate.full-bright", true);
        glowColor = color(c.getString("crate.glow-color", "#4fd6ff"), Color.AQUA);
        for (String s : c.getStringList("crate.parachute")) {
            Material m = Material.matchMaterial(s);
            if (m != null && m.isBlock()) parachute.add(m);
        }
        if (parachute.isEmpty()) {
            parachute.add(Material.RED_WOOL);
            parachute.add(Material.WHITE_WOOL);
        }

        openTicks = Math.max(20, (int) Math.round(c.getDouble("opening.seconds", 10) * 20));
        maxDistance = Math.max(1.5, c.getDouble("opening.max-distance", 4));
        anyDamage = c.getBoolean("opening.interrupt-on-any-damage", false);
        attackInterrupts = c.getBoolean("opening.interrupt-when-attacking", true);
        blockNewbies = c.getBoolean("opening.block-protected-newbies", true);
        despawnSeconds = Math.max(30, c.getInt("opening.despawn-minutes", 20) * 60);
        alertRadius = c.getDouble("opening.alert-radius", 250);

        bossbar = c.getBoolean("tracker.bossbar", true);
        compass = c.getBoolean("tracker.compass", true);

        spillMode = "SPILL".equalsIgnoreCase(c.getString("loot.mode", "CHEST"));
        chestSeconds = Math.max(10, c.getInt("loot.chest-seconds", 120));
        loadLoot(c, log);
    }

    private void loadLoot(FileConfiguration c, Logger log) {
        int[] rolls = range(c.getString("loot.rolls", "5-7"), 6);
        loot.minRolls = Math.max(1, rolls[0]);
        loot.maxRolls = Math.max(loot.minRolls, rolls[1]);

        ConfigurationSection rs = c.getConfigurationSection("loot.rarities");
        int rank = 0;
        if (rs != null) {
            for (String id : rs.getKeys(false)) {
                ConfigurationSection r = rs.getConfigurationSection(id);
                if (r == null) continue;
                loot.rarities.put(id.toLowerCase(Locale.ROOT), new Rarity(id.toLowerCase(Locale.ROOT), rank++,
                        r.getDouble("weight", 1), r.getString("display", id),
                        color(r.getString("color", "#ffffff"), Color.WHITE),
                        r.getBoolean("glow", false), r.getBoolean("announce", false)));
            }
        }
        if (loot.rarities.isEmpty()) {
            loot.rarities.put("commun", new Rarity("commun", 0, 1, "<gray>Commun", Color.GRAY, false, false));
        }
        for (String g : c.getStringList("loot.guaranteed")) {
            Rarity r = loot.rarities.get(g.toLowerCase(Locale.ROOT));
            if (r != null) loot.guaranteed.add(r);
            else log.warning("Rareté garantie inconnue : " + g);
        }

        for (Map<?, ?> m : c.getMapList("loot.items")) {
            try {
                String rid = str(m.get("rarity"), "commun").toLowerCase(Locale.ROOT);
                Rarity rar = loot.rarities.get(rid);
                if (rar == null) {
                    log.warning("Rareté inconnue : " + rid);
                    continue;
                }
                Material mat = Material.matchMaterial(str(m.get("material"), ""));
                if (mat == null || !mat.isItem()) {
                    log.warning("Objet inconnu dans le butin : " + m.get("material"));
                    continue;
                }
                Map<String, String> ench = new LinkedHashMap<>();
                if (m.get("enchants") instanceof Map<?, ?> em) {
                    for (Map.Entry<?, ?> e : em.entrySet()) ench.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
                List<String> lore = new ArrayList<>();
                if (m.get("lore") instanceof List<?> l) for (Object o : l) lore.add(String.valueOf(o));
                List<String> bonus = new ArrayList<>();
                if (m.get("bonus") instanceof List<?> l) for (Object o : l) bonus.add(String.valueOf(o));
                Object name = m.get("name");
                Object potion = m.get("potion");
                loot.entries.add(new LootEntry(rar, mat, range(str(m.get("amount"), "1"), 1),
                        dbl(m.get("weight"), 1), name == null ? null : String.valueOf(name), lore, ench,
                        potion == null ? null : String.valueOf(potion), (int) dbl(m.get("max"), 0), bonus, log));
            } catch (Exception e) {
                log.warning("Objet de butin ignoré (" + m + ") : " + e.getMessage());
            }
        }
        if (loot.entries.isEmpty()) log.warning("Aucun objet dans loot.items : les caisses seront vides !");
    }

    // ------------------------------------------------------------ utilitaires

    /** "3" -> [3,3] ; "2-5" -> [2,5]. */
    static int[] range(String s, int def) {
        if (s == null || s.isBlank()) return new int[]{def, def};
        s = s.trim();
        try {
            int dash = s.indexOf('-', 1);
            if (dash > 0) {
                int a = Integer.parseInt(s.substring(0, dash).trim());
                int b = Integer.parseInt(s.substring(dash + 1).trim());
                return new int[]{Math.min(a, b), Math.max(a, b)};
            }
            int v = (int) Double.parseDouble(s);
            return new int[]{v, v};
        } catch (NumberFormatException e) {
            return new int[]{def, def};
        }
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private static double dbl(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return o == null ? def : Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Material mat(FileConfiguration c, String path, Material def, Logger log) {
        String s = c.getString(path);
        if (s == null) return def;
        Material m = Material.matchMaterial(s);
        if (m == null || !m.isBlock()) {
            log.warning("Bloc invalide pour " + path + " : " + s);
            return def;
        }
        return m;
    }

    static Color color(String hex, Color def) {
        if (hex == null) return def;
        try {
            String h = hex.trim().replace("#", "");
            return Color.fromRGB(Integer.parseInt(h, 16) & 0xFFFFFF);
        } catch (Exception e) {
            return def;
        }
    }
}
