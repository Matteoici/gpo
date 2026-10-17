package fr.smpguilds;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static fr.smpguilds.Messages.guild;
import static fr.smpguilds.Messages.ph;

/** Toutes les guildes en mémoire + sauvegarde (un fichier YAML par guilde). */
public final class GuildManager {

    private static final DateTimeFormatter LOG_FMT = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    private final SmpGuilds plugin;
    private final File folder;
    private final Map<UUID, Guild> guilds = new ConcurrentHashMap<>();
    private final Map<String, UUID> byName = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> byPlayer = new ConcurrentHashMap<>();
    private final Map<ChunkPos, UUID> claims = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    /** joueur invité -> (guilde -> expiration) */
    private final Map<UUID, Map<UUID, Long>> invites = new HashMap<>();
    /** guilde ciblée -> guildes qui lui demandent une alliance */
    private final Map<UUID, Set<UUID>> allyRequests = new HashMap<>();

    GuildManager(SmpGuilds plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "guilds");
    }

    // ============================================================ recherche

    public Collection<Guild> all() {
        return guilds.values();
    }

    public Guild get(UUID id) {
        return id == null ? null : guilds.get(id);
    }

    public Guild byName(String name) {
        return get(byName.get(name.toLowerCase(Locale.ROOT)));
    }

    public Guild byTag(String tag) {
        for (Guild g : guilds.values()) if (g.tag.equalsIgnoreCase(tag)) return g;
        return null;
    }

    /** Par nom ou par tag. */
    public Guild find(String s) {
        Guild g = byName(s);
        return g != null ? g : byTag(s);
    }

    public Guild of(UUID player) {
        return get(byPlayer.get(player));
    }

    public Guild of(Player p) {
        return of(p.getUniqueId());
    }

    public Guild at(ChunkPos pos) {
        return get(claims.get(pos));
    }

    /** Guilde qui protège cet endroit (chunk claim ET dans la hauteur choisie par la guilde). */
    public Guild at(Location loc) {
        if (loc.getWorld() == null) return null;
        Guild g = at(ChunkPos.of(loc));
        return g != null && g.inZone(loc.getY()) ? g : null;
    }

    public Level level(Guild g) {
        return plugin.settings().levelFor(g.xp);
    }

    public boolean has(Guild g, UUID player, GuildPerm perm) {
        return g != null && plugin.settings().has(g.role(player), perm);
    }

    public List<Guild> top() {
        List<Guild> list = new ArrayList<>(guilds.values());
        list.sort(Comparator.comparingLong((Guild g) -> g.xp).reversed().thenComparing(g -> g.name));
        return list;
    }

    public boolean areAllies(Guild a, Guild b) {
        return a != null && b != null && a.allies.contains(b.id);
    }

    // ============================================================ création / membres

    public Guild create(Player leader, String name, String tag) {
        Guild g = new Guild(UUID.randomUUID());
        g.name = name;
        g.tag = tag;
        g.color = plugin.settings().defaultColor();
        g.open = plugin.settings().b("guild.default-open", false);
        g.leader = leader.getUniqueId();
        guilds.put(g.id, g);
        byName.put(name.toLowerCase(Locale.ROOT), g.id);
        addMember(g, leader.getUniqueId(), leader.getName(), Role.LEADER);
        log(g, "Création de la guilde par " + leader.getName());
        save(g);
        return g;
    }

    public void disband(Guild g) {
        for (UUID u : new ArrayList<>(g.members.keySet())) byPlayer.remove(u);
        for (ChunkPos c : g.claims) claims.remove(c);
        for (Guild other : guilds.values()) other.allies.remove(g.id);
        allyRequests.remove(g.id);
        allyRequests.values().forEach(s -> s.remove(g.id));
        invites.values().forEach(m -> m.remove(g.id));
        byName.remove(g.name.toLowerCase(Locale.ROOT));
        guilds.remove(g.id);
        plugin.vaults().forget(g);
        dirty.remove(g.id);
        File f = new File(folder, g.id + ".yml");
        if (f.exists() && !f.delete()) plugin.getLogger().warning("Impossible de supprimer " + f.getName());
    }

    public void addMember(Guild g, UUID player, String name, Role role) {
        g.members.put(player, role);
        g.names.put(player, name);
        g.stats(player).joined = System.currentTimeMillis();
        g.applications.remove(player);
        byPlayer.put(player, g.id);
        invites.remove(player);
        // un joueur ne peut postuler qu'à une guilde à la fois une fois membre
        for (Guild other : guilds.values()) {
            if (other.applications.remove(player) != null) markDirty(other);
        }
        markDirty(g);
    }

    public void removeMember(Guild g, UUID player) {
        g.members.remove(player);
        g.stats.remove(player);
        byPlayer.remove(player);
        markDirty(g);
    }

    public void rename(Guild g, String newName) {
        byName.remove(g.name.toLowerCase(Locale.ROOT));
        g.name = newName;
        byName.put(newName.toLowerCase(Locale.ROOT), g.id);
        markDirty(g);
    }

    // ============================================================ invitations / alliances

    public void invite(Guild g, UUID player) {
        long exp = System.currentTimeMillis() + plugin.settings().l("guild.invite-expire-seconds", 120) * 1000L;
        invites.computeIfAbsent(player, k -> new HashMap<>()).put(g.id, exp);
    }

    public boolean isInvited(Guild g, UUID player) {
        Map<UUID, Long> m = invites.get(player);
        if (m == null) return false;
        Long exp = m.get(g.id);
        return exp != null && exp > System.currentTimeMillis();
    }

    public List<Guild> invitesOf(UUID player) {
        List<Guild> out = new ArrayList<>();
        Map<UUID, Long> m = invites.get(player);
        if (m == null) return out;
        long now = System.currentTimeMillis();
        m.forEach((id, exp) -> {
            Guild g = get(id);
            if (g != null && exp > now) out.add(g);
        });
        return out;
    }

    public void requestAlly(Guild from, Guild to) {
        allyRequests.computeIfAbsent(to.id, k -> new HashSet<>()).add(from.id);
    }

    public boolean hasAllyRequest(Guild from, Guild to) {
        Set<UUID> s = allyRequests.get(to.id);
        return s != null && s.contains(from.id);
    }

    public List<Guild> allyRequestsTo(Guild to) {
        List<Guild> out = new ArrayList<>();
        Set<UUID> s = allyRequests.get(to.id);
        if (s != null) for (UUID id : s) if (get(id) != null) out.add(get(id));
        return out;
    }

    public void makeAllies(Guild a, Guild b) {
        a.allies.add(b.id);
        b.allies.add(a.id);
        Set<UUID> s1 = allyRequests.get(a.id);
        if (s1 != null) s1.remove(b.id);
        Set<UUID> s2 = allyRequests.get(b.id);
        if (s2 != null) s2.remove(a.id);
        markDirty(a);
        markDirty(b);
    }

    public void breakAlliance(Guild a, Guild b) {
        a.allies.remove(b.id);
        b.allies.remove(a.id);
        markDirty(a);
        markDirty(b);
    }

    // ============================================================ terrains

    public void claim(Guild g, ChunkPos pos) {
        g.claims.add(pos);
        claims.put(pos, g.id);
        markDirty(g);
    }

    public void unclaim(Guild g, ChunkPos pos) {
        g.claims.remove(pos);
        claims.remove(pos);
        if (g.home != null && g.home.getWorld() != null && ChunkPos.of(g.home).equals(pos)) g.home = null;
        markDirty(g);
    }

    // ============================================================ XP / niveaux

    public void addXp(Guild g, long amount) {
        if (amount <= 0) return;
        Level before = level(g);
        g.xp += amount;
        markDirty(g);
        Level after = level(g);
        if (after.level() > before.level()) {
            Messages m = plugin.messages();
            for (Player p : g.online()) {
                m.send(p, "level.up", guild(g), ph("level", after.level()));
                p.showTitle(Title.title(m.get("level.up-title", ph("level", after.level())),
                        m.get("level.up-subtitle", guild(g), ph("level", after.level()))));
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
            }
            log(g, "Niveau " + after.level() + " atteint !");
        }
    }

    // ============================================================ journal

    public void log(Guild g, String entry) {
        g.log.add(0, LocalDateTime.now().format(LOG_FMT) + " | " + entry);
        int max = plugin.settings().i("guild.log-size", 60);
        while (g.log.size() > max) g.log.remove(g.log.size() - 1);
        markDirty(g);
    }

    // ============================================================ sauvegarde

    public void markDirty(Guild g) {
        dirty.add(g.id);
    }

    public void saveDirty() {
        for (UUID id : new ArrayList<>(dirty)) {
            Guild g = guilds.get(id);
            if (g != null) save(g);
        }
    }

    public void saveAll() {
        for (Guild g : guilds.values()) save(g);
    }

    public void save(Guild g) {
        dirty.remove(g.id);
        plugin.vaults().sync(g);
        folder.mkdirs();
        YamlConfiguration y = new YamlConfiguration();
        y.set("id", g.id.toString());
        y.set("name", g.name);
        y.set("tag", g.tag);
        y.set("description", g.description);
        y.set("color", NamedTextColor.NAMES.key(g.color));
        y.set("banner", Items.encode(g.banner));
        y.set("open", g.open);
        y.set("created", g.created);
        y.set("leader", g.leader.toString());
        g.members.forEach((u, r) -> {
            String base = "members." + u;
            y.set(base + ".role", r.name());
            y.set(base + ".name", g.nameOf(u));
            MemberStats s = g.stats(u);
            y.set(base + ".kills", s.kills);
            y.set(base + ".deaths", s.deaths);
            y.set(base + ".deposited", s.deposited);
            y.set(base + ".joined", s.joined);
        });
        if (g.home != null && g.home.getWorld() != null) {
            y.set("home.world", g.home.getWorld().getName());
            y.set("home.x", g.home.getX());
            y.set("home.y", g.home.getY());
            y.set("home.z", g.home.getZ());
            y.set("home.yaw", (double) g.home.getYaw());
            y.set("home.pitch", (double) g.home.getPitch());
        }
        y.set("bank", g.bank);
        y.set("xp", g.xp);
        y.set("kills", g.kills);
        y.set("deaths", g.deaths);
        y.set("wars-won", g.warsWon);
        y.set("wars-lost", g.warsLost);
        y.set("last-upkeep", g.lastUpkeep);
        y.set("claims", g.claims.stream().map(ChunkPos::serialize).toList());
        y.set("zone.min-y", g.zoneMinY);
        y.set("zone.max-y", g.zoneMaxY);
        y.set("allies", g.allies.stream().map(UUID::toString).toList());
        g.applications.forEach((u, a) -> {
            y.set("applications." + u + ".message", a.message());
            y.set("applications." + u + ".time", a.time());
        });
        List<String> vault = new ArrayList<>();
        for (ItemStack it : g.vault) vault.add(Items.encode(it));
        y.set("vault", vault);
        y.set("log", g.log);
        try {
            y.save(new File(folder, g.id + ".yml"));
        } catch (IOException e) {
            plugin.getLogger().severe("Sauvegarde impossible de la guilde " + g.name + " : " + e.getMessage());
        }
    }

    public void loadAll() {
        guilds.clear();
        byName.clear();
        byPlayer.clear();
        claims.clear();
        File[] files = folder.listFiles((dir, n) -> n.endsWith(".yml"));
        if (files == null) return;
        for (File f : files) {
            try {
                Guild g = load(YamlConfiguration.loadConfiguration(f));
                guilds.put(g.id, g);
                byName.put(g.name.toLowerCase(Locale.ROOT), g.id);
                for (UUID u : g.members.keySet()) byPlayer.put(u, g.id);
                for (ChunkPos c : g.claims) claims.put(c, g.id);
            } catch (Exception e) {
                plugin.getLogger().severe("Fichier de guilde illisible " + f.getName() + " : " + e.getMessage());
            }
        }
        // nettoie les alliances vers des guildes disparues
        for (Guild g : guilds.values()) g.allies.removeIf(id -> !guilds.containsKey(id));
        plugin.getLogger().info(guilds.size() + " guilde(s) chargée(s).");
    }

    private Guild load(YamlConfiguration y) {
        Guild g = new Guild(UUID.fromString(y.getString("id")));
        g.name = y.getString("name");
        g.tag = y.getString("tag");
        g.description = y.getString("description", "");
        NamedTextColor col = NamedTextColor.NAMES.value(y.getString("color", "gold"));
        g.color = col != null ? col : NamedTextColor.GOLD;
        g.banner = Items.decode(y.getString("banner", ""));
        g.open = y.getBoolean("open", false);
        g.created = y.getLong("created", System.currentTimeMillis());
        g.leader = UUID.fromString(y.getString("leader"));
        ConfigurationSection ms = y.getConfigurationSection("members");
        if (ms != null) {
            for (String key : ms.getKeys(false)) {
                UUID u = UUID.fromString(key);
                ConfigurationSection s = ms.getConfigurationSection(key);
                if (s == null) continue;
                g.members.put(u, Role.valueOf(s.getString("role", "MEMBER")));
                g.names.put(u, s.getString("name", key.substring(0, 8)));
                MemberStats st = g.stats(u);
                st.kills = s.getInt("kills", 0);
                st.deaths = s.getInt("deaths", 0);
                st.deposited = s.getDouble("deposited", 0);
                st.joined = s.getLong("joined", g.created);
            }
        }
        if (y.isConfigurationSection("home")) {
            World w = Bukkit.getWorld(y.getString("home.world", ""));
            if (w != null) {
                g.home = new Location(w, y.getDouble("home.x"), y.getDouble("home.y"), y.getDouble("home.z"),
                        (float) y.getDouble("home.yaw"), (float) y.getDouble("home.pitch"));
            }
        }
        g.bank = y.getDouble("bank", 0);
        g.xp = y.getLong("xp", 0);
        g.kills = y.getInt("kills", 0);
        g.deaths = y.getInt("deaths", 0);
        g.warsWon = y.getInt("wars-won", 0);
        g.warsLost = y.getInt("wars-lost", 0);
        g.lastUpkeep = y.getLong("last-upkeep", System.currentTimeMillis());
        for (String c : y.getStringList("claims")) g.claims.add(ChunkPos.parse(c));
        if (y.isInt("zone.min-y")) g.zoneMinY = y.getInt("zone.min-y");
        if (y.isInt("zone.max-y")) g.zoneMaxY = y.getInt("zone.max-y");
        for (String a : y.getStringList("allies")) g.allies.add(UUID.fromString(a));
        ConfigurationSection as = y.getConfigurationSection("applications");
        if (as != null) {
            for (String key : as.getKeys(false)) {
                g.applications.put(UUID.fromString(key),
                        new Guild.Application(as.getString(key + ".message", ""), as.getLong(key + ".time", 0)));
            }
        }
        List<String> vault = y.getStringList("vault");
        for (int i = 0; i < Math.min(54, vault.size()); i++) g.vault[i] = Items.decode(vault.get(i));
        g.log.addAll(y.getStringList("log"));
        return g;
    }
}
