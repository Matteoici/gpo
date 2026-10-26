package fr.smpboss;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Accroche (côté client uniquement) les affichages des cosmétiques sur le joueur, comme des passagers :
 * le jeu les fait suivre le joueur parfaitement, sans retard ni effet "flottant".
 * Côté serveur, le joueur n'a aucun passager : les téléportations (même entre mondes) ne sont pas gênées.
 * N'est chargée que si ProtocolLib est installé.
 */
final class Mounts {

    private final ProtocolManager pm = ProtocolLibrary.getProtocolManager();

    void send(Player wearer, List<Integer> ids) {
        List<Entity> real = wearer.getPassengers();
        int[] all = new int[ids.size() + real.size()];
        for (int i = 0; i < ids.size(); i++) all[i] = ids.get(i);
        for (int i = 0; i < real.size(); i++) all[ids.size() + i] = real.get(i).getEntityId();
        PacketContainer pc = pm.createPacket(PacketType.Play.Server.MOUNT);
        pc.getIntegers().write(0, wearer.getEntityId());
        pc.getIntegerArrays().write(0, all);
        for (Player v : wearer.getWorld().getPlayers()) {
            if (!v.equals(wearer) && (!v.canSee(wearer) || v.getLocation().distanceSquared(wearer.getLocation()) > 100 * 100)) continue;
            try {
                pm.sendServerPacket(v, pc);
            } catch (RuntimeException ignored) {
                // joueur en train de se déconnecter
            }
        }
    }
}
