package com.tradepanel;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import com.motivewave.platform.sdk.common.Enums$OrderAction;
import com.motivewave.platform.sdk.common.Enums$OrderType;
import com.motivewave.platform.sdk.common.Enums$TIF;
import com.motivewave.platform.sdk.common.Instrument;
import com.motivewave.platform.sdk.order_mgmt.Order;
import com.motivewave.platform.sdk.order_mgmt.OrderContext;

/**
 * Bróker simulado para probar el motor sin MotiveWave: implementa lo que el motor usa de
 * OrderContext / Instrument / Order y devuelve los eventos (fill, cancel, modify, position closed)
 * igual que lo haría la plataforma.
 */
final class SimBroker {

    final double tick, pointValue;
    float last, bid, ask;
    int position;
    float avg;
    double realized;
    double cash = 50000;
    int nextId = 1;
    final List<SimOrder> orders = new ArrayList<>();
    final List<String> log = new ArrayList<>();
    TradeEngine engine;
    final OrderContext ctx;
    final Instrument instrument;

    SimBroker(double tick, double pointValue, float price) {
        this.tick = tick; this.pointValue = pointValue;
        quote(price);
        instrument = (Instrument) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Instrument.class}, instrumentHandler());
        ctx = (OrderContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{OrderContext.class}, contextHandler());
    }

    void quote(float price) { last = price; bid = price; ask = (float) (price + tick); }

    double round(double v) { return Math.round(v / tick) * tick; }

    // ------------------------------------------------------------------
    //  mercado
    // ------------------------------------------------------------------

    /** Mueve el precio: ejecuta las órdenes que toca y avisa al motor con un tick. */
    void price(float p) {
        quote(p);
        for (SimOrder o : new ArrayList<>(orders)) {
            if (!o.isActive()) continue;
            if (o.type == Enums$OrderType.LIMIT) {
                if (o.buy ? ask <= o.limit : bid >= o.limit) fill(o, o.qty - o.filled, o.limit);
            } else if (o.type == Enums$OrderType.STOP) {
                if (o.buy ? last >= o.stop : last <= o.stop) fill(o, o.qty - o.filled, o.buy ? ask : bid);
            }
        }
        engine.tick(p);
    }

    /** Ejecución (parcial o total) de una orden. */
    void fill(SimOrder o, int q, float px) {
        if (q <= 0 || !o.isActive()) return;
        o.avgFill = (o.avgFill * o.filled + px * q) / (o.filled + q);
        o.filled += q;
        if (o.filled >= o.qty) o.state = "FILLED";
        int before = position;
        int signed = o.buy ? q : -q;
        if (position == 0 || (position > 0) == (signed > 0)) {
            avg = (avg * Math.abs(position) + px * q) / (Math.abs(position) + q);
            position += signed;
        } else {
            int closing = Math.min(Math.abs(position), q);
            realized += (px - avg) * (position > 0 ? closing : -closing) * pointValue;
            position += signed;
            if (position == 0) avg = 0;
            else if ((position > 0) != (before > 0)) avg = px;
        }
        log.add("FILL " + o);
        engine.orderFilled(o);
        if (before != 0 && position == 0) engine.positionClosed();
    }

    SimOrder working(Enums$OrderType type, boolean buy) {
        for (SimOrder o : orders) if (o.isActive() && o.type == type && o.buy == buy) return o;
        return null;
    }

    int activeCount() { int n = 0; for (SimOrder o : orders) if (o.isActive()) n++; return n; }

    // ------------------------------------------------------------------
    //  OrderContext
    // ------------------------------------------------------------------

    private SimOrder create(Enums$OrderType type, Object[] a) {
        // firmas usadas por el motor: (action, qty) | (action, tif, qty, price)
        Enums$OrderAction act = (Enums$OrderAction) a[0];
        SimOrder o = new SimOrder();
        o.type = type; o.buy = act == Enums$OrderAction.BUY;
        if (type == Enums$OrderType.MARKET) o.qty = (Integer) a[1];
        else {
            o.tif = (Enums$TIF) a[1];
            o.qty = (Integer) a[2];
            float px = (Float) a[3];
            if (type == Enums$OrderType.LIMIT) o.limit = px; else o.stop = px;
        }
        return o;
    }

    private void submit(SimOrder o) {
        if ("NEW".equals(o.state)) {
            o.id = "S" + (nextId++);
            o.state = "WORKING";
            orders.add(o);
            log.add("SUBMIT " + o);
            if (o.type == Enums$OrderType.MARKET) fill(o, o.qty, o.buy ? ask : bid);
            return;
        }
        if (!o.isActive()) return;
        boolean changed = false;
        if (o.adjLimit != null) { o.limit = o.adjLimit; o.adjLimit = null; changed = true; }
        if (o.adjStop != null) { o.stop = o.adjStop; o.adjStop = null; changed = true; }
        if (o.adjQty > 0) { o.qty = o.adjQty; o.adjQty = 0; changed = true; }
        if (changed) { log.add("MODIFY " + o); engine.orderModified(o); }
    }

    /** El bróker rechaza una orden activa (p. ej. por margen). */
    void reject(SimOrder o) {
        if (!o.isActive()) return;
        o.state = "REJECTED";
        log.add("REJECT " + o);
        engine.orderRejected(o);
    }

    private void cancel(SimOrder o) {
        if (!o.isActive()) return;
        o.state = "CANCELLED";
        log.add("CANCEL " + o);
        engine.orderCancelled(o);
    }

    private InvocationHandler contextHandler() {
        return (proxy, m, a) -> {
            switch (m.getName()) {
                case "getInstrument": return instrument;
                case "getPosition": return position;
                case "getAvgEntryPrice": return avg;
                case "getUnrealizedPnL": return (double) ((last - avg) * position * pointValue);
                case "getTotalRealizedPnL": case "getRealizedPnL": return realized;
                case "getCashBalance": return cash;
                case "calcPnl": return (double) (((Float) a[1]) - ((Float) a[0])) * ((Number) a[2]).doubleValue() * pointValue;
                case "getActiveOrders": { List<Order> l = new ArrayList<>(); for (SimOrder o : orders) if (o.isActive()) l.add(o); return l; }
                case "createMarketOrder": return create(Enums$OrderType.MARKET, a);
                case "createLimitOrder": return create(Enums$OrderType.LIMIT, a);
                case "createStopOrder": return create(Enums$OrderType.STOP, a);
                case "submitOrders": for (Object o : flat(a)) submit((SimOrder) o); return null;
                case "cancelOrders":
                    if (a == null || a.length == 0) { for (SimOrder o : new ArrayList<>(orders)) cancel(o); }
                    else for (Object o : flat(a)) cancel((SimOrder) o);
                    return null;
                case "closeAtMarket": {
                    if (position == 0) return null;
                    SimOrder o = new SimOrder();
                    o.type = Enums$OrderType.MARKET; o.buy = position < 0; o.qty = Math.abs(position);
                    submit(o);
                    return null;
                }
                case "getCurrentTime": return System.currentTimeMillis();
                case "toString": return "SimContext";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == a[0];
                default: throw new UnsupportedOperationException("OrderContext." + m.getName());
            }
        };
    }

    private static List<Object> flat(Object[] a) {
        List<Object> out = new ArrayList<>();
        if (a == null) return out;
        for (Object x : a) {
            if (x instanceof Object[]) for (Object y : (Object[]) x) out.add(y);
            else if (x instanceof Collection) out.addAll((Collection<?>) x);
            else out.add(x);
        }
        return out;
    }

    private InvocationHandler instrumentHandler() {
        return (proxy, m, a) -> {
            switch (m.getName()) {
                case "getSymbol": return "MNQ-TEST";
                case "getTickSize": return tick;
                case "getPointValue": return pointValue;
                case "getLastPrice": return last;
                case "getBidPrice": return bid;
                case "getAskPrice": return ask;
                case "getBroker": return null;
                case "round":
                    if (a[0] instanceof Float) return (float) round((Float) a[0]);
                    return round((Double) a[0]);
                case "format": return String.format(Locale.forLanguageTag("es-ES"), "%.2f", ((Number) a[0]).doubleValue());
                case "toString": return "SimInstrument";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == a[0];
                default: throw new UnsupportedOperationException("Instrument." + m.getName());
            }
        };
    }

    // ------------------------------------------------------------------
    //  Order
    // ------------------------------------------------------------------

    final class SimOrder implements Order {
        String id; String state = "NEW";
        Enums$OrderType type; boolean buy; int qty; int filled; float avgFill;
        Float limit, stop, adjLimit, adjStop; int adjQty; Enums$TIF tif = Enums$TIF.DAY;

        @Override public String toString() {
            return (buy ? "BUY " : "SELL ") + qty + " " + type + (limit != null ? " lmt " + limit : "") + (stop != null ? " stp " + stop : "") + " [" + state + " " + filled + "]";
        }
        @Override public String getAccountId() { return "SIM1"; }
        @Override public String getOrderId() { return id; }
        @Override public String getExchangeOrderId() { return id; }
        @Override public Instrument getInstrument() { return instrument; }
        @Override public Enums$OrderType getType() { return type; }
        @Override public Enums$OrderAction getAction() { return buy ? Enums$OrderAction.BUY : Enums$OrderAction.SELL; }
        @Override public boolean isAdjusted() { return adjLimit != null || adjStop != null || adjQty > 0; }
        @Override public boolean isSell() { return !buy; }
        @Override public boolean isBuy() { return buy; }
        @Override public int getQuantity() { return qty; }
        @Override public float getQuantityAsFloat() { return qty; }
        @Override public float getAvgFillPrice() { return avgFill; }
        @Override public float getLastFillPrice() { return avgFill; }
        @Override public int getFilled() { return filled; }
        @Override public float getFilledAsFloat() { return filled; }
        @Override public long getLastFillTime() { return 0; }
        @Override public Object getReferenceID() { return null; }
        @Override public Float getLimitPrice() { return limit; }
        @Override public Float getStopPrice() { return stop; }
        @Override public Enums$TIF getTIF() { return tif; }
        @Override public Float getAdjLimitPrice() { return adjLimit; }
        @Override public void setAdjLimitPrice(Float p) { adjLimit = p; }
        @Override public Float getAdjStopPrice() { return adjStop; }
        @Override public void setAdjStopPrice(Float p) { adjStop = p; }
        @Override public int getAdjQuantity() { return adjQty; }
        @Override public void setAdjQuantity(int q) { adjQty = q; }
        @Override public float getAdjQuantityAsFloat() { return adjQty; }
        @Override public void setAdjQuantity(float q) { adjQty = (int) q; }
        @Override public Enums$TIF getAdjTIF() { return tif; }
        @Override public void setAdjTIF(Enums$TIF t) { tif = t; }
        @Override public boolean isCancelled() { return "CANCELLED".equals(state); }
        @Override public boolean isFilled() { return "FILLED".equals(state); }
        @Override public boolean exists() { return !"NEW".equals(state); }
        @Override public boolean isActive() { return "WORKING".equals(state); }
    }
}
