package com.tradepanel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.prefs.Preferences;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.geometry.Side;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Ventana del Trade Panel: réplica del panel lateral de DeepCharts (mismas filas, colores y textos).
 *
 * MotiveWave es una aplicación JavaFX, así que el panel es un Stage sin decoración cuyo dueño es
 * la ventana de MotiveWave: flota sobre el gráfico, se minimiza con él y no tapa otras
 * aplicaciones. Se arrastra por la barra superior y recuerda su posición.
 *
 * Hay una sola ventana por aplicación; se enlaza al motor de la estrategia activa más reciente.
 * Todos los controles son propios (no dependen del tema de MotiveWave) y solo escriben en
 * {@link Config} o llaman al {@link TradeEngine}, que hace el trabajo en su propio hilo.
 */
final class PanelWindow {

    private static final Locale ES = Locale.forLanguageTag("es-ES");
    private static final double WIDTH = 236;

    private static PanelWindow instance;                 // solo hilo JavaFX
    private static volatile TradeEngine shownEngine;      // motor de la ventana visible (lectura desde otros hilos)

    // ------------------------------------------------------------------
    //  API para la estrategia (cualquier hilo)
    // ------------------------------------------------------------------

    static void show(TradeEngine e, boolean alwaysOnTop) {
        fx(() -> {
            PanelWindow w = instance;
            if (w == null || w.alwaysOnTop != alwaysOnTop || w.ownerGone()) {
                if (w != null) w.close();
                w = new PanelWindow(alwaysOnTop);
                instance = w;
            }
            w.bind(e);
            if (!w.stage.isShowing()) { w.place(); w.stage.show(); w.root.requestFocus(); }
            w.stage.toFront();
            shownEngine = e;
        });
    }

    static void hide(TradeEngine e) {
        if (shownEngine == e) shownEngine = null;
        fx(() -> {
            PanelWindow w = instance;
            if (w != null && w.engine == e) w.stage.hide();
        });
    }

    static boolean isShowing(TradeEngine e) { return shownEngine == e; }

    /** Cambio de idioma: el panel (y el editor de estrategias) se vuelven a crear con los textos nuevos. */
    static void relocalize() {
        fx(() -> {
            PanelWindow w = instance;
            if (w == null) return;
            boolean showing = w.stage.isShowing();
            TradeEngine e = w.engine;
            boolean top = w.alwaysOnTop;
            if (w.stratDialog != null) w.stratDialog.close();
            w.close();
            instance = null;
            if (showing && e != null) show(e, top);
        });
    }

    /** Abre el editor de la estrategia Multi elegida (o de una nueva si no hay ninguna). */
    static void openStrategyEditor(TradeEngine e) {
        fx(() -> {
            PanelWindow w = instance;
            if (w != null && w.engine == e && w.stage.isShowing()) w.editStrategy(e.cfg.multi(), false);
        });
    }

    // ------------------------------------------------------------------
    //  Esc en cualquier ventana de MotiveWave: cancela la colocación armada
    // ------------------------------------------------------------------

    private static final Set<TradeEngine> escEngines = new CopyOnWriteArraySet<>();
    private static final Map<Window, Boolean> escWindows = new WeakHashMap<>();   // solo hilo JavaFX
    private static boolean escInstalled;                                            // solo hilo JavaFX

    /**
     * El SDK no entrega el teclado a los estudios, pero MotiveWave es JavaFX: un filtro en cada
     * ventana recibe el Esc antes que el gráfico. Solo actúa (y consume la tecla) si hay algo armado.
     */
    static void watchEsc(TradeEngine e) {
        escEngines.add(e);
        fx(() -> {
            if (escInstalled) return;
            escInstalled = true;
            for (Window w : Window.getWindows()) escFilter(w);
            Window.getWindows().addListener((ListChangeListener<Window>) ch -> {
                while (ch.next()) for (Window w : ch.getAddedSubList()) escFilter(w);
            });
        });
    }

    static void unwatchEsc(TradeEngine e) { escEngines.remove(e); }

    private static void escFilter(Window w) {
        if (w == null || escWindows.containsKey(w)) return;
        escWindows.put(w, Boolean.TRUE);
        w.addEventFilter(KeyEvent.KEY_PRESSED, ev -> {
            if (ev.getCode() != KeyCode.ESCAPE) return;
            boolean any = false;
            for (TradeEngine e : escEngines) if (e.isArmed()) { e.disarm(); any = true; }
            if (any) ev.consume();
        });
    }

    /** Diálogo de cantidad (doble clic sobre una orden del gráfico). */
    static void askQuantity(int current, IntConsumer onOk) {
        fx(() -> {
            TextInputDialog d = new TextInputDialog(Integer.toString(current));
            d.setTitle("Trade Panel");
            d.setHeaderText(null);
            d.setContentText(T.t("Nueva cantidad:"));
            Window owner = instance != null && instance.stage.isShowing() ? instance.stage : mainWindow();
            if (owner != null) d.initOwner(owner);
            d.showAndWait().ifPresent(s -> {
                try {
                    int q = Integer.parseInt(s.trim());
                    if (q > 0) onOk.accept(q);
                } catch (NumberFormatException ignore) { }
            });
        });
    }

    private static void fx(Runnable r) {
        try {
            if (Platform.isFxApplicationThread()) r.run();
            else Platform.runLater(r);
        } catch (IllegalStateException toolkitNotRunning) { /* sin JavaFX no hay panel */ }
    }

    /** Ventana de MotiveWave con el foco (o la primera visible). */
    private static Window mainWindow() {
        Window best = null;
        for (Window w : Window.getWindows()) {
            if (!(w instanceof Stage) || !w.isShowing()) continue;
            if (instance != null && w == instance.stage) continue;
            if (w.isFocused()) return w;
            if (best == null || w.getWidth() * w.getHeight() > best.getWidth() * best.getHeight()) best = w;
        }
        return best;
    }

    // ------------------------------------------------------------------
    //  instancia
    // ------------------------------------------------------------------

    private final Stage stage;
    private final boolean alwaysOnTop;
    private final Window owner;
    private TradeEngine engine;
    private boolean loading;
    private final Timeline timer;
    private final VBox root;
    private final Scale zoom = new Scale(1, 1);

    // cabecera
    private final Label lblSymbol = label("—", "tp-val");
    private final Label lblSaldo = label("0,00 $", "tp-val");
    private final InfoRow rowQty = new InfoRow(T.t("Cant. abierta"));
    private final InfoRow rowOpen = new InfoRow(T.t("P/L abierto"));
    private final InfoRow rowDaily = new InfoRow(T.t("P/L diario"));
    private final Label lblBroker = label("—", "tp-val");
    private final Label lblAccount = label("—", "tp-val");

    // controles
    private final Combo cbQtyMode = new Combo(null, "Qty", "Risk", "Promediado");
    private final Spin spAvgTotal = new Spin(false, 0, 1e9, 50, true), spAvgPct = new Spin(false, 1, 100, 5, true);
    private final VBox avgBox = new VBox(4);
    private final Spin spQty = new Spin(true, 1, 100000, 1, false);
    private final Check ckTickDiff = new Check();
    private final Spin spTickDiff = new Spin(true, 0, 100000, 1, true);
    private final Switch swOco = new Switch();
    private final Combo cbMode = new Combo(T.t("Modo"), "SL/TP");
    private final Label lblGear = label("\u2699", "tp-gear");  // ajustes: nueva estrategia, editar, duplicar, eliminar
    private final Label lblStrat = label("", "tp-strat");
    private GridPane rowChecks, rowUnits, rowVals;
    private HBox tpCheck;
    private Stage stratDialog;
    private final Check ckSl = new Check(), ckTp = new Check(), ckBe = new Check(), ckTrail = new Check();
    private final Combo cbSlUnit = new Combo(null, "Ticks", "Puntos", "Money", "% Inicial", "% Actual"),
                        cbTpUnit = new Combo(null, "Ticks", "Puntos", "Money", "RR", "% Inicial", "% Actual");
    private final Combo cbBeUnit = new Combo(null, "Ticks"), cbTrailUnit = new Combo(null, "Ticks");
    private final Spin spSl = new Spin(false, 0, 1e9, 1, false), spTp = new Spin(false, 0, 1e9, 1, false);
    private final Spin spBeTrig = new Spin(true, 0, 100000, 1, false), spBeOff = new Spin(true, 0, 100000, 1, false);
    private final Spin spTrTrig = new Spin(true, 0, 100000, 1, false), spTrDist = new Spin(true, 1, 100000, 1, false);
    private final VBox ocoBox = new VBox(4);
    private final Radio rServer = new Radio("Server"), rClient = new Radio("Client");

    private final Btn bBuyMkt, bSellMkt, bBid, bAsk, bBuyLmt, bSellLmt, bBuyStp, bSellStp, bBuyStl, bSellStl;

    private PanelWindow(boolean alwaysOnTop) {
        this.alwaysOnTop = alwaysOnTop;
        this.owner = alwaysOnTop ? null : mainWindow();
        stage = new Stage(StageStyle.UNDECORATED);
        stage.setTitle("Trade Panel");
        stage.setResizable(false);
        if (owner != null) stage.initOwner(owner); else stage.setAlwaysOnTop(true);

        bBuyMkt  = new Btn("MKT", "tp-buy",  () -> engine.market(true));
        bSellMkt = new Btn("MKT", "tp-sell", () -> engine.market(false));
        bBid     = new Btn("BID", "tp-buy",  () -> engine.bidAsk(true));
        bAsk     = new Btn("ASK", "tp-sell", () -> engine.bidAsk(false));
        bBuyLmt  = new Btn("LMT", "tp-buy",  () -> engine.arm(true, TradeEngine.Kind.LIMIT));
        bSellLmt = new Btn("LMT", "tp-sell", () -> engine.arm(false, TradeEngine.Kind.LIMIT));
        bBuyStp  = new Btn("STP", "tp-buy",  () -> engine.arm(true, TradeEngine.Kind.STOP));
        bSellStp = new Btn("STP", "tp-sell", () -> engine.arm(false, TradeEngine.Kind.STOP));
        bBuyStl  = new Btn("STP LMT", "tp-buy",  () -> engine.arm(true, TradeEngine.Kind.STOP_LIMIT));
        bSellStl = new Btn("STP LMT", "tp-sell", () -> engine.arm(false, TradeEngine.Kind.STOP_LIMIT));

        root = new VBox(titleBar(), body());
        root.getStyleClass().add("tp-root");
        root.setPrefWidth(WIDTH);
        root.setMinWidth(WIDTH);
        root.setMaxWidth(WIDTH);
        root.getTransforms().add(zoom);
        root.setFocusTraversable(true);

        // el Group hace que la escena mida el panel ya escalado (pantallas bajas: ver place())
        Scene scene = new Scene(new Group(root));
        scene.setFill(Color.web("#151518"));
        scene.getStylesheets().add("data:text/css;base64," + Base64.getEncoder().encodeToString(CSS.getBytes(StandardCharsets.UTF_8)));
        scene.addEventFilter(KeyEvent.KEY_PRESSED, ev -> {
            if (ev.getCode() == KeyCode.ESCAPE && engine != null) { engine.disarm(); root.requestFocus(); }
        });
        stage.setScene(scene);
        stage.sizeToScene();
        stage.setOnHidden(ev -> { if (shownEngine == engine) shownEngine = null; });

        wire();
        timer = new Timeline(new KeyFrame(Duration.millis(200), ev -> refresh()));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
    }

    private boolean ownerGone() { return owner != null && !owner.isShowing(); }

    private void close() {
        timer.stop();
        stage.close();
    }

    private void bind(TradeEngine e) {
        engine = e;
        load(e.cfg);
        refresh();
    }

    // ------------------------------------------------------------------
    //  construcción
    // ------------------------------------------------------------------

    private Node titleBar() {
        Label t = label("Trade Panel", "tp-title-text");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Label x = label("✕", "tp-close");
        x.setOnMouseClicked(ev -> stage.hide());
        Tooltip.install(x, new Tooltip(T.t("Ocultar (se reabre con el botón PANEL del gráfico)")));
        HBox bar = new HBox(t, sp, x);
        bar.getStyleClass().add("tp-title");
        bar.setAlignment(Pos.CENTER_LEFT);
        final double[] d = new double[2];
        bar.setOnMousePressed(ev -> { d[0] = stage.getX() - ev.getScreenX(); d[1] = stage.getY() - ev.getScreenY(); });
        bar.setOnMouseDragged(ev -> { stage.setX(ev.getScreenX() + d[0]); stage.setY(ev.getScreenY() + d[1]); });
        bar.setOnMouseReleased(ev -> savePosition());
        return bar;
    }

    private Node body() {
        VBox b = new VBox(4);
        b.getStyleClass().add("tp-body");

        // instrumento
        HBox sym = field(lblSymbol, grow(), chevron());
        Tooltip.install(sym, new Tooltip(T.t("Instrumento del gráfico donde está activa la estrategia")));
        b.getChildren().add(sym);

        // saldo y P/L
        lblSaldo.getStyleClass().add("tp-pos-text");
        HBox saldo = new HBox(label(T.t("Saldo"), "tp-dim"), grow(), lblSaldo);
        saldo.setPadding(new Insets(3, 8, 3, 8));
        b.getChildren().addAll(saldo, rowQty, rowOpen, rowDaily, gap(4));

        // cantidad / bróker / cuenta / dif. en ticks
        b.getChildren().add(two(cbQtyMode, spQty));
        Tooltip.install(cbQtyMode, new Tooltip(T.t("Qty: cantidad fija.\n")
            + T.t("Risk: marcas el stop en el gráfico y la cantidad sale del riesgo del SL (Money o % del balance).\n")
            + T.t("Promediado: la 1ª entrada como en Risk; las siguientes del mismo lado llevan el stop al SL común y su cantidad sale de:\n")
            + T.t("  · en contra (algún SL en pérdida): el Riesgo total menos lo que ya arriesgan los SL;\n")
            + T.t("  · a favor (todos los SL en BE o mejor): el % indicado de lo que aseguran los SL.")));
        Label lTot = label(T.t("Riesgo total"), "tp-dim"), lPct = label(T.t("% a favor"), "tp-dim");
        HBox tot = field(lTot, grow(), spAvgTotal), pct = field(lPct, grow(), spAvgPct);
        tot.setSpacing(6); pct.setSpacing(6);
        Tooltip.install(tot, new Tooltip(T.t("Promediado en contra: dinero máximo que pueden perder TODAS las entradas juntas si salta el SL común.\n")
            + T.t("Ej.: 500 con 100 ya en riesgo -> la nueva entrada puede arriesgar 400.")));
        Tooltip.install(pct, new Tooltip(T.t("Promediado a favor (todos los SL en BE o mejor): % de lo que ya aseguran los SL que puede arriesgar la nueva entrada.\n")
            + T.t("Ej.: los SL aseguran 200 y pones 50 -> la nueva entrada arriesga 100 y el SL común sigue asegurando 100.")));
        avgBox.getChildren().addAll(tot, pct);
        b.getChildren().add(avgBox);
        b.getChildren().add(field(label(T.t("Bróker"), "tp-dim"), grow(), lblBroker));
        b.getChildren().add(field(label(T.t("Cuenta"), "tp-dim"), grow(), lblAccount));
        Label lDiff = label(T.t("Dif. en ticks"), "tp-dim");
        HBox diff = field(ckTickDiff, lDiff, grow(), spTickDiff);
        diff.setSpacing(7);
        Tooltip.install(lDiff, new Tooltip(T.t("BID/ASK a esta distancia en ticks del mejor bid/ask")));
        b.getChildren().addAll(diff, gap(4));

        // COMPRAR / VENDER
        Label hb = label(T.t("COMPRAR"), "tp-head"), hs = label(T.t("VENDER"), "tp-head");
        b.getChildren().add(two(center(hb), center(hs)));
        b.getChildren().addAll(two(bBuyMkt, bSellMkt), two(bBid, bAsk), two(bBuyLmt, bSellLmt), two(bBuyStp, bSellStp), two(bBuyStl, bSellStl));

        Btn cancel = new Btn(T.t("Cancelar"), "tp-out-orange", () -> engine.cancelAll());
        Btn be = new Btn("BE", "tp-out-orange", () -> engine.breakeven());
        Tooltip.install(cancel, new Tooltip(T.t("Cancela todas las órdenes pendientes")));
        Tooltip.install(be, new Tooltip(T.t("BE (punto de equilibrio): mueve el stop al precio medio (o lo crea si no hay)")));
        Btn flat = new Btn("Cancel and Flat", "tp-fill-orange", () -> engine.cancelAndFlat());
        Tooltip.install(flat, new Tooltip(T.t("Cancela todas las órdenes y cierra la posición a mercado")));
        b.getChildren().addAll(two(cancel, be), flat, gap(4));

        // OCO Strategy
        HBox oco = new HBox(label("OCO Strategy", "tp-val"), grow(), swOco);
        oco.setAlignment(Pos.CENTER_LEFT);
        oco.setPadding(new Insets(0, 2, 0, 2));
        b.getChildren().add(oco);

        Tooltip.install(cbSlUnit, new Tooltip(T.t("Ticks · Puntos (1 punto = 1,00 de precio) · Money (pérdida total de la operación)\n")
            + T.t("% Inicial / % Actual: pérdida = ese % del saldo inicial (se lee de la cuenta en MotiveWave) o del saldo actual de la cuenta")));
        Tooltip.install(cbTpUnit, new Tooltip(T.t("Ticks · Puntos · Money · RR: el objetivo se pone a la distancia del SL multiplicada por este número\n")
            + T.t("(RR 2 con un SL de 10 puntos = TP a 20 puntos). Usa el SL del panel, o el stop marcado en el gráfico en modo Risk.\n")
            + T.t("% Inicial / % Actual: beneficio = ese % del saldo inicial o del actual")));
        Tooltip.install(spBeTrig, new Tooltip(T.t("BE: ticks a favor para mover el stop a la entrada")));
        Tooltip.install(spBeOff, new Tooltip(T.t("BE: ticks más allá de la entrada donde queda el stop")));
        Tooltip.install(spTrTrig, new Tooltip(T.t("Trail: ticks a favor para empezar a seguir (línea Trail. Trigger)")));
        Tooltip.install(spTrDist, new Tooltip(T.t("Trail: distancia en ticks del stop al precio")));
        HBox sc = new HBox(18, rServer, rClient);
        sc.setAlignment(Pos.CENTER);
        sc.setPadding(new Insets(3, 0, 1, 0));
        Tooltip.install(rServer, new Tooltip(T.t("Server: el STOP de una entrada LMT (o BID/ASK) se envía al bróker junto con ella, así sigue ahí aunque cierres MotiveWave o apagues el equipo.\n")
            + T.t("El objetivo (TP), el OCO, el BE y el trailing siguen gestionándose en cliente: el SDK de MotiveWave no permite dejarlos en el bróker.\n")
            + T.t("Las entradas STP, STP LMT y MKT no cambian.")));
        Tooltip.install(rClient, new Tooltip(T.t("Client: el SL y el TP son internos y se envían cuando se ejecuta la entrada (MotiveWave debe seguir abierto)")));
        Tooltip.install(cbMode, new Tooltip(T.t("SL/TP: un stop y un objetivo por entrada, con los valores de abajo.\n")
            + T.t("O elige una de tus estrategias de salida guardadas (como las Exit Strategies de MotiveWave):\n")
            + T.t("pasos con un % de la cantidad, cada uno con su stop y su objetivo, riesgo que calcula la cantidad y break even.\n")
            + T.t("Se crean y se editan con el icono de ajustes de la derecha.")));
        Tooltip.install(lblGear, new Tooltip(T.t("Estrategias de salida: nueva, editar, duplicar o eliminar")));
        lblGear.setOnMouseClicked(ev -> gearMenu());
        cbMode.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(cbMode, Priority.ALWAYS);
        HBox modeRow = new HBox(4, cbMode, lblGear);
        modeRow.setAlignment(Pos.CENTER_LEFT);
        lblStrat.setWrapText(true);
        lblStrat.setMaxWidth(WIDTH - 18);
        tpCheck = checkRow("TP.", ckTp);
        rowChecks = two(checkRow("SL.", ckSl), tpCheck);
        rowUnits = two(cbSlUnit, cbTpUnit);
        rowVals = two(spSl, spTp);
        ocoBox.getChildren().addAll(
            modeRow,
            lblStrat,
            rowChecks,
            rowUnits,
            rowVals,
            two(checkRow("BE.", ckBe), checkRow("Trail.", ckTrail)),
            two(cbBeUnit, cbTrailUnit),
            two(spBeTrig, spTrTrig),
            two(spBeOff, spTrDist),
            sc);
        b.getChildren().add(ocoBox);

        Btn link = new Btn(T.t("Enlazar órdenes pendientes"), "tp-out-green", () -> engine.linkPending());
        Tooltip.install(link, new Tooltip(T.t("Sin posición: enlaza las entradas pendientes (al ejecutarse una se cancelan las demás).\n")
            + T.t("Con posición: convierte en OCO las órdenes de salida sueltas (stop + objetivo): al ejecutarse una se cancela la otra.")));
        b.getChildren().add(link);

        return b;
    }

    // ------------------------------------------------------------------
    //  controles <-> Config
    // ------------------------------------------------------------------

    private void wire() {
        cbQtyMode.onChange = v -> change(c -> {
            c.qtyMode = "Risk".equals(v) ? Config.QtyMode.RISK : "Promediado".equals(v) ? Config.QtyMode.AVG : Config.QtyMode.QTY;
            if (c.qtyMode != Config.QtyMode.QTY) {
                // Risk y Promediado marcan el stop en el gráfico con el SL del panel: no van con una estrategia de salida
                c.strategy = "";
                c.oco = true; c.slOn = true; if (!Config.isMoney(c.slUnit)) c.slUnit = Config.Unit.MONEY;
            }
        });
        spAvgTotal.onChange = v -> change(c -> c.avgTotal = v);
        spAvgPct.onChange = v -> change(c -> c.avgPct = v);
        spQty.onChange = v -> change(c -> c.qty = Math.max(1, (int) v));
        ckTickDiff.onChange = v -> change(c -> c.tickDiffOn = v);
        spTickDiff.onChange = v -> change(c -> c.tickDiff = (int) v);
        swOco.onChange = v -> change(c -> { c.oco = v; if (!v) c.qtyMode = Config.QtyMode.QTY; });
        ckSl.onChange = v -> change(c -> { c.slOn = v; if (!v) c.qtyMode = Config.QtyMode.QTY; });
        ckTp.onChange = v -> change(c -> c.tpOn = v);
        cbSlUnit.onChange = v -> change(c -> {
            c.slUnit = unitOf(v);
            if (!Config.isMoney(c.slUnit)) c.qtyMode = Config.QtyMode.QTY;
        });
        cbTpUnit.onChange = v -> change(c -> c.tpUnit = unitOf(v));
        spSl.onChange = v -> change(c -> {
            switch (c.slUnit) {
                case MONEY:  c.slMoney = v; break;
                case POINTS: c.slPoints = v; break;
                case PCT_INI: case PCT_CUR: c.slPct = v; break;
                default:     c.slTicks = Math.max(1, Math.round(v));
            }
        });
        spTp.onChange = v -> change(c -> {
            switch (c.tpUnit) {
                case MONEY:  c.tpMoney = v; break;
                case POINTS: c.tpPoints = v; break;
                case PCT_INI: case PCT_CUR: c.tpPct = v; break;
                case RR:     c.tpRR = v; break;
                default:     c.tpTicks = Math.max(1, Math.round(v));
            }
        });
        ckBe.onChange = v -> change(c -> c.beOn = v);
        ckTrail.onChange = v -> change(c -> c.trailOn = v);
        spBeTrig.onChange = v -> change(c -> c.beTrigger = (int) v);
        spBeOff.onChange = v -> change(c -> c.beOffset = (int) v);
        spTrTrig.onChange = v -> change(c -> c.trailTrigger = (int) v);
        spTrDist.onChange = v -> change(c -> c.trailDist = Math.max(1, (int) v));
        cbMode.onChange = v -> change(c -> {
            Config.Strategy st = c.find(v);
            c.strategy = st == null ? "" : st.name;
            if (st != null) c.qtyMode = Config.QtyMode.QTY;     // la estrategia define los stops y su propio riesgo
        });
        cbMode.extraLabel = T.t("Nueva estrategia…");
        cbMode.extra = () -> editStrategy(null, false);
        rServer.onPick = () -> change(c -> c.server = true);
        rClient.onPick = () -> change(c -> c.server = false);
    }

    private static Config.Unit unitOf(String v) {
        switch (v) {
            case "Money": return Config.Unit.MONEY;
            case "Puntos": return Config.Unit.POINTS;
            case "RR": return Config.Unit.RR;
            case "% Inicial": return Config.Unit.PCT_INI;
            case "% Actual": return Config.Unit.PCT_CUR;
            default: return Config.Unit.TICKS;
        }
    }

    private static String unitName(Config.Unit u) {
        switch (u) {
            case MONEY: return "Money";
            case POINTS: return "Puntos";
            case RR: return "RR";
            case PCT_INI: return "% Inicial";
            case PCT_CUR: return "% Actual";
            default: return "Ticks";
        }
    }

    /** Un control ha cambiado: actualiza Config, reaplica dependencias y avisa al motor. */
    private void change(Consumer<Config> edit) {
        if (loading || engine == null) return;
        Config c = engine.cfg;
        edit.accept(c);
        load(c);
        engine.configChanged();
        Platform.runLater(this::refit);          // el panel cambia de alto al mostrar u ocultar filas
    }

    private void refit() {
        if (!stage.isShowing()) return;
        List<Screen> at = Screen.getScreensForRectangle(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        fit((at.isEmpty() ? Screen.getPrimary() : at.get(0)).getVisualBounds());
    }

    // ------------------------------------------------------------------
    //  estrategias Multi (como en DeepCharts: ajustes -> nueva estrategia -> añadir brackets -> guardar)
    // ------------------------------------------------------------------

    private void gearMenu() {
        if (engine == null) return;
        Config.Strategy cur = engine.cfg.multi();
        ContextMenu menu = new ContextMenu();
        menu.getStyleClass().add("tp-menu");
        MenuItem nu = new MenuItem(T.t("Nueva estrategia…"));
        nu.setOnAction(a -> editStrategy(null, false));
        menu.getItems().add(nu);
        if (cur != null) {
            MenuItem ed = new MenuItem(T.t("Editar «") + cur.name + "»…");
            ed.setOnAction(a -> editStrategy(cur, false));
            MenuItem du = new MenuItem(T.t("Duplicar «") + cur.name + "»…");
            du.setOnAction(a -> editStrategy(cur, true));
            MenuItem de = new MenuItem(T.t("Eliminar «") + cur.name + "»");
            de.setOnAction(a -> change(c -> c.removeStrategy(cur.name)));
            menu.getItems().addAll(ed, du, new SeparatorMenuItem(), de);
        }
        menu.show(lblGear, Side.BOTTOM, 0, 2);
    }

    /** Texto bajo «Modo» con la estrategia elegida; si calcula la cantidad por riesgo, esa cantidad va al campo de cantidad. */
    private void stratInfo() {
        TradeEngine e = engine;
        if (e == null) return;
        Config c = e.cfg;
        Config.Strategy st = c.multi();
        if (st == null) return;
        boolean stratRisk = c.oco && !c.sizing() && st.riskOn;
        int q = stratRisk ? e.strategyQty() : -1;
        boolean bad = stratRisk && q == 0;
        String t = st.summary();
        if (bad) t = T.t("El riesgo de «") + st.name + T.t("» no da para 1 contrato") + (st.anyStop() ? "" : T.t(" (no tiene ningún stop)"));
        boolean changed = !t.equals(lblStrat.getText());
        if (changed) lblStrat.setText(t);
        toggle(lblStrat, "tp-warn", bad);
        if (stratRisk) {
            spQty.setBlank(q < 1);
            if (q >= 1) spQty.set(q);
        }
        if (changed && !loading) refit();
    }

    /** Una fila de la tabla de pasos del editor de estrategias. */
    private static final class StepRow {
        final Spin pct = new Spin(false, 0, 100, 5, false), stopOff = new Spin(false, 0, 1e7, 1, false), tgtOff = new Spin(false, 0, 1e7, 1, false);
        final Combo stopType = new Combo(null, "Stop", "Trail", "MIT"), tgtType = new Combo(null, "Limit", "MIT");
        final Check stopOn = new Check(), tgtOn = new Check();

        StepRow(Config.Step st) {
            pct.width(40); stopOff.width(46); tgtOff.width(46);
            stopType.setPrefWidth(74); tgtType.setPrefWidth(74);
            pct.set(st.pct); stopOff.set(st.stopOff); tgtOff.set(st.targetOff);
            stopType.set(st.stopType == Config.StopType.TRAIL ? "Trail" : st.stopType == Config.StopType.MIT ? "MIT" : "Stop");
            tgtType.set(st.targetType == Config.TargetType.MIT ? "MIT" : "Limit");
            stopOn.set(st.stopOn); tgtOn.set(st.targetOn);
            stopOn.onChange = v -> dim();
            tgtOn.onChange = v -> dim();
            Tooltip.install(stopType, new Tooltip(T.t("Stop: orden stop en el bróker.\n")
                + T.t("Trail: orden stop que el panel va acercando para que quede como mucho a esa distancia del precio.\n")
                + T.t("MIT: en el lado del stop equivale a Stop (el SDK no tiene órdenes MIT).")));
            Tooltip.install(tgtType, new Tooltip(T.t("Limit: orden límite en el bróker.\n")
                + T.t("MIT: el panel vigila el precio y, cuando lo toca, cierra esa parte a mercado\n")
                + T.t("(no hay orden en el bróker: MotiveWave tiene que seguir abierto).")));
            dim();
        }

        void dim() {
            stopOff.setDisable(!stopOn.isOn()); stopType.setDisable(!stopOn.isOn());
            tgtOff.setDisable(!tgtOn.isOn()); tgtType.setDisable(!tgtOn.isOn());
        }

        Config.Step toStep() {
            String s = stopType.value();
            return new Config.Step(pct.value(), stopOn.isOn(), stopOff.value(),
                "Trail".equals(s) ? Config.StopType.TRAIL : "MIT".equals(s) ? Config.StopType.MIT : Config.StopType.STOP,
                tgtOn.isOn(), tgtOff.value(), "MIT".equals(tgtType.value()) ? Config.TargetType.MIT : Config.TargetType.LIMIT);
        }
    }

    /**
     * Ventana "Exit Strategy", con la misma estructura que la de MotiveWave: nombre, tipo de offset,
     * riesgo (calcula la cantidad), break even y la tabla de pasos (cantidad %, stop y objetivo).
     */
    private void editStrategy(Config.Strategy src, boolean copy) {
        if (engine == null) return;
        if (stratDialog != null) stratDialog.close();
        final Config cfg = engine.cfg;
        final String replaces = src != null && !copy ? src.name : null;
        final Stage d = new Stage(StageStyle.UNDECORATED);
        stratDialog = d;
        d.initOwner(stage);
        d.setTitle("Estrategia OCO");
        d.setResizable(false);

        Label desc = label(T.t("Una estrategia de salida crea automáticamente uno o más stops y objetivos al entrar en una posición. ")
            + T.t("Cada paso tiene un stop y un objetivo (los dos opcionales). Con Riesgo activado, la cantidad se calcula a partir de la distancia de los stops."), "tp-desc");
        desc.setWrapText(true);
        desc.setMaxWidth(520);

        TextField name = new TextField(src == null ? "" : copy ? src.name + T.t(" (copia)") : src.name);
        name.getStyleClass().addAll("tp-input", "tp-name");
        name.setPromptText(T.t("p. ej. MD 25k"));
        HBox.setHgrow(name, Priority.ALWAYS);
        HBox nameBox = field(name);
        nameBox.setPrefWidth(300); nameBox.setMaxWidth(300);

        Combo offType = new Combo(null, "Ticks", "Porcentaje");
        offType.set(src != null && src.percent ? "Porcentaje" : "Ticks");
        offType.setPrefWidth(110); offType.setMaxWidth(110);
        Tooltip.install(offType, new Tooltip(T.t("Ticks: las distancias son ticks desde el precio de entrada.\n")
            + T.t("Porcentaje: son un % del precio de entrada (1 % con entrada en 100 = 1,00).")));

        Spin risk = new Spin(false, 0, 1e9, 50, false);
        risk.width(64);
        risk.set(src == null ? 500 : src.risk);
        Combo riskType = new Combo(null, "Importe fijo", "% de la cuenta");
        riskType.set(src != null && src.riskPct ? "% de la cuenta" : "Importe fijo");
        riskType.setPrefWidth(130);
        Check riskOn = new Check();
        riskOn.set(src != null && src.riskOn);
        Runnable riskDim = () -> { risk.setDisable(!riskOn.isOn()); riskType.setDisable(!riskOn.isOn()); };
        riskOn.onChange = v -> riskDim.run();
        riskDim.run();
        HBox riskRow = new HBox(6, risk, riskType, riskOn, label(T.t("Activado"), "tp-val"));
        riskRow.setAlignment(Pos.CENTER_LEFT);
        Tooltip.install(riskRow, new Tooltip(T.t("Con Riesgo activado, la cantidad de la entrada la calcula la estrategia:\n")
            + T.t("la mayor con la que, si saltan todos los stops, no se pierde más de ese importe (se redondea hacia abajo).\n")
            + T.t("% de la cuenta: ese % del saldo actual.")));

        Spin beTrig = new Spin(false, 0, 1e7, 1, false), beOff = new Spin(false, 0, 1e7, 1, false);
        beTrig.width(46); beOff.width(46);
        beTrig.set(src == null ? 10 : src.beTrig);
        beOff.set(src == null ? 0 : src.beOff);
        Check beOn = new Check();
        beOn.set(src != null && src.beOn);
        Runnable beDim = () -> { beTrig.setDisable(!beOn.isOn()); beOff.setDisable(!beOn.isOn()); };
        beOn.onChange = v -> beDim.run();
        beDim.run();
        HBox beRow = new HBox(6, beTrig, label("Offset:", "tp-val"), beOff, beOn, label(T.t("Activado"), "tp-val"));
        beRow.setAlignment(Pos.CENTER_LEFT);
        Tooltip.install(beRow, new Tooltip(T.t("Break Even: cuando el precio avanza a favor esa distancia, los stops pasan a la entrada.\n")
            + T.t("Offset: distancia más allá de la entrada donde quedan (para cubrir comisiones).")));

        GridPane form = new GridPane();
        form.setHgap(8); form.setVgap(5);
        String[] caps = {T.t("Nombre:"), T.t("Tipo de offset:"), T.t("Riesgo:"), "Break Even:"};
        Node[] vals = {nameBox, offType, riskRow, beRow};
        for (int i = 0; i < caps.length; i++) {
            Label cap = label(caps[i], "tp-val");
            GridPane.setHalignment(cap, javafx.geometry.HPos.RIGHT);
            form.add(cap, 0, i);
            form.add(vals[i], 1, i);
        }

        final List<StepRow> rows = new ArrayList<>();
        if (src == null) rows.add(new StepRow(new Config.Step(100, true, 40, Config.StopType.STOP, true, 80, Config.TargetType.LIMIT)));
        else for (Config.Step st : src.steps) rows.add(new StepRow(st));
        GridPane table = new GridPane();
        table.setHgap(6); table.setVgap(4);
        table.getStyleClass().add("tp-table");
        Label note = label("", "tp-strat"), err = label("", "tp-strat");
        note.setWrapText(true); err.setWrapText(true);
        note.setMaxWidth(520); err.setMaxWidth(520);
        // sin texto no ocupan sitio
        for (Label l : new Label[]{note, err}) { l.managedProperty().bind(l.textProperty().isNotEmpty()); l.visibleProperty().bind(l.textProperty().isNotEmpty()); }
        err.getStyleClass().add("tp-warn");
        final Runnable[] redraw = new Runnable[1];
        final Runnable sums = () -> {
            double t = 0;
            for (StepRow r : rows) t += r.pct.value();
            String v = Config.Strategy.num(t);
            note.setText(Math.abs(t - 100) < 0.001 ? ""
                : t < 100 ? T.t("Los pasos suman ") + v + T.t(" %: el resto de la cantidad queda sin stop ni objetivo")
                : T.t("Los pasos suman ") + v + T.t(" %: lo que pasa de 100 % no se usa (los últimos pasos se quedan sin contratos)"));
        };
        redraw[0] = () -> {
            table.getChildren().clear();
            String[] head = {T.t("Paso"), T.t("Cantidad (%)"), T.t("Offset stop"), T.t("Tipo"), "", T.t("Offset objetivo"), T.t("Tipo"), "", ""};
            for (int k = 0; k < head.length; k++) table.add(label(head[k], "tp-th"), k, 0);
            for (int i = 0; i < rows.size(); i++) {
                final StepRow r = rows.get(i);
                Label no = label(String.valueOf(i + 1), "tp-val");
                GridPane.setHalignment(no, javafx.geometry.HPos.RIGHT);
                r.pct.onChange = v -> sums.run();
                HBox ops = new HBox(2);
                ops.setAlignment(Pos.CENTER_LEFT);
                if (i > 0) {
                    Label del = label("\u2715", "tp-rowdel");
                    Tooltip.install(del, new Tooltip(T.t("Quitar este paso")));
                    del.setOnMouseClicked(ev -> { rows.remove(r); redraw[0].run(); });
                    ops.getChildren().add(del);
                }
                if (i == rows.size() - 1) {
                    Label add = label("+", "tp-rowadd");
                    Tooltip.install(add, new Tooltip(T.t("Añadir un paso")));
                    add.setOnMouseClicked(ev -> {
                        double used = 0;
                        for (StepRow o : rows) used += o.pct.value();
                        Config.Step last = r.toStep();
                        rows.add(new StepRow(new Config.Step(Math.max(0, 100 - used), last.stopOn, last.stopOff, last.stopType, last.targetOn, last.targetOff, last.targetType)));
                        redraw[0].run();
                    });
                    ops.getChildren().add(add);
                }
                Node[] cells = {no, r.pct, r.stopOff, r.stopType, r.stopOn, r.tgtOff, r.tgtType, r.tgtOn, ops};
                for (int k = 0; k < cells.length; k++) table.add(cells[k], k, i + 1);
            }
            sums.run();
            err.setText("");
            d.sizeToScene();
        };

        Btn save = new Btn("OK", "tp-buy", () -> {
            String n = Config.Strategy.clean(name.getText());
            boolean pct = "Porcentaje".equals(offType.value());
            List<Config.Step> steps = new ArrayList<>();
            boolean any = false;
            for (StepRow r : rows) { Config.Step st = r.toStep(); steps.add(st); any |= st.hasStop() || st.hasTarget(); }
            Config.Strategy st = new Config.Strategy(n, pct, riskOn.isOn(), risk.value(), "% de la cuenta".equals(riskType.value()),
                beOn.isOn(), beTrig.value(), beOff.value(), steps);
            String bad = n.isEmpty() ? T.t("Ponle un nombre a la estrategia")
                : "SL/TP".equalsIgnoreCase(n) ? T.t("Ese nombre está reservado")
                : cfg.find(n) != null && !n.equals(replaces) ? T.t("Ya hay una estrategia con ese nombre")
                : !any ? T.t("Activa al menos un stop o un objetivo con una distancia mayor que 0")
                : st.riskOn && !st.anyStop() ? T.t("Con Riesgo activado hace falta al menos un stop: de él sale la cantidad")
                : st.riskOn && st.risk <= 0 ? T.t("Con Riesgo activado el importe tiene que ser mayor que 0") : null;
            if (bad != null) { err.setText(bad); d.sizeToScene(); return; }
            d.close();
            change(c -> { c.putStrategy(replaces, st); c.qtyMode = Config.QtyMode.QTY; });
        });
        Btn cancel = new Btn(T.t("Cancelar"), "tp-out-orange", d::close);
        save.setPrefWidth(84); save.setMaxWidth(84);
        cancel.setPrefWidth(84); cancel.setMaxWidth(84);
        HBox buttons = new HBox(8, save, cancel);
        buttons.setAlignment(Pos.CENTER);

        Label title = label("Exit Strategy · " + (replaces != null ? T.t("editar") : T.t("nueva")), "tp-title-text");
        Label closeX = label("\u2715", "tp-close");
        closeX.setOnMouseClicked(ev -> d.close());
        HBox bar = new HBox(title, grow(), closeX);
        bar.getStyleClass().add("tp-title");
        bar.setAlignment(Pos.CENTER_LEFT);
        final double[] off = new double[2];
        bar.setOnMousePressed(ev -> { off[0] = d.getX() - ev.getScreenX(); off[1] = d.getY() - ev.getScreenY(); });
        bar.setOnMouseDragged(ev -> { d.setX(ev.getScreenX() + off[0]); d.setY(ev.getScreenY() + off[1]); });

        VBox body = new VBox(8, desc, form, table, note, err, buttons);
        body.getStyleClass().add("tp-body");
        body.setPadding(new Insets(8, 12, 10, 12));
        VBox box = new VBox(bar, body);
        box.getStyleClass().add("tp-root");
        Scene sc = new Scene(box);
        sc.setFill(Color.web("#151518"));
        sc.getStylesheets().add("data:text/css;base64," + Base64.getEncoder().encodeToString(CSS.getBytes(StandardCharsets.UTF_8)));
        sc.addEventFilter(KeyEvent.KEY_PRESSED, ev -> { if (ev.getCode() == KeyCode.ESCAPE) d.close(); });
        d.setScene(sc);
        d.setOnHidden(ev -> { if (stratDialog == d) stratDialog = null; });
        redraw[0].run();
        d.show();
        // a la izquierda del panel si cabe; si no, a la derecha
        double left = stage.getX() - d.getWidth() - 6;
        boolean fits = !Screen.getScreensForRectangle(left, stage.getY(), d.getWidth(), 80).isEmpty();
        d.setX(fits ? left : stage.getX() + stage.getWidth() + 6);
        d.setY(stage.getY() + 60);
        name.requestFocus();
    }

    /** Vuelca Config en los controles (sin disparar cambios). */
    private void load(Config c) {
        loading = true;
        try {
            boolean risk = c.sizing();
            cbQtyMode.set(c.mode() == Config.QtyMode.AVG ? "Promediado" : risk ? "Risk" : "Qty");
            boolean avg = c.mode() == Config.QtyMode.AVG;
            avgBox.setVisible(avg); avgBox.setManaged(avg);
            spAvgTotal.set(c.avgTotal); spAvgPct.set(c.avgPct);
            // con una estrategia que calcula la cantidad por riesgo, el campo muestra esa cantidad y no se edita
            Config.Strategy rs = c.oco && !risk ? c.multi() : null;
            boolean stratRisk = rs != null && rs.riskOn;
            spQty.set(c.qty);
            spQty.setBlank(risk);
            spQty.setDisable(risk || stratRisk);
            ckTickDiff.set(c.tickDiffOn);
            spTickDiff.set(c.tickDiff);
            spTickDiff.setDim(!c.tickDiffOn);
            swOco.set(c.oco);
            ocoBox.setDisable(!c.oco);
            ocoBox.setOpacity(c.oco ? 1 : 0.35);
            ckSl.set(c.slOn);
            ckTp.set(c.tpOn);
            cbSlUnit.set(unitName(c.slUnit));
            cbTpUnit.set(unitName(c.tpUnit));
            spSl.set(c.slValue());
            spTp.set(c.tpValue());
            spSl.setBlank(!c.slOn); spSl.setDisable(!c.slOn);
            spTp.setBlank(!c.tpOn); spTp.setDisable(!c.tpOn);
            Config.Strategy strat = c.multi();
            boolean multi = strat != null;
            List<Config.Strategy> all = c.strategies;
            String[] modes = new String[all.size() + 1];
            modes[0] = "SL/TP";
            for (int i = 0; i < all.size(); i++) modes[i + 1] = all.get(i).name;
            cbMode.setOptions(modes);
            cbMode.set(multi ? strat.name : "SL/TP");
            // con una estrategia elegida, sus pasos definen el stop y el objetivo: el SL y el TP del panel no se usan
            boolean slRows = !multi;
            for (Node n : new Node[]{rowChecks, rowUnits, rowVals}) { n.setVisible(slRows); n.setManaged(slRows); }
            tpCheck.setVisible(!multi); cbTpUnit.setVisible(!multi); spTp.setVisible(!multi);
            lblStrat.setVisible(multi); lblStrat.setManaged(multi);
            stratInfo();
            cbSlUnit.setDim(!c.slOn);
            cbTpUnit.setDim(!c.tpOn);
            ckBe.set(c.beOn);
            ckTrail.set(c.trailOn);
            spBeTrig.set(c.beTrigger); spBeOff.set(c.beOffset);
            spTrTrig.set(c.trailTrigger); spTrDist.set(c.trailDist);
            spBeTrig.setDim(!c.beOn); spBeOff.setDim(!c.beOn); cbBeUnit.setDim(!c.beOn);
            spTrTrig.setDim(!c.trailOn); spTrDist.setDim(!c.trailOn); cbTrailUnit.setDim(!c.trailOn);
            rServer.set(c.server); rClient.set(!c.server);
        } finally {
            loading = false;
        }
    }

    /** Refresco periódico con la instantánea del motor. */
    private void refresh() {
        TradeEngine e = engine;
        if (e == null) return;
        PanelSnapshot s = e.snapshot();
        lblSymbol.setText(s.symbol);
        lblSaldo.setText(TradeEngine.money(s.balance));
        rowQty.set(Integer.toString(s.openQty), s.openQty);
        rowOpen.set(TradeEngine.money(s.openPnl), s.openQty == 0 ? 0 : s.openPnl);
        rowDaily.set(TradeEngine.money(s.dailyPnl), s.dailyPnl);
        lblBroker.setText(s.broker);
        lblAccount.setText(s.account);
        stratInfo();
        String k = s.armedKind;
        bBuyMkt.armed(s.armedBuy && "MKT".equals(k));   bSellMkt.armed(!s.armedBuy && "MKT".equals(k));
        bBid.armed(s.armedBuy && "BIDASK".equals(k));   bAsk.armed(!s.armedBuy && "BIDASK".equals(k));
        bBuyLmt.armed(s.armedBuy && "LMT".equals(k));   bSellLmt.armed(!s.armedBuy && "LMT".equals(k));
        bBuyStp.armed(s.armedBuy && "STP".equals(k));   bSellStp.armed(!s.armedBuy && "STP".equals(k));
        bBuyStl.armed(s.armedBuy && "STP LMT".equals(k)); bSellStl.armed(!s.armedBuy && "STP LMT".equals(k));
    }

    // ------------------------------------------------------------------
    //  posición de la ventana
    // ------------------------------------------------------------------

    private static Preferences prefs() { return Preferences.userRoot().node("com/tradepanel/window"); }

    private void savePosition() {
        try { Preferences p = prefs(); p.putDouble("x", stage.getX()); p.putDouble("y", stage.getY()); }
        catch (Exception ignore) { }
    }

    /** Si el panel no cabe en la altura de la pantalla, se reduce proporcionalmente. */
    private void fit(Rectangle2D screen) {
        root.applyCss();
        double h = root.prefHeight(WIDTH);
        double s = h > 0 ? Math.max(0.7, Math.min(1, (screen.getHeight() - 24) / h)) : 1;
        zoom.setX(s); zoom.setY(s);
        stage.sizeToScene();
    }

    private void place() {
        Window ref = owner != null ? owner : mainWindow();
        Screen scr = Screen.getPrimary();
        if (ref != null) {
            List<Screen> at = Screen.getScreensForRectangle(ref.getX(), ref.getY(), ref.getWidth(), ref.getHeight());
            if (!at.isEmpty()) scr = at.get(0);
        }
        fit(scr.getVisualBounds());
        double x = Double.NaN, y = Double.NaN;
        try { Preferences p = prefs(); x = p.getDouble("x", Double.NaN); y = p.getDouble("y", Double.NaN); }
        catch (Exception ignore) { }
        if (!Double.isNaN(x) && !Double.isNaN(y) && !Screen.getScreensForRectangle(x, y, WIDTH, 80).isEmpty()) {
            stage.setX(x); stage.setY(y);
            return;
        }
        Window m = owner != null ? owner : mainWindow();
        if (m != null) {
            stage.setX(m.getX() + m.getWidth() - WIDTH - 90);
            stage.setY(m.getY() + 90);
        } else {
            Rectangle2D vb = Screen.getPrimary().getVisualBounds();
            stage.setX(vb.getMaxX() - WIDTH - 40);
            stage.setY(vb.getMinY() + 80);
        }
    }

    // ------------------------------------------------------------------
    //  piezas de interfaz
    // ------------------------------------------------------------------

    private static Label label(String text, String cls) {
        Label l = new Label(text);
        l.getStyleClass().add(cls);
        return l;
    }

    private static Region grow() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private static Region gap(double h) {
        Region r = new Region();
        r.setMinHeight(h); r.setPrefHeight(h);
        return r;
    }

    private static HBox field(Node... nodes) {
        HBox h = new HBox(nodes);
        h.getStyleClass().add("tp-field");
        return h;
    }

    private static HBox center(Node n) {
        HBox h = new HBox(n);
        h.setAlignment(Pos.CENTER);
        return h;
    }

    private static HBox checkRow(String text, Check c) {
        HBox h = new HBox(label(text, "tp-val"), grow(), c);
        h.setAlignment(Pos.CENTER_LEFT);
        h.setPadding(new Insets(2, 1, 2, 2));
        return h;
    }

    /** Fila de dos columnas iguales. */
    private static GridPane two(Node a, Node b) {
        GridPane g = new GridPane();
        g.setHgap(6);
        ColumnConstraints c1 = new ColumnConstraints(), c2 = new ColumnConstraints();
        c1.setPercentWidth(50); c2.setPercentWidth(50);
        c1.setFillWidth(true); c2.setFillWidth(true);
        g.getColumnConstraints().addAll(c1, c2);
        if (a instanceof Region) ((Region) a).setMaxWidth(Double.MAX_VALUE);
        if (b instanceof Region) ((Region) b).setMaxWidth(Double.MAX_VALUE);
        g.add(a, 0, 0);
        g.add(b, 1, 0);
        return g;
    }

    private static SVGPath path(String d, String cls) {
        SVGPath p = new SVGPath();
        p.setContent(d);
        p.getStyleClass().add(cls);
        p.setMouseTransparent(true);
        return p;
    }

    private static Node chevron() { return path("M0 0 L3.5 3.5 L7 0", "tp-glyph"); }

    private static void toggle(Node n, String cls, boolean on) {
        boolean has = n.getStyleClass().contains(cls);
        if (on && !has) n.getStyleClass().add(cls);
        else if (!on && has) n.getStyleClass().remove(cls);
    }

    /** Fila "Cant. abierta / P/L": gris en cero, verde en positivo, morado en negativo. */
    private static final class InfoRow extends HBox {
        private final Label name, value = label("0", "tp-info-val");
        InfoRow(String text) {
            name = label(text, "tp-info-name");
            getChildren().addAll(name, grow(), value);
            getStyleClass().add("tp-field");
        }
        void set(String text, double sign) {
            value.setText(text);
            toggle(this, "tp-pos", sign > 0);
            toggle(this, "tp-neg", sign < 0);
        }
    }

    /** Botón de acción. */
    private static final class Btn extends StackPane {
        Btn(String text, String cls, Runnable action) {
            getStyleClass().addAll("tp-btn", cls);
            Label l = label(text, "tp-btn-text");
            l.setMouseTransparent(true);
            getChildren().add(l);
            setMaxWidth(Double.MAX_VALUE);
            setOnMouseClicked(ev -> { if (ev.getButton() == MouseButton.PRIMARY) action.run(); });
        }
        void armed(boolean on) { toggle(this, "tp-armed", on); }
    }

    /** Casilla de verificación. */
    private static final class Check extends StackPane {
        Consumer<Boolean> onChange;
        private boolean on;
        private final SVGPath mark = path("M2.5 7 L5.5 10 L11 3.5", "tp-checkmark");
        Check() {
            getStyleClass().add("tp-check");
            setMinSize(15, 15); setPrefSize(15, 15); setMaxSize(15, 15);
            getChildren().add(mark);
            mark.setVisible(false);
            setOnMouseClicked(ev -> { set(!on); if (onChange != null) onChange.accept(on); });
        }
        void set(boolean v) { on = v; mark.setVisible(v); toggle(this, "tp-check-on", v); }
        boolean isOn() { return on; }
    }

    /** Interruptor (OCO Strategy). */
    private static final class Switch extends StackPane {
        Consumer<Boolean> onChange;
        private boolean on;
        private final Circle thumb = new Circle(5.5);
        Switch() {
            getStyleClass().add("tp-switch");
            setMinSize(30, 15); setPrefSize(30, 15); setMaxSize(30, 15);
            thumb.setFill(Color.WHITE);
            thumb.setMouseTransparent(true);
            getChildren().add(thumb);
            set(false);
            setOnMouseClicked(ev -> { set(!on); if (onChange != null) onChange.accept(on); });
        }
        void set(boolean v) { on = v; thumb.setTranslateX(v ? 7.5 : -7.5); toggle(this, "tp-switch-on", v); }
    }

    /** Botón de opción (Server / Client). */
    private static final class Radio extends HBox {
        Runnable onPick;
        private final Circle dot = new Circle(3);
        private final Label text;
        Radio(String caption) {
            super(5);
            setAlignment(Pos.CENTER_LEFT);
            StackPane ring = new StackPane();
            ring.getStyleClass().add("tp-radio");
            ring.setMinSize(12, 12); ring.setPrefSize(12, 12); ring.setMaxSize(12, 12);
            dot.setFill(Color.web("#2ecc40"));
            ring.getChildren().add(dot);
            text = label(caption, "tp-dim");
            getChildren().addAll(ring, text);
            setOnMouseClicked(ev -> { if (onPick != null) onPick.run(); });
            set(false);
        }
        void set(boolean on) {
            dot.setVisible(on);
            toggle(text, "tp-val", on);
            toggle(text, "tp-dim", !on);
        }
    }

    /** Desplegable: etiqueta opcional a la izquierda, valor y flecha a la derecha. */
    private static final class Combo extends HBox {
        Consumer<String> onChange;
        private final Label value = label("", "tp-val");
        private String[] options;
        String extraLabel;          // entrada extra al final del menú (p. ej. "Nueva estrategia…")
        Runnable extra;
        Combo(String caption, String... opts) {
            this.options = opts;
            getStyleClass().add("tp-field");
            setSpacing(6);
            if (caption != null) getChildren().addAll(label(caption, "tp-dim"), grow(), value, chevron());
            else getChildren().addAll(value, grow(), chevron());
            set(opts[0]);
            setOnMouseClicked(ev -> {
                if (options.length < 2 && extra == null) return;
                ContextMenu menu = new ContextMenu();
                menu.getStyleClass().add("tp-menu");
                for (String o : options) {
                    MenuItem it = new MenuItem(T.t(o));
                    it.setOnAction(a -> { set(o); if (onChange != null) onChange.accept(o); });
                    menu.getItems().add(it);
                }
                if (extra != null) {
                    MenuItem it = new MenuItem(extraLabel);
                    it.setOnAction(a -> extra.run());
                    menu.getItems().addAll(new SeparatorMenuItem(), it);
                }
                menu.show(this, Side.BOTTOM, 0, 2);
            });
        }
        void setOptions(String... o) { options = o; }
        String value() { return key; }
        private String key = "";
        /** Elige un valor (el original en español); se muestra traducido. */
        void set(String v) { key = v; value.setText(T.t(v)); }
        void setDim(boolean dim) { setOpacity(dim ? 0.45 : 1); }
    }

    /** Campo numérico con flechas, rueda del ratón y edición directa. */
    private static final class Spin extends HBox {
        DoubleConsumer onChange;
        private final TextField tf = new TextField();
        private final boolean integer;
        private final double min, max, step;
        private double val;
        private boolean blank;

        Spin(boolean integer, double min, double max, double step, boolean bare) {
            this.integer = integer; this.min = min; this.max = max; this.step = step;
            if (!bare) getStyleClass().add("tp-field");
            setAlignment(Pos.CENTER_RIGHT);
            setSpacing(3);
            tf.getStyleClass().add("tp-input");
            tf.setPrefColumnCount(4);
            tf.setMinWidth(24);
            tf.setPrefWidth(bare ? 44 : 60);
            HBox.setHgrow(tf, Priority.ALWAYS);
            StackPane up = arrow("M0 3.5 L3.5 0 L7 3.5"), dn = arrow("M0 0 L3.5 3.5 L7 0");
            up.setOnMousePressed(ev -> nudge(+1));
            dn.setOnMousePressed(ev -> nudge(-1));
            VBox arrows = new VBox(1, up, dn);
            arrows.setAlignment(Pos.CENTER);
            getChildren().addAll(tf, arrows);
            tf.setOnAction(ev -> commit());
            tf.focusedProperty().addListener((o, was, now) -> { if (!now) commit(); });
            setOnScroll(ev -> { if (ev.getDeltaY() != 0) { nudge(ev.getDeltaY() > 0 ? +1 : -1); ev.consume(); } });
        }

        private static StackPane arrow(String d) {
            StackPane s = new StackPane(path(d, "tp-glyph"));
            s.setMinSize(13, 9); s.setPrefSize(13, 9);
            s.getStyleClass().add("tp-arrow");
            return s;
        }

        private double clamp(double v) {
            v = Math.max(min, Math.min(max, v));
            return integer ? Math.rint(v) : Math.round(v * 100) / 100.0;
        }

        private String text() {
            if (integer || val == Math.rint(val)) return String.format(T.loc(), "%d", (long) val);
            return String.format(T.loc(), "%.2f", val);
        }

        private void show() { tf.setText(blank ? "" : text()); }

        void set(double v) { val = clamp(v); show(); }
        /** Valor actual, dando por bueno lo que se esté tecleando. */
        double value() { commit(); return val; }
        void setBlank(boolean b) { blank = b; show(); }
        void width(double w) { tf.setPrefWidth(w); }
        boolean editing() { return tf.isFocused(); }
        void setDim(boolean dim) { setOpacity(dim ? 0.45 : 1); }

        private void nudge(int dir) {
            if (isDisabled() || blank) return;
            apply(val + dir * step);
        }

        private void commit() {
            if (blank) return;
            String s = tf.getText() == null ? "" : tf.getText().trim();
            if (T.en) s = s.replace(",", "");                                  // formato inglés: 1,234.5
            else if (s.contains(",")) s = s.replace(".", "").replace(',', '.');   // formato español: 1.234,5
            try { apply(Double.parseDouble(s)); }
            catch (NumberFormatException e) { show(); }
        }

        private void apply(double v) {
            double n = clamp(v);
            boolean changed = n != val;
            val = n;
            show();
            if (changed && onChange != null) onChange.accept(val);
        }
    }

    // ------------------------------------------------------------------
    //  estilo (colores del panel de DeepCharts)
    // ------------------------------------------------------------------

    private static final String CSS = String.join("\n",
        ".tp-root { -fx-background-color: #151518; -fx-border-color: #2c2c33; -fx-border-width: 1; -fx-font-family: 'Segoe UI'; -fx-font-size: 12px; }",
        ".tp-title { -fx-background-color: #0f0f11; -fx-padding: 2 0 2 8; -fx-cursor: move; }",
        ".tp-title-text { -fx-text-fill: #7d7d88; -fx-font-size: 11px; }",
        ".tp-close { -fx-text-fill: #7d7d88; -fx-font-size: 11px; -fx-padding: 1 8 1 8; -fx-cursor: hand; }",
        ".tp-close:hover { -fx-text-fill: white; -fx-background-color: #c42b1c; }",
        ".tp-body { -fx-padding: 6 8 8 8; }",
        ".tp-field { -fx-background-color: #2a2a30; -fx-background-radius: 3; -fx-padding: 0 6 0 8; -fx-min-height: 23; -fx-pref-height: 23; -fx-max-height: 23; -fx-alignment: center-left; }",
        ".tp-field:disabled { -fx-opacity: 0.45; }",
        ".tp-dim { -fx-text-fill: #7d7d88; }",
        ".tp-val { -fx-text-fill: #e4e4e8; }",
        ".tp-head { -fx-text-fill: #9a9aa5; -fx-padding: 2 0 0 0; }",
        ".tp-pos-text { -fx-text-fill: #3ddc4f; }",
        ".tp-info-name { -fx-text-fill: #7d7d88; }",
        ".tp-info-val { -fx-text-fill: #7d7d88; }",
        ".tp-pos { -fx-background-color: #123d1a; }",
        ".tp-pos .tp-info-name, .tp-pos .tp-info-val { -fx-text-fill: #3ddc4f; -fx-font-weight: bold; }",
        ".tp-neg { -fx-background-color: #3a1560; }",
        ".tp-neg .tp-info-name, .tp-neg .tp-info-val { -fx-text-fill: #c77dff; -fx-font-weight: bold; }",
        ".tp-status { -fx-text-fill: #7d7d88; -fx-font-size: 10.5px; -fx-padding: 2 2 0 2; }",
        ".tp-status-armed { -fx-text-fill: #ff8c1a; -fx-font-weight: bold; }",
        ".tp-glyph { -fx-stroke: #9a9aa5; -fx-stroke-width: 1.3; -fx-fill: transparent; }",
        ".tp-arrow { -fx-cursor: hand; }",
        ".tp-arrow:hover .tp-glyph { -fx-stroke: white; }",
        ".tp-input { -fx-background-color: transparent; -fx-background-insets: 0; -fx-background-radius: 0; -fx-border-width: 0; -fx-padding: 0 1 0 1;"
            + " -fx-text-fill: #e4e4e8; -fx-alignment: center-right; -fx-highlight-fill: #3d5a80; -fx-highlight-text-fill: white;"
            + " -fx-font-family: 'Segoe UI'; -fx-font-size: 12px; -fx-cursor: text; }",
        ".tp-input:focused { -fx-background-color: transparent; -fx-background-insets: 0; }",
        ".tp-btn { -fx-background-radius: 3; -fx-border-radius: 3; -fx-border-width: 1; -fx-border-color: transparent; -fx-padding: 0 4 0 4;"
            + " -fx-min-height: 23; -fx-pref-height: 23; -fx-max-height: 23; -fx-cursor: hand; }",
        ".tp-btn-text { -fx-text-fill: white; -fx-font-weight: bold; }",
        ".tp-buy { -fx-background-color: linear-gradient(to bottom, #248f24, #186318); }",
        ".tp-buy:hover { -fx-background-color: linear-gradient(to bottom, #2ba82b, #1d771d); }",
        ".tp-buy:pressed { -fx-background-color: #145214; }",
        ".tp-sell { -fx-background-color: linear-gradient(to bottom, #6a25b3, #4a1782); }",
        ".tp-sell:hover { -fx-background-color: linear-gradient(to bottom, #7d2fd0, #581c9a); }",
        ".tp-sell:pressed { -fx-background-color: #3d126c; }",
        ".tp-armed { -fx-border-color: white; -fx-border-width: 1.6; }",
        ".tp-out-orange { -fx-background-color: transparent; -fx-border-color: #ff7f0e; }",
        ".tp-out-orange .tp-btn-text { -fx-text-fill: #ff8c1a; -fx-font-weight: normal; }",
        ".tp-out-orange:hover { -fx-background-color: #3a2410; }",
        ".tp-out-orange:pressed { -fx-background-color: #5a3410; }",
        ".tp-fill-orange { -fx-background-color: #ff7f0e; }",
        ".tp-fill-orange .tp-btn-text { -fx-font-weight: normal; }",
        ".tp-fill-orange:hover { -fx-background-color: #ff9633; }",
        ".tp-fill-orange:pressed { -fx-background-color: #d96a05; }",
        ".tp-out-green { -fx-background-color: transparent; -fx-border-color: #2ecc40; }",
        ".tp-out-green .tp-btn-text { -fx-text-fill: #3ddc4f; -fx-font-weight: normal; }",
        ".tp-out-green:hover { -fx-background-color: #12301a; }",
        ".tp-out-green:pressed { -fx-background-color: #1a4a26; }",
        ".tp-check { -fx-background-color: #3a3a42; -fx-background-radius: 3; -fx-border-color: #55555e; -fx-border-radius: 3; -fx-cursor: hand; }",
        ".tp-check-on { -fx-background-color: #2ea043; -fx-border-color: #2ea043; }",
        ".tp-checkmark { -fx-stroke: white; -fx-stroke-width: 1.8; -fx-fill: transparent; }",
        ".tp-switch { -fx-background-color: #4a4a54; -fx-background-radius: 8; -fx-cursor: hand; }",
        ".tp-switch-on { -fx-background-color: #2ea043; }",
        ".tp-radio { -fx-background-color: transparent; -fx-border-color: #7d7d88; -fx-border-radius: 6; -fx-background-radius: 6; }",
        ".tp-gear, .tp-match { -fx-background-color: #2a2a30; -fx-background-radius: 3; -fx-min-width: 23; -fx-pref-width: 23; -fx-max-width: 23;"
            + " -fx-min-height: 23; -fx-pref-height: 23; -fx-max-height: 23; -fx-alignment: center; -fx-text-fill: #9a9aa5; -fx-font-size: 13px; }",
        ".tp-gear { -fx-font-family: 'Segoe UI Symbol'; -fx-cursor: hand; }",
        ".tp-gear:hover { -fx-text-fill: white; -fx-background-color: #3d3d47; }",
        ".tp-match { -fx-font-weight: bold; }",
        ".tp-match-ok { -fx-text-fill: #3ddc4f; -fx-background-color: #123d1a; }",
        ".tp-match-bad { -fx-text-fill: #ff6b6b; -fx-background-color: #4a1616; }",
        ".tp-strat { -fx-text-fill: #7d7d88; -fx-font-size: 10.5px; -fx-padding: 0 2 1 2; }",
        ".tp-warn { -fx-text-fill: #ff6b6b; }",
        ".tp-name { -fx-alignment: center-left; }",
        ".tp-ex-stop { -fx-text-fill: #ff8fa3; }",
        ".tp-ex-target { -fx-text-fill: #7ee08a; }",
        ".tp-rowx { -fx-text-fill: #7d7d88; -fx-font-size: 10px; -fx-padding: 0 2 0 6; -fx-cursor: hand; }",
        ".tp-rowx:hover { -fx-text-fill: white; }",
        ".tp-desc { -fx-text-fill: #9a9aa5; -fx-font-size: 11.5px; }",
        ".tp-th { -fx-text-fill: #9a9aa5; -fx-font-size: 11px; -fx-padding: 0 0 0 2; }",
        ".tp-table { -fx-border-color: #3a3a42; -fx-border-width: 1; -fx-border-radius: 3; -fx-padding: 6 8 8 8; }",
        ".tp-rowdel { -fx-text-fill: #ff5252; -fx-font-size: 11px; -fx-padding: 0 4 0 4; -fx-cursor: hand; }",
        ".tp-rowadd { -fx-text-fill: #3ddc4f; -fx-font-size: 16px; -fx-font-weight: bold; -fx-padding: 0 4 0 4; -fx-cursor: hand; }",
        ".tp-rowdel:hover, .tp-rowadd:hover { -fx-text-fill: white; }",
        ".tp-menu .separator .line { -fx-border-color: #45454f; -fx-border-width: 1 0 0 0; }",
        ".tp-menu { -fx-background-color: #2a2a30; -fx-border-color: #45454f; -fx-padding: 2; }",
        ".tp-menu .menu-item { -fx-background-color: transparent; -fx-padding: 4 18 4 10; }",
        ".tp-menu .menu-item .label { -fx-text-fill: #e4e4e8; -fx-font-family: 'Segoe UI'; -fx-font-size: 12px; }",
        ".tp-menu .menu-item:focused { -fx-background-color: #3d3d47; }");
}
