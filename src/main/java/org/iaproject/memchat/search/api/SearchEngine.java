package org.iaproject.memchat.search.api;

import java.io.IOException;

/** Common component contract implemented by individual indexes and search composites. */
public interface SearchEngine extends SearchReader, SearchWriter, AutoCloseable {
    @Override
    /** Releases owned resources. Repeated calls must have no effect. */
    void close() throws IOException;
}
