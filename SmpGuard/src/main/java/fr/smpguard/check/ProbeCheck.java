package fr.smpguard.check;

import fr.smpguard.Check;
import fr.smpguard.Profile;
import fr.smpguard.SmpGuard;
import fr.smpguard.Texts;
import io.papermc.paper.event.packet.UncheckedSignChangeEvent;
import io.papermc.paper.math.Position;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.sign.Side;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sonde des mods côté client (CheatUtils, etc.).
 *
 * Le serveur ouvre un instant (2 ticks) l'éditeur d'un panneau INVISIBLE qui contient des "clés de traduction"
 * propres à certains mods (ex. key.category.cheatutils.common). Puis il le referme.
 * - Client normal : il renvoie la clé telle quelle (elle n'existe pas chez lui) -> rien.
 * - Client avec le mod : la clé est traduite ("CheatUtils") dans ce qu'il renvoie -> mod prouvé.
 * - CheatUtils avec sa protection "Privacy" (activée par défaut) : il se DÉCONNECTE immédiatement en voyant
 *   la sonde. Une déconnexion pile à ce moment = signal ; deux fois (sur deux connexions) = alerte.
 */
public final class ProbeCheck {

    private record Key(String key, String mod) {
    }

    private static final class Pending {
        final Location loc;
        final List<Key> keys;
        final long openedAt;
        boolean answered;

        Pending(Location loc, List<Key> keys, long openedAt) {
            this.loc = loc;
            this.keys = keys;
            this.openedAt = openedAt;
        }
    }

    private final SmpGuard plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final File file;
    private final YamlConfiguration data;

    public ProbeCheck(SmpGuard plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "sondes.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    private boolean enabled() {
        return plugin.settings().raw().getBoolean("checks.client.probe.enabled", true)
                && plugin.settings().limits(Check.CLIENT).enabled();
    }

    private List<Key> keys() {
        List<Key> out = new ArrayList<>();
        for (Map<?, ?> m : plugin.settings().raw().getMapList("checks.client.probe.keys")) {
            Object k = m.get("key"), mod = m.get("mod");
            if (k != null) out.add(new Key(k.toString(), mod == null ? k.toString() : mod.toString()));
        }
        if (out.isEmpty()) out.add(new Key("key.category.cheatutils.common", "CheatUtils"));
        return out;
    }

    /** À la connexion : on sonde une fois le joueur installé (pas pendant le chargement). */
    public void onJoin(Player p) {
        if (!enabled()) return;
        long delay = Math.max(5, plugin.settings().raw().getLong("checks.client.probe.delay-seconds", 25)) * 20L;
        Bukkit.getScheduler().runTaskLater(plugin, () -> probe(p, false), delay);
    }

    /** Lance la sonde (aussi utilisable par le staff). Retourne false si impossible maintenant. */
    public boolean probe(Player p, boolean manual) {
        if (!p.isOnline() || pending.containsKey(p.getUniqueId())) return false;
        if (!manual && (p.hasPermission("smpguard.allow-mods") || p.hasPermission("smpguard.bypass"))) return false;
        // pas pendant un menu ouvert (on fermerait son menu)
        if (p.getOpenInventory().getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING) {
            if (!manual) Bukkit.getScheduler().runTaskLater(plugin, () -> probe(p, false), 200L);
            return false;
        }
        List<Key> all = keys();
        List<Key> batch = all.subList(0, Math.min(4, all.size()));
        Block b = p.getLocation().getBlock().getRelative(0, -2, 0);
        if (b.getY() < p.getWorld().getMinHeight()) b = p.getLocation().getBlock().getRelative(0, 3, 0);
        Location loc = b.getLocation();
        List<Component> lines = new ArrayList<>();
        for (int i = 0; i < 4; i++) lines.add(i < batch.size() ? Component.translatable(batch.get(i).key()) : Component.empty());
        p.sendBlockChange(loc, Material.OAK_SIGN.createBlockData());
        p.sendSignChange(loc, lines);
        Pending pd = new Pending(loc, batch, System.currentTimeMillis());
        pending.put(p.getUniqueId(), pd);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            p.openVirtualSign(Position.block(loc), Side.FRONT);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) p.closeInventory(); // referme l'éditeur : le client renvoie ce qu'il "lit"
            }, 2L);
        }, 1L);
        // fin de la sonde : on remet le vrai bloc
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Pending x = pending.get(p.getUniqueId());
            if (x == pd) pending.remove(p.getUniqueId());
            if (p.isOnline()) p.sendBlockChange(loc, loc.getBlock().getBlockData());
        }, 60L);
        return true;
    }

    /** Ce que le client renvoie en fermant l'éditeur. */
    public void onSign(UncheckedSignChangeEvent e) {
        Player p = e.getPlayer();
        Pending pd = pending.get(p.getUniqueId());
        if (pd == null) return;
        var pos = e.getEditedBlockPosition();
        if (pos.blockX() != pd.loc.getBlockX() || pos.blockY() != pd.loc.getBlockY() || pos.blockZ() != pd.loc.getBlockZ()) return;
        e.setCancelled(true);
        pd.answered = true;
        List<Component> lines = e.lines();
        for (int i = 0; i < pd.keys.size() && i < lines.size(); i++) {
            String got = PlainTextComponentSerializer.plainText().serialize(lines.get(i)).trim();
            Key k = pd.keys.get(i);
            if (!got.isEmpty() && !got.equals(k.key())) {
                // la clé a été traduite : le mod est installé (preuve directe)
                detected(p, k.mod(), "traduction « " + got + " » renvoyée par le client");
            }
        }
    }

    /** Déconnexion : pile pendant la sonde, sans réponse = protection d'un client de triche. */
    public void onQuit(Player p) {
        Pending pd = pending.remove(p.getUniqueId());
        if (pd == null || pd.answered) return;
        long since = System.currentTimeMillis() - pd.openedAt;
        if (since > 2500) return;
        String id = p.getUniqueId().toString();
        long now = System.currentTimeMillis();
        int count = data.getInt(id + ".count", 0);
        long last = data.getLong(id + ".last", 0);
        if (now - last > 7L * 24 * 3600 * 1000) count = 0;
        count++;
        data.set(id + ".count", count);
        data.set(id + ".last", now);
        data.set(id + ".name", p.getName());
        try {
            data.save(file);
        } catch (IOException ignored) {
        }
        Profile prof = plugin.profile(p);
        if (prof == null) return;
        String detail = plugin.texts().raw("detail.probe-disconnect").replace("<ms>", String.valueOf(since))
                .replace("<count>", String.valueOf(count));
        double alertVl = plugin.settings().limits(Check.CLIENT).alertVl();
        // une fois : à surveiller ; deux fois (deux connexions différentes) : alerte
        plugin.alerts().flag(p, prof, Check.CLIENT, count >= 2 ? Math.max(1, alertVl) : alertVl * 0.5, detail);
    }

    private void detected(Player p, String mod, String how) {
        Profile prof = plugin.profile(p);
        boolean kick = "kick".equalsIgnoreCase(plugin.settings().raw().getString("checks.client.probe.action", "kick"));
        String detail = plugin.texts().raw("detail.client").replace("<mod>", mod).replace("<how>", how)
                .replace("<action>", plugin.texts().raw(kick ? "detail.client-kicked" : "detail.client-alert"));
        if (prof != null) plugin.alerts().flag(p, prof, Check.CLIENT, Math.max(1, plugin.settings().limits(Check.CLIENT).alertVl()), detail);
        if (kick) {
            String reason = plugin.settings().raw().getString("checks.client.probe.reason",
                    "<red><b>Client de triche détecté.</b>\n<gray>Rejoins avec un client normal.");
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) p.kick(Texts.parse(reason.replace("\\n", "\n")));
            });
        }
    }
}
