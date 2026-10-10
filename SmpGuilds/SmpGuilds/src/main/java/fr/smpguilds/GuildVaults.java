package fr.smpguilds;

import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static fr.smpguilds.Messages.guild;

/** Coffre partagé de chaque guilde : un seul inventaire vivant par guilde, partagé entre les membres. */
public final class GuildVaults {

    public static final class Holder implements InventoryHolder {
        final UUID guildId;
        Inventory inventory;

        Holder(UUID guildId) {
            this.guildId = guildId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final SmpGuilds plugin;
    private final Map<UUID, Inventory> open = new HashMap<>();

    GuildVaults(SmpGuilds plugin) {
        this.plugin = plugin;
    }

    public void open(Player p, Guild g) {
        int rows = plugin.guilds().level(g).vaultRows();
        Inventory inv = open.get(g.id);
        if (inv != null && inv.getSize() != rows * 9 && inv.getViewers().isEmpty()) {
            sync(g);
            inv = null;
        }
        if (inv == null) {
            Holder h = new Holder(g.id);
            inv = Bukkit.createInventory(h, rows * 9, plugin.messages().get("gui.vault.title", guild(g)));
            h.inventory = inv;
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, g.vault[i]);
            open.put(g.id, inv);
        }
        p.openInventory(inv);
    }

    /** Recopie le contenu de l'inventaire ouvert dans la guilde (avant sauvegarde). */
    public void sync(Guild g) {
        Inventory inv = open.get(g.id);
        if (inv == null) return;
        for (int i = 0; i < inv.getSize(); i++) g.vault[i] = inv.getItem(i);
    }

    public void onClose(Holder h) {
        Guild g = plugin.guilds().get(h.guildId);
        if (g == null) return;
        sync(g);
        plugin.guilds().markDirty(g);
        Inventory inv = open.get(g.id);
        if (inv != null && inv.getViewers().size() <= 1) open.remove(g.id);
    }

    /** Guilde dissoute : on ferme le coffre et on rend le contenu au chef s'il est connecté. */
    public void forget(Guild g) {
        sync(g); // récupère ce qui est dans le coffre ouvert avant de le fermer
        Inventory inv = open.remove(g.id);
        if (inv != null) {
            for (HumanEntity h : new ArrayList<>(inv.getViewers())) h.closeInventory();
        }
    }

    public void closeAll() {
        for (Map.Entry<UUID, Inventory> e : new HashMap<>(open).entrySet()) {
            Guild g = plugin.guilds().get(e.getKey());
            if (g != null) sync(g);
            for (HumanEntity h : new ArrayList<>(e.getValue().getViewers())) h.closeInventory();
        }
        open.clear();
    }

    /** Items du coffre (pour les rendre à la dissolution). */
    public static ItemStack[] contents(Guild g) {
        return g.vault;
    }
}
