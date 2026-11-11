package com.tradepanel;

/** Instantánea inmutable del estado que el motor publica para la ventana del panel. */
final class PanelSnapshot {
    final boolean active;
    final String symbol;
    final String broker;
    final String account;
    final double balance;
    final int openQty;
    final double openPnl;
    final double dailyPnl;
    final String armed;     // instrucción en curso ("VENDER LMT · clic en el precio"), o ""
    final String status;    // último mensaje (envíos, rechazos, validaciones)
    final boolean armedBuy;
    final String armedKind; // "LMT", "STP", "STP LMT", "MKT", "BID/ASK" o "" (resalta el botón armado)
    final double autoInitBal; // balance inicial que da la cuenta en MotiveWave (NaN si no lo da)

    PanelSnapshot(boolean active, String symbol, String broker, String account, double balance, int openQty,
                  double openPnl, double dailyPnl, String armed, String status, boolean armedBuy, String armedKind, double autoInitBal) {
        this.active = active; this.symbol = symbol; this.broker = broker; this.account = account;
        this.balance = balance; this.openQty = openQty; this.openPnl = openPnl; this.dailyPnl = dailyPnl;
        this.armed = armed; this.status = status; this.armedBuy = armedBuy; this.armedKind = armedKind;
        this.autoInitBal = autoInitBal;
    }

    static final PanelSnapshot EMPTY = new PanelSnapshot(false, "—", "—", "—", 0, 0, 0, 0, "", "", false, "", Double.NaN);
}
