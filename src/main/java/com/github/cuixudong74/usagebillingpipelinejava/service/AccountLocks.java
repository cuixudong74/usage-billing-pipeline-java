package com.github.cuixudong74.usagebillingpipelinejava.service;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

@Component
public class AccountLocks {
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ReentrantLock forAccount(String accountId) {
        return locks.computeIfAbsent(accountId, ignored -> new ReentrantLock());
    }

    public Optional<Thread> owner(String accountId) {
        ReentrantLock lock = locks.get(accountId);
        return lock == null || !lock.isLocked() ? Optional.empty() : Optional.ofNullable(ownerOf(lock));
    }

    private Thread ownerOf(ReentrantLock lock) {
        try {
            var method = ReentrantLock.class.getDeclaredMethod("getOwner");
            method.setAccessible(true);
            return (Thread) method.invoke(lock);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
