package fr.smpguilds.gui;

import fr.smpguilds.Guild;
import fr.smpguilds.Items;
import fr.smpguilds.Level;
import fr.smpguilds.SmpGuilds;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

import static fr.smpguilds.Messages.ph;

/** Paliers de niveau : progression, bonus débloqués et à venir. */
public final class LevelsMenu extends Menu {

    public LevelsMenu(SmpGuilds plugin, Player viewer) {
        super(plugin, viewer, 5, plugin.messages().get("gui.levels.title"));
    }

    @Override
    protected void build() {
        Guild g = myGuild();
        if (g == null) {
            viewer.closeInventory();
            return;
        }
        Level cur = gm().level(g);
        set(4, item(Material.EXPERIENCE_BOTTLE, "gui.levels.progress", Menus.resolvers(plugin, g)));
        int[] slots = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        List<Level> levels = plugin.settings().levels();
        for (int i = 0; i < Math.min(slots.length, levels.size()); i++) {
            Level l = levels.get(i);
            boolean reached = l.level() <= cur.level();
            String effects = l.effects().isEmpty() ? "-" : String.join(", ", l.effects().stream().map(Level.Effect::label).toList());
            var r = new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[]{ph("level", l.level()), ph("xp", l.xp()),
                    ph("max_members", l.maxMembers()), ph("max_claims", l.maxClaims()), ph("vault", l.vaultRows() * 9),
                    ph("effects", effects)};
            List<Component> lore = new ArrayList<>(msg().list("gui.levels.lore", r));
            lore.add(msg().get(reached ? "gui.levels.reached" : "gui.levels.locked", r));
            var it = Items.of(reached ? Material.EXPERIENCE_BOTTLE : Material.GLASS_BOTTLE, msg().get("gui.levels.name", r), lore);
            it.setAmount(Math.max(1, Math.min(64, l.level())));
            if (l.level() == cur.level()) Items.glow(it);
            set(slots[i], it);
        }
        set(40, item(Material.IRON_SWORD, "gui.levels.how", ph("player_kill", plugin.settings().l("xp.player-kill", 10)),
                ph("mob_kill", plugin.settings().l("xp.mob-kill", 1)), ph("war_kill", plugin.settings().l("xp.war-kill", 25)),
                ph("war_win", plugin.settings().l("xp.war-win", 500))));
        back(36, () -> new MainMenu(plugin, viewer).open());
        fill(g.color);
    }
}
