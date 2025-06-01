import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

public class ThreadPool implements CustomExecutor {
    private static final Logger logger = Logger.getLogger(ThreadPool.class.getName());

    private final int corePoolSize;
    private final int maxPoolSize;
    private final long keepAliveTime;
    private final TimeUnit timeUnit;
    private final int queueSize;
    private final int minSpareThreads;

    private final List<Worker> workers;
    private final List<BlockingQueue<Runnable>> queues;
    private final ThreadFactory threadFactory;
    private final RejectedExecutionHandler rejectionHandler;

    private volatile boolean isShutdown = false;
    private final ReentrantLock mainLock = new ReentrantLock();
    private final AtomicInteger currentPoolSize = new AtomicInteger(0);
    private final AtomicInteger activeThreads = new AtomicInteger(0);
    private final AtomicInteger nextQueueIndex = new AtomicInteger(0);

    public ThreadPool(
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

    class Worker implements Runnable {
        private final BlockingQueue<Runnable> queue;
        private volatile boolean running = true;

        public Worker(BlockingQueue<Runnable> queue) {
            this.queue = queue;
        }

        public void interrupt() {
            running = false;
            Thread.currentThread().interrupt();
        }

        @Override
        public void run() {
            while (running) {
                try {
                    Runnable task = queue.poll(keepAliveTime, timeUnit);
                    if (task != null) {
                        activeThreads.incrementAndGet();
                        try {
                            task.run();
                        } finally {
                            activeThreads.decrementAndGet();
                        }
                    } else if (currentPoolSize.get() > corePoolSize) {
                        // If no task received, and we have more than core threads,
                        // this thread should terminate
                        break;
                    }
                } catch (InterruptedException e) {
                    if (!running) {
                        break;
                    }
                }
            }

            // Cleanup
            currentPoolSize.decrementAndGet();
            workers.remove(this);
            logger.info("Worker thread terminated. Current pool size: " + currentPoolSize.get());
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
        try {
            // Check if we need to create new threads
            int activeCount = activeThreads.get();
            int currentSize = currentPoolSize.get();

            if (activeCount >= currentSize && currentSize < maxPoolSize) {
                createWorker();
                logger.info("Created new worker thread. Current pool size: " + currentSize);
            }

            // Find the least loaded queue
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
    public <T> Future<T> submit(Callable<T> callable) {
        return null;
    }

    private BlockingQueue<Runnable> getTargetQueue() {
        // Simple round-robin implementation
        int index = nextQueueIndex.getAndIncrement() % queues.size();
        return queues.get(index);
    }

    private void initializePool() {
        for (int i = 0; i < corePoolSize; i++) {
            createWorker();
        }
        logger.info("Thread pool initialized with " + corePoolSize + " core threads");
    }

    private void createWorker() {
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
        mainLock.lock();
        try {
            isShutdown = true;
            for (Worker worker : workers) {
                worker.interrupt();
            }
            logger.info("Thread pool shutdown initiated");
        } finally {
            mainLock.unlock();
        }
    }

    @Override
    public void shutdownNow() {

    }
}
