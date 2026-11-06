package fr.smpboss;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static fr.smpboss.SmpBoss.parse;

/**
 * Les effets du Sandevistan (implants de Night City) : bords de l'écran teintés, titre qui se reconstruit,
 * onde au sol, ciel figé, copies rémanentes du joueur (de vraies silhouettes, avec son skin) qui changent de couleur en
 * s'éteignant, compte à rebours, sons du pack (repli vanilla sans le pack).
 */
final class SandeFx {

    private static final NamedTextColor[] PRESTIGE_GLOW = {NamedTextColor.GREEN, NamedTextColor.AQUA, NamedTextColor.DARK_AQUA, NamedTextColor.DARK_GREEN};
    private static final NamedTextColor[] PROTO_GLOW = {NamedTextColor.LIGHT_PURPLE, NamedTextColor.RED, NamedTextColor.DARK_PURPLE, NamedTextColor.DARK_RED};

    private final SmpBoss plugin;
    private final NamespacedKey imageKey;
    private final Map<UUID, State> states = new HashMap<>();

    private static final class Img {
        final Mannequin m;
        final int born;
        int stage = -1;

        Img(Mannequin m, int born) {
            this.m = m;
            this.born = born;
        }
    }

    private static final class State {
        final boolean prestige;
        final int total;
        int tick;
        double wave;
        BossBar bar;
        Location lastImage;
        final Deque<Location> trail = new ArrayDeque<>();
        final Deque<Img> images = new ArrayDeque<>();

        State(boolean prestige, int total) {
            this.prestige = prestige;
            this.total = total;
        }
    }

    SandeFx(SmpBoss plugin) {
        this.plugin = plugin;
        this.imageKey = new NamespacedKey(plugin, "sande_image");
    }

    private boolean on(String k) {
        return plugin.getConfig().getBoolean("cyberware.fx." + k, true);
    }

    void start(Player p, boolean prestige, int secs) {
        stop(p);
        State s = new State(prestige, secs * 20);
        states.put(p.getUniqueId(), s);
        p.stopAllSounds();
        sound(p, "cyber.sande_on", "block.beacon.power_select", 1f, prestige ? 1f : 1.15f);
        if (!PackStatus.has(p)) {
            p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_SONIC_CHARGE, SoundCategory.PLAYERS, 0.6f, 2f);
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1f, 0.6f);
        }
        p.playSound(p.getLocation(), Sound.AMBIENT_UNDERWATER_ENTER, SoundCategory.AMBIENT, 1f, 1.2f); // le monde devient étouffé
        Color c = prestige ? Cyberware.GREEN : Cyberware.PINK;
        p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 70, 0.6, 1.0, 0.6, 0, new Particle.DustOptions(c, 1.8f), true);
        if (on("tint")) {
            WorldBorder b = Bukkit.createWorldBorder();
            b.setCenter(p.getLocation());
            b.setSize(10_000);
            b.setWarningDistance((int) (5_000 / (1 - 0.5)));
            p.setWorldBorder(b);
        }
        if (on("freeze-sky")) p.setPlayerTime(p.getWorld().getTime(), false);
        if (on("bossbar")) {
            s.bar = BossBar.bossBar(Component.empty(), 1f, prestige ? BossBar.Color.GREEN : BossBar.Color.PINK, BossBar.Overlay.NOTCHED_20);
            p.showBossBar(s.bar);
        }
    }

    void tick(Player p, boolean prestige, int ticksLeft) {
        State s = states.get(p.getUniqueId());
        if (s == null) return;
        s.tick++;
        Location c = p.getLocation();
        // onde au sol qui part de lui
        if (s.wave < (prestige ? 24 : 16)) {
            s.wave += 2.4;
            int pts = (int) Math.min(120, 10 + s.wave * 4);
            for (int i = 0; i < pts; i++) {
                double a = Math.PI * 2 * i / pts;
                Color col = i % 3 == 0 ? Cyberware.CYAN : prestige ? Cyberware.GREEN : Cyberware.PINK;
                p.getWorld().spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * s.wave, 0.2, Math.sin(a) * s.wave), 1, 0, 0, 0, 0,
                        new Particle.DustOptions(col, 1.5f), true);
            }
        }
        // titre qui se reconstruit lettre par lettre
        if (s.tick <= 14 && on("title")) {
            String word = "SANDEVISTAN", noise = "█▓▒░#$%&@";
            StringBuilder sb = new StringBuilder();
            double clean = s.tick / 10.0;
            ThreadLocalRandom r = ThreadLocalRandom.current();
            for (char ch : word.toCharArray()) sb.append(r.nextDouble() < clean ? ch : noise.charAt(r.nextInt(noise.length())));
            String grad = prestige ? "<gradient:#b2ff59:#00ff9c:#00c853>" : "<gradient:#ff7ab8:#ff2a6d:#b0003a>";
            p.showTitle(Title.title(parse(grad + "<b>" + (s.tick < 12 ? sb : word) + "</b></gradient>"),
                    parse(prestige ? "<dark_green>// <green>temps dilaté</green> //" : "<dark_red>// <#ff7ab8>prototype instable</#ff7ab8> //"),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(s.tick < 14 ? 300 : 500), Duration.ofMillis(400))));
        }
        // compte à rebours
        if (s.bar != null && s.tick % 2 == 0) {
            float prog = Math.max(0, Math.min(1, ticksLeft / (float) Math.max(1, s.total)));
            s.bar.progress(prog);
            String sec = String.format(Locale.US, "%.1f", ticksLeft / 20.0);
            s.bar.name(parse((prestige ? "<gradient:#b2ff59:#00ff9c><b>SANDEVISTAN</b></gradient> <dark_green>|</dark_green> <green>"
                    : "<gradient:#ff7ab8:#ff2a6d><b>SANDEVISTAN</b></gradient> <dark_red>|</dark_red> <#ff7ab8>") + sec + " s"));
        }
        if (s.tick % 20 == 0) p.playSound(c, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 1f, 1.25f);
        if (ticksLeft < 50 && s.tick % 10 == 0) p.playSound(c, Sound.BLOCK_NOTE_BLOCK_BIT, SoundCategory.PLAYERS, 0.6f, 2f);
        if (s.tick % 40 == 1) sound(p, "cyber.sande_loop", null, 0.5f, 1f);
        // copies rémanentes : une tous les 2 ticks quand il bouge, à quelques pas derrière lui (pas dans sa caméra)
        s.trail.addLast(c.clone());
        while (s.trail.size() > 4) s.trail.pollFirst();
        if (on("afterimages") && s.tick % 2 == 0) {
            Location at = s.trail.peekFirst();
            if (at != null && (s.lastImage == null || s.lastImage.getWorld() != at.getWorld() || s.lastImage.distanceSquared(at) > 0.36)) {
                s.lastImage = at.clone();
                copy(p, s, at);
            }
        }
        age(s, prestige);
    }

    private void copy(Player p, State s, Location at) {
        int max = plugin.getConfig().getInt("cyberware.fx.afterimages-max", 40);
        while (s.images.size() >= max) s.images.pollFirst().m.remove();
        Mannequin m;
        try {
            m = at.getWorld().spawn(at, Mannequin.class, e -> {
                e.setPersistent(false);
                e.getPersistentDataContainer().set(imageKey, PersistentDataType.BYTE, (byte) 1);
                e.setProfile(ResolvableProfile.resolvableProfile(p.getPlayerProfile()));
                e.setImmovable(true);
                e.setInvulnerable(true);
                e.setGravity(false);
                e.setSilent(true);
                e.setCollidable(false);
                e.setDescription(Component.empty());
                e.setBodyYaw(at.getYaw());
                e.getEquipment().setArmorContents(p.getInventory().getArmorContents());
                e.getEquipment().setItemInMainHand(p.getInventory().getItemInMainHand());
                e.getEquipment().setItemInOffHand(p.getInventory().getItemInOffHand());
                if (p.isSneaking()) e.setPose(Pose.SNEAKING, true);
                e.setGlowing(true);
            });
        } catch (RuntimeException ex) {
            return;
        }
        if (!m.isValid()) return;
        s.images.addLast(new Img(m, s.tick));
    }

    /** Chaque copie change de couleur en vieillissant, puis disparaît. */
    private void age(State s, boolean prestige) {
        int life = plugin.getConfig().getInt("cyberware.fx.afterimage-ticks", 14);
        NamedTextColor[] cols = prestige ? PRESTIGE_GLOW : PROTO_GLOW;
        while (!s.images.isEmpty() && (s.tick - s.images.peekFirst().born >= life || !s.images.peekFirst().m.isValid())) s.images.pollFirst().m.remove();
        for (Img im : s.images) {
            int stage = Math.min(cols.length - 1, (s.tick - im.born) * cols.length / Math.max(1, life));
            if (stage != im.stage) {
                im.stage = stage;
                team(cols[stage]).addEntry(im.m.getUniqueId().toString());
            }
        }
    }

    void stop(Player p) {
        State s = states.remove(p.getUniqueId());
        if (s == null) return;
        for (Img im : s.images) im.m.remove();
        s.images.clear();
        if (s.bar != null) p.hideBossBar(s.bar);
        p.setWorldBorder(null);
        p.resetPlayerTime();
        p.stopSound(SoundCategory.PLAYERS);
        p.playSound(p.getLocation(), Sound.AMBIENT_UNDERWATER_EXIT, SoundCategory.AMBIENT, 1f, 1.1f);
        sound(p, "cyber.sande_off", "block.beacon.deactivate", 1f, 1f);
        p.playSound(p.getLocation(), Sound.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 0.6f, 0.6f);
    }

    void stopAll() {
        for (UUID id : new java.util.ArrayList<>(states.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) stop(p);
        }
    }

    private static Team team(NamedTextColor c) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        String name = "smpb_sde_" + c;
        Team t = sb.getTeam(name);
        if (t == null) {
            t = sb.registerNewTeam(name);
            t.color(c);
            t.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
            t.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        }
        return t;
    }

    /** Son du pack pour ceux qui l'ont, sinon le son vanilla (ou rien). */
    private static void sound(Player p, String custom, String vanilla, float vol, float pitch) {
        if (PackStatus.has(p)) p.playSound(p.getLocation(), "limitless:" + custom, SoundCategory.PLAYERS, vol, pitch);
        else if (vanilla != null) p.playSound(p.getLocation(), "minecraft:" + vanilla, SoundCategory.PLAYERS, vol, pitch);
    }
}
