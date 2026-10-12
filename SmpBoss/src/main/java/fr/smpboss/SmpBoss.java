package fr.smpboss;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SmpBoss extends JavaPlugin {

    public static final MiniMessage MM = MiniMessage.miniMessage();

    private Store store;
    private BossItems items;
    private Relics relics;
    private Loot loot;
    private Backpacks backpacks;
    private PumpkinKing king;
    private Location altar;
    private long lastEnd;
    private final Set<RelicMenu> openMenus = new HashSet<>();

    public static Component parse(String s) {
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        store = new Store(this);
        items = new BossItems(this);
        relics = new Relics(this);
        loot = new Loot(this);
        backpacks = new Backpacks(this);
        store.load();
        relics.load();
        loadAltar();
        registerRecipe();
        getServer().getPluginManager().registerEvents(new BossListener(this), this);

        Bukkit.getScheduler().runTaskTimer(this, relics::tickBackpacks, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, relics::tickTrails, 5L, 5L);
        Bukkit.getScheduler().runTaskTimer(this, relics::tickRegen, 80L, 80L);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            openMenus.removeIf(m -> m.getInventory().getViewers().isEmpty());
            for (RelicMenu m : openMenus) m.animate();
        }, 4L, 4L);
        Bukkit.getScheduler().runTaskTimer(this, () -> store.save(true), 1200L, 1200L);
        for (Player p : Bukkit.getOnlinePlayers()) relics.apply(p);
    }

    @Override
    public void onDisable() {
        if (king != null) king.cleanup();
        if (relics != null) relics.removeAll();
        for (Player p : Bukkit.getOnlinePlayers()) {
            var top = p.getOpenInventory().getTopInventory().getHolder(false);
            if (top instanceof Backpacks.Holder h) backpacks.close(h);
            if (top instanceof Backpacks.Holder || top instanceof RelicMenu) p.closeInventory();
        }
        if (store != null) store.save(false);
    }

    public String msg(String key) {
        return getConfig().getString("messages." + key, "<red>[" + key + "]");
    }

    // ================================================================= recette

    private void registerRecipe() {
        NamespacedKey key = new NamespacedKey(this, "sacrifice");
        Bukkit.removeRecipe(key);
        ShapedRecipe r = new ShapedRecipe(key, items.sacrifice());
        r.shape("SSS", "SPS", "SSS");
        r.setIngredient('S', new RecipeChoice.ExactChoice(items.seed(1)));
        r.setIngredient('P', Material.CARVED_PUMPKIN);
        Bukkit.addRecipe(r);
    }

    // ================================================================= autel

    private File altarFile() {
        return new File(getDataFolder(), "altar.yml");
    }

    private void loadAltar() {
        File f = altarFile();
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        World w = Bukkit.getWorld(y.getString("world", "world"));
        if (w != null) altar = new Location(w, y.getInt("x"), y.getInt("y"), y.getInt("z"));
    }

    private void saveAltar() {
        YamlConfiguration y = new YamlConfiguration();
        if (altar != null) {
            y.set("world", altar.getWorld().getName());
            y.set("x", altar.getBlockX());
            y.set("y", altar.getBlockY());
            y.set("z", altar.getBlockZ());
        }
        try {
            y.save(altarFile());
        } catch (IOException e) {
            getLogger().warning("Sauvegarde de l'autel impossible : " + e.getMessage());
        }
    }

    public boolean isAltar(Block b) {
        return altar != null && b != null && b.getWorld() == altar.getWorld() && b.getX() == altar.getBlockX()
                && b.getY() == altar.getBlockY() && b.getZ() == altar.getBlockZ();
    }

    /** Un joueur utilise la citrouille sacrificielle sur l'autel. */
    public void trySummon(Player p) {
        if (king != null && king.active()) {
            p.sendMessage(parse(msg("summon.already")));
            return;
        }
        long cd = getConfig().getLong("boss.cooldown-minutes", 30) * 60_000L;
        long left = lastEnd + cd - System.currentTimeMillis();
        if (left > 0) {
            p.sendMessage(parse(msg("summon.cooldown").replace("<minutes>", String.valueOf(left / 60_000 + 1))));
            return;
        }
        if (getConfig().getBoolean("boss.night-only", true)) {
            long t = altar.getWorld().getTime();
            if (t < 13000 || t > 23000) {
                p.sendMessage(parse(msg("summon.day")));
                return;
            }
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!items.isSacrifice(hand)) return;
        hand.setAmount(hand.getAmount() - 1);
        king = new PumpkinKing(this, altar);
        king.start(p);
    }

    void bossEnded() {
        lastEnd = System.currentTimeMillis();
        king = null;
    }

    // ================================================================= commandes

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (name.equals("reliques")) {
            if (sender instanceof Player p) new RelicMenu(this, p).open();
            return true;
        }
        if (name.equals("sac")) {
            if (sender instanceof Player p) backpacks.open(p);
            return true;
        }
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "autel", "altar" -> {
                if (!(sender instanceof Player p)) return true;
                Block b = p.getTargetBlockExact(6);
                if (b == null || b.getType().isAir()) {
                    sender.sendMessage(parse("<red>Regarde le bloc qui servira d'autel (à moins de 6 blocs)."));
                    return true;
                }
                altar = b.getLocation();
                saveAltar();
                sender.sendMessage(parse("<green>Autel placé sur ce bloc (" + b.getType() + " " + b.getX() + " " + b.getY() + " " + b.getZ()
                        + "). <gray>Les joueurs font clic droit dessus avec une Citrouille sacrificielle, la nuit."));
            }
            case "invoquer", "summon" -> {
                if (altar == null) {
                    sender.sendMessage(parse("<red>Pas d'autel : /boss autel"));
                    return true;
                }
                if (king != null && king.active()) {
                    sender.sendMessage(parse("<red>Un boss est déjà là."));
                    return true;
                }
                king = new PumpkinKing(this, altar);
                king.start(sender instanceof Player p ? p : Bukkit.getOnlinePlayers().stream().findFirst().orElse(null));
            }
            case "stop" -> {
                if (king != null) king.leave("boss.stopped");
                lastEnd = 0;
                sender.sendMessage(parse("<green>Boss arrêté (temps d'attente remis à zéro)."));
            }
            case "give" -> {
                if (a.length < 3) {
                    sender.sendMessage(parse("<red>/boss give <joueur> <graine|citrouille|faucille|relique:id> [nombre]"));
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) {
                    sender.sendMessage(parse("<red>Joueur hors ligne."));
                    return true;
                }
                int n = a.length >= 4 ? Math.max(1, Integer.parseInt(a[3].replaceAll("\\D", "0"))) : 1;
                String what = a[2].toLowerCase(Locale.ROOT);
                ItemStack it;
                if (what.startsWith("relique:") || what.startsWith("relic:")) {
                    Relic r = relics.get(what.substring(what.indexOf(':') + 1));
                    if (r == null) {
                        sender.sendMessage(parse("<red>Relique inconnue. Liste : " + String.join(", ", relics.all().keySet())));
                        return true;
                    }
                    it = items.relicItem(r);
                } else {
                    it = switch (what) {
                        case "graine", "graines", "seed" -> items.seed(n);
                        case "citrouille", "sacrifice" -> {
                            ItemStack s = items.sacrifice();
                            s.setAmount(Math.min(16, n));
                            yield s;
                        }
                        case "faucille", "sickle" -> items.sickle();
                        default -> null;
                    };
                }
                if (it == null) {
                    sender.sendMessage(parse("<red>Objet inconnu."));
                    return true;
                }
                Loot.give(t, it);
                sender.sendMessage(parse("<green>Donné à " + t.getName() + "."));
            }
            case "reload" -> {
                reloadConfig();
                relics.load();
                registerRecipe();
                for (Player p : Bukkit.getOnlinePlayers()) relics.apply(p);
                sender.sendMessage(parse("<green>SmpBoss rechargé (" + relics.all().size() + " reliques)."));
            }
            default -> {
                sender.sendMessage(parse("<gold><b>Roi Citrouille"));
                sender.sendMessage(parse("<aqua>/boss autel</aqua> <gray>- le bloc que tu regardes devient l'autel"));
                sender.sendMessage(parse("<aqua>/boss invoquer</aqua> · <aqua>/boss stop</aqua> · <aqua>/boss reload"));
                sender.sendMessage(parse("<aqua>/boss give <joueur> <graine|citrouille|faucille|relique:id> [n]"));
                sender.sendMessage(parse("<gray>Autel : " + (altar == null ? "<red>pas placé" : altar.getBlockX() + " " + altar.getBlockY()
                        + " " + altar.getBlockZ()) + "<gray> · Boss : " + (king != null && king.active() ? "<green>en cours" : "aucun")));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (!cmd.getName().equalsIgnoreCase("boss")) return out;
        if (a.length == 1) out.addAll(List.of("autel", "invoquer", "stop", "give", "reload"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("give")) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        else if (a.length == 3 && a[0].equalsIgnoreCase("give")) {
            out.addAll(List.of("graine", "citrouille", "faucille"));
            for (String id : relics.all().keySet()) out.add("relique:" + id);
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    // ================================================================= accès

    public Store store() {
        return store;
    }

    public BossItems items() {
        return items;
    }

    public Relics relics() {
        return relics;
    }

    public Loot loot() {
        return loot;
    }

    public Backpacks backpacks() {
        return backpacks;
    }

    public PumpkinKing king() {
        return king;
    }

    public Set<RelicMenu> openMenus() {
        return openMenus;
    }
}
