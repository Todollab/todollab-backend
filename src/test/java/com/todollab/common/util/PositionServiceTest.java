package com.todollab.common.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PositionServiceTest {

    @Test
    void appendPositionReturnsDefaultStepWhenNullAndAddsStepWhenPresent() {
        assertThat(PositionService.DEFAULT_STEP).isEqualTo(1000.0);
        assertThat(PositionService.MIN_GAP).isEqualTo(1e-5);
        assertThat(PositionService.appendPosition(null)).isEqualTo(1000.0);
        assertThat(PositionService.appendPosition(3000.0)).isEqualTo(4000.0);
    }

    @Test
    void needsRebalanceReturnsTrueForTinyGapsOrNonPositivePositionsAndFalseWhenSpaced() {
        assertThat(PositionService.needsRebalance(List.of())).isFalse();
        assertThat(PositionService.needsRebalance(List.of(1000.0))).isFalse();
        assertThat(PositionService.needsRebalance(List.of(1000.0, 2000.0, 3000.0))).isFalse();
        assertThat(PositionService.needsRebalance(List.of(1000.0, 1000.00002))).isFalse();

        assertThat(PositionService.needsRebalance(List.of(1000.0, 1000.000005))).isTrue();
        assertThat(PositionService.needsRebalance(List.of(1000.0, 1000.0))).isTrue();
        assertThat(PositionService.needsRebalance(List.of(2000.0, 1000.0))).isTrue();
        assertThat(PositionService.needsRebalance(List.of(0.0, 1000.0))).isTrue();
        assertThat(PositionService.needsRebalance(List.of(-5.0, 1000.0))).isTrue();
        assertThat(PositionService.needsRebalance(List.of(0.0))).isTrue();
    }

    @Test
    void rebalancePositionsReturnsMultiplesOfDefaultStep() {
        assertThat(PositionService.rebalancePositions(0)).isEmpty();
        assertThat(PositionService.rebalancePositions(3)).containsExactly(1000.0, 2000.0, 3000.0);
        assertThatThrownBy(() -> PositionService.rebalancePositions(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
