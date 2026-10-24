package com.clahbot.input;

import com.clahbot.input.IInputEventCallback;

interface IInputEventService {
    void destroy() = 16777114;

    String getTouchscreenInfo() = 1;

    void start(String devicePath, IInputEventCallback callback) = 2;

    void stop() = 3;
}
