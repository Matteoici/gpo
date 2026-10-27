package fr.smpzones;

import org.bukkit.Material;

import java.util.Locale;

/** Les permissions d'une zone. Non réglée = autorisé (sauf si une zone moins prioritaire ou le monde l'interdit). */
enum Flag {
    CASSER("casser", Material.IRON_PICKAXE, "Casser des blocs", true),
    POSER("poser", Material.BRICKS, "Poser des blocs (et cadres, bateaux, porte-armures...)", true),
    INTERAGIR("interagir", Material.OAK_DOOR, "Portes, trappes, boutons, leviers, établis, enclumes...", true),
    COFFRES("coffres", Material.CHEST, "Ouvrir coffres, tonneaux, fours, shulkers, entonnoirs...", true),
    ENTITES("entites", Material.ITEM_FRAME, "Cadres, porte-armures, tableaux, taper les animaux", true),
    SEAUX("seaux", Material.WATER_BUCKET, "Vider / remplir des seaux", true),
    VEHICULES("vehicules", Material.OAK_BOAT, "Casser bateaux et wagonnets", true),
    PVP("pvp", Material.DIAMOND_SWORD, "Les joueurs se tapent entre eux", false),
    DEGATS("degats", Material.TOTEM_OF_UNDYING, "Les joueurs prennent des dégâts (interdit = invincibles)", false),
    CHUTE("chute", Material.FEATHER, "Dégâts de chute", false),
    FAIM("faim", Material.COOKED_BEEF, "La faim baisse", false),
    ATTAQUE_MONSTRES("attaque-monstres", Material.ZOMBIE_HEAD, "Les monstres blessent les joueurs", false),
    MONSTRES("monstres", Material.CREEPER_HEAD, "Apparition naturelle des monstres", false),
    ANIMAUX("animaux", Material.PIG_SPAWN_EGG, "Apparition naturelle des animaux", false),
    MOBS_CASSENT("mobs-cassent", Material.ENDERMAN_SPAWN_EGG, "Endermen, ravageurs, wither... qui abîment les blocs", false),
    EXPLOSIONS("explosions", Material.TNT, "Les explosions cassent les blocs et blessent", false),
    FEU("feu", Material.FLINT_AND_STEEL, "Allumer du feu, le feu se propage et brûle les blocs", false),
    LIQUIDES("liquides", Material.LAVA_BUCKET, "L'eau et la lave coulent dans la zone", false),
    JETER("jeter", Material.DROPPER, "Jeter des objets", false),
    RAMASSER("ramasser", Material.HOPPER, "Ramasser des objets", false),
    PERLES("perles", Material.ENDER_PEARL, "Ender pearls", false),
    CHORUS("chorus", Material.CHORUS_FRUIT, "Téléportation par fruit de chorus", false),
    ELYTRES("elytres", Material.ELYTRA, "Voler en élytres", false),
    PIETINEMENT("pietinement", Material.FARMLAND, "Piétiner les cultures", false),
    FEUILLES("feuilles", Material.OAK_LEAVES, "Les feuilles disparaissent toutes seules", false),
    NEIGE_GLACE("neige-glace", Material.SNOW_BLOCK, "La neige et la glace se forment / fondent", false),
    ENTRER("entrer", Material.IRON_BARS, "Entrer dans la zone", false);

    final String key;
    final Material icon;
    final String description;
    /** les membres de la zone ne sont pas concernés */
    final boolean memberOk;

    Flag(String key, Material icon, String description, boolean memberOk) {
        this.key = key;
        this.icon = icon;
        this.description = description;
        this.memberOk = memberOk;
    }

    static Flag of(String s) {
        if (s == null) return null;
        String k = s.toLowerCase(Locale.ROOT).replace('_', '-');
        for (Flag f : values()) if (f.key.equals(k) || f.name().equalsIgnoreCase(s)) return f;
        return switch (k) {
            case "build", "break" -> CASSER;
            case "place" -> POSER;
            case "interact", "use" -> INTERAGIR;
            case "chest", "chests", "chest-access", "containers" -> COFFRES;
            case "damage", "invincible" -> DEGATS;
            case "fall", "fall-damage" -> CHUTE;
            case "hunger" -> FAIM;
            case "mob-spawning", "mobs" -> MONSTRES;
            case "explosion", "tnt", "creeper" -> EXPLOSIONS;
            case "fire", "fire-spread" -> FEU;
            case "pearl", "enderpearl" -> PERLES;
            case "drop", "item-drop" -> JETER;
            case "pickup", "item-pickup" -> RAMASSER;
            case "entry", "enter" -> ENTRER;
            default -> null;
        };
    }
}
