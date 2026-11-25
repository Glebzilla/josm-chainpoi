package org.openstreetmap.josm.plugins.chainpoi;

import java.util.Locale;
import java.util.Map;

/** Преобразование названий топлива сети в теги OSM. */
final class FuelTags {

    private FuelTags() {
    }

    static void add(Map<String, String> tags, String fuel) {
        if (fuel == null) {
            return;
        }
        String s = fuel.toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replace(" ", "");
        if (s.contains("100")) {
            tags.put("fuel:octane_100", "yes");
        } else if (s.contains("98")) {
            tags.put("fuel:octane_98", "yes");
        } else if (s.contains("95")) {
            tags.put("fuel:octane_95", "yes");
        } else if (s.contains("93")) {
            tags.put("fuel:octane_93", "yes");
        } else if (s.contains("92")) {
            tags.put("fuel:octane_92", "yes");
        } else if (s.contains("89")) {
            tags.put("fuel:octane_89", "yes");
        } else if (s.contains("87")) {
            tags.put("fuel:octane_87", "yes");
        } else if (s.contains("80")) {
            tags.put("fuel:octane_80", "yes");
        }
        if (s.contains("дизел") || s.contains("дт") || s.contains("diesel")) {
            tags.put("fuel:diesel", "yes");
        }
        if (s.contains("метан") || s.contains("кпг") || s.contains("cng")) {
            tags.put("fuel:cng", "yes");
        } else if (s.contains("спг") || s.contains("lng")) {
            tags.put("fuel:lng", "yes");
        } else if (s.contains("газ") || s.contains("пропан") || s.contains("суг")
                || s.contains("gpl") || s.contains("lpg")) {
            tags.put("fuel:lpg", "yes");
        }
        if (s.contains("e85")) {
            tags.put("fuel:e85", "yes");
        }
        if (s.contains("керосин") || s.contains("kerosene")) {
            tags.put("fuel:kerosene", "yes");
        }
    }

    static void addAll(Map<String, String> tags, Iterable<String> fuels) {
        for (String fuel : fuels) {
            add(tags, fuel);
        }
    }
}
