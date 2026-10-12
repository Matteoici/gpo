package fr.smpshop;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.StonecuttingRecipe;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Donne un prix à TOUS les objets du jeu :
 * 1. ceux écrits dans worth.yml gardent leur prix ;
 * 2. un objet fabriqué vaut (somme de ses ingrédients ÷ quantité obtenue) × craft-factor
 *    (moins que ses ingrédients : fabriquer pour revendre ne rapporte jamais plus) ;
 *    un objet cuit vaut son ingrédient cru × smelt-factor ;
 * 3. tout ce qui reste reçoit le prix par défaut, sauf les objets de créatif (œufs, blocs de commande...).
 */
final class AutoPrice {

    private AutoPrice() {
    }

    private record R(Material result, int amount, List<RecipeChoice> inputs, double factor) {
    }

    static void fill(Map<Material, Double> prices, Set<Material> never, double craftFactor, double smeltFactor,
                     double defaultPrice, List<String> neverPatterns) {
        List<R> recipes = new ArrayList<>();
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe r;
            try {
                r = it.next();
            } catch (Throwable t) {
                continue;
            }
            ItemStack res = r.getResult();
            if (res == null || res.getType().isAir()) continue;
            List<RecipeChoice> in = new ArrayList<>();
            double factor = craftFactor;
            if (r instanceof ShapedRecipe sr) {
                Map<Character, RecipeChoice> map = sr.getChoiceMap();
                for (String row : sr.getShape()) {
                    for (char ch : row.toCharArray()) {
                        RecipeChoice c = map.get(ch);
                        if (c != null) in.add(c);
                    }
                }
            } else if (r instanceof ShapelessRecipe sl) {
                in.addAll(sl.getChoiceList());
            } else if (r instanceof CookingRecipe<?> cr) {
                in.add(cr.getInputChoice());
                factor = smeltFactor;
            } else if (r instanceof StonecuttingRecipe st) {
                in.add(st.getInputChoice());
                factor = 1.0;
            } else if (r instanceof SmithingTransformRecipe sm) {
                in.add(sm.getBase());
                in.add(sm.getAddition());
            } else {
                continue;
            }
            in.removeIf(c -> c == null);
            if (!in.isEmpty()) recipes.add(new R(res.getType(), Math.max(1, res.getAmount()), in, factor));
        }

        Map<Material, Double> fixed = new EnumMap<>(Material.class);
        fixed.putAll(prices);
        // plusieurs passes : les objets fabriqués à partir d'objets fabriqués
        for (int pass = 0; pass < 12; pass++) {
            boolean changed = false;
            for (R r : recipes) {
                if (fixed.containsKey(r.result()) || never.contains(r.result())) continue;
                double sum = 0;
                boolean ok = true;
                for (RecipeChoice c : r.inputs()) {
                    double v = cheapest(c, prices);
                    if (v < 0) {
                        ok = false;
                        break;
                    }
                    sum += v;
                }
                if (!ok || sum <= 0) continue;
                double v = Math.floor(sum / r.amount() * r.factor() * 100) / 100.0;
                if (v <= 0) v = 0.01;
                Double old = prices.get(r.result());
                if (old == null || v < old - 0.001) {
                    prices.put(r.result(), v);
                    changed = true;
                }
            }
            if (!changed) break;
        }

        // prix par défaut pour tout le reste
        if (defaultPrice > 0) {
            for (Material m : Material.values()) {
                if (m.isLegacy() || !m.isItem() || m.isAir() || prices.containsKey(m) || never.contains(m)) continue;
                if (creativeOnly(m, neverPatterns)) continue;
                prices.put(m, defaultPrice);
            }
        }
        for (Material m : never) prices.remove(m);
        prices.keySet().removeIf(m -> creativeOnly(m, neverPatterns) && !fixed.containsKey(m));
    }

    /** Prix de l'ingrédient le moins cher parmi les choix possibles (-1 = inconnu). */
    private static double cheapest(RecipeChoice c, Map<Material, Double> prices) {
        double best = -1;
        if (c instanceof RecipeChoice.MaterialChoice mc) {
            for (Material m : mc.getChoices()) {
                Double v = prices.get(m);
                if (v != null && (best < 0 || v < best)) best = v;
            }
        } else if (c instanceof RecipeChoice.ExactChoice ec) {
            for (ItemStack s : ec.getChoices()) {
                Double v = prices.get(s.getType());
                if (v != null && (best < 0 || v * s.getAmount() < best)) best = v * s.getAmount();
            }
        }
        return best;
    }

    private static boolean creativeOnly(Material m, List<String> patterns) {
        String n = m.name();
        if (n.endsWith("_SPAWN_EGG")) return true;
        for (String p : patterns) {
            String k = p.toUpperCase(Locale.ROOT);
            if (k.endsWith("*") ? n.startsWith(k.substring(0, k.length() - 1)) : n.equals(k)) return true;
            if (k.startsWith("*") && n.endsWith(k.substring(1))) return true;
        }
        return false;
    }
}
