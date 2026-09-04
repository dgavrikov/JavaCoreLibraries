package io.github.dgavrikov.core.lock;

import lombok.Getter;

public class LockObject<T extends Enum<T>> implements AutoCloseable {

    private LockManager<T> lockManager;
    @Getter
    private Status status;
    @Getter
    private final T type;
    @Getter
    private final String key;

    public LockObject(LockManager<T> lockManager, T type, String key, Status status) {
        this.status = status;
        this.type = type;
        this.key = key;
        this.lockManager = status == Status.RECEIVED ? lockManager : null;
    }

    @Override
    public void close() throws Exception {
        if (lockManager != null) {
            lockManager.releaseLock(this);
            lockManager = null;
        }
        status = Status.RELEASED;
    }

    public void throwIfLockNotReceived() {
        throwIfLockNotReceived(null);
    }

    public void throwIfLockNotReceived(String details) {
        if (Status.RECEIVED.equals(this.status))
            return;
        throw new RequestLockException(
                "Lock acquisition attempt failed (Type="
                        + this.type + ", Key="
                        + this.key + ", Status="
                        + this.status + "): "
                        + (details == null ? "" : details) + ".");
    }

    public enum Status {
        /**
         * Lock successfully acquired
         */
        RECEIVED,
        /**
         * Lock already exists
         */
        EXISTS,
        /**
         * Lock released
         */
        RELEASED
    }

}
