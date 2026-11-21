package org.openstreetmap.josm.plugins.chainpoi;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;

import javax.swing.ImageIcon;
import javax.swing.Timer;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.MapViewPaintable;

/**
 * Временный значок сети в точке, где магазин должен быть, но в OSM его нет.
 *
 * <p>Мигает с ореолом ~2,6 с, затем сам снимается с карты. Выделение в JOSM
 * при этом не меняется.
 */
final class StoreMarker implements MapViewPaintable {

    private static final int DURATION_MS = 2600;
    private static final int ICON_PX = 26;

    private final MapView mv;
    private final LatLon pos;
    private final ImageIcon icon;
    private final Color color;
    private final long start = System.currentTimeMillis();
    private final Timer timer;
    private Runnable onFinish;

    StoreMarker(MapView mv, LatLon pos, ImageIcon icon, Color color) {
        this.mv = mv;
        this.pos = pos;
        this.icon = icon;
        this.color = color;
        this.timer = new Timer(70, e -> {
            if (System.currentTimeMillis() - start >= DURATION_MS) {
                finish();
            } else {
                mv.repaint();
            }
        });
        timer.setRepeats(true);
    }

    void setOnFinish(Runnable onFinish) {
        this.onFinish = onFinish;
    }

    void start() {
        timer.start();
    }

    /** Убрать досрочно (при следующем «Показать»). */
    void cancel() {
        timer.stop();
        mv.removeTemporaryLayer(this);
    }

    private void finish() {
        cancel();
        if (onFinish != null) {
            onFinish.run();
        }
    }

    @Override
    public void paint(Graphics2D g0, MapView view, Bounds bbox) {
        double age = (System.currentTimeMillis() - start) / (double) DURATION_MS;
        if (age >= 1) {
            return;
        }
        Point p = view.getPoint(pos);
        if (p == null) {
            return;
        }
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        double pulse = 0.5 + 0.5 * Math.sin(age * Math.PI * 8);
        int radius = (int) Math.round(15 + 9 * pulse);
        int alpha = (int) Math.round(70 + 110 * (1 - age));
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.min(255, alpha)));
        g.fill(new Ellipse2D.Double(p.x - radius, p.y - radius, radius * 2.0, radius * 2.0));

        int ring = (int) Math.round(radius + 4 + 3 * pulse);
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.min(255, alpha + 40)));
        g.setStroke(new BasicStroke(2f));
        g.draw(new Ellipse2D.Double(p.x - ring, p.y - ring, ring * 2.0, ring * 2.0));

        if (icon != null) {
            g.drawImage(icon.getImage(), p.x - ICON_PX / 2, p.y - ICON_PX / 2,
                    ICON_PX, ICON_PX, null);
        }
        g.dispose();
    }

    /** Цвет ореола по сети. */
    static Color colorFor(String chainId) {
        if (chainId == null) {
            return new Color(0x1B, 0x5E, 0x9A);
        }
        switch (chainId) {
            case "magnit":
                return new Color(0xD1, 0x1E, 0x2A);
            case "magnit-kosmetik":
                return new Color(0xD6, 0x24, 0x7A);
            case "pyaterochka":
                return new Color(0x1E, 0x8A, 0x3C);
            case "ozon":
            case "ozon-box":
                return new Color(0x1B, 0x5E, 0x9A);
            case "wildberries":
                return new Color(0x8B, 0x1B, 0x6A);
            default:
                return new Color(0x1B, 0x5E, 0x9A);
        }
    }
}
