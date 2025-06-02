package pool;

import java.util.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

public class CustomThreadPool implements CustomExecutor {
    private static final Logger logger = Logger.getLogger(CustomThreadPool.class.getName());

    private final int corePoolSize;
    private final int maxPoolSize;
    private final long keepAliveTime;
    private final TimeUnit timeUnit;
    private final int queueSize;
    private final int minSpareThreads;
    private boolean areWorkersInitialized = false;

    private final List<Worker> workers;
    private final List<BlockingQueue<Runnable>> queues;
    private final ThreadFactory threadFactory;
    private final RejectedExecutionHandler rejectionHandler;

    private volatile boolean isShutdown = false;
    private final ReentrantLock mainLock = new ReentrantLock();
    private final AtomicInteger currentPoolSize = new AtomicInteger(0);
    private final AtomicInteger activeThreads = new AtomicInteger(0);
    private final AtomicInteger nextQueueIndex = new AtomicInteger(0);

    public CustomThreadPool(
            int corePoolSize,
            int maxPoolSize,
            long keepAliveTime,
            TimeUnit timeUnit,
            int queueSize,
            int minSpareThreads
    ) {
        if (
                corePoolSize < 0
                        || maxPoolSize <= 0
                        || maxPoolSize < corePoolSize
                        || keepAliveTime < 0
                        || queueSize <= 0
                        || minSpareThreads < 0
        ) {
            throw new IllegalArgumentException("Invalid thread pool parameters");
        }

        this.corePoolSize = corePoolSize;
        this.maxPoolSize = maxPoolSize;
        this.keepAliveTime = keepAliveTime;
        this.timeUnit = timeUnit;
        this.queueSize = queueSize;
        this.minSpareThreads = minSpareThreads;

        workers = new ArrayList<>(maxPoolSize);
        queues = new ArrayList<>(maxPoolSize);
        threadFactory = new LoggingThreadFactory(logger);
        rejectionHandler = new LoggingRejectionHandler(logger);
    }

    private class Worker implements Runnable {
        private final BlockingQueue<Runnable> queue;
        private volatile boolean isRunning = true;

        private Worker(BlockingQueue<Runnable> queue) {
            this.queue = queue;
        }

        private void interrupt() {
            isRunning = false;
            Thread.currentThread().interrupt();
        }

        @Override
        public void run() {
            while (isRunning) {
                try {
                    Runnable task = queue.poll(keepAliveTime, timeUnit);
                    if (task != null) {
                        activeThreads.incrementAndGet();
                        try {
                            task.run();
                        } finally {
                            activeThreads.decrementAndGet();
                        }
                        if (!isShutdown && shouldAddSpareThread()) {
                            mainLock.lock();
                            try {
                                if (currentPoolSize.get() < maxPoolSize) {
                                    createAndStartWorker();
                                    logger.info("Spare thread created to maintain minSpareThreads");
                                }
                            } finally {
                                mainLock.unlock();
                            }
                        }
                    } else if (isShutdown && queue.isEmpty()) {
                        isRunning = false;
                        break;
                    } else if (currentPoolSize.get() > corePoolSize) {
                        // If no task received, and we have more than core threads,
                        // this thread should terminate
                        break;
                    }
                } catch (Exception e) {
                    if (!(e instanceof InterruptedException)) {
                        logger.warning("Unexpected exception while processing tasks: " + e.getMessage());
                    }
                    isRunning = false;
                    break;
                }
            }

            cleanup();
            logger.info("Worker thread terminated. Current pool size: " + currentPoolSize.get());
        }

        private void cleanup() {
            mainLock.lock();
            try {
                currentPoolSize.decrementAndGet();
                workers.remove(this);
                queues.remove(queue);
            } finally {
                mainLock.unlock();
            }
        }

        private boolean shouldAddSpareThread() {
            int busy = activeThreads.get();
            int total = currentPoolSize.get();
            int spare = total - busy;
            return spare < minSpareThreads;
        }
    }

    @Override
    public void execute(Runnable task) {
        if (task == null) {
            throw new NullPointerException("Task cannot be null");
        }

        if (isShutdown) {
            rejectionHandler.rejectedExecution(task, null);
            return;
        }

        mainLock.lock();

        if (!areWorkersInitialized) {
            areWorkersInitialized = true;
            initializeWorkers();
        }

        try {
            // Check if we need to create new threads
            int activeCount = activeThreads.get();
            int currentSize = currentPoolSize.get();

            if (activeCount >= currentSize && currentSize < maxPoolSize) {
                createAndStartWorker();
                logger.info("Created new worker thread. Current pool size: " + currentSize);
            }

            BlockingQueue<Runnable> targetQueue = getTargetQueue();

            if (!targetQueue.offer(task)) {
                rejectionHandler.rejectedExecution(task, null);
            } else {
                logger.info("Task submitted to queue " + queues.indexOf(targetQueue));
            }
        } finally {
            mainLock.unlock();
        }
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        if (task == null) {
            throw new NullPointerException("Task cannot be null");
        }

        FutureTask<T> futureTask = new FutureTask<>(task);
        execute(futureTask);
        return futureTask;
    }

    private BlockingQueue<Runnable> getTargetQueue() {
        // Simple round-robin implementation
        int index = nextQueueIndex.getAndIncrement() % queues.size();
        return queues.get(index);
    }

    private void initializeWorkers() {
        for (int i = 0; i < corePoolSize; i++) {
            createAndStartWorker();
        }
        logger.info("Thread pool started with " + corePoolSize + " core threads");
    }

    private void createAndStartWorker() {
        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(queueSize);
        queues.add(queue);
        Worker worker = new Worker(queue);
        workers.add(worker);
        Thread thread = threadFactory.newThread(worker);
        thread.start();
        currentPoolSize.incrementAndGet();
    }

    @Override
    public void shutdown() {
        isShutdown = true;
        logger.info("Thread pool shut down");
    }

    @Override
    public void shutdownNow() {
        mainLock.lock();
        try {
            isShutdown = true;
            for (Worker worker : workers) {
                worker.interrupt();
            }
            logger.info("Thread pool shut down, workers forcibly terminated");
        } finally {
            mainLock.unlock();
        }
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (true) {
            boolean done;
            mainLock.lock();
            try {
                done = (activeThreads.get() == 0 && queues.stream().allMatch(Collection::isEmpty));
            } finally {
                mainLock.unlock();
            }
            if (done) return true;
            if (System.nanoTime() >= deadline) return false;
            Thread.sleep(50);
        }
    }
}
