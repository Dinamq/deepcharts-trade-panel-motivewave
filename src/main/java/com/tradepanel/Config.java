package com.tradepanel;

import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Parámetros del panel (lo que el usuario toca en la ventana). Los escribe el hilo de JavaFX y los
 * lee el hilo de órdenes: por eso todos los campos son volatile y no hay estado compuesto.
 * Se recuerdan entre sesiones en las preferencias del usuario.
 */
final class Config {

    /** RISK: la cantidad sale del riesgo y del stop marcado. AVG ("Promediado"): además reparte un riesgo total entre varias entradas. */
    enum QtyMode { QTY, RISK, AVG }
    /**
     * POINTS = puntos de precio (1 punto = 1,00). RR solo vale para el TP: múltiplo de la distancia del SL.
     * PCT_INI / PCT_CUR = % del balance inicial / actual de la cuenta, convertido a dinero.
     */
    enum Unit { TICKS, POINTS, MONEY, RR, PCT_INI, PCT_CUR }

    enum StopType { STOP, TRAIL, MIT }
    enum TargetType { LIMIT, MIT }

    /** Un paso de una estrategia de salida: una parte de la cantidad con su stop y su objetivo (los dos opcionales). */
    static final class Step {
        final double pct;            // % de la cantidad de la entrada
        final boolean stopOn;
        final double stopOff;        // distancia del stop a la entrada (ticks o % del precio)
        final StopType stopType;
        final boolean targetOn;
        final double targetOff;
        final TargetType targetType;
        Step(double pct, boolean stopOn, double stopOff, StopType stopType, boolean targetOn, double targetOff, TargetType targetType) {
            this.pct = Math.max(0, Math.min(100, pct));
            this.stopOn = stopOn; this.stopOff = Math.max(0, stopOff); this.stopType = stopType == null ? StopType.STOP : stopType;
            this.targetOn = targetOn; this.targetOff = Math.max(0, targetOff); this.targetType = targetType == null ? TargetType.LIMIT : targetType;
        }
        boolean hasStop() { return stopOn && stopOff > 0; }
        boolean hasTarget() { return targetOn && targetOff > 0; }
    }

    /**
     * Estrategia de salida con nombre (misma estructura que las Exit Strategies de MotiveWave):
     * tipo de offset (ticks o % del precio), riesgo opcional que calcula la cantidad, break even
     * opcional y una tabla de pasos. Cada paso se lleva un % de la cantidad de la entrada.
     */
    static final class Strategy {
        final String name;
        final boolean percent;       // Offset Type: false = ticks, true = % del precio de entrada
        final boolean riskOn;        // la cantidad sale del riesgo y de los stops
        final double risk;
        final boolean riskPct;       // true = % del saldo de la cuenta; false = importe fijo
        final boolean beOn;
        final double beTrig, beOff;  // avance a favor para mover los stops a BE y offset sobre la entrada
        final List<Step> steps;

        Strategy(String name, boolean percent, boolean riskOn, double risk, boolean riskPct,
                 boolean beOn, double beTrig, double beOff, List<Step> steps) {
            this.name = clean(name); this.percent = percent;
            this.riskOn = riskOn; this.risk = Math.max(0, risk); this.riskPct = riskPct;
            this.beOn = beOn; this.beTrig = Math.max(0, beTrig); this.beOff = Math.max(0, beOff);
            this.steps = List.copyOf(steps);
        }

        static String clean(String n) { return n == null ? "" : n.replace('|', '/').replace('\n', ' ').trim(); }

        double pctSum() { double t = 0; for (Step st : steps) t += st.pct; return t; }
        boolean anyStop() { for (Step st : steps) if (st.hasStop()) return true; return false; }

        /**
         * Contratos de cada paso para una entrada de {@code q}: su % redondeado, sin pasar de lo que
         * queda. Si los pasos suman el 100 %, el último se lleva lo que quede.
         */
        int[] qtys(int q) {
            int n = steps.size();
            int[] out = new int[n];
            boolean full = pctSum() >= 99.999;
            int left = Math.max(0, q);
            for (int i = 0; i < n; i++) {
                int v = i == n - 1 && full ? left : (int) Math.min(left, Math.round(q * steps.get(i).pct / 100.0));
                out[i] = Math.max(0, v);
                left -= out[i];
            }
            return out;
        }

        /** "2 pasos · riesgo 1.500 $ · BE a 10 ticks". */
        String summary() {
            int n = steps.size();
            String unit = percent ? " %" : " ticks";
            StringBuilder sb = new StringBuilder().append(n).append(T.t(n == 1 ? " paso" : " pasos"));
            if (n == 1) {
                Step st = steps.get(0);
                if (st.hasStop()) sb.append(T.t(" · stop ")).append(num(st.stopOff));
                if (st.hasTarget()) sb.append(T.t(" · objetivo ")).append(num(st.targetOff));
                if (st.hasStop() || st.hasTarget()) sb.append(unit);
            }
            if (riskOn) sb.append(T.t(" · riesgo ")).append(num(risk)).append(riskPct ? T.t(" % de la cuenta") : " $");
            if (beOn) sb.append(T.t(" · BE a ")).append(num(beTrig)).append(unit);
            return sb.toString();
        }

        static String num(double v) {
            return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 100) / 100.0).replace('.', ',');
        }

        String encode() {
            StringBuilder sb = new StringBuilder(name).append('|').append(percent ? 'P' : 'T')
                .append('|').append(riskOn ? 1 : 0).append(',').append(risk).append(',').append(riskPct ? 1 : 0)
                .append('|').append(beOn ? 1 : 0).append(',').append(beTrig).append(',').append(beOff).append('|');
            for (int i = 0; i < steps.size(); i++) {
                Step st = steps.get(i);
                if (i > 0) sb.append(';');
                sb.append(st.pct).append(',').append(st.stopOn ? 1 : 0).append(',').append(st.stopOff).append(',').append(st.stopType.name())
                  .append(',').append(st.targetOn ? 1 : 0).append(',').append(st.targetOff).append(',').append(st.targetType.name());
            }
            return sb.toString();
        }

        static Strategy decode(String s) {
            try {
                String[] part = s.split("\\|", -1);
                String[] rk = part[2].split(","), be = part[3].split(",");
                List<Step> steps = new ArrayList<>();
                for (String row : part[4].split(";")) {
                    if (row.isEmpty()) continue;
                    String[] f = row.split(",");
                    steps.add(new Step(Double.parseDouble(f[0]), "1".equals(f[1]), Double.parseDouble(f[2]), StopType.valueOf(f[3]),
                        "1".equals(f[4]), Double.parseDouble(f[5]), TargetType.valueOf(f[6])));
                }
                String name = clean(part[0]);
                if (name.isEmpty() || steps.isEmpty()) return null;
                return new Strategy(name, "P".equals(part[1]), "1".equals(rk[0]), Double.parseDouble(rk[1]), "1".equals(rk[2]),
                    "1".equals(be[0]), Double.parseDouble(be[1]), Double.parseDouble(be[2]), steps);
            } catch (RuntimeException e) { return null; }
        }
    }

    // ---- cantidad ----
    volatile QtyMode qtyMode = QtyMode.QTY;
    volatile int qty = 1;

    // ---- Dif. en ticks (BID/ASK a distancia fija del mercado) ----
    volatile boolean tickDiffOn = false;
    volatile int tickDiff = 0;

    // ---- OCO Strategy (modo SL/TP) ----
    volatile boolean oco = false;
    volatile boolean slOn = true;
    volatile boolean tpOn = false;
    volatile Unit slUnit = Unit.TICKS;
    volatile Unit tpUnit = Unit.TICKS;
    volatile double slTicks = 40;
    volatile double slMoney = 100;
    volatile double tpTicks = 80;
    volatile double tpMoney = 200;
    volatile double slPoints = 10;
    volatile double tpPoints = 20;
    volatile double tpRR = 2;       // TP = distancia del SL x este número
    volatile double slPct = 1;      // % del balance (inicial o actual) que se arriesga
    volatile double tpPct = 2;      // % del balance que se busca ganar
    /** Balance inicial escrito a mano; 0 = automático (lo que da la cuenta en MotiveWave). */
    volatile double initBal = 0;

    // ---- estrategias de salida guardadas y la elegida ("" = modo SL/TP) ----
    volatile List<Strategy> strategies = List.of();
    volatile String strategy = "";

    // ---- Promediado ----
    volatile double avgTotal = 500; // riesgo total entre todas las entradas (promediar en contra)
    volatile double avgPct = 50;    // % del beneficio asegurado por los SL que puede arriesgar una entrada a favor
    /** Server: el stop de una entrada LÍMITE se envía al bróker junto con ella (sigue ahí con el equipo apagado). */
    volatile boolean server = false;

    // ---- BE automático y trailing ----
    volatile boolean beOn = false;
    volatile int beTrigger = 20;   // ticks a favor para mover el stop
    volatile int beOffset = 0;     // ticks más allá de la entrada
    volatile boolean trailOn = false;
    volatile int trailTrigger = 20; // ticks a favor para empezar a seguir
    volatile int trailDist = 20;    // distancia del stop al precio

    double slValue() { return slUnit == Unit.MONEY ? slMoney : slUnit == Unit.POINTS ? slPoints : isPct(slUnit) ? slPct : slTicks; }
    double tpValue() { return tpUnit == Unit.MONEY ? tpMoney : tpUnit == Unit.POINTS ? tpPoints : tpUnit == Unit.RR ? tpRR : isPct(tpUnit) ? tpPct : tpTicks; }

    static boolean isPct(Unit u) { return u == Unit.PCT_INI || u == Unit.PCT_CUR; }
    /** Unidades que expresan dinero (directamente o como % del balance). */
    static boolean isMoney(Unit u) { return u == Unit.MONEY || isPct(u); }
    /**
     * Modo de cantidad efectivo. Con una estrategia de salida elegida mandan sus stops y su Riesgo:
     * no se marca el stop en el gráfico, así que Risk y Promediado no se aplican.
     */
    QtyMode mode() { return multi() != null ? QtyMode.QTY : qtyMode; }
    boolean sizing() { return mode() != QtyMode.QTY; }

    /** La estrategia de salida elegida, o null en modo SL/TP. */
    Strategy multi() {
        String n = strategy;
        return n == null || n.isEmpty() ? null : find(n);
    }

    Strategy find(String name) {
        for (Strategy s : strategies) if (s.name.equals(name)) return s;
        return null;
    }

    /** Guarda una estrategia (sustituye a {@code replaces} si se está editando) y la deja elegida. */
    synchronized void putStrategy(String replaces, Strategy s) {
        List<Strategy> l = new ArrayList<>();
        boolean done = false;
        for (Strategy o : strategies) {
            if (o.name.equals(replaces)) { l.add(s); done = true; }
            else if (!o.name.equals(s.name)) l.add(o);
        }
        if (!done) l.add(s);
        strategies = List.copyOf(l);
        strategy = s.name;
    }

    synchronized void removeStrategy(String name) {
        List<Strategy> l = new ArrayList<>();
        for (Strategy o : strategies) if (!o.name.equals(name)) l.add(o);
        strategies = List.copyOf(l);
        if (name.equals(strategy)) strategy = "";
    }

    private static Unit unit(String s, boolean allowRR) {
        try { Unit u = Unit.valueOf(s); return u == Unit.RR && !allowRR ? Unit.TICKS : u; }
        catch (RuntimeException e) { return Unit.TICKS; }
    }

    /** Los modos Risk y Promediado dimensionan con el SL en dinero (Money o % del balance): lo exigen activo. */
    boolean riskReady() { return oco && slOn && isMoney(slUnit) && slValue() > 0; }

    // ------------------------------------------------------------------
    //  persistencia
    // ------------------------------------------------------------------

    private static Preferences node() { return Preferences.userRoot().node("com/tradepanel/panel"); }

    void load() {
        try {
            Preferences p = node();
            try { qtyMode = QtyMode.valueOf(p.get("qtyMode", "QTY")); } catch (RuntimeException e) { qtyMode = QtyMode.QTY; }
            qty = Math.max(1, p.getInt("qty", qty));
            tickDiffOn = p.getBoolean("tickDiffOn", tickDiffOn);
            tickDiff = Math.max(0, p.getInt("tickDiff", tickDiff));
            oco = p.getBoolean("oco", oco);
            slOn = p.getBoolean("slOn", slOn);
            tpOn = p.getBoolean("tpOn", tpOn);
            slUnit = unit(p.get("slUnit", "TICKS"), false);
            tpUnit = unit(p.get("tpUnit", "TICKS"), true);
            slTicks = p.getDouble("slTicks", slTicks);
            slMoney = p.getDouble("slMoney", slMoney);
            tpTicks = p.getDouble("tpTicks", tpTicks);
            tpMoney = p.getDouble("tpMoney", tpMoney);
            slPoints = p.getDouble("slPoints", slPoints);
            tpPoints = p.getDouble("tpPoints", tpPoints);
            tpRR = p.getDouble("tpRR", tpRR);
            slPct = p.getDouble("slPct", slPct);
            tpPct = p.getDouble("tpPct", tpPct);
            avgTotal = p.getDouble("avgTotal", avgTotal);
            avgPct = p.getDouble("avgPct", avgPct);
            server = p.getBoolean("server", server);
            beOn = p.getBoolean("beOn", beOn);
            beTrigger = p.getInt("beTrigger", beTrigger);
            beOffset = p.getInt("beOffset", beOffset);
            trailOn = p.getBoolean("trailOn", trailOn);
            trailTrigger = p.getInt("trailTrigger", trailTrigger);
            trailDist = p.getInt("trailDist", trailDist);
            List<Strategy> l = new ArrayList<>();
            int n = Math.max(0, Math.min(200, p.getInt("exitCount", 0)));
            for (int i = 0; i < n; i++) {
                Strategy s = Strategy.decode(p.get("exit" + i, ""));
                if (s != null) l.add(s);
            }
            strategies = List.copyOf(l);
            strategy = p.get("exitStrategy", "");
            if (find(strategy) == null) strategy = "";
            if (qtyMode != QtyMode.QTY && (!riskReady() || multi() != null)) qtyMode = QtyMode.QTY;
        } catch (Exception ignore) { /* sin preferencias: valores por defecto */ }
    }

    void save() {
        try {
            Preferences p = node();
            p.put("qtyMode", qtyMode.name());
            p.putInt("qty", qty);
            p.putBoolean("tickDiffOn", tickDiffOn);
            p.putInt("tickDiff", tickDiff);
            p.putBoolean("oco", oco);
            p.putBoolean("slOn", slOn);
            p.putBoolean("tpOn", tpOn);
            p.put("slUnit", slUnit.name());
            p.put("tpUnit", tpUnit.name());
            p.putDouble("slTicks", slTicks);
            p.putDouble("slMoney", slMoney);
            p.putDouble("tpTicks", tpTicks);
            p.putDouble("tpMoney", tpMoney);
            p.putDouble("slPoints", slPoints);
            p.putDouble("tpPoints", tpPoints);
            p.putDouble("tpRR", tpRR);
            p.putDouble("slPct", slPct);
            p.putDouble("tpPct", tpPct);
            p.putDouble("avgTotal", avgTotal);
            p.putDouble("avgPct", avgPct);
            p.putBoolean("server", server);
            p.putBoolean("beOn", beOn);
            p.putInt("beTrigger", beTrigger);
            p.putInt("beOffset", beOffset);
            p.putBoolean("trailOn", trailOn);
            p.putInt("trailTrigger", trailTrigger);
            p.putInt("trailDist", trailDist);
            List<Strategy> l = strategies;
            int old = p.getInt("exitCount", 0);
            p.putInt("exitCount", l.size());
            for (int i = 0; i < l.size(); i++) p.put("exit" + i, l.get(i).encode());
            for (int i = l.size(); i < old; i++) p.remove("exit" + i);
            p.put("exitStrategy", strategy);
        } catch (Exception ignore) { /* no crítico */ }
    }
}
