package fr.smpadmin.gui;

import fr.smpadmin.Items;
import fr.smpadmin.SmpAdmin;
import fr.smpadmin.Texts;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Prison (SmpPrison) : choix de la durée et de la raison, confirmation, ou libération. */
public final class PrisonMenu extends Menu {

    private static final String[][] DURATIONS = {
            {"30m", "30 minutes"}, {"1h", "1 heure"}, {"5h", "5 heures"}, {"12h", "12 heures"}, {"1j", "1 jour"},
            {"3j", "3 jours"}, {"1s", "1 semaine"}, {"1mo", "1 mois"}, {"500a", "500 ans (à vie)"}};
    private static final int[] DURATION_SLOTS = {9, 10, 11, 12, 13, 14, 15, 16, 17};
    private static final Object[][] REASONS = {
            {"X-ray", Material.DIAMOND_ORE}, {"Fly", Material.FEATHER}, {"Killaura", Material.DIAMOND_SWORD},
            {"Freecam", Material.ENDER_EYE}, {"Client de triche", Material.COMMAND_BLOCK}, {"Grief", Material.TNT},
            {"Vol", Material.CHEST}, {"Insultes", Material.BOOK}};
    private static final int[] REASON_SLOTS = {28, 29, 30, 31, 32, 33, 34, 37};

    private final OfflinePlayer target;
    private final Runnable back;
    private String duration = "1j", durationLabel = "1 jour";
    private String pendingReason;

    public PrisonMenu(SmpAdmin plugin, Player viewer, OfflinePlayer target, Runnable back) {
        super(plugin, viewer, 6, Texts.parse("<dark_gray>Prison - " + PlayerMenu.nameOf(target)));
        this.target = target;
        this.back = back;
    }

    private static ItemStack it(Material m, String name, String... lore) {
        List<Component> l = new java.util.ArrayList<>();
        for (String s : lore) l.add(Texts.parse(s));
        return Items.of(m, Texts.parse(name), l);
    }

    @Override
    protected void build() {
        String name = PlayerMenu.nameOf(target);
        if (pendingReason != null) {
            set(4, Items.head(target, Texts.parse("<white><b>" + Texts.MM.escapeTags(name)), List.of()));
            set(22, it(Material.IRON_BARS, "<red><b>Envoyer " + Texts.MM.escapeTags(name) + " en prison ?",
                    "<gray>Durée : <white>" + durationLabel, "<gray>Raison : <white>" + Texts.MM.escapeTags(pendingReason),
                    "", "<dark_gray>Son inventaire est mis de côté et rendu à sa sortie.",
                    "<dark_gray>Chaque bloc miné avec /travailler réduit sa peine."));
            button(29, it(Material.LIME_CONCRETE, "<green><b>✔ Confirmer"), c -> {
                viewer.closeInventory();
                viewer.performCommand("smpprison:prison " + name + " " + duration + " " + pendingReason);
            });
            button(33, it(Material.RED_CONCRETE, "<red><b>✖ Annuler"), c -> {
                pendingReason = null;
                redraw();
            });
            return;
        }
        set(4, Items.head(target, Texts.parse("<red><b>⛓ Prison : " + Texts.MM.escapeTags(name)),
                List.of(Texts.parse("<gray>1. Choisis la durée"), Texts.parse("<gray>2. Clique sur la raison"))));
        for (int i = 0; i < DURATIONS.length; i++) {
            String d = DURATIONS[i][0], label = DURATIONS[i][1];
            boolean sel = d.equals(duration);
            ItemStack item = it(d.equals("500a") ? Material.WITHER_SKELETON_SKULL : Material.CLOCK,
                    (sel ? "<green><b>✔ " : "<yellow>") + label, sel ? "<green>Sélectionnée" : "<gray>Clic : choisir");
            button(DURATION_SLOTS[i], sel ? Items.glow(item) : item, c -> {
                duration = d;
                durationLabel = label;
                redraw();
            });
        }
        set(22, it(Material.PAPER, "<white>Durée choisie : <yellow>" + durationLabel, "<gray>Clique maintenant sur une raison ↓"));
        for (int i = 0; i < REASONS.length; i++) {
            String r = (String) REASONS[i][0];
            button(REASON_SLOTS[i], it((Material) REASONS[i][1], "<red>" + r, "<gray>Durée : <white>" + durationLabel, "<yellow>▶ Clic : continuer"), c -> {
                pendingReason = r;
                redraw();
            });
        }
        button(38, it(Material.OAK_SIGN, "<white>Autre raison…", "<gray>Écris la raison dans le chat"), c -> plugin.input().ask(viewer, text -> {
            pendingReason = text;
            open();
        }));
        button(42, it(Material.LIME_DYE, "<green><b>Libérer " + Texts.MM.escapeTags(name), "<gray>S'il est en prison : il sort tout de suite",
                "<gray>et récupère son inventaire."), c -> {
            viewer.closeInventory();
            viewer.performCommand("smpprison:prison libérer " + name);
        });
        button(43, it(Material.BOOK, "<white>Sa peine", "<gray>Temps restant et raison"), c -> {
            viewer.closeInventory();
            viewer.performCommand("smpprison:prison info " + name);
        });
        if (back != null) back(45, back);
        close(49);
    }
}
