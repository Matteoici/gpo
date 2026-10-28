package fr.smpboss;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static fr.smpboss.SmpBoss.parse;

/** Tirage du butin, garantie (pitié), annonces. */
public final class Loot {

    private final SmpBoss plugin;

    Loot(SmpBoss plugin) {
        this.plugin = plugin;
    }

    /** Résultat d'un tirage pour un joueur. */
    public void reward(Player p) {
        var c = plugin.getConfig();
        PlayerData d = plugin.store().of(p.getUniqueId());
        d.kills++;

        // ---- toujours : commandes + graines
        for (String cmd : c.getStringList("loot.always-commands")) run(p, cmd);
        List<Integer> seeds = c.getIntegerList("loot.always-seeds");
        int min = seeds.isEmpty() ? 1 : seeds.get(0), max = seeds.size() > 1 ? seeds.get(1) : min;
        int n = ThreadLocalRandom.current().nextInt(min, max + 1);
        if (n > 0) give(p, plugin.items().seed(n));
        // ---- armes rares (Sonic Bow, Tranche Mace) : tirage à part
        plugin.weapons().roll(p);

        // ---- tirage de rareté (avec garantie)
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double roll = r.nextDouble() * 100;
        double prestige = c.getDouble("loot.chances.prestige", 0.01);
        double secret = c.getDouble("loot.chances.secret", 0.5);
        double legendary = c.getDouble("loot.chances.legendary", 1);
        double epic = c.getDouble("loot.chances.epic", 5);
        double rare = c.getDouble("loot.chances.rare", 20);
        int pitySecret = c.getInt("loot.pity.secret", 300), pityLegendary = c.getInt("loot.pity.legendary", 150);
        Rarity got;
        boolean hasPrestige = !c.getMapList("loot.pools.prestige").isEmpty();
        if (hasPrestige && roll < prestige) got = Rarity.PRESTIGE;
        else if ((roll -= hasPrestige ? prestige : 0) < secret || (pitySecret > 0 && d.pitySecret + 1 >= pitySecret)) got = Rarity.SECRET;
        else if (roll < secret + legendary || (pityLegendary > 0 && d.pityLegendary + 1 >= pityLegendary)) got = Rarity.LEGENDARY;
        else if (roll < secret + legendary + epic) got = Rarity.EPIC;
        else if (roll < secret + legendary + epic + rare) got = Rarity.RARE;
        else got = Rarity.COMMON;

        if (got == Rarity.SECRET || got == Rarity.PRESTIGE) {
            d.pitySecret = 0;
            d.pityLegendary = 0;
        } else if (got == Rarity.LEGENDARY) {
            d.pityLegendary = 0;
            d.pitySecret++;
        } else {
            d.pityLegendary++;
            d.pitySecret++;
        }
        plugin.store().dirty();
        if (got == Rarity.COMMON) {
            p.sendMessage(parse(plugin.msg("loot.common")));
            return;
        }
        List<Map<?, ?>> pool = c.getMapList("loot.pools." + got.name().toLowerCase(Locale.ROOT));
        if (pool.isEmpty()) return;
        Map<?, ?> e = pool.get(r.nextInt(pool.size()));
        givePoolEntry(p, got, e);
    }

    private void givePoolEntry(Player p, Rarity rarity, Map<?, ?> e) {
        String displayName;
        if (e.get("relic") != null) {
            Relic relic = plugin.relics().get(String.valueOf(e.get("relic")));
            if (relic == null) {
                plugin.getLogger().warning("loot : relique inconnue " + e.get("relic"));
                return;
            }
            displayName = relic.rarity().name(relic.name(), 0);
            // directement dans la collection : rien à ramasser, rien à perdre
            String where;
            if (plugin.relics().claim(p, relic)) where = plugin.msg("loot.where-relic");
            else where = give(p, plugin.items().relicItem(relic)) ? plugin.msg("loot.where-inventory") : plugin.msg("loot.where-stash");
            Reveal.show(plugin, p, plugin.items().relicItem(relic), rarity, displayName, where);
        } else if (Boolean.TRUE.equals(e.get("sickle"))) {
            ItemStack it = plugin.items().sickle();
            displayName = Rarity.SECRET.name(SmpBoss.MM.stripTags(plugin.msg("items.sickle.name")), 0);
            boolean inv = give(p, it.clone());
            Reveal.show(plugin, p, it, rarity, displayName, plugin.msg(inv ? "loot.where-inventory" : "loot.where-stash"));
        } else if (e.get("item") != null) {
            Material m = Material.matchMaterial(String.valueOf(e.get("item")));
            if (m == null) return;
            String name = e.get("name") == null ? null : String.valueOf(e.get("name"));
            List<String> lore = new ArrayList<>();
            if (e.get("lore") instanceof List<?> l) for (Object o : l) lore.add(String.valueOf(o));
            int amount = e.get("amount") instanceof Number num ? num.intValue() : 1;
            String model = e.get("item-model") == null ? null : String.valueOf(e.get("item-model"));
            ItemStack it = plugin.items().lootItem(m, amount, name, rarity, lore, model);
            displayName = name == null ? "<lang:" + m.translationKey() + ">" : rarity.name(name, 0);
            boolean inv = give(p, it.clone());
            Reveal.show(plugin, p, it, rarity, displayName, plugin.msg(inv ? "loot.where-inventory" : "loot.where-stash"));
        } else {
            displayName = e.get("name") == null ? "?" : rarity.name(String.valueOf(e.get("name")), 0);
        }
        if (e.get("commands") instanceof List<?> l) for (Object o : l) run(p, String.valueOf(o));
        String title = e.get("title") == null ? null : String.valueOf(e.get("title"));
        announce(p, rarity, displayName, title);
    }

    private void announce(Player p, Rarity rarity, String itemMini, String title) {
        p.sendMessage(parse(plugin.msg("loot.new-item").replace("<item>", itemMini)));
        if (title != null) {
            plugin.store().of(p.getUniqueId()).titles.add(title);
            // le titre secret devient aussi un titre affichable (SmpTitles)
            if (Bukkit.getPluginManager().isPluginEnabled("SmpTitles")) {
                String id = "boss_" + Integer.toHexString(title.toLowerCase(Locale.ROOT).hashCode());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "titre give " + p.getName() + " " + id + " "
                        + (rarity == Rarity.PRESTIGE ? Rarity.PRESTIGE : Rarity.SECRET).name(title, 0));
            }
            plugin.store().dirty();
            p.sendMessage(parse(plugin.msg("loot.new-title").replace("<title>", (rarity == Rarity.PRESTIGE ? Rarity.PRESTIGE : Rarity.SECRET).name(title, 0))));
        }
        if (rarity == Rarity.PRESTIGE) {
            // tout le serveur le voit : titre, éclair, son du dragon
            Component msg = parse(plugin.msg("loot.broadcast-prestige")
                    .replace("<player>", SmpBoss.MM.escapeTags(p.getName())).replace("<item>", itemMini));
            Bukkit.broadcast(msg);
            for (Player o : Bukkit.getOnlinePlayers()) {
                o.showTitle(Title.title(parse(rarity.tag), parse(plugin.msg("loot.prestige-subtitle")
                                .replace("<player>", SmpBoss.MM.escapeTags(p.getName())).replace("<item>", itemMini)),
                        Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(5000), Duration.ofMillis(1000))));
                o.playSound(Sound.sound(Key.key("minecraft", "entity.ender_dragon.death"), Sound.Source.MASTER, 0.6f, 1.1f));
                o.playSound(Sound.sound(Key.key("minecraft", "ui.toast.challenge_complete"), Sound.Source.MASTER, 1f, 0.8f));
            }
            p.getWorld().strikeLightningEffect(p.getLocation());
        } else if (rarity.ordinal() >= Rarity.LEGENDARY.ordinal()) {
            Component msg = parse(plugin.msg(rarity == Rarity.SECRET ? "loot.broadcast-secret" : "loot.broadcast-legendary")
                    .replace("<player>", SmpBoss.MM.escapeTags(p.getName())).replace("<item>", itemMini));
            Bukkit.broadcast(msg);
            p.showTitle(Title.title(parse(rarity.tag), parse(itemMini),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3500), Duration.ofMillis(800))));
            for (Player o : Bukkit.getOnlinePlayers()) {
                o.playSound(Sound.sound(Key.key("minecraft", rarity == Rarity.SECRET ? "entity.wither.spawn" : "ui.toast.challenge_complete"),
                        Sound.Source.MASTER, 0.7f, rarity == Rarity.SECRET ? 0.6f : 1f));
            }
        } else {
            p.playSound(Sound.sound(Key.key("minecraft", "entity.player.levelup"), Sound.Source.MASTER, 1f, 1.3f));
        }
    }

    static void run(Player p, String cmd) {
        String line = cmd.replace("%player%", p.getName()).replace("<player>", p.getName());
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line.startsWith("/") ? line.substring(1) : line);
    }

    /**
     * Donne un objet sans jamais le faire tomber par terre : ce qui ne rentre pas dans l'inventaire
     * part dans le coffre de butin du joueur (/reliques). Renvoie true si tout est entré dans l'inventaire.
     */
    public boolean give(Player p, ItemStack it) {
        var rest = p.getInventory().addItem(it).values();
        if (rest.isEmpty()) return true;
        PlayerData d = plugin.store().of(p.getUniqueId());
        int n = 0;
        for (ItemStack left : rest) {
            d.stash.add(left);
            n += left.getAmount();
        }
        plugin.store().dirty();
        p.sendMessage(parse(plugin.msg("loot.stash-added").replace("<n>", String.valueOf(n))));
        return false;
    }

    /** Sort du coffre de butin tout ce qui rentre dans l'inventaire. Renvoie le nombre d'objets récupérés. */
    public int takeStash(Player p) {
        PlayerData d = plugin.store().of(p.getUniqueId());
        int got = 0;
        java.util.List<ItemStack> keep = new ArrayList<>();
        for (ItemStack it : d.stash) {
            int before = it.getAmount();
            var rest = p.getInventory().addItem(it.clone()).values();
            int left = 0;
            for (ItemStack l : rest) {
                keep.add(l);
                left += l.getAmount();
            }
            got += before - left;
        }
        d.stash.clear();
        d.stash.addAll(keep);
        plugin.store().dirty();
        return got;
    }
}
