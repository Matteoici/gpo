package fr.smpsaison;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static fr.smpsaison.SmpSaison.fmt;
import static fr.smpsaison.SmpSaison.mm;

/** Menus : /metiers, /passe, /hebdo. */
final class Gui implements InventoryHolder {

    final String kind;
    final int page;
    Inventory inv;

    private Gui(String kind, int page) {
        this.kind = kind;
        this.page = page;
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    static ItemStack item(Material m, String name, List<String> lore, boolean glow) {
        ItemStack it = new ItemStack(m == null ? Material.PAPER : m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(mm(name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(mm(s));
        meta.lore(l);
        meta.addItemFlags(ItemFlag.values());
        if (glow) meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
        return it;
    }

    static String bar(double ratio, int len) {
        int full = (int) Math.round(Math.max(0, Math.min(1, ratio)) * len);
        return "<green>" + "■".repeat(full) + "<dark_gray>" + "■".repeat(len - full);
    }

    /** Icône du pack (papier + custom_model_data) : le menu du passe en parchemin. */
    static ItemStack pitem(String model, String name, List<String> lore, int amount) {
        ItemStack it = item(Material.PAPER, name, lore, false);
        it.setAmount(Math.max(1, Math.min(64, amount)));
        ItemMeta m = it.getItemMeta();
        try {
            var c = m.getCustomModelDataComponent();
            c.setStrings(List.of(model));
            m.setCustomModelDataComponent(c);
        } catch (RuntimeException | LinkageError ignored) {
        }
        if (name.isBlank()) m.setHideTooltip(true);
        it.setItemMeta(m);
        return it;
    }

    /** Titre = le fond en parchemin (police du pack), décalé pour couvrir tout le coffre. */
    static Component packTitle() {
        return packTitle('\ue100');
    }

    static Component packTitle(char glyph) {
        return Component.text("\uf801" + glyph).font(net.kyori.adventure.key.Key.key("limitless", "gui"))
                .color(net.kyori.adventure.text.format.NamedTextColor.WHITE);
    }

    private static void fillBlank(Inventory inv) {
        ItemStack blank = pitem("pass_blank", " ", List.of(), 1);
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, blank);
    }

    private static void fill(Inventory inv) {
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of(), false);
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, pane);
    }

    // ================================================================= métiers

    static void jobs(SmpSaison pl, Player p) {
        Gui g = new Gui("jobs", 0);
        g.inv = Bukkit.createInventory(g, 27, packTitle('\ue110'));
        Jobs j = pl.jobs();
        Data.P d = pl.data().of(p.getUniqueId());
        int[] slots = {11, 12, 13, 14, 15};
        int i = 0;
        for (String id : j.ids()) {
            if (i >= slots.length) break;
            double[] v = d.job(id);
            int lvl = (int) v[0];
            boolean max = lvl >= j.maxLevel();
            double ratio = max ? 1 : v[1] / j.need(lvl);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Niveau <white><b>" + lvl + "</b></white> / " + j.maxLevel());
            lore.add(bar(ratio, 20) + " <white>" + (max ? "MAX" : fmt(v[1]) + " / " + fmt(j.need(lvl)) + " XP"));
            lore.add("");
            int pr = j.prestige(p.getUniqueId(), id);
            lore.add("<gray>Gains : <gold>+" + Math.round((j.moneyMult(p.getUniqueId(), id) - 1) * 100) + " %");
            lore.add("<gray>Bonus : <aqua>" + fmt(j.bonusChance(p.getUniqueId(), id)) + " %</aqua> <gray>de " + j.bonusText(id));
            lore.add("<gray>Prestige : <gold>" + (pr == 0 ? "<dark_gray>aucun" : "★".repeat(pr)) + " <dark_gray>(" + pr + "/" + j.maxPrestige() + ")");
            if (max && pr < j.maxPrestige()) {
                lore.add("");
                lore.add("<gold><b>▶ Clic : passer Prestige " + (pr + 1) + "</b>");
                lore.add("<dark_gray>Retour au niveau 1, mais +10 % d'argent et +2 % de bonus pour toujours.");
            }
            lore.add("");
            lore.add("<dark_gray>Chaque action rapporte de l'XP de métier,");
            lore.add("<dark_gray>de l'argent et de l'XP de saison.");
            ItemStack icon = item(j.icon(id), j.name(id) + (pr > 0 ? " <gold>" + "★".repeat(pr) : ""), lore, max);
            ItemMeta im = icon.getItemMeta();
            try {
                var cmd = im.getCustomModelDataComponent();
                cmd.setStrings(List.of("job_" + id));
                im.setCustomModelDataComponent(cmd);
            } catch (RuntimeException | LinkageError ignored) {
            }
            // la barre de « durabilité » sous l'icône = la progression vers le niveau suivant
            if (im instanceof org.bukkit.inventory.meta.Damageable dm) {
                dm.setMaxDamage(1000);
                dm.setDamage(max ? 0 : (int) Math.round((1 - Math.max(0.001, Math.min(1, ratio))) * 999));
            }
            icon.setItemMeta(im);
            g.inv.setItem(slots[i], icon);
            g.inv.setItem(slots[i] + 9, pitem("ui_level", j.name(id) + " <white>niveau " + lvl, lore, lvl));
            i++;
        }
        Data.P s = pl.season(p.getUniqueId());
        g.inv.setItem(26, pitem("pass_season", "<gold>🎫 Passe de saison", List.of("<gray>Palier <white>" + pl.tierOf(s) + "</white> / " + pl.maxTier(),
                "", "<yellow>Clic : ouvrir le passe"), 1));
        fillBlank(g.inv);
        p.openInventory(g.inv);
    }

    // ================================================================= passe

    static void pass(SmpSaison pl, Player p, int page) {
        Data.P d = pl.season(p.getUniqueId());
        int tier = pl.tierOf(d);
        int pages = (pl.maxTier() + 8) / 9;
        if (page < 0) page = Math.min(pages - 1, Math.max(0, (Math.max(1, tier) - 1) / 9));
        Gui g = new Gui("pass", page);
        g.inv = Bukkit.createInventory(g, 54, packTitle());
        boolean prem = pl.premium(p, d);
        ConfigurationSection t = pl.getConfig().getConfigurationSection("season.tiers");
        int per = pl.xpPerTier();
        for (int c = 0; c < 9; c++) {
            int n = page * 9 + c + 1;
            if (n > pl.maxTier()) break;
            boolean reached = n <= tier;
            double prog = reached ? 1 : (n == tier + 1 ? (d.seasonXp - (long) tier * per) / (double) per : 0);
            g.inv.setItem(9 + c, pitem(reached ? "pass_node_on" : (n == tier + 1 ? "pass_node_cur" : "pass_node_off"),
                    (reached ? "<green>" : "<gray>") + "Palier " + n, List.of(bar(prog, 10), "<dark_gray>" + fmt((long) n * per) + " XP de saison"), n));
            for (int row = 0; row < 2; row++) {
                boolean free = row == 0;
                String base = n + "." + (free ? "free" : "premium");
                if (t == null || !t.isConfigurationSection(base)) continue;
                Material icon = Material.matchMaterial(t.getString(base + ".icon", "PAPER"));
                boolean claimed = (free ? d.claimedFree : d.claimedPremium).contains(n);
                List<String> lore = new ArrayList<>();
                lore.add("<white>" + t.getString(base + ".desc", ""));
                lore.add("");
                if (claimed) lore.add("<green>✔ Récupéré");
                else if (!reached) lore.add("<red>🔒 Palier " + n + " pas encore atteint");
                else if (!free && !prem) lore.add("<gold>🔒 Passe premium <gray>(/passe premium)");
                else lore.add("<yellow>▶ Clic pour récupérer !");
                String nm = (free ? "<white>Gratuit" : "<gold>★ Premium") + " · palier " + n;
                g.inv.setItem((free ? 18 : 36) + c, claimed ? pitem("pass_check", nm, lore, 1) : item(icon, nm, lore, reached && (free || prem)));
            }
        }
        g.inv.setItem(4, pitem("pass_season", pl.getConfig().getString("season.name", "Saison"), List.of(
                "<gray>Page <white>" + (page + 1) + "</white> / " + pages,
                "<gray>Palier <white><b>" + tier + "</b></white> / " + pl.maxTier() + " · <white>" + fmt(d.seasonXp) + "</white> XP",
                "<gray>Fin de saison : <white>" + pl.getConfig().getString("season.ends", "?"),
                "",
                "<dark_gray>XP de saison : métiers, kills, temps de jeu,",
                "<dark_gray>connexion du jour, boss, largages..."), 1));
        if (page > 0) g.inv.setItem(45, pitem("pass_prev", "<white>◀ Page précédente", List.of(), 1));
        if (page < pages - 1) g.inv.setItem(53, pitem("pass_next", "<white>Page suivante ▶", List.of(), 1));
        g.inv.setItem(49, pitem("pass_claim", "<green>Tout récupérer", List.of("<gray>Récupère tous les paliers atteints"), 1));
        g.inv.setItem(47, prem ? pitem("pass_premium_on", "<gold><b>★ Passe premium actif", List.of("<gray>La ligne du bas est à toi", "<gray>+25 % d'XP de saison"), 1)
                : pitem("pass_premium", "<gold><b>★ Passe premium", List.of("<gray>Débloque la ligne du bas : <gold>12,6 M $</gold> en tout",
                "<gray>(remboursé vers le palier 25) + clés et pioches", "<gray>et <white>+25 % d'XP de saison</white> pour monter plus vite",
                "<gray>Prix : <gold>" + fmt(pl.getConfig().getDouble("season.premium-price", 5000000)) + " " + pl.money(), "", "<yellow>Clic pour l'acheter"), 1));
        g.inv.setItem(51, pitem("pass_week", "<aqua>🏆 Classements de la semaine", List.of("<yellow>Clic : /hebdo"), 1));
        fillBlank(g.inv);
        p.openInventory(g.inv);
    }

    // ================================================================= semaine

    static void weekly(SmpSaison pl, Player p) {
        Gui g = new Gui("weekly", 0);
        g.inv = Bukkit.createInventory(g, 27, packTitle('\ue111'));
        ConfigurationSection boards = pl.getConfig().getConfigurationSection("weekly.boards");
        Duration left = Duration.between(ZonedDateTime.now(pl.zone()), pl.nextReset());
        String time = left.toDays() + " j " + left.toHoursPart() + " h " + left.toMinutesPart() + " min";
        int[] slots = {13, 10, 11, 12, 14, 15, 16}; // le premier (XP de saison) sur le podium doré
        int i = 0;
        if (boards != null) for (String b : boards.getKeys(false)) {
            if (i >= slots.length) break;
            List<SmpSaison.Rank> top = pl.top(b, 10);
            List<String> lore = new ArrayList<>();
            for (int k = 0; k < top.size(); k++) {
                SmpSaison.Rank r = top.get(k);
                String medal = k == 0 ? "<gold>🥇" : k == 1 ? "<gray>🥈" : k == 2 ? "<#cd7f32>🥉" : "<dark_gray>" + (k + 1) + ".";
                lore.add(medal + " <white>" + SmpSaison.MM.escapeTags(r.name()) + " <dark_gray>— <yellow>" + fmt(r.value()));
            }
            if (top.isEmpty()) lore.add("<dark_gray>Personne pour l'instant... à toi de jouer !");
            lore.add("");
            double mine = pl.data().of(p.getUniqueId()).weekly.getOrDefault(b, 0.0);
            lore.add("<gray>Toi : <white>" + fmt(mine));
            lore.add("");
            lore.add("<gold>Récompenses :");
            for (int k = 1; k <= 3; k++) {
                List<String> rw = boards.getStringList(b + ".rewards." + k);
                if (rw.isEmpty()) continue;
                lore.add("<gray>" + k + (k == 1 ? "er" : "e") + " : <white>" + describe(pl, rw));
            }
            lore.add("");
            lore.add("<dark_gray>Fin dans " + time);
            g.inv.setItem(slots[i++], item(Material.matchMaterial(boards.getString(b + ".icon", "PAPER")), boards.getString(b + ".name", b), lore, false));
        }
        g.inv.setItem(22, pitem("pass_season", "<gold>🎫 Passe de saison", List.of("<yellow>Clic : /passe"), 1));
        fillBlank(g.inv);
        p.openInventory(g.inv);
    }

    private static String describe(SmpSaison pl, List<String> rewards) {
        List<String> out = new ArrayList<>();
        for (String r : rewards) {
            if (r.startsWith("money:")) out.add(fmt(Double.parseDouble(r.substring(6))) + " " + pl.money());
            else if (r.contains("caisse cle")) {
                String[] p = r.split(" ");
                out.add("clé " + (p.length > 3 ? p[3].replace("epique", "épique").replace("legendaire", "légendaire") : "?"));
            } else out.add(r.replace("%player%", "toi"));
        }
        return String.join(" + ", out);
    }

    // ================================================================= clics

    private static final java.util.Map<String, Long> CONFIRM = new java.util.HashMap<>();

    static final class Listen implements Listener {
        private final SmpSaison pl;

        Listen(SmpSaison pl) {
            this.pl = pl;
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
            switch (g.kind) {
                case "jobs" -> {
                    if (s == 26) pass(pl, p, -1);
                    else if (s >= 11 && s <= 15) {
                        List<String> ids = pl.jobs().ids();
                        int k = s - 11;
                        if (k >= ids.size()) return;
                        String job = ids.get(k);
                        if (pl.data().of(p.getUniqueId()).level(job) < pl.jobs().maxLevel()) return;
                        // deux clics pour confirmer
                        String key = p.getUniqueId() + ":" + job;
                        Long t = CONFIRM.get(key);
                        if (t == null || t < System.currentTimeMillis()) {
                            CONFIRM.put(key, System.currentTimeMillis() + 5000);
                            p.sendMessage(mm("<gold>★ Reclique dans les 5 s pour passer Prestige en " + pl.jobs().name(job) + " <gray>(retour au niveau 1)."));
                            return;
                        }
                        CONFIRM.remove(key);
                        String err = pl.jobs().doPrestige(p, job);
                        if (err != null) p.sendMessage(mm(err));
                        jobs(pl, p);
                    }
                }
                case "weekly" -> {
                    if (s == 22) pass(pl, p, -1);
                }
                case "pass" -> {
                    int pages = (pl.maxTier() + 8) / 9;
                    if (s == 45 && g.page > 0) pass(pl, p, g.page - 1);
                    else if (s == 53 && g.page < pages - 1) pass(pl, p, g.page + 1);
                    else if (s == 49) {
                        int n = pl.claimAll(p);
                        p.sendMessage(mm(n == 0 ? "<gray>Rien à récupérer pour l'instant." : "<green>✔ " + n + " récompense(s) récupérée(s) !"));
                        pass(pl, p, g.page);
                    } else if (s == 47) {
                        pl.buyPremium(p);
                        pass(pl, p, g.page);
                    } else if (s == 51) weekly(pl, p);
                    else if ((s >= 18 && s <= 26) || (s >= 36 && s <= 44)) {
                        boolean free = s <= 26;
                        int tier = g.page * 9 + (s - (free ? 18 : 36)) + 1;
                        String err = pl.claim(p, tier, free);
                        if (err != null) {
                            p.sendMessage(mm(err));
                            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
                        }
                        pass(pl, p, g.page);
                    }
                }
                default -> {
                }
            }
        }
    }
}
