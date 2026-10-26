package fr.smphalloween;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Transforme une zone (le spawn) en version Halloween : remplacement de blocs (en gardant leurs états),
 * décorations (citrouilles, toiles, bougies, feuilles mortes...). Tout est sauvegardé : /halloween retirer remet tout.
 */
public final class SmpHalloween extends JavaPlugin {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int MAGIC = 0x48414C57; // "HALW"
    private static final BlockFace[] HORIZONTAL = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
    private static final BlockFace[] ROTATIONS = {BlockFace.NORTH, BlockFace.NORTH_NORTH_EAST, BlockFace.NORTH_EAST, BlockFace.EAST_NORTH_EAST,
            BlockFace.EAST, BlockFace.EAST_SOUTH_EAST, BlockFace.SOUTH_EAST, BlockFace.SOUTH_SOUTH_EAST, BlockFace.SOUTH,
            BlockFace.SOUTH_SOUTH_WEST, BlockFace.SOUTH_WEST, BlockFace.WEST_SOUTH_WEST, BlockFace.WEST, BlockFace.WEST_NORTH_WEST,
            BlockFace.NORTH_WEST, BlockFace.NORTH_NORTH_WEST};

    /** Une cible possible d'un remplacement ("pale_oak_*" pour une famille). */
    private record Choice(String id, double weight) {
    }

    private record Rule(int index, String prefix, List<Choice> choices) {
    }

    // zone
    private String worldName;
    private int minX, minY, minZ, maxX, maxY, maxZ;
    private boolean cylinder;
    private int cx, cz, radius;
    private Location pos1, pos2;
    // règles
    private final Map<String, Rule> exact = new HashMap<>();
    private final List<Rule> families = new ArrayList<>();
    private final Map<Material, Rule> cache = new EnumMap<>(Material.class);
    private final Map<String, BlockData> dataCache = new HashMap<>();
    private final Set<Material> ground = new HashSet<>();
    private final List<Choice> decorations = new ArrayList<>();
    // chantier
    private BukkitTask job;
    private final Map<UUID, Long> confirm = new HashMap<>();
    private File dataFile, backupFile;

    static Component mm(String s) {
        return MM.deserialize(s);
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "zone.yml");
        backupFile = new File(getDataFolder(), "sauvegarde.dat");
        loadRules();
        loadZone();
    }

    @Override
    public void onDisable() {
        if (job != null) job.cancel();
    }

    // ================================================================= règles

    private void loadRules() {
        exact.clear();
        families.clear();
        cache.clear();
        dataCache.clear();
        ground.clear();
        decorations.clear();
        ConfigurationSection r = getConfig().getConfigurationSection("replace");
        int i = 0;
        if (r != null) {
            for (String from : r.getKeys(false)) {
                List<Choice> choices = parseChoices(r.getString(from, ""));
                if (choices.isEmpty()) continue;
                String f = from.toLowerCase(Locale.ROOT).replace("minecraft:", "");
                if (f.endsWith("*")) families.add(new Rule(i++, f.substring(0, f.length() - 1), choices));
                else exact.put(f, new Rule(i++, null, choices));
            }
        }
        // la famille la plus précise d'abord (mossy_stone_brick* avant stone_brick*)
        families.sort((a, b) -> Integer.compare(b.prefix().length(), a.prefix().length()));
        for (String g : getConfig().getStringList("decorations.ground")) {
            Material m = Material.matchMaterial(g);
            if (m != null) ground.add(m);
        }
        ConfigurationSection d = getConfig().getConfigurationSection("decorations.on-ground");
        if (d != null) for (String k : d.getKeys(false)) decorations.add(new Choice(k, d.getDouble(k)));
    }

    private static List<Choice> parseChoices(String s) {
        List<Choice> out = new ArrayList<>();
        for (String part : s.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            double w = 1;
            int colon = p.lastIndexOf(':');
            if (colon > 0 && p.substring(colon + 1).trim().matches("[0-9.]+")) {
                w = Double.parseDouble(p.substring(colon + 1).trim());
                p = p.substring(0, colon).trim();
            }
            out.add(new Choice(p.toLowerCase(Locale.ROOT).replace("minecraft:", ""), w));
        }
        return out;
    }

    private Rule rule(Material m) {
        if (cache.containsKey(m)) return cache.get(m);
        String id = m.getKey().getKey();
        Rule r = exact.get(id);
        if (r == null) {
            for (Rule f : families) {
                if (id.startsWith(f.prefix())) {
                    r = f;
                    break;
                }
            }
        }
        cache.put(m, r);
        return r;
    }

    private static double hash(int x, int y, int z, int s) {
        long h = x * 73856093L ^ y * 19349663L ^ z * 83492791L ^ s * 2654435761L;
        h ^= h >>> 13;
        h *= 0x5bd1e995L;
        h ^= h >>> 15;
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }

    /** Choix pondéré, le même pour tout un paquet de blocs (un arbre entier de la même couleur). */
    private String choose(Rule r, String id, int x, int y, int z) {
        double total = 0;
        for (Choice c : r.choices()) total += c.weight();
        int cs = Math.max(1, getConfig().getInt("cluster-size", 6));
        double h = cs <= 1 ? ThreadLocalRandom.current().nextDouble()
                : hash(Math.floorDiv(x, cs), Math.floorDiv(y, cs), Math.floorDiv(z, cs), r.index());
        double t = h * total;
        Choice pick = r.choices().get(r.choices().size() - 1);
        for (Choice c : r.choices()) {
            t -= c.weight();
            if (t < 0) {
                pick = c;
                break;
            }
        }
        String to = pick.id();
        if (r.prefix() != null && to.endsWith("*")) to = to.substring(0, to.length() - 1) + id.substring(r.prefix().length());
        return to;
    }

    /** Le nouveau bloc en gardant les états de l'ancien (escaliers, dalles...) quand c'est possible. */
    private BlockData convert(BlockData old, String to) {
        if (to.equals("air")) return Material.AIR.createBlockData();
        String s = old.getAsString();
        int br = s.indexOf('[');
        String states = br < 0 ? "" : s.substring(br);
        BlockData out = parse("minecraft:" + to + states);
        if (out == null) out = parse("minecraft:" + to);
        if (out instanceof Leaves l) l.setPersistent(true); // ne disparaissent pas loin des troncs
        return out;
    }

    private BlockData parse(String s) {
        if (dataCache.containsKey(s)) {
            BlockData d = dataCache.get(s);
            return d == null ? null : d.clone();
        }
        BlockData d;
        try {
            d = Bukkit.createBlockData(s);
        } catch (IllegalArgumentException e) {
            d = null;
        }
        dataCache.put(s, d);
        return d == null ? null : d.clone();
    }

    private static boolean leafy(Material m) {
        return m.name().endsWith("_LEAVES") || m == Material.BROWN_MUSHROOM_BLOCK;
    }

    // ================================================================= zone

    private void loadZone() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        worldName = y.getString("world");
        minX = y.getInt("min-x");
        minY = y.getInt("min-y");
        minZ = y.getInt("min-z");
        maxX = y.getInt("max-x");
        maxY = y.getInt("max-y");
        maxZ = y.getInt("max-z");
        cylinder = y.getBoolean("cylinder");
        cx = y.getInt("cx");
        cz = y.getInt("cz");
        radius = y.getInt("radius");
    }

    private void saveZone() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("world", worldName);
        y.set("min-x", minX);
        y.set("min-y", minY);
        y.set("min-z", minZ);
        y.set("max-x", maxX);
        y.set("max-y", maxY);
        y.set("max-z", maxZ);
        y.set("cylinder", cylinder);
        y.set("cx", cx);
        y.set("cz", cz);
        y.set("radius", radius);
        try {
            y.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Sauvegarde de la zone impossible : " + e.getMessage());
        }
    }

    private boolean hasZone() {
        return worldName != null && Bukkit.getWorld(worldName) != null;
    }

    private long volume() {
        long v = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        return cylinder ? (long) (v * Math.PI / 4) : v;
    }

    private String zoneText() {
        if (!hasZone()) return "<red>aucune";
        return cylinder ? "cercle de rayon " + radius + " autour de " + cx + " " + cz + " (Y " + minY + " à " + maxY + ")"
                : minX + " " + minY + " " + minZ + " → " + maxX + " " + maxY + " " + maxZ;
    }

    // ================================================================= commandes

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "zone", "rayon" -> {
                if (!(sender instanceof Player p)) return true;
                if (a.length < 2) {
                    sender.sendMessage(mm("<red>/halloween zone <rayon> [blocs en dessous] [blocs au-dessus]"));
                    return true;
                }
                int r = Math.max(5, Math.min(400, num(a[1], 80)));
                int down = a.length >= 3 ? num(a[2], 20) : 20, up = a.length >= 4 ? num(a[3], 60) : 60;
                Location l = p.getLocation();
                World w = l.getWorld();
                worldName = w.getName();
                cylinder = true;
                cx = l.getBlockX();
                cz = l.getBlockZ();
                radius = r;
                minX = cx - r;
                maxX = cx + r;
                minZ = cz - r;
                maxZ = cz + r;
                minY = Math.max(w.getMinHeight(), l.getBlockY() - down);
                maxY = Math.min(w.getMaxHeight() - 1, l.getBlockY() + up);
                saveZone();
                sender.sendMessage(mm("<gold>Zone Halloween : <white>" + zoneText() + "</white>. <gray>/halloween apercu pour voir ce qui changerait."));
            }
            case "pos1", "pos2" -> {
                if (!(sender instanceof Player p)) return true;
                Location l = p.getLocation().getBlock().getLocation();
                if (sub.equals("pos1")) pos1 = l;
                else pos2 = l;
                sender.sendMessage(mm("<gold>" + sub + " : <white>" + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()));
                if (pos1 != null && pos2 != null && pos1.getWorld() == pos2.getWorld()) {
                    worldName = pos1.getWorld().getName();
                    cylinder = false;
                    minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
                    maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
                    minY = Math.min(pos1.getBlockY(), pos2.getBlockY());
                    maxY = Math.max(pos1.getBlockY(), pos2.getBlockY());
                    minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
                    maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());
                    saveZone();
                    sender.sendMessage(mm("<gold>Zone Halloween : <white>" + zoneText()));
                }
            }
            case "apercu", "aperçu", "preview" -> start(sender, true);
            case "appliquer", "apply" -> {
                if (backupFile.exists()) {
                    sender.sendMessage(mm("<red>Le spawn est déjà en mode Halloween. <gray>/halloween retirer d'abord."));
                    return true;
                }
                UUID id = sender instanceof Player p ? p.getUniqueId() : new UUID(0, 0);
                Long until = confirm.get(id);
                if (until == null || until < System.currentTimeMillis()) {
                    confirm.put(id, System.currentTimeMillis() + 20_000);
                    sender.sendMessage(mm("<gold><b>Transformer la zone en Halloween ?</b> <gray>(" + zoneText() + ", ~" + volume() / 1000 + "k blocs à vérifier)"));
                    sender.sendMessage(mm("<gray>Tout est sauvegardé, <aqua>/halloween retirer</aqua> remet exactement comme avant."));
                    sender.sendMessage(mm("<yellow>Retape <white>/halloween appliquer</white> dans les 20 secondes pour confirmer."));
                    return true;
                }
                confirm.remove(id);
                start(sender, false);
            }
            case "retirer", "remove", "undo" -> undo(sender);
            case "reload" -> {
                reloadConfig();
                loadRules();
                sender.sendMessage(mm("<green>SmpHalloween rechargé (" + (exact.size() + families.size()) + " remplacements)."));
            }
            case "stop" -> {
                if (job != null) {
                    job.cancel();
                    job = null;
                    sender.sendMessage(mm("<yellow>Chantier arrêté. <gray>(ce qui a déjà été changé reste sauvegardé : /halloween retirer)"));
                }
            }
            default -> {
                sender.sendMessage(mm("<gold><b>🎃 SmpHalloween"));
                sender.sendMessage(mm("<aqua>/halloween zone <rayon> [dessous] [dessus]</aqua> <gray>- zone autour de toi"));
                sender.sendMessage(mm("<aqua>/halloween pos1</aqua> / <aqua>pos2</aqua> <gray>- ou une boîte précise"));
                sender.sendMessage(mm("<aqua>/halloween apercu</aqua> <gray>- ce qui changerait (rien n'est modifié)"));
                sender.sendMessage(mm("<aqua>/halloween appliquer</aqua> <gray>- transforme · <aqua>/halloween retirer</aqua> <gray>- remet comme avant"));
                sender.sendMessage(mm("<gray>Zone : <white>" + zoneText() + "</white> · état : "
                        + (backupFile.exists() ? "<gold>Halloween actif" : "<gray>normal")));
            }
        }
        return true;
    }

    private static int num(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("zone", "pos1", "pos2", "apercu", "appliquer", "retirer", "stop", "reload"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("zone")) out.addAll(List.of("50", "80", "120", "150"));
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.startsWith(last));
        return out;
    }

    // ================================================================= transformation

    private void start(CommandSender sender, boolean preview) {
        if (job != null) {
            sender.sendMessage(mm("<red>Un chantier est déjà en cours."));
            return;
        }
        if (!hasZone()) {
            sender.sendMessage(mm("<red>Pas de zone. <gray>/halloween zone <rayon>"));
            return;
        }
        World w = Bukkit.getWorld(worldName);
        int scanPerTick = Math.max(2000, getConfig().getInt("scan-per-tick", 60000));
        int changesPerTick = Math.max(200, getConfig().getInt("changes-per-tick", 3000));
        boolean deco = getConfig().getBoolean("decorations.enabled", true);
        double cobwebs = getConfig().getDouble("decorations.cobwebs", 5) / 100.0;
        double moss = getConfig().getDouble("decorations.pale-hanging-moss", 12) / 100.0;
        double litter = getConfig().getDouble("decorations.leaf-litter", 35) / 100.0;
        Map<String, Integer> counts = new HashMap<>();
        List<int[]> pos = new ArrayList<>();
        List<String> old = new ArrayList<>();
        long[] scanned = {0};
        long total = volume();
        int[] cur = {minX, minZ, minY};
        sender.sendMessage(mm(preview ? "<gray>Aperçu en cours…" : "<gold>🎃 Transformation Halloween en cours…"));
        job = Bukkit.getScheduler().runTaskTimer(this, () -> {
            int scan = 0, changes = 0;
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            while (scan < scanPerTick && changes < changesPerTick) {
                int x = cur[0], z = cur[1], y = cur[2];
                if (x > maxX) {
                    finish(sender, preview, counts, pos, old, w);
                    return;
                }
                if (cylinder && (long) (x - cx) * (x - cx) + (long) (z - cz) * (z - cz) > (long) radius * radius) {
                    // colonne hors du cercle : on saute toute la colonne
                    cur[2] = minY;
                    nextColumn(cur);
                    continue;
                }
                // avance au bloc suivant (colonne par colonne, de bas en haut)
                if (++cur[2] > maxY) {
                    cur[2] = minY;
                    nextColumn(cur);
                }
                scan++;
                scanned[0]++;
                Block b = w.getBlockAt(x, y, z);
                Material m = b.getType();
                Rule r = rule(m);
                if (r != null) {
                    String id = m.getKey().getKey();
                    String to = choose(r, id, x, y, z);
                    if (!to.equals(id)) {
                        BlockData now = b.getBlockData();
                        BlockData nd = convert(now, to);
                        if (nd != null) {
                            counts.merge(id + " → " + to, 1, Integer::sum);
                            if (!preview) {
                                pos.add(new int[]{x, y, z});
                                old.add(now.getAsString());
                                b.setBlockData(nd, false);
                            }
                            changes++;
                            m = nd.getMaterial();
                        }
                    }
                }
                if (!deco) continue;
                // décorations
                Block above = b.getRelative(BlockFace.UP);
                if ((ground.contains(m)) && above.getType().isAir() && y < maxY) {
                    String put = null;
                    if (litter > 0 && rnd.nextDouble() < litter && underLeaves(above)) {
                        put = "leaf_litter[segment_amount=" + (1 + rnd.nextInt(4)) + ",facing=" + HORIZONTAL[rnd.nextInt(4)].name().toLowerCase(Locale.ROOT) + "]";
                    } else {
                        double roll = rnd.nextDouble() * 100;
                        for (Choice c : decorations) {
                            roll -= c.weight();
                            if (roll < 0) {
                                put = c.id();
                                break;
                            }
                        }
                    }
                    if (put != null && place(above, put, preview, counts, pos, old, rnd)) changes++;
                } else if (m == Material.PALE_OAK_LEAVES && moss > 0 && rnd.nextDouble() < moss) {
                    Block below = b.getRelative(BlockFace.DOWN);
                    if (below.getType().isAir() && y > minY && place(below, "pale_hanging_moss[tip=true]", preview, counts, pos, old, rnd)) changes++;
                } else if (m.isAir() && cobwebs > 0 && rnd.nextDouble() < cobwebs && corner(b)) {
                    if (place(b, "cobweb", preview, counts, pos, old, rnd)) changes++;
                }
            }
            if (scanned[0] % (scanPerTick * 10L) < scanPerTick && sender instanceof Player p && p.isOnline()) {
                p.sendActionBar(mm("<gold>🎃 " + Math.min(100, scanned[0] * 100 / Math.max(1, total)) + "% <gray>· " + pos.size() + " blocs changés"));
            }
        }, 1L, 1L);
    }

    private void nextColumn(int[] cur) {
        if (++cur[1] > maxZ) {
            cur[1] = minZ;
            cur[0]++;
        }
    }

    /** Des feuilles juste au-dessus (jusqu'à 7 blocs) : on est sous un arbre. */
    private static boolean underLeaves(Block air) {
        for (int i = 1; i <= 7; i++) {
            Material m = air.getRelative(0, i, 0).getType();
            if (leafy(m)) return true;
            if (!m.isAir()) return false;
        }
        return false;
    }

    /** Un coin sous un plafond, contre au moins deux murs. */
    private static boolean corner(Block b) {
        if (!b.getRelative(BlockFace.UP).getType().isOccluding()) return false;
        int walls = 0;
        for (BlockFace f : HORIZONTAL) if (b.getRelative(f).getType().isOccluding()) walls++;
        return walls >= 2;
    }

    private boolean place(Block b, String id, boolean preview, Map<String, Integer> counts, List<int[]> pos, List<String> old,
                          ThreadLocalRandom rnd) {
        BlockData d = parse(id.contains(":") ? id : "minecraft:" + id);
        if (d == null) return false;
        if (d instanceof Directional dir && dir.getFaces().contains(BlockFace.NORTH)) dir.setFacing(HORIZONTAL[rnd.nextInt(4)]);
        if (d instanceof Rotatable rot) rot.setRotation(ROTATIONS[rnd.nextInt(ROTATIONS.length)]);
        String name = id.contains("[") ? id.substring(0, id.indexOf('[')) : id;
        counts.merge("+ " + name, 1, Integer::sum);
        if (preview) return true;
        pos.add(new int[]{b.getX(), b.getY(), b.getZ()});
        old.add(b.getBlockData().getAsString());
        b.setBlockData(d, false);
        return true;
    }

    private void finish(CommandSender sender, boolean preview, Map<String, Integer> counts, List<int[]> pos, List<String> old, World w) {
        if (job != null) job.cancel();
        job = null;
        List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        int sum = 0;
        for (var e : list) sum += e.getValue();
        if (!preview) writeBackup(w, pos, old);
        sender.sendMessage(mm(preview ? "<gold><b>Aperçu :</b> <white>" + sum + "</white> <gray>blocs seraient changés"
                : "<gold><b>🎃 Le spawn est en mode Halloween !</b> <white>" + sum + "</white> <gray>blocs changés. <aqua>/halloween retirer</aqua> pour revenir."));
        for (int i = 0; i < Math.min(14, list.size()); i++) {
            sender.sendMessage(mm("<gray> · " + list.get(i).getKey() + " <dark_gray>×</dark_gray> <white>" + list.get(i).getValue()));
        }
    }

    // ================================================================= sauvegarde / retour en arrière

    private void writeBackup(World w, List<int[]> pos, List<String> old) {
        Map<String, Integer> palette = new HashMap<>();
        List<String> pal = new ArrayList<>();
        for (String s : old) {
            if (!palette.containsKey(s)) {
                palette.put(s, pal.size());
                pal.add(s);
            }
        }
        getDataFolder().mkdirs();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(backupFile)))) {
            out.writeInt(MAGIC);
            out.writeUTF(w.getName());
            out.writeInt(pal.size());
            for (String s : pal) out.writeUTF(s);
            out.writeInt(pos.size());
            for (int i = 0; i < pos.size(); i++) {
                int[] q = pos.get(i);
                out.writeInt(q[0]);
                out.writeInt(q[1]);
                out.writeInt(q[2]);
                out.writeInt(palette.get(old.get(i)));
            }
        } catch (IOException e) {
            getLogger().severe("Sauvegarde impossible : " + e.getMessage());
        }
    }

    private void undo(CommandSender sender) {
        if (job != null) {
            sender.sendMessage(mm("<red>Un chantier est déjà en cours."));
            return;
        }
        if (!backupFile.exists()) {
            sender.sendMessage(mm("<red>Rien à retirer : le spawn n'est pas en mode Halloween."));
            return;
        }
        World w;
        int[] xs, ys, zs, ps;
        BlockData[] datas;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(backupFile)))) {
            if (in.readInt() != MAGIC) throw new IOException("fichier invalide");
            w = Bukkit.getWorld(in.readUTF());
            if (w == null) throw new IOException("monde introuvable");
            datas = new BlockData[in.readInt()];
            for (int i = 0; i < datas.length; i++) {
                String s = in.readUTF();
                try {
                    datas[i] = Bukkit.createBlockData(s);
                } catch (IllegalArgumentException ex) {
                    datas[i] = Material.AIR.createBlockData();
                }
            }
            int n = in.readInt();
            xs = new int[n];
            ys = new int[n];
            zs = new int[n];
            ps = new int[n];
            for (int i = 0; i < n; i++) {
                xs[i] = in.readInt();
                ys[i] = in.readInt();
                zs[i] = in.readInt();
                ps[i] = in.readInt();
            }
        } catch (IOException e) {
            sender.sendMessage(mm("<red>Lecture de la sauvegarde impossible : " + e.getMessage()));
            return;
        }
        int perTick = Math.max(500, getConfig().getInt("changes-per-tick", 3000) * 2);
        int[] idx = {xs.length - 1};
        sender.sendMessage(mm("<gold>Retour du spawn normal… <gray>(" + xs.length + " blocs)"));
        job = Bukkit.getScheduler().runTaskTimer(this, () -> {
            int done = 0;
            while (idx[0] >= 0 && done++ < perTick) {
                int i = idx[0]--;
                w.getBlockAt(xs[i], ys[i], zs[i]).setBlockData(datas[ps[i]], false);
            }
            if (idx[0] < 0) {
                job.cancel();
                job = null;
                File old = new File(getDataFolder(), "sauvegarde-retiree.dat");
                if (old.exists()) old.delete();
                if (!backupFile.renameTo(old)) backupFile.delete();
                sender.sendMessage(mm("<green>Le spawn est revenu comme avant Halloween."));
            }
        }, 1L, 1L);
    }
}
