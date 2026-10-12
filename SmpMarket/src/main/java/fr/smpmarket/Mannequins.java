package fr.smpmarket;

import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Mannequin du locataire : quand il n'est pas dans son stand (ou déconnecté),
 * un mannequin avec SON skin tient la boutique. Clic droit dessus = catalogue.
 */
public final class Mannequins {

    private final SmpMarket plugin;
    private final Map<String, Mannequin> npcs = new HashMap<>();

    Mannequins(SmpMarket plugin) {
        this.plugin = plugin;
    }

    private boolean wanted(Stall st) {
        if (!plugin.settings().mannequins || !st.isRented() || !st.mannequin || st.npcSpot == null) return false;
        Player owner = Bukkit.getPlayer(st.owner);
        return owner == null || !st.region.contains(owner.getLocation());
    }

    /**
     * Relève le skin du locataire pendant qu'il est connecté (le serveur le connaît alors),
     * pour que son mannequin l'ait aussi quand il est déconnecté.
     */
    private void captureSkin(Stall st) {
        if (st.owner == null) return;
        Player p = Bukkit.getPlayer(st.owner);
        if (p == null) return;
        for (ProfileProperty pp : p.getPlayerProfile().getProperties()) {
            if (!pp.getName().equals("textures")) continue;
            if (pp.getValue().equals(st.skinValue)) return;
            st.skinValue = pp.getValue();
            st.skinSignature = pp.getSignature();
            plugin.store().markDirty();
            Mannequin m = npcs.remove(st.id);
            if (m != null) m.remove();
            return;
        }
    }

    /** Toutes les 2 secondes. */
    public void tick() {
        for (Stall st : plugin.store().stalls()) {
            if (st.isRented()) captureSkin(st);
            update(st);
        }
        npcs.keySet().removeIf(id -> plugin.store().stall(id) == null && kill(id));
    }

    private boolean kill(String id) {
        Mannequin m = npcs.get(id);
        if (m != null) m.remove();
        return true;
    }

    private void update(Stall st) {
        Mannequin m = npcs.get(st.id);
        if (!wanted(st)) {
            if (m != null) {
                m.remove();
                npcs.remove(st.id);
            }
            return;
        }
        if (m != null && m.isValid()) return;
        World w = st.region.bukkitWorld();
        if (w == null) return;
        Location l = new Location(w, st.npcSpot[0], st.npcSpot[1], st.npcSpot[2], (float) st.npcSpot[3], 0);
        if (!w.isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) return;
        Mannequin spawned = w.spawn(l, Mannequin.class, e -> {
            e.setPersistent(false);
            var profile = ResolvableProfile.resolvableProfile().uuid(st.owner).name(st.ownerName);
            if (st.skinValue != null) profile.addProperty(new ProfileProperty("textures", st.skinValue, st.skinSignature));
            e.setProfile(profile.build());
            e.setImmovable(true);
            e.setInvulnerable(true);
            e.setGravity(false);
            e.setSilent(true);
            e.customName(plugin.styles().styled(st.theme, st.displayName(), true));
            e.setCustomNameVisible(true);
            e.setDescription(plugin.texts().get("mannequin.description"));
            e.getEquipment().setItemInMainHand(new ItemStack(Material.EMERALD));
            plugin.visuals().tagTarget(e, st.id);
        });
        npcs.put(st.id, spawned);
    }

    /** Le locataire a changé quelque chose (place, activé / désactivé). */
    public void refresh(Stall st) {
        captureSkin(st);
        Mannequin m = npcs.remove(st.id);
        if (m != null) m.remove();
        update(st);
    }

    public void removeAll() {
        for (Mannequin m : npcs.values()) m.remove();
        npcs.clear();
    }
}
