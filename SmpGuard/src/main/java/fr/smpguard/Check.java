package fr.smpguard;

/** Les différentes détections. key = clé dans config.yml et messages.yml. */
public enum Check {
    ESP_AIM("esp-aim"),
    XRAY("xray"),
    FREECAM("freecam"),
    AUTO_TOTEM("auto-totem"),
    CRYSTAL("crystal"),
    BAIT("bait");

    public final String key;

    Check(String key) {
        this.key = key;
    }
}
