package fr.smpmarket.gui;

import fr.smpmarket.Items;
import fr.smpmarket.SmpMarket;
import fr.smpmarket.Stall;
import fr.smpmarket.Styles;
import fr.smpmarket.Texts;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

import static fr.smpmarket.Texts.pc;
import static fr.smpmarket.Texts.ph;

/** Personnalisation du stand : couleur du nom, fond, symbole, slogan. */
public final class StyleMenu extends Menu {

    private static final int[] THEME_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
    private static final int[] BG_SLOTS = {28, 29, 30, 31, 32, 33, 34};
    private static final int[] SYMBOL_SLOTS = {37, 38, 39, 40, 41, 42, 43};

    private final Stall stall;

    public StyleMenu(SmpMarket plugin, Player viewer, Stall stall) {
        super(plugin, viewer, 6, plugin.texts().get("gui.style.title"));
        this.stall = stall;
    }

    private void apply() {
        stall.touch();
                plugin.store().markDirty();
        plugin.visuals().restyle(stall);
        redraw();
    }

    @Override
    protected void build() {
        if (!stall.isOwner(viewer.getUniqueId())) {
            viewer.closeInventory();
            return;
        }
        Styles styles = plugin.styles();
        String currentTheme = styles.theme(stall.theme).id();
        String currentBg = styles.background(stall.background).id();
        String currentSymbol = stall.symbol == null ? styles.symbols().getFirst().id() : stall.symbol;

        // aperçu
        String sym = styles.symbol(stall.symbol);
        Component preview = styles.styled(stall.theme, (sym.isEmpty() ? "" : sym + " ") + stall.displayName(), true);
        set(4, Items.glow(Items.of(Material.OAK_HANGING_SIGN, preview, t().list("gui.style.preview",
                ph("slogan", stall.slogan == null || stall.slogan.isBlank() ? "-" : stall.slogan)))));

        // couleurs du nom
        List<Styles.Theme> themes = styles.themes();
        for (int i = 0; i < themes.size() && i < THEME_SLOTS.length; i++) {
            Styles.Theme th = themes.get(i);
            boolean sel = th.id().equals(currentTheme);
            List<Component> lore = new ArrayList<>();
            lore.add(t().get("gui.style.example", pc("example", styles.styled(th.id(), stall.displayName(), true))));
            lore.add(Component.empty());
            lore.add(t().get(sel ? "gui.style.selected" : "gui.style.click"));
            ItemStack it = Items.of(th.icon(), styles.styled(th.id(), th.name(), true), lore);
            if (sel) Items.glow(it);
            button(THEME_SLOTS[i], it, c -> {
                stall.theme = th.id();
                apply();
            });
        }

        // fonds
        List<Styles.Background> bgs = styles.backgrounds();
        for (int i = 0; i < bgs.size() && i < BG_SLOTS.length; i++) {
            Styles.Background bg = bgs.get(i);
            boolean sel = bg.id().equals(currentBg);
            ItemStack it = Items.of(bg.icon(), t().get("gui.style.background", ph("name", bg.name())),
                    List.of(t().get(sel ? "gui.style.selected" : "gui.style.click")));
            if (sel) Items.glow(it);
            button(BG_SLOTS[i], it, c -> {
                stall.background = bg.id();
                apply();
            });
        }

        // symboles
        List<Styles.Symbol> syms = styles.symbols();
        for (int i = 0; i < syms.size() && i < SYMBOL_SLOTS.length; i++) {
            Styles.Symbol s = syms.get(i);
            boolean sel = s.id().equals(currentSymbol);
            Component name = s.symbol().isEmpty() ? t().get("gui.style.no-symbol")
                    : styles.styled(stall.theme, s.symbol(), true);
            ItemStack it = Items.of(Material.PAPER, name, List.of(t().get(sel ? "gui.style.selected" : "gui.style.click")));
            if (sel) Items.glow(it);
            button(SYMBOL_SLOTS[i], it, c -> {
                stall.symbol = s.id();
                apply();
            });
        }

        set(18, item(Material.NAME_TAG, "gui.style.label-themes"));
        set(27, item(Material.BLACK_STAINED_GLASS_PANE, "gui.style.label-backgrounds"));
        set(36, item(Material.PAPER, "gui.style.label-symbols"));

        button(47, item(Material.NAME_TAG, "gui.stall.rename", plugin.auctions().stallTags(stall)),
                c -> plugin.input().ask(viewer, "input.name", name -> {
                    fr.smpmarket.MarketCommand.rename(plugin, viewer, stall, name);
                    open();
                }));
        button(49, item(Material.WRITABLE_BOOK, "gui.style.slogan",
                ph("slogan", stall.slogan == null || stall.slogan.isBlank() ? "-" : stall.slogan)), c -> {
            if (c.isRightClick()) {
                stall.slogan = null;
                apply();
                return;
            }
            plugin.input().ask(viewer, "input.slogan", text -> {
                String clean = Texts.MM.stripTags(text == null ? "" : text).trim();
                if (clean.length() > 32) {
                    t().send(viewer, "stall.bad-slogan");
                } else {
                    stall.slogan = clean.isEmpty() ? null : clean;
                    stall.touch();
                plugin.store().markDirty();
                    plugin.visuals().restyle(stall);
                    t().send(viewer, "stall.slogan-set");
                }
                open();
            });
        });
        back(45, () -> new StallMenu(plugin, viewer, stall).open());
        close(53);
    }
}
