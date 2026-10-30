package fr.smptitles;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Titres : chaque joueur a des compteurs (KOTH gagnés, primes, boss...). Atteindre un palier débloque un titre.
 * Le joueur choisit son titre avec /titres ; il s'affiche devant son pseudo (chat et TAB).
 */
public final class SmpTitles extends JavaPlugin implements Listener, TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private record Title(String id, String display, String description, Map<String, Integer> requires) {
    }

    private static final class Data {
        final Map<String, Integer> counters = new LinkedHashMap<>();
        final Set<String> unlocked = new LinkedHashSet<>();
        /** titres donnés à la main avec un texte libre (ex. titres secrets du boss) */
        final Map<String, String> custom = new LinkedHashMap<>();
        String active;
        /** ce que le joueur veut au-dessus de sa tête (null = réglage par défaut de la config) */
        Boolean tagTitle, tagGuild, tagMoney;
    }

    private final Map<String, Title> titles = new LinkedHashMap<>();
    private final Map<UUID, Data> data = new HashMap<>();
    private File file;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // titres de la Reine Araignée (ajoutés aux configs déjà installées)
        if (!getConfig().isSet("titles.reine_1")) {
            getConfig().set("counters.reine", "Reines Araignées vaincues");
            getConfig().set("titles.reine_1.display", "<#5aa0ff>🕷 Briseur de Toiles");
            getConfig().set("titles.reine_1.description", "Vaincre la Reine Araignée");
            getConfig().set("titles.reine_1.requires.reine", 1);
            getConfig().set("titles.reine_10.display", "<gradient:#3a6bff:#e1ebff><b>🕷 Fléau de la Reine");
            getConfig().set("titles.reine_10.description", "Vaincre 10 fois la Reine Araignée");
            getConfig().set("titles.reine_10.requires.reine", 10);
            saveConfig();
        }
        file = new File(getDataFolder(), "players.yml");
        loadTitles();
        loadData();
        getServer().getPluginManager().registerEvents(this, this);
        for (String c : List.of("titres", "titre", "nametag")) {
            var cmd = getCommand(c);
            if (cmd != null) {
                cmd.setExecutor(this);
                cmd.setTabCompleter(this);
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) apply(p);
        // l'argent et la guilde changent : on met le name tag à jour régulièrement
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) refreshTag(p, titleOf(p));
        }, 100L, 100L);
    }

    @Override
    public void onDisable() {
        saveData();
        for (Player p : Bukkit.getOnlinePlayers()) nameTag(p, null);
    }

    private static Component mm(String s) {
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private String msg(String k) {
        String s = getConfig().getString("messages." + k);
        return s == null ? "<red>[" + k + "]" : s;
    }

    // ================================================================= données

    private void loadTitles() {
        titles.clear();
        ConfigurationSection s = getConfig().getConfigurationSection("titles");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection t = s.getConfigurationSection(id);
            if (t == null) continue;
            Map<String, Integer> req = new LinkedHashMap<>();
            ConfigurationSection r = t.getConfigurationSection("requires");
            if (r != null) for (String k : r.getKeys(false)) req.put(k.toLowerCase(Locale.ROOT), r.getInt(k));
            titles.put(id.toLowerCase(Locale.ROOT), new Title(id.toLowerCase(Locale.ROOT), t.getString("display", id),
                    t.getString("description", ""), req));
        }
    }

    private void loadData() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String k : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(k);
            if (s == null) continue;
            Data d = new Data();
            ConfigurationSection c = s.getConfigurationSection("counters");
            if (c != null) for (String n : c.getKeys(false)) d.counters.put(n, c.getInt(n));
            d.unlocked.addAll(s.getStringList("unlocked"));
            ConfigurationSection cu = s.getConfigurationSection("custom");
            if (cu != null) for (String n : cu.getKeys(false)) d.custom.put(n, cu.getString(n));
            d.active = s.getString("active");
            if (s.isSet("nametag.titre")) d.tagTitle = s.getBoolean("nametag.titre");
            if (s.isSet("nametag.guilde")) d.tagGuild = s.getBoolean("nametag.guilde");
            if (s.isSet("nametag.argent")) d.tagMoney = s.getBoolean("nametag.argent");
            try {
                data.put(UUID.fromString(k), d);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : data.entrySet()) {
            String k = e.getKey().toString();
            Data d = e.getValue();
            for (var c : d.counters.entrySet()) y.set(k + ".counters." + c.getKey(), c.getValue());
            y.set(k + ".unlocked", new ArrayList<>(d.unlocked));
            for (var c : d.custom.entrySet()) y.set(k + ".custom." + c.getKey(), c.getValue());
            y.set(k + ".active", d.active);
            y.set(k + ".nametag.titre", d.tagTitle);
            y.set(k + ".nametag.guilde", d.tagGuild);
            y.set(k + ".nametag.argent", d.tagMoney);
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            getLogger().warning("players.yml : " + ex.getMessage());
        }
    }

    private Data of(UUID id) {
        return data.computeIfAbsent(id, k -> new Data());
    }

    /** Texte du titre (config ou titre libre). */
    private String display(Data d, String id) {
        Title t = titles.get(id);
        if (t != null) return t.display();
        return d.custom.get(id);
    }

    // ================================================================= affichage

    /** Titre à mettre devant chaque message de chat (lu depuis le fil du chat, donc thread-safe). */
    private final Map<UUID, Component> chatPrefix = new java.util.concurrent.ConcurrentHashMap<>();

    /** Nom TAB d'origine (grade mis par un autre plugin...) et dernier nom TAB qu'on a mis nous-mêmes. */
    private final Map<UUID, Component> tabBase = new HashMap<>();
    private final Map<UUID, Component> tabSet = new HashMap<>();

    private void apply(Player p) {
        Data d = data.get(p.getUniqueId());
        String disp = d == null || d.active == null ? null : display(d, d.active);
        Component title = disp == null ? null : mm(getConfig().getString("format", "<title> ").replace("<title>", disp));
        // chat : inséré devant le pseudo dans le message déjà mis en forme (voir onChat)
        if (title != null && getConfig().getBoolean("show-in-chat", true)) chatPrefix.put(p.getUniqueId(), title);
        else chatPrefix.remove(p.getUniqueId());
        if (getConfig().getBoolean("show-in-tab", true)) tab(p, title);
        refreshTag(p, title);
    }

    // ================================================================= name tag personnalisable (/nametag)

    private boolean tagTitle(Data d) {
        return d != null && d.tagTitle != null ? d.tagTitle : getConfig().getBoolean("nametag.defaut.titre", true);
    }

    private boolean tagGuild(Data d) {
        return d != null && d.tagGuild != null ? d.tagGuild : getConfig().getBoolean("nametag.defaut.guilde", true);
    }

    private boolean tagMoney(Data d) {
        return d != null && d.tagMoney != null ? d.tagMoney : getConfig().getBoolean("nametag.defaut.argent", false);
    }

    private Component titleOf(Player p) {
        Data d = data.get(p.getUniqueId());
        String disp = d == null || d.active == null ? null : display(d, d.active);
        return disp == null ? null : mm(getConfig().getString("format", "<title> ").replace("<title>", disp));
    }

    /** Au-dessus de la tête : [guilde] titre Pseudo argent, selon les choix du joueur. */
    private void refreshTag(Player p, Component title) {
        if (!getConfig().getBoolean("show-above-head", true)) {
            nameTag(p, null, null);
            return;
        }
        Data d = data.get(p.getUniqueId());
        Component prefix = Component.empty();
        boolean any = false;
        if (tagGuild(d)) {
            Component g = guildTag(p);
            if (g != null) {
                prefix = prefix.append(g).append(Component.text(" "));
                any = true;
            }
        }
        if (tagTitle(d) && title != null) {
            prefix = prefix.append(title);
            any = true;
        }
        Component suffix = null;
        if (tagMoney(d)) {
            Double bal = balance(p);
            if (bal != null) {
                suffix = mm(getConfig().getString("nametag.format-argent", " <gold><money> ⛁").replace("<money>", compact(bal)));
                any = true;
            }
        }
        nameTag(p, any ? prefix : null, suffix);
    }

    /** Tag de guilde [TAG] (SmpGuilds), ou null. */
    private Component guildTag(Player p) {
        try {
            var pl = Bukkit.getPluginManager().getPlugin("SmpGuilds");
            if (pl == null || !pl.isEnabled()) return null;
            Object gm = pl.getClass().getMethod("guilds").invoke(pl);
            Object g = gm.getClass().getMethod("of", UUID.class).invoke(gm, p.getUniqueId());
            if (g == null) return null;
            return (Component) g.getClass().getMethod("tagComponent").invoke(g);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            return null;
        }
    }

    /** Argent du joueur (Vault), ou null. */
    private Double balance(Player p) {
        try {
            for (Class<?> c : Bukkit.getServicesManager().getKnownServices()) {
                if (!c.getName().equals("net.milkbowl.vault.economy.Economy")) continue;
                var reg = Bukkit.getServicesManager().getRegistration(c);
                if (reg == null) return null;
                Object eco = reg.getProvider();
                return (Double) c.getMethod("getBalance", OfflinePlayer.class).invoke(eco, p);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }
        return null;
    }

    static String compact(double v) {
        double a = Math.abs(v);
        if (a < 1000) return String.valueOf((long) v);
        String[] u = {"k", "M", "Md", "B"};
        int i = -1;
        while (a >= 1000 && i < u.length - 1) {
            a /= 1000;
            v /= 1000;
            i++;
        }
        String n = String.format(Locale.ROOT, a >= 100 ? "%.0f" : "%.1f", v);
        if (n.endsWith(".0")) n = n.substring(0, n.length() - 2);
        return n + u[i];
    }

    private final class TagMenu implements InventoryHolder {
        final Inventory inv;
        final Player viewer;

        TagMenu(Player viewer) {
            this.viewer = viewer;
            this.inv = Bukkit.createInventory(this, 27, mm("<dark_gray>Au-dessus de ta tête"));
            draw();
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }

        void draw() {
            Data d = of(viewer.getUniqueId());
            ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
            ItemMeta pm = pane.getItemMeta();
            pm.setHideTooltip(true);
            pane.setItemMeta(pm);
            for (int i = 0; i < 27; i++) inv.setItem(i, pane);
            // aperçu
            Component g = guildTag(viewer), t = titleOf(viewer);
            Double bal = balance(viewer);
            Component preview = Component.empty();
            if (tagGuild(d) && g != null) preview = preview.append(g).append(Component.text(" "));
            if (tagTitle(d) && t != null) preview = preview.append(t);
            preview = preview.append(Component.text(viewer.getName(), net.kyori.adventure.text.format.NamedTextColor.WHITE));
            if (tagMoney(d) && bal != null) preview = preview.append(mm(getConfig().getString("nametag.format-argent", " <gold><money> ⛁").replace("<money>", compact(bal))));
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta hm = head.getItemMeta();
            if (hm instanceof org.bukkit.inventory.meta.SkullMeta sm) sm.setOwningPlayer(viewer);
            hm.displayName(preview.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            hm.lore(List.of(mm("<gray>Voilà ce que les autres voient"), mm("<gray>au-dessus de ta tête.")));
            head.setItemMeta(hm);
            inv.setItem(4, head);
            inv.setItem(11, toggle(Material.NAME_TAG, "Titre", tagTitle(d), t == null ? "<dark_gray>(tu n'as pas de titre équipé : /titres)" : "<gray>Actuel : " + MM.serialize(t)));
            inv.setItem(13, toggle(Material.WHITE_BANNER, "Guilde", tagGuild(d), g == null ? "<dark_gray>(tu n'es dans aucune guilde)" : "<gray>Tag : " + MM.serialize(g)));
            inv.setItem(15, toggle(Material.GOLD_INGOT, "Argent", tagMoney(d), bal == null ? "<dark_gray>(pas d'économie)" : "<gray>Actuel : <gold>" + compact(bal) + " ⛁"));
            inv.setItem(22, item(Material.BARRIER, "<red>Fermer", List.of(), false));
        }

        private ItemStack toggle(Material m, String name, boolean on, String info) {
            return item(m, (on ? "<green>✔ " : "<red>✘ ") + "<white><b>" + name, List.of(mm(info), Component.empty(),
                    mm(on ? "<green>Affiché <gray>- clic pour le cacher" : "<red>Caché <gray>- clic pour l'afficher")), on);
        }

        void click(int slot) {
            Data d = of(viewer.getUniqueId());
            switch (slot) {
                case 11 -> d.tagTitle = !tagTitle(d);
                case 13 -> d.tagGuild = !tagGuild(d);
                case 15 -> d.tagMoney = !tagMoney(d);
                case 22 -> {
                    viewer.closeInventory();
                    return;
                }
                default -> {
                    return;
                }
            }
            viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.3f);
            refreshTag(viewer, titleOf(viewer));
            saveData();
            draw();
        }
    }

    /** TAB : on garde ce qu'un autre plugin a mis (grade) et on glisse le titre juste devant le pseudo. */
    private void tab(Player p, Component title) {
        UUID id = p.getUniqueId();
        Component cur = p.playerListName();
        Component last = tabSet.get(id);
        Component base = last != null && last.equals(cur) ? tabBase.getOrDefault(id, cur) : cur;
        tabBase.put(id, base);
        Component out = title == null ? base : beforeName(base, p.getName(), title);
        if (!out.equals(cur)) p.playerListName(out);
        tabSet.put(id, out);
    }

    /** Insère "title" juste devant le pseudo (après le grade) ; tout devant si le pseudo n'y est pas tel quel. */
    static Component beforeName(Component c, String name, Component title) {
        Component r = c.replaceText(b -> b.matchLiteral(name).once().replacement(m -> title.append(m)));
        return r.equals(c) ? title.append(c) : r;
    }

    private static String teamName(UUID id) {
        return "smpt_" + id.toString().replace("-", "").substring(0, 12);
    }

    private void nameTag(Player p, Component prefix) {
        nameTag(p, prefix, null);
    }

    private void nameTag(Player p, Component prefix, Component suffix) {
        org.bukkit.scoreboard.Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        String name = teamName(p.getUniqueId());
        org.bukkit.scoreboard.Team team = sb.getTeam(name);
        if (prefix == null && suffix == null) {
            if (team != null) team.unregister();
            return;
        }
        org.bukkit.scoreboard.Team other = sb.getEntryTeam(p.getName());
        if (other != null && !other.getName().equals(name)) {
            // déjà dans l'équipe d'un autre plugin (grade au-dessus de la tête) : on ne la lui vole pas
            if (team != null) team.unregister();
            return;
        }
        if (team == null) team = sb.registerNewTeam(name);
        Component pre = prefix == null ? Component.empty() : prefix, suf = suffix == null ? Component.empty() : suffix;
        if (!team.prefix().equals(pre)) team.prefix(pre);
        if (!team.suffix().equals(suf)) team.suffix(suf);
        if (!team.hasEntry(p.getName())) team.addEntry(p.getName());
    }

    /** On passe en dernier : le titre est ajouté devant le message déjà mis en forme par le plugin de chat. */
    /** On passe en dernier : le titre est glissé devant le pseudo, dans le message déjà mis en forme (grade compris). */
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(io.papermc.paper.event.player.AsyncChatEvent e) {
        Component title = chatPrefix.get(e.getPlayer().getUniqueId());
        if (title == null) return;
        String name = e.getPlayer().getName();
        io.papermc.paper.chat.ChatRenderer r = e.renderer();
        e.renderer((source, displayName, message, viewer) -> beforeName(r.render(source, displayName, message, viewer), name, title));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            // titres débloqués depuis (nouveau palier ajouté dans la config...)
            check(p.getUniqueId(), p);
            apply(p);
        }, 3L);
        // les plugins de grade mettent souvent le nom TAB un peu après : on repasse derrière eux
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) apply(p);
        }, 60L);
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        chatPrefix.remove(e.getPlayer().getUniqueId());
        tabBase.remove(e.getPlayer().getUniqueId());
        tabSet.remove(e.getPlayer().getUniqueId());
        nameTag(e.getPlayer(), null);
    }

    // ================================================================= compteurs et déblocage

    private void win(OfflinePlayer op, String counter, int n) {
        Data d = of(op.getUniqueId());
        d.counters.merge(counter.toLowerCase(Locale.ROOT), n, Integer::sum);
        check(op.getUniqueId(), op.getPlayer());
        saveData();
    }

    private void check(UUID id, Player online) {
        Data d = of(id);
        for (Title t : titles.values()) {
            if (d.unlocked.contains(t.id()) || t.requires().isEmpty()) continue;
            boolean ok = true;
            for (var r : t.requires().entrySet()) if (d.counters.getOrDefault(r.getKey(), 0) < r.getValue()) ok = false;
            if (!ok) continue;
            d.unlocked.add(t.id());
            if (d.active == null) d.active = t.id();
            if (online != null) announce(online, t.display());
        }
        if (online != null) apply(online);
    }

    private void announce(Player p, String display) {
        p.sendMessage(mm(msg("prefix") + msg("unlocked").replace("<title>", display)));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
    }

    // ================================================================= menu /titres

    private final class Menu implements InventoryHolder {
        final Inventory inv;
        final Player viewer;
        final List<String> ids = new ArrayList<>();

        Menu(Player viewer) {
            this.viewer = viewer;
            this.inv = Bukkit.createInventory(this, 54, mm(msg("menu-title")));
            draw();
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }

        void draw() {
            inv.clear();
            ids.clear();
            Data d = of(viewer.getUniqueId());
            ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
            ItemMeta pm = pane.getItemMeta();
            pm.setHideTooltip(true);
            pane.setItemMeta(pm);
            for (int i = 0; i < 54; i++) if (i < 9 || i >= 45) inv.setItem(i, pane);
            // en-tête : compteurs
            List<Component> lore = new ArrayList<>();
            ConfigurationSection cn = getConfig().getConfigurationSection("counters");
            if (cn != null) {
                for (String k : cn.getKeys(false)) {
                    lore.add(mm("<gray>" + cn.getString(k) + " : <white>" + d.counters.getOrDefault(k, 0)));
                }
            }
            String act = d.active == null ? null : display(d, d.active);
            lore.add(Component.empty());
            lore.add(mm("<gray>Titre actuel : " + (act == null ? "<dark_gray>aucun" : act)));
            inv.setItem(4, item(Material.NAME_TAG, "<gold><b>Mes titres", lore, false));
            int slot = 9;
            List<String> all = new ArrayList<>(titles.keySet());
            for (String c : d.custom.keySet()) if (!all.contains(c)) all.add(c);
            for (String id : all) {
                if (slot >= 45) break;
                boolean has = d.unlocked.contains(id) || d.custom.containsKey(id);
                boolean active = id.equals(d.active);
                Title t = titles.get(id);
                List<Component> l = new ArrayList<>();
                if (t != null && !t.description().isEmpty()) l.add(mm("<gray>" + t.description()));
                if (!has && t != null) {
                    for (var r : t.requires().entrySet()) {
                        String label = cn == null ? r.getKey() : cn.getString(r.getKey(), r.getKey());
                        int cur = Math.min(r.getValue(), d.counters.getOrDefault(r.getKey(), 0));
                        l.add(mm("<dark_gray>" + label + " : " + cur + "/" + r.getValue()));
                    }
                }
                l.add(Component.empty());
                l.add(mm(active ? "<green>✔ Équipé <gray>(clic pour retirer)" : has ? "<yellow>▶ Clic : équiper" : "<red>🔒 Pas encore débloqué"));
                String disp = display(d, id);
                inv.setItem(slot, item(has ? (active ? Material.ENCHANTED_BOOK : Material.NAME_TAG) : Material.GRAY_DYE,
                        has ? disp : "<dark_gray>" + MM.stripTags(disp == null ? id : disp), l, active));
                ids.add(id);
                slot++;
            }
            inv.setItem(49, item(Material.BARRIER, "<red>Fermer", List.of(), false));
        }

        void click(int slot) {
            if (slot == 49) {
                viewer.closeInventory();
                return;
            }
            int idx = slot - 9;
            if (idx < 0 || idx >= ids.size()) return;
            String id = ids.get(idx);
            Data d = of(viewer.getUniqueId());
            if (!d.unlocked.contains(id) && !d.custom.containsKey(id)) {
                viewer.sendMessage(mm(msg("prefix") + msg("locked")));
                viewer.playSound(viewer.getLocation(), Sound.BLOCK_CHEST_LOCKED, 0.7f, 1.3f);
                return;
            }
            if (id.equals(d.active)) {
                d.active = null;
                viewer.sendMessage(mm(msg("prefix") + msg("removed")));
            } else {
                d.active = id;
                viewer.sendMessage(mm(msg("prefix") + msg("equipped").replace("<title>", display(d, id))));
            }
            viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.3f);
            apply(viewer);
            saveData();
            draw();
        }
    }

    private static ItemStack item(Material m, String name, List<Component> lore, boolean glow) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(mm(name));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.values());
        if (glow) meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Object h = e.getView().getTopInventory().getHolder(false);
        if (h instanceof TagMenu tm) {
            e.setCancelled(true);
            if (e.getClickedInventory() == e.getView().getTopInventory()) tm.click(e.getSlot());
            return;
        }
        if (!(h instanceof Menu m)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == e.getView().getTopInventory()) m.click(e.getSlot());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Object h = e.getView().getTopInventory().getHolder(false);
        if (h instanceof Menu || h instanceof TagMenu) e.setCancelled(true);
    }

    // ================================================================= commandes

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (cmd.getName().equalsIgnoreCase("nametag")) {
            if (s instanceof Player p) p.openInventory(new TagMenu(p).getInventory());
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("titres")) {
            if (s instanceof Player p) p.openInventory(new Menu(p).getInventory());
            return true;
        }
        if (a.length == 0) {
            s.sendMessage(mm("<gold>/titre win <joueur> <compteur> [n]</gold> <gray>- compte une victoire (koth, bounty, boss, event...)"));
            s.sendMessage(mm("<gold>/titre give <joueur> <id> [texte]</gold> <gray>- donne un titre (texte = titre libre)"));
            s.sendMessage(mm("<gold>/titre take <joueur> <id></gold> · <gold>/titre list</gold> · <gold>/titre reload"));
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                reloadConfig();
                loadTitles();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    check(p.getUniqueId(), p);
                    apply(p);
                }
                s.sendMessage(mm("<green>SmpTitles rechargé (" + titles.size() + " titres)."));
            }
            case "list" -> {
                for (Title t : titles.values()) s.sendMessage(mm("<gray>" + t.id() + " : " + t.display() + " <dark_gray>" + t.requires()));
            }
            case "win" -> {
                if (a.length < 3) return true;
                OfflinePlayer op = Bukkit.getOfflinePlayer(a[1]);
                int n = 1;
                if (a.length >= 4) {
                    try {
                        n = Integer.parseInt(a[3]);
                    } catch (NumberFormatException ignored) {
                    }
                }
                win(op, a[2], n);
                if (!(s instanceof org.bukkit.command.ConsoleCommandSender)) {
                    s.sendMessage(mm(msg("prefix") + msg("counted").replace("<player>", a[1]).replace("<counter>", a[2])
                            .replace("<n>", String.valueOf(of(op.getUniqueId()).counters.getOrDefault(a[2].toLowerCase(Locale.ROOT), 0)))));
                }
            }
            case "give" -> {
                if (a.length < 3) return true;
                OfflinePlayer op = Bukkit.getOfflinePlayer(a[1]);
                String id = a[2].toLowerCase(Locale.ROOT);
                Data d = of(op.getUniqueId());
                String disp;
                if (a.length > 3) {
                    disp = String.join(" ", java.util.Arrays.copyOfRange(a, 3, a.length));
                    if (d.custom.containsKey(id)) return true; // déjà donné
                    d.custom.put(id, disp);
                } else {
                    Title t = titles.get(id);
                    if (t == null) {
                        s.sendMessage(mm(msg("prefix") + msg("unknown").replace("<id>", id)));
                        return true;
                    }
                    if (!d.unlocked.add(id)) return true;
                    disp = t.display();
                }
                if (d.active == null) d.active = id;
                Player online = op.getPlayer();
                if (online != null) {
                    announce(online, disp);
                    apply(online);
                }
                saveData();
                if (!(s instanceof org.bukkit.command.ConsoleCommandSender)) {
                    s.sendMessage(mm(msg("prefix") + msg("given").replace("<title>", disp).replace("<player>", a[1])));
                }
            }
            case "take" -> {
                if (a.length < 3) return true;
                OfflinePlayer op = Bukkit.getOfflinePlayer(a[1]);
                String id = a[2].toLowerCase(Locale.ROOT);
                Data d = of(op.getUniqueId());
                d.unlocked.remove(id);
                d.custom.remove(id);
                if (id.equals(d.active)) d.active = null;
                if (op.getPlayer() != null) apply(op.getPlayer());
                saveData();
                s.sendMessage(mm(msg("prefix") + msg("taken").replace("<player>", a[1])));
            }
            default -> s.sendMessage(mm("<red>/titre win|give|take|list|reload"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String l, String[] a) {
        List<String> out = new ArrayList<>();
        if (!cmd.getName().equalsIgnoreCase("titre")) return out;
        if (a.length == 1) out.addAll(List.of("win", "give", "take", "list", "reload"));
        else if (a.length == 2) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        else if (a.length == 3 && a[0].equalsIgnoreCase("win")) {
            ConfigurationSection cn = getConfig().getConfigurationSection("counters");
            if (cn != null) out.addAll(cn.getKeys(false));
        } else if (a.length == 3) out.addAll(titles.keySet());
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
