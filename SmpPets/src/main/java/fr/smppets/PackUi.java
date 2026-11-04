package fr.smppets;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Fond parchemin des menus (resource pack, police limitless:gui) glissé derrière le titre. */
final class PackUi {

    private static final Key FONT = Key.key("limitless", "gui");

    private PackUi() {
    }

    static Component title(Component t, int size) {
        if (size <= 0 || size % 9 != 0) return t;
        int rows = Math.max(1, Math.min(6, size / 9));
        return Component.empty()
                .append(Component.text("\uf801" + (char) (0xe100 + rows) + "\uf803").font(FONT).color(NamedTextColor.WHITE))
                .append(t);
    }
}
