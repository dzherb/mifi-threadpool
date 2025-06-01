import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.logging.Logger;

public class LoggingRejectionHandler implements RejectedExecutionHandler {
    private final Logger logger;

    public LoggingRejectionHandler(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        logger.warning("Task rejected: " + r.toString());
    }
}