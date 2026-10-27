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
            putBuild(spot, m.build); // sa construction réapparaît
            int ox = spot.region.minX(), oy = spot.region.minY(), oz = spot.region.minZ();
            for (Showcase c : m.showcases) {
                if (c.virtual) {
                    spot.showcases.add(c);
                    continue;
                }
                Showcase n = new Showcase(c.id, c.template, c.price, c.stock, c.x + ox, c.y + oy, c.z + oz);
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
        m.build.addAll(takeBuild(spot, owner)); // sa construction part avec lui : l'emplacement redevient vide
        int ox = spot.region.minX(), oy = spot.region.minY(), oz = spot.region.minZ();
        for (Showcase c : spot.showcases) {
            if (c.virtual) {
                m.showcases.add(c);
                continue;
            }
            Showcase r = new Showcase(c.id, c.template, c.price, c.stock, c.x - ox, c.y - oy, c.z - oz);
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
        // de haut en bas : rien ne tombe ni ne se casse en route
        for (int y = y1; y > f; y--) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    org.bukkit.block.Block b = w.getBlockAt(x, y, z);
                    if (b.getType().isAir()) continue;
                    out.add((x - st.region.minX()) + "," + (y - st.region.minY()) + "," + (z - st.region.minZ()) + "|" + b.getBlockData().getAsString());
                    b.setType(org.bukkit.Material.AIR, false);
                }
            }
        }
        java.util.Collections.reverse(out); // gardé de bas en haut pour le remettre dans l'ordre
        return out;
    }

    /** Remet une construction sur un emplacement (de bas en haut, seulement dans l'intérieur et au-dessus du sol). */
    private void putBuild(Stall st, List<String> build) {
        org.bukkit.World w = st.region.bukkitWorld();
        if (w == null || build.isEmpty()) return;
        int m = margin(st), f = floor(st);
        List<String> sorted = new java.util.ArrayList<>(build);
        sorted.sort(java.util.Comparator.comparingInt(s -> Integer.parseInt(s.substring(0, s.indexOf('|')).split(",")[1])));
        for (String s : sorted) {
            try {
                int bar = s.indexOf('|');
                String[] p = s.substring(0, bar).split(",");
                int x = st.region.minX() + Integer.parseInt(p[0]), y = st.region.minY() + Integer.parseInt(p[1]),
                        z = st.region.minZ() + Integer.parseInt(p[2]);
                if (x < st.region.minX() + m || x > st.region.maxX() - m || z < st.region.minZ() + m || z > st.region.maxZ() - m) continue;
                if (y <= f || y > st.region.maxY() + above()) continue;
                org.bukkit.block.Block b = w.getBlockAt(x, y, z);
                if (!b.getType().isAir()) continue;
                b.setBlockData(org.bukkit.Bukkit.createBlockData(s.substring(bar + 1)), false);
            } catch (RuntimeException ignored) {
                // bloc d'une autre version : on passe
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
