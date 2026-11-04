package fr.smpbanque;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 🏦 Banque à intérêts (placements bloqués) et 📜 contrats d'achat entre joueurs. */
public final class SmpBanque extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final long DAY = 86_400_000L;

    static final class Placement {
        double value;
        long start, unlock, last;
    }

    static final class Contract {
        String id;
        UUID owner;
        String ownerName;
        ItemStack sample;
        int quantity;
        double price;
        long created, expires;
    }

    private final Map<UUID, List<Placement>> bank = new HashMap<>();
    private final Map<String, Contract> contracts = new LinkedHashMap<>();
    /** colis : objets livrés (contrats) en attente pour un joueur */
    private final Map<UUID, List<ItemStack>> parcels = new HashMap<>();
    private File file;
    private boolean dirty;

    static Component mm(String s) {
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    String money(double v) {
        return String.format(Locale.FRANCE, "%,d", Math.round(v)).replace(' ', ' ').replace(' ', ' ') + " " + getConfig().getString("money-symbol", "$");
    }

    private double c(String k, double def) {
        return getConfig().getDouble(k, def);
    }

    private static String date(long t) {
        return new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(new Date(t));
    }

    private static String left(long ms) {
        if (ms <= 0) return "maintenant";
        long h = ms / 3_600_000L;
        return h >= 24 ? (h / 24) + " j " + (h % 24) + " h" : h + " h " + (ms / 60_000L % 60) + " min";
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        file = new File(getDataFolder(), "data.yml");
        load();
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            expireContracts();
            if (dirty) save();
        }, 1200L, 1200L);
    }

    @Override
    public void onDisable() {
        save();
    }

    // ================================================================= Vault

    private static net.milkbowl.vault.economy.Economy eco() {
        var rsp = Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }

    private static boolean take(OfflinePlayer p, double v) {
        var e = eco();
        return e != null && e.has(p, v) && e.withdrawPlayer(p, v).transactionSuccess();
    }

    private static void give(OfflinePlayer p, double v) {
        var e = eco();
        if (e != null && v > 0) e.depositPlayer(p, v);
    }

    // ================================================================= données

    private static String enc(ItemStack it) {
        return Base64.getEncoder().encodeToString(it.serializeAsBytes());
    }

    private static ItemStack dec(String s) {
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(s));
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection b = y.getConfigurationSection("bank");
        if (b != null) for (String k : b.getKeys(false)) {
            List<Placement> list = new ArrayList<>();
            for (Map<?, ?> m : b.getMapList(k)) {
                Placement pl = new Placement();
                pl.value = ((Number) m.get("value")).doubleValue();
                pl.start = ((Number) m.get("start")).longValue();
                pl.unlock = ((Number) m.get("unlock")).longValue();
                pl.last = ((Number) m.get("last")).longValue();
                list.add(pl);
            }
            bank.put(UUID.fromString(k), list);
        }
        ConfigurationSection cs = y.getConfigurationSection("contracts");
        if (cs != null) for (String k : cs.getKeys(false)) {
            try {
                ConfigurationSection s = cs.getConfigurationSection(k);
                Contract ct = new Contract();
                ct.id = k;
                ct.owner = UUID.fromString(s.getString("owner"));
                ct.ownerName = s.getString("owner-name", "?");
                ct.sample = dec(s.getString("item"));
                ct.quantity = s.getInt("quantity");
                ct.price = s.getDouble("price");
                ct.created = s.getLong("created");
                ct.expires = s.getLong("expires");
                contracts.put(k, ct);
            } catch (RuntimeException e) {
                getLogger().warning("Contrat " + k + " ignoré : " + e.getMessage());
            }
        }
        ConfigurationSection ps = y.getConfigurationSection("parcels");
        if (ps != null) for (String k : ps.getKeys(false)) {
            List<ItemStack> list = new ArrayList<>();
            for (String s : ps.getStringList(k)) {
                try {
                    list.add(dec(s));
                } catch (RuntimeException ignored) {
                }
            }
            if (!list.isEmpty()) parcels.put(UUID.fromString(k), list);
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, List<Placement>> e : bank.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            List<Map<String, Object>> list = new ArrayList<>();
            for (Placement p : e.getValue()) list.add(Map.of("value", p.value, "start", p.start, "unlock", p.unlock, "last", p.last));
            y.set("bank." + e.getKey(), list);
        }
        for (Contract c : contracts.values()) {
            String b = "contracts." + c.id + ".";
            y.set(b + "owner", c.owner.toString());
            y.set(b + "owner-name", c.ownerName);
            y.set(b + "item", enc(c.sample));
            y.set(b + "quantity", c.quantity);
            y.set(b + "price", c.price);
            y.set(b + "created", c.created);
            y.set(b + "expires", c.expires);
        }
        for (Map.Entry<UUID, List<ItemStack>> e : parcels.entrySet()) {
            List<String> l = new ArrayList<>();
            for (ItemStack it : e.getValue()) l.add(enc(it));
            if (!l.isEmpty()) y.set("parcels." + e.getKey(), l);
        }
        try {
            y.save(file);
            dirty = false;
        } catch (IOException e) {
            getLogger().warning("data.yml : " + e.getMessage());
        }
    }

    // ================================================================= banque

    /** Intérêts : un jour entier écoulé = un versement (composé), sans dépasser le plafond. */
    private List<Placement> account(UUID id) {
        List<Placement> list = bank.computeIfAbsent(id, k -> new ArrayList<>());
        long now = System.currentTimeMillis();
        double rate = c("bank.daily-rate", 0.01), max = c("bank.max-balance", 10_000_000);
        for (Placement p : list) {
            long days = (now - p.last) / DAY;
            if (days <= 0) continue;
            double total = list.stream().mapToDouble(x -> x.value).sum();
            double grown = p.value * Math.pow(1 + rate, days);
            p.value = Math.min(grown, p.value + Math.max(0, max - total));
            p.last += days * DAY;
            dirty = true;
        }
        return list;
    }

    private void deposit(Player p, double amount) {
        List<Placement> list = account(p.getUniqueId());
        if (amount < c("bank.min-deposit", 1000)) {
            p.sendMessage(mm("<red>Minimum : <white>" + money(c("bank.min-deposit", 1000))));
            return;
        }
        if (list.size() >= getConfig().getInt("bank.max-placements", 5)) {
            p.sendMessage(mm("<red>Tu as déjà " + list.size() + " placements (le maximum). Attends qu'un se débloque."));
            return;
        }
        double total = list.stream().mapToDouble(x -> x.value).sum();
        if (total + amount > c("bank.max-balance", 10_000_000)) {
            p.sendMessage(mm("<red>La banque ne garde pas plus de <white>" + money(c("bank.max-balance", 10_000_000)) + "</white> par joueur."));
            return;
        }
        if (!take(p, amount)) {
            p.sendMessage(mm("<red>Tu n'as pas <white>" + money(amount)));
            return;
        }
        Placement pl = new Placement();
        pl.value = amount;
        pl.start = pl.last = System.currentTimeMillis();
        pl.unlock = pl.start + (long) (c("bank.lock-days", 7) * DAY);
        list.add(pl);
        dirty = true;
        save();
        double rate = c("bank.daily-rate", 0.01);
        double after = amount * Math.pow(1 + rate, c("bank.lock-days", 7));
        p.sendMessage(mm("<gold>🏦 <white>" + money(amount) + "</white> placés à <green>" + Math.round(rate * 1000) / 10.0 + " %/jour</green>. "
                + "<gray>Débloqué le <white>" + date(pl.unlock) + "</white> (≈ <gold>" + money(after) + "</gold>)."));
        p.playSound(p.getLocation(), Sound.BLOCK_CHAIN_PLACE, 1f, 1.4f);
    }

    private void withdraw(Player p, int index, boolean early) {
        List<Placement> list = account(p.getUniqueId());
        if (index < 0 || index >= list.size()) return;
        Placement pl = list.get(index);
        long now = System.currentTimeMillis();
        double out = pl.value;
        if (now < pl.unlock) {
            double pen = c("bank.early-penalty", 0.15);
            if (!early || pen <= 0) {
                p.sendMessage(mm("<gray>Bloqué encore <white>" + left(pl.unlock - now) + "</white>." + (pen > 0 ? " <dark_gray>(Maj + clic : retirer avant, -" + Math.round(pen * 100) + " %)" : "")));
                return;
            }
            out = pl.value * (1 - pen);
        }
        list.remove(index);
        give(p, out);
        dirty = true;
        save();
        p.sendMessage(mm("<gold>🏦 +" + money(out) + " <gray>retirés de la banque" + (out < pl.value ? " <red>(pénalité " + money(pl.value - out) + ")" : "") + "."));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
    }

    // ================================================================= contrats

    private void create(Player p, int qty, double price) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            p.sendMessage(mm("<red>Tiens en main l'objet que tu veux commander (un exemplaire suffit)."));
            return;
        }
        if (qty < 1 || qty > 2304) {
            p.sendMessage(mm("<red>Quantité entre 1 et 2304."));
            return;
        }
        if (price < c("contracts.min-price", 100)) {
            p.sendMessage(mm("<red>Prix minimum : " + money(c("contracts.min-price", 100))));
            return;
        }
        long mine = contracts.values().stream().filter(x -> x.owner.equals(p.getUniqueId())).count();
        if (mine >= getConfig().getInt("contracts.max-per-player", 5)) {
            p.sendMessage(mm("<red>Tu as déjà " + mine + " contrats en cours (le maximum)."));
            return;
        }
        if (!take(p, price)) {
            p.sendMessage(mm("<red>Tu n'as pas <white>" + money(price) + "</white> (l'argent est bloqué jusqu'à la livraison)."));
            return;
        }
        Contract ct = new Contract();
        ct.id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1296), 36);
        ct.owner = p.getUniqueId();
        ct.ownerName = p.getName();
        ct.sample = hand.clone();
        ct.sample.setAmount(1);
        ct.quantity = qty;
        ct.price = price;
        ct.created = System.currentTimeMillis();
        ct.expires = ct.created + (long) (c("contracts.expire-days", 3) * DAY);
        contracts.put(ct.id, ct);
        dirty = true;
        save();
        Component name = ct.sample.effectiveName();
        p.sendMessage(mm("<gold>📜 Contrat posté : <white>" + qty + " x </white>").append(name).append(mm(" <gray>pour <gold>" + money(price) + "</gold> <dark_gray>(argent bloqué)")));
        Bukkit.broadcast(mm("<gold>📜 <white>" + p.getName() + "</white> cherche <white>" + qty + " x </white>").append(name).append(mm(" <gray>pour <gold>" + money(price) + "</gold> · <yellow>/contrats")));
    }

    /** Combien d'exemplaires du modèle le joueur a dans son inventaire. */
    private static int count(Player p, ItemStack sample) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) if (it != null && it.isSimilar(sample)) n += it.getAmount();
        return n;
    }

    private void fulfill(Player p, Contract ct) {
        if (ct.owner.equals(p.getUniqueId())) {
            cancel(p, ct, false);
            return;
        }
        if (!contracts.containsKey(ct.id)) return;
        if (count(p, ct.sample) < ct.quantity) {
            p.sendMessage(mm("<red>Il te faut <white>" + ct.quantity + "</white> exemplaires (identiques au modèle). Tu en as " + count(p, ct.sample) + "."));
            return;
        }
        // on retire les objets
        int need = ct.quantity;
        List<ItemStack> taken = new ArrayList<>();
        ItemStack[] inv = p.getInventory().getStorageContents();
        for (int i = 0; i < inv.length && need > 0; i++) {
            ItemStack it = inv[i];
            if (it == null || !it.isSimilar(ct.sample)) continue;
            int n = Math.min(need, it.getAmount());
            ItemStack part = it.clone();
            part.setAmount(n);
            taken.add(part);
            it.setAmount(it.getAmount() - n);
            if (it.getAmount() <= 0) inv[i] = null;
            need -= n;
        }
        p.getInventory().setStorageContents(inv);
        contracts.remove(ct.id);
        double pay = ct.price * (1 - c("contracts.tax", 0.03));
        give(p, pay);
        deliver(ct.owner, taken);
        dirty = true;
        save();
        p.sendMessage(mm("<green>📜 Contrat rempli ! <gold>+" + money(pay)));
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1f);
        Player o = Bukkit.getPlayer(ct.owner);
        if (o != null) o.sendMessage(mm("<gold>📜 <white>" + p.getName() + "</white> a rempli ton contrat (" + ct.quantity + " x ").append(ct.sample.effectiveName()).append(mm("<gold>) !")));
    }

    /** Livraison : dans l'inventaire s'il est là, sinon en colis (/contrats colis). */
    private void deliver(UUID to, List<ItemStack> items) {
        Player o = Bukkit.getPlayer(to);
        if (o != null) {
            for (ItemStack it : items) for (ItemStack left : o.getInventory().addItem(it).values()) o.getWorld().dropItemNaturally(o.getLocation(), left);
        } else {
            parcels.computeIfAbsent(to, k -> new ArrayList<>()).addAll(items);
        }
    }

    private void cancel(CommandSender who, Contract ct, boolean expired) {
        if (contracts.remove(ct.id) == null) return;
        give(Bukkit.getOfflinePlayer(ct.owner), ct.price);
        dirty = true;
        save();
        Player o = Bukkit.getPlayer(ct.owner);
        if (o != null) o.sendMessage(mm("<gray>📜 Contrat " + (expired ? "expiré" : "annulé") + " : <gold>" + money(ct.price) + "</gold> rendus."));
        if (who != null && who != o) who.sendMessage(mm("<gray>Contrat annulé."));
    }

    private void expireContracts() {
        long now = System.currentTimeMillis();
        for (Contract ct : new ArrayList<>(contracts.values())) if (ct.expires < now) cancel(null, ct, true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        List<ItemStack> items = parcels.get(e.getPlayer().getUniqueId());
        if (items != null && !items.isEmpty())
            Bukkit.getScheduler().runTaskLater(this, () -> e.getPlayer().sendMessage(mm("<gold>📦 Tu as " + items.size() + " colis de contrats : <yellow>/contrats colis")), 60L);
    }

    // ================================================================= menus

    private static final class Gui implements InventoryHolder {
        final String kind;
        final int page;
        final List<String> ids = new ArrayList<>();
        Inventory inv;

        Gui(String kind, int page) {
            this.kind = kind;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static ItemStack item(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(mm(name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(mm(s));
        meta.lore(l);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    private static void fill(Inventory inv) {
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, pane);
    }

    private void openBank(Player p) {
        Gui g = new Gui("bank", 0);
        g.inv = Bukkit.createInventory(g, 27, PackUi.title(mm("🏦 Ta banque"), 27));
        List<Placement> list = account(p.getUniqueId());
        double total = list.stream().mapToDouble(x -> x.value).sum();
        double rate = c("bank.daily-rate", 0.01);
        g.inv.setItem(10, item(Material.GOLD_BLOCK, "<gold><b>🏦 " + money(total), List.of(
                "<gray>Intérêts : <green>" + Math.round(rate * 1000) / 10.0 + " % par jour</green> (composés)",
                "<gray>Chaque placement est bloqué <white>" + (int) c("bank.lock-days", 7) + " jours</white>.",
                "<gray>Plafond : <white>" + money(c("bank.max-balance", 10_000_000)),
                "", "<yellow>/banque placer <montant>")));
        long now = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            if (i < list.size()) {
                Placement pl = list.get(i);
                boolean open = now >= pl.unlock;
                g.inv.setItem(12 + i, item(open ? Material.EMERALD : Material.GOLD_INGOT, (open ? "<green>" : "<gold>") + money(pl.value), List.of(
                        "<gray>Placé le <white>" + date(pl.start),
                        open ? "<green>✔ Débloqué" : "<gray>Débloqué dans <white>" + left(pl.unlock - now),
                        "", open ? "<yellow>Clic : retirer" : "<dark_gray>Maj + clic : retirer avant (-" + Math.round(c("bank.early-penalty", 0.15) * 100) + " %)")));
            } else {
                g.inv.setItem(12 + i, item(Material.LIGHT_GRAY_DYE, "<dark_gray>Emplacement libre", List.of("<gray>/banque placer <montant>")));
            }
        }
        double[] quick = {10_000, 100_000, 1_000_000};
        for (int k = 0; k < 3; k++) {
            g.inv.setItem(21 + k, item(k == 0 ? Material.GOLD_NUGGET : k == 1 ? Material.GOLD_INGOT : Material.GOLD_BLOCK,
                    "<yellow>Placer " + money(quick[k]), List.of("<gray>Rapportera ≈ <gold>" + money(quick[k] * Math.pow(1 + rate, c("bank.lock-days", 7)) - quick[k]) + "</gold> en " + (int) c("bank.lock-days", 7) + " j")));
        }
        fill(g.inv);
        p.openInventory(g.inv);
    }

    private void openContracts(Player p, int page) {
        Gui g = new Gui("contracts", page);
        g.inv = Bukkit.createInventory(g, 54, PackUi.title(mm("📜 Contrats"), 54));
        List<Contract> all = new ArrayList<>(contracts.values());
        all.sort((a, b) -> Boolean.compare(!a.owner.equals(p.getUniqueId()), !b.owner.equals(p.getUniqueId())));
        int from = page * 45;
        long now = System.currentTimeMillis();
        for (int i = 0; i < 45 && from + i < all.size(); i++) {
            Contract ct = all.get(from + i);
            boolean mine = ct.owner.equals(p.getUniqueId());
            ItemStack it = ct.sample.clone();
            it.setAmount(Math.max(1, Math.min(64, ct.quantity)));
            ItemMeta m = it.getItemMeta();
            List<Component> lore = new ArrayList<>();
            lore.add(mm("<gold>📜 Contrat de <white>" + ct.ownerName));
            lore.add(mm("<gray>Veut : <white>" + ct.quantity + " x"));
            lore.add(mm("<gray>Paie : <gold>" + money(ct.price) + " <dark_gray>(" + money(ct.price / ct.quantity) + " / pièce)"));
            lore.add(mm("<gray>Expire dans <white>" + left(ct.expires - now)));
            lore.add(Component.empty());
            if (mine) lore.add(mm("<red>Clic : annuler (argent rendu)"));
            else {
                int have = count(p, ct.sample);
                lore.add(mm(have >= ct.quantity ? "<green>▶ Clic : livrer et encaisser <gold>" + money(ct.price * (1 - c("contracts.tax", 0.03)))
                        : "<dark_gray>Tu en as " + have + " / " + ct.quantity));
            }
            m.lore(lore);
            it.setItemMeta(m);
            g.inv.setItem(i, it);
            g.ids.add(ct.id);
        }
        if (page > 0) g.inv.setItem(45, item(Material.ARROW, "<white>◀ Page précédente", List.of()));
        if (from + 45 < all.size()) g.inv.setItem(53, item(Material.ARROW, "<white>Page suivante ▶", List.of()));
        g.inv.setItem(49, item(Material.WRITABLE_BOOK, "<gold>Poster un contrat", List.of("<gray>Tiens l'objet en main, puis :",
                "<yellow>/contrats creer <quantité> <prix total>", "<dark_gray>Ton argent est bloqué jusqu'à la livraison.",
                "<dark_gray>Expire au bout de " + (int) c("contracts.expire-days", 3) + " j (argent rendu).")));
        int n = parcels.getOrDefault(p.getUniqueId(), List.of()).size();
        g.inv.setItem(47, item(Material.CHEST, "<gold>📦 Mes colis <gray>(" + n + ")", List.of("<gray>Objets livrés pendant ton absence", "", "<yellow>Clic : tout récupérer")));
        fill(g.inv);
        p.openInventory(g.inv);
    }

    private void claimParcels(Player p) {
        List<ItemStack> items = parcels.remove(p.getUniqueId());
        if (items == null || items.isEmpty()) {
            p.sendMessage(mm("<gray>Pas de colis."));
            return;
        }
        for (ItemStack it : items) for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        dirty = true;
        save();
        p.sendMessage(mm("<gold>📦 " + items.size() + " colis récupérés."));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Gui) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Gui g)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        int s = e.getRawSlot();
        if (g.kind.equals("bank")) {
            if (s >= 12 && s <= 16) withdraw(p, s - 12, e.getClick() == ClickType.SHIFT_LEFT || e.getClick() == ClickType.SHIFT_RIGHT);
            else if (s >= 21 && s <= 23) deposit(p, new double[]{10_000, 100_000, 1_000_000}[s - 21]);
            else return;
            openBank(p);
        } else {
            if (s < 45 && s < g.ids.size()) {
                Contract ct = contracts.get(g.ids.get(s));
                if (ct != null) fulfill(p, ct);
                openContracts(p, g.page);
            } else if (s == 45 && g.page > 0) openContracts(p, g.page - 1);
            else if (s == 53) openContracts(p, g.page + 1);
            else if (s == 47) {
                claimParcels(p);
                openContracts(p, g.page);
            }
        }
    }

    // ================================================================= commandes

    private static double amount(String s) {
        s = s.toLowerCase(Locale.ROOT).replace(" ", "").replace(",", ".");
        double mult = 1;
        if (s.endsWith("k")) {
            mult = 1_000;
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("m")) {
            mult = 1_000_000;
            s = s.substring(0, s.length() - 1);
        }
        return Double.parseDouble(s) * mult;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) return true;
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        try {
            if (cmd.getName().equalsIgnoreCase("banque")) {
                switch (sub) {
                    case "placer", "deposer", "déposer", "deposit" -> {
                        if (a.length < 2) p.sendMessage(mm("<red>/banque placer <montant> <gray>(ex. 50000, 250k, 1m)"));
                        else deposit(p, amount(a[1]));
                    }
                    case "retirer", "withdraw" -> {
                        List<Placement> list = account(p.getUniqueId());
                        int n = 0;
                        for (int i = list.size() - 1; i >= 0; i--) {
                            if (System.currentTimeMillis() >= list.get(i).unlock) {
                                withdraw(p, i, false);
                                n++;
                            }
                        }
                        if (n == 0) p.sendMessage(mm("<gray>Aucun placement débloqué pour l'instant."));
                    }
                    default -> openBank(p);
                }
            } else {
                switch (sub) {
                    case "creer", "créer", "create", "poster" -> {
                        if (a.length < 3) p.sendMessage(mm("<red>/contrats creer <quantité> <prix total> <gray>(objet en main)"));
                        else create(p, Integer.parseInt(a[1]), amount(a[2]));
                    }
                    case "colis" -> claimParcels(p);
                    case "annuler" -> {
                        if (a.length >= 2 && p.hasPermission("smpbanque.admin") && contracts.containsKey(a[1])) cancel(p, contracts.get(a[1]), false);
                    }
                    default -> openContracts(p, 0);
                }
            }
        } catch (NumberFormatException e) {
            p.sendMessage(mm("<red>Nombre invalide."));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(cmd.getName().equalsIgnoreCase("banque") ? List.of("placer", "retirer") : List.of("creer", "colis"));
        else if (a.length == 2 && cmd.getName().equalsIgnoreCase("banque") && a[0].equalsIgnoreCase("placer")) out.addAll(List.of("10k", "100k", "1m"));
        else if (a.length == 2 && !cmd.getName().equalsIgnoreCase("banque")) out.addAll(List.of("64", "128", "16"));
        else if (a.length == 3 && !cmd.getName().equalsIgnoreCase("banque")) out.addAll(List.of("10k", "50k", "100k"));
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.startsWith(last));
        return out;
    }
}
