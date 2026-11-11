package com.tradepanel;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 * Idioma de los textos visibles: español (el del código) o inglés. Cada texto se traduce al usarse,
 * así que el gráfico y los mensajes cambian al momento; el panel se reconstruye al cambiar el idioma.
 * El idioma se elige en los ajustes de la estrategia y se recuerda en las preferencias del usuario.
 */
final class T {

    private T() { }

    static volatile boolean en;

    private static final Locale ES = Locale.forLanguageTag("es-ES");
    private static final Map<String, String> M = new HashMap<>();

    /** Texto en el idioma elegido (si no hay traducción, el original). */
    static String t(String es) {
        if (!en || es == null) return es;
        String v = M.get(es);
        return v == null ? es : v;
    }

    /** Formato de números del idioma elegido (coma decimal en español, punto en inglés). */
    static Locale loc() { return en ? Locale.US : ES; }

    private static Preferences node() { return Preferences.userRoot().node("com/tradepanel/panel"); }

    /** Último idioma elegido (para los textos que se crean antes de leer los ajustes). */
    static void load() {
        try { en = "en".equals(node().get("lang", "en")); } catch (Exception ignore) { en = true; }   // por defecto, inglés
    }

    /** Cambia el idioma; devuelve true si ha cambiado. */
    static boolean set(boolean english) {
        boolean changed = en != english;
        en = english;
        if (changed) { try { node().put("lang", english ? "en" : "es"); } catch (Exception ignore) { } }
        return changed;
    }

    static {
        M.put(" paso", " step");
        M.put("riesgo ", "risk ");
        M.put("Línea de posición", "Position line");
        M.put("Mostrar R", "Show R");
        M.put("R sobre", "R based on");
        M.put("Riesgo inicial", "Initial risk");
        M.put("Riesgo actual", "Current risk");
        M.put("Mostrar riesgo", "Show risk");
        M.put("Riesgo en", "Risk in");
        M.put("Dinero", "Money");
        M.put("% saldo inicial", "% initial balance");
        M.put("% saldo actual", "% current balance");
        M.put("Etiquetas de SL/TP", "SL/TP labels");
        M.put("Mostrar importe", "Show amount");
        M.put("Importe en", "Amount in");
        M.put(" % de la cuenta", " % of account");
        M.put(" pasos", " steps");
        M.put(" · objetivo ", " · target ");
        M.put(" · riesgo ", " · risk ");
        M.put("% de la cuenta", "% of account");
        M.put(" · BE a ", " · BE at ");
        M.put("Nueva cantidad:", "New quantity:");
        M.put("Cant. abierta", "Open qty");
        M.put("P/L abierto", "Open P/L");
        M.put("P/L diario", "Daily P/L");
        M.put("Promediado", "Averaging");
        M.put("Modo", "Mode");
        M.put("Puntos", "Points");
        M.put("% Inicial", "% Initial");
        M.put("% Actual", "% Current");
        M.put("Ocultar (se reabre con el botón PANEL del gráfico)", "Hide (reopen it with the chart's PANEL button)");
        M.put("Instrumento del gráfico donde está activa la estrategia", "Instrument of the chart where the strategy is active");
        M.put("Saldo", "Balance");
        M.put("Qty: cantidad fija.\n", "Qty: fixed quantity.\n");
        M.put("Risk: marcas el stop en el gráfico y la cantidad sale del riesgo del SL (Money o % del balance).\n", "Risk: you mark the stop on the chart and the quantity comes from the SL risk (Money or % of balance).\n");
        M.put("Promediado: la 1ª entrada como en Risk; las siguientes del mismo lado llevan el stop al SL común y su cantidad sale de:\n", "Averaging: the 1st entry works like Risk; the next ones on the same side put their stop on the common SL and their quantity comes from:\n");
        M.put("  · en contra (algún SL en pérdida): el Riesgo total menos lo que ya arriesgan los SL;\n", "  · against (some SL at a loss): the Total risk minus what the SLs already risk;\n");
        M.put("  · a favor (todos los SL en BE o mejor): el % indicado de lo que aseguran los SL.", "  · in favor (all SLs at BE or better): the given % of what the SLs lock in.");
        M.put("Riesgo total", "Total risk");
        M.put("% a favor", "% in favor");
        M.put("Promediado en contra: dinero máximo que pueden perder TODAS las entradas juntas si salta el SL común.\n", "Averaging against: maximum money ALL entries together can lose if the common SL is hit.\n");
        M.put("Ej.: 500 con 100 ya en riesgo -> la nueva entrada puede arriesgar 400.", "E.g.: 500 with 100 already at risk -> the new entry can risk 400.");
        M.put("Promediado a favor (todos los SL en BE o mejor): % de lo que ya aseguran los SL que puede arriesgar la nueva entrada.\n", "Averaging in favor (all SLs at BE or better): % of what the SLs already lock in that the new entry can risk.\n");
        M.put("Ej.: los SL aseguran 200 y pones 50 -> la nueva entrada arriesga 100 y el SL común sigue asegurando 100.", "E.g.: the SLs lock in 200 and you set 50 -> the new entry risks 100 and the common SL still locks in 100.");
        M.put("Bróker", "Broker");
        M.put("Cuenta", "Account");
        M.put("Dif. en ticks", "Tick offset");
        M.put("BID/ASK a esta distancia en ticks del mejor bid/ask", "BID/ASK at this distance in ticks from the best bid/ask");
        M.put("COMPRAR", "BUY");
        M.put("VENDER", "SELL");
        M.put("Cancelar", "Cancel");
        M.put("Cancela todas las órdenes pendientes", "Cancels all pending orders");
        M.put("BE (punto de equilibrio): mueve el stop al precio medio (o lo crea si no hay)", "BE (breakeven): moves the stop to the average price (or creates one if there is none)");
        M.put("Cancela todas las órdenes y cierra la posición a mercado", "Cancels all orders and closes the position at market");
        M.put("Ticks · Puntos (1 punto = 1,00 de precio) · Money (pérdida total de la operación)\n", "Ticks · Points (1 point = 1.00 of price) · Money (total loss of the trade)\n");
        M.put("% Inicial / % Actual: pérdida = ese % del saldo inicial (se lee de la cuenta en MotiveWave) o del saldo actual de la cuenta", "% Initial / % Current: loss = that % of the initial balance (read from the MotiveWave account) or of the current account balance");
        M.put("Ticks · Puntos · Money · RR: el objetivo se pone a la distancia del SL multiplicada por este número\n", "Ticks · Points · Money · RR: the target goes at the SL distance multiplied by this number\n");
        M.put("(RR 2 con un SL de 10 puntos = TP a 20 puntos). Usa el SL del panel, o el stop marcado en el gráfico en modo Risk.\n", "(RR 2 with a 10-point SL = TP at 20 points). Uses the panel SL, or the stop marked on the chart in Risk mode.\n");
        M.put("% Inicial / % Actual: beneficio = ese % del saldo inicial o del actual", "% Initial / % Current: profit = that % of the initial or the current balance");
        M.put("BE: ticks a favor para mover el stop a la entrada", "BE: ticks in favor to move the stop to the entry");
        M.put("BE: ticks más allá de la entrada donde queda el stop", "BE: ticks beyond the entry where the stop is placed");
        M.put("Trail: ticks a favor para empezar a seguir (línea Trail. Trigger)", "Trail: ticks in favor to start trailing (Trail. Trigger line)");
        M.put("Trail: distancia en ticks del stop al precio", "Trail: distance in ticks from the stop to the price");
        M.put("Server: el STOP de una entrada LMT (o BID/ASK) se envía al bróker junto con ella, así sigue ahí aunque cierres MotiveWave o apagues el equipo.\n", "Server: the STOP of an LMT (or BID/ASK) entry is sent to the broker with it, so it stays there even if you close MotiveWave or turn off the computer.\n");
        M.put("El objetivo (TP), el OCO, el BE y el trailing siguen gestionándose en cliente: el SDK de MotiveWave no permite dejarlos en el bróker.\n", "The target (TP), the OCO, the BE and the trailing are still managed by the client: the MotiveWave SDK does not allow leaving them at the broker.\n");
        M.put("Las entradas STP, STP LMT y MKT no cambian.", "STP, STP LMT and MKT entries do not change.");
        M.put("Client: el SL y el TP son internos y se envían cuando se ejecuta la entrada (MotiveWave debe seguir abierto)", "Client: the SL and TP are internal and are sent when the entry fills (MotiveWave must stay open)");
        M.put("SL/TP: un stop y un objetivo por entrada, con los valores de abajo.\n", "SL/TP: one stop and one target per entry, with the values below.\n");
        M.put("O elige una de tus estrategias de salida guardadas (como las Exit Strategies de MotiveWave):\n", "Or choose one of your saved exit strategies (like MotiveWave's Exit Strategies):\n");
        M.put("pasos con un % de la cantidad, cada uno con su stop y su objetivo, riesgo que calcula la cantidad y break even.\n", "steps with a % of the quantity, each with its stop and target, a risk that sets the quantity, and break even.\n");
        M.put("Se crean y se editan con el icono de ajustes de la derecha.", "Create and edit them with the settings icon on the right.");
        M.put("Estrategias de salida: nueva, editar, duplicar o eliminar", "Exit strategies: new, edit, duplicate or delete");
        M.put("Enlazar órdenes pendientes", "Link pending orders");
        M.put("Sin posición: enlaza las entradas pendientes (al ejecutarse una se cancelan las demás).\n", "No position: links the pending entries (when one fills, the others are cancelled).\n");
        M.put("Con posición: convierte en OCO las órdenes de salida sueltas (stop + objetivo): al ejecutarse una se cancela la otra.", "With a position: turns loose exit orders (stop + target) into an OCO: when one fills, the other is cancelled.");
        M.put("Nueva estrategia…", "New strategy…");
        M.put("Editar «", "Edit «");
        M.put("Duplicar «", "Duplicate «");
        M.put("Eliminar «", "Delete «");
        M.put("El riesgo de «", "The risk of «");
        M.put("» no da para 1 contrato", "» is not enough for 1 contract");
        M.put(" (no tiene ningún stop)", " (it has no stop)");
        M.put("Stop: orden stop en el bróker.\n", "Stop: stop order at the broker.\n");
        M.put("Trail: orden stop que el panel va acercando para que quede como mucho a esa distancia del precio.\n", "Trail: stop order that the panel keeps moving so it stays at most that distance from the price.\n");
        M.put("MIT: en el lado del stop equivale a Stop (el SDK no tiene órdenes MIT).", "MIT: on the stop side it works like Stop (the SDK has no MIT orders).");
        M.put("Limit: orden límite en el bróker.\n", "Limit: limit order at the broker.\n");
        M.put("MIT: el panel vigila el precio y, cuando lo toca, cierra esa parte a mercado\n", "MIT: the panel watches the price and, when it is touched, closes that part at market\n");
        M.put("(no hay orden en el bróker: MotiveWave tiene que seguir abierto).", "(there is no order at the broker: MotiveWave must stay open).");
        M.put("Una estrategia de salida crea automáticamente uno o más stops y objetivos al entrar en una posición. ", "An exit strategy automatically creates one or more stops and targets when a position is entered. ");
        M.put("Cada paso tiene un stop y un objetivo (los dos opcionales). Con Riesgo activado, la cantidad se calcula a partir de la distancia de los stops.", "Each step has a stop and a target (both optional). With Risk enabled, the quantity is calculated from the stop distance.");
        M.put(" (copia)", " (copy)");
        M.put("p. ej. MD 25k", "e.g. MD 25k");
        M.put("Porcentaje", "Percent");
        M.put("Ticks: las distancias son ticks desde el precio de entrada.\n", "Ticks: distances are ticks from the entry price.\n");
        M.put("Porcentaje: son un % del precio de entrada (1 % con entrada en 100 = 1,00).", "Percent: they are a % of the entry price (1 % with entry at 100 = 1.00).");
        M.put("Importe fijo", "Fixed amount");
        M.put("Activado", "Enabled");
        M.put("Con Riesgo activado, la cantidad de la entrada la calcula la estrategia:\n", "With Risk enabled, the strategy calculates the entry quantity:\n");
        M.put("la mayor con la que, si saltan todos los stops, no se pierde más de ese importe (se redondea hacia abajo).\n", "the largest one that, if all stops are hit, loses no more than that amount (rounded down).\n");
        M.put("% de la cuenta: ese % del saldo actual.", "% of account: that % of the current balance.");
        M.put("Break Even: cuando el precio avanza a favor esa distancia, los stops pasan a la entrada.\n", "Break Even: when the price moves that distance in favor, the stops move to the entry.\n");
        M.put("Offset: distancia más allá de la entrada donde quedan (para cubrir comisiones).", "Offset: distance beyond the entry where they are placed (to cover commissions).");
        M.put("Nombre:", "Name:");
        M.put("Tipo de offset:", "Offset type:");
        M.put("Riesgo:", "Risk:");
        M.put("Los pasos suman ", "The steps add up to ");
        M.put(" %: el resto de la cantidad queda sin stop ni objetivo", " %: the rest of the quantity has no stop or target");
        M.put(" %: lo que pasa de 100 % no se usa (los últimos pasos se quedan sin contratos)", " %: anything above 100 % is not used (the last steps get no contracts)");
        M.put("Paso", "Step");
        M.put("Cantidad (%)", "Quantity (%)");
        M.put("Offset stop", "Stop offset");
        M.put("Tipo", "Type");
        M.put("Offset objetivo", "Target offset");
        M.put("Quitar este paso", "Remove this step");
        M.put("Añadir un paso", "Add a step");
        M.put("Ponle un nombre a la estrategia", "Give the strategy a name");
        M.put("Ese nombre está reservado", "That name is reserved");
        M.put("Ya hay una estrategia con ese nombre", "There is already a strategy with that name");
        M.put("Activa al menos un stop o un objetivo con una distancia mayor que 0", "Enable at least one stop or target with a distance greater than 0");
        M.put("Con Riesgo activado hace falta al menos un stop: de él sale la cantidad", "With Risk enabled you need at least one stop: the quantity comes from it");
        M.put("Con Riesgo activado el importe tiene que ser mayor que 0", "With Risk enabled the amount must be greater than 0");
        M.put("editar", "edit");
        M.put("nueva", "new");
        M.put("Estrategia inactiva: pulsa Activate en el gráfico", "Strategy inactive: press Activate on the chart");
        M.put("Colocación cancelada", "Placement cancelled");
        M.put("Órdenes pendientes canceladas", "Pending orders cancelled");
        M.put("FLAT: órdenes canceladas y posición cerrada", "FLAT: orders cancelled and position closed");
        M.put("Punto de equilibrio: no hay posición abierta", "Breakeven: no open position");
        M.put("Punto de equilibrio NO aplicado: el precio está en contra (", "Breakeven NOT applied: the price is against you (");
        M.put(" se ejecutaría ya)", " would fill right away)");
        M.put("Punto de equilibrio: stop movido a ", "Breakeven: stop moved to ");
        M.put("Punto de equilibrio: stop creado en ", "Breakeven: stop created at ");
        M.put("Enlazar: hacen falta al menos 2 órdenes de entrada pendientes sin enlazar", "Link: you need at least 2 unlinked pending entry orders");
        M.put("Enlazadas ", "Linked ");
        M.put(" órdenes: al ejecutarse una se cancelan las demás", " orders: when one fills, the others are cancelled");
        M.put("Enlazadas como OCO de la posición: al ejecutarse el stop o el objetivo se cancela el otro", "Linked as the position's OCO: when the stop or the target fills, the other is cancelled");
        M.put("Enlazadas a la posición: se cancelan al cerrarla (para un OCO hacen falta un stop y un objetivo de la misma cantidad)", "Linked to the position: they are cancelled when it closes (an OCO needs a stop and a target of the same quantity)");
        M.put("NO colocada: ", "NOT placed: ");
        M.put("Promediado NO enviado: ", "Averaging NOT sent: ");
        M.put("Clic con modificadores no disponible en modo Risk/Promediado: usa los botones del panel", "Modifier click not available in Risk/Averaging mode: use the panel buttons");
        M.put("Trailing desactivado", "Trailing disabled");
        M.put("Objetivo", "Target");
        M.put(" NO separado: ", " NOT split: ");
        M.put(" de ", " of ");
        M.put(" separado del grupo", " split from the group");
        M.put(" (quedan ", " (");
        M.put(" juntos)", " still together)");
        M.put(" y movido a ", " and moved to ");
        M.put(" · agrupado allí con ", " · grouped there with ");
        M.put(" más", " more");
        M.put(" (trailing desactivado)", " (trailing disabled)");
        M.put("Trail. Trigger en ", "Trail. Trigger at ");
        M.put("Cantidad NO cambiada: la entrada ya se ha ejecutado en parte y sus salidas están repartidas", "Quantity NOT changed: the entry is partly filled and its exits are already split");
        M.put("Cantidad cambiada a ", "Quantity changed to ");
        M.put("EJECUTADA %s%d @ %s", "FILLED %s%d @ %s");
        M.put(" · ATENCIÓN: el stop se ejecutó ANTES que su entrada, revisa la posición", " · WARNING: the stop filled BEFORE its entry, check the position");
        M.put("Modificación RECHAZADA por el bróker: ", "Modification REJECTED by the broker: ");
        M.put(" sigue en ", " stays at ");
        M.put("RECHAZADA por el bróker: ", "REJECTED by the broker: ");
        M.put("Posición cerrada", "Position closed");
        M.put("Modo ", "Mode ");
        M.put(": activa OCO Strategy con SL en Money o en % del balance (el importe es el riesgo por operación)", ": enable OCO Strategy with the SL in Money or % of balance (the amount is the risk per trade)");
        M.put(": el riesgo del SL es 0", ": the SL risk is 0");
        M.put("Promediado: indica el riesgo total", "Averaging: set the total risk");
        M.put("parte de la posición", "part of the position");
        M.put("la entrada ", "the entry ");
        M.put(" no tiene SL: ponle stop primero", " has no SL: add a stop first");
        M.put(" contrato(s) de la posición sin SL: ponles stop primero", " position contract(s) without SL: add a stop first");
        M.put("los SL están justo en BE: no hay beneficio asegurado del que arriesgar un %", "the SLs are exactly at BE: there is no locked-in profit to risk a % of");
        M.put("ya arriesgas ", "you already risk ");
        M.put(": no queda margen", ": no room left");
        M.put(" queda al otro lado del SL común ", " is on the other side of the common SL ");
        M.put("con ", "with ");
        M.put(" de margen no llega ni a 1 contrato (cada uno arriesga ", " of room it does not reach 1 contract (each one risks ");
        M.put(" hasta ", " down to ");
        M.put("Promediado a favor: %s · arriesga %s (%.0f %% de %s asegurados)", "Averaging in favor: %s · risks %s (%.0f %% of %s locked in)");
        M.put("Promediado en contra: %s · arriesga %s (quedaban %s de %s)", "Averaging against: %s · risks %s (%s of %s was left)");
        M.put(" · SL común ", " · common SL ");
        M.put("MotiveWave no da el balance inicial de esta cuenta", "MotiveWave does not provide the initial balance of this account");
        M.put("el bróker no da el saldo actual de la cuenta", "the broker does not provide the current account balance");
        M.put("NO enviada: ", "NOT sent: ");
        M.put("NO enviada: el stop %s está en el lado incorrecto para %s (entrada ≈ %s)", "NOT sent: the stop %s is on the wrong side for %s (entry ≈ %s)");
        M.put("NO enviada: con ese stop el riesgo no cubre ni 1 contrato (acerca el stop o sube el riesgo del SL)", "NOT sent: with that stop the risk does not cover 1 contract (move the stop closer or raise the SL risk)");
        M.put("Límite de órdenes por segundo alcanzado: envío bloqueado", "Orders-per-second limit reached: sending blocked");
        M.put(" (límite ", " (limit ");
        M.put(", emulada en cliente)", ", emulated in client)");
        M.put(" ya en el bróker", " already at the broker");
        M.put(" stops ya en el bróker", " stops already at the broker");
        M.put(" · SL/TP en cliente (solo las entradas LMT envían el stop al bróker)", " · SL/TP in client (only LMT entries send the stop to the broker)");
        M.put("STP LMT cancelada", "STP LMT cancelled");
        M.put("Cancelando orden…", "Cancelling order…");
        M.put(": no hay posición abierta", ": no open position");
        M.put(": toda la posición ya tiene ", ": the whole position already has a ");
        M.put("objetivo", "target");
        M.put(" (arrástralo para moverlo)", " (drag it to move it)");
        M.put(": la orden ya no está pendiente", ": the order is no longer pending");
        M.put(" ya definido para esta orden: arrástralo para moverlo", " already set for this order: drag it to move it");
        M.put(" NO colocado: ", " NOT placed: ");
        M.put(" creado en ", " created at ");
        M.put(" para ", " for ");
        M.put(" (arrástralo para ajustarlo)", " (drag it to adjust it)");
        M.put(" NO colocado: el ", " NOT placed: the ");
        M.put("stop debe quedar en el lado de pérdida", "stop must be on the loss side");
        M.put("objetivo debe quedar en el lado de beneficio", "target must be on the profit side");
        M.put(" de la entrada (", " of the entry (");
        M.put(" interno", " internal");
        M.put(" (ya en el bróker)", " (already at the broker)");
        M.put(" añadido en ", " added at ");
        M.put(" · agrupado con ", " · grouped with ");
        M.put(" objetivo", " target");
        M.put("Objetivos", "Targets");
        M.put(" separados: arrastra cada uno por su lado", " separated: drag each one on its own");
        M.put("activado", "enabled");
        M.put("desactivado", "disabled");
        M.put(" en los ", " on the ");
        M.put(" stops del grupo", " stops of the group");
        M.put(" objetivos", " targets");
        M.put(" cancelados", " cancelled");
        M.put("Trailing activado: empieza al tocar ", "Trailing enabled: starts when the price touches ");
        M.put(" (arrastra la línea Trail. Trigger)", " (drag the Trail. Trigger line)");
        M.put("Cerrando posición a mercado", "Closing position at market");
        M.put("NO movida: ", "NOT moved: ");
        M.put("Orden movida a ", "Order moved to ");
        M.put("STP LMT movida a ", "STP LMT moved to ");
        M.put("NO movido: ", "NOT moved: ");
        M.put("Agrupados ", "Grouped ");
        M.put(" en ", " at ");
        M.put(": se mueven y se cancelan juntos (#", ": they move and cancel together (#");
        M.put(" los separa)", " splits them)");
        M.put(" movidos a ", " moved to ");
        M.put(" movido a ", " moved to ");
        M.put(" interno en ", " internal at ");
        M.put("la salida ya no existe", "the exit no longer exists");
        M.put("el stop debe quedar en el lado de pérdida de la entrada (", "the stop must be on the loss side of the entry (");
        M.put("el ", "the ");
        M.put("STP LMT disparada: límite en ", "STP LMT triggered: limit at ");
        M.put("MIT tocado en ", "MIT touched at ");
        M.put(" a mercado", " at market");
        M.put("BE automático: stop a ", "Auto BE: stop to ");
        M.put("Trailing en marcha (distancia ", "Trailing running (distance ");
        M.put("» calcula la cantidad por riesgo pero no tiene ningún stop", "» sizes by risk but has no stop");
        M.put("el bróker no da el saldo de la cuenta (riesgo en % de «", "the broker does not provide the account balance (risk in % of «");
        M.put("con el riesgo de «", "with the risk of «");
        M.put(") no llega ni a 1 contrato", ") it does not reach 1 contract");
        M.put("compra límite ", "buy limit ");
        M.put(" por encima del ask ", " above the ask ");
        M.put(" (entraría a mercado)", " (it would fill at market)");
        M.put("venta límite ", "sell limit ");
        M.put(" por debajo del bid ", " below the bid ");
        M.put("stop de compra ", "buy stop ");
        M.put(" por debajo del ask ", " below the ask ");
        M.put(" (se dispararía ya)", " (it would trigger right away)");
        M.put("stop de venta ", "sell stop ");
        M.put(" por encima del bid ", " above the bid ");
        M.put(" · clic en el precio del ", " · click the price of the ");
        M.put("COMPRAR ", "BUY ");
        M.put("VENDER ", "SELL ");
        M.put(" · clic en el precio de entrada", " · click the entry price");
        M.put(" (promediado)", " (averaging)");
        M.put(" · clic en el nivel del stop (riesgo ", " · click the stop level (risk ");
        M.put(" · lado incorrecto", " · wrong side");
        M.put(" · se separa", " · splitting");
        M.put(" STP · promediado ", " STP · averaging ");
        M.put("a favor", "in favor");
        M.put("en contra", "against");
        M.put("PR | STP · lado incorrecto", "PR | STP · wrong side");
        M.put("PR | STP · riesgo insuficiente para 1 contrato", "PR | STP · not enough risk for 1 contract");
        M.put("Órdenes", "Orders");
        M.put("Punto de equilibrio: ticks a favor desde la entrada", "Breakeven: ticks in favor from the entry");
        M.put("STP LMT: ticks del límite más allá del stop", "STP LMT: limit ticks beyond the stop");
        M.put("Entradas GTC (si no, DAY)", "GTC entries (otherwise DAY)");
        M.put("Protecciones", "Protections");
        M.put("Máx. órdenes de entrada por segundo", "Max. entry orders per second");
        M.put("Clic directo en el gráfico: Ctrl = comprar, Shift = vender, +Alt = a mercado", "Direct click on the chart: Ctrl = buy, Shift = sell, +Alt = at market");
        M.put("Aspecto", "Appearance");
        M.put("Etiquetas: distancia al borde derecho (px)", "Labels: distance to the right edge (px)");
        M.put("Panel siempre encima de todas las ventanas", "Panel always on top of all windows");
        M.put("Cerrar posición a mercado", "Close position at market");
        M.put("Cancelar ", "Cancel ");
        M.put("Mostrar Trade Panel", "Show Trade Panel");
        M.put("Cancelar todas las órdenes", "Cancel all orders");
        M.put("BE (punto de equilibrio)", "BE (breakeven)");
        M.put("  ·  Esc o clic derecho para cancelar  ✕", "  ·  Esc or right-click to cancel  ✕");
    }
}
