package com.packing.backend.core.shared;

import java.io.IOException;
import java.io.InputStream;

@FunctionalInterface
public interface ContentSource {

    InputStream open() throws IOException;
}
