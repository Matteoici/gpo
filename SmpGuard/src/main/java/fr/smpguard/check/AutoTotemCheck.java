package fr.smpguard.check;

import fr.smpguard.Check;
import fr.smpguard.Profile;
import fr.smpguard.Settings;
import fr.smpguard.SmpGuard;
import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Auto-totem : un totem est remis dans la seconde main plus vite qu'un humain ne peut réagir.
 *
 * Quand un totem s'active, le client ne l'apprend qu'après la moitié du ping, et sa réaction met encore
 * la moitié du ping pour revenir. Le délai mesuré par le serveur = ping + temps de réaction.
 * On retire donc le ping (en supposant même qu'il était au plus bas à ce moment-là) et on ne compte que
 * les réactions impossibles pour un humain (moins de ~90 ms, alors qu'un bon joueur met 150 ms ou plus,
 * même avec le totem déjà en main et la touche F).
 *
 * Deuxième indice : un clic sur un totem dans l'inventaire alors que le joueur garde les touches de déplacement
 * enfoncées. En jeu normal, ouvrir son inventaire relâche ces touches : le tricheur, lui, ne l'ouvre jamais.
 *
 * Blocage : remettre un totem dans le MÊME tick que l'activation est physiquement impossible (le client ne
 * sait pas encore que son totem a sauté) ; cette action est annulée.
 */
public final class AutoTotemCheck {

    private record Pop(int tick, long time) {
    }

    private final SmpGuard plugin;
    private final Map<UUID, Pop> pops = new HashMap<>();
    private final Map<UUID, Integer> lastInputChange = new HashMap<>();

    public AutoTotemCheck(SmpGuard plugin) {
        this.plugin = plugin;
    }

    public void onPop(Player p) {
        if (!plugin.analysed(p)) return;
        pops.put(p.getUniqueId(), new Pop(plugin.currentTick(), System.currentTimeMillis()));
    }

    public void onInputChange(Player p) {
        lastInputChange.put(p.getUniqueId(), plugin.currentTick());
    }

    public void forget(Player p) {
        pops.remove(p.getUniqueId());
        lastInputChange.remove(p.getUniqueId());
    }

    private static boolean isTotem(ItemStack it) {
        return it != null && it.getType() == Material.TOTEM_OF_UNDYING;
    }

    /**
     * Clic d'inventaire ou touche F. totemMove = l'action déplace un totem.
     * ownInventory = aucun coffre ouvert (inventaire du joueur).
     */
    public void onTotemAction(Player p, Cancellable event, boolean totemMove, boolean ownInventory) {
        if (!totemMove || !plugin.analysed(p)) return;
        Settings s = plugin.settings();
        if (!s.limits(Check.AUTO_TOTEM).enabled()) return;
        Profile prof = plugin.profile(p);
        if (prof == null) return;
        int now = plugin.currentTick();

        // 1. remis dans le même tick que l'activation : impossible
        Pop pop = pops.get(p.getUniqueId());
        if (pop != null && now == pop.tick()) {
            if (s.totemBlockImpossible) event.setCancelled(true);
            plugin.alerts().flag(p, prof, Check.AUTO_TOTEM, 1.5, plugin.texts().raw("detail.auto-totem-instant"));
            return;
        }

        // 2. clic dans l'inventaire en gardant les touches de déplacement enfoncées
        if (ownInventory && !p.isInsideVehicle() && !plugin.isBedrock(p)) {
            Input in = p.getCurrentInput();
            Integer changed = lastInputChange.get(p.getUniqueId());
            boolean moving = in != null && (in.isForward() || in.isBackward() || in.isLeft() || in.isRight());
            if (moving && (changed == null || now - changed > 4)) {
                double w = plugin.lag().weight(prof);
                if (w >= 0.3) {
                    plugin.alerts().flag(p, prof, Check.AUTO_TOTEM, 0.5 * w, plugin.texts().raw("detail.auto-totem-walking"));
                }
            }
        }

        // 3. temps de réaction après l'activation (vérifié au tick suivant : le totem est-il bien en seconde main ?)
        if (pop == null) return;
        int clickTick = now;
        if (clickTick - pop.tick() > 40) {
            pops.remove(p.getUniqueId()); // plus de 2 s : réaction humaine évidente
            return;
        }
        // on regarde au tick suivant si ce clic a bien remis un totem en seconde main
        // (prendre le totem puis le poser = 2 clics : c'est le clic qui le pose qui compte)
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline() || pops.get(p.getUniqueId()) != pop) return;
            if (!isTotem(p.getInventory().getItemInOffHand())) return;
            pops.remove(p.getUniqueId());
            judgeReaction(p, prof, (clickTick - pop.tick()) * 50L);
        });
    }

    private void judgeReaction(Player p, Profile prof, long delayMs) {
        double weight = plugin.lag().weight(prof);
        if (weight < 0.3) return;
        // hypothèse la plus favorable au joueur : ping au plus bas, +50 ms d'arrondi au tick
        double lowestPing = Math.max(0, prof.avgPing() - 2 * prof.jitter());
        double reaction = delayMs + 50 - lowestPing;
        if (reaction >= plugin.settings().totemMinReactionMs) return;
        String detail = plugin.texts().raw("detail.auto-totem")
                .replace("<reaction>", String.valueOf(Math.max(0, Math.round(reaction))))
                .replace("<delay>", String.valueOf(delayMs))
                .replace("<ping>", String.valueOf(prof.avgPing()));
        plugin.alerts().flag(p, prof, Check.AUTO_TOTEM, (reaction < 0 ? 1.5 : 1.0) * weight, detail);
    }

    /** Les activations trop anciennes sont oubliées. */
    public void prune() {
        int now = plugin.currentTick();
        pops.values().removeIf(pop -> now - pop.tick() > 60);
    }

    /** Utilitaire pour le listener : un clic d'inventaire concerne-t-il un totem ? */
    public static boolean involvesTotem(ItemStack current, ItemStack cursor, ItemStack hotbarItem) {
        return isTotem(current) || isTotem(cursor) || isTotem(hotbarItem);
    }

    public static boolean isOwnInventory(InventoryType top) {
        return top == InventoryType.CRAFTING || top == InventoryType.CREATIVE;
    }
}
