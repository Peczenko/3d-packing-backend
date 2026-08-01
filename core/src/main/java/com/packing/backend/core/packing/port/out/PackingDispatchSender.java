package com.packing.backend.core.packing.port.out;

import com.packing.backend.core.packing.message.PackingDispatchMessage;

public interface PackingDispatchSender {

    void send(PackingDispatchMessage message);
}
