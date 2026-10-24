package org.iaproject.memchat.search.semantic;

/** An embedding space shared by document and query vectors. Identity and dimensions must stay fixed. */
public interface EmbeddingModel {
    String modelId();

    int dimensions();

    /** Return a finite, nonzero vector of exactly {@link #dimensions()} components. */
    float[] embed(String text);
}
