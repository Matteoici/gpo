package fr.smpmarket;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Recherche d'objets dans toutes les vitrines.
 * Le serveur ne connaît que les noms anglais des objets : un petit dictionnaire traduit
 * les mots français courants (diamant -> diamond, épée -> sword...).
 */
public final class Search {

    public record Result(Stall stall, Showcase showcase) {
    }

    private static final Map<String, String> FR = new HashMap<>();

    static {
        String[][] words = {
                {"diamant", "diamond"}, {"diamants", "diamond"}, {"fer", "iron"}, {"or", "gold"}, {"dore", "golden"}, {"doree", "golden"}, {"notch", "enchanted"}, {"enchantee", "enchanted"},
                {"emeraude", "emerald"}, {"netherite", "netherite"}, {"cuivre", "copper"}, {"charbon", "coal"},
                {"lapis", "lapis"}, {"quartz", "quartz"}, {"redstone", "redstone"}, {"amethyste", "amethyst"},
                {"lingot", "ingot"}, {"pepite", "nugget"}, {"minerai", "ore"}, {"bloc", "block"}, {"brut", "raw"},
                {"epee", "sword"}, {"pioche", "pickaxe"}, {"hache", "axe"}, {"pelle", "shovel"}, {"houe", "hoe"},
                {"casque", "helmet"}, {"plastron", "chestplate"}, {"jambieres", "leggings"}, {"bottes", "boots"},
                {"arc", "bow"}, {"arbalete", "crossbow"}, {"fleche", "arrow"}, {"fleches", "arrow"}, {"bouclier", "shield"},
                {"trident", "trident"}, {"masse", "mace"}, {"elytre", "elytra"}, {"elytres", "elytra"}, {"fusee", "firework"},
                {"totem", "totem"}, {"livre", "book"}, {"enchante", "enchanted"}, {"potion", "potion"}, {"bouteille", "bottle"},
                {"experience", "experience"}, {"xp", "experience"}, {"perle", "pearl"}, {"oeil", "eye"},
                {"pomme", "apple"}, {"carotte", "carrot"}, {"patate", "potato"}, {"pomme de terre", "potato"}, {"ble", "wheat"},
                {"pain", "bread"}, {"steak", "beef"}, {"boeuf", "beef"}, {"poulet", "chicken"}, {"porc", "porkchop"},
                {"mouton", "mutton"}, {"poisson", "cod"}, {"saumon", "salmon"}, {"oeuf", "egg"}, {"sucre", "sugar"},
                {"gateau", "cake"}, {"cookie", "cookie"}, {"citrouille", "pumpkin"}, {"pasteque", "melon"}, {"baie", "berries"},
                {"canne", "sugar cane"}, {"cactus", "cactus"}, {"bambou", "bamboo"}, {"champignon", "mushroom"}, {"miel", "honey"},
                {"cuir", "leather"}, {"os", "bone"}, {"ficelle", "string"}, {"poudre", "powder"}, {"plume", "feather"},
                {"laine", "wool"}, {"tapis", "carpet"}, {"lit", "bed"}, {"verre", "glass"}, {"vitre", "pane"}, {"sable", "sand"},
                {"gravier", "gravel"}, {"argile", "clay"}, {"terre", "dirt"}, {"herbe", "grass"}, {"pierre", "stone"},
                {"roche", "stone"}, {"pave", "cobblestone"}, {"brique", "brick"}, {"briques", "bricks"}, {"beton", "concrete"},
                {"terre cuite", "terracotta"}, {"obsidienne", "obsidian"}, {"neige", "snow"}, {"glace", "ice"},
                {"buche", "log"}, {"bois", "wood"}, {"planche", "planks"}, {"planches", "planks"}, {"feuille", "leaves"},
                {"feuilles", "leaves"}, {"pousse", "sapling"}, {"chene", "oak"}, {"bouleau", "birch"}, {"sapin", "spruce"},
                {"acacia", "acacia"}, {"jungle", "jungle"}, {"cerisier", "cherry"}, {"fleur", "flower"}, {"graine", "seeds"},
                {"graines", "seeds"}, {"coffre", "chest"}, {"tonneau", "barrel"}, {"four", "furnace"}, {"enclume", "anvil"},
                {"entonnoir", "hopper"}, {"rail", "rail"}, {"torche", "torch"}, {"lanterne", "lantern"}, {"bougie", "candle"},
                {"porte", "door"}, {"trappe", "trapdoor"}, {"barriere", "fence"}, {"escalier", "stairs"}, {"dalle", "slab"},
                {"mur", "wall"}, {"panneau", "sign"}, {"tableau", "painting"}, {"cadre", "frame"}, {"selle", "saddle"},
                {"armure", "armor"}, {"cheval", "horse"}, {"seau", "bucket"}, {"eau", "water"}, {"lave", "lava"},
                {"lait", "milk"}, {"carte", "map"}, {"boussole", "compass"}, {"horloge", "clock"}, {"canne a peche", "fishing rod"},
                {"ciseaux", "shears"}, {"briquet", "flint and steel"}, {"silex", "flint"}, {"fil", "string"},
                {"teinture", "dye"}, {"colorant", "dye"}, {"rouge", "red"}, {"bleu", "blue"}, {"vert", "green"}, {"jaune", "yellow"},
                {"noir", "black"}, {"blanc", "white"}, {"rose", "pink"}, {"violet", "purple"}, {"orange", "orange"},
                {"gris", "gray"}, {"marron", "brown"}, {"cyan", "cyan"}, {"ender", "ender"}, {"blaze", "blaze"},
                {"slime", "slime"}, {"shulker", "shulker"}, {"tete", "head"}, {"crane", "skull"}, {"wither", "wither"},
                {"etoile", "star"}, {"balise", "beacon"}, {"spawner", "spawner"}, {"oeuf d'apparition", "spawn egg"},
                {"modele", "template"}, {"amelioration", "upgrade"}, {"nether", "nether"}, {"end", "end"},
                {"ecaille", "scute"}, {"coquille", "shell"}, {"vent", "wind"}, {"charge", "charge"}, {"tnt", "tnt"},
        };
        for (String[] w : words) FR.put(w[0], w[1]);
    }

    private Search() {
    }

    public static String normalize(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replace('_', ' ').replace('\'', ' ').trim();
    }

    /** Texte dans lequel on cherche : nom anglais du matériau + nom personnalisé. */
    private static String haystack(ItemStack it) {
        StringBuilder b = new StringBuilder(normalize(it.getType().name()));
        ItemMeta m = it.getItemMeta();
        if (m != null && m.hasDisplayName() && m.displayName() != null) {
            b.append(' ').append(normalize(PlainTextComponentSerializer.plainText().serialize(m.displayName())));
        }
        return b.toString();
    }

    /** Chaque mot de la recherche doit être trouvé (en français ou en anglais). */
    public static boolean matches(ItemStack it, String query) {
        return matchesText(haystack(it), query);
    }

    /** hay : texte normalisé de l'objet (ex. "diamond sword Lame du roi"). */
    public static boolean matchesText(String hay, String query) {
        String q = normalize(query);
        if (q.isEmpty()) return false;
        // expressions de plusieurs mots du dictionnaire d'abord ("pomme de terre")
        for (Map.Entry<String, String> e : FR.entrySet()) {
            if (e.getKey().contains(" ") && q.contains(e.getKey())) q = q.replace(e.getKey(), e.getValue());
        }
        for (String word : q.split("\\s+")) {
            if (word.length() < 2 && !word.equals("or")) continue;
            if (List.of("de", "du", "des", "en", "la", "le", "les", "un", "une").contains(word)) continue;
            String singular = word.endsWith("s") && word.length() > 3 ? word.substring(0, word.length() - 1) : word;
            List<String> candidates = new ArrayList<>();
            if (FR.containsKey(word)) candidates.add(FR.get(word));
            if (FR.containsKey(singular)) candidates.add(FR.get(singular));
            if (candidates.isEmpty()) {
                candidates.add(word);
                candidates.add(singular);
            }
            boolean found = false;
            for (String c : candidates) if (hay.contains(c)) found = true;
            if (!found) return false;
        }
        return true;
    }

    /** Toutes les vitrines correspondantes avec du stock, de la moins chère à la plus chère. */
    public static List<Result> find(SmpMarket plugin, String query) {
        List<Result> out = new ArrayList<>();
        for (Stall st : plugin.store().stalls()) {
            if (!st.isRented()) continue;
            for (Showcase c : st.showcases) {
                if (c.stock > 0 && matches(c.template, query)) out.add(new Result(st, c));
            }
        }
        out.sort(Comparator.comparingDouble(r -> r.showcase().price));
        return out;
    }
}
