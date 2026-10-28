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
    private Weapons weapons;
    private Backpacks backpacks;
    private Cosmetics cosmetics;
    private InvButtons invButtons;
    private PumpkinKing king;
    private Arena arena;
    private GuildTop guildTop;
    private AltarHologram hologram;
    private Location altar;
    private long lastEnd;
    private final Set<RelicMenu> openMenus = new HashSet<>();

    public static Component parse(String s) {
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // mise à jour des réglages d'une ancienne config (sans toucher à ce que l'admin a changé)
        if (getConfig().getInt("config-version", 1) < 2) {
            if (getConfig().getDouble("loot.min-damage-percent", 3) == 3) getConfig().set("loot.min-damage-percent", 5);
            getConfig().set("config-version", 2);
            saveConfig();
        }
        // v3 : boss beaucoup plus dur (seulement les valeurs restées à l'ancien réglage par défaut)
        if (getConfig().getInt("config-version", 1) < 3) {
            harder("boss.health-per-player", 1200, 2600);
            harder("boss.speed", 0.24, 0.27);
            harder("boss.melee-damage", 9, 12);
            harder("boss.attack-interval.1", 110, 75);
            harder("boss.attack-interval.2", 90, 60);
            harder("boss.attack-interval.3", 70, 42);
            harder("boss.attack-interval-phase-3", 55, 42);
            harder("boss.phase-3-combo-chance", 0.4, 0.6);
            harder("boss.minions", 4, 6);
            harder("boss.seed-rain-count", 14, 22);
            harder("boss.damage.multiplier", 1.0, 1.35);
            if (!getConfig().isSet("boss.zone-scale")) getConfig().set("boss.zone-scale", 1.5);
            getConfig().set("config-version", 3);
            saveConfig();
        }
        // v4 : 8 nouvelles reliques (2 par rareté) ajoutées aux anciennes configs, totem de l'inventaire retiré
        if (getConfig().getInt("config-version", 1) < 4) {
            addDefaultRelics();
            getConfig().set("inventory-buttons.cosmetics", false);
            getConfig().set("config-version", 4);
            saveConfig();
        }
        // v6 : cosmétiques 3D accrochés au joueur (ProtocolLib) : réactivés
        if (getConfig().getInt("config-version", 1) < 6) {
            getConfig().set("cosmetics.enabled", true);
            getConfig().set("config-version", 6);
            saveConfig();
        }
        // v7 : l'accroche côté client ne marche pas sur ce serveur : suivi classique (modèles bien placés)
        if (getConfig().getInt("config-version", 1) < 7) {
            getConfig().set("cosmetics.attach", false);
            getConfig().set("config-version", 7);
            saveConfig();
        }
        // v8 : combat plus court (~8 min) : phase 1 plus courte, moins de vie, phase 3 à 60 %
        if (getConfig().getInt("config-version", 1) < 8) {
            harder("boss.health-per-player", 2600, 1600);
            harder("boss.phase-2-at-v2", 0.5, 0.7);
            harder("boss.phase-3-health-multiplier", 1.0, 0.6);
            if (!getConfig().isSet("boss.armor-wear")) getConfig().set("boss.armor-wear", 0.25);
            getConfig().set("config-version", 8);
            saveConfig();
        }
        // v9 : équilibrage global + Jugement Dernier
        if (getConfig().getInt("config-version", 1) < 9) {
            harder("boss.damage.phase-1-multiplier", 1.4, 1.3);
            harder("boss.damage.phase-2-multiplier", 1.4, 1.45);
            harder("boss.damage.phase-3-multiplier", 1.6, 1.5);
            harder("boss.damage.armor-pierce-phase-3", 0.0, 0.3);
            harder("boss.attack-interval-phase-3", 42, 48);
            harder("boss.phase-3-combo-chance", 0.6, 0.5);
            getConfig().set("config-version", 9);
            saveConfig();
        }
        // v10 : l'épée fait plus mal au boss (plus d'armure du boss, bonus corps à corps) -> un peu plus de vie
        if (getConfig().getInt("config-version", 1) < 10) {
            harder("boss.health-per-player", 1600, 2400);
            if (!getConfig().isSet("boss.melee-bonus")) getConfig().set("boss.melee-bonus", 1.3);
            getConfig().set("config-version", 10);
            saveConfig();
        }
        // v11 : le Roi ne se soigne plus (Festin)
        if (getConfig().getInt("config-version", 1) < 11) {
            getConfig().set("boss.feast-heal-percent", 0.0);
            getConfig().set("config-version", 11);
            saveConfig();
        }
        // v12 : combat de moins de 10 min même en équipe : 1800 PV, les joueurs en plus ajoutent 70 %
        if (getConfig().getInt("config-version", 1) < 12) {
            harder("boss.health-per-player", 2400, 1800);
            if (!getConfig().isSet("boss.extra-player-health")) getConfig().set("boss.extra-player-health", 0.7);
            getConfig().set("config-version", 12);
            saveConfig();
        }
        // v13 : Jugement Dernier immense
        if (getConfig().getInt("config-version", 1) < 13) {
            harder("boss.judgment-radius", 10, 14);
            harder("boss.judgment-size", 9, 16);
            getConfig().set("config-version", 13);
            saveConfig();
        }
        // v14 : 15 min entre deux combats
        if (getConfig().getInt("config-version", 1) < 14) {
            harder("boss.cooldown-minutes", 30, 15);
            getConfig().set("config-version", 14);
            saveConfig();
        }
        // v15 : le Roi peut aussi être réveillé le jour
        if (getConfig().getInt("config-version", 1) < 15) {
            getConfig().set("boss.night-only", false);
            getConfig().set("messages.altar.hologram", null);
            getConfig().set("messages.items.sacrifice.lore", null);
            getConfig().set("config-version", 15);
            saveConfig();
        }
        // v16 : reliques prestige encore plus fortes
        if (getConfig().getInt("config-version", 1) < 16) {
            if (getConfig().isSet("relics.list.couronne_roi_eternel")) {
                harder("relics.list.couronne_roi_eternel.health", 5, 10);
                harder("relics.list.couronne_roi_eternel.damage", 20, 35);
                harder("relics.list.couronne_roi_eternel.armor", 4, 10);
                harder("relics.list.couronne_roi_eternel.regen", 2, 4);
            }
            if (getConfig().isSet("relics.list.ailes_eclipse")) {
                harder("relics.list.ailes_eclipse.speed", 15, 30);
                harder("relics.list.ailes_eclipse.health", 4, 8);
                harder("relics.list.ailes_eclipse.damage", 12, 25);
                harder("relics.list.ailes_eclipse.armor", 3, 8);
                harder("relics.list.ailes_eclipse.regen", 1, 3);
            }
            getConfig().set("config-version", 16);
            saveConfig();
        }
        // v17 : reliques prestige rééquilibrées (fortes, mais pas injouables en PvP)
        if (getConfig().getInt("config-version", 1) < 17) {
            if (getConfig().isSet("relics.list.couronne_roi_eternel")) {
                harder("relics.list.couronne_roi_eternel.health", 10, 6);
                harder("relics.list.couronne_roi_eternel.damage", 35, 25);
                harder("relics.list.couronne_roi_eternel.armor", 10, 5);
                harder("relics.list.couronne_roi_eternel.regen", 4, 3);
            }
            if (getConfig().isSet("relics.list.ailes_eclipse")) {
                harder("relics.list.ailes_eclipse.speed", 30, 20);
                harder("relics.list.ailes_eclipse.health", 8, 5);
                harder("relics.list.ailes_eclipse.damage", 25, 15);
                harder("relics.list.ailes_eclipse.armor", 8, 4);
                harder("relics.list.ailes_eclipse.regen", 3, 2);
            }
            getConfig().set("config-version", 17);
            saveConfig();
        }
        // v18 : les reliques PRESTIGE (et leur lot) ajoutées aux configs existantes
        if (getConfig().getInt("config-version", 1) < 18) {
            addDefaultRelics();
            getConfig().set("config-version", 18);
            saveConfig();
        }
        // v19 : Sonic Bow plus rapide, Tranche Mace plus forte
        if (getConfig().getInt("config-version", 1) < 19) {
            harder("weapons.sonic-bow.cooldown-seconds", 6, 3);
            harder("weapons.tranche-mace.damage", 6, 8);
            getConfig().set("config-version", 19);
            saveConfig();
        }
        // v20 : armes prestige bien plus fortes (et leur description)
        if (getConfig().getInt("config-version", 1) < 20) {
            harder("weapons.brise-terre.attack-damage", 18, 22);
            harder("weapons.brise-terre.damage", 14, 20);
            harder("weapons.brise-terre.radius", 8, 10);
            harder("weapons.brise-terre.cooldown-seconds", 8, 6);
            harder("weapons.arbalete-spectrale.skulls", 3, 5);
            harder("weapons.arbalete-spectrale.damage", 14, 16);
            harder("weapons.arbalete-spectrale.range", 32, 36);
            harder("weapons.arbalete-spectrale.wither-seconds", 4, 5);
            harder("weapons.arbalete-spectrale.glow-seconds", 8, 10);
            harder("weapons.arbalete-spectrale.cooldown-seconds", 3, 2.5);
            getConfig().set("messages.weapons.brise-terre.lore", null);
            getConfig().set("messages.weapons.arbalete-spectrale.lore", null);
            getConfig().set("config-version", 20);
            saveConfig();
        }
        // v21 : Sonic Bow (portée x3, recharge plus longue), Tranche Mace (plus de dégâts, lévitation 5 s), nouveau style
        if (getConfig().getInt("config-version", 1) < 21) {
            harder("weapons.sonic-bow.cooldown-seconds", 3, 10);
            harder("weapons.sonic-bow.range", 32, 96);
            harder("weapons.tranche-mace.attack-damage", 13, 16);
            harder("weapons.tranche-mace.damage", 8, 12);
            harder("weapons.tranche-mace.levitation-seconds", 1.5, 5);
            getConfig().set("messages.weapons.sonic-bow.lore", null);
            getConfig().set("messages.weapons.tranche-mace.lore", null);
            getConfig().set("config-version", 21);
            saveConfig();
        }
        // v22 : armes à 0,1 %, prestige en bleu nocturne
        if (getConfig().getInt("config-version", 1) < 22) {
            harder("weapons.sonic-bow.chance", 0.5, 0.1);
            harder("weapons.tranche-mace.chance", 0.5, 0.1);
            harder("weapons.brise-terre.chance", 0.001, 0.1);
            harder("weapons.arbalete-spectrale.chance", 0.001, 0.1);
            getConfig().set("messages.loot.broadcast-prestige", null);
            getConfig().set("config-version", 22);
            saveConfig();
        }
        if (getConfig().getInt("config-version", 1) < 23) {
            // armes prestige refaites : Brise-Terre (faille) et Faux des Âmes (ex-arbalète)
            harder("weapons.brise-terre.damage", 20, 16);
            harder("weapons.brise-terre.cooldown-seconds", 6, 7);
            harder("weapons.arbalete-spectrale.damage", 16, 14);
            harder("weapons.arbalete-spectrale.cooldown-seconds", 2.5, 2);
            harder("weapons.arbalete-spectrale.range", 36, 40);
            for (String k : new String[]{"brise-terre.radius", "arbalete-spectrale.skulls", "arbalete-spectrale.wither-seconds", "arbalete-spectrale.glow-seconds"})
                getConfig().set("weapons." + k, null);
            getConfig().set("messages.weapons.brise-terre", null);
            getConfig().set("messages.weapons.arbalete-spectrale", null);
            getConfig().set("config-version", 23);
            saveConfig();
        }
        if (getConfig().getInt("config-version", 1) < 24) {
            // la Faux des Âmes sort du butin ; nouvelles armes (Croc de Givre, Arc Tempête, Violet Imaginaire, Fûga)
            getConfig().set("weapons.arbalete-spectrale.chance", 0);
            getConfig().set("config-version", 24);
            saveConfig();
        }
        if (getConfig().getInt("config-version", 1) < 25) {
            // toutes les raretés PRESTIGE à 0,01 % (1 chance sur 10 000)
            for (String w : new String[]{"sonic-bow", "brise-terre", "hollow-purple", "fuga"}) harder("weapons." + w + ".chance", 0.1, 0.01);
            getConfig().set("config-version", 25);
            saveConfig();
        }
        if (getConfig().getInt("config-version", 1) < 26) {
            // Légendaire 1 %, "Secret" renommé MYTHICAL à 0,5 %, Prestige 0,001 %
            getConfig().set("loot.chances.legendary", 1);
            getConfig().set("loot.chances.secret", 0.5);
            getConfig().set("loot.chances.prestige", 0.001);
            for (String w : new String[]{"tranche-mace", "croc-de-givre", "arc-tempete"}) getConfig().set("weapons." + w + ".chance", 0.5);
            for (String w : new String[]{"sonic-bow", "brise-terre", "hollow-purple", "fuga"}) getConfig().set("weapons." + w + ".chance", 0.001);
            for (String m : new String[]{"messages.loot.broadcast-secret", "messages.loot.broadcast-prestige", "messages.menu.info-pity-secret", "messages.drops.info-lore"})
                getConfig().set(m, null);
            getConfig().set("config-version", 26);
            saveConfig();
        }
        if (getConfig().getInt("config-version", 1) < 27) {
            // phase 3 un peu plus facile
            harder("boss.damage.phase-3-multiplier", 1.5, 1.3);
            harder("boss.damage.armor-pierce-phase-3", 0.3, 0.25);
            harder("boss.phase-3-health-multiplier", 0.6, 0.5);
            harder("boss.attack-interval-phase-3", 48, 60);
            harder("boss.phase-3-combo-chance", 0.5, 0.3);
            getConfig().set("config-version", 27);
            saveConfig();
        }
        if (getConfig().getInt("config-version", 1) < 28) {
            // tous les PRESTIGE : 1 chance sur 10 000 (0,01 %)
            getConfig().set("loot.chances.prestige", 0.01);
            for (String w : new String[]{"sonic-bow", "brise-terre", "hollow-purple", "fuga"}) getConfig().set("weapons." + w + ".chance", 0.01);
            getConfig().set("messages.loot.broadcast-prestige", null);
            getConfig().set("config-version", 28);
            saveConfig();
        }
        if (getConfig().getInt("config-version", 1) < 29) {
            // Violet Imaginaire et Fûga : 15 min de recharge
            getConfig().set("weapons.hollow-purple.cooldown-minutes", 15);
            getConfig().set("weapons.fuga.cooldown-minutes", 15);
            getConfig().set("messages.weapons.hollow-purple.lore", null);
            getConfig().set("messages.weapons.fuga.lore", null);
            getConfig().set("config-version", 29);
            saveConfig();
        }
        store = new Store(this);
        items = new BossItems(this);
        relics = new Relics(this);
        loot = new Loot(this);
        weapons = new Weapons(this);
        getServer().getPluginManager().registerEvents(weapons, this);
        backpacks = new Backpacks(this);
        cosmetics = new Cosmetics(this);
        store.load();
        relics.load();
        loadAltar();
        registerRecipe();
        getServer().getPluginManager().registerEvents(new BossListener(this), this);
        getServer().getPluginManager().registerEvents(new DropsMenu.Listen(this), this);
        guildTop = new GuildTop(this);
        hologram = new AltarHologram(this);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (arena != null) arena.portalTick();
        }, 40L, 5L);
        Bukkit.getScheduler().runTaskTimer(this, hologram::tick, 40L, 10L);
        arena = new Arena(this);
        ArenaGuard guard = new ArenaGuard(this, arena);
        getServer().getPluginManager().registerEvents(guard, this);
        Bukkit.getScheduler().runTaskTimer(this, arena::ambience, 40L, 10L);
        Bukkit.getScheduler().runTaskTimer(this, arena::tint, 40L, 10L);
        Bukkit.getScheduler().runTaskTimer(this, guard::sweep, 100L, 100L);
        invButtons = new InvButtons(this);
        getServer().getPluginManager().registerEvents(invButtons, this);
        Bukkit.getScheduler().runTaskTimer(this, invButtons::tick, 40L, 40L);

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
        if (weapons != null) {
            weapons.saveKept();
            weapons.ultimates.shutdown();
        }
        if (arena != null) arena.shutdown();
        if (hologram != null) hologram.remove();
        if (relics != null) relics.removeAll();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (invButtons != null) {
                invButtons.clear(p);
                invButtons.sanitize(p);
            }
            var top = p.getOpenInventory().getTopInventory().getHolder(false);
            if (top instanceof Backpacks.Holder h) backpacks.close(h);
            if (top instanceof Backpacks.Holder || top instanceof RelicMenu) p.closeInventory();
        }
        if (store != null) store.save(false);
    }

    /** Copie depuis le config.yml du plugin les reliques et les lots qui manquent dans celui du serveur. */
    private void addDefaultRelics() {
        var def = getConfig().getDefaults();
        if (def == null) return;
        var list = def.getConfigurationSection("relics.list");
        if (list != null) {
            for (String id : list.getKeys(false)) {
                if (getConfig().isSet("relics.list." + id)) continue;
                var sec = list.getConfigurationSection(id);
                if (sec == null) continue;
                for (String k : sec.getKeys(true)) {
                    if (!sec.isConfigurationSection(k)) getConfig().set("relics.list." + id + "." + k, sec.get(k));
                }
            }
        }
        var pools = def.getConfigurationSection("loot.pools");
        if (pools == null) return;
        for (String rarity : pools.getKeys(false)) {
            List<java.util.Map<?, ?>> mine = new ArrayList<>(getConfig().getMapList("loot.pools." + rarity));
            for (java.util.Map<?, ?> m : def.getMapList("loot.pools." + rarity)) {
                Object relic = m.get("relic");
                if (relic == null) continue;
                boolean has = false;
                for (java.util.Map<?, ?> x : mine) if (relic.equals(x.get("relic"))) has = true;
                if (!has) mine.add(m);
            }
            getConfig().set("loot.pools." + rarity, mine);
        }
    }

    private void harder(String path, double old, double now) {
        if (!getConfig().isSet(path) || Math.abs(getConfig().getDouble(path) - old) < 1e-9) getConfig().set(path, now);
    }

    public String msg(String key) {
        // sans valeur par défaut explicite : les nouveaux textes du plugin sont pris même si le config.yml est ancien
        String s = getConfig().getString("messages." + key);
        return s == null ? "<red>[" + key + "]" : s;
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

    /** L'arène construite place l'autel en son centre. */
    void setAltar(Location l) {
        altar = l.clone();
        saveAltar();
    }

    // ================================================================= échanges (GpoTrade, par réflexion)

    /** Reliques que le joueur peut mettre dans un échange (pas le sac, qui garde ses objets). */
    public List<String> tradeableRelics(Player p) {
        List<String> out = new ArrayList<>();
        for (String id : store.of(p.getUniqueId()).relics) {
            Relic r = relics.get(id);
            if (r != null && !r.backpack()) out.add(id);
        }
        return out;
    }

    public ItemStack relicDisplay(String id) {
        Relic r = relics.get(id);
        return r == null ? null : items.relicItem(r);
    }

    /** La relique quitte la collection et devient un objet (pour l'échange). null si impossible. */
    public ItemStack takeRelicForTrade(Player p, String id) {
        Relic r = relics.get(id);
        PlayerData d = store.of(p.getUniqueId());
        if (r == null || r.backpack() || !d.relics.remove(r.id())) return null;
        d.equipped.remove(r.id());
        store.dirty();
        relics.apply(p);
        return items.relicItem(r);
    }

    /** Les objets-reliques de l'inventaire que le joueur n'a pas encore vont dans sa collection. */
    public int claimRelicItems(Player p) {
        int n = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            String id = items.relicId(it);
            if (id == null || id.equals("backpack-display")) continue;
            Relic r = relics.get(id);
            if (r == null || store.of(p.getUniqueId()).relics.contains(r.id())) continue;
            if (!relics.claim(p, r)) continue;
            if (it.getAmount() > 1) it.setAmount(it.getAmount() - 1);
            else inv.setItem(i, null);
            p.sendMessage(parse(msg("relics.claimed").replace("<relic>", r.rarity().name(r.name(), 0))));
            n++;
        }
        return n;
    }

    public GuildTop guildTop() {
        return guildTop;
    }

    public Location altar() {
        return altar == null ? null : altar.clone();
    }

    public Arena arena() {
        return arena;
    }

    public boolean isAltar(Block b) {
        return altar != null && b != null && b.getWorld() == altar.getWorld() && b.getX() == altar.getBlockX()
                && b.getY() == altar.getBlockY() && b.getZ() == altar.getBlockZ();
    }

    /** Ligne d'état de l'autel (texte flottant). */
    String altarStatus() {
        long left = lastEnd + getConfig().getLong("boss.cooldown-minutes", 15) * 60_000L - System.currentTimeMillis();
        if (left > 0) return msg("altar.status-cooldown").replace("<minutes>", String.valueOf(left / 60_000 + 1));
        if (getConfig().getBoolean("boss.night-only", false) && altar != null) {
            long t = altar.getWorld().getTime();
            if (t < 13000 || t > 23000) return msg("altar.status-day");
        }
        return msg("altar.status-ready");
    }

    /** Statut court du boss (MiniMessage) pour le scoreboard (SmpBoard le lit). */
    public String scoreboardStatus() {
        if (king != null && king.active()) return msg("scoreboard.fight").replace("<phase>", String.valueOf(king.phase()));
        if (altar == null || altar.getWorld() == null) return msg("scoreboard.closed");
        long left = lastEnd + getConfig().getLong("boss.cooldown-minutes", 15) * 60_000L - System.currentTimeMillis();
        if (left > 0) {
            long s = left / 1000;
            return msg("scoreboard.cooldown").replace("<time>", String.format("%d:%02d", s / 60, s % 60));
        }
        if (getConfig().getBoolean("boss.night-only", false)) {
            long tm = altar.getWorld().getTime();
            if (tm < 13000 || tm > 23000) return msg("scoreboard.day");
        }
        return msg("scoreboard.ready");
    }

    /** Statut du boss pour l'hologramme du portail : en combat, disponible, ou dans combien de temps. */
    String bossStatus() {
        if (king != null && king.active()) {
            return msg("portal.status-fight").replace("<phase>", String.valueOf(king.phase()))
                    .replace("<hp>", String.valueOf((long) Math.ceil(Math.max(0, king.hp())))).replace("<max>", String.valueOf((long) king.maxHp()))
                    .replace("<fighters>", String.valueOf(king.fighters()));
        }
        if (altar == null || altar.getWorld() == null) return msg("portal.status-closed");
        long left = lastEnd + getConfig().getLong("boss.cooldown-minutes", 15) * 60_000L - System.currentTimeMillis();
        if (left > 0) {
            long s = left / 1000;
            return msg("portal.status-cooldown").replace("<time>", String.format("%d:%02d", s / 60, s % 60));
        }
        if (getConfig().getBoolean("boss.night-only", false)) {
            long tm = altar.getWorld().getTime();
            if (tm < 13000 || tm > 23000) {
                long s = ((13000 - tm + 24000) % 24000) / 20;
                return msg("portal.status-day").replace("<time>", String.format("%d:%02d", s / 60, s % 60));
            }
        }
        return msg("portal.status-ready");
    }

    /** Un joueur utilise la citrouille sacrificielle sur l'autel. */
    public void trySummon(Player p) {
        if (king != null && king.active()) {
            p.sendMessage(parse(msg("summon.already")));
            return;
        }
        long cd = getConfig().getLong("boss.cooldown-minutes", 15) * 60_000L;
        long left = lastEnd + cd - System.currentTimeMillis();
        if (left > 0) {
            p.sendMessage(parse(msg("summon.cooldown").replace("<minutes>", String.valueOf(left / 60_000 + 1))));
            return;
        }
        if (getConfig().getBoolean("boss.night-only", false)) {
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
        if (name.equals("topguildes")) {
            guildTop.show(sender);
            return true;
        }
        if (name.equals("drops")) {
            if (sender instanceof Player p) DropsMenu.open(this, p, 0);
            return true;
        }
        if (name.equals("arene")) {
            if (sender instanceof Player p) arena.join(p);
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
            case "arene", "arène", "arena" -> arena.command(sender, a);
            case "topguildes" -> {
                if (a.length >= 2 && a[1].equalsIgnoreCase("reset")) {
                    guildTop.reset();
                    sender.sendMessage(parse("<green>Classement des guildes remis à zéro."));
                } else guildTop.show(sender);
            }
            case "panneau", "sign" -> {
                if (!(sender instanceof Player sp)) return true;
                DropsMenu.signCommand(this, sp, a.length >= 2 && (a[1].equalsIgnoreCase("retirer") || a[1].equalsIgnoreCase("remove")));
            }
            case "ult", "ulti" -> {
                Player t = a.length >= 3 ? Bukkit.getPlayerExact(a[2]) : sender instanceof Player sp ? sp : null;
                if (a.length < 2 || !a[1].equalsIgnoreCase("reset") || t == null) {
                    sender.sendMessage(parse("<red>/boss ult reset [joueur] <gray>- remet à zéro la recharge du Violet Imaginaire et de Fûga"));
                    return true;
                }
                weapons.ultimates.resetCooldown(t);
                sender.sendMessage(parse("<green>Recharge des attaques finales remise à zéro pour " + t.getName() + "."));
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
                        case "sonicbow", "sonic_bow", "arc" -> weapons.create(Weapons.SONIC);
                        case "tranchemace", "tranche_mace", "epee", "épée" -> weapons.create(Weapons.TRANCHE);
                        case "briseterre", "brise_terre", "hache" -> weapons.create(Weapons.BRISE);
                        case "faux", "fauxdesames", "arbalete", "arbalète", "arbalete_spectrale" -> weapons.create(Weapons.SPECTRAL);
                        case "crocdegivre", "croc_de_givre", "givre" -> weapons.create(Weapons.GIVRE);
                        case "arctempete", "arc_tempete", "tempete", "tempête" -> weapons.create(Weapons.TEMPETE);
                        case "hollowpurple", "hollow_purple", "violet", "hollow" -> weapons.create(Weapons.HOLLOW);
                        case "fuga", "fûga" -> weapons.create(Weapons.FUGA);
                        default -> null;
                    };
                }
                if (it == null) {
                    sender.sendMessage(parse("<red>Objet inconnu."));
                    return true;
                }
                loot.give(t, it);
                sender.sendMessage(parse("<green>Donné à " + t.getName() + "."));
            }
            case "retirer", "remove", "enlever" -> {
                if (a.length < 3) {
                    sender.sendMessage(parse("<red>/boss retirer <joueur> <id|tout>"));
                    return true;
                }
                org.bukkit.OfflinePlayer t = Bukkit.getOfflinePlayer(a[1]);
                PlayerData d = store.of(t.getUniqueId());
                String what = a[2].toLowerCase(Locale.ROOT).replace("relique:", "");
                int removed;
                if (what.equals("tout") || what.equals("all")) {
                    removed = d.relics.size();
                    d.relics.clear();
                    d.equipped.clear();
                } else {
                    removed = d.relics.remove(what) ? 1 : 0;
                    d.equipped.remove(what);
                }
                store.dirty();
                if (t.getPlayer() != null) {
                    relics.apply(t.getPlayer());
                    cosmetics.refresh(t.getPlayer());
                }
                sender.sendMessage(parse(removed > 0 ? "<green>" + removed + " relique(s) retirée(s) à " + a[1] + "."
                        : "<yellow>" + a[1] + " n'a pas cette relique. <gray>(id : " + String.join(", ", d.relics) + ")"));
            }
            case "reload" -> {
                reloadConfig();
                relics.load();
                cosmetics.loadModels();
                cosmetics.removeAll();
                for (Player pl : Bukkit.getOnlinePlayers()) cosmetics.refresh(pl);
                registerRecipe();
                for (Player p : Bukkit.getOnlinePlayers()) relics.apply(p);
                sender.sendMessage(parse("<green>SmpBoss rechargé (" + relics.all().size() + " reliques)."));
            }
            default -> {
                sender.sendMessage(parse("<gold><b>Roi Citrouille"));
                sender.sendMessage(parse("<aqua>/boss autel</aqua> <gray>- le bloc que tu regardes devient l'autel"));
                sender.sendMessage(parse("<aqua>/boss invoquer</aqua> · <aqua>/boss stop</aqua> · <aqua>/boss reload"));
                sender.sendMessage(parse("<aqua>/boss arene</aqua> <gray>- construire / protéger l'arène infernale du boss"));
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
        if (a.length == 1) out.addAll(List.of("autel", "invoquer", "stop", "give", "retirer", "reload", "arene", "topguildes", "ult", "panneau"));
        else if (a.length == 2 && a[0].toLowerCase(Locale.ROOT).startsWith("ar"))
            out.addAll(List.of("construire", "annuler", "tp", "setspawn", "portail", "sortie", "recuperer", "definir", "supprimer"));
        else if (a.length == 3 && a[0].toLowerCase(Locale.ROOT).startsWith("ar") && (a[1].equalsIgnoreCase("portail") || a[1].equalsIgnoreCase("sortie")))
            out.addAll(List.of("pos1", "pos2", "info", "supprimer"));
        else if (a.length == 3 && a[0].toLowerCase(Locale.ROOT).startsWith("ar") && a[1].equalsIgnoreCase("construire"))
            out.addAll(List.of("16", "20", "24", "28"));
        else if (a.length == 4 && a[0].toLowerCase(Locale.ROOT).startsWith("ar") && a[1].equalsIgnoreCase("construire"))
            out.addAll(List.of("ici", "ciel"));
        else if (a.length == 2 && (a[0].equalsIgnoreCase("give") || a[0].equalsIgnoreCase("retirer"))) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        else if (a.length == 3 && a[0].equalsIgnoreCase("retirer")) {
            out.add("tout");
            Player t = Bukkit.getPlayerExact(a[1]);
            if (t != null) out.addAll(store.of(t.getUniqueId()).relics);
        }
        else if (a.length == 3 && a[0].equalsIgnoreCase("give")) {
            out.addAll(List.of("graine", "citrouille", "faucille", "sonicbow", "tranchemace", "briseterre", "faux", "crocdegivre", "arctempete", "hollowpurple", "fuga"));
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

    public Weapons weapons() {
        return weapons;
    }

    public Loot loot() {
        return loot;
    }

    public InvButtons invButtons() {
        return invButtons;
    }

    public Cosmetics cosmetics() {
        return cosmetics;
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
