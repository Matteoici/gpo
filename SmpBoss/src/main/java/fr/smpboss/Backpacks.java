package fr.smpboss;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static fr.smpboss.SmpBoss.parse;

/** Le sac (relique secrète) : 27 cases. À la mort, 70 % des objets tombent au hasard. */
public final class Backpacks {

    public static final class Holder implements InventoryHolder {
        final Player owner;
        Inventory inv;
        boolean done;

        Holder(Player owner) {
            this.owner = owner;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final SmpBoss plugin;

    Backpacks(SmpBoss plugin) {
        this.plugin = plugin;
    }

    public void open(Player p) {
        if (!plugin.relics().hasBackpack(p)) {
            p.sendMessage(parse(plugin.relics().ownsBackpack(p) ? plugin.msg("backpack.not-equipped") : plugin.msg("backpack.unknown")));
            return;
        }
        Holder h = new Holder(p);
        h.inv = Bukkit.createInventory(h, 27, parse(plugin.msg("backpack.title")));
        h.inv.setContents(plugin.store().of(p.getUniqueId()).backpack.clone());
        p.openInventory(h.inv);
    }

    /** Fermeture : on enregistre le contenu. */
    public void close(Holder h) {
        if (h.done) return;
        h.done = true;
        ItemStack[] c = h.inv.getContents();
        plugin.store().of(h.owner.getUniqueId()).backpack = java.util.Arrays.copyOf(c, 27);
        plugin.store().dirty();
    }

    /** Mort : une partie du sac tombe au sol (au hasard). */
    public List<ItemStack> dropOnDeath(Player p) {
        List<ItemStack> dropped = new ArrayList<>();
        if (!plugin.relics().hasBackpack(p)) return dropped;
        // si le sac est ouvert, on enregistre d'abord ce qu'il contient
        if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof Holder h) {
            close(h);
            h.inv.clear(); // la fermeture qui suit la mort ne doit rien réenregistrer (pas de duplication)
        }
        PlayerData d = plugin.store().of(p.getUniqueId());
        List<Integer> filled = new ArrayList<>();
        for (int i = 0; i < 27; i++) if (d.backpack[i] != null && !d.backpack[i].getType().isAir()) filled.add(i);
        Collections.shuffle(filled);
        double pct = plugin.getConfig().getDouble("backpack.drop-percent-on-death", 70) / 100.0;
        int n = (int) Math.round(filled.size() * pct);
        for (int k = 0; k < n; k++) {
            int i = filled.get(k);
            dropped.add(d.backpack[i]);
            d.backpack[i] = null;
        }
        plugin.store().dirty();
        return dropped;
    }

    public void dropAt(Location l, List<ItemStack> items) {
        for (ItemStack it : items) l.getWorld().dropItemNaturally(l, it);
    }
}
