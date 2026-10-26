package fr.smpevents.quest;

import org.bukkit.Material;

import java.util.List;
import java.util.Set;

/** Une quête du fichier quests.yml. */
public record QuestDef(String id, boolean weekly, String tier, QuestType type, Set<String> targets, long amount,
                       double reward, List<String> commands, Material icon, String name, String event) {

    public boolean accepts(String target) {
        return targets.isEmpty() || (target != null && targets.contains(target));
    }
}
