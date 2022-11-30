package vmware.data;

import lombok.extern.slf4j.Slf4j;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.cache.execute.FunctionService;
import org.apache.geode.cache.execute.ResultCollector;
import org.apache.geode.internal.cache.execute.DefaultResultCollector;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

@Slf4j
public class CaptureClient {
    public static void main(String[] args) throws IOException {
        if ((args == null) || (args.length < 2) || (args.length > 2)) {
            System.out.println("Invalid arguments - argument 1 [locator hostname/address]; argument 2 [locator port number]");
            return;
        }

        Integer portNumber;
        try {
            portNumber = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            System.out.println("Argument 2 must be a numeric port number");
            return;
        }

        final ClientCacheFactory ccf = new ClientCacheFactory();
        ccf.addPoolLocator(args[0], portNumber).setPoolMinConnections(0).setPoolRetryAttempts(0).setPoolReadTimeout(1);
        final ClientCache cache = ccf.create();
        final ClientRegionFactory crf = cache.createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY);
        final Region pdxTypes = crf.create("PdxTypes");
        final ResultCollector collector = FunctionService.onServer(pdxTypes.getRegionService()).withCollector(new DefaultResultCollector()).execute("Analysis");
        final ArrayList<ArrayList<String>> reportList = (ArrayList<ArrayList<String>>) collector.getResult();
        final ArrayList<String> reports = (ArrayList<String>) reportList.get(0);
        reports.forEach(r -> {
            final String name = r.substring(0, r.indexOf("<html>"));
            final String newReport = r.substring(r.indexOf("<html>"));
            final File file = new File("cluster-report-" + new SimpleDateFormat("dd-MM-yyyy").format(new Date()) + "-" + name + ".html");
            try {
                Files.write(file.toPath(), newReport.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE);
            } catch (IOException ex) {
                log.error("Error writing file" + file.getAbsolutePath(), ex);
            }
        });
        cache.close();
    }
}
