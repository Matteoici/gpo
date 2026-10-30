package fr.smpspawners;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.Locale;
import java.util.Map;

/** Noms français des mobs et des objets les plus courants. */
final class Names {

    private Names() {
    }

    private static final Map<String, String> MOBS = Map.ofEntries(
            Map.entry("ZOMBIE", "Zombie"), Map.entry("SKELETON", "Squelette"), Map.entry("SPIDER", "Araignée"),
            Map.entry("COW", "Vache"), Map.entry("PIG", "Cochon"), Map.entry("CHICKEN", "Poulet"), Map.entry("SHEEP", "Mouton"),
            Map.entry("CREEPER", "Creeper"), Map.entry("CAVE_SPIDER", "Araignée venimeuse"), Map.entry("SLIME", "Slime"),
            Map.entry("DROWNED", "Noyé"), Map.entry("HUSK", "Zombie momifié"), Map.entry("WITCH", "Sorcière"),
            Map.entry("ENDERMAN", "Enderman"), Map.entry("BLAZE", "Blaze"), Map.entry("MAGMA_CUBE", "Cube de magma"),
            Map.entry("GUARDIAN", "Gardien"), Map.entry("ZOMBIFIED_PIGLIN", "Piglin zombifié"), Map.entry("IRON_GOLEM", "Golem de fer"),
            Map.entry("WITHER_SKELETON", "Wither squelette"), Map.entry("PIGLIN_BRUTE", "Piglin barbare"), Map.entry("SHULKER", "Shulker"),
            Map.entry("EVOKER", "Évocateur"), Map.entry("STRAY", "Vagabond"), Map.entry("RABBIT", "Lapin"), Map.entry("SQUID", "Poulpe"),
            Map.entry("GLOW_SQUID", "Poulpe luisant"), Map.entry("PIGLIN", "Piglin"), Map.entry("HOGLIN", "Hoglin"), Map.entry("GHAST", "Ghast"),
            Map.entry("PHANTOM", "Phantom"), Map.entry("VINDICATOR", "Vindicateur"), Map.entry("PILLAGER", "Pillard"),
            Map.entry("SILVERFISH", "Poisson d'argent"), Map.entry("ENDERMITE", "Endermite"), Map.entry("BOGGED", "Embourbé"),
            Map.entry("BREEZE", "Breeze"), Map.entry("MOOSHROOM", "Champimeuh"), Map.entry("GOAT", "Chèvre"), Map.entry("FROG", "Grenouille"));

    private static final Map<String, String> ITEMS = Map.ofEntries(
            Map.entry("ROTTEN_FLESH", "Chair putréfiée"), Map.entry("IRON_INGOT", "Lingot de fer"), Map.entry("CARROT", "Carotte"),
            Map.entry("POTATO", "Pomme de terre"), Map.entry("BONE", "Os"), Map.entry("ARROW", "Flèche"), Map.entry("STRING", "Ficelle"),
            Map.entry("SPIDER_EYE", "Œil d'araignée"), Map.entry("BEEF", "Bœuf cru"), Map.entry("LEATHER", "Cuir"),
            Map.entry("PORKCHOP", "Côtelette de porc crue"), Map.entry("CHICKEN", "Poulet cru"), Map.entry("FEATHER", "Plume"),
            Map.entry("MUTTON", "Mouton cru"), Map.entry("WHITE_WOOL", "Laine blanche"), Map.entry("GUNPOWDER", "Poudre à canon"),
            Map.entry("SLIME_BALL", "Boule de slime"), Map.entry("COPPER_INGOT", "Lingot de cuivre"), Map.entry("GLASS_BOTTLE", "Fiole"),
            Map.entry("GLOWSTONE_DUST", "Poudre lumineuse"), Map.entry("REDSTONE", "Redstone"), Map.entry("SUGAR", "Sucre"),
            Map.entry("STICK", "Bâton"), Map.entry("ENDER_PEARL", "Perle de l'Ender"), Map.entry("BLAZE_ROD", "Bâton de blaze"),
            Map.entry("MAGMA_CREAM", "Crème de magma"), Map.entry("PRISMARINE_SHARD", "Éclat de prismarine"),
            Map.entry("PRISMARINE_CRYSTALS", "Cristaux de prismarine"), Map.entry("COD", "Morue crue"), Map.entry("GOLD_NUGGET", "Pépite d'or"),
            Map.entry("GOLD_INGOT", "Lingot d'or"), Map.entry("POPPY", "Coquelicot"), Map.entry("COAL", "Charbon"),
            Map.entry("WITHER_SKELETON_SKULL", "Crâne de wither squelette"), Map.entry("GOLDEN_AXE", "Hache en or"),
            Map.entry("SHULKER_SHELL", "Carapace de shulker"), Map.entry("TOTEM_OF_UNDYING", "Totem d'immortalité"),
            Map.entry("EMERALD", "Émeraude"));

    static String mob(EntityType t) {
        String n = MOBS.get(t.name());
        return n != null ? n : pretty(t.name());
    }

    static String item(Material m) {
        String n = ITEMS.get(m.name());
        return n != null ? n : pretty(m.name());
    }

    private static String pretty(String s) {
        String l = s.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(l.charAt(0)) + l.substring(1);
    }
}
