package com.github.cuixudong74.usagebillingpipelinejava.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * 账户级锁：出账/更正按账户串行，不同账户互不阻塞。
 * 数据库唯一约束与乐观锁作为跨实例与最终一致性的兜底。
 */
@Component
public class AccountLockManager {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public void runLocked(String accountId, Runnable action) {
        ReentrantLock lock = locks.computeIfAbsent(accountId, k -> new ReentrantLock());
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    public <T> T callLocked(String accountId, java.util.concurrent.Callable<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(accountId, k -> new ReentrantLock());
        lock.lock();
        try {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            lock.unlock();
        }
    }
}
