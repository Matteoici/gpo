package fr.smpboss;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static fr.smpboss.SmpBoss.parse;

/**
 * Menu "Butin du Roi" : chaque relique, arme et objet qu'on peut gagner, avec sa vraie chance par victoire
 * (calculée depuis le config) et son lore. S'ouvre avec /drops, un clic droit sur l'hologramme du portail,
 * ou un clic droit sur un panneau enregistré avec /boss panneau.
 */
final class DropsMenu implements InventoryHolder {

    private static final int PER_PAGE = 36, PREV = 45, CLOSE = 49, NEXT = 53, INFO = 4;

    private final SmpBoss plugin;
    private final Inventory inv;
    private final int page;
    private final boolean spider;

    private record Entry(ItemStack item, Rarity rarity, double chance) {}

    private DropsMenu(SmpBoss plugin, int page, boolean spider) {
        this.plugin = plugin;
        this.spider = spider;
        List<Entry> all = entries();
        int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        this.page = Math.max(0, Math.min(page, pages - 1));
        this.inv = Bukkit.createInventory(this, 54, parse(plugin.msg(spider ? "spider.drops-title" : "drops.title")));
        // bandes
        ItemStack band = pane(spider ? Material.LIGHT_BLUE_STAINED_GLASS_PANE : Material.ORANGE_STAINED_GLASS_PANE), dark = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int i = 0; i < 9; i++) inv.setItem(i, i % 2 == 0 ? band : dark);
        for (int i = 45; i < 54; i++) inv.setItem(i, i % 2 == 0 ? band : dark);
        inv.setItem(INFO, info());
        int from = this.page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && from + i < all.size(); i++) inv.setItem(9 + i, all.get(from + i).item());
        if (this.page > 0) inv.setItem(PREV, button(Material.ARROW, plugin.msg("drops.prev")));
        if (this.page < pages - 1) inv.setItem(NEXT, button(Material.ARROW, plugin.msg("drops.next")));
        inv.setItem(CLOSE, button(Material.BARRIER, plugin.msg("drops.close")));
    }

    static void open(SmpBoss plugin, Player p, int page) {
        open(plugin, p, page, false);
    }

    /** spider = butin de la Reine Araignée (ses armes, sa section spider-loot). */
    static void open(SmpBoss plugin, Player p, int page, boolean spider) {
        p.openInventory(new DropsMenu(plugin, page, spider).inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.2f);
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    // ================================================================= contenu

    private List<Entry> entries() {
        var c = plugin.getConfig();
        List<Entry> out = new ArrayList<>();
        // armes : tirage à part, chacune sa chance
        Weapons w = plugin.weapons();
        for (String id : spider ? Weapons.SPIDER : Weapons.PUMPKIN) {
            double ch = c.getDouble("weapons." + Weapons.path(id) + ".chance", Weapons.defChance(id));
            if (ch <= 0) continue;
            ItemStack it = w.create(id);
            out.add(new Entry(withChance(it, ch, null), Weapons.rarity(id), ch));
        }
        // reliques et objets : d'abord la rareté, puis un objet au hasard dans la liste
        String L = spider ? "spider-loot." : "loot.";
        boolean hasPrestige = !c.getMapList(L + "pools.prestige").isEmpty();
        Map<Rarity, Double> rar = Map.of(
                Rarity.PRESTIGE, hasPrestige ? chance(L, "prestige", 0.01) : 0,
                Rarity.SECRET, chance(L, "secret", 0.5),
                Rarity.LEGENDARY, chance(L, "legendary", 1),
                Rarity.EPIC, chance(L, "epic", 5),
                Rarity.RARE, chance(L, "rare", 20));
        for (Rarity r : new Rarity[]{Rarity.PRESTIGE, Rarity.SECRET, Rarity.LEGENDARY, Rarity.EPIC, Rarity.RARE}) {
            List<Map<?, ?>> pool = c.getMapList(L + "pools." + r.name().toLowerCase(Locale.ROOT));
            if (pool.isEmpty() || rar.get(r) <= 0) continue;
            double each = rar.get(r) / pool.size();
            for (Map<?, ?> e : pool) {
                ItemStack it = item(r, e);
                if (it == null) continue;
                String title = e.get("title") == null ? null : String.valueOf(e.get("title"));
                out.add(new Entry(withChance(it, each, title), r, each));
            }
        }
        out.sort((a, b) -> a.rarity() != b.rarity() ? b.rarity().ordinal() - a.rarity().ordinal() : Double.compare(a.chance(), b.chance()));
        return out;
    }

    private double chance(String L, String r, double def) {
        var c = plugin.getConfig();
        return c.getDouble(L + "chances." + r, c.getDouble("loot.chances." + r, def));
    }

    private ItemStack item(Rarity r, Map<?, ?> e) {
        if (e.get("relic") != null) {
            Relic relic = plugin.relics().get(String.valueOf(e.get("relic")));
            return relic == null ? null : plugin.items().relicItem(relic);
        }
        if (Boolean.TRUE.equals(e.get("sickle"))) return plugin.items().sickle();
        if (e.get("item") != null) {
            Material m = Material.matchMaterial(String.valueOf(e.get("item")));
            if (m == null) return null;
            List<String> lore = new ArrayList<>();
            if (e.get("lore") instanceof List<?> l) for (Object o : l) lore.add(String.valueOf(o));
            int amount = e.get("amount") instanceof Number n ? n.intValue() : 1;
            String name = e.get("name") == null ? null : String.valueOf(e.get("name"));
            String model = e.get("item-model") == null ? null : String.valueOf(e.get("item-model"));
            return plugin.items().lootItem(m, amount, name, r, lore, model);
        }
        return null;
    }

    /** Le lore de l'objet + sa chance par victoire. Le menu montre une copie : impossible de la sortir. */
    private ItemStack withChance(ItemStack it, double pct, String title) {
        ItemStack copy = it.clone();
        ItemMeta m = copy.getItemMeta();
        List<Component> lore = m.lore() == null ? new ArrayList<>() : new ArrayList<>(m.lore());
        lore.add(Component.empty());
        lore.add(line(plugin.msg("drops.separator")));
        lore.add(line(plugin.msg("drops.chance").replace("<pct>", pct(pct))));
        lore.add(line(plugin.msg("drops.one-in").replace("<n>", oneIn(pct))));
        if (title != null) lore.add(line(plugin.msg("drops.title-bonus").replace("<title>", title)));
        m.lore(lore);
        m.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        // plus de clé "relique"/"arme" : cette copie n'est qu'une vitrine
        for (NamespacedKey k : new ArrayList<>(m.getPersistentDataContainer().getKeys())) m.getPersistentDataContainer().remove(k);
        copy.setItemMeta(m);
        return copy;
    }

    private ItemStack info() {
        var c = plugin.getConfig();
        String L = spider ? "spider-loot." : "loot.";
        ItemStack it = new ItemStack(spider ? Material.COBWEB : Material.NETHER_STAR);
        ItemMeta m = it.getItemMeta();
        m.displayName(line(plugin.msg("drops.info-name")));
        List<Component> lore = new ArrayList<>();
        List<String> src = plugin.getConfig().getStringList(spider ? "messages.spider.drops-info-lore" : "messages.drops.info-lore");
        if (src.isEmpty() && plugin.getConfig().getDefaults() != null)
            src = plugin.getConfig().getDefaults().getStringList(spider ? "messages.spider.drops-info-lore" : "messages.drops.info-lore");
        for (String l : src) {
            lore.add(line(l.replace("<prestige>", pct(c.getMapList(L + "pools.prestige").isEmpty() ? 0 : chance(L, "prestige", 0.01)))
                    .replace("<secret>", pct(chance(L, "secret", 0.5)))
                    .replace("<legendary>", pct(chance(L, "legendary", 1)))
                    .replace("<epic>", pct(chance(L, "epic", 5)))
                    .replace("<rare>", pct(chance(L, "rare", 20)))
                    .replace("<pity-secret>", String.valueOf(c.getInt("loot.pity.secret", 300)))
                    .replace("<pity-legendary>", String.valueOf(c.getInt("loot.pity.legendary", 150)))));
        }
        m.lore(lore);
        m.setEnchantmentGlintOverride(true);
        it.setItemMeta(m);
        return it;
    }

    private static Component line(String mini) {
        return parse(mini).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static ItemStack pane(Material m) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.empty());
        meta.setHideTooltip(true);
        it.setItemMeta(meta);
        return it;
    }

    private static ItemStack button(Material mat, String name) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.displayName(line(name));
        it.setItemMeta(m);
        return it;
    }

    /** 0,01 % · 0,5 % · 20 % (2 chiffres significatifs, virgule française). */
    static String pct(double v) {
        if (v <= 0) return "0";
        String s = v >= 10 ? String.valueOf(Math.round(v * 10) / 10.0) : new BigDecimal(v).round(new MathContext(2)).stripTrailingZeros().toPlainString();
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s.replace('.', ',');
    }

    static String oneIn(double pct) {
        if (pct <= 0) return "∞";
        long n = Math.round(100 / pct);
        return String.format(Locale.FRANCE, "%,d", n).replace(' ', ' ').replace(' ', ' ');
    }

    // ================================================================= ouverture / clics

    static final class Listen implements Listener {
        private final SmpBoss plugin;
        private final NamespacedKey tag;

        Listen(SmpBoss plugin) {
            this.plugin = plugin;
            this.tag = new NamespacedKey(plugin, "drops_click");
        }

        @EventHandler(priority = EventPriority.HIGH)
        public void onClick(InventoryClickEvent e) {
            if (!(e.getView().getTopInventory().getHolder(false) instanceof DropsMenu m)) return;
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player p) || e.getRawSlot() < 0 || e.getRawSlot() >= m.inv.getSize()) return;
            switch (e.getRawSlot()) {
                case PREV -> {
                    if (m.inv.getItem(PREV) != null && m.inv.getItem(PREV).getType() == Material.ARROW) open(plugin, p, m.page - 1, m.spider);
                }
                case NEXT -> {
                    if (m.inv.getItem(NEXT) != null && m.inv.getItem(NEXT).getType() == Material.ARROW) open(plugin, p, m.page + 1, m.spider);
                }
                case CLOSE -> p.closeInventory();
                default -> {
                }
            }
        }

        @EventHandler(priority = EventPriority.HIGH)
        public void onDrag(InventoryDragEvent e) {
            if (e.getView().getTopInventory().getHolder(false) instanceof DropsMenu) e.setCancelled(true);
        }

        /** Clic droit sur la zone cliquable de l'hologramme du portail. */
        @EventHandler(priority = EventPriority.HIGH)
        public void onEntity(PlayerInteractEntityEvent e) {
            if (!(e.getRightClicked() instanceof Interaction i) || !i.getPersistentDataContainer().has(tag, PersistentDataType.BYTE)) return;
            e.setCancelled(true);
            if (e.getHand() != EquipmentSlot.HAND) return;
            Byte kind = i.getPersistentDataContainer().get(tag, PersistentDataType.BYTE);
            open(plugin, e.getPlayer(), 0, kind != null && kind == 2);
        }

        /** Clic droit sur un panneau enregistré (/boss panneau). */
        @EventHandler(priority = EventPriority.HIGH)
        public void onSign(PlayerInteractEvent e) {
            if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
            String k = key(e.getClickedBlock());
            boolean sp = plugin.getConfig().getStringList("spider-drops-signs").contains(k);
            if (!sp && !plugin.getConfig().getStringList("drops-signs").contains(k)) return;
            e.setCancelled(true);
            open(plugin, e.getPlayer(), 0, sp);
        }

        NamespacedKey tag() {
            return tag;
        }
    }

    static String key(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    /** /boss panneau [retirer] : le bloc regardé (un panneau...) ouvre le menu du butin. */
    static void signCommand(SmpBoss plugin, Player p, boolean remove) {
        signCommand(plugin, p, remove, false);
    }

    static void signCommand(SmpBoss plugin, Player p, boolean remove, boolean spider) {
        String path = spider ? "spider-drops-signs" : "drops-signs";
        Block b = p.getTargetBlockExact(6);
        if (b == null || b.getType().isAir()) {
            p.sendMessage(parse("<red>Regarde le panneau (à moins de 6 blocs)."));
            return;
        }
        List<String> list = new ArrayList<>(plugin.getConfig().getStringList(path));
        String k = key(b);
        if (remove) {
            if (list.remove(k)) p.sendMessage(parse("<yellow>Ce bloc n'ouvre plus le menu du butin."));
            else p.sendMessage(parse("<red>Ce bloc n'était pas enregistré."));
        } else if (!list.contains(k)) {
            list.add(k);
            p.sendMessage(parse("<green>Clic droit sur ce bloc = menu du butin " + (spider ? "de la Reine Araignée" : "du Roi Citrouille") + " (chances de drop de chaque relique / arme)."));
        } else p.sendMessage(parse("<gray>Déjà enregistré."));
        plugin.getConfig().set(path, list);
        plugin.saveConfig();
    }

    /** Zone cliquable invisible sous un hologramme. */
    static Interaction spawnClick(SmpBoss plugin, Location at, float width, float height) {
        return spawnClick(plugin, at, width, height, false);
    }

    static Interaction spawnClick(SmpBoss plugin, Location at, float width, float height, boolean spider) {
        NamespacedKey tag = new NamespacedKey(plugin, "drops_click");
        return at.getWorld().spawn(at, Interaction.class, i -> {
            i.setPersistent(false);
            i.setInteractionWidth(width);
            i.setInteractionHeight(height);
            i.setResponsive(true);
            i.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) (spider ? 2 : 1));
        });
    }
}
