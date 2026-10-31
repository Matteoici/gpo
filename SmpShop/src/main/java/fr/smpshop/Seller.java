package fr.smpshop;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

import static fr.smpshop.Texts.ph;

/** Vente d'objets contre de l'argent (Vault). */
public final class Seller {

    public record Result(long items, double money, List<ItemStack> unsold) {
    }

    private final SmpShop plugin;

    Seller(SmpShop plugin) {
        this.plugin = plugin;
    }

    /** Valeur totale (sans retirer les objets). */
    public double value(Player p, Iterable<ItemStack> items) {
        double total = 0;
        for (ItemStack it : items) total += plugin.worth().unitPrice(it) * (it == null ? 0 : it.getAmount());
        return Math.floor(total * plugin.worth().multiplier(p) * 100) / 100.0;
    }

    /** Vend les cases [from, to[ de l'inventaire. Les objets invendables restent en place. */
    public Result sellSlots(Player p, Inventory inv, int from, int to) {
        long count = 0;
        double total = 0;
        List<ItemStack> unsold = new ArrayList<>();
        java.util.Map<String, double[]> detail = new java.util.LinkedHashMap<>();
        java.util.Map<String, ItemStack> sample = new java.util.HashMap<>();
        for (int i = from; i < to; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            double unit = plugin.worth().unitPrice(it);
            if (unit <= 0) {
                unsold.add(it);
                continue;
            }
            count += it.getAmount();
            total += unit * it.getAmount();
            note(detail, sample, it, unit);
            inv.setItem(i, null);
        }
        return pay(p, count, total, unsold, detail, sample);
    }

    public Result sellHand(Player p) {
        ItemStack it = p.getInventory().getItemInMainHand();
        double unit = plugin.worth().unitPrice(it);
        if (unit <= 0) return new Result(0, 0, List.of());
        int n = it.getAmount();
        java.util.Map<String, double[]> detail = new java.util.LinkedHashMap<>();
        java.util.Map<String, ItemStack> sample = new java.util.HashMap<>();
        note(detail, sample, it, unit);
        p.getInventory().setItemInMainHand(null);
        return pay(p, n, unit * n, List.of(), detail, sample);
    }

    /** Regroupe les objets vendus par type (et nom perso) pour l'historique. */
    private static void note(java.util.Map<String, double[]> detail, java.util.Map<String, ItemStack> sample, ItemStack it, double unit) {
        String name = "";
        var meta = it.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        }
        String k = it.getType().name() + ";" + name;
        double[] d = detail.computeIfAbsent(k, x -> new double[2]);
        d[0] += it.getAmount();
        d[1] += unit * it.getAmount();
        sample.putIfAbsent(k, it);
    }

    private Result pay(Player p, long count, double total, List<ItemStack> unsold, java.util.Map<String, double[]> detail,
                       java.util.Map<String, ItemStack> sample) {
        if (count == 0) return new Result(0, 0, unsold);
        double mult = plugin.worth().multiplier(p);
        double money = Math.floor(total * mult * 100) / 100.0;
        plugin.giveMoney(p, money);
        // historique
        List<SellHistory.Line> lines = new ArrayList<>();
        for (var e : detail.entrySet()) {
            String[] k = e.getKey().split(";", 2);
            lines.add(new SellHistory.Line(sample.get(e.getKey()).getType(), k.length > 1 ? k[1] : "", (long) e.getValue()[0],
                    Math.floor(e.getValue()[1] * mult * 100) / 100.0));
        }
        lines.sort((a, b) -> Double.compare(b.money(), a.money()));
        plugin.history().add(p.getUniqueId(), new SellHistory.Sale(System.currentTimeMillis(), count, money, mult, lines));
        return new Result(count, money, unsold);
    }

    /** Message + son après une vente. */
    public void report(Player p, Result r) {
        Texts t = plugin.texts();
        if (r.items() == 0) {
            t.send(p, "sell.nothing");
            return;
        }
        double mult = plugin.worth().multiplier(p);
        t.send(p, mult > 1 ? "sell.done-bonus" : "sell.done", ph("count", r.items()), ph("money", plugin.settings().money(r.money())),
                ph("bonus", Math.round((mult - 1) * 100)));
        p.playSound(Sound.sound(Key.key("minecraft", "entity.experience_orb.pickup"), Sound.Source.MASTER, 1f, 1.1f));
    }
}
