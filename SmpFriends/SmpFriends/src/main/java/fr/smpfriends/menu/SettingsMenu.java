package fr.smpfriends.menu;

import fr.smpfriends.FriendsPlugin;
import fr.smpfriends.Items;
import fr.smpfriends.Profile;
import fr.smpfriends.Setting;
import fr.smpfriends.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Réglages de confidentialité : chaque option = une icône + un interrupteur en dessous. */
public final class SettingsMenu extends Menu {

    private static final int[] ICON_SLOTS = {11, 12, 13, 14, 15};
    private static final int BACK = 31;

    public SettingsMenu(FriendsPlugin plugin, Player viewer) {
        super(plugin, viewer, 4, Component.text("⚙ Réglages", NamedTextColor.DARK_GRAY).decorate(TextDecoration.BOLD));
    }

    @Override
    public void render() {
        frame(Material.CYAN_STAINED_GLASS_PANE, Material.BLACK_STAINED_GLASS_PANE);
        Setting[] all = Setting.values();
        for (int i = 0; i < all.length && i < ICON_SLOTS.length; i++) {
            Setting s = all[i];
            boolean on = plugin.friends().allows(viewer.getUniqueId(), s);
            List<Component> lore = List.of(Text.t(s.description, NamedTextColor.GRAY), Component.empty(),
                    Text.t(on ? "✔ Activé" : "✖ Désactivé", on ? NamedTextColor.GREEN : NamedTextColor.RED),
                    Text.t("Clique pour changer.", NamedTextColor.YELLOW));
            ItemStack icon = Items.of(s.icon, Text.t(s.label, NamedTextColor.AQUA).decorate(TextDecoration.BOLD), lore);
            inv.setItem(ICON_SLOTS[i], on ? Items.glow(icon) : icon);
            inv.setItem(ICON_SLOTS[i] + 9, Items.of(on ? Material.LIME_DYE : Material.GRAY_DYE,
                    Text.t(on ? "✔ Activé" : "✖ Désactivé", on ? NamedTextColor.GREEN : NamedTextColor.RED), lore));
        }
        inv.setItem(BACK, Items.of(Material.ARROW, Text.t("◀ Retour", NamedTextColor.WHITE), null));
    }

    @Override
    public void click(int slot, ClickType type) {
        Setting[] all = Setting.values();
        for (int i = 0; i < all.length && i < ICON_SLOTS.length; i++) {
            if (slot == ICON_SLOTS[i] || slot == ICON_SLOTS[i] + 9) {
                Profile me = plugin.friends().profile(viewer.getUniqueId());
                me.set(all[i], !plugin.friends().allows(viewer.getUniqueId(), all[i]));
                plugin.store().save();
                click();
                render();
                return;
            }
        }
        if (slot == BACK) {
            click();
            new MainMenu(plugin, viewer).open();
        }
    }
}
