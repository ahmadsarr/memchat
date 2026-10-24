package org.iaproject.memchat.search.semantic;

import org.iaproject.memchat.search.api.SearchException;

final class EmbeddingVectors {
    private EmbeddingVectors() {}

    static float[] normalizedCopy(float[] vector, int dimensions) {
        if (vector == null || vector.length != dimensions) {
            throw new SearchException("Embedding must contain " + dimensions + " dimensions", null);
        }
        double squaredNorm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new SearchException("Embedding contains a non-finite component", null);
            }
            squaredNorm += (double) value * value;
        }
        if (squaredNorm == 0) throw new SearchException("Embedding must not be a zero vector", null);
        double norm = Math.sqrt(squaredNorm);
        float[] normalized = new float[dimensions];
        for (int i = 0; i < dimensions; i++) normalized[i] = (float) (vector[i] / norm);
        return normalized;
    }
}
