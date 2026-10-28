package fr.smpguilds;

import net.kyori.adventure.text.format.NamedTextColor;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Lecture de config.yml (rechargeable avec /guild admin reload). */
public final class Settings {

    private final SmpGuilds plugin;
    private FileConfiguration c;
    private final List<Level> levels = new ArrayList<>();
    private final Map<Role, Set<GuildPerm>> perms = new EnumMap<>(Role.class);
    private Pattern nameRegex;
    private Pattern tagRegex;

    Settings(SmpGuilds plugin) {
        this.plugin = plugin;
    }

    void load() {
        plugin.reloadConfig();
        c = plugin.getConfig();
        nameRegex = Pattern.compile(c.getString("guild.name-regex", "^[A-Za-z0-9_]{3,16}$"));
        tagRegex = Pattern.compile(c.getString("guild.tag-regex", "^[A-Za-z0-9]{2,5}$"));

        perms.clear();
        perms.put(Role.LEADER, EnumSet.allOf(GuildPerm.class));
        for (Role r : new Role[]{Role.RECRUIT, Role.MEMBER, Role.OFFICER}) {
            Set<GuildPerm> set = EnumSet.noneOf(GuildPerm.class);
            for (String s : c.getStringList("roles." + r.key())) {
                try {
                    set.add(GuildPerm.valueOf(s.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Permission inconnue dans roles." + r.key() + " : " + s);
                }
            }
            perms.put(r, set);
        }

        levels.clear();
        ConfigurationSection ls = c.getConfigurationSection("levels");
        if (ls != null) {
            for (String key : ls.getKeys(false)) {
                ConfigurationSection s = ls.getConfigurationSection(key);
                if (s == null) continue;
                int lvl;
                try {
                    lvl = Integer.parseInt(key);
                } catch (NumberFormatException e) {
                    continue;
                }
                List<Level.Effect> effects = new ArrayList<>();
                for (String e : s.getStringList("effects")) {
                    String[] p = e.split(":");
                    // plus de vision nocturne dans la base (même si un ancien config.yml la contient encore)
                    if (p[0].trim().equalsIgnoreCase("NIGHT_VISION")) continue;
                    PotionEffectType type;
                    try {
                        type = RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT)
                                .get(NamespacedKey.minecraft(p[0].trim().toLowerCase(Locale.ROOT)));
                    } catch (IllegalArgumentException ex) {
                        type = null;
                    }
                    if (type == null) {
                        plugin.getLogger().warning("Effet inconnu au niveau " + key + " : " + e);
                        continue;
                    }
                    int amp = 0;
                    try {
                        if (p.length > 1) amp = Math.max(0, Integer.parseInt(p[1].trim()));
                    } catch (NumberFormatException ex) {
                        plugin.getLogger().warning("Niveau d'effet invalide au niveau " + key + " : " + e);
                    }
                    effects.add(new Level.Effect(type, amp, p[0].trim().toUpperCase(Locale.ROOT) + " " + roman(amp + 1)));
                }
                levels.add(new Level(lvl, s.getLong("xp", 0), s.getInt("max-members", 5), s.getInt("max-claims", 4),
                        Math.max(1, Math.min(6, s.getInt("vault-rows", 1))), effects));
            }
        }
        levels.sort((a, b) -> Integer.compare(a.level(), b.level()));
        if (levels.isEmpty()) levels.add(new Level(1, 0, 5, 4, 1, List.of()));
    }

    private static String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(n);
        };
    }

    // ------------------------------------------------------------ niveaux

    public List<Level> levels() {
        return levels;
    }

    public Level levelFor(long xp) {
        Level cur = levels.get(0);
        for (Level l : levels) if (xp >= l.xp()) cur = l;
        return cur;
    }

    /** Palier suivant, ou null si niveau max. */
    public Level nextLevel(Level l) {
        for (Level x : levels) if (x.level() > l.level()) return x;
        return null;
    }

    // ------------------------------------------------------------ accès

    public boolean has(Role role, GuildPerm perm) {
        return role != null && perms.getOrDefault(role, Set.of()).contains(perm);
    }

    public Set<GuildPerm> perms(Role role) {
        return perms.getOrDefault(role, Set.of());
    }

    public boolean validName(String s) {
        return nameRegex.matcher(s).matches() && !blacklisted(s);
    }

    public boolean validTag(String s) {
        return tagRegex.matcher(s).matches() && !blacklisted(s);
    }

    private boolean blacklisted(String s) {
        String low = s.toLowerCase(Locale.ROOT);
        for (String w : c.getStringList("guild.blacklist")) {
            if (!w.isBlank() && low.contains(w.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    public NamedTextColor defaultColor() {
        NamedTextColor col = NamedTextColor.NAMES.value(c.getString("guild.default-color", "gold"));
        return col != null ? col : NamedTextColor.GOLD;
    }

    public int i(String path, int def) {
        return c.getInt(path, def);
    }

    public long l(String path, long def) {
        return c.getLong(path, def);
    }

    public double d(String path, double def) {
        return c.getDouble(path, def);
    }

    public boolean b(String path, boolean def) {
        return c.getBoolean(path, def);
    }

    public String s(String path, String def) {
        String v = c.getString(path);
        return v != null ? v : def;
    }

    public List<String> list(String path) {
        return c.getStringList(path);
    }
}
