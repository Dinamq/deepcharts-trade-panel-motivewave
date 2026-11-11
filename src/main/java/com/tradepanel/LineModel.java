package com.tradepanel;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Descripción inmutable de una línea del gráfico (orden, hija OCO interna, posición, disparador
 * de trailing o vista previa). La produce el motor y la pinta {@link LineFigure}; así el dibujo
 * nunca toca el estado del motor.
 */
final class LineModel {

    enum Kind { ENTRY, STOP, TARGET, SYNTH, POSITION, TRIGGER, PREVIEW }

    /** Botón pequeño junto a la etiqueta (SL, TP, TS, X). */
    static final class Btn {
        final String text;
        final boolean active;   // p. ej. TS con el trailing encendido: se pinta invertido
        Btn(String text, boolean active) { this.text = text; this.active = active; }
    }

    final String id;            // estable mientras exista la línea (reutiliza la figura y su tirador)
    final Kind kind;
    final float price;
    final String text;          // "Subm | -13 LMT"
    final boolean linked;       // dibuja el eslabón + "C" (OCO gestionado en cliente)
    final String money;         // "-4056,00 $" o null
    final Color color;
    final boolean draggable;
    final boolean dotted;       // línea punteada (disparador de trailing, vistas previas)
    final boolean closeAtRight; // "X" junto al eje de precios
    final List<Btn> buttons;
    final String mergeKey;      // salidas que se pueden agrupar entre sí (mismo tipo y lado); null si no
    final String badge;         // número de la entrada a la que pertenece ("1", "1·3" en un grupo) o null

    private LineModel(Builder b) {
        id = b.id; kind = b.kind; price = b.price; text = b.text; linked = b.linked; money = b.money;
        color = b.color; draggable = b.draggable; dotted = b.dotted; closeAtRight = b.closeAtRight;
        buttons = List.copyOf(b.buttons);
        mergeKey = b.mergeKey;
        badge = b.badge;
    }

    static Builder of(String id, Kind kind, float price) { return new Builder(id, kind, price); }

    static final class Builder {
        private final String id; private final Kind kind; private final float price;
        private String text = ""; private boolean linked; private String money; private Color color = Color.GRAY;
        private boolean draggable; private boolean dotted; private boolean closeAtRight; private String mergeKey;
        private String badge;
        private final List<Btn> buttons = new ArrayList<>();

        private Builder(String id, Kind kind, float price) { this.id = id; this.kind = kind; this.price = price; }
        Builder text(String t) { text = t; return this; }
        Builder linked(boolean l) { linked = l; return this; }
        Builder money(String m) { money = m; return this; }
        Builder color(Color c) { color = c; return this; }
        Builder draggable(boolean d) { draggable = d; return this; }
        Builder dotted(boolean d) { dotted = d; return this; }
        Builder closeAtRight(boolean c) { closeAtRight = c; return this; }
        Builder merge(String k) { mergeKey = k; return this; }
        Builder badge(String b) { badge = b; return this; }
        Builder btn(String t) { buttons.add(new Btn(t, false)); return this; }
        Builder btn(String t, boolean active) { buttons.add(new Btn(t, active)); return this; }
        LineModel build() { return new LineModel(this); }
    }
}
