package org.openstreetmap.josm.plugins.chainpoi;

import java.awt.AWTEvent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.List;

import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;

/**
 * «Цветок» действий вокруг курсора: зажать ПКМ, повести к лепестку, отпустить.
 *
 * <p>Окно без фокуса и без рамки. Пока оно открыто, события мыши и Esc ловятся
 * глобальным {@link AWTEventListener}, поэтому отпускание кнопки срабатывает
 * даже если курсор вышел за пределы круга.
 */
public final class PieMenu extends JWindow {

    /** Что нарисовать на лепестке. */
    public enum Kind { ACCEPT, SKIP, SHOW, SELECT, PANO }

    /** Один лепесток: подпись, иконка, доступность и действие. */
    public static final class Item {
        public final String label;
        public final String shortLabel;
        public final Kind kind;
        public final boolean enabled;
        public final Runnable action;

        public Item(String label, String shortLabel, Kind kind, boolean enabled, Runnable action) {
            this.label = label;
            this.shortLabel = shortLabel;
            this.kind = kind;
            this.enabled = enabled;
            this.action = action;
        }
    }

    private static final int OUTER = 104;
    private static final int INNER = 40;
    private static final int PAD = 10;
    private static final double GAP = Math.toRadians(4);

    /** После закрытия цветка гасим «хвостовое» отпускание для обычного меню. */
    private static volatile long suppressUntil;
    private static PieMenu active;

    private final List<Item> items;
    private final JPanel canvas = new JPanel() {
        @Override
        protected void paintComponent(Graphics g) {
            paintPie((Graphics2D) g);
        }
    };
    private int hover = -1;

    private final AWTEventListener global = event -> {
        if (event instanceof MouseEvent) {
            MouseEvent me = (MouseEvent) event;
            if (me.getID() == MouseEvent.MOUSE_MOVED
                    || me.getID() == MouseEvent.MOUSE_DRAGGED) {
                updateHover(me.getLocationOnScreen());
            } else if (me.getID() == MouseEvent.MOUSE_RELEASED
                    && me.getButton() == MouseEvent.BUTTON3) {
                finishAt(me.getLocationOnScreen());
            }
        } else if (event instanceof KeyEvent) {
            KeyEvent ke = (KeyEvent) event;
            if (ke.getID() == KeyEvent.KEY_PRESSED && ke.getKeyCode() == KeyEvent.VK_ESCAPE) {
                close();
            }
        }
    };

    private PieMenu(Window owner, List<Item> items) {
        super(owner);
        this.items = items;
        setFocusableWindowState(false);
        setAutoRequestFocus(false);
        setAlwaysOnTop(true);
        try {
            setBackground(new Color(0, 0, 0, 0));
        } catch (RuntimeException ex) {
            setBackground(new Color(28, 32, 38));
        }
        canvas.setOpaque(false);
        int size = 2 * (OUTER + PAD);
        canvas.setPreferredSize(new java.awt.Dimension(size, size));
        setContentPane(canvas);
        pack();
    }

    /** Показать цветок с центром в экранной точке. Возвращает false, если не вышло. */
    public static boolean showRadial(Window owner, List<Item> items, Point at) {
        if (items == null || items.isEmpty()) {
            return false;
        }
        if (active != null) {
            active.close();
        }
        try {
            PieMenu menu = new PieMenu(owner, items);
            Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds();
            int size = menu.getWidth();
            int x = at.x - size / 2;
            int y = at.y - size / 2;
            x = Math.max(screen.x, Math.min(x, screen.x + screen.width - size));
            y = Math.max(screen.y, Math.min(y, screen.y + screen.height - size));
            menu.setLocation(x, y);
            Toolkit.getDefaultToolkit().addAWTEventListener(menu.global,
                    AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK
                            | AWTEvent.KEY_EVENT_MASK);
            menu.setVisible(true);
            menu.toFront();
            active = menu;
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Новое нажатие ПКМ снимает подавление предыдущего цветка. */
    public static void resetSuppress() {
        suppressUntil = 0;
    }

    /** true, если отпускание кнопки уже обработал цветок. */
    public static boolean suppressed() {
        return System.currentTimeMillis() < suppressUntil;
    }

    private void close() {
        if (active == this) {
            active = null;
        }
        try {
            Toolkit.getDefaultToolkit().removeAWTEventListener(global);
        } catch (RuntimeException ex) {
            // слушателя уже нет
        }
        suppressUntil = System.currentTimeMillis() + 300;
        setVisible(false);
        dispose();
    }

    private void updateHover(Point screen) {
        if (!isShowing()) {
            return;
        }
        Point p = new Point(screen.x - getX(), screen.y - getY());
        int h = indexAt(p);
        if (h != hover) {
            hover = h;
            canvas.repaint();
        }
    }

    private void finishAt(Point screen) {
        if (!isShowing()) {
            return;
        }
        Point p = new Point(screen.x - getX(), screen.y - getY());
        double dx = p.x - getWidth() / 2.0;
        double dy = p.y - getHeight() / 2.0;
        double d = Math.hypot(dx, dy);
        int idx = (d >= INNER && d <= OUTER + 12) ? indexAt(p) : -1;
        Item chosen = idx >= 0 && items.get(idx).enabled ? items.get(idx) : null;
        close();
        if (chosen != null) {
            SwingUtilities.invokeLater(chosen.action);
        }
    }

    /** Индекс лепестка по точке внутри окна; -1 — центр или мимо. */
    private int indexAt(Point p) {
        double dx = p.x - getWidth() / 2.0;
        double dy = p.y - getHeight() / 2.0;
        double d = Math.hypot(dx, dy);
        if (d < INNER || d > OUTER + 20) {
            return -1;
        }
        int n = items.size();
        double deg = Math.toDegrees(Math.atan2(dy, dx));
        int idx = (int) Math.round((deg + 90) / (360.0 / n));
        return ((idx % n) + n) % n;
    }

    // ------------------------------------------------------------------
    // рисование
    // ------------------------------------------------------------------

    private void paintPie(Graphics2D g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int cx = getWidth() / 2;
        int cy = getHeight() / 2;

        g.setColor(new Color(18, 21, 26, 130));
        g.fillOval(cx - OUTER - 8, cy - OUTER - 8, 2 * (OUTER + 8), 2 * (OUTER + 8));

        int n = items.size();
        double step = 2 * Math.PI / n;
        for (int i = 0; i < n; i++) {
            Item item = items.get(i);
            double mid = -Math.PI / 2 + i * step;
            Path2D sector = sector(cx, cy, INNER + 4, OUTER,
                    mid - step / 2 + GAP, mid + step / 2 - GAP);
            boolean hot = i == hover;
            Color fill;
            if (!item.enabled) {
                fill = new Color(90, 96, 104, 150);
            } else if (hot) {
                fill = new Color(32, 105, 180, 240);
            } else {
                fill = new Color(42, 48, 57, 220);
            }
            g.setColor(fill);
            g.fill(sector);
            g.setColor(hot ? new Color(170, 212, 255) : new Color(255, 255, 255, 70));
            g.setStroke(new BasicStroke(hot ? 2f : 1f));
            g.draw(sector);

            drawIcon(g, item.kind, (int) Math.round(cx + Math.cos(mid) * 70),
                    (int) Math.round(cy + Math.sin(mid) * 70), item.enabled);
        }

        g.setColor(new Color(24, 28, 34, 250));
        g.fillOval(cx - INNER, cy - INNER, 2 * INNER, 2 * INNER);
        g.setColor(new Color(255, 255, 255, 90));
        g.drawOval(cx - INNER, cy - INNER, 2 * INNER, 2 * INNER);

        String center = hover >= 0 ? items.get(hover).shortLabel : "Отмена";
        g.setFont(getFont().deriveFont(Font.BOLD, 11f));
        drawCentered(g, center, cx, cy, new Color(225, 230, 240));
        g.dispose();
    }

    private static Path2D sector(int cx, int cy, double r1, double r2, double a0, double a1) {
        Path2D p = new Path2D.Double();
        int steps = 20;
        for (int i = 0; i <= steps; i++) {
            double a = a0 + (a1 - a0) * i / steps;
            double x = cx + Math.cos(a) * r2;
            double y = cy + Math.sin(a) * r2;
            if (i == 0) {
                p.moveTo(x, y);
            } else {
                p.lineTo(x, y);
            }
        }
        for (int i = steps; i >= 0; i--) {
            double a = a0 + (a1 - a0) * i / steps;
            p.lineTo(cx + Math.cos(a) * r1, cy + Math.sin(a) * r1);
        }
        p.closePath();
        return p;
    }

    private static void drawCentered(Graphics2D g, String text, double x, double y, Color color) {
        java.awt.FontMetrics fm = g.getFontMetrics();
        g.setColor(color);
        g.drawString(text, (float) (x - fm.stringWidth(text) / 2.0),
                (float) (y + (fm.getAscent() - fm.getDescent()) / 2.0));
    }

    private static void drawIcon(Graphics2D g0, Kind kind, int x, int y, boolean enabled) {
        Graphics2D g = (Graphics2D) g0.create();
        g.translate(x, y);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Color c = enabled ? Color.WHITE : new Color(210, 210, 210, 110);
        g.setColor(c);
        switch (kind) {
            case ACCEPT:
                if (enabled) {
                    g.setColor(new Color(120, 225, 140));
                }
                g.drawPolyline(new int[] {-7, -1, 8}, new int[] {0, 7, -7}, 3);
                break;
            case SKIP:
                g.drawLine(-7, -7, -7, 7);
                g.drawLine(-3, 0, 6, 0);
                g.drawLine(2, -5, 7, 0);
                g.drawLine(2, 5, 7, 0);
                break;
            case SHOW:
                g.drawOval(-7, -7, 14, 14);
                g.drawLine(-10, 0, 10, 0);
                g.drawLine(0, -10, 0, 10);
                break;
            case SELECT:
                Path2D arrow = new Path2D.Double();
                arrow.moveTo(-6, -9);
                arrow.lineTo(7, 2);
                arrow.lineTo(0, 3);
                arrow.lineTo(-3, 9);
                arrow.closePath();
                g.fill(arrow);
                break;
            case PANO:
                g.drawRect(-9, -7, 18, 14);
                g.drawOval(-3, -3, 6, 6);
                g.drawLine(-8, 6, -2, 0);
                g.drawLine(-3, 3, 4, 7);
                break;
            default:
                break;
        }
        g.dispose();
    }
}
