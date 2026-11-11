package com.tradepanel;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

import com.motivewave.platform.sdk.common.DrawContext;
import com.motivewave.platform.sdk.common.Enums$ResizeType;
import com.motivewave.platform.sdk.draw.Figure;
import com.motivewave.platform.sdk.draw.ResizePoint;

/**
 * Una línea del gráfico al estilo DeepCharts:
 *
 *   [⋮ Subm | -13 LMT | ⚯C | -4056,00 $] [SL] [TP] [X] ─────────────── [X] [29777,50]
 *
 * La etiqueta es el tirador de arrastre (un {@link ResizePoint} cuya zona activa es la etiqueta),
 * los cuadros pequeños son botones (los resuelve {@link #hit}) y la línea llega hasta el eje.
 */
final class LineFigure extends Figure {

    static final Font FONT = new Font("Segoe UI", Font.PLAIN, 11);
    private static final FontMetrics FM;
    static {
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        FM = g.getFontMetrics(FONT);
        g.dispose();
    }

    private static final int H = 17;        // alto de etiquetas y botones
    private static final int PAD = 5;
    private static final int GAP = 4;
    private static final int GRIP = 9;      // franja del tirador a la izquierda de la etiqueta
    private static final int LINK_W = 13;   // ancho del icono de eslabón

    /** Tirador: MotiveWave gestiona el arrastre vertical y avisa a la estrategia (onResize). */
    final class Grip extends ResizePoint {
        Grip() {
            super(Enums$ResizeType.VERTICAL, true);
            setSnapToLocation(false);
        }
        LineFigure line() { return LineFigure.this; }
        @Override public void layout(DrawContext dc) {
            super.layout(dc);
            Rectangle r = label;
            if (r != null) setBounds(new Rectangle2D.Double(r.x, r.y, r.width, r.height));
        }
        @Override public void draw(Graphics2D gc, DrawContext dc) { /* el tirador es la propia etiqueta */ }
    }

    /**
     * Tirador sobre el botón SL o TP: pulsar y arrastrar el botón saca una línea de vista previa
     * y al soltar se coloca ahí la salida (un clic simple la deja armada para el siguiente clic).
     */
    final class BtnGrip extends ResizePoint {
        final String btn;
        BtnGrip(String btn) {
            super(Enums$ResizeType.VERTICAL, true);
            this.btn = btn;
            setSnapToLocation(false);
        }
        LineFigure line() { return LineFigure.this; }
        @Override public void layout(DrawContext dc) {
            super.layout(dc);
            Rectangle r = btnRect(btn);
            if (r != null) setBounds(new Rectangle2D.Double(r.x, r.y, r.width, r.height));
            else setBounds(new Rectangle2D.Double(-10, -10, 0, 0));
        }
        @Override public void draw(Graphics2D gc, DrawContext dc) { /* el tirador es el propio botón */ }
    }

    final Grip grip = new Grip();
    final BtnGrip slGrip = new BtnGrip("SL"), tpGrip = new BtnGrip("TP");
    /** Botón "#N" de un grupo de salidas: arrastrarlo saca la última salida que se unió. */
    final BtnGrip splitGrip = new BtnGrip("#");
    volatile boolean dragging;
    volatile Float dragPrice;      // precio mientras se arrastra (y hasta que el motor confirma)
    volatile int labelOffset = 400; // distancia de la etiqueta al borde derecho

    private volatile LineModel m;
    private volatile Rectangle label;
    private volatile Rectangle[] btnRects = new Rectangle[0];
    private volatile Rectangle rightX;
    private volatile Rectangle tag;
    private volatile int y;
    private volatile String priceText = "";

    void set(LineModel model) {
        LineModel old = m;
        m = model;
        // el motor ya ha respondido al arrastre: su precio manda (también si rechazó el movimiento)
        if (!dragging && (old == null || old != model)) dragPrice = null;
    }

    LineModel model() { return m; }
    String id() { LineModel mm = m; return mm == null ? "" : mm.id; }

    float price() {
        Float d = dragPrice;
        LineModel mm = m;
        return d != null ? d : mm == null ? Float.NaN : mm.price;
    }

    /** Botón bajo el punto ("SL", "TP", "TS", "X") o null. */
    String hit(Point p) {
        LineModel mm = m;
        if (mm == null) return null;
        Rectangle[] rs = btnRects;
        for (int i = 0; i < rs.length && i < mm.buttons.size(); i++) if (rs[i] != null && rs[i].contains(p)) return mm.buttons.get(i).text;
        Rectangle rx = rightX;
        if (rx != null && rx.contains(p)) return "X";
        return null;
    }

    /** Rectángulo del botón con ese texto, o null si la línea no lo tiene. */
    Rectangle btnRect(String text) {
        LineModel mm = m;
        if (mm == null) return null;
        Rectangle[] rs = btnRects;
        for (int i = 0; i < rs.length && i < mm.buttons.size(); i++) if (same(mm.buttons.get(i).text, text)) return rs[i];
        return null;
    }

    boolean hasBtn(String text) {
        LineModel mm = m;
        if (mm == null) return false;
        for (LineModel.Btn b : mm.buttons) if (same(b.text, text)) return true;
        return false;
    }

    /** "#" vale para cualquier "#N". */
    private static boolean same(String btn, String text) {
        return btn.equals(text) || ("#".equals(text) && btn.startsWith("#"));
    }

    boolean onLabel(double px, double py) {
        Rectangle r = label;
        return r != null && r.contains(px, py);
    }

    @Override public boolean isVisible(DrawContext dc) { return m != null; }

    @Override public boolean contains(double px, double py, DrawContext dc) {
        if (onLabel(px, py)) return true;
        for (Rectangle r : btnRects) if (r != null && r.contains(px, py)) return true;
        Rectangle rx = rightX;
        return rx != null && rx.contains(px, py);
    }

    @Override public void layout(DrawContext dc) {
        compute(dc, FM);
        Rectangle b = dc.getBounds();
        Rectangle l = label;
        if (l == null) return;
        setBounds(new Rectangle2D.Double(l.x, y - H, b.x + b.width - l.x, 2 * H));
        LineModel mm = m;
        if (mm == null || dragging) return;
        try {
            if (mm.draggable) grip.setLocation(dc.translate2Time(l.x + GRIP / 2.0), price());
            for (BtnGrip g : new BtnGrip[]{slGrip, tpGrip, splitGrip}) {
                Rectangle r = btnRect(g.btn);
                if (r != null) g.setLocation(dc.translate2Time(r.x + r.width / 2.0), price());
            }
        } catch (RuntimeException ignore) { }
    }

    /** Calcula la geometría (etiqueta, botones, X derecha y etiqueta de precio). */
    private void compute(DrawContext dc, FontMetrics fm) {
        LineModel mm = m;
        if (mm == null) { label = null; return; }
        Rectangle b = dc.getBounds();
        float p = price();
        y = dc.translateValue(p);
        priceText = dc.format(p);
        int x = Math.max(b.x + 6, b.x + b.width - labelOffset);
        int top = y - H / 2;

        int w = (mm.draggable ? GRIP : PAD) + badgeW(mm, fm) + fm.stringWidth(mm.text) + PAD;
        if (mm.linked) w += fm.stringWidth(" | ") + LINK_W + fm.stringWidth("C");
        if (mm.money != null) w += fm.stringWidth(" | " + mm.money);
        label = new Rectangle(x, top, w, H);

        int bx = x + w + GAP;
        Rectangle[] rs = new Rectangle[mm.buttons.size()];
        for (int i = 0; i < rs.length; i++) {
            int bw = Math.max(H, fm.stringWidth(mm.buttons.get(i).text) + 8);
            rs[i] = new Rectangle(bx, top, bw, H);
            bx += bw + GAP;
        }
        btnRects = rs;

        int tw = fm.stringWidth(priceText) + 2 * PAD;
        int right = b.x + b.width;
        tag = new Rectangle(right - tw, top, tw, H);
        rightX = mm.closeAtRight ? new Rectangle(right - tw - 2 - H, top, H, H) : null;
    }

    @Override public void draw(Graphics2D gc, DrawContext dc) {
        LineModel mm = m;
        if (mm == null) return;
        Rectangle b = dc.getBounds();
        Graphics2D g = (Graphics2D) gc.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(FONT);
            FontMetrics fm = g.getFontMetrics();
            compute(dc, fm);
            Rectangle l = label;
            if (l == null || y < b.y - H || y > b.y + b.height + H) return;
            Color col = mm.color;
            int base = l.y + (H + fm.getAscent() - fm.getDescent()) / 2;

            // línea desde el último cuadro hasta el eje
            Rectangle[] rs = btnRects;
            int from = rs.length > 0 ? rs[rs.length - 1].x + rs[rs.length - 1].width : l.x + l.width;
            Rectangle rx = rightX, tg = tag;
            int to = rx != null ? rx.x : tg.x;
            g.setColor(col);
            g.setStroke(mm.dotted
                ? new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{2f, 3f}, 0f)
                : new BasicStroke(1.6f));
            if (to > from) g.drawLine(from, y, to, y);
            g.setStroke(new BasicStroke(1f));

            // etiqueta
            g.fillRect(l.x, l.y, l.width, l.height);
            int tx = l.x + PAD;
            if (mm.draggable) {
                g.setColor(new Color(255, 255, 255, 150));
                for (int i = -1; i <= 1; i++) g.fillRect(l.x + 3, y + i * 4 - 1, 2, 2);
                tx = l.x + GRIP;
            }
            if (mm.badge != null) {
                // número de la entrada: cuadro blanco con el número en el color de la línea
                int bw = badgeW(mm, fm) - BADGE_GAP;
                g.setColor(Color.WHITE);
                g.fillRoundRect(tx, l.y + 2, bw, H - 4, 5, 5);
                g.setColor(col);
                g.setFont(BADGE_FONT);
                FontMetrics bf = g.getFontMetrics();
                g.drawString(mm.badge, tx + (bw - bf.stringWidth(mm.badge)) / 2, base);
                g.setFont(FONT);
                tx += bw + BADGE_GAP;
            }
            g.setColor(Color.WHITE);
            g.drawString(mm.text, tx, base);
            tx += fm.stringWidth(mm.text);
            if (mm.linked) {
                g.drawString(" | ", tx, base);
                tx += fm.stringWidth(" | ");
                drawLink(g, tx, y);
                tx += LINK_W;
                g.drawString("C", tx, base);
                tx += fm.stringWidth("C");
            }
            if (mm.money != null) g.drawString(" | " + mm.money, tx, base);

            // botones
            for (int i = 0; i < rs.length; i++) {
                LineModel.Btn bt = mm.buttons.get(i);
                drawBox(g, fm, rs[i], bt.text, col, bt.active, base);
            }
            if (rx != null) drawBox(g, fm, rx, "X", col, false, base);

            // etiqueta de precio pegada al eje
            g.setColor(col);
            g.fillRect(tg.x, tg.y, tg.width, tg.height);
            g.setColor(Color.WHITE);
            g.drawString(priceText, tg.x + PAD, base);
        } finally {
            g.dispose();
        }
    }

    private static final Font BADGE_FONT = FONT.deriveFont(Font.BOLD);
    private static final int BADGE_GAP = 5;

    /** Ancho de la insignia con el número (más su separación), 0 si no tiene. */
    private static int badgeW(LineModel mm, FontMetrics fm) {
        if (mm.badge == null) return 0;
        return Math.max(H - 2, fm.stringWidth(mm.badge) + 9) + BADGE_GAP;
    }

    private static void drawBox(Graphics2D g, FontMetrics fm, Rectangle r, String text, Color col, boolean inverted, int base) {
        g.setColor(inverted ? Color.WHITE : col);
        g.fillRect(r.x, r.y, r.width, r.height);
        if (inverted) { g.setColor(col); g.drawRect(r.x, r.y, r.width - 1, r.height - 1); }
        g.setColor(inverted ? col : Color.WHITE);
        g.drawString(text, r.x + (r.width - fm.stringWidth(text)) / 2, base);
    }

    /** Icono de eslabón (orden enlazada / OCO). */
    private static void drawLink(Graphics2D g, int x, int cy) {
        Graphics2D k = (Graphics2D) g.create();
        try {
            k.setStroke(new BasicStroke(1.3f));
            k.rotate(Math.toRadians(-35), x + LINK_W / 2.0, cy);
            k.drawRoundRect(x, cy - 2, 7, 4, 4, 4);
            k.drawRoundRect(x + 5, cy - 2, 7, 4, 4, 4);
        } finally {
            k.dispose();
        }
    }
}
