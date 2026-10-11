package fr.smpstart;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lecture de config.yml (rechargeable avec /guide reload). */
public final class Settings {

    private final SmpStart plugin;
    private FileConfiguration c;
    private final List<Step> steps = new ArrayList<>();
    private final List<ItemStack> kit = new ArrayList<>();
    private Reward completion = Reward.NONE;

    Settings(SmpStart plugin) {
        this.plugin = plugin;
    }

    void load() {
        plugin.reloadConfig();
        c = plugin.getConfig();
        loadSteps();
        loadKit();
        completion = parseReward(c.getConfigurationSection("completion.reward"));
    }

    // ------------------------------------------------------------ étapes

    private void loadSteps() {
        steps.clear();
        ConfigurationSection ss = c.getConfigurationSection("steps");
        if (ss == null) return;
        for (String key : ss.getKeys(false)) {
            ConfigurationSection s = ss.getConfigurationSection(key);
            if (s == null) continue;
            StepType type;
            try {
                type = StepType.valueOf(s.getString("type", "").trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Étape '" + key + "' ignorée : type inconnu (BREAK, PLACE, CRAFT, KILL, COMMAND, PLAYTIME).");
                continue;
            }
            Material icon = Material.matchMaterial(s.getString("icon", "PAPER"));
            if (icon == null) icon = Material.PAPER;
            List<String> targets = new ArrayList<>(s.getStringList("targets"));
            String single = s.getString("target");
            if (single != null && !single.isBlank()) targets.add(single);
            if (targets.isEmpty() && type != StepType.PLAYTIME) {
                plugin.getLogger().warning("Étape '" + key + "' ignorée : aucune cible (targets).");
                continue;
            }
            steps.add(new Step(key, s.getString("name", key), icon, s.getStringList("description"), type, targets,
                    Math.max(1, s.getInt("amount", 1)), s.getString("hint", ""),
                    parseReward(s.getConfigurationSection("reward"))));
        }
        if (steps.size() > GuideMenu.SLOTS.length) {
            plugin.getLogger().warning("Le menu n'affiche que " + GuideMenu.SLOTS.length + " étapes (" + steps.size() + " configurées).");
        }
        plugin.getLogger().info(steps.size() + " étape(s) de guide chargée(s).");
    }

    private Reward parseReward(ConfigurationSection s) {
        if (s == null) return Reward.NONE;
        return new Reward(Math.max(0, s.getDouble("money", 0)), s.getStringList("items"), s.getStringList("commands"));
    }

    // ------------------------------------------------------------ kit

    private void loadKit() {
        kit.clear();
        for (Map<?, ?> m : c.getMapList("kit.items")) {
            ItemStack it = buildItem(m);
            if (it != null) kit.add(it);
        }
    }

    private ItemStack buildItem(Map<?, ?> m) {
        Object mat = m.get("material");
        Material material = mat == null ? null : Material.matchMaterial(String.valueOf(mat));
        if (material == null || material.isAir()) {
            plugin.getLogger().warning("Objet de kit ignoré (matériau inconnu) : " + mat);
            return null;
        }
        int amount = 1;
        if (m.get("amount") instanceof Number n) amount = Math.max(1, Math.min(64, n.intValue()));
        ItemStack it = new ItemStack(material, amount);
        ItemMeta meta = it.getItemMeta();
        if (m.get("name") != null) meta.displayName(plugin.texts().parse(String.valueOf(m.get("name"))));
        if (m.get("lore") instanceof List<?> lore) {
            List<net.kyori.adventure.text.Component> lines = new ArrayList<>();
            for (Object o : lore) lines.add(plugin.texts().parse(String.valueOf(o)));
            meta.lore(lines);
        }
        if (m.get("enchants") instanceof Map<?, ?> ench) {
            for (Map.Entry<?, ?> e : ench.entrySet()) {
                Enchantment en = enchantment(String.valueOf(e.getKey()));
                if (en == null) {
                    plugin.getLogger().warning("Enchantement inconnu dans le kit : " + e.getKey());
                    continue;
                }
                int level = e.getValue() instanceof Number n ? Math.max(1, n.intValue()) : 1;
                meta.addEnchant(en, level, true);
            }
        }
        if (Boolean.TRUE.equals(m.get("guide-item"))) {
            meta.getPersistentDataContainer().set(plugin.guideKey(), PersistentDataType.BYTE, (byte) 1);
        }
        it.setItemMeta(meta);
        return it;
    }

    private Enchantment enchantment(String name) {
        try {
            return RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)
                    .get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ accès

    public List<Step> steps() {
        return steps;
    }

    public Reward completion() {
        return completion;
    }

    /** Copies fraîches des objets du kit. */
    public List<ItemStack> kitItems() {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack it : kit) out.add(it.clone());
        return out;
    }

    public boolean kitEnabled() {
        return c.getBoolean("kit.enabled", true);
    }

    public double starterMoney() {
        return Math.max(0, c.getDouble("kit.starter-money", 0));
    }

    public int protectionMinutes() {
        return Math.max(0, c.getInt("protection.minutes", 20));
    }

    public boolean includeExisting() {
        return c.getBoolean("include-existing-players", false);
    }

    public int reminderMinutes() {
        return c.getInt("reminder-minutes", 6);
    }

    public boolean broadcastFirstJoin() {
        return c.getBoolean("broadcast-first-join", true);
    }

    public boolean broadcastFinish() {
        return c.getBoolean("completion.broadcast", true);
    }

    public boolean greetEnabled() {
        return c.getBoolean("greet.enabled", true);
    }

    public int greetWindowSeconds() {
        return Math.max(5, c.getInt("greet.window-seconds", 60));
    }

    public int greetMaxPlayers() {
        return Math.max(1, c.getInt("greet.max-greeters", 5));
    }

    public double greetReward() {
        return Math.max(0, c.getDouble("greet.reward", 50));
    }

    public List<String> greetWords() {
        List<String> l = c.getStringList("greet.words");
        return l.isEmpty() ? List.of("bienvenue", "bvn", "welcome") : l;
    }

    public List<String> firstJoinCommands() {
        return c.getStringList("first-join-commands");
    }
}
