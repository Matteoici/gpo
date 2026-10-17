package fr.smpparkour;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Parcours : génération automatique (/parkour generer), chrono, checkpoints, chutes, classements, récompenses.
 */
public final class SmpParkour extends JavaPlugin implements Listener, TabExecutor {

    static final MiniMessage MM = MiniMessage.miniMessage();

    /** Une course en cours. */
    private static final class Run {
        final Course course;
        long startMs;
        int cp = -1; // -1 = départ
        int fails;

        Run(Course c) {
            this.course = c;
            this.startMs = System.currentTimeMillis();
        }
    }

    private record Trigger(Course course, int type, int index) { // type 0 départ, 1 checkpoint, 2 arrivée
    }

    final Map<String, Course> courses = new LinkedHashMap<>();
    private final Map<UUID, Run> runs = new HashMap<>();
    private final Map<String, Trigger> triggers = new HashMap<>();
    final List<TextDisplay> labels = new ArrayList<>();
    private final Map<String, TextDisplay> boards = new HashMap<>();
    private final Set<UUID> teleporting = new HashSet<>();
    private Generator generator;
    private NamespacedKey itemKey, savedKey;
    private File file;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "item");
        savedKey = new NamespacedKey(this, "saved_items");
        generator = new Generator(this);
        file = new File(getDataFolder(), "courses.yml");
        load();
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("parkour");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        // les mondes peuvent charger après nous : étiquettes et classements une seconde plus tard
        Bukkit.getScheduler().runTaskLater(this, this::spawnVisuals, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::safeTick, 1L, 1L);
        for (Player p : Bukkit.getOnlinePlayers()) restoreItems(p);
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(runs.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) end(p);
        }
        clearVisuals();
        save();
    }

    Component mm(String s) {
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private String msg(String k) {
        return getConfig().getString("messages." + k, k);
    }

    private void send(CommandSender to, String key, String... repl) {
        String s = msg(key);
        for (int i = 0; i + 1 < repl.length; i += 2) s = s.replace("<" + repl[i] + ">", repl[i + 1]);
        to.sendMessage(mm(msg("prefix") + s));
    }

    private static void sound(Player p, String key, float vol, float pitch) {
        p.playSound(Sound.sound(Key.key("minecraft", key), Sound.Source.MASTER, vol, pitch));
    }

    static String time(long ms) {
        long m = ms / 60000, s = (ms / 1000) % 60, r = ms % 1000;
        return m > 0 ? String.format(Locale.ROOT, "%d:%02d.%03d", m, s, r) : String.format(Locale.ROOT, "%d.%03ds", s, r);
    }

    // ================================================================= fichier

    private void load() {
        courses.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection cs = y.getConfigurationSection("courses");
        if (cs != null) {
            for (String k : cs.getKeys(false)) {
                ConfigurationSection s = cs.getConfigurationSection(k);
                if (s != null) courses.put(k.toLowerCase(Locale.ROOT), Course.load(k, s));
            }
        }
        reindex();
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Course c : courses.values()) c.save(y.createSection("courses." + c.name));
        try {
            y.save(file);
        } catch (IOException e) {
            getLogger().warning("Sauvegarde impossible : " + e.getMessage());
        }
    }

    private void reindex() {
        triggers.clear();
        for (Course c : courses.values()) c.invalidate();
        for (Course c : courses.values()) {
            if (c.start != null) triggers.put(key(c.start), new Trigger(c, 0, 0));
            for (int i = 0; i < c.checkpoints.size(); i++) triggers.put(key(c.checkpoints.get(i)), new Trigger(c, 1, i));
            if (c.finish != null) triggers.put(key(c.finish), new Trigger(c, 2, 0));
        }
    }

    private static String key(Location l) {
        return l.getWorld().getName() + ";" + l.getBlockX() + ";" + l.getBlockY() + ";" + l.getBlockZ();
    }

    // ================================================================= visuels (étiquettes, classements)

    private void spawnVisuals() {
        clearVisuals();
        for (Course c : courses.values()) {
            for (String[] l : c.labels) {
                Location at = Course.loc(l[0]);
                if (at != null) generator.spawnLabel(at, l[1]);
            }
            board(c);
        }
    }

    private void clearVisuals() {
        for (TextDisplay d : labels) d.remove();
        labels.clear();
        for (TextDisplay d : boards.values()) d.remove();
        boards.clear();
    }

    /** Hologramme du top 10. */
    private void board(Course c) {
        TextDisplay old = boards.remove(c.name);
        if (old != null) old.remove();
        if (c.board == null || c.board.getWorld() == null) return;
        StringBuilder sb = new StringBuilder("<gradient:#7cff6b:#00d4ff><b>PARKOUR " + c.name.toUpperCase(Locale.ROOT) + "</b></gradient>\n<dark_gray>───────────────");
        var top = c.top(10);
        if (top.isEmpty()) sb.append("\n<gray>Personne pour l'instant !");
        int i = 1;
        for (var e : top) {
            String color = i == 1 ? "<gold>" : i == 2 ? "<#c0c0c0>" : i == 3 ? "<#cd7f32>" : "<gray>";
            sb.append("\n").append(color).append(i).append(". <white>").append(MM.escapeTags(c.names.getOrDefault(e.getKey(), "?")))
                    .append(" <dark_gray>— ").append(color).append(time(e.getValue()));
            i++;
        }
        String text = sb.toString();
        TextDisplay d = c.board.getWorld().spawn(c.board, TextDisplay.class, t -> {
            t.setPersistent(false);
            t.setBillboard(Display.Billboard.CENTER);
            t.setShadowed(true);
            t.setLineWidth(300);
            t.text(mm(text));
        });
        boards.put(c.name, d);
    }

    // ================================================================= courses

    private void start(Player p, Course c) {
        for (String eff : getConfig().getStringList("forbidden-effects")) {
            PotionEffectType t = io.papermc.paper.registry.RegistryAccess.registryAccess()
                    .getRegistry(io.papermc.paper.registry.RegistryKey.MOB_EFFECT).get(NamespacedKey.minecraft(eff.toLowerCase(Locale.ROOT)));
            if (t != null && p.hasPotionEffect(t)) {
                p.sendActionBar(mm(msg("effects")));
                return;
            }
        }
        Run old = runs.get(p.getUniqueId());
        if (old != null && old.course == c && System.currentTimeMillis() - old.startMs < 800) return; // encore sur la plaque
        if (old == null) giveItems(p);
        runs.put(p.getUniqueId(), new Run(c));
        send(p, "started");
        sound(p, "block.note_block.pling", 1f, 1.6f);
    }

    private void checkpoint(Player p, Run r, int index) {
        if (index <= r.cp) return;
        r.cp = index;
        send(p, "checkpoint", "n", String.valueOf(index + 1), "total", String.valueOf(r.course.checkpoints.size()),
                "time", time(System.currentTimeMillis() - r.startMs));
        sound(p, "entity.experience_orb.pickup", 1f, 1.2f);
    }

    private void finish(Player p, Run r) {
        Course c = r.course;
        if (r.cp < c.checkpoints.size() - 1) {
            p.sendActionBar(mm("<red>Tu as raté un checkpoint ! <gray>(" + (r.cp + 1) + "/" + c.checkpoints.size() + ")"));
            return;
        }
        long ms = System.currentTimeMillis() - r.startMs;
        end(p);
        send(p, "finished", "time", time(ms), "fails", String.valueOf(r.fails));
        sound(p, "ui.toast.challenge_complete", 1f, 1f);
        p.getWorld().spawnParticle(org.bukkit.Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 60, 0.5, 1, 0.5, 0.3);
        UUID id = p.getUniqueId();
        Long before = c.best.get(id);
        boolean first = before == null;
        var top = c.top(1);
        long record = top.isEmpty() ? Long.MAX_VALUE : top.get(0).getValue();
        if (before == null || ms < before) {
            c.best.put(id, ms);
            c.names.put(id, p.getName());
            if (before != null) send(p, "personal-best", "old", time(before));
            if (ms < record && getConfig().getBoolean("rewards.broadcast-records", true)) {
                String line = msg("prefix") + msg("record").replace("<player>", MM.escapeTags(p.getName()))
                        .replace("<course>", MM.escapeTags(c.name)).replace("<time>", time(ms));
                Bukkit.broadcast(mm(line));
            }
        }
        // récompenses
        List<String> cmds = new ArrayList<>();
        if (first) cmds.addAll(getConfig().getStringList("rewards.first-completion"));
        long cd = (long) (getConfig().getDouble("rewards.every-cooldown-hours", 12) * 3_600_000);
        long last = c.rewarded.getOrDefault(id, 0L);
        if (System.currentTimeMillis() - last >= cd) {
            cmds.addAll(getConfig().getStringList("rewards.every-completion"));
            c.rewarded.put(id, System.currentTimeMillis());
        }
        for (String cmd : cmds) {
            String line = cmd.replace("%player%", p.getName()).replace("%course%", c.name).replace("%time%", time(ms));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line.startsWith("/") ? line.substring(1) : line);
        }
        board(c);
        save();
    }

    /** Retour au dernier checkpoint (ou au départ, chrono relancé). */
    private void back(Player p, Run r, boolean fall) {
        Location to = r.cp >= 0 ? r.course.checkpoints.get(r.cp) : r.course.start;
        if (to == null) return;
        if (fall) r.fails++;
        if (r.cp < 0) r.startMs = System.currentTimeMillis();
        tp(p, to.clone().add(0, 0.1, 0));
        p.setFallDistance(0);
        p.setVelocity(new org.bukkit.util.Vector());
        if (fall) sound(p, "entity.enderman.teleport", 0.6f, 1.4f);
    }

    private void tp(Player p, Location to) {
        teleporting.add(p.getUniqueId());
        try {
            p.teleport(to);
        } finally {
            teleporting.remove(p.getUniqueId());
        }
    }

    private void end(Player p) {
        if (runs.remove(p.getUniqueId()) == null) return;
        restoreItems(p);
        p.sendActionBar(Component.empty());
    }

    private void cancel(Player p, String reason) {
        if (!runs.containsKey(p.getUniqueId())) return;
        end(p);
        send(p, "cancelled", "reason", reason);
    }

    private void safeTick() {
        try {
            tick();
        } catch (Throwable t) {
            getLogger().warning("Parkour : " + t);
        }
    }

    private int tick;

    private void tick() {
        tick++;
        int depth = getConfig().getInt("fall-depth", 6);
        boolean liquid = getConfig().getBoolean("reset-in-liquid", true);
        for (var e : new ArrayList<>(runs.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            Run r = e.getValue();
            if (p == null) {
                runs.remove(e.getKey());
                continue;
            }
            Location ref = r.cp >= 0 ? r.course.checkpoints.get(r.cp) : r.course.start;
            if (ref != null && ref.getWorld() == p.getWorld()) {
                double lowest = Math.min(ref.getY(), r.course.minY());
                if (p.getLocation().getY() < lowest - depth || (liquid && (p.isInWater() || p.isInLava()))) back(p, r, true);
            }
            if (tick % 2 == 0) {
                p.sendActionBar(mm(msg("actionbar").replace("<time>", time(System.currentTimeMillis() - r.startMs))
                        .replace("<cp>", String.valueOf(r.cp + 1)).replace("<total>", String.valueOf(r.course.checkpoints.size()))
                        .replace("<fails>", String.valueOf(r.fails))));
            }
        }
    }

    // ================================================================= objets de course

    private void giveItems(Player p) {
        var inv = p.getInventory();
        List<String> saved = new ArrayList<>();
        ConfigurationSection items = getConfig().getConfigurationSection("items");
        if (items == null) return;
        for (String k : items.getKeys(false)) {
            int slot = items.getInt(k + ".slot", -1);
            Material m = Material.matchMaterial(items.getString(k + ".material", "STONE"));
            if (slot < 0 || slot > 8 || m == null) continue;
            ItemStack old = inv.getItem(slot);
            saved.add(slot + ":" + (old == null || old.getType().isAir() ? "" : Base64.getEncoder().encodeToString(old.serializeAsBytes())));
            ItemStack it = new ItemStack(m);
            ItemMeta meta = it.getItemMeta();
            meta.displayName(mm(items.getString(k + ".name", k)));
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, k);
            it.setItemMeta(meta);
            inv.setItem(slot, it);
        }
        // gardé sur le joueur : même un crash du serveur ne fait pas perdre ses objets
        p.getPersistentDataContainer().set(savedKey, PersistentDataType.STRING, String.join("|", saved));
    }

    private void restoreItems(Player p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (tag(it) != null) inv.setItem(i, null);
        }
        String saved = p.getPersistentDataContainer().get(savedKey, PersistentDataType.STRING);
        if (saved == null) return;
        p.getPersistentDataContainer().remove(savedKey);
        for (String part : saved.split("\\|")) {
            if (part.isEmpty()) continue;
            int c = part.indexOf(':');
            int slot = Integer.parseInt(part.substring(0, c));
            String data = part.substring(c + 1);
            if (data.isEmpty()) continue;
            ItemStack it = ItemStack.deserializeBytes(Base64.getDecoder().decode(data));
            if (inv.getItem(slot) == null || inv.getItem(slot).getType().isAir()) inv.setItem(slot, it);
            else inv.addItem(it).values().forEach(r -> p.getWorld().dropItemNaturally(p.getLocation(), r));
        }
    }

    private String tag(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
    }

    // ================================================================= événements

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location f = e.getFrom(), t = e.getTo();
        if (f.getBlockX() == t.getBlockX() && f.getBlockY() == t.getBlockY() && f.getBlockZ() == t.getBlockZ()) return;
        Player p = e.getPlayer();
        Trigger tr = triggers.get(key(t));
        if (tr == null) tr = triggers.get(t.getWorld().getName() + ";" + t.getBlockX() + ";" + (t.getBlockY() - 1) + ";" + t.getBlockZ());
        if (tr == null) return;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR || p.isFlying()) return;
        Run r = runs.get(p.getUniqueId());
        switch (tr.type()) {
            case 0 -> start(p, tr.course());
            case 1 -> {
                if (r != null && r.course == tr.course()) checkpoint(p, r, tr.index());
            }
            default -> {
                if (r != null && r.course == tr.course()) finish(p, r);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() == Action.PHYSICAL) return;
        String t = tag(e.getItem());
        if (t == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Run r = runs.get(p.getUniqueId());
        if (r == null) {
            restoreItems(p);
            return;
        }
        switch (t) {
            case "checkpoint" -> back(p, r, false);
            case "restart" -> {
                r.cp = -1;
                r.fails = 0;
                back(p, r, false);
                r.startMs = System.currentTimeMillis();
            }
            case "quit" -> {
                end(p);
                send(p, "quit");
            }
            default -> {
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (tag(e.getItemDrop().getItemStack()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (tag(e.getCurrentItem()) != null || tag(e.getCursor()) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(it -> tag(it) != null);
        end(e.getEntity());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        end(e.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        restoreItems(e.getPlayer());
    }

    // ---------------------------------------------------------------- triche pendant la course

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (teleporting.contains(e.getPlayer().getUniqueId())) return;
        cancel(e.getPlayer(), "téléportation");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFly(PlayerToggleFlightEvent e) {
        if (e.isFlying()) cancel(e.getPlayer(), "vol");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent e) {
        if (e.isGliding() && e.getEntity() instanceof Player p) cancel(p, "élytres");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGamemode(PlayerGameModeChangeEvent e) {
        cancel(e.getPlayer(), "mode de jeu");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorld(PlayerChangedWorldEvent e) {
        cancel(e.getPlayer(), "changement de monde");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicle(VehicleEnterEvent e) {
        if (e.getEntered() instanceof Player p && runs.containsKey(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (runs.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPearl(ProjectileLaunchEvent e) {
        if (e.getEntity().getShooter() instanceof Player p && runs.containsKey(p.getUniqueId())) e.setCancelled(true);
    }

    /** Les blocs d'un parcours généré ne se cassent pas (sauf staff). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (e.getPlayer().hasPermission("smpparkour.admin")) return;
        String k = Course.key(e.getBlock());
        for (Course c : courses.values()) {
            if (c.placed.containsKey(k)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> {
            String k = Course.key(b);
            for (Course c : courses.values()) if (c.placed.containsKey(k)) return true;
            return false;
        });
    }

    // ================================================================= commande

    private Course course(CommandSender s, String name) {
        Course c = courses.get(name.toLowerCase(Locale.ROOT));
        if (c == null) send(s, "no-course", "name", name);
        return c;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "liste" : a[0].toLowerCase(Locale.ROOT);
        boolean admin = s.hasPermission("smpparkour.admin");
        Player p = s instanceof Player pl ? pl : null;
        switch (sub) {
            case "liste", "list" -> {
                s.sendMessage(mm(msg("prefix") + "<white><b>Parcours :</b>"));
                for (Course c : courses.values()) {
                    if (!c.ready()) continue;
                    var top = c.top(1);
                    String rec = top.isEmpty() ? "<gray>pas de record" : "<gold>" + time(top.get(0).getValue()) + " <gray>(" + c.names.getOrDefault(top.get(0).getKey(), "?") + ")";
                    s.sendMessage(mm(" <dark_gray>▸ <aqua>" + c.name + " <dark_gray>— " + rec + " <green>[Y aller]")
                            .clickEvent(ClickEvent.runCommand("/parkour rejoindre " + c.name))
                            .hoverEvent(HoverEvent.showText(mm("<gray>Se téléporter au départ"))));
                }
                if (courses.isEmpty()) s.sendMessage(mm("<gray>Aucun parcours pour l'instant."));
            }
            case "rejoindre", "join", "tp" -> {
                if (p == null || a.length < 2) return true;
                Course c = course(s, a[1]);
                if (c == null || c.start == null) return true;
                end(p);
                tp(p, c.start.clone().add(0, 0.1, 0));
            }
            case "checkpoint", "cp" -> {
                Run r = p == null ? null : runs.get(p.getUniqueId());
                if (r != null) back(p, r, false);
            }
            case "recommencer", "restart" -> {
                Run r = p == null ? null : runs.get(p.getUniqueId());
                if (r != null) {
                    r.cp = -1;
                    r.fails = 0;
                    back(p, r, false);
                }
            }
            case "quitter", "quit", "leave" -> {
                if (p != null && runs.containsKey(p.getUniqueId())) {
                    end(p);
                    send(p, "quit");
                }
            }
            case "top" -> {
                if (a.length < 2) return true;
                Course c = course(s, a[1]);
                if (c == null) return true;
                s.sendMessage(mm(msg("prefix") + "<white><b>Top " + c.name + "</b>"));
                int i = 1;
                for (var e : c.top(10)) {
                    s.sendMessage(mm(" <gray>" + i++ + ". <white>" + MM.escapeTags(c.names.getOrDefault(e.getKey(), "?")) + " <dark_gray>— <gold>" + time(e.getValue())));
                }
            }
            default -> {
                if (!admin) {
                    send(s, "no-course", "name", sub);
                    return true;
                }
                admin(s, p, sub, a);
            }
        }
        return true;
    }

    private void admin(CommandSender s, Player p, String sub, String[] a) {
        if (sub.equals("reload")) {
            reloadConfig();
            load();
            spawnVisuals();
            s.sendMessage(mm(msg("prefix") + "<green>Rechargé."));
            return;
        }
        if (p == null || a.length < 2) {
            s.sendMessage(mm(msg("prefix") + "<gray>/parkour generer <nom> <sauts> [facile|moyen|dur] [thème]  |  creer/depart/checkpoint-ajouter/arrivee/classement/supprimer <nom>"));
            return;
        }
        String name = a[1];
        String key = name.toLowerCase(Locale.ROOT);
        switch (sub) {
            case "generer", "generate", "gen" -> {
                if (courses.containsKey(key)) {
                    s.sendMessage(mm(msg("prefix") + "<red>Ce parcours existe déjà (supprime-le d'abord)."));
                    return;
                }
                int jumps = 30;
                try {
                    if (a.length >= 3) jumps = Integer.parseInt(a[2]);
                } catch (NumberFormatException ignored) {
                }
                jumps = Math.max(5, Math.min(getConfig().getInt("generator.max-jumps", 250), jumps));
                String diff = a.length >= 4 ? a[3].toLowerCase(Locale.ROOT) : "moyen";
                if (!Generator.difficulties().contains(diff)) diff = "moyen";
                String theme = a.length >= 5 && Generator.isTheme(a[4]) ? a[4].toLowerCase(Locale.ROOT) : "neon";
                Course c = new Course(name);
                int built = generator.build(c, p.getLocation(), jumps, diff, theme);
                courses.put(key, c);
                reindex();
                save();
                s.sendMessage(mm(msg("prefix") + "<green>Parcours <white>" + name + "</white> construit : <white>" + built
                        + "</white> sauts, <white>" + c.checkpoints.size() + "</white> checkpoints <gray>(" + diff + ", " + theme + ")"
                        + (built < jumps ? " <yellow>— arrêté plus tôt, plus de place" : "")));
                s.sendMessage(mm("<gray>Pose le classement avec <white>/parkour classement " + name + "</white> là où tu veux."));
            }
            case "creer", "create" -> {
                courses.putIfAbsent(key, new Course(name));
                save();
                s.sendMessage(mm(msg("prefix") + "<green>Créé. Mets-toi au départ : <white>/parkour depart " + name));
            }
            case "depart", "setstart" -> {
                Course c = course(s, name);
                if (c == null) return;
                c.start = p.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
                c.start.setYaw(p.getLocation().getYaw());
                reindex();
                save();
                s.sendMessage(mm(msg("prefix") + "<green>Départ placé. <gray>(mets une plaque de pression ici, c'est plus clair)"));
            }
            case "checkpoint-ajouter", "addcp" -> {
                Course c = course(s, name);
                if (c == null) return;
                Location l = p.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
                l.setYaw(p.getLocation().getYaw());
                c.checkpoints.add(l);
                reindex();
                save();
                s.sendMessage(mm(msg("prefix") + "<green>Checkpoint " + c.checkpoints.size() + " ajouté."));
            }
            case "arrivee", "setfinish" -> {
                Course c = course(s, name);
                if (c == null) return;
                c.finish = p.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
                reindex();
                save();
                s.sendMessage(mm(msg("prefix") + "<green>Arrivée placée."));
            }
            case "classement", "board", "holo" -> {
                Course c = course(s, name);
                if (c == null) return;
                c.board = p.getLocation().add(0, 1.5, 0);
                board(c);
                save();
                s.sendMessage(mm(msg("prefix") + "<green>Classement placé."));
            }
            case "supprimer", "delete" -> {
                Course c = courses.remove(key);
                if (c == null) {
                    send(s, "no-course", "name", name);
                    return;
                }
                for (UUID id : new ArrayList<>(runs.keySet())) {
                    Player o = Bukkit.getPlayer(id);
                    if (o != null && runs.get(id).course == c) end(o);
                }
                int n = 0;
                List<Map.Entry<String, String>> blocks = new ArrayList<>(c.placed.entrySet());
                java.util.Collections.reverse(blocks); // plaques d'abord, puis leurs supports
                for (var e : blocks) {
                    String[] k = e.getKey().split(";");
                    var w = Bukkit.getWorld(k[0]);
                    if (w == null) continue;
                    Block b = w.getBlockAt(Integer.parseInt(k[1]), Integer.parseInt(k[2]), Integer.parseInt(k[3]));
                    try {
                        b.setBlockData(Bukkit.createBlockData(e.getValue()), false);
                    } catch (IllegalArgumentException ex) {
                        b.setType(Material.AIR, false);
                    }
                    n++;
                }
                reindex();
                save();
                spawnVisuals();
                s.sendMessage(mm(msg("prefix") + "<yellow>Parcours supprimé" + (n > 0 ? " (" + n + " blocs remis comme avant)." : ".")));
            }
            default -> s.sendMessage(mm(msg("prefix") + "<red>Commande inconnue."));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> out = new ArrayList<>();
        boolean admin = s.hasPermission("smpparkour.admin");
        if (a.length == 1) {
            out.addAll(List.of("liste", "rejoindre", "checkpoint", "recommencer", "quitter", "top"));
            if (admin) out.addAll(List.of("generer", "creer", "depart", "checkpoint-ajouter", "arrivee", "classement", "supprimer", "reload"));
        } else if (a.length == 2 && !a[0].equalsIgnoreCase("generer") && !a[0].equalsIgnoreCase("creer")) {
            for (Course co : courses.values()) out.add(co.name);
        } else if (a.length == 3 && a[0].equalsIgnoreCase("generer")) {
            out.addAll(List.of("20", "30", "50", "100"));
        } else if (a.length == 4 && a[0].equalsIgnoreCase("generer")) {
            out.addAll(Generator.difficulties());
        } else if (a.length == 5 && a[0].equalsIgnoreCase("generer")) {
            out.addAll(Generator.THEMES.keySet());
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
