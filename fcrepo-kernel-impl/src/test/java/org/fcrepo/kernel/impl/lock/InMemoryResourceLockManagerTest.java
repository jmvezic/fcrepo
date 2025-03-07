/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.kernel.impl.lock;

import org.fcrepo.kernel.api.exception.ConcurrentUpdateException;
import org.fcrepo.kernel.api.identifiers.FedoraId;
import org.fcrepo.kernel.api.lock.ResourceLockManager;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @author pwinckles
 */
public class InMemoryResourceLockManagerTest {

    private ResourceLockManager lockManager;

    private static ExecutorService executor;

    private String txId1;
    private String txId2;
    private FedoraId resourceId;

    @BeforeClass
    public static void beforeClass() {
        executor = Executors.newCachedThreadPool();
    }

    @AfterClass
    public static void afterClass() {
        executor.shutdown();
    }

    @Before
    public void setup() {
        lockManager = new InMemoryResourceLockManager();
        txId1 = UUID.randomUUID().toString();
        txId2 = UUID.randomUUID().toString();
        resourceId = randomResourceId();
    }

    @Test
    public void shouldLockResourceWhenNotAlreadyLocked() {
        lockManager.acquire(txId1, resourceId);
    }

    @Test
    public void sameTxShouldBeAbleToReacquireLockItAlreadyHolds() {
        lockManager.acquire(txId1, resourceId);
        lockManager.acquire(txId1, resourceId);
    }

    @Test
    public void shouldFailToAcquireLockWhenHeldByAnotherTx() {
        lockManager.acquire(txId1, resourceId);
        assertLockException(() -> {
            lockManager.acquire(txId2, resourceId);
        });
    }

    @Test
    public void shouldAcquireLockAfterReleasedByAnotherTx() {
        lockManager.acquire(txId1, resourceId);
        lockManager.releaseAll(txId1);
        lockManager.acquire(txId2, resourceId);
    }

    @Test
    public void concurrentRequestsFromSameTxShouldBothSucceedWhenLockAvailable()
            throws ExecutionException, InterruptedException {
        final var phaser = new Phaser(3);

        final var future1 = executor.submit(() -> {
            phaser.arriveAndAwaitAdvance();
            lockManager.acquire(txId1, resourceId);
            return true;
        });
        final var future2 = executor.submit(() -> {
            phaser.arriveAndAwaitAdvance();
            lockManager.acquire(txId1, resourceId);
            return true;
        });

        phaser.arriveAndAwaitAdvance();

        assertTrue(future1.get());
        assertTrue(future2.get());
    }

    @Test
    public void concurrentRequestsFromDifferentTxesOnlyOneShouldSucceed()
            throws ExecutionException, InterruptedException {
        final var phaser = new Phaser(3);

        final var future1 = executor.submit(() -> {
            phaser.arriveAndAwaitAdvance();
            try {
                lockManager.acquire(txId1, resourceId);
                return true;
            } catch (ConcurrentUpdateException e) {
                return false;
            }
        });
        final var future2 = executor.submit(() -> {
            phaser.arriveAndAwaitAdvance();
            try {
                lockManager.acquire(txId2, resourceId);
                return true;
            } catch (ConcurrentUpdateException e) {
                return false;
            }
        });

        phaser.arriveAndAwaitAdvance();

        if (future1.get()) {
            assertFalse("Only one tx should have acquired a lock", future2.get());
        } else {
            assertTrue("Only one tx should have acquired a lock", future2.get());
        }
    }

    @Test
    public void releasingAlreadyReleasedLocksShouldDoNothing() {
        lockManager.acquire(txId1, resourceId);
        lockManager.releaseAll(txId1);
        lockManager.releaseAll(txId1);
        lockManager.acquire(txId2, resourceId);
    }

    private void assertLockException(final Runnable runnable) {
        try {
            runnable.run();
            fail("acquire should have thrown an exception");
        } catch (ConcurrentUpdateException e) {
            // expected exception
        }
    }

    private FedoraId randomResourceId() {
        return FedoraId.create(UUID.randomUUID().toString());
    }

}
