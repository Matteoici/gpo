package fr.smpsalles;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Salle insonorisée (sons Minecraft) : un joueur dans une salle ne reçoit plus les sons venus de l'extérieur
 * (pas, coups, explosions de blocs, portes, mobs, joueurs...). Les sons de la salle passent normalement.
 * Classe chargée seulement si ProtocolLib est présent. Les paquets arrivent sur le fil réseau :
 * on ne lit que des instantanés mis à jour sur le fil principal.
 */
final class SoundWall {

    private SoundWall() {
    }

    /** Une salle, vue depuis le fil réseau. */
    record Snap(String name, String world, int[] box, Set<Integer> entities) {
        boolean contains(double x, double y, double z) {
            int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
            return bx >= box[0] && bx <= box[3] && by >= box[1] && by <= box[4] && bz >= box[2] && bz <= box[5];
        }
    }

    static void register(SmpSalles plugin, Map<UUID, Snap> viewers) {
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGH,
                PacketType.Play.Server.NAMED_SOUND_EFFECT, PacketType.Play.Server.ENTITY_SOUND, PacketType.Play.Server.WORLD_EVENT) {
            @Override
            public void onPacketSending(PacketEvent event) {
                Player p = event.getPlayer();
                if (p == null) return;
                Snap room = viewers.get(p.getUniqueId());
                if (room == null) return; // pas dans une salle : rien ne change
                try {
                    PacketContainer pk = event.getPacket();
                    PacketType t = event.getPacketType();
                    if (t == PacketType.Play.Server.NAMED_SOUND_EFFECT) {
                        // position en 1/8 de bloc
                        double x = pk.getIntegers().read(0) / 8.0, y = pk.getIntegers().read(1) / 8.0, z = pk.getIntegers().read(2) / 8.0;
                        if (!room.contains(x, y, z)) event.setCancelled(true);
                    } else if (t == PacketType.Play.Server.ENTITY_SOUND) {
                        int id = pk.getIntegers().read(0);
                        if (id != p.getEntityId() && !room.entities().contains(id)) event.setCancelled(true);
                    } else if (t == PacketType.Play.Server.WORLD_EVENT) {
                        BlockPosition b = pk.getBlockPositionModifier().read(0);
                        if (b != null && !room.contains(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5)) event.setCancelled(true);
                    }
                } catch (RuntimeException ignored) {
                    // paquet d'une autre forme selon la version : on le laisse passer
                }
            }
        });
    }
}
