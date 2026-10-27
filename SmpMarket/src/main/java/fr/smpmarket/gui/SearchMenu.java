package fr.smpmarket.gui;

import fr.smpmarket.MarketCommand;
import fr.smpmarket.Search;
import fr.smpmarket.Showcase;
import fr.smpmarket.SmpMarket;
import fr.smpmarket.Stall;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

import static fr.smpmarket.Texts.ph;

/** Résultats d'une recherche, du moins cher au plus cher. */
public final class SearchMenu extends Menu {

    private final String query;
    private int page;

    public SearchMenu(SmpMarket plugin, Player viewer, String query, int page) {
        super(plugin, viewer, 6, plugin.texts().get("gui.search.title", ph("query", query)));
        this.query = query;
        this.page = page;
    }

    @Override
    protected void build() {
        List<Search.Result> results = Search.find(plugin, query);
        int pages = pageCount(results.size(), GRID.length);
        page = Math.min(page, pages - 1);
        if (results.isEmpty()) set(22, item(Material.GRAY_DYE, "gui.search.empty"));
        for (int i = 0; i < GRID.length; i++) {
            int idx = page * GRID.length + i;
            if (idx >= results.size()) break;
            Stall st = results.get(idx).stall();
            Showcase c = results.get(idx).showcase();
            button(GRID[i], CatalogMenu.display(this, c, "gui.search.lore", ph("price", money(c.price)), ph("stock", c.stock),
                    ph("shop", st.displayName()), ph("owner", st.ownerName)), click -> {
                if (st.isPacked() || (click.isRightClick() && CatalogMenu.near(plugin, viewer, st))) {
                    new BuyMenu(plugin, viewer, st, c, () -> new SearchMenu(plugin, viewer, query, page).open()).open();
                } else {
                    viewer.closeInventory();
                    MarketCommand.teleport(plugin, viewer, st);
                }
            });
        }
        pages(page, pages, p -> new SearchMenu(plugin, viewer, query, p).open());
        back(45, () -> new MainMenu(plugin, viewer).open());
        close(49);
    }
}
