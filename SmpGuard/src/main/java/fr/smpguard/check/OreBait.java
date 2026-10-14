package fr.smpguard.check;

import fr.smpguard.Check;
import fr.smpguard.Profile;
import fr.smpguard.SmpGuard;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Piège à X-ray : des FAUX minerais (diamant, débris antiques) sont envoyés au joueur seul, enfermés dans la roche.
 * Un joueur normal ne peut pas les voir (aucune face à l'air) ; un X-ray les affiche à travers la pierre.
 *
 * Dès qu'un joueur casse un bloc qui touche un faux minerai, le faux redevient de la pierre pour lui
 * (un mineur honnête qui tombe dessus par hasard ne voit jamais de faux diamant) et on compte une "découverte".
 * Par hasard, un mineur honnête en découvre très rarement (on calcule la probabilité exacte selon ce qu'il a miné).
 * Un X-ray creuse droit vers eux : plusieurs découvertes alors que le hasard en prévoyait presque zéro = alerte.
 */
public final class OreBait {

    private record Fake(Block block, Material shown, long created) {
    }

    private static final class State {
        final List<Fake> fakes = new ArrayList<>();
        final Deque<Long> found = new ArrayDeque<>();
        final Deque<Long> mined = new ArrayDeque<>();
        long lastAlert;
    }

    private static final Set<Material> STONE = EnumSet.of(Material.STONE, Material.GRANITE, Material.DIORITE, Material.ANDESITE,
            Material.TUFF, Material.DEEPSLATE);
    private static final Set<Material> NETHER = EnumSet.of(Material.NETHERRACK, Material.BASALT, Material.BLACKSTONE);
    private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private final SmpGuard plugin;
    private final Map<UUID, State> states = new HashMap<>();

    public OreBait(SmpGuard plugin) {
        this.plugin = plugin;
    }

    private boolean on() {
        return plugin.settings().limits(Check.XRAY).enabled() && plugin.settings().flag(Check.XRAY, "ore-bait.enabled", true);
    }

    private double cfg(String k, double def) {
        return plugin.settings().num(Check.XRAY, "ore-bait." + k, def);
    }

    public void forget(Player p) {
        states.remove(p.getUniqueId());
    }

    /** Toutes les 5 secondes : on renouvelle les faux minerais autour des joueurs qui minent sous terre. */
    public void tick() {
        if (!on()) return;
        long now = System.currentTimeMillis();
        long life = (long) (cfg("lifetime-seconds", 300) * 1000);
        int max = (int) cfg("max-fakes", 10);
        for (Player p : Bukkit.getOnlinePlayers()) {
            State s = states.computeIfAbsent(p.getUniqueId(), k -> new State());
            // anciens faux : on remet le vrai bloc chez le joueur
            for (Iterator<Fake> it = s.fakes.iterator(); it.hasNext(); ) {
                Fake f = it.next();
                boolean far = f.block().getWorld() != p.getWorld() || f.block().getLocation().distanceSquared(p.getLocation()) > 48 * 48;
                if (now - f.created() > life || far || !STONE.contains(f.block().getType()) && !NETHER.contains(f.block().getType())) {
                    if (p.getWorld() == f.block().getWorld()) p.sendBlockChange(f.block().getLocation(), f.block().getBlockData());
                    it.remove();
                }
            }
            if (!plugin.analysed(p) || !underground(p)) continue;
            int tries = 0;
            while (s.fakes.size() < max && tries++ < 40) {
                Fake f = place(p);
                if (f != null) {
                    s.fakes.add(f);
                    p.sendBlockChange(f.block().getLocation(), f.shown().createBlockData());
                }
            }
        }
    }

    private boolean underground(Player p) {
        Location l = p.getLocation();
        World.Environment env = p.getWorld().getEnvironment();
        if (env == World.Environment.NETHER) return true;
        if (env != World.Environment.NORMAL) return false;
        return l.getY() < cfg("max-y", 40) && l.getBlock().getLightFromSky() == 0;
    }

    private Fake place(Player p) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double min = cfg("min-distance", 6), max = cfg("max-distance", 20);
        double dist = r.nextDouble(min, max), a = r.nextDouble(Math.PI * 2);
        Location l = p.getLocation().add(Math.cos(a) * dist, r.nextInt(-6, 7), Math.sin(a) * dist);
        Block b = l.getBlock();
        boolean nether = p.getWorld().getEnvironment() == World.Environment.NETHER;
        if (nether ? !NETHER.contains(b.getType()) : !STONE.contains(b.getType())) return null;
        if (b.getY() <= b.getWorld().getMinHeight() + 1) return null;
        for (BlockFace f : FACES) {
            Block n = b.getRelative(f);
            if (!n.getType().isOccluding()) return null; // une face visible : un joueur honnête pourrait le voir
        }
        // pas collé à un autre faux
        State s = states.get(p.getUniqueId());
        if (s != null) for (Fake f : s.fakes) if (f.block().getLocation().distanceSquared(b.getLocation()) < 9) return null;
        Material shown = nether ? Material.ANCIENT_DEBRIS : b.getType() == Material.DEEPSLATE ? Material.DEEPSLATE_DIAMOND_ORE : Material.DIAMOND_ORE;
        return new Fake(b, shown, System.currentTimeMillis());
    }

    /** Un bloc cassé : découvre-t-il un faux minerai ? */
    public void onBreak(Player p, Block broken) {
        if (!on()) return;
        State s = states.get(p.getUniqueId());
        if (s == null) return;
        long now = System.currentTimeMillis();
        long window = (long) (cfg("window-minutes", 30) * 60_000);
        Material m = broken.getType();
        if (STONE.contains(m) || NETHER.contains(m)) s.mined.addLast(now);
        while (!s.mined.isEmpty() && now - s.mined.peekFirst() > window) s.mined.removeFirst();
        while (!s.found.isEmpty() && now - s.found.peekFirst() > window) s.found.removeFirst();
        for (Iterator<Fake> it = s.fakes.iterator(); it.hasNext(); ) {
            Fake f = it.next();
            Block fb = f.block();
            if (fb.getWorld() != broken.getWorld()) continue;
            int d = Math.abs(fb.getX() - broken.getX()) + Math.abs(fb.getY() - broken.getY()) + Math.abs(fb.getZ() - broken.getZ());
            if (d > 1) continue;
            // découvert : il redevient de la pierre chez lui, tout de suite
            it.remove();
            Bukkit.getScheduler().runTask(plugin, () -> p.sendBlockChange(fb.getLocation(), fb.getBlockData()));
            if (now - f.created() < 3000) continue; // posé il y a moins de 3 s : il n'a pas pu le viser
            s.found.addLast(now);
            evaluate(p, s);
        }
    }

    private void evaluate(Player p, State s) {
        Profile prof = plugin.profile(p);
        if (prof == null) return;
        int found = s.found.size();
        int need = (int) cfg("min-found", 3);
        if (found < need) return;
        // chance qu'un mineur honnête découvre autant de faux par hasard :
        // chaque bloc miné dévoile ses 6 voisins ; densité des faux autour de lui = max-fakes / volume de la zone
        double rMin = cfg("min-distance", 6), rMax = cfg("max-distance", 20);
        double volume = Math.PI * (rMax * rMax - rMin * rMin) * 13; // anneau de 13 blocs de haut
        double perBlock = 6 * cfg("max-fakes", 10) / volume;
        double lambda = Math.max(1e-6, s.mined.size() * perBlock);
        double chance = poissonAtLeast(found, lambda);
        if (chance > cfg("max-chance", 0.001)) return;
        long now = System.currentTimeMillis();
        if (now - s.lastAlert < 20_000) return;
        s.lastAlert = now;
        double w = Math.max(0.6, plugin.lag().weight(prof));
        String detail = plugin.texts().raw("detail.ore-bait").replace("<found>", String.valueOf(found))
                .replace("<mined>", String.valueOf(s.mined.size()))
                .replace("<expected>", String.format(Locale.US, "%.2f", lambda))
                .replace("<chance>", chance < 1e-6 ? "< 1 sur un million" : "1 sur " + String.format(Locale.FRANCE, "%,d", Math.round(1 / chance)));
        plugin.alerts().flag(p, prof, Check.XRAY, plugin.settings().limits(Check.XRAY).alertVl() * w, detail);
    }

    /** P(X >= k) pour une loi de Poisson de moyenne lambda. */
    static double poissonAtLeast(int k, double lambda) {
        double term = Math.exp(-lambda), sum = 0;
        for (int i = 0; i < k; i++) {
            sum += term;
            term *= lambda / (i + 1);
        }
        return Math.max(0, 1 - sum);
    }
}
