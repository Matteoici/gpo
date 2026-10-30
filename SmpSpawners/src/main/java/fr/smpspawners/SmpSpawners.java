package fr.smpspawners;

import fr.smpspawners.Defs.Drop;
import fr.smpspawners.Defs.Mob;
import fr.smpspawners.Defs.Rarity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class SmpSpawners extends JavaPlugin implements TabCompleter {

    static final MiniMessage MM = MiniMessage.miniMessage();
    static final String RANDOM = "RANDOM";

    final Map<String, Rarity> rarities = new LinkedHashMap<>();
    final Map<EntityType, Mob> mobs = new EnumMap<>(EntityType.class);
    private final Map<String, VSpawner> spawners = new HashMap<>();
    NamespacedKey mobKey, holoKey;
    private File dataFile;
    private boolean dirty;
    private int ticks;

    // réglages
    boolean requirePlayer, ownerOnly, convertVanilla, naturalSilk, hologram;
    int playerRange, maxStack, maxPerChunk;
    long itemsPerStack, xpPerStack;
    String prefix;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        mobKey = new NamespacedKey(this, "mob");
        holoKey = new NamespacedKey(this, "holo");
        dataFile = new File(getDataFolder(), "spawners.yml");
        loadSettings();
        loadData();
        // textes oubliés après un arrêt brutal
        for (World w : Bukkit.getWorlds())
            for (TextDisplay d : w.getEntitiesByClass(TextDisplay.class))
                if (d.getPersistentDataContainer().has(holoKey, PersistentDataType.BYTE)) d.remove();
        getServer().getPluginManager().registerEvents(new SpawnerListener(this), this);
        var cmd = getCommand("spawner");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        getLogger().info(spawners.size() + " spawner(s) chargé(s), " + mobs.size() + " mobs configurés.");
    }

    @Override
    public void onDisable() {
        for (VSpawner s : spawners.values()) killHolo(s);
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof SpawnerMenu) p.closeInventory();
        saveData();
    }

    // ================================================================= config

    void loadSettings() {
        reloadConfig();
        var c = getConfig();
        requirePlayer = c.getBoolean("production.require-player-nearby", true);
        playerRange = Math.max(4, c.getInt("production.player-range", 32));
        itemsPerStack = Math.max(64, c.getLong("storage.items-per-stack", 2304));
        xpPerStack = Math.max(10, c.getLong("storage.xp-per-stack", 5000));
        maxStack = Math.max(1, c.getInt("limits.max-stack", 64));
        maxPerChunk = Math.max(0, c.getInt("limits.max-per-chunk", 16));
        ownerOnly = c.getBoolean("limits.owner-only", true);
        convertVanilla = c.getBoolean("convert-vanilla-spawners", true);
        naturalSilk = c.getBoolean("natural-silk-touch", true);
        hologram = c.getBoolean("hologram", true);
        prefix = c.getString("messages.prefix", "<dark_gray>[<gold>Spawners</gold>]</dark_gray> ");
        rarities.clear();
        ConfigurationSection rs = c.getConfigurationSection("rarities");
        if (rs != null) {
            for (String id : rs.getKeys(false)) {
                ConfigurationSection r = rs.getConfigurationSection(id);
                if (r == null) continue;
                rarities.put(id, new Rarity(id, r.getString("name", id), r.getString("color", "<gray>"), Math.max(0, r.getDouble("weight", 1)),
                        Math.max(5, r.getInt("interval", 30))));
            }
        }
        if (rarities.isEmpty()) rarities.put("commun", new Rarity("commun", "Commun", "<gray>", 1, 30));
        mobs.clear();
        ConfigurationSection ms = c.getConfigurationSection("mobs");
        if (ms != null) {
            for (String id : ms.getKeys(false)) {
                EntityType t;
                try {
                    t = EntityType.valueOf(id.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    getLogger().warning("Mob inconnu dans la config : " + id);
                    continue;
                }
                ConfigurationSection m = ms.getConfigurationSection(id);
                if (m == null) continue;
                Rarity r = rarities.get(m.getString("rarity", "commun"));
                if (r == null) r = rarities.values().iterator().next();
                List<Drop> drops = new ArrayList<>();
                for (String d : m.getStringList("drops")) {
                    Drop dr = Drop.parse(d);
                    if (dr == null) getLogger().warning("Butin invalide pour " + id + " : " + d);
                    else drops.add(dr);
                }
                mobs.put(t, new Mob(t, r, Math.max(0, m.getInt("xp", 5)), Math.max(5, m.getInt("interval", r.interval())), drops));
            }
        }
    }

    Mob mob(EntityType t) {
        return t == null ? null : mobs.get(t);
    }

    /** Tirage au hasard : d'abord la rareté (poids), puis un mob de cette rareté. */
    Mob roll() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Map<Rarity, List<Mob>> by = new LinkedHashMap<>();
        for (Mob m : mobs.values()) by.computeIfAbsent(m.rarity(), k -> new ArrayList<>()).add(m);
        double total = 0;
        for (Rarity ra : by.keySet()) total += ra.weight();
        double t = r.nextDouble() * total;
        for (var en : by.entrySet()) {
            t -= en.getKey().weight();
            if (t < 0) return en.getValue().get(r.nextInt(en.getValue().size()));
        }
        List<Mob> all = new ArrayList<>(mobs.values());
        return all.isEmpty() ? null : all.get(r.nextInt(all.size()));
    }

    /** Chance d'obtenir ce mob avec un spawner mystère. */
    double chance(Mob m) {
        double total = 0;
        Map<Rarity, Integer> count = new HashMap<>();
        for (Mob x : mobs.values()) count.merge(x.rarity(), 1, Integer::sum);
        for (Rarity ra : count.keySet()) total += ra.weight();
        if (total <= 0) return 0;
        return m.rarity().weight() / total / count.get(m.rarity());
    }

    // ================================================================= données

    private void loadData() {
        spawners.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection root = y.getConfigurationSection("spawners");
        if (root == null) return;
        for (String k : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(k);
            if (s == null) continue;
            EntityType t;
            try {
                t = EntityType.valueOf(s.getString("mob", "PIG"));
            } catch (IllegalArgumentException e) {
                continue;
            }
            UUID owner = null;
            try {
                owner = UUID.fromString(s.getString("owner", ""));
            } catch (IllegalArgumentException ignored) {
                // pas de propriétaire
            }
            VSpawner v = new VSpawner(s.getString("world", "world"), s.getInt("x"), s.getInt("y"), s.getInt("z"), t,
                    Math.max(1, s.getInt("stack", 1)), owner, s.getString("owner-name", "?"));
            v.xp = s.getLong("xp");
            v.progress = s.getDouble("progress");
            ConfigurationSection it = s.getConfigurationSection("items");
            if (it != null) {
                for (String m : it.getKeys(false)) {
                    Material mat = Material.matchMaterial(m);
                    long n = it.getLong(m);
                    if (mat != null && n > 0) v.items.put(mat, n);
                }
            }
            spawners.put(v.key(), v);
        }
    }

    void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        int i = 0;
        for (VSpawner v : spawners.values()) {
            String p = "spawners.s" + (i++) + ".";
            y.set(p + "world", v.world);
            y.set(p + "x", v.x);
            y.set(p + "y", v.y);
            y.set(p + "z", v.z);
            y.set(p + "mob", v.type.name());
            y.set(p + "stack", v.stack);
            y.set(p + "owner", v.owner == null ? "" : v.owner.toString());
            y.set(p + "owner-name", v.ownerName);
            y.set(p + "xp", v.xp);
            y.set(p + "progress", Math.round(v.progress * 10) / 10.0);
            for (var en : v.items.entrySet()) y.set(p + "items." + en.getKey().name(), en.getValue());
        }
        try {
            getDataFolder().mkdirs();
            File tmp = new File(dataFile.getPath() + ".tmp");
            Files.writeString(tmp.toPath(), y.saveToString(), StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            getLogger().warning("Sauvegarde impossible : " + e.getMessage());
        }
        dirty = false;
    }

    void markDirty() {
        dirty = true;
    }

    VSpawner at(Block b) {
        return b == null ? null : spawners.get(VSpawner.key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
    }

    Collection<VSpawner> all() {
        return spawners.values();
    }

    void add(VSpawner s) {
        spawners.put(s.key(), s);
        markDirty();
    }

    void remove(VSpawner s) {
        spawners.remove(s.key());
        killHolo(s);
        markDirty();
    }

    int countInChunk(String world, int cx, int cz) {
        int n = 0;
        for (VSpawner s : spawners.values()) if (s.world.equals(world) && s.x >> 4 == cx && s.z >> 4 == cz) n++;
        return n;
    }

    // ================================================================= objets

    ItemStack item(String id, int amount) {
        ItemStack it = new ItemStack(Material.SPAWNER, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = it.getItemMeta();
        List<Component> lore = new ArrayList<>();
        if (RANDOM.equals(id)) {
            meta.displayName(text("<gradient:#ffd36e:#ff7ae0><b>Spawner mystère</b></gradient>"));
            lore.add(text("<gray>Pose-le : un mob est tiré <white>au hasard</white>."));
            lore.add(text("<gray>Plus il est rare, meilleur est le butin !"));
            lore.add(Component.empty());
            for (Rarity r : rarities.values()) lore.add(text("<dark_gray>• " + r.label() + " <dark_gray>(" + pct(rarityChance(r)) + ")"));
        } else {
            Mob m = mob(EntityType.valueOf(id));
            String color = m == null ? "<white>" : m.rarity().color();
            meta.displayName(text(color + "<b>Spawner à " + (m == null ? id : m.name())));
            if (m != null) {
                lore.add(text("<gray>Rareté : " + m.rarity().label()));
                lore.add(text("<gray>1 kill toutes les <white>" + m.interval() + "s</white>, <yellow>" + m.xp() + " XP</yellow>"));
                for (Drop d : m.drops()) lore.add(text("<dark_gray>• <white>" + Names.item(d.mat()) + " <gray>" + dropText(d)));
            }
        }
        lore.add(Component.empty());
        lore.add(text("<gray>Aucun mob n'apparaît : l'XP et le butin"));
        lore.add(text("<gray>s'accumulent dedans. <yellow>Clic droit</yellow> dessus pour récupérer."));
        lore.add(text("<gray>Clic droit avec le même spawner : <white>empiler</white>."));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(mobKey, PersistentDataType.STRING, id);
        it.setItemMeta(meta);
        return it;
    }

    /** "RANDOM", le nom du mob, ou null si ce n'est pas un spawner du plugin. */
    String mobOf(ItemStack it) {
        if (it == null || it.getType() != Material.SPAWNER || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(mobKey, PersistentDataType.STRING);
    }

    static String dropText(Drop d) {
        String n = d.min() == d.max() ? String.valueOf(d.min()) : d.min() + "-" + d.max();
        return "×" + n + (d.chance() < 1 ? " <dark_gray>(" + pct(d.chance()) + ")" : "");
    }

    double rarityChance(Rarity r) {
        double total = 0;
        java.util.Set<Rarity> used = new java.util.HashSet<>();
        for (Mob m : mobs.values()) used.add(m.rarity());
        for (Rarity x : used) total += x.weight();
        return used.contains(r) && total > 0 ? r.weight() / total : 0;
    }

    static String pct(double v) {
        double p = v * 100;
        if (p >= 10) return Math.round(p) + "%";
        if (p >= 1) return String.format(Locale.ROOT, "%.1f%%", p);
        return String.format(Locale.ROOT, "%.2f%%", p);
    }

    static Component text(String mini) {
        return MM.deserialize(mini).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    void msg(CommandSender to, String mini) {
        to.sendMessage(MM.deserialize(prefix + mini));
    }

    // ================================================================= bloc

    /** Le bloc affiche le mob qui tourne, mais ne fait jamais rien apparaître. */
    void applyBlock(VSpawner s) {
        Location l = s.location();
        if (l == null || !l.isChunkLoaded()) return;
        Block b = l.getBlock();
        if (b.getType() != Material.SPAWNER || !(b.getState() instanceof CreatureSpawner cs)) return;
        cs.setSpawnedType(s.type);
        try {
            cs.setMaxSpawnDelay(32000);
            cs.setMinSpawnDelay(32000);
            cs.setDelay(32000);
            cs.setSpawnCount(1);
            cs.setMaxNearbyEntities(1);
            cs.setRequiredPlayerRange(16);
        } catch (IllegalArgumentException ignored) {
            // valeurs refusées : l'annulation des apparitions suffit
        }
        cs.update(true, false);
    }

    // ================================================================= production

    private void tick() {
        ticks++;
        List<VSpawner> gone = new ArrayList<>();
        Map<String, List<Location>> players = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            players.computeIfAbsent(p.getWorld().getName(), k -> new ArrayList<>()).add(p.getLocation());
        }
        double r2 = (double) playerRange * playerRange;
        for (VSpawner s : spawners.values()) {
            World w = Bukkit.getWorld(s.world);
            if (w == null || !w.isChunkLoaded(s.x >> 4, s.z >> 4)) {
                killHolo(s);
                continue;
            }
            if (w.getBlockAt(s.x, s.y, s.z).getType() != Material.SPAWNER) {
                gone.add(s); // bloc remplacé (commande, WorldEdit...) : on oublie ce spawner
                continue;
            }
            Mob m = mob(s.type);
            double best = Double.MAX_VALUE;
            for (Location l : players.getOrDefault(s.world, List.of())) {
                double dx = l.getX() - s.x - 0.5, dy = l.getY() - s.y, dz = l.getZ() - s.z - 0.5;
                best = Math.min(best, dx * dx + dy * dy + dz * dz);
            }
            if (m != null && (!requirePlayer || best <= r2)) {
                s.progress += 1;
                if (s.progress >= m.interval()) {
                    long kills = (long) (s.progress / m.interval());
                    s.progress -= kills * m.interval();
                    produce(s, m, kills * s.stack);
                    markDirty();
                }
            }
            if (ticks % 2 == 0) holo(s, m, best);
        }
        for (VSpawner s : gone) {
            remove(s);
            getLogger().info("Spawner disparu retiré : " + s.key());
        }
        // menus ouverts : on rafraîchit
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof SpawnerMenu menu) menu.refresh();
        if (dirty && ticks % 120 == 0) saveData();
    }

    void produce(VSpawner s, Mob m, long kills) {
        if (kills <= 0) return;
        long cap = itemsPerStack * s.stack, total = s.totalItems();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (Drop d : m.drops()) {
            long room = cap - total;
            if (room <= 0) break;
            long n = 0;
            if (kills <= 256) {
                for (long i = 0; i < kills; i++) if (r.nextDouble() < d.chance()) n += d.min() + r.nextInt(d.max() - d.min() + 1);
            } else {
                double mean = kills * d.average();
                double ex2 = 0;
                for (int v = d.min(); v <= d.max(); v++) ex2 += (double) v * v / (d.max() - d.min() + 1);
                double var = kills * (d.chance() * ex2 - Math.pow(d.average(), 2));
                n = Math.max(0, Math.round(mean + r.nextGaussian() * Math.sqrt(Math.max(0, var))));
            }
            n = Math.min(n, room);
            if (n <= 0) continue;
            s.items.merge(d.mat(), n, Long::sum);
            total += n;
        }
        s.xp = Math.min(xpPerStack * s.stack, s.xp + (long) m.xp() * kills);
    }

    boolean full(VSpawner s) {
        return s.totalItems() >= itemsPerStack * s.stack;
    }

    // ================================================================= hologramme

    private void holo(VSpawner s, Mob m, double bestDist2) {
        boolean show = hologram && m != null && bestDist2 <= 20 * 20;
        if (!show) {
            if (bestDist2 > 26 * 26 || !hologram) killHolo(s);
            return;
        }
        String line = m.rarity().color() + "<b>" + m.name() + "</b>" + (s.stack > 1 ? " <gray>×" + s.stack : "")
                + "\n<yellow>" + s.xp + " XP <dark_gray>· <white>" + s.totalItems() + " objets" + (full(s) ? " <red>(plein)" : "");
        Component c = MM.deserialize(line);
        if (s.holo != null && s.holo.isValid()) {
            s.holo.text(c);
            return;
        }
        Location l = new Location(Bukkit.getWorld(s.world), s.x + 0.5, s.y + 1.15, s.z + 0.5);
        s.holo = l.getWorld().spawn(l, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.getPersistentDataContainer().set(holoKey, PersistentDataType.BYTE, (byte) 1);
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(Color.fromARGB(70, 0, 0, 0));
            d.setShadowed(true);
            d.setViewRange(0.4f);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            var tr = d.getTransformation();
            d.setTransformation(new org.bukkit.util.Transformation(tr.getTranslation(), tr.getLeftRotation(),
                    new org.joml.Vector3f(0.7f, 0.7f, 0.7f), tr.getRightRotation()));
            d.text(c);
        });
    }

    void killHolo(VSpawner s) {
        if (s.holo != null) s.holo.remove();
        s.holo = null;
    }

    // ================================================================= récupération

    /** Tout ce qu'il y a dans le spawner + les spawners eux-mêmes : dans l'inventaire (le reste par terre). */
    void pickup(Player p, VSpawner s) {
        Location l = s.location();
        remove(s);
        if (l != null) l.getBlock().setType(Material.AIR);
        List<ItemStack> out = new ArrayList<>();
        int left = s.stack;
        while (left > 0) {
            int n = Math.min(64, left);
            out.add(item(s.type.name(), n));
            left -= n;
        }
        for (var en : s.items.entrySet()) {
            long n = en.getValue();
            while (n > 0) {
                int k = (int) Math.min(en.getKey().getMaxStackSize(), n);
                out.add(new ItemStack(en.getKey(), k));
                n -= k;
            }
        }
        if (p != null) {
            var rest = p.getInventory().addItem(out.toArray(new ItemStack[0]));
            for (ItemStack it : rest.values()) p.getWorld().dropItemNaturally(p.getLocation(), it);
            if (s.xp > 0) p.giveExp((int) Math.min(Integer.MAX_VALUE, s.xp));
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 0.8f);
        } else if (l != null) {
            for (ItemStack it : out) l.getWorld().dropItemNaturally(l.clone().add(0.5, 0.5, 0.5), it);
            if (s.xp > 0) l.getWorld().spawn(l.clone().add(0.5, 0.5, 0.5), ExperienceOrb.class, o -> o.setExperience((int) Math.min(Integer.MAX_VALUE, s.xp)));
        }
    }

    // ================================================================= tirage animé

    void rollAnimation(Player p, Mob result) {
        List<Mob> all = new ArrayList<>(mobs.values());
        final int[] step = {0};
        int steps = 14;
        Bukkit.getScheduler().runTaskTimer(this, task -> {
            if (!p.isOnline()) {
                task.cancel();
                return;
            }
            if (step[0] < steps) {
                Mob m = all.get(ThreadLocalRandom.current().nextInt(all.size()));
                p.showTitle(Title.title(MM.deserialize(m.rarity().color() + m.name()), MM.deserialize("<gray>Tirage du spawner…"),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(400), Duration.ZERO)));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 0.8f + step[0] * 0.08f);
                step[0]++;
                return;
            }
            task.cancel();
            String rid = result.rarity().id();
            p.showTitle(Title.title(MM.deserialize(result.rarity().color() + "<b>" + result.name()),
                    MM.deserialize(result.rarity().label() + " <gray>· " + pct(chance(result)) + " de chances"),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(2200), Duration.ofMillis(600))));
            boolean big = rid.equals("legendaire") || rid.equals("epique");
            p.playSound(p.getLocation(), big ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.1f);
            msg(p, "<gray>Ton spawner mystère est un spawner à " + result.rarity().color() + "<b>" + result.name() + "</b> <gray>("
                    + result.rarity().label() + "<gray>) !");
            if (big) Bukkit.broadcast(MM.deserialize(prefix + "<white>" + p.getName() + " <gray>a obtenu un spawner " + result.rarity().label()
                    + " <gray>: " + result.rarity().color() + "<b>" + result.name() + "</b><gray> !"));
        }, 1L, 3L);
    }

    // ================================================================= commande

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        boolean adm = sender.hasPermission("smpspawners.admin");
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "liste", "list", "mobs" -> {
                msg(sender, "<gold><b>Mobs possibles</b> <gray>(spawner mystère)");
                for (Rarity r : rarities.values()) {
                    List<String> names = new ArrayList<>();
                    for (Mob m : mobs.values()) if (m.rarity() == r) names.add(m.name());
                    if (names.isEmpty()) continue;
                    sender.sendMessage(MM.deserialize(" " + r.label() + " <dark_gray>(" + pct(rarityChance(r)) + ") <gray>: <white>"
                            + String.join("<gray>, <white>", names)));
                }
            }
            case "give", "donner" -> {
                if (!adm) {
                    msg(sender, "<red>Tu n'as pas la permission.");
                    return true;
                }
                if (a.length < 3) {
                    msg(sender, "<gray>/spawner give <joueur> <mob|mystere> [quantité]");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) {
                    msg(sender, "<red>Joueur introuvable : " + a[1]);
                    return true;
                }
                String id = parseMob(a[2]);
                if (id == null) {
                    msg(sender, "<red>Mob inconnu : " + a[2] + " <gray>(/spawner liste)");
                    return true;
                }
                int n = 1;
                if (a.length >= 4) {
                    try {
                        n = Math.max(1, Math.min(64, Integer.parseInt(a[3])));
                    } catch (NumberFormatException ignored) {
                        // 1
                    }
                }
                var rest = t.getInventory().addItem(item(id, n));
                for (ItemStack it : rest.values()) t.getWorld().dropItemNaturally(t.getLocation(), it);
                msg(sender, "<green>" + n + " spawner(s) donné(s) à " + t.getName() + ".");
                if (sender != t) msg(t, "<green>Tu as reçu " + n + " spawner(s) " + (RANDOM.equals(id) ? "<gradient:#ffd36e:#ff7ae0>mystère</gradient>"
                        : "à " + mob(EntityType.valueOf(id)).name()) + "<green> !");
            }
            case "reload" -> {
                if (!adm) {
                    msg(sender, "<red>Tu n'as pas la permission.");
                    return true;
                }
                loadSettings();
                for (VSpawner s : spawners.values()) killHolo(s);
                msg(sender, "<green>Config rechargée (" + mobs.size() + " mobs).");
            }
            case "info" -> {
                if (!adm) {
                    msg(sender, "<red>Tu n'as pas la permission.");
                    return true;
                }
                msg(sender, "<gray>" + spawners.size() + " spawner(s) posé(s) sur le serveur.");
            }
            default -> {
                msg(sender, "<gold><b>Spawners</b>");
                sender.sendMessage(MM.deserialize("<gray>• Pose un spawner : <white>aucun mob n'apparaît</white>, il produit tout seul l'<yellow>XP</yellow> et le <white>butin</white> du mob."));
                sender.sendMessage(MM.deserialize("<gray>• Un <gradient:#ffd36e:#ff7ae0>spawner mystère</gradient> tire un mob au hasard quand tu le poses."));
                sender.sendMessage(MM.deserialize("<gray>• <yellow>Clic droit</yellow> dessus : récupérer l'XP et les objets, ou reprendre le spawner."));
                sender.sendMessage(MM.deserialize("<gray>• Clic droit avec un spawner du même mob : <white>empiler</white> (jusqu'à " + maxStack + ", production ×" + maxStack + ")."));
                sender.sendMessage(MM.deserialize("<gray>• Il faut être à moins de " + playerRange + " blocs pour qu'il produise. <yellow>/spawner liste</yellow> : les mobs possibles."));
                if (adm) sender.sendMessage(MM.deserialize("<dark_gray>Admin : /spawner give <joueur> <mob|mystere> [n] · /spawner reload · /spawner info"));
            }
        }
        return true;
    }

    String parseMob(String s) {
        String u = s.toUpperCase(Locale.ROOT);
        if (u.equals("MYSTERE") || u.equals("MYSTÈRE") || u.equals("RANDOM") || u.equals("ALEATOIRE") || u.equals("HASARD")) return RANDOM;
        try {
            EntityType t = EntityType.valueOf(u);
            return mobs.containsKey(t) ? t.name() : null;
        } catch (IllegalArgumentException e) {
            for (Mob m : mobs.values()) if (m.name().equalsIgnoreCase(s)) return m.type().name();
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] a) {
        List<String> out = new ArrayList<>();
        boolean adm = sender.hasPermission("smpspawners.admin");
        if (a.length == 1) {
            out.add("liste");
            if (adm) out.addAll(List.of("give", "reload", "info"));
        } else if (a.length == 2 && adm && a[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (a.length == 3 && adm && a[0].equalsIgnoreCase("give")) {
            out.add("mystere");
            for (EntityType t : mobs.keySet()) out.add(t.name().toLowerCase(Locale.ROOT));
        } else if (a.length == 4 && adm && a[0].equalsIgnoreCase("give")) {
            out.addAll(List.of("1", "16", "64"));
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
