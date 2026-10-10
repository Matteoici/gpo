package fr.smpfriends.menu;

import fr.smpfriends.Friends;
import fr.smpfriends.FriendsPlugin;
import fr.smpfriends.Items;
import fr.smpfriends.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Envoyer de l'argent à un ami : montants rapides ou montant personnalisé. */
public final class PayMenu extends Menu {

    private static final int[] SLOTS = {10, 11, 12, 13, 14};
    private static final Material[] ICONS = {Material.GOLD_NUGGET, Material.GOLD_INGOT, Material.GOLD_BLOCK,
            Material.EMERALD_BLOCK, Material.DIAMOND_BLOCK};
    private static final int CUSTOM = 16, BACK = 22;

    private final UUID friend;
    private final List<Long> amounts;

    public PayMenu(FriendsPlugin plugin, Player viewer, UUID friend) {
        super(plugin, viewer, 3, Component.text("Envoyer à " + Friends.name(friend), NamedTextColor.DARK_GRAY)
                .decorate(TextDecoration.BOLD));
        this.friend = friend;
        this.amounts = plugin.getConfig().getLongList("pay-amounts");
    }

    @Override
    public void render() {
        frame(Material.YELLOW_STAINED_GLASS_PANE, Material.BLACK_STAINED_GLASS_PANE);
        String sym = plugin.symbol();
        double balance = plugin.vault().balance(viewer);
        for (int i = 0; i < SLOTS.length && i < amounts.size(); i++) {
            long a = amounts.get(i);
            boolean ok = balance >= a;
            inv.setItem(SLOTS[i], Items.of(ok ? ICONS[i] : Material.GRAY_DYE,
                    Text.t("Envoyer " + Text.money(a, sym), ok ? NamedTextColor.GOLD : NamedTextColor.DARK_GRAY).decorate(TextDecoration.BOLD),
                    List.of(Text.line("Ton solde :", Text.money(balance, sym)))));
        }
        inv.setItem(CUSTOM, Items.of(Material.OAK_SIGN, Text.t("Montant personnalisé", NamedTextColor.YELLOW),
                List.of(Text.t("Clique puis écris le montant.", NamedTextColor.GRAY))));
        inv.setItem(BACK, Items.of(Material.ARROW, Text.t("◀ Retour", NamedTextColor.WHITE), null));
    }

    @Override
    public void click(int slot, ClickType type) {
        for (int i = 0; i < SLOTS.length && i < amounts.size(); i++) {
            if (slot == SLOTS[i]) {
                plugin.friends().pay(viewer, friend, amounts.get(i));
                render();
                return;
            }
        }
        if (slot == CUSTOM) {
            plugin.prompts().ask(viewer, "Combien veux-tu envoyer à " + Friends.name(friend) + " ?", text -> {
                try {
                    double v = parse(text);
                    plugin.friends().pay(viewer, friend, v);
                } catch (NumberFormatException e) {
                    viewer.sendMessage(Text.msg("Montant invalide (ex : 2500, 10k, 1.5m).", NamedTextColor.RED));
                }
            });
        } else if (slot == BACK) {
            click();
            new FriendMenu(plugin, viewer, friend).open();
        }
    }

    /** Accepte 2500, 10k, 1.5m, 2b. */
    static double parse(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT).replace(",", ".").replace(" ", "");
        double mult = 1;
        if (t.endsWith("k")) mult = 1_000;
        else if (t.endsWith("m")) mult = 1_000_000;
        else if (t.endsWith("b")) mult = 1_000_000_000;
        if (mult > 1) t = t.substring(0, t.length() - 1);
        double v = Double.parseDouble(t) * mult;
        if (v <= 0 || Double.isNaN(v) || Double.isInfinite(v)) throw new NumberFormatException();
        return Math.floor(v * 100) / 100;
    }
}
