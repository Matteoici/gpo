package fr.smpshop;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Affiche le prix de vente sous les objets, UNIQUEMENT dans ce qui est envoyé à l'écran du joueur.
 * L'objet sur le serveur n'est jamais modifié : il garde son identité et s'empile normalement.
 * Pas d'affichage en créatif (le client renverrait l'objet avec la ligne de prix).
 * Classe chargée seulement si ProtocolLib est présent.
 */
final class PriceDisplay {

    private PriceDisplay() {
    }

    static void register(SmpShop plugin) {
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST,
                PacketType.Play.Server.SET_SLOT, PacketType.Play.Server.WINDOW_ITEMS) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (!plugin.displayEnabled()) return;
                Player p = event.getPlayer();
                if (p == null || p.getGameMode() == GameMode.CREATIVE) return;
                try {
                    PacketContainer packet = event.getPacket();
                    if (event.getPacketType() == PacketType.Play.Server.SET_SLOT) {
                        ItemStack it = packet.getItemModifier().read(0);
                        ItemStack out = decorate(plugin, it);
                        if (out != it) {
                            PacketContainer copy = packet.shallowClone();
                            copy.getItemModifier().write(0, out);
                            event.setPacket(copy);
                        }
                    } else {
                        List<ItemStack> list = packet.getItemListModifier().read(0);
                        List<ItemStack> outList = new ArrayList<>(list.size());
                        boolean changed = false;
                        for (ItemStack it : list) {
                            ItemStack out = decorate(plugin, it);
                            if (out != it) changed = true;
                            outList.add(out);
                        }
                        if (changed) {
                            PacketContainer copy = packet.shallowClone();
                            copy.getItemListModifier().write(0, outList);
                            event.setPacket(copy);
                        }
                    }
                } catch (Throwable t) {
                    // ne jamais casser l'envoi d'un inventaire à cause de l'affichage
                }
            }
        });
    }

    /** Copie de l'objet avec la ligne de prix, ou l'objet tel quel s'il ne se vend pas. */
    static ItemStack decorate(SmpShop plugin, ItemStack it) {
        if (it == null || it.getType().isAir()) return it;
        double unit = plugin.worth().unitPrice(it);
        if (unit <= 0) return it;
        ItemStack copy = it.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta == null) return it;
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.addAll(plugin.displayLines(unit, it.getAmount()));
        meta.lore(lore);
        copy.setItemMeta(meta);
        return copy;
    }
}
