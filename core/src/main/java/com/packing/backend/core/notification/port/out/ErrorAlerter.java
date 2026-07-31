package com.packing.backend.core.notification.port.out;

import com.packing.backend.core.notification.ServerErrorReport;

public interface ErrorAlerter {

    void alert(ServerErrorReport report);
}
