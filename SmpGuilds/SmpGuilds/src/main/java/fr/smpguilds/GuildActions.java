package fr.smpguilds;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static fr.smpguilds.Messages.guild;
import static fr.smpguilds.Messages.money;
import static fr.smpguilds.Messages.ph;

/**
 * Toutes les actions d'une guilde. Utilisées à la fois par les commandes et par les menus,
 * pour que les deux se comportent exactement pareil (mêmes vérifications, mêmes messages).
 */
public final class GuildActions {

    private final SmpGuilds plugin;

    GuildActions(SmpGuilds plugin) {
        this.plugin = plugin;
    }

    private GuildManager gm() {
        return plugin.guilds();
    }

    private Messages m() {
        return plugin.messages();
    }

    private Settings s() {
        return plugin.settings();
    }

    // ============================================================ vérifications communes

    /** Guilde du joueur, ou message d'erreur. */
    public Guild require(Player p) {
        Guild g = gm().of(p);
        if (g == null) m().send(p, "error.no-guild");
        return g;
    }

    /** Guilde du joueur s'il a la permission, sinon message d'erreur. */
    public Guild require(Player p, GuildPerm perm) {
        Guild g = require(p);
        if (g == null) return null;
        if (!gm().has(g, p.getUniqueId(), perm)) {
            m().send(p, "error.no-permission");
            return null;
        }
        return g;
    }

    private boolean pay(Player p, double cost) {
        if (cost <= 0) return true;
        Economy eco = plugin.economy();
        if (eco == null || !eco.available()) return true; // pas d'économie : gratuit
        if (!eco.has(p, cost)) {
            m().send(p, "error.not-enough-money", ph("amount", money(cost)));
            return false;
        }
        return eco.take(p, cost);
    }

    private boolean payFromBank(Player p, Guild g, double cost) {
        if (cost <= 0) return true;
        if (g.bank < cost) {
            m().send(p, "error.bank-insufficient", ph("amount", money(cost)), ph("bank", money(g.bank)));
            return false;
        }
        g.bank -= cost;
        gm().markDirty(g);
        return true;
    }

    private boolean full(Guild g) {
        return g.members.size() >= gm().level(g).maxMembers();
    }

    private UUID memberByName(Guild g, String name) {
        for (UUID u : g.members.keySet()) if (g.nameOf(u).equalsIgnoreCase(name)) return u;
        return null;
    }

    private void sound(Player p, Sound s) {
        p.playSound(p.getLocation(), s, 0.7f, 1f);
    }

    // ============================================================ création / dissolution

    public void create(Player p, String name, String tag) {
        if (gm().of(p) != null) {
            m().send(p, "error.already-in-guild");
            return;
        }
        if (!s().validName(name)) {
            m().send(p, "error.invalid-name");
            return;
        }
        if (!s().validTag(tag)) {
            m().send(p, "error.invalid-tag");
            return;
        }
        if (gm().byName(name) != null) {
            m().send(p, "error.name-taken");
            return;
        }
        if (gm().byTag(tag) != null) {
            m().send(p, "error.tag-taken");
            return;
        }
        if (!pay(p, s().d("guild.create-cost", 0))) return;
        Guild g = gm().create(p, name, tag.toUpperCase());
        m().send(p, "guild.created", guild(g));
        p.showTitle(Title.title(m().get("guild.created-title", guild(g)), m().get("guild.created-subtitle", guild(g))));
        sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE);
        if (s().b("guild.broadcast-create", true)) {
            Bukkit.broadcast(m().prefix().append(m().get("broadcast.created", guild(g), ph("player", p.getName()))));
        }
    }

    public void disband(Player p, boolean confirmed) {
        Guild g = require(p);
        if (g == null) return;
        if (!g.leader.equals(p.getUniqueId())) {
            m().send(p, "error.leader-only");
            return;
        }
        if (plugin.wars().warOf(g) != null) {
            m().send(p, "error.at-war");
            return;
        }
        if (!confirmed) {
            p.sendMessage(m().prefix().append(m().get("guild.disband-confirm"))
                    .append(Component.space())
                    .append(m().button("button.confirm", "/guild disband confirm")));
            return;
        }
        plugin.vaults().forget(g);
        // le chef récupère la trésorerie et le contenu du coffre
        Economy eco = plugin.economy();
        if (eco != null && eco.available() && g.bank > 0) eco.give(p, g.bank);
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack it : g.vault) if (it != null && !it.getType().isAir()) items.add(it);
        if (!items.isEmpty()) {
            p.getInventory().addItem(items.toArray(new ItemStack[0])).values()
                    .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        }
        g.broadcast(m().prefix().append(m().get("guild.disbanded", guild(g))));
        if (s().b("guild.broadcast-disband", true)) {
            Bukkit.broadcast(m().prefix().append(m().get("broadcast.disbanded", guild(g))));
        }
        gm().disband(g);
    }

    // ============================================================ membres

    public void invite(Player p, String targetName) {
        Guild g = require(p, GuildPerm.INVITE);
        if (g == null) return;
        Player t = Bukkit.getPlayerExact(targetName);
        if (t == null) {
            m().send(p, "error.player-offline");
            return;
        }
        if (gm().of(t) != null) {
            m().send(p, "error.target-in-guild");
            return;
        }
        if (full(g)) {
            m().send(p, "error.guild-full");
            return;
        }
        gm().invite(g, t.getUniqueId());
        m().send(p, "members.invite-sent", ph("player", t.getName()));
        t.sendMessage(m().prefix().append(m().get("members.invite-received", guild(g), ph("player", p.getName())))
                .append(Component.space())
                .append(m().button("button.join", "/guild join " + g.name))
                .append(Component.space())
                .append(m().button("button.info", "/guild info " + g.name)));
        sound(t, Sound.BLOCK_NOTE_BLOCK_CHIME);
    }

    public void join(Player p, String guildName) {
        if (gm().of(p) != null) {
            m().send(p, "error.already-in-guild");
            return;
        }
        Guild g = gm().find(guildName);
        if (g == null) {
            m().send(p, "error.guild-not-found");
            return;
        }
        if (!g.open && !gm().isInvited(g, p.getUniqueId())) {
            p.sendMessage(m().prefix().append(m().get("error.not-invited", guild(g)))
                    .append(Component.space())
                    .append(m().button("button.apply", "/guild apply " + g.name)));
            return;
        }
        if (full(g)) {
            m().send(p, "error.guild-full");
            return;
        }
        gm().addMember(g, p.getUniqueId(), p.getName(), Role.RECRUIT);
        gm().log(g, p.getName() + " a rejoint la guilde");
        g.broadcast(m().prefix().append(m().get("members.joined", ph("player", p.getName()), guild(g))));
        p.showTitle(Title.title(m().get("members.welcome-title", guild(g)), m().get("members.welcome-subtitle", guild(g))));
        sound(p, Sound.ENTITY_PLAYER_LEVELUP);
    }

    public void apply(Player p, String guildName, String message) {
        if (gm().of(p) != null) {
            m().send(p, "error.already-in-guild");
            return;
        }
        Guild g = gm().find(guildName);
        if (g == null) {
            m().send(p, "error.guild-not-found");
            return;
        }
        if (g.open || gm().isInvited(g, p.getUniqueId())) {
            join(p, g.name);
            return;
        }
        if (g.applications.containsKey(p.getUniqueId())) {
            m().send(p, "error.already-applied");
            return;
        }
        String msg = message == null ? "" : message.length() > 100 ? message.substring(0, 100) : message;
        g.applications.put(p.getUniqueId(), new Guild.Application(msg, System.currentTimeMillis()));
        g.names.put(p.getUniqueId(), p.getName());
        gm().markDirty(g);
        m().send(p, "applications.sent", guild(g));
        for (Player o : g.online()) {
            if (gm().has(g, o.getUniqueId(), GuildPerm.APPLICATIONS)) {
                o.sendMessage(m().prefix().append(m().get("applications.received", ph("player", p.getName()), ph("message", msg)))
                        .append(Component.space())
                        .append(m().button("button.view", "/guild applications")));
                sound(o, Sound.BLOCK_NOTE_BLOCK_BELL);
            }
        }
    }

    public void acceptApplication(Player p, UUID target) {
        Guild g = require(p, GuildPerm.APPLICATIONS);
        if (g == null) return;
        Guild.Application app = g.applications.get(target);
        if (app == null) {
            m().send(p, "error.no-application");
            return;
        }
        String name = g.nameOf(target);
        if (gm().of(target) != null) {
            g.applications.remove(target);
            gm().markDirty(g);
            m().send(p, "error.target-in-guild");
            return;
        }
        if (full(g)) {
            m().send(p, "error.guild-full");
            return;
        }
        gm().addMember(g, target, name, Role.RECRUIT);
        gm().log(g, p.getName() + " a accepté la candidature de " + name);
        g.broadcast(m().prefix().append(m().get("members.joined", ph("player", name), guild(g))));
        Player t = Bukkit.getPlayer(target);
        if (t != null) {
            m().send(t, "applications.accepted", guild(g));
            sound(t, Sound.ENTITY_PLAYER_LEVELUP);
        }
    }

    public void denyApplication(Player p, UUID target) {
        Guild g = require(p, GuildPerm.APPLICATIONS);
        if (g == null) return;
        if (g.applications.remove(target) == null) {
            m().send(p, "error.no-application");
            return;
        }
        gm().markDirty(g);
        m().send(p, "applications.denied", ph("player", g.nameOf(target)));
        Player t = Bukkit.getPlayer(target);
        if (t != null) m().send(t, "applications.denied-target", guild(g));
    }

    public UUID applicantByName(Guild g, String name) {
        for (UUID u : g.applications.keySet()) if (g.nameOf(u).equalsIgnoreCase(name)) return u;
        return null;
    }

    public void kick(Player p, String targetName) {
        Guild g = require(p, GuildPerm.KICK);
        if (g == null) return;
        UUID t = memberByName(g, targetName);
        kick(p, g, t);
    }

    public void kick(Player p, Guild g, UUID t) {
        if (t == null || !g.isMember(t)) {
            m().send(p, "error.not-member");
            return;
        }
        if (!gm().has(g, p.getUniqueId(), GuildPerm.KICK) || !g.role(p.getUniqueId()).higherThan(g.role(t))) {
            m().send(p, "error.rank-too-low");
            return;
        }
        String name = g.nameOf(t);
        gm().removeMember(g, t);
        gm().log(g, p.getName() + " a expulsé " + name);
        g.broadcast(m().prefix().append(m().get("members.kicked", ph("player", name), ph("by", p.getName()))));
        Player tp = Bukkit.getPlayer(t);
        if (tp != null) m().send(tp, "members.kicked-target", guild(g));
    }

    public void leave(Player p) {
        Guild g = require(p);
        if (g == null) return;
        if (g.leader.equals(p.getUniqueId())) {
            m().send(p, "error.leader-cannot-leave");
            return;
        }
        gm().removeMember(g, p.getUniqueId());
        gm().log(g, p.getName() + " a quitté la guilde");
        m().send(p, "members.left-self", guild(g));
        g.broadcast(m().prefix().append(m().get("members.left", ph("player", p.getName()))));
    }

    public void promote(Player p, String targetName) {
        Guild g = require(p, GuildPerm.PROMOTE);
        if (g == null) return;
        promote(p, g, memberByName(g, targetName));
    }

    public void promote(Player p, Guild g, UUID t) {
        if (t == null || !g.isMember(t)) {
            m().send(p, "error.not-member");
            return;
        }
        Role mine = g.role(p.getUniqueId());
        Role next = g.role(t).next();
        if (next == Role.LEADER) {
            m().send(p, "error.use-transfer");
            return;
        }
        // on ne peut promouvoir qu'en dessous de son propre rang
        if (!gm().has(g, p.getUniqueId(), GuildPerm.PROMOTE) || !mine.higherThan(next)) {
            m().send(p, "error.rank-too-low");
            return;
        }
        g.members.put(t, next);
        gm().markDirty(g);
        gm().log(g, p.getName() + " a promu " + g.nameOf(t) + " " + next.name());
        g.broadcast(m().prefix().append(m().get("members.promoted", ph("player", g.nameOf(t)), Messages.comp("role", m().get("role." + next.key())))));
    }

    public void demote(Player p, String targetName) {
        Guild g = require(p, GuildPerm.PROMOTE);
        if (g == null) return;
        demote(p, g, memberByName(g, targetName));
    }

    public void demote(Player p, Guild g, UUID t) {
        if (t == null || !g.isMember(t)) {
            m().send(p, "error.not-member");
            return;
        }
        Role cur = g.role(t);
        if (!gm().has(g, p.getUniqueId(), GuildPerm.PROMOTE) || !g.role(p.getUniqueId()).higherThan(cur) || cur == Role.RECRUIT) {
            m().send(p, "error.rank-too-low");
            return;
        }
        Role prev = cur.previous();
        g.members.put(t, prev);
        gm().markDirty(g);
        gm().log(g, p.getName() + " a rétrogradé " + g.nameOf(t) + " " + prev.name());
        g.broadcast(m().prefix().append(m().get("members.demoted", ph("player", g.nameOf(t)), Messages.comp("role", m().get("role." + prev.key())))));
    }

    public void transfer(Player p, String targetName) {
        Guild g = require(p);
        if (g == null) return;
        transfer(p, g, memberByName(g, targetName));
    }

    public void transfer(Player p, Guild g, UUID t) {
        if (!g.leader.equals(p.getUniqueId())) {
            m().send(p, "error.leader-only");
            return;
        }
        if (t == null || !g.isMember(t) || t.equals(p.getUniqueId())) {
            m().send(p, "error.not-member");
            return;
        }
        g.members.put(p.getUniqueId(), Role.OFFICER);
        g.members.put(t, Role.LEADER);
        g.leader = t;
        gm().markDirty(g);
        gm().log(g, p.getName() + " a transmis la direction à " + g.nameOf(t));
        g.broadcast(m().prefix().append(m().get("members.new-leader", ph("player", g.nameOf(t)))));
    }

    // ============================================================ personnalisation

    public void setDescription(Player p, String text) {
        Guild g = require(p, GuildPerm.EDIT);
        if (g == null) return;
        int max = s().i("guild.description-max-length", 80);
        if (text.length() > max) {
            m().send(p, "error.too-long", ph("max", max));
            return;
        }
        g.description = text;
        gm().markDirty(g);
        gm().log(g, p.getName() + " a changé la description");
        m().send(p, "settings.description-set", ph("description", text));
    }

    public void setTag(Player p, String tag) {
        Guild g = require(p, GuildPerm.EDIT);
        if (g == null) return;
        if (!s().validTag(tag)) {
            m().send(p, "error.invalid-tag");
            return;
        }
        Guild other = gm().byTag(tag);
        if (other != null && other != g) {
            m().send(p, "error.tag-taken");
            return;
        }
        g.tag = tag.toUpperCase();
        gm().markDirty(g);
        gm().log(g, p.getName() + " a changé le tag en " + g.tag);
        m().send(p, "settings.tag-set", guild(g));
    }

    public void rename(Player p, String name) {
        Guild g = require(p);
        if (g == null) return;
        if (!g.leader.equals(p.getUniqueId())) {
            m().send(p, "error.leader-only");
            return;
        }
        if (!s().validName(name)) {
            m().send(p, "error.invalid-name");
            return;
        }
        Guild other = gm().byName(name);
        if (other != null && other != g) {
            m().send(p, "error.name-taken");
            return;
        }
        if (!pay(p, s().d("guild.rename-cost", 0))) return;
        String old = g.name;
        gm().rename(g, name);
        gm().log(g, p.getName() + " a renommé la guilde (" + old + " → " + name + ")");
        g.broadcast(m().prefix().append(m().get("settings.renamed", guild(g))));
    }

    public void setColor(Player p, String colorName) {
        Guild g = require(p, GuildPerm.EDIT);
        if (g == null) return;
        NamedTextColor c = NamedTextColor.NAMES.value(colorName.toLowerCase());
        if (c == null) {
            m().send(p, "error.invalid-color", ph("colors", String.join(", ", NamedTextColor.NAMES.keys())));
            return;
        }
        g.color = c;
        gm().markDirty(g);
        m().send(p, "settings.color-set", guild(g));
    }

    public void setBanner(Player p) {
        Guild g = require(p, GuildPerm.EDIT);
        if (g == null) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir() || !hand.getType().name().endsWith("_BANNER")) {
            m().send(p, "error.hold-banner");
            return;
        }
        ItemStack b = hand.clone();
        b.setAmount(1);
        g.banner = b;
        gm().markDirty(g);
        gm().log(g, p.getName() + " a changé la bannière");
        m().send(p, "settings.banner-set");
        sound(p, Sound.ENTITY_VILLAGER_WORK_CARTOGRAPHER);
    }

    public void toggleOpen(Player p) {
        Guild g = require(p, GuildPerm.EDIT);
        if (g == null) return;
        g.open = !g.open;
        gm().markDirty(g);
        m().send(p, g.open ? "settings.opened" : "settings.closed");
    }

    // ============================================================ home

    public void setHome(Player p) {
        Guild g = require(p, GuildPerm.SETHOME);
        if (g == null) return;
        if (s().b("home.must-be-in-claim", true) && gm().at(p.getLocation()) != g) {
            m().send(p, "error.home-outside-claim");
            return;
        }
        g.home = p.getLocation().clone();
        gm().markDirty(g);
        gm().log(g, p.getName() + " a placé le home");
        m().send(p, "home.set");
        sound(p, Sound.BLOCK_BEACON_ACTIVATE);
    }

    public void home(Player p) {
        // permission du serveur (LuckPerms) : un grade peut être privé de téléportation de guilde
        if (!p.hasPermission("smpguilds.home")) {
            m().send(p, "error.no-home-permission");
            return;
        }
        Guild g = require(p, GuildPerm.HOME);
        if (g == null) return;
        if (g.home == null || g.home.getWorld() == null) {
            m().send(p, "error.no-home");
            return;
        }
        plugin.homes().start(p, g.home);
    }

    // ============================================================ terrains

    public boolean claim(Player p, boolean silent) {
        Guild g = require(p, GuildPerm.CLAIM);
        if (g == null) return false;
        Location loc = p.getLocation();
        ChunkPos pos = ChunkPos.of(loc);
        World w = loc.getWorld();
        if (s().list("claims.disabled-worlds").contains(w.getName())) {
            if (!silent) m().send(p, "error.claim-world-disabled");
            return false;
        }
        int radius = s().i("claims.spawn-protection-radius", 0);
        if (radius > 0 && w.getSpawnLocation().distanceSquared(loc) < (double) radius * radius) {
            if (!silent) m().send(p, "error.claim-near-spawn", ph("radius", radius));
            return false;
        }
        Guild owner = gm().at(pos);
        if (owner == g) {
            if (!silent) m().send(p, "error.already-claimed-own");
            return false;
        }
        if (owner != null) {
            if (!silent) m().send(p, "error.already-claimed", guild(owner));
            return false;
        }
        Level lvl = gm().level(g);
        if (g.claims.size() >= lvl.maxClaims()) {
            m().send(p, "error.claim-limit", ph("max", lvl.maxClaims()));
            return false;
        }
        if (s().b("claims.must-connect", true) && !g.claims.isEmpty()) {
            boolean touches = false;
            for (ChunkPos c : g.claims) {
                if (c.adjacent(pos)) {
                    touches = true;
                    break;
                }
            }
            if (!touches) {
                if (!silent) m().send(p, "error.claim-not-connected");
                return false;
            }
        }
        int buffer = s().i("claims.buffer-chunks", 0);
        for (int dx = -buffer; dx <= buffer; dx++) {
            for (int dz = -buffer; dz <= buffer; dz++) {
                Guild near = gm().at(pos.offset(dx, dz));
                if (near != null && near != g) {
                    if (!silent) m().send(p, "error.claim-too-close", guild(near));
                    return false;
                }
            }
        }
        if (!payFromBank(p, g, s().d("claims.cost", 0))) return false;
        gm().claim(g, pos);
        gm().log(g, p.getName() + " a claim le chunk " + pos.x() + ", " + pos.z());
        m().send(p, "claims.claimed", ph("x", pos.x()), ph("z", pos.z()),
                ph("count", g.claims.size()), ph("max", lvl.maxClaims()));
        showBorder(p, pos, Particle.HAPPY_VILLAGER);
        sound(p, Sound.BLOCK_AMETHYST_BLOCK_CHIME);
        return true;
    }

    public void unclaim(Player p) {
        Guild g = require(p, GuildPerm.UNCLAIM);
        if (g == null) return;
        ChunkPos pos = ChunkPos.of(p.getLocation());
        if (gm().at(pos) != g) {
            m().send(p, "error.not-your-claim");
            return;
        }
        gm().unclaim(g, pos);
        gm().log(g, p.getName() + " a libéré le chunk " + pos.x() + ", " + pos.z());
        m().send(p, "claims.unclaimed", ph("x", pos.x()), ph("z", pos.z()));
        showBorder(p, pos, Particle.SMOKE);
    }

    /**
     * Hauteur protégée : /guild hauteur <au-dessus> [en-dessous] depuis la hauteur où on se trouve.
     * Ex. dans une base enterrée : /guild hauteur 20 -> protégé du fond du monde jusqu'à 20 blocs au-dessus de soi ;
     * au-dessus, les autres ne voient plus le terrain et peuvent construire. /guild hauteur tout -> toute la hauteur.
     */
    public void setHeight(Player p, String[] args) {
        Guild g = require(p, GuildPerm.CLAIM);
        if (g == null) return;
        if (args.length < 2) {
            m().send(p, "claims.height-usage", ph("current", heightText(g)));
            return;
        }
        String a = args[1].toLowerCase(java.util.Locale.ROOT);
        if (a.equals("tout") || a.equals("all") || a.equals("reset") || a.equals("max")) {
            g.zoneMinY = null;
            g.zoneMaxY = null;
        } else {
            int above, below = -1;
            try {
                above = Integer.parseInt(a);
                if (args.length >= 3) below = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                m().send(p, "claims.height-usage", ph("current", heightText(g)));
                return;
            }
            int min = s().i("claims.min-height", 5);
            if (above < min || (below >= 0 && below + above < min)) {
                m().send(p, "claims.height-too-small", ph("min", min));
                return;
            }
            int y = p.getLocation().getBlockY();
            g.zoneMaxY = y + above;
            g.zoneMinY = below < 0 ? null : y - below;
        }
        gm().save(g);
        gm().log(g, p.getName() + " a réglé la hauteur des terrains : " + heightText(g));
        m().send(p, "claims.height-set", ph("current", heightText(g)));
    }

    public static String heightText(Guild g) {
        if (g.zoneMinY == null && g.zoneMaxY == null) return "toute la hauteur";
        String lo = g.zoneMinY == null ? "fond du monde" : "Y " + g.zoneMinY;
        String hi = g.zoneMaxY == null ? "ciel" : "Y " + g.zoneMaxY;
        return lo + " → " + hi;
    }

    public void unclaimAll(Player p) {
        Guild g = require(p);
        if (g == null) return;
        if (!g.leader.equals(p.getUniqueId())) {
            m().send(p, "error.leader-only");
            return;
        }
        int n = g.claims.size();
        for (ChunkPos c : new ArrayList<>(g.claims)) gm().unclaim(g, c);
        gm().log(g, p.getName() + " a libéré tous les terrains (" + n + ")");
        m().send(p, "claims.unclaimed-all", ph("count", n));
    }

    /** Montre les bords du chunk avec des particules pendant quelques secondes. */
    public void showBorder(Player p, ChunkPos pos, Particle particle) {
        int bx = pos.x() << 4, bz = pos.z() << 4;
        final int[] runs = {0};
        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            if (!p.isOnline() || runs[0]++ >= 10) {
                task.cancel();
                return;
            }
            double y = p.getLocation().getY() + 1;
            for (int i = 0; i <= 16; i += 2) {
                p.spawnParticle(particle, bx + i, y, bz, 1, 0, 0, 0, 0);
                p.spawnParticle(particle, bx + i, y, bz + 16, 1, 0, 0, 0, 0);
                p.spawnParticle(particle, bx, y, bz + i, 1, 0, 0, 0, 0);
                p.spawnParticle(particle, bx + 16, y, bz + i, 1, 0, 0, 0, 0);
            }
        }, 0L, 10L);
    }

    /** Carte des terrains autour du joueur, dans le chat. */
    public void map(Player p) {
        ChunkPos center = ChunkPos.of(p.getLocation());
        Guild mine = gm().of(p);
        m().send(p, "claims.map-header", ph("x", center.x()), ph("z", center.z()));
        int r = 5;
        for (int dz = -r; dz <= r; dz++) {
            Component line = Component.text("  ");
            for (int dx = -r * 2; dx <= r * 2; dx++) {
                ChunkPos c = center.offset(dx, dz);
                Guild owner = gm().at(c);
                Component cell;
                if (dx == 0 && dz == 0) {
                    cell = Component.text("⬛", NamedTextColor.WHITE);
                } else if (owner == null) {
                    cell = Component.text("▪", NamedTextColor.DARK_GRAY);
                } else if (owner == mine) {
                    cell = Component.text("■", NamedTextColor.GREEN);
                } else if (gm().areAllies(mine, owner)) {
                    cell = Component.text("■", NamedTextColor.AQUA);
                } else if (mine != null && plugin.wars().atWar(mine, owner)) {
                    cell = Component.text("■", NamedTextColor.RED);
                } else {
                    cell = Component.text("■", owner.color);
                }
                if (owner != null) cell = cell.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(owner.tagComponent()
                        .append(Component.space()).append(owner.nameComponent())));
                line = line.append(cell);
            }
            p.sendMessage(line);
        }
        m().send(p, "claims.map-legend");
    }

    // ============================================================ trésorerie

    public void deposit(Player p, double amount) {
        Guild g = require(p, GuildPerm.BANK_DEPOSIT);
        if (g == null) return;
        Economy eco = plugin.economy();
        if (eco == null || !eco.available()) {
            m().send(p, "error.no-economy");
            return;
        }
        if (amount <= 0 || Double.isNaN(amount)) {
            m().send(p, "error.invalid-amount");
            return;
        }
        if (!eco.has(p, amount)) {
            m().send(p, "error.not-enough-money", ph("amount", money(amount)));
            return;
        }
        if (!eco.take(p, amount)) return;
        g.bank += amount;
        g.stats(p.getUniqueId()).deposited += amount;
        gm().log(g, p.getName() + " a déposé " + money(amount));
        m().send(p, "bank.deposited", ph("amount", money(amount)), ph("bank", money(g.bank)));
        sound(p, Sound.BLOCK_CHAIN_PLACE);
    }

    public void withdraw(Player p, double amount) {
        Guild g = require(p, GuildPerm.BANK_WITHDRAW);
        if (g == null) return;
        Economy eco = plugin.economy();
        if (eco == null || !eco.available()) {
            m().send(p, "error.no-economy");
            return;
        }
        if (amount <= 0 || Double.isNaN(amount)) {
            m().send(p, "error.invalid-amount");
            return;
        }
        if (g.bank < amount) {
            m().send(p, "error.bank-insufficient", ph("amount", money(amount)), ph("bank", money(g.bank)));
            return;
        }
        g.bank -= amount;
        eco.give(p, amount);
        gm().log(g, p.getName() + " a retiré " + money(amount));
        m().send(p, "bank.withdrawn", ph("amount", money(amount)), ph("bank", money(g.bank)));
        sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
    }

    public void openVault(Player p) {
        Guild g = require(p, GuildPerm.VAULT);
        if (g == null) return;
        plugin.vaults().open(p, g);
        sound(p, Sound.BLOCK_ENDER_CHEST_OPEN);
    }

    // ============================================================ alliances

    public void ally(Player p, String targetName) {
        Guild g = require(p, GuildPerm.ALLY);
        if (g == null) return;
        Guild t = gm().find(targetName);
        if (t == null) {
            m().send(p, "error.guild-not-found");
            return;
        }
        ally(p, g, t);
    }

    public void ally(Player p, Guild g, Guild t) {
        if (t == g) {
            m().send(p, "error.self");
            return;
        }
        if (g.allies.contains(t.id)) {
            m().send(p, "error.already-allies");
            return;
        }
        if (plugin.wars().atWar(g, t)) {
            m().send(p, "error.at-war");
            return;
        }
        int max = s().i("guild.max-allies", 3);
        if (g.allies.size() >= max || t.allies.size() >= max) {
            m().send(p, "error.ally-limit", ph("max", max));
            return;
        }
        if (gm().hasAllyRequest(t, g)) {
            gm().makeAllies(g, t);
            gm().log(g, "Alliance conclue avec " + t.name);
            gm().log(t, "Alliance conclue avec " + g.name);
            g.broadcast(m().prefix().append(m().get("allies.formed", guild(t))));
            t.broadcast(m().prefix().append(m().get("allies.formed", guild(g))));
            return;
        }
        if (gm().hasAllyRequest(g, t)) {
            m().send(p, "error.ally-already-requested");
            return;
        }
        gm().requestAlly(g, t);
        m().send(p, "allies.request-sent", guild(t));
        for (Player o : t.online()) {
            if (gm().has(t, o.getUniqueId(), GuildPerm.ALLY)) {
                o.sendMessage(m().prefix().append(m().get("allies.request-received", guild(g)))
                        .append(Component.space())
                        .append(m().button("button.accept", "/guild ally " + g.name)));
            }
        }
    }

    public void unally(Player p, String targetName) {
        Guild g = require(p, GuildPerm.ALLY);
        if (g == null) return;
        Guild t = gm().find(targetName);
        if (t == null || !g.allies.contains(t.id)) {
            m().send(p, "error.not-allies");
            return;
        }
        gm().breakAlliance(g, t);
        gm().log(g, "Fin de l'alliance avec " + t.name);
        gm().log(t, "Fin de l'alliance avec " + g.name);
        g.broadcast(m().prefix().append(m().get("allies.broken", guild(t))));
        t.broadcast(m().prefix().append(m().get("allies.broken", guild(g))));
    }

    // ============================================================ infos

    public void info(org.bukkit.command.CommandSender to, Guild g) {
        Level lvl = gm().level(g);
        Level next = s().nextLevel(lvl);
        OfflinePlayer leader = Bukkit.getOfflinePlayer(g.leader);
        for (Component line : m().list("info.lines", guild(g),
                ph("description", g.description.isEmpty() ? "-" : g.description),
                ph("leader", leader.getName() != null ? leader.getName() : g.nameOf(g.leader)),
                ph("level", lvl.level()),
                ph("xp", g.xp),
                ph("next", next == null ? "MAX" : String.valueOf(next.xp())),
                ph("members", g.members.size()),
                ph("max_members", lvl.maxMembers()),
                ph("online", g.online().size()),
                ph("claims", g.claims.size()),
                ph("max_claims", lvl.maxClaims()),
                ph("bank", money(g.bank)),
                ph("kills", g.kills),
                ph("deaths", g.deaths),
                ph("kdr", g.kdr()),
                ph("wars_won", g.warsWon),
                ph("wars_lost", g.warsLost),
                ph("allies", g.allies.isEmpty() ? "-" : String.join(", ", g.allies.stream()
                        .map(gm()::get).filter(x -> x != null).map(x -> x.name).toList())),
                Messages.comp("status", m().get(g.open ? "info.open" : "info.closed")))) {
            to.sendMessage(line);
        }
    }

    // ============================================================ utilitaire

    public static double parseAmount(String s) {
        String v = s.toLowerCase().replace(" ", "").replace(",", ".");
        double mult = 1;
        if (v.endsWith("k")) {
            mult = 1_000;
            v = v.substring(0, v.length() - 1);
        } else if (v.endsWith("m")) {
            mult = 1_000_000;
            v = v.substring(0, v.length() - 1);
        }
        try {
            return Math.floor(Double.parseDouble(v) * mult * 100) / 100;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
