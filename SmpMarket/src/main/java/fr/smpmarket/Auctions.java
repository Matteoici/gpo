package fr.smpmarket;

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

import static fr.smpmarket.Texts.ph;

/**
 * Enchères des stands.
 * - Stand libre : enchère ouverte en permanence, le compte à rebours démarre à la première offre.
 * - Stand loué : X heures avant la fin, une enchère s'ouvre pour la location suivante (le locataire peut enchérir aussi).
 * L'argent de l'offre est retenu tout de suite et rendu si quelqu'un surenchérit.
 */
public final class Auctions {

    private final SmpMarket plugin;

    Auctions(SmpMarket plugin) {
        this.plugin = plugin;
    }

    private Settings s() {
        return plugin.settings();
    }

    private Economy eco() {
        return plugin.economy();
    }

    // ================================================================= boucle (chaque seconde)

    void tick() {
        long now = System.currentTimeMillis();
        for (Stall st : plugin.store().stalls()) {
            Auction a = st.auction;
            if (!st.isRented() && a == null) {
                st.auction = new Auction();
                plugin.store().markDirty();
                plugin.visuals().refreshSign(st);
                continue;
            }
            if (st.isRented() && a == null && st.leaseEnd - now <= s().renewWindowMs) {
                Auction r = new Auction();
                r.renewal = true;
                r.endsAt = Math.max(st.leaseEnd, now + 1000);
                st.auction = r;
                plugin.store().markDirty();
                plugin.notify(st.owner, plugin.texts().get("auction.renewal-opened", stallTags(st),
                        ph("time", Settings.time(r.endsAt - now)), ph("price", s().money(st.startPrice))));
                plugin.visuals().refreshSign(st);
                continue;
            }
            if (a != null && a.endsAt > 0 && now >= a.endsAt) resolve(st);
            else if (st.isRented() && st.showcases.isEmpty() && s().inactiveMs > 0 && st.lastChange > 0
                    && now - st.lastChange >= s().inactiveMs && (st.auction == null || !st.auction.hasBid())) {
                // un stand qui expose au moins un objet (même en rupture de stock) n'est jamais repris
                reclaimInactive(st);
            }
        }
    }

    /** Stand acheté mais pas modifié depuis X heures : on rembourse le locataire et le stand repart aux enchères. */
    private void reclaimInactive(Stall st) {
        UUID old = st.owner;
        String oldName = st.ownerName;
        // stands achetés avant cette mise à jour : on ne connaît pas le prix payé, on rend le prix de départ
        double refund = st.paid > 0 ? st.paid : Math.max(0, st.startPrice);
        if (st.auction != null) cancel(st);
        evict(st);
        if (old != null && refund > 0) eco().give(Bukkit.getOfflinePlayer(old), refund);
        st.paid = 0;
        st.auction = new Auction();
        if (old != null) {
            plugin.notify(old, plugin.texts().get("auction.inactive-refund", stallTags(st), ph("amount", s().money(refund)),
                    ph("time", Settings.time(s().inactiveMs))));
        }
        for (Player o : Bukkit.getOnlinePlayers()) {
            plugin.texts().send(o, "auction.inactive-broadcast", stallTags(st), ph("player", oldName == null ? "?" : oldName));
        }
        plugin.store().save();
        plugin.visuals().rebuild(st);
    }

    // ================================================================= offre

    /** Nombre de stands loués + enchères menées par ce joueur, sans compter "except". */
    public int holdings(UUID player, Stall except) {
        int n = 0;
        for (Stall st : plugin.store().stalls()) {
            if (st == except) continue;
            if (st.isOwner(player)) n++;
            else if (st.auction != null && player.equals(st.auction.bidder)) n++;
        }
        return n;
    }

    public boolean bid(Player p, Stall st, double amount) {
        Texts t = plugin.texts();
        if (st.auction == null) {
            if (st.isRented()) {
                t.send(p, "auction.closed", ph("time", Settings.time(st.leaseEnd - s().renewWindowMs - System.currentTimeMillis())));
                return false;
            }
            st.auction = new Auction();
        }
        Auction a = st.auction;
        amount = Math.floor(amount);
        double min = s().minBid(st);
        if (amount < min) {
            t.send(p, "auction.too-low", ph("min", s().money(min)));
            return false;
        }
        if (p.getUniqueId().equals(a.bidder)) {
            // il est déjà en tête : il augmente simplement son offre
        } else if (holdings(p.getUniqueId(), st) >= s().maxPerPlayer) {
            t.send(p, "auction.limit", ph("max", s().maxPerPlayer));
            return false;
        }
        double need = p.getUniqueId().equals(a.bidder) ? amount - a.bid : amount;
        if (!eco().has(p, need)) {
            t.send(p, "auction.no-money", ph("amount", s().money(need)));
            return false;
        }
        if (!eco().take(p, need)) {
            t.send(p, "auction.no-money", ph("amount", s().money(need)));
            return false;
        }
        // rembourser l'ancien meilleur enchérisseur
        if (a.bidder != null && !a.bidder.equals(p.getUniqueId())) {
            OfflinePlayer prev = Bukkit.getOfflinePlayer(a.bidder);
            eco().give(prev, a.bid);
            plugin.notify(a.bidder, t.get("auction.outbid", stallTags(st), ph("player", p.getName()),
                    ph("amount", s().money(amount)), ph("refund", s().money(a.bid))));
        }
        long now = System.currentTimeMillis();
        a.bidder = p.getUniqueId();
        a.bidderName = p.getName();
        a.bid = amount;
        a.bids++;
        if (a.endsAt == 0) {
            a.endsAt = now + s().auctionMs;
            for (Player o : Bukkit.getOnlinePlayers()) {
                t.send(o, "auction.started", stallTags(st), ph("player", p.getName()), ph("amount", s().money(amount)),
                        ph("time", Settings.time(s().auctionMs)));
            }
        } else if (a.endsAt - now < s().antiSnipeMs) {
            a.endsAt = now + s().antiSnipeMs;
            if (a.renewal && st.isRented()) st.leaseEnd = Math.max(st.leaseEnd, a.endsAt);
            t.send(p, "auction.extended", ph("time", Settings.time(s().antiSnipeMs)));
        }
        if (st.isRented() && !st.isOwner(p.getUniqueId()) && a.renewal) {
            plugin.notify(st.owner, t.get("auction.owner-warned", stallTags(st), ph("player", p.getName()),
                    ph("amount", s().money(amount))));
        }
        t.send(p, "auction.bid-ok", stallTags(st), ph("amount", s().money(amount)), ph("time", Settings.time(a.endsAt - now)));
        Fx.sound(p, "entity.experience_orb.pickup", 1.2f);
        plugin.store().save(); // de l'argent a bougé : on sauvegarde tout de suite
        plugin.visuals().refreshSign(st);
        return true;
    }

    // ================================================================= fin d'enchère

    void resolve(Stall st) {
        Auction a = st.auction;
        st.auction = null;
        long now = System.currentTimeMillis();
        Texts t = plugin.texts();
        if (a.hasBid()) {
            if (!st.isRented()) {
                assign(st, a.bidder, a.bidderName, now + s().leaseMs);
                st.paid = a.bid;
                announceWin(st, a);
            } else if (st.isOwner(a.bidder)) {
                st.leaseEnd = Math.max(st.leaseEnd, now) + s().leaseMs;
                st.paid = a.bid;
                plugin.notify(st.owner, t.get("auction.renewed", stallTags(st), ph("amount", s().money(a.bid)),
                        ph("time", Settings.time(st.leaseEnd - now))));
            } else {
                UUID old = st.owner;
                evict(st);
                plugin.notify(old, t.get("auction.lost-stall", stallTags(st), ph("player", a.bidderName)));
                assign(st, a.bidder, a.bidderName, now + s().leaseMs);
                st.paid = a.bid;
                announceWin(st, a);
            }
        } else if (a.renewal && st.isRented()) {
            OfflinePlayer owner = Bukkit.getOfflinePlayer(st.owner);
            if (st.autoRenew && eco().has(owner, st.startPrice) && eco().take(owner, st.startPrice)) {
                st.leaseEnd = Math.max(st.leaseEnd, now) + s().leaseMs;
                st.paid = st.startPrice;
                plugin.notify(st.owner, t.get("auction.auto-renewed", stallTags(st), ph("amount", s().money(st.startPrice)),
                        ph("time", Settings.time(st.leaseEnd - now))));
            } else {
                UUID old = st.owner;
                evict(st);
                plugin.notify(old, t.get("auction.expired", stallTags(st)));
            }
        }
        plugin.store().save();
        plugin.visuals().rebuild(st);
    }

    private void announceWin(Stall st, Auction a) {
        Texts t = plugin.texts();
        for (Player o : Bukkit.getOnlinePlayers()) {
            t.send(o, "auction.won-broadcast", stallTags(st), ph("player", a.bidderName), ph("amount", s().money(a.bid)));
        }
        plugin.notify(a.bidder, t.get("auction.won", stallTags(st), ph("amount", s().money(a.bid)),
                ph("time", Settings.time(s().leaseMs))));
        Player p = Bukkit.getPlayer(a.bidder);
        if (p != null) Fx.sound(p, "ui.toast.challenge_complete", 1f);
    }

    /** Donne le stand à un joueur. */
    public void assign(Stall st, UUID owner, String name, long leaseEnd) {
        st.owner = owner;
        st.ownerName = name;
        st.leaseEnd = leaseEnd;
        st.autoRenew = true;
        st.name = null;
        st.theme = null;
        st.background = null;
        st.symbol = null;
        st.slogan = null;
        st.extraShowcases = 0;
        st.catalogSlots = 0;
        st.privateStall = false;
        st.mannequin = true;
        st.npcSpot = null;
        st.skinValue = null;
        st.skinSignature = null;
        st.trusted.clear();
        st.totalSold = 0;
        st.totalRevenue = 0;
        st.touch();
        plugin.store().markDirty();
    }

    static String key(org.bukkit.block.Block b) {
        return b.getX() + "," + b.getY() + "," + b.getZ();
    }

    /**
     * Coffres, tonneaux, shulkers, fours... posés dans le stand : le contenant ET son contenu partent dans le colis
     * (une shulker garde ses objets à l'intérieur), puis le bloc est retiré. Les vitrines ne sont pas touchées.
     */
    private int collectContainers(Stall st) {
        org.bukkit.World w = st.region == null ? null : Bukkit.getWorld(st.region.world());
        if (w == null || st.owner == null) return 0;
        java.util.List<org.bukkit.block.Block> blocks = new java.util.ArrayList<>();
        if (st.tracksContainers) {
            for (String k : st.containers) {
                String[] p = k.split(",");
                try {
                    blocks.add(w.getBlockAt(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])));
                } catch (RuntimeException ignored) {
                }
            }
        } else {
            // stand loué avant le suivi : on fouille tout le stand
            Cuboid r = st.region;
            for (int x = r.minX(); x <= r.maxX(); x++)
                for (int y = r.minY(); y <= r.maxY() + 2; y++)
                    for (int z = r.minZ(); z <= r.maxZ(); z++) {
                        org.bukkit.block.Block b = w.getBlockAt(x, y, z);
                        if (st.insideCore(b.getLocation())) blocks.add(b); // l'intérieur seulement, pas les murs du marché
                    }
        }
        int items = 0;
        for (org.bukkit.block.Block b : blocks) {
            if (!w.isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) w.getChunkAt(b.getX() >> 4, b.getZ() >> 4);
            boolean showcase = false;
            for (Showcase c : st.showcases) {
                if (c.isAt(b.getX(), b.getY(), b.getZ()) || c.isAt(b.getX(), b.getY() - 1, b.getZ())) showcase = true;
            }
            if (showcase) continue;
            org.bukkit.block.BlockState state = b.getState(false);
            if (!(state instanceof org.bukkit.inventory.BlockInventoryHolder holder)) continue;
            Material type = b.getType();
            if (state instanceof org.bukkit.block.ShulkerBox box) {
                // la shulker entière, avec ce qu'il y a dedans et son nom
                ItemStack item = new ItemStack(type);
                if (item.getItemMeta() instanceof org.bukkit.inventory.meta.BlockStateMeta meta) {
                    meta.setBlockState(box);
                    item.setItemMeta(meta);
                }
                plugin.store().addParcel(st.owner, item);
                items++;
                box.getInventory().clear();
            } else {
                org.bukkit.inventory.Inventory inv = state instanceof org.bukkit.block.Chest chest ? chest.getBlockInventory()
                        : holder.getInventory();
                for (ItemStack it : inv.getContents()) {
                    if (it == null || it.getType().isAir()) continue;
                    plugin.store().addParcel(st.owner, it.clone());
                    items += it.getAmount();
                }
                inv.clear();
                if (type.isItem()) {
                    plugin.store().addParcel(st.owner, new ItemStack(type));
                    items++;
                }
            }
            b.setType(Material.AIR, false);
        }
        st.containers.clear();
        return items;
    }

    /** Libère le stand : le stock des vitrines et les contenants posés partent dans le colis de l'ancien locataire. */
    public void evict(Stall st) {
        if (st.owner != null) {
            int items = collectContainers(st);
            for (Showcase c : st.showcases) {
                if (c.stock > 0) {
                    plugin.store().addParcel(st.owner, c.template, c.stock);
                    items += c.stock;
                }
            }
            if (items > 0) plugin.notify(st.owner, plugin.texts().get("parcel.received", ph("count", items)));
        }
        plugin.visuals().removeShowcases(st);
        st.showcases.clear();
        st.owner = null;
        st.ownerName = null;
        st.leaseEnd = 0;
        st.name = null;
        st.theme = null;
        st.background = null;
        st.symbol = null;
        st.slogan = null;
        st.extraShowcases = 0;
        st.catalogSlots = 0;
        st.privateStall = false;
        st.mannequin = true;
        st.npcSpot = null;
        st.skinValue = null;
        st.skinSignature = null;
        st.trusted.clear();
        st.containers.clear();
        st.tracksContainers = true; // le prochain locataire est suivi dès qu'il pose un contenant
        plugin.store().markDirty();
    }

    /** Annule l'enchère en cours et rembourse (suppression / remise à zéro d'un stand). */
    public void cancel(Stall st) {
        Auction a = st.auction;
        st.auction = null;
        if (a != null && a.bidder != null) {
            eco().give(Bukkit.getOfflinePlayer(a.bidder), a.bid);
            plugin.notify(a.bidder, plugin.texts().get("auction.cancelled", stallTags(st), ph("amount", s().money(a.bid))));
        }
        plugin.store().markDirty();
    }

    public TagResolver stallTags(Stall st) {
        return TagResolver.resolver(ph("stall", st.id), ph("shop", st.displayName()), ph("size", st.region.size()));
    }
}
