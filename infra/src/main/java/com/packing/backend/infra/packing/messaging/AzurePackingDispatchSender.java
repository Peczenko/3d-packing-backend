package com.packing.backend.infra.packing.messaging;

import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.packing.backend.core.packing.message.PackingDispatchMessage;
import com.packing.backend.core.packing.port.out.PackingDispatchSender;
import com.packing.backend.infra.packing.PackingContractCodec;

public final class AzurePackingDispatchSender implements PackingDispatchSender {

    private final ServiceBusSenderClient sender;
    private final PackingContractCodec codec;

    public AzurePackingDispatchSender(ServiceBusSenderClient sender, PackingContractCodec codec) {
        this.sender = sender;
        this.codec = codec;
    }

    @Override
    public void send(PackingDispatchMessage command) {
        ServiceBusMessage message = new ServiceBusMessage(codec.encodeDispatch(command));
        message.setMessageId(command.jobId().toString());
        message.setContentType("application/json");
        sender.sendMessage(message);
    }
}
