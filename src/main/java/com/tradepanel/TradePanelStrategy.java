package com.tradepanel;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.motivewave.platform.sdk.common.DataContext;
import com.motivewave.platform.sdk.common.Defaults;
import com.motivewave.platform.sdk.common.DrawContext;
import com.motivewave.platform.sdk.common.Instrument;
import com.motivewave.platform.sdk.common.NVP;
import com.motivewave.platform.sdk.common.Tick;
import com.motivewave.platform.sdk.common.TickOperation;
import com.motivewave.platform.sdk.common.desc.BooleanDescriptor;
import com.motivewave.platform.sdk.common.desc.DiscreteDescriptor;
import com.motivewave.platform.sdk.common.desc.IntegerDescriptor;
import com.motivewave.platform.sdk.common.desc.SettingGroup;
import com.motivewave.platform.sdk.common.desc.SettingTab;
import com.motivewave.platform.sdk.common.desc.SettingsDescriptor;
import com.motivewave.platform.sdk.common.menu.MenuDescriptor;
import com.motivewave.platform.sdk.common.menu.MenuItem;
import com.motivewave.platform.sdk.common.menu.MenuSeparator;
import com.motivewave.platform.sdk.draw.Figure;
import com.motivewave.platform.sdk.draw.ResizePoint;
import com.motivewave.platform.sdk.order_mgmt.Order;
import com.motivewave.platform.sdk.order_mgmt.OrderContext;
import com.motivewave.platform.sdk.study.Study;
import com.motivewave.platform.sdk.study.StudyHeader;

/**
 * Trade Panel estilo DeepCharts para MotiveWave (estrategia manual).
 *
 * Esta clase solo es el enlace con el SDK: traduce los eventos de MotiveWave (órdenes, clics,
 * arrastres, ticks) en llamadas al {@link TradeEngine}, y pinta en el gráfico las líneas que el
 * motor publica. La ventana del panel es {@link PanelWindow}.
 *
 * Uso: añade la estrategia al gráfico y pulsa Activate. Aparece el panel flotante; el botón
 * "PANEL" de la esquina superior derecha del gráfico lo muestra u oculta.
 */
@StudyHeader(
    namespace = "com.tradepanel",
    id = "TradePanelStrategy",
    name = "Trade Panel (DeepCharts)",
    desc = "Panel de trading al estilo DeepCharts: MKT/BID/ASK/LMT/STP/STP LMT, OCO SL/TP, modo Risk, breakeven, trailing y gestión de órdenes desde el gráfico.",
    menu = "MotiveWave Custom",
    overlay = true,
    strategy = true,
    manualEntry = true,
    autoEntry = false,
    supportsUseAccountPosition = true,
    supportsPosition = false,
    supportsPositionType = false,
    supportsEntryPrice = false,
    supportsTradeLots = false,
    supportsSessions = false,
    showTradeOptions = true,
    supportsUnrealizedPL = true,
    supportsRealizedPL = true,
    supportsTotalPL = true
)
public class TradePanelStrategy extends Study implements TradeEngine.Host {

    private static final String BE_OFFSET     = "beOffset";
    private static final String STPLMT_OFFSET = "stpLmtOffset";
    private static final String MAX_PER_SEC   = "maxPerSec";
    private static final String ENTRY_GTC     = "entryGtc";
    private static final String MOD_CLICK     = "modClick";
    private static final String LABEL_OFFSET  = "labelOffset";
    private static final String ON_TOP        = "alwaysOnTop";
    private static final String LANG          = "language";
    private static final String POS_R         = "posShowR";
    private static final String POS_R_BASE    = "posRBase";
    private static final String POS_RISK      = "posShowRisk";
    private static final String POS_UNIT      = "posRiskUnit";
    private static final String EXIT_AMT      = "exitShowAmount";
    private static final String EXIT_UNIT     = "exitAmountUnit";
    private static final String EXIT_R        = "exitShowR";

    private TradeEngine engine = new TradeEngine();
    private volatile DrawContext lastDraw;
    private volatile long noticeRedrawAt;      // redibujado ya programado para quitar el error del gráfico

    /** Un hilo para todas las instancias: redibuja cuando caduca el error mostrado en el gráfico. */
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tradepanel-notice");
        t.setDaemon(true);
        return t;
    });

    // MotiveWave clona los estudios (copia superficial): el estado de dibujo se etiqueta con su
    // dueño para que cada instancia tenga el suyo aunque el clon herede las referencias.
    private Object figsOwner;
    private Map<String, LineFigure> figs;
    private Hud hud;
    private ClickCatcher catcher;

    private Instrument tickInstrument;
    private TickOperation tickListener;

    // ------------------------------------------------------------------
    //  ajustes
    // ------------------------------------------------------------------

    @Override
    public void initialize(Defaults defaults) {
        T.load();                                       // los textos de los ajustes salen ya en el último idioma elegido
        SettingsDescriptor sd = new SettingsDescriptor();
        SettingTab tab = new SettingTab("Trade Panel");
        sd.addTab(tab);

        SettingGroup g1 = new SettingGroup(T.t("Órdenes"));
        g1.addRow(new IntegerDescriptor(BE_OFFSET, T.t("Punto de equilibrio: ticks a favor desde la entrada"), 0, 0, 500, 1));
        g1.addRow(new IntegerDescriptor(STPLMT_OFFSET, T.t("STP LMT: ticks del límite más allá del stop"), 2, 0, 500, 1));
        g1.addRow(new BooleanDescriptor(ENTRY_GTC, T.t("Entradas GTC (si no, DAY)"), false));
        tab.addGroup(g1);

        SettingGroup g2 = new SettingGroup(T.t("Protecciones"));
        g2.addRow(new IntegerDescriptor(MAX_PER_SEC, T.t("Máx. órdenes de entrada por segundo"), 5, 1, 50, 1));
        g2.addRow(new BooleanDescriptor(MOD_CLICK, T.t("Clic directo en el gráfico: Ctrl = comprar, Shift = vender, +Alt = a mercado"), false));
        tab.addGroup(g2);

        SettingGroup g3 = new SettingGroup(T.t("Aspecto"));
        g3.addRow(new DiscreteDescriptor(LANG, "Idioma / Language", "en",
            List.of(new NVP("Español", "es"), new NVP("English", "en"))));
        g3.addRow(new IntegerDescriptor(LABEL_OFFSET, T.t("Etiquetas: distancia al borde derecho (px)"), 400, 120, 2000, 10));
        g3.addRow(new BooleanDescriptor(ON_TOP, T.t("Panel siempre encima de todas las ventanas"), false));
        tab.addGroup(g3);


        // etiquetas: por defecto como en DeepCharts (lo nuevo, desactivado)
        SettingGroup g4 = new SettingGroup(T.t("Línea de posición"));
        g4.addRow(new BooleanDescriptor(POS_R, T.t("Mostrar R"), false));
        g4.addRow(new DiscreteDescriptor(POS_R_BASE, T.t("R sobre"), "initial",
            List.of(new NVP(T.t("Riesgo inicial"), "initial"), new NVP(T.t("Riesgo actual"), "current"))));
        g4.addRow(new BooleanDescriptor(POS_RISK, T.t("Mostrar riesgo"), false));
        g4.addRow(new DiscreteDescriptor(POS_UNIT, T.t("Riesgo en"), "MONEY", units()));
        tab.addGroup(g4);

        SettingGroup g5 = new SettingGroup(T.t("Etiquetas de SL/TP"));
        g5.addRow(new BooleanDescriptor(EXIT_AMT, T.t("Mostrar importe"), true));
        g5.addRow(new DiscreteDescriptor(EXIT_UNIT, T.t("Importe en"), "MONEY", units()));
        g5.addRow(new BooleanDescriptor(EXIT_R, T.t("Mostrar R"), false));
        tab.addGroup(g5);

        setSettingsDescriptor(sd);
    }

    private static List<NVP> units() {
        return List.of(new NVP(T.t("Dinero"), "MONEY"), new NVP(T.t("% saldo inicial"), "PCT_INI"), new NVP(T.t("% saldo actual"), "PCT_CUR"),
            new NVP("Ticks", "TICKS"), new NVP(T.t("Puntos"), "POINTS"));
    }

    private Config.Unit unit(String key) {
        try { return Config.Unit.valueOf(getSettings().getString(key, "MONEY")); }
        catch (RuntimeException e) { return Config.Unit.MONEY; }
    }

    private void applyOptions(TradeEngine e) {
        e.optBeOffset = getSettings().getInteger(BE_OFFSET, 0);
        e.optStpLmtOffset = getSettings().getInteger(STPLMT_OFFSET, 2);
        e.optMaxPerSec = getSettings().getInteger(MAX_PER_SEC, 5);
        e.optEntryGtc = getSettings().getBoolean(ENTRY_GTC, false);
        e.optModClick = getSettings().getBoolean(MOD_CLICK, false);
        e.optPosR = getSettings().getBoolean(POS_R, false);
        e.optPosRCurrent = "current".equals(getSettings().getString(POS_R_BASE, "initial"));
        e.optPosRisk = getSettings().getBoolean(POS_RISK, false);
        e.optPosUnit = unit(POS_UNIT);
        e.optExitAmt = getSettings().getBoolean(EXIT_AMT, true);
        e.optExitUnit = unit(EXIT_UNIT);
        e.optExitR = getSettings().getBoolean(EXIT_R, false);
        if (T.set("en".equals(getSettings().getString(LANG, "en")))) PanelWindow.relocalize();
        e.refresh();
    }

    @Override
    public void onSettingsUpdated(DataContext ctx) {
        applyOptions(engine);
        super.onSettingsUpdated(ctx);
        rebuildFigures();
    }

    // ------------------------------------------------------------------
    //  ciclo de vida
    // ------------------------------------------------------------------

    @Override
    public void onActivate(OrderContext c) {
        // un clon activado en otro gráfico no debe compartir el motor del original
        if (engine.isActive() && !engine.ownedBy(this)) engine = new TradeEngine();
        TradeEngine e = engine;
        applyOptions(e);
        setVetoCloseOnDeactivate(true);   // desactivar la estrategia NO cierra la posición
        e.bind(c, this, this);
        listenTicks(c, e);
        PanelWindow.watchEsc(e);
        PanelWindow.show(e, getSettings().getBoolean(ON_TOP, false));
    }

    @Override
    public void onDeactivate(OrderContext c) {
        stopTicks();
        TradeEngine e = engine;
        if (e.ownedBy(this)) {
            e.unbind(this);
            PanelWindow.unwatchEsc(e);
            PanelWindow.hide(e);
        }
        rebuildFigures();
        notifyRedraw();
    }

    @Override
    public void destroy() {
        stopTicks();
        TradeEngine e = engine;
        if (e.ownedBy(this)) {
            e.unbind(this);
            PanelWindow.unwatchEsc(e);
            PanelWindow.hide(e);
            e.dispose();
        }
        super.destroy();
    }

    private void listenTicks(OrderContext c, TradeEngine e) {
        stopTicks();
        Instrument in = c.getInstrument();
        if (in == null) return;
        tickInstrument = in;
        tickListener = (Tick t) -> e.tick(t.getPrice());
        in.addListener(tickListener);
    }

    private void stopTicks() {
        Instrument in = tickInstrument;
        TickOperation l = tickListener;
        tickInstrument = null; tickListener = null;
        if (in != null && l != null) { try { in.removeListener(l); } catch (RuntimeException ignore) { } }
    }

    /**
     * Si MotiveWave sigue trabajando con un clon sin reactivarlo, el clon recibe los eventos pero el
     * motor apunta al original: la instancia que recibe eventos de órdenes pasa a ser la dueña.
     */
    private TradeEngine claim(OrderContext c) {
        TradeEngine e = engine;
        if (c != null && !e.ownedBy(this)) {
            applyOptions(e);
            e.bind(c, this, this);
            listenTicks(c, e);
            PanelWindow.watchEsc(e);
        }
        return e;
    }

    // ------------------------------------------------------------------
    //  eventos de órdenes
    // ------------------------------------------------------------------

    @Override public void onOrderFilled(OrderContext c, Order o)    { claim(c).orderFilled(o); }
    @Override public void onOrderCancelled(OrderContext c, Order o) { claim(c).orderCancelled(o); }
    @Override public void onOrderRejected(OrderContext c, Order o)  { claim(c).orderRejected(o); }
    @Override public void onOrderModified(OrderContext c, Order o)  { claim(c).orderModified(o); }
    @Override public void onPositionClosed(OrderContext c)          { claim(c).positionClosed(); }

    @Override
    public void onBarUpdate(OrderContext c) { heartbeat(c); }

    @Override
    public void onBarClose(OrderContext c) { heartbeat(c); }

    private void heartbeat(OrderContext c) {
        Instrument in = c != null ? c.getInstrument() : null;
        if (in != null) claim(c).tick(in.getLastPrice());
    }

    /** Botón "Enter Now" del control nativo de la estrategia: muestra el panel. */
    @Override
    public void onEnterNow(OrderContext c) { showPanel(); }

    // ------------------------------------------------------------------
    //  TradeEngine.Host
    // ------------------------------------------------------------------

    @Override
    public void redraw() {
        try { rebuildFigures(); notifyRedraw(); }
        catch (RuntimeException e) { debug("[TradePanel] redraw: " + e); }
    }

    @Override
    public void log(String msg) { info("[TradePanel] " + msg); }

    // ------------------------------------------------------------------
    //  figuras
    // ------------------------------------------------------------------

    @Override
    protected void calculateValues(DataContext dc) { rebuildFigures(); }

    @Override
    public void onFiguresLaidOut(DrawContext dc) { lastDraw = dc; }

    private synchronized void ownState() {
        if (figsOwner == this) return;
        figsOwner = this;
        figs = new HashMap<>();
        hud = new Hud();
        catcher = new ClickCatcher();
    }

    private synchronized void rebuildFigures() {
        ownState();
        TradeEngine e = engine;
        boolean mine = e.ownedBy(this);
        List<LineModel> ls = mine ? e.lines() : List.of();
        int off = getSettings() != null ? getSettings().getInteger(LABEL_OFFSET, 400) : 400;
        beginFigureUpdate();
        try {
            clearFigures();
            // recolector de clics solo cuando hace falta: así el gráfico se comporta de forma nativa
            if (mine && e.isActive() && (e.isArmed() || e.optModClick)) addFigure(catcher);
            Set<String> seen = new HashSet<>();
            for (LineModel m : ls) {
                LineFigure f = figs.get(m.id);
                if (f == null) { f = new LineFigure(); figs.put(m.id, f); }
                f.labelOffset = off;
                f.set(m);
                seen.add(m.id);
                addFigure(f);
                if (m.draggable) addFigure(f.grip);
                // los botones SL/TP de la posición y de las entradas también se pueden arrastrar
                if (m.kind == LineModel.Kind.POSITION || m.kind == LineModel.Kind.ENTRY || m.kind == LineModel.Kind.SYNTH) {
                    if (f.hasBtn("SL")) addFigure(f.slGrip);
                    if (f.hasBtn("TP")) addFigure(f.tpGrip);
                }
                // el botón #N de un grupo se arrastra para sacar de él la última salida que se unió
                if ((m.kind == LineModel.Kind.STOP || m.kind == LineModel.Kind.TARGET) && f.hasBtn("#")) addFigure(f.splitGrip);
            }
            figs.keySet().retainAll(seen);
            addFigure(hud);
        } finally {
            endFigureUpdate();
        }
    }

    private synchronized List<LineFigure> figures() {
        ownState();
        return new ArrayList<>(figs.values());
    }

    private LineFigure figureAt(Point2D p, DrawContext dc) {
        for (LineFigure f : figures()) if (f.contains(p.getX(), p.getY(), dc)) return f;
        return null;
    }

    // ------------------------------------------------------------------
    //  ratón
    // ------------------------------------------------------------------

    @Override
    public boolean onClick(Point p, int flags) {
        ownState();
        if (hud.onPill(p)) { togglePanel(); return false; }
        TradeEngine e = engine;
        if (hud.onBanner(p) && e.isArmed()) { e.disarm(); return false; }    // clic en el aviso naranja = cancelar
        if (hud.onNotice(p)) { e.dismissNotice(); redraw(); return false; }   // clic en el error = cerrarlo
        for (LineFigure f : figures()) {
            String b = f.hit(p);
            if (b == null) continue;
            // si este clic ya llegó como "arrastre" del botón SL/TP, no se repite (lo desarmaría)
            boolean exit = "SL".equals(b) || "TP".equals(b) || b.startsWith("#");
            if (!exit || System.currentTimeMillis() - lastBtnGripMs > 500) e.lineButton(f.id(), b);
            return false;
        }
        DrawContext dc = lastDraw;
        if (dc == null || !e.ownedBy(this)) return true;
        boolean shift = (flags & SHIFT_DOWN) != 0, ctrl = (flags & CTRL_DOWN) != 0, alt = (flags & ALT_DOWN) != 0;
        if (!e.wantsClick(shift, ctrl)) return true;
        if (figureAt(p, dc) != null) return true;          // clic sobre una etiqueta: no es un precio
        Rectangle b = dc.getBounds();
        if (b != null && !b.contains(p)) return true;
        e.chartClick(snapPlacing(dc, (float) dc.translate2Value(p.getY())), shift, ctrl, alt);
        return false;
    }

    @Override
    public boolean onDoubleClick(Point p, int flags) {
        TradeEngine e = engine;
        for (LineFigure f : figures()) {
            LineModel m = f.model();
            if (m == null || !f.onLabel(p.getX(), p.getY())) continue;
            if (m.kind != LineModel.Kind.ENTRY && m.kind != LineModel.Kind.SYNTH) return true;
            int q = e.qtyOf(m.id);
            if (q <= 0) return true;
            final String id = m.id;
            PanelWindow.askQuantity(q, nq -> e.editQty(id, nq));
            return false;
        }
        return true;
    }

    @Override
    public void onHover(Point2D loc, int flags, DrawContext dc) {
        if (dc != null) lastDraw = dc;
        TradeEngine e = engine;
        if (dc != null && loc != null && e.isArmed() && e.ownedBy(this)) e.hover(snapPlacing(dc, (float) dc.translate2Value(loc.getY())));
    }

    @Override
    public MenuDescriptor onMenu(String plotName, Point loc, DrawContext dc) {
        List<MenuItem> items = new ArrayList<>();
        TradeEngine e = engine;
        if (e.isActive() && e.ownedBy(this) && e.isArmed()) {
            // con una colocación armada el clic derecho la cancela directamente (como en DeepCharts), sin menú
            e.disarm();
            return new MenuDescriptor(items, false);
        }
        if (e.isActive() && e.ownedBy(this)) {
            DrawContext d = dc != null ? dc : lastDraw;
            LineFigure f = d != null && loc != null ? figureAt(loc, d) : null;
            LineModel m = f != null ? f.model() : null;
            if (m != null && m.kind != LineModel.Kind.PREVIEW && m.kind != LineModel.Kind.TRIGGER) {
                final String id = m.id;
                String what = m.kind == LineModel.Kind.POSITION ? T.t("Cerrar posición a mercado") : T.t("Cancelar ") + m.text;
                items.add(new MenuItem(what, () -> e.lineButton(id, "X")));
                items.add(new MenuSeparator());
            }
        }
        items.add(new MenuItem(T.t("Mostrar Trade Panel"), this::showPanel));
        if (e.isActive() && e.ownedBy(this)) {
            items.add(new MenuSeparator());
            items.add(new MenuItem(T.t("Cancelar todas las órdenes"), e::cancelAll));
            items.add(new MenuItem(T.t("BE (punto de equilibrio)"), e::breakeven));
            items.add(new MenuItem("Cancel and Flat", e::cancelAndFlat));
        }
        return new MenuDescriptor(items, true);
    }

    // ---- arrastre de etiquetas (MotiveWave mueve el ResizePoint y avisa aquí) ----

    private LineFigure lineOf(ResizePoint rp) {
        return rp instanceof LineFigure.Grip ? ((LineFigure.Grip) rp).line() : null;
    }

    private float rounded(DrawContext dc, double v) {
        try {
            Instrument in = dc.getDataContext().getInstrument();
            if (in != null) return (float) in.round(v);
        } catch (RuntimeException ignore) { }
        return (float) v;
    }

    private volatile long lastBtnGripMs;

    @Override
    public void onBeginResize(ResizePoint rp, DrawContext dc) {
        if (rp instanceof LineFigure.BtnGrip) {
            LineFigure.BtnGrip g = (LineFigure.BtnGrip) rp;
            LineFigure f = g.line();
            f.dragging = true;
            lastBtnGripMs = System.currentTimeMillis();
            if ("#".equals(g.btn)) engine.splitBegin(f.id());
            else engine.pickBegin(f.id(), "SL".equals(g.btn));
            return;
        }
        LineFigure f = lineOf(rp);
        if (f != null) f.dragging = true;
    }

    @Override
    public void onResize(ResizePoint rp, DrawContext dc) {
        if (rp instanceof LineFigure.BtnGrip) {
            LineFigure.BtnGrip g = (LineFigure.BtnGrip) rp;
            float px = rounded(dc, rp.getValue());
            if ("#".equals(g.btn)) engine.splitMove(snap(g.line(), px, dc));   // la salida que se separa sigue al ratón
            else engine.hover(snapPlacing(dc, px));        // la vista previa del SL/TP sigue al ratón
            return;
        }
        LineFigure f = lineOf(rp);
        if (f == null) return;
        float px = snap(f, rounded(dc, rp.getValue()), dc);
        f.dragPrice = px;
        f.layout(dc);
        engine.dragLine(f.id(), px);                       // precio e importe en vivo mientras se arrastra
    }

    /**
     * Imán: si un SL/TP se suelta cerca (pocos píxeles) de otro del mismo tipo y lado, se pega a su
     * precio y el motor los agrupa (como en DeepCharts).
     */
    private float snap(LineFigure f, float px, DrawContext dc) {
        LineModel m = f.model();
        return m == null ? px : snapTo(m.mergeKey, f, px, dc);
    }

    /** Imán para el SL/TP que se está colocando (botón SL/TP o stop del modo Risk): se pega a otro igual y se agrupa. */
    private float snapPlacing(DrawContext dc, float px) {
        return snapTo(engine.snapKey(), null, px, dc);
    }

    private float snapTo(String key, LineFigure skip, float px, DrawContext dc) {
        if (key == null || Float.isNaN(px)) return px;
        int y = dc.translateValue(px);
        float best = px;
        int bestD = 7;
        for (LineFigure o : figures()) {
            LineModel om = o.model();
            if (o == skip || om == null || !key.equals(om.mergeKey)) continue;
            int d = Math.abs(dc.translateValue(om.price) - y);
            if (d < bestD) { bestD = d; best = om.price; }
        }
        return best;
    }

    @Override
    public void onEndResize(ResizePoint rp, DrawContext dc) {
        if (rp instanceof LineFigure.BtnGrip) {
            LineFigure.BtnGrip g = (LineFigure.BtnGrip) rp;
            LineFigure f = g.line();
            LineModel m = f.model();
            float px = rounded(dc, rp.getValue());
            f.dragging = false;
            lastBtnGripMs = System.currentTimeMillis();
            // un arrastre de verdad coloca la salida al soltar; un clic sin moverse la deja armada
            boolean moved = m != null && Math.abs(dc.translateValue(px) - dc.translateValue(m.price)) >= 8;
            if ("#".equals(g.btn)) {
                // arrastre: la última salida del grupo sale y va a ese precio; clic: se separan todas
                if (moved) engine.splitDrop(snap(f, px, dc));
                else { engine.splitDrop(Float.NaN); if (m != null) engine.lineButton(m.id, "#"); }
            } else if (moved) engine.pickDrop(snapPlacing(dc, px));
            f.layout(dc);
            return;
        }
        LineFigure f = lineOf(rp);
        if (f == null) return;
        float px = snap(f, rounded(dc, rp.getValue()), dc);
        f.dragging = false;
        f.dragPrice = px;                    // se mantiene hasta que el motor publique el resultado
        engine.moveLine(f.id(), px);         // el motor decide si hay cambio (y deja de publicar el arrastre)
    }

    // ------------------------------------------------------------------
    //  panel
    // ------------------------------------------------------------------

    private void showPanel() { PanelWindow.show(engine, getSettings().getBoolean(ON_TOP, false)); }

    private void togglePanel() {
        if (PanelWindow.isShowing(engine)) PanelWindow.hide(engine);
        else showPanel();
    }

    // ------------------------------------------------------------------
    //  figuras auxiliares
    // ------------------------------------------------------------------

    /** Botón "PANEL" (esquina superior derecha) y aviso de la colocación armada. */
    private final class Hud extends Figure {
        private volatile Rectangle pill = new Rectangle();
        private volatile Rectangle bannerRect = new Rectangle();
        private volatile Rectangle noticeRect = new Rectangle();

        boolean onPill(Point p) { return pill.contains(p); }
        boolean onBanner(Point p) { return bannerRect.contains(p); }
        boolean onNotice(Point p) { return noticeRect.contains(p); }

        @Override public boolean isVisible(DrawContext dc) { lastDraw = dc; return true; }
        @Override public boolean contains(double x, double y, DrawContext dc) {
            return pill.contains(x, y) || bannerRect.contains(x, y) || noticeRect.contains(x, y);
        }

        @Override public void layout(DrawContext dc) {
            lastDraw = dc;
            Rectangle b = dc.getBounds();
            pill = new Rectangle(b.x + b.width - 62, b.y + 6, 54, 18);
            setBounds(new Rectangle2D.Double(pill.x, pill.y, pill.width, pill.height));
        }

        @Override public void draw(Graphics2D gc, DrawContext dc) {
            lastDraw = dc;
            TradeEngine e = engine;
            boolean on = e.isActive() && e.ownedBy(TradePanelStrategy.this);
            Rectangle r = pill, b = dc.getBounds();
            Graphics2D g = (Graphics2D) gc.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setFont(LineFigure.FONT.deriveFont(Font.BOLD, 10f));
                FontMetrics fm = g.getFontMetrics();
                g.setColor(new Color(0x1B1B1F));
                g.fillRoundRect(r.x, r.y, r.width, r.height, 6, 6);
                g.setColor(on ? new Color(0x2EA043) : new Color(0x55555E));
                g.setStroke(new BasicStroke(1.2f));
                g.drawRoundRect(r.x, r.y, r.width, r.height, 6, 6);
                g.fillOval(r.x + 6, r.y + 6, 6, 6);
                g.setColor(on ? Color.WHITE : new Color(0x9A9AA5));
                g.drawString("PANEL", r.x + 16, r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2);

                String txt = on ? e.banner() : "";
                if (txt.isEmpty()) bannerRect = new Rectangle();
                else {
                    // la ✕ se dibuja con líneas: la fuente del gráfico no siempre tiene ese símbolo
                    txt += T.t("  ·  Esc o clic derecho para cancelar  ✕").replace("✕", "").replaceAll("\\s+$", "");
                    g.setFont(LineFigure.FONT.deriveFont(Font.BOLD, 11f));
                    fm = g.getFontMetrics();
                    int w = fm.stringWidth(txt) + 20 + CROSS + 8, h = 22;
                    int x = b.x + (b.width - w) / 2, y = b.y + 6;
                    bannerRect = new Rectangle(x, y, w, h);
                    g.setColor(new Color(0x1B, 0x1B, 0x1F, 235));
                    g.fillRoundRect(x, y, w, h, 8, 8);
                    g.setColor(new Color(0xFF8C1A));
                    g.drawRoundRect(x, y, w, h, 8, 8);
                    g.setColor(Color.WHITE);
                    g.drawString(txt, x + 10, y + (h + fm.getAscent() - fm.getDescent()) / 2);
                    cross(g, x + w - 10 - CROSS, y + (h - CROSS) / 2);
                }
                drawNotice(g, on ? e.notice() : "", b, bannerRect.isEmpty() ? b.y + 6 : bannerRect.y + bannerRect.height + 6);
                if (on) scheduleNoticeRedraw(e.noticeLeft());
            } finally {
                g.dispose();
            }
        }
    }

    /** Recuadro rojo con el último error, centrado arriba (varias líneas si es largo). Un clic lo cierra. */
    private void drawNotice(Graphics2D g, String txt, Rectangle b, int y) {
        if (txt == null || txt.isEmpty()) { hud.noticeRect = new Rectangle(); return; }
        g.setFont(LineFigure.FONT.deriveFont(Font.BOLD, 11f));
        FontMetrics fm = g.getFontMetrics();
        int max = Math.max(160, Math.min(b.width - 40, 760));
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : txt.split(" ")) {
            String next = cur.length() == 0 ? w : cur + " " + w;
            if (cur.length() > 0 && fm.stringWidth(next) > max) { lines.add(cur.toString()); cur = new StringBuilder(w); }
            else cur = new StringBuilder(next);
        }
        lines.add(cur.toString());
        int lh = fm.getHeight(), w = 0;
        for (String l : lines) w = Math.max(w, fm.stringWidth(l));
        w += 20 + CROSS + 8;
        int h = lh * lines.size() + 8, x = b.x + (b.width - w) / 2;
        hud.noticeRect = new Rectangle(x, y, w, h);
        g.setColor(new Color(0x2A, 0x12, 0x14, 240));
        g.fillRoundRect(x, y, w, h, 8, 8);
        g.setColor(new Color(0xE5484D));
        g.setStroke(new BasicStroke(1.2f));
        g.drawRoundRect(x, y, w, h, 8, 8);
        g.setColor(Color.WHITE);
        for (int i = 0; i < lines.size(); i++) g.drawString(lines.get(i), x + 10, y + 4 + fm.getAscent() + i * lh);
        cross(g, x + w - 10 - CROSS, y + 4 + (lh - CROSS) / 2);
    }

    private static final int CROSS = 7;

    /** Aspa de cerrar dibujada con dos líneas. */
    private static void cross(Graphics2D g, int x, int y) {
        g.setStroke(new BasicStroke(1.6f));
        g.drawLine(x, y, x + CROSS, y + CROSS);
        g.drawLine(x + CROSS, y, x, y + CROSS);
    }

    /** Programa un redibujado para cuando caduque el error (el gráfico no se repinta solo si el precio no se mueve). */
    private void scheduleNoticeRedraw(long left) {
        if (left <= 0) return;
        long at = System.currentTimeMillis() + left;
        if (Math.abs(at - noticeRedrawAt) < 250) return;
        noticeRedrawAt = at;
        try { TIMER.schedule(this::redraw, left + 60, TimeUnit.MILLISECONDS); } catch (RuntimeException ignore) { }
    }

    /**
     * Figura invisible que cubre el área de precios. MotiveWave solo entrega onClick/onHover a un
     * estudio si hay una figura suya bajo el cursor, así que con una colocación armada (o con el
     * clic directo activado) hace falta para recibir el clic en un precio cualquiera.
     */
    private final class ClickCatcher extends Figure {
        @Override public boolean isVisible(DrawContext dc) { lastDraw = dc; return true; }
        @Override public boolean contains(double x, double y, DrawContext dc) {
            Rectangle b = dc.getBounds();
            return b != null && b.contains(x, y);
        }
        @Override public void layout(DrawContext dc) {
            lastDraw = dc;
            Rectangle b = dc.getBounds();
            setBounds(new Rectangle2D.Double(b.x, b.y, b.width, b.height));
        }
        @Override public void draw(Graphics2D gc, DrawContext dc) { /* invisible a propósito */ }
    }
}
