package fr.smpmarket.gui;

import fr.smpmarket.Auction;
import fr.smpmarket.Items;
import fr.smpmarket.MarketCommand;
import fr.smpmarket.Settings;
import fr.smpmarket.SmpMarket;
import fr.smpmarket.Stall;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import static fr.smpmarket.Texts.ph;

/** Enchérir sur un stand. */
public final class AuctionMenu extends Menu {

    private final Stall stall;
    private final Runnable back;

    public AuctionMenu(SmpMarket plugin, Player viewer, Stall stall, Runnable back) {
        super(plugin, viewer, 5, plugin.texts().get("gui.auction.title", ph("stall", stall.id)));
        this.stall = stall;
        this.back = back;
    }

    private void bid(double amount) {
        if (plugin.auctions().bid(viewer, stall, amount)) redraw();
    }

    @Override
    protected void build() {
        long now = System.currentTimeMillis();
        Auction a = stall.auction;
        Settings set = s();
        double min = set.minBid(stall);
        TagResolver[] r = {plugin.auctions().stallTags(stall),
                ph("x", (stall.region.minX() + stall.region.maxX()) / 2), ph("z", (stall.region.minZ() + stall.region.maxZ()) / 2),
                ph("days", Math.round(set.leaseMs / 86_400_000.0)),
                ph("bid", a != null && a.hasBid() ? money(a.bid) : "-"),
                ph("bidder", a != null && a.hasBid() ? a.bidderName : "-"),
                ph("time", a != null && a.endsAt > 0 ? Settings.time(a.endsAt - now) : "-"),
                ph("min", money(min))};
        set(4, item(Material.OAK_HANGING_SIGN, "gui.auction.header", r));

        if (a == null) {
            // stand loué, pas encore dans la période d'enchère
            set(22, item(Material.GRAY_DYE, "gui.auction.rented", ph("owner", stall.ownerName == null ? "-" : stall.ownerName),
                    ph("time", Settings.time(stall.leaseEnd - set.renewWindowMs - now))));
        } else {
            double current = a.hasBid() ? a.bid : stall.startPrice;
            double p10 = Math.max(min, Math.ceil(current * 1.10));
            double p25 = Math.max(min, Math.ceil(current * 1.25));
            button(20, item(Material.GOLD_NUGGET, "gui.auction.min", ph("amount", money(min))), c -> bid(min));
            button(21, item(Material.GOLD_INGOT, "gui.auction.plus10", ph("amount", money(p10))), c -> bid(p10));
            button(22, item(Material.GOLD_BLOCK, "gui.auction.plus25", ph("amount", money(p25))), c -> bid(p25));
            button(23, item(Material.OAK_SIGN, "gui.auction.custom"), c -> plugin.input().ask(viewer, "input.bid", text -> {
                double v = MarketCommand.parseMoney(text);
                if (v <= 0) t().send(viewer, "error.bad-amount", ph("value", text));
                else plugin.auctions().bid(viewer, stall, v);
                open();
            }));
            if (viewer.getUniqueId().equals(a.bidder)) set(31, Items.glow(item(Material.LIME_DYE, "gui.auction.leading")));
        }
        button(24, item(Material.ENDER_PEARL, "gui.auction.tp"), c -> {
            viewer.closeInventory();
            MarketCommand.teleport(plugin, viewer, stall);
        });
        set(40, item(Material.EMERALD, "gui.auction.balance", ph("balance", money(plugin.economy().balance(viewer)))));
        if (back != null) back(36, back);
        close(44);
    }
}
