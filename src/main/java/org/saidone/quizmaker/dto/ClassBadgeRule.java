package org.saidone.quizmaker.dto;

import java.util.HashSet;
import java.util.List;

/** Teacher settings are snapshotted on each completed attempt. */
public record ClassBadgeRule(String id, boolean enabled, int target) {
    public static List<ClassBadgeRule> defaults() {
        return List.of(new ClassBadgeRule("FIRST_QUIZ", true, 1),
                new ClassBadgeRule("FIVE_QUIZZES", true, 5),
                new ClassBadgeRule("TEN_QUIZZES", true, 10),
                new ClassBadgeRule("THREE_DAYS", true, 3),
                new ClassBadgeRule("SEVEN_DAYS", true, 7));
    }

    public static List<ClassBadgeRule> normalize(List<ClassBadgeRule> rules) {
        if (rules == null) return defaults();
        var known = defaults().stream().map(ClassBadgeRule::id).toList();
        var seen = new HashSet<String>();
        if (rules.size() != known.size()) throw new IllegalArgumentException("Configura tutti e cinque i badge della classe.");
        for (var rule : rules) {
            if (rule == null || !known.contains(rule.id()) || !seen.add(rule.id()) || rule.target() < 1 || rule.target() > 10000)
                throw new IllegalArgumentException("Ogni badge richiede una soglia tra 1 e 10000 e un identificativo valido.");
        }
        return List.copyOf(rules);
    }
}
