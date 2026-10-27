package fr.smpsalles;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EntitySoundPacketEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.LocationalSoundPacketEvent;
import de.maxhenkel.voicechat.api.events.PacketEvent;
import de.maxhenkel.voicechat.api.events.StaticSoundPacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Simple Voice Chat : chaque paquet de voix envoyé à un joueur passe ici. Si celui qui parle et celui qui écoute
 * ne sont pas dans la même salle (ou l'un dedans, l'autre dehors), le paquet est jeté.
 * -> dans la salle on ne s'entend qu'entre gens de la salle ; dehors on ne les entend pas (proximité ET groupes).
 */
final class SvcLink implements VoiceLink, VoicechatPlugin {

    private final SmpSalles plugin;
    private volatile boolean started;
    private volatile String problem;

    SvcLink(SmpSalles plugin) {
        this.plugin = plugin;
        BukkitVoicechatService service = plugin.getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service != null) service.registerPlugin(this);
        else problem = "service Simple Voice Chat introuvable";
    }

    @Override
    public String getPluginId() {
        return "smpsalles";
    }

    @Override
    public void initialize(VoicechatApi api) {
        started = true;
    }

    @Override
    public void registerEvents(EventRegistration r) {
        r.registerEvent(VoicechatServerStartedEvent.class, e -> started = true);
        r.registerEvent(EntitySoundPacketEvent.class, this::filter);
        r.registerEvent(LocationalSoundPacketEvent.class, this::filter);
        r.registerEvent(StaticSoundPacketEvent.class, this::filter);
    }

    private void filter(PacketEvent<?> e) {
        try {
            if (!plugin.voiceIsolation) return;
            VoicechatConnection from = e.getSenderConnection(), to = e.getReceiverConnection();
            if (from == null || to == null) return;
            Map<UUID, String> rooms = plugin.roomOf;
            String a = rooms.get(from.getPlayer().getUuid()), b = rooms.get(to.getPlayer().getUuid());
            if (!Objects.equals(a, b)) e.cancel();
        } catch (RuntimeException ex) {
            problem = ex.toString();
        }
    }

    @Override
    public boolean ready() {
        return started;
    }

    @Override
    public String problem() {
        return problem;
    }

    @Override
    public void shutdown() {
    }
}
