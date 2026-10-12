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
            inv.setItem(i, null);
        }
        return pay(p, count, total, unsold);
    }

    public Result sellHand(Player p) {
        ItemStack it = p.getInventory().getItemInMainHand();
        double unit = plugin.worth().unitPrice(it);
        if (unit <= 0) return new Result(0, 0, List.of());
        int n = it.getAmount();
        p.getInventory().setItemInMainHand(null);
        return pay(p, n, unit * n, List.of());
    }

    private Result pay(Player p, long count, double total, List<ItemStack> unsold) {
        if (count == 0) return new Result(0, 0, unsold);
        double money = Math.floor(total * plugin.worth().multiplier(p) * 100) / 100.0;
        plugin.giveMoney(p, money);
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
