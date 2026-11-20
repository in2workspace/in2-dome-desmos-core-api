package es.in2.desmos.domain.services.api.impl;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.RejectedExecutionException;

/**
 * A reactive mutex with a single permit, granted in arrival order (FIFO).
 * <p>
 * Waiting for the permit holds no thread. A subscriber cancelled while waiting leaves the queue; one cancelled right
 * when the permit is granted to it passes the permit on. The permit is released when the guarded work completes,
 * fails or is cancelled, before that signal goes downstream: what the caller does with the result does not hold the
 * permit. It is not reentrant: guarded work must not ask for the permit again.
 */
public final class ReactiveMutex {

    private enum State { WAITING, GRANTED, DONE }

    private final Object lock = new Object();
    private final Deque<Waiter> queue = new ArrayDeque<>();
    private final Scheduler handOffScheduler;
    private boolean held;

    public ReactiveMutex() {
        this(Schedulers.parallel());
    }

    /**
     * @param handOffScheduler where a queued waiter is resumed when the permit is passed to it. Resuming it on the
     *                         releasing thread would nest every queued work inside the previous one.
     */
    public ReactiveMutex(Scheduler handOffScheduler) {
        this.handOffScheduler = handOffScheduler;
    }

    /**
     * Runs {@code work} holding the permit. {@code work} is subscribed only once the permit is granted.
     */
    public <T> Mono<T> withPermit(Mono<T> work) {
        return Mono.defer(() -> {
            Waiter waiter = new Waiter();
            boolean grantedNow = enqueue(waiter);
            Mono<Void> turn = grantedNow ? Mono.empty() : waiter.granted.asMono();
            // Released before the signal goes downstream (doFinally would run after it): the caller's handling of the
            // result, such as logging every saved row, does not delay the next turn. finish() is idempotent, so a
            // cancellation racing with the terminal signal releases once.
            return turn.then(work)
                    .doOnSuccess(value -> finish(waiter))
                    .doOnError(error -> finish(waiter))
                    .doOnCancel(() -> finish(waiter));
        });
    }

    /** For tests: whether the permit is currently held. */
    boolean isHeld() {
        synchronized (lock) {
            return held;
        }
    }

    /** For tests: subscribers waiting for the permit. */
    int queued() {
        synchronized (lock) {
            return queue.size();
        }
    }

    private boolean enqueue(Waiter waiter) {
        synchronized (lock) {
            if (!held) {
                held = true;
                waiter.state = State.GRANTED;
                return true;
            }
            queue.addLast(waiter);
            return false;
        }
    }

    // Runs on completion, error or cancellation of the guarded chain (also while waiting); only the first call acts
    private void finish(Waiter waiter) {
        Waiter next;
        synchronized (lock) {
            State previous = waiter.state;
            waiter.state = State.DONE;
            if (previous == State.WAITING) {
                queue.remove(waiter);
                return;
            }
            if (previous != State.GRANTED) {
                return;
            }
            next = queue.pollFirst();
            if (next == null) {
                held = false;
                return;
            }
            next.state = State.GRANTED;
        }
        // If next is being cancelled concurrently, its finish() sees GRANTED and passes the permit on
        try {
            handOffScheduler.schedule(next.granted::tryEmitEmpty);
        } catch (RejectedExecutionException e) {
            // Scheduler disposed (shutdown): resume next here rather than lose the permit
            next.granted.tryEmitEmpty();
        }
    }

    private static final class Waiter {
        private final Sinks.Empty<Void> granted = Sinks.empty();
        private State state = State.WAITING; // guarded by ReactiveMutex.lock
    }
}
