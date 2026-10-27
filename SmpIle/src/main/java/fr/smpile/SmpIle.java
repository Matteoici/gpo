package fr.smpile;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.PointedDripstone;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Monte le spawn dans le ciel : la zone choisie est COPIÉE plus haut (blocs, coffres, panneaux, cadres...),
 * puis un dessous en roche en forme de cône est généré sous l'île, avec des racines, des lianes lumineuses,
 * des chaînes et lanternes qui pendent, et quelques petits îlots qui flottent autour.
 * Le spawn d'origine n'est pas touché. Tout ce qui est posé est noté : /ile annuler remet comme avant.
 */
public final class SmpIle extends JavaPlugin {

    static final MiniMessage MM = MiniMessage.miniMessage();

    private final Map<UUID, Location[]> sel = new HashMap<>();
    private BukkitTask job;
    private File undoFile;
    /** blocs modifiés par la dernière île : "monde;x;y;z;ancien blockdata" */
    private final List<String> undo = new ArrayList<>();
    private Location lastCenter;
    private int[] lastShift = {0, 0, 0};

    @Override
    public void onEnable() {
        saveDefaultConfig();
        undoFile = new File(getDataFolder(), "annuler.txt.gz");
        loadUndo();
    }

    @Override
    public void onDisable() {
        if (job != null) job.cancel();
        saveUndo();
    }

    static Component parse(String s) {
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static void msg(CommandSender s, String m) {
        s.sendMessage(parse("<dark_aqua>[Île]</dark_aqua> " + m));
    }

    // ================================================================= commande

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        Player p = s instanceof Player pl ? pl : null;
        switch (sub) {
            case "pos1", "pos2" -> {
                if (p == null) return true;
                Location l = p.getLocation().getBlock().getLocation();
                Location[] c = sel.computeIfAbsent(p.getUniqueId(), k -> new Location[2]);
                c[sub.equals("pos1") ? 0 : 1] = l;
                msg(s, "<green>" + sub + "</green> <gray>= <white>" + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ());
                if (c[0] != null && c[1] != null) info(s, c, 0);
            }
            case "info" -> {
                Location[] c = p == null ? null : sel.get(p.getUniqueId());
                if (c == null || c[0] == null || c[1] == null) msg(s, "<red>Fais d'abord <white>/ile pos1</white> et <white>/ile pos2</white>.");
                else info(s, c, 0);
            }
            case "monter", "up" -> {
                if (p == null) return true;
                Location[] c = sel.get(p.getUniqueId());
                if (c == null || c[0] == null || c[1] == null || c[0].getWorld() != c[1].getWorld()) {
                    msg(s, "<red>Fais d'abord <white>/ile pos1</white> et <white>/ile pos2</white> (même monde).");
                    return true;
                }
                int dy;
                try {
                    dy = a.length > 1 ? Integer.parseInt(a[1]) : -1;
                } catch (NumberFormatException e) {
                    dy = -1;
                }
                if (dy < 20) {
                    msg(s, "<red>/ile monter <hauteur></red> <gray>(au moins 20, ex. <white>/ile monter 120</white>)");
                    return true;
                }
                int x0 = Math.min(c[0].getBlockX(), c[1].getBlockX()), x1 = Math.max(c[0].getBlockX(), c[1].getBlockX());
                int y0 = Math.min(c[0].getBlockY(), c[1].getBlockY()) - 1, y1 = Math.max(c[0].getBlockY(), c[1].getBlockY());
                int z0 = Math.min(c[0].getBlockZ(), c[1].getBlockZ()), z1 = Math.max(c[0].getBlockZ(), c[1].getBlockZ());
                if (!check(s, c[0].getWorld(), x0, x1, y0, y1, z0, z1, 0, dy, 0)) return true;
                if (a.length < 3 || !a[2].equalsIgnoreCase("confirmer")) {
                    info(s, c, dy);
                    msg(s, "<yellow>Pour lancer : <white>/ile monter " + dy + " confirmer");
                    return true;
                }
                start(s, c, 0, dy, 0);
            }
            case "placer", "ici", "place" -> {
                if (p == null) return true;
                Location[] c = sel.get(p.getUniqueId());
                if (c == null || c[0] == null || c[1] == null || c[0].getWorld() != c[1].getWorld()) {
                    msg(s, "<red>Fais d'abord <white>/ile pos1</white> et <white>/ile pos2</white> (même monde).");
                    return true;
                }
                if (p.getWorld() != c[0].getWorld()) {
                    msg(s, "<red>Place-toi dans le même monde que le spawn.");
                    return true;
                }
                int x0 = Math.min(c[0].getBlockX(), c[1].getBlockX()), x1 = Math.max(c[0].getBlockX(), c[1].getBlockX());
                int y0 = Math.min(c[0].getBlockY(), c[1].getBlockY()) - 1, y1 = Math.max(c[0].getBlockY(), c[1].getBlockY());
                int z0 = Math.min(c[0].getBlockZ(), c[1].getBlockZ()), z1 = Math.max(c[0].getBlockZ(), c[1].getBlockZ());
                Location l = p.getLocation();
                // là où tu es = le centre de l'île, ses pieds posés sur le sol de l'île
                int dx = l.getBlockX() - (x0 + x1) / 2, dz = l.getBlockZ() - (z0 + z1) / 2, dy = (l.getBlockY() - 1) - y0;
                if (!check(s, c[0].getWorld(), x0, x1, y0, y1, z0, z1, dx, dy, dz)) return true;
                if (a.length < 2 || !a[1].equalsIgnoreCase("confirmer")) {
                    info(s, c, dy);
                    msg(s, "<gray>Le centre de l'île sera ici : <white>" + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()
                            + "</white> <dark_gray>(décalage " + dx + " " + dy + " " + dz + ")");
                    msg(s, "<yellow>Reste à cette place et tape : <white>/ile placer confirmer");
                    return true;
                }
                start(s, c, dx, dy, dz);
            }
            case "annuler", "undo" -> undoAll(s);
            case "stop" -> {
                if (job != null) {
                    job.cancel();
                    job = null;
                    msg(s, "<yellow>Construction arrêtée. <gray>/ile annuler pour enlever ce qui a été posé.");
                }
            }
            case "tp" -> {
                if (p == null) return true;
                if (lastCenter == null) msg(s, "<red>Pas encore d'île.");
                else p.teleport(safeTop(lastCenter));
            }
            default -> {
                msg(s, "<white>/ile pos1</white> <gray>et</gray> <white>/ile pos2</white> <gray>: deux coins du spawn (pos1 au sol, pos2 en haut)");
                msg(s, "<white>/ile monter <hauteur></white> <gray>: copie le spawn X blocs plus haut, avec le dessous et le décor");
                msg(s, "<white>/ile placer</white> <gray>: copie le spawn là où tu es (vole à l'endroit voulu : tes pieds = le sol de l'île)");
                msg(s, "<white>/ile tp</white> <gray>·</gray> <white>/ile annuler</white> <gray>·</gray> <white>/ile stop");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        if (a.length == 1) return List.of("pos1", "pos2", "info", "monter", "placer", "tp", "annuler", "stop");
        if (a.length == 2 && a[0].equalsIgnoreCase("placer")) return List.of("confirmer");
        if (a.length == 2 && a[0].equalsIgnoreCase("monter")) return List.of("80", "100", "120", "150");
        if (a.length == 3 && a[0].equalsIgnoreCase("monter")) return List.of("confirmer");
        return List.of();
    }

    /** Vérifications avant de lancer : pas déjà en cours, pas d'île existante, pas trop haut, pas par-dessus le spawn. */
    private boolean check(CommandSender s, World w, int x0, int x1, int y0, int y1, int z0, int z1, int dx, int dy, int dz) {
        if (job != null) {
            msg(s, "<red>Une île est déjà en cours de construction. <gray>/ile stop");
            return false;
        }
        if (!undo.isEmpty()) {
            msg(s, "<red>Il y a déjà une île. <gray>Fais <white>/ile annuler</white> d'abord si tu veux la refaire.");
            return false;
        }
        if (y1 + dy >= w.getMaxHeight() - 2) {
            msg(s, "<red>Trop haut : le haut de l'île serait en Y " + (y1 + dy) + " (limite " + (w.getMaxHeight() - 1) + ").");
            return false;
        }
        int under = getConfig().getInt("underside.max-depth", 42);
        if (y0 + dy - under < w.getMinHeight()) {
            msg(s, "<red>Trop bas : le dessous de l'île passerait sous le monde.");
            return false;
        }
        // la copie ne doit pas recouvrir le spawn d'origine (ni son futur dessous)
        boolean overlap = x0 + dx <= x1 && x1 + dx >= x0 && z0 + dz <= z1 && z1 + dz >= z0 && y0 + dy - under <= y1 && y1 + dy >= y0;
        if (overlap) {
            msg(s, "<red>L'île toucherait le spawn d'origine. <gray>Monte plus haut ou éloigne-toi.");
            return false;
        }
        return true;
    }

    private void info(CommandSender s, Location[] c, int dy) {
        int sx = Math.abs(c[0].getBlockX() - c[1].getBlockX()) + 1, sy = Math.abs(c[0].getBlockY() - c[1].getBlockY()) + 1,
                sz = Math.abs(c[0].getBlockZ() - c[1].getBlockZ()) + 1;
        msg(s, "<gray>Zone : <white>" + sx + "×" + sz + "</white>, <white>" + sy + "</white> de haut <dark_gray>(" + ((long) sx * sy * sz) + " blocs)");
        if (dy > 0) {
            int minY = Math.min(c[0].getBlockY(), c[1].getBlockY()) - 1;
            msg(s, "<gray>Le sol de l'île sera en <white>Y " + (minY + dy) + "</white>, le dessous descendra jusqu'à ~<white>Y "
                    + (minY + dy - getConfig().getInt("underside.max-depth", 42)) + "</white>.");
        }
    }

    // ================================================================= construction

    private void start(CommandSender s, Location[] c, int dx, int dy, int dz) {
        World w = c[0].getWorld();
        int x0 = Math.min(c[0].getBlockX(), c[1].getBlockX()), x1 = Math.max(c[0].getBlockX(), c[1].getBlockX());
        // une couche sous le point le plus bas : le sol sous les pieds est toujours copié
        int y0 = Math.min(c[0].getBlockY(), c[1].getBlockY()) - 1, y1 = Math.max(c[0].getBlockY(), c[1].getBlockY());
        int z0 = Math.min(c[0].getBlockZ(), c[1].getBlockZ()), z1 = Math.max(c[0].getBlockZ(), c[1].getBlockZ());
        int per = Math.max(200, getConfig().getInt("blocks-per-tick", 5000));
        long total = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        lastCenter = new Location(w, (x0 + x1) / 2.0 + 0.5 + dx, y0 + dy + 1, (z0 + z1) / 2.0 + 0.5 + dz);
        lastShift = new int[]{dx, dy, dz};
        msg(s, "<green>Copie du spawn lancée</green> <gray>(" + total + " blocs à lire, ~" + Math.max(1, total / per / 20) + " s)...");
        // la copie se fait de bas en haut (les torches, portes... trouvent leur support)
        int[] cur = {y0, x0, z0};
        long[] done = {0, 0};
        job = Bukkit.getScheduler().runTaskTimer(this, () -> {
            int n = 0;
            while (n < per) {
                if (cur[0] > y1) {
                    job.cancel();
                    job = null;
                    afterCopy(s, w, x0, x1, y0, y1, z0, z1, dx, dy, dz);
                    return;
                }
                Block from = w.getBlockAt(cur[1], cur[0], cur[2]);
                if (!from.getType().isAir()) {
                    Block to = w.getBlockAt(cur[1] + dx, cur[0] + dy, cur[2] + dz);
                    remember(to);
                    try {
                        BlockState st = from.getState();
                        st.copy(to.getLocation()).update(true, false);
                    } catch (RuntimeException ex) {
                        to.setBlockData(from.getBlockData(), false);
                    }
                    done[1]++;
                }
                n++;
                done[0]++;
                if (++cur[2] > z1) {
                    cur[2] = z0;
                    if (++cur[1] > x1) {
                        cur[1] = x0;
                        cur[0]++;
                    }
                }
            }
            if (done[0] % (per * 40L) < per && s instanceof Player pl) {
                pl.sendActionBar(parse("<aqua>Copie de l'île : <white>" + (done[0] * 100 / Math.max(1, total)) + "%"));
            }
        }, 1L, 1L);
    }

    private void afterCopy(CommandSender s, World w, int x0, int x1, int y0, int y1, int z0, int z1, int dx, int dy, int dz) {
        if (getConfig().getBoolean("copy-entities", true)) {
            int ents = 0;
            BoundingBox box = new BoundingBox(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1);
            for (Entity e : w.getNearbyEntities(box)) {
                boolean ok = e instanceof Hanging || (e instanceof ArmorStand as && as.getPersistentDataContainer().isEmpty());
                if (!ok || !e.getPersistentDataContainer().getKeys().isEmpty()) continue;
                try {
                    e.copy(e.getLocation().add(dx, dy, dz));
                    ents++;
                } catch (RuntimeException ignored) {
                }
            }
            if (ents > 0) msg(s, "<gray>" + ents + " cadre(s) / tableau(x) / porte-armure(s) copiés.");
        }
        msg(s, "<green>Spawn copié.</green> <gray>Création du dessous de l'île...");
        // ---- empreinte de l'île : les colonnes qui ont un sol (3 premières couches de la zone)
        int sx = x1 - x0 + 1, sz = z1 - z0 + 1;
        boolean[][] foot = new boolean[sx][sz];
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                for (int y = y0; y <= Math.min(y1, y0 + 2); y++) {
                    Material m = w.getBlockAt(x0 + x, y, z0 + z).getType();
                    if (!m.isAir() && m.isSolid()) {
                        foot[x][z] = true;
                        break;
                    }
                }
            }
        }
        int[][] edge = distanceToEdge(foot, sx, sz);
        List<int[]> columns = new ArrayList<>();
        for (int x = 0; x < sx; x++) for (int z = 0; z < sz; z++) if (foot[x][z]) columns.add(new int[]{x, z});
        int baseY = y0 + dy - 1;
        boolean under = getConfig().getBoolean("underside.enabled", true);
        double minD = getConfig().getDouble("underside.min-depth", 3), per = getConfig().getDouble("underside.depth-per-block", 0.85);
        int maxD = getConfig().getInt("underside.max-depth", 42);
        int perTick = Math.max(20, getConfig().getInt("blocks-per-tick", 5000) / 30);
        int[] i = {0};
        Map<Long, Integer> bottom = new HashMap<>();
        job = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (int k = 0; k < perTick && i[0] < columns.size(); k++, i[0]++) {
                int[] col = columns.get(i[0]);
                int x = x0 + dx + col[0], z = z0 + dz + col[1];
                if (!under) continue;
                double e = edge[col[0]][col[1]];
                double noise = hash(x, 0, z, 1) * 3 + Math.sin(x * 0.21) * Math.cos(z * 0.17) * 2.2;
                int depth = (int) Math.min(maxD, Math.max(1, minD + e * per + (e > 10 ? (e - 10) * 0.45 : 0) + noise));
                int lowest = baseY;
                for (int d = 0; d < depth; d++) {
                    Block b = w.getBlockAt(x, baseY - d, z);
                    if (!b.getType().isAir()) continue;
                    double t = d / (double) Math.max(1, depth);
                    double h = hash(x, baseY - d, z);
                    Material m;
                    if (d < 2) m = h < 0.75 ? Material.DIRT : Material.COARSE_DIRT;
                    else if (t < 0.45) m = pick(h, Material.STONE, Material.ANDESITE, Material.STONE, Material.TUFF, Material.COBBLESTONE);
                    else if (t < 0.8) m = pick(h, Material.TUFF, Material.DEEPSLATE, Material.STONE, Material.ANDESITE, Material.COBBLED_DEEPSLATE);
                    else m = pick(h, Material.DEEPSLATE, Material.COBBLED_DEEPSLATE, Material.TUFF, Material.MOSS_BLOCK, Material.DEEPSLATE);
                    set(b, m.createBlockData());
                    lowest = baseY - d;
                }
                bottom.put(key(x, z), lowest);
            }
            if (i[0] >= columns.size()) {
                job.cancel();
                job = null;
                decorate(s, w, x0 + dx, z0 + dz, sx, sz, foot, edge, bottom, baseY);
            }
        }, 1L, 1L);
    }

    /** Distance (en blocs) de chaque case de l'empreinte jusqu'au bord. */
    private static int[][] distanceToEdge(boolean[][] foot, int sx, int sz) {
        int[][] d = new int[sx][sz];
        ArrayDeque<int[]> q = new ArrayDeque<>();
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                boolean border = x == 0 || z == 0 || x == sx - 1 || z == sz - 1;
                if (!foot[x][z]) d[x][z] = 0;
                else if (border) {
                    d[x][z] = 1;
                    q.add(new int[]{x, z});
                } else d[x][z] = Integer.MAX_VALUE;
            }
        }
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                if (!foot[x][z] || d[x][z] != Integer.MAX_VALUE) continue;
                for (int[] n : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    if (!foot[x + n[0]][z + n[1]]) {
                        d[x][z] = 1;
                        q.add(new int[]{x, z});
                        break;
                    }
                }
            }
        }
        while (!q.isEmpty()) {
            int[] c = q.poll();
            for (int[] n : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = c[0] + n[0], nz = c[1] + n[1];
                if (nx < 0 || nz < 0 || nx >= sx || nz >= sz || !foot[nx][nz]) continue;
                if (d[nx][nz] > d[c[0]][c[1]] + 1) {
                    d[nx][nz] = d[c[0]][c[1]] + 1;
                    q.add(new int[]{nx, nz});
                }
            }
        }
        for (int x = 0; x < sx; x++) for (int z = 0; z < sz; z++) if (d[x][z] == Integer.MAX_VALUE) d[x][z] = 1;
        return d;
    }

    // ================================================================= décor

    private void decorate(CommandSender s, World w, int x0, int z0, int sx, int sz, boolean[][] foot, int[][] edge,
                          Map<Long, Integer> bottom, int baseY) {
        if (getConfig().getBoolean("decor.enabled", true)) {
            double hang = getConfig().getDouble("decor.hanging-chance", 0.10), lant = getConfig().getDouble("decor.lanterns-chance", 0.025);
            Material chain = Material.matchMaterial("IRON_CHAIN");
            if (chain == null) chain = Material.matchMaterial("CHAIN");
            for (Map.Entry<Long, Integer> e : bottom.entrySet()) {
                int x = (int) (e.getKey() >> 32), z = (int) (long) e.getKey().intValue();
                int y = e.getValue() - 1;
                double h = hash(x, y, z, 7);
                Block b = w.getBlockAt(x, y, z);
                if (!b.getType().isAir()) continue;
                if (h < lant && chain != null) {
                    // chaîne + lanterne qui pend
                    int len = 2 + (int) (hash(x, y, z, 9) * 6);
                    for (int k = 0; k < len; k++) set(w.getBlockAt(x, y - k, z), chain.createBlockData());
                    BlockData l = (hash(x, y, z, 11) < 0.5 ? Material.SOUL_LANTERN : Material.LANTERN).createBlockData();
                    if (l instanceof Lantern ln) ln.setHanging(true);
                    set(w.getBlockAt(x, y - len, z), l);
                } else if (h < lant + hang * 0.35) {
                    // lianes lumineuses (baies)
                    int len = 2 + (int) (hash(x, y, z, 13) * 7);
                    for (int k = 0; k < len; k++) {
                        Material m = k == len - 1 ? Material.CAVE_VINES : Material.CAVE_VINES_PLANT;
                        BlockData d = m.createBlockData();
                        try {
                            if (hash(x, y - k, z, 15) < 0.45) d = Bukkit.createBlockData(m.getKey() + "[berries=true]");
                        } catch (IllegalArgumentException ignored) {
                        }
                        set(w.getBlockAt(x, y - k, z), d);
                    }
                } else if (h < lant + hang * 0.7) {
                    set(b, Material.HANGING_ROOTS.createBlockData());
                } else if (h < lant + hang) {
                    // pointe de roche (stalactite)
                    int len = 1 + (int) (hash(x, y, z, 17) * 3);
                    for (int k = 0; k < len; k++) {
                        BlockData d = Material.POINTED_DRIPSTONE.createBlockData();
                        if (d instanceof PointedDripstone pd) {
                            pd.setVerticalDirection(BlockFace.DOWN);
                            pd.setThickness(k == len - 1 ? PointedDripstone.Thickness.TIP
                                    : k == 0 && len > 2 ? PointedDripstone.Thickness.BASE : PointedDripstone.Thickness.MIDDLE);
                        }
                        set(w.getBlockAt(x, y - k, z), d);
                    }
                } else if (h > 0.97) {
                    BlockData d = Material.GLOW_LICHEN.createBlockData();
                    if (d instanceof MultipleFacing mf) mf.setFace(BlockFace.UP, true);
                    set(b, d);
                }
            }
            // lianes le long des bords de l'île
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    if (!foot[x][z] || edge[x][z] != 1 || hash(x0 + x, baseY, z0 + z, 21) > 0.35) continue;
                    for (int[] n : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int ox = x + n[0], oz = z + n[1];
                        if (ox >= 0 && oz >= 0 && ox < sx && oz < sz && foot[ox][oz]) continue;
                        int len = 2 + (int) (hash(x0 + x, baseY, z0 + z, 23) * 8);
                        BlockFace towards = n[0] == 1 ? BlockFace.WEST : n[0] == -1 ? BlockFace.EAST : n[1] == 1 ? BlockFace.NORTH : BlockFace.SOUTH;
                        for (int k = 0; k < len; k++) {
                            Block v = w.getBlockAt(x0 + ox, baseY - k, z0 + oz);
                            if (!v.getType().isAir()) break;
                            BlockData d = Material.VINE.createBlockData();
                            if (d instanceof MultipleFacing mf) mf.setFace(towards, true);
                            set(v, d);
                        }
                        break;
                    }
                }
            }
            islets(w, x0, z0, sx, sz, baseY);
        }
        saveUndo();
        msg(s, "<green><b>Île terminée !</b></green> <gray>" + undo.size() + " blocs posés. <white>/ile tp</white> <gray>pour aller la voir.");
        msg(s, "<yellow>N'oublie pas :</yellow> <gray>refais <white>/setworldspawn</white> (et <white>/setspawn</white> d'Essentials) sur l'île, "
                + "et déplace les zones des plugins (marché : <white>/marche admin decaler " + lastShift[0] + " " + lastShift[1] + " " + lastShift[2]
                + "</white>, portail de l'arène, PNJ...).");
    }

    /** Petits îlots qui flottent autour de l'île. */
    private void islets(World w, int x0, int z0, int sx, int sz, int baseY) {
        int n = getConfig().getInt("decor.islets", 7);
        double cx = x0 + sx / 2.0, cz = z0 + sz / 2.0, r = Math.max(sx, sz) / 2.0;
        java.util.Random rnd = new java.util.Random(x0 * 31L + z0);
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n + rnd.nextDouble() * 0.6;
            double d = r + 8 + rnd.nextDouble() * 16;
            int ix = (int) Math.round(cx + Math.cos(a) * d), iz = (int) Math.round(cz + Math.sin(a) * d);
            int iy = baseY - 8 + rnd.nextInt(18);
            int rad = 2 + rnd.nextInt(3);
            for (int x = -rad; x <= rad; x++) {
                for (int z = -rad; z <= rad; z++) {
                    double dist = Math.sqrt(x * x + z * z);
                    if (dist > rad + 0.3) continue;
                    int depth = (int) Math.max(1, (rad - dist) * 1.6 + rnd.nextInt(2) + 1);
                    set(w.getBlockAt(ix + x, iy, iz + z), Material.GRASS_BLOCK.createBlockData());
                    for (int k = 1; k <= depth; k++) {
                        Material m = k <= 1 ? Material.DIRT : rnd.nextDouble() < 0.7 ? Material.STONE : Material.ANDESITE;
                        set(w.getBlockAt(ix + x, iy - k, iz + z), m.createBlockData());
                    }
                    double f = rnd.nextDouble();
                    Block top = w.getBlockAt(ix + x, iy + 1, iz + z);
                    if (f < 0.25) set(top, Material.SHORT_GRASS.createBlockData());
                    else if (f < 0.33) set(top, pick(rnd.nextDouble(), Material.POPPY, Material.DANDELION, Material.CORNFLOWER, Material.AZURE_BLUET, Material.OXEYE_DAISY).createBlockData());
                }
            }
            if (rnd.nextBoolean()) {
                BlockData l = Material.LANTERN.createBlockData();
                set(w.getBlockAt(ix, iy + 1, iz), l);
            }
        }
    }

    // ================================================================= outils

    private void set(Block b, BlockData d) {
        remember(b);
        b.setBlockData(d, false);
    }

    private void remember(Block b) {
        undo.add(b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ() + ";" + b.getBlockData().getAsString());
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private static double hash(int x, int y, int z) {
        return hash(x, y, z, 0);
    }

    private static double hash(int x, int y, int z, int seed) {
        long h = x * 73856093L ^ y * 19349663L ^ z * 83492791L ^ seed * 2654435761L;
        h ^= (h >>> 13);
        h *= 0x5bd1e995L;
        h ^= (h >>> 15);
        return (h & 0xffffff) / (double) 0x1000000;
    }

    private static Material pick(double h, Material... m) {
        return m[Math.min(m.length - 1, (int) (h * m.length))];
    }

    private static Location safeTop(Location c) {
        World w = c.getWorld();
        Location l = c.clone();
        int top = w.getHighestBlockYAt(l.getBlockX(), l.getBlockZ());
        if (top > l.getBlockY() - 5 && top < l.getBlockY() + 80) l.setY(top + 1);
        return l;
    }

    private void undoAll(CommandSender s) {
        if (job != null) {
            job.cancel();
            job = null;
        }
        if (undo.isEmpty()) {
            msg(s, "<red>Rien à annuler.");
            return;
        }
        // on retire les entités copiées (cadres, tableaux, porte-armures) au-dessus du spawn d'origine
        List<String> list = new ArrayList<>(undo);
        undo.clear();
        int per = Math.max(500, getConfig().getInt("blocks-per-tick", 5000));
        int[] i = {list.size() - 1};
        msg(s, "<yellow>Retrait de l'île (" + list.size() + " blocs)...");
        job = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (int k = 0; k < per && i[0] >= 0; k++, i[0]--) {
                String[] p = list.get(i[0]).split(";", 5);
                World w = Bukkit.getWorld(p[0]);
                if (w == null) continue;
                Block b = w.getBlockAt(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                for (Entity e : w.getNearbyEntities(new BoundingBox(b.getX(), b.getY(), b.getZ(), b.getX() + 1, b.getY() + 1, b.getZ() + 1))) {
                    if (e instanceof Hanging || e instanceof ArmorStand) e.remove();
                }
                try {
                    b.setBlockData(Bukkit.createBlockData(p[4]), false);
                } catch (IllegalArgumentException ex) {
                    b.setType(Material.AIR, false);
                }
            }
            if (i[0] < 0) {
                job.cancel();
                job = null;
                lastCenter = null;
                saveUndo();
                msg(s, "<green>Île retirée, tout est comme avant.");
            }
        }, 1L, 1L);
    }

    private void saveUndo() {
        try {
            getDataFolder().mkdirs();
            if (undo.isEmpty()) {
                undoFile.delete();
                return;
            }
            try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(undoFile)), StandardCharsets.UTF_8))) {
                if (lastCenter != null) {
                    out.write("#center;" + lastCenter.getWorld().getName() + ";" + lastCenter.getX() + ";" + lastCenter.getY() + ";" + lastCenter.getZ());
                    out.newLine();
                }
                for (String l : undo) {
                    out.write(l);
                    out.newLine();
                }
            }
        } catch (Exception e) {
            getLogger().warning("Impossible d'enregistrer annuler.txt.gz : " + e.getMessage());
        }
    }

    private void loadUndo() {
        undo.clear();
        if (!undoFile.exists()) return;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(undoFile)), StandardCharsets.UTF_8))) {
            String l;
            while ((l = in.readLine()) != null) {
                if (l.startsWith("#center;")) {
                    String[] p = l.split(";");
                    World w = Bukkit.getWorld(p[1]);
                    if (w != null) lastCenter = new Location(w, Double.parseDouble(p[2]), Double.parseDouble(p[3]), Double.parseDouble(p[4]));
                } else if (!l.isBlank()) undo.add(l);
            }
        } catch (Exception e) {
            getLogger().warning("Impossible de lire annuler.txt.gz : " + e.getMessage());
        }
    }
}
