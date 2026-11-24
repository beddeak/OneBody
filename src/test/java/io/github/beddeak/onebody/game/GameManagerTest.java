package io.github.beddeak.onebody.game;

import static org.junit.jupiter.api.Assertions.*;

import io.github.beddeak.onebody.config.GameSettings;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class GameManagerTest {
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();
    private final UUID d = UUID.randomUUID();
    private final FakeRuntime runtime = new FakeRuntime();
    private final GameManager game = new GameManager(runtime, settings(2, 1.0, 1));

    private static GameSettings settings(int tolerance, double damage, int interval) {
        return new GameSettings(tolerance, damage, interval, EnumSet.allOf(SyncAction.class));
    }

    @BeforeEach void setup() {
        for (UUID id : List.of(a, b, c, d)) {
            runtime.available.add(id);
            runtime.online.add(id);
        }
        assertTrue(game.start());
    }

    @ParameterizedTest @EnumSource(value = SyncAction.class, names = {"SNEAK", "SPRINT"})
    void heldStateIsAcceptedWithoutAnotherToggleAndEitherUnanimousStateRecovers(SyncAction action) {
        game.settings(settings(2, 3.0, 20));
        game.stateSnapshot(action, java.util.Map.of(a, true, b, true, c, true, d, true));
        assertNull(game.activeChallenge());
        game.stateSnapshot(action, java.util.Map.of(a, true, b, false, c, true, d, true));
        assertEquals(Set.of(b), game.activeChallenge().pendingPlayers());
        runtime.advanceTo(103);
        assertEquals(ChallengeState.PUNISHING, game.activeChallenge().state());
        game.stateSnapshot(action, java.util.Map.of(a, true, b, true, c, true, d, true));
        assertNull(game.activeChallenge());
        assertEquals(0, runtime.activeTasks());
        runtime.advanceTo(150);
        assertEquals(0, runtime.totalHits());
        game.stateSnapshot(action, java.util.Map.of(a, true, b, false, c, true, d, true));
        game.stateSnapshot(action, java.util.Map.of(a, false, b, false, c, false, d, false));
        assertNull(game.activeChallenge());
    }

    @ParameterizedTest @EnumSource(value = SyncAction.class, names = {"SNEAK", "SPRINT"})
    void releasingAfterMatchingReopensUntilEveryoneMatchesNow(SyncAction action) {
        game.stateSnapshot(action, java.util.Map.of(a, true, b, true, c, true, d, false));
        UUID released = List.of(a, b, c).stream()
                .filter(id -> !id.equals(game.activeChallenge().initiator())).findFirst().orElseThrow();
        var frame = new java.util.HashMap<>(java.util.Map.of(a, true, b, true, c, true, d, true));
        frame.put(released, false);
        game.stateSnapshot(action, frame);
        assertEquals(Set.of(released), game.activeChallenge().pendingPlayers());
        // A historical start event must not clear a present mismatch.
        game.action(released, action);
        assertEquals(Set.of(released), game.activeChallenge().pendingPlayers());
        game.stateSnapshot(action, java.util.Map.of(a, true, b, true, c, true, d, true));
        assertNull(game.activeChallenge());
    }

    @ParameterizedTest @EnumSource(value = SyncAction.class, names = {"SNEAK", "SPRINT"})
    void stateMismatchDuringAnotherActionIsDetectedOnNextSample(SyncAction action) {
        threeJump();
        var mixed = java.util.Map.of(a, true, b, false, c, true, d, true);
        game.stateSnapshot(action, mixed);
        assertEquals(SyncAction.JUMP, game.activeChallenge().action());
        game.action(d, SyncAction.JUMP);
        game.stateSnapshot(action, mixed);
        assertEquals(action, game.activeChallenge().action());
        game.settings(new GameSettings(20, 9, 5, Set.of()));
        game.stateSnapshot(action, java.util.Map.of(a, true, b, true, c, true, d, true));
        assertNull(game.activeChallenge());
        game.stateSnapshot(action, mixed);
        assertNull(game.activeChallenge());
    }

    @ParameterizedTest @EnumSource(value = SyncAction.class, names = {"SNEAK", "SPRINT"})
    void joiningPlayerAlreadyHoldingStateCompletesOnNextFrameAndLeavingInitiatorCannotStick(SyncAction action) {
        game.stateSnapshot(action, java.util.Map.of(a, true, b, true, c, false, d, true));
        UUID newcomer = UUID.randomUUID();
        runtime.online.add(newcomer);
        runtime.available.add(newcomer);
        game.playerConnected(newcomer);
        game.stateSnapshot(action, java.util.Map.of(a, true, b, true, c, false, d, true, newcomer, true));
        assertEquals(Set.of(c), game.activeChallenge().pendingPlayers());
        disconnect(game.activeChallenge().initiator());
        runtime.available.remove(c);
        game.unavailable(c);
        assertNull(game.activeChallenge());
    }

    @Test void incompleteStateFrameCannotInventAStoppedPlayerOrResolveChallenge() {
        game.stateSnapshot(SyncAction.SPRINT, java.util.Map.of(a, true));
        assertNull(game.activeChallenge());
        game.stateSnapshot(SyncAction.SPRINT, java.util.Map.of(a, true, b, false, c, true, d, true));
        game.stateSnapshot(SyncAction.SPRINT, java.util.Map.of(a, false));
        assertNotNull(game.activeChallenge());
        game.stop();
        game.stateSnapshot(SyncAction.SPRINT, java.util.Map.of(a, true, b, false, c, true, d, true));
        assertNull(game.activeChallenge());
    }

    private void threeJump() {
        game.action(a, SyncAction.JUMP);
        game.action(b, SyncAction.JUMP);
        game.action(c, SyncAction.JUMP);
    }

    private boolean disconnect(UUID id) {
        runtime.online.remove(id);
        runtime.available.remove(id);
        return game.playerDisconnected(id);
    }

    @Test void fourPlayersAtTicks100And101SucceedWithNoDamage() {
        game.action(a, SyncAction.JUMP);
        game.action(b, SyncAction.JUMP);
        runtime.advanceTo(101);
        game.action(c, SyncAction.JUMP);
        game.action(d, SyncAction.JUMP);
        assertNull(game.activeChallenge());
        assertEquals(List.of(true), runtime.outcomes);
        runtime.advanceTo(110);
        assertEquals(0, runtime.totalHits());
        assertEquals(0, runtime.activeTasks());
    }

    @ParameterizedTest @EnumSource(value = SyncAction.class, names = {"MOVE", "SNEAK", "SPRINT"}, mode = EnumSource.Mode.EXCLUDE)
    void everyActionCanSynchronize(SyncAction action) {
        for (UUID id : game.participants()) { game.action(id, action); }
        assertNull(game.activeChallenge());
        assertEquals(List.of(true), runtime.outcomes);
        assertEquals(0, runtime.totalHits());
    }

    @Test void oneMissingPlayerPunishesCompletedAndPendingPlayersEveryTick() {
        threeJump();
        runtime.advanceTo(101);
        assertEquals(0, runtime.totalHits());
        runtime.advanceTo(104);
        assertEquals(ChallengeState.PUNISHING, game.activeChallenge().state());
        for (UUID id : game.participants()) {
            assertEquals(List.of(1.0, 1.0), runtime.hits.get(id));
        }
        assertEquals(List.of(false), runtime.outcomes);
    }

    @Test void lastLateResponseStopsEveryPlayersDamageImmediately() {
        threeJump();
        runtime.advanceTo(103);
        game.action(d, SyncAction.JUMP);
        int hits = runtime.totalHits();
        assertNull(game.activeChallenge());
        assertEquals(0, runtime.activeTasks());
        runtime.advanceTo(120);
        assertEquals(hits, runtime.totalHits());
        assertEquals(List.of(false, true), runtime.outcomes);
    }

    @Test void responseConsumesExistingChallengeRatherThanStartingAnother() {
        game.action(a, SyncAction.JUMP);
        ActiveSyncChallenge challenge = game.activeChallenge();
        game.action(b, SyncAction.JUMP);
        assertSame(challenge, game.activeChallenge());
        assertEquals(Set.of(a, b), challenge.completedPlayers());
        assertEquals(Set.of(c, d), challenge.pendingPlayers());
        assertEquals(1, runtime.activeTasks());
    }

    @Test void otherActionsNeverStartOrCompleteAConcurrentChallenge() {
        game.action(a, SyncAction.JUMP);
        ActiveSyncChallenge challenge = game.activeChallenge();
        game.action(b, SyncAction.BLOCK_BREAK);
        assertSame(challenge, game.activeChallenge());
        assertTrue(challenge.pendingPlayers().contains(b));
    }

    @Test void completedPlayerRepeatingDuringPunishmentChangesNothing() {
        threeJump();
        runtime.advanceTo(103);
        ActiveSyncChallenge challenge = game.activeChallenge();
        game.action(a, SyncAction.JUMP);
        assertSame(challenge, game.activeChallenge());
        assertEquals(Set.of(d), challenge.pendingPlayers());
    }

    @Test void eventAtDeadlineStillFailsButQuickRecoveryPreventsFirstDamage() {
        threeJump();
        runtime.tick = 102; // Deliberately process action before this tick's due task.
        game.action(d, SyncAction.JUMP);
        assertEquals(List.of(false, true), runtime.outcomes);
        assertEquals(0, runtime.totalHits());
        runtime.runDue();
        assertEquals(0, runtime.totalHits());
        assertEquals(0, runtime.activeTasks());
    }

    @Test void firstActionIsSilentAndCountsInitiatorAsComplete() {
        game.action(a, SyncAction.JUMP);
        assertTrue(runtime.outcomes.isEmpty());
        assertEquals(0, runtime.punishmentBars);
        assertEquals(Set.of(a), game.activeChallenge().completedPlayers());
        assertEquals(a, game.activeChallenge().initiator());
    }

    @Test void failureShowsRequiredActionImmediatelyAndRecoveryStopsRefreshingIt() {
        game.settings(settings(2, 3.0, 20));
        threeJump();
        runtime.advanceTo(101);
        assertEquals(0, runtime.punishmentBars);
        runtime.advanceTo(102);
        assertEquals(1, runtime.punishmentBars);
        assertEquals(0, runtime.totalHits());
        game.action(d, SyncAction.JUMP);
        int displayed = runtime.punishmentBars;
        runtime.advanceTo(130);
        assertEquals(displayed, runtime.punishmentBars);
    }

    @Test void toleranceOneIncludesOnlyTheStartingTick() {
        game.settings(settings(1, 1, 1));
        threeJump();
        runtime.advanceTo(101);
        assertEquals(ChallengeState.PUNISHING, game.activeChallenge().state());
    }

    @Test void leavingLastPendingPlayerCompletesAndStopsTasks() {
        threeJump();
        runtime.advanceTo(102);
        assertTrue(disconnect(d));
        assertFalse(game.participants().contains(d));
        assertNull(game.activeChallenge());
        int hits = runtime.totalHits();
        runtime.advanceTo(110);
        assertEquals(hits, runtime.totalHits());
        assertEquals(0, runtime.activeTasks());
    }

    @Test void leavingCompletedPlayerImmediatelyRemovesDamageTarget() {
        threeJump();
        runtime.advanceTo(103);
        disconnect(a);
        runtime.advanceTo(104);
        assertEquals(1, runtime.hitCount(a));
        assertEquals(2, runtime.hitCount(d));
    }

    @Test void oneRemainingParticipantStopsGameAndPunishment() {
        game.action(a, SyncAction.JUMP);
        runtime.advanceTo(102);
        disconnect(b);
        disconnect(c);
        disconnect(d);
        assertFalse(game.running());
        assertNull(game.activeChallenge());
        assertEquals(0, runtime.activeTasks());
    }

    @Test void midChallengeJoinBecomesPendingAndSharesPunishment() {
        game.action(a, SyncAction.JUMP);
        UUID extra = UUID.randomUUID();
        runtime.available.add(extra);
        runtime.online.add(extra);
        game.playerConnected(extra);
        assertTrue(game.activeChallenge().pendingPlayers().contains(extra));
        runtime.advanceTo(103);
        assertEquals(1, runtime.hitCount(extra));
    }

    @Test void deadPendingPlayerIsExcludedButRemainsRegisteredForNextChallenge() {
        threeJump();
        runtime.advanceTo(102);
        runtime.available.remove(d);
        game.unavailable(d);
        assertNull(game.activeChallenge());
        assertTrue(game.participants().contains(d));
        runtime.available.add(d);
        runtime.advanceTo(103);
        game.action(a, SyncAction.BLOCK_PLACE);
        assertTrue(game.activeChallenge().pendingPlayers().contains(d));
    }

    @Test void respawnDoesNotRejoinTheChallengeFromWhichDeathRemovedPlayer() {
        game.action(a, SyncAction.JUMP);
        runtime.available.remove(b);
        game.unavailable(b);
        runtime.available.add(b);
        game.action(b, SyncAction.JUMP);
        assertFalse(game.activeChallenge().players().contains(b));
        runtime.advanceTo(102);
        assertEquals(0, runtime.hitCount(b));
    }

    @Test void synchronousDeathsDuringDamageDoNotModifyIterationOrLeaveTasks() {
        game.action(a, SyncAction.JUMP);
        runtime.onDamage = id -> {
            runtime.available.remove(id);
            game.unavailable(id);
        };
        assertDoesNotThrow(() -> runtime.advanceTo(104));
        assertNull(game.activeChallenge());
        assertEquals(0, runtime.activeTasks());
        assertEquals(4, game.participants().size());
    }

    @Test void synchronousStopDuringDamagePreventsFurtherTargetsAndCallbacks() {
        game.action(a, SyncAction.JUMP);
        runtime.onDamage = id -> game.stop();
        runtime.advanceTo(110);
        assertEquals(1, runtime.totalHits());
        assertEquals(0, runtime.activeTasks());
        assertFalse(game.running());
    }

    @Test void synchronousLeaveOfLaterDamageTargetExcludesThemWithinTheSamePulse() {
        threeJump();
        runtime.onDamage = id -> {
            if (id.equals(a)) { disconnect(b); }
        };
        runtime.advanceTo(103);
        assertFalse(game.participants().contains(b));
        assertEquals(0, runtime.hitCount(b));
        assertEquals(1, runtime.hitCount(c));
        assertEquals(1, runtime.hitCount(d));
    }

    @Test void stopPreservesEnrollmentAndCancelsAllTemporaryState() {
        threeJump();
        runtime.advanceTo(102);
        game.stop();
        int hits = runtime.totalHits();
        runtime.advanceTo(110);
        assertEquals(hits, runtime.totalHits());
        assertEquals(4, game.participants().size());
        assertEquals(0, runtime.activeTasks());
        assertNull(game.activeChallenge());
        assertTrue(game.start());
    }

    @Test void staleDeadlineCannotPunishNewGameOrNewChallenge() {
        game.action(a, SyncAction.JUMP);
        Runnable oldCallback = runtime.tasks.getFirst().callback;
        game.stop();
        game.start();
        game.action(b, SyncAction.BLOCK_PLACE);
        ActiveSyncChallenge current = game.activeChallenge();
        oldCallback.run();
        assertSame(current, game.activeChallenge());
        assertEquals(ChallengeState.SYNCING, current.state());
        assertEquals(0, runtime.totalHits());
    }

    @Test void reloadPreservesActiveToleranceDamageIntervalAndRequiredAction() {
        threeJump();
        game.settings(new GameSettings(20, 9, 5, Set.of()));
        runtime.advanceTo(103);
        assertEquals(List.of(1.0), runtime.hits.get(a));
        game.action(d, SyncAction.JUMP); // Must still accept the now disabled action as a response.
        assertNull(game.activeChallenge());
        runtime.advanceTo(104);
        game.action(a, SyncAction.JUMP);
        assertNull(game.activeChallenge());
    }

    @Test void configuredDamageIntervalIsUsedWithoutChangingDefault() {
        game.settings(settings(2, 2.5, 3));
        threeJump();
        runtime.advanceTo(104);
        assertEquals(0, runtime.totalHits());
        runtime.advanceTo(105);
        assertEquals(List.of(2.5), runtime.hits.get(a));
        runtime.advanceTo(108);
        assertEquals(List.of(2.5, 2.5), runtime.hits.get(a));
    }

    @Test void shutdownClearsParticipantsTasksAndRejectsFurtherActions() {
        threeJump();
        runtime.advanceTo(102);
        game.shutdown();
        int hits = runtime.totalHits();
        runtime.advanceTo(110);
        game.action(d, SyncAction.JUMP);
        assertEquals(hits, runtime.totalHits());
        assertTrue(game.participants().isEmpty());
        assertFalse(game.running());
        assertEquals(0, runtime.activeTasks());
    }

    @Test void duplicateMultiShotCallbacksAfterSuccessCannotStartAnotherChallenge() {
        for (UUID id : game.participants()) { game.action(id, SyncAction.ITEM_USE); }
        game.action(d, SyncAction.ITEM_USE);
        game.action(d, SyncAction.ITEM_USE);
        assertNull(game.activeChallenge());
        assertEquals(List.of(true), runtime.outcomes);
    }

    @Test void nonparticipantAndUnavailablePlayersCannotTriggerChallenges() {
        UUID outsider = UUID.randomUUID();
        runtime.available.add(outsider);
        game.action(outsider, SyncAction.JUMP);
        runtime.available.remove(a);
        game.action(a, SyncAction.JUMP);
        assertNull(game.activeChallenge());
    }

    @Test void startRequiresAtLeastTwoEligiblePlayers() {
        game.stop();
        runtime.available.retainAll(Set.of(a));
        assertFalse(game.start());
        assertFalse(game.running());
    }

    @Test void repeatedJoinAndLeaveAreIdempotent() {
        assertFalse(game.playerConnected(a));
        assertTrue(disconnect(a));
        assertFalse(disconnect(a));
        assertEquals(3, game.participants().size());
    }

    @Test void restartClearsExistingPunishmentWithoutGhostDamage() {
        threeJump();
        runtime.advanceTo(102);
        assertTrue(game.start());
        int hits = runtime.totalHits();
        runtime.advanceTo(110);
        assertEquals(hits, runtime.totalHits());
        assertNull(game.activeChallenge());
        assertEquals(0, runtime.activeTasks());
    }

    @Test void startAutomaticallyIncludesAllOnlinePlayersWithoutEnrollment() {
        assertEquals(runtime.online, game.participants());
        UUID late = UUID.randomUUID();
        runtime.online.add(late);
        runtime.available.add(late);
        assertTrue(game.start());
        assertTrue(game.participants().contains(late));
    }

    @Test void rosterRefreshRemovesOfflinePlayersAndTheirChallengeState() {
        threeJump();
        runtime.online.remove(d);
        runtime.available.remove(d);
        game.synchronizeParticipants();
        assertFalse(game.participants().contains(d));
        assertNull(game.activeChallenge());
    }

    @Test void threeSecondPenaltyHitsEveryoneOnlyAtTicks162And222() {
        game.settings(settings(2, 1, 60));
        threeJump();
        runtime.advanceTo(161);
        assertEquals(ChallengeState.PUNISHING, game.activeChallenge().state());
        assertEquals(0, runtime.totalHits());
        runtime.advanceTo(162);
        for (UUID id : game.participants()) { assertEquals(1, runtime.hitCount(id)); }
        runtime.advanceTo(221);
        assertEquals(4, runtime.totalHits());
        runtime.advanceTo(222);
        for (UUID id : game.participants()) { assertEquals(List.of(1.0, 1.0), runtime.hits.get(id)); }
        game.action(d, SyncAction.JUMP);
        runtime.advanceTo(400);
        assertEquals(8, runtime.totalHits());
    }

    @Test void latestPenaltyHitsEveryoneForOnePointFiveEveryTwentyTicks() {
        game.settings(settings(2, 3.0, 20));
        threeJump();
        runtime.advanceTo(121);
        assertEquals(0, runtime.totalHits());
        runtime.advanceTo(122);
        for (UUID id : game.participants()) { assertEquals(List.of(3.0), runtime.hits.get(id)); }
        runtime.advanceTo(141);
        assertEquals(4, runtime.totalHits());
        runtime.advanceTo(142);
        for (UUID id : game.participants()) { assertEquals(List.of(3.0, 3.0), runtime.hits.get(id)); }
        game.action(d, SyncAction.JUMP);
        runtime.advanceTo(300);
        assertEquals(8, runtime.totalHits());
    }

    @Test void repeatedFailuresRecoveredBeforeThreeSecondsCannotSpamImmediateDamage() {
        game.settings(settings(2, 1, 60));
        threeJump();
        runtime.advanceTo(120);
        game.action(d, SyncAction.JUMP);
        runtime.advanceTo(121);
        threeJump();
        runtime.advanceTo(150);
        game.action(d, SyncAction.JUMP);
        runtime.advanceTo(250);
        assertEquals(List.of(false, true, false, true), runtime.outcomes);
        assertEquals(0, runtime.totalHits());
    }

    private static final MovementInput FORWARD = new MovementInput(true, false, false, false);
    private static final MovementInput RIGHT = new MovementInput(false, false, false, true);

    private void move(UUID player, MovementInput input) {
        runtime.inputs.put(player, input);
        game.movement(player, input);
    }

    @Test void matchingForwardInputsAtTicks100And101Succeed() {
        move(a, FORWARD);
        move(b, FORWARD);
        runtime.advanceTo(101);
        move(c, FORWARD);
        move(d, FORWARD);
        assertNull(game.activeChallenge());
        assertEquals(List.of(true), runtime.outcomes);
        assertEquals(0, runtime.totalHits());
    }

    @Test void differentDirectionsAreAllowedWhenEveryoneIsMoving() {
        move(a, FORWARD);
        move(b, RIGHT);
        move(c, new MovementInput(false, true, false, false));
        move(d, new MovementInput(false, false, true, false));
        assertNull(game.activeChallenge());
        assertEquals(0, runtime.totalHits());
    }

    @Test void diagonalAndStraightMovementHaveTheSameMovingState() {
        MovementInput diagonal = new MovementInput(true, false, true, false);
        move(a, diagonal);
        move(b, FORWARD);
        assertTrue(game.activeChallenge().completedPlayers().contains(b));
        move(b, diagonal);
        move(c, diagonal);
        move(d, diagonal);
        assertNull(game.activeChallenge());
    }

    @Test void stoppingMustAlsoBeSynchronizedAndHeldKeysDoNotCreateRepeatedChallenges() {
        for (UUID id : List.of(a, b, c, d)) { move(id, FORWARD); }
        runtime.advanceTo(101);
        for (UUID id : List.of(a, b, c, d)) { move(id, FORWARD); }
        assertNull(game.activeChallenge());
        move(a, MovementInput.NONE);
        assertEquals(MovementInput.NONE, game.activeChallenge().movementInput());
        for (UUID id : List.of(b, c, d)) { move(id, MovementInput.NONE); }
        assertNull(game.activeChallenge());
        assertEquals(List.of(true, true), runtime.outcomes);
    }

    @Test void alreadyHeldMatchingInputIsCountedWhenAnotherPlayerStartsMoving() {
        game.stop();
        for (UUID id : List.of(b, c, d)) { runtime.inputs.put(id, FORWARD); }
        game.start();
        move(a, FORWARD);
        assertNull(game.activeChallenge());
        assertEquals(List.of(true), runtime.outcomes);
    }

    @Test void completedMoverChangingDirectionRemainsCompleted() {
        move(a, FORWARD);
        move(b, FORWARD);
        move(a, RIGHT);
        move(c, FORWARD);
        move(d, FORWARD);
        assertNull(game.activeChallenge());
    }

    @Test void movementFailureDamagesWholeGroupAfterThreeSecondsAndLateMatchStopsIt() {
        game.settings(settings(2, 1, 60));
        for (UUID id : List.of(a, b, c)) { move(id, FORWARD); }
        move(d, MovementInput.NONE);
        runtime.advanceTo(162);
        for (UUID id : game.participants()) { assertEquals(1, runtime.hitCount(id)); }
        move(d, RIGHT);
        runtime.advanceTo(300);
        assertEquals(4, runtime.totalHits());
        assertNull(game.activeChallenge());
    }

    @Test void movementDuringOtherChallengeDoesNotCreateParallelChallenge() {
        game.action(a, SyncAction.JUMP);
        ActiveSyncChallenge current = game.activeChallenge();
        move(b, FORWARD);
        assertSame(current, game.activeChallenge());
        assertEquals(SyncAction.JUMP, current.action());
    }

    @Test void disabledMovementDoesNotCreateChallengeButReloadStillAllowsCurrentResponse() {
        move(a, FORWARD);
        Set<SyncAction> enabled = EnumSet.allOf(SyncAction.class);
        enabled.remove(SyncAction.MOVE);
        game.settings(new GameSettings(2, 1, 60, enabled));
        for (UUID id : List.of(b, c, d)) { move(id, FORWARD); }
        assertNull(game.activeChallenge());
        move(a, RIGHT);
        assertNull(game.activeChallenge());
    }

    @Test void unchangedDirectionalInputDoesNotTurnJumpOnlyPacketsIntoMovementChallenge() {
        move(a, MovementInput.NONE);
        assertNull(game.activeChallenge());
        assertTrue(runtime.outcomes.isEmpty());
    }

    @Test void resumingMovementClearsAnOldStopChallengeInsteadOfGettingStuck() {
        for (UUID id : List.of(a, b, c, d)) { move(id, FORWARD); }
        runtime.advanceTo(101);
        move(a, MovementInput.NONE);
        assertNotNull(game.activeChallenge());
        move(a, FORWARD);
        assertNull(game.activeChallenge());
        assertEquals(0, runtime.activeTasks());
    }

    @Test void everyoneStoppingAlsoClearsAnOldWalkingChallenge() {
        move(a, FORWARD);
        runtime.advanceTo(103);
        move(a, MovementInput.NONE);
        assertNull(game.activeChallenge());
        assertEquals(0, runtime.activeTasks());
    }

    @Test void snapshotObservesWholeFrameWithoutIterationOrderFailures() {
        game.movementSnapshot(java.util.Map.of(a, true, b, true, c, true, d, true));
        assertNull(game.activeChallenge());
        assertTrue(runtime.outcomes.isEmpty());
        game.movementSnapshot(java.util.Map.of(a, false, b, true, c, true, d, true));
        assertNotNull(game.activeChallenge());
        runtime.advanceTo(103);
        game.movementSnapshot(java.util.Map.of(a, true, b, true, c, true, d, true));
        assertNull(game.activeChallenge());
        assertEquals(0, runtime.activeTasks());
    }

    @Test void persistentMovementMismatchIsDetectedAfterAnotherChallengeEnds() {
        game.action(a, SyncAction.JUMP);
        var mixed = java.util.Map.of(a, true, b, false, c, false, d, false);
        game.movementSnapshot(mixed);
        assertEquals(SyncAction.JUMP, game.activeChallenge().action());
        for (UUID id : List.of(b, c, d)) { game.action(id, SyncAction.JUMP); }
        assertNull(game.activeChallenge());
        game.movementSnapshot(mixed);
        assertEquals(SyncAction.MOVE, game.activeChallenge().action());
    }
}
