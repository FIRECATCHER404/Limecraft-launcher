package com.limecraft.launcher.core;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

public final class LauncherJobExecutor extends ThreadPoolExecutor {
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final AtomicInteger activeTasks = new AtomicInteger();
    private volatile IntConsumer activityListener = count -> {
    };

    public LauncherJobExecutor(int threads) {
        super(
                threads,
                threads,
                30L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                createThreadFactory()
        );
    }

    public void setActivityListener(IntConsumer activityListener) {
        this.activityListener = activityListener == null ? count -> {
        } : activityListener;
        notifyActivity();
    }

    public int activeOrQueuedJobs() {
        return activeTasks.get() + getQueue().size();
    }

    @Override
    public void execute(Runnable command) {
        super.execute(command);
        notifyActivity();
    }

    @Override
    protected void beforeExecute(Thread thread, Runnable runnable) {
        super.beforeExecute(thread, runnable);
        activeTasks.incrementAndGet();
        notifyActivity();
    }

    @Override
    protected void afterExecute(Runnable runnable, Throwable throwable) {
        try {
            super.afterExecute(runnable, throwable);
        } finally {
            activeTasks.updateAndGet(value -> Math.max(0, value - 1));
            notifyActivity();
        }
    }

    private void notifyActivity() {
        try {
            activityListener.accept(activeOrQueuedJobs());
        } catch (Exception ignored) {
        }
    }

    private static ThreadFactory createThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "limecraft-io-" + THREAD_COUNTER.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
