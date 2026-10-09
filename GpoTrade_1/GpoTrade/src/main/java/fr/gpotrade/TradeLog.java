package fr.gpotrade;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Journal des échanges terminés (trades.log) : utile aux admins en cas de litige ou d'arnaque. */
final class TradeLog {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TradePlugin plugin;
    private final File file;

    TradeLog(TradePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "trades.log");
    }

    static String describe(ItemStack[] items, long money, String symbol) {
        List<String> parts = new ArrayList<>();
        for (ItemStack it : items) {
            String name = it.getType().name();
            ItemMeta meta = it.getItemMeta();
            if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
                name += " \"" + PlainTextComponentSerializer.plainText().serialize(meta.displayName()) + "\"";
            }
            parts.add(it.getAmount() + "x " + name);
        }
        if (money > 0) parts.add(symbol + money);
        return parts.isEmpty() ? "rien" : String.join(", ", parts);
    }

    void write(String line) {
        String full = "[" + LocalDateTime.now().format(FMT) + "] " + line + System.lineSeparator();
        try {
            Files.writeString(file.toPath(), full, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warning("Impossible d'écrire trades.log : " + e.getMessage());
        }
    }
}
