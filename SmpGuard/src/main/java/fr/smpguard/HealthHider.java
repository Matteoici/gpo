package fr.smpguard;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache la vie des AUTRES joueurs : le serveur envoie normalement la vie exacte de chaque joueur à tous les clients
 * (c'est ce qu'affichent les mods "health indicator" / les cœurs au-dessus des têtes). Ici, chaque joueur ne reçoit
 * que sa propre vie ; celle des autres est toujours "pleine" (et l'absorption à zéro).
 * Le jeu n'en a pas besoin : les dégâts, l'animation de coup et la mort passent par d'autres paquets.
 * Classe chargée seulement si ProtocolLib est présent.
 */
final class HealthHider {

    /** Joueurs connectés par identifiant d'entité (lu depuis le fil réseau). */
    private static final Map<Integer, Snapshot> PLAYERS = new ConcurrentHashMap<>();

    private record Snapshot(double health, double absorption, float shown) {
    }

    private HealthHider() {
    }

    static void register(SmpGuard plugin) {
        // instantané de la vie de chaque joueur, mis à jour sur le fil principal
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            PLAYERS.clear();
            for (Player p : Bukkit.getOnlinePlayers()) {
                var max = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                float shown = (float) (max == null ? 20 : max.getValue());
                PLAYERS.put(p.getEntityId(), new Snapshot(p.getHealth(), p.getAbsorptionAmount(), shown));
            }
        }, 1L, 1L);

        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST,
                PacketType.Play.Server.ENTITY_METADATA) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (!plugin.settings().hideHealth) return;
                Player viewer = event.getPlayer();
                if (viewer == null || viewer.hasPermission("smpguard.see-health")) return;
                try {
                    PacketContainer packet = event.getPacket();
                    int id = packet.getIntegers().read(0);
                    if (id == viewer.getEntityId()) return; // sa propre vie : intacte
                    Snapshot s = PLAYERS.get(id);
                    if (s == null) return; // pas un joueur
                    List<WrappedDataValue> values = packet.getDataValueCollectionModifier().read(0);
                    if (values == null || values.isEmpty()) return;
                    List<WrappedDataValue> out = new ArrayList<>(values.size());
                    boolean changed = false;
                    for (WrappedDataValue v : values) {
                        Object val = v.getValue();
                        if (val instanceof Float f && v.getIndex() >= 8) {
                            if (f > 0 && Math.abs(f - s.health()) < 0.05 && v.getIndex() <= 12) {
                                out.add(new WrappedDataValue(v.getIndex(), v.getSerializer(), s.shown()));
                                changed = true;
                                continue;
                            }
                            if (f > 0 && Math.abs(f - s.absorption()) < 0.05 && v.getIndex() > 12) {
                                out.add(new WrappedDataValue(v.getIndex(), v.getSerializer(), 0f));
                                changed = true;
                                continue;
                            }
                        }
                        out.add(v);
                    }
                    if (!changed) return;
                    PacketContainer copy = packet.shallowClone();
                    copy.getDataValueCollectionModifier().write(0, out);
                    event.setPacket(copy);
                } catch (Throwable ignored) {
                    // ne jamais casser l'envoi des entités
                }
            }
        });
    }
}
