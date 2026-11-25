package org.openstreetmap.josm.plugins.chainpoi;

/** Очистка файлов кэша сетей. */
final class Caches {

    private Caches() {
    }

    static int clearAll() {
        AreaCache.clearMemory();
        return StoreCache.clearAll();
    }
}
