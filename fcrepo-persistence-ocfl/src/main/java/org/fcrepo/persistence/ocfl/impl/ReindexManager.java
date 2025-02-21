/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.persistence.ocfl.impl;

import static org.slf4j.LoggerFactory.getLogger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.fcrepo.common.db.DbTransactionExecutor;
import org.fcrepo.config.OcflPropsConfig;
import org.fcrepo.kernel.api.Transaction;
import org.fcrepo.kernel.api.TransactionManager;

import org.slf4j.Logger;

/**
 * Class to coordinate the index rebuilding tasks.
 * @author whikloj
 * @since 6.0.0
 */
public class ReindexManager {

    private static final Logger LOGGER = getLogger(ReindexManager.class);

    private static final long REPORTING_INTERVAL_SECS = 300;

    private final List<ReindexWorker> workers;

    private final Iterator<String> ocflIter;

    private final Stream<String> ocflStream;

    private final AtomicInteger completedCount;

    private final AtomicInteger errorCount;

    private final ReindexService reindexService;

    private final long batchSize;

    private final boolean failOnError;

    private TransactionManager txManager;

    private DbTransactionExecutor dbTransactionExecutor;

    private Transaction transaction = null;

    /**
     * Basic constructor
     * @param ids stream of ocfl ids.
     * @param reindexService the reindexing service.
     * @param config OCFL property config object.
     * @param manager the transaction manager object.
     * @param dbTransactionExecutor manages db transactions
     */
    public ReindexManager(final Stream<String> ids,
                          final ReindexService reindexService,
                          final OcflPropsConfig config,
                          final TransactionManager manager,
                          final DbTransactionExecutor dbTransactionExecutor) {
        this.ocflStream = ids;
        this.ocflIter = ocflStream.iterator();
        this.reindexService = reindexService;
        this.batchSize = config.getReindexBatchSize();
        this.failOnError = config.isReindexFailOnError();
        txManager = manager;
        this.dbTransactionExecutor = dbTransactionExecutor;
        workers = new ArrayList<>();
        completedCount = new AtomicInteger(0);
        errorCount = new AtomicInteger(0);

        final var workerCount = config.getReindexingThreads();

        if (workerCount < 1) {
            throw new IllegalStateException(String.format("Reindexing requires at least 1 thread. Found: %s",
                    workerCount));
        }

        for (var i = 0; i < workerCount; i += 1) {
            workers.add(new ReindexWorker("ReindexWorker-" + i, this,
                    this.reindexService, txManager, this.dbTransactionExecutor, this.failOnError));
        }
    }

    /**
     * Start reindexing.
     * @throws InterruptedException on an indexing error in a thread.
     */
    public void start() throws InterruptedException {
        final var reporter = startReporter();
        try {
            workers.forEach(ReindexWorker::start);
            for (final var worker : workers) {
                worker.join();
            }
            if (!failOnError || errorCount.get() == 0) {
                indexMembership();
            } else {
                LOGGER.error("Reindex did not complete successfully");
            }
        } catch (final Exception e) {
            LOGGER.error("Error while rebuilding index", e);
            stop();
            throw e;
        } finally {
            reporter.interrupt();
        }
    }

    /**
     * Stop all threads.
     */
    public void stop() {
        LOGGER.debug("Stop worker threads");
        workers.forEach(ReindexWorker::stopThread);
    }

    /**
     * Return a batch of OCFL ids to reindex.
     * @return list of OCFL ids.
     */
    public synchronized List<String> getIds() {
        int counter = 0;
        final List<String> ids = new ArrayList<>((int) batchSize);
        while (ocflIter.hasNext() && counter < batchSize) {
            ids.add(ocflIter.next());
            counter += 1;
        }
        return ids;
    }

    /**
     * Update the master list of reindexing states.
     * @param batchSuccessful how many items were completed successfully in the last batch.
     * @param batchErrors how many items had an error in the last batch.
     */
    public void updateComplete(final int batchSuccessful, final int batchErrors) {
        completedCount.addAndGet(batchSuccessful);
        errorCount.addAndGet(batchErrors);
    }

    /**
     * @return the count of items that completed successfully.
     */
    public int getCompletedCount() {
        return completedCount.get();
    }

    /**
     * @return the count of items that had errors.
     */
    public int getErrorCount() {
        return errorCount.get();
    }

    /**
     * Index the membership relationships
     */
    private void indexMembership() {
        final var tx = transaction();
        LOGGER.info("Starting membership indexing");
        reindexService.indexMembership(tx);
        tx.commit();
        LOGGER.debug("Completed membership indexing");
    }

    /**
     * Close stream.
     */
    public void shutdown() {
        ocflStream.close();
    }

    private Thread startReporter() {
        final var reporter = new Thread(() -> {
            final var startTime = Instant.now();
            try {
                while (true) {
                    TimeUnit.SECONDS.sleep(REPORTING_INTERVAL_SECS);
                    final var complete = completedCount.get();
                    final var errored = errorCount.get();
                    final var now = Instant.now();
                    final var duration = Duration.between(startTime, now);
                    LOGGER.info("Index rebuild progress: Complete: {}; Errored: {}; Time: {}; Rate: {}/s",
                            complete, errored, getDurationMessage(duration),
                            (complete + errored) / duration.getSeconds());
                }
            } catch (final InterruptedException e) {
                // processing has completed exit normally
            }
        });

        reporter.start();
        return reporter;
    }

    private String getDurationMessage(final Duration duration) {
        String message = String.format("%d secs", duration.toSecondsPart());
        if (duration.getSeconds() > 60) {
            message = String.format("%d mins, ", duration.toMinutesPart()) + message;
        }
        if (duration.getSeconds() > 3600) {
            message = String.format("%d hours, ", duration.getSeconds() / 3600) + message;
        }
        return message;
    }

    private Transaction transaction() {
        if (transaction == null) {
            transaction = txManager.create();
            transaction.setShortLived(true);
        }
        return transaction;
    }
}
