package vmware.data;

import com.vmware.gemfire.metrics.exceptions.RegistryDoesNotExistException;
import com.vmware.gemfire.metrics.exceptions.RegistryExistsException;
import com.vmware.gemfire.metrics.exceptions.ServiceNotAvailableException;
import com.vmware.gemfire.metrics.server.ApplicationServerMetrics;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.Histogram;
import lombok.extern.slf4j.Slf4j;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;

@Slf4j
public class ApplicationServerMetricsClient {

    private ApplicationServerMetrics applicationServerMetrics;
    private PrometheusMeterRegistry prometheusMeterRegistry;

    private void run() throws ServiceNotAvailableException {
        Long sleepTime = 500L;
        int counter = 0;

        ClientCache cache = new ClientCacheFactory()
                .addPoolLocator("localhost", 10334)
                .set("log-file", "client.log")
                .create();
        applicationServerMetrics = new ApplicationServerMetrics("client_test");

        prometheusMeterRegistry = applicationServerMetrics.createCountersAndTimers();

        try {
            applicationServerMetrics.addRegistryToService(prometheusMeterRegistry);
        } catch(RegistryExistsException ex) {
            log.warn("Registry already exists for {}", prometheusMeterRegistry, ex);
        }

        do {
            applicationServerMetrics.getMessagesReceivedCounter().inc();
            Histogram.Timer timer = applicationServerMetrics.getMessageProcessingTime().startTimer();
            try {
                Thread.sleep(sleepTime);
            } catch (InterruptedException ex) {
                // do nothing
            }
            counter++;
            sleepTime = sleepTime + 500L;
            timer.observeDuration();
            timer.close();
            applicationServerMetrics.getMessagesProducedCounter().inc(2);
        } while (counter < 5);
        long timer = 250;
        do {
            try {
                Thread.sleep(250L);
                timer = timer + 250L;
            } catch (Exception ex) {
                log.warn("Registry does not exist {}", prometheusMeterRegistry, ex);
            }
        } while (timer <= 60000);

        try {
            applicationServerMetrics.removeRegistryFromService(prometheusMeterRegistry);
        } catch (RegistryDoesNotExistException ex) {
            log.warn("Registry does not exist {}", prometheusMeterRegistry, ex);
        }
        cache.close();
    }

    public static void main(String[] args) {
        ApplicationServerMetricsClient client = new ApplicationServerMetricsClient();
        try {
            client.run();

        } catch (ServiceNotAvailableException ex) {
            log.error("Unable to run", ex);
        }
    }
}
