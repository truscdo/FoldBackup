package com.foldbackup.mod.rollback;

/** Localizable result for an in-game chunk rollback operation. */
public record RollbackOutcome(boolean success, String translationKey, Object[] args) {
    public RollbackOutcome {
        args = args == null ? new Object[0] : args.clone();
    }

    @Override
    public Object[] args() {
        return args.clone();
    }
}
