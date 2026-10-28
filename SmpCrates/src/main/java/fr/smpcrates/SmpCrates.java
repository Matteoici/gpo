package fr.smpcrates;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

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
import java.util.concurrent.ThreadLocalRandom;

/**
 * Caisses Rare / Épique / Légendaire / Mythique, payées avec l'argent du jeu (ou avec une clé).
 * - Posées dans le monde : effets tout autour, nom et prix qui flottent, la caisse qui tourne au-dessus.
 * - Clic : la liste des lots avec leur pourcentage, puis ouverture en roulette (comme CS:GO) qui ralentit
 *   et s'arrête sur le lot gagné.
 */
public final class SmpCrates extends JavaPlugin implements Listener {

    static final MiniMessage MM = MiniMessage.miniMessage();

    /** Un lot. */
    record Reward(int weight, ItemStack item, double money, String command, String name, Material icon, boolean broadcast, int idx) {
    }

    /** Une caisse. */
    static final class Crate {
        final String id;
        String name, color;
        double price;
        Material icon, pane;
        /** ouvrable seulement avec une clé (caisse boutique / premium) */
        boolean keysOnly;
        /** effets autour de la caisse : 0 rare … 3 mythique, -1 = d'après le prix */
        int tier = -1;
        final List<Reward> rewards = new ArrayList<>();
        int total;

        Crate(String id) {
            this.id = id;
        }

        Reward roll() {
            int r = ThreadLocalRandom.current().nextInt(Math.max(1, total));
            for (Reward w : rewards) {
                r -= w.weight();
                if (r < 0) return w;
            }
            return rewards.get(rewards.size() - 1);
        }

        double chance(Reward r) {
            return total <= 0 ? 0 : r.weight() * 100.0 / total;
        }
    }

    private final Map<String, Crate> crates = new LinkedHashMap<>();
    /** "monde;x;y;z" -> caisse */
    private final Map<String, String> placed = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Integer>> keys = new HashMap<>();
    private final Map<String, List<Entity>> shows = new HashMap<>();
    private final Set<UUID> opening = new HashSet<>();
    private NamespacedKey tag;
    private File dataFile;
    private Object economy;
    private int clock;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getConfig().getInt("config-version", 1) < 2) {
            // nouvelle caisse Premium (blanche), copiée depuis le config du plugin si elle manque
            var def = getConfig().getDefaults();
            if (def != null && !getConfig().isSet("crates.premium") && def.isConfigurationSection("crates.premium")) {
                for (String k : def.getConfigurationSection("crates.premium").getKeys(true))
                    if (!def.isConfigurationSection("crates.premium." + k)) getConfig().set("crates.premium." + k, def.get("crates.premium." + k));
            }
            List<String> bc = new ArrayList<>(getConfig().getStringList("broadcast-crates"));
            if (!bc.contains("premium")) bc.add("premium");
            getConfig().set("broadcast-crates", bc);
            getConfig().set("config-version", 2);
            saveConfig();
        }
        tag = new NamespacedKey(this, "crate");
        dataFile = new File(getDataFolder(), "data.yml");
        loadCrates();
        loadData();
        hookVault();
        getServer().getPluginManager().registerEvents(this, this);
        // nettoyage des restes d'un ancien démarrage
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) if (e.getPersistentDataContainer().has(tag, PersistentDataType.STRING)) e.remove();
        }
        Bukkit.getScheduler().runTaskTimer(this, this::ambience, 20L, 2L);
        getLogger().info(crates.size() + " caisse(s), " + placed.size() + " posée(s).");
    }

    @Override
    public void onDisable() {
        for (List<Entity> l : shows.values()) for (Entity e : l) e.remove();
        shows.clear();
        saveData();
    }

    static Component parse(String s) {
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private String msg(String key) {
        return getConfig().getString("messages." + key, "<red>[" + key + "]");
    }

    private void send(CommandSender s, String body) {
        s.sendMessage(parse(msg("prefix") + body));
    }

    // ================================================================= économie (Vault)

    private void hookVault() {
        try {
            RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> rsp =
                    Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
            economy = rsp == null ? null : rsp.getProvider();
        } catch (Throwable t) {
            economy = null;
        }
        if (economy == null) getLogger().warning("Pas d'économie Vault : on ne pourra ouvrir les caisses qu'avec des clés.");
    }

    private net.milkbowl.vault.economy.Economy eco() {
        if (economy == null) hookVault();
        return (net.milkbowl.vault.economy.Economy) economy;
    }

    private String money(double v) {
        String sym = getConfig().getString("currency", "$");
        if (v >= 1_000_000) return trim(v / 1_000_000) + "M " + sym;
        if (v >= 10_000) return trim(v / 1000) + "k " + sym;
        return String.format(Locale.FRANCE, "%,.0f", v) + " " + sym;
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 10) / 10.0);
    }

    // ================================================================= chargement

    private void loadCrates() {
        crates.clear();
        ConfigurationSection root = getConfig().getConfigurationSection("crates");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection c = root.getConfigurationSection(id);
            if (c == null) continue;
            Crate cr = new Crate(id.toLowerCase(Locale.ROOT));
            cr.name = c.getString("name", id);
            cr.price = c.getDouble("price", 10000);
            cr.color = c.getString("color", "#ffffff");
            Material ic = Material.matchMaterial(c.getString("icon", "CHEST"));
            cr.icon = ic == null ? Material.CHEST : ic;
            Material pane = Material.matchMaterial(c.getString("pane", paneFor(cr.icon)));
            cr.pane = pane == null ? Material.GRAY_STAINED_GLASS_PANE : pane;
            cr.keysOnly = c.getBoolean("keys-only", false);
            cr.tier = c.getInt("tier", -1);
            int idx = 0;
            for (Map<?, ?> m : c.getMapList("rewards")) {
                Reward r = reward(m, idx++);
                if (r != null) {
                    cr.rewards.add(r);
                    cr.total += r.weight();
                }
            }
            crates.put(cr.id, cr); // même vide : on peut la remplir avec /caisse editer
        }
    }

    private static String paneFor(Material icon) {
        String n = icon.name();
        int i = n.indexOf("_SHULKER_BOX");
        return i > 0 ? n.substring(0, i) + "_STAINED_GLASS_PANE" : "GRAY_STAINED_GLASS_PANE";
    }

    private Reward reward(Map<?, ?> m, int idx) {
        int weight = m.get("weight") instanceof Number n ? n.intValue() : 1;
        String name = m.get("name") == null ? null : String.valueOf(m.get("name"));
        Material icon = m.get("icon") == null ? null : Material.matchMaterial(String.valueOf(m.get("icon")));
        boolean bc = Boolean.TRUE.equals(m.get("broadcast"));
        ItemStack item = null;
        if (m.get("stack") != null) {
            try {
                item = ItemStack.deserializeBytes(Base64.getDecoder().decode(String.valueOf(m.get("stack"))));
            } catch (RuntimeException e) {
                getLogger().warning("Lot illisible (stack) ignoré.");
                return null;
            }
        } else if (m.get("item") != null) {
            Material mat = Material.matchMaterial(String.valueOf(m.get("item")));
            if (mat == null) {
                getLogger().warning("Matériau inconnu : " + m.get("item"));
                return null;
            }
            item = new ItemStack(mat, m.get("amount") instanceof Number n ? Math.max(1, n.intValue()) : 1);
            ItemMeta meta = item.getItemMeta();
            if (name != null) meta.displayName(parse(name));
            if (m.get("lore") instanceof List<?> l) {
                List<Component> lore = new ArrayList<>();
                for (Object o : l) lore.add(parse(String.valueOf(o)));
                meta.lore(lore);
            }
            if (m.get("enchants") instanceof Map<?, ?> en) {
                for (var e : en.entrySet()) {
                    Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(String.valueOf(e.getKey()).toLowerCase(Locale.ROOT)));
                    if (ench != null) meta.addEnchant(ench, e.getValue() instanceof Number n ? n.intValue() : 1, true);
                }
            }
            if (m.get("stored-enchants") instanceof Map<?, ?> en && meta instanceof EnchantmentStorageMeta sm) {
                for (var e : en.entrySet()) {
                    Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(String.valueOf(e.getKey()).toLowerCase(Locale.ROOT)));
                    if (ench != null) sm.addStoredEnchant(ench, e.getValue() instanceof Number n ? n.intValue() : 1, true);
                }
            }
            item.setItemMeta(meta);
        }
        double money = m.get("money") instanceof Number n ? n.doubleValue() : 0;
        String cmd = m.get("command") == null ? null : String.valueOf(m.get("command"));
        if (item == null && money <= 0 && cmd == null) return null;
        return new Reward(Math.max(1, weight), item, money, cmd, name, icon, bc, idx);
    }

    private void loadData() {
        placed.clear();
        keys.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        for (String s : y.getStringList("placed")) {
            int i = s.lastIndexOf(';');
            if (i > 0) placed.put(s.substring(0, i), s.substring(i + 1));
        }
        ConfigurationSection k = y.getConfigurationSection("keys");
        if (k != null) for (String id : k.getKeys(false)) {
            ConfigurationSection c = k.getConfigurationSection(id);
            if (c == null) continue;
            Map<String, Integer> m = new HashMap<>();
            for (String cr : c.getKeys(false)) m.put(cr, c.getInt(cr));
            try {
                keys.put(UUID.fromString(id), m);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        List<String> l = new ArrayList<>();
        for (var e : placed.entrySet()) l.add(e.getKey() + ";" + e.getValue());
        y.set("placed", l);
        for (var e : keys.entrySet()) for (var c : e.getValue().entrySet()) if (c.getValue() > 0) y.set("keys." + e.getKey() + "." + c.getKey(), c.getValue());
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Impossible d'enregistrer data.yml : " + e.getMessage());
        }
    }

    private int keysOf(UUID p, String crate) {
        return keys.getOrDefault(p, Map.of()).getOrDefault(crate, 0);
    }

    private void addKeys(UUID p, String crate, int n) {
        keys.computeIfAbsent(p, k -> new HashMap<>()).merge(crate, n, Integer::sum);
        saveData();
    }

    private static String key(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    private static Location loc(String k) {
        String[] p = k.split(";");
        World w = Bukkit.getWorld(p[0]);
        return w == null ? null : new Location(w, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
    }

    // ================================================================= caisses posées : effets

    private void ambience() {
        clock++;
        for (var e : placed.entrySet()) {
            Crate c = crates.get(e.getValue());
            Location b = loc(e.getKey());
            if (c == null || b == null || !b.getWorld().isChunkLoaded(b.getBlockX() >> 4, b.getBlockZ() >> 4)) continue;
            boolean near = false;
            for (Player p : b.getWorld().getPlayers()) if (p.getLocation().distanceSquared(b) < 48 * 48) near = true;
            if (!near) continue;
            ensureShow(e.getKey(), c, b);
            World w = b.getWorld();
            Location mid = b.clone().add(0.5, 0, 0.5);
            Color col = color(c.color);
            Particle.DustOptions dust = new Particle.DustOptions(col, 1.1f);
            // anneau qui tourne au pied de la caisse
            for (int k = 0; k < 3; k++) {
                double a = clock * 0.15 + Math.PI * 2 * k / 3;
                w.spawnParticle(Particle.DUST, mid.clone().add(Math.cos(a) * 0.85, 0.15 + (Math.sin(clock * 0.1 + k) + 1) * 0.5, Math.sin(a) * 0.85),
                        1, 0, 0, 0, 0, dust);
            }
            if (clock % 4 == 0) w.spawnParticle(Particle.END_ROD, mid.clone().add(0, 1.2, 0), 1, 0.35, 0.3, 0.35, 0.01);
            int tier = tier(c);
            if (tier >= 2 && clock % 3 == 0) w.spawnParticle(Particle.WAX_OFF, mid.clone().add(0, 1.6, 0), 1, 0.4, 0.3, 0.4, 0);
            if (tier >= 3) {
                if (clock % 2 == 0) w.spawnParticle(Particle.FLAME, mid.clone().add(Math.cos(clock * 0.2) * 0.6, 0.2, Math.sin(clock * 0.2) * 0.6), 1, 0, 0.02, 0, 0.005);
                if (clock % 10 == 0) w.spawnParticle(Particle.SOUL_FIRE_FLAME, mid.clone().add(0, 0.4, 0), 6, 0.4, 0.1, 0.4, 0.02);
            }
        }
    }

    /** 0 = rare, 1 = épique, 2 = légendaire, 3 = mythique (d'après le prix, du moins cher au plus cher). */
    private int tier(Crate c) {
        if (c.tier >= 0) return Math.min(3, c.tier);
        List<Crate> l = new ArrayList<>(crates.values());
        l.sort(java.util.Comparator.comparingDouble(x -> x.price));
        int i = l.indexOf(c);
        return Math.max(0, Math.min(3, l.size() <= 1 ? 0 : i * 3 / (l.size() - 1)));
    }

    private static Color color(String hex) {
        try {
            return Color.fromRGB(Integer.parseInt(hex.replace("#", ""), 16));
        } catch (RuntimeException e) {
            return Color.WHITE;
        }
    }

    private void ensureShow(String k, Crate c, Location b) {
        List<Entity> l = shows.get(k);
        if (l != null && l.stream().allMatch(Entity::isValid)) {
            // la caisse miniature tourne
            if (l.size() > 1 && l.get(1) instanceof ItemDisplay d) {
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(4);
                float s = 0.55f;
                d.setTransformation(new Transformation(new Vector3f(0, (float) Math.sin(clock * 0.08) * 0.06f, 0),
                        new Quaternionf().rotateY(clock * 0.09f), new Vector3f(s, s, s), new Quaternionf()));
            }
            return;
        }
        if (l != null) for (Entity e : l) e.remove();
        World w = b.getWorld();
        Location top = b.clone().add(0.5, 2.15, 0.5);
        TextDisplay text = w.spawn(top, TextDisplay.class, t -> {
            t.setPersistent(false);
            t.getPersistentDataContainer().set(tag, PersistentDataType.STRING, k);
            t.setBillboard(Display.Billboard.CENTER);
            t.setShadowed(true);
            t.setBackgroundColor(Color.fromARGB(110, 8, 6, 14));
            t.text(parse(c.name + "\n" + (c.keysOnly ? "<gray>🔑 Avec une clé" : "<gray>Prix : <gold>" + money(c.price)) + "\n<dark_gray>Clic : voir les lots et ouvrir"));
        });
        ItemDisplay item = w.spawn(b.clone().add(0.5, 1.45, 0.5), ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.getPersistentDataContainer().set(tag, PersistentDataType.STRING, k);
            d.setItemStack(new ItemStack(c.icon));
            d.setBrightness(new Display.Brightness(15, 15));
            d.setGlowing(true);
            d.setGlowColorOverride(color(c.color));
            d.setTeleportDuration(2);
        });
        // zone cliquable : du dessus du bloc jusqu'en haut de l'hologramme
        Interaction click = w.spawn(b.clone().add(0.5, 1.0, 0.5), Interaction.class, i -> {
            i.setPersistent(false);
            i.getPersistentDataContainer().set(tag, PersistentDataType.STRING, k);
            i.setInteractionWidth(1.3f);
            i.setInteractionHeight(2.2f);
            i.setResponsive(true);
        });
        shows.put(k, new ArrayList<>(List.of(text, item, click)));
    }

    private void removeShow(String k) {
        List<Entity> l = shows.remove(k);
        if (l != null) for (Entity e : l) e.remove();
    }

    // ================================================================= clic sur une caisse

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.LEFT_CLICK_BLOCK)) return;
        String id = placed.get(key(b));
        if (id == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Crate c = crates.get(id);
        if (c == null) return;
        Player p = e.getPlayer();
        if (e.getAction() == Action.LEFT_CLICK_BLOCK && p.isSneaking() && p.hasPermission("smpcrates.admin")) {
            send(p, "<gray>Caisse <white>" + c.id + "</white>. <white>/caisse retirer</white> en la regardant pour l'enlever.");
            return;
        }
        openPreview(p, c, b.getLocation());
    }

    /** Clic sur l'hologramme / la caisse qui tourne (zone cliquable au-dessus du bloc). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onClickHolo(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Interaction i)) return;
        String k = i.getPersistentDataContainer().get(tag, PersistentDataType.STRING);
        if (k == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Crate c = crates.get(placed.get(k));
        Location at = loc(k);
        if (c != null && at != null) openPreview(e.getPlayer(), c, at);
    }

    /** Clic gauche sur la zone cliquable : pareil. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onHitHolo(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Interaction i) || !(e.getDamager() instanceof Player p)) return;
        String k = i.getPersistentDataContainer().get(tag, PersistentDataType.STRING);
        if (k == null) return;
        e.setCancelled(true);
        Crate c = crates.get(placed.get(k));
        Location at = loc(k);
        if (c != null && at != null) openPreview(p, c, at);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (placed.containsKey(key(e.getBlock()))) {
            e.setCancelled(true);
            if (e.getPlayer().hasPermission("smpcrates.admin")) send(e.getPlayer(), "<gray>C'est une caisse : <white>/caisse retirer</white> en la regardant.");
        }
    }

    // ================================================================= menus

    static final class Menu implements InventoryHolder {
        final String kind; // "list", "preview", "spin"
        final Crate crate;
        final Location at;
        Inventory inv;
        Runnable finish;

        Menu(String kind, Crate crate, Location at) {
            this.kind = kind;
            this.crate = crate;
            this.at = at;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static ItemStack named(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(parse(name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(parse(s));
        meta.lore(l);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    /** Rareté d'un lot d'après sa chance. */
    private static String rarity(double pct) {
        if (pct < 1) return "<gradient:#ff1a1a:#ffd700><b>MYTHIQUE";
        if (pct < 3) return "<gold><b>LÉGENDAIRE";
        if (pct < 8) return "<#b45cff><b>ÉPIQUE";
        if (pct < 18) return "<#4aa8ff><b>RARE";
        return "<gray>COMMUN";
    }

    private static Material rarityPane(double pct) {
        if (pct < 1) return Material.RED_STAINED_GLASS_PANE;
        if (pct < 3) return Material.YELLOW_STAINED_GLASS_PANE;
        if (pct < 8) return Material.PURPLE_STAINED_GLASS_PANE;
        if (pct < 18) return Material.LIGHT_BLUE_STAINED_GLASS_PANE;
        return Material.LIGHT_GRAY_STAINED_GLASS_PANE;
    }

    private static String pct(double v) {
        if (v >= 10) return String.format(Locale.FRANCE, "%.0f", v) + " %";
        if (v >= 1) return String.format(Locale.FRANCE, "%.1f", v) + " %";
        return String.format(Locale.FRANCE, "%.2f", v) + " %";
    }

    /** L'icône d'un lot, avec sa chance et sa rareté sous sa description. */
    private ItemStack display(Crate c, Reward r, boolean details) {
        ItemStack it;
        if (r.item() != null) it = r.item().clone();
        else it = new ItemStack(r.icon() != null ? r.icon() : r.money() > 0 ? Material.GOLD_INGOT : Material.NETHER_STAR);
        ItemMeta meta = it.getItemMeta();
        if (r.item() == null || r.name() != null) {
            String n = r.name() != null ? r.name() : r.money() > 0 ? "<gold><b>" + money(r.money()) : "<white>Lot spécial";
            meta.displayName(parse(n));
        }
        if (details) {
            double p = c.chance(r);
            List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Component.empty());
            lore.add(parse(rarity(p)));
            lore.add(parse("<gray>Chance : <white><b>" + pct(p)));
            if (r.item() != null && r.item().getAmount() > 1) lore.add(parse("<gray>Quantité : <white>×" + r.item().getAmount()));
            if (r.money() > 0 && r.item() != null) lore.add(parse("<gray>+ <gold>" + money(r.money())));
            meta.lore(lore);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }

    void openList(Player p) {
        Menu m = new Menu("list", null, null);
        m.inv = Bukkit.createInventory(m, 27, parse("<dark_gray>✦ Caisses"));
        ItemStack bg = named(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 27; i++) m.inv.setItem(i, bg);
        int[] slots = listSlots();
        int i = 0;
        for (Crate c : crates.values()) {
            if (i >= slots.length) break;
            List<String> lore = new ArrayList<>();
            lore.add(c.keysOnly ? "<gray>🔑 Seulement avec une clé" : "<gray>Prix : <gold>" + money(c.price));
            int k = keysOf(p.getUniqueId(), c.id);
            if (k > 0) lore.add("<green>🔑 " + k + " clé(s)");
            lore.add("<gray>" + c.rewards.size() + " lots possibles");
            lore.add("");
            lore.add("<yellow>▶ Clic : voir les lots");
            ItemStack it = named(c.icon, c.name, lore);
            ItemMeta meta = it.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            it.setItemMeta(meta);
            m.inv.setItem(slots[i++], it);
        }
        p.openInventory(m.inv);
        p.playSound(p.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.6f, 1.2f);
    }

    private int[] listSlots() {
        int n = crates.size();
        if (n <= 4) return new int[]{10, 12, 14, 16};
        if (n <= 5) return new int[]{9, 11, 13, 15, 17};
        return new int[]{10, 11, 12, 13, 14, 15, 16};
    }

    void openPreview(Player p, Crate c, Location at) {
        Menu m = new Menu("preview", c, at);
        m.inv = Bukkit.createInventory(m, 54, parse(c.name));
        ItemStack bg = named(c.pane, " ", List.of());
        for (int i = 45; i < 54; i++) m.inv.setItem(i, bg);
        List<Reward> list = new ArrayList<>(c.rewards);
        list.sort(java.util.Comparator.comparingInt(Reward::weight)); // les plus rares d'abord
        for (int i = 0; i < Math.min(45, list.size()); i++) m.inv.setItem(i, display(c, list.get(i), true));
        int k = keysOf(p.getUniqueId(), c.id);
        double bal = eco() == null ? 0 : eco().getBalance(p);
        m.inv.setItem(48, named(Material.BOOK, "<white><b>Comment ça marche ?", List.of(
                "<gray>Chaque ouverture tire un lot au hasard.", "<gray>La chance de chaque lot est écrite", "<gray>sous son nom.")));
        m.inv.setItem(49, named(k > 0 ? Material.TRIPWIRE_HOOK : Material.EMERALD, k > 0 ? "<green><b>🔑 Ouvrir avec une clé" : "<green><b>Ouvrir",
                k > 0 ? List.of("<gray>Tu as <white>" + k + "</white> clé(s).", "", "<yellow>▶ Clic") :
                        c.keysOnly ? List.of("<gray>Cette caisse s'ouvre <white>seulement avec une clé</white>.", "<gray>Tu n'en as pas.") :
                        List.of("<gray>Prix : <gold>" + money(c.price), "<gray>Ton argent : <gold>" + money(bal), "", "<yellow>▶ Clic pour payer et ouvrir")));
        m.inv.setItem(53, named(Material.BARRIER, "<red>Fermer", List.of()));
        p.openInventory(m.inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.7f, 1.1f);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m) || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != m.inv) return;
        int slot = e.getRawSlot();
        switch (m.kind) {
            case "list" -> {
                int[] slots = listSlots();
                int i = 0;
                for (Crate c : crates.values()) {
                    if (i < slots.length && slots[i] == slot) {
                        openPreview(p, c, null);
                        return;
                    }
                    i++;
                }
            }
            case "preview" -> {
                if (slot == 53) p.closeInventory();
                else if (slot == 49) tryOpen(p, m.crate, m.at);
            }
            case "edit" -> editClick(p, m.crate, slot, e.isShiftClick());
            case "lots" -> lotsClick(p, m.crate, slot, e.isShiftClick(), e.isRightClick());
            default -> {
            }
        }
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    /** Fermer la roulette ne fait rien perdre : le lot est donné tout de suite. */
    @EventHandler
    public void onMenuClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof Menu m && m.finish != null) {
            Runnable f = m.finish;
            m.finish = null;
            Bukkit.getScheduler().runTask(this, f);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (e.getPlayer().getOpenInventory().getTopInventory().getHolder() instanceof Menu m && m.finish != null) {
            Runnable f = m.finish;
            m.finish = null;
            f.run();
        }
        opening.remove(e.getPlayer().getUniqueId());
    }

    // ================================================================= ouverture

    private void tryOpen(Player p, Crate c, Location at) {
        if (opening.contains(p.getUniqueId())) {
            send(p, msg("busy"));
            return;
        }
        if (at == null && !getConfig().getBoolean("open-anywhere", true) && !p.hasPermission("smpcrates.admin")) {
            send(p, msg("only-here"));
            return;
        }
        if (c.rewards.isEmpty()) {
            send(p, "<red>Cette caisse n'a pas encore de lots.");
            return;
        }
        int k = keysOf(p.getUniqueId(), c.id);
        if (k <= 0 && c.keysOnly) {
            send(p, msg("keys-only"));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }
        if (k > 0) {
            addKeys(p.getUniqueId(), c.id, -1);
            send(p, msg("key-used").replace("<keys>", String.valueOf(k - 1)));
        } else {
            var eco = eco();
            if (eco == null) {
                send(p, msg("no-vault"));
                return;
            }
            if (!eco.has(p, c.price)) {
                send(p, msg("no-money").replace("<price>", money(c.price)).replace("<balance>", money(eco.getBalance(p))));
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                return;
            }
            if (!eco.withdrawPlayer(p, c.price).transactionSuccess()) return;
            send(p, msg("bought").replace("<price>", money(c.price)));
        }
        spin(p, c, at);
    }

    /** La roulette : les lots défilent, ralentissent, et s'arrêtent sous la flèche. */
    private void spin(Player p, Crate c, Location at) {
        opening.add(p.getUniqueId());
        Reward win = c.roll();
        int len = 56, winAt = 50;
        List<Reward> strip = new ArrayList<>();
        for (int i = 0; i < len; i++) strip.add(i == winAt ? win : c.roll());
        Menu m = new Menu("spin", c, at);
        m.inv = Bukkit.createInventory(m, 27, parse(c.name + " <dark_gray>· ouverture..."));
        ItemStack arrowDown = named(Material.LIME_STAINED_GLASS_PANE, "<green><b>▼", List.of());
        ItemStack arrowUp = named(Material.LIME_STAINED_GLASS_PANE, "<green><b>▲", List.of());
        boolean[] done = {false};
        Runnable give = () -> {
            if (done[0]) return;
            done[0] = true;
            opening.remove(p.getUniqueId());
            deliver(p, c, win, at);
        };
        m.finish = give;
        int steps = winAt - 4; // la case du milieu (13) montre strip[pos + 4]
        int[] pos = {0};
        Runnable[] next = new Runnable[1];
        next[0] = () -> {
            if (done[0] || !p.isOnline()) return;
            draw(m.inv, c, strip, pos[0], arrowDown, arrowUp, pos[0]);
            float pitch = 0.6f + Math.min(1.4f, pos[0] / (float) steps * 1.4f);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.9f, pitch);
            if (pos[0] % 3 == 0) p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.25f, 1.6f);
            if (pos[0] >= steps) {
                reveal(p, m, c, win, give);
                return;
            }
            int i = pos[0]++;
            int delay = i < 24 ? 1 : i < 32 ? 2 : i < 37 ? 3 : i < 41 ? 5 : i < 44 ? 7 : 10;
            Bukkit.getScheduler().runTaskLater(this, next[0], delay);
        };
        p.openInventory(m.inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 1f, 0.8f);
        if (at != null) {
            try {
                Object data = Particle.FLASH.getDataType() == Color.class ? Color.WHITE : null;
                at.getWorld().spawnParticle(Particle.FLASH, at.clone().add(0.5, 1, 0.5), 1, 0, 0, 0, 0, data);
            } catch (RuntimeException ignored) {
            }
        }
        next[0].run();
    }

    private void draw(Inventory inv, Crate c, List<Reward> strip, int pos, ItemStack down, ItemStack up, int frame) {
        for (int i = 0; i < 9; i++) {
            Material pane = (i + frame) % 2 == 0 ? c.pane : Material.BLACK_STAINED_GLASS_PANE;
            ItemStack b = named(pane, " ", List.of());
            inv.setItem(i, i == 4 ? down : b);
            inv.setItem(18 + i, i == 4 ? up : b);
            inv.setItem(9 + i, display(c, strip.get(pos + i), true));
        }
    }

    private void reveal(Player p, Menu m, Crate c, Reward win, Runnable give) {
        double pct = c.chance(win);
        Material glow = rarityPane(pct);
        int[] f = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(this, () -> {
            f[0]++;
            for (int i = 0; i < 27; i++) {
                if (i == 13) continue;
                m.inv.setItem(i, named(f[0] % 2 == 0 ? glow : Material.WHITE_STAINED_GLASS_PANE, " ", List.of()));
            }
            if (f[0] >= 6) {
                task[0].cancel();
                m.finish = null;
                give.run();
            }
        }, 0L, 4L);
        ItemStack shown = display(c, win, true);
        ItemMeta meta = shown.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        shown.setItemMeta(meta);
        m.inv.setItem(13, shown);
        boolean big = pct < 3;
        p.playSound(p.getLocation(), big ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 1f, big ? 1f : 1.3f);
        if (pct < 1) p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.4f);
    }

    private void deliver(Player p, Crate c, Reward r, Location at) {
        if (!p.isOnline()) {
            // déconnecté pendant la roulette : le lot est donné à la prochaine connexion via une clé rendue
            addKeys(p.getUniqueId(), c.id, 1);
            return;
        }
        if (r.item() != null) {
            for (ItemStack left : p.getInventory().addItem(r.item().clone()).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
        var eco = eco();
        if (r.money() > 0 && eco != null) eco.depositPlayer(p, r.money());
        if (r.command() != null) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), r.command().replace("%player%", p.getName()));
        Component name = display(c, r, false).effectiveName();
        String nameMini = MM.serialize(name);
        double pct = c.chance(r);
        send(p, msg("won").replace("<reward>", nameMini) + " <dark_gray>(" + pct(pct) + ")");
        p.showTitle(Title.title(parse(rarity(pct)), name,
                Title.Times.times(java.time.Duration.ofMillis(150), java.time.Duration.ofMillis(1800), java.time.Duration.ofMillis(500))));
        Location fx = at != null ? at.clone().add(0.5, 1.2, 0.5) : p.getLocation().add(0, 1, 0);
        World w = fx.getWorld();
        Color col = color(c.color);
        w.spawnParticle(Particle.DUST, fx, 60, 0.6, 0.6, 0.6, 0, new Particle.DustOptions(col, 1.6f));
        w.spawnParticle(Particle.END_ROD, fx, 30, 0.2, 0.2, 0.2, 0.15);
        if (pct < 3) {
            w.spawnParticle(Particle.TOTEM_OF_UNDYING, fx, 120, 0.4, 0.4, 0.4, 0.6);
            w.spawnParticle(Particle.FIREWORK, fx, 60, 0.3, 0.3, 0.3, 0.2);
        }
        if (r.broadcast() || getConfig().getStringList("broadcast-crates").contains(c.id) && pct < 8) {
            Bukkit.broadcast(parse(msg("prefix") + msg("broadcast").replace("<player>", MM.escapeTags(p.getName()))
                    .replace("<reward>", nameMini).replace("<crate>", c.name)));
        }
    }

    // ================================================================= éditeur des caisses (admin)

    /** réponses tapées dans le chat (nom, prix, couleur, poids...) */
    private final Map<UUID, java.util.function.Consumer<String>> asking = new HashMap<>();

    private void ask(Player p, String question, java.util.function.Consumer<String> then) {
        p.closeInventory();
        asking.put(p.getUniqueId(), then);
        send(p, question + " <dark_gray>(tape <white>annuler</white> pour arrêter)");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(io.papermc.paper.event.player.AsyncChatEvent e) {
        java.util.function.Consumer<String> then = asking.remove(e.getPlayer().getUniqueId());
        if (then == null) return;
        e.setCancelled(true);
        String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Bukkit.getScheduler().runTask(this, () -> {
            if (text.equalsIgnoreCase("annuler") || text.equalsIgnoreCase("cancel")) {
                send(e.getPlayer(), "<gray>Annulé.");
                return;
            }
            then.accept(text);
        });
    }

    private void reloadCrates() {
        loadCrates();
        for (String k : new ArrayList<>(shows.keySet())) removeShow(k);
    }

    private void set(Crate c, String field, Object v) {
        getConfig().set("crates." + c.id + "." + field, v);
        saveConfig();
        reloadCrates();
    }

    void openEditor(Player p, Crate c) {
        Menu m = new Menu("edit", c, null);
        m.inv = Bukkit.createInventory(m, 27, parse("<dark_gray>✎ Modifier : " + c.id));
        ItemStack bg = named(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 27; i++) m.inv.setItem(i, bg);
        m.inv.setItem(10, named(Material.NAME_TAG, "<white><b>Nom", List.of("<gray>Actuel : " + c.name, "", "<yellow>▶ Clic : écrire le nouveau nom",
                "<dark_gray>couleurs : <white><red>, <#ff7a00>, <gradient:#fff:#888>, <b>")));
        m.inv.setItem(11, named(Material.GOLD_INGOT, "<white><b>Prix", List.of("<gray>Actuel : <gold>" + money(c.price), "", "<yellow>▶ Clic : écrire le prix")));
        m.inv.setItem(12, named(Material.MAGENTA_DYE, "<white><b>Couleur des effets", List.of("<gray>Actuelle : <color:" + c.color + ">■■■ " + c.color, "",
                "<yellow>▶ Clic : écrire une couleur (ex. #ffffff)")));
        m.inv.setItem(13, named(c.icon, "<white><b>Apparence (bloc qui tourne)", List.of("<gray>Actuelle : <white>" + c.icon.name().toLowerCase(Locale.ROOT), "",
                "<yellow>▶ Clic : prendre l'objet que tu as en main")));
        m.inv.setItem(14, named(c.keysOnly ? Material.TRIPWIRE_HOOK : Material.EMERALD, "<white><b>Ouverture : " + (c.keysOnly ? "<aqua>clé seulement" : "<green>argent ou clé"),
                List.of("<gray>Clé seulement = pour une caisse boutique / premium", "<gray>(clés : <white>/caisse cle <joueur> " + c.id + "</white>)", "", "<yellow>▶ Clic pour changer")));
        String[] tiers = {"Rare", "Épique", "Légendaire", "Mythique"};
        m.inv.setItem(15, named(Material.BLAZE_POWDER, "<white><b>Effets autour : " + (c.tier < 0 ? "<gray>auto (d'après le prix)" : "<gold>" + tiers[Math.min(3, c.tier)]),
                List.of("<gray>Plus haut = plus de particules autour", "", "<yellow>▶ Clic pour changer")));
        m.inv.setItem(16, named(Material.CHEST, "<white><b>Lots <gray>(" + c.rewards.size() + ")", List.of("<gray>Voir, changer les chances,", "<gray>ajouter ou retirer des lots.", "", "<yellow>▶ Clic")));
        m.inv.setItem(22, named(Material.BARRIER, "<red><b>Supprimer la caisse", List.of("<gray>Shift + clic pour confirmer")));
        p.openInventory(m.inv);
    }

    private void editClick(Player p, Crate c, int slot, boolean shift) {
        switch (slot) {
            case 10 -> ask(p, "<gray>Nouveau nom de la caisse :", t -> {
                set(c, "name", t);
                send(p, "<green>Nom : " + t);
                openEditor(p, crates.get(c.id));
            });
            case 11 -> ask(p, "<gray>Nouveau prix (ex. 250000 ou 250k) :", t -> {
                double v = parseAmount(t);
                if (v < 0) {
                    send(p, "<red>Prix invalide.");
                    return;
                }
                set(c, "price", v);
                send(p, "<green>Prix : <gold>" + money(v));
                openEditor(p, crates.get(c.id));
            });
            case 12 -> ask(p, "<gray>Nouvelle couleur (ex. <white>#ffffff</white>) :", t -> {
                String hex = t.startsWith("#") ? t : "#" + t;
                if (!hex.matches("#[0-9a-fA-F]{6}")) {
                    send(p, "<red>Couleur invalide (ex. #ff3030).");
                    return;
                }
                set(c, "color", hex.toLowerCase(Locale.ROOT));
                openEditor(p, crates.get(c.id));
            });
            case 13 -> {
                Material mat = p.getInventory().getItemInMainHand().getType();
                if (mat.isAir()) {
                    send(p, "<red>Prends en main l'objet / le bloc voulu (ex. une shulker blanche).");
                    return;
                }
                set(c, "icon", mat.name());
                getConfig().set("crates." + c.id + ".pane", null);
                saveConfig();
                reloadCrates();
                openEditor(p, crates.get(c.id));
            }
            case 14 -> {
                set(c, "keys-only", !c.keysOnly);
                openEditor(p, crates.get(c.id));
            }
            case 15 -> {
                int next = c.tier >= 3 ? -1 : c.tier + 1;
                set(c, "tier", next < 0 ? null : next);
                openEditor(p, crates.get(c.id));
            }
            case 16 -> openLots(p, c);
            case 22 -> {
                if (!shift) {
                    send(p, "<red>Shift + clic pour vraiment supprimer la caisse.");
                    return;
                }
                p.closeInventory();
                p.performCommand("caisse supprimer " + c.id);
            }
            default -> {
            }
        }
    }

    void openLots(Player p, Crate c) {
        Menu m = new Menu("lots", c, null);
        m.inv = Bukkit.createInventory(m, 54, parse("<dark_gray>✎ Lots : " + c.id));
        for (int i = 0; i < Math.min(45, c.rewards.size()); i++) {
            Reward r = c.rewards.get(i);
            ItemStack it = display(c, r, true);
            ItemMeta meta = it.getItemMeta();
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(parse("<gray>Poids : <white>" + r.weight()));
            lore.add(Component.empty());
            lore.add(parse("<yellow>▶ Clic : changer le poids (la chance)"));
            lore.add(parse("<red>▶ Shift + clic droit : retirer ce lot"));
            meta.lore(lore);
            it.setItemMeta(meta);
            m.inv.setItem(i, it);
        }
        ItemStack bg = named(c.pane, " ", List.of());
        for (int i = 45; i < 54; i++) m.inv.setItem(i, bg);
        m.inv.setItem(49, named(Material.LIME_DYE, "<green><b>+ Ajouter l'objet que j'ai en main", List.of("<gray>Avec son nom, ses enchantements, sa quantité.",
                "<gray>Ensuite tu écris son poids (ex. 5).", "", "<dark_gray>Poids = chance relative : 20 sort 2 fois plus que 10.")));
        m.inv.setItem(45, named(Material.ARROW, "<gray>◀ Retour", List.of()));
        p.openInventory(m.inv);
    }

    private void lotsClick(Player p, Crate c, int slot, boolean shift, boolean right) {
        if (slot == 45) {
            openEditor(p, c);
            return;
        }
        if (slot == 49) {
            ItemStack hand = p.getInventory().getItemInMainHand().clone();
            if (hand.getType().isAir()) {
                send(p, "<red>Prends en main l'objet à ajouter.");
                return;
            }
            ask(p, "<gray>Poids de ce lot (ex. 5) :", t -> {
                int w;
                try {
                    w = Integer.parseInt(t);
                } catch (NumberFormatException ex) {
                    w = 0;
                }
                if (w <= 0) {
                    send(p, "<red>Il faut un nombre plus grand que 0.");
                    return;
                }
                List<Map<?, ?>> list = new ArrayList<>(getConfig().getMapList("crates." + c.id + ".rewards"));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("weight", w);
                m.put("stack", Base64.getEncoder().encodeToString(hand.serializeAsBytes()));
                list.add(m);
                set(c, "rewards", list);
                send(p, "<green>Lot ajouté.");
                openLots(p, crates.get(c.id));
            });
            return;
        }
        if (slot < 0 || slot >= c.rewards.size() || slot >= 45) return;
        Reward r = c.rewards.get(slot);
        List<Map<?, ?>> list = new ArrayList<>(getConfig().getMapList("crates." + c.id + ".rewards"));
        if (r.idx() < 0 || r.idx() >= list.size()) return;
        if (shift && right) {
            list.remove(r.idx());
            set(c, "rewards", list);
            send(p, "<yellow>Lot retiré.");
            openLots(p, crates.get(c.id));
            return;
        }
        ask(p, "<gray>Nouveau poids de ce lot (actuel : <white>" + r.weight() + "</white>) :", t -> {
            int w;
            try {
                w = Integer.parseInt(t);
            } catch (NumberFormatException ex) {
                w = 0;
            }
            if (w <= 0) {
                send(p, "<red>Il faut un nombre plus grand que 0.");
                return;
            }
            List<Map<?, ?>> l2 = new ArrayList<>(getConfig().getMapList("crates." + c.id + ".rewards"));
            if (r.idx() >= l2.size()) return;
            Map<String, Object> m = new LinkedHashMap<>();
            for (var en : l2.get(r.idx()).entrySet()) m.put(String.valueOf(en.getKey()), en.getValue());
            m.put("weight", w);
            l2.set(r.idx(), m);
            set(c, "rewards", l2);
            send(p, "<green>Poids changé.");
            openLots(p, crates.get(c.id));
        });
    }

    /** 250000 · 250k · 1.5m */
    private static double parseAmount(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT).replace(",", ".").replace(" ", "");
        double mult = 1;
        if (t.endsWith("k")) mult = 1_000;
        else if (t.endsWith("m")) mult = 1_000_000;
        if (mult > 1) t = t.substring(0, t.length() - 1);
        try {
            return Double.parseDouble(t) * mult;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ================================================================= commande

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        Player p = s instanceof Player pl ? pl : null;
        boolean admin = s.hasPermission("smpcrates.admin");
        switch (sub) {
            case "" -> {
                if (p != null) openList(p);
            }
            case "placer", "place" -> {
                if (p == null || !admin) return true;
                Crate c = a.length > 1 ? crates.get(a[1].toLowerCase(Locale.ROOT)) : null;
                Block b = p.getTargetBlockExact(6);
                if (c == null || b == null || b.getType().isAir()) {
                    send(s, "<red>/caisse placer <" + String.join("|", crates.keySet()) + "> <gray>en regardant un bloc");
                    return true;
                }
                placed.put(key(b), c.id);
                removeShow(key(b));
                saveData();
                send(s, "<green>Ce bloc est maintenant la " + c.name + "<green>.");
            }
            case "retirer", "remove" -> {
                if (p == null || !admin) return true;
                Block b = p.getTargetBlockExact(6);
                if (b == null || placed.remove(key(b)) == null) {
                    send(s, "<red>Regarde une caisse posée.");
                    return true;
                }
                removeShow(key(b));
                saveData();
                send(s, "<yellow>Caisse retirée (le bloc reste).");
            }
            case "ajouter", "add" -> {
                if (p == null || !admin) return true;
                Crate c = a.length > 2 ? crates.get(a[1].toLowerCase(Locale.ROOT)) : null;
                ItemStack hand = p.getInventory().getItemInMainHand();
                int w;
                try {
                    w = a.length > 2 ? Integer.parseInt(a[2]) : 0;
                } catch (NumberFormatException ex) {
                    w = 0;
                }
                if (c == null || w <= 0 || hand.getType().isAir()) {
                    send(s, "<red>/caisse ajouter <caisse> <poids> <gray>avec l'objet en main (ex. poids 5)");
                    return true;
                }
                List<Map<?, ?>> list = new ArrayList<>(getConfig().getMapList("crates." + c.id + ".rewards"));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("weight", w);
                m.put("stack", Base64.getEncoder().encodeToString(hand.serializeAsBytes()));
                list.add(m);
                getConfig().set("crates." + c.id + ".rewards", list);
                saveConfig();
                loadCrates();
                Crate n = crates.get(c.id);
                send(s, "<green>Lot ajouté à la " + c.name + "<green> (chance : <white>" + pct(n == null ? 0 : w * 100.0 / n.total) + "</white>).");
            }
            case "cle", "clé", "key", "keys" -> {
                if (!admin) return true;
                if (a.length < 3 || !crates.containsKey(a[2].toLowerCase(Locale.ROOT))) {
                    send(s, "<red>/caisse cle <joueur> <" + String.join("|", crates.keySet()) + "> [nombre]");
                    return true;
                }
                OfflinePlayer o = Bukkit.getOfflinePlayer(a[1]);
                int n;
                try {
                    n = a.length > 3 ? Integer.parseInt(a[3]) : 1;
                } catch (NumberFormatException ex) {
                    n = 1;
                }
                addKeys(o.getUniqueId(), a[2].toLowerCase(Locale.ROOT), n);
                send(s, "<green>" + n + " clé(s) donnée(s) à " + a[1] + ".");
                if (o.getPlayer() != null) send(o.getPlayer(), "<green>Tu as reçu <white>" + n + "</white> clé(s) de " + crates.get(a[2].toLowerCase(Locale.ROOT)).name + "<green> !");
            }
            case "editer", "éditer", "edit", "modifier" -> {
                if (p == null || !admin) return true;
                Crate c = a.length > 1 ? crates.get(a[1].toLowerCase(Locale.ROOT)) : null;
                if (c == null) {
                    send(s, "<red>/caisse editer <" + String.join("|", crates.keySet()) + ">");
                    return true;
                }
                openEditor(p, c);
            }
            case "creer", "créer", "create" -> {
                if (!admin) return true;
                String id = a.length > 1 ? a[1].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "") : "";
                if (id.isEmpty() || crates.containsKey(id)) {
                    send(s, "<red>/caisse creer <id> <gray>(lettres / chiffres, pas déjà pris)");
                    return true;
                }
                String base = "crates." + id + ".";
                getConfig().set(base + "name", "<white><b>Caisse " + id);
                getConfig().set(base + "price", 10000);
                getConfig().set(base + "icon", "CHEST");
                getConfig().set(base + "color", "#ffffff");
                getConfig().set(base + "rewards", new ArrayList<>());
                saveConfig();
                reloadCrates();
                send(s, "<green>Caisse <white>" + id + "</white> créée. <gray>Règle-la avec <white>/caisse editer " + id + "</white>.");
                if (p != null) openEditor(p, crates.get(id));
            }
            case "supprimer", "delete" -> {
                if (!admin) return true;
                String id = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                if (!crates.containsKey(id)) {
                    send(s, "<red>/caisse supprimer <" + String.join("|", crates.keySet()) + ">");
                    return true;
                }
                getConfig().set("crates." + id, null);
                saveConfig();
                placed.values().removeIf(v -> v.equals(id));
                saveData();
                reloadCrates();
                send(s, "<yellow>Caisse <white>" + id + "</white> supprimée (et retirée des blocs où elle était posée).");
            }
            case "reload" -> {
                if (!admin) return true;
                reloadConfig();
                loadCrates();
                for (String k : new ArrayList<>(shows.keySet())) removeShow(k);
                send(s, "<green>Rechargé (" + crates.size() + " caisses).");
            }
            default -> {
                Crate c = crates.get(sub);
                if (c != null && p != null) openPreview(p, c, null);
                else if (p != null) openList(p);
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        boolean admin = s.hasPermission("smpcrates.admin");
        if (a.length == 1) {
            out.addAll(crates.keySet());
            if (admin) out.addAll(List.of("placer", "retirer", "ajouter", "cle", "editer", "creer", "supprimer", "reload"));
        } else if (a.length == 2 && admin && List.of("placer", "ajouter", "editer", "supprimer").contains(a[0].toLowerCase(Locale.ROOT))) {
            out.addAll(crates.keySet());
        } else if (a.length == 2 && admin && a[0].equalsIgnoreCase("cle")) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (a.length == 3 && admin && a[0].equalsIgnoreCase("cle")) {
            out.addAll(crates.keySet());
        } else if (a.length == 3 && admin && a[0].equalsIgnoreCase("ajouter")) {
            out.addAll(List.of("1", "5", "10", "20"));
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
