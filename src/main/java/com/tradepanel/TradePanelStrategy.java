package com.tradepanel;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
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

import javafx.event.EventType;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

/**
 * Trade Panel estilo DeepCharts para MotiveWave (estrategia manual).
 *
 * Esta clase solo es el enlace con el SDK: traduce los eventos de MotiveWave (órdenes, clics,
 * arrastres, ticks) en llamadas al {@link TradeEngine}, y pinta en el gráfico las líneas que el
 * motor publica. La ventana del panel es {@link PanelWindow}.
 *
 * Uso: añade la estrategia al gráfico y pulsa Activate. Aparece el panel flotante; el botón
 * "DEEPCHARTS" de la esquina inferior derecha del gráfico lo muestra u oculta.
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

    // ---- captura propia del ratón (para que otro indicador que tape el gráfico no se quede con el clic) ----
    /** Estrategias activas: el filtro de ratón de las ventanas las consulta mientras se coloca una orden. */
    private static final Set<TradePanelStrategy> LIVE = new CopyOnWriteArraySet<>();
    private volatile Canvas chartCanvas;        // canvas JavaFX donde MotiveWave pinta este gráfico
    private volatile AffineTransform chartTx;   // coordenadas del gráfico -> píxeles del canvas (las del Graphics2D al dibujar)
    private boolean capturing;                  // pulsación en curso que es nuestra (solo hilo JavaFX)
    private boolean clickPending;               // soltada nuestra: su MOUSE_CLICKED también es nuestro
    static volatile long lastCancelMs;          // clic derecho que canceló una colocación: su menú contextual no sale
    private static final Map<Class<?>, Field> GC_FIELD = new java.util.concurrent.ConcurrentHashMap<>();

    // ---- varios gráficos del mismo activo: uno opera (el primero que se activa) y los demás hacen de espejo ----
    /** Motor que opera cada activo (cada símbolo del activo -> motor del gráfico activado). */
    private static final Map<String, TradeEngine> ENGINES = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Set<String> chartKeys = Set.of();   // símbolos del activo de este gráfico
    private volatile TradeEngine viewing;       // motor de otro gráfico que este muestra (espejo), o null

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
        // al desactivar (o cambiar de cuenta) MotiveWave cancela las órdenes de la estrategia, también el SL/TP de
        // la posición: para no dejarla sin protección, decide su opción "Close on Deactivate" (con su confirmación)
        Set<String> ks = union(keys(c == null ? null : c.getInstrument()), chartKeys);
        TradeEngine s = find(ks);
        boolean mirror = s != null && s != engine && s.sameAccount(c);   // con otra cuenta, este gráfico opera por su cuenta
        trace("Activate · gráfico " + chartKeys + " · órdenes " + keys(c == null ? null : c.getInstrument()) + (mirror ? " · ya hay otro gráfico operando: espejo" : " · opera este gráfico"));
        if (mirror) {
            // otro gráfico ya opera este activo: este muestra sus órdenes y las maneja con su motor
            attach(s);
            PanelWindow.show(s, getSettings().getBoolean(ON_TOP, false));
            rebuildFigures();
            notifyRedraw();
            return;
        }
        detach();
        // un clon activado en otro gráfico no debe compartir el motor del original
        if (engine.isActive() && !engine.ownedBy(this)) engine = new TradeEngine();
        TradeEngine e = engine;
        applyOptions(e);
        e.bind(c, this, this);
        register(ks, e);
        listenTicks(c, e);
        PanelWindow.watchEsc(e);
        LIVE.add(this);
        PanelWindow.show(e, getSettings().getBoolean(ON_TOP, false));
    }

    @Override
    public void onDeactivate(OrderContext c) {
        stopTicks();
        LIVE.remove(this);
        detach();
        TradeEngine e = engine;
        if (e.ownedBy(this)) {
            unregister(e);
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
        LIVE.remove(this);
        detach();
        TradeEngine e = engine;
        if (e.ownedBy(this)) {
            unregister(e);
            e.unbind(this);
            PanelWindow.unwatchEsc(e);
            PanelWindow.hide(e);
            e.dispose();
        }
        super.destroy();
    }

    /**
     * Símbolos con los que se reconoce un activo: el del gráfico y el contrato activo (un gráfico
     * continuo "MNQ" opera el contrato "MNQZ6": los dos tienen que encontrarse). El "símbolo del
     * exchange" no sirve: es el mercado ("CME") y juntaría activos distintos.
     */
    private static Set<String> keys(Instrument in) {
        Set<String> out = new HashSet<>();
        if (in == null) return out;
        for (java.util.function.Supplier<String> f : List.<java.util.function.Supplier<String>>of(in::getSymbol, in::getActiveSymbol)) {
            try { String v = f.get(); if (v != null && !v.isBlank()) out.add(v.trim().toUpperCase(java.util.Locale.ROOT)); }
            catch (RuntimeException ignore) { }
        }
        return out;
    }

    private static Set<String> union(Set<String> a, Set<String> b) { Set<String> u = new HashSet<>(a); u.addAll(b); return u; }

    /** Motor activo que opera alguno de esos símbolos, o null. */
    private static TradeEngine find(Set<String> ks) {
        for (String k : ks) { TradeEngine s = ENGINES.get(k); if (s != null && s.isActive()) return s; }
        return null;
    }

    /** Registra el motor con esos símbolos, sin quitárselos a otro motor activo (p. ej. el de otra cuenta). */
    private static void register(Set<String> ks, TradeEngine e) {
        for (String k : ks) ENGINES.compute(k, (kk, old) -> old != null && old != e && old.isActive() ? old : e);
    }

    private volatile String lastTrace = "";
    /** Traza de conexión entre gráficos para el log de MotiveWave (solo cuando cambia). */
    private void trace(String msg) {
        if (msg.equals(lastTrace)) return;
        lastTrace = msg;
        try { info("[TradePanel] " + msg + " · instancia " + Integer.toHexString(System.identityHashCode(this))
            + " · motores registrados " + ENGINES.keySet()); } catch (Throwable ignore) { }
    }

    private static void unregister(TradeEngine e) { ENGINES.entrySet().removeIf(en -> en.getValue() == e); }

    /**
     * Motor que muestra y maneja este gráfico: el suyo si se ha activado aquí; si no, el del gráfico que
     * opera este mismo activo. Así las órdenes se ven y se manejan desde cualquier gráfico del activo,
     * pero solo un motor las gestiona (dos motores sobre las mismas órdenes se pisarían).
     */
    private TradeEngine eng() {
        TradeEngine e = engine;
        if (e.isActive() && e.ownedBy(this)) return e;
        TradeEngine s = find(chartKeys);
        if (s != null && s != e) { attach(s); return s; }
        if (!ENGINES.isEmpty()) trace("gráfico " + chartKeys + ": sin motor para estos símbolos");
        return e;
    }

    private void attach(TradeEngine s) {
        if (viewing == s) return;
        trace("gráfico " + chartKeys + ": espejo conectado al motor que opera el activo");
        TradeEngine old = viewing;
        if (old != null) old.removeViewer(this);
        viewing = s;
        s.addViewer(this);
        LIVE.add(this);
        PanelWindow.watchEsc(s);
    }

    private void detach() {
        TradeEngine v = viewing;
        viewing = null;
        if (v != null) v.removeViewer(this);
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
            Set<String> ks = union(keys(c.getInstrument()), chartKeys);
            TradeEngine s = find(ks);
            if (s != null && s != e && s.sameAccount(c)) return null;   // espejo: las órdenes las gestiona el gráfico que opera
            register(ks, e);
            applyOptions(e);
            e.bind(c, this, this);
            listenTicks(c, e);
            PanelWindow.watchEsc(e);
            LIVE.add(this);
        }
        return e;
    }

    // ------------------------------------------------------------------
    //  eventos de órdenes
    // ------------------------------------------------------------------

    @Override public void onOrderFilled(OrderContext c, Order o)    { TradeEngine e = claim(c); if (e != null) e.orderFilled(o); }
    @Override public void onOrderCancelled(OrderContext c, Order o) { TradeEngine e = claim(c); if (e != null) e.orderCancelled(o); }
    @Override public void onOrderRejected(OrderContext c, Order o)  { TradeEngine e = claim(c); if (e != null) e.orderRejected(o); }
    @Override public void onOrderModified(OrderContext c, Order o)  { TradeEngine e = claim(c); if (e != null) e.orderModified(o); }
    @Override public void onPositionClosed(OrderContext c)          { TradeEngine e = claim(c); if (e != null) e.positionClosed(); }

    @Override
    public void onBarUpdate(OrderContext c) { heartbeat(c); }

    @Override
    public void onBarClose(OrderContext c) { heartbeat(c); }

    private void heartbeat(OrderContext c) {
        Instrument in = c != null ? c.getInstrument() : null;
        TradeEngine e = in != null ? claim(c) : null;
        if (e != null) e.tick(in.getLastPrice());
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
    protected void calculateValues(DataContext dc) {
        try {
            Set<String> ks = keys(dc.getInstrument());
            if (!ks.isEmpty()) {
                if (!ks.equals(chartKeys)) trace("gráfico con símbolos " + ks);
                chartKeys = ks;
                TradeEngine e = engine;
                if (e.isActive() && e.ownedBy(this)) register(ks, e);   // el gráfico que opera, también con los símbolos de su gráfico
            }
        } catch (RuntimeException ignore) { }
        rebuildFigures();
    }

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
        TradeEngine e = eng();
        boolean mine = e.isActive();
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
        TradeEngine e = eng();
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
        if (dc == null || !e.isActive()) return true;
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
        TradeEngine e = eng();
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
        TradeEngine e = eng();
        if (dc != null && loc != null && e.isArmed() && e.isActive()) e.hover(snapPlacing(dc, (float) dc.translate2Value(loc.getY())));
    }

    @Override
    public MenuDescriptor onMenu(String plotName, Point loc, DrawContext dc) {
        List<MenuItem> items = new ArrayList<>();
        TradeEngine e = eng();
        if (e.isActive() && e.isArmed()) {
            // con una colocación armada el clic derecho la cancela directamente (como en DeepCharts), sin menú
            e.disarm();
            return new MenuDescriptor(items, false);
        }
        if (e.isActive()) {
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
        if (e.isActive()) {
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
            if ("#".equals(g.btn)) eng().splitBegin(f.id());
            else eng().pickBegin(f.id(), "SL".equals(g.btn));
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
            if ("#".equals(g.btn)) eng().splitMove(snap(g.line(), px, dc));   // la salida que se separa sigue al ratón
            else eng().hover(snapPlacing(dc, px));        // la vista previa del SL/TP sigue al ratón
            return;
        }
        LineFigure f = lineOf(rp);
        if (f == null) return;
        float px = snap(f, rounded(dc, rp.getValue()), dc);
        f.dragPrice = px;
        f.layout(dc);
        eng().dragLine(f.id(), px);                       // precio e importe en vivo mientras se arrastra
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
        return snapTo(eng().snapKey(), null, px, dc);
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
                if (moved) eng().splitDrop(snap(f, px, dc));
                else { eng().splitDrop(Float.NaN); if (m != null) eng().lineButton(m.id, "#"); }
            } else if (moved) eng().pickDrop(snapPlacing(dc, px));
            f.layout(dc);
            return;
        }
        LineFigure f = lineOf(rp);
        if (f == null) return;
        float px = snap(f, rounded(dc, rp.getValue()), dc);
        f.dragging = false;
        f.dragPrice = px;                    // se mantiene hasta que el motor publique el resultado
        eng().moveLine(f.id(), px);         // el motor decide si hay cambio (y deja de publicar el arrastre)
    }

    // ------------------------------------------------------------------
    //  panel
    // ------------------------------------------------------------------

    private void showPanel() { PanelWindow.show(eng(), getSettings().getBoolean(ON_TOP, false)); }

    private void togglePanel() {
        TradeEngine e = eng();
        if (PanelWindow.isShowing(e)) PanelWindow.hide(e);
        else showPanel();
    }

    // ------------------------------------------------------------------
    //  figuras auxiliares
    // ------------------------------------------------------------------

    /** Botón "DEEPCHARTS" (esquina inferior derecha) y aviso de la colocación armada. */
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
            pill = new Rectangle(b.x + b.width - 96, b.y + b.height - 26, 88, 18);   // abajo: arriba suelen estar los botones de otras extensiones
            setBounds(new Rectangle2D.Double(pill.x, pill.y, pill.width, pill.height));
        }

        @Override public void draw(Graphics2D gc, DrawContext dc) {
            lastDraw = dc;
            noteCanvas(gc);
            TradeEngine e = eng();
            boolean on = e.isActive();
            Rectangle r = pill, b = dc.getBounds();
            Graphics2D g = (Graphics2D) gc.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setFont(LineFigure.FONT.deriveFont(Font.BOLD, 10f));
                FontMetrics fm = g.getFontMetrics();
                g.setColor(new Color(0x1B1B1F));
                g.fillRoundRect(r.x, r.y, r.width, r.height, 6, 6);
                g.setColor(!on ? new Color(0x55555E) : e != engine ? new Color(0x3D8BFD) : new Color(0x2EA043));   // verde: opera aquí · azul: espejo de otro gráfico · gris: sin conectar
                g.setStroke(new BasicStroke(1.2f));
                g.drawRoundRect(r.x, r.y, r.width, r.height, 6, 6);
                g.fillOval(r.x + 6, r.y + 6, 6, 6);
                g.setColor(on ? Color.WHITE : new Color(0x9A9AA5));
                g.drawString("DEEPCHARTS", r.x + 16, r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2);

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
                boolean mine = on || !e.isActive();                // sin activar: este gráfico es el único que puede mostrarlo
                drawNotice(g, mine ? e.notice() : "", b, bannerRect.isEmpty() ? b.y + 6 : bannerRect.y + bannerRect.height + 6);
                if (mine) scheduleNoticeRedraw(e.noticeLeft());
            } finally {
                g.dispose();
            }
        }
    }

    // ------------------------------------------------------------------
    //  captura propia del ratón mientras se coloca una orden
    // ------------------------------------------------------------------

    /**
     * Anota en qué canvas JavaFX se pinta este gráfico y con qué transformación. MotiveWave dibuja cada
     * gráfico con un Graphics2D propio sobre un Canvas: getTransform() (API estándar de Java2D) da el
     * paso de coordenadas del gráfico a píxeles del canvas, y el canvas se localiza por el tipo de su
     * GraphicsContext (sin depender de nombres internos de MotiveWave). Si algo no encaja, no se anota
     * nada y el panel funciona como antes (clic a través de MotiveWave).
     */
    private void noteCanvas(Graphics2D g) {
        try {
            AffineTransform t = g.getTransform();
            Canvas cv = canvasOf(g);
            if (t != null && cv != null) { chartTx = new AffineTransform(t); chartCanvas = cv; }
        } catch (Throwable ignore) { }
    }

    private static Canvas canvasOf(Graphics2D g) throws IllegalAccessException {
        Class<?> k0 = g.getClass();
        Field f = GC_FIELD.get(k0);
        if (f == null) {
            for (Class<?> k = k0; k != null && k != Object.class && f == null; k = k.getSuperclass())
                for (Field x : k.getDeclaredFields())
                    if (GraphicsContext.class.isAssignableFrom(x.getType())) { x.setAccessible(true); f = x; break; }
            if (f == null) return null;
            GC_FIELD.put(k0, f);
        }
        GraphicsContext gc = (GraphicsContext) f.get(g);
        return gc == null ? null : gc.getCanvas();
    }

    /**
     * Filtro de ratón de las ventanas (lo instala {@link PanelWindow}): mientras hay una colocación armada
     * el clic sobre el gráfico es nuestro aunque otro indicador (p. ej. uno que cubre todo el gráfico)
     * esté por encima. Devuelve true si el evento se consume.
     */
    static boolean fxMouse(MouseEvent ev) {
        for (TradePanelStrategy s : LIVE) {
            Boolean r = s.fxMouseOne(ev);
            if (r != null) return r;
        }
        return false;
    }

    /** null = el evento no es de este gráfico (o no hay nada que hacer); true = consumirlo. */
    private Boolean fxMouseOne(MouseEvent ev) {
        TradeEngine e = eng();
        EventType<? extends MouseEvent> t = ev.getEventType();
        boolean armed = e.isActive() && e.isArmed();
        if (!armed && !capturing && !clickPending) return null;
        Canvas cv = chartCanvas;
        AffineTransform tx = chartTx;
        DrawContext dc = lastDraw;
        if (cv == null || tx == null || dc == null || cv.getScene() == null || cv.getScene() != sceneOf(ev)) return null;
        Point2D p = toChart(cv, tx, ev.getSceneX(), ev.getSceneY());
        Rectangle b = dc.getBounds();
        // dentro del área de precios de ESTE gráfico y no encima de un control (botones, campos…) de MotiveWave
        boolean inside = p != null && b != null && b.contains(p) && cv.isVisible() && !onControl(ev);
        if (t == MouseEvent.MOUSE_MOVED || (t == MouseEvent.MOUSE_DRAGGED && !capturing)) {
            if (armed && inside) onHover(p, flags(ev), dc);     // la vista previa sigue al ratón (no se consume: cruz de MotiveWave)
            return null;
        }
        if (t == MouseEvent.MOUSE_PRESSED) {
            capturing = false; clickPending = false;
            if (!armed || !inside) return null;
            ownState();
            if (figureAt(p, dc) != null) return null;          // sobre una etiqueta: la gestiona MotiveWave (arrastres)
            capturing = true;
            if (ev.getButton() == MouseButton.SECONDARY) { e.disarm(); lastCancelMs = System.currentTimeMillis(); }   // clic derecho = cancelar, como en DeepCharts
            return true;
        }
        if (t == MouseEvent.MOUSE_CLICKED) {
            if (!clickPending) return null;
            clickPending = false;
            if (ev.getButton() == MouseButton.PRIMARY && ev.isStillSincePress() && p != null)
                onClick(new Point((int) Math.round(p.getX()), (int) Math.round(p.getY())), flags(ev));
            return true;
        }
        if (!capturing) return null;
        if (t == MouseEvent.MOUSE_RELEASED) { capturing = false; clickPending = true; }   // la pulsación termina aquí, salga o no el clic
        return true;                                            // RELEASED / DRAGGED de una pulsación nuestra
    }

    private static boolean onControl(MouseEvent ev) {
        javafx.scene.input.PickResult pr = ev.getPickResult();
        for (javafx.scene.Node n = pr == null ? null : pr.getIntersectedNode(); n != null; n = n.getParent())
            if (n instanceof javafx.scene.control.Control) return true;
        return false;
    }

    private static javafx.scene.Scene sceneOf(MouseEvent ev) {
        Object src = ev.getSource();
        if (src instanceof javafx.stage.Window) return ((javafx.stage.Window) src).getScene();
        if (src instanceof javafx.scene.Scene) return (javafx.scene.Scene) src;
        if (src instanceof javafx.scene.Node) return ((javafx.scene.Node) src).getScene();
        return null;
    }

    /** Punto de la escena -> coordenadas del gráfico (las que usan onClick y DrawContext). */
    static Point2D toChart(Canvas cv, AffineTransform tx, double sceneX, double sceneY) {
        javafx.geometry.Point2D local = cv.sceneToLocal(sceneX, sceneY);
        if (local == null) return null;
        try { return tx.inverseTransform(new Point2D.Double(local.getX(), local.getY()), null); }
        catch (NoninvertibleTransformException e) { return null; }
    }

    private static int flags(MouseEvent ev) {
        int f = 0;
        if (ev.isShiftDown()) f |= SHIFT_DOWN;
        if (ev.isControlDown()) f |= CTRL_DOWN;
        if (ev.isAltDown()) f |= ALT_DOWN;
        if (ev.isMetaDown()) f |= META_DOWN;
        return f;
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
