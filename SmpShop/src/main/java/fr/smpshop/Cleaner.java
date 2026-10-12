package fr.smpshop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Répare les objets abîmés par l'ancien plugin de vente : il écrivait le prix DANS l'objet
 * (ligne de description + données cachées), ce qui empêchait les objets de s'empiler.
 * On retire seulement les lignes qui ressemblent à un prix (mot-clé + chiffre ou symbole d'argent).
 */
public final class Cleaner {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final SmpShop plugin;
    private volatile List<Pattern> patterns = List.of();
    private volatile List<String> namespaces = List.of();
    public volatile boolean enabled;

    Cleaner(SmpShop plugin) {
        this.plugin = plugin;
    }

    void load() {
        var c = plugin.getConfig();
        enabled = c.getBoolean("cleanup.enabled", true);
        List<Pattern> p = new ArrayList<>();
        for (String s : c.getStringList("cleanup.lore-patterns")) {
            try {
                p.add(Pattern.compile(s));
            } catch (Exception e) {
                plugin.getLogger().warning("cleanup.lore-patterns : expression invalide « " + s + " »");
            }
        }
        patterns = p;
        List<String> ns = new ArrayList<>();
        for (String s : c.getStringList("cleanup.pdc-namespaces")) ns.add(s.toLowerCase(Locale.ROOT));
        namespaces = ns;
    }

    private boolean isPriceLine(String plain) {
        if (plain.isBlank()) return false;
        boolean money = plain.matches(".*[0-9$€].*");
        if (!money) return false;
        for (Pattern p : patterns) if (p.matcher(plain).find()) return true;
        return false;
    }

    /** Nettoie l'objet sur place. true = il a été modifié. */
    public boolean clean(ItemStack it) {
        if (!enabled || it == null || it.getType().isAir() || !it.hasItemMeta()) return false;
        ItemMeta meta = it.getItemMeta();
        boolean changed = false;
        List<Component> lore = meta.lore();
        if (lore != null && !lore.isEmpty()) {
            List<Component> kept = new ArrayList<>();
            boolean removed = false;
            for (Component line : lore) {
                if (isPriceLine(PLAIN.serialize(line))) removed = true;
                else kept.add(line);
            }
            if (removed) {
                // enlève les lignes vides laissées en fin de description
                while (!kept.isEmpty() && PLAIN.serialize(kept.get(kept.size() - 1)).isBlank()) kept.remove(kept.size() - 1);
                meta.lore(kept.isEmpty() ? null : kept);
                changed = true;
            }
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (!namespaces.isEmpty()) {
            for (NamespacedKey k : new ArrayList<>(pdc.getKeys())) {
                if (namespaces.contains(k.getNamespace().toLowerCase(Locale.ROOT))) {
                    pdc.remove(k);
                    changed = true;
                }
            }
        }
        if (changed) it.setItemMeta(meta);
        return changed;
    }

    /** Nettoie tout un inventaire. Renvoie le nombre de piles réparées. */
    public int clean(Inventory inv) {
        if (!enabled || inv == null) return 0;
        int n = 0;
        ItemStack[] content = inv.getContents();
        for (int i = 0; i < content.length; i++) {
            ItemStack it = content[i];
            if (it != null && clean(it)) {
                inv.setItem(i, it);
                n++;
            }
        }
        return n;
    }
}
