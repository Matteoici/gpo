package fr.smpboss;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Un plan d'arène (sans Bukkit) : blocs relatifs à l'autel, (0,0,0) = l'autel au niveau du sol, nord = z négatif. */
interface ArenaPlan {

    /** Blocs à poser, clé = ArenaDesign.key(x, y, z). */
    Map<Long, String> blocks();

    /** Posés seulement s'il n'y a rien (air / liquide). */
    Set<Long> soft();

    /** Bannières à décorer. */
    List<int[]> banners();

    /** Rayon du sol de combat. */
    int radius();

    /** Rayon de la zone protégée. */
    int protect();
}
