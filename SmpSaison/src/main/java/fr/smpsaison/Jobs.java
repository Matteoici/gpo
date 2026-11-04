package fr.smpsaison;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Les métiers : Mineur, Bûcheron, Fermier, Chasseur, Pêcheur.
 * Chaque action rapporte de l'XP de métier (niveaux 1 à 50) et de l'argent ; plus le niveau est haut, plus ça paie
 * et plus le bonus du métier (objets en double) est fréquent. Un bloc posé par un joueur ne rapporte rien.
 */
final class Jobs implements Listener {

    record Gain(double xp, double money) {
    }

    private final SmpSaison plugin;
    private final Map<String, String> names = new LinkedHashMap<>();
    private final Map<String, Material> icons = new HashMap<>();
    private final Map<String, String> bonusText = new HashMap<>();
    /** bloc -> (métier, gain) */
    private final Map<Material, String> blockJob = new EnumMap<>(Material.class);
    private final Map<Material, Gain> blockGain = new EnumMap<>(Material.class);
    private final Map<EntityType, Gain> mobGain = new EnumMap<>(EntityType.class);
    private Gain fish = new Gain(10, 14);
    /** blocs posés par des joueurs (ne rapportent rien quand on les casse) */
    private final Set<Long> placed = new LinkedHashSet<>();
    /** résumé des gains à afficher : joueur -> [xp, argent, métier] */
    private final Map<UUID, Object[]> bar = new HashMap<>();

    Jobs(SmpSaison plugin) {
        this.plugin = plugin;
    }

    void load() {
        names.clear();
        blockJob.clear();
        blockGain.clear();
        mobGain.clear();
        ConfigurationSection js = plugin.getConfig().getConfigurationSection("jobs");
        if (js == null) return;
        for (String j : js.getKeys(false)) {
            names.put(j, js.getString(j + ".name", j));
            Material ic = Material.matchMaterial(js.getString(j + ".icon", "PAPER"));
            icons.put(j, ic == null ? Material.PAPER : ic);
            bonusText.put(j, js.getString(j + ".bonus", ""));
            ConfigurationSection b = js.getConfigurationSection(j + ".blocks");
            if (b != null) for (String k : b.getKeys(false)) {
                Material m = Material.matchMaterial(k);
                List<?> v = b.getList(k);
                if (m == null || v == null || v.size() < 2) continue;
                blockJob.put(m, j);
                blockGain.put(m, new Gain(num(v.get(0)), num(v.get(1))));
            }
            ConfigurationSection mb = js.getConfigurationSection(j + ".mobs");
            if (mb != null) for (String k : mb.getKeys(false)) {
                List<?> v = mb.getList(k);
                if (v == null || v.size() < 2) continue;
                try {
                    mobGain.put(EntityType.valueOf(k.toUpperCase()), new Gain(num(v.get(0)), num(v.get(1))));
                } catch (IllegalArgumentException ignored) {
                }
            }
            List<?> c = js.getList(j + ".catch");
            if (c != null && c.size() >= 2) fish = new Gain(num(c.get(0)), num(c.get(1)));
        }
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(o));
    }

    List<String> ids() {
        return new ArrayList<>(names.keySet());
    }

    String name(String j) {
        return names.getOrDefault(j, j);
    }

    Material icon(String j) {
        return icons.getOrDefault(j, Material.PAPER);
    }

    String bonusText(String j) {
        return bonusText.getOrDefault(j, "");
    }

    int maxLevel() {
        return plugin.getConfig().getInt("jobs-settings.max-level", 50);
    }

    /** XP pour passer du niveau lvl au suivant. */
    double need(int lvl) {
        return plugin.getConfig().getDouble("jobs-settings.xp-base", 100) * Math.pow(lvl, plugin.getConfig().getDouble("jobs-settings.xp-exponent", 1.55));
    }

    /** % de chance du bonus (objets en double). */
    double bonusChance(int lvl) {
        return lvl * plugin.getConfig().getDouble("jobs-settings.bonus-per-level", 0.6);
    }

    int maxPrestige() {
        return plugin.getConfig().getInt("jobs-settings.prestige.max", 5);
    }

    int prestige(UUID id, String job) {
        return plugin.data().of(id).prestige.getOrDefault(job, 0);
    }

    /** bonus (% d'objets en double) avec le prestige */
    double bonusChance(UUID id, String job) {
        return bonusChance(plugin.data().of(id).level(job)) + prestige(id, job) * plugin.getConfig().getDouble("jobs-settings.prestige.bonus-chance", 2);
    }

    /** multiplicateur d'argent avec le prestige */
    double moneyMult(UUID id, String job) {
        return moneyMult(plugin.data().of(id).level(job)) * (1 + prestige(id, job) * plugin.getConfig().getDouble("jobs-settings.prestige.money", 0.10));
    }

    /** Niveau max -> Prestige : retour au niveau 1, bonus permanent, récompense. Renvoie un message d'erreur ou null. */
    String doPrestige(Player p, String job) {
        Data.P d = plugin.data().of(p.getUniqueId());
        if (d.level(job) < maxLevel()) return "<red>Il faut être niveau " + maxLevel() + " pour passer Prestige.";
        int pr = prestige(p.getUniqueId(), job);
        if (pr >= maxPrestige()) return "<gold>Tu as déjà le prestige maximum dans ce métier. ★";
        d.prestige.put(job, pr + 1);
        double[] j = d.job(job);
        j[0] = 1;
        j[1] = 0;
        plugin.data().dirty = true;
        plugin.addSeasonXp(p.getUniqueId(), plugin.getConfig().getDouble("jobs-settings.prestige.season-xp", 1000));
        for (String c : plugin.getConfig().getStringList("jobs-settings.prestige.rewards")) {
            if (c.startsWith("money:")) Bank.give(p, Double.parseDouble(c.substring(6)));
            else Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("%player%", p.getName()));
        }
        String stars = "★".repeat(pr + 1);
        p.showTitle(net.kyori.adventure.title.Title.title(SmpSaison.mm("<gold><b>" + stars + " PRESTIGE " + (pr + 1)), SmpSaison.mm(name(job) + " <gray>· retour au niveau 1, bonus permanent")));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.8f);
        Bukkit.broadcast(SmpSaison.mm("<gold>" + stars + " <white>" + p.getName() + "</white> est passé <b>Prestige " + (pr + 1) + "</b> en " + name(job) + " !"));
        return null;
    }

    double moneyMult(int lvl) {
        return 1 + (lvl - 1) * plugin.getConfig().getDouble("jobs-settings.money-bonus-per-level", 0.02);
    }

    // ================================================================= gains

    private void gain(Player p, String job, Gain g, double ratio) {
        if (g == null || ratio <= 0) return;
        Data.P d = plugin.data().of(p.getUniqueId());
        double[] j = d.job(job);
        int lvl = (int) j[0];
        double xp = g.xp() * ratio, money = g.money() * ratio * moneyMult(p.getUniqueId(), job);
        if (lvl < maxLevel()) {
            j[1] += xp;
            while (lvl < maxLevel() && j[1] >= need(lvl)) {
                j[1] -= need(lvl);
                lvl++;
                j[0] = lvl;
                levelUp(p, job, lvl);
            }
            if (lvl >= maxLevel()) j[1] = 0;
        }
        if (money > 0) Bank.give(p, money);
        plugin.addWeekly(p.getUniqueId(), "metiers", xp);
        plugin.addSeasonXp(p.getUniqueId(), xp * plugin.getConfig().getDouble("season.xp.job-ratio", 0.5));
        plugin.data().dirty = true;
        Object[] b = bar.computeIfAbsent(p.getUniqueId(), k -> new Object[]{0.0, 0.0, job});
        b[0] = (double) b[0] + xp;
        b[1] = (double) b[1] + money;
        b[2] = job;
    }

    private void levelUp(Player p, String job, int lvl) {
        p.showTitle(net.kyori.adventure.title.Title.title(SmpSaison.mm(name(job) + " <white>niveau " + lvl),
                SmpSaison.mm("<gray>Gains <white>+" + Math.round((moneyMult(lvl) - 1) * 100) + " %</white> · " + bonusText(job) + " <white>"
                        + SmpSaison.fmt(bonusChance(lvl)) + " %")));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.1f);
        if (lvl % 10 == 0) Bukkit.broadcast(SmpSaison.mm("<gold>🛠 <white>" + p.getName() + "</white> est passé " + name(job) + " <white>niveau " + lvl + "</white> !"));
    }

    void flushBars() {
        if (!plugin.getConfig().getBoolean("jobs-settings.actionbar", true)) {
            bar.clear();
            return;
        }
        for (Map.Entry<UUID, Object[]> e : bar.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            Object[] b = e.getValue();
            String job = (String) b[2];
            Data.P d = plugin.data().of(p.getUniqueId());
            double[] j = d.job(job);
            int lvl = (int) j[0];
            String prog = lvl >= maxLevel() ? "MAX" : Math.round(j[1] * 100 / need(lvl)) + " %";
            p.sendActionBar(SmpSaison.mm(name(job) + " <white>+" + SmpSaison.fmt((double) b[0]) + " XP</white> <gold>+"
                    + SmpSaison.fmt((double) b[1]) + " " + plugin.money() + "</gold> <dark_gray>· niv. " + lvl + " (" + prog + ")"));
        }
        bar.clear();
    }

    private static long key(Block b) {
        return ((long) b.getWorld().getName().hashCode() << 52) ^ ((long) (b.getX() & 0x3FFFFFF) << 26) ^ ((long) (b.getZ() & 0x3FFFFFF)) ^ ((long) b.getY() << 40);
    }

    /** plante qui pousse (comptée seulement mûre) ; la canne, le cactus et le bambou se comptent comme des blocs */
    private static boolean crop(Material m, Block b) {
        return b.getBlockData() instanceof Ageable && m != Material.SUGAR_CANE && m != Material.CACTUS && m != Material.BAMBOO;
    }

    private static boolean survival(Player p) {
        return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE;
    }

    // ================================================================= écoute

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Material m = e.getBlockPlaced().getType();
        if (!blockJob.containsKey(m) || crop(m, e.getBlockPlaced())) return;
        placed.add(key(e.getBlockPlaced()));
        if (placed.size() > 400_000) {
            var it = placed.iterator();
            for (int i = 0; i < 50_000 && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (!survival(p)) return;
        Block b = e.getBlock();
        Material m = b.getType();
        String job = blockJob.get(m);
        if (job == null) return;
        if (crop(m, b)) {
            if (b.getBlockData() instanceof Ageable a && a.getAge() < a.getMaximumAge()) return; // pas mûr
        } else if (placed.remove(key(b))) {
            return; // posé par un joueur
        }
        gain(p, job, blockGain.get(m), 1);
        if (job.equals("mineur") && m.name().endsWith("_ORE") || m == Material.ANCIENT_DEBRIS) plugin.addWeekly(p.getUniqueId(), "minerais", 1);
        // bonus : objets en double (minerais, bûches, récoltes ; pas la pierre)
        boolean worth = !job.equals("mineur") || m.name().endsWith("_ORE") || m == Material.ANCIENT_DEBRIS || m == Material.AMETHYST_CLUSTER;
        if (worth && ThreadLocalRandom.current().nextDouble() * 100 < bonusChance(p.getUniqueId(), job)) {
            ItemStack tool = p.getInventory().getItemInMainHand();
            for (ItemStack it : b.getDrops(tool, p)) b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), it);
        }
    }

    /** Seul le vrai butin de monstre peut être doublé : jamais un objet qu'on lui a fait ramasser (sinon duplication). */
    static final java.util.Set<org.bukkit.Material> MOB_LOOT = java.util.EnumSet.of(
            org.bukkit.Material.ROTTEN_FLESH, org.bukkit.Material.BONE, org.bukkit.Material.ARROW, org.bukkit.Material.STRING,
            org.bukkit.Material.SPIDER_EYE, org.bukkit.Material.GUNPOWDER, org.bukkit.Material.ENDER_PEARL, org.bukkit.Material.BLAZE_ROD,
            org.bukkit.Material.GHAST_TEAR, org.bukkit.Material.SLIME_BALL, org.bukkit.Material.MAGMA_CREAM, org.bukkit.Material.PHANTOM_MEMBRANE,
            org.bukkit.Material.PRISMARINE_SHARD, org.bukkit.Material.PRISMARINE_CRYSTALS, org.bukkit.Material.COAL, org.bukkit.Material.GLOWSTONE_DUST,
            org.bukkit.Material.REDSTONE, org.bukkit.Material.SUGAR, org.bukkit.Material.GLASS_BOTTLE, org.bukkit.Material.STICK,
            org.bukkit.Material.GOLD_NUGGET, org.bukkit.Material.COPPER_INGOT, org.bukkit.Material.LEATHER, org.bukkit.Material.FEATHER,
            org.bukkit.Material.INK_SAC, org.bukkit.Material.BREEZE_ROD, org.bukkit.Material.EMERALD, org.bukkit.Material.RABBIT_HIDE);

    static boolean mobLoot(org.bukkit.inventory.ItemStack it) {
        return it != null && MOB_LOOT.contains(it.getType()) && !it.hasItemMeta();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        Player p = e.getEntity().getKiller();
        if (p == null || !survival(p)) return;
        Gain g = mobGain.get(e.getEntityType());
        if (g == null) return;
        double ratio = e.getEntity().fromMobSpawner() ? plugin.getConfig().getDouble("jobs-settings.spawner-ratio", 0.2) : 1;
        gain(p, "chasseur", g, ratio);
        if (ratio >= 1 && ThreadLocalRandom.current().nextDouble() * 100 < bonusChance(p.getUniqueId(), "chasseur")) {
            List<ItemStack> extra = new ArrayList<>();
            for (ItemStack it : e.getDrops()) if (mobLoot(it)) extra.add(it.clone());
            e.getDrops().addAll(extra);
            e.setDroppedExp(e.getDroppedExp() * 2);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH || !survival(e.getPlayer())) return;
        Player p = e.getPlayer();
        gain(p, "pecheur", fish, 1);
        plugin.addWeekly(p.getUniqueId(), "peche", 1);
        if (e.getCaught() instanceof Item it && ThreadLocalRandom.current().nextDouble() * 100 < bonusChance(p.getUniqueId(), "pecheur")) {
            for (ItemStack left : p.getInventory().addItem(it.getItemStack().clone()).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
            p.playSound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_SPLASH, 1f, 1.6f);
        }
    }
}
