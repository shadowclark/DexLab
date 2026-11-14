package com.dexlab.app.shizuku;

oneway interface ILogcatCallback {
    void onLines(in String[] lines);
    void onError(String message);
    void onStopped();
}
