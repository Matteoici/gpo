package fr.smpboss;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Logger;

/**
 * Accroche (côté client uniquement) les affichages des cosmétiques sur le joueur, comme des passagers :
 * le jeu les fait suivre le joueur parfaitement, sans retard ni effet "flottant".
 * Côté serveur, le joueur n'a aucun passager : les téléportations (même entre mondes) ne sont pas gênées.
 *
 * Le paquet est fabriqué directement avec les classes du serveur (aucun plugin requis). Si ça ne marche pas
 * sur cette version, {@link #create} renvoie null et les cosmétiques suivent le joueur à l'ancienne.
 */
final class Mounts {

    private final Constructor<?> bufCtor, packetCtor;
    private final Method unpooled, writeVarInt, writeVarIntArray;
    private final Class<?> packetClass;
    private Method getHandle, send;
    private java.lang.reflect.Field connection;
    private boolean broken;
    private final Logger log;

    private Mounts(Logger log) throws ReflectiveOperationException {
        this.log = log;
        Class<?> fbb = Class.forName("net.minecraft.network.FriendlyByteBuf");
        Class<?> byteBuf = Class.forName("io.netty.buffer.ByteBuf");
        bufCtor = fbb.getConstructor(byteBuf);
        unpooled = Class.forName("io.netty.buffer.Unpooled").getMethod("buffer");
        writeVarInt = fbb.getMethod("writeVarInt", int.class);
        writeVarIntArray = fbb.getMethod("writeVarIntArray", int[].class);
        Class<?> pc = Class.forName("net.minecraft.network.protocol.game.ClientboundSetPassengersPacket");
        packetCtor = pc.getDeclaredConstructor(fbb);
        packetCtor.setAccessible(true);
        packetClass = Class.forName("net.minecraft.network.protocol.Packet");
        // essai à blanc : si la fabrication du paquet échoue ici, on n'utilise pas ce système
        packet(1, new int[]{2, 3});
    }

    static Mounts create(Logger log) {
        try {
            return new Mounts(log);
        } catch (Throwable t) {
            log.warning("Cosmétiques : accroche au joueur indisponible sur cette version (" + t + "), ils suivront le joueur à chaque tick.");
            return null;
        }
    }

    boolean broken() {
        return broken;
    }

    private Object packet(int vehicle, int[] ids) throws ReflectiveOperationException {
        Object buf = bufCtor.newInstance(unpooled.invoke(null));
        writeVarInt.invoke(buf, vehicle);
        writeVarIntArray.invoke(buf, (Object) ids);
        return packetCtor.newInstance(buf);
    }

    private void deliver(Player viewer, Object packet) throws ReflectiveOperationException {
        if (getHandle == null) getHandle = viewer.getClass().getMethod("getHandle");
        Object handle = getHandle.invoke(viewer);
        if (connection == null) connection = handle.getClass().getField("connection");
        Object conn = connection.get(handle);
        if (conn == null) return;
        if (send == null) send = conn.getClass().getMethod("send", packetClass);
        send.invoke(conn, packet);
    }

    /** Dit à tous ceux qui voient le joueur que ces affichages sont posés sur lui. */
    void send(Player wearer, List<Integer> ids) {
        if (broken) return;
        List<Entity> real = wearer.getPassengers();
        int[] all = new int[ids.size() + real.size()];
        for (int i = 0; i < ids.size(); i++) all[i] = ids.get(i);
        for (int i = 0; i < real.size(); i++) all[ids.size() + i] = real.get(i).getEntityId();
        try {
            Object packet = packet(wearer.getEntityId(), all);
            for (Player v : wearer.getWorld().getPlayers()) {
                if (!v.equals(wearer) && (!v.canSee(wearer) || v.getLocation().distanceSquared(wearer.getLocation()) > 100 * 100)) continue;
                deliver(v, packet);
            }
        } catch (Throwable t) {
            broken = true;
            log.warning("Cosmétiques : l'accroche au joueur a échoué (" + t + "), retour au suivi classique.");
            Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("SmpBoss"), () -> {
                var pl = (SmpBoss) Bukkit.getPluginManager().getPlugin("SmpBoss");
                if (pl != null) pl.cosmetics().rebuildAll();
            });
        }
    }
}
