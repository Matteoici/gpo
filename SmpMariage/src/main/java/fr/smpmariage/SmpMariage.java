package fr.smpmariage;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Mariages : demande, cérémonie, bisous, coffre commun, lune de miel, divorce avec pension. */
public final class SmpMariage extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Un couple (enregistré sous les deux joueurs). */
    static final class Couple {
        UUID a, b;
        String nameA, nameB;
        long since;
        int kisses;
        ItemStack[] chest = new ItemStack[27];
    }

    private final Map<UUID, Couple> couples = new HashMap<>();
    /** demandé -> [qui demande, expiration] */
    private final Map<UUID, Object[]> proposals = new HashMap<>();
    private final Map<UUID, Long> kissCd = new HashMap<>(), tpCd = new HashMap<>();
    private final Set<UUID> divorceConfirm = new HashSet<>();
    private File file;

    static Component mm(String s) {
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static String esc(String s) {
        return MM.escapeTags(s == null ? "?" : s);
    }

    private static String pick(String... s) {
        return s[ThreadLocalRandom.current().nextInt(s.length)];
    }

    private String fmt(double v) {
        return String.format(Locale.FRANCE, "%,d", Math.round(v)).replace(' ', ' ').replace(' ', ' ') + " " + getConfig().getString("money-symbol", "$");
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        file = new File(getDataFolder(), "couples.yml");
        load();
        getServer().getPluginManager().registerEvents(this, this);
        // aura d'amour : près l'un de l'autre, petits cœurs et un peu de vie
        Bukkit.getScheduler().runTaskTimer(this, this::aura, 600L, 600L);
    }

    @Override
    public void onDisable() {
        save();
    }

    // ================================================================= données

    private void load() {
        couples.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = y.getConfigurationSection("couples");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            try {
                ConfigurationSection c = s.getConfigurationSection(k);
                Couple cp = new Couple();
                cp.a = UUID.fromString(c.getString("a"));
                cp.b = UUID.fromString(c.getString("b"));
                cp.nameA = c.getString("name-a", "?");
                cp.nameB = c.getString("name-b", "?");
                cp.since = c.getLong("since");
                cp.kisses = c.getInt("kisses");
                List<String> items = c.getStringList("chest");
                for (int i = 0; i < items.size() && i < 27; i++) {
                    String b64 = items.get(i);
                    cp.chest[i] = b64.isEmpty() ? null : ItemStack.deserializeBytes(Base64.getDecoder().decode(b64));
                }
                couples.put(cp.a, cp);
                couples.put(cp.b, cp);
            } catch (RuntimeException e) {
                getLogger().warning("couples.yml : couple " + k + " ignoré (" + e.getMessage() + ")");
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        Set<Couple> done = new HashSet<>();
        int i = 0;
        for (Couple c : couples.values()) {
            if (!done.add(c)) continue;
            String b = "couples." + (i++) + ".";
            y.set(b + "a", c.a.toString());
            y.set(b + "b", c.b.toString());
            y.set(b + "name-a", c.nameA);
            y.set(b + "name-b", c.nameB);
            y.set(b + "since", c.since);
            y.set(b + "kisses", c.kisses);
            List<String> items = new ArrayList<>();
            for (ItemStack it : c.chest) items.add(it == null || it.getType().isAir() ? "" : Base64.getEncoder().encodeToString(it.serializeAsBytes()));
            y.set(b + "chest", items);
        }
        try {
            y.save(file);
        } catch (IOException e) {
            getLogger().warning("couples.yml : " + e.getMessage());
        }
    }

    private UUID partner(UUID id) {
        Couple c = couples.get(id);
        return c == null ? null : (c.a.equals(id) ? c.b : c.a);
    }

    private String partnerName(UUID id) {
        Couple c = couples.get(id);
        return c == null ? "?" : (c.a.equals(id) ? c.nameB : c.nameA);
    }

    // ================================================================= argent

    private boolean pay(Player p, double v) {
        if (v <= 0) return true;
        try {
            if (Bukkit.getPluginManager().getPlugin("Vault") == null) return true;
            return Bank.take(p, v);
        } catch (Throwable t) {
            return true;
        }
    }

    private void give(OfflinePlayer p, double v) {
        try {
            if (v > 0 && Bukkit.getPluginManager().getPlugin("Vault") != null) Bank.give(p, v);
        } catch (Throwable ignored) {
        }
    }

    static final class Bank {
        static boolean take(Player p, double v) {
            var rsp = Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
            if (rsp == null) return true;
            var e = rsp.getProvider();
            return e.has(p, v) && e.withdrawPlayer(p, v).transactionSuccess();
        }

        static void give(OfflinePlayer p, double v) {
            var rsp = Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
            if (rsp != null) rsp.getProvider().depositPlayer(p, v);
        }
    }

    // ================================================================= demande & cérémonie

    private void propose(Player p, Player t) {
        if (t == null || t.equals(p)) {
            p.sendMessage(mm(t == null ? "<red>Ce joueur n'est pas connecté." : "<red>Tu t'aimes beaucoup, mais non, on ne peut pas s'épouser soi-même. 🪞"));
            return;
        }
        if (couples.containsKey(p.getUniqueId())) {
            p.sendMessage(mm("<red>Tu es déjà marié(e) avec <white>" + esc(partnerName(p.getUniqueId())) + "</white>... on ne fait pas ça ici. 👀"));
            return;
        }
        if (couples.containsKey(t.getUniqueId())) {
            p.sendMessage(mm("<red>" + esc(t.getName()) + " est déjà pris(e) ! <gray>Il fallait être plus rapide. 💔"));
            return;
        }
        double price = getConfig().getDouble("proposal-price", 5000);
        if (!pay(p, price)) {
            p.sendMessage(mm("<red>Il te faut <white>" + fmt(price) + "</white> pour la bague. 💍 <gray>Pas de bague, pas de mariage."));
            return;
        }
        proposals.put(t.getUniqueId(), new Object[]{p.getUniqueId(), System.currentTimeMillis() + getConfig().getLong("proposal-seconds", 60) * 1000});
        Bukkit.broadcast(mm("<light_purple>💍 <white>" + esc(p.getName()) + "</white> " + pick("pose un genou à terre devant", "sort une bague devant",
                "rougit et demande la main de", "a enfin trouvé le courage de demander en mariage") + " <white>" + esc(t.getName()) + "</white> ! <gray>Suspense..."));
        t.sendMessage(mm("<light_purple><b>💍 " + esc(p.getName()) + " te demande en mariage !"));
        t.sendMessage(mm("  <green><b>[OUI !]</b></green>").clickEvent(ClickEvent.runCommand("/mariage accepter"))
                .append(mm("   ")).append(mm("<red><b>[Non merci]</b></red>").clickEvent(ClickEvent.runCommand("/mariage refuser"))));
        t.playSound(t.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.4f);
        p.sendMessage(mm("<gray>Demande envoyée... croise les doigts. 🤞"));
    }

    private void answer(Player t, boolean yes) {
        Object[] pr = proposals.remove(t.getUniqueId());
        if (pr == null || (long) pr[1] < System.currentTimeMillis()) {
            t.sendMessage(mm("<gray>Personne ne t'a demandé(e) en mariage... pour l'instant. 😏"));
            return;
        }
        Player p = Bukkit.getPlayer((UUID) pr[0]);
        if (!yes) {
            Bukkit.broadcast(mm("<red>💔 <white>" + esc(t.getName()) + "</white> a dit non à <white>" + (p == null ? "?" : esc(p.getName())) + "</white>. "
                    + pick("<gray>Aïe. Ça pique.", "<gray>La bague n'est pas remboursée. 😬", "<gray>On a tous connu ça... courage.", "<gray>Le silence est gênant.")));
            return;
        }
        if (p == null || couples.containsKey(p.getUniqueId()) || couples.containsKey(t.getUniqueId())) {
            t.sendMessage(mm("<red>Trop tard, la demande n'est plus valable."));
            return;
        }
        Couple c = new Couple();
        c.a = p.getUniqueId();
        c.b = t.getUniqueId();
        c.nameA = p.getName();
        c.nameB = t.getName();
        c.since = System.currentTimeMillis();
        couples.put(c.a, c);
        couples.put(c.b, c);
        save();
        ceremony(p, t);
    }

    private void ceremony(Player a, Player b) {
        Bukkit.broadcast(mm(""));
        Bukkit.broadcast(mm("<gradient:#ff9cf0:#ff4a8a><b>💒 VIVE LES MARIÉS !</b></gradient> <white>" + esc(a.getName()) + " <light_purple>❤</light_purple> " + esc(b.getName())));
        Bukkit.broadcast(mm("<gray>" + pick("Que leurs coffres soient toujours pleins et leurs creepers loin.", "Que le Nether leur soit doux.",
                "Ils/elles partageront tout... même les pommes dorées ?", "Rendez-vous dans 9 mois pour un petit villageois. 👶")));
        Bukkit.broadcast(mm(""));
        int honey = getConfig().getInt("honeymoon-minutes", 10);
        for (Player p : new Player[]{a, b}) {
            p.showTitle(Title.title(mm("<gradient:#ff9cf0:#ff4a8a><b>💒 Marié(e) !"), mm("<white>avec " + esc(p.equals(a) ? b.getName() : a.getName()))));
            p.playSound(p.getLocation(), Sound.BLOCK_BELL_USE, 1f, 1f);
            Bukkit.getScheduler().runTaskLater(this, () -> p.playSound(p.getLocation(), Sound.BLOCK_BELL_USE, 1f, 1.2f), 10L);
            Bukkit.getScheduler().runTaskLater(this, () -> p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f), 20L);
            for (ItemStack left : p.getInventory().addItem(new ItemStack(Material.CAKE)).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
            if (honey > 0) p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, honey * 1200, 0, false, false, true));
            for (int i = 0; i < 4; i++) {
                int k = i;
                Bukkit.getScheduler().runTaskLater(this, () -> firework(p.getLocation()), 10L + k * 15L);
            }
            hearts(p.getLocation(), 30);
        }
        // lune de miel : cœurs qui les suivent
        int[] t = {0};
        Bukkit.getScheduler().runTaskTimer(this, task -> {
            if (++t[0] > honey * 60 / 2) {
                task.cancel();
                return;
            }
            for (Player p : new Player[]{a, b}) if (p.isOnline()) hearts(p.getLocation(), 2);
        }, 40L, 40L);
    }

    private static void hearts(Location l, int n) {
        l.getWorld().spawnParticle(Particle.HEART, l.clone().add(0, 1.8, 0), n, 0.6, 0.5, 0.6, 0);
    }

    private static void firework(Location l) {
        Firework f = l.getWorld().spawn(l.clone().add(0, 1, 0), Firework.class, fw -> {
            FireworkMeta m = fw.getFireworkMeta();
            m.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(Color.fromRGB(255, 120, 200), Color.WHITE)
                    .withFade(Color.fromRGB(255, 60, 120)).flicker(true).build());
            m.setPower(1);
            fw.setFireworkMeta(m);
        });
        f.getPersistentDataContainer().set(new org.bukkit.NamespacedKey("smpmariage", "fw"), org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
    }

    // ================================================================= bisou, tp, coffre

    private void kiss(Player p) {
        UUID other = partner(p.getUniqueId());
        if (other == null) {
            p.sendMessage(mm("<gray>Tu fais un bisou... dans le vide. 😗 <dark_gray>(/mariage demander <joueur>)"));
            return;
        }
        Player o = Bukkit.getPlayer(other);
        double dist = getConfig().getDouble("kiss-distance", 3);
        if (o == null || !o.getWorld().equals(p.getWorld()) || o.getLocation().distanceSquared(p.getLocation()) > dist * dist) {
            p.sendMessage(mm("<gray>Tu envoies un bisou volant à <white>" + esc(partnerName(p.getUniqueId())) + "</white>... il faut être collé(e)s pour un vrai. 😘"));
            if (o != null) o.sendMessage(mm("<light_purple>😘 <white>" + esc(p.getName()) + "</white> t'envoie un bisou volant !"));
            return;
        }
        long now = System.currentTimeMillis();
        Long cd = kissCd.get(p.getUniqueId());
        if (cd != null && cd > now) {
            p.sendMessage(mm("<gray>Doucement, reprends ton souffle... (" + ((cd - now) / 1000 + 1) + " s) 😳"));
            return;
        }
        kissCd.put(p.getUniqueId(), now + getConfig().getLong("kiss-cooldown-seconds", 20) * 1000);
        Couple c = couples.get(p.getUniqueId());
        c.kisses++;
        String line = pick("fait un gros bisou à", "fait un bisou baveux à", "fait un bisou esquimau à", "vole un bisou à",
                "fait un bisou tout timide à", "embrasse passionnément", "fait un smack sonore à");
        for (Player x : p.getWorld().getPlayers()) {
            if (x.getLocation().distanceSquared(p.getLocation()) < 30 * 30) x.sendMessage(mm("<light_purple>💋 <white>" + esc(p.getName()) + "</white> " + line + " <white>" + esc(o.getName()) + "</white>"));
        }
        double heal = getConfig().getDouble("kiss-heal", 2);
        for (Player x : new Player[]{p, o}) {
            var max = x.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (max != null) x.setHealth(Math.min(max.getValue(), x.getHealth() + heal));
            x.playSound(x.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 2f);
        }
        hearts(p.getLocation().add(o.getLocation()).multiply(0.5), 12);
        if (c.kisses % 100 == 0) Bukkit.broadcast(mm("<light_purple>💋 <white>" + esc(c.nameA) + "</white> et <white>" + esc(c.nameB) + "</white> en sont à <b>" + c.kisses + " bisous</b>. Prenez une chambre. 🛏"));
    }

    private void tp(Player p) {
        UUID other = partner(p.getUniqueId());
        Player o = other == null ? null : Bukkit.getPlayer(other);
        if (o == null) {
            p.sendMessage(mm(other == null ? "<gray>Tu n'es marié(e) avec personne." : "<gray>Ton/ta marié(e) n'est pas connecté(e)... 🥲"));
            return;
        }
        long now = System.currentTimeMillis();
        Long cd = tpCd.get(p.getUniqueId());
        if (cd != null && cd > now) {
            p.sendMessage(mm("<gray>Encore " + ((cd - now) / 1000 + 1) + " s avant de pouvoir le/la rejoindre. Patience, l'amour attend."));
            return;
        }
        tpCd.put(p.getUniqueId(), now + getConfig().getLong("tp-cooldown-seconds", 300) * 1000);
        p.teleportAsync(o.getLocation());
        o.sendMessage(mm("<light_purple>💞 <white>" + esc(p.getName()) + "</white> te rejoint !"));
        hearts(o.getLocation(), 10);
    }

    private static final class ChestHolder implements InventoryHolder {
        final Couple c;
        Inventory inv;

        ChestHolder(Couple c) {
            this.c = c;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private void chest(Player p) {
        Couple c = couples.get(p.getUniqueId());
        if (c == null) {
            p.sendMessage(mm("<gray>Le coffre commun, c'est pour les couples. 💍"));
            return;
        }
        // un seul inventaire ouvert à la fois pour le couple (pas de duplication)
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.getOpenInventory().getTopInventory().getHolder() instanceof ChestHolder h && h.c == c) {
                p.openInventory(h.inv);
                return;
            }
        }
        ChestHolder h = new ChestHolder(c);
        h.inv = Bukkit.createInventory(h, 27, mm("<light_purple>💞 Coffre de " + esc(c.nameA) + " & " + esc(c.nameB)));
        h.inv.setContents(c.chest);
        p.openInventory(h.inv);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof ChestHolder h) {
            h.c.chest = e.getInventory().getContents().clone();
            save();
        }
    }

    private void divorce(Player p) {
        Couple c = couples.get(p.getUniqueId());
        if (c == null) {
            p.sendMessage(mm("<gray>Pour divorcer, il faut d'abord être marié(e). Logique."));
            return;
        }
        double price = getConfig().getDouble("divorce-price", 20000);
        if (!divorceConfirm.remove(p.getUniqueId())) {
            divorceConfirm.add(p.getUniqueId());
            p.sendMessage(mm("<red>Tu veux vraiment divorcer de <white>" + esc(partnerName(p.getUniqueId())) + "</white> ? <gray>Ça coûte <white>" + fmt(price)
                    + "</white> (la moitié part en pension alimentaire). Le coffre commun reste à l'autre. <red>Retape /mariage divorcer pour confirmer."));
            Bukkit.getScheduler().runTaskLater(this, () -> divorceConfirm.remove(p.getUniqueId()), 400L);
            return;
        }
        if (!pay(p, price)) {
            p.sendMessage(mm("<red>Il te faut <white>" + fmt(price) + "</white> pour l'avocat. <gray>Bon, vous allez devoir vous supporter encore un peu."));
            return;
        }
        UUID ex = partner(p.getUniqueId());
        OfflinePlayer exP = Bukkit.getOfflinePlayer(ex);
        give(exP, price / 2);
        couples.remove(c.a);
        couples.remove(c.b);
        // le coffre commun reste à l'autre : il tombe à ses pieds s'il est là, sinon on le lui garde dans son inventaire à la connexion (simple : on le rend s'il est là)
        Player o = exP.getPlayer();
        if (o != null) {
            for (ItemStack it : c.chest) {
                if (it == null) continue;
                for (ItemStack left : o.getInventory().addItem(it).values()) o.getWorld().dropItemNaturally(o.getLocation(), left);
            }
        } else {
            pendingChest.put(ex, c.chest);
        }
        save();
        long days = (System.currentTimeMillis() - c.since) / 86_400_000L;
        Bukkit.broadcast(mm("<dark_gray>💔 <white>" + esc(p.getName()) + "</white> a divorcé de <white>" + esc(exP.getName()) + "</white> après " + days + " jour(s). "
                + pick("<gray>" + esc(exP.getName()) + " garde le chien. 🐶", "<gray>Le gâteau du mariage n'était même pas fini.", "<gray>Les avocats se frottent les mains.",
                "<gray>Pension alimentaire : " + fmt(price / 2) + ". Ouch.", "<gray>C'était à cause des clés de caisse, on le sait tous.")));
    }

    private final Map<UUID, ItemStack[]> pendingChest = new HashMap<>();

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        ItemStack[] items = pendingChest.remove(e.getPlayer().getUniqueId());
        if (items != null) for (ItemStack it : items) if (it != null)
            for (ItemStack left : e.getPlayer().getInventory().addItem(it).values()) e.getPlayer().getWorld().dropItemNaturally(e.getPlayer().getLocation(), left);
        UUID other = partner(e.getPlayer().getUniqueId());
        Player o = other == null ? null : Bukkit.getPlayer(other);
        if (o != null) o.sendMessage(mm("<light_purple>💞 Ton/ta marié(e) <white>" + esc(e.getPlayer().getName()) + "</white> vient de se connecter !"));
    }

    // ================================================================= bonus

    private void aura() {
        double d = getConfig().getDouble("love-aura-distance", 8);
        Set<Couple> done = new HashSet<>();
        for (Couple c : couples.values()) {
            if (!done.add(c)) continue;
            Player a = Bukkit.getPlayer(c.a), b = Bukkit.getPlayer(c.b);
            if (a == null || b == null || !a.getWorld().equals(b.getWorld()) || a.getLocation().distanceSquared(b.getLocation()) > d * d) continue;
            for (Player p : new Player[]{a, b}) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 0, false, false, true));
                hearts(p.getLocation(), 2);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void noFriendlyFire(EntityDamageByEntityEvent e) {
        if (!getConfig().getBoolean("no-friendly-fire", true) || !(e.getEntity() instanceof Player v)) return;
        Player d = e.getDamager() instanceof Player p ? p : (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p2 ? p2 : null);
        if (e.getDamager() instanceof Firework fw && fw.getPersistentDataContainer().has(new org.bukkit.NamespacedKey("smpmariage", "fw"))) {
            e.setCancelled(true); // les feux d'artifice du mariage ne blessent personne
            return;
        }
        if (d == null || !v.getUniqueId().equals(partner(d.getUniqueId()))) return;
        e.setCancelled(true);
        d.sendActionBar(mm(pick("<light_purple>Tu ne vas quand même pas frapper ton/ta chéri(e) ?! 😤", "<light_purple>Violence conjugale interdite. Fais un /bisou plutôt. 💋",
                "<light_purple>On règle ça par un câlin, pas par l'épée.")));
    }

    /** Clic droit accroupi sur son/sa marié(e) = bisou. */
    @EventHandler
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !e.getPlayer().isSneaking() || !(e.getRightClicked() instanceof Player t)) return;
        if (t.getUniqueId().equals(partner(e.getPlayer().getUniqueId()))) kiss(e.getPlayer());
    }

    // ================================================================= commandes

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) return true;
        if (cmd.getName().equalsIgnoreCase("bisou")) {
            kiss(p);
            return true;
        }
        String sub = a.length == 0 ? "info" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "demander", "propose", "demande" -> {
                if (a.length < 2) p.sendMessage(mm("<red>/mariage demander <joueur>"));
                else propose(p, Bukkit.getPlayerExact(a[1]));
            }
            case "accepter", "oui", "accept" -> answer(p, true);
            case "refuser", "non", "deny" -> answer(p, false);
            case "tp" -> tp(p);
            case "coffre", "chest" -> chest(p);
            case "divorcer", "divorce" -> divorce(p);
            case "liste", "couples" -> {
                Set<Couple> done = new HashSet<>();
                List<Couple> all = new ArrayList<>();
                for (Couple c : couples.values()) if (done.add(c)) all.add(c);
                all.sort((x, y) -> Long.compare(x.since, y.since));
                p.sendMessage(mm("<light_purple><b>💒 Les couples du serveur</b> <gray>(" + all.size() + ")"));
                for (Couple c : all.subList(0, Math.min(15, all.size()))) {
                    long days = (System.currentTimeMillis() - c.since) / 86_400_000L;
                    p.sendMessage(mm("<white>" + esc(c.nameA) + " <light_purple>❤</light_purple> " + esc(c.nameB) + " <dark_gray>— " + days + " j · " + c.kisses + " bisous"));
                }
            }
            default -> {
                Couple c = couples.get(p.getUniqueId());
                if (c == null) {
                    p.sendMessage(mm("<light_purple><b>💍 Mariage</b> <gray>— tu es célibataire."));
                    p.sendMessage(mm("<gray>/mariage demander <joueur> <dark_gray>(" + fmt(getConfig().getDouble("proposal-price", 5000)) + " la bague) <gray>· /mariage liste"));
                } else {
                    long days = (System.currentTimeMillis() - c.since) / 86_400_000L;
                    p.sendMessage(mm("<light_purple><b>💍 Marié(e) avec " + esc(partnerName(p.getUniqueId())) + "</b> <gray>depuis le "
                            + new SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(new Date(c.since)) + " (" + days + " j) · " + c.kisses + " bisous"));
                    p.sendMessage(mm("<gray>/bisou (ou accroupi + clic droit) · /mariage tp · /mariage coffre · /mariage divorcer"));
                }
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("mariage")) {
            if (a.length == 1) out.addAll(List.of("demander", "accepter", "refuser", "info", "tp", "coffre", "divorcer", "liste"));
            else if (a.length == 2 && a[0].equalsIgnoreCase("demander")) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
