package es.in2.desmos.domain.services.api.impl;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ReactiveMutexTest {

    // Hand-off on the calling thread, so the tests are deterministic
    private final ReactiveMutex mutex = new ReactiveMutex(Schedulers.immediate());

    @Test
    void itShouldGrantThePermitsInArrivalOrder() {
        Sinks.Empty<Void> release = Sinks.empty();
        List<Integer> order = new CopyOnWriteArrayList<>();
        mutex.withPermit(release.asMono()).subscribe();

        List<Disposable> waiters = IntStream.range(0, 5)
                .mapToObj(i -> mutex.withPermit(Mono.fromRunnable(() -> order.add(i))).subscribe())
                .toList();
        assertThat(mutex.queued()).isEqualTo(5);
        assertThat(order).isEmpty();

        release.tryEmitEmpty();

        assertThat(order).containsExactly(0, 1, 2, 3, 4);
        assertThat(mutex.isHeld()).isFalse();
        assertThat(waiters).allMatch(Disposable::isDisposed);
    }

    @Test
    void itShouldRunOnlyOneWorkAtATime() {
        ReactiveMutex parallelMutex = new ReactiveMutex();
        AtomicBoolean inside = new AtomicBoolean();
        List<Boolean> overlaps = new CopyOnWriteArrayList<>();
        Mono<Void> work = Mono.fromRunnable(() -> overlaps.add(!inside.compareAndSet(false, true)))
                .then(Mono.delay(Duration.ofMillis(2)))
                .doOnTerminate(() -> inside.set(false))
                .then();

        StepVerifier.create(reactor.core.publisher.Flux.range(0, 50)
                        .flatMap(i -> parallelMutex.withPermit(work.subscribeOn(Schedulers.parallel())), 50)
                        .then())
                .expectComplete()
                .verify(Duration.ofSeconds(10));

        assertThat(overlaps).hasSize(50).containsOnly(false);
    }

    @Test
    void itShouldRemoveACancelledWaiterFromTheQueueAndNeverRunItsWork() {
        Sinks.Empty<Void> release = Sinks.empty();
        AtomicBoolean cancelledWorkRan = new AtomicBoolean();
        mutex.withPermit(release.asMono()).subscribe();

        Disposable waiter = mutex.withPermit(Mono.fromRunnable(() -> cancelledWorkRan.set(true))).subscribe();
        assertThat(mutex.queued()).isEqualTo(1);
        waiter.dispose();
        assertThat(mutex.queued()).isZero();

        release.tryEmitEmpty();

        assertThat(cancelledWorkRan).isFalse();
        assertThat(mutex.isHeld()).isFalse();
    }

    @Test
    void itShouldReleaseThePermitAfterSuccessErrorAndCancellation() {
        StepVerifier.create(mutex.withPermit(Mono.just(1))).expectNext(1).verifyComplete();
        assertThat(mutex.isHeld()).isFalse();

        StepVerifier.create(mutex.withPermit(Mono.error(new IllegalStateException("boom"))))
                .expectError(IllegalStateException.class)
                .verify();
        assertThat(mutex.isHeld()).isFalse();

        Disposable running = mutex.withPermit(Mono.never()).subscribe();
        assertThat(mutex.isHeld()).isTrue();
        running.dispose();
        assertThat(mutex.isHeld()).isFalse();

        StepVerifier.create(mutex.withPermit(Mono.just(2))).expectNext(2).verifyComplete();
    }

    @Test
    void itShouldPassThePermitOnWhenTheHolderIsCancelled() {
        Disposable holder = mutex.withPermit(Mono.never()).subscribe();
        List<String> ran = new CopyOnWriteArrayList<>();
        mutex.withPermit(Mono.fromRunnable(() -> ran.add("next"))).subscribe();

        holder.dispose();

        assertThat(ran).containsExactly("next");
        assertThat(mutex.isHeld()).isFalse();
    }

    @Test
    void itShouldWaitWithoutBlockingTheSubscribingThread() {
        Sinks.Empty<Void> release = Sinks.empty();
        mutex.withPermit(release.asMono()).subscribe();
        AtomicBoolean waiterDone = new AtomicBoolean();

        // On a single-thread scheduler: if waiting blocked the thread, the second task could never run
        var single = Schedulers.newSingle("mutex-test");
        try {
            mutex.withPermit(Mono.fromRunnable(() -> waiterDone.set(true)))
                    .subscribeOn(single)
                    .subscribe();
            StepVerifier.create(Mono.fromCallable(() -> "free").subscribeOn(single))
                    .expectNext("free")
                    .expectComplete()
                    .verify(Duration.ofSeconds(5));
            assertThat(waiterDone).isFalse();

            release.tryEmitEmpty();
            await().atMost(Duration.ofSeconds(5)).untilTrue(waiterDone);
        } finally {
            single.dispose();
        }
    }

    // A waiter cancelled right when the permit is handed to it must not keep it
    @RepeatedTest(200)
    void itShouldNeverLoseThePermitWhenAWaiterIsCancelledWhileItIsGranted() throws InterruptedException {
        ReactiveMutex racyMutex = new ReactiveMutex(Schedulers.parallel());
        Sinks.Empty<Void> release = Sinks.empty();
        racyMutex.withPermit(release.asMono()).subscribe();
        Disposable waiter = racyMutex.withPermit(Mono.delay(Duration.ofMillis(1)).then()).subscribe();

        CountDownLatch start = new CountDownLatch(1);
        Thread releaser = new Thread(() -> {
            awaitQuietly(start);
            release.tryEmitEmpty();
        });
        Thread canceller = new Thread(() -> {
            awaitQuietly(start);
            waiter.dispose();
        });
        releaser.start();
        canceller.start();
        start.countDown();
        releaser.join();
        canceller.join();

        StepVerifier.create(racyMutex.withPermit(Mono.just("acquired")))
                .expectNext("acquired")
                .expectComplete()
                .verify(Duration.ofSeconds(5));
        await().atMost(Duration.ofSeconds(5)).until(() -> !racyMutex.isHeld());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
