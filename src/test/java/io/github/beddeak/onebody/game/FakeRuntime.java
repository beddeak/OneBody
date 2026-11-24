package io.github.beddeak.onebody.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

final class FakeRuntime implements GameRuntime {
    long tick = 100;
    final Set<UUID> available = new HashSet<>();
    final Set<UUID> online = new HashSet<>();
    final Map<UUID, MovementInput> inputs = new HashMap<>();
    final List<FakeTask> tasks = new ArrayList<>();
    final Map<UUID, List<Double>> hits = new HashMap<>();
    final List<Boolean> outcomes = new ArrayList<>();
    int starts;
    int stops;
    int punishmentBars;
    Consumer<UUID> onDamage = id -> { };

    final class FakeTask implements Task {
        final Runnable callback;
        final long interval;
        long due;
        boolean cancelled;

        FakeTask(Runnable callback, long delay, long interval) {
            this.callback = callback;
            due = tick + delay;
            this.interval = interval;
        }

        @Override public void cancel() { cancelled = true; }
    }

    @Override public long currentTick() { return tick; }
    @Override public Set<UUID> onlinePlayers() { return Set.copyOf(online); }
    @Override public MovementInput movementInput(UUID id) { return inputs.getOrDefault(id, MovementInput.NONE); }
    @Override public boolean eligible(UUID player) { return available.contains(player); }

    @Override public Task later(Runnable callback, long delayTicks) { return schedule(callback, delayTicks, 0); }
    @Override public Task repeat(Runnable callback, long delayTicks, long intervalTicks) {
        return schedule(callback, delayTicks, intervalTicks);
    }

    private Task schedule(Runnable callback, long delay, long interval) {
        FakeTask task = new FakeTask(callback, delay, interval);
        tasks.add(task);
        return task;
    }

    void advanceTo(long target) {
        while (tick < target) {
            tick++;
            runDue();
        }
    }

    void runDue() {
        for (FakeTask task : List.copyOf(tasks)) {
            if (!task.cancelled && task.due <= tick) {
                if (task.interval == 0) { task.cancelled = true; }
                else { task.due = tick + task.interval; }
                task.callback.run();
            }
        }
    }

    long activeTasks() { return tasks.stream().filter(task -> !task.cancelled).count(); }
    int hitCount(UUID id) { return hits.getOrDefault(id, List.of()).size(); }
    int totalHits() { return hits.values().stream().mapToInt(List::size).sum(); }
    @Override public void damage(UUID player, double amount) {
        hits.computeIfAbsent(player, id -> new ArrayList<>()).add(amount);
        onDamage.accept(player);
    }
    @Override public void started(Set<UUID> players) { starts++; }
    @Override public void stopped(Set<UUID> players) { stops++; }
    @Override public void outcome(Set<UUID> players, boolean success) { outcomes.add(success); }
    @Override public void punishmentBar(ActiveSyncChallenge challenge) { punishmentBars++; }
    @Override public void clearBars(Set<UUID> players) { }
}
