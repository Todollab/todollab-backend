package com.todollab.common.util;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Fractional-indexing helpers for ordering columns, tasks, and subtasks.
 */
public final class PositionService {

    public static final double DEFAULT_STEP = 1000.0;
    public static final double MIN_GAP = 1e-5;

    private PositionService() {
    }

    public static double appendPosition(Double maxPosition) {
        if (maxPosition == null) {
            return DEFAULT_STEP;
        }
        if (!Double.isFinite(maxPosition)) {
            throw new IllegalArgumentException("Posição máxima deve ser um número finito.");
        }
        return maxPosition + DEFAULT_STEP;
    }

    public static boolean needsRebalance(List<Double> sortedPositions) {
        if (sortedPositions == null || sortedPositions.isEmpty()) {
            return false;
        }
        Double first = sortedPositions.get(0);
        if (first == null || !Double.isFinite(first) || first <= 0.0) {
            return true;
        }
        for (int i = 0; i < sortedPositions.size() - 1; i++) {
            Double current = sortedPositions.get(i);
            Double next = sortedPositions.get(i + 1);
            if (current == null || next == null || !Double.isFinite(current) || !Double.isFinite(next)) {
                return true;
            }
            if (next <= 0.0) {
                return true;
            }
            double gap = next - current;
            if (gap <= 0.0 || gap < MIN_GAP) {
                return true;
            }
        }
        return false;
    }

    public static List<Double> rebalancePositions(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Quantidade de posições não pode ser negativa.");
        }
        if (count == 0) {
            return List.of();
        }
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> i * DEFAULT_STEP)
                .toList();
    }
}
