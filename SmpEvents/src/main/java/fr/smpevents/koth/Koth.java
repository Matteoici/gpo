package fr.smpevents.koth;

import fr.smpevents.DataStore;
import fr.smpevents.Hooks;
import fr.smpevents.PlayerData;
import fr.smpevents.Settings;
import fr.smpevents.SmpEvents;
import fr.smpevents.Texts;
import fr.smpevents.quest.QuestType;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import static fr.smpevents.Texts.pc;
import static fr.smpevents.Texts.ph;

/**
 * Roi de la colline. Une zone s'active à heure fixe : l'équipe (la guilde, ou le joueur seul s'il n'en a pas)
 * qui est SEULE dans la zone gagne du temps. Si deux équipes ennemies sont dedans, c'est "contesté" : personne ne gagne de temps.
 * Les guildes alliées ne se contestent pas entre elles.
 * Victoire : la première équipe à cumuler capture-seconds, ou la meilleure à la fin du temps imparti.
 */
public final class Koth {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final SmpEvents plugin;
    private final Map<String, KothZone> zones = new LinkedHashMap<>();
    private final File file;
    private final Random random = new Random();

    // réglages
    public boolean enabled;
    public List<LocalTime> schedule = new ArrayList<>();
    public Set<DayOfWeek> days = new HashSet<>();
    public int minPlayers;
    public List<Integer> announce = new ArrayList<>();
    public int captureSeconds;
    public int maxMinutes;
    public int minSecondsToWin;
    public double rewardMoney;
    public List<String> rewardCommands = List.of();
    public int participantSeconds;
    public double height;
    public boolean bossbarEveryone;
    public int everyHours;
    /** auto = endroit au hasard à chaque fois ; zones = seulement les zones créées ; both = les deux mélangées */
    public String zoneMode;
    public String autoWorld;
    public double autoCenterX, autoCenterZ, autoMin, autoMax, autoRadius;
    public boolean autoAvoidClaims;
    public boolean beam;

    // événement en cours
    private KothZone active;
    private int elapsed;
    private String king;
    private boolean contested;
    private final Map<String, Integer> teamTime = new HashMap<>();
    private final Map<String, String> teamNames = new HashMap<>();
    private final Map<UUID, Integer> personal = new HashMap<>();
    private final Map<UUID, String> playerTeam = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private BossBar bar; // conservé pour la compatibilité (null = pas d'événement)
    /** joueurs qui suivent l'événement en cours / prévu, et ceux qui veulent toujours être prévenus */
    private final Set<UUID> followers = new HashSet<>();
    private final Set<UUID> alwaysNotify = new HashSet<>();
    private static final String[] ARROWS = {"⬆", "⬈", "➡", "⬊", "⬇", "⬋", "⬅", "⬉"};
    private TextDisplay label;
    private BlockDisplay pillar;
    private boolean searching;
    private final Set<String> sent = new HashSet<>();
    private String lastZone;

    public Koth(SmpEvents plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "koth.yml");
    }

    public void loadSettings(ConfigurationSection c) {
        if (c == null) c = plugin.getConfig().createSection("koth");
        enabled = c.getBoolean("enabled", true);
        schedule = new ArrayList<>();
        for (String s : c.getStringList("schedule")) {
            try {
                schedule.add(LocalTime.parse(s.trim().length() == 4 ? "0" + s.trim() : s.trim(), HM));
            } catch (Exception e) {
                plugin.getLogger().warning("koth.schedule : heure invalide « " + s + " » (format 18:30)");
            }
        }
        everyHours = Math.max(0, Math.min(24, c.getInt("every-hours", 0)));
        if (everyHours > 0) {
            int first = Math.floorMod(c.getInt("first-hour", 0), 24);
            int minute = Math.floorMod(c.getInt("minute", 0), 60);
            schedule = new ArrayList<>();
            for (int i = 0; i < 24; i += everyHours) schedule.add(LocalTime.of((first + i) % 24, minute));
        }
        days = new HashSet<>();
        for (String d : c.getStringList("days")) {
            DayOfWeek dw = day(d);
            if (dw != null) days.add(dw);
        }
        minPlayers = Math.max(0, c.getInt("min-players", 3));
        announce = c.getIntegerList("announce-minutes");
        if (announce.isEmpty()) announce = List.of(10, 5, 1);
        captureSeconds = Math.max(10, c.getInt("capture-seconds", 300));
        maxMinutes = Math.max(1, c.getInt("max-minutes", 20));
        minSecondsToWin = Math.max(0, c.getInt("min-seconds-to-win", 60));
        rewardMoney = c.getDouble("reward-money", 750000);
        rewardCommands = c.getStringList("reward-commands");
        participantSeconds = Math.max(0, c.getInt("participant-seconds", 30));
        height = Math.max(2, c.getDouble("height", 6));
        bossbarEveryone = c.getBoolean("bossbar-everyone", true);
        zoneMode = c.getString("zone-mode", "auto").toLowerCase(Locale.ROOT);
        autoWorld = c.getString("auto-zone.world", "world");
        autoCenterX = c.getDouble("auto-zone.center-x", 0);
        autoCenterZ = c.getDouble("auto-zone.center-z", 0);
        autoMin = Math.max(0, c.getDouble("auto-zone.min-distance", 300));
        autoMax = Math.max(autoMin + 32, c.getDouble("auto-zone.max-distance", 2000));
        autoRadius = Math.max(3, Math.min(30, c.getDouble("auto-zone.radius", 7)));
        autoAvoidClaims = c.getBoolean("auto-zone.avoid-guild-claims", true);
        beam = c.getBoolean("light-beam", true);
    }

    private static DayOfWeek day(String s) {
        String k = s.trim().toUpperCase(Locale.ROOT);
        return switch (k) {
            case "LUNDI", "MONDAY" -> DayOfWeek.MONDAY;
            case "MARDI", "TUESDAY" -> DayOfWeek.TUESDAY;
            case "MERCREDI", "WEDNESDAY" -> DayOfWeek.WEDNESDAY;
            case "JEUDI", "THURSDAY" -> DayOfWeek.THURSDAY;
            case "VENDREDI", "FRIDAY" -> DayOfWeek.FRIDAY;
            case "SAMEDI", "SATURDAY" -> DayOfWeek.SATURDAY;
            case "DIMANCHE", "SUNDAY" -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }

    // ================================================================= zones

    public void load() {
        zones.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("zones");
        if (root != null) {
            for (String k : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(k);
                if (s == null) continue;
                zones.put(k.toLowerCase(Locale.ROOT), new KothZone(k, s.getString("world", "world"), s.getDouble("x"),
                        s.getDouble("y"), s.getDouble("z"), s.getDouble("radius", 5)));
            }
        }
        lastZone = y.getString("last-zone");
        alwaysNotify.clear();
        for (String s : y.getStringList("notify")) {
            try {
                alwaysNotify.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (KothZone z : zones.values()) {
            String p = "zones." + z.name + ".";
            y.set(p + "world", z.world);
            y.set(p + "x", z.x);
            y.set(p + "y", z.y);
            y.set(p + "z", z.z);
            y.set(p + "radius", z.radius);
        }
        if (lastZone != null) y.set("last-zone", lastZone);
        y.set("notify", alwaysNotify.stream().map(UUID::toString).toList());
        DataStore.write(file, y.saveToString());
    }

    public KothZone zone(String name) {
        return name == null ? null : zones.get(name.toLowerCase(Locale.ROOT));
    }

    public List<KothZone> zones() {
        return new ArrayList<>(zones.values());
    }

    public KothZone create(String name, Location at, double radius) {
        KothZone z = new KothZone(name, at.getWorld().getName(), at.getBlockX() + 0.5, at.getBlockY(), at.getBlockZ() + 0.5, radius);
        zones.put(name.toLowerCase(Locale.ROOT), z);
        save();
        return z;
    }

    public boolean delete(String name) {
        KothZone z = zones.remove(name.toLowerCase(Locale.ROOT));
        if (z == null) return false;
        if (active == z) stop(false);
        if (pendingZone == z) pendingZone = null;
        save();
        return true;
    }

    public KothZone active() {
        return active;
    }

    public List<UUID> entityIds() {
        List<UUID> l = new ArrayList<>();
        if (label != null) l.add(label.getUniqueId());
        if (pillar != null) l.add(pillar.getUniqueId());
        return l;
    }

    // ================================================================= horaires

    /** Prochain lancement prévu (null = aucun). */
    public ZonedDateTime next() {
        if (schedule.isEmpty()) return null;
        ZonedDateTime now = ZonedDateTime.now(plugin.settings().zone);
        ZonedDateTime best = null;
        for (int d = 0; d < 8; d++) {
            ZonedDateTime day = now.plusDays(d);
            if (!days.isEmpty() && !days.contains(day.getDayOfWeek())) continue;
            for (LocalTime t : schedule) {
                ZonedDateTime at = day.with(t).withSecond(0).withNano(0);
                if (at.isAfter(now) && (best == null || at.isBefore(best))) best = at;
            }
            if (best != null) return best;
        }
        return best;
    }

    private void checkSchedule() {
        if (!enabled || schedule.isEmpty() || (zones.isEmpty() && zoneMode.equals("zones"))) return;
        ZonedDateTime now = ZonedDateTime.now(plugin.settings().zone).withSecond(0).withNano(0);
        if (!days.isEmpty() && !days.contains(now.getDayOfWeek())) return;
        String date = now.toLocalDate().toString();
        for (LocalTime t : schedule) {
            ZonedDateTime at = now.with(t);
            long minutesLeft = java.time.Duration.between(now, at).toMinutes();
            for (int m : announce) {
                if (minutesLeft == m) {
                    String k = date + t + "-" + m;
                    if (sent.add(k) && active == null && Bukkit.getOnlinePlayers().size() >= minPlayers) {
                        upcoming(z -> {
                            if (z != null && active == null) {
                                broadcast("koth.soon", z, ph("minutes", m));
                                Bukkit.broadcast(plugin.texts().get("koth.follow-button"));
                                for (Player p : Bukkit.getOnlinePlayers()) {
                                    if (follows(p)) p.playSound(Sound.sound(Key.key("minecraft", "block.note_block.bell"),
                                            Sound.Source.MASTER, 1f, 1f));
                                }
                            }
                        });
                    }
                }
            }
            if (minutesLeft == 0) {
                String k = date + t + "-start";
                if (sent.add(k) && active == null) {
                    if (Bukkit.getOnlinePlayers().size() < minPlayers) {
                        Bukkit.broadcast(plugin.texts().prefixed("koth.not-enough", ph("min", minPlayers)));
                    } else {
                        upcoming(z -> {
                            if (z == null) plugin.getLogger().warning("KOTH : aucun endroit valable trouvé (eau, claims...).");
                            else start(z);
                        });
                    }
                }
            }
        }
        if (sent.size() > 200) sent.clear();
    }

    private KothZone pendingZone;

    /** Zone du prochain événement : une zone créée, ou un endroit trouvé au hasard (asynchrone). */
    public void upcoming(Consumer<KothZone> then) {
        if (pendingZone != null && (pendingZone.auto || zones.containsValue(pendingZone))) {
            then.accept(pendingZone);
            return;
        }
        boolean auto = switch (zoneMode) {
            case "zones" -> false;
            case "both" -> zones.isEmpty() || random.nextInt(zones.size() + 1) == 0;
            default -> true;
        };
        if (!auto && !zones.isEmpty()) {
            List<KothZone> list = new ArrayList<>(zones.values());
            if (list.size() > 1 && lastZone != null) list.removeIf(z -> z.name.equalsIgnoreCase(lastZone));
            pendingZone = list.get(random.nextInt(list.size()));
            then.accept(pendingZone);
            return;
        }
        if (searching) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> upcoming(then), 20L);
            return;
        }
        searching = true;
        findRandom().whenComplete((z, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            searching = false;
            if (z != null) pendingZone = z;
            then.accept(z);
        }));
    }

    /** Endroit au hasard (sol plat, pas d'eau, pas dans un terrain de guilde). Chunks chargés en asynchrone. */
    private CompletableFuture<KothZone> findRandom() {
        CompletableFuture<KothZone> out = new CompletableFuture<>();
        World w = Bukkit.getWorld(autoWorld);
        if (w == null) {
            plugin.getLogger().warning("koth.auto-zone.world introuvable : " + autoWorld);
            out.complete(null);
            return out;
        }
        attempt(w, 50, out);
        return out;
    }

    private void attempt(World w, int left, CompletableFuture<KothZone> out) {
        if (left <= 0 || !plugin.isEnabled()) {
            out.complete(null);
            return;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double angle = r.nextDouble() * Math.PI * 2;
        double dist = Math.sqrt(r.nextDouble() * (autoMax * autoMax - autoMin * autoMin) + autoMin * autoMin);
        int x = (int) Math.floor(autoCenterX + Math.cos(angle) * dist);
        int z = (int) Math.floor(autoCenterZ + Math.sin(angle) * dist);
        if (!w.getWorldBorder().isInside(new Location(w, x + 0.5, 64, z + 0.5))) {
            attempt(w, left - 1, out);
            return;
        }
        w.getChunkAtAsync(x >> 4, z >> 4, true).whenComplete((chunk, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            Location ok = err == null && chunk != null ? check(w, x, z) : null;
            if (ok == null) {
                attempt(w, left - 1, out);
                return;
            }
            String name = plugin.texts().raw("koth.auto-name").replace("<x>", String.valueOf(x)).replace("<z>", String.valueOf(z));
            out.complete(new KothZone(name, w.getName(), ok.getX() + 0.5, ok.getY(), ok.getZ() + 0.5, autoRadius, true));
        }));
    }

    /** Le centre et quelques points du cercle doivent être sur la terre ferme, à peu près au même niveau. */
    private Location check(World w, int x, int z) {
        int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (y <= w.getMinHeight() + 1 || y >= w.getMaxHeight() - 20) return null;
        if (!solidGround(w.getBlockAt(x, y, z))) return null;
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8;
            int px = (int) Math.floor(x + Math.cos(a) * autoRadius), pz = (int) Math.floor(z + Math.sin(a) * autoRadius);
            if ((px >> 4) != (x >> 4) || (pz >> 4) != (z >> 4)) {
                if (!w.isChunkLoaded(px >> 4, pz >> 4)) continue;
            }
            int py = w.getHighestBlockYAt(px, pz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (Math.abs(py - y) > 4 || !solidGround(w.getBlockAt(px, py, pz))) return null;
        }
        Location loc = new Location(w, x, y + 1, z);
        if (autoAvoidClaims && Hooks.isClaimed(loc)) return null;
        return loc;
    }

    private static boolean solidGround(Block top) {
        Material m = top.getType();
        if (top.isLiquid() || !m.isSolid()) return false;
        return switch (m) {
            case ICE, PACKED_ICE, BLUE_ICE, MAGMA_BLOCK, CACTUS, POWDER_SNOW, BEDROCK -> false;
            default -> true;
        };
    }

    // ================================================================= déroulement

    public boolean start(KothZone z) {
        if (z == null || active != null) return false;
        Location c = z.center();
        if (c == null) return false;
        active = z;
        pendingZone = null;
        if (!z.auto) {
            lastZone = z.name;
            save();
        }
        elapsed = 0;
        king = null;
        contested = false;
        teamTime.clear();
        teamNames.clear();
        personal.clear();
        playerTeam.clear();
        c.getChunk().addPluginChunkTicket(plugin);
        bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_10);
        updateBar();
        label = c.getWorld().spawn(c.clone().add(0, height - 1, 0), TextDisplay.class, d -> {
            d.setPersistent(false);
            d.getPersistentDataContainer().set(plugin.key(), PersistentDataType.BYTE, (byte) 1);
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
            d.setShadowed(true);
            d.setViewRange(3f);
            d.setBrightness(new Display.Brightness(15, 15));
        });
        if (beam) {
            pillar = c.getWorld().spawn(c.clone().add(0, 0.2, 0), BlockDisplay.class, d -> {
                d.setPersistent(false);
                d.getPersistentDataContainer().set(plugin.key(), PersistentDataType.BYTE, (byte) 1);
                d.setBlock(Material.YELLOW_STAINED_GLASS.createBlockData());
                d.setTransformation(new Transformation(new Vector3f(-0.25f, 0f, -0.25f), new Quaternionf(),
                        new Vector3f(0.5f, 120f, 0.5f), new Quaternionf()));
                d.setGlowing(true);
                d.setGlowColorOverride(Color.YELLOW);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setViewRange(16f);
            });
        }
        updateLabel();
        broadcast("koth.started", z);
        Bukkit.broadcast(plugin.texts().get("koth.follow-button"));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!follows(p)) continue;
            p.playSound(Sound.sound(Key.key("minecraft", "event.raid.horn"), Sound.Source.MASTER, 0.7f, 1f));
            p.showTitle(net.kyori.adventure.title.Title.title(plugin.texts().get("koth.title"),
                    plugin.texts().get("koth.subtitle", ph("zone", z.name), ph("x", (int) z.x), ph("z", (int) z.z))));
        }
        return true;
    }

    public void stop(boolean announce) {
        if (active == null) return;
        if (announce) Bukkit.broadcast(plugin.texts().prefixed("koth.stopped", ph("zone", active.name)));
        cleanup();
    }

    private void cleanup() {
        for (var e : bars.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) p.hideBossBar(e.getValue());
        }
        bars.clear();
        bar = null;
        followers.clear();
        if (label != null) label.remove();
        label = null;
        if (pillar != null) pillar.remove();
        pillar = null;
        if (active != null) {
            Location c = active.center();
            if (c != null) c.getChunk().removePluginChunkTicket(plugin);
        }
        active = null;
    }

    /** Toutes les secondes. */
    public void tickSecond() {
        if (active == null) {
            checkSchedule();
            return;
        }
        elapsed++;
        Location center = active.center();
        if (center == null) {
            stop(true);
            return;
        }
        // qui est dans la zone ?
        Map<String, List<Player>> present = new LinkedHashMap<>();
        for (Player p : center.getWorld().getPlayers()) {
            if (!eligible(p) || !active.contains(p.getLocation(), height)) continue;
            String team = teamOf(p);
            present.computeIfAbsent(team, k -> new ArrayList<>()).add(p);
            playerTeam.put(p.getUniqueId(), team);
            personal.merge(p.getUniqueId(), 1, Integer::sum);
            plugin.quests().progress(p, QuestType.KOTH_TIME, "KOTH", 1);
        }
        String scorer = null;
        contested = false;
        if (present.size() == 1) {
            scorer = present.keySet().iterator().next();
        } else if (present.size() > 1) {
            // les alliés du roi actuel ne le contestent pas
            String base = king != null && present.containsKey(king) ? king : null;
            if (base != null && allAllied(base, present)) scorer = base;
            else contested = true;
        }
        if (scorer != null) {
            if (!scorer.equals(king)) {
                king = scorer;
                Bukkit.broadcast(plugin.texts().prefixed("koth.new-king", ph("team", teamNames.getOrDefault(king, "?")),
                        ph("zone", active.name)));
            }
            int t = teamTime.merge(scorer, 1, Integer::sum);
            if (t >= captureSeconds) {
                win(scorer);
                return;
            }
        }
        // affichage
        updateBar();
        updateLabel();
        ring(center);
        for (var e : present.entrySet()) {
            for (Player p : e.getValue()) {
                String key = contested ? "koth.bar-contested" : e.getKey().equals(scorer) ? "koth.bar-holding" : "koth.bar-waiting";
                p.sendActionBar(plugin.texts().get(key, ph("time", fmt(teamTime.getOrDefault(e.getKey(), 0))),
                        ph("capture", fmt(captureSeconds))));
            }
        }
        if (elapsed >= maxMinutes * 60) timeUp();
    }

    private boolean eligible(Player p) {
        GameMode g = p.getGameMode();
        return (g == GameMode.SURVIVAL || g == GameMode.ADVENTURE) && !p.isDead() && !Hooks.vanished(p);
    }

    private String teamOf(Player p) {
        UUID g = Hooks.guildId(p);
        String key = g != null ? "g:" + g : "p:" + p.getUniqueId();
        String name = g != null ? Hooks.guildName(p) : p.getName();
        teamNames.put(key, name == null ? p.getName() : name);
        return key;
    }

    private boolean allAllied(String base, Map<String, List<Player>> present) {
        if (!base.startsWith("g:")) return false;
        Player ref = present.get(base).getFirst();
        Set<UUID> allies = Hooks.allyIds(ref);
        for (String k : present.keySet()) {
            if (k.equals(base)) continue;
            if (!k.startsWith("g:")) return false;
            if (!allies.contains(UUID.fromString(k.substring(2)))) return false;
        }
        return true;
    }

    private void timeUp() {
        String best = null;
        int bt = 0;
        for (var e : teamTime.entrySet()) {
            if (e.getValue() > bt) {
                bt = e.getValue();
                best = e.getKey();
            }
        }
        if (best == null || bt < minSecondsToWin) {
            Bukkit.broadcast(plugin.texts().prefixed("koth.no-winner", ph("zone", active.name)));
            cleanup();
            return;
        }
        win(best);
    }

    private void win(String team) {
        KothZone z = active;
        Settings s = plugin.settings();
        Texts t = plugin.texts();
        // gagnants : membres de l'équipe restés assez longtemps dans la zone (et connectés)
        List<Player> winners = new ArrayList<>();
        for (var e : playerTeam.entrySet()) {
            if (!team.equals(e.getValue())) continue;
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && personal.getOrDefault(e.getKey(), 0) >= participantSeconds) winners.add(p);
        }
        if (winners.isEmpty()) {
            // personne n'a tenu assez longtemps : le joueur de l'équipe le plus présent gagne
            UUID bestId = null;
            int bt = -1;
            for (var e : playerTeam.entrySet()) {
                if (!team.equals(e.getValue())) continue;
                int v = personal.getOrDefault(e.getKey(), 0);
                if (v > bt && Bukkit.getPlayer(e.getKey()) != null) {
                    bt = v;
                    bestId = e.getKey();
                }
            }
            if (bestId != null) winners.add(Bukkit.getPlayer(bestId));
        }
        String teamName = teamNames.getOrDefault(team, "?");
        double each = winners.isEmpty() ? 0 : Math.floor(rewardMoney / winners.size());
        List<String> names = new ArrayList<>();
        for (Player p : winners) {
            names.add(p.getName());
            plugin.giveMoney(p, each);
            PlayerData d = plugin.data().of(p);
            d.kothWins++;
            for (String c : rewardCommands) {
                String line = c.replace("%player%", p.getName()).replace("<player>", p.getName());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line.startsWith("/") ? line.substring(1) : line);
            }
            t.send(p, "koth.you-won", ph("money", s.money(each)));
            p.playSound(Sound.sound(Key.key("minecraft", "ui.toast.challenge_complete"), Sound.Source.MASTER, 1f, 1f));
        }
        plugin.markDirty();
        Bukkit.broadcast(t.prefixed("koth.won", ph("team", teamName), ph("zone", z.name),
                ph("players", names.isEmpty() ? "-" : String.join(", ", names)), ph("money", s.money(rewardMoney)),
                ph("time", fmt(teamTime.getOrDefault(team, 0)))));
        Location c = z.center();
        if (c != null) fireworks(c);
        cleanup();
        plugin.boards().recompute();
    }

    // ================================================================= visuels

    private static String fmt(int seconds) {
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }

    private void updateBar() {
        if (bar == null || active == null) return;
        if (pillar != null && pillar.isValid()) {
            Color col = contested ? Color.RED : king == null ? Color.YELLOW : Color.LIME;
            if (!col.equals(pillar.getGlowColorOverride())) {
                pillar.setGlowColorOverride(col);
                pillar.setBlock((contested ? Material.RED_STAINED_GLASS : king == null ? Material.YELLOW_STAINED_GLASS
                        : Material.LIME_STAINED_GLASS).createBlockData());
            }
        }
        int kt = king == null ? 0 : teamTime.getOrDefault(king, 0);
        String kingName = king == null ? plugin.texts().raw("koth.nobody") : teamNames.getOrDefault(king, "?");
        String status = contested ? "koth.boss-contested" : "koth.boss";
        int left = maxMinutes * 60 - elapsed;
        float progress = Math.max(0f, Math.min(1f, kt / (float) captureSeconds));
        BossBar.Color color = contested ? BossBar.Color.RED : king == null ? BossBar.Color.WHITE : BossBar.Color.YELLOW;
        Location c = active.center();
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean near = c != null && p.getWorld() == c.getWorld() && p.getLocation().distanceSquared(c) < 80 * 80;
            if (!follows(p) && !near) {
                BossBar old = bars.remove(p.getUniqueId());
                if (old != null) p.hideBossBar(old);
                continue;
            }
            Component name = plugin.texts().get(contested ? "koth.bar2-contested" : "koth.bar2", ph("zone", active.name),
                    ph("king", kingName), ph("time", fmt(kt)), ph("capture", fmt(captureSeconds)), ph("left", fmt(Math.max(0, left))),
                    ph("x", (int) active.x), ph("y", (int) active.y), ph("z", (int) active.z), direction(p, c));
            BossBar b = bars.get(p.getUniqueId());
            if (b == null) {
                b = BossBar.bossBar(name, progress, color, BossBar.Overlay.NOTCHED_10);
                bars.put(p.getUniqueId(), b);
                p.showBossBar(b);
            } else {
                b.name(name);
                b.progress(progress);
                b.color(color);
            }
        }
    }

    /** Flèche et distance vers la colline, pour ce joueur. */
    private TagResolver direction(Player p, Location c) {
        if (c == null || p.getWorld() != c.getWorld()) {
            return TagResolver.resolver(ph("arrow", "✖"), ph("distance", worldName(active.world)));
        }
        double dx = c.getX() - p.getLocation().getX(), dz = c.getZ() - p.getLocation().getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist <= active.radius) return TagResolver.resolver(ph("arrow", "●"), ph("distance", plugin.texts().raw("koth.here")));
        double rel = Math.toDegrees(Math.atan2(-dx, dz)) - p.getLocation().getYaw();
        rel = ((rel % 360) + 540) % 360 - 180;
        int idx = ((int) Math.round(rel / 45.0) % 8 + 8) % 8;
        String d = dist >= 1000 ? String.format(Locale.FRANCE, "%.1f km", dist / 1000) : (int) dist + " m";
        return TagResolver.resolver(ph("arrow", ARROWS[idx]), ph("distance", d));
    }

    // ================================================================= suivre l'événement

    public boolean follows(Player p) {
        return followers.contains(p.getUniqueId()) || alwaysNotify.contains(p.getUniqueId());
    }

    /** [Suivre ce KOTH] : true = suivi. */
    public boolean toggleFollow(Player p) {
        if (followers.remove(p.getUniqueId())) {
            BossBar b = bars.remove(p.getUniqueId());
            if (b != null) p.hideBossBar(b);
            return false;
        }
        followers.add(p.getUniqueId());
        p.playSound(Sound.sound(Key.key("minecraft", "block.note_block.bell"), Sound.Source.MASTER, 1f, 1.4f));
        updateBar();
        return true;
    }

    public boolean toggleNotify(Player p) {
        boolean on = !alwaysNotify.remove(p.getUniqueId());
        if (on) alwaysNotify.add(p.getUniqueId());
        save();
        if (active != null) updateBar();
        return on;
    }

    private void updateLabel() {
        if (label == null || !label.isValid() || active == null) return;
        int kt = king == null ? 0 : teamTime.getOrDefault(king, 0);
        String kingName = king == null ? plugin.texts().raw("koth.nobody") : teamNames.getOrDefault(king, "?");
        TagResolver r = TagResolver.resolver(ph("zone", active.name), ph("king", kingName), ph("time", fmt(kt)),
                ph("capture", fmt(captureSeconds)),
                pc("status", plugin.texts().get(contested ? "koth.label-contested" : king == null ? "koth.label-free" : "koth.label-held")));
        label.text(plugin.texts().lines("koth.label", r));
    }

    private void ring(Location c) {
        World w = c.getWorld();
        Color col = contested ? Color.RED : king == null ? Color.WHITE : Color.YELLOW;
        Particle.DustOptions dust = new Particle.DustOptions(col, 1.6f);
        int points = (int) Math.max(24, active.radius * 8);
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            Location l = new Location(w, c.getX() + Math.cos(a) * active.radius, c.getY() + 0.3, c.getZ() + Math.sin(a) * active.radius);
            w.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, dust, true);
        }
    }

    private void fireworks(Location c) {
        for (int i = 0; i < 4; i++) {
            Location l = c.clone().add(random.nextDouble() * 4 - 2, 1, random.nextDouble() * 4 - 2);
            c.getWorld().spawn(l, Firework.class, f -> {
                FireworkMeta m = f.getFireworkMeta();
                m.addEffect(FireworkEffect.builder().withColor(Color.YELLOW, Color.ORANGE).withFade(Color.WHITE)
                        .with(FireworkEffect.Type.BALL_LARGE).trail(true).build());
                m.setPower(1);
                f.setFireworkMeta(m);
            });
        }
    }

    private void broadcast(String key, KothZone z, TagResolver... extra) {
        TagResolver base = TagResolver.resolver(ph("zone", z.name), ph("world", worldName(z.world)),
                ph("x", (int) Math.floor(z.x)), ph("y", (int) Math.floor(z.y)), ph("z", (int) Math.floor(z.z)),
                ph("capture", fmt(captureSeconds)), ph("money", plugin.settings().money(rewardMoney)));
        TagResolver all = TagResolver.resolver(base, TagResolver.resolver(extra));
        for (Component c : plugin.texts().list(key, all)) Bukkit.broadcast(c);
    }

    private String worldName(String w) {
        String k = "koth.worlds." + w;
        String v = plugin.texts().raw(k);
        return v.startsWith("<red>[") ? w : v;
    }

    /** Un joueur qui se connecte pendant l'événement voit la barre. */
    public void onJoin(Player p) {
        if (active != null) updateBar();
    }

    public int elapsed() {
        return elapsed;
    }

    public String kingName() {
        return king == null ? null : teamNames.get(king);
    }

    public int kingTime() {
        return king == null ? 0 : teamTime.getOrDefault(king, 0);
    }
}
