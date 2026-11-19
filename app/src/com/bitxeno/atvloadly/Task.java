package com.bitxeno.atvloadly;

/**
 * One-shot background job that runs a {@link Job} off the main thread and
 * delivers its result back on the main thread.
 *
 * Keeps each panel's network calls small and avoids leaking a reference to the
 * Activity past its lifetime: callbacks are dropped once the job is cancelled.
 * Use the static {@link #run} factory; the constructor is private.
 */
public final class Task<T> {

    /** The background work. Runs on a worker thread; must not touch the UI. */
    public interface Job<T> {
        T run() throws Exception;
    }

    /** Consumer of the finished result, invoked on the main thread. */
    public interface Done<T> {
        void onDone(T result);
    }

    private final Thread thread;
    private volatile boolean cancelled = false;

    private Task(Thread thread) {
        this.thread = thread;
    }

    /** Start a job that yields a result to done on the main thread. */
    public static <T> Task<T> run(final Job<T> job, final Done<T> done) {
        final Task<T>[] holder = new Task[1];
        Thread t = new Thread(new Runnable() {
            public void run() {
                T result = null;
                try {
                    result = job.run();
                } catch (Exception e) {
                    // Surface the failure as the result; jobs report their own
                    // error values so the UI can decide how to show them.
                    android.util.Log.w("atvloadly.Task", "job failed: " + e);
                }
                final T r = result;
                if (holder[0] != null && holder[0].cancelled) return;
                App.MAIN.post(new Runnable() {
                    public void run() {
                        if (holder[0] != null && holder[0].cancelled) return;
                        done.onDone(r);
                    }
                });
            }
        }, "atvloadly-task");
        Task<T> task = new Task<T>(t);
        holder[0] = task;
        t.start();
        return task;
    }

    /** Cancel delivery of the result (the worker thread still runs to completion). */
    public void cancel() {
        cancelled = true;
    }
}
