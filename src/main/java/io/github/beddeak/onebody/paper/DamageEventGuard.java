package io.github.beddeak.onebody.paper;

/** Main-thread scope for damage initiated by this plugin's punishment. */
public final class DamageEventGuard {
    private int depth;

    public boolean active() { return depth > 0; }

    public void runPenalty(Runnable damage) {
        depth++;
        try {
            damage.run();
        } finally {
            depth--;
        }
    }
}
