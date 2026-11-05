package com.packing.backend.infra.packing.temporal;

import com.packing.backend.infra.packing.temporal.PackingEngineContract.EngineResult;
import com.packing.backend.infra.packing.temporal.PackingEngineContract.RunEngineInput;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface PackingEngineActivity {

    @ActivityMethod(name = "RunEngine")
    EngineResult runEngine(RunEngineInput input);
}
