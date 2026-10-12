package fr.smpmarket;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** Une vitrine : un objet exposé au-dessus d'un bloc, avec un prix à l'unité et un stock. */
public final class Showcase {

    public final UUID id;
    public ItemStack template;     // l'objet vendu (quantité 1)
    public double price;           // prix à l'unité
    public int stock;
    public final int x, y, z;      // bloc support (l'objet flotte au-dessus)
    public long sold;
    public double revenue;
    /** true = article du catalogue : vendu dans le menu du stand, sans vitrine dans le monde. */
    public boolean virtual;

    /** Article du catalogue (pas de bloc). */
    public static Showcase virtual(UUID id, ItemStack template, double price, int stock) {
        Showcase s = new Showcase(id, template, price, stock, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
        s.virtual = true;
        return s;
    }

    public Showcase(UUID id, ItemStack template, double price, int stock, int x, int y, int z) {
        this.id = id;
        this.template = template;
        this.price = price;
        this.stock = stock;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Location top(World w) {
        return new Location(w, x + 0.5, y + 1, z + 0.5);
    }

    public boolean isAt(int bx, int by, int bz) {
        return !virtual && x == bx && y == by && z == bz;
    }
}
