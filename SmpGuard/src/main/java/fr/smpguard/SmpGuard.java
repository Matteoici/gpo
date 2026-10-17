package fr.smpguard;

import fr.smpguard.check.AutoTotemCheck;
import fr.smpguard.check.BaitTest;
import fr.smpguard.check.BreakCheck;
import fr.smpguard.check.ClientCheck;
import fr.smpguard.check.CombatCheck;
import fr.smpguard.check.MoveCheck;
import fr.smpguard.check.OreBait;
import fr.smpguard.check.WallCheck;
import fr.smpguard.check.Culling;
import fr.smpguard.check.EspAimCheck;
import fr.smpguard.check.FreecamCheck;
import fr.smpguard.check.XrayCheck;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SmpGuard extends JavaPlugin {

    private Settings settings;
    private Texts texts;
    private Lag lag;
    private Alerts alerts;
    private EspAimCheck espAim;
    private FreecamCheck freecam;
    private XrayCheck xray;
    private Culling culling;
    private AutoTotemCheck autoTotem;
    private ClientCheck client;
    private WallCheck wall;
    private CombatCheck combat;
    private MoveCheck move;
    private BreakCheck breaks;
    private OreBait oreBait;
    private NamespacedKey key;
    private final Map<UUID, Profile> profiles = new HashMap<>();
    private final Map<UUID, BaitTest> baits = new HashMap<>();
    private int tick;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfig();
        key = new NamespacedKey(this, "guard");
        settings = new Settings(getConfig(), getLogger());
        texts = new Texts(this);
        texts.load();
        lag = new Lag(this);
        alerts = new Alerts(this);
        espAim = new EspAimCheck(this);
        freecam = new FreecamCheck(this);
        xray = new XrayCheck(this);
        culling = new Culling(this);
        autoTotem = new AutoTotemCheck(this);
        client = new ClientCheck(this);
        wall = new WallCheck(this);
        combat = new CombatCheck(this);
        move = new MoveCheck(this);
        breaks = new BreakCheck(this);
        oreBait = new OreBait(this);

        getServer().getPluginManager().registerEvents(new GuardListener(this), this);
        PluginCommand cmd = getCommand("anticheat");
        if (cmd != null) {
            GuardCommand gc = new GuardCommand(this);
            cmd.setExecutor(gc);
            cmd.setTabCompleter(gc);
        }
        for (Player p : Bukkit.getOnlinePlayers()) join(p);

        Bukkit.getScheduler().runTaskTimer(this, this::safeTick, 1L, 1L);
        getLogger().info("SmpGuard actif. Rappel : active aussi l'anti-xray de Paper (voir README).");
    }

    @Override
    public void onDisable() {
        for (BaitTest b : baits.values().toArray(new BaitTest[0])) b.cancel();
        if (culling != null) culling.showAll();
    }

    /**
     * v3 : alertes uniquement pour la triche sérieuse. Les anciens seuils (une alerte au moindre moment bizarre)
     * sont remplacés : score plus élevé ET plusieurs incidents distincts en peu de temps.
     */
    private void migrateConfig() {
        var c = getConfig();
        if (c.getInt("config-version", 1) >= 3) return;
        for (Check ch : Check.values()) {
            if (ch == Check.BAIT) continue;
            String b = "checks." + ch.key + ".";
            double[] d = Settings.defaults(ch);
            c.set(b + "alert-vl", d[0]);
            c.set(b + "sus-vl", d[1]);
            c.set(b + "decay-per-minute", d[2]);
            c.set(b + "min-events", (int) d[3]);
            c.set(b + "window-seconds", (int) d[4]);
        }
        c.set("checks.freecam.min-seconds", 8);
        c.set("checks.speed.max-blocks-per-second", 8.6);
        c.set("checks.fly.push-grace-ms", 3500);
        c.set("checks.autoclicker.max-cps", 25);
        c.set("checks.autoclicker.seconds", 5);
        c.set("checks.reach.margin", 0.6);
        c.set("checks.killaura.max-angle", 75);
        c.set("alerts.cooldown-seconds", 60);
        c.set("config-version", 3);
        saveConfig();
        getLogger().info("Config mise à jour (v3) : alertes seulement pour la triche sérieuse et répétée.");
    }

    void reload() {
        reloadConfig();
        settings = new Settings(getConfig(), getLogger());
        texts.load();
    }

    // une erreur répétée n'est écrite qu'une fois toutes les 10 minutes (jamais de logs qui remplissent le disque)
    private long lastTickError;

    private void safeTick() {
        try {
            tick();
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            if (now - lastTickError > 600_000) {
                lastTickError = now;
                getLogger().warning("Erreur (affichée une fois toutes les 10 min) : " + t);
            }
        }
    }

    private void tick() {
        tick++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Profile prof = profiles.get(p.getUniqueId());
            if (prof != null) prof.record(p);
        }
        if (tick % EspAimCheck.period() == 0) espAim.run();
        if (tick % FreecamCheck.PERIOD == 3) freecam.run();
        culling.tick();
        move.tick();
        if (tick % 100 == 37) oreBait.tick();
        if (tick % 20 == 11) {
            combat.tickSecond();
            breaks.tickSecond();
            autoTotem.prune();
        }
        if (tick % 20 == 7) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                Profile prof = profiles.get(p.getUniqueId());
                if (prof == null) continue;
                prof.samplePing(p.getPing());
                alerts.decay(prof);
            }
        }
    }

    // ================================================================= joueurs

    void join(Player p) {
        Profile prof = new Profile(p);
        prof.graceUntil = System.currentTimeMillis() + settings.graceJoin;
        profiles.put(p.getUniqueId(), prof);
    }

    void quit(Player p) {
        profiles.remove(p.getUniqueId());
        BaitTest b = baits.get(p.getUniqueId());
        if (b != null) b.cancel();
        culling.forget(p);
        autoTotem.forget(p);
        client.forget(p);
        combat.forget(p);
        move.forget(p);
        breaks.forget(p);
        oreBait.forget(p);
        for (Profile o : profiles.values()) o.tracks.remove(p.getUniqueId());
    }

    /** Pause des analyses (téléportation, mort...). */
    void grace(Player p, long ms) {
        Profile prof = profiles.get(p.getUniqueId());
        if (prof == null) return;
        prof.graceUntil = Math.max(prof.graceUntil, System.currentTimeMillis() + ms);
        prof.clearHistory();
        prof.tracks.clear();
        prof.inputStillSeconds = 0;
        if (move != null) move.reset(p);
    }

    public Profile profile(Player p) {
        return profiles.get(p.getUniqueId());
    }

    public Collection<Profile> profiles() {
        return profiles.values();
    }

    /** Ce joueur doit-il être analysé ? (pas le staff exempté, pas en créatif / spectateur) */
    public boolean analysed(Player p) {
        GameMode gm = p.getGameMode();
        return (gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE) && !p.hasPermission("smpguard.bypass")
                && !Hooks.isVanished(p);
    }

    /** Joueur que les autres ne peuvent de toute façon pas voir (vanish du staff). */
    public boolean hidden(Player p) {
        return Hooks.isVanished(p);
    }

    public boolean sameGuild(Player a, Player b) {
        return Hooks.sameGuild(a, b);
    }

    /** Joueur Bedrock (Geyser/Floodgate) : ses touches et son inventaire sont traduits, on ne les analyse pas. */
    public boolean isBedrock(Player p) {
        return p.getUniqueId().getMostSignificantBits() == 0;
    }

    public boolean frozen(Player p) {
        return Hooks.isFrozen(p);
    }

    private static final org.bukkit.NamespacedKey EXEMPT = new org.bukkit.NamespacedKey("smpguard", "exempt_until");

    /**
     * Un autre plugin contrôle ses déplacements en ce moment : blocs factices (extensions de territoire SmpGojo),
     * ou déplacement annulé / modifié par un plugin (gel, cinématique, zone interdite, bordure) il y a moins de 3 s.
     * N'importe quel plugin peut poser la clé smpguard:exempt_until (LONG, en ms) sur le joueur.
     */
    public boolean scripted(Player p) {
        Long until = p.getPersistentDataContainer().get(EXEMPT, org.bukkit.persistence.PersistentDataType.LONG);
        if (until != null && until > System.currentTimeMillis()) return true;
        Profile prof = profile(p);
        return prof != null && System.currentTimeMillis() - prof.moveBlockedAt < 3000;
    }

    // ================================================================= test appât

    public BaitTest bait(UUID target) {
        return baits.get(target);
    }

    public void startBait(org.bukkit.command.CommandSender staff, Player target, int seconds) {
        if (baits.containsKey(target.getUniqueId())) {
            texts.send(staff, "bait.already", Texts.ph("player", target.getName()));
            return;
        }
        BaitTest b = BaitTest.start(this, staff, target, seconds);
        if (b != null) baits.put(target.getUniqueId(), b);
    }

    public void baitEnded(BaitTest b) {
        baits.remove(b.target().getUniqueId());
    }

    // ================================================================= accès

    public Settings settings() {
        return settings;
    }

    public Texts texts() {
        return texts;
    }

    public Lag lag() {
        return lag;
    }

    public Alerts alerts() {
        return alerts;
    }

    public XrayCheck xray() {
        return xray;
    }

    public AutoTotemCheck autoTotem() {
        return autoTotem;
    }

    public ClientCheck client() {
        return client;
    }

    public WallCheck wall() {
        return wall;
    }

    public CombatCheck combat() {
        return combat;
    }

    public MoveCheck move() {
        return move;
    }

    public OreBait oreBait() {
        return oreBait;
    }

    public BreakCheck breaks() {
        return breaks;
    }

    /** Numéro du tick serveur (compté par le plugin). */
    public int currentTick() {
        return tick;
    }

    public Culling culling() {
        return culling;
    }

    public NamespacedKey key() {
        return key;
    }
}
