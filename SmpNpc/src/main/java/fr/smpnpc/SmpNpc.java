package fr.smpnpc;

import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * PNJ médiévaux (mannequins avec skin de joueur) qui se promènent au hasard dans des zones,
 * s'arrêtent, regardent les joueurs proches et disent une phrase quand on clique dessus.
 * Ils ne sont jamais sauvegardés dans le monde : recréés au démarrage / chargement du chunk.
 */
public final class SmpNpc extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    record Zone(String name, String world, double x, double y, double z, double radius, int count) {
        Location center() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z);
        }
    }

    record Profession(String id, String title, Map<EquipmentSlot, String> items) {
    }

    record Skin(String name, String value, String signature, String profession) {
    }

    static final class Walker {
        final Zone zone;
        final String name;
        final Profession job;
        Mannequin entity;
        Location target;
        int pause;

        Walker(Zone zone, String name, Profession job) {
            this.zone = zone;
            this.name = name;
            this.job = job;
        }
    }

    private final Map<String, Zone> zones = new LinkedHashMap<>();
    private final Map<String, List<Walker>> walkers = new HashMap<>();
    private final List<Profession> professions = new ArrayList<>();
    private final List<Skin> skins = new ArrayList<>();
    private List<String> names = List.of("Villageois");
    private List<String> phrases = List.of("Bonjour !");
    private double step;
    private int pauseMin, pauseMax;
    private double lookDistance;
    private boolean showNames;
    private NamespacedKey key;
    private File zonesFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        key = new NamespacedKey(this, "walker");
        zonesFile = new File(getDataFolder(), "zones.yml");
        loadSettings();
        loadZones();
        getServer().getPluginManager().registerEvents(this, this);
        // marche : toutes les 2 ticks ; apparition / réapparition : toutes les 5 secondes
        Bukkit.getScheduler().runTaskTimer(this, this::tickWalk, 2L, 2L);
        Bukkit.getScheduler().runTaskTimer(this, this::ensureAll, 40L, 100L);
        // restes d'un crash : mannequins marqués mais plus suivis
        Bukkit.getScheduler().runTaskLater(this, this::cleanupStray, 60L);
    }

    @Override
    public void onDisable() {
        for (List<Walker> l : walkers.values()) for (Walker w : l) if (w.entity != null) w.entity.remove();
        walkers.clear();
    }

    // ================================================================= réglages

    private void loadSettings() {
        reloadConfig();
        var c = getConfig();
        step = Math.max(0.2, c.getDouble("speed", 1.6)) / 10.0; // 10 pas par seconde
        pauseMin = Math.max(0, c.getInt("pause-min", 2)) * 20;
        pauseMax = Math.max(pauseMin + 1, c.getInt("pause-max", 7) * 20);
        lookDistance = c.getDouble("look-at-players", 6);
        showNames = c.getBoolean("show-names", true);
        names = c.getStringList("names").isEmpty() ? List.of("Villageois") : c.getStringList("names");
        phrases = c.getStringList("phrases").isEmpty() ? List.of("Bonjour !") : c.getStringList("phrases");
        professions.clear();
        ConfigurationSection ps = c.getConfigurationSection("professions");
        if (ps != null) {
            for (String id : ps.getKeys(false)) {
                ConfigurationSection p = ps.getConfigurationSection(id);
                if (p == null) continue;
                Map<EquipmentSlot, String> items = new HashMap<>();
                put(items, EquipmentSlot.HEAD, p.getString("helmet"));
                put(items, EquipmentSlot.CHEST, p.getString("chestplate"));
                put(items, EquipmentSlot.LEGS, p.getString("leggings"));
                put(items, EquipmentSlot.FEET, p.getString("boots"));
                put(items, EquipmentSlot.HAND, p.getString("main-hand"));
                put(items, EquipmentSlot.OFF_HAND, p.getString("off-hand"));
                professions.add(new Profession(id, p.getString("title", id), items));
            }
        }
        if (professions.isEmpty()) professions.add(new Profession("villageois", "<gray>Villageois", Map.of()));
        skins.clear();
        for (Map<?, ?> m : c.getMapList("skins")) {
            Object n = m.get("name"), v = m.get("value"), s = m.get("signature"), job = m.get("profession");
            if (n == null && v == null) continue;
            skins.add(new Skin(n == null ? null : String.valueOf(n), v == null ? null : String.valueOf(v),
                    s == null ? null : String.valueOf(s), job == null ? null : String.valueOf(job).toLowerCase(Locale.ROOT)));
        }
    }

    private static void put(Map<EquipmentSlot, String> m, EquipmentSlot slot, String v) {
        if (v != null && !v.isBlank()) m.put(slot, v);
    }

    private void loadZones() {
        zones.clear();
        if (!zonesFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(zonesFile);
        for (String k : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(k);
            if (s == null) continue;
            zones.put(k.toLowerCase(Locale.ROOT), new Zone(k, s.getString("world", "world"), s.getDouble("x"), s.getDouble("y"),
                    s.getDouble("z"), s.getDouble("radius", 20), s.getInt("count", 10)));
        }
    }

    private void saveZones() {
        YamlConfiguration y = new YamlConfiguration();
        for (Zone z : zones.values()) {
            y.set(z.name() + ".world", z.world());
            y.set(z.name() + ".x", z.x());
            y.set(z.name() + ".y", z.y());
            y.set(z.name() + ".z", z.z());
            y.set(z.name() + ".radius", z.radius());
            y.set(z.name() + ".count", z.count());
        }
        try {
            y.save(zonesFile);
        } catch (IOException e) {
            getLogger().warning("Sauvegarde des zones impossible : " + e.getMessage());
        }
    }

    // ================================================================= apparition

    private void ensureAll() {
        for (Zone z : zones.values()) {
            Location c = z.center();
            if (c == null || !c.getWorld().isChunkLoaded(c.getBlockX() >> 4, c.getBlockZ() >> 4)) continue;
            List<Walker> list = walkers.computeIfAbsent(z.name().toLowerCase(Locale.ROOT), k -> new ArrayList<>());
            list.removeIf(w -> w.entity == null || !w.entity.isValid());
            while (list.size() < z.count()) {
                Walker w = spawn(z);
                if (w == null) break;
                list.add(w);
            }
        }
    }

    private Walker spawn(Zone z) {
        Location at = randomPoint(z);
        if (at == null) return null;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Profession job = professions.get(r.nextInt(professions.size()));
        String name = names.get(r.nextInt(names.size()));
        Walker w = new Walker(z, name, job);
        at.setYaw(r.nextFloat() * 360f);
        w.entity = at.getWorld().spawn(at, Mannequin.class, m -> {
            m.setPersistent(false);
            m.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            m.setInvulnerable(true);
            m.setGravity(false);
            m.setSilent(true);
            m.setImmovable(true);
            m.setProfile(profileFor(job));
            if (showNames) {
                m.customName(parse("<white>" + MM.escapeTags(name)));
                m.setCustomNameVisible(true);
                m.setDescription(parse(job.title()));
            } else {
                m.setDescription(null);
            }
            EntityEquipment eq = m.getEquipment();
            for (var e : job.items().entrySet()) {
                ItemStack it = item(e.getValue());
                if (it != null) eq.setItem(e.getKey(), it);
            }
        });
        w.pause = r.nextInt(pauseMin, pauseMax);
        return w;
    }

    private ResolvableProfile profileFor(Profession job) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<Skin> pool = new ArrayList<>();
        for (Skin s : skins) if (s.profession() == null || s.profession().equals(job.id())) pool.add(s);
        if (pool.isEmpty()) {
            // skin Minecraft par défaut (choisi par le jeu à partir de l'identifiant)
            return ResolvableProfile.resolvableProfile().uuid(UUID.randomUUID()).build();
        }
        Skin s = pool.get(r.nextInt(pool.size()));
        if (s.value() != null) {
            return ResolvableProfile.resolvableProfile().uuid(UUID.randomUUID())
                    .addProperty(new ProfileProperty("textures", s.value(), s.signature())).build();
        }
        return ResolvableProfile.resolvableProfile().name(s.name()).build();
    }

    private static ItemStack item(String spec) {
        String[] parts = spec.split("@", 2);
        Material m = Material.matchMaterial(parts[0].trim());
        if (m == null || !m.isItem()) return null;
        ItemStack it = new ItemStack(m);
        if (parts.length == 2 && it.getItemMeta() instanceof LeatherArmorMeta lm) {
            try {
                lm.setColor(Color.fromRGB(Integer.parseInt(parts[1].trim().replace("#", ""), 16)));
                it.setItemMeta(lm);
            } catch (NumberFormatException ignored) {
            }
        }
        return it;
    }

    private static Component parse(String s) {
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private void cleanupStray() {
        for (World w : Bukkit.getWorlds()) {
            for (Mannequin m : w.getEntitiesByClass(Mannequin.class)) {
                if (!m.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) continue;
                boolean tracked = false;
                for (List<Walker> l : walkers.values()) for (Walker wk : l) if (wk.entity == m) tracked = true;
                if (!tracked) m.remove();
            }
        }
    }

    // ================================================================= marche

    private void tickWalk() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (List<Walker> list : walkers.values()) {
            for (Walker w : list) {
                Mannequin e = w.entity;
                if (e == null || !e.isValid()) continue;
                Location cur = e.getLocation();
                if (w.pause > 0) {
                    w.pause -= 2;
                    if (w.pause % 10 == 0) lookAround(e, cur);
                    continue;
                }
                if (w.target == null) {
                    w.target = randomPoint(w.zone);
                    if (w.target == null) {
                        w.pause = 40;
                        continue;
                    }
                }
                double dx = w.target.getX() - cur.getX(), dz = w.target.getZ() - cur.getZ();
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist < 0.4) {
                    w.target = null;
                    w.pause = r.nextInt(pauseMin, pauseMax);
                    continue;
                }
                double s = Math.min(step, dist);
                double nx = cur.getX() + dx / dist * s, nz = cur.getZ() + dz / dist * s;
                Double ny = surface(cur.getWorld(), nx, cur.getY(), nz);
                if (ny == null || !inZone(w.zone, nx, nz)) {
                    w.target = null;
                    w.pause = 20;
                    continue;
                }
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                e.teleport(new Location(cur.getWorld(), nx, ny, nz, yaw, 0f));
            }
        }
    }

    private void lookAround(Mannequin e, Location cur) {
        Player best = null;
        double bd = lookDistance * lookDistance;
        for (Player p : cur.getWorld().getPlayers()) {
            double d = p.getLocation().distanceSquared(cur);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        if (best == null) return;
        Location eye = best.getEyeLocation();
        double dx = eye.getX() - cur.getX(), dy = eye.getY() - (cur.getY() + 1.62), dz = eye.getZ() - cur.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        e.setRotation(yaw, pitch);
    }

    private static boolean inZone(Zone z, double x, double zz) {
        double dx = x - z.x(), dz = zz - z.z();
        return dx * dx + dz * dz <= z.radius() * z.radius();
    }

    /** Hauteur du sol où poser les pieds près de (x, y, z) : marche de 1 bloc max en montée, 2 en descente. */
    private static Double surface(World w, double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z), by = (int) Math.floor(y);
        Double best = null;
        for (int fy = by + 1; fy >= by - 2; fy--) {
            Block b = w.getBlockAt(bx, fy, bz);
            if (b.isLiquid()) return null;
            if (b.isPassable()) continue;
            BoundingBox box = b.getBoundingBox();
            if (box.getVolume() <= 0) continue;
            double top = box.getMaxY();
            if (top - y > 1.1 || y - top > 2.1) continue;
            if (!free(w, bx, top, bz)) continue;
            best = top;
            break;
        }
        return best;
    }

    /** 2 blocs d'air (sans liquide) au-dessus de la hauteur donnée. */
    private static boolean free(World w, int bx, double top, int bz) {
        int base = (int) Math.floor(top + 0.01);
        for (int i = 0; i < 2; i++) {
            Block b = w.getBlockAt(bx, base + i, bz);
            if (b.isLiquid()) return false;
            if (!b.isPassable()) {
                double bottom = b.getBoundingBox().getMinY();
                if (b.getBoundingBox().getVolume() > 0 && bottom < top + 1.8) return false;
            }
        }
        return true;
    }

    private Location randomPoint(Zone z) {
        Location c = z.center();
        if (c == null) return null;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int tries = 0; tries < 12; tries++) {
            double a = r.nextDouble() * Math.PI * 2, d = Math.sqrt(r.nextDouble()) * z.radius();
            double x = z.x() + Math.cos(a) * d, zz = z.z() + Math.sin(a) * d;
            int bx = (int) Math.floor(x), bz = (int) Math.floor(zz);
            if (!c.getWorld().isChunkLoaded(bx >> 4, bz >> 4)) continue;
            for (int fy = (int) z.y() + 6; fy >= (int) z.y() - 6; fy--) {
                Block b = c.getWorld().getBlockAt(bx, fy, bz);
                if (b.isLiquid()) break;
                if (b.isPassable() || b.getBoundingBox().getVolume() <= 0) continue;
                double top = b.getBoundingBox().getMaxY();
                if (b.getRelative(BlockFace.UP).isLiquid()) break;
                if (free(c.getWorld(), bx, top, bz)) return new Location(c.getWorld(), x, top, zz);
                break;
            }
        }
        return null;
    }

    // ================================================================= clic droit : une phrase

    @EventHandler
    public void onClick(PlayerInteractEntityEvent e) {
        Entity t = e.getRightClicked();
        if (!(t instanceof Mannequin m) || !m.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        for (List<Walker> l : walkers.values()) {
            for (Walker w : l) {
                if (w.entity != m) continue;
                w.pause = Math.max(w.pause, 60);
                w.target = null;
                lookAround(m, m.getLocation());
                String phrase = phrases.get(ThreadLocalRandom.current().nextInt(phrases.size()));
                e.getPlayer().sendMessage(MM.deserialize(getConfig().getString("chat-format", "<gray>[<name>] <phrase>"),
                        Placeholder.unparsed("name", w.name), Placeholder.unparsed("phrase", phrase)));
                return;
            }
        }
    }

    // ================================================================= commande /pnj

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "zone" -> {
                String act = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                if ((act.equals("creer") || act.equals("create")) && a.length >= 3 && sender instanceof Player p) {
                    double radius = a.length >= 4 ? parse(a[3], 20) : 20;
                    int count = a.length >= 5 ? (int) parse(a[4], 10) : 10;
                    Location l = p.getLocation();
                    Zone z = new Zone(a[2], l.getWorld().getName(), l.getX(), l.getY(), l.getZ(),
                            Math.max(3, Math.min(150, radius)), Math.max(1, Math.min(60, count)));
                    removeZone(z.name());
                    zones.put(z.name().toLowerCase(Locale.ROOT), z);
                    saveZones();
                    ensureAll();
                    sender.sendMessage(parse("<green>Zone <white>" + MM.escapeTags(z.name()) + "</white> créée : <white>"
                            + z.count() + "</white> PNJ sur <white>" + (int) z.radius() + "</white> blocs autour de toi."));
                    return true;
                }
                if ((act.equals("supprimer") || act.equals("delete")) && a.length >= 3) {
                    boolean ok = zones.remove(a[2].toLowerCase(Locale.ROOT)) != null;
                    removeZone(a[2]);
                    saveZones();
                    sender.sendMessage(parse(ok ? "<green>Zone supprimée." : "<red>Zone inconnue."));
                    return true;
                }
                sender.sendMessage(parse("<red>/pnj zone creer <nom> [rayon] [nombre]  ·  /pnj zone supprimer <nom>"));
            }
            case "liste", "list" -> {
                if (zones.isEmpty()) sender.sendMessage(parse("<gray>Aucune zone. <white>/pnj zone creer spawn 25 12"));
                for (Zone z : zones.values()) {
                    int alive = walkers.getOrDefault(z.name().toLowerCase(Locale.ROOT), List.of()).size();
                    sender.sendMessage(parse("<gold>" + MM.escapeTags(z.name()) + "</gold> <dark_gray>»</dark_gray> <gray>"
                            + z.world() + " " + (int) z.x() + " " + (int) z.y() + " " + (int) z.z() + " · rayon " + (int) z.radius()
                            + " · " + alive + "/" + z.count() + " PNJ"));
                }
            }
            case "respawn" -> {
                for (String k : new ArrayList<>(walkers.keySet())) removeZone(k);
                ensureAll();
                sender.sendMessage(parse("<green>PNJ recréés (nouveaux noms, tenues et skins)."));
            }
            case "reload" -> {
                loadSettings();
                for (String k : new ArrayList<>(walkers.keySet())) removeZone(k);
                loadZones();
                ensureAll();
                sender.sendMessage(parse("<green>SmpNpc rechargé : " + skins.size() + " skin(s), " + professions.size() + " métier(s)."));
            }
            default -> {
                sender.sendMessage(parse("<gold><b>PNJ médiévaux"));
                sender.sendMessage(parse("<aqua>/pnj zone creer <nom> [rayon] [nombre]</aqua> <dark_gray>-</dark_gray> <gray>au centre de la zone"));
                sender.sendMessage(parse("<aqua>/pnj zone supprimer <nom></aqua> · <aqua>/pnj liste</aqua> · <aqua>/pnj respawn</aqua> · <aqua>/pnj reload"));
            }
        }
        return true;
    }

    private void removeZone(String name) {
        List<Walker> l = walkers.remove(name.toLowerCase(Locale.ROOT));
        if (l != null) for (Walker w : l) if (w.entity != null) w.entity.remove();
    }

    private static double parse(String s, double def) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("zone", "liste", "respawn", "reload"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("zone")) out.addAll(List.of("creer", "supprimer"));
        else if (a.length == 3 && a[0].equalsIgnoreCase("zone") && a[1].equalsIgnoreCase("supprimer")) {
            for (Zone z : zones.values()) out.add(z.name());
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
