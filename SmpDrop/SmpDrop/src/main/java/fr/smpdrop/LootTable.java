package fr.smpdrop;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Tirage du butin : une rareté est choisie selon son poids, puis un objet de cette rareté. */
public final class LootTable {

    /** Un objet tiré : la ligne de config, les piles créées et la quantité totale. */
    public record Rolled(LootEntry entry, List<ItemStack> items, int amount) {
    }

    public final Map<String, Rarity> rarities = new LinkedHashMap<>();
    public final List<LootEntry> entries = new ArrayList<>();
    public int minRolls = 5, maxRolls = 7;
    public final List<Rarity> guaranteed = new ArrayList<>();

    /** Tire le contenu complet d'une caisse, trié du plus rare au plus commun. */
    public List<Rolled> roll(Random r) {
        Map<LootEntry, Integer> counts = new HashMap<>();
        List<Rolled> out = new ArrayList<>();
        int total = minRolls + (maxRolls > minRolls ? r.nextInt(maxRolls - minRolls + 1) : 0);
        for (Rarity g : guaranteed) {
            LootEntry e = pickEntry(g, counts, r);
            if (e != null) out.add(make(e, counts, r));
        }
        int extra = Math.max(0, total - out.size());
        for (int i = 0; i < extra; i++) {
            Rarity rar = pickRarity(counts, r);
            if (rar == null) break;
            LootEntry e = pickEntry(rar, counts, r);
            if (e != null) out.add(make(e, counts, r));
        }
        out.sort(Comparator.comparingInt((Rolled x) -> x.entry().rarity.rank()).reversed());
        return out;
    }

    private Rolled make(LootEntry e, Map<LootEntry, Integer> counts, Random r) {
        counts.merge(e, 1, Integer::sum);
        List<ItemStack> items = e.create(r);
        int amount = 0;
        for (ItemStack it : items) if (it.getType() == e.material) amount += it.getAmount();
        return new Rolled(e, items, Math.max(1, amount));
    }

    private boolean available(LootEntry e, Map<LootEntry, Integer> counts) {
        return e.max <= 0 || counts.getOrDefault(e, 0) < e.max;
    }

    private Rarity pickRarity(Map<LootEntry, Integer> counts, Random r) {
        double sum = 0;
        List<Rarity> ok = new ArrayList<>();
        for (Rarity rar : rarities.values()) {
            if (rar.weight() <= 0) continue;
            boolean any = false;
            for (LootEntry e : entries) if (e.rarity == rar && available(e, counts)) { any = true; break; }
            if (!any) continue;
            ok.add(rar);
            sum += rar.weight();
        }
        if (ok.isEmpty()) return null;
        double x = r.nextDouble() * sum;
        for (Rarity rar : ok) {
            x -= rar.weight();
            if (x < 0) return rar;
        }
        return ok.get(ok.size() - 1);
    }

    private LootEntry pickEntry(Rarity rar, Map<LootEntry, Integer> counts, Random r) {
        double sum = 0;
        List<LootEntry> ok = new ArrayList<>();
        for (LootEntry e : entries) {
            if (e.rarity != rar || e.weight <= 0 || !available(e, counts)) continue;
            ok.add(e);
            sum += e.weight;
        }
        if (ok.isEmpty()) return null;
        double x = r.nextDouble() * sum;
        for (LootEntry e : ok) {
            x -= e.weight;
            if (x < 0) return e;
        }
        return ok.get(ok.size() - 1);
    }

    // ------------------------------------------------------------ chances affichées

    public double rarityChance(Rarity rar) {
        double sum = 0;
        for (Rarity r : rarities.values()) sum += Math.max(0, r.weight());
        return sum <= 0 ? 0 : rar.weight() / sum;
    }

    /** Probabilité d'un objet à un tirage libre. */
    public double entryChancePerRoll(LootEntry e) {
        double sum = 0;
        for (LootEntry x : entries) if (x.rarity == e.rarity) sum += Math.max(0, x.weight);
        return sum <= 0 ? 0 : rarityChance(e.rarity) * e.weight / sum;
    }

    /** Probabilité approximative d'avoir au moins un exemplaire de cet objet dans une caisse. */
    public double entryChancePerCrate(LootEntry e) {
        double free = (minRolls + maxRolls) / 2.0 - guaranteed.size();
        double miss = Math.pow(1 - entryChancePerRoll(e), Math.max(0, free));
        for (Rarity g : guaranteed) {
            if (g != e.rarity) continue;
            double sum = 0;
            for (LootEntry x : entries) if (x.rarity == g) sum += Math.max(0, x.weight);
            if (sum > 0) miss *= 1 - e.weight / sum;
        }
        return 1 - miss;
    }
}
