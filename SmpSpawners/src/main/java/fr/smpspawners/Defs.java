package fr.smpspawners;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.List;

/** Raretés, mobs et butins lus dans la config. */
final class Defs {

    private Defs() {
    }

    record Rarity(String id, String name, String color, double weight, int interval) {
        String label() {
            return color + name;
        }
    }

    record Drop(Material mat, int min, int max, double chance) {

        /** "IRON_INGOT 1-3 25%" */
        static Drop parse(String s) {
            String[] p = s.trim().split("\\s+");
            if (p.length == 0) return null;
            Material m = Material.matchMaterial(p[0]);
            if (m == null || !m.isItem()) return null;
            int min = 1, max = 1;
            double chance = 1;
            if (p.length >= 2) {
                String[] r = p[1].split("-");
                try {
                    min = Integer.parseInt(r[0]);
                    max = r.length > 1 ? Integer.parseInt(r[1]) : min;
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            if (p.length >= 3) {
                try {
                    chance = Double.parseDouble(p[2].replace("%", "").replace(',', '.')) / 100.0;
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return new Drop(m, Math.max(0, min), Math.max(min, max), Math.max(0, Math.min(1, chance)));
        }

        /** Moyenne par kill (pour l'affichage). */
        double average() {
            return chance * (min + max) / 2.0;
        }
    }

    record Mob(EntityType type, Rarity rarity, int xp, int interval, List<Drop> drops) {
        String name() {
            return Names.mob(type);
        }

        Material egg() {
            Material m = Material.matchMaterial(type.name() + "_SPAWN_EGG");
            return m == null ? Material.SPAWNER : m;
        }
    }
}
