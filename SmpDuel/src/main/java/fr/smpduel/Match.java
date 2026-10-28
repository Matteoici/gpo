package fr.smpduel;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static fr.smpduel.SmpDuel.mm;

/** Un duel en cours : compte à rebours, combat, fin (et temps pour ramasser le butin en duel à mort). */
final class Match {

    enum State {COUNTDOWN, FIGHT, LOOT, OVER}

    final SmpDuel plugin;
    final DuelArena arena;
    final Mode mode;
    final UUID ida, idb;
    final String na, nb;
    final List<ItemStack> stakeA, stakeB;
    State state = State.COUNTDOWN;
    private int ticks;
    private int fightTicks;
    private BukkitTask task;
    UUID winner;

    Match(SmpDuel plugin, DuelArena arena, Mode mode, Player a, Player b, List<ItemStack> stakeA, List<ItemStack> stakeB) {
        this.plugin = plugin;
        this.arena = arena;
        this.mode = mode;
        this.ida = a.getUniqueId();
        this.idb = b.getUniqueId();
        this.na = a.getName();
        this.nb = b.getName();
        this.stakeA = stakeA == null ? List.of() : stakeA;
        this.stakeB = stakeB == null ? List.of() : stakeB;
    }

    Player a() {
        return Bukkit.getPlayer(ida);
    }

    Player b() {
        return Bukkit.getPlayer(idb);
    }

    UUID other(UUID id) {
        return id.equals(ida) ? idb : ida;
    }

    String name(UUID id) {
        return id.equals(ida) ? na : nb;
    }

    boolean has(UUID id) {
        return id.equals(ida) || id.equals(idb);
    }

    boolean fighting() {
        return state == State.FIGHT;
    }

    // ================================================================= début

    void start() {
        arena.match = this;
        if (mode == Mode.PARI) {
            plugin.escrow().lock(ida, stakeA);
            plugin.escrow().lock(idb, stakeB);
        }
        prepare(a(), arena.spawn1);
        prepare(b(), arena.spawn2);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void prepare(Player p, Location spawn) {
        // retour : là où il était (gardé aussi sur le joueur, au cas où il se déconnecte)
        p.getPersistentDataContainer().set(plugin.returnKey, PersistentDataType.STRING,
                p.getWorld().getName() + ";" + DuelArena.loc(p.getLocation()));
        p.getPersistentDataContainer().set(plugin.duelKey, PersistentDataType.STRING, mode.id);
        p.setGliding(false);
        p.closeInventory();
        plugin.teleport(p, spawn);
        heal(p);
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 110, 4, false, false, false));
    }

    static void heal(Player p) {
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(max == null ? 20 : max.getValue());
        p.setFoodLevel(20);
        p.setSaturation(10);
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.setFreezeTicks(0);
    }

    // ================================================================= boucle

    private void tick() {
        ticks++;
        Player a = a(), b = b();
        switch (state) {
            case COUNTDOWN -> {
                int left = 5 - ticks / 20;
                if (ticks % 20 == 1 && left > 0) {
                    for (Player p : new Player[]{a, b}) {
                        if (p == null) continue;
                        p.showTitle(Title.title(mm(plugin.msg("countdown-title").replace("<n>", String.valueOf(left))),
                                mm(plugin.msg("countdown-sub").replace("<mode>", plugin.modeName(mode)).replace("<enemy>", name(other(p.getUniqueId())))),
                                Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ofMillis(100))));
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.8f + (5 - left) * 0.1f);
                    }
                }
                if (ticks >= 100) {
                    state = State.FIGHT;
                    for (Player p : new Player[]{a, b}) {
                        if (p == null) continue;
                        p.showTitle(Title.title(mm(plugin.msg("fight-title")), mm(plugin.msg("fight-sub")),
                                Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(400))));
                        p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f, 1.4f);
                        p.playSound(p.getLocation(), Sound.ITEM_TRIDENT_THUNDER, 0.6f, 1f);
                        p.removePotionEffect(PotionEffectType.RESISTANCE);
                    }
                }
            }
            case FIGHT -> {
                fightTicks++;
                int limit = plugin.getConfig().getInt("max-minutes", 5) * 1200;
                if (fightTicks >= limit) {
                    draw(plugin.msg("draw-time"));
                    return;
                }
                if (ticks % 5 == 0) {
                    keepIn(a, arena.spawn1);
                    keepIn(b, arena.spawn2);
                }
                if (ticks % 20 == 0) {
                    int s = (limit - fightTicks) / 20;
                    String time = String.format("%d:%02d", s / 60, s % 60);
                    bar(a, b, time);
                    bar(b, a, time);
                }
            }
            case LOOT -> {
                Player w = winner == null ? null : Bukkit.getPlayer(winner);
                int left = plugin.getConfig().getInt("loot-seconds", 20) - fightTicks / 20;
                fightTicks++;
                if (w == null || !w.isOnline() || left <= 0 || !arena.region(w.getLocation())) {
                    finish();
                    return;
                }
                if (ticks % 20 == 0) w.sendActionBar(mm(plugin.msg("loot-bar").replace("<s>", String.valueOf(left))));
            }
            default -> {
            }
        }
    }

    private void bar(Player p, Player enemy, String time) {
        if (p == null) return;
        String hp = enemy == null ? "?" : String.valueOf((int) Math.ceil(enemy.getHealth() / 2));
        p.sendActionBar(mm(plugin.msg("fight-bar").replace("<enemy>", enemy == null ? "?" : enemy.getName())
                .replace("<hp>", hp).replace("<time>", time).replace("<mode>", plugin.modeName(mode))));
    }

    /** Sorti du cercle (perle, saut par-dessus le muret...) : retour à son plot. */
    private void keepIn(Player p, Location spawn) {
        if (p == null || p.isDead() || arena.inside(p.getLocation())) return;
        plugin.teleport(p, spawn);
        p.setFallDistance(0);
        p.sendActionBar(mm(plugin.msg("out-of-bounds")));
    }

    // ================================================================= fin

    /** Un joueur est mort (ou a fui) : l'autre gagne. */
    void win(UUID winnerId, boolean byQuit) {
        if (state != State.FIGHT) return;
        this.winner = winnerId;
        UUID loser = other(winnerId);
        int elo = plugin.stats().record(winnerId, name(winnerId), loser, name(loser), mode, plugin.getConfig().getInt("elo-k", 32));
        plugin.stats().save(false);
        String msg = plugin.msg(byQuit ? "result-quit" : "result").replace("<winner>", name(winnerId)).replace("<loser>", name(loser))
                .replace("<mode>", plugin.modeName(mode)).replace("<elo>", String.valueOf(elo));
        if (plugin.getConfig().getBoolean("broadcast-results", true)) Bukkit.broadcast(mm(msg));
        else for (Player p : new Player[]{a(), b()}) if (p != null) p.sendMessage(mm(msg));
        // les mises
        if (mode == Mode.PARI) {
            plugin.escrow().unlock(ida);
            plugin.escrow().unlock(idb);
            List<ItemStack> all = new ArrayList<>(stakeA);
            all.addAll(stakeB);
            plugin.escrow().give(winnerId, all);
        }
        Player w = Bukkit.getPlayer(winnerId);
        if (w != null) {
            w.showTitle(Title.title(mm(plugin.msg("win-title")), mm(plugin.msg("win-sub").replace("<elo>", String.valueOf(elo))),
                    Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2500), Duration.ofMillis(600))));
            w.playSound(w.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            w.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, w.getLocation().add(0, 1, 0), 80, 0.5, 1, 0.5, 0.4);
            w.getPersistentDataContainer().remove(plugin.duelKey);
        }
        Player l = Bukkit.getPlayer(loser);
        if (l != null) {
            l.showTitle(Title.title(mm(plugin.msg("lose-title")), mm(plugin.msg("lose-sub").replace("<elo>", String.valueOf(elo))),
                    Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2500), Duration.ofMillis(600))));
            l.getPersistentDataContainer().remove(plugin.duelKey);
        }
        if (mode == Mode.MORT && !byQuit) {
            // le gagnant a le temps de ramasser le stuff
            state = State.LOOT;
            fightTicks = 0;
            if (w != null) w.sendMessage(mm(plugin.msg("loot-info").replace("<s>", String.valueOf(plugin.getConfig().getInt("loot-seconds", 20)))));
        } else {
            state = State.OVER;
            if (w != null) heal(w);
            Bukkit.getScheduler().runTaskLater(plugin, this::finish, 60L);
        }
    }

    /** Égalité (temps écoulé, arrêt du serveur...) : chacun repart avec ses affaires, les mises sont rendues. */
    void draw(String reason) {
        if (state == State.OVER) return;
        boolean wasFight = state == State.FIGHT || state == State.COUNTDOWN;
        state = State.OVER;
        if (wasFight && mode == Mode.PARI) {
            plugin.escrow().unlock(ida);
            plugin.escrow().unlock(idb);
            plugin.escrow().give(ida, stakeA);
            plugin.escrow().give(idb, stakeB);
        }
        for (Player p : new Player[]{a(), b()}) {
            if (p == null) continue;
            p.sendMessage(mm(reason));
            p.getPersistentDataContainer().remove(plugin.duelKey);
        }
        finish();
    }

    /** Tout le monde rentre, l'arène est nettoyée et libérée. */
    void finish() {
        if (task != null) task.cancel();
        task = null;
        state = State.OVER;
        for (UUID id : new UUID[]{ida, idb}) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || p.isDead()) continue; // il rentrera à la réapparition / à la connexion
            p.getPersistentDataContainer().remove(plugin.duelKey);
            if (arena.region(p.getLocation())) plugin.sendBack(p);
            else p.getPersistentDataContainer().remove(plugin.returnKey);
        }
        arena.cleanup();
        arena.match = null;
        plugin.ended(this);
    }

    static NamespacedKey key(SmpDuel plugin, String k) {
        return new NamespacedKey(plugin, k);
    }
}
