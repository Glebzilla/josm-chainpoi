package org.openstreetmap.josm.plugins.chainpoi;

import java.util.List;

/** Известные файлы кэша сетей и их очистка. */
final class Caches {

    private static final List<String> FILES = List.of(
            "chainpoi-kb.json",
            "chainpoi-wb.json",
            "chainpoi-rigla.json",
            "chainpoi-yarche.json",
            "chainpoi-maria-ra.json");

    private Caches() {
    }

    static int clearAll() {
        int removed = 0;
        for (String name : FILES) {
            if (StoreCache.delete(name)) {
                removed++;
            }
        }
        return removed;
    }
}
