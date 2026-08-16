package com.packing.backend.infra.packing.messaging;

import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.packing.backend.core.packing.message.PackingDispatchMessage;
import com.packing.backend.domain.packing.PackingJobId;
import com.packing.backend.infra.packing.PackingContractCodec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AzurePackingDispatchSenderTest {

    @Test
    void publishesTheExactVersionOneDispatchEnvelopeWithMessageMetadata() {
        ServiceBusSenderClient sender = mock(ServiceBusSenderClient.class);
        AzurePackingDispatchSender dispatchSender = new AzurePackingDispatchSender(
                                                                                   sender,
                                                                                   new PackingContractCodec(new ObjectMapper()));
        PackingDispatchMessage command = PackingDispatchMessage.versionOne(PackingJobId.generate());

        dispatchSender.send(command);

        ArgumentCaptor<ServiceBusMessage> message = ArgumentCaptor.forClass(ServiceBusMessage.class);
        verify(sender).sendMessage(message.capture());
        assertThat(message.getValue()
                          .getBody()
                          .toString())
                                      .isEqualTo("{\"messageVersion\":1,\"jobId\":\"" + command.jobId() + "\"}");
        assertThat(message.getValue()
                          .getMessageId()).isEqualTo(command.jobId()
                                                            .toString());
        assertThat(message.getValue()
                          .getContentType()).isEqualTo("application/json");
    }
}
