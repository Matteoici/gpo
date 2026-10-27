package fr.smpsalles;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Door;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class SmpSalles extends JavaPlugin implements Listener {

    static final MiniMessage MM = MiniMessage.miniMessage();

    private final Map<String, Room> rooms = new LinkedHashMap<>();
    private final Map<UUID, Integer> fails = new HashMap<>();
    private final Map<UUID, Long> lockedUntil = new HashMap<>();
    private VoiceLink voice;
    /** joueur -> salle -> autorisé jusqu'à (ms) : bon code tapé, ou déjà dedans */
    private final Map<UUID, Map<String, Long>> access = new HashMap<>();
    /** pour SoundWall (fil réseau) : salle où est chaque joueur */
    private final Map<UUID, SoundWall.Snap> soundView = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean soundWall;
    /** joueur -> salle où il est (lu par le filtre de voix, sur le fil du voice chat) */
    final Map<UUID, String> roomOf = new java.util.concurrent.ConcurrentHashMap<>();
    volatile boolean voiceIsolation = true;
    private final NameTags tags = new NameTags();
    private File file;

    // pavé : 1 2 3 / 4 5 6 / 7 8 9 / ✖ 0 ✔
    private static final int[] DIGIT_SLOT = {40, 12, 13, 14, 21, 22, 23, 30, 31, 32};
    private static final int CLEAR = 39, CONFIRM = 41, DISPLAY = 4;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        file = new File(getDataFolder(), "salles.yml");
        load();
        getServer().getPluginManager().registerEvents(this, this);
        if (getServer().getPluginManager().getPlugin("voicechat") != null) {
            try {
                voice = (VoiceLink) Class.forName("fr.smpsalles.SvcLink").getDeclaredConstructor(SmpSalles.class).newInstance(this);
                getLogger().info("Simple Voice Chat détecté : salles insonorisées actives.");
            } catch (Throwable t) {
                getLogger().warning("Simple Voice Chat présent mais impossible de s'y brancher : " + t);
            }
        } else {
            getLogger().info("Simple Voice Chat absent : les portes à code marchent, mais pas l'insonorisation.");
        }
        if (getServer().getPluginManager().getPlugin("ProtocolLib") != null) {
            try {
                Class.forName("fr.smpsalles.SoundWall").getDeclaredMethod("register", SmpSalles.class, Map.class).invoke(null, this, soundView);
                soundWall = true;
                getLogger().info("ProtocolLib détecté : les sons de l'extérieur sont coupés dans les salles.");
            } catch (Throwable t) {
                getLogger().warning("ProtocolLib présent mais incompatible : les sons de l'extérieur restent audibles (" + t + ")");
            }
        }
        voiceIsolation = getConfig().getBoolean("voice-isolation", true);
        Bukkit.getScheduler().runTaskTimer(this, this::zoneTick, 20L, 5L);
        Bukkit.getScheduler().runTaskTimer(this, this::guardTick, 20L, 5L);
    }

    @Override
    public void onDisable() {
        if (voice != null) voice.shutdown();
        tags.restoreAll();
        save();
    }

    static Component parse(String s) {
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    String msg(String key) {
        String s = getConfig().getString("messages." + key);
        return s == null ? "<red>[" + key + "]" : s;
    }

    // ================================================================= sauvegarde

    private void load() {
        rooms.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String name : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(name);
            if (s == null) continue;
            Room r = new Room(name);
            r.world = s.getString("world");
            List<Integer> b = s.getIntegerList("box");
            if (b.size() == 6) r.box = new int[]{b.get(0), b.get(1), b.get(2), b.get(3), b.get(4), b.get(5)};
            r.code = s.getString("code", "");
            for (String d : s.getStringList("doors")) {
                String[] p = d.split(",");
                if (p.length == 3) r.doors.add(new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())});
            }
            String g = s.getString("voice-group");
            if (g != null) {
                try {
                    r.groupId = UUID.fromString(g);
                } catch (IllegalArgumentException ignored) {
                }
            }
            rooms.put(name.toLowerCase(Locale.ROOT), r);
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Room r : rooms.values()) {
            y.set(r.name + ".world", r.world);
            if (r.box != null) y.set(r.name + ".box", List.of(r.box[0], r.box[1], r.box[2], r.box[3], r.box[4], r.box[5]));
            y.set(r.name + ".code", r.code);
            List<String> d = new ArrayList<>();
            for (int[] x : r.doors) d.add(x[0] + "," + x[1] + "," + x[2]);
            y.set(r.name + ".doors", d);
            if (r.groupId != null) y.set(r.name + ".voice-group", r.groupId.toString());
        }
        try {
            getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            getLogger().warning("Impossible d'enregistrer salles.yml : " + e.getMessage());
        }
    }

    private Room roomAt(Location l) {
        for (Room r : rooms.values()) if (r.contains(l)) return r;
        return null;
    }

    /** Bas de la porte (les portes font 2 blocs). */
    private static Block doorBase(Block b) {
        if (b.getBlockData() instanceof Bisected bi && bi.getHalf() == Bisected.Half.TOP) return b.getRelative(0, -1, 0);
        return b;
    }

    private Room roomOfDoor(Block b) {
        Block base = doorBase(b);
        for (Room r : rooms.values()) if (r.isDoor(base)) return r;
        return null;
    }

    // ================================================================= accès (code)

    private boolean allowed(Player p, Room r) {
        if (!getConfig().getBoolean("push-intruders", true) || p.hasPermission("smpsalles.bypass")) return true;
        if (r.code == null || r.code.isEmpty()) return true;
        Map<String, Long> m = access.get(p.getUniqueId());
        Long until = m == null ? null : m.get(r.name);
        return until != null && until > System.currentTimeMillis();
    }

    private void grant(Player p, Room r, long ms) {
        access.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).put(r.name, System.currentTimeMillis() + ms);
    }

    /** Point juste devant la porte, côté extérieur. */
    private Location outside(Room r, Player p) {
        World w = Bukkit.getWorld(r.world);
        if (w == null) return null;
        Location best = null;
        double bestD = Double.MAX_VALUE;
        for (int[] d : r.doors) {
            for (int[] n : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                Location l = new Location(w, d[0] + n[0] * 1 + 0.5, d[1], d[2] + n[1] * 1 + 0.5);
                if (r.contains(l)) l = new Location(w, d[0] + n[0] * 2 + 0.5, d[1], d[2] + n[1] * 2 + 0.5);
                if (r.contains(l) || !l.getBlock().isPassable() || !l.clone().add(0, 1, 0).getBlock().isPassable()) continue;
                double dist = l.distanceSquared(p.getLocation());
                if (dist < bestD) {
                    bestD = dist;
                    best = l;
                    best.setYaw((float) Math.toDegrees(Math.atan2(-n[0], n[1])));
                }
            }
        }
        return best;
    }

    private void eject(Player p, Room r) {
        Location out = outside(r, p);
        if (out == null) {
            // pas de porte utilisable : on le pose juste au-dessus du toit de la salle
            out = new Location(Bukkit.getWorld(r.world), (r.box[0] + r.box[3]) / 2.0 + 0.5, r.box[4] + 1, (r.box[2] + r.box[5]) / 2.0 + 0.5);
        }
        p.teleport(out);
        p.setVelocity(out.getDirection().setY(0).normalize().multiply(0.4).setY(0.2));
        p.sendActionBar(parse(msg("intruder")));
        p.playSound(p.getLocation(), Sound.BLOCK_IRON_DOOR_CLOSE, 1f, 0.7f);
    }

    /** Entrer dans une salle sans le code : repoussé. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnter(org.bukkit.event.player.PlayerMoveEvent e) {
        Location to = e.getTo();
        if (e.getFrom().getBlockX() == to.getBlockX() && e.getFrom().getBlockY() == to.getBlockY() && e.getFrom().getBlockZ() == to.getBlockZ()) return;
        Room r = roomAt(to);
        if (r == null || r.contains(e.getFrom())) return;
        Player p = e.getPlayer();
        if (allowed(p, r)) return;
        e.setCancelled(true);
        Location from = e.getFrom();
        org.bukkit.util.Vector back = from.toVector().subtract(to.toVector()).setY(0);
        if (back.lengthSquared() > 1e-4) p.setVelocity(back.normalize().multiply(0.5).setY(0.15));
        p.sendActionBar(parse(msg("intruder")));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleportIn(org.bukkit.event.player.PlayerTeleportEvent e) {
        Room r = roomAt(e.getTo());
        if (r == null || r.contains(e.getFrom()) || allowed(e.getPlayer(), r)) return;
        e.setCancelled(true);
        e.getPlayer().sendActionBar(parse(msg("intruder")));
    }

    /** Toutes les 5 ticks : dedans avec le code = reste autorisé ; dedans sans code (connexion, tp...) = dehors. */
    private void guardTick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Room r = roomAt(p.getLocation());
            if (r == null) continue;
            if (allowed(p, r)) {
                Map<String, Long> m = access.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
                m.merge(r.name, now + 4000, Math::max);
            } else {
                eject(p, r);
            }
        }
        access.values().forEach(m -> m.values().removeIf(t -> t < now));
        access.values().removeIf(Map::isEmpty);
    }

    // ================================================================= voix

    /** Toutes les 5 ticks : qui est dans quelle salle (voix, sons, pseudos). */
    private void zoneTick() {
        boolean sounds = getConfig().getBoolean("sound-isolation", true);
        Map<String, SoundWall.Snap> snaps = new HashMap<>();
        if (soundWall && sounds) {
            for (Room r : rooms.values()) {
                if (!r.ready()) continue;
                World w = Bukkit.getWorld(r.world);
                if (w == null) continue;
                java.util.Set<Integer> ids = new java.util.HashSet<>();
                if (w.isChunkLoaded(r.box[0] >> 4, r.box[2] >> 4)) {
                    org.bukkit.util.BoundingBox bb = new org.bukkit.util.BoundingBox(r.box[0], r.box[1], r.box[2], r.box[3] + 1, r.box[4] + 1, r.box[5] + 1);
                    for (org.bukkit.entity.Entity en : w.getNearbyEntities(bb)) ids.add(en.getEntityId());
                }
                snaps.put(r.name, new SoundWall.Snap(r.name, r.world, r.box.clone(), java.util.Set.copyOf(ids)));
            }
            SoundWall.ALL = List.copyOf(snaps.values());
        } else if (soundWall) {
            SoundWall.ALL = List.of();
        }
        java.util.Set<String> inside = new java.util.HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Room r = roomAt(p.getLocation());
            String before = r == null ? roomOf.remove(p.getUniqueId()) : roomOf.put(p.getUniqueId(), r.name);
            if (r != null) inside.add(p.getName());
            if (soundWall) {
                SoundWall.Snap s = r == null ? null : snaps.get(r.name);
                if (s == null) soundView.remove(p.getUniqueId());
                else soundView.put(p.getUniqueId(), s);
            }
            String now = r == null ? null : r.name;
            if (!java.util.Objects.equals(before, now)) p.sendActionBar(parse(msg(now != null ? "enter-voice" : "leave-voice")));
        }
        if (getConfig().getBoolean("hide-nametags", true)) tags.update(inside);
        else tags.restoreAll();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        soundView.remove(e.getPlayer().getUniqueId());
        access.remove(e.getPlayer().getUniqueId());
        roomOf.remove(e.getPlayer().getUniqueId());
        tags.update(new java.util.HashSet<>(roomOf.keySet().stream().map(Bukkit::getPlayer).filter(java.util.Objects::nonNull)
                .filter(x -> !x.equals(e.getPlayer())).map(Player::getName).toList()));
    }

    // ================================================================= porte

    private final Map<UUID, Long> doorClick = new HashMap<>();

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDoor(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Room r = roomOfDoor(e.getClickedBlock());
        if (r == null) return;
        // bloqué pour LES DEUX mains : sinon le clic de la main gauche ouvrait la porte quand on spammait
        e.setCancelled(true);
        e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        if (now - doorClick.getOrDefault(p.getUniqueId(), 0L) < 400) return; // anti-spam
        doorClick.put(p.getUniqueId(), now);
        Block base = doorBase(e.getClickedBlock());
        if (r.contains(p.getLocation()) || r.code == null || r.code.isEmpty()) {
            openDoor(base); // de l'intérieur : on sort sans code
            return;
        }
        long lock = lockedUntil.getOrDefault(p.getUniqueId(), 0L) - System.currentTimeMillis();
        if (lock > 0) {
            p.sendActionBar(parse(msg("locked").replace("<seconds>", String.valueOf(lock / 1000 + 1))));
            p.playSound(p.getLocation(), Sound.BLOCK_IRON_DOOR_CLOSE, 1f, 0.6f);
            return;
        }
        openKeypad(p, r, base);
    }

    /** Bouton, plaque de pression, levier... : une porte de salle ne s'ouvre qu'avec le code. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRedstone(org.bukkit.event.block.BlockRedstoneEvent e) {
        Block b = e.getBlock();
        if (!(b.getBlockData() instanceof Openable) || roomOfDoor(b) == null || opening.contains(doorBase(b))) return;
        e.setNewCurrent(e.getOldCurrent());
    }

    /** Les portes d'une salle ne se cassent qu'avec la permission admin. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (roomOfDoor(e.getBlock()) != null && !e.getPlayer().hasPermission("smpsalles.admin")) e.setCancelled(true);
    }

    private final java.util.Set<Block> opening = new java.util.HashSet<>();

    private void openDoor(Block base) {
        opening.add(base);
        setOpen(base, true);
        World w = base.getWorld();
        boolean iron = base.getType() == Material.IRON_DOOR || base.getType() == Material.IRON_TRAPDOOR;
        w.playSound(base.getLocation().add(0.5, 0.5, 0.5), iron ? Sound.BLOCK_IRON_DOOR_OPEN : Sound.BLOCK_WOODEN_DOOR_OPEN, 1f, 1f);
        long ticks = Math.max(1, Math.round(getConfig().getDouble("open-seconds", 3) * 20));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            opening.remove(base);
            setOpen(base, false);
            w.playSound(base.getLocation().add(0.5, 0.5, 0.5), iron ? Sound.BLOCK_IRON_DOOR_CLOSE : Sound.BLOCK_WOODEN_DOOR_CLOSE, 1f, 1f);
        }, ticks);
    }

    private static void setOpen(Block base, boolean open) {
        for (Block b : new Block[]{base, base.getRelative(0, 1, 0)}) {
            BlockData d = b.getBlockData();
            if (!(d instanceof Openable o)) continue;
            if (b != base && !(d instanceof Door)) continue;
            o.setOpen(open);
            b.setBlockData(o, false);
        }
    }

    // ================================================================= pavé numérique

    static final class Keypad implements InventoryHolder {
        final Room room;
        final Block door;
        final StringBuilder typed = new StringBuilder();
        Inventory inv;

        Keypad(Room room, Block door) {
            this.room = room;
            this.door = door;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private void openKeypad(Player p, Room r, Block door) {
        Keypad k = new Keypad(r, door);
        k.inv = Bukkit.createInventory(k, 45, parse(msg("keypad-title").replace("<room>", MM.escapeTags(r.name))));
        ItemStack bg = item(Material.BLACK_STAINED_GLASS_PANE, 1, " ");
        for (int i = 0; i < 45; i++) k.inv.setItem(i, bg);
        for (int d = 0; d <= 9; d++) {
            k.inv.setItem(DIGIT_SLOT[d], item(d == 0 ? Material.WHITE_STAINED_GLASS_PANE : Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                    d == 0 ? 1 : d, msg("digit").replace("<n>", String.valueOf(d))));
        }
        k.inv.setItem(CLEAR, item(Material.RED_STAINED_GLASS_PANE, 1, msg("clear")));
        k.inv.setItem(CONFIRM, item(Material.LIME_STAINED_GLASS_PANE, 1, msg("confirm")));
        refresh(k);
        p.openInventory(k.inv);
        p.playSound(p.getLocation(), Sound.BLOCK_STONE_BUTTON_CLICK_ON, 1f, 1.2f);
    }

    private void refresh(Keypad k) {
        String shown = k.typed.isEmpty() ? msg("display-empty") : msg("display").replace("<code>", "●".repeat(k.typed.length()));
        k.inv.setItem(DISPLAY, item(Material.NAME_TAG, 1, shown));
    }

    private static ItemStack item(Material m, int amount, String name) {
        ItemStack it = new ItemStack(m, Math.max(1, amount));
        ItemMeta meta = it.getItemMeta();
        meta.displayName(parse(name));
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Keypad) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Keypad k) || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != k.inv) return;
        int slot = e.getRawSlot();
        for (int d = 0; d <= 9; d++) {
            if (DIGIT_SLOT[d] != slot) continue;
            if (k.typed.length() < 8) k.typed.append(d);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1.2f + d * 0.05f);
            refresh(k);
            return;
        }
        if (slot == CLEAR) {
            k.typed.setLength(0);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.8f);
            refresh(k);
        } else if (slot == CONFIRM) {
            check(p, k);
        }
    }

    private void check(Player p, Keypad k) {
        p.closeInventory();
        if (k.typed.toString().equals(k.room.code)) {
            fails.remove(p.getUniqueId());
            grant(p, k.room, getConfig().getLong("entry-seconds", 15) * 1000);
            p.sendActionBar(parse(msg("granted")));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.6f);
            openDoor(k.door);
            return;
        }
        int max = Math.max(1, getConfig().getInt("max-attempts", 3));
        int f = fails.merge(p.getUniqueId(), 1, Integer::sum);
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        if (f >= max) {
            fails.remove(p.getUniqueId());
            long secs = getConfig().getLong("lockout-seconds", 30);
            lockedUntil.put(p.getUniqueId(), System.currentTimeMillis() + secs * 1000);
            p.sendActionBar(parse(msg("locked").replace("<seconds>", String.valueOf(secs))));
        } else {
            p.sendActionBar(parse(msg("denied").replace("<left>", String.valueOf(max - f))));
        }
    }

    // ================================================================= commande

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0) {
            help(s);
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("liste") || sub.equals("list")) {
            if (rooms.isEmpty()) s.sendMessage(parse("<gray>Aucune salle. <white>/salle creer <nom>"));
            for (Room r : rooms.values()) {
                s.sendMessage(parse("<gold>• <white>" + MM.escapeTags(r.name) + " <gray>· " + (r.ready() ? "zone ok" : "<red>zone manquante")
                        + " <gray>· " + r.doors.size() + " porte(s) · " + (r.code.isEmpty() ? "<red>pas de code" : "code ok")));
            }
            return true;
        }
        if (sub.equals("reload")) {
            reloadConfig();
            voiceIsolation = getConfig().getBoolean("voice-isolation", true);
            s.sendMessage(parse("<green>Config rechargée."));
            return true;
        }
        if (a.length < 2) {
            help(s);
            return true;
        }
        String name = a[1];
        Room r = rooms.get(name.toLowerCase(Locale.ROOT));
        if (sub.equals("creer") || sub.equals("create")) {
            if (r != null) {
                s.sendMessage(parse("<red>La salle existe déjà."));
                return true;
            }
            rooms.put(name.toLowerCase(Locale.ROOT), new Room(name));
            save();
            s.sendMessage(parse("<green>Salle <white>" + MM.escapeTags(name) + "</white> créée. <gray>Maintenant : <white>/salle pos1 " + name
                    + "</white> et <white>/salle pos2 " + name + "</white> aux deux coins opposés."));
            return true;
        }
        if (r == null) {
            s.sendMessage(parse("<red>Salle inconnue. <gray>/salle liste"));
            return true;
        }
        switch (sub) {
            case "pos1", "pos2" -> {
                if (!(s instanceof Player p)) return true;
                Location l = p.getLocation().getBlock().getLocation();
                if (sub.equals("pos1")) r.pos1 = l;
                else r.pos2 = l;
                s.sendMessage(parse("<green>" + sub + " placée en <white>" + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()));
                if (r.pos1 != null && r.pos2 != null && r.pos1.getWorld() == r.pos2.getWorld()) {
                    r.setBox(r.pos1, r.pos2);
                    save();
                    s.sendMessage(parse("<green>Zone de la salle enregistrée <gray>(" + (r.box[3] - r.box[0] + 1) + "×" + (r.box[4] - r.box[1] + 1)
                            + "×" + (r.box[5] - r.box[2] + 1) + "). <gray>Ensuite regarde la porte : <white>/salle porte " + r.name));
                }
            }
            case "porte", "door" -> {
                if (!(s instanceof Player p)) return true;
                Block b = p.getTargetBlockExact(6);
                if (b == null || !(b.getBlockData() instanceof Openable)) {
                    s.sendMessage(parse("<red>Regarde une porte (ou une trappe / portillon) à moins de 6 blocs."));
                    return true;
                }
                Block base = doorBase(b);
                if (r.isDoor(base)) {
                    r.doors.removeIf(d -> d[0] == base.getX() && d[1] == base.getY() && d[2] == base.getZ());
                    s.sendMessage(parse("<yellow>Porte retirée de la salle."));
                } else {
                    if (r.world == null) r.world = base.getWorld().getName();
                    r.doors.add(new int[]{base.getX(), base.getY(), base.getZ()});
                    s.sendMessage(parse("<green>Porte ajoutée. " + (base.getType().name().contains("IRON") ? ""
                            : "<gray>Conseil : une <white>porte en fer</white> ne s'ouvre pas avec la redstone des autres joueurs aussi facilement.")));
                }
                save();
            }
            case "code" -> {
                if (a.length < 3 || !a[2].matches("[0-9]{1,8}")) {
                    s.sendMessage(parse("<red>Le code doit faire 1 à 8 chiffres. <gray>/salle code " + r.name + " 1234"));
                    return true;
                }
                r.code = a[2];
                save();
                s.sendMessage(parse("<green>Code de <white>" + MM.escapeTags(r.name) + "</white> : <yellow>" + a[2]));
            }
            case "supprimer", "delete" -> {
                rooms.remove(name.toLowerCase(Locale.ROOT));
                save();
                s.sendMessage(parse("<yellow>Salle supprimée."));
            }
            case "info" -> {
                s.sendMessage(parse("<gold>Salle <white>" + MM.escapeTags(r.name)));
                s.sendMessage(parse("<gray>Zone : <white>" + (r.ready() ? r.world + " " + r.box[0] + " " + r.box[1] + " " + r.box[2] + " → "
                        + r.box[3] + " " + r.box[4] + " " + r.box[5] : "<red>manquante")));
                s.sendMessage(parse("<gray>Portes : <white>" + r.doors.size() + " <gray>· Code : <white>" + (r.code.isEmpty() ? "<red>aucun" : r.code)));
                s.sendMessage(parse("<gray>Voix : " + (voice == null ? "<red>Simple Voice Chat absent"
                        : !voice.ready() ? "<yellow>Simple Voice Chat pas encore démarré"
                        : "<green>insonorisée (Simple Voice Chat)") + (voice != null && voice.problem() != null ? " <red>(" + MM.escapeTags(voice.problem()) + ")" : "")));
                s.sendMessage(parse("<gray>Pseudos : " + (getConfig().getBoolean("hide-nametags", true) ? "<green>cachés pour ceux qui sont dedans" : "<yellow>visibles")));
                s.sendMessage(parse("<gray>Sons Minecraft : " + (soundWall ? "<green>extérieur coupé (ProtocolLib)" : "<red>ProtocolLib absent")));
            }
            default -> help(s);
        }
        return true;
    }

    private void help(CommandSender s) {
        s.sendMessage(parse("<gold><b>Salles à code</b>"));
        s.sendMessage(parse("<white>/salle creer <nom>"));
        s.sendMessage(parse("<white>/salle pos1 <nom> <gray>/ <white>pos2 <nom> <gray>· deux coins opposés (murs compris)"));
        s.sendMessage(parse("<white>/salle porte <nom> <gray>· en regardant la porte (refaire = la retirer)"));
        s.sendMessage(parse("<white>/salle code <nom> <chiffres>"));
        s.sendMessage(parse("<white>/salle info <nom> <gray>· <white>/salle liste <gray>· <white>/salle supprimer <nom>"));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String x : List.of("creer", "pos1", "pos2", "porte", "code", "info", "liste", "supprimer", "reload")) {
                if (x.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(x);
            }
        } else if (a.length == 2 && !a[0].equalsIgnoreCase("creer")) {
            for (Room r : rooms.values()) if (r.name.toLowerCase(Locale.ROOT).startsWith(a[1].toLowerCase(Locale.ROOT))) out.add(r.name);
        }
        return out;
    }
}
