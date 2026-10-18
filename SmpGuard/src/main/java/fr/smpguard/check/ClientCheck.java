package fr.smpguard.check;

import fr.smpguard.Check;
import fr.smpguard.Profile;
import fr.smpguard.SmpGuard;
import fr.smpguard.Texts;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Mods et clients interdits : la détection la plus fiable de toutes.
 *
 * Les mods Fabric / Forge annoncent au serveur les "canaux" sur lesquels ils écoutent (ex. axiom:hello),
 * et le jeu envoie le nom de son client ("vanilla", "fabric", "lunarclient", parfois le nom d'un client de triche).
 * Axiom, par exemple, a une caméra libre que rien ne trahit en jeu : mais ses canaux "axiom:*" le trahissent dès la connexion.
 *
 * Les règles sont dans config.yml (checks.client.rules) : nom, canaux, marques, action (kick ou alert).
 * Le staff avec la permission smpguard.allow-mods n'est jamais concerné (pour construire avec Axiom, par exemple).
 */
public final class ClientCheck {

    private record Rule(String name, List<Pattern> channels, List<Pattern> brands, boolean kick, String reason) {
    }

    private final SmpGuard plugin;
    /** règles déjà déclenchées pour chaque joueur (une alerte par mod et par connexion) */
    private final Map<UUID, Set<String>> fired = new HashMap<>();
    private List<Rule> rules;
    private Object rulesSource;

    public ClientCheck(SmpGuard plugin) {
        this.plugin = plugin;
    }

    private static Pattern glob(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '*') b.append(".*");
            else b.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(b.toString());
    }

    private List<Rule> rules() {
        Object src = plugin.settings();
        if (rules != null && rulesSource == src) return rules;
        List<Rule> out = new ArrayList<>();
        for (Map<?, ?> m : plugin.settings().raw().getMapList("checks.client.rules")) {
            String name = String.valueOf(m.get("name") == null ? "?" : m.get("name"));
            List<Pattern> ch = new ArrayList<>(), br = new ArrayList<>();
            if (m.get("channels") instanceof List<?> l) for (Object o : l) ch.add(glob(String.valueOf(o)));
            if (m.get("brands") instanceof List<?> l) for (Object o : l) br.add(glob(String.valueOf(o)));
            boolean kick = "kick".equalsIgnoreCase(String.valueOf(m.get("action")));
            String reason = m.get("reason") == null ? "<red>Ce mod n'est pas autorisé sur ce serveur." : String.valueOf(m.get("reason"));
            out.add(new Rule(name, ch, br, kick, reason));
        }
        rules = out;
        rulesSource = src;
        return out;
    }

    /** Un canal annoncé par le client (à la connexion ou plus tard). */
    public void onChannel(Player p, String channel) {
        evaluate(p, channel.toLowerCase(Locale.ROOT), null);
    }

    /** Quelques secondes après la connexion : marque du client et canaux déjà annoncés. */
    public void onJoin(Player p) {
        for (long delay : new long[]{40L, 200L}) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                String brand = p.getClientBrandName();
                if (brand != null) evaluate(p, null, brand.toLowerCase(Locale.ROOT));
                for (String c : p.getListeningPluginChannels()) evaluate(p, c.toLowerCase(Locale.ROOT), null);
                strict(p, delay == 200L);
            }, delay);
        }
    }

    /**
     * Mode strict : seuls les clients autorisés (vanilla, Lunar, Badlion, Feather, LabyMod... voir config) entrent.
     * Fabric / Forge / NeoForge / Quilt = refusés : c'est là que tournent CheatUtils, Meteor, Wurst, les freecams...
     * Un client qui se dit "vanilla" mais écoute des canaux de mods = faux vanilla, refusé aussi.
     */
    private void strict(Player p, boolean last) {
        var cfg = plugin.settings().raw();
        if (!cfg.getBoolean("checks.client.strict.enabled", true)) return;
        if (p.hasPermission("smpguard.allow-mods") || p.hasPermission("smpguard.bypass") || plugin.isBedrock(p)) return;
        String brand = p.getClientBrandName();
        if (brand == null) {
            if (last && cfg.getBoolean("checks.client.strict.kick-unknown-brand", false)) kickStrict(p, "client inconnu");
            return;
        }
        String b = brand.toLowerCase(Locale.ROOT);
        boolean allowed = false;
        for (String a : cfg.getStringList("checks.client.strict.allowed-brands")) if (glob(a).matcher(b).matches()) allowed = true;
        if (cfg.getStringList("checks.client.strict.allowed-brands").isEmpty()) allowed = b.equals("vanilla");
        if (!allowed) {
            kickStrict(p, "client « " + brand + " »");
            return;
        }
        if (b.equals("vanilla")) {
            List<Pattern> ok = new ArrayList<>();
            for (String a : cfg.getStringList("checks.client.strict.allowed-channels")) ok.add(glob(a));
            for (String c : p.getListeningPluginChannels()) {
                String ch = c.toLowerCase(Locale.ROOT);
                boolean fine = ch.startsWith("minecraft:");
                for (Pattern pt : ok) if (pt.matcher(ch).matches()) fine = true;
                if (!fine) {
                    kickStrict(p, "faux client vanilla (canal " + ch + ")");
                    return;
                }
            }
        }
    }

    private void kickStrict(Player p, String how) {
        if (!fired.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>()).add("strict")) return;
        Profile prof = plugin.profile(p);
        String detail = plugin.texts().raw("detail.client").replace("<mod>", "Client moddé (mode strict)").replace("<how>", how)
                .replace("<action>", plugin.texts().raw("detail.client-kicked"));
        if (prof != null) plugin.alerts().flag(p, prof, Check.CLIENT, Math.max(1, plugin.settings().limits(Check.CLIENT).alertVl()), detail);
        String reason = plugin.settings().raw().getString("checks.client.strict.reason",
                "<red><b>Client moddé refusé.</b>\n<gray>Seuls les clients normaux sont acceptés (vanilla, Lunar, Badlion, Feather, LabyMod).\n<gray>Retire Fabric / Forge pour rejoindre.");
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) p.kick(Texts.parse(reason.replace("\\n", "\n")));
        });
    }

    private void evaluate(Player p, String channel, String brand) {
        if (!plugin.settings().limits(Check.CLIENT).enabled()) return;
        if (p.hasPermission("smpguard.allow-mods") || p.hasPermission("smpguard.bypass")) return;
        for (Rule r : rules()) {
            String how = null;
            if (channel != null) for (Pattern pt : r.channels()) if (pt.matcher(channel).matches()) how = "canal " + channel;
            if (brand != null) for (Pattern pt : r.brands()) if (pt.matcher(brand).matches()) how = "client « " + brand + " »";
            if (how == null) continue;
            if (!fired.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>()).add(r.name())) continue;
            Profile prof = plugin.profile(p);
            String detail = plugin.texts().raw("detail.client").replace("<mod>", r.name()).replace("<how>", how)
                    .replace("<action>", plugin.texts().raw(r.kick() ? "detail.client-kicked" : "detail.client-alert"));
            if (prof != null) {
                // preuve directe : l'alerte part tout de suite (pas besoin d'accumuler)
                plugin.alerts().flag(p, prof, Check.CLIENT, Math.max(1, plugin.settings().limits(Check.CLIENT).alertVl()), detail);
            }
            if (r.kick()) {
                String reason = r.reason();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (p.isOnline()) p.kick(Texts.parse(reason.replace("\\n", "\n")));
                });
            }
        }
    }

    public void forget(Player p) {
        fired.remove(p.getUniqueId());
    }
}
