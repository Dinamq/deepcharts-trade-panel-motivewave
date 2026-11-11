package com.tradepanel;

import java.util.ArrayList;
import java.util.List;

import com.motivewave.platform.sdk.common.Enums$OrderAction;
import com.motivewave.platform.sdk.common.Enums$OrderType;
import com.motivewave.platform.sdk.common.Enums$TIF;
import com.motivewave.platform.sdk.order_mgmt.Order;

/**
 * Pruebas del motor contra el bróker simulado (sin MotiveWave ni cuenta real).
 * Ejecutar: build.ps1 (las lanza antes de empaquetar) o  java com.tradepanel.EngineTest
 */
public final class EngineTest {

    private static int passed, failed;
    private static String current = "";

    private static void check(String what, boolean ok) {
        if (ok) passed++;
        else { failed++; System.out.println("  FALLO [" + current + "] " + what); }
    }

    private static void eq(String what, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) what += "  (esperado " + expected + ", obtenido " + actual + ")";
        check(what, ok);
    }

    private static void near(String what, double expected, double actual) {
        check(what + "  (esperado " + expected + ", obtenido " + actual + ")", Math.abs(expected - actual) < 1e-3);
    }

    /** Entorno: MNQ (tick 0,25; 2 $ por punto) con el motor en modo síncrono. */
    private static final class Env {
        final SimBroker sim;
        final TradeEngine eng = new TradeEngine(Runnable::run);
        final List<String> logs = new ArrayList<>();
        Env(float price) {
            sim = new SimBroker(0.25, 2.0, price);
            sim.engine = eng;
            eng.bind(sim.ctx, new TradeEngine.Host() {
                @Override public void redraw() { }
                @Override public void log(String msg) { logs.add(msg); }
            }, this);
        }
        LineModel line(String id) { for (LineModel m : eng.lines()) if (m.id.equals(id)) return m; return null; }
        LineModel lineWith(String text) { for (LineModel m : eng.lines()) if (m.text.contains(text)) return m; return null; }
        String status() { return eng.snapshot().status; }
        String dump() { StringBuilder sb = new StringBuilder(); for (LineModel m : eng.lines()) sb.append("\n      ").append(m.id).append(" ").append(m.text).append(m.linked ? " [link]" : "").append(m.money != null ? " | " + m.money : "").append(" @ ").append(m.price); return sb.toString(); }
    }

    private static void test(String name, Runnable r) {
        current = name;
        int before = failed;
        try { r.run(); }
        catch (Throwable t) { failed++; System.out.println("  EXCEPCIÓN [" + name + "] " + t); t.printStackTrace(System.out); }
        System.out.println((failed == before ? "ok    " : "FALLA ") + name);
    }

    public static void main(String[] args) {
        test("venta límite con OCO SL/TP en ticks: hijas internas, activación y cancelación cruzada", () -> {
            Env e = new Env(100f);
            e.eng.cfg.qty = 2; e.eng.cfg.oco = true; e.eng.cfg.slOn = true; e.eng.cfg.tpOn = true;
            e.eng.cfg.slTicks = 40; e.eng.cfg.tpTicks = 80;
            e.eng.arm(false, TradeEngine.Kind.LIMIT);
            check("queda armado", e.eng.isArmed());
            e.eng.hover(105f);
            check("vista previa de entrada", e.lineWith("PR | ENTRY - LMT") != null);
            check("vista previa del stop", e.lineWith("PR | +2 STP") != null);
            e.eng.chartClick(105f, false, false, false);
            check("se desarma tras colocar", !e.eng.isArmed());
            SimBroker.SimOrder entry = e.sim.working(Enums$OrderType.LIMIT, false);
            check("orden límite de venta enviada", entry != null && entry.limit == 105f && entry.qty == 2);
            LineModel ent = e.lineWith("Subm | -2 LMT");
            check("línea de entrada con botones SL TP X", ent != null && ent.buttons.size() == 3);
            LineModel sl = e.lineWith("Int | +2 STP"), tp = e.lineWith("Int | +2 LMT");
            check("stop interno a +40 ticks", sl != null && sl.price == 115f && sl.linked);
            check("objetivo interno a -80 ticks", tp != null && tp.price == 85f && tp.linked);
            eq("riesgo del stop en dinero", "-40,00 $", sl == null ? null : sl.money);
            eq("beneficio del objetivo en dinero", "80,00 $", tp == null ? null : tp.money);
            eq("solo la entrada está en el bróker", 1, e.sim.activeCount());

            e.sim.price(105f);                       // se ejecuta la entrada
            eq("posición corta de 2", -2, e.sim.position);
            SimBroker.SimOrder stop = e.sim.working(Enums$OrderType.STOP, true), target = e.sim.working(Enums$OrderType.LIMIT, true);
            check("stop vivo en 115", stop != null && stop.stop == 115f && stop.qty == 2);
            check("objetivo vivo en 85", target != null && target.limit == 85f && target.qty == 2);
            check("etiqueta del stop pasa a Subm", e.lineWith("Subm | +2 STP") != null && e.lineWith("Int |") == null);
            check("línea de posición", e.lineWith("-2 QTY") != null);

            e.sim.price(84.75f);                     // el ask (84,75 + 1 tick) toca el objetivo de compra en 85
            eq("posición cerrada", 0, e.sim.position);
            eq("no quedan órdenes vivas (stop cancelado)", 0, e.sim.activeCount());
            eq("no quedan líneas", 0, e.eng.lines().size());
            near("P/L realizado", 80.0, e.sim.realized);
        });

        test("modo Risk como en el vídeo: 4000 $ de riesgo, entrada 29777,50 y stop 29933,50 -> 13 contratos", () -> {
            Env e = new Env(29765f);
            Config c = e.eng.cfg;
            c.qtyMode = Config.QtyMode.RISK; c.oco = true; c.slOn = true; c.slUnit = Config.Unit.MONEY; c.slMoney = 4000; c.tpOn = false;
            e.eng.arm(false, TradeEngine.Kind.LIMIT);
            e.eng.chartClick(29777.50f, false, false, false);
            check("sigue armado esperando el stop", e.eng.isArmed());
            check("pide el nivel del stop", e.eng.banner().contains("stop"));
            eq("aún no hay órdenes", 0, e.sim.activeCount());
            e.eng.hover(29933.50f);
            LineModel pr = e.lineWith("PR | -13 STP");
            check("vista previa con 13 contratos" + e.dump(), pr != null);
            eq("riesgo mostrado", "4056,00 $", pr == null ? null : pr.money);
            e.eng.chartClick(29933.50f, false, false, false);
            SimBroker.SimOrder entry = e.sim.working(Enums$OrderType.LIMIT, false);
            check("entrada de 13 contratos", entry != null && entry.qty == 13 && entry.limit == 29777.50f);
            LineModel sl = e.lineWith("Int | +13 STP");
            check("stop interno en 29933,50", sl != null && sl.price == 29933.50f);
            eq("riesgo del stop", "-4056,00 $", sl == null ? null : sl.money);
            check("desarmado", !e.eng.isArmed());

            e.eng.arm(false, TradeEngine.Kind.LIMIT);
            e.eng.chartClick(29800f, false, false, false);
            e.eng.chartClick(29790f, false, false, false);     // stop por debajo de una venta: lado incorrecto
            check("stop en lado incorrecto rechazado y sigue armado", e.eng.isArmed() && e.status().contains("lado incorrecto"));
            e.eng.disarm();
            check("desarmar", !e.eng.isArmed());
        });

        test("modo Risk exige SL en Money", () -> {
            Env e = new Env(100f);
            e.eng.cfg.qtyMode = Config.QtyMode.RISK; e.eng.cfg.oco = false;
            e.eng.market(true);
            check("no arma ni envía", !e.eng.isArmed() && e.sim.orders.isEmpty());
            check("explica el motivo", e.status().contains("Risk"));
        });

        test("compra a mercado en modo Risk: el clic fija el stop y la cantidad", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.qtyMode = Config.QtyMode.RISK; c.oco = true; c.slOn = true; c.slUnit = Config.Unit.MONEY; c.slMoney = 100; c.tpOn = true; c.tpUnit = Config.Unit.TICKS; c.tpTicks = 40;
            e.eng.market(true);
            check("armado para el stop", e.eng.isArmed());
            e.eng.chartClick(95.25f, false, false, false);   // ask 100,25 -> 5 puntos = 10 $/contrato -> 10 contratos
            eq("posición larga de 10", 10, e.sim.position);
            SimBroker.SimOrder stop = e.sim.working(Enums$OrderType.STOP, false), target = e.sim.working(Enums$OrderType.LIMIT, false);
            check("stop vivo en 95,25 x10", stop != null && stop.stop == 95.25f && stop.qty == 10);
            check("objetivo a +40 ticks de la ejecución", target != null && target.limit == 110.25f && target.qty == 10);
        });

        test("compra a mercado con OCO en dinero: distancia = dinero / (cantidad x valor del tick)", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.qty = 4; c.oco = true; c.slOn = true; c.slUnit = Config.Unit.MONEY; c.slMoney = 80; c.tpOn = false;
            e.eng.market(true);
            eq("posición", 4, e.sim.position);
            SimBroker.SimOrder stop = e.sim.working(Enums$OrderType.STOP, false);
            // 80 $ / (4 x 0,5 $) = 40 ticks = 10 puntos bajo 100,25
            check("stop a 40 ticks", stop != null && stop.stop == 90.25f && stop.qty == 4);
        });

        test("validación de lado: límite de compra por encima del ask y stop de venta por encima del bid", () -> {
            Env e = new Env(100f);
            e.eng.arm(true, TradeEngine.Kind.LIMIT);
            e.eng.chartClick(101f, false, false, false);
            check("no envía y sigue armado", e.sim.orders.isEmpty() && e.eng.isArmed());
            e.eng.chartClick(99f, false, false, false);
            check("límite válido enviado", e.sim.working(Enums$OrderType.LIMIT, true) != null);
            e.eng.arm(false, TradeEngine.Kind.STOP);
            e.eng.chartClick(100.5f, false, false, false);
            check("stop de venta inválido no enviado", e.sim.working(Enums$OrderType.STOP, false) == null);
        });

        test("BID / ASK con Dif. en ticks", () -> {
            Env e = new Env(100f);
            e.eng.bidAsk(true);
            SimBroker.SimOrder b = e.sim.working(Enums$OrderType.LIMIT, true);
            check("compra en el bid", b != null && b.limit == 100f);
            e.eng.cfg.tickDiffOn = true; e.eng.cfg.tickDiff = 8;
            e.eng.bidAsk(false);
            SimBroker.SimOrder s = e.sim.working(Enums$OrderType.LIMIT, false);
            check("venta 8 ticks sobre el ask", s != null && s.limit == 102.25f);
        });

        test("arrastres: mover la entrada arrastra las hijas internas; mover un stop vivo modifica la orden", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.qty = 1; c.oco = true; c.slOn = true; c.tpOn = true; c.slTicks = 20; c.tpTicks = 20;
            e.eng.arm(true, TradeEngine.Kind.LIMIT);
            e.eng.chartClick(98f, false, false, false);
            LineModel ent = e.lineWith("Subm | +1 LMT");
            e.eng.moveLine(ent.id, 97f);
            SimBroker.SimOrder entry = e.sim.working(Enums$OrderType.LIMIT, true);
            check("entrada modificada a 97", entry.limit == 97f);
            check("stop interno acompaña", e.lineWith("Int | -1 STP").price == 92f);
            check("objetivo interno acompaña", e.lineWith("Int | -1 LMT").price == 102f);
            e.eng.moveLine(e.lineWith("Int | -1 STP").id, 99f);
            check("stop interno no cruza la entrada", e.lineWith("Int | -1 STP").price == 92f);
            e.eng.moveLine(e.lineWith("Int | -1 STP").id, 90f);
            check("stop interno movido", e.lineWith("Int | -1 STP").price == 90f);

            e.sim.price(96.5f);
            eq("posición larga", 1, e.sim.position);
            SimBroker.SimOrder stop = e.sim.working(Enums$OrderType.STOP, false);
            check("stop vivo donde se dejó", stop.stop == 90f);
            e.eng.moveLine(e.lineWith("Subm | -1 STP").id, 94f);
            check("stop vivo modificado", stop.stop == 94f);
            e.eng.moveLine(e.lineWith("Subm | -1 STP").id, 97f);
            check("no se puede subir por encima del bid", stop.stop == 94f);
            e.eng.lineButton(e.lineWith("Subm | -1 STP").id, "X");
            check("X cancela el stop y deja el objetivo", e.sim.working(Enums$OrderType.STOP, false) == null && e.sim.working(Enums$OrderType.LIMIT, false) != null);
        });

        test("punto de equilibrio: mueve el stop con beneficio, lo rechaza con pérdida y lo crea si no hay", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.qty = 2; c.oco = true; c.slOn = true; c.tpOn = false; c.slTicks = 40;
            e.eng.market(true);                              // compra en 100,25
            SimBroker.SimOrder stop = e.sim.working(Enums$OrderType.STOP, false);
            e.sim.price(99f);
            e.eng.breakeven();
            check("rechazado en pérdida", stop.stop == 90.25f && e.status().contains("NO aplicado"));
            e.sim.price(104f);
            e.eng.breakeven();
            check("stop en el precio medio", stop.stop == 100.25f);

            Env f = new Env(100f);
            f.eng.cfg.oco = false; f.eng.cfg.qty = 3;
            f.eng.market(false);                             // venta en 100
            f.sim.price(97f);
            f.eng.optBeOffset = 2;
            f.eng.breakeven();
            SimBroker.SimOrder s = f.sim.working(Enums$OrderType.STOP, true);
            check("stop creado 2 ticks a favor de la entrada", s != null && s.stop == 99.5f && s.qty == 3);
            f.sim.price(99.5f);
            eq("stop ejecutado: plano", 0, f.sim.position);
            eq("sin líneas", 0, f.eng.lines().size());
        });

        test("BE automático y trailing stop", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.qty = 1; c.oco = true; c.slOn = true; c.tpOn = false; c.slTicks = 40;
            c.beOn = true; c.beTrigger = 20; c.beOffset = 4;
            c.trailOn = true; c.trailTrigger = 40; c.trailDist = 20;
            e.eng.market(true);                              // compra en 100,25; stop 90,25
            SimBroker.SimOrder stop = e.sim.working(Enums$OrderType.STOP, false);
            check("línea Trail. Trigger a +40 ticks", e.lineWith("Trail. Trigger") != null && e.lineWith("Trail. Trigger").price == 110.25f);
            e.sim.price(104f);
            check("sin cambios antes del disparo", stop.stop == 90.25f);
            e.sim.price(105.5f);                             // +21 ticks: BE
            check("BE automático a entrada + 4 ticks", stop.stop == 101.25f);
            e.sim.price(110.5f);                             // supera el disparador
            check("trailing arranca: stop a 20 ticks del precio", stop.stop == 105.5f);
            check("desaparece la línea del disparador", e.lineWith("Trail. Trigger") == null);
            sleep(320);
            e.sim.price(112f);
            check("el stop sigue al precio", stop.stop == 107f);
            sleep(320);
            e.sim.price(111f);
            check("el stop no retrocede", stop.stop == 107f);
            e.sim.price(107f);
            eq("stop ejecutado", 0, e.sim.position);
        });

        test("botón TS del gráfico y arrastre del disparador", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.qty = 1; c.oco = true; c.slOn = true; c.tpOn = false; c.slTicks = 40; c.trailTrigger = 20; c.trailDist = 12;
            e.eng.market(false);                             // venta en 100; stop 110
            LineModel sl = e.lineWith("Subm | +1 STP");
            check("TS apagado", sl != null && !sl.buttons.get(0).active);
            e.eng.lineButton(sl.id, "TS");
            LineModel tr = e.lineWith("Trail. Trigger");
            check("disparador a -20 ticks", tr != null && tr.price == 95f);
            check("TS encendido", e.lineWith("Subm | +1 STP").buttons.get(0).active);
            e.eng.moveLine(tr.id, 98f);
            check("disparador movido", e.lineWith("Trail. Trigger").price == 98f);
            e.sim.price(97.75f);
            SimBroker.SimOrder stop = e.sim.working(Enums$OrderType.STOP, true);
            check("trailing a 12 ticks", stop.stop == 100.75f);
        });

        test("enlazar órdenes pendientes: al ejecutarse una se cancela la otra", () -> {
            Env e = new Env(100f);
            e.eng.cfg.qty = 1;
            e.eng.arm(true, TradeEngine.Kind.STOP);
            e.eng.chartClick(102f, false, false, false);
            e.eng.arm(false, TradeEngine.Kind.STOP);
            e.eng.chartClick(98f, false, false, false);
            eq("dos entradas", 2, e.sim.activeCount());
            e.eng.linkPending();
            check("etiquetas enlazadas", e.lineWith("Subm | +1 STP").linked && e.lineWith("Subm | -1 STP").linked);
            e.sim.price(102f);
            eq("posición larga", 1, e.sim.position);
            eq("la venta se canceló", 0, e.sim.activeCount());
        });

        test("STP LMT emulada: se dispara al tocar el stop y envía la límite", () -> {
            Env e = new Env(100f);
            e.eng.cfg.qty = 2; e.eng.optStpLmtOffset = 2;
            e.eng.arm(true, TradeEngine.Kind.STOP_LIMIT);
            e.eng.chartClick(103f, false, false, false);
            eq("nada en el bróker todavía", 0, e.sim.activeCount());
            check("línea interna", e.lineWith("Int | +2 STP LMT") != null);
            e.sim.price(102f);
            eq("sigue sin disparar", 0, e.sim.activeCount());
            e.sim.price(103f);
            SimBroker.SimOrder l = e.sim.working(Enums$OrderType.LIMIT, true);
            check("límite a stop + 2 ticks", l != null && l.limit == 103.5f && l.qty == 2);
        });

        test("ejecución parcial: las salidas crecen con cada tramo", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.qty = 3; c.oco = true; c.slOn = true; c.tpOn = true; c.slTicks = 20; c.tpTicks = 20;
            e.eng.arm(true, TradeEngine.Kind.LIMIT);
            e.eng.chartClick(99f, false, false, false);
            SimBroker.SimOrder entry = e.sim.working(Enums$OrderType.LIMIT, true);
            e.sim.fill(entry, 1, 99f);
            SimBroker.SimOrder stop = e.sim.working(Enums$OrderType.STOP, false), target = e.sim.working(Enums$OrderType.LIMIT, false);
            check("salidas por 1", stop != null && stop.qty == 1 && target != null && target.qty == 1);
            e.sim.fill(entry, 2, 99f);
            check("salidas por 3", stop.qty == 3 && target.qty == 3);
            eq("posición", 3, e.sim.position);
            e.sim.fill(target, 1, 104f);
            check("objetivo parcial reduce el stop", stop.qty == 2 && stop.isActive());
        });

        test("botones SL / TP / X de la línea de posición", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.qty = 2; c.oco = false; c.slTicks = 20; c.tpTicks = 40;
            e.eng.market(true);                              // compra 2 en 100,25
            eq("sin salidas", 0, e.sim.activeCount());
            e.eng.lineButton("POS", "SL");
            check("SL: queda armado y pide el precio (no usa la distancia del panel)", e.eng.isArmed() && e.eng.banner().contains("SL"));
            eq("aún no envía nada", 0, e.sim.activeCount());
            e.eng.hover(97f);
            LineModel pr = e.lineWith("PR | -2 STP");
            check("vista previa del stop sigue al ratón", pr != null && pr.price == 97f);
            eq("importe de la vista previa", "-13,00 $", pr == null ? null : pr.money);
            e.eng.chartClick(101f, false, false, false);     // por encima del bid: se ejecutaría ya
            check("stop inválido rechazado y sigue armado", e.eng.isArmed() && e.sim.activeCount() == 0 && e.status().contains("NO colocado"));
            e.eng.chartClick(97f, false, false, false);
            SimBroker.SimOrder stop = e.sim.working(Enums$OrderType.STOP, false);
            check("stop creado donde se hizo clic", stop != null && stop.stop == 97f && stop.qty == 2);
            check("desarmado tras colocar", !e.eng.isArmed());
            check("stop sin pareja: sin eslabón", !e.lineWith("Subm | -2 STP").linked);
            e.eng.lineButton("POS", "SL");
            check("no duplica el stop", e.sim.activeCount() == 1 && !e.eng.isArmed());
            e.eng.lineButton("POS", "TP");
            e.eng.lineButton("POS", "TP");
            check("pulsar de nuevo el botón cancela la colocación", !e.eng.isArmed() && e.sim.activeCount() == 1);
            e.eng.pickBegin("POS", false);                   // arrastrando el botón TP
            e.eng.hover(104f);
            check("vista previa del objetivo al arrastrar", e.lineWith("PR | -2 LMT") != null && e.lineWith("PR | -2 LMT").price == 104f);
            e.eng.pickDrop(104f);
            SimBroker.SimOrder target = e.sim.working(Enums$OrderType.LIMIT, false);
            check("objetivo creado al soltar", target != null && target.limit == 104f && target.qty == 2 && !e.eng.isArmed());
            check("stop + objetivo forman OCO (eslabón)", e.lineWith("Subm | -2 STP").linked && e.lineWith("Subm | -2 LMT").linked);
            e.eng.lineButton("POS", "X");
            eq("posición cerrada", 0, e.sim.position);
            eq("salidas canceladas", 0, e.sim.activeCount());
        });

        test("Cancelar y Cancel and Flat", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.qty = 1; c.oco = true; c.slOn = true; c.tpOn = true;
            e.eng.market(true);
            e.eng.arm(true, TradeEngine.Kind.LIMIT);
            e.eng.chartClick(95f, false, false, false);
            eq("stop + objetivo + límite", 3, e.sim.activeCount());
            e.eng.cancelAll();
            eq("todo cancelado", 0, e.sim.activeCount());
            eq("la posición sigue", 1, e.sim.position);
            e.eng.arm(false, TradeEngine.Kind.LIMIT);
            e.eng.chartClick(108f, false, false, false);
            e.eng.cancelAndFlat();
            eq("plano", 0, e.sim.position);
            eq("sin órdenes", 0, e.sim.activeCount());
            eq("sin líneas", 0, e.eng.lines().size());
        });

        test("protección: máximo de órdenes por segundo", () -> {
            Env e = new Env(100f);
            e.eng.optMaxPerSec = 3; e.eng.cfg.oco = false;
            for (int i = 0; i < 5; i++) e.eng.market(true);
            eq("solo 3 enviadas", 3, e.sim.position);
            check("avisa del bloqueo", e.status().contains("Límite"));
        });

        test("botones SL / TP sobre una entrada pendiente sin OCO y cambio de cantidad", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.qty = 2; c.oco = false; c.slTicks = 16; c.tpTicks = 32;
            e.eng.arm(false, TradeEngine.Kind.LIMIT);
            e.eng.chartClick(104f, false, false, false);
            LineModel ent = e.lineWith("Subm | -2 LMT");
            check("sin hijas", e.lineWith("Int |") == null);
            e.eng.lineButton(ent.id, "SL");
            check("SL armado", e.eng.isArmed());
            e.eng.chartClick(103f, false, false, false);     // por debajo de una venta: lado de beneficio
            check("stop en el lado incorrecto rechazado y sigue armado", e.eng.isArmed() && e.lineWith("Int |") == null && e.status().contains("NO colocado"));
            e.eng.chartClick(108f, false, false, false);
            check("SL interno donde se hizo clic", e.lineWith("Int | +2 STP") != null && e.lineWith("Int | +2 STP").price == 108f && !e.eng.isArmed());
            e.eng.lineButton(ent.id, "SL");
            check("SL ya definido: no vuelve a armar", !e.eng.isArmed() && e.status().contains("ya definido"));
            e.eng.pickBegin(ent.id, false);                  // arrastrando el botón TP
            e.eng.pickDrop(96f);
            check("TP interno donde se soltó", e.lineWith("Int | +2 LMT") != null && e.lineWith("Int | +2 LMT").price == 96f);
            eq("solo la entrada está en el bróker", 1, e.sim.activeCount());
            eq("cantidad leída de la etiqueta", 2, e.eng.qtyOf(ent.id));
            e.eng.editQty(ent.id, 5);
            check("cantidad modificada", e.sim.working(Enums$OrderType.LIMIT, false).qty == 5);
            e.eng.lineButton(ent.id, "X");
            eq("entrada cancelada", 0, e.sim.activeCount());
            eq("hijas internas eliminadas", 0, e.eng.lines().size());
        });

        test("arrastre en vivo: la línea, el importe y las hijas se actualizan antes de soltar", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.qty = 1; c.oco = true; c.slOn = true; c.tpOn = true; c.slTicks = 20; c.tpTicks = 20;
            e.eng.arm(true, TradeEngine.Kind.LIMIT);
            e.eng.chartClick(98f, false, false, false);
            String sl = e.lineWith("Int | -1 STP").id, ent = e.lineWith("Subm | +1 LMT").id;
            e.eng.dragLine(sl, 91f);
            check("el stop se dibuja en el precio del arrastre", e.line(sl).price == 91f);
            eq("su importe se recalcula en vivo", "-14,00 $", e.line(sl).money);
            e.eng.moveLine(sl, 91f);
            check("al soltar queda ahí", e.line(sl).price == 91f);

            e.eng.dragLine(ent, 97f);
            check("la entrada se dibuja en el precio del arrastre", e.line(ent).price == 97f);
            check("las hijas la acompañan en vivo", e.line(sl).price == 90f && e.lineWith("Int | -1 LMT").price == 102f);
            check("la orden real no cambia hasta soltar", e.sim.working(Enums$OrderType.LIMIT, true).limit == 98f);
            e.eng.moveLine(ent, 97f);
            check("al soltar se modifica la orden", e.sim.working(Enums$OrderType.LIMIT, true).limit == 97f && e.line(sl).price == 90f);
            e.eng.dragLine(ent, 101f);
            e.eng.moveLine(ent, 101f);                       // límite de compra sobre el ask: rechazado
            check("movimiento inválido: la línea vuelve a su sitio", e.line(ent).price == 97f && e.line(sl).price == 90f);
        });

        test("modo Server: el stop de una entrada límite va al bróker con ella", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.qty = 2; c.oco = true; c.slOn = true; c.tpOn = true; c.slTicks = 20; c.tpTicks = 40; c.server = true;
            e.eng.arm(true, TradeEngine.Kind.LIMIT);
            e.eng.chartClick(98f, false, false, false);
            eq("entrada y stop en el bróker", 2, e.sim.activeCount());
            SimBroker.SimOrder entry = e.sim.working(Enums$OrderType.LIMIT, true), stop = e.sim.working(Enums$OrderType.STOP, false);
            check("stop real a 20 ticks por toda la cantidad", stop != null && stop.stop == 93f && stop.qty == 2);
            check("el stop se muestra como enviado y el objetivo como interno" + e.dump(),
                e.lineWith("Subm | -2 STP") != null && e.lineWith("Int | -2 STP") == null && e.lineWith("Int | -2 LMT") != null);
            check("lo indica en el estado", e.status().contains("bróker"));
            String ent = e.lineWith("Subm | +2 LMT").id, sl = e.lineWith("Subm | -2 STP").id;
            e.eng.moveLine(ent, 97f);
            check("mover la entrada mueve el stop real y el objetivo interno", entry.limit == 97f && stop.stop == 92f && e.lineWith("Int | -2 LMT").price == 107f);
            e.eng.moveLine(sl, 97.5f);
            check("el stop no puede cruzar la entrada", stop.stop == 92f && e.status().contains("NO movido"));
            e.eng.moveLine(sl, 91f);
            check("stop movido", stop.stop == 91f);
            e.eng.editQty(ent, 3);
            check("cambiar la cantidad ajusta también el stop", entry.qty == 3 && stop.qty == 3);

            e.sim.price(96.5f);                              // se ejecuta la entrada
            eq("posición", 3, e.sim.position);
            SimBroker.SimOrder target = e.sim.working(Enums$OrderType.LIMIT, false);
            check("el stop es la misma orden y se envía el objetivo", stop.isActive() && stop.qty == 3 && target != null && target.limit == 107f && target.qty == 3);
            eq("stop + objetivo", 2, e.sim.activeCount());
            e.sim.price(107f);
            eq("objetivo ejecutado: plano", 0, e.sim.position);
            eq("stop cancelado", 0, e.sim.activeCount());

            Env f = new Env(100f);
            f.eng.cfg.qty = 1; f.eng.cfg.oco = true; f.eng.cfg.slOn = true; f.eng.cfg.tpOn = false; f.eng.cfg.slTicks = 20; f.eng.cfg.server = true;
            f.eng.market(true);                              // compra en 100,25 con stop vivo en 95,25
            f.eng.arm(false, TradeEngine.Kind.LIMIT);
            f.eng.chartClick(104f, false, false, false);     // venta límite con su stop de compra en 109
            eq("stop de la posición + entrada + su stop", 3, f.sim.activeCount());
            f.eng.breakeven();
            check("el punto de equilibrio no toca el stop de la entrada pendiente", f.sim.working(Enums$OrderType.STOP, true).stop == 109f);
            f.eng.lineButton("POS", "X");
            eq("cerrar la posición no cancela el stop de la entrada pendiente", 2, f.sim.activeCount());
            f.eng.lineButton(f.lineWith("Subm | -1 LMT").id, "X");
            eq("cancelar la entrada cancela su stop", 0, f.sim.activeCount());
            eq("sin líneas", 0, f.eng.lines().size());

            Env g = new Env(100f);
            g.eng.cfg.qty = 1; g.eng.cfg.oco = true; g.eng.cfg.slOn = true; g.eng.cfg.server = true;
            g.eng.arm(true, TradeEngine.Kind.STOP);
            g.eng.chartClick(102f, false, false, false);
            check("entrada STP: el stop sigue interno (no es seguro adelantarlo)", g.sim.activeCount() == 1 && g.lineWith("Int | -1 STP") != null);

            Env h = new Env(100f);
            h.eng.cfg.qty = 2; h.eng.cfg.oco = false; h.eng.cfg.server = true;
            h.eng.arm(true, TradeEngine.Kind.LIMIT);
            h.eng.chartClick(98f, false, false, false);
            h.eng.lineButton(h.lineWith("Subm | +2 LMT").id, "SL");
            h.eng.chartClick(95f, false, false, false);
            SimBroker.SimOrder hs = h.sim.working(Enums$OrderType.STOP, false);
            check("SL manual sobre una límite en modo Server: va al bróker", hs != null && hs.stop == 95f && hs.qty == 2);

            Env p = new Env(100f);
            p.eng.cfg.qty = 3; p.eng.cfg.oco = true; p.eng.cfg.slOn = true; p.eng.cfg.tpOn = true; p.eng.cfg.slTicks = 20; p.eng.cfg.tpTicks = 20; p.eng.cfg.server = true;
            p.eng.arm(true, TradeEngine.Kind.LIMIT);
            p.eng.chartClick(99f, false, false, false);
            SimBroker.SimOrder pe = p.sim.working(Enums$OrderType.LIMIT, true), ps = p.sim.working(Enums$OrderType.STOP, false);
            p.sim.fill(pe, 1, 99f);
            SimBroker.SimOrder pt = p.sim.working(Enums$OrderType.LIMIT, false);
            check("ejecución parcial: el stop sigue cubriendo toda la entrada y el objetivo lo ejecutado", ps.isActive() && ps.qty == 3 && pt != null && pt.qty == 1);
            p.eng.lineButton(p.lineWith("Subm | +3 LMT").id, "X");
            check("entrada cancelada a medias: el stop se reduce a lo ejecutado", ps.isActive() && ps.qty == 1 && p.sim.position == 1);
            p.sim.fill(pt, 1, 104f);
            eq("objetivo ejecutado: el stop se cancela", 0, p.sim.activeCount());
        });

        test("enlazar con posición abierta: las salidas sueltas pasan a ser un OCO", () -> {
            Env e = new Env(100f);
            e.eng.cfg.qty = 2; e.eng.cfg.oco = false;
            e.eng.market(true);                              // compra 2 en 100,25
            // órdenes puestas fuera del panel (p. ej. desde MotiveWave o tras reiniciar)
            Order s = e.sim.ctx.createStopOrder(Enums$OrderAction.SELL, Enums$TIF.GTC, 2, 97f);
            Order t = e.sim.ctx.createLimitOrder(Enums$OrderAction.SELL, Enums$TIF.GTC, 2, 104f);
            e.sim.ctx.submitOrders(s, t);
            e.eng.linkPending();
            check("lo indica", e.status().contains("OCO"));
            LineModel sl = e.lineWith("Subm | -2 STP");
            check("el stop pasa a ser salida de la posición (botón TS, eslabón)" + e.dump(), sl != null && sl.linked && "TS".equals(sl.buttons.get(0).text));
            eq("importe respecto al precio medio", "-13,00 $", sl == null ? null : sl.money);
            e.sim.price(104f);
            eq("objetivo ejecutado: plano", 0, e.sim.position);
            eq("el stop se cancela solo", 0, e.sim.activeCount());

            Env f = new Env(100f);
            f.eng.cfg.qty = 1; f.eng.cfg.oco = false;
            f.eng.market(false);                             // venta en 100
            Order bs = f.sim.ctx.createStopOrder(Enums$OrderAction.BUY, Enums$TIF.GTC, 1, 103f);
            f.sim.ctx.submitOrders(bs);
            f.eng.linkPending();
            check("un stop suelto queda ligado a la posición", f.status().contains("posición"));
            f.eng.lineButton("POS", "X");
            eq("al cerrar la posición se cancela", 0, f.sim.activeCount());
        });

        test("agrupar SL de distintas órdenes soltando uno sobre otro (como en el vídeo)", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.qty = 2; c.oco = true; c.slOn = true; c.tpOn = true; c.slTicks = 20; c.tpTicks = 20;
            e.eng.arm(false, TradeEngine.Kind.LIMIT);
            e.eng.chartClick(104f, false, false, false);     // venta 2 en 104: stop 109, objetivo 99
            e.eng.arm(false, TradeEngine.Kind.LIMIT);
            e.eng.chartClick(106f, false, false, false);     // venta 2 en 106: stop 111, objetivo 101
            LineModel s1 = e.lineWith("Int | +2 STP");
            check("dos stops sueltos", s1 != null && e.lineWith("Int | +4 STP") == null);
            String id1 = null, id2 = null;
            for (LineModel m : e.eng.lines()) if (m.kind == LineModel.Kind.STOP) { if (m.price == 109f) id1 = m.id; else if (m.price == 111f) id2 = m.id; }
            check("mismo mergeKey para los dos stops", e.line(id1).mergeKey != null && e.line(id1).mergeKey.equals(e.line(id2).mergeKey));
            e.eng.moveLine(id1, 111f);                       // suelta el primer stop sobre el segundo
            LineModel g = e.lineWith("Int | +4 STP");
            check("una sola línea con la cantidad sumada" + e.dump(), g != null && g.price == 111f && e.lineWith("Int | +2 STP") == null);
            eq("importe sumado (-28 y -20)", "-48,00 $", g == null ? null : g.money);
            check("botón #2", g != null && g.buttons.stream().anyMatch(b -> "#2".equals(b.text)));
            check("lo indica", e.status().contains("Agrupados 2 stops"));

            e.eng.moveLine(g.id, 112f);
            check("el grupo se mueve junto", e.lineWith("Int | +4 STP").price == 112f);
            e.eng.moveLine(g.id, 105f);                      // por debajo de la venta de 106: inválido para uno de ellos
            check("si no vale para uno, no se mueve ninguno", e.lineWith("Int | +4 STP").price == 112f && e.status().contains("NO movido"));
            e.eng.moveLine(e.lineWith("Subm | -2 LMT").id, 103f);
            check("mover una entrada no saca su stop del grupo", e.lineWith("Int | +4 STP") != null && e.lineWith("Int | +4 STP").price == 112f);

            e.sim.price(103f);                               // se ejecuta la venta de 103
            eq("posición corta de 2", -2, e.sim.position);
            LineModel mixed = e.lineWith("+4 STP");
            check("stop vivo + interno siguen agrupados" + e.dump(), mixed != null && mixed.text.startsWith("Subm/Int") && mixed.price == 112f);
            SimBroker.SimOrder live = e.sim.working(Enums$OrderType.STOP, true);
            check("el vivo es su propia orden de 2", live != null && live.qty == 2 && live.stop == 112f);
            e.eng.moveLine(mixed.id, 110f);
            check("mover el grupo mueve también la orden viva", live.stop == 110f && e.lineWith("+4 STP").price == 110f);

            e.eng.lineButton(mixed.id, "TS");
            check("TS activa el trailing en todo el grupo", e.lineWith("+4 STP").buttons.get(0).active);
            e.eng.lineButton(mixed.id, "#2");
            check("#2 los separa", e.lineWith("+4 STP") == null && e.lineWith("Subm | +2 STP") != null && e.lineWith("Int | +2 STP") != null);
            e.eng.moveLine(e.lineWith("Int | +2 STP").id, 110f);
            check("soltar otra vez encima los vuelve a agrupar", e.lineWith("+4 STP") != null);
            e.eng.lineButton(e.lineWith("+4 STP").id, "X");
            check("X cancela todos los stops del grupo", e.lineWith("STP") == null && e.sim.working(Enums$OrderType.STOP, true) == null);
            check("los objetivos siguen", e.lineWith("LMT |") == null && e.lineWith("+2 LMT") != null);

            e.eng.moveLine(e.lineWith("Int | +2 LMT").id, e.lineWith("Subm | +2 LMT").price);
            check("también se agrupan objetivos (TP)", e.lineWith("+4 LMT") != null && e.lineWith("+4 LMT").text.startsWith("Subm/Int"));

            Env f = new Env(100f);
            f.eng.cfg.qty = 1; f.eng.cfg.oco = true; f.eng.cfg.slOn = true; f.eng.cfg.slTicks = 20;
            f.eng.arm(false, TradeEngine.Kind.LIMIT); f.eng.chartClick(104f, false, false, false);
            f.eng.arm(true, TradeEngine.Kind.LIMIT); f.eng.chartClick(96f, false, false, false);
            check("stops de lados contrarios no comparten mergeKey",
                !e.eng.lines().isEmpty() && !f.lineWith("Int | +1 STP").mergeKey.equals(f.lineWith("Int | -1 STP").mergeKey));
        });

        test("SL / TP en puntos y TP en RR (múltiplo del stop)", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.qty = 1; c.oco = true; c.slOn = true; c.tpOn = true;
            c.slUnit = Config.Unit.POINTS; c.slPoints = 2.5; c.tpUnit = Config.Unit.RR; c.tpRR = 3;
            e.eng.arm(true, TradeEngine.Kind.LIMIT);
            e.eng.hover(98f);
            check("vista previa del objetivo en RR", e.lineWith("PR | -1 LMT") != null && e.lineWith("PR | -1 LMT").price == 105.5f);
            e.eng.chartClick(98f, false, false, false);
            check("stop a 2,5 puntos", e.lineWith("Int | -1 STP").price == 95.5f);
            check("objetivo a 3 x 2,5 puntos", e.lineWith("Int | -1 LMT").price == 105.5f);

            Env f = new Env(100f);
            Config d = f.eng.cfg;
            d.qty = 2; d.oco = true; d.slOn = true; d.tpOn = true;
            d.slUnit = Config.Unit.MONEY; d.slMoney = 40; d.tpUnit = Config.Unit.RR; d.tpRR = 1.5;
            f.eng.arm(false, TradeEngine.Kind.LIMIT);
            f.eng.chartClick(104f, false, false, false);     // 40 $ / (2 x 0,5 $) = 40 ticks = 10 puntos
            check("RR sobre un stop en dinero", f.lineWith("Int | +2 STP").price == 114f && f.lineWith("Int | +2 LMT").price == 89f);
            d.tpUnit = Config.Unit.POINTS; d.tpPoints = 4; d.slOn = false;
            f.eng.arm(true, TradeEngine.Kind.LIMIT);
            f.eng.chartClick(96f, false, false, false);
            check("objetivo en puntos sin stop", f.lineWith("Int | -2 LMT") != null && f.lineWith("Int | -2 LMT").price == 100f);

            Env g = new Env(100f);
            Config k = g.eng.cfg;
            k.qtyMode = Config.QtyMode.RISK; k.oco = true; k.slOn = true; k.slUnit = Config.Unit.MONEY; k.slMoney = 100;
            k.tpOn = true; k.tpUnit = Config.Unit.RR; k.tpRR = 2;
            g.eng.market(true);
            g.eng.hover(95.25f);
            check("modo Risk: la vista previa usa el stop marcado", g.lineWith("PR | -10 LMT") != null && g.lineWith("PR | -10 LMT").price == 110.25f);
            g.eng.chartClick(95.25f, false, false, false);   // ask 100,25: stop a 5 puntos -> 10 contratos
            SimBroker.SimOrder t = g.sim.working(Enums$OrderType.LIMIT, false);
            check("modo Risk: RR 2 sobre el stop marcado en el gráfico", t != null && t.limit == 110.25f && t.qty == 10);
        });

        test("clic con modificadores (opcional) y estrategia inactiva", () -> {
            Env e = new Env(100f);
            check("desactivado por defecto", !e.eng.wantsClick(false, true));
            e.eng.optModClick = true; e.eng.cfg.qty = 1; e.eng.cfg.oco = false;
            check("Ctrl+clic aceptado", e.eng.wantsClick(false, true));
            e.eng.chartClick(98f, false, true, false);
            check("Ctrl bajo el precio = compra límite", e.sim.working(Enums$OrderType.LIMIT, true) != null);
            e.eng.chartClick(97f, true, false, false);
            check("Shift bajo el precio = stop de venta", e.sim.working(Enums$OrderType.STOP, false) != null);
            e.eng.unbind(e);
            e.eng.market(true);
            check("inactiva: no opera y lo dice", e.status().contains("inactiva") && e.sim.position == 0);
            check("inactiva: sin líneas", e.eng.lines().isEmpty());
        });

        test("SL en % del balance inicial: 0,2 % de 50.000 = 100 $ de riesgo", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.qty = 4; c.oco = true; c.slOn = true; c.tpOn = false;
            c.slUnit = Config.Unit.PCT_INI; c.slPct = 0.2; c.initBal = 50000;
            e.eng.market(true);
            SimBroker.SimOrder stop = e.sim.working(Enums$OrderType.STOP, false);
            // 100 $ / (4 x 0,5 $) = 50 ticks = 12,5 puntos bajo 100,25
            check("stop a 50 ticks", stop != null && stop.stop == 87.75f && stop.qty == 4);
        });

        test("SL y TP en % del saldo actual", () -> {
            Env e = new Env(100f);
            e.sim.cash = 20000;
            Config c = e.eng.cfg;
            c.qty = 4; c.oco = true; c.slOn = true; c.tpOn = true;
            c.slUnit = Config.Unit.PCT_CUR; c.slPct = 0.5;      // 100 $
            c.tpUnit = Config.Unit.PCT_CUR; c.tpPct = 1;        // 200 $
            e.eng.market(true);
            SimBroker.SimOrder stop = e.sim.working(Enums$OrderType.STOP, false), target = e.sim.working(Enums$OrderType.LIMIT, false);
            check("stop a 100 $", stop != null && stop.stop == 87.75f);
            check("objetivo a 200 $", target != null && target.limit == 125.25f);
        });

        test("% del balance inicial desconocido: no envía y lo explica", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.qty = 1; c.oco = true; c.slOn = true; c.slUnit = Config.Unit.PCT_INI; c.slPct = 1; c.initBal = 0;
            e.eng.market(true);
            check("sin órdenes", e.sim.orders.isEmpty());
            check("pide el saldo inicial: " + e.status(), e.status().contains("balance inicial"));
        });

        test("modo Risk con SL en % del saldo actual", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.qtyMode = Config.QtyMode.RISK; c.oco = true; c.slOn = true; c.tpOn = false;
            c.slUnit = Config.Unit.PCT_CUR; c.slPct = 0.2;      // 0,2 % de 50.000 = 100 $
            e.eng.market(true);
            check("armado", e.eng.isArmed() && e.eng.banner().contains("100,00 $"));
            e.eng.chartClick(95.25f, false, false, false);
            eq("10 contratos", 10, e.sim.position);
        });

        test("Promediado en contra: 500 $ en total, la 2ª entrada arriesga lo que falta hasta el SL común", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.qtyMode = Config.QtyMode.AVG; c.oco = true; c.slOn = true; c.slUnit = Config.Unit.MONEY; c.slMoney = 100;
            c.avgTotal = 500; c.avgPct = 50; c.tpOn = false;
            e.eng.market(true);
            check("1ª entrada: pide el stop como en Risk", e.eng.isArmed() && e.eng.banner().contains("stop"));
            e.eng.chartClick(95.25f, false, false, false);
            eq("1ª entrada de 10 (100 $ de riesgo)", 10, e.sim.position);

            e.sim.price(99f);
            e.eng.arm(true, TradeEngine.Kind.LIMIT);
            e.eng.hover(98.25f);
            LineModel pr = e.lineWith("PR | -66 STP");
            check("vista previa: 66 contratos al SL común" + e.dump(), pr != null && pr.price == 95.25f && pr.text.contains("en contra"));
            eq("riesgo de la vista previa", "-396,00 $", pr == null ? null : pr.money);
            e.eng.chartClick(98.25f, false, false, false);
            SimBroker.SimOrder lim = e.sim.working(Enums$OrderType.LIMIT, true);
            check("límite de 66 en 98,25 sin pedir stop", lim != null && lim.qty == 66 && lim.limit == 98.25f && !e.eng.isArmed());
            check("lo explica: " + e.status(), e.status().contains("Promediado en contra") && e.status().contains("396,00"));
            LineModel g = e.lineWith("-76 STP");
            check("su stop se agrupa con el de la posición" + e.dump(), g != null && g.price == 95.25f && g.text.contains("Subm/Int"));
            eq("SL total", "-496,00 $", g == null ? null : g.money);

            int before = e.sim.orders.size();
            e.eng.market(true);
            check("sin margen: no envía", e.sim.orders.size() == before && e.status().contains("Promediado NO enviado"));

            e.sim.price(98f);                        // se ejecuta la límite
            eq("posición de 76", 76, e.sim.position);
            LineModel live = e.lineWith("Subm | -76 STP");
            check("los dos stops vivos agrupados", live != null && live.price == 95.25f);
        });

        test("Promediado a favor: % de lo que aseguran los SL", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.qtyMode = Config.QtyMode.AVG; c.oco = true; c.slOn = true; c.slUnit = Config.Unit.MONEY; c.slMoney = 100;
            c.avgTotal = 500; c.avgPct = 50; c.tpOn = false;
            e.eng.market(true);
            e.eng.chartClick(95.25f, false, false, false);
            e.sim.price(110f);
            e.eng.moveLine(e.lineWith("Subm | -10 STP").id, 100.25f);       // justo en BE
            e.eng.market(true);
            check("en BE no hay nada asegurado: " + e.status(), e.status().contains("justo en BE") && e.sim.position == 10);

            e.eng.moveLine(e.lineWith("Subm | -10 STP").id, 105.25f);       // asegura +100 $
            e.eng.market(true);                                              // ask 110,25: 10 $ por contrato hasta 105,25
            eq("5 contratos más (50 % de 100 $)", 15, e.sim.position);
            check("lo explica: " + e.status(), e.status().contains("a favor") && e.status().contains("50,00"));
            LineModel g = e.lineWith("Subm | -15 STP");
            check("stop común agrupado" + e.dump(), g != null && g.price == 105.25f);
            eq("el SL común sigue asegurando 50 $", "50,00 $", g == null ? null : g.money);
        });

        test("Promediado con parte de la posición sin SL: no envía", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.qty = 1;
            e.eng.market(true);                                              // sin OCO: posición sin stop
            c.qtyMode = Config.QtyMode.AVG; c.oco = true; c.slOn = true; c.slUnit = Config.Unit.MONEY; c.slMoney = 100; c.tpOn = false;
            e.eng.market(true);
            check("no envía y lo explica: " + e.status(), e.sim.position == 1 && e.status().contains("Promediado NO enviado") && e.status().contains("SL"));
        });

        test("SL nuevo soltado sobre otro: se agrupa al momento (botón SL y stop del modo Risk)", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.qty = 2; c.oco = true; c.slOn = true; c.slTicks = 20; c.tpOn = false;
            e.eng.arm(false, TradeEngine.Kind.LIMIT); e.eng.chartClick(104f, false, false, false);   // stop interno en 109
            c.oco = false;
            e.eng.arm(false, TradeEngine.Kind.LIMIT); e.eng.chartClick(106f, false, false, false);   // sin SL
            String ent = null;
            for (LineModel m : e.eng.lines()) if (m.kind == LineModel.Kind.ENTRY && m.price == 106f) ent = m.id;
            check("sin imán si no se coloca nada", e.eng.snapKey() == null);
            e.eng.pickBegin(ent, true);
            LineModel s109 = e.lineWith("Int | +2 STP");
            eq("imán: el SL que se coloca busca los stops de las ventas", s109.mergeKey, e.eng.snapKey());
            e.eng.pickDrop(109f);
            LineModel g = e.lineWith("Int | +4 STP");
            check("agrupado sin tener que moverlo" + e.dump(), g != null && g.price == 109f && g.buttons.stream().anyMatch(b -> "#2".equals(b.text)));
            check("lo indica: " + e.status(), e.status().contains("agrupado con 1 stop"));
            check("imán apagado al terminar", e.eng.snapKey() == null);

            Env r = new Env(100f);
            Config k = r.eng.cfg;
            k.qtyMode = Config.QtyMode.RISK; k.oco = true; k.slOn = true; k.slUnit = Config.Unit.MONEY; k.slMoney = 100; k.tpOn = false;
            r.eng.market(true); r.eng.chartClick(95.25f, false, false, false);        // 10 contratos, stop 95,25
            r.eng.market(true);
            LineModel st = r.lineWith("Subm | -10 STP");
            eq("modo Risk: imán al marcar el stop", st == null ? null : st.mergeKey, r.eng.snapKey());
            r.eng.chartClick(95.25f, false, false, false);
            LineModel rg = r.lineWith("Subm | -20 STP");
            check("modo Risk: el stop marcado encima se agrupa" + r.dump(), rg != null && rg.price == 95.25f);
            check("modo Risk: lo indica: " + r.status(), r.status().contains("agrupado con 1 stop"));
        });

        test("separar del grupo arrastrando #N: sale la última salida que se unió (pila)", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.qty = 1; c.oco = true; c.slOn = true; c.slTicks = 20; c.tpOn = false;
            for (float p : new float[]{104f, 105f, 106f}) { e.eng.arm(false, TradeEngine.Kind.LIMIT); e.eng.chartClick(p, false, false, false); }
            String idA = null, idB = null, idC = null;      // stops de 105 (110), 104 (109) y 106 (111)
            for (LineModel m : e.eng.lines()) if (m.kind == LineModel.Kind.STOP) {
                if (m.price == 110f) idA = m.id; else if (m.price == 109f) idB = m.id; else if (m.price == 111f) idC = m.id;
            }
            e.eng.moveLine(idB, 110f);                        // B encima de A
            e.eng.moveLine(idC, 110f);                        // C encima de los dos
            LineModel g = e.lineWith("Int | +3 STP");
            check("tres stops agrupados" + e.dump(), g != null && g.price == 110f && g.buttons.stream().anyMatch(b -> "#3".equals(b.text)));

            e.eng.splitBegin(g.id);
            e.eng.splitMove(112f);
            check("mientras se arrastra: el grupo sin ella y su vista previa" + e.dump(),
                e.lineWith("Int | +2 STP") != null && e.lineWith("Int | +2 STP").price == 110f
                && e.line("PX") != null && e.line("PX").price == 112f && e.line("PX").text.contains("+1 STP"));
            e.eng.splitDrop(112f);
            check("sale la última que entró (C)" + e.dump(), e.line(idC) != null && e.line(idC).price == 112f && e.line("PX") == null);
            check("las otras siguen juntas", e.lineWith("Int | +2 STP") != null && e.lineWith("Int | +2 STP").price == 110f);
            check("lo indica: " + e.status(), e.status().contains("separado del grupo"));

            g = e.lineWith("Int | +2 STP");
            e.eng.splitBegin(g.id);
            e.eng.splitDrop(108f);
            check("después sale B" + e.dump(), e.line(idB) != null && e.line(idB).price == 108f);
            check("A queda suelta, sin #", e.line(idA) != null && e.line(idA).price == 110f && e.line(idA).buttons.stream().noneMatch(b -> b.text.startsWith("#")));

            e.eng.moveLine(idB, 110f);                        // B vuelve encima de A
            g = e.lineWith("Int | +2 STP");
            e.eng.splitBegin(g.id);
            e.eng.splitDrop(103f);                            // por debajo de la venta de 104: no vale para un stop
            check("precio inválido: no se separa: " + e.status(), e.lineWith("Int | +2 STP") != null && e.status().contains("NO separado"));
            e.eng.splitBegin(g.id);
            e.eng.splitDrop(Float.NaN);                       // clic sin arrastrar
            check("sin arrastre no cambia nada", e.lineWith("Int | +2 STP") != null && e.line("PX") == null);
            e.eng.splitBegin(g.id);
            e.eng.splitDrop(111f);
            check("sale B (la última en volver)", e.line(idB) != null && e.line(idB).price == 111f);
            e.eng.moveLine(idB, 112f);                        // se suelta sobre C: grupo nuevo
            check("separada y soltada sobre otro: se agrupa con él" + e.dump(), e.lineWith("Int | +2 STP") != null && e.lineWith("Int | +2 STP").price == 112f);
        });

        test("número de cada entrada: el menor libre desde 1, compartido con su SL y su TP", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.qty = 1; c.oco = true; c.slOn = true; c.tpOn = true; c.slTicks = 20; c.tpTicks = 20;
            for (float p : new float[]{104f, 105f, 106f}) { e.eng.arm(false, TradeEngine.Kind.LIMIT); e.eng.chartClick(p, false, false, false); }
            java.util.function.BiFunction<LineModel.Kind, Float, LineModel> at = (k, p) -> {
                for (LineModel m : e.eng.lines()) if (m.kind == k && m.price == p) return m;
                return null;
            };
            eq("1ª entrada", "1", at.apply(LineModel.Kind.ENTRY, 104f).badge);
            eq("2ª entrada", "2", at.apply(LineModel.Kind.ENTRY, 105f).badge);
            eq("3ª entrada", "3", at.apply(LineModel.Kind.ENTRY, 106f).badge);
            eq("su SL lleva su número", "2", at.apply(LineModel.Kind.STOP, 110f).badge);
            eq("su TP lleva su número", "2", at.apply(LineModel.Kind.TARGET, 100f).badge);

            e.eng.lineButton(at.apply(LineModel.Kind.ENTRY, 105f).id, "X");   // cancela la 2
            check("la 2 desaparece" + e.dump(), at.apply(LineModel.Kind.ENTRY, 105f) == null && at.apply(LineModel.Kind.STOP, 110f) == null);
            e.eng.arm(false, TradeEngine.Kind.LIMIT); e.eng.chartClick(107f, false, false, false);
            eq("la siguiente reutiliza el 2", "2", at.apply(LineModel.Kind.ENTRY, 107f).badge);
            e.eng.arm(false, TradeEngine.Kind.LIMIT); e.eng.chartClick(108f, false, false, false);
            eq("y luego el 4", "4", at.apply(LineModel.Kind.ENTRY, 108f).badge);

            e.eng.moveLine(at.apply(LineModel.Kind.STOP, 109f).id, 111f);     // SL de la 1 sobre el de la 3
            LineModel g = e.lineWith("Int | +2 STP");
            eq("un grupo muestra los números de sus entradas", "1·3", g == null ? null : g.badge);

            e.sim.price(104f);                                                // se ejecuta la 1
            LineModel s1 = null;
            for (LineModel m : e.eng.lines()) if (m.kind == LineModel.Kind.STOP && m.text.startsWith("Subm/Int")) s1 = m;
            eq("tras ejecutarse, el SL sigue con su número (y el de la 3)" + e.dump(), "1·3", s1 == null ? null : s1.badge);
            check("la posición no lleva número", e.line("POS") != null && e.line("POS").badge == null);
        });

        test("estrategia de salida: 3 pasos con el mismo stop (una sola orden), objetivos parciales y break even", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            Config.Strategy st = new Config.Strategy("3 pasos", false, false, 0, false, true, 12, 0,
                List.of(step(34, 20, 8), step(33, 20, 12), step(33, 20, 20)));
            eq("se guarda y se lee igual", st.encode(), Config.Strategy.decode(st.encode()).encode());
            eq("reparto de 3", "[1, 1, 1]", java.util.Arrays.toString(st.qtys(3)));
            eq("reparto de 10 (el último se lleva el resto)", "[3, 3, 4]", java.util.Arrays.toString(st.qtys(10)));
            eq("reparto de 1", "[0, 0, 1]", java.util.Arrays.toString(st.qtys(1)));
            c.oco = true; c.putStrategy(null, st); c.qty = 3;
            eq("queda elegida", "3 pasos", c.strategy);
            e.eng.market(true);                                   // compra 3 a 100,25
            eq("posición", 3, e.sim.position);
            SimBroker.SimOrder stop = e.sim.working(Enums$OrderType.STOP, false);
            int stops = 0, lims = 0;
            for (SimBroker.SimOrder o : e.sim.orders) if (o.isActive()) { if (o.type == Enums$OrderType.STOP) stops++; else if (o.type == Enums$OrderType.LIMIT) lims++; }
            check("los 3 pasos comparten un solo stop de 3" + e.dump(), stops == 1 && stop.qty == 3 && stop.stop == 95.25f);
            eq("tres objetivos vivos", 3, lims);
            check("TP1 1 @ 102,25" + e.dump(), e.lineWith("-1 LMT TP1") != null && e.lineWith("-1 LMT TP1").price == 102.25f);
            check("TP2 1 @ 103,25", e.lineWith("-1 LMT TP2") != null && e.lineWith("-1 LMT TP2").price == 103.25f);
            check("TP3 1 @ 105,25", e.lineWith("-1 LMT TP3") != null && e.lineWith("-1 LMT TP3").price == 105.25f);
            check("con un solo stop, sin número", e.lineWith("-3 STP") != null && !e.lineWith("-3 STP").text.contains("SL1"));
            check("los objetivos llevan el número de la entrada", "1".equals(e.lineWith("TP3").badge));
            check("los objetivos de una estrategia no se agrupan", e.lineWith("TP2").mergeKey == null);

            e.sim.price(102.25f);                                 // TP1
            eq("tras TP1 quedan 2", 2, e.sim.position);
            check("el stop baja a 2 y sigue en su sitio", stop.qty == 2 && stop.isActive() && stop.stop == 95.25f);
            check("TP1 desaparece", e.lineWith("TP1") == null && e.lineWith("TP2") != null);

            e.sim.price(103.25f);                                 // TP2, y 12 ticks a favor: break even de la estrategia
            eq("tras TP2 queda 1", 1, e.sim.position);
            check("el stop baja a 1 y pasa a BE (100,25): " + e.status(), stop.qty == 1 && stop.stop == 100.25f);

            e.sim.price(99f);                                     // salta el stop en BE
            eq("posición cerrada", 0, e.sim.position);
            check("TP3 cancelado", e.sim.working(Enums$OrderType.LIMIT, false) == null && e.lineWith("TP3") == null);
            eq("sin órdenes vivas", 0, e.sim.activeCount());

            c.strategy = "";                                      // vuelta al modo SL/TP
            c.slOn = true; c.slTicks = 20; c.tpOn = false; c.qty = 2;
            e.eng.market(true);
            check("modo SL/TP intacto", e.sim.position == 2 && e.sim.working(Enums$OrderType.STOP, false).qty == 2);
        });

        test("estrategia de salida: stops distintos por paso, un paso sin stop, vista previa, mover / cancelar y cambiar la cantidad", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.oco = true; c.qty = 4;
            c.putStrategy(null, strat("3 pasos", step(50, 10, 8), step(25, 20, 12), step(25, 0, 20)));
            java.util.function.BiFunction<Enums$OrderType, Float, SimBroker.SimOrder> ord = (t, px) -> {
                for (SimBroker.SimOrder o : e.sim.orders) {
                    if (!o.isActive() || o.type != t) continue;
                    float v = t == Enums$OrderType.LIMIT ? o.limit : o.stop;
                    if (v == px.floatValue()) return o;
                }
                return null;
            };
            e.eng.arm(true, TradeEngine.Kind.LIMIT);
            e.eng.hover(98f);
            check("vista previa de los 2 stops" + e.dump(), e.line("PS") != null && e.line("PS").price == 95.5f && e.line("PS").text.contains("-2 STP SL1")
                && e.line("PS2") != null && e.line("PS2").price == 93f && e.line("PS2").text.contains("-1 STP SL2"));
            check("vista previa de los 3 objetivos", e.line("PT") != null && e.line("PT").price == 100f && e.line("PT").text.contains("-2 LMT TP1")
                && e.line("PT2") != null && e.line("PT2").price == 101f && e.line("PT3") != null && e.line("PT3").price == 103f);
            e.eng.chartClick(98f, false, false, false);
            check("internos: SL1 de 2 y SL2 de 1" + e.dump(), e.lineWith("Int | -2 STP SL1") != null && e.lineWith("Int | -2 STP SL1").price == 95.5f
                && e.lineWith("Int | -1 STP SL2") != null && e.lineWith("Int | -1 STP SL2").price == 93f);
            check("internos: TP1 de 2, TP2 y TP3 de 1", e.lineWith("Int | -2 LMT TP1").price == 100f && e.lineWith("Int | -1 LMT TP2").price == 101f && e.lineWith("Int | -1 LMT TP3").price == 103f);
            check("cada salida tiene su línea", e.lineWith("SL2").id.contains("_") && !e.lineWith("SL2").id.equals(e.lineWith("SL1").id)
                && e.lineWith("TP2").id.contains("_") && !e.lineWith("TP2").id.equals(e.lineWith("TP1").id));
            e.eng.moveLine(e.lineWith("TP2").id, 101.5f);
            check("se mueve solo el TP2", e.lineWith("TP2").price == 101.5f && e.lineWith("TP1").price == 100f);
            e.eng.moveLine(e.lineWith("SL2").id, 92.5f);
            check("se mueve solo el SL2", e.lineWith("SL2").price == 92.5f && e.lineWith("SL1").price == 95.5f);
            e.eng.lineButton(e.lineWith("TP3").id, "X");
            check("X quita solo el TP3", e.lineWith("TP3") == null && e.lineWith("TP1") != null);
            String ent = null;
            for (LineModel m : e.eng.lines()) if (m.kind == LineModel.Kind.ENTRY) ent = m.id;
            e.eng.editQty(ent, 8);                                // el doble: 4 / 2 / 2
            check("nueva cantidad repartida por pasos; el TP3 cancelado no vuelve" + e.dump(),
                e.lineWith("Int | -4 STP SL1") != null && e.lineWith("Int | -2 STP SL2") != null
                && e.lineWith("Int | -4 LMT TP1") != null && e.lineWith("Int | -2 LMT TP2") != null && e.lineWith("TP3") == null);

            e.sim.price(97.75f);                                  // ask 98: se ejecuta la compra de 8
            eq("posición 8", 8, e.sim.position);
            SimBroker.SimOrder s1 = ord.apply(Enums$OrderType.STOP, 95.5f), s2 = ord.apply(Enums$OrderType.STOP, 92.5f);
            check("SL1 de 4 y SL2 de 2 en el bróker (el paso 3 no tiene stop)" + e.dump(), s1 != null && s1.qty == 4 && s2 != null && s2.qty == 2);
            SimBroker.SimOrder t1 = ord.apply(Enums$OrderType.LIMIT, 100f), t2 = ord.apply(Enums$OrderType.LIMIT, 101.5f);
            check("TP1 de 4 y TP2 de 2", t1 != null && t1.qty == 4 && t2 != null && t2.qty == 2);

            e.sim.price(100f);                                    // TP1: cierra el paso 1 entero
            eq("tras TP1 quedan 4", 4, e.sim.position);
            check("el stop del paso 1 se cancela; el del paso 2 no cambia" + e.dump(), !s1.isActive() && s2.isActive() && s2.qty == 2 && e.lineWith("SL1") == null);
            e.sim.price(101.5f);                                  // TP2: cierra el paso 2
            eq("tras TP2 quedan 2 (el paso 3, sin salidas)", 2, e.sim.position);
            eq("sin órdenes vivas", 0, e.sim.activeCount());

            // un stop salta antes: se cancela el objetivo de SU paso y sigue el otro
            Env f = new Env(100f);
            Config d = f.eng.cfg;
            d.oco = true; d.qty = 3;
            d.putStrategy(null, strat("2 pasos", step(67, 10, 8), step(33, 20, 20)));
            f.eng.market(true);                                   // compra 3 a 100,25: SL1 97,75 (2) · SL2 95,25 (1)
            f.sim.price(97.5f);                                   // salta el SL1
            eq("quedan 1", 1, f.sim.position);
            int lims = 0; SimBroker.SimOrder left = null;
            for (SimBroker.SimOrder o : f.sim.orders) if (o.isActive() && o.type == Enums$OrderType.LIMIT) { lims++; left = o; }
            check("solo queda el objetivo del paso 2 (1 @ 105,25) con su stop" + f.dump(), lims == 1 && left.qty == 1 && left.limit == 105.25f
                && f.sim.working(Enums$OrderType.STOP, false) != null && f.sim.working(Enums$OrderType.STOP, false).stop == 95.25f);
        });

        test("estrategia de salida en modo Server: todos los stops van al bróker con la entrada límite", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.oco = true; c.qty = 3; c.server = true;
            c.putStrategy(null, strat("2 stops", step(67, 10, 12), step(33, 20, 12)));
            e.eng.arm(true, TradeEngine.Kind.LIMIT);
            e.eng.chartClick(98f, false, false, false);
            int stops = 0, q = 0, lims = 0;
            for (SimBroker.SimOrder o : e.sim.orders) if (o.isActive() && !o.buy) { if (o.type == Enums$OrderType.STOP) { stops++; q += o.qty; } else lims++; }
            check("2 stops (2 + 1) en el bróker y ningún objetivo todavía: " + e.status(), stops == 2 && q == 3 && lims == 0 && e.status().contains("2 stops ya en el bróker"));
            e.sim.price(97.75f);
            stops = 0; q = 0; lims = 0;
            for (SimBroker.SimOrder o : e.sim.orders) if (o.isActive() && !o.buy) { if (o.type == Enums$OrderType.STOP) { stops++; q += o.qty; } else { lims++; } }
            check("al ejecutarse: los mismos stops y un solo objetivo de 3 (los dos pasos lo comparten)" + e.dump(), e.sim.position == 3 && stops == 2 && q == 3 && lims == 1);

            Env f = new Env(100f);
            Config d = f.eng.cfg;
            d.oco = true; d.qty = 3; d.server = true;
            d.putStrategy(null, strat("2 stops", step(67, 10, 12), step(33, 20, 12)));
            f.eng.arm(true, TradeEngine.Kind.LIMIT);
            f.eng.chartClick(98f, false, false, false);
            String ent = null;
            for (LineModel m : f.eng.lines()) if (m.kind == LineModel.Kind.ENTRY) ent = m.id;
            f.eng.lineButton(ent, "X");
            eq("cancelar la entrada retira sus stops adelantados", 0, f.sim.activeCount());
        });

        test("estrategia de salida con Riesgo: la cantidad sale del importe y de los stops", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.oco = true; c.qty = 1;
            // 40 ticks x 0,5 $ = 20 $ por contrato; 100 $ -> 5 contratos
            c.putStrategy(null, new Config.Strategy("riesgo 100", false, true, 100, false, false, 0, 0, List.of(step(100, 40, 24))));
            e.eng.arm(true, TradeEngine.Kind.LIMIT);
            e.eng.hover(98f);
            eq("la cantidad calculada se publica para el panel", 5, e.eng.strategyQty());
            check("vista previa con la cantidad calculada" + e.dump(), e.line("PS") != null && e.line("PS").text.contains("-5 STP") && e.line("PT").text.contains("-5 LMT"));
            e.eng.disarm();
            e.eng.market(true);
            eq("5 contratos", 5, e.sim.position);
            SimBroker.SimOrder stop = e.sim.working(Enums$OrderType.STOP, false), tp = e.sim.working(Enums$OrderType.LIMIT, false);
            check("stop de 5 a 40 ticks y objetivo de 5 a 24 ticks", stop.qty == 5 && stop.stop == 90.25f && tp.qty == 5 && tp.limit == 106.25f);

            Env f = new Env(100f);
            Config d = f.eng.cfg;
            d.oco = true;
            d.putStrategy(null, new Config.Strategy("poco", false, true, 10, false, false, 0, 0, List.of(step(100, 40, 24))));
            f.eng.market(true);
            check("riesgo insuficiente: no envía y lo explica: " + f.status(), f.sim.orders.isEmpty() && f.status().contains("no llega ni a 1 contrato"));
            eq("y el panel lo sabe", 0, f.eng.strategyQty());

            Env g = new Env(100f);
            Config k = g.eng.cfg;
            k.oco = true;
            k.putStrategy(null, new Config.Strategy("1 %", false, true, 1, true, false, 0, 0, List.of(step(100, 40, 0))));
            g.eng.market(true);                                   // 1 % de 50.000 = 500 $ -> 25 contratos
            eq("% de la cuenta", 25, g.sim.position);

            Env h = new Env(100f);
            Config m = h.eng.cfg;
            m.oco = true;
            // dos pasos: 50 % con stop a 40 ticks (20 $) y 50 % a 20 ticks (10 $); con 6 contratos se pierden 90 $, con 7 serían 110 $
            m.putStrategy(null, new Config.Strategy("2 pasos", false, true, 100, false, false, 0, 0, List.of(step(50, 40, 0), step(50, 20, 0))));
            h.eng.market(true);
            eq("redondeo hacia abajo con varios pasos", 6, h.sim.position);
        });

        test("estrategia de salida: stop Trail, objetivo MIT y offsets en porcentaje", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.oco = true; c.qty = 1;
            c.putStrategy(null, new Config.Strategy("trail", false, false, 0, false, false, 0, 0,
                List.of(new Config.Step(100, true, 20, Config.StopType.TRAIL, false, 0, Config.TargetType.LIMIT))));
            e.eng.market(true);                                   // compra a 100,25: stop 95,25
            SimBroker.SimOrder stop = e.sim.working(Enums$OrderType.STOP, false);
            check("stop tipo Trail" + e.dump(), stop != null && stop.stop == 95.25f && e.lineWith("TRL") != null);
            e.sim.price(101f);
            check("sigue al precio a 20 ticks", stop.stop == 96f);
            e.sim.price(100.5f);
            check("y no retrocede", stop.stop == 96f);

            Env f = new Env(100f);
            Config d = f.eng.cfg;
            d.oco = true; d.qty = 2;
            d.putStrategy(null, new Config.Strategy("mit", false, false, 0, false, false, 0, 0,
                List.of(new Config.Step(100, true, 20, Config.StopType.STOP, true, 8, Config.TargetType.MIT))));
            f.eng.market(true);                                   // compra 2 a 100,25
            check("el objetivo MIT no está en el bróker: es una línea interna" + f.dump(),
                f.sim.working(Enums$OrderType.LIMIT, false) == null && f.lineWith("Int | -2 MIT") != null && f.lineWith("Int | -2 MIT").price == 102.25f);
            f.sim.price(102f);
            eq("sin tocar el precio no hace nada", 2, f.sim.position);
            f.sim.price(102.25f);
            eq("al tocarlo sale a mercado", 0, f.sim.position);
            eq("y se cancela el stop", 0, f.sim.activeCount());

            Env g = new Env(100f);
            Config k = g.eng.cfg;
            k.oco = true; k.qty = 1;
            k.putStrategy(null, new Config.Strategy("pct", true, false, 0, false, false, 0, 0,
                List.of(new Config.Step(100, true, 1, Config.StopType.STOP, true, 2, Config.TargetType.LIMIT))));
            g.eng.market(true);                                   // compra a 100,25: 1 % = 1,0025 · 2 % = 2,005
            SimBroker.SimOrder s2 = g.sim.working(Enums$OrderType.STOP, false), t2 = g.sim.working(Enums$OrderType.LIMIT, false);
            check("offsets en % del precio de entrada" + g.dump(), s2 != null && s2.stop == 99.25f && t2 != null && t2.limit == 102.25f);
        });

        test("estrategia de salida elegida: no se marca el stop aunque el panel esté en Risk o Promediado", () -> {
            Env e = new Env(100f);
            Config k = e.eng.cfg;
            k.qtyMode = Config.QtyMode.RISK; k.oco = true; k.slOn = true; k.slUnit = Config.Unit.MONEY; k.slMoney = 100;
            k.putStrategy(null, strat("3 pasos", step(34, 20, 8), step(33, 20, 12), step(33, 20, 20)));
            k.qty = 3;
            check("el modo efectivo es Qty", k.mode() == Config.QtyMode.QTY && !k.sizing());
            e.eng.market(true);
            check("MKT se envía directo, sin pedir el stop", !e.eng.isArmed() && e.sim.position == 3);
            SimBroker.SimOrder stop = e.sim.working(Enums$OrderType.STOP, false);
            check("el stop es el de la estrategia" + e.dump(), stop != null && stop.qty == 3 && stop.stop == 95.25f);

            Env f = new Env(100f);
            Config d = f.eng.cfg;
            d.qtyMode = Config.QtyMode.RISK; d.oco = true; d.slOn = true; d.slUnit = Config.Unit.MONEY; d.slMoney = 100;
            d.putStrategy(null, strat("1 paso", step(100, 20, 8)));
            d.qty = 2;
            f.eng.arm(true, TradeEngine.Kind.LIMIT);
            f.eng.chartClick(98f, false, false, false);
            check("LMT: un clic y queda enviada con sus salidas (sin segundo clic para el stop)" + f.dump(),
                !f.eng.isArmed() && f.lineWith("Subm | +2 LMT") != null && f.lineWith("Int | -2 STP") != null && f.lineWith("Int | -2 STP").price == 93f);

            Env g = new Env(100f);
            Config m = g.eng.cfg;
            m.qtyMode = Config.QtyMode.AVG; m.oco = true; m.slOn = true; m.slUnit = Config.Unit.MONEY; m.slMoney = 100;
            m.putStrategy(null, strat("1 paso", step(100, 20, 8)));
            m.qty = 1;
            g.eng.market(true);
            g.eng.market(true);
            check("Promediado tampoco se aplica: dos entradas normales de 1", !g.eng.isArmed() && g.sim.position == 2);

            m.strategy = "";
            check("sin estrategia vuelve el modo del panel", m.mode() == Config.QtyMode.AVG);
        });

        test("errores en el gráfico: solo lo que NO se hizo, unos segundos y se pueden cerrar", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.qty = 1;
            e.eng.cancelAll();
            eq("un mensaje normal no es un error", "", e.eng.notice());
            e.eng.arm(true, TradeEngine.Kind.LIMIT);
            e.eng.chartClick(101f, false, false, false);          // compra límite por encima del ask
            check("orden NO colocada: " + e.eng.notice(), e.eng.notice().startsWith("NO colocada") && e.eng.noticeLeft() > 0);
            e.eng.dismissNotice();
            eq("clic para cerrarlo", "", e.eng.notice());
            e.eng.breakeven();
            check("BE sin posición también avisa: " + e.eng.notice(), e.eng.notice().contains("no hay posición"));
            e.eng.disarm();
            e.eng.arm(true, TradeEngine.Kind.LIMIT);
            e.eng.chartClick(98f, false, false, false);
            check("una orden enviada no tapa el error anterior con un mensaje normal", e.eng.notice().contains("no hay posición"));
            SimBroker.SimOrder o = e.sim.working(Enums$OrderType.LIMIT, true);
            e.sim.reject(o);
            check("rechazo del bróker: " + e.eng.notice(), e.eng.notice().startsWith("RECHAZADA"));
            T.en = true;
            try {
                e.eng.breakeven();
                check("en inglés también: " + e.eng.notice(), e.eng.notice().contains("no open position"));
            } finally { T.en = false; }
        });

        test("etiquetas: R y riesgo en la posición, importe y R en SL/TP (por defecto como DeepCharts)", () -> {
            Env e = new Env(100f);
            Config c = e.eng.cfg;
            c.qty = 2; c.oco = true; c.slOn = true; c.slTicks = 20; c.tpOn = true; c.tpTicks = 40;
            e.eng.market(true);                                   // compra 2 a 100,25: stop 95,25, objetivo 110,25
            e.sim.price(101.25f);
            e.eng.refresh();
            eq("por defecto: posición solo con su P/L", "4,00 $", e.line("POS").money);
            eq("por defecto: SL/TP con su importe en dinero", "40,00 $", e.lineWith("LMT").money);

            e.eng.optPosR = true; e.eng.optPosRisk = true; e.eng.refresh();
            eq("R sobre el riesgo inicial y riesgo en dinero", "4,00 $ | 0,2R | riesgo -20,00 $", e.line("POS").money);
            e.eng.optPosUnit = Config.Unit.TICKS; e.eng.refresh();
            eq("riesgo en ticks", "4,00 $ | 0,2R | riesgo -20 ticks", e.line("POS").money);
            e.eng.optPosUnit = Config.Unit.POINTS; e.eng.refresh();
            eq("riesgo en puntos", "4,00 $ | 0,2R | riesgo -5,00 pts", e.line("POS").money);
            e.eng.optPosUnit = Config.Unit.PCT_CUR; e.eng.refresh();
            eq("riesgo en % del saldo actual", "4,00 $ | 0,2R | riesgo -0,04 %", e.line("POS").money);
            e.eng.optPosRCurrent = true; e.eng.optPosUnit = Config.Unit.MONEY; e.eng.refresh();
            eq("R sobre el riesgo actual", "4,00 $ | 0,2R | riesgo -20,00 $", e.line("POS").money);

            e.eng.moveLine(e.lineWith("STP").id, 101f);           // stop con beneficio
            eq("stop en beneficio: riesgo con signo + y R actual sin sentido", "4,00 $ | —R | riesgo +3,00 $", e.line("POS").money);
            e.eng.optPosRCurrent = false; e.eng.refresh();
            eq("el R inicial no cambia al mover el stop", "4,00 $ | 0,2R | riesgo +3,00 $", e.line("POS").money);
            e.eng.moveLine(e.lineWith("STP").id, 95.25f);

            e.eng.optExitUnit = Config.Unit.TICKS; e.eng.optExitR = true; e.eng.refresh();
            eq("objetivo: distancia y R", "40 ticks | 2,0R", e.lineWith("LMT").money);
            eq("stop: distancia y R", "-20 ticks | -1,0R", e.lineWith("STP").money);
            e.eng.optExitAmt = false; e.eng.refresh();
            eq("solo R", "2,0R", e.lineWith("LMT").money);
            e.eng.optExitR = false; e.eng.refresh();
            check("nada", e.lineWith("LMT").money == null);

            e.eng.optExitAmt = true; e.eng.optExitR = true; e.eng.optExitUnit = Config.Unit.MONEY;
            e.eng.arm(true, TradeEngine.Kind.LIMIT);
            e.eng.hover(98f);
            eq("vista previa del objetivo con R", "40,00 $ | 2,0R", e.line("PT").money);
            eq("vista previa del stop con R", "-20,00 $ | -1,0R", e.line("PS").money);

            Env f = new Env(100f);
            f.eng.optPosR = true; f.eng.optPosRisk = true;
            f.eng.cfg.qty = 1;
            f.eng.market(true);                                   // sin stop
            check("sin stops no hay riesgo ni R", !f.line("POS").money.contains("R") && !f.line("POS").money.contains("riesgo"));
        });

        test("idioma inglés: mensajes, vistas previas, resúmenes y números", () -> {
            T.en = true;
            try {
                Env e = new Env(100f);
                Config c = e.eng.cfg;
                c.qty = 1; c.oco = true; c.slOn = true; c.slTicks = 20;
                e.eng.cancelAll();
                eq("mensaje traducido", "Pending orders cancelled", e.status());
                e.eng.arm(true, TradeEngine.Kind.LIMIT);
                eq("aviso del gráfico", "BUY LMT · click the entry price", e.eng.banner());
                e.eng.chartClick(101f, false, false, false);
                check("error traducido: " + e.status(), e.status().startsWith("NOT placed: buy limit 101") && e.status().contains("above the ask"));
                eq("dinero con punto decimal", "1234.50 $", TradeEngine.money(1234.5));
                Config.Strategy st = new Config.Strategy("x", false, true, 1, true, true, 10, 0, List.of(step(100, 40, 24)));
                eq("resumen de la estrategia", "1 step · stop 40 · target 24 ticks · risk 1 % of account · BE at 10 ticks", st.summary());
            } finally {
                T.en = false;
            }
            eq("de vuelta al español", "1234,50 $", TradeEngine.money(1234.5));
        });

        System.out.println();
        System.out.println(passed + " comprobaciones correctas, " + failed + " fallidas");
        if (failed > 0) System.exit(1);
    }

    /** Paso con stop y objetivo en ticks (0 = sin él), de tipo Stop y Limit. */
    private static Config.Step step(double pct, double stop, double target) {
        return new Config.Step(pct, stop > 0, stop, Config.StopType.STOP, target > 0, target, Config.TargetType.LIMIT);
    }

    /** Estrategia en ticks, sin riesgo ni break even. */
    private static Config.Strategy strat(String name, Config.Step... steps) {
        return new Config.Strategy(name, false, false, 0, false, false, 0, 0, List.of(steps));
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignore) { }
    }
}
