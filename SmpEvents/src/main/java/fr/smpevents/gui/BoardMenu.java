package fr.smpevents.gui;

import fr.smpevents.Items;
import fr.smpevents.SmpEvents;
import fr.smpevents.Texts;
import fr.smpevents.board.Board;
import fr.smpevents.board.Boards;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

import static fr.smpevents.Texts.ph;
import static fr.smpevents.Texts.pp;

/** /classement : tous les classements, avec ta place dans chacun. */
public final class BoardMenu extends Menu {

    private static final int[] SLOTS = {11, 13, 15, 19, 21, 23, 25, 29, 31, 33};

    public BoardMenu(SmpEvents plugin, Player viewer) {
        super(plugin, viewer, 5, plugin.texts().get("gui.boards.title"));
    }

    @Override
    protected void build() {
        Boards boards = plugin.boards();
        Texts t = t();
        Board[] all = Board.values();
        for (int i = 0; i < all.length && i < SLOTS.length; i++) {
            Board b = all[i];
            List<Component> lore = new ArrayList<>();
            List<Boards.Entry> top = boards.top(b, 10);
            for (int r = 0; r < top.size(); r++) {
                Boards.Entry e = top.get(r);
                String color = r == 0 ? t.raw("hologram.rank-color.1") : r == 1 ? t.raw("hologram.rank-color.2")
                        : r == 2 ? t.raw("hologram.rank-color.3") : t.raw("hologram.rank-color.other");
                lore.add(t.get("gui.boards.line", pp("color", color), ph("rank", r + 1), ph("player", e.name()),
                        ph("value", boards.format(b, e.value()))));
            }
            if (top.isEmpty()) lore.add(t.get("gui.boards.empty"));
            lore.add(Component.empty());
            int rank = boards.rank(b, viewer.getUniqueId());
            lore.add(rank > 0
                    ? t.get("gui.boards.you", ph("rank", rank), ph("value", boards.format(b, boards.valueOf(b, viewer.getUniqueId()))))
                    : t.get("gui.boards.you-none"));
            set(SLOTS[i], Items.of(b.icon, t.get("hologram.title." + b.key), lore));
        }
        set(4, item(Material.NAME_TAG, "gui.boards.header", ph("seconds", boards.refreshSeconds)));
        button(36, item(Material.WRITABLE_BOOK, "gui.boards.to-quests"), c -> new QuestMenu(plugin, viewer).open());
        close(40);
    }
}
