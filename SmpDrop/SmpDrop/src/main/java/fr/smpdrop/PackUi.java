package fr.smpdrop;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Menus style grimoire (resource pack, police limitless:gui) : fond derrière le titre, titre en crème sur le bandeau rouge. */
final class PackUi {

    private static final Key FONT = Key.key("limitless", "gui");
    private static final TextColor CREAM = TextColor.color(0xF5E6C0);

    private PackUi() {
    }

    static Component title(Component t, int size) {
        if (size <= 0 || size % 9 != 0) return t;
        int rows = Math.max(1, Math.min(6, size / 9));
        String plain = PlainTextComponentSerializer.plainText().serialize(t);
        return Component.empty()
                .append(Component.text("\uf801" + (char) (0xe100 + rows) + "\uf803").font(FONT).color(NamedTextColor.WHITE))
                .append(Component.text(plain, CREAM));
    }
}
