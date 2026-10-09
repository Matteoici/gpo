package fr.gpotrade;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Identifie nos fenêtres d'échange et à quel joueur (0 ou 1) chacune appartient. */
final class TradeHolder implements InventoryHolder {

    private final TradeSession session;
    private final int index;
    private Inventory inventory;

    TradeHolder(TradeSession session, int index) {
        this.session = session;
        this.index = index;
    }

    TradeSession session() {
        return session;
    }

    int index() {
        return index;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
