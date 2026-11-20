package org.openstreetmap.josm.plugins.chainpoi;

import java.util.List;

import org.openstreetmap.josm.data.Bounds;

/**
 * Источник данных одной торговой сети.
 */
public interface ChainProvider {

    String id();

    String name();

    /**
     * Возвращает магазины сети внутри прямоугольника.
     */
    List<Store> fetch(Bounds bbox) throws Exception;
}
