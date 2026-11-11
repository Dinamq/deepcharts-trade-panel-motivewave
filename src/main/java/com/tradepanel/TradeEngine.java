package com.tradepanel;

import java.awt.Color;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.motivewave.platform.sdk.common.Enums$OrderAction;
import com.motivewave.platform.sdk.common.Enums$OrderType;
import com.motivewave.platform.sdk.common.Enums$TIF;
import com.motivewave.platform.sdk.common.Instrument;
import com.motivewave.platform.sdk.order_mgmt.Order;
import com.motivewave.platform.sdk.order_mgmt.OrderContext;

/**
 * Motor de órdenes del Trade Panel (dinámica de DeepCharts):
 *
 *  - Entradas MKT / BID-ASK / LMT / STP / STP LMT (esta última emulada: el SDK no tiene stop-limit).
 *  - OCO Strategy modo SL/TP: cada entrada lleva hijas "Int" (internas, en cliente) que se envían
 *    al bróker cuando la entrada se ejecuta; al ejecutarse una salida se cancela la otra.
 *  - Modo Risk: la cantidad sale del riesgo en dinero (SL Money o % del balance) y del stop marcado en el gráfico.
 *  - Modo Promediado: un riesgo total repartido entre varias entradas con el SL común (en contra), o un
 *    % del beneficio que ya aseguran los SL (a favor, con todos los SL en BE o mejor).
 *  - Breakeven manual y automático, trailing stop con línea "Trail. Trigger", enlace de órdenes
 *    pendientes (al ejecutarse una se cancelan las demás), Cancelar y Cancel and Flat.
 *
 * Todo el estado vive en UN hilo (el ejecutor): los eventos del SDK, los botones del panel y los
 * clics del gráfico se encolan ahí. El dibujo y la ventana solo leen instantáneas inmutables
 * ({@link #lines()} y {@link #snapshot()}), así que no hay bloqueos ni carreras con MotiveWave.
 */
final class TradeEngine {

    interface Host {
        void redraw();
        void log(String msg);
    }

    enum Kind {
        MARKET("MKT"), LIMIT("LMT"), STOP("STP"), STOP_LIMIT("STP LMT");
        final String label;
        Kind(String label) { this.label = label; }
    }

    // ---- colores de las líneas (tomados del panel de DeepCharts) ----
    static final Color C_BUY_LMT  = new Color(0x3FA35B);
    static final Color C_BUY_STP  = new Color(0x2FA58C);
    static final Color C_SELL_LMT = new Color(0xC2256E);
    static final Color C_SELL_STP = new Color(0xD9456F);
    static final Color C_INTERNAL = new Color(0x8E2A9A);
    static final Color C_LONG     = new Color(0x1E9E5A);
    static final Color C_SHORT    = new Color(0xE23B4E);
    static final Color C_TRIGGER  = new Color(0x2FA58C);
    static final Color C_MUTED    = new Color(0x7A7A85);

    private static final Locale ES = Locale.forLanguageTag("es-ES");

    // ---- opciones (ajustes del estudio) ----
    volatile int optBeOffset = 0;        // botón "Punto de equilibrio": ticks a favor desde la entrada
    volatile int optStpLmtOffset = 2;    // STP LMT: ticks del límite más allá del stop
    volatile int optMaxPerSec = 5;       // protección: órdenes de entrada por segundo
    volatile boolean optEntryGtc = false;
    volatile boolean optModClick = false; // Ctrl+clic = comprar, Shift+clic = vender, +Alt = a mercado
    // etiquetas (por defecto como DeepCharts: la posición con su P/L y cada SL/TP con su importe en dinero)
    volatile boolean optPosR = false, optPosRCurrent = false, optPosRisk = false;
    volatile Config.Unit optPosUnit = Config.Unit.MONEY;
    volatile boolean optExitAmt = true, optExitR = false;
    volatile Config.Unit optExitUnit = Config.Unit.MONEY;

    final Config cfg = new Config();

    // ---- conexión ----
    private final Executor exec;
    private ExecutorService ownExec;
    private volatile OrderContext ctx;
    private volatile Host host;
    private volatile Object owner;
    private volatile String symbol = "—";

    // ---- publicado para el dibujo y la ventana ----
    private volatile List<LineModel> lines = List.of();
    private volatile PanelSnapshot snap = PanelSnapshot.EMPTY;
    private volatile boolean armedFlag;
    private volatile String banner = "";

    // ---- estado (solo el hilo del motor) ----
    private final List<Bracket> brackets = new ArrayList<>();
    private final List<PendingPx> pendingPx = new ArrayList<>();
    private final List<Order> engineCancels = new ArrayList<>();
    private final Deque<Long> submitTimes = new ArrayDeque<>();
    private Armed armed;
    private Pick pick;              // SL/TP manual pendiente de fijar en el gráfico
    private Float hover;
    private String dragId;          // etiqueta que se está arrastrando (su precio se publica en vivo)
    private float dragPx;
    private boolean nakedWarn;
    private int serial;
    private String account = "—";
    private long lastPublishMs;
    private Bracket lastPlaced;         // último bracket creado por placeEntry (Promediado lo agrupa)
    private Class<?> acctClass;         // clase del OrderContext donde se buscó la cuenta interna
    private Method acctMethod;          // método (no público) que devuelve la cuenta interna de MotiveWave
    private long joinCounter;
    private String fillNote;            // aviso extra para el estado de la ejecución en curso (BE tras TP…)
    private Bracket splitB;             // salida que se está sacando de su grupo (arrastre del botón #N)
    private boolean splitStop;
    private float splitPx = Float.NaN;
    private volatile float splitRaw = Float.NaN;
    private final AtomicBoolean splitQueued = new AtomicBoolean();
    private volatile String snapKey;    // tipo de línea a la que se pega lo que se está colocando (imán), o null
    private volatile int stratQty = -1; // cantidad que calcula el riesgo de la estrategia elegida (-1 = no aplica)

    private volatile float lastPrice;
    private volatile float hoverRaw = Float.NaN;
    private volatile String status = "";
    private volatile String notice = "";        // último error (se muestra unos segundos en el gráfico)
    private volatile long noticeAt;
    static final long NOTICE_MS = 7000;
    /** Mensajes que dicen que algo NO se ha hecho (en los dos idiomas). */
    private static final java.util.regex.Pattern ERROR = java.util.regex.Pattern.compile(
        "\\bNO |\\bNOT |RECHAZADA|REJECTED|ATENCIÓN|WARNING|^Error: "
        + "|no hay posición|no open position|hacen falta|you need at least|no disponible|not available"
        + "|Límite de órdenes|Orders-per-second|activa OCO Strategy|enable OCO Strategy|riesgo del SL es 0|SL risk is 0"
        + "|indica el riesgo total|set the total risk|ya tiene |already has a|ya no está pendiente|no longer pending"
        + "|ya definido|already set|no da el|does not provide|no llega ni a 1|does not reach 1|no tiene ningún stop|has no stop");
    private final AtomicBoolean tickQueued = new AtomicBoolean();
    private final AtomicBoolean hoverQueued = new AtomicBoolean();
    private volatile String dragRawId;
    private volatile float dragRaw;
    private final AtomicBoolean dragQueued = new AtomicBoolean();

    private final boolean persist;
    private boolean dirty;

    TradeEngine() { this.exec = null; this.persist = true; cfg.load(); }

    /** Para pruebas: con un ejecutor directo el motor es síncrono y determinista (y no toca preferencias). */
    TradeEngine(Executor direct) { this.exec = direct; this.persist = false; }

    // ------------------------------------------------------------------
    //  modelos
    // ------------------------------------------------------------------

    /** Salida (stop o target) de un bracket: primero es un plan interno, luego una orden viva. */
    static final class Leg {
        final boolean stop;
        boolean planned;
        float price = Float.NaN;  // precio absoluto (NaN hasta conocer el precio de la entrada)
        double offset;            // distancia a la entrada mientras no hay precio absoluto
        Order order;              // orden viva en el bróker (null mientras es interna)
        boolean pre;              // modo Server: stop enviado al bróker ANTES de ejecutarse la entrada
        Object group;             // salidas agrupadas (mismo precio, se mueven/cancelan juntas); null = suelta
        long joinSeq;             // orden de llegada al grupo (pila: la última en unirse es la primera en salir)
        int filledSeen;
        // estrategia de salida
        int no;                   // número en la etiqueta (SL2, TP1…); 0 = sin número
        boolean dropped;          // cancelado a mano: no vuelve aunque cambie la cantidad
        double offPct;            // distancia a la entrada en % del precio (Offset Type = Percent); 0 = usa offset
        boolean trail;            // stop tipo Trail: sigue al precio a su distancia
        double trailDist;
        long lastMoveMs;
        boolean mit;              // objetivo tipo MIT: interno; al tocarse su precio sale a mercado
        Leg(boolean stop) { this.stop = stop; }
        boolean resolved() { return !Float.isNaN(price); }
        boolean live() { return order != null && order.isActive(); }
    }

    /** Una entrada (real, emulada o la posición) con sus salidas OCO, BE automático y trailing. */
    static final class Bracket {
        final int serial;
        final boolean buy;        // lado de la ENTRADA (las salidas van al contrario)
        int qty;
        Order entry;
        boolean synth;            // STP LMT emulado aún sin disparar
        float synthStop, synthLimit;
        boolean entryDone;        // la entrada ya no está pendiente (ejecutada del todo o cancelada)
        boolean positional;       // creado desde la línea de posición (sin orden de entrada)
        int entryFilledSeen;
        float ref = Float.NaN;    // precio de referencia: precio de la orden y luego precio medio de ejecución
        final Leg sl = new Leg(true), tp = new Leg(false);
        // ---- modo Multi: varios stops y objetivos, cada uno con sus contratos ----
        final List<Leg> sls = new ArrayList<>(List.of(sl));   // stops: sl y los demás de la estrategia
        final List<Leg> tps = new ArrayList<>(List.of(tp));   // objetivos: tp y los demás de la estrategia
        boolean multi;
        /** Un hueco por contrato de la entrada: qué stop y qué objetivo le tocan (índice en sls/tps, -1 = ninguno) y su estado. */
        int[] slotS, slotT;
        byte[] slotSt;
        Config.Strategy strat;    // estrategia con la que se creó (para repartir otra cantidad)
        int[] stepS, stepT;       // por paso: su stop y su objetivo (índice en sls/tps, -1 = no tiene)
        boolean marked;           // el stop se marcó en el gráfico (Risk / Promediado): cubre todos los contratos
        double beTrigPct, beOffPct;   // break even de la estrategia en % del precio
        int liveQty;              // cantidad ejecutada que protegen las salidas vivas
        boolean autoBe, beDone;
        double beTrig, beOff;
        boolean trail, trailActive;
        float trailTrigger = Float.NaN;
        double trailTrigDist, trailDist;
        long lastTrailMs;
        Object link;              // grupo "Enlazar órdenes pendientes"
        int num;                  // número visible (1, 2, 3…): el menor libre al crearse; lo comparten entrada, SL y TP
        double riskPer = Double.NaN;  // riesgo inicial por contrato (dinero): lo que perdía cada contrato con sus stops al ejecutarse
        Bracket(int serial, boolean buy, int qty) { this.serial = serial; this.buy = buy; this.qty = qty; }
        boolean entryPending() { return synth || (entry != null && !entryDone); }
        int remaining() { return Math.max(0, qty - entryFilledSeen); }
        Leg leg(boolean stop) { return stop ? sl : tp; }
        /** El stop y todos los objetivos. */
        List<Leg> legs() { List<Leg> l = new ArrayList<>(sls); l.addAll(tps); return l; }
    }

    // estado de cada contrato de un bracket Multi
    private static final byte PEND = 0;   // su parte de la entrada aún no se ha ejecutado
    private static final byte OPEN = 1;   // abierto: lo protegen su stop y su objetivo
    private static final byte DONE = 2;   // ya cerrado (o la entrada se canceló antes de ejecutarlo)

    private static final class Armed {
        final boolean buy; final Kind kind; final boolean bidAsk;
        int stage;      // 0 = falta el precio de entrada, 1 = falta el nivel del stop (modo Risk)
        Float entry;    // precio de entrada fijado (null para MKT)
        Armed(boolean buy, Kind kind, boolean bidAsk) { this.buy = buy; this.kind = kind; this.bidAsk = bidAsk; }
    }

    /** SL o TP que se está colocando a mano desde el botón de una etiqueta (posición o entrada pendiente). */
    private static final class Pick {
        final String id; final boolean stop;
        Pick(String id, boolean stop) { this.id = id; this.stop = stop; }
    }

    private static final class PendingPx {
        final Order o; final float px;
        PendingPx(Order o, float px) { this.o = o; this.px = px; }
    }

    // ------------------------------------------------------------------
    //  conexión con la estrategia
    // ------------------------------------------------------------------

    void bind(OrderContext c, Host h, Object who) {
        this.host = h; this.owner = who; this.ctx = c;
        Instrument in = c != null ? c.getInstrument() : null;
        if (in != null) { symbol = String.valueOf(in.getSymbol()); lastPrice = in.getLastPrice(); }
        run(() -> { });
    }

    void unbind(Object who) {
        if (owner != who) return;
        ctx = null;
        run(() -> { armed = null; pick = null; hover = null; dragId = null; splitB = null; });
    }

    boolean ownedBy(Object who) { return owner == who; }
    boolean isActive() { return ctx != null; }
    boolean isArmed() { return armedFlag; }
    /** Error reciente para mostrar en el gráfico ("" si no hay o ya caducó). */
    String notice() { return System.currentTimeMillis() - noticeAt < NOTICE_MS ? notice : ""; }
    /** Milisegundos que le quedan al error en pantalla (0 si no hay). */
    long noticeLeft() { return notice().isEmpty() ? 0 : Math.max(0, NOTICE_MS - (System.currentTimeMillis() - noticeAt)); }
    void dismissNotice() { noticeAt = 0; }
    /** Cantidad calculada por el riesgo de la estrategia elegida (0 = no llega a 1 contrato), o -1 si no aplica. */
    int strategyQty() { return stratQty; }
    /** Clave de agrupación ("SL"/"SS"/"TL"/"TS") de la salida que se está colocando, o null. */
    String snapKey() { return snapKey; }
    String banner() { return banner; }
    List<LineModel> lines() { return lines; }
    PanelSnapshot snapshot() { return snap; }

    synchronized void dispose() {
        if (ownExec != null) { ownExec.shutdownNow(); ownExec = null; }
    }

    private synchronized Executor executor() {
        if (exec != null) return exec;
        if (ownExec == null || ownExec.isShutdown()) {
            ownExec = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "tradepanel-engine");
                t.setDaemon(true);
                return t;
            });
        }
        return ownExec;
    }

    private void run(Runnable r) { submit(r, true); }

    private void submit(Runnable r, boolean forcePublish) {
        try {
            executor().execute(() -> {
                try { r.run(); }
                catch (Throwable t) { setStatus("Error: " + t); }
                if (forcePublish || dirty || System.currentTimeMillis() - lastPublishMs >= 200) publish();
            });
        } catch (RuntimeException rejected) { /* motor cerrándose */ }
    }

    // ------------------------------------------------------------------
    //  acciones del panel
    // ------------------------------------------------------------------

    /** Vuelve a publicar líneas y avisos (p. ej. tras cambiar el idioma). */
    void refresh() { run(() -> { }); }

    void configChanged() { if (persist) cfg.save(); run(() -> { if (armed != null && armed.stage == 1 && !cfg.sizing()) armed = null; }); }

    void market(boolean buy) {
        run(() -> {
            OrderContext c = need(); if (c == null) return;
            pick = null;
            if (cfg.mode() == Config.QtyMode.AVG) { avgEntry(c, buy, Kind.MARKET, null, false); return; }
            if (cfg.mode() == Config.QtyMode.RISK) { armStop(buy, Kind.MARKET, null, false); return; }
            armed = null;
            placeEntry(c, buy, Kind.MARKET, Float.NaN, null);
        });
    }

    void bidAsk(boolean buy) {
        run(() -> {
            OrderContext c = need(); if (c == null) return;
            Instrument in = c.getInstrument();
            double off = cfg.tickDiffOn ? cfg.tickDiff * in.getTickSize() : 0;
            float px = (float) in.round(buy ? bid(c) - off : ask(c) + off);
            pick = null;
            if (cfg.mode() == Config.QtyMode.AVG) { avgEntry(c, buy, Kind.LIMIT, px, true); return; }
            if (cfg.mode() == Config.QtyMode.RISK) { armStop(buy, Kind.LIMIT, px, true); return; }
            armed = null;
            placeEntry(c, buy, Kind.LIMIT, px, null);
        });
    }

    /** LMT / STP / STP LMT: el siguiente clic en el gráfico fija el precio. Pulsar de nuevo desarma. */
    void arm(boolean buy, Kind kind) {
        run(() -> {
            if (need() == null) return;
            Armed a = armed;
            pick = null;
            if (a != null && a.buy == buy && a.kind == kind && !a.bidAsk) { armed = null; setStatus(""); return; }
            armed = new Armed(buy, kind, false);
            hover = null;
            setStatus("");
        });
    }

    void disarm() { run(() -> { if (armed != null || pick != null) { armed = null; pick = null; setStatus(T.t("Colocación cancelada")); } }); }

    void cancelAll() {
        run(() -> {
            OrderContext c = need(); if (c == null) return;
            armed = null; pick = null;
            c.cancelOrders();
            brackets.clear();
            pendingPx.clear();
            setStatus(T.t("Órdenes pendientes canceladas"));
        });
    }

    void cancelAndFlat() {
        run(() -> {
            OrderContext c = need(); if (c == null) return;
            armed = null; pick = null;
            c.cancelOrders();
            brackets.clear();
            pendingPx.clear();
            if (c.getPosition() != 0) c.closeAtMarket();
            setStatus(T.t("FLAT: órdenes canceladas y posición cerrada"));
        });
    }

    /** Punto de equilibrio: mueve los stops al precio medio (± ticks) o crea uno si no hay. */
    void breakeven() {
        run(() -> {
            OrderContext c = need(); if (c == null) return;
            int pos = c.getPosition();
            if (pos == 0) { setStatus(T.t("Punto de equilibrio: no hay posición abierta")); return; }
            Instrument in = c.getInstrument();
            boolean lng = pos > 0;
            float avg = c.getAvgEntryPrice();
            float be = (float) in.round(lng ? avg + optBeOffset * in.getTickSize() : avg - optBeOffset * in.getTickSize());
            if (lng ? be >= bid(c) : be <= ask(c)) {
                setStatus(T.t("Punto de equilibrio NO aplicado: el precio está en contra (") + fmt(be) + T.t(" se ejecutaría ya)"));
                return;
            }
            int moved = 0;
            for (Bracket b : brackets) {
                if (b.liveQty <= 0) continue;
                for (Leg st : b.sls) if (st.live()) { modifyStop(c, st, be); b.beDone = true; moved++; }
            }
            if (moved > 0) { setStatus(T.t("Punto de equilibrio: stop movido a ") + fmt(be)); return; }
            Bracket pb = positional(c, lng, Math.abs(pos), true);
            pb.sl.planned = true;
            pb.sl.price = be;
            pb.beDone = true;
            pb.sl.order = c.createStopOrder(exitAction(pb), Enums$TIF.GTC, pb.liveQty, be);
            c.submitOrders(pb.sl.order);
            setStatus(T.t("Punto de equilibrio: stop creado en ") + fmt(be));
        });
    }

    /**
     * "Enlazar órdenes pendientes" (como en DeepCharts):
     *  - con posición abierta, las órdenes de salida sueltas (stop y límite contrarios a la posición)
     *    pasan a formar un OCO con ella: al ejecutarse una se cancela la otra, y al cerrarse la
     *    posición se cancelan las dos;
     *  - si no, enlaza las entradas pendientes: cuando se ejecuta una, se cancelan las demás.
     */
    void linkPending() {
        run(() -> {
            OrderContext c = need(); if (c == null) return;
            adopt(c);
            if (linkExits(c)) return;
            List<Bracket> free = new ArrayList<>();
            for (Bracket b : brackets) if (b.entryPending() && b.liveQty == 0 && b.link == null) free.add(b);
            if (free.size() < 2) { setStatus(T.t("Enlazar: hacen falta al menos 2 órdenes de entrada pendientes sin enlazar")); return; }
            Object group = new Object();
            for (Bracket b : free) b.link = group;
            setStatus(T.t("Enlazadas ") + free.size() + T.t(" órdenes: al ejecutarse una se cancelan las demás"));
        });
    }

    /** Órdenes sueltas contrarias a la posición -> salidas OCO de la posición. Devuelve false si no hay ninguna. */
    private boolean linkExits(OrderContext c) {
        int pos = c.getPosition();
        if (pos == 0) return false;
        boolean lng = pos > 0;
        List<Bracket> stops = new ArrayList<>(), lims = new ArrayList<>();
        for (Bracket b : brackets) {
            if (b.synth || b.entryDone || b.entry == null || !b.entry.isActive()) continue;
            if (b.buy == lng || b.liveQty != 0 || b.entryFilledSeen != 0 || b.link != null || b.sl.planned || b.tp.planned) continue;
            Enums$OrderType t = b.entry.getType();
            if (t == Enums$OrderType.STOP) stops.add(b);
            else if (t == Enums$OrderType.LIMIT) lims.add(b);
        }
        if (stops.isEmpty() && lims.isEmpty()) return false;
        double tick = c.getInstrument().getTickSize();
        int pairs = 0;
        List<Bracket> rest = new ArrayList<>(stops);
        rest.addAll(lims);
        for (Bracket s : stops) {
            // un stop y un objetivo de la misma cantidad forman un OCO; el resto queda ligado solo a la posición
            Bracket t = null;
            for (Bracket l : lims) if (rest.contains(l) && l.qty == s.qty) { t = l; break; }
            if (t == null) continue;
            exitBracket(c, lng, s, t, tick);
            rest.remove(s); rest.remove(t);
            pairs++;
        }
        for (Bracket one : rest) {
            boolean isStop = one.entry.getType() == Enums$OrderType.STOP;
            exitBracket(c, lng, isStop ? one : null, isStop ? null : one, tick);
        }
        setStatus(pairs > 0
            ? T.t("Enlazadas como OCO de la posición: al ejecutarse el stop o el objetivo se cancela el otro")
            : T.t("Enlazadas a la posición: se cancelan al cerrarla (para un OCO hacen falta un stop y un objetivo de la misma cantidad)"));
        return true;
    }

    private void exitBracket(OrderContext c, boolean lng, Bracket stop, Bracket target, double tick) {
        Bracket src = stop != null ? stop : target;
        Bracket pb = newBracket(lng, src.qty);
        pb.num = src.num;                                    // las mismas órdenes conservan su número
        pb.positional = true; pb.entryDone = true; pb.liveQty = src.qty; pb.entryFilledSeen = src.qty;
        pb.ref = c.getAvgEntryPrice();
        pb.trailTrigDist = cfg.trailTrigger * tick; pb.trailDist = cfg.trailDist * tick;
        if (stop != null) { pb.sl.planned = true; pb.sl.order = stop.entry; pb.sl.price = orderPrice(stop.entry); pb.sl.filledSeen = filled(stop.entry); brackets.remove(stop); }
        if (target != null) { pb.tp.planned = true; pb.tp.order = target.entry; pb.tp.price = orderPrice(target.entry); pb.tp.filledSeen = filled(target.entry); brackets.remove(target); }
        brackets.add(pb);
    }

    // ------------------------------------------------------------------
    //  interacción desde el gráfico
    // ------------------------------------------------------------------

    /** ¿Debe la estrategia quedarse con el clic? (decisión síncrona; el trabajo va al hilo del motor). */
    boolean wantsClick(boolean shift, boolean ctrl) {
        return ctx != null && (armedFlag || (optModClick && (shift || ctrl)));
    }

    void chartClick(float price, boolean shift, boolean ctrl, boolean alt) {
        run(() -> {
            OrderContext c = need(); if (c == null) return;
            Instrument in = c.getInstrument();
            float px = (float) in.round(price);
            if (pick != null) { placePick(c, px); return; }
            Armed a = armed;
            if (a != null) {
                if (a.stage == 0) {
                    String bad = sideError(c, a.buy, a.kind, px);
                    if (bad != null) { setStatus(T.t("NO colocada: ") + bad); return; }
                    if (cfg.mode() == Config.QtyMode.AVG) {
                        if (!riskOk()) return;
                        AvgPlan plan = avgPlan(c, a.buy, px);
                        if (plan.first) { a.entry = px; a.stage = 1; hover = null; setStatus(""); return; }
                        if (plan.error != null) { setStatus(T.t("Promediado NO enviado: ") + plan.error); return; }
                        if (placeAvg(c, a.buy, a.kind, px, plan)) armed = null;
                        return;
                    }
                    if (cfg.mode() == Config.QtyMode.RISK) {
                        if (!riskOk()) return;
                        a.entry = px; a.stage = 1; hover = null;
                        setStatus("");
                        return;
                    }
                    if (placeEntry(c, a.buy, a.kind, px, null)) armed = null;
                } else {
                    // si no se envía (stop en el lado incorrecto, riesgo insuficiente…) sigue armado para reintentar
                    if (placeEntry(c, a.buy, a.kind, a.entry != null ? a.entry : Float.NaN, px)) armed = null;
                }
                return;
            }
            if (optModClick && (shift || ctrl)) {
                if (cfg.sizing()) { setStatus(T.t("Clic con modificadores no disponible en modo Risk/Promediado: usa los botones del panel")); return; }
                boolean buy = !shift;
                float last = last(c);
                Kind k = alt ? Kind.MARKET : (buy ? (px < last ? Kind.LIMIT : Kind.STOP) : (px > last ? Kind.LIMIT : Kind.STOP));
                placeEntry(c, buy, k, px, null);
            }
        });
    }

    /** El ratón se mueve por el gráfico con una colocación armada: actualiza la vista previa. */
    void hover(float price) {
        hoverRaw = price;
        if (!armedFlag) return;
        if (hoverQueued.compareAndSet(false, true)) {
            run(() -> {
                hoverQueued.set(false);
                OrderContext c = ctx;
                float raw = hoverRaw;
                hover = (c == null || Float.isNaN(raw)) ? null : (float) c.getInstrument().round(raw);
            });
        }
    }

    /** Botón de una etiqueta del gráfico: SL, TP, TS o X. */
    void lineButton(String id, String btn) {
        run(() -> {
            OrderContext c = need(); if (c == null) return;
            if ("POS".equals(id)) { positionButton(c, btn); return; }
            Bracket b = bySerial(id);
            if (b == null) return;
            char t = id.charAt(0);
            if (t == 'E' || t == 'Y') entryButton(c, b, btn);
            else if (t == 'S' || t == 'T') { Leg l = legById(b, id); if (l != null) legButton(c, b, l, btn); }
            else if (t == 'G' && "X".equals(btn)) { b.trail = false; b.trailActive = false; setStatus(T.t("Trailing desactivado")); }
        });
    }

    /** Se empieza a arrastrar el botón SL/TP de una etiqueta: la vista previa sigue al ratón. */
    void pickBegin(String id, boolean stop) {
        run(() -> {
            OrderContext c = need(); if (c == null) return;
            Pick p = pick;
            if (p != null && p.id.equals(id) && p.stop == stop) return;
            startPick(c, id, stop);
        });
    }

    /** Se suelta el botón SL/TP arrastrado sobre un precio: la salida se coloca ahí. */
    void pickDrop(float price) {
        run(() -> {
            OrderContext c = need(); if (c == null || pick == null) return;
            placePick(c, (float) c.getInstrument().round(price));
        });
    }

    /** Se empieza a arrastrar el botón #N de un grupo: sale la última salida que se unió (pila). */
    void splitBegin(String id) {
        run(() -> {
            OrderContext c = need(); if (c == null) return;
            Bracket b = bySerial(id);
            if (b == null || id.isEmpty()) return;
            char t = id.charAt(0);
            if (t != 'S' && t != 'T') return;
            boolean stop = t == 'S';
            List<Bracket> members = groupOf(b, stop);
            if (members.size() < 2) return;
            armed = null; pick = null; hover = null;
            splitB = stackTop(members, stop); splitStop = stop; splitPx = Float.NaN;
            setStatus("");
        });
    }

    /** El botón #N arrastrado se mueve: la salida que se separa sigue al ratón. */
    void splitMove(float price) {
        splitRaw = price;
        if (splitQueued.compareAndSet(false, true)) {
            run(() -> {
                splitQueued.set(false);
                OrderContext c = ctx;
                float raw = splitRaw;
                if (c == null || splitB == null || Float.isNaN(raw)) return;
                splitPx = (float) c.getInstrument().round(raw);
            });
        }
    }

    /** Se suelta el botón #N: esa salida deja el grupo y va a ese precio (NaN = no se movió: no cambia nada). */
    void splitDrop(float price) {
        run(() -> {
            Bracket b = splitB;
            boolean stop = splitStop;
            splitB = null; splitPx = Float.NaN;
            OrderContext c = need();
            if (c == null || b == null || Float.isNaN(price) || !brackets.contains(b)) return;
            Leg l = b.leg(stop);
            if (l.group == null || !legShown(b, l)) return;
            float px = (float) c.getInstrument().round(price);
            if (px == legPrice(l)) return;
            String what = stop ? "Stop" : T.t("Objetivo");
            String err = legMoveError(c, b, l, px);
            if (err != null) { setStatus(what + T.t(" NO separado: ") + err); return; }
            int left = groupOf(b, stop).size() - 1;
            l.group = null;
            boolean trailOff = applyLegMove(c, b, l, px);
            int joined = joinAt(b, l, px);
            String st = what + T.t(" de ") + signed(!b.buy, legQty(b, l)) + T.t(" separado del grupo")
                + (left > 1 ? T.t(" (quedan ") + left + T.t(" juntos)") : "") + T.t(" y movido a ") + fmt(px);
            if (joined > 0) st += T.t(" · agrupado allí con ") + joined + T.t(" más");
            setStatus(st + (trailOff ? T.t(" (trailing desactivado)") : ""));
        });
    }

    /** Arrastre en curso de una etiqueta: su línea, su precio y su importe se publican en vivo. */
    void dragLine(String id, float price) {
        dragRaw = price;
        dragRawId = id;
        if (dragQueued.compareAndSet(false, true)) {
            run(() -> {
                dragQueued.set(false);
                OrderContext c = ctx;
                String rid = dragRawId;
                if (c == null || rid == null) { dragId = null; return; }
                dragId = rid;
                dragPx = (float) c.getInstrument().round(dragRaw);
            });
        }
    }

    /** Fin de un arrastre: mueve la orden / hija interna / disparador al nuevo precio. */
    void moveLine(String id, float price) {
        dragRawId = null;
        run(() -> {
            dragId = null;
            OrderContext c = need(); if (c == null) return;
            Instrument in = c.getInstrument();
            float px = (float) in.round(price);
            Bracket b = bySerial(id);
            if (b == null) return;
            char t = id.charAt(0);
            if (t == 'E') moveEntry(c, b, px);
            else if (t == 'Y') moveSynth(c, b, px);
            else if (t == 'S' || t == 'T') { Leg l = legById(b, id); if (l != null) moveLegs(c, b, l, px); }
            else if (t == 'G') { b.trailTrigger = px; b.trailActive = false; setStatus(T.t("Trail. Trigger en ") + fmt(px)); }
        });
    }

    /** Doble clic sobre una entrada pendiente: nueva cantidad. */
    void editQty(String id, int q) {
        run(() -> {
            OrderContext c = need(); if (c == null || q <= 0) return;
            Bracket b = bySerial(id);
            if (b == null || !b.entryPending()) return;
            if (b.multi && b.entryFilledSeen != 0) {
                setStatus(T.t("Cantidad NO cambiada: la entrada ya se ha ejecutado en parte y sus salidas están repartidas"));
                return;
            }
            if (b.synth) { b.qty = q; }
            else if (b.entry != null && b.entry.isActive()) {
                b.entry.setAdjQuantity(q);
                c.submitOrders(b.entry);
                b.qty = q;
                if (!b.multi && b.sl.pre && b.sl.live() && b.liveQty == 0) {      // el stop adelantado cubre la nueva cantidad
                    b.sl.order.setAdjQuantity(q);
                    c.submitOrders(b.sl.order);
                }
            }
            if (b.multi) { buildSlots(b, b.qty); syncMulti(c, b); }   // las salidas se reparten otra vez en proporción
            setStatus(T.t("Cantidad cambiada a ") + q);
        });
    }

    /** Cantidad actual de una línea de entrada (para el diálogo de edición), o -1. */
    int qtyOf(String id) {
        for (LineModel m : lines) if (m.id.equals(id) && (m.kind == LineModel.Kind.ENTRY || m.kind == LineModel.Kind.SYNTH)) {
            try {
                String s = m.text.substring(m.text.indexOf('|') + 1).trim().split(" ")[0];
                return Math.abs(Integer.parseInt(s.replace("+", "")));
            } catch (Exception e) { return -1; }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    //  eventos del SDK (se encolan en el hilo del motor)
    // ------------------------------------------------------------------

    void orderFilled(Order o) {
        run(() -> {
            OrderContext c = ctx; if (c == null || o == null) return;
            noteAccount(o);
            clearPending(o);
            Bracket b = byEntry(o);
            nakedWarn = false;
            fillNote = null;
            if (b != null) entryFilled(c, b, o);
            else {
                Bracket lb = byLeg(o);
                if (lb != null) legFilled(c, lb, o);
            }
            setStatus(String.format(T.loc(), T.t("EJECUTADA %s%d @ %s"), o.isBuy() ? "+" : "-", filled(o), fmt(o.getAvgFillPrice()))
                + (fillNote != null ? " · " + fillNote : "")
                + (nakedWarn ? T.t(" · ATENCIÓN: el stop se ejecutó ANTES que su entrada, revisa la posición") : ""));
        });
    }

    void orderCancelled(Order o) { run(() -> closed(o)); }

    void orderRejected(Order o) {
        run(() -> {
            if (o == null) return;
            if (o.isActive()) {
                // lo rechazado es una MODIFICACIÓN: la orden sigue viva con su precio anterior
                clearPending(o);
                setStatus(T.t("Modificación RECHAZADA por el bróker: ") + (o.isBuy() ? "+" : "-") + o.getQuantity() + " " + typeLabel(o) + T.t(" sigue en ") + fmt(orderPrice(o)));
                return;
            }
            closed(o);
            setStatus(T.t("RECHAZADA por el bróker: ") + (o.isBuy() ? "+" : "-") + o.getQuantity() + " " + typeLabel(o));
        });
    }

    void orderModified(Order o) { run(() -> { if (o != null) { noteAccount(o); clearPending(o); } }); }

    /** Posición a cero: se cancelan las salidas que sigan vivas (como hace DeepCharts). */
    void positionClosed() {
        run(() -> {
            OrderContext c = ctx; if (c == null) return;
            List<Order> cancel = new ArrayList<>();
            List<Order> resize = new ArrayList<>();
            for (Iterator<Bracket> it = brackets.iterator(); it.hasNext();) {
                Bracket b = it.next();
                if (b.multi) for (int i = 0; i < b.slotSt.length; i++) if (b.slotSt[i] == OPEN) b.slotSt[i] = DONE;
                for (Leg l : b.legs()) {
                    int pq = pendQty(b, l);
                    if (l.pre && l.live() && b.entryPending() && pq > 0) {
                        // stop en el bróker de una entrada que sigue pendiente: se queda, por lo que falta por ejecutar
                        if (l.order.getQuantity() != pq) { l.order.setAdjQuantity(pq); resize.add(l.order); }
                        continue;
                    }
                    if (l.live()) { cancel.add(l.order); engineCancels.add(l.order); }
                    l.order = null; l.filledSeen = 0; l.pre = false;
                }
                b.liveQty = 0; b.trailActive = false; b.beDone = false;
                if (!b.entryPending()) it.remove();
            }
            if (!cancel.isEmpty()) c.cancelOrders(cancel);
            if (!resize.isEmpty()) c.submitOrders(resize);
            setStatus(T.t("Posición cerrada"));
        });
    }

    /** Último precio negociado: STP LMT emulados, BE automático y trailing. */
    void tick(float price) {
        lastPrice = price;
        if (ctx == null) return;
        if (tickQueued.compareAndSet(false, true)) {
            submit(() -> {
                tickQueued.set(false);
                OrderContext c = ctx; if (c == null) return;
                checkSynths(c);
                manage(c);
            }, false);
        }
    }

    // ------------------------------------------------------------------
    //  entradas
    // ------------------------------------------------------------------

    private void armStop(boolean buy, Kind kind, Float entry, boolean bidAsk) {
        if (!riskOk()) return;
        Armed a = armed;
        if (a != null && a.stage == 1 && a.buy == buy && a.kind == kind && a.bidAsk == bidAsk) { armed = null; setStatus(""); return; }
        Armed n = new Armed(buy, kind, bidAsk);
        n.stage = 1; n.entry = entry;
        armed = n; hover = null;
        setStatus("");
    }

    private boolean riskOk() {
        String mode = cfg.mode() == Config.QtyMode.AVG ? T.t("Promediado") : "Risk";
        if (!cfg.riskReady()) {
            setStatus(T.t("Modo ") + mode + T.t(": activa OCO Strategy con SL en Money o en % del balance (el importe es el riesgo por operación)"));
            return false;
        }
        OrderContext c = ctx;
        double m = c == null ? Double.NaN : slRiskMoney(c);
        if (Double.isNaN(m)) { setStatus(T.t("Modo ") + mode + ": " + balanceMissing(cfg.slUnit)); return false; }
        if (m <= 0) { setStatus(T.t("Modo ") + mode + T.t(": el riesgo del SL es 0")); return false; }
        if (cfg.mode() == Config.QtyMode.AVG && cfg.avgTotal <= 0) { setStatus(T.t("Promediado: indica el riesgo total")); return false; }
        return true;
    }

    // ------------------------------------------------------------------
    //  Promediado
    // ------------------------------------------------------------------

    /** Resultado del cálculo de una entrada de promediado. */
    static final class AvgPlan {
        boolean first;      // no hay nada abierto en ese lado: es la 1ª entrada (se dimensiona como en Risk)
        String error;       // no se puede promediar (motivo)
        boolean favor;      // todos los SL en BE o mejor: se arriesga un % de lo asegurado
        float sl = Float.NaN;   // SL común donde va el stop de la nueva entrada
        double atStop;      // resultado de todo lo abierto si salta el SL (negativo = pérdida)
        double budget;      // dinero que puede arriesgar la nueva entrada
        int qty;
        double risk;        // lo que arriesga de verdad la nueva entrada (qty x distancia)
    }

    /**
     * Calcula una entrada de promediado en {@code buy} a {@code ref}.
     *  - Se mira todo lo abierto en ese lado: la posición (por la parte que protege cada stop) y las
     *    entradas pendientes (por lo que les falta por ejecutar). Todo debe tener SL.
     *  - Resultado en el SL ({@code atStop}): lo que daría todo si saltan los stops.
     *  - En contra (algún SL en pérdida): la nueva entrada puede arriesgar el total indicado menos lo que ya se arriesga.
     *  - A favor (todos los SL en BE o mejor): puede arriesgar el % indicado de lo que aseguran los SL.
     *  - El stop de la nueva entrada va al SL común (si hay varios precios, al más cercano al mercado).
     */
    private AvgPlan avgPlan(OrderContext c, boolean buy, float ref) {
        AvgPlan p = new AvgPlan();
        int pos = c.getPosition();
        boolean posSide = buy ? pos > 0 : pos < 0;
        int covered = 0;
        boolean any = false, allBe = true;
        double total = 0;
        float common = Float.NaN;
        for (Bracket b : brackets) {
            if (b.buy != buy) continue;
            int live = posSide ? b.liveQty : 0;
            int pend = b.entryPending() ? b.remaining() : 0;
            if (live <= 0 && pend <= 0) continue;
            any = true;
            if (b.multi) {
                // varios stops: cada contrato cuenta con el suyo
                float from = Float.isNaN(b.ref) ? c.getAvgEntryPrice() : b.ref;
                int covLive = 0, covPend = 0;
                double r = 0;
                for (Leg st : b.sls) {
                    float sp = st.live() ? orderPrice(st.order) : (st.planned && st.resolved() ? st.price : Float.NaN);
                    if (Float.isNaN(sp)) continue;
                    int lo = live > 0 ? slots(b, st, OPEN) : 0, pe = pend > 0 ? slots(b, st, PEND) : 0;
                    if (lo + pe <= 0) continue;
                    if (lo > 0) { r += pnl(c, from, sp, buy ? lo : -lo); covLive += lo; }
                    if (pe > 0) { r += pnl(c, entryPrice(b), sp, buy ? pe : -pe); covPend += pe; }
                    if (Float.isNaN(common) || (buy ? sp > common : sp < common)) common = sp;
                }
                if (covLive < live || covPend < pend) {
                    p.error = (covLive < live ? T.t("parte de la posición") : T.t("la entrada ") + signed(buy, pend) + " @ " + fmt(entryPrice(b))) + T.t(" no tiene SL: ponle stop primero");
                    return p;
                }
                covered += live;
                total += r;
                if (r < -1e-6) allBe = false;
                continue;
            }
            Leg l = b.sl;
            float sp = l.live() ? orderPrice(l.order) : (l.planned && l.resolved() ? l.price : Float.NaN);
            if (Float.isNaN(sp)) {
                p.error = (live > 0 ? T.t("parte de la posición") : T.t("la entrada ") + signed(buy, pend) + " @ " + fmt(entryPrice(b))) + T.t(" no tiene SL: ponle stop primero");
                return p;
            }
            double r = 0;
            if (live > 0) {
                float from = Float.isNaN(b.ref) ? c.getAvgEntryPrice() : b.ref;
                r += pnl(c, from, sp, buy ? live : -live);
                covered += live;
            }
            if (pend > 0) r += pnl(c, entryPrice(b), sp, buy ? pend : -pend);
            total += r;
            if (r < -1e-6) allBe = false;
            // SL común: el que antes saltaría (el más cercano al mercado)
            if (Float.isNaN(common) || (buy ? sp > common : sp < common)) common = sp;
        }
        if (posSide && covered < Math.abs(pos)) {
            p.error = (Math.abs(pos) - covered) + T.t(" contrato(s) de la posición sin SL: ponles stop primero");
            return p;
        }
        if (!any) { p.first = true; return p; }
        p.sl = common;
        p.atStop = total;
        p.favor = allBe;
        if (p.favor) {
            if (total < 0.005) { p.error = T.t("los SL están justo en BE: no hay beneficio asegurado del que arriesgar un %"); return p; }
            p.budget = total * cfg.avgPct / 100.0;
        } else {
            p.budget = cfg.avgTotal - Math.max(0, -total);
            if (p.budget < 0.005) { p.error = T.t("ya arriesgas ") + money(-total) + T.t(" de ") + money(cfg.avgTotal) + T.t(": no queda margen"); return p; }
        }
        if (Float.isNaN(ref)) return p;
        if (buy ? common >= ref : common <= ref) {
            p.error = T.t("la entrada ") + fmt(ref) + T.t(" queda al otro lado del SL común ") + fmt(common);
            return p;
        }
        double perContract = Math.abs(ref - common) * pointValue(c);
        p.qty = perContract > 0 ? (int) Math.floor(p.budget / perContract + 1e-9) : 0;
        if (p.qty < 1) {
            p.error = T.t("con ") + money(p.budget) + T.t(" de margen no llega ni a 1 contrato (cada uno arriesga ") + money(perContract) + T.t(" hasta ") + fmt(common) + ")";
            return p;
        }
        p.risk = p.qty * perContract;
        return p;
    }

    /** Precio al que se ejecutaría una entrada pendiente (para el STP LMT emulado, su límite: el peor caso). */
    private float entryPrice(Bracket b) {
        if (b.synth) return b.synthLimit;
        if (b.entry != null) { float px = orderPrice(b.entry); if (!Float.isNaN(px)) return px; }
        return b.ref;
    }

    /** Botón MKT / BID / ASK en Promediado: si ya hay algo abierto en ese lado se envía directo; si no, como en Risk. */
    private void avgEntry(OrderContext c, boolean buy, Kind kind, Float px, boolean bidAsk) {
        if (!riskOk()) return;
        float ref = px != null ? px : (buy ? ask(c) : bid(c));
        AvgPlan plan = avgPlan(c, buy, ref);
        if (plan.first) { armStop(buy, kind, px, bidAsk); return; }
        armed = null;
        if (plan.error != null) { setStatus(T.t("Promediado NO enviado: ") + plan.error); return; }
        placeAvg(c, buy, kind, px != null ? px : Float.NaN, plan);
    }

    private boolean placeAvg(OrderContext c, boolean buy, Kind kind, float price, AvgPlan plan) {
        lastPlaced = null;
        if (!placeEntry(c, buy, kind, price, plan.sl, plan.qty)) return false;
        Bracket b = lastPlaced;
        if (b != null && (b.sl.live() || b.sl.resolved())) joinAt(b, b.sl, legPrice(b.sl));
        String head = plan.favor
            ? String.format(T.loc(), T.t("Promediado a favor: %s · arriesga %s (%.0f %% de %s asegurados)"), signed(buy, plan.qty), money(plan.risk), cfg.avgPct, money(plan.atStop))
            : String.format(T.loc(), T.t("Promediado en contra: %s · arriesga %s (quedaban %s de %s)"), signed(buy, plan.qty), money(plan.risk), money(plan.budget), money(cfg.avgTotal));
        setStatus(head + T.t(" · SL común ") + fmt(plan.sl));
        return true;
    }

    // ------------------------------------------------------------------
    //  balance (para SL/TP en % y el riesgo de Risk/Promediado)
    // ------------------------------------------------------------------

    /** Riesgo en dinero del SL del panel (Money o % del balance); NaN si el balance no se conoce. */
    private double slRiskMoney(OrderContext c) {
        return moneyOf(c, cfg.slUnit, cfg.slValue());
    }

    private double moneyOf(OrderContext c, Config.Unit unit, double value) {
        if (unit == Config.Unit.MONEY) return value;
        if (!Config.isPct(unit)) return Double.NaN;
        double bal = unit == Config.Unit.PCT_INI ? initialBalance(c) : currentBalance(c);
        return Double.isNaN(bal) || bal <= 0 ? Double.NaN : bal * value / 100.0;
    }

    private static String balanceMissing(Config.Unit u) {
        return u == Config.Unit.PCT_INI
            ? T.t("MotiveWave no da el balance inicial de esta cuenta")
            : T.t("el bróker no da el saldo actual de la cuenta");
    }

    private double currentBalance(OrderContext c) {
        try { double v = c.getCashBalance(); return v > 0 ? v : Double.NaN; } catch (Throwable t) { return Double.NaN; }
    }

    /** Balance inicial: el escrito en el panel o, si es 0, el que guarda la cuenta en MotiveWave. */
    double initialBalance(OrderContext c) {
        if (cfg.initBal > 0) return cfg.initBal;
        return autoInitialBalance(c);
    }

    /**
     * El SDK solo da el saldo actual. La cuenta interna de MotiveWave (com.motivewave.platform.databean.Account)
     * guarda el saldo inicial: se localiza por reflexión el método del OrderContext que la devuelve.
     */
    double autoInitialBalance(OrderContext c) {
        if (c == null) return Double.NaN;
        try {
            if (acctClass != c.getClass()) {
                acctClass = c.getClass();
                acctMethod = null;
                for (Class<?> k = acctClass; k != null && k != Object.class && acctMethod == null; k = k.getSuperclass()) {
                    for (Method m : k.getDeclaredMethods()) {
                        if (m.getParameterCount() == 0 && "com.motivewave.platform.databean.Account".equals(m.getReturnType().getName())) {
                            m.setAccessible(true);
                            acctMethod = m;
                            break;
                        }
                    }
                }
            }
            if (acctMethod == null) return Double.NaN;
            Object acct = acctMethod.invoke(c);
            if (acct == null) return Double.NaN;
            for (String n : new String[]{"getInitialCashBalance", "getInitialAccountValue"}) {
                try {
                    Object v = acct.getClass().getMethod(n).invoke(acct);
                    if (v instanceof Number && ((Number) v).doubleValue() > 0) return ((Number) v).doubleValue();
                } catch (Throwable ignore) { }
            }
        } catch (Throwable ignore) { }
        return Double.NaN;
    }

    /**
     * Envía una entrada con su bracket. {@code slAbs} es el stop marcado en el gráfico (modo Risk):
     * fija el lado y la cantidad. Devuelve false si no se envió (el motivo queda en el estado).
     */
    private boolean placeEntry(OrderContext c, boolean buy, Kind kind, float price, Float slAbs) {
        return placeEntry(c, buy, kind, price, slAbs, 0);
    }

    /** {@code fixedQty} > 0: cantidad ya calculada (Promediado) en vez de sacarla del riesgo del SL. */
    private boolean placeEntry(OrderContext c, boolean buy, Kind kind, float price, Float slAbs, int fixedQty) {
        Instrument in = c.getInstrument();
        double tick = in.getTickSize();
        if (kind != Kind.MARKET) {
            String bad = sideError(c, buy, kind, price);
            if (bad != null) { setStatus(T.t("NO enviada: ") + bad); return false; }
        }
        float ref = kind == Kind.MARKET ? (buy ? ask(c) : bid(c)) : price;
        int q = Math.max(1, cfg.qty);
        if (slAbs != null) {
            if (buy ? slAbs >= ref : slAbs <= ref) {
                setStatus(String.format(T.t("NO enviada: el stop %s está en el lado incorrecto para %s (entrada ≈ %s)"),
                    fmt(slAbs), buy ? T.t("COMPRAR") : T.t("VENDER"), fmt(ref)));
                return false;
            }
            q = fixedQty > 0 ? fixedQty : riskQty(c, ref, slAbs);
            if (q < 1) { setStatus(T.t("NO enviada: con ese stop el riesgo no cubre ni 1 contrato (acerca el stop o sube el riesgo del SL)")); return false; }
        }
        Config.Strategy strat = cfg.oco || slAbs != null ? cfg.multi() : null;
        if (strat != null && slAbs == null && strat.riskOn) {
            // riesgo de la estrategia: la cantidad sale de su importe y de la distancia de sus stops
            q = stratRiskQty(c, strat, ref);
            if (q < 1) { setStatus(T.t("NO enviada: ") + stratRiskError(c, strat)); return false; }
        }
        String unitBad = null;
        if (slAbs == null && strat == null && cfg.oco && cfg.slOn && Config.isPct(cfg.slUnit) && Double.isNaN(moneyOf(c, cfg.slUnit, cfg.slValue()))) unitBad = balanceMissing(cfg.slUnit);
        if (strat == null && (cfg.oco || slAbs != null) && cfg.tpOn && Config.isPct(cfg.tpUnit) && Double.isNaN(moneyOf(c, cfg.tpUnit, cfg.tpValue()))) unitBad = balanceMissing(cfg.tpUnit);
        if (unitBad != null) { setStatus(T.t("NO enviada: ") + unitBad); return false; }
        if (!rateOk()) { setStatus(T.t("Límite de órdenes por segundo alcanzado: envío bloqueado")); return false; }

        Bracket b = newBracket(buy, q);
        lastPlaced = b;
        if (slAbs != null) { b.sl.planned = true; b.sl.price = (float) in.round(slAbs); }
        if (strat != null) setupMulti(c, b, q, strat, slAbs != null);
        else {
            if (slAbs == null && cfg.oco && cfg.slOn) { b.sl.planned = true; b.sl.offset = slDist(c, q); }
            if ((cfg.oco || slAbs != null) && cfg.tpOn) {
                b.tp.planned = true;
                // RR: se mide sobre el stop real de esta orden (el marcado en el gráfico o el del panel)
                b.tp.offset = tpDist(c, q, slAbs != null ? Math.abs(ref - b.sl.price) : slDist(c, q));
            }
        }
        boolean hasStop = anyPlanned(b.sls);
        b.autoBe = cfg.beOn && hasStop;
        b.beTrig = cfg.beTrigger * tick; b.beOff = cfg.beOffset * tick;
        if (strat != null && strat.beOn) {
            // el break even de la estrategia manda sobre el del panel
            b.autoBe = hasStop;
            if (strat.percent) { b.beTrigPct = strat.beTrig; b.beOffPct = strat.beOff; }
            else { b.beTrig = strat.beTrig * tick; b.beOff = strat.beOff * tick; }
        }
        b.trail = cfg.trailOn && hasStop;
        b.trailTrigDist = cfg.trailTrigger * tick; b.trailDist = cfg.trailDist * tick;
        if (kind != Kind.MARKET) resolve(c, b, price);

        Enums$OrderAction act = buy ? Enums$OrderAction.BUY : Enums$OrderAction.SELL;
        Enums$TIF tif = optEntryGtc ? Enums$TIF.GTC : Enums$TIF.DAY;
        String what = String.format("%s%d %s", buy ? "+" : "-", q, kind.label);
        switch (kind) {
            case MARKET: b.entry = c.createMarketOrder(act, q); break;
            case LIMIT:  b.entry = c.createLimitOrder(act, tif, q, price); break;
            case STOP:   b.entry = c.createStopOrder(act, tif, q, price); break;
            default: {
                b.synth = true;
                b.synthStop = price;
                b.synthLimit = (float) in.round(buy ? price + optStpLmtOffset * tick : price - optStpLmtOffset * tick);
                brackets.add(b);
                int j = slAbs != null ? joinAt(b, b.sl, b.sl.price) : 0;
                setStatus(what + " @ " + fmt(price) + T.t(" (límite ") + fmt(b.synthLimit) + T.t(", emulada en cliente)") + joinedText(j, true));
                return true;
            }
        }
        brackets.add(b);
        List<Order> pre = preStops(c, b);
        if (pre.isEmpty()) c.submitOrders(b.entry);
        else if (pre.size() == 1) c.submitOrders(b.entry, pre.get(0));
        else { List<Order> all = new ArrayList<>(); all.add(b.entry); all.addAll(pre); c.submitOrders(all); }
        int j = slAbs != null ? joinAt(b, b.sl, b.sl.price) : 0;
        String where = joinedText(j, true);
        if (pre.size() == 1) where += T.t(" · stop ") + fmt(orderPrice(pre.get(0))) + T.t(" ya en el bróker");
        else if (pre.size() > 1) where += " · " + pre.size() + T.t(" stops ya en el bróker");
        else if (cfg.server && hasStop && kind == Kind.STOP) where += T.t(" · SL/TP en cliente (solo las entradas LMT envían el stop al bróker)");
        setStatus(what + (kind == Kind.MARKET ? "" : " @ " + fmt(price)) + where);
        return true;
    }

    /**
     * Estrategia de salida: crea el stop y el objetivo de cada paso y reparte entre los pasos los
     * contratos de la entrada. Los pasos con el mismo stop (u objetivo) comparten una sola orden. Con un
     * stop marcado en el gráfico (Risk / Promediado) ese stop sustituye a los de la estrategia.
     */
    private void setupMulti(OrderContext c, Bracket b, int q, Config.Strategy st, boolean markedStop) {
        double tick = c.getInstrument().getTickSize();
        int n = st.steps.size();
        b.strat = st;
        b.marked = markedStop;
        b.stepS = new int[n]; b.stepT = new int[n];
        b.sls.clear(); b.tps.clear();
        for (int i = 0; i < n; i++) {
            Config.Step sp = st.steps.get(i);
            // MIT en el lado del stop equivale a un stop normal: se envía como orden stop
            b.stepS[i] = markedStop ? 0 : !sp.hasStop() ? -1
                : legFor(b, b.sls, true, st.percent, sp.stopOff, tick, sp.stopType == Config.StopType.TRAIL, false);
            b.stepT[i] = !sp.hasTarget() ? -1
                : legFor(b, b.tps, false, st.percent, sp.targetOff, tick, false, sp.targetType == Config.TargetType.MIT);
        }
        if (b.sls.isEmpty()) b.sls.add(b.sl);
        if (b.tps.isEmpty()) b.tps.add(b.tp);
        for (int i = 0; i < b.sls.size(); i++) b.sls.get(i).no = b.sls.size() > 1 ? i + 1 : 0;
        for (int i = 0; i < b.tps.size(); i++) b.tps.get(i).no = b.tps.size() > 1 ? i + 1 : 0;
        b.multi = true;
        buildSlots(b, q);
    }

    /** La salida de la lista con esa distancia y ese tipo (dos pasos iguales comparten orden), o una nueva. */
    private static int legFor(Bracket b, List<Leg> ls, boolean stop, boolean percent, double off, double tick, boolean trail, boolean mit) {
        double offset = percent ? 0 : Math.max(1, Math.round(off)) * tick, pct = percent ? off : 0;
        for (int i = 0; i < ls.size(); i++) {
            Leg l = ls.get(i);
            if (l.offset == offset && l.offPct == pct && l.trail == trail && l.mit == mit) return i;
        }
        Leg l = ls.isEmpty() ? (stop ? b.sl : b.tp) : new Leg(stop);
        l.offset = offset; l.offPct = pct; l.trail = trail; l.mit = mit;
        ls.add(l);
        return ls.size() - 1;
    }

    /**
     * Asigna a cada contrato el stop y el objetivo de su paso: los primeros contratos son del paso 1,
     * los siguientes del 2… Solo se llama antes de que la entrada se ejecute.
     */
    private static void buildSlots(Bracket b, int q) {
        int[] sq = b.strat.qtys(q);
        b.slotS = new int[q]; b.slotT = new int[q]; b.slotSt = new byte[q];
        java.util.Arrays.fill(b.slotS, b.marked ? 0 : -1);   // lo que no entra en ningún paso solo lleva el stop marcado
        java.util.Arrays.fill(b.slotT, -1);
        int k = 0;
        for (int i = 0; i < sq.length; i++)
            for (int n = 0; n < sq[i] && k < q; n++, k++) { b.slotS[k] = b.stepS[i]; b.slotT[k] = b.stepT[i]; }
        for (Leg l : b.legs()) l.planned = !l.dropped && slots(b, l, PEND) > 0;
    }

    /**
     * Multi: pone cada salida con la cantidad que le toca. Un objetivo cubre sus contratos abiertos; un
     * stop, además, los que faltan por ejecutar si se adelantó al bróker (modo Server). Lo que ya no
     * protege nada se cancela.
     */
    private void syncMulti(OrderContext c, Bracket b) {
        int open = 0;
        for (byte st : b.slotSt) if (st == OPEN) open++;
        b.liveQty = open;
        boolean pend = b.entryPending();
        Enums$OrderAction exit = exitAction(b);
        List<Order> send = new ArrayList<>(), cancel = new ArrayList<>();
        boolean stopLive = false;
        for (Leg l : b.legs()) {
            int mine = slots(b, l, OPEN), waiting = slots(b, l, PEND);
            int want = l.planned && l.resolved() ? mine + (l.stop && l.pre && pend ? waiting : 0) : 0;
            if (l.mit) {
                // objetivo MIT: no hay orden en el bróker; cuando el precio lo toca sale a mercado (ver manage)
                if (mine + waiting == 0) { l.planned = false; l.group = null; }
                continue;
            }
            if (want > 0) {
                if (l.live()) {
                    if (l.order.getQuantity() != want) { l.order.setAdjQuantity(want); send.add(l.order); }
                } else {
                    l.filledSeen = 0; l.pre = false;
                    l.order = l.stop ? c.createStopOrder(exit, Enums$TIF.GTC, want, l.price)
                                     : c.createLimitOrder(exit, Enums$TIF.GTC, want, l.price);
                    send.add(l.order);
                }
            } else if (l.live()) {
                cancel.add(l.order); engineCancels.add(l.order);
                l.order = null; l.filledSeen = 0; l.pre = false;
            }
            if (!pend) l.pre = false;
            if (mine + waiting == 0) { l.planned = false; l.group = null; }   // ya no le queda nada que cerrar
            stopLive |= l.stop && l.live();
        }
        if (!stopLive) b.trailActive = false;
        if (!cancel.isEmpty()) c.cancelOrders(cancel);
        if (!send.isEmpty()) c.submitOrders(send);
    }

    /** Multi: ejecución de uno de sus stops u objetivos: cierra sus contratos y reajusta las demás salidas. */
    private void multiLegFilled(OrderContext c, Bracket b, Order o) {
        Leg me = legOf(b, o);
        int f = filled(o);
        int n = Math.max(0, f - me.filledSeen);
        me.filledSeen = f;
        int idx = (me.stop ? b.sls : b.tps).indexOf(me);
        int[] arr = me.stop ? b.slotS : b.slotT;
        int closed = 0;
        for (int i = 0; i < arr.length && closed < n; i++) if (arr[i] == idx && b.slotSt[i] == OPEN) { b.slotSt[i] = DONE; closed++; }
        // más contratos de los que tenía asignados (no debería ocurrir): se cierran otros abiertos para cuadrar
        for (int i = 0; i < arr.length && closed < n; i++) if (b.slotSt[i] == OPEN) { b.slotSt[i] = DONE; closed++; }
        if (closed < n && me.pre) { nakedWarn = true; me.planned = false; }   // stop antes que la entrada
        if (o.isFilled()) { me.order = null; me.filledSeen = 0; me.pre = false; }
        syncMulti(c, b);
        cleanup(b);
    }

    /**
     * Modo Server: el stop de una entrada LÍMITE aún sin ejecutar se envía al bróker junto con ella,
     * así la protección existe aunque se cierre MotiveWave. Solo es seguro en entradas límite: el stop
     * queda más allá de la entrada, de modo que el precio tiene que pasar antes por ella. El objetivo
     * NO se puede adelantar (se ejecutaría sin posición) y sigue siendo interno. En modo Multi se
     * adelantan todos los stops, cada uno con sus contratos.
     */
    private List<Order> preStops(OrderContext c, Bracket b) {
        List<Order> out = new ArrayList<>();
        if (!cfg.server || b.synth || b.entry == null || b.entry.getType() != Enums$OrderType.LIMIT) return out;
        if (b.liveQty != 0 || b.entryFilledSeen != 0) return out;
        for (Leg l : b.sls) {
            if (!l.planned || !l.resolved() || l.live()) continue;
            int q = b.multi ? slots(b, l, PEND) : b.qty;
            if (q <= 0) continue;
            // misma vigencia que la entrada: si esta caduca, el stop caduca con ella y no queda huérfano
            l.order = c.createStopOrder(exitAction(b), optEntryGtc ? Enums$TIF.GTC : Enums$TIF.DAY, q, l.price);
            l.pre = true; l.filledSeen = 0;
            out.add(l.order);
        }
        return out;
    }

    /** Fija los precios absolutos de las salidas y del disparador a partir del precio de entrada. */
    private void resolve(OrderContext c, Bracket b, float ref) {
        Instrument in = c.getInstrument();
        b.ref = ref;
        if (b.beTrigPct > 0) { b.beTrig = ref * b.beTrigPct / 100.0; b.beOff = ref * b.beOffPct / 100.0; }
        for (Leg l : b.legs()) {
            double off = l.offPct > 0 ? Math.max(in.getTickSize(), ref * l.offPct / 100.0) : l.offset;
            if (l.trail && off > 0) l.trailDist = off;
            if (l.resolved() || !(l.planned || off > 0)) continue;
            // stop de una compra y objetivo de una venta: por debajo de la entrada; los otros dos, por encima
            l.price = (float) in.round(l.stop == b.buy ? ref - off : ref + off);
        }
        if (b.trail && Float.isNaN(b.trailTrigger)) b.trailTrigger = (float) in.round(b.buy ? ref + b.trailTrigDist : ref - b.trailTrigDist);
    }

    private void entryFilled(OrderContext c, Bracket b, Order o) {
        int f = filled(o);
        int n = f - b.entryFilledSeen;
        if (n <= 0) return;
        b.entryFilledSeen = f;
        float px = o.getAvgFillPrice();
        if (px > 0) resolve(c, b, px);   // fija las salidas que aún dependían del precio de ejecución (entradas MKT)
        if (o.isFilled() || f >= b.qty) b.entryDone = true;
        activate(c, b, n);
        if (b.link != null) {
            Object g = b.link;
            List<Order> cancel = new ArrayList<>();
            for (Iterator<Bracket> it = brackets.iterator(); it.hasNext();) {
                Bracket other = it.next();
                if (other.link != g) continue;
                other.link = null;
                if (other == b || !other.entryPending()) continue;
                if (other.synth) { if (other.liveQty == 0) it.remove(); else other.synth = false; }
                else if (other.entry != null && other.entry.isActive()) cancel.add(other.entry);
            }
            if (!cancel.isEmpty()) c.cancelOrders(cancel);
        }
        cleanup(b);
    }

    /** Convierte las salidas internas en órdenes vivas para la cantidad recién ejecutada. */
    private void activate(OrderContext c, Bracket b, int n) {
        if (b.multi) {
            int left = n;
            for (int i = 0; i < b.slotSt.length && left > 0; i++) if (b.slotSt[i] == PEND) { b.slotSt[i] = OPEN; left--; }
            syncMulti(c, b);
            fixRisk(c, b);
            return;
        }
        b.liveQty += n;
        Enums$OrderAction exit = exitAction(b);
        List<Order> send = new ArrayList<>();
        for (Leg l : new Leg[]{b.sl, b.tp}) {
            if (!l.planned || !l.resolved()) continue;
            if (l.live()) {
                if (l.pre) {
                    // el stop adelantado ya cubre toda la entrada; solo se ajusta si esta terminó incompleta
                    if (!b.entryDone || l.order.getQuantity() == b.liveQty) continue;
                    l.pre = false;
                }
                l.order.setAdjQuantity(b.liveQty);
            } else {
                l.pre = false;
                l.filledSeen = 0;
                l.order = l.stop ? c.createStopOrder(exit, Enums$TIF.GTC, b.liveQty, l.price)
                                 : c.createLimitOrder(exit, Enums$TIF.GTC, b.liveQty, l.price);
            }
            send.add(l.order);
        }
        if (!send.isEmpty()) c.submitOrders(send);
        fixRisk(c, b);
    }

    /** Fija el riesgo inicial de un bracket la primera vez que tiene contratos abiertos con stop. */
    private void fixRisk(OrderContext c, Bracket b) {
        if (!Double.isNaN(b.riskPer) || b.liveQty <= 0) return;
        b.riskPer = riskPerLive(c, b, b.positional ? c.getAvgEntryPrice() : b.ref);
    }

    /** Pérdida media por contrato si saltan los stops del bracket ahora, desde {@code ref}; NaN si no hay stops o no pierden. */
    private double riskPerLive(OrderContext c, Bracket b, float ref) {
        if (Float.isNaN(ref)) return Double.NaN;
        double pv = pointValue(c), tot = 0;
        int n = 0;
        if (!b.multi) {
            Leg s = b.sl;
            float sp = s.live() ? orderPrice(s.order) : s.planned ? s.price : Float.NaN;
            if (Float.isNaN(sp)) return Double.NaN;
            tot = (b.buy ? ref - sp : sp - ref) * pv; n = 1;
        } else {
            for (int i = 0; i < b.slotSt.length; i++) {
                if (b.slotSt[i] == DONE || b.slotS[i] < 0) continue;
                Leg s = b.sls.get(b.slotS[i]);
                float sp = s.live() ? orderPrice(s.order) : s.planned ? s.price : Float.NaN;
                if (Float.isNaN(sp)) continue;
                tot += (b.buy ? ref - sp : sp - ref) * pv; n++;
            }
        }
        return n > 0 && tot > 1e-9 ? tot / n : Double.NaN;
    }

    /** Riesgo por contrato para el R de las etiquetas: el inicial si ya está fijado; si no, el de ahora. */
    private double riskOf(OrderContext c, Bracket b, float ref) {
        return !Double.isNaN(b.riskPer) ? b.riskPer : riskPerLive(c, b, ref);
    }

    // ------------------------------------------------------------------
    //  textos de importe, riesgo y R de las etiquetas
    // ------------------------------------------------------------------

    /** Importe en la unidad elegida: dinero, % del saldo inicial o actual, o distancia (ticks / puntos). */
    private String amount(OrderContext c, Config.Unit u, double money, double dist, boolean plus) {
        double tick = c.getInstrument().getTickSize();
        switch (u) {
            case TICKS: {
                double t = dist / tick;
                boolean whole = Math.abs(t - Math.rint(t)) < 0.05;
                return String.format(T.loc(), (plus ? "%+" : "%") + (whole ? ".0f" : ".1f") + " ticks", whole ? Math.rint(t) : t);
            }
            case POINTS:
                return String.format(T.loc(), (plus ? "%+" : "%") + ".2f pts", dist);
            case PCT_INI: case PCT_CUR: {
                double bal = u == Config.Unit.PCT_INI ? initialBalance(c) : currentBalance(c);
                if (!Double.isNaN(bal) && bal > 0) return String.format(T.loc(), (plus ? "%+" : "%") + ".2f %%", money / bal * 100);
                break;
            }
            default:
        }
        return plus ? String.format(T.loc(), "%+.2f $", money) : money(money);
    }

    /** Stop que se marca en modo Risk: como en DeepCharts, el riesgo se muestra en positivo (y su R es -1). */
    private String riskLabel(OrderContext c, double risk, double dist) {
        List<String> parts = new ArrayList<>();
        if (optExitAmt) parts.add(amount(c, optExitUnit, risk, dist, false));
        if (optExitR) parts.add(rText(-1));
        return parts.isEmpty() ? null : String.join(" | ", parts);
    }

    private static String rText(double r) { return String.format(T.loc(), "%.1fR", r); }

    /** Texto tras la etiqueta de un SL/TP (o de su vista previa): importe y R según los ajustes; null si nada. */
    private String exitLabel(OrderContext c, double money, double dist, double risk) {
        List<String> parts = new ArrayList<>();
        if (optExitAmt) parts.add(amount(c, optExitUnit, money, dist, false));
        if (optExitR && !Double.isNaN(risk) && risk > 1e-9) parts.add(rText(money / risk));
        return parts.isEmpty() ? null : String.join(" | ", parts);
    }

    /** Texto tras la etiqueta de la posición: P/L y, según los ajustes, R y riesgo de sus stops. */
    private String positionLabel(OrderContext c, int pos, float avg) {
        double pl = c.getUnrealizedPnL();
        StringBuilder sb = new StringBuilder(money(pl));
        if (!optPosR && !optPosRisk) return sb.toString();
        boolean lng = pos > 0;
        double cur = 0, dist = 0, init = 0;
        int cov = 0;
        for (Bracket b : brackets) {
            if (b.buy != lng || b.liveQty <= 0) continue;
            fixRisk(c, b);
            for (Leg s : b.sls) {
                if (!s.live()) continue;
                int q = b.multi ? slots(b, s, OPEN) : b.liveQty;
                float sp = orderPrice(s.order);
                if (q <= 0 || Float.isNaN(sp)) continue;
                cur += pnl(c, avg, sp, lng ? q : -q);          // < 0 = pérdida si salta; > 0 = beneficio asegurado
                dist += (lng ? sp - avg : avg - sp) * q;
                cov += q;
                if (!Double.isNaN(b.riskPer)) init += b.riskPer * q;
            }
        }
        if (cov == 0) return sb.toString();                    // sin stops no hay riesgo ni R
        if (optPosR) {
            double base = optPosRCurrent ? (cur < -1e-9 ? -cur : Double.NaN) : (init > 1e-9 ? init : Double.NaN);
            sb.append(" | ").append(Double.isNaN(base) ? "\u2014R" : rText(pl / base));
        }
        if (optPosRisk) sb.append(" | ").append(T.t("riesgo ")).append(amount(c, optPosUnit, cur, dist / cov, true));
        return sb.toString();
    }

    private void legFilled(OrderContext c, Bracket b, Order o) {
        if (b.multi) { multiLegFilled(c, b, o); return; }
        Leg me = same(b.sl.order, o) ? b.sl : b.tp;
        Leg other = me == b.sl ? b.tp : b.sl;
        int f = filled(o);
        int n = Math.max(0, f - me.filledSeen);
        me.filledSeen = f;
        if (me.pre && b.liveQty < n) { nakedWarn = true; me.planned = false; }   // no debería ocurrir: stop antes que la entrada
        b.liveQty = Math.max(0, b.liveQty - n);
        if (o.isFilled() || b.liveQty <= 0) {
            me.order = null; me.filledSeen = 0; me.pre = false;
            b.liveQty = 0; b.trailActive = false;
            if (other.live()) { engineCancels.add(other.order); c.cancelOrders(other.order); }
            other.order = null; other.filledSeen = 0;
        } else if (other.live()) {
            other.order.setAdjQuantity(b.liveQty);
            c.submitOrders(other.order);
        }
        cleanup(b);
    }

    /** Orden cancelada o rechazada. */
    private void closed(Order o) {
        if (o == null) return;
        clearPending(o);
        boolean mine = removeSame(engineCancels, o);
        Bracket b = byEntry(o);
        if (b != null) {
            b.entryDone = true; b.link = null;
            OrderContext c = ctx;
            if (b.multi) {
                // lo que no llegó a ejecutarse ya no cuenta; los stops adelantados se ajustan o se retiran
                for (int i = 0; i < b.slotSt.length; i++) if (b.slotSt[i] == PEND) b.slotSt[i] = DONE;
                if (c != null) syncMulti(c, b);
                cleanup(b);
                return;
            }
            Leg s = b.sl;
            if (s.pre && s.live() && c != null) {
                // la entrada ya no existe: su stop adelantado se retira (o se queda por lo ya ejecutado)
                if (b.liveQty <= 0) {
                    engineCancels.add(s.order);
                    c.cancelOrders(s.order);
                    s.order = null; s.planned = false;
                } else if (s.order.getQuantity() != b.liveQty) {
                    s.order.setAdjQuantity(b.liveQty);
                    c.submitOrders(s.order);
                }
                s.pre = false;
            }
            cleanup(b);
            return;
        }
        Bracket lb = byLeg(o);
        if (lb == null) return;
        Leg l = legOf(lb, o);
        l.order = null; l.filledSeen = 0; l.pre = false;
        if (!mine) { l.planned = false; l.dropped = true; }
        cleanup(lb);
    }

    /** Un bracket sin entrada pendiente y sin salidas vivas ya no tiene nada que gestionar. */
    private void cleanup(Bracket b) {
        if (b.entryPending()) return;
        for (Leg l : b.legs()) if (l.live() || (l.mit && l.planned && slots(b, l, OPEN) > 0)) return;
        brackets.remove(b);
    }

    // ------------------------------------------------------------------
    //  botones de las etiquetas
    // ------------------------------------------------------------------

    private void entryButton(OrderContext c, Bracket b, String btn) {
        if ("X".equals(btn)) {
            if (b.synth) { b.synth = false; b.link = null; cleanup(b); setStatus(T.t("STP LMT cancelada")); return; }
            if (b.entry != null && b.entry.isActive()) { c.cancelOrders(b.entry); setStatus(T.t("Cancelando orden…")); }
            return;
        }
        boolean stop = "SL".equals(btn);
        if (!stop && !"TP".equals(btn)) return;
        togglePick(c, (b.synth ? "Y" : "E") + b.serial, stop);
    }

    // ------------------------------------------------------------------
    //  SL / TP manual: botón de la etiqueta y después el precio en el gráfico
    // ------------------------------------------------------------------

    private void togglePick(OrderContext c, String id, boolean stop) {
        Pick p = pick;
        if (p != null && p.id.equals(id) && p.stop == stop) { pick = null; hover = null; setStatus(T.t("Colocación cancelada")); return; }
        startPick(c, id, stop);
    }

    private boolean startPick(OrderContext c, String id, boolean stop) {
        String bad = pickError(c, id, stop);
        if (bad != null) { setStatus(bad); return false; }
        armed = null; hover = null;
        pick = new Pick(id, stop);
        setStatus("");
        return true;
    }

    /** Motivo por el que esa salida no se puede colocar (ya existe, ya no hay posición…), o null. */
    private String pickError(OrderContext c, String id, boolean stop) {
        String what = stop ? "SL" : "TP";
        if ("POS".equals(id)) {
            if (c.getPosition() == 0) return what + T.t(": no hay posición abierta");
            if (uncovered(c, stop) <= 0) return what + T.t(": toda la posición ya tiene ") + (stop ? "stop" : T.t("objetivo")) + T.t(" (arrástralo para moverlo)");
            return null;
        }
        Bracket b = bySerial(id);
        if (b == null || !b.entryPending()) return what + T.t(": la orden ya no está pendiente");
        if (anyPlanned(stop ? b.sls : b.tps)) return what + T.t(" ya definido para esta orden: arrástralo para moverlo");
        return null;
    }

    /** Contratos de la posición que aún no tienen stop (u objetivo) vivo. */
    private int uncovered(OrderContext c, boolean stop) {
        int covered = 0;
        for (Bracket b : brackets) covered += coveredQty(b, stop);
        return Math.abs(c.getPosition()) - covered;
    }

    /** Precio de entrada de una orden pendiente (real o STP LMT emulada). */
    private float entryRef(Bracket b) { return b.synth ? b.synthStop : orderPrice(b.entry); }

    /** Coloca el SL/TP elegido en el precio marcado. Si el precio no vale, sigue armado para reintentar. */
    private void placePick(OrderContext c, float px) {
        Pick p = pick;
        String bad = pickError(c, p.id, p.stop);
        if (bad != null) { pick = null; hover = null; setStatus(bad); return; }
        String what = p.stop ? "SL" : "TP";
        if ("POS".equals(p.id)) {
            boolean lng = c.getPosition() > 0;
            String err = sideError(c, !lng, p.stop ? Kind.STOP : Kind.LIMIT, px);
            if (err != null) { setStatus(what + T.t(" NO colocado: ") + err); return; }
            Bracket pb = positional(c, lng, uncovered(c, p.stop), p.stop);
            Leg l = pb.leg(p.stop);
            l.planned = true; l.price = px; l.filledSeen = 0; l.pre = false;
            l.order = p.stop ? c.createStopOrder(exitAction(pb), Enums$TIF.GTC, pb.liveQty, px)
                             : c.createLimitOrder(exitAction(pb), Enums$TIF.GTC, pb.liveQty, px);
            c.submitOrders(l.order);
            pick = null; hover = null;
            int j = joinAt(pb, l, px);
            setStatus(what + T.t(" creado en ") + fmt(px) + T.t(" para ") + pb.liveQty + joinedText(j, p.stop) + T.t(" (arrástralo para ajustarlo)"));
            return;
        }
        Bracket b = bySerial(p.id);
        float ref = entryRef(b);
        if (Float.isNaN(ref)) { pick = null; hover = null; return; }
        boolean lossSide = b.buy ? px < ref : px > ref;
        if (px == ref || p.stop != lossSide) {
            setStatus(what + T.t(" NO colocado: el ") + (p.stop ? T.t("stop debe quedar en el lado de pérdida") : T.t("objetivo debe quedar en el lado de beneficio"))
                + T.t(" de la entrada (") + fmt(ref) + ")");
            return;
        }
        Leg l = b.leg(p.stop);
        l.planned = true; l.price = px; l.pre = false;
        if (b.multi) {
            // no le queda ninguna salida de ese tipo: la nueva cubre todos los contratos que sigan en juego
            int[] arr = p.stop ? b.slotS : b.slotT;
            for (int i = 0; i < arr.length; i++) if (b.slotSt[i] != DONE) arr[i] = 0;
            l.dropped = false; l.no = 0;
        }
        if (Float.isNaN(b.ref)) b.ref = ref;
        pick = null; hover = null;
        String where = T.t(" interno");
        if (b.liveQty > 0) {
            // entrada parcialmente ejecutada: lo ya ejecutado queda cubierto ahora mismo
            String err = sideError(c, !b.buy, p.stop ? Kind.STOP : Kind.LIMIT, px);
            if (err == null) {
                if (b.multi) syncMulti(c, b);
                else {
                    l.filledSeen = 0;
                    l.order = p.stop ? c.createStopOrder(exitAction(b), Enums$TIF.GTC, b.liveQty, px)
                                     : c.createLimitOrder(exitAction(b), Enums$TIF.GTC, b.liveQty, px);
                    c.submitOrders(l.order);
                }
                where = "";
            }
        } else if (p.stop) {
            List<Order> pre = preStops(c, b);
            if (!pre.isEmpty()) { c.submitOrders(pre); where = T.t(" (ya en el bróker)"); }
        }
        int j = joinAt(b, l, px);
        setStatus(what + where + T.t(" añadido en ") + fmt(px) + joinedText(j, p.stop) + T.t(" (arrástralo para ajustarlo)"));
    }

    private static String joinedText(int joined, boolean stop) {
        return joined > 0 ? T.t(" · agrupado con ") + joined + (stop ? " stop" : T.t(" objetivo")) + (joined > 1 ? "s" : "") : "";
    }

    private void legButton(OrderContext c, Bracket b, Leg l, String btn) {
        if (!groupable(b, l)) { legButton1(c, b, l, btn); return; }
        List<Bracket> members = groupOf(b, l.stop);
        if (btn.startsWith("#")) {
            for (Bracket mb : members) mb.leg(l.stop).group = null;
            setStatus((l.stop ? "Stops" : T.t("Objetivos")) + T.t(" separados: arrastra cada uno por su lado"));
            return;
        }
        if (members.size() > 1) {
            if ("TS".equals(btn) && l.stop) {
                boolean on = false;
                for (Bracket mb : members) if (!mb.trail) on = true;      // si alguno no lo tiene, se activa en todos
                for (Bracket mb : members) if (mb.trail != on) legButton1(c, mb, mb.sl, "TS");
                setStatus("Trailing " + (on ? T.t("activado") : T.t("desactivado")) + T.t(" en los ") + members.size() + T.t(" stops del grupo"));
                return;
            }
            if ("X".equals(btn)) {
                for (Bracket mb : members) legButton1(c, mb, mb.leg(l.stop), "X");
                setStatus(members.size() + (l.stop ? " stops" : T.t(" objetivos")) + T.t(" cancelados"));
                return;
            }
        }
        legButton1(c, b, l, btn);
    }

    private void legButton1(OrderContext c, Bracket b, Leg l, String btn) {
        if ("X".equals(btn)) {
            l.group = null;
            l.planned = false;
            l.dropped = true;
            if (l.live()) c.cancelOrders(l.order);
            if (l.stop && !anyPlanned(b.sls)) { b.trail = false; b.trailActive = false; b.autoBe = false; }
            cleanup(b);
            return;
        }
        if ("TS".equals(btn) && l.stop) {
            b.trail = !b.trail;
            b.trailActive = false;
            if (b.trail) {
                Instrument in = c.getInstrument();
                double tick = in.getTickSize();
                b.trailTrigDist = cfg.trailTrigger * tick;
                b.trailDist = cfg.trailDist * tick;
                float base = !Float.isNaN(b.ref) ? b.ref : last(c);
                b.trailTrigger = (float) in.round(b.buy ? base + b.trailTrigDist : base - b.trailTrigDist);
                setStatus(T.t("Trailing activado: empieza al tocar ") + fmt(b.trailTrigger) + T.t(" (arrastra la línea Trail. Trigger)"));
            } else setStatus(T.t("Trailing desactivado"));
        }
    }

    private void positionButton(OrderContext c, String btn) {
        int pos = c.getPosition();
        if (pos == 0) return;
        if ("X".equals(btn)) { c.closeAtMarket(); setStatus(T.t("Cerrando posición a mercado")); return; }
        boolean stop = "SL".equals(btn);
        if (!stop && !"TP".equals(btn)) return;
        // el precio lo decide el usuario en el gráfico (no la configuración del panel)
        togglePick(c, "POS", stop);
    }

    /** Bracket de la posición (sin orden de entrada) al que le falte la salida pedida, o uno nuevo. */
    private Bracket positional(OrderContext c, boolean lng, int qty, boolean stop) {
        for (Bracket b : brackets) if (b.positional && b.buy == lng && b.liveQty > 0 && !b.leg(stop).live()) return b;
        Bracket b = newBracket(lng, qty);
        b.positional = true; b.entryDone = true; b.liveQty = qty; b.entryFilledSeen = qty;
        b.ref = c.getAvgEntryPrice();
        double tick = c.getInstrument().getTickSize();
        b.trailTrigDist = cfg.trailTrigger * tick; b.trailDist = cfg.trailDist * tick;
        brackets.add(b);
        return b;
    }

    // ------------------------------------------------------------------
    //  arrastres
    // ------------------------------------------------------------------

    private void moveEntry(OrderContext c, Bracket b, float px) {
        Order o = b.entry;
        if (o == null || !o.isActive()) return;
        float old = orderPrice(o);
        if (Float.isNaN(old) || px == old) return;
        boolean lim = o.getType() == Enums$OrderType.LIMIT;
        if (!lim && o.getType() != Enums$OrderType.STOP) return;
        String bad = sideError(c, o.isBuy(), lim ? Kind.LIMIT : Kind.STOP, px);
        if (bad != null) { setStatus(T.t("NO movida: ") + bad); return; }
        if (lim) o.setAdjLimitPrice(px); else o.setAdjStopPrice(px);
        setPending(o, px);
        c.submitOrders(o);
        shiftPlan(c, b, px - old);
        setStatus(T.t("Orden movida a ") + fmt(px));
    }

    private void moveSynth(OrderContext c, Bracket b, float px) {
        if (!b.synth || px == b.synthStop) return;
        String bad = sideError(c, b.buy, Kind.STOP_LIMIT, px);
        if (bad != null) { setStatus(T.t("NO movida: ") + bad); return; }
        Instrument in = c.getInstrument();
        float delta = px - b.synthStop;
        b.synthStop = px;
        b.synthLimit = (float) in.round(b.buy ? px + optStpLmtOffset * in.getTickSize() : px - optStpLmtOffset * in.getTickSize());
        shiftPlan(c, b, delta);
        setStatus(T.t("STP LMT movida a ") + fmt(px));
    }

    /** Al mover una entrada pendiente, sus salidas internas y el disparador se mueven con ella. */
    private void shiftPlan(OrderContext c, Bracket b, float delta) {
        Instrument in = c.getInstrument();
        if (!Float.isNaN(b.ref) && b.liveQty == 0) b.ref = (float) in.round(b.ref + delta);
        for (Leg l : b.legs()) {
            if (!l.planned || !l.resolved() || l.group != null) continue;   // una salida agrupada se queda con su grupo
            if (!l.live()) l.price = (float) in.round(l.price + delta);
            else if (l.pre && b.liveQty == 0) {
                // stop adelantado en el bróker: se mueve la orden real con la entrada
                float np = (float) in.round(orderPrice(l.order) + delta);
                l.order.setAdjStopPrice(np);
                setPending(l.order, np);
                c.submitOrders(l.order);
                l.price = np;
            }
        }
        if (b.trail && !b.trailActive && !Float.isNaN(b.trailTrigger) && b.liveQty == 0)
            b.trailTrigger = (float) in.round(b.trailTrigger + delta);
    }

    /**
     * Mueve una salida (o su grupo entero) y, si queda en el mismo precio que otras salidas del
     * mismo tipo y lado, las agrupa con ella: desde entonces se ven como una sola línea con la
     * cantidad y el importe sumados, y se mueven, se cancelan y activan el trailing juntas. Cada
     * una sigue siendo su propia orden (su OCO no cambia). El botón "#N" las vuelve a separar.
     */
    private void moveLegs(OrderContext c, Bracket b, Leg l, float px) {
        if (!l.planned) return;
        List<Bracket> members = groupable(b, l) ? groupOf(b, l.stop) : List.of(b);
        for (Bracket mb : members) {
            String err = legMoveError(c, mb, peer(mb, b, l), px);
            if (err != null) { setStatus(T.t("NO movido: ") + err); return; }
        }
        boolean trailOff = false, moved = false;
        for (Bracket mb : members) {
            Leg ml = peer(mb, b, l);
            if (legPrice(ml) == px) continue;
            moved = true;
            trailOff |= applyLegMove(c, mb, ml, px);
        }
        String what = l.no > 0 ? (l.stop ? "SL" : "TP") + l.no : l.stop ? "Stop" : T.t("Objetivo");
        int joined = joinAt(b, l, px);
        if (joined > 0) {
            int n = groupOf(b, l.stop).size();
            setStatus(T.t("Agrupados ") + n + (l.stop ? " stops" : T.t(" objetivos")) + T.t(" en ") + fmt(px)
                + T.t(": se mueven y se cancelan juntos (#") + n + T.t(" los separa)"));
        } else if (moved) {
            String s;
            if (members.size() > 1) s = members.size() + (l.stop ? " stops" : T.t(" objetivos")) + T.t(" movidos a ") + fmt(px);
            else s = what + (l.live() ? T.t(" movido a ") : T.t(" interno en ")) + fmt(px);
            setStatus(s + (trailOff ? T.t(" (trailing desactivado)") : ""));
        }
    }

    /** Por qué esta salida no puede ir a ese precio, o null. */
    private String legMoveError(OrderContext c, Bracket b, Leg l, float px) {
        if (!l.planned) return T.t("la salida ya no existe");
        if (l.live() && b.entryPending() && b.liveQty == 0) {
            // stop adelantado de una entrada aún sin ejecutar: no puede cruzar la entrada (se ejecutaría sin posición)
            float eref = entryRef(b);
            if (!Float.isNaN(eref) && (b.buy ? px >= eref : px <= eref))
                return T.t("el stop debe quedar en el lado de pérdida de la entrada (") + fmt(eref) + ")";
        }
        if (l.live()) return sideError(c, !b.buy, l.stop ? Kind.STOP : Kind.LIMIT, px);
        float ref = b.synth ? b.synthStop : (b.entry != null ? orderPrice(b.entry) : b.ref);
        if (!Float.isNaN(ref)) {
            boolean lossSide = b.buy ? px < ref : px > ref;
            if (l.stop != lossSide)
                return T.t("el ") + (l.stop ? T.t("stop debe quedar en el lado de pérdida") : T.t("objetivo debe quedar en el lado de beneficio")) + T.t(" de la entrada (") + fmt(ref) + ")";
        }
        return null;
    }

    /** Aplica el movimiento ya validado. Devuelve true si desactivó un trailing en marcha. */
    private boolean applyLegMove(OrderContext c, Bracket b, Leg l, float px) {
        if (!l.live()) { l.price = px; return false; }
        if (l.stop) l.order.setAdjStopPrice(px); else l.order.setAdjLimitPrice(px);
        setPending(l.order, px);
        c.submitOrders(l.order);
        l.price = px;
        boolean wasTrailing = l.stop && b.trailActive;
        if (wasTrailing) { b.trailActive = false; b.trail = false; }   // el arrastre manual manda sobre el trailing
        if (l.trail) { l.trail = false; wasTrailing = true; }          // también sobre un stop tipo Trail
        return wasTrailing;
    }

    // ------------------------------------------------------------------
    //  grupos de salidas
    // ------------------------------------------------------------------

    /** La salida se ve en el gráfico (orden viva o hija interna de una entrada pendiente). */
    private static boolean legShown(Bracket b, Leg l) {
        if (l.live()) return true;
        if (!l.planned || !l.resolved()) return false;
        return (b.entryPending() && b.remaining() > 0) || (l.mit && slots(b, l, OPEN) > 0);
    }

    private float legPrice(Leg l) { return l.live() ? orderPrice(l.order) : l.price; }

    /** Brackets cuyo stop (u objetivo) está en el mismo grupo que el de {@code b}; solo {@code b} si va suelto. */
    private List<Bracket> groupOf(Bracket b, boolean stop) {
        List<Bracket> out = new ArrayList<>();
        Object g = b.leg(stop).group;
        if (g == null) { out.add(b); return out; }
        for (Bracket o : brackets) if (o.leg(stop).group == g) out.add(o);
        if (!out.contains(b)) out.add(0, b);
        return out;
    }

    /** Agrupa con la salida de {@code b} las salidas del mismo tipo y lado que estén en {@code px}. Devuelve cuántas se unieron. */
    private int joinAt(Bracket b, Leg l, float px) {
        if (!groupable(b, l)) return 0;
        List<Leg> join = new ArrayList<>();
        for (Bracket o : brackets) {
            Leg ol = o.leg(l.stop);
            if (o == b || o.buy != b.buy || !groupable(o, ol) || !legShown(o, ol)) continue;
            if (l.group != null && ol.group == l.group) continue;
            if (legPrice(ol) == px) join.add(ol);
        }
        if (join.isEmpty()) return 0;
        // pila: lo que ya estaba en ese precio queda debajo y lo que llega (l, o su grupo entero) encima
        List<Leg> moving = new ArrayList<>();
        if (l.group != null) for (Bracket o : brackets) if (o.leg(l.stop).group == l.group) moving.add(o.leg(l.stop));
        if (!moving.contains(l)) moving.add(l);
        moving.sort((x, y) -> Long.compare(x.joinSeq, y.joinSeq));
        Object g = l.group != null ? l.group : new Object();
        l.group = g;
        for (Leg ol : join) {
            if (ol.group == null) ol.joinSeq = ++joinCounter;  // suelta hasta ahora: entra a la pila
            else if (ol.group != g) {                          // une dos grupos: todos pasan a uno
                Object old = ol.group;
                for (Bracket o : brackets) if (o.leg(l.stop).group == old) o.leg(l.stop).group = g;
            }
            ol.group = g;
        }
        for (Leg ml : moving) ml.joinSeq = ++joinCounter;
        return join.size();
    }

    /** La última salida que entró en el grupo (empate: la orden más reciente). */
    private static Bracket stackTop(List<Bracket> members, boolean stop) {
        Bracket top = null;
        for (Bracket mb : members) {
            if (top == null) { top = mb; continue; }
            long a = mb.leg(stop).joinSeq, t = top.leg(stop).joinSeq;
            if (a > t || (a == t && mb.serial > top.serial)) top = mb;
        }
        return top;
    }

    /** Contratos que cubre una salida (la orden viva o lo que queda por ejecutar de su entrada). */
    private static int legQty(Bracket b, Leg l) {
        return l.live() ? l.order.getQuantity() : pendQty(b, l);
    }

    /** Disuelve los grupos que ya no tienen sentido: con un solo miembro o con miembros a precios distintos. */
    private void normalizeGroups() {
        Map<Object, List<Leg>> by = new IdentityHashMap<>();
        for (Bracket b : brackets) for (Leg l : new Leg[]{b.sl, b.tp}) {
            if (l.group == null) continue;
            if (!legShown(b, l)) { l.group = null; continue; }
            by.computeIfAbsent(l.group, k -> new ArrayList<>()).add(l);
        }
        for (List<Leg> ls : by.values()) {
            boolean ok = ls.size() > 1;
            float p0 = legPrice(ls.get(0));
            for (Leg l : ls) if (legPrice(l) != p0) ok = false;
            if (!ok) for (Leg l : ls) l.group = null;
        }
    }

    // ------------------------------------------------------------------
    //  gestión en cada tick
    // ------------------------------------------------------------------

    /** STP LMT emulado: cuando el último precio toca el stop se envía la orden límite. */
    private void checkSynths(OrderContext c) {
        float last = lastPrice;
        if (last <= 0) return;
        for (Bracket b : new ArrayList<>(brackets)) {
            if (!b.synth) continue;
            if (b.buy ? last < b.synthStop : last > b.synthStop) continue;
            b.synth = false;
            Enums$OrderAction act = b.buy ? Enums$OrderAction.BUY : Enums$OrderAction.SELL;
            b.entry = c.createLimitOrder(act, optEntryGtc ? Enums$TIF.GTC : Enums$TIF.DAY, b.qty, b.synthLimit);
            c.submitOrders(b.entry);
            setStatus(T.t("STP LMT disparada: límite en ") + fmt(b.synthLimit));
        }
    }

    /** BE automático y trailing stop de los brackets con stop vivo. */
    private void manage(OrderContext c) {
        float px = lastPrice;
        if (px <= 0) return;
        Instrument in = c.getInstrument();
        double tick = in.getTickSize();
        long now = System.currentTimeMillis();
        for (Bracket b : new ArrayList<>(brackets)) {
            if (b.liveQty <= 0) continue;
            if (b.multi) {
                // objetivos MIT: cuando el precio los toca, sus contratos salen a mercado
                for (Leg t : b.tps) {
                    if (!t.mit || !t.planned || !t.resolved() || t.live() || now - t.lastMoveMs < 3000) continue;
                    int q = slots(b, t, OPEN);
                    if (q <= 0 || (b.buy ? px < t.price : px > t.price)) continue;
                    t.lastMoveMs = now;
                    t.filledSeen = 0;
                    t.order = c.createMarketOrder(exitAction(b), q);
                    setStatus(T.t("MIT tocado en ") + fmt(t.price) + ": " + signed(!b.buy, q) + T.t(" a mercado"));
                    c.submitOrders(t.order);
                }
                if (b.liveQty <= 0) continue;
            }
            List<Leg> stops = new ArrayList<>();
            for (Leg st : b.sls) if (st.live() && !Float.isNaN(orderPrice(st.order))) stops.add(st);
            if (stops.isEmpty()) continue;
            float ref = b.positional ? c.getAvgEntryPrice() : b.ref;
            if (b.autoBe && !b.beDone && !Float.isNaN(ref)) {
                double fav = b.buy ? px - ref : ref - px;
                if (fav >= b.beTrig) {
                    float be = (float) in.round(b.buy ? ref + b.beOff : ref - b.beOff);
                    b.beDone = true;
                    for (Leg st : stops) {
                        if (improves(b, orderPrice(st.order), be, tick) && stopValid(c, b, be)) {
                            modifyStop(c, st, be);
                            setStatus(T.t("BE automático: stop a ") + fmt(be));
                        }
                    }
                }
            }
            // stops tipo Trail de la estrategia: siguen al precio a su distancia desde el principio
            for (Leg st : stops) {
                if (!st.trail || st.trailDist <= 0 || now - st.lastMoveMs < 300) continue;
                float want = (float) in.round(b.buy ? px - st.trailDist : px + st.trailDist);
                if (improves(b, orderPrice(st.order), want, tick) && stopValid(c, b, want)) { st.lastMoveMs = now; modifyStop(c, st, want); }
            }
            if (!b.trail) continue;
            if (!b.trailActive) {
                if (Float.isNaN(b.trailTrigger)) b.trailTrigger = (float) in.round(b.buy ? ref + b.trailTrigDist : ref - b.trailTrigDist);
                if (b.buy ? px >= b.trailTrigger : px <= b.trailTrigger) {
                    b.trailActive = true;
                    if (b.trailDist <= 0) b.trailDist = Math.abs(px - orderPrice(stops.get(0).order));
                    setStatus(T.t("Trailing en marcha (distancia ") + Math.round(b.trailDist / tick) + " ticks)");
                }
            }
            if (b.trailActive && now - b.lastTrailMs >= 300) {
                float want = (float) in.round(b.buy ? px - b.trailDist : px + b.trailDist);
                for (Leg st : stops) {
                    if (improves(b, orderPrice(st.order), want, tick) && stopValid(c, b, want)) {
                        b.lastTrailMs = now;
                        modifyStop(c, st, want);
                    }
                }
            }
        }
    }

    private static boolean improves(Bracket b, float cur, float want, double tick) {
        return b.buy ? want >= cur + tick * 0.999 : want <= cur - tick * 0.999;
    }

    private boolean stopValid(OrderContext c, Bracket b, float px) {
        return b.buy ? px < bid(c) : px > ask(c);
    }

    private void modifyStop(OrderContext c, Leg st, float px) {
        Order o = st.order;
        o.setAdjStopPrice(px);
        setPending(o, px);
        st.price = px;
        c.submitOrders(o);
    }

    // ------------------------------------------------------------------
    //  cálculos
    // ------------------------------------------------------------------

    private float last(OrderContext c) {
        float v = lastPrice;
        if (v <= 0) v = c.getInstrument().getLastPrice();
        return v;
    }

    private float bid(OrderContext c) { float b = c.getInstrument().getBidPrice(); return b > 0 ? b : last(c); }
    private float ask(OrderContext c) { float a = c.getInstrument().getAskPrice(); return a > 0 ? a : last(c); }

    /** Valor monetario de un punto de precio por contrato. */
    private double pointValue(OrderContext c) {
        Instrument in = c.getInstrument();
        double tick = in.getTickSize();
        float p = last(c);
        double v = 0;
        try { if (p > 0 && tick > 0) v = Math.abs(c.calcPnl(p, (float) (p + tick), 1)) / tick; } catch (Throwable ignore) { }
        if (v > 0 && !Double.isNaN(v) && !Double.isInfinite(v)) return v;
        double pv = in.getPointValue();
        return pv > 0 ? pv : 1;
    }

    private double tickValue(OrderContext c) { return pointValue(c) * c.getInstrument().getTickSize(); }

    private double pnl(OrderContext c, float from, float to, int signedQty) { return (to - from) * signedQty * pointValue(c); }

    /** Distancia del stop en precio según el panel (ticks, o dinero repartido entre la cantidad). */
    private double slDist(OrderContext c, int q) { return dist(c, q, cfg.slUnit, cfg.slValue()); }
    private double tpDist(OrderContext c, int q) { return tpDist(c, q, slDist(c, q)); }

    /** Distancia del objetivo; en RR es la distancia del stop ({@code slD}) por el múltiplo del panel. */
    private double tpDist(OrderContext c, int q, double slD) { return tpDist(c, q, slD, cfg.tpValue()); }

    /** Igual con otro valor (los TP1..TP3 del modo Multi): en Money / % es lo que gana esa parte. */
    private double tpDist(OrderContext c, int q, double slD, double value) {
        if (cfg.tpUnit != Config.Unit.RR) return dist(c, q, cfg.tpUnit, value);
        if (Double.isNaN(slD)) return Double.NaN;
        double tick = c.getInstrument().getTickSize();
        return Math.max(1, Math.round(slD * value / tick)) * tick;
    }

    private double dist(OrderContext c, int q, Config.Unit unit, double value) {
        double tick = c.getInstrument().getTickSize();
        double ticks = value;
        if (unit == Config.Unit.POINTS) ticks = value / tick;
        else if (Config.isMoney(unit)) {
            double m = moneyOf(c, unit, value);
            if (Double.isNaN(m)) return Double.NaN;        // % de un balance desconocido
            double tv = tickValue(c);
            ticks = tv > 0 ? m / (Math.max(1, q) * tv) : m;
        }
        return Math.max(1, Math.round(ticks)) * tick;
    }

    /** Dinero en riesgo de una estrategia con Riesgo activado; NaN si es un % de un saldo que el bróker no da. */
    private double stratRiskMoney(OrderContext c, Config.Strategy st) {
        if (!st.riskPct) return st.risk;
        double bal = currentBalance(c);
        return Double.isNaN(bal) ? Double.NaN : bal * st.risk / 100.0;
    }

    /**
     * Cantidad de una estrategia con Riesgo activado para una entrada en {@code ref}: la mayor con la
     * que, si saltan todos sus stops, la pérdida no pasa del riesgo (se redondea hacia abajo). 0 si no
     * llega a 1 contrato, no tiene stops o no se conoce el saldo.
     */
    private int stratRiskQty(OrderContext c, Config.Strategy st, float ref) {
        double m = stratRiskMoney(c, st);
        if (Double.isNaN(m) || m <= 0 || !st.anyStop()) return 0;
        double tick = c.getInstrument().getTickSize(), pv = pointValue(c);
        int n = st.steps.size();
        double[] loss = new double[n];          // pérdida por contrato de cada paso
        double per = 0, room = 100;
        for (int i = 0; i < n; i++) {
            Config.Step sp = st.steps.get(i);
            if (sp.hasStop()) loss[i] = (st.percent ? Math.max(tick, ref * sp.stopOff / 100.0) : Math.max(1, Math.round(sp.stopOff)) * tick) * pv;
            double share = Math.min(room, sp.pct);
            room -= share;
            per += share / 100.0 * loss[i];
        }
        if (per <= 0) return 0;
        for (int q = (int) Math.min(100000, Math.floor(m / per)) + n; q >= 1; q--) {
            int[] sq = st.qtys(q);
            double tot = 0;
            for (int i = 0; i < n; i++) tot += sq[i] * loss[i];
            if (tot <= m + 1e-6) return q;
        }
        return 0;
    }

    private String stratRiskError(OrderContext c, Config.Strategy st) {
        if (!st.anyStop()) return "«" + st.name + T.t("» calcula la cantidad por riesgo pero no tiene ningún stop");
        double m = stratRiskMoney(c, st);
        if (Double.isNaN(m)) return T.t("el bróker no da el saldo de la cuenta (riesgo en % de «") + st.name + "»)";
        return T.t("con el riesgo de «") + st.name + "» (" + money(m) + T.t(") no llega ni a 1 contrato");
    }

    /** Modo Risk: contratos = riesgo en dinero / (distancia al stop × valor del punto), redondeado. */
    private int riskQty(OrderContext c, float entry, float stop) {
        double perContract = Math.abs(entry - stop) * pointValue(c);
        double m = slRiskMoney(c);
        if (perContract <= 0 || Double.isNaN(m)) return 0;
        return (int) Math.round(m / perContract);
    }

    /** Una LMT/STP en el lado equivocado del mercado se ejecutaría al instante: se rechaza. */
    private String sideError(OrderContext c, boolean buy, Kind kind, float px) {
        if (kind == Kind.MARKET) return null;
        float ask = ask(c), bid = bid(c);
        if (kind == Kind.LIMIT) {
            if (buy && px >= ask) return T.t("compra límite ") + fmt(px) + T.t(" por encima del ask ") + fmt(ask) + T.t(" (entraría a mercado)");
            if (!buy && px <= bid) return T.t("venta límite ") + fmt(px) + T.t(" por debajo del bid ") + fmt(bid) + T.t(" (entraría a mercado)");
        } else {
            if (buy && px <= ask) return T.t("stop de compra ") + fmt(px) + T.t(" por debajo del ask ") + fmt(ask) + T.t(" (se dispararía ya)");
            if (!buy && px >= bid) return T.t("stop de venta ") + fmt(px) + T.t(" por encima del bid ") + fmt(bid) + T.t(" (se dispararía ya)");
        }
        return null;
    }

    private boolean rateOk() {
        long now = System.currentTimeMillis();
        while (!submitTimes.isEmpty() && now - submitTimes.peekFirst() > 1000) submitTimes.pollFirst();
        if (submitTimes.size() >= Math.max(1, optMaxPerSec)) return false;
        submitTimes.addLast(now);
        return true;
    }

    // ------------------------------------------------------------------
    //  utilidades de órdenes
    // ------------------------------------------------------------------

    private OrderContext need() {
        OrderContext c = ctx;
        if (c == null) setStatus(T.t("Estrategia inactiva: pulsa Activate en el gráfico"));
        return c;
    }

    private static Enums$OrderAction exitAction(Bracket b) { return b.buy ? Enums$OrderAction.SELL : Enums$OrderAction.BUY; }

    private static int filled(Order o) {
        int f = o.getFilled();
        if (f <= 0 && o.isFilled()) f = o.getQuantity();
        return f;
    }

    /** MotiveWave puede entregar instancias distintas de la misma orden: se compara también por id. */
    private static boolean same(Order a, Order b) {
        if (a == null || b == null) return false;
        if (a == b) return true;
        String ia = a.getOrderId(), ib = b.getOrderId();
        return ia != null && ia.equals(ib);
    }

    private static boolean removeSame(List<Order> list, Order o) {
        for (Iterator<Order> it = list.iterator(); it.hasNext();) if (same(it.next(), o)) { it.remove(); return true; }
        return false;
    }

    private Bracket byEntry(Order o) {
        for (Bracket b : brackets) if (same(b.entry, o)) return b;
        return null;
    }

    private Bracket byLeg(Order o) {
        for (Bracket b : brackets) if (legOf(b, o) != null) return b;
        return null;
    }

    /** Salida del bracket a la que pertenece la orden, o null. */
    private static Leg legOf(Bracket b, Order o) {
        for (Leg l : b.legs()) if (same(l.order, o)) return l;
        return null;
    }

    /** Id de línea de una salida: "S12" / "T12" el primer stop / objetivo; "S12_1", "T12_2"… los demás (modo Multi). */
    static String legId(Bracket b, Leg l) {
        int k = (l.stop ? b.sls : b.tps).indexOf(l);
        String head = (l.stop ? "S" : "T") + b.serial;
        return k <= 0 ? head : head + "_" + k;
    }

    private static Leg legById(Bracket b, String id) {
        List<Leg> ls = id.charAt(0) == 'S' ? b.sls : b.tps;
        int cut = id.indexOf('_');
        if (cut < 0) return ls.get(0);
        try { int k = Integer.parseInt(id.substring(cut + 1)); return k >= 0 && k < ls.size() ? ls.get(k) : null; }
        catch (NumberFormatException e) { return null; }
    }

    /** Se agrupan con otras órdenes el stop principal y el objetivo del modo SL/TP (las demás salidas del Multi no). */
    private static boolean groupable(Bracket b, Leg l) { return l == b.sl || (l == b.tp && !b.multi); }

    private static boolean anyPlanned(List<Leg> ls) { for (Leg l : ls) if (l.planned) return true; return false; }

    /** Multi: contratos en ese estado que le tocan a una salida. */
    private static int slots(Bracket b, Leg l, byte st) {
        if (b.slotSt == null) return 0;
        int idx = (l.stop ? b.sls : b.tps).indexOf(l);
        int[] arr = l.stop ? b.slotS : b.slotT;
        int n = 0;
        for (int i = 0; i < arr.length; i++) if (arr[i] == idx && b.slotSt[i] == st) n++;
        return n;
    }

    /** Contratos de una salida que dependen de la entrada pendiente (lo que muestra su línea interna). */
    private static int pendQty(Bracket b, Leg l) { return b.multi ? slots(b, l, PEND) : b.remaining(); }

    /** Contratos abiertos del bracket que tienen stop (u objetivo) vivo. */
    private static int coveredQty(Bracket b, boolean stop) {
        if (!b.multi) return b.leg(stop).live() ? b.liveQty : 0;
        int n = 0;
        for (Leg l : stop ? b.sls : b.tps) if (l.live() || (l.mit && l.planned)) n += slots(b, l, OPEN);
        return n;
    }

    /** La salida equivalente de otro miembro del grupo (o la propia). */
    private static Leg peer(Bracket mb, Bracket b, Leg l) { return mb == b ? l : mb.leg(l.stop); }

    private Bracket bySerial(String id) {
        if (id == null || id.length() < 2) return null;
        int s;
        int cut = id.indexOf('_');
        try { s = Integer.parseInt(id.substring(1, cut < 0 ? id.length() : cut)); } catch (NumberFormatException e) { return null; }
        for (Bracket b : brackets) if (b.serial == s) return b;
        return null;
    }

    private void setPending(Order o, float px) { clearPending(o); pendingPx.add(new PendingPx(o, px)); }

    private void clearPending(Order o) { pendingPx.removeIf(p -> same(p.o, o)); }

    /** Precio de trabajo de una orden (el arrastre en curso manda hasta que el bróker confirma). */
    private float orderPrice(Order o) {
        if (o == null) return Float.NaN;
        for (PendingPx p : pendingPx) if (same(p.o, o)) return p.px;
        Float lp = o.getLimitPrice(), sp = o.getStopPrice();
        if (o.getType() == Enums$OrderType.STOP && sp != null) return sp;
        if (lp != null) return lp;
        if (sp != null) return sp;
        return Float.NaN;
    }

    private static String typeLabel(Order o) {
        Enums$OrderType t = o.getType();
        return t == Enums$OrderType.LIMIT ? "LMT" : t == Enums$OrderType.STOP ? "STP" : t == Enums$OrderType.MARKET ? "MKT" : "TRL";
    }

    private void noteAccount(Order o) {
        try { String a = o.getAccountId(); if (a != null && !a.isEmpty()) account = a; } catch (Throwable ignore) { }
    }

    private static List<Order> activeOrders(OrderContext c) {
        List<Order> out = new ArrayList<>();
        List<?> raw = c.getActiveOrders();
        if (raw != null) for (Object x : raw) if (x instanceof Order && ((Order) x).isActive()) out.add((Order) x);
        return out;
    }

    /** Bracket nuevo con el menor número libre (empezando por 1). */
    private Bracket newBracket(boolean buy, int qty) {
        Bracket b = new Bracket(++serial, buy, qty);
        Set<Integer> used = new HashSet<>();
        for (Bracket o : brackets) used.add(o.num);
        int n = 1;
        while (used.contains(n)) n++;
        b.num = n;
        return b;
    }

    /** Números de un grupo de salidas, de menor a mayor: "1·3". */
    private static String nums(List<ExitView> vs) {
        TreeSet<Integer> ns = new TreeSet<>();
        for (ExitView v : vs) ns.add(v.b.num);
        StringBuilder sb = new StringBuilder();
        for (Integer n : ns) { if (sb.length() > 0) sb.append('·'); sb.append(n); }
        return sb.toString();
    }

    /** Las órdenes activas que el motor no conoce se adoptan como entradas sin salidas. */
    private List<Order> adopt(OrderContext c) {
        List<Order> act = activeOrders(c);
        for (Order o : act) {
            if (byEntry(o) != null || byLeg(o) != null) continue;
            Bracket b = newBracket(o.isBuy(), o.getQuantity());
            b.entry = o;
            b.ref = orderPrice(o);
            brackets.add(b);
            noteAccount(o);
        }
        return act;
    }

    // ------------------------------------------------------------------
    //  publicación: líneas del gráfico e instantánea del panel
    // ------------------------------------------------------------------

    private void setStatus(String s) {
        status = s == null ? "" : s;
        dirty = true;
        if (!status.isEmpty() && ERROR.matcher(status).find()) { notice = status; noticeAt = System.currentTimeMillis(); }
        Host h = host;
        if (h != null && !status.isEmpty()) h.log(status);
    }

    private String fmt(double v) {
        OrderContext c = ctx;
        try { if (c != null && c.getInstrument() != null) return c.getInstrument().format(v); } catch (Throwable ignore) { }
        return String.format(T.loc(), "%.2f", v);
    }

    static String money(double v) { return String.format(T.loc(), "%.2f $", v); }

    private static String signed(boolean buy, int q) { return (buy ? "+" : "-") + q; }

    private void publish() {
        lastPublishMs = System.currentTimeMillis();
        dirty = false;
        OrderContext c = ctx;
        List<LineModel> out = new ArrayList<>();
        Armed a = armed;
        try {
            // una colocación manual de SL/TP deja de tener sentido si su posición u orden ya no existe
            if (pick != null && (c == null || pickError(c, pick.id, pick.stop) != null)) { pick = null; hover = null; }
            if (splitB != null && (!brackets.contains(splitB) || splitB.leg(splitStop).group == null)) { splitB = null; splitPx = Float.NaN; }
            snapKey = c == null ? null : placingKey(c, a);
            if (c != null) buildLines(c, out);
        } catch (Throwable t) {
            Host h = host; if (h != null) h.log("publish: " + t);
        }
        lines = List.copyOf(out);
        armedFlag = (a != null || pick != null) && c != null;
        int sq = -1;
        Config.Strategy rs = cfg.oco && !cfg.sizing() ? cfg.multi() : null;
        if (c != null && rs != null && rs.riskOn) { try { sq = stratRiskQty(c, rs, last(c)); } catch (Throwable t) { sq = 0; } }
        stratQty = sq;
        banner = armedText(a);
        snap = buildSnapshot(c, a);
        Host h = host;
        if (h != null) { try { h.redraw(); } catch (Throwable ignore) { } }
    }

    /** Clave de agrupación del SL/TP que se está colocando a mano (botón SL/TP o stop del modo Risk). */
    private String placingKey(OrderContext c, Armed a) {
        Pick p = pick;
        if (p != null) {
            boolean lng;
            if ("POS".equals(p.id)) lng = c.getPosition() > 0;
            else { Bracket b = bySerial(p.id); if (b == null) return null; lng = b.buy; }
            return (p.stop ? "S" : "T") + (lng ? "L" : "S");
        }
        if (a != null && a.stage == 1) return "S" + (a.buy ? "L" : "S");
        return null;
    }

    private String armedText(Armed a) {
        Pick p = pick;
        if (p != null) return (p.stop ? "SL" : "TP") + T.t(" · clic en el precio del ") + (p.stop ? "stop" : T.t("objetivo"));
        if (a == null) return "";
        String side = a.buy ? T.t("COMPRAR ") : T.t("VENDER ");
        String k = a.bidAsk ? (a.buy ? "BID" : "ASK") : a.kind.label;
        if (a.stage == 0) return side + k + T.t(" · clic en el precio de entrada") + (cfg.mode() == Config.QtyMode.AVG ? T.t(" (promediado)") : "");
        OrderContext c = ctx;
        double m = c == null ? Double.NaN : slRiskMoney(c);
        return side + k + T.t(" · clic en el nivel del stop (riesgo ") + (Double.isNaN(m) ? "?" : money(m)) + ")";
    }

    private PanelSnapshot buildSnapshot(OrderContext c, Armed a) {
        String ak = a == null ? "" : a.bidAsk ? "BIDASK" : a.kind.label;
        boolean ab = a != null && a.buy;
        if (c == null) return new PanelSnapshot(false, symbol, "—", account, 0, 0, 0, 0, "", status, false, "", Double.NaN);
        try {
            Instrument in = c.getInstrument();
            Object br = in.getBroker();
            return new PanelSnapshot(true, symbol, br == null ? "—" : br.toString(), account, c.getCashBalance(),
                c.getPosition(), c.getUnrealizedPnL(), c.getTotalRealizedPnL(), armedText(a), status, ab, ak, autoInitialBalance(c));
        } catch (Throwable t) {
            return new PanelSnapshot(true, symbol, "—", account, 0, 0, 0, 0, armedText(a), status, ab, ak, Double.NaN);
        }
    }

    private void buildLines(OrderContext c, List<LineModel> out) {
        int pos = c.getPosition();
        float avg = pos != 0 ? c.getAvgEntryPrice() : Float.NaN;
        normalizeGroups();
        List<ExitView> exits = new ArrayList<>();

        // órdenes vivas en el bróker
        for (Order o : adopt(c)) {
            float px = orderPrice(o);
            if (Float.isNaN(px)) continue;                       // órdenes a mercado en vuelo: sin línea
            boolean stopType = o.getType() == Enums$OrderType.STOP;
            Color col = o.isBuy() ? (stopType ? C_BUY_STP : C_BUY_LMT) : (stopType ? C_SELL_STP : C_SELL_LMT);
            Bracket lb = byLeg(o);
            if (lb != null) {
                Leg l = legOf(lb, o);
                boolean otherPlanned = false;
                if (l.stop) { for (Leg t : lb.tps) otherPlanned |= t.planned; } else otherPlanned = lb.sl.planned;
                float ref = lb.positional && !Float.isNaN(avg) ? avg : lb.ref;
                float d = l.group != null ? 0 : entryDrag(lb);   // stop adelantado: acompaña a su entrada mientras se arrastra
                // eslabón = forma un OCO: tiene pareja (stop + objetivo) o cuelga de una entrada pendiente
                boolean oco = lb.entryPending() || otherPlanned;
                exits.add(new ExitView(lb, l, px + d, Float.isNaN(ref) ? ref : ref + d, o.getQuantity(), true, col, oco));
                continue;
            }
            Bracket b = byEntry(o);
            if (b == null) continue;
            px = shown("E" + b.serial, px);
            LineModel.Builder m = LineModel.of("E" + b.serial, LineModel.Kind.ENTRY, px)
                .text("Subm | " + signed(o.isBuy(), o.getQuantity()) + " " + typeLabel(o)).linked(b.link != null).color(col).badge(String.valueOf(b.num))
                .draggable(true).closeAtRight(true).btn("SL").btn("TP").btn("X");
            if (pos != 0 && (pos > 0) != o.isBuy())
                m.money(money(pnl(c, avg, px, (pos > 0 ? 1 : -1) * Math.min(Math.abs(pos), o.getQuantity()))));
            out.add(m.build());
        }

        for (Bracket b : brackets) {
            // STP LMT emulada
            float drag = entryDrag(b);
            if (b.synth) {
                out.add(LineModel.of("Y" + b.serial, LineModel.Kind.SYNTH, shown("Y" + b.serial, b.synthStop))
                    .text("Int | " + signed(b.buy, b.qty) + " STP LMT").linked(b.link != null).badge(String.valueOf(b.num))
                    .color(b.buy ? C_BUY_STP : C_SELL_STP).draggable(true).closeAtRight(true)
                    .btn("SL").btn("TP").btn("X").build());
            }
            // salidas internas de una entrada aún pendiente
            int rem = b.remaining();
            boolean pend = b.entryPending() && rem > 0;
            for (Leg l : b.legs()) {
                // con la entrada parcialmente ejecutada la salida viva ya ocupa esta línea
                if (!l.planned || !l.resolved() || l.live()) continue;
                // interna: cuelga de una entrada pendiente, o es un objetivo MIT que espera a que se toque su precio
                int lq = (pend ? pendQty(b, l) : 0) + (l.mit ? slots(b, l, OPEN) : 0);
                if (lq <= 0) continue;
                float d = l.group != null ? 0 : drag;        // una salida agrupada no sigue a su entrada
                exits.add(new ExitView(b, l, l.price + d, pend ? entryRef(b) + drag : b.ref, lq, false, C_INTERNAL, true));
            }
            // disparador del trailing
            if (b.trail && !b.trailActive && anyPlanned(b.sls) && !Float.isNaN(b.trailTrigger) && (b.entryPending() || coveredQty(b, true) > 0 || b.sl.live())) {
                out.add(LineModel.of("G" + b.serial, LineModel.Kind.TRIGGER, shown("G" + b.serial, b.trailTrigger + drag))
                    .text("Trail. Trigger").badge(String.valueOf(b.num)).color(C_TRIGGER).draggable(true).dotted(true).build());
            }
        }

        exitLines(c, exits, out);

        // posición
        if (pos != 0) {
            out.add(LineModel.of("POS", LineModel.Kind.POSITION, avg)
                .text(signed(pos > 0, Math.abs(pos)) + " QTY").money(positionLabel(c, pos, avg))
                .color(pos > 0 ? C_LONG : C_SHORT).closeAtRight(true).btn("SL").btn("TP").btn("X").build());
        }

        previewLines(c, out);
    }

    /** Una salida (stop u objetivo, viva o interna) antes de convertirla en línea. */
    private static final class ExitView {
        final Bracket b; final Leg l; final float px, ref; final int qty; final boolean live; final Color col; final boolean oco;
        ExitView(Bracket b, Leg l, float px, float ref, int qty, boolean live, Color col, boolean oco) {
            this.b = b; this.l = l; this.px = px; this.ref = ref; this.qty = qty; this.live = live; this.col = col; this.oco = oco;
        }
        String id() { return legId(b, l); }
    }

    /** Líneas de las salidas: las agrupadas se pintan como una sola con la cantidad y el importe sumados. */
    private void exitLines(OrderContext c, List<ExitView> exits, List<LineModel> out) {
        List<ExitView> done = new ArrayList<>();
        ExitView sv = null;                                  // salida que se está sacando de su grupo
        for (ExitView v : exits) {
            if (done.contains(v)) continue;
            List<ExitView> grp = new ArrayList<>();
            grp.add(v);
            if (v.l.group != null) for (ExitView w : exits) if (w != v && w.l.group == v.l.group && !done.contains(w)) grp.add(w);
            done.addAll(grp);
            grp.sort((x, y) -> Integer.compare(x.b.serial, y.b.serial));
            ExitView f = grp.get(0);
            String id = f.id();
            boolean stop = f.l.stop;
            float px = shown(id, f.px);
            int qty = 0, live = 0, shownN = 0;
            double money = 0, dist = 0, risk = 0;
            boolean hasMoney = false, trail = true, hasRisk = true;
            List<ExitView> shownV = new ArrayList<>();
            Color col = C_INTERNAL;
            for (ExitView g : grp) {
                if (grp.size() > 1 && g.b == splitB && g.l.stop == splitStop && !Float.isNaN(splitPx)) { sv = g; continue; }
                shownN++;
                shownV.add(g);
                qty += g.qty;
                if (g.live) { live++; col = g.col; }
                if (!Float.isNaN(g.ref)) {
                    money += pnl(c, g.ref, px, g.b.buy ? g.qty : -g.qty);
                    dist += (g.b.buy ? px - g.ref : g.ref - px) * g.qty;
                    double rp = riskOf(c, g.b, g.ref);
                    if (Double.isNaN(rp)) hasRisk = false; else risk += rp * g.qty;
                    hasMoney = true;
                }
                trail &= g.b.trail;
            }
            String pre = live == shownN ? "Subm" : live == 0 ? "Int" : "Subm/Int";
            String type = f.l.mit ? "MIT" : grp.size() == 1 && f.l.trail ? "TRL"
                : grp.size() == 1 && f.live ? typeLabel(f.l.order) : (stop ? "STP" : "LMT");
            LineModel.Builder m = LineModel.of(id, stop ? LineModel.Kind.STOP : LineModel.Kind.TARGET, px)
                .text(pre + " | " + signed(!f.b.buy, qty) + " " + type + (grp.size() == 1 && f.l.no > 0 ? (stop ? " SL" : " TP") + f.l.no : ""))
                .linked(grp.size() > 1 || f.oco).color(col).draggable(true).closeAtRight(true)
                .badge(nums(shownV));
            if (groupable(f.b, f.l)) m.merge((stop ? "S" : "T") + (f.b.buy ? "L" : "S"));
            if (hasMoney && qty > 0) m.money(exitLabel(c, money, dist / qty, hasRisk ? risk : Double.NaN));
            if (stop) m.btn("TS", trail);
            if (grp.size() > 1) m.btn("#" + grp.size());
            out.add(m.btn("X").build());
        }
        if (sv != null) {
            float h = splitPx;
            String type = sv.l.stop ? " STP" : " LMT";
            if (legMoveError(c, sv.b, sv.l, h) != null) {
                out.add(LineModel.of("PX", LineModel.Kind.PREVIEW, h).text("PR |" + type + T.t(" · lado incorrecto")).color(C_MUTED).dotted(true).build());
            } else {
                LineModel.Builder m = LineModel.of("PX", LineModel.Kind.PREVIEW, h)
                    .text("PR | " + signed(!sv.b.buy, sv.qty) + type + T.t(" · se separa")).badge(String.valueOf(sv.b.num)).color(sv.col).dotted(true);
                if (!Float.isNaN(sv.ref)) m.money(exitLabel(c, pnl(c, sv.ref, h, sv.b.buy ? sv.qty : -sv.qty),
                    sv.b.buy ? h - sv.ref : sv.ref - h, riskOf(c, sv.b, sv.ref) * sv.qty));
                out.add(m.build());
            }
        }
    }

    /** Precio con el que se dibuja una línea: el del arrastre en curso si es la que se está moviendo. */
    private float shown(String id, float px) { return id.equals(dragId) ? dragPx : px; }

    /** Desplazamiento de las hijas de una entrada pendiente mientras se arrastra esa entrada. */
    private float entryDrag(Bracket b) {
        String d = dragId;
        if (d == null || !b.entryPending() || b.liveQty != 0) return 0;
        if (!d.equals("E" + b.serial) && !d.equals("Y" + b.serial)) return 0;
        float ref = entryRef(b);
        return Float.isNaN(ref) ? 0 : dragPx - ref;
    }

    /** Vista previa del SL/TP manual: sigue al ratón con su cantidad y su importe. */
    private void pickPreview(OrderContext c, Pick p, List<LineModel> out) {
        Float h = hover;
        if (h == null) return;
        boolean lng; int q; float ref; boolean wrong;
        double rp = Double.NaN;
        if ("POS".equals(p.id)) {
            int pos = c.getPosition();
            if (pos == 0) return;
            lng = pos > 0; q = Math.max(1, uncovered(c, p.stop)); ref = c.getAvgEntryPrice();
            wrong = sideError(c, !lng, p.stop ? Kind.STOP : Kind.LIMIT, h) != null;
        } else {
            Bracket b = bySerial(p.id);
            if (b == null) return;
            lng = b.buy; q = Math.max(1, b.remaining()); ref = entryRef(b);
            if (Float.isNaN(ref)) return;
            rp = riskOf(c, b, ref);
            boolean lossSide = lng ? h < ref : h > ref;
            wrong = h == ref || p.stop != lossSide;
        }
        String id = p.stop ? "PS" : "PT", type = p.stop ? " STP" : " LMT";
        if (wrong) {
            out.add(LineModel.of(id, LineModel.Kind.PREVIEW, h).text("PR |" + type + T.t(" · lado incorrecto")).color(C_MUTED).dotted(true).build());
            return;
        }
        Color col = p.stop ? (lng ? C_SELL_STP : C_BUY_STP) : (lng ? C_SELL_LMT : C_BUY_LMT);
        out.add(LineModel.of(id, LineModel.Kind.PREVIEW, h).text("PR | " + signed(!lng, q) + type)
            .money(exitLabel(c, pnl(c, ref, h, lng ? q : -q), lng ? h - ref : ref - h, rp * q)).color(col).dotted(true).build());
    }

    /** Vista previa "PR" mientras hay una colocación armada y el ratón está sobre el gráfico. */
    private void previewLines(OrderContext c, List<LineModel> out) {
        Pick p = pick;
        if (p != null) { pickPreview(c, p, out); return; }
        Armed a = armed;
        if (a == null) return;
        Float h = hover;
        Instrument in = c.getInstrument();
        Color side = a.buy ? (a.kind == Kind.LIMIT ? C_BUY_LMT : C_BUY_STP) : (a.kind == Kind.LIMIT ? C_SELL_LMT : C_SELL_STP);
        Color exitStop = a.buy ? C_SELL_STP : C_BUY_STP, exitLmt = a.buy ? C_SELL_LMT : C_BUY_LMT;
        if (a.stage == 0) {
            if (h == null) return;
            out.add(LineModel.of("PE", LineModel.Kind.PREVIEW, h).text("PR | ENTRY - " + a.kind.label).color(side).dotted(true).build());
            if (cfg.mode() == Config.QtyMode.AVG) {
                AvgPlan plan = avgPlan(c, a.buy, h);
                if (plan.first) return;
                if (plan.error != null) {
                    float y = Float.isNaN(plan.sl) ? h : plan.sl;
                    out.add(LineModel.of("PS", LineModel.Kind.PREVIEW, y).text("PR | STP · " + plan.error).color(C_MUTED).dotted(true).build());
                    return;
                }
                out.add(LineModel.of("PS", LineModel.Kind.PREVIEW, plan.sl).text("PR | " + signed(!a.buy, plan.qty) + T.t(" STP · promediado ") + (plan.favor ? T.t("a favor") : T.t("en contra")))
                    .money(exitLabel(c, -plan.risk, -Math.abs(h - plan.sl), plan.risk)).color(exitStop).dotted(true).build());
                targetPreviews(c, out, a.buy, h, plan.qty, Math.abs(h - plan.sl));
                return;
            }
            Config.Strategy strat = cfg.oco ? cfg.multi() : null;
            if (cfg.mode() == Config.QtyMode.QTY && strat != null) {
                int q = strat.riskOn ? stratRiskQty(c, strat, h) : Math.max(1, cfg.qty);
                if (q < 1) {
                    out.add(LineModel.of("PS", LineModel.Kind.PREVIEW, h).text("PR | " + stratRiskError(c, strat)).color(C_MUTED).dotted(true).build());
                    return;
                }
                exitPreviews(c, out, a.buy, h, q, strat, false, true, Double.NaN);
                return;
            }
            if (cfg.mode() == Config.QtyMode.QTY && cfg.oco) {
                int q = Math.max(1, cfg.qty);
                double sd = slDist(c, q);
                if (cfg.slOn && !Double.isNaN(sd)) {
                    float sl = (float) in.round(a.buy ? h - sd : h + sd);
                    out.add(LineModel.of("PS", LineModel.Kind.PREVIEW, sl).text("PR | " + signed(!a.buy, q) + " STP")
                        .money(exitLabel(c, pnl(c, h, sl, a.buy ? q : -q), -sd, sd * pointValue(c) * q)).color(exitStop).dotted(true).build());
                }
                targetPreviews(c, out, a.buy, h, q, sd);
            }
            return;
        }
        float ref = a.entry != null ? a.entry : (a.buy ? ask(c) : bid(c));
        if (a.entry != null)
            out.add(LineModel.of("PE", LineModel.Kind.PREVIEW, a.entry).text("PR | ENTRY - " + (a.bidAsk ? (a.buy ? "BID" : "ASK") : a.kind.label)).color(side).dotted(true).build());
        if (h == null) return;
        boolean wrong = a.buy ? h >= ref : h <= ref;
        int q = wrong ? 0 : riskQty(c, ref, h);
        if (wrong || q < 1) {
            out.add(LineModel.of("PS", LineModel.Kind.PREVIEW, h)
                .text(wrong ? T.t("PR | STP · lado incorrecto") : T.t("PR | STP · riesgo insuficiente para 1 contrato")).color(C_MUTED).dotted(true).build());
            return;
        }
        out.add(LineModel.of("PS", LineModel.Kind.PREVIEW, h).text("PR | " + signed(a.buy, q) + " STP")
            .money(riskLabel(c, Math.abs(pnl(c, ref, h, q)), Math.abs(ref - h))).color(exitStop).dotted(true).build());
        targetPreviews(c, out, a.buy, ref, q, Math.abs(ref - h));
    }

    /** Vista previa del objetivo (o de los objetivos de la estrategia Multi) de una entrada de {@code q} en {@code ref}. */
    private void targetPreviews(OrderContext c, List<LineModel> out, boolean buy, float ref, int q, double slD) {
        Config.Strategy strat = cfg.multi();
        if (strat != null) { exitPreviews(c, out, buy, ref, q, strat, true, false, slD * pointValue(c)); return; }
        if (!cfg.tpOn) return;
        double td = tpDist(c, q, slD);
        if (Double.isNaN(td)) return;
        float tp = (float) c.getInstrument().round(buy ? ref + td : ref - td);
        out.add(LineModel.of("PT", LineModel.Kind.PREVIEW, tp).text("PR | " + signed(!buy, q) + " LMT")
            .money(exitLabel(c, pnl(c, ref, tp, buy ? q : -q), td, slD * pointValue(c) * q)).color(buy ? C_SELL_LMT : C_BUY_LMT).dotted(true).build());
    }

    /** Vista previa de las salidas de una estrategia: se monta el mismo bracket que crearía la entrada. */
    private void exitPreviews(OrderContext c, List<LineModel> out, boolean buy, float ref, int q, Config.Strategy st, boolean marked, boolean withStops, double riskPer) {
        Bracket tmp = new Bracket(0, buy, q);
        setupMulti(c, tmp, q, st, marked);
        resolve(c, tmp, ref);
        if (Double.isNaN(riskPer)) riskPer = riskPerLive(c, tmp, ref);
        for (Leg l : tmp.legs()) {
            if (!l.planned || !l.resolved() || (l.stop && !withStops)) continue;
            int n = slots(tmp, l, PEND);
            if (n <= 0) continue;
            int k = (l.stop ? tmp.sls : tmp.tps).indexOf(l);
            Color col = l.stop ? (buy ? C_SELL_STP : C_BUY_STP) : (buy ? C_SELL_LMT : C_BUY_LMT);
            String type = l.stop ? (l.trail ? " TRL" : " STP") : (l.mit ? " MIT" : " LMT");
            out.add(LineModel.of((l.stop ? "PS" : "PT") + (k == 0 ? "" : String.valueOf(k + 1)), LineModel.Kind.PREVIEW, l.price)
                .text("PR | " + signed(!buy, n) + type + (l.no > 0 ? (l.stop ? " SL" : " TP") + l.no : ""))
                .money(exitLabel(c, pnl(c, ref, l.price, buy ? n : -n), buy ? l.price - ref : ref - l.price, riskPer * n)).color(col).dotted(true).build());
        }
    }
}
