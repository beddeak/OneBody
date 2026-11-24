package io.github.beddeak.onebody.paper;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DamageEventGuardTest {
    private final DamageEventGuard guard = new DamageEventGuard();

    @Test void punishmentScopeActivatesAndThenClears() {
        assertFalse(guard.active());
        guard.runPenalty(() -> assertTrue(guard.active()));
        assertFalse(guard.active());
    }

    @Test void innerScopeDoesNotClearTheOuterScope() {
        guard.runPenalty(() -> {
            assertTrue(guard.active());
            guard.runPenalty(() -> assertTrue(guard.active()));
            assertTrue(guard.active());
        });
        assertFalse(guard.active());
    }

    @Test void exceptionCannotLeaveTheGuardActive() {
        assertThrows(IllegalStateException.class, () -> guard.runPenalty(() -> {
            assertTrue(guard.active());
            throw new IllegalStateException("damage listener failed");
        }));
        assertFalse(guard.active());
        guard.runPenalty(() -> assertTrue(guard.active()));
        assertFalse(guard.active());
    }

    @Test void caughtInnerExceptionStillPreservesOuterScope() {
        guard.runPenalty(() -> {
            assertThrows(IllegalStateException.class, () -> guard.runPenalty(() -> {
                throw new IllegalStateException("nested damage failed");
            }));
            assertTrue(guard.active());
        });
        assertFalse(guard.active());
    }

    @Test void propagatedNestedExceptionUnwindsEveryScope() {
        assertThrows(IllegalStateException.class, () -> guard.runPenalty(() ->
                guard.runPenalty(() -> { throw new IllegalStateException("nested damage failed"); })));
        assertFalse(guard.active());
    }
}
