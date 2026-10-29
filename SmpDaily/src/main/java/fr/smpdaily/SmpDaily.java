package fr.smpdaily;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
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
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Récompense quotidienne : un cadeau par jour, série de N jours qui recommence, bonus pendant les événements. */
public final class SmpDaily extends JavaPlugin implements Listener, TabExecutor {

    static final MiniMessage MM = MiniMessage.miniMessage();

    record Reward(int day, String name, Material icon, double money, List<String> items, List<String> commands, List<String> desc) {
    }

    record Event(String id, String name, Material icon, LocalDate until, double money, List<String> items, List<String> commands, List<String> desc) {
    }

    static final class Data {
        LocalDate last;
        int streak;
        int total;
    }

    final List<Reward> rewards = new ArrayList<>();
    final List<Event> events = new ArrayList<>();
    final Map<UUID, Data> data = new HashMap<>();
    File file;
    boolean vault;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        file = new File(getDataFolder(), "joueurs.yml");
        vault = Bukkit.getPluginManager().getPlugin("Vault") != null;
        load();
        loadData();
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("daily");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        saveData();
    }

    void load() {
        reloadConfig();
        rewards.clear();
        events.clear();
        ConfigurationSection j = getConfig().getConfigurationSection("jours");
        if (j != null) {
            List<String> keys = new ArrayList<>(j.getKeys(false));
            keys.sort((a, b) -> Integer.compare(parseInt(a, 999), parseInt(b, 999)));
            int n = 1;
            for (String k : keys) {
                ConfigurationSection s = j.getConfigurationSection(k);
                if (s == null) continue;
                rewards.add(new Reward(n, s.getString("nom", "<white>Jour " + n), mat(s.getString("icone"), Material.CHEST), s.getDouble("argent", 0),
                        s.getStringList("items"), s.getStringList("commandes"), s.getStringList("description")));
                n++;
            }
        }
        ConfigurationSection ev = getConfig().getConfigurationSection("evenements");
        if (ev != null) for (String id : ev.getKeys(false)) {
            ConfigurationSection s = ev.getConfigurationSection(id);
            if (s == null || !s.getBoolean("actif", true)) continue;
            LocalDate until;
            try {
                until = LocalDate.parse(s.getString("jusqu-au", "2000-01-01"));
            } catch (RuntimeException ex) {
                getLogger().warning("Événement " + id + " : date invalide (format AAAA-MM-JJ)");
                continue;
            }
            events.add(new Event(id, s.getString("nom", id), mat(s.getString("icone"), Material.CAKE), until, s.getDouble("argent", 0),
                    s.getStringList("items"), s.getStringList("commandes"), s.getStringList("description")));
        }
    }

    void loadData() {
        data.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String k : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(k);
            if (s == null) continue;
            Data d = new Data();
            try {
                String l = s.getString("dernier");
                d.last = l == null ? null : LocalDate.parse(l);
                d.streak = s.getInt("serie");
                d.total = s.getInt("total");
                data.put(UUID.fromString(k), d);
            } catch (RuntimeException ignored) {
            }
        }
    }

    void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : data.entrySet()) {
            String k = e.getKey().toString();
            Data d = e.getValue();
            y.set(k + ".dernier", d.last == null ? null : d.last.toString());
            y.set(k + ".serie", d.streak);
            y.set(k + ".total", d.total);
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            getLogger().warning("joueurs.yml : " + ex.getMessage());
        }
    }

    Data of(UUID id) {
        return data.computeIfAbsent(id, k -> new Data());
    }

    // ================================================================= jours

    ZoneId zone() {
        try {
            return ZoneId.of(getConfig().getString("fuseau", "Europe/Paris"));
        } catch (RuntimeException ex) {
            return ZoneId.systemDefault();
        }
    }

    LocalDate today() {
        return ZonedDateTime.now(zone()).minusHours(getConfig().getInt("heure-changement", 0)).toLocalDate();
    }

    Duration untilNext() {
        ZonedDateTime now = ZonedDateTime.now(zone());
        ZonedDateTime next = today().plusDays(1).atStartOfDay(zone()).plusHours(getConfig().getInt("heure-changement", 0));
        return Duration.between(now, next);
    }

    boolean claimedToday(Data d) {
        return d.last != null && d.last.equals(today());
    }

    boolean missed(Data d) {
        return d.last != null && d.last.isBefore(today().minusDays(1));
    }

    /** Numéro du prochain jour à récupérer (1..N). */
    int nextDay(Data d) {
        int n = Math.max(1, rewards.size());
        if (d.last == null) return 1;
        if (missed(d) && getConfig().getBoolean("serie-perdue-si-rate", true)) return 1;
        return d.streak % n + 1;
    }

    List<Event> activeEvents() {
        List<Event> out = new ArrayList<>();
        for (Event e : events) if (!today().isAfter(e.until())) out.add(e);
        return out;
    }

    void claim(Player p) {
        Data d = of(p.getUniqueId());
        if (claimedToday(d)) {
            send(p, msg("already").replace("<time>", time(untilNext())));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
            return;
        }
        if (rewards.isEmpty()) return;
        boolean lost = missed(d) && getConfig().getBoolean("serie-perdue-si-rate", true) && d.streak > 0;
        int day = nextDay(d);
        Reward r = rewards.get(day - 1);
        d.last = today();
        d.streak = day;
        d.total++;
        saveData();
        if (lost) send(p, msg("streak-lost"));
        boolean dropped = give(p, r.money(), r.items(), r.commands());
        send(p, msg("claimed").replace("<day>", MM.stripTags(r.name())).replace("<streak>", String.valueOf(day)));
        for (Event e : activeEvents()) {
            dropped |= give(p, e.money(), e.items(), e.commands());
            send(p, msg("event-bonus").replace("<event>", e.name()));
        }
        if (dropped) send(p, msg("inventory-full"));
        boolean big = day == rewards.size();
        p.playSound(p.getLocation(), big ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 1f, big ? 1f : 1.4f);
        p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), big ? 120 : 40, 0.4, 0.6, 0.4, 0.4);
    }

    /** Donne argent + items + commandes. Retourne true si des items sont tombés par terre. */
    boolean give(Player p, double money, List<String> items, List<String> commands) {
        if (money > 0 && vault) Money.give(p, money);
        boolean dropped = false;
        for (String s : items) {
            String[] parts = s.split(":");
            Material m = mat(parts[0], null);
            if (m == null) continue;
            int n = parts.length > 1 ? Math.max(1, parseInt(parts[1], 1)) : 1;
            while (n > 0) {
                int k = Math.min(n, m.getMaxStackSize());
                n -= k;
                for (ItemStack left : p.getInventory().addItem(new ItemStack(m, k)).values()) {
                    p.getWorld().dropItemNaturally(p.getLocation(), left);
                    dropped = true;
                }
            }
        }
        for (String c : commands) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("%player%", p.getName()));
        return dropped;
    }

    // ================================================================= menu

    final class Menu implements InventoryHolder {
        final Inventory inv;
        final Player p;

        Menu(Player p) {
            this.p = p;
            int n = rewards.size();
            int size = n <= 7 ? 27 : Math.min(54, 9 * ((n + 8) / 9 + 2));
            inv = Bukkit.createInventory(this, size, mm("<dark_gray>Récompense du jour"));
            draw();
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }

        int slotOf(int i) {
            return rewards.size() <= 7 ? 10 + i + (7 - rewards.size()) / 2 : 9 + i;
        }

        void draw() {
            inv.clear();
            ItemStack pane = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of(), false);
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);
            Data d = of(p.getUniqueId());
            boolean today = claimedToday(d);
            int next = nextDay(d);
            // jours déjà pris dans la série en cours
            int done = today ? d.streak : next - 1;
            for (int i = 0; i < rewards.size() && slotOf(i) < inv.getSize() - 9; i++) {
                Reward r = rewards.get(i);
                List<String> lore = new ArrayList<>(lines(r.money(), r.items(), r.desc()));
                lore.add("");
                ItemStack it;
                if (r.day() <= done) {
                    lore.add("<green>✔ Récupéré");
                    it = item(Material.LIME_STAINED_GLASS_PANE, "<green>✔ " + MM.stripTags(r.name()), lore, false);
                } else if (r.day() == next && !today) {
                    lore.add("<yellow><b>▶ Clic pour récupérer !");
                    it = item(r.icon(), r.name(), lore, true);
                } else {
                    int in = r.day() - (today ? d.streak : next) + (today ? 0 : 0);
                    if (today) in = r.day() - d.streak;
                    else in = r.day() - next;
                    lore.add("<gray>Dans <white>" + in + "</white> jour(s)");
                    it = item(r.icon(), "<gray>" + MM.stripTags(r.name()), lore, false);
                }
                it.setAmount(Math.max(1, Math.min(64, r.day())));
                inv.setItem(slotOf(i), it);
            }
            List<String> info = new ArrayList<>();
            info.add("<gray>Série : <gold>" + (today || !missed(d) ? d.streak : 0) + " jour(s)");
            info.add("<gray>Jours récupérés au total : <white>" + d.total);
            info.add("");
            info.add(today ? "<gray>Prochaine récompense dans <white>" + time(untilNext()) : "<yellow>Ta récompense t'attend !");
            if (getConfig().getBoolean("serie-perdue-si-rate", true)) info.add("<dark_gray>Rate un jour et la série repart au jour 1.");
            inv.setItem(4, item(today ? Material.CLOCK : Material.CHEST, "<gradient:#ffd23c:#ff7ad9><b>Récompense du jour", info, !today));
            List<Event> evs = activeEvents();
            int base = inv.getSize() - 9;
            for (int i = 0; i < evs.size() && i < 3; i++) {
                Event e = evs.get(i);
                List<String> lore = new ArrayList<>(lines(e.money(), e.items(), e.desc()));
                lore.add("");
                lore.add("<gray>En plus de la récompense du jour,");
                lore.add("<gray>jusqu'au <white>" + e.until() + "</white>.");
                inv.setItem(base + 2 + i * 2 + (evs.size() == 1 ? 2 : 0), item(e.icon(), e.name(), lore, true));
            }
            inv.setItem(base + 8, item(Material.BARRIER, "<red>Fermer", List.of(), false));
        }

        void click(int slot) {
            if (slot == inv.getSize() - 1) {
                p.closeInventory();
                return;
            }
            Data d = of(p.getUniqueId());
            int next = nextDay(d);
            if (slot == 4 || (next - 1 < rewards.size() && slot == slotOf(next - 1))) {
                claim(p);
                draw();
            }
        }
    }

    List<String> lines(double money, List<String> items, List<String> desc) {
        List<String> out = new ArrayList<>();
        if (money > 0) out.add("<gold>+ " + (vault ? Money.format(money) : String.valueOf((long) money)));
        for (String s : items) {
            String[] parts = s.split(":");
            Material m = mat(parts[0], null);
            if (m == null) continue;
            out.add("<white>+ " + (parts.length > 1 ? parts[1] : "1") + " " + m.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        }
        out.addAll(desc);
        return out;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Menu m)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == e.getView().getTopInventory()) m.click(e.getSlot());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof Menu) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!getConfig().getBoolean("rappel-connexion", true)) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline() || !p.hasPermission("smpdaily.use") || claimedToday(of(p.getUniqueId()))) return;
            send(p, msg("reminder"));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.2f);
        }, 80L);
    }

    // ================================================================= commandes

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload") && s.hasPermission("smpdaily.admin")) {
            load();
            sendRaw(s, msg("reloaded"));
            return true;
        }
        if (sub.equals("reset") && s.hasPermission("smpdaily.admin")) {
            if (a.length < 2) {
                sendRaw(s, "<red>/daily reset <joueur>");
                return true;
            }
            var op = Bukkit.getOfflinePlayerIfCached(a[1]);
            if (op == null) {
                sendRaw(s, "<red>Joueur inconnu.");
                return true;
            }
            data.remove(op.getUniqueId());
            saveData();
            sendRaw(s, msg("reset").replace("<player>", a[1]));
            return true;
        }
        if (s instanceof Player p) p.openInventory(new Menu(p).getInventory());
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission("smpdaily.admin")) return out;
        if (a.length == 1) out.addAll(List.of("reset", "reload"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("reset")) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    // ================================================================= outils

    String msg(String k) {
        // sans valeur par défaut ici : les nouveaux messages du plugin marchent même avec un ancien config.yml
        String s = getConfig().getString("messages." + k);
        return s == null ? k : s;
    }

    void send(CommandSender s, String mini) {
        s.sendMessage(MM.deserialize(msg("prefix") + mini));
    }

    void sendRaw(CommandSender s, String mini) {
        s.sendMessage(MM.deserialize(mini));
    }

    static Component mm(String s) {
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    static ItemStack item(Material m, String name, List<String> lore, boolean glow) {
        ItemStack it = new ItemStack(m == null ? Material.CHEST : m);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;
        meta.displayName(mm(name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(mm(s));
        meta.lore(l);
        meta.addItemFlags(ItemFlag.values());
        if (name.isBlank()) meta.setHideTooltip(true);
        if (glow) meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
        return it;
    }

    static Material mat(String s, Material def) {
        if (s == null || s.isBlank()) return def;
        Material m = Material.matchMaterial(s.trim());
        return m == null || !m.isItem() ? def : m;
    }

    static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException ex) {
            return def;
        }
    }

    static String time(Duration d) {
        long h = d.toHours(), m = d.toMinutesPart();
        return h > 0 ? h + " h " + m + " min" : Math.max(1, m) + " min";
    }
}
