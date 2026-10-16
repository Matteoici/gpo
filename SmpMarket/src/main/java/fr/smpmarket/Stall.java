package fr.smpmarket;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Un stand du marché. */
public final class Stall {

    public final String id;
    public Cuboid region;
    // point d'entrée (téléportation, panneau flottant au-dessus)
    public String world;
    public double ex, ey, ez;
    public float eyaw;

    public double startPrice;
    public int maxShowcases;

    public UUID owner;
    public String ownerName;
    public long leaseEnd;
    public boolean autoRenew = true;
    public String name;                                    // nom de boutique choisi par le locataire
    public String theme;                                   // couleur du nom (customization.themes)
    public String background;                              // fond du panneau et des étiquettes
    public String symbol;                                  // symbole devant le nom
    public String slogan;                                  // petite phrase sous le nom
    public int extraShowcases;                             // vitrines achetées en plus (pour cette location)
    public int catalogSlots;                               // places de catalogue achetées (pour cette location)
    public boolean privateStall;                           // seuls le locataire et ses amis peuvent entrer
    public boolean mannequin = true;                       // afficher son mannequin quand on est absent
    public double[] npcSpot;                               // x, y, z, yaw du mannequin (null = pas placé)
    public String skinValue, skinSignature;                // skin du locataire (relevé quand il est connecté)

    public int showcaseLimit() {
        return maxShowcases + extraShowcases;
    }

    public int physicalCount() {
        int n = 0;
        for (Showcase c : showcases) if (!c.virtual) n++;
        return n;
    }

    public int virtualCount() {
        int n = 0;
        for (Showcase c : showcases) if (c.virtual) n++;
        return n;
    }
    public final Map<UUID, String> trusted = new LinkedHashMap<>();
    public final List<Showcase> showcases = new ArrayList<>();
    public Auction auction;

    /** Contenants posés par le locataire ("x,y,z") : rendus dans son colis s'il perd le stand. */
    public final java.util.Set<String> containers = new java.util.LinkedHashSet<>();
    /** false = stand loué avant ce suivi : on fouille tout l'intérieur du stand à la libération. */
    public boolean tracksContainers;

    public long lastChange;                                // dernière modification par le locataire (ms)
    public double paid;                                    // ce qu'il a payé pour ce stand (remboursé s'il est inactif)
    public long totalSold;
    public double totalRevenue;

    public Stall(String id) {
        this.id = id;
    }

    public boolean isRented() {
        return owner != null;
    }

    public boolean isOwner(UUID id) {
        return owner != null && owner.equals(id);
    }

    public boolean canBuild(UUID id) {
        return isOwner(id) || trusted.containsKey(id);
    }

    /** Intérieur du stand (sans le mur / la bordure) : c'est là que la zone privée commence. */
    public boolean insideCore(Location l) {
        if (l == null || l.getWorld() == null || !l.getWorld().getName().equals(region.world())) return false;
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        int m = region.sizeX() > 3 && region.sizeZ() > 3 ? 1 : 0;
        return x >= region.minX() + m && x <= region.maxX() - m && z >= region.minZ() + m && z <= region.maxZ() - m
                && y >= region.minY() - 1 && y <= region.maxY() + 2;
    }

    public void touch() {
        lastChange = System.currentTimeMillis();
    }

    public Location entrance() {
        World w = Bukkit.getWorld(world);
        if (w == null) return null;
        Location l = new Location(w, ex, ey, ez);
        l.setYaw(eyaw);
        return l;
    }

    public Showcase showcaseAt(int x, int y, int z) {
        for (Showcase s : showcases) if (s.isAt(x, y, z)) return s;
        return null;
    }

    public Showcase showcase(UUID id) {
        for (Showcase s : showcases) if (s.id.equals(id)) return s;
        return null;
    }

    public int itemsForSale() {
        int n = 0;
        for (Showcase s : showcases) if (s.stock > 0) n++;
        return n;
    }

    /** Nom affiché : nom choisi, sinon "Boutique de X", sinon l'identifiant. */
    public String displayName() {
        if (name != null && !name.isBlank()) return name;
        if (ownerName != null) return "Boutique de " + ownerName;
        return "Stand " + id;
    }
}
