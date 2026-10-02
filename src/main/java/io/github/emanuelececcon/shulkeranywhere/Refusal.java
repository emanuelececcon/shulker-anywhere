package io.github.emanuelececcon.shulkeranywhere;

/** Why a shulker box can't be opened without placing it. */
enum Refusal {
    STACKED("Only a single shulker box can be opened, not a stack."),
    LOCKED("This shulker box is locked. Place it to open it."),
    LOOT("This shulker box still has to generate its loot. Place it to open it."),
    UNUSUAL("This shulker box has unusual data. Place it to open it.");

    private final String message;

    Refusal(String message) {
        this.message = message;
    }

    String message() {
        return message;
    }
}
