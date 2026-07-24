package com.packing.backend.core.shared;

import java.io.IOException;
import java.io.InputStream;

/**
 * Contract: every call must return a fresh stream positioned at the first byte,
 * independent of any stream previously returned. The caller closes what it opens.
 */
@FunctionalInterface
public interface ContentSource {

    InputStream open() throws IOException;
}
