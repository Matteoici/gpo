package fr.smpguard;

import org.bukkit.Material;

/** Les différentes détections. key = clé dans config.yml et messages.yml. */
public enum Check {
    ESP_AIM("esp-aim", Material.SPYGLASS),
    XRAY("xray", Material.DIAMOND_ORE),
    FREECAM("freecam", Material.ENDER_EYE),
    WALL("wall-interact", Material.CHEST),
    CLIENT("client", Material.COMMAND_BLOCK),
    REACH("reach", Material.TRIDENT),
    KILLAURA("killaura", Material.DIAMOND_SWORD),
    AUTOCLICKER("autoclicker", Material.STONE_BUTTON),
    FLY("fly", Material.FEATHER),
    SPEED("speed", Material.SUGAR),
    NOFALL("nofall", Material.HAY_BLOCK),
    FASTBREAK("fastbreak", Material.DIAMOND_PICKAXE),
    AUTO_TOTEM("auto-totem", Material.TOTEM_OF_UNDYING),
    INVMOVE("inventory-move", Material.CHEST_MINECART),
    CRITICALS("criticals", Material.IRON_SWORD),
    SCAFFOLD("scaffold", Material.SCAFFOLDING),
    BAIT("bait", Material.ZOMBIE_HEAD);

    public final String key;
    public final Material icon;

    Check(String key, Material icon) {
        this.key = key;
        this.icon = icon;
    }
}
