import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class LoggingThreadFactory implements ThreadFactory {
    private final AtomicInteger threadNumber = new AtomicInteger(1);
    private final Logger logger;

    public LoggingThreadFactory(Logger logger) {
        this.logger = logger;
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(r, "Thread-" + threadNumber.getAndIncrement());
        t.setDaemon(false);
        logger.info("Created new thread: " + t.getName());
        return t;
    }
}
