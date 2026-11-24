package io.github.beddeak.onebody.game;

/** Movement state; the original directional fields remain for server input compatibility. */
public record MovementInput(boolean forward, boolean backward, boolean left, boolean right) {
    public static final MovementInput NONE = new MovementInput(false, false, false, false);
    public static final MovementInput MOVING = new MovementInput(true, false, false, false);

    public boolean moving() { return forward || backward || left || right; }
    public MovementInput normalized() { return moving() ? MOVING : NONE; }

    public String display() {
        return moving() ? "이동 중" : "정지";
    }
}
