package com.packing.backend.infra.packing.temporal;

import com.packing.backend.infra.packing.temporal.PackingEngineContract.ResultFiles;
import com.packing.backend.infra.packing.temporal.PackingEngineContract.WriteResultInput;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface PackingResultActivity {

    @ActivityMethod(name = "WriteResult")
    ResultFiles writeResult(WriteResultInput input);
}
