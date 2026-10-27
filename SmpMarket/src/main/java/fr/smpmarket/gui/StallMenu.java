package fr.smpmarket.gui;

import fr.smpmarket.Auction;
import fr.smpmarket.Items;
import fr.smpmarket.MarketCommand;
import fr.smpmarket.Settings;
import fr.smpmarket.Showcase;
import fr.smpmarket.SmpMarket;
import fr.smpmarket.Stall;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import static fr.smpmarket.Texts.pc;
import static fr.smpmarket.Texts.ph;

/** Gestion de sa boutique (pour le locataire). */
public final class StallMenu extends Menu {

    private final Stall stall;
    private final int page;

    public StallMenu(SmpMarket plugin, Player viewer, Stall stall) {
        this(plugin, viewer, stall, 0);
    }

    public StallMenu(SmpMarket plugin, Player viewer, Stall stall, int page) {
        super(plugin, viewer, 6, plugin.texts().get("gui.stall.title"));
        this.stall = stall;
        this.page = page;
    }

    @Override
    protected void build() {
        if (!stall.isOwner(viewer.getUniqueId())) {
            viewer.closeInventory();
            return;
        }
        long now = System.currentTimeMillis();
        TagResolver tags = plugin.auctions().stallTags(stall);
        set(4, Items.glow(item(Material.EMERALD, stall.isPacked() ? "claim.v2.header-packed" : "claim.v2.header-open", tags,
                ph("distance", (int) plugin.settings().leaveDistance),
                ph("sold", stall.totalSold), ph("revenue", money(stall.totalRevenue)),
                ph("used", stall.physicalCount()), ph("max", stall.showcaseLimit()),
                ph("catalog", stall.virtualCount()), ph("catalog-max", stall.catalogSlots))));

        int per = 21;
        int pages = Math.max(1, (stall.showcases.size() + per - 1) / per);
        int pg = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < per; i++) {
            int idx = pg * per + i;
            if (idx >= stall.showcases.size()) break;
            Showcase c = stall.showcases.get(idx);
            button(GRID[i], CatalogMenu.display(this, c, c.virtual ? "gui.stall.catalog-lore" : "gui.stall.showcase-lore",
                    ph("price", money(c.price)), ph("stock", c.stock), ph("sold", c.sold), ph("revenue", money(c.revenue))),
                    click -> new ShowcaseMenu(plugin, viewer, stall, c).open());
        }
        if (pg > 0) button(36, item(Material.SPECTRAL_ARROW, "gui.common.previous", ph("page", pg), ph("pages", pages)),
                c -> new StallMenu(plugin, viewer, stall, pg - 1).open());
        if (pg < pages - 1) button(44, item(Material.SPECTRAL_ARROW, "gui.common.next", ph("page", pg + 2), ph("pages", pages)),
                c -> new StallMenu(plugin, viewer, stall, pg + 1).open());

        button(46, item(Material.NAME_TAG, "gui.stall.rename", tags), c -> plugin.input().ask(viewer, "input.name", name -> {
            MarketCommand.rename(plugin, viewer, stall, name);
            open();
        }));
        button(47, item(Material.PLAYER_HEAD, "gui.stall.trusted", ph("count", stall.trusted.size())),
                c -> new TrustedMenu(plugin, viewer, stall).open());
        if (!stall.isPacked()) {
            button(48, item(Material.BARREL, "claim.gui.pack"), c -> {
                viewer.closeInventory();
                plugin.claims().pack(stall, "claim.packed-manual");
            });
            button(49, item(Material.ENDER_PEARL, "gui.stall.tp"), c -> {
                viewer.closeInventory();
                MarketCommand.teleport(plugin, viewer, stall);
            });
        } else {
            button(49, item(Material.OAK_HANGING_SIGN, "claim.gui.find-spot"), c -> new DirectoryMenu(plugin, viewer, 0, true).open());
        }
        set(50, item(Material.BOOK, "gui.stall.howto"));

        // ---- améliorations et options (ligne du bas de la grille)
        var shops = plugin.shops();
        var set = plugin.settings();
        if (stall.extraShowcases < set.maxExtraShowcases) {
            button(37, item(Material.ITEM_FRAME, "gui.stall.buy-showcase", ph("price", money(shops.nextShowcasePrice(stall))),
                    ph("max", stall.showcaseLimit()), ph("next", stall.showcaseLimit() + 1)), c -> {
                shops.buyShowcase(viewer, stall);
                redraw();
            });
        } else {
            set(37, item(Material.ITEM_FRAME, "gui.stall.showcase-maxed", ph("max", stall.showcaseLimit())));
        }
        if (stall.catalogSlots < set.maxCatalogSlots) {
            button(38, item(Material.WRITABLE_BOOK, "gui.stall.buy-catalog", ph("price", money(shops.nextCatalogPrice(stall))),
                    ph("max", stall.catalogSlots), ph("next", stall.catalogSlots + 1)), c -> {
                shops.buyCatalogSlot(viewer, stall);
                redraw();
            });
        } else {
            set(38, item(Material.WRITABLE_BOOK, "gui.stall.catalog-maxed", ph("max", stall.catalogSlots)));
        }
        button(39, item(Material.HOPPER, "gui.stall.add-catalog", ph("used", stall.virtualCount()), ph("max", stall.catalogSlots)),
                c -> {
                    if (viewer.getInventory().getItemInMainHand().getType().isAir()) {
                        t().send(viewer, "showcase.empty-hand");
                        return;
                    }
                    if (stall.virtualCount() >= stall.catalogSlots) {
                        t().send(viewer, "catalog.no-slot");
                        return;
                    }
                    plugin.input().ask(viewer, "input.catalog-price", text -> {
                        double price = MarketCommand.parseMoney(text);
                        if (price > 0) shops.createVirtual(viewer, stall, price);
                        else t().send(viewer, "error.usage", ph("usage", "un prix, ex. 1500 ou 2k"));
                        open();
                    });
                });
        if (set.privateAllowed) {
            button(41, item(stall.privateStall ? Material.IRON_DOOR : Material.OAK_DOOR, "gui.stall.private",
                    pc("state", t().get(stall.privateStall ? "gui.stall.on" : "gui.stall.off"))), c -> {
                stall.privateStall = !stall.privateStall;
                stall.touch();
                plugin.store().markDirty();
                t().send(viewer, stall.privateStall ? "stall.private-on" : "stall.private-off");
                redraw();
            });
        }
        if (set.mannequins && !stall.isPacked()) {
            button(42, item(Material.ARMOR_STAND, "gui.stall.mannequin-spot"), c -> {
                if (!stall.region.contains(viewer.getLocation().getBlock())) {
                    t().send(viewer, "stall.mannequin-outside");
                    return;
                }
                var l = viewer.getLocation();
                stall.npcSpot = new double[]{l.getX(), l.getY(), l.getZ(), l.getYaw()};
                stall.mannequin = true;
                stall.touch();
                plugin.store().markDirty();
                plugin.mannequins().refresh(stall);
                t().send(viewer, "stall.mannequin-placed");
                redraw();
            });
            button(43, item(stall.mannequin ? Material.LIME_DYE : Material.GRAY_DYE, "gui.stall.mannequin-toggle",
                    pc("state", t().get(stall.mannequin ? "gui.stall.on" : "gui.stall.off"))), c -> {
                stall.mannequin = !stall.mannequin;
                stall.touch();
                plugin.store().markDirty();
                plugin.mannequins().refresh(stall);
                redraw();
            });
        }
        button(52, Items.glow(item(Material.PAINTING, "gui.stall.style")), c -> new StyleMenu(plugin, viewer, stall).open());
        Auction a = stall.auction;
        if (a != null && a.renewal && !stall.isPacked()) {
            button(51, Items.glow(item(Material.BELL, "gui.stall.renewal",
                    ph("bid", money(a.hasBid() ? a.bid : stall.startPrice)),
                    pc("bidder", a.hasBid() ? t().get("gui.auctions.by", ph("player", a.bidderName)) : t().get("gui.auctions.waiting")),
                    ph("time", Settings.time(a.endsAt - now)))),
                    c -> new AuctionMenu(plugin, viewer, stall, () -> new StallMenu(plugin, viewer, stall).open()).open());
        }
        back(45, () -> new MainMenu(plugin, viewer).open());
        close(53);
    }
}
