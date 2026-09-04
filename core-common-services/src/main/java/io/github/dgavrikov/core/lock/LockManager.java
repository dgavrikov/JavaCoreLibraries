package io.github.dgavrikov.core.lock;

public interface LockManager<T extends Enum<T>> {
    /**
     * Acquires a lock. If it is already held, blocks and waits until it is released.
     *
     * @param lockType the type of the lock.
     * @param key      the unique lock key.
     * @return The lock object. If successfully acquired, status is set to RECEIVED,
     *         otherwise, if the acquisition failed, status is set to RELEASED.
     */
    LockObject<T> keyLock(T lockType, String key);

    /**
     * Attempts to acquire a lock immediately without waiting for it to be released.
     * If the lock is already held by another process, the returned status is set to EXISTS.
     *
     * @param lockType the type of the lock.
     * @param key      the unique lock key.
     * @return The lock object. If successfully acquired, status is set to RECEIVED,
     *         otherwise, if it is already taken, status is set to EXISTS.
     */
    LockObject<T> keyLockImmediately(T lockType, String key);

    /**
     * Releases the specified lock.
     *
     * @param object the lock object to be released.
     */
    void releaseLock(LockObject<T> object);
}
