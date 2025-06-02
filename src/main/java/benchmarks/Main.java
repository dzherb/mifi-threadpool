package benchmarks;

import pool.CustomThreadPool;

import java.util.List;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class Main {
    private static final List<Executor> executorsToBenchmark = List.of(
        new ForkJoinPool(8),
        Executors.newSingleThreadExecutor(),
        new ThreadPoolExecutor(2, 8, 5, TimeUnit.SECONDS, new LinkedBlockingQueue<>(80)),
        new CustomThreadPool(2, 8, 5, TimeUnit.SECONDS, 80, 2)
    );

    public static void main(String[] args) {
        Logger rootLogger = LogManager.getLogManager().getLogger("");
        rootLogger.setLevel(Level.WARNING);

        executorsToBenchmark.forEach((executor) -> new ExecutorBenchmark(executor).benchmark());
    }
}
