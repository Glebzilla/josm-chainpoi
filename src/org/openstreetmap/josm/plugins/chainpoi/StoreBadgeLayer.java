package org.openstreetmap.josm.plugins.chainpoi;

import java.awt.Graphics2D;
import java.awt.Point;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.swing.ImageIcon;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.MapViewPaintable;

/**
 * Небольшие значки сетей поверх карты. Включается отдельной кнопкой и не
 * меняет данные OSM.
 */
final class StoreBadgeLayer implements MapViewPaintable {

    private static final int ICON_PX = 22;

    private final MapView mv;
    private final Function<String, ImageIcon> iconProvider;
    private final Map<String, ImageIcon> icons = new HashMap<>();
    private volatile List<Store> stores = List.of();

    StoreBadgeLayer(MapView mv, Function<String, ImageIcon> iconProvider) {
        this.mv = mv;
        this.iconProvider = iconProvider;
    }

    void setStores(List<Store> stores) {
        this.stores = stores == null ? List.of() : stores;
        mv.repaint();
    }

    @Override
    public void paint(Graphics2D g, MapView view, Bounds bbox) {
        for (Store store : stores) {
            LatLon pos = new LatLon(store.lat, store.lon);
            Point p = view.getPoint(pos);
            if (p == null) {
                continue;
            }
            ImageIcon icon = icons.computeIfAbsent(store.chainId, iconProvider);
            if (icon == null) {
                continue;
            }
            g.drawImage(icon.getImage(), p.x - ICON_PX / 2, p.y - ICON_PX / 2,
                    ICON_PX, ICON_PX, null);
        }
    }
}
