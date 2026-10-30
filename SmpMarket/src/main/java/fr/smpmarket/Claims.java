package fr.smpmarket;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static fr.smpmarket.Texts.ph;

/**
 * Emplacements à claim.
 * - Un emplacement libre : n'importe qui le prend (clic droit sur le panneau, ou /marche installer dedans).
 *   Son marché (nom, style, vitrines, stock, catalogue...) apparaît dessus.
 * - Il s'éloigne (ou se déconnecte) : après un petit délai le marché est RANGÉ (sauvegardé avec lui)
 *   et l'emplacement redevient libre pour quelqu'un d'autre.
 * - Rangé, son stock reste achetable à distance (recherche / catalogue).
 */
public final class Claims {

    private final SmpMarket plugin;
    /** propriétaire -> depuis quand il est loin / déconnecté */
    private final Map<UUID, Long> away = new HashMap<>();

    Claims(SmpMarket plugin) {
        this.plugin = plugin;
    }

    private Texts t() {
        return plugin.texts();
    }

    // ================================================================= installer

    public boolean deploy(Player p, Stall spot) {
        if (spot == null || spot.isPacked()) return false;
        if (spot.isRented()) {
            if (spot.isOwner(p.getUniqueId())) t().send(p, "claim.already-yours");
            else t().send(p, "claim.taken", ph("player", spot.ownerName == null ? "?" : spot.ownerName));
            return false;
        }
        for (Stall s : plugin.store().stalls()) {
            if (s.isOwner(p.getUniqueId())) {
                t().send(p, "claim.already-deployed", plugin.auctions().stallTags(s));
                return false;
            }
        }
        if (spot.auction != null) plugin.auctions().cancel(spot);
        Stall m = plugin.store().takeMarket(p.getUniqueId());
        spot.owner = p.getUniqueId();
        spot.ownerName = p.getName();
        spot.leaseEnd = Long.MAX_VALUE;
        spot.paid = 0;
        if (m != null) {
            copyStyle(m, spot);
            putBuild(spot, m.build); // sa construction réapparaît, centrée et tournée vers l'entrée
            int ox = spot.region.minX(), oy = spot.region.minY(), oz = spot.region.minZ();
            Frame fr = frame(spot);
            for (Showcase c : m.showcases) {
                if (c.virtual) {
                    spot.showcases.add(c);
                    continue;
                }
                int[] wpos = m.layout == 2 ? toWorld(fr, c.x, c.y, c.z) : new int[]{c.x + ox, c.y + oy, c.z + oz};
                Showcase n = new Showcase(c.id, c.template, c.price, c.stock, wpos[0], wpos[1], wpos[2]);
                n.sold = c.sold;
                n.revenue = c.revenue;
                // emplacement plus petit / autrement construit : l'article passe au catalogue (rien n'est perdu)
                if (!spot.region.contains(n.x, n.y, n.z) || !spot.region.contains(n.x, n.y + 1, n.z)) {
                    Showcase v = Showcase.virtual(c.id, c.template, c.price, c.stock);
                    v.sold = c.sold;
                    v.revenue = c.revenue;
                    spot.showcases.add(v);
                } else {
                    spot.showcases.add(n);
                }
            }
        } else {
            resetStyle(spot);
            spot.totalSold = 0;
            spot.totalRevenue = 0;
        }
        spot.touch();
        away.remove(p.getUniqueId());
        plugin.store().save();
        plugin.visuals().rebuild(spot);
        plugin.mannequins().refresh(spot);
        t().send(p, m == null ? "claim.v2.deployed-new" : "claim.v2.deployed", plugin.auctions().stallTags(spot),
                ph("count", spot.itemsForSale()));
        Fx.sound(p, "block.barrel.open", 1f);
        Fx.sound(p, "entity.player.levelup", 1.4f);
        return true;
    }

    // ================================================================= ranger

    /** Range le marché de cet emplacement (sauvegardé avec son propriétaire) et libère l'emplacement. */
    public void pack(Stall spot, String reasonKey) {
        if (spot == null || spot.isPacked() || !spot.isRented()) return;
        UUID owner = spot.owner;
        // anciens stands : coffres posés dedans -> dans le colis du propriétaire
        int items = plugin.auctions().collectContainers(spot);
        if (items > 0) plugin.notify(owner, t().get("parcel.received", ph("count", items)));
        Stall m = new Stall("m-" + owner);
        m.owner = owner;
        m.ownerName = spot.ownerName;
        copyStyle(spot, m);
        Frame fr = frame(spot);
        m.build.addAll(takeBuild(spot, owner)); // sa construction part avec lui : l'emplacement redevient vide
        m.layout = 2;
        for (Showcase c : spot.showcases) {
            if (c.virtual) {
                m.showcases.add(c);
                continue;
            }
            int[] l = toLocal(fr, c.x, c.y, c.z);
            Showcase r = new Showcase(c.id, c.template, c.price, c.stock, l[0], l[1], l[2]);
            r.sold = c.sold;
            r.revenue = c.revenue;
            m.showcases.add(r);
        }
        m.lastChange = System.currentTimeMillis();
        plugin.store().putMarket(m);

        plugin.visuals().removeShowcases(spot);
        spot.showcases.clear();
        spot.owner = null;
        spot.ownerName = null;
        spot.leaseEnd = 0;
        resetStyle(spot);
        spot.totalSold = 0;
        spot.totalRevenue = 0;
        spot.containers.clear();
        spot.tracksContainers = true;
        away.remove(owner);
        plugin.store().save();
        plugin.visuals().rebuild(spot);
        plugin.mannequins().refresh(spot);
        if (reasonKey != null) plugin.notify(owner, t().get(reasonKey, plugin.auctions().stallTags(spot)));
    }

    private static void copyStyle(Stall from, Stall to) {
        to.name = from.name;
        to.theme = from.theme;
        to.background = from.background;
        to.symbol = from.symbol;
        to.slogan = from.slogan;
        to.extraShowcases = from.extraShowcases;
        to.catalogSlots = from.catalogSlots;
        to.privateStall = from.privateStall;
        to.mannequin = from.mannequin;
        to.skinValue = from.skinValue;
        to.skinSignature = from.skinSignature;
        to.trusted.clear();
        to.trusted.putAll(from.trusted);
        to.totalSold = from.totalSold;
        to.totalRevenue = from.totalRevenue;
        to.npcSpot = null;
    }

    private static void resetStyle(Stall st) {
        st.name = null;
        st.theme = null;
        st.background = null;
        st.symbol = null;
        st.slogan = null;
        st.extraShowcases = 0;
        st.catalogSlots = 0;
        st.privateStall = false;
        st.mannequin = false;
        st.npcSpot = null;
        st.skinValue = null;
        st.skinSignature = null;
        st.trusted.clear();
    }

    // ================================================================= chaque seconde

    /** Le joueur quitte le serveur : son marché se range (tout de suite, ou après le délai réglé). */
    void onQuit(Player p) {
        for (Stall st : new java.util.ArrayList<>(plugin.store().stalls())) {
            if (!st.isOwner(p.getUniqueId())) continue;
            if (plugin.settings().quitDelayMs <= 0) pack(st, "claim.v2.packed-quit");
            else away.put(p.getUniqueId(), System.currentTimeMillis());
        }
    }

    /** Chaque seconde : marchés dont le propriétaire n'est plus connecté (délai, redémarrage...). */
    void tick() {
        long now = System.currentTimeMillis();
        for (Stall st : new java.util.ArrayList<>(plugin.store().stalls())) {
            if (!st.isRented()) continue;
            if (Bukkit.getPlayer(st.owner) != null) {
                away.remove(st.owner);
                continue;
            }
            long since = away.computeIfAbsent(st.owner, k -> now);
            if (now - since >= plugin.settings().quitDelayMs) pack(st, "claim.v2.packed-quit");
        }
    }

    /** Au démarrage : les vieilles enchères sont annulées et remboursées (on ne loue plus, on claim). */
    void migrate() {
        for (Stall st : plugin.store().stalls()) {
            if (st.auction != null) plugin.auctions().cancel(st);
            if (st.isRented()) st.leaseEnd = Long.MAX_VALUE;
        }
        if (!plugin.store().oldBuildsClearedV2) {
            for (Stall st : plugin.store().stalls()) st.floorY = Integer.MIN_VALUE;
        }
        plugin.store().markDirty();
        // les vieux stands sont retirés juste après (mondes chargés, propriétaires rangés)
        Bukkit.getScheduler().runTaskLater(plugin, this::wipeOldStands, 100L);
    }

    // ================================================================= construction du marché

    // ================================================================= repère d'un emplacement

    /**
     * Repère d'un emplacement : son centre (coordonnées doublées pour rester entières), son sol, et son orientation
     * (le regard du point d'entrée, au quart de tour). Une construction enregistrée dans ce repère se remet au centre
     * de n'importe quel autre emplacement, tournée pour regarder du même côté que son entrée.
     */
    record Frame(int cx2, int floor, int cz2, int facing) {
    }

    Frame frame(Stall st) {
        return new Frame(st.region.minX() + st.region.maxX() + 1, floor(st), st.region.minZ() + st.region.maxZ() + 1,
                Math.floorMod(Math.round(st.eyaw / 90f), 4));
    }

    /** (x, z) tourné de k quarts de tour (sens des aiguilles d'une montre vu du dessus, comme Minecraft). */
    static int[] rot(int x, int z, int k) {
        for (int i = 0; i < Math.floorMod(k, 4); i++) {
            int t = x;
            x = -z;
            z = t;
        }
        return new int[]{x, z};
    }

    static int[] toLocal(Frame f, int x, int y, int z) {
        int[] r = rot(2 * x + 1 - f.cx2(), 2 * z + 1 - f.cz2(), -f.facing());
        return new int[]{r[0], y - f.floor(), r[1]};
    }

    static int[] toWorld(Frame f, int lx, int ly, int lz) {
        int[] r = rot(lx, lz, f.facing());
        return new int[]{Math.floorDiv(r[0] + f.cx2() - 1, 2), ly + f.floor(), Math.floorDiv(r[1] + f.cz2() - 1, 2)};
    }

    private static org.bukkit.block.structure.StructureRotation rotation(int k) {
        return switch (Math.floorMod(k, 4)) {
            case 1 -> org.bukkit.block.structure.StructureRotation.CLOCKWISE_90;
            case 2 -> org.bukkit.block.structure.StructureRotation.CLOCKWISE_180;
            case 3 -> org.bukkit.block.structure.StructureRotation.COUNTERCLOCKWISE_90;
            default -> org.bukkit.block.structure.StructureRotation.NONE;
        };
    }

    /** Tout l'emplacement (bords compris) appartient au marché posé dessus. */
    private static int margin(Stall st) {
        return 0;
    }

    /** Hauteur au-dessus de la zone prise en compte (toits des anciens stands). */
    private int above() {
        return Math.max(0, plugin.getConfig().getInt("claim.clear-above", 0));
    }

    /** Retire ce qui reste sur un emplacement libre (anciens stands) ; renvoie true s'il y avait quelque chose. */
    public boolean wipe(Stall st) {
        if (st.isRented() || st.region == null || st.region.bukkitWorld() == null) return false;
        return !takeBuild(st, null).isEmpty();
    }

    /** Une seule fois, quelques secondes après le démarrage (les mondes sont chargés) : emplacements libres vidés. */
    void wipeOldStands() {
        if (plugin.store().oldBuildsClearedV2) return;
        boolean all = true;
        int n = 0;
        for (Stall st : plugin.store().stalls()) {
            if (st.region == null) continue;
            if (st.region.bukkitWorld() == null) {
                all = false;
                continue;
            }
            if (st.isRented()) continue; // il sera rangé (et sa construction gardée) à la seconde suivante
            st.floorY = Integer.MIN_VALUE; // recalculé sur tout l'emplacement
            if (wipe(st)) n++;
        }
        if (all) plugin.store().oldBuildsClearedV2 = true;
        plugin.store().save();
        if (n > 0) plugin.getLogger().info(n + " ancien(s) stand(s) retiré(s) des emplacements libres.");
    }

    /** Peut-on construire ici ? Seulement à l'intérieur, au-dessus du sol (ce qui partira avec le marché). */
    public boolean buildable(Stall st, org.bukkit.block.Block b) {
        int m = margin(st);
        return b.getX() >= st.region.minX() + m && b.getX() <= st.region.maxX() - m && b.getZ() >= st.region.minZ() + m
                && b.getZ() <= st.region.maxZ() - m && b.getY() > floor(st) && b.getY() <= st.region.maxY();
    }

    /** Hauteur du sol de l'emplacement (couches pleines à au moins 60 % en partant du bas). */
    int floor(Stall st) {
        if (st.floorY != Integer.MIN_VALUE) return st.floorY;
        org.bukkit.World w = st.region.bukkitWorld();
        if (w == null) return st.region.minY();
        int m = margin(st), f = st.region.minY() - 1;
        for (int y = st.region.minY(); y <= st.region.maxY(); y++) {
            int solid = 0, total = 0;
            for (int x = st.region.minX() + m; x <= st.region.maxX() - m; x++) {
                for (int z = st.region.minZ() + m; z <= st.region.maxZ() - m; z++) {
                    total++;
                    if (w.getBlockAt(x, y, z).getType().isSolid()) solid++;
                }
            }
            if (total > 0 && solid >= total * 0.6) f = y;
            else break;
        }
        st.floorY = f;
        plugin.store().markDirty();
        return f;
    }

    /**
     * Retire tout ce qui est construit sur l'emplacement (au-dessus du sol, à l'intérieur) et le renvoie.
     * Cadres / tableaux / porte-armures : leur contenu va dans le colis du propriétaire.
     */
    private List<String> takeBuild(Stall st, UUID owner) {
        List<String> out = new java.util.ArrayList<>();
        org.bukkit.World w = st.region.bukkitWorld();
        if (w == null) return out;
        int m = margin(st), f = floor(st);
        int x0 = st.region.minX() + m, x1 = st.region.maxX() - m, z0 = st.region.minZ() + m, z1 = st.region.maxZ() - m;
        int y1 = st.region.maxY() + above();
        if (f + 1 > y1 || x0 > x1 || z0 > z1) return out;
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) w.getChunkAt(cx, cz);
        org.bukkit.util.BoundingBox bb = new org.bukkit.util.BoundingBox(x0, f + 1, z0, x1 + 1, y1 + 1, z1 + 1);
        for (org.bukkit.entity.Entity e : w.getNearbyEntities(bb)) {
            if (!e.getPersistentDataContainer().getKeys().isEmpty()) continue; // vitrines, mannequins... (plugins)
            List<org.bukkit.inventory.ItemStack> give = new java.util.ArrayList<>();
            if (e instanceof org.bukkit.entity.ItemFrame fr) {
                give.add(fr.getItem());
                give.add(new org.bukkit.inventory.ItemStack(fr instanceof org.bukkit.entity.GlowItemFrame ? org.bukkit.Material.GLOW_ITEM_FRAME : org.bukkit.Material.ITEM_FRAME));
            } else if (e instanceof org.bukkit.entity.Painting) {
                give.add(new org.bukkit.inventory.ItemStack(org.bukkit.Material.PAINTING));
            } else if (e instanceof org.bukkit.entity.ArmorStand as) {
                for (org.bukkit.inventory.ItemStack it : as.getEquipment().getArmorContents()) give.add(it);
                give.add(as.getEquipment().getItemInMainHand());
                give.add(as.getEquipment().getItemInOffHand());
                give.add(new org.bukkit.inventory.ItemStack(org.bukkit.Material.ARMOR_STAND));
            } else continue;
            if (owner != null) for (org.bukkit.inventory.ItemStack it : give) plugin.store().addParcel(owner, it);
            e.remove();
        }
        Frame fr = new Frame(st.region.minX() + st.region.maxX() + 1, f, st.region.minZ() + st.region.maxZ() + 1,
                Math.floorMod(Math.round(st.eyaw / 90f), 4));
        // de haut en bas : rien ne tombe ni ne se casse en route
        for (int y = y1; y > f; y--) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    org.bukkit.block.Block b = w.getBlockAt(x, y, z);
                    if (b.getType().isAir()) continue;
                    int[] l = toLocal(fr, x, y, z);
                    org.bukkit.block.data.BlockData data = b.getBlockData().clone();
                    try {
                        data.rotate(rotation(-fr.facing()));
                    } catch (RuntimeException ignored) {
                    }
                    out.add("L|" + l[0] + "," + l[1] + "," + l[2] + "|" + data.getAsString());
                    // contenu des blocs de déco (bibliothèque sculptée, étagère, pot, pupitre, jukebox...) : gardé aussi
                    if (b.getState(false) instanceof org.bukkit.inventory.BlockInventoryHolder h) {
                        org.bukkit.inventory.ItemStack[] content = h.getInventory().getContents();
                        StringBuilder sb = new StringBuilder();
                        boolean any = false;
                        for (int i = 0; i < content.length; i++) {
                            if (i > 0) sb.append(';');
                            String enc = Items.encode(content[i]);
                            if (!enc.isEmpty()) any = true;
                            sb.append(enc.isEmpty() ? "-" : enc);
                        }
                        if (any) out.add("I|" + l[0] + "," + l[1] + "," + l[2] + "|" + sb);
                        h.getInventory().clear();
                    }
                    b.setType(org.bukkit.Material.AIR, false);
                }
            }
        }
        java.util.Collections.reverse(out); // gardé de bas en haut pour le remettre dans l'ordre
        return out;
    }

    /** Remet une construction sur un emplacement (de bas en haut, seulement dans l'emplacement et au-dessus du sol). */
    private void putBuild(Stall st, List<String> build) {
        org.bukkit.World w = st.region.bukkitWorld();
        if (w == null || build.isEmpty()) return;
        int f = floor(st);
        Frame fr = frame(st);
        List<int[]> pos = new java.util.ArrayList<>();
        List<String> data = new java.util.ArrayList<>();
        List<String> contents = new java.util.ArrayList<>();
        for (String s : build) {
            if (s.startsWith("I|")) {
                contents.add(s);
                continue;
            }
            try {
                boolean local = s.startsWith("L|");
                String body = local ? s.substring(2) : s;
                int bar = body.indexOf('|');
                String[] p = body.substring(0, bar).split(",");
                int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]), c = Integer.parseInt(p[2]);
                int[] wp = local ? toWorld(fr, a, b, c)
                        : new int[]{st.region.minX() + a, st.region.minY() + b, st.region.minZ() + c}; // ancien format
                String d = body.substring(bar + 1);
                if (local && fr.facing() != 0) {
                    org.bukkit.block.data.BlockData bd = org.bukkit.Bukkit.createBlockData(d);
                    try {
                        bd.rotate(rotation(fr.facing()));
                    } catch (RuntimeException ignored) {
                    }
                    d = bd.getAsString();
                }
                pos.add(wp);
                data.add(d);
            } catch (RuntimeException ignored) {
                // bloc d'une autre version : on passe
            }
        }
        Integer[] order = new Integer[pos.size()];
        for (int k = 0; k < order.length; k++) order[k] = k;
        java.util.Arrays.sort(order, java.util.Comparator.comparingInt(k -> pos.get(k)[1]));
        for (int k : order) {
            int[] p = pos.get(k);
            if (p[0] < st.region.minX() || p[0] > st.region.maxX() || p[2] < st.region.minZ() || p[2] > st.region.maxZ()) continue;
            if (p[1] <= f || p[1] > st.region.maxY() + above()) continue;
            org.bukkit.block.Block bl = w.getBlockAt(p[0], p[1], p[2]);
            if (!bl.getType().isAir()) continue;
            try {
                bl.setBlockData(org.bukkit.Bukkit.createBlockData(data.get(k)), false);
            } catch (RuntimeException ignored) {
            }
        }
        // puis on remet le contenu des blocs de déco
        for (String s : contents) {
            try {
                String body = s.substring(2);
                int bar = body.indexOf('|');
                String[] p = body.substring(0, bar).split(",");
                int[] wp = toWorld(fr, Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                org.bukkit.block.Block bl = w.getBlockAt(wp[0], wp[1], wp[2]);
                if (!(bl.getState(false) instanceof org.bukkit.inventory.BlockInventoryHolder h)) continue;
                String[] parts = body.substring(bar + 1).split(";", -1);
                org.bukkit.inventory.ItemStack[] items = new org.bukkit.inventory.ItemStack[h.getInventory().getSize()];
                for (int i = 0; i < parts.length && i < items.length; i++) items[i] = "-".equals(parts[i]) ? null : Items.decode(parts[i]);
                h.getInventory().setContents(items);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Emplacements libres. */
    public int freeCount() {
        int n = 0;
        for (Stall st : plugin.store().stalls()) if (!st.isRented()) n++;
        return n;
    }

    public static String ownerName(OfflinePlayer o) {
        return o.getName() == null ? "?" : o.getName();
    }
}
