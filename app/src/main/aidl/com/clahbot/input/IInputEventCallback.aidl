package com.clahbot.input;

oneway interface IInputEventCallback {
    void onLines(String lines);
    void onError(String message);
}
