package fr.smpadmin.gui;

import fr.smpadmin.Items;
import fr.smpadmin.Punishment;
import fr.smpadmin.Settings;
import fr.smpadmin.SmpAdmin;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static fr.smpadmin.Texts.pc;
import static fr.smpadmin.Texts.ph;
import static fr.smpadmin.Texts.pp;

/** Choix de la durée et de la raison d'une sanction, puis confirmation. */
public final class SanctionMenu extends Menu {

    private static final int[] DURATION_SLOTS = {9, 10, 11, 12, 13, 14, 15, 16, 17};
    private static final int[] REASON_SLOTS = {27, 28, 29, 30, 31, 32, 33, 34, 35};

    private final OfflinePlayer target;
    private final Punishment.Type type;
    private final Runnable back;
    private Long selected;          // durée choisie à la main (null = celle de la raison)
    private boolean silent;
    private String pendingReason;   // raison choisie, en attente de confirmation
    private long pendingDuration;

    public SanctionMenu(SmpAdmin plugin, Player viewer, OfflinePlayer target, Punishment.Type type, Runnable back) {
        super(plugin, viewer, 6, plugin.texts().get("gui.sanction.title", pp("type", typeName(plugin, type)),
                ph("player", PlayerMenu.nameOf(target))));
        this.target = target;
        this.type = type;
        this.back = back;
    }

    private static String typeName(SmpAdmin plugin, Punishment.Type type) {
        return plugin.texts().raw("types." + type.name().toLowerCase(Locale.ROOT));
    }

    private boolean timed() {
        return type == Punishment.Type.BAN || type == Punishment.Type.MUTE;
    }

    private long defaultDuration() {
        Settings s = plugin.settings();
        return type == Punishment.Type.BAN ? s.banDefault : s.muteDefault;
    }

    private String durationText(long d) {
        return timed() ? plugin.actions().duration(d) : "-";
    }

    @Override
    protected void build() {
        String name = PlayerMenu.nameOf(target);
        if (pendingReason != null) {
            buildConfirm(name);
            return;
        }
        set(4, Items.head(target, t().get("gui.sanction.header.name", ph("player", name)),
                t().list("gui.sanction.header.lore", ph("duration", selected == null ? "-" : durationText(selected)))));

        if (timed()) {
            List<Long> durations = plugin.settings().durations;
            for (int i = 0; i < durations.size() && i < DURATION_SLOTS.length; i++) {
                long d = durations.get(i);
                boolean sel = selected != null && selected == d;
                List<net.kyori.adventure.text.Component> lore = new ArrayList<>(t().list("gui.sanction.duration.lore"));
                if (sel) lore.add(0, t().get("gui.sanction.duration.selected"));
                ItemStack it = Items.of(d == 0 ? Material.RED_STAINED_GLASS : Material.CLOCK,
                        t().get("gui.sanction.duration.name", ph("duration", durationText(d))), lore);
                if (sel) Items.glow(it);
                button(DURATION_SLOTS[i], it, c -> {
                    selected = (selected != null && selected == d) ? null : d;
                    redraw();
                });
            }
        }

        List<Settings.Reason> reasons = plugin.settings().reasons.get(type);
        for (int i = 0; i < reasons.size() && i < REASON_SLOTS.length; i++) {
            Settings.Reason r = reasons.get(i);
            long d = selected != null ? selected : (r.duration() >= 0 ? r.duration() : defaultDuration());
            TagResolver[] tr = {ph("reason", r.name()), ph("duration", durationText(d))};
            List<net.kyori.adventure.text.Component> lore = timed() ? t().list("gui.sanction.reason.lore", tr) : List.of();
            button(REASON_SLOTS[i], Items.of(r.icon(), t().get("gui.sanction.reason.name", tr), lore), c -> {
                pendingReason = r.name();
                pendingDuration = d;
                redraw();
            });
        }

        button(40, item(Material.OAK_SIGN, "gui.sanction.custom"), c -> plugin.input().ask(viewer, text -> {
            pendingReason = text;
            pendingDuration = selected != null ? selected : defaultDuration();
            open();
        }));
        button(42, item(silent ? Material.REDSTONE_TORCH : Material.LEVER, "gui.sanction.silent",
                pc("state", t().get(silent ? "gui.player.on" : "gui.player.off"))), c -> {
            silent = !silent;
            redraw();
        });
        if (back != null) back(45, back);
        close(49);
    }

    private void buildConfirm(String name) {
        TagResolver[] r = {ph("player", name), pp("type", typeName(plugin, type)),
                ph("duration", durationText(pendingDuration)), ph("reason", pendingReason)};
        set(4, Items.head(target, t().get("gui.sanction.header.name", ph("player", name)), List.of()));
        button(29, item(Material.LIME_CONCRETE, "gui.sanction.confirm", r), c -> apply());
        set(31, item(Material.PAPER, "gui.sanction.confirm", r));
        button(33, item(Material.RED_CONCRETE, "gui.sanction.cancel"), c -> {
            pendingReason = null;
            redraw();
        });
    }

    private void apply() {
        boolean ok = switch (type) {
            case BAN -> plugin.actions().ban(viewer, target, pendingDuration, pendingReason, silent);
            case MUTE -> plugin.actions().mute(viewer, target, pendingDuration, pendingReason, silent);
            case WARN -> plugin.actions().warn(viewer, target, pendingReason, silent);
            case KICK -> {
                Player online = target.getPlayer();
                if (online == null) {
                    t().send(viewer, "error.offline", ph("player", PlayerMenu.nameOf(target)));
                    yield false;
                }
                yield plugin.actions().kick(viewer, online, pendingReason, silent);
            }
        };
        pendingReason = null;
        if (ok && back != null) back.run();
        else if (ok) viewer.closeInventory();
        else redraw();
    }
}
