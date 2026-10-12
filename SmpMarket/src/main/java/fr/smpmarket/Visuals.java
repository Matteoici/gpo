package fr.smpmarket;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static fr.smpmarket.Texts.pc;
import static fr.smpmarket.Texts.ph;

/**
 * Panneaux flottants des stands et objets exposés dans les vitrines.
 * Entités d'affichage non sauvegardées : recréées quand le chunk se charge, rien ne reste si le plugin est retiré.
 */
public final class Visuals {

    /** Ce sur quoi on clique : un stand (showcase = null) ou une vitrine. */
    public record Target(String stall, UUID showcase) {
    }

    private static final class SignParts {
        TextDisplay text;
        Interaction hit;
    }

    private static final class ShowParts {
        ItemDisplay item;
        TextDisplay label;
        Interaction hit;
        float angle;
    }

    private final SmpMarket plugin;
    private final Map<String, SignParts> signs = new HashMap<>();
    private final Map<UUID, ShowParts> shows = new HashMap<>();
    private final NamespacedKey stallKey;
    private final NamespacedKey showKey;

    Visuals(SmpMarket plugin) {
        this.plugin = plugin;
        this.stallKey = new NamespacedKey(plugin, "stall");
        this.showKey = new NamespacedKey(plugin, "showcase");
    }

    /**
     * Ce que représente cette entité. Lu directement sur l'entité (et pas dans une liste en mémoire)
     * pour que le clic marche toujours, même si le serveur a "oublié" l'entité un moment (chunk à moitié chargé).
     */
    public Target target(Entity e) {
        if (e == null) return null;
        var pdc = e.getPersistentDataContainer();
        String stall = pdc.get(stallKey, PersistentDataType.STRING);
        if (stall == null) return null;
        String show = pdc.get(showKey, PersistentDataType.STRING);
        try {
            return new Target(stall, show == null ? null : UUID.fromString(show));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ================================================================= création

    /** Fait apparaître ce qui manque pour ce stand (si les chunks sont chargés). */
    public void ensure(Stall st) {
        World w = st.region.bukkitWorld();
        if (w == null) return;
        Location entrance = st.entrance();
        if (entrance != null) {
            SignParts sp = signs.get(st.id);
            if ((sp == null || !valid(sp.text) || !valid(sp.hit)) && loaded(entrance)) {
                if (sp != null) removeSign(sp);
                signs.put(st.id, spawnSign(st, entrance));
            }
        }
        for (Showcase c : st.showcases) {
            if (c.virtual) continue;
            ShowParts p = shows.get(c.id);
            Location top = c.top(w);
            if ((p == null || !valid(p.item) || !valid(p.label) || !valid(p.hit)) && loaded(top)) {
                if (p != null) removeShow(p);
                shows.put(c.id, spawnShow(st, c, top));
            }
        }
    }

    public void ensureAll() {
        for (Stall st : plugin.store().stalls()) ensure(st);
    }

    /** Un chunk vient de se charger : on recrée les éléments qui s'y trouvent. */
    public void onChunkLoad(String world, int cx, int cz) {
        for (Stall st : plugin.store().stalls()) if (st.region.touchesChunk(world, cx, cz)) ensure(st);
    }

    private static boolean valid(Entity e) {
        return e != null && e.isValid();
    }

    private static boolean loaded(Location l) {
        return l.getWorld() != null && l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4);
    }

    /** Marque une entité comme "cliquer = ouvrir ce stand" (mannequin du locataire). */
    public void tagTarget(Entity e, String stallId) {
        tag(e, new Target(stallId, null));
    }

    private void tag(Entity e, Target t) {
        e.setPersistent(false);
        e.getPersistentDataContainer().set(plugin.key(), PersistentDataType.BYTE, (byte) 1);
        if (t != null) {
            e.getPersistentDataContainer().set(stallKey, PersistentDataType.STRING, t.stall());
            if (t.showcase() != null) e.getPersistentDataContainer().set(showKey, PersistentDataType.STRING, t.showcase().toString());
        }
    }

    private SignParts spawnSign(Stall st, Location entrance) {
        SignParts sp = new SignParts();
        Location at = entrance.clone().add(0, plugin.settings().signHeight, 0);
        World w = at.getWorld();
        sp.text = w.spawn(at, TextDisplay.class, d -> {
            d.text(signText(st));
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(plugin.styles().background(st.background).color());
            d.setShadowed(true);
            d.setViewRange(1.2f);
            d.setLineWidth(220);
            d.setBrightness(new Display.Brightness(15, 15));
            tag(d, null);
        });
        sp.hit = w.spawn(at.clone().add(0, -0.4, 0), Interaction.class, i -> {
            i.setInteractionWidth(1.8f);
            i.setInteractionHeight(1.4f);
            i.setResponsive(true);
            tag(i, new Target(st.id, null));
        });
        return sp;
    }

    private ShowParts spawnShow(Stall st, Showcase c, Location top) {
        ShowParts p = new ShowParts();
        World w = top.getWorld();
        float scale = plugin.settings().itemScale;
        p.item = w.spawn(top.clone().add(0, 0.45, 0), ItemDisplay.class, d -> {
            d.setItemStack(c.template);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale),
                    new Quaternionf()));
            d.setViewRange(0.8f);
            d.setBrightness(new Display.Brightness(15, 15));
            tag(d, null);
        });
        Settings set = plugin.settings();
        // vitrines côte à côte : une étiquette sur deux un peu plus haut, pour qu'elles ne se chevauchent pas
        double lift = set.labelStagger && ((c.x + c.z) & 1) == 1 ? 0.38 : 0;
        float ls = set.labelScale;
        p.label = w.spawn(top.clone().add(0, 0.95 + lift, 0), TextDisplay.class, d -> {
            d.text(showText(st, c));
            d.setBillboard(Display.Billboard.CENTER);
            Color bg = plugin.styles().background(st.background).color();
            d.setBackgroundColor(Color.fromARGB(Math.min(bg.getAlpha(), 170), bg.getRed(), bg.getGreen(), bg.getBlue()));
            d.setShadowed(true);
            d.setViewRange(0.5f);
            d.setLineWidth(set.labelWidth);
            Transformation tr = d.getTransformation();
            d.setTransformation(new Transformation(tr.getTranslation(), tr.getLeftRotation(), new Vector3f(ls, ls, ls),
                    tr.getRightRotation()));
            tag(d, null);
        });
        p.hit = w.spawn(top.clone().add(0, -0.05, 0), Interaction.class, i -> {
            i.setInteractionWidth(0.9f);
            i.setInteractionHeight(1.1f);
            i.setResponsive(true);
            tag(i, new Target(st.id, c.id));
        });
        return p;
    }

    // ================================================================= textes

    private Component signText(Stall st) {
        Texts t = plugin.texts();
        Settings s = plugin.settings();
        Auction a = st.auction;
        long now = System.currentTimeMillis();
        TagResolver base = TagResolver.resolver(plugin.auctions().stallTags(st), ph("owner", st.ownerName == null ? "-" : st.ownerName),
                ph("count", st.itemsForSale()));
        if (!st.isRented()) {
            boolean bid = a != null && a.hasBid();
            boolean running = a != null && a.endsAt > 0;
            TagResolver vals = TagResolver.resolver(ph("bid", s.money(bid ? a.bid : st.startPrice)),
                    ph("bidder", bid ? a.bidderName : "-"), ph("time", running ? Settings.time(a.endsAt - now) : "-"));
            Component bidLine = t.get(bid ? "sign.free-bid" : "sign.free-start", vals);
            Component timeLine = t.get(running ? "sign.free-time" : "sign.free-waiting", vals);
            return t.lines("sign.free", base, pc("bid-line", bidLine), pc("time-line", timeLine));
        }
        Styles styles = plugin.styles();
        String sym = styles.symbol(st.symbol);
        Component name = styles.styled(st.theme, (sym.isEmpty() ? "" : sym + " ") + st.displayName(), true);
        Component slogan = st.slogan == null || st.slogan.isBlank() ? Component.empty()
                : t.get("sign.slogan", ph("slogan", st.slogan));
        Component c = st.slogan == null || st.slogan.isBlank()
                ? t.lines("sign.styled", base, pc("name", name))
                : t.lines("sign.styled-slogan", base, pc("name", name), pc("slogan", slogan));
        if (a != null && a.renewal) {
            c = c.append(Component.newline()).append(t.get("sign.renewal", ph("bid", s.money(a.hasBid() ? a.bid : st.startPrice)),
                    ph("time", Settings.time(a.endsAt - now))));
        }
        return c;
    }

    private Component showText(Stall st, Showcase c) {
        Texts t = plugin.texts();
        Settings set = plugin.settings();
        String price = set.labelShortPrices ? set.moneyShort(c.price) : set.money(c.price);
        TagResolver[] r = {pc("item", Shops.itemName(c.template)), pc("price", plugin.styles().styled(st.theme, price, true)),
                ph("stock", c.stock)};
        return t.lines(c.stock > 0 ? "showcase-label.v2.in-stock" : "showcase-label.v2.out-of-stock", r);
    }

    public void refreshSign(Stall st) {
        SignParts sp = signs.get(st.id);
        if (sp != null && sp.text != null && sp.text.isValid()) sp.text.text(signText(st));
        else ensure(st);
    }

    public void updateShowcase(Stall st, Showcase c) {
        ShowParts p = shows.get(c.id);
        if (p == null || p.label == null || !p.label.isValid()) {
            ensure(st);
            return;
        }
        p.label.text(showText(st, c));
        if (p.item != null && p.item.isValid()) p.item.setItemStack(c.template);
    }

    /** Toutes les secondes : compte à rebours des enchères sur les panneaux. */
    public void tickSigns() {
        for (Stall st : plugin.store().stalls()) {
            if (st.auction != null && st.auction.endsAt > 0) refreshSign(st);
        }
    }

    /** Rotation des objets (le client anime en douceur entre deux positions). */
    public void spin(int ticks) {
        if (!plugin.settings().spin) return;
        float scale = plugin.settings().itemScale;
        for (ShowParts p : shows.values()) {
            if (p.item == null || !p.item.isValid()) continue;
            p.angle = (p.angle + (float) Math.PI / 2) % ((float) Math.PI * 2);
            p.item.setInterpolationDelay(0);
            p.item.setInterpolationDuration(ticks);
            p.item.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotationY(p.angle),
                    new Vector3f(scale, scale, scale), new Quaternionf()));
        }
    }

    // ================================================================= suppression

    /** Tout reconstruire (changement de locataire, de nom...). */
    public void rebuild(Stall st) {
        SignParts sp = signs.remove(st.id);
        if (sp != null) removeSign(sp);
        ensure(st);
    }

    /** Le style du stand a changé : on refait le panneau et les étiquettes. */
    public void restyle(Stall st) {
        SignParts sp = signs.remove(st.id);
        if (sp != null) removeSign(sp);
        removeShowcases(st);
        ensure(st);
    }

    public void removeStall(Stall st) {
        SignParts sp = signs.remove(st.id);
        if (sp != null) removeSign(sp);
        removeShowcases(st);
    }

    public void removeShowcases(Stall st) {
        for (Showcase c : st.showcases) removeShowcase(c);
    }

    public void removeShowcase(Showcase c) {
        ShowParts p = shows.remove(c.id);
        if (p != null) removeShow(p);
    }

    private void removeSign(SignParts sp) {
        kill(sp.text);
        kill(sp.hit);
    }

    private void removeShow(ShowParts p) {
        kill(p.item);
        kill(p.label);
        kill(p.hit);
    }

    private void kill(Entity e) {
        if (e == null) return;
        e.remove();
    }

    public void removeAll() {
        for (SignParts sp : signs.values()) removeSign(sp);
        for (ShowParts p : shows.values()) removeShow(p);
        signs.clear();
        shows.clear();
    }

    /** Toutes les 30 s : supprime les entités du plugin qui ne sont plus suivies (doublons, restes). */
    public void prune() {
        java.util.Set<UUID> known = new java.util.HashSet<>();
        for (SignParts sp : signs.values()) {
            if (sp.text != null) known.add(sp.text.getUniqueId());
            if (sp.hit != null) known.add(sp.hit.getUniqueId());
        }
        for (ShowParts p : shows.values()) {
            if (p.item != null) known.add(p.item.getUniqueId());
            if (p.label != null) known.add(p.label.getUniqueId());
            if (p.hit != null) known.add(p.hit.getUniqueId());
        }
        for (World w : plugin.getServer().getWorlds()) {
            for (Entity e : w.getEntitiesByClasses(TextDisplay.class, ItemDisplay.class, Interaction.class)) {
                if (e.getPersistentDataContainer().has(plugin.key(), PersistentDataType.BYTE) && !known.contains(e.getUniqueId())) {
                    e.remove();
                }
            }
        }
    }
}
