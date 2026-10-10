package fr.smpguilds.gui;

import fr.smpguilds.Guild;
import fr.smpguilds.GuildPerm;
import fr.smpguilds.Items;
import fr.smpguilds.SmpGuilds;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static fr.smpguilds.Messages.ph;

/** Candidatures reçues : clic gauche = accepter, clic droit = refuser. */
public final class ApplicationsMenu extends Menu {

    public ApplicationsMenu(SmpGuilds plugin, Player viewer) {
        super(plugin, viewer, 6, plugin.messages().get("gui.applications.title"));
    }

    @Override
    protected void build() {
        Guild g = myGuild();
        if (g == null) {
            viewer.closeInventory();
            return;
        }
        boolean allowed = gm().has(g, viewer.getUniqueId(), GuildPerm.APPLICATIONS);
        SimpleDateFormat df = new SimpleDateFormat("dd/MM HH:mm");
        List<Map.Entry<UUID, Guild.Application>> apps = new ArrayList<>(g.applications.entrySet());
        for (int i = 0; i < Math.min(36, apps.size()); i++) {
            UUID u = apps.get(i).getKey();
            Guild.Application app = apps.get(i).getValue();
            var r = new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[]{ph("player", g.nameOf(u)),
                    ph("message", app.message().isEmpty() ? "-" : app.message()), ph("date", df.format(new Date(app.time())))};
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>(msg().list("gui.applications.lore", r));
            if (allowed) lore.addAll(msg().list("gui.applications.lore-manage", r));
            button(9 + i, Items.head(Bukkit.getOfflinePlayer(u), msg().get("gui.applications.name", r), lore), t -> {
                if (!allowed) return;
                if (t == ClickType.RIGHT) plugin.actions().denyApplication(viewer, u);
                else plugin.actions().acceptApplication(viewer, u);
                redraw();
            });
        }
        if (apps.isEmpty()) set(22, item(Material.STRUCTURE_VOID, "gui.applications.empty"));
        set(4, item(Material.WRITABLE_BOOK, "gui.applications.header", ph("count", apps.size()),
                fr.smpguilds.Messages.comp("status", msg().get(g.open ? "info.open" : "info.closed"))));
        back(49, () -> new MainMenu(plugin, viewer).open());
        fill(g.color);
    }
}
