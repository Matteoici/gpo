package fr.smpboss;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Ce que le plugin retient d'un joueur. */
public final class PlayerData {

    public final UUID id;
    public final Set<String> relics = new LinkedHashSet<>();
    public final List<String> equipped = new ArrayList<>();
    public final Set<String> titles = new LinkedHashSet<>();
    /** combats de boss gagnés sans légendaire (ou mieux) / sans secret : garantie */
    public int pityLegendary, pitySecret;
    public long kills;
    /** cosmétiques visibles ou non (les bonus restent actifs) */
    public boolean hideCosmetics;
    /** ne plus voir les particules des reliques (les siennes et celles des autres) */
    public boolean hideEffects;
    public ItemStack[] backpack = new ItemStack[27];
    /** butin gagné qui n'a pas pu entrer dans l'inventaire (récupérable dans /reliques) */
    public final List<ItemStack> stash = new ArrayList<>();
    /** armurerie (/reliques) : armes et tenues rangées */
    public final List<ItemStack> armory = new ArrayList<>();

    public PlayerData(UUID id) {
        this.id = id;
    }
}
