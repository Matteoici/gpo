package fr.smpdrop;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static fr.smpdrop.Texts.pc;
import static fr.smpdrop.Texts.ph;
import static fr.smpdrop.Texts.pp;

/** Un largage, de l'annonce jusqu'au pillage. Tout tourne dans une seule tâche (1 tick). */
public final class DropEvent {

    public enum Phase { SEARCHING, ANNOUNCED, FALLING, LANDED, LOOTING, ENDED }

    private static final String[] ARROWS = {"⬆", "⬈", "➡", "⬊", "⬇", "⬋", "⬅", "⬉"};

    private final SmpDrop plugin;
    private final Settings s;
    private final Texts t;
    private final Random random = new Random();

    private Phase phase = Phase.SEARCHING;
    private World world;
    private Location ground;           // bloc où la caisse se pose
    private BukkitTask task;
    private int tick;

    // annonce
    private long fallAt, announceMs;
    private int lastSecond = -1;
    // chute
    private double startY;
    private int fallTick;
    private float yaw;
    private CrateModel model;
    // au sol
    private long landedAt;
    private Block barrier;
    private BlockData replaced;
    private boolean ticket;
    // ouverture
    private UUID opener;
    private String openerName;
    private int progress;
    // pillage
    private String winner;
    private LootHolder loot;
    private long lootUntil;

    private final Map<UUID, BossBar> bars = new HashMap<>();

    DropEvent(SmpDrop plugin) {
        this.plugin = plugin;
        this.s = plugin.settings();
        this.t = plugin.texts();
    }

    public Phase phase() {
        return phase;
    }

    public Location center() {
        return ground == null ? null : center(ground.getY());
    }

    private Location center(double y) {
        return new Location(world, ground.getBlockX() + 0.5, y, ground.getBlockZ() + 0.5);
    }

    // ================================================================= démarrage

    /** Cherche un endroit au hasard puis lance l'annonce. */
    void startRandom(boolean instant) {
        world = Bukkit.getWorld(s.world);
        if (world == null) {
            plugin.getLogger().warning("Monde introuvable : " + s.world);
            fail();
            return;
        }
        LocationFinder.find(plugin, world, s).thenAccept(loc -> {
            if (phase != Phase.SEARCHING) return;      // annulé entre-temps
            if (loc == null) {
                fail();
                return;
            }
            announce(loc, instant ? 5 : s.announceSeconds);
        });
    }

    /** Largage à un endroit précis (commande admin). */
    void startAt(Location block, int seconds) {
        world = block.getWorld();
        announce(block, seconds);
    }

    private void fail() {
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("smpdrop.admin")) t.send(p, "search-failed");
        plugin.getLogger().warning("Aucun emplacement valide trouvé pour le largage.");
        phase = Phase.ENDED;
        plugin.manager().ended(this);
    }

    // ================================================================= abonnés (titre, sons, flèche)

    private final java.util.Set<UUID> followers = new java.util.HashSet<>();

    /** Ce joueur veut-il les titres, sons et la flèche ? */
    boolean follows(Player p) {
        return followers.contains(p.getUniqueId()) || plugin.manager().alwaysNotify(p.getUniqueId());
    }

    boolean active() {
        return phase != Phase.SEARCHING && phase != Phase.ENDED;
    }

    /** Clic sur [Suivre ce largage]. true = suivi activé. */
    boolean toggleFollow(Player p) {
        if (followers.remove(p.getUniqueId())) {
            BossBar b = bars.remove(p.getUniqueId());
            if (b != null) p.hideBossBar(b);
            return false;
        }
        followers.add(p.getUniqueId());
        pointCompass(p);
        play(p, "block.note_block.bell", 1f, 1.4f);
        TagResolver[] r = {coords(), ph("time", time(Math.max(0, fallAt - System.currentTimeMillis()))), ph("seconds", s.openTicks / 20)};
        p.showTitle(Title.title(t.get("announce.title", r), t.get("announce.subtitle", r),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))));
        updateBars();
        return true;
    }

    private void announce(Location block, int seconds) {
        ground = block.getBlock().getLocation();
        world = ground.getWorld();
        phase = Phase.ANNOUNCED;
        announceMs = seconds * 1000L;
        fallAt = System.currentTimeMillis() + announceMs;

        tickets(true);

        TagResolver[] r = {coords(), ph("time", time(announceMs)), ph("seconds", s.openTicks / 20)};
        Title title = Title.title(t.get("announce.title", r), t.get("announce.subtitle", r),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofSeconds(1)));
        Component follow = t.get("announce.follow-button");
        for (Player p : Bukkit.getOnlinePlayers()) {
            t.sendLines(p, "announce.message", r);
            p.sendMessage(follow);
            if (!follows(p)) continue;
            p.showTitle(title);
            play(p, "event.raid.horn", 1f, 1f);
            play(p, "block.beacon.activate", 0.8f, 1.2f);
            pointCompass(p);
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        updateBars();
    }

    // ================================================================= boucle

    private void tick() {
        tick++;
        try {
            switch (phase) {
                case ANNOUNCED -> tickAnnounce();
                case FALLING -> tickFall();
                case LANDED -> tickLanded();
                case LOOTING -> tickLooting();
                default -> {
                }
            }
            if (phase != Phase.ENDED && tick % 5 == 0) updateBars();
        } catch (Exception e) {
            plugin.getLogger().severe("Erreur pendant le largage : " + e);
            e.printStackTrace();
            end();
        }
    }

    private void tickAnnounce() {
        long rem = fallAt - System.currentTimeMillis();
        int sec = (int) Math.ceil(rem / 1000.0);
        if (sec != lastSecond && sec > 0) {
            lastSecond = sec;
            if (s.countdown.contains(sec)) countdown(sec);
        }
        if (rem <= 0) beginFall();
    }

    private void countdown(int sec) {
        TagResolver[] r = {coords(), ph("time", time(sec * 1000L)), ph("seconds", sec)};
        if (sec <= 10 && s.countdownTitles) {
            Title title = Title.title(t.get("countdown.title", r), t.get("countdown.subtitle", r),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(200)));
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!follows(p)) continue;
                p.showTitle(title);
                play(p, "block.note_block.pling", 1f, 0.6f + (10 - sec) * 0.1f);
            }
        } else {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (follows(p)) {
                    t.send(p, "countdown.chat", r);
                    play(p, "block.note_block.pling", 0.7f, 1.2f);
                } else if (sec == 60) {
                    // les autres : un seul rappel discret, sans son
                    t.send(p, "countdown.chat", r);
                }
            }
        }
    }

    // ================================================================= chute

    private void beginFall() {
        phase = Phase.FALLING;
        startY = Math.min(ground.getY() + s.fallHeight, world.getMaxHeight() - 6);
        fallTick = 0;
        model = new CrateModel(plugin, s);
        model.spawn(center(startY));

        TagResolver[] r = {coords(), ph("time", time(s.fallTicks * 50L))};
        Title title = Title.title(t.get("fall.title", r), t.get("fall.subtitle", r),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            t.send(p, "fall.chat", r);
            if (!follows(p)) continue;
            p.showTitle(title);
            play(p, "entity.firework_rocket.launch", 1f, 0.6f);
            play(p, "item.elytra.flying", 0.35f, 1.4f);
        }
        // fusée de signalisation partie du point d'impact
        firework(center(ground.getY() + 1), s.glowColor, Color.WHITE, FireworkEffect.Type.STAR, 3, false);
    }

    private void tickFall() {
        fallTick++;
        double progress = Math.min(1, fallTick / (double) s.fallTicks);
        // légère décélération à la fin (le parachute freine)
        double eased = 1 - Math.pow(1 - progress, 1.35);
        double y = startY + (ground.getY() - startY) * eased;
        double sway = 1 - progress;
        Location at = center(y).add(Math.sin(fallTick * 0.07) * 0.45 * sway, 0, Math.cos(fallTick * 0.05) * 0.35 * sway);
        yaw += s.spin;

        if (fallTick % CrateModel.moveInterval() == 0 || progress >= 1) model.moveTo(at, yaw);

        if (fallTick % 3 == 0) {
            world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, at, 1, 0.15, 0, 0.15, 0.005, null, true);
            world.spawnParticle(Particle.DUST, at.clone().add(0, s.crateSize / 2, 0), 3, 0.4, 0.4, 0.4, 0,
                    new Particle.DustOptions(s.glowColor, 1.6f), true);
        }
        if (fallTick % 8 == 0) ring(center(ground.getY() + 0.15), 2.2, 28, Color.fromRGB(255, 60, 60), 1.4f);
        if (fallTick % 20 == 0) {
            for (Player p : world.getPlayers()) {
                if (p.getLocation().distanceSquared(at) < 64 * 64) play(p, "item.elytra.flying", 0.15f, 1.6f);
            }
        }
        if (progress >= 1) land();
    }

    private void land() {
        phase = Phase.LANDED;
        landedAt = System.currentTimeMillis();

        // si quelqu'un a construit à l'endroit prévu, la caisse se pose dessus
        Block b = ground.getBlock();
        while (!(b.isEmpty() || b.isPassable()) && b.getY() < world.getMaxHeight() - 2) b = b.getRelative(0, 1, 0);
        ground = b.getLocation();
        Location c = center(ground.getY());
        model.moveTo(c, yaw);

        replaced = b.getBlockData();
        b.setType(Material.BARRIER, false);
        barrier = b;
        plugin.manager().rememberBarrier(b, replaced);

        model.land(c, t.lines("landed.label", ph("seconds", s.openTicks / 20)));

        // impact
        world.spawnParticle(Particle.EXPLOSION, c, 2, 0.4, 0.2, 0.4, 0, null, true);
        world.spawnParticle(Particle.CLOUD, c, 60, 1.2, 0.1, 1.2, 0.12, null, true);
        BlockData under = b.getRelative(0, -1, 0).getBlockData();
        world.spawnParticle(Particle.BLOCK, c, 80, 1.0, 0.2, 1.0, 0.2, under, true);
        world.playSound(c, "block.anvil.land", 1.4f, 0.6f);
        world.playSound(c, "entity.generic.explode", 1.2f, 1.3f);
        if (s.lightning) world.strikeLightningEffect(c);

        TagResolver[] r = {coords(), ph("seconds", s.openTicks / 20)};
        for (Player p : Bukkit.getOnlinePlayers()) {
            t.send(p, "landed.chat", r);
            play(p, "block.beacon.power_select", 0.8f, 1.2f);
        }
    }

    private void tickLanded() {
        Location c = center(ground.getY());
        if (tick % 20 == 0) {
            world.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, c.clone().add(0, s.crateSize + 0.4, 0), 2,
                    0.1, 0.1, 0.1, 0.02, null, true);
        }
        if (tick % 6 == 0) {
            world.spawnParticle(Particle.DUST, c.clone().add(0, s.crateSize / 2, 0), 2, 0.6, 0.5, 0.6, 0,
                    new Particle.DustOptions(s.glowColor, 1.2f), true);
        }
        if (opener != null) {
            tickOpening(c);
            return;
        }
        if (System.currentTimeMillis() - landedAt > s.despawnSeconds * 1000L) expire();
    }

    // ================================================================= ouverture

    void tryOpen(Player p) {
        if (phase == Phase.LOOTING) {
            if (loot != null) p.openInventory(loot.getInventory());
            return;
        }
        if (phase != Phase.LANDED) {
            t.send(p, "opening.not-ready");
            return;
        }
        if (opener != null) {
            if (!opener.equals(p.getUniqueId())) t.send(p, "opening.already", ph("player", openerName));
            return;
        }
        if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) {
            t.send(p, "opening.gamemode");
            return;
        }
        if (s.blockNewbies && Hooks.isNewbieProtected(p)) {
            t.send(p, "opening.newbie");
            return;
        }
        Location c = center(ground.getY());
        if (p.getWorld() != world || p.getLocation().distanceSquared(c) > s.maxDistance * s.maxDistance) {
            t.send(p, "opening.too-far");
            return;
        }
        opener = p.getUniqueId();
        openerName = p.getName();
        progress = 0;
        t.send(p, "opening.start");
        play(p, "block.chest.locked", 1f, 0.8f);
        world.playSound(c, "block.beacon.power_select", 1f, 1.6f);
        alert("opening.broadcast", p.getName());
        updateBars();
    }

    private void tickOpening(Location c) {
        Player p = Bukkit.getPlayer(opener);
        if (p == null || !p.isOnline() || p.isDead()) {
            interrupt("died");
            return;
        }
        if (p.getWorld() != world || p.getLocation().distanceSquared(c) > s.maxDistance * s.maxDistance) {
            interrupt("moved");
            return;
        }
        progress++;
        double frac = Math.min(1, progress / (double) s.openTicks);
        int percent = (int) Math.floor(frac * 100);

        if (tick % 2 == 0) {
            // anneau qui se remplit autour de la caisse, du rouge au vert
            int points = 32;
            int lit = (int) Math.ceil(points * frac);
            Color col = mix(Color.fromRGB(255, 60, 60), Color.fromRGB(80, 255, 120), frac);
            for (int i = 0; i < lit; i++) {
                double ang = i / (double) points * Math.PI * 2;
                world.spawnParticle(Particle.DUST, c.clone().add(Math.cos(ang) * 1.3, 0.15, Math.sin(ang) * 1.3), 1,
                        0, 0, 0, 0, new Particle.DustOptions(col, 1.1f), true);
            }
            p.sendActionBar(t.get("opening.actionbar", pc("bar", bar(frac, 20)), ph("percent", percent)));
        }
        if (tick % 4 == 0) {
            model.text(t.lines("opening.label", ph("player", openerName), pc("bar", bar(frac, 14)), ph("percent", percent)));
        }
        if (progress % 10 == 0) world.playSound(c, "block.note_block.hat", 0.9f, 0.7f + (float) frac);
        if (progress % 20 == 0) world.playSound(c, "block.note_block.chime", 1f, 0.6f + (float) frac * 1.2f);

        if (progress >= s.openTicks) open(p);
    }

    /** Interrompt l'ouverture (coup reçu, éloignement, mort...). */
    void interrupt(String reason) {
        if (opener == null) return;
        Player p = Bukkit.getPlayer(opener);
        String name = openerName;
        opener = null;
        openerName = null;
        progress = 0;
        if (p != null) {
            t.send(p, "opening.interrupted." + reason);
            play(p, "item.shield.break", 1f, 0.8f);
            p.sendActionBar(Component.empty());
        }
        Location c = center(ground.getY());
        world.playSound(c, "block.chest.close", 1f, 0.6f);
        world.spawnParticle(Particle.SMOKE, c.clone().add(0, s.crateSize / 2, 0), 25, 0.5, 0.4, 0.5, 0.03, null, true);
        alert("opening.interrupted-broadcast", name);
        model.text(t.lines("landed.label", ph("seconds", s.openTicks / 20)));
        updateBars();
    }

    boolean isOpener(Player p) {
        return opener != null && opener.equals(p.getUniqueId());
    }

    private void open(Player p) {
        phase = Phase.LOOTING;
        opener = null;
        winner = p.getName();
        Location c = center(ground.getY());
        List<LootTable.Rolled> rolled = s.loot.roll(random);
        plugin.manager().addWin(p);

        model.open();
        Rarity best = rolled.isEmpty() ? null : rolled.get(0).entry().rarity;
        Color bestColor = best == null ? s.glowColor : best.color();

        // effets
        firework(c.clone().add(0, 1.2, 0), bestColor, Color.WHITE, FireworkEffect.Type.BALL_LARGE, 0, true);
        Bukkit.getScheduler().runTaskLater(plugin, () ->
                firework(c.clone().add(0, 2.5, 0), s.glowColor, bestColor, FireworkEffect.Type.BURST, 0, true), 8L);
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, c.clone().add(0, 1, 0), 120, 0.6, 0.8, 0.6, 0.5, null, true);
        world.playSound(c, "ui.toast.challenge_complete", 1.5f, 1f);
        world.playSound(c, "block.chest.open", 1f, 0.7f);
        play(p, "entity.player.levelup", 1f, 1f);

        // annonces
        Component bestName = rolled.isEmpty() ? Component.text("-") : describe(rolled.get(0));
        Component details = t.get("opened.details").hoverEvent(HoverEvent.showText(lootList(rolled)));
        Component msg = t.prefixed("opened.broadcast", ph("player", p.getName()), pc("best", bestName), pc("details", details));
        Title title = Title.title(t.get("opened.title"), t.get("opened.subtitle", pc("best", bestName)),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(700)));
        p.showTitle(title);
        boolean mythic = false;
        List<Component> rare = new ArrayList<>();
        for (LootTable.Rolled r : rolled) {
            if (!r.entry().rarity.announce()) continue;
            rare.add(t.prefixed("opened.rare-drop", ph("player", p.getName()), pc("item", r.entry().displayName()),
                    pp("rarity", r.entry().rarity.display())));
            if (r.entry().rarity.rank() == s.loot.rarities.size() - 1) mythic = true;
        }
        for (Player o : Bukkit.getOnlinePlayers()) {
            o.sendMessage(msg);
            rare.forEach(o::sendMessage);
            if (mythic) play(o, "item.totem.use", 0.6f, 1.2f);
        }

        List<ItemStack> items = new ArrayList<>();
        for (LootTable.Rolled r : rolled) items.addAll(r.items());
        if (s.spillMode) {
            spill(items, rolled);
            lootUntil = System.currentTimeMillis() + 5000;
        } else {
            loot = new LootHolder(t.get("opened.inventory-title"), items, random);
            lootUntil = System.currentTimeMillis() + s.chestSeconds * 1000L;
            p.openInventory(loot.getInventory());
        }
        model.text(t.lines("opened.label", ph("player", winner)));
        updateBars();
    }

    private void tickLooting() {
        long now = System.currentTimeMillis();
        if (loot != null) {
            if (tick % 10 == 0 && loot.getInventory().isEmpty() && loot.getInventory().getViewers().isEmpty()) {
                end();
                return;
            }
            if (now >= lootUntil) {
                List<ItemStack> left = new ArrayList<>();
                for (ItemStack it : loot.getInventory().getContents()) if (it != null && !it.getType().isAir()) left.add(it);
                for (var v : new ArrayList<>(loot.getInventory().getViewers())) v.closeInventory();
                loot.getInventory().clear();
                if (!left.isEmpty()) {
                    spill(left, List.of());
                    for (Player p : world.getPlayers()) {
                        if (p.getLocation().distanceSquared(center(ground.getY())) < 100 * 100) t.send(p, "opened.spilled");
                    }
                }
                end();
            }
        } else if (now >= lootUntil) {
            end();
        }
    }

    /** Appelé quand le coffre est fermé : s'il est vide, la caisse disparaît. */
    void checkEmpty() {
        if (loot != null && loot.getInventory().isEmpty()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (phase == Phase.LOOTING && loot != null && loot.getInventory().isEmpty()) end();
            }, 20L);
        }
    }

    private void spill(List<ItemStack> items, List<LootTable.Rolled> rolled) {
        Location top = center(ground.getY() + s.crateSize + 0.3);
        Map<ItemStack, Boolean> glow = new HashMap<>();
        for (LootTable.Rolled r : rolled) for (ItemStack it : r.items()) glow.put(it, r.entry().rarity.glow());
        List<ItemStack> list = new ArrayList<>(items);
        Collections.shuffle(list, random);
        for (int i = 0; i < list.size(); i++) {
            ItemStack it = list.get(i);
            boolean g = glow.getOrDefault(it, false);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Item drop = world.dropItem(top, it);
                double ang = random.nextDouble() * Math.PI * 2;
                double force = 0.12 + random.nextDouble() * 0.15;
                drop.setVelocity(new Vector(Math.cos(ang) * force, 0.35 + random.nextDouble() * 0.2, Math.sin(ang) * force));
                drop.setPickupDelay(10);
                if (g) drop.setGlowing(true);
                world.playSound(top, "entity.item.pickup", 0.6f, 0.6f + random.nextFloat() * 0.6f);
            }, i * 2L);
        }
    }

    // ================================================================= fin

    private void expire() {
        Location c = center(ground.getY());
        world.spawnParticle(Particle.EXPLOSION_EMITTER, c, 1, 0, 0, 0, 0, null, true);
        world.playSound(c, "entity.generic.explode", 1.5f, 0.8f);
        for (Player p : Bukkit.getOnlinePlayers()) t.send(p, "expired");
        end();
    }

    void cancelByAdmin() {
        for (Player p : Bukkit.getOnlinePlayers()) t.send(p, "stopped");
        end();
    }

    /** Nettoie tout : entités, barrière, chunk, barres, boussoles. */
    void end() {
        if (phase == Phase.ENDED) return;
        phase = Phase.ENDED;
        if (task != null) task.cancel();
        if (model != null) model.remove();
        if (loot != null) {
            for (var v : new ArrayList<>(loot.getInventory().getViewers())) v.closeInventory();
        }
        if (barrier != null) {
            if (barrier.getType() == Material.BARRIER) barrier.setBlockData(replaced == null ? Material.AIR.createBlockData() : replaced, false);
            barrier = null;
        }
        plugin.manager().forgetBarrier();
        tickets(false);
        for (Map.Entry<UUID, BossBar> e : bars.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) p.hideBossBar(e.getValue());
        }
        bars.clear();
        if (s.compass && world != null) {
            for (Player p : world.getPlayers()) p.setCompassTarget(world.getSpawnLocation());
        }
        plugin.manager().ended(this);
    }

    /** Garde chargés les chunks autour de la caisse (sinon elle disparaîtrait sans joueur à côté). */
    private void tickets(boolean add) {
        if (world == null || ground == null || ticket == add) return;
        ticket = add;
        int cx = ground.getBlockX() >> 4;
        int cz = ground.getBlockZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (add) world.addPluginChunkTicket(cx + dx, cz + dz, plugin);
                else world.removePluginChunkTicket(cx + dx, cz + dz, plugin);
            }
        }
    }

    // ================================================================= joueurs

    void onJoin(Player p) {
        if (phase == Phase.SEARCHING || phase == Phase.ENDED) return;
        pointCompass(p);
        updateBars();
    }

    void onQuit(Player p) {
        if (isOpener(p)) interrupt("died");
        BossBar b = bars.remove(p.getUniqueId());
        if (b != null) p.hideBossBar(b);
    }

    private void pointCompass(Player p) {
        if (s.compass && ground != null && p.getWorld() == world) p.setCompassTarget(center(ground.getY()));
    }

    boolean isCrate(Entity e) {
        return model != null && model.isPart(e);
    }

    boolean isCrateBlock(Block b) {
        return b != null && barrier != null && b.equals(barrier);
    }

    boolean isLoot(InventoryHolder h) {
        return loot != null && h == loot;
    }

    // ================================================================= barre du haut

    private void updateBars() {
        if (!s.bossbar || ground == null) return;
        Iterator<Map.Entry<UUID, BossBar>> it = bars.entrySet().iterator();
        while (it.hasNext()) if (Bukkit.getPlayer(it.next().getKey()) == null) it.remove();

        long now = System.currentTimeMillis();
        String key;
        float prog;
        BossBar.Color color;
        String time = "";
        switch (phase) {
            case ANNOUNCED -> {
                key = "bossbar.announced";
                prog = clamp((fallAt - now) / (float) announceMs);
                color = BossBar.Color.YELLOW;
                time = time(fallAt - now);
            }
            case FALLING -> {
                key = "bossbar.falling";
                prog = clamp(1 - fallTick / (float) s.fallTicks);
                color = BossBar.Color.BLUE;
            }
            case LANDED -> {
                if (opener != null) {
                    key = "bossbar.opening";
                    prog = clamp(progress / (float) s.openTicks);
                    color = BossBar.Color.RED;
                } else {
                    long left = s.despawnSeconds * 1000L - (now - landedAt);
                    key = "bossbar.landed";
                    prog = clamp(left / (s.despawnSeconds * 1000f));
                    color = BossBar.Color.GREEN;
                    time = time(left);
                }
            }
            case LOOTING -> {
                key = "bossbar.looting";
                long total = s.spillMode ? 5000 : s.chestSeconds * 1000L;
                prog = clamp((lootUntil - now) / (float) total);
                color = BossBar.Color.PURPLE;
            }
            default -> {
                return;
            }
        }
        String who = phase == Phase.LOOTING ? winner : openerName;
        int percent = (int) Math.floor(clamp(progress / (float) s.openTicks) * 100);
        Location c = center(ground.getY());
        for (Player p : Bukkit.getOnlinePlayers()) {
            // barre et flèche : seulement ceux qui ont cliqué sur [Suivre] ou [Toujours me prévenir]
            if (!follows(p)) {
                BossBar old = bars.remove(p.getUniqueId());
                if (old != null) p.hideBossBar(old);
                continue;
            }
            Component name = t.get(key, coords(), ph("time", time), ph("player", who == null ? "?" : who),
                    ph("percent", percent), direction(p, c));
            BossBar bar = bars.get(p.getUniqueId());
            if (bar == null) {
                bar = BossBar.bossBar(name, prog, color, BossBar.Overlay.PROGRESS);
                bars.put(p.getUniqueId(), bar);
                p.showBossBar(bar);
            } else {
                bar.name(name);
                bar.progress(prog);
                bar.color(color);
            }
        }
    }

    /** Placeholders <arrow> et <distance> propres à chaque joueur. */
    private TagResolver direction(Player p, Location c) {
        if (p.getWorld() != world) {
            return TagResolver.resolver(ph("arrow", ""), pc("distance", t.get("bossbar.other-world", ph("world", world.getName()))));
        }
        Location pl = p.getLocation();
        double dx = c.getX() - pl.getX();
        double dz = c.getZ() - pl.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 5) return TagResolver.resolver(ph("arrow", "●"), pc("distance", t.get("bossbar.here")));
        double target = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = target - pl.getYaw();
        rel = ((rel % 360) + 540) % 360 - 180;
        int idx = ((int) Math.round(rel / 45.0) % 8 + 8) % 8;
        String d = dist >= 1000 ? String.format(java.util.Locale.FRANCE, "%.1f km", dist / 1000) : (int) dist + " m";
        return TagResolver.resolver(ph("arrow", ARROWS[idx]), ph("distance", d));
    }

    // ================================================================= utilitaires

    TagResolver coords() {
        if (ground == null) return TagResolver.resolver(ph("x", "?"), ph("y", "?"), ph("z", "?"), ph("world", "?"));
        return TagResolver.resolver(ph("x", ground.getBlockX()), ph("y", ground.getBlockY()), ph("z", ground.getBlockZ()),
                ph("world", world.getName()));
    }

    private void alert(String key, String player) {
        Location c = center(ground.getY());
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (s.alertRadius >= 0 && (o.getWorld() != world || o.getLocation().distanceSquared(c) > s.alertRadius * s.alertRadius)) continue;
            t.send(o, key, ph("player", player));
        }
    }

    private Component describe(LootTable.Rolled r) {
        return t.get("opened.item", ph("amount", r.amount()), pc("name", r.entry().displayName()),
                pp("rarity", r.entry().rarity.display()));
    }

    private Component lootList(List<LootTable.Rolled> rolled) {
        Component out = t.get("opened.details-header");
        for (LootTable.Rolled r : rolled) out = out.append(Component.newline()).append(describe(r));
        return out;
    }

    private Component bar(double frac, int len) {
        int done = (int) Math.round(frac * len);
        return Texts.parse(t.raw("opening.bar-done") + "|".repeat(done) + t.raw("opening.bar-left") + "|".repeat(len - done));
    }

    static String time(long ms) {
        long sec = Math.max(0, (ms + 999) / 1000);
        if (sec >= 3600) return (sec / 3600) + "h" + String.format("%02d", (sec % 3600) / 60);
        return (sec / 60) + ":" + String.format("%02d", sec % 60);
    }

    private static float clamp(float f) {
        return Math.max(0, Math.min(1, f));
    }

    private static Color mix(Color a, Color b, double f) {
        return Color.fromRGB((int) (a.getRed() + (b.getRed() - a.getRed()) * f),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * f),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * f));
    }

    private void ring(Location c, double radius, int points, Color color, float size) {
        for (int i = 0; i < points; i++) {
            double a = i / (double) points * Math.PI * 2;
            world.spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius), 1,
                    0, 0, 0, 0, new Particle.DustOptions(color, size), true);
        }
    }

    private static void play(Player p, String sound, float volume, float pitch) {
        p.playSound(Sound.sound(Key.key("minecraft", sound), Sound.Source.MASTER, volume, pitch));
    }

    /** Feu d'artifice décoratif : il explose sans faire de dégâts (voir DropListener). */
    private void firework(Location loc, Color main, Color fade, FireworkEffect.Type type, int power, boolean instant) {
        Firework fw = world.spawn(loc, Firework.class, f -> {
            FireworkMeta meta = f.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder().with(type).withColor(main).withFade(fade).trail(true).flicker(true).build());
            meta.setPower(Math.max(0, power));
            f.setFireworkMeta(meta);
            f.setPersistent(false);
            f.getPersistentDataContainer().set(plugin.entityKey(), PersistentDataType.BYTE, (byte) 1);
        });
        if (instant) Bukkit.getScheduler().runTaskLater(plugin, fw::detonate, 1L);
    }

    // ================================================================= coffre de butin

    /** Inventaire partagé de la caisse ouverte. */
    static final class LootHolder implements InventoryHolder {
        private final Inventory inv;

        LootHolder(Component title, List<ItemStack> items, Random r) {
            int size = items.size() > 27 ? 54 : 27;
            inv = Bukkit.createInventory(this, size, title);
            List<Integer> slots = new ArrayList<>();
            for (int i = 0; i < size; i++) slots.add(i);
            Collections.shuffle(slots, r);
            int n = 0;
            for (ItemStack it : items) {
                if (n >= slots.size()) break;
                inv.setItem(slots.get(n++), it);
            }
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }
}
