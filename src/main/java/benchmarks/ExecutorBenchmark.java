package benchmarks;

import pool.CustomThreadPool;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ExecutorBenchmark {
    private final Executor executor;
    private static final int TOTAL_TASKS = 100;
    private static final int TASK_TIME_IN_MS = 10;
    private final AtomicInteger completedTasks = new AtomicInteger(0);

    ExecutorBenchmark(Executor executor) {
        this.executor = executor;
    }

    public void benchmark() {
        completedTasks.set(0);
        log("Starting benchmark for " + executor.getClass().getName());

        try {
            long startTime = System.currentTimeMillis();

            for (int i = 1; i <= TOTAL_TASKS; i++) {
                try {
                    executor.execute(new WorkerTask());
                } catch (RejectedExecutionException _) {
                }
            }

            if (executor instanceof ExecutorService executorService) {
                executorService.shutdown();
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    log("Pool timed out!");
                    executorService.shutdownNow();
                }
            } else if (executor instanceof CustomThreadPool customThreadPool) {
                customThreadPool.shutdown();
                if (!customThreadPool.awaitTermination(30, TimeUnit.SECONDS)) {
                    log("Pool timed out");
                    customThreadPool.shutdownNow();
                }
            }


            long endTime = System.currentTimeMillis();

            log("Total time: %d".formatted(endTime - startTime));
            log("Completed tasks: %d".formatted(completedTasks.get()));
            log("Rejected tasks: %d".formatted(TOTAL_TASKS - completedTasks.get()));
            log("------------------\n");
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private class WorkerTask implements Runnable {
        @Override
        public void run() {
            try {
                Thread.sleep(TASK_TIME_IN_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            completedTasks.incrementAndGet();
        }
    }

    private void log(String message) {
        System.out.println(message);
    }
}
