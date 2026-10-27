package fr.smpzones;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Protection de zones, en français. Une zone = une boîte (ou tout un monde) avec des permissions
 * (casser, poser, PvP, dégâts, monstres, explosions, feu, seaux, perles...). Les zones se superposent :
 * la plus prioritaire qui règle une permission décide. Les membres d'une zone peuvent y construire.
 * En plus : anti-flood, aucun seau ne peut être vidé dans un rayon autour du spawn.
 */
public final class SmpZones extends JavaPlugin implements Listener {

    static final MiniMessage MM = MiniMessage.miniMessage();

    private final Map<String, Zone> zones = new LinkedHashMap<>();
    private final Map<UUID, Location[]> sel = new HashMap<>();
    /** op qui ont coupé leur contournement des protections (/zone bypass) */
    private final Set<UUID> noBypass = new HashSet<>();
    /** zones où est chaque joueur (messages d'entrée / sortie) */
    private final Map<UUID, Set<String>> inside = new HashMap<>();
    private NamespacedKey wandKey;
    private File file;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        wandKey = new NamespacedKey(this, "wand");
        file = new File(getDataFolder(), "zones.yml");
        load();
        getServer().getPluginManager().registerEvents(new ZoneListener(this), this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info(zones.size() + " zone(s) chargée(s).");
    }

    @Override
    public void onDisable() {
        save();
    }

    static Component parse(String s) {
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    String msg(String key, String def) {
        return getConfig().getString("messages." + key, def);
    }

    private void send(CommandSender s, String m) {
        s.sendMessage(parse("<dark_green>[Zones]</dark_green> " + m));
    }

    // ================================================================= décision

    boolean bypass(Player p) {
        return p != null && p.hasPermission("smpzones.bypass") && !noBypass.contains(p.getUniqueId());
    }

    /** La zone qui décide de cette permission ici (la plus prioritaire qui la règle), ou null. */
    private Zone decider(Location l, Flag f) {
        Zone best = null;
        for (Zone z : zones.values()) {
            if (!z.flags.containsKey(f) || !z.contains(l)) continue;
            if (best == null || z.priority > best.priority || (z.priority == best.priority && z.volume() < best.volume())) best = z;
        }
        return best;
    }

    /** p = le joueur concerné (null = la nature : feu, eau, monstres...). */
    boolean allowed(Player p, Location l, Flag f) {
        if (l == null) return true;
        if (bypass(p)) return true;
        Zone z = decider(l, f);
        if (z == null || z.flags.get(f)) return true;
        return f.memberOk && p != null && z.members.containsKey(p.getUniqueId());
    }

    /** true seulement si une zone AUTORISE explicitement cette permission ici. */
    boolean allowedExplicit(Location l, Flag f) {
        Zone z = decider(l, f);
        return z != null && z.flags.get(f);
    }

    int floodRadius() {
        return getConfig().getInt("anti-flood.radius", 1000);
    }

    /** Dans le rayon protégé autour du spawn (mondes concernés), sans tenir compte des zones ni des joueurs. */
    boolean inSpawnRadius(Location l) {
        if (!getConfig().getBoolean("anti-flood.enabled", true) || l == null || l.getWorld() == null) return false;
        List<String> worlds = getConfig().getStringList("anti-flood.worlds");
        if (!worlds.isEmpty() && !worlds.contains(l.getWorld().getName())) return false;
        Location c = center(l.getWorld());
        double dx = l.getX() - c.getX(), dz = l.getZ() - c.getZ(), r = floodRadius();
        return dx * dx + dz * dz <= r * r;
    }

    /** true = vider un seau est interdit ici (rayon autour du spawn), sauf si une zone autorise les seaux. */
    boolean antiFlood(Player p, Location l) {
        if (!getConfig().getBoolean("anti-flood.enabled", true) || l == null || l.getWorld() == null || bypass(p)) return false;
        List<String> worlds = getConfig().getStringList("anti-flood.worlds");
        if (!worlds.isEmpty() && !worlds.contains(l.getWorld().getName())) return false;
        Zone z = decider(l, Flag.SEAUX);
        if (z != null && z.flags.get(Flag.SEAUX)) return false; // une zone autorise les seaux ici (fermes...)
        Location c = center(l.getWorld());
        double dx = l.getX() - c.getX(), dz = l.getZ() - c.getZ();
        double r = floodRadius();
        return dx * dx + dz * dz <= r * r;
    }

    private Location center(World w) {
        ConfigurationSection c = getConfig().getConfigurationSection("anti-flood.center");
        if (c != null && c.contains("x") && c.contains("z")) return new Location(w, c.getDouble("x"), 0, c.getDouble("z"));
        return w.getSpawnLocation();
    }

    // ================================================================= messages d'entrée / sortie

    void moved(Player p, Location to) {
        Set<String> now = new HashSet<>();
        for (Zone z : zones.values()) if (!z.global && z.contains(to)) now.add(z.name);
        Set<String> before = inside.getOrDefault(p.getUniqueId(), Set.of());
        for (String n : now) {
            if (before.contains(n)) continue;
            Zone z = zones.get(n.toLowerCase(Locale.ROOT));
            if (z != null && z.enterMsg != null && !z.enterMsg.isBlank()) p.sendActionBar(parse(z.enterMsg));
        }
        for (String n : before) {
            if (now.contains(n)) continue;
            Zone z = zones.get(n.toLowerCase(Locale.ROOT));
            if (z != null && z.leaveMsg != null && !z.leaveMsg.isBlank()) p.sendActionBar(parse(z.leaveMsg));
        }
        inside.put(p.getUniqueId(), now);
    }

    void forgetPlayer(Player p) {
        inside.remove(p.getUniqueId());
        sel.remove(p.getUniqueId());
    }

    // ================================================================= sauvegarde

    private void load() {
        zones.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String name : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(name);
            if (s == null) continue;
            Zone z = new Zone(name);
            z.world = s.getString("world");
            z.global = s.getBoolean("global");
            List<Integer> b = s.getIntegerList("box");
            if (b.size() == 6) z.box = new int[]{b.get(0), b.get(1), b.get(2), b.get(3), b.get(4), b.get(5)};
            if (!z.global && z.box == null) continue;
            z.priority = s.getInt("priority");
            ConfigurationSection f = s.getConfigurationSection("flags");
            if (f != null) for (String k : f.getKeys(false)) {
                Flag fl = Flag.of(k);
                if (fl != null) z.flags.put(fl, f.getBoolean(k));
            }
            ConfigurationSection m = s.getConfigurationSection("members");
            if (m != null) for (String k : m.getKeys(false)) {
                try {
                    z.members.put(UUID.fromString(k), m.getString(k, "?"));
                } catch (IllegalArgumentException ignored) {
                }
            }
            z.enterMsg = s.getString("enter-message");
            z.leaveMsg = s.getString("leave-message");
            zones.put(name.toLowerCase(Locale.ROOT), z);
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Zone z : zones.values()) {
            String b = z.name + ".";
            y.set(b + "world", z.world);
            if (z.global) y.set(b + "global", true);
            else y.set(b + "box", List.of(z.box[0], z.box[1], z.box[2], z.box[3], z.box[4], z.box[5]));
            y.set(b + "priority", z.priority);
            for (Map.Entry<Flag, Boolean> f : z.flags.entrySet()) y.set(b + "flags." + f.getKey().key, f.getValue());
            for (Map.Entry<UUID, String> m : z.members.entrySet()) y.set(b + "members." + m.getKey(), m.getValue());
            if (z.enterMsg != null) y.set(b + "enter-message", z.enterMsg);
            if (z.leaveMsg != null) y.set(b + "leave-message", z.leaveMsg);
        }
        try {
            getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            getLogger().warning("Impossible d'enregistrer zones.yml : " + e.getMessage());
        }
    }

    // ================================================================= baguette

    private ItemStack wand() {
        ItemStack it = new ItemStack(Material.GOLDEN_AXE);
        ItemMeta m = it.getItemMeta();
        m.displayName(parse("<gold><b>Baguette des zones"));
        m.lore(List.of(parse("<gray>Clic gauche : coin 1"), parse("<gray>Clic droit : coin 2"), parse("<gray>puis <white>/zone creer <nom>")));
        m.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        m.setEnchantmentGlintOverride(true);
        m.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(m);
        return it;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWand(PlayerInteractEvent e) {
        ItemStack it = e.getItem();
        if (it == null || !it.hasItemMeta() || !it.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE)) return;
        if (e.getClickedBlock() == null || !e.getPlayer().hasPermission("smpzones.admin")) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        int i = e.getAction() == Action.LEFT_CLICK_BLOCK ? 0 : e.getAction() == Action.RIGHT_CLICK_BLOCK ? 1 : -1;
        if (i < 0) return;
        setPos(e.getPlayer(), i, e.getClickedBlock().getLocation());
    }

    private void setPos(Player p, int i, Location l) {
        Location[] c = sel.computeIfAbsent(p.getUniqueId(), k -> new Location[2]);
        c[i] = l.getBlock().getLocation();
        send(p, "<green>Coin " + (i + 1) + "</green> <gray>= <white>" + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ());
        if (c[0] != null && c[1] != null && c[0].getWorld() == c[1].getWorld()) {
            int[] b = box(c);
            send(p, "<gray>Sélection : <white>" + (b[3] - b[0] + 1) + "×" + (b[4] - b[1] + 1) + "×" + (b[5] - b[2] + 1)
                    + "</white> · <white>/zone creer <nom></white> <gray>(ajoute <white>haut</white> pour toute la hauteur)");
            outline(p, c[0].getWorld(), b, 100);
        }
    }

    private static int[] box(Location[] c) {
        return new int[]{Math.min(c[0].getBlockX(), c[1].getBlockX()), Math.min(c[0].getBlockY(), c[1].getBlockY()),
                Math.min(c[0].getBlockZ(), c[1].getBlockZ()), Math.max(c[0].getBlockX(), c[1].getBlockX()),
                Math.max(c[0].getBlockY(), c[1].getBlockY()), Math.max(c[0].getBlockZ(), c[1].getBlockZ())};
    }

    /** Contour en particules, visible par le joueur seul. */
    private void outline(Player p, World w, int[] b, int ticks) {
        Particle.DustOptions d = new Particle.DustOptions(Color.fromRGB(80, 255, 120), 1.2f);
        int[] t = {0};
        org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(this, () -> {
            t[0] += 10;
            if (t[0] > ticks || !p.isOnline() || p.getWorld() != w) {
                task[0].cancel();
                return;
            }
            double x0 = b[0], y0 = b[1], z0 = b[2], x1 = b[3] + 1, y1 = b[4] + 1, z1 = b[5] + 1;
            double step = Math.max(1, Math.max(x1 - x0, Math.max(y1 - y0, z1 - z0)) / 60.0);
            for (double x = x0; x <= x1; x += step) {
                for (double[] yz : new double[][]{{y0, z0}, {y0, z1}, {y1, z0}, {y1, z1}}) p.spawnParticle(Particle.DUST, x, yz[0], yz[1], 1, 0, 0, 0, 0, d);
            }
            for (double y = y0; y <= y1; y += step) {
                for (double[] xz : new double[][]{{x0, z0}, {x0, z1}, {x1, z0}, {x1, z1}}) p.spawnParticle(Particle.DUST, xz[0], y, xz[1], 1, 0, 0, 0, 0, d);
            }
            for (double z = z0; z <= z1; z += step) {
                for (double[] xy : new double[][]{{x0, y0}, {x0, y1}, {x1, y0}, {x1, y1}}) p.spawnParticle(Particle.DUST, xy[0], xy[1], z, 1, 0, 0, 0, 0, d);
            }
        }, 0L, 10L);
    }

    // ================================================================= menu des permissions

    static final class FlagMenu implements InventoryHolder {
        final Zone zone;
        Inventory inv;

        FlagMenu(Zone zone) {
            this.zone = zone;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private void openFlags(Player p, Zone z) {
        FlagMenu m = new FlagMenu(z);
        m.inv = Bukkit.createInventory(m, 54, parse("<dark_green>Zone <white>" + MM.escapeTags(z.name)));
        fillFlags(m);
        p.openInventory(m.inv);
    }

    private void fillFlags(FlagMenu m) {
        Zone z = m.zone;
        m.inv.clear();
        Flag[] all = Flag.values();
        for (int i = 0; i < all.length && i < 45; i++) {
            Flag f = all[i];
            Boolean v = z.flags.get(f);
            ItemStack it = new ItemStack(f.icon);
            ItemMeta meta = it.getItemMeta();
            String state = v == null ? "<gray>● par défaut <dark_gray>(autorisé)" : v ? "<green>✔ autorisé" : "<red>✖ interdit";
            meta.displayName(parse((v == null ? "<white>" : v ? "<green>" : "<red>") + "<b>" + f.key));
            List<Component> lore = new ArrayList<>();
            lore.add(parse("<gray>" + f.description));
            lore.add(Component.empty());
            lore.add(parse(state));
            if (f.memberOk) lore.add(parse("<dark_gray>Les membres de la zone ne sont pas concernés"));
            lore.add(Component.empty());
            lore.add(parse("<yellow>▶ Clic : changer"));
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
            if (v != null && !v) meta.setEnchantmentGlintOverride(true);
            it.setItemMeta(meta);
            m.inv.setItem(i, it);
        }
        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta im = info.getItemMeta();
        im.displayName(parse("<gold><b>" + MM.escapeTags(z.name)));
        im.lore(List.of(parse("<gray>Taille : <white>" + z.size()), parse("<gray>Priorité : <white>" + z.priority),
                parse("<gray>Membres : <white>" + z.members.size())));
        info.setItemMeta(im);
        m.inv.setItem(49, info);
        ItemStack deny = new ItemStack(Material.RED_CONCRETE);
        ItemMeta dm = deny.getItemMeta();
        dm.displayName(parse("<red><b>Tout interdire <gray>(spawn protégé)"));
        dm.lore(List.of(parse("<gray>Casser, poser, PvP, dégâts, monstres, explosions,"), parse("<gray>feu, seaux, liquides, mobs qui cassent...")));
        deny.setItemMeta(dm);
        m.inv.setItem(47, deny);
        ItemStack reset = new ItemStack(Material.BARRIER);
        ItemMeta rm = reset.getItemMeta();
        rm.displayName(parse("<white><b>Tout remettre par défaut"));
        reset.setItemMeta(rm);
        m.inv.setItem(51, reset);
    }

    /** Préréglage "spawn" : tout ce qui casse, blesse ou abîme est interdit. */
    private static void protectAll(Zone z) {
        for (Flag f : new Flag[]{Flag.CASSER, Flag.POSER, Flag.COFFRES, Flag.ENTITES, Flag.SEAUX, Flag.VEHICULES, Flag.PVP, Flag.DEGATS,
                Flag.CHUTE, Flag.FAIM, Flag.ATTAQUE_MONSTRES, Flag.MONSTRES, Flag.MOBS_CASSENT, Flag.EXPLOSIONS, Flag.FEU, Flag.LIQUIDES,
                Flag.PIETINEMENT, Flag.FEUILLES}) z.flags.put(f, false);
    }

    @EventHandler
    public void onMenu(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof FlagMenu m) || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != m.inv || !p.hasPermission("smpzones.admin")) return;
        int slot = e.getRawSlot();
        Flag[] all = Flag.values();
        if (slot >= 0 && slot < all.length) {
            Flag f = all[slot];
            Boolean v = m.zone.flags.get(f);
            // défaut -> interdit -> autorisé -> défaut
            if (v == null) m.zone.flags.put(f, false);
            else if (!v) m.zone.flags.put(f, true);
            else m.zone.flags.remove(f);
        } else if (slot == 47) {
            protectAll(m.zone);
        } else if (slot == 51) {
            m.zone.flags.clear();
        } else return;
        save();
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
        fillFlags(m);
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof FlagMenu) e.setCancelled(true);
    }

    // ================================================================= commande

    private Zone zone(CommandSender s, String name) {
        Zone z = name == null ? null : zones.get(name.toLowerCase(Locale.ROOT));
        if (z == null) send(s, "<red>Zone inconnue. <gray>/zone liste");
        return z;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        Player p = s instanceof Player pl ? pl : null;
        switch (sub) {
            case "baguette", "wand" -> {
                if (p != null) p.getInventory().addItem(wand());
                send(s, "<gray>Clic gauche = coin 1, clic droit = coin 2.");
            }
            case "pos1", "pos2" -> {
                if (p != null) setPos(p, sub.equals("pos1") ? 0 : 1, p.getLocation());
            }
            case "creer", "create", "redefinir", "redefine" -> {
                if (p == null || a.length < 2) {
                    send(s, "<red>/zone " + sub + " <nom> [haut]");
                    return true;
                }
                Location[] c = sel.get(p.getUniqueId());
                if (c == null || c[0] == null || c[1] == null || c[0].getWorld() != c[1].getWorld()) {
                    send(s, "<red>Choisis d'abord les deux coins : <white>/zone baguette</white> ou <white>/zone pos1</white> / <white>pos2</white>.");
                    return true;
                }
                boolean redefine = sub.startsWith("redef");
                Zone z = zones.get(a[1].toLowerCase(Locale.ROOT));
                if (!redefine && z != null) {
                    send(s, "<red>Cette zone existe déjà. <gray>/zone redefinir " + a[1]);
                    return true;
                }
                if (redefine && z == null) {
                    send(s, "<red>Zone inconnue.");
                    return true;
                }
                if (a[1].startsWith("__")) {
                    send(s, "<red>Nom réservé.");
                    return true;
                }
                if (z == null) {
                    z = new Zone(a[1]);
                    zones.put(a[1].toLowerCase(Locale.ROOT), z);
                }
                z.world = c[0].getWorld().getName();
                z.global = false;
                z.box = box(c);
                if (a.length > 2 && a[2].equalsIgnoreCase("haut")) {
                    z.box[1] = c[0].getWorld().getMinHeight();
                    z.box[4] = c[0].getWorld().getMaxHeight() - 1;
                }
                save();
                send(s, "<green>Zone <white>" + MM.escapeTags(z.name) + "</white> " + (redefine ? "redéfinie" : "créée") + " <gray>(" + z.size()
                        + "). Règle ses permissions : <white>/zone flags " + z.name);
                if (!redefine) openFlags(p, z);
            }
            case "monde", "world", "global" -> {
                if (p == null) return true;
                String name = "__monde_" + p.getWorld().getName();
                Zone z = zones.get(name.toLowerCase(Locale.ROOT));
                if (z == null) {
                    z = new Zone(name);
                    z.world = p.getWorld().getName();
                    z.global = true;
                    z.priority = -1;
                    zones.put(name.toLowerCase(Locale.ROOT), z);
                    save();
                }
                openFlags(p, z);
            }
            case "flags", "flag", "perms", "permissions" -> {
                if (a.length >= 4) {
                    // /zone flag <zone> <permission> <autoriser|interdire|defaut>
                    Zone z = zone(s, a[1]);
                    Flag f = Flag.of(a[2]);
                    if (z == null) return true;
                    if (f == null) {
                        send(s, "<red>Permission inconnue. <gray>Liste : " + String.join(", ", flagKeys()));
                        return true;
                    }
                    String v = a[3].toLowerCase(Locale.ROOT);
                    if (List.of("allow", "autoriser", "oui", "true", "on").contains(v)) z.flags.put(f, true);
                    else if (List.of("deny", "interdire", "non", "false", "off").contains(v)) z.flags.put(f, false);
                    else z.flags.remove(f);
                    save();
                    send(s, "<green>" + f.key + "</green> <gray>=</gray> " + (z.flags.containsKey(f) ? (z.flags.get(f) ? "<green>autorisé" : "<red>interdit") : "<gray>par défaut"));
                    return true;
                }
                Zone z = a.length > 1 ? zone(s, a[1]) : null;
                if (z == null && a.length <= 1 && p != null) z = here(p);
                if (z == null) {
                    if (a.length <= 1) send(s, "<red>/zone flags <zone>");
                    return true;
                }
                if (p != null) openFlags(p, z);
            }
            case "proteger", "protéger", "protect" -> {
                Zone z = a.length > 1 ? zone(s, a[1]) : null;
                if (z == null) return true;
                protectAll(z);
                save();
                send(s, "<green>Zone <white>" + MM.escapeTags(z.name) + "</white> entièrement protégée.</green> <gray>(/zone flags " + z.name + " pour affiner)");
            }
            case "supprimer", "delete", "remove" -> {
                Zone z = a.length > 1 ? zone(s, a[1]) : null;
                if (z == null) return true;
                zones.remove(z.name.toLowerCase(Locale.ROOT));
                save();
                send(s, "<yellow>Zone supprimée.");
            }
            case "liste", "list" -> {
                if (zones.isEmpty()) send(s, "<gray>Aucune zone. <white>/zone baguette</white> pour commencer.");
                List<Zone> l = new ArrayList<>(zones.values());
                l.sort(Comparator.comparingInt((Zone z) -> -z.priority).thenComparing(z -> z.name));
                for (Zone z : l) {
                    long denied = z.flags.values().stream().filter(v -> !v).count();
                    send(s, "<gold>• <white>" + MM.escapeTags(z.name) + "</white> <gray>" + z.world + " · " + z.size() + " · priorité " + z.priority
                            + " · <red>" + denied + "</red> interdit(s)");
                }
            }
            case "info", "ici", "here" -> {
                if (p == null) return true;
                List<Zone> l = new ArrayList<>();
                for (Zone z : zones.values()) if (z.contains(p.getLocation())) l.add(z);
                if (l.isEmpty()) send(s, "<gray>Aucune zone ici.");
                for (Zone z : l) {
                    List<String> denied = new ArrayList<>();
                    for (Map.Entry<Flag, Boolean> f : z.flags.entrySet()) if (!f.getValue()) denied.add(f.getKey().key);
                    send(s, "<gold>" + MM.escapeTags(z.name) + "</gold> <gray>(priorité " + z.priority + ") interdit : <red>"
                            + (denied.isEmpty() ? "rien" : String.join(", ", denied)));
                }
                if (antiFlood(null, p.getLocation())) send(s, "<gray>Anti-flood : <red>seaux interdits ici</red> (rayon " + floodRadius() + " autour du spawn)");
            }
            case "voir", "show" -> {
                if (p == null) return true;
                Zone z = a.length > 1 ? zone(s, a[1]) : here(p);
                if (z == null || z.global) {
                    if (a.length <= 1) send(s, "<red>/zone voir <zone>");
                    return true;
                }
                World w = Bukkit.getWorld(z.world);
                if (w != null) outline(p, w, z.box, 300);
            }
            case "priorite", "priorité", "priority" -> {
                Zone z = a.length > 2 ? zone(s, a[1]) : null;
                if (z == null) {
                    if (a.length <= 2) send(s, "<red>/zone priorite <zone> <nombre>");
                    return true;
                }
                try {
                    z.priority = Integer.parseInt(a[2]);
                } catch (NumberFormatException ex) {
                    send(s, "<red>Nombre invalide.");
                    return true;
                }
                save();
                send(s, "<green>Priorité de " + MM.escapeTags(z.name) + " : " + z.priority);
            }
            case "membre", "member" -> {
                // /zone membre <zone> <ajouter|retirer> <joueur>
                Zone z = a.length > 3 ? zone(s, a[1]) : null;
                if (z == null) {
                    if (a.length <= 3) send(s, "<red>/zone membre <zone> <ajouter|retirer> <joueur>");
                    return true;
                }
                OfflinePlayer o = Bukkit.getOfflinePlayer(a[3]);
                if (a[2].toLowerCase(Locale.ROOT).startsWith("a")) z.members.put(o.getUniqueId(), o.getName() == null ? a[3] : o.getName());
                else z.members.remove(o.getUniqueId());
                save();
                send(s, "<green>Membres de " + MM.escapeTags(z.name) + " : <white>" + String.join(", ", z.members.values()));
            }
            case "message" -> {
                // /zone message <zone> <entree|sortie> <texte...>
                Zone z = a.length > 2 ? zone(s, a[1]) : null;
                if (z == null) {
                    if (a.length <= 2) send(s, "<red>/zone message <zone> <entree|sortie> <texte> <gray>(sans texte = retirer)");
                    return true;
                }
                String text = a.length > 3 ? String.join(" ", java.util.Arrays.copyOfRange(a, 3, a.length)) : null;
                if (a[2].toLowerCase(Locale.ROOT).startsWith("e")) z.enterMsg = text;
                else z.leaveMsg = text;
                save();
                send(s, "<green>Message enregistré.");
            }
            case "deplacer", "déplacer", "move" -> {
                // /zone deplacer <zone|tout> <x> <y> <z>
                if (a.length < 5) {
                    send(s, "<red>/zone deplacer <zone|tout> <x> <y> <z> <gray>(ex. les chiffres donnés par /ile)");
                    return true;
                }
                int dx, dy, dz;
                try {
                    dx = Integer.parseInt(a[2]);
                    dy = Integer.parseInt(a[3]);
                    dz = Integer.parseInt(a[4]);
                } catch (NumberFormatException ex) {
                    send(s, "<red>Nombres invalides.");
                    return true;
                }
                List<Zone> l = new ArrayList<>();
                if (a[1].equalsIgnoreCase("tout") || a[1].equalsIgnoreCase("all")) l.addAll(zones.values());
                else {
                    Zone z = zone(s, a[1]);
                    if (z == null) return true;
                    l.add(z);
                }
                int n = 0;
                for (Zone z : l) {
                    if (z.global) continue;
                    z.box = new int[]{z.box[0] + dx, z.box[1] + dy, z.box[2] + dz, z.box[3] + dx, z.box[4] + dy, z.box[5] + dz};
                    n++;
                }
                save();
                send(s, "<green>" + n + " zone(s) déplacée(s).");
            }
            case "bypass" -> {
                if (p == null) return true;
                if (!noBypass.remove(p.getUniqueId())) {
                    noBypass.add(p.getUniqueId());
                    send(s, "<yellow>Contournement coupé : <white>les protections s'appliquent à toi</white> (pour tester).");
                } else send(s, "<green>Contournement remis : tu ignores les protections.");
            }
            case "reload" -> {
                reloadConfig();
                load();
                send(s, "<green>Rechargé (" + zones.size() + " zones).");
            }
            default -> help(s);
        }
        return true;
    }

    private Zone here(Player p) {
        Zone best = null;
        for (Zone z : zones.values()) {
            if (z.global || !z.contains(p.getLocation())) continue;
            if (best == null || z.priority > best.priority || (z.priority == best.priority && z.volume() < best.volume())) best = z;
        }
        return best;
    }

    private static List<String> flagKeys() {
        List<String> l = new ArrayList<>();
        for (Flag f : Flag.values()) l.add(f.key);
        return l;
    }

    private void help(CommandSender s) {
        send(s, "<gold><b>Zones protégées</b>");
        s.sendMessage(parse("<white>/zone baguette</white> <gray>· clic gauche / droit = les 2 coins (ou <white>/zone pos1</white>, <white>pos2</white>)"));
        s.sendMessage(parse("<white>/zone creer <nom> [haut]</white> <gray>· crée la zone (<white>haut</white> = toute la hauteur du monde)"));
        s.sendMessage(parse("<white>/zone flags <nom></white> <gray>· menu des permissions (clic = défaut → interdit → autorisé)"));
        s.sendMessage(parse("<white>/zone proteger <nom></white> <gray>· tout interdire d'un coup (spawn)"));
        s.sendMessage(parse("<white>/zone monde</white> <gray>· permissions de TOUT le monde (ex. PvP partout)"));
        s.sendMessage(parse("<white>/zone membre <nom> ajouter|retirer <joueur></white> <gray>· peut construire dedans"));
        s.sendMessage(parse("<white>/zone priorite <nom> <n></white> <gray>· la plus haute gagne quand les zones se chevauchent"));
        s.sendMessage(parse("<white>/zone message <nom> entree|sortie <texte></white> <gray>· message en entrant / sortant"));
        s.sendMessage(parse("<white>/zone info</white> <gray>·</gray> <white>/zone voir <nom></white> <gray>·</gray> <white>/zone liste</white> <gray>·</gray> <white>/zone supprimer <nom>"));
        s.sendMessage(parse("<white>/zone deplacer <nom|tout> <x> <y> <z></white> <gray>·</gray> <white>/zone bypass</white> <gray>(tester comme un joueur)"));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(List.of("baguette", "pos1", "pos2", "creer", "redefinir", "flags", "proteger", "monde", "liste", "info", "voir",
                    "priorite", "membre", "message", "deplacer", "supprimer", "bypass", "reload"));
        } else if (a.length == 2 && !a[0].equalsIgnoreCase("creer")) {
            for (Zone z : zones.values()) out.add(z.name);
            if (a[0].equalsIgnoreCase("deplacer")) out.add("tout");
        } else if (a.length == 3 && a[0].equalsIgnoreCase("creer")) {
            out.add("haut");
        } else if (a.length == 3 && a[0].toLowerCase(Locale.ROOT).startsWith("flag")) {
            out.addAll(flagKeys());
        } else if (a.length == 4 && a[0].toLowerCase(Locale.ROOT).startsWith("flag")) {
            out.addAll(List.of("interdire", "autoriser", "defaut"));
        } else if (a.length == 3 && a[0].equalsIgnoreCase("membre")) {
            out.addAll(List.of("ajouter", "retirer"));
        } else if (a.length == 3 && a[0].equalsIgnoreCase("message")) {
            out.addAll(List.of("entree", "sortie"));
        } else if (a.length == 4 && a[0].equalsIgnoreCase("membre")) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
