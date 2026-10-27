package fr.smpsalles;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Simple Voice Chat : chaque salle a son groupe ISOLÉ et caché.
 * Dans un groupe isolé, on n'entend que les membres du groupe, et les gens dehors ne l'entendent pas.
 * En sortant, le joueur retrouve le groupe qu'il avait avant (s'il existe encore).
 */
final class SvcLink implements VoiceLink, VoicechatPlugin {

    private final SmpSalles plugin;
    private VoicechatServerApi api;
    /** joueur -> salle où il est (pour la voix) */
    private final Map<UUID, String> inside = new HashMap<>();
    /** joueur -> groupe qu'il avait avant d'entrer */
    private final Map<UUID, UUID> previous = new HashMap<>();

    SvcLink(SmpSalles plugin) {
        this.plugin = plugin;
        BukkitVoicechatService service = plugin.getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service != null) service.registerPlugin(this);
        else plugin.getLogger().warning("Simple Voice Chat trouvé mais son service n'est pas disponible : pas d'insonorisation.");
    }

    @Override
    public String getPluginId() {
        return "smpsalles";
    }

    @Override
    public void initialize(VoicechatApi a) {
        if (a instanceof VoicechatServerApi s) api = s;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, e -> api = e.getVoicechat());
    }

    private Group groupOf(Room room) {
        if (room.groupId != null) {
            Group g = api.getGroup(room.groupId);
            if (g != null) return g;
        }
        Group g = api.groupBuilder()
                .setName("Salle " + room.name)
                .setPassword(UUID.randomUUID().toString())
                .setType(Group.Type.ISOLATED)
                .setHidden(true)
                .setPersistent(true)
                .build();
        room.groupId = g.getId();
        return g;
    }

    @Override
    public boolean update(Player p, Room room) {
        if (api == null) return false;
        VoicechatConnection c = api.getConnectionOf(p.getUniqueId());
        if (c == null) return false;
        UUID id = p.getUniqueId();
        String was = inside.get(id);
        if (room != null) {
            Group g = groupOf(room);
            Group cur = c.getGroup();
            boolean entering = !room.name.equals(was);
            if (entering && was == null) previous.put(id, cur == null ? null : cur.getId());
            if (cur == null || !cur.getId().equals(g.getId())) c.setGroup(g);
            inside.put(id, room.name);
            return entering;
        }
        if (was == null) return false;
        inside.remove(id);
        UUID back = previous.remove(id);
        Group prev = back == null ? null : api.getGroup(back);
        c.setGroup(prev);
        return true;
    }

    @Override
    public void forget(Player p) {
        inside.remove(p.getUniqueId());
        previous.remove(p.getUniqueId());
    }

    @Override
    public void shutdown() {
        if (api == null) return;
        for (UUID id : inside.keySet()) {
            VoicechatConnection c = api.getConnectionOf(id);
            if (c == null) continue;
            UUID back = previous.get(id);
            c.setGroup(back == null ? null : api.getGroup(back));
        }
        inside.clear();
        previous.clear();
    }
}
