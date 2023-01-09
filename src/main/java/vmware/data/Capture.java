package vmware.data;

import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.geode.cache.*;
import org.apache.geode.cache.control.ResourceManager;
import org.apache.geode.cache.execute.Function;
import org.apache.geode.cache.execute.FunctionContext;
import org.apache.geode.cache.lucene.LuceneIndex;
import org.apache.geode.cache.lucene.LuceneServiceProvider;
import org.apache.geode.cache.lucene.internal.LuceneIndexImpl;
import org.apache.geode.cache.lucene.internal.LuceneServiceImpl;
import org.apache.geode.cache.lucene.internal.cli.LuceneIndexDetails;
import org.apache.geode.cache.lucene.internal.cli.LuceneIndexStatus;
import org.apache.geode.cache.partition.PartitionListener;
import org.apache.geode.cache.query.internal.DefaultQuery;
import org.apache.geode.cache.server.CacheServer;
import org.apache.geode.distributed.ServerLauncher;
import org.apache.geode.distributed.internal.DistributionConfig;
import org.apache.geode.distributed.internal.OperationExecutors;
import org.apache.geode.distributed.internal.membership.InternalDistributedMember;
import org.apache.geode.internal.cache.*;
import org.apache.geode.management.internal.util.HostUtils;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class Capture implements Function {

    public String capture() {
        final InternalCache cache = (InternalCache) CacheFactory.getAnyInstance();
        final ServerLauncher serverLauncher = ServerLauncher.getInstance();
        final InternalDistributedMember currentMember = cache.getDistributionManager().getId();
        StringBuilder sb = new StringBuilder();

        // Get other members and add this member to other members set
        Set<InternalDistributedMember> otherMembers = cache.getDistributionManager().getAllOtherMembers();
        if (otherMembers == null) {
            otherMembers = new HashSet<>();
        }
        otherMembers.add(currentMember);
        final Set<InternalDistributedMember> members = otherMembers;

        HashMap<String,String> sysConfig = new HashMap<>();
        sysConfig.put("vm_swappiness", "/proc/sys/vm/swappiness");
        sysConfig.put("somaxconn", "/proc/sys/net/core/somaxconn");
        sysConfig.put("rmem_max","/proc/sys/net/core/rmem_max");
        sysConfig.put("wmem_max","/proc/sys/net/core/wmem_max");
        sysConfig.put("netdev_max_backlog","/proc/sys/net/core/netdev_max_backlog");
        sysConfig.put("tcp_rmem","/proc/sys/net/ipv4/tcp_rmem");
        sysConfig.put("tcp_wmem","/proc/sys/net/ipv4/tcp_wmem");
        sysConfig.put("tcp_syncookies","/proc/sys/net/ipv4/tcp_syncookies");
        sysConfig.put("shmmax","/proc/sys/kernel/shmmax");
        sysConfig.put("nr_hugepages","/proc/sys/vm/nr_hugepages");
        sysConfig.put("hugetlb_shm_group","/proc/sys/vm/hugetlb_shm_group");
        sysConfig.put("transparent_hugepage","/sys/kernel/mm/transparent_hugepage/enabled");

        sb.append(currentMember.getName());
        sb.append("<html>").append("<heading>").append("</heading>").append("<title>").append("Cache Server Analysis").append("</title>").append("<body>")
                .append("<br>");

        sb.append("<h2><b>").append("Cluster Configuration").append("</b></h2>");

        // cluster locators
        Map<InternalDistributedMember, Collection<String>> locators = processLocators(cache, sb, members);

        // cluster servers
        processServers(cache, sb, locators, members);

        // cache server
        processCacheServer(cache, sb, serverLauncher, currentMember);

        // cluster groups
        processGroups(cache,sb, currentMember);

        // JVM details
        processJVM(cache, sb, sysConfig);

        // pdx
        processPdx(cache, sb);

        // defined cache services
        processCacheServices(cache, sb);

        // resource manager
        processResourceManager(cache, sb);

        // security
        processSecurity(cache, sb);

        // transaction manager
        processTxMgr(cache, sb);

        // async queues
        processAsync(cache, sb);

        // gateway senders
        processGatewaySenders(cache, sb);

        // gateway receivers
        processGatewayReceivers(cache, sb);

        // regions
        Set<InternalRegion> regions = cache.getApplicationRegions();
        processRegions(cache, regions, sb, serverLauncher.getMemberName());

        // cluster backup
        processBackup(cache, sb);

        // cache xml
        processCacheXml(cache, sb);

        // spring xml
        processSpringXml(cache, sb, serverLauncher);

        // meters
        processMeters(cache, sb);

        // gemfire and system properties
        processProperties(cache, sb);

        sb.append("</body></html>");

        return sb.toString();
    }

    private Map<InternalDistributedMember, Collection<String>> processLocators(InternalCache cache, StringBuilder sb, Set<InternalDistributedMember> members) {
        // cluster locators
        Map<InternalDistributedMember, Collection<String>> locators = cache.getDistributionManager().getAllHostedLocators();
        sb.append("<h3><b>").append("Cluster Locators").append("</b></h3>");
        sb.append("<table><tr><td><b>").append("Name").append("</b></td><td><b>").append("Host Name").append("</b></td><td><b>")
                .append("IP Address").append("</b></td><td><b>").append("Port").append("</b></td></tr>");
        locators.forEach((member, locator) -> locator.forEach(a -> {
            if (members.contains(member)) {
                final String locatorPort = a.substring(a.indexOf("[") + 1, a.indexOf("]"));
                sb.append("<tr><td>").append(member.getName()).append("</td>");
                sb.append("<td>").append(member.getInetAddress().getHostName()).append("</td>");
                sb.append("<td>").append(member.getInetAddress().getHostAddress()).append("<td>").append(locatorPort).append("</td></tr>");
            }
        }));
        sb.append("</table>");
        return locators;
    }

    private void processServers(InternalCache cache, StringBuilder sb,  Map<InternalDistributedMember, Collection<String>> locators, Set<InternalDistributedMember> members) {
        sb.append("<h3><b>").append("Cluster Servers").append("</b></h3>");
        sb.append("<table><tr><td><b>").append("Name").append("</b></td><td><b>").append("Host Name").append("</b></td><td><b>")
                .append("IP Address").append("</b></td><td><b>").append("Port").append("</b></td></tr>");
        members.forEach(member -> {
            if (locators.get(member) == null) {
                final String serverPort = member.getId().substring(member.getId().lastIndexOf(":") + 1);
                sb.append("<tr><td>").append(member.getName()).append("</td>");
                sb.append("<td>").append(member.getInetAddress().getHostName()).append("</td>");
                if (member.equals(cache.getDistributionManager().getId())) {
                    sb.append("<td>").append(member.getInetAddress().getHostAddress()).append(" ** ").append("</td><td>")
                            .append(serverPort).append("</td></tr>");
                } else {
                    sb.append("<td>").append(member.getInetAddress().getHostAddress()).append("    ").append("</td><td>")
                            .append(serverPort).append("</td></tr>");
                }
            }
        });
        sb.append("</table>");
    }

    private void processCacheServer(InternalCache cache, StringBuilder sb, ServerLauncher serverLauncher, InternalDistributedMember currentMember) {
        sb.append("<h3><b>").append("Cache Server Details").append("</b></h3>");
        sb.append("<h4><b>").append("Server Name - ").append(serverLauncher.getMemberName()).append("</b></h4>");
        sb.append("<table><tr><td><b>").append("Property").append("</b></td><td><b>").append("Value").append("</b></td></tr>");
        sb.append("<tr><td>").append("Host Name").append("</td><td>").append(HostUtils.getLocalHost()).append("</td></tr>");
        sb.append("<tr><td>").append("Bind Address").append("</td><td>").append(cache.getDistributionManager().getConfig().getBindAddress() == null || cache.getDistributionManager().getConfig().getBindAddress().length() == 0 ? "Not Defined" : cache.getDistributionManager().getConfig().getBindAddress()).append("</td></tr>");
        sb.append("<tr><td>").append("Server Bind Address").append("</td><td>").append(serverLauncher.getServerBindAddressAsString() == null ? "Not Defined" : serverLauncher.getServerBindAddressAsString()).append("</td></tr>");
        sb.append("<tr><td>").append("Host Name For Clients").append("</td><td>").append(serverLauncher.getHostNameForClients() == null ? "Not Defined" : serverLauncher.getHostNameForClients()).append("</td></tr>");
        sb.append("<tr><td>").append("Socket Buffer Size").append("</td><td>").append(cache.getDistributionManager().getConfig().getSocketBufferSize()).append("</td></tr>");
        sb.append("<tr><td>").append("Socket Lease Time").append("</td><td>").append(cache.getDistributionManager().getConfig().getSocketLeaseTime()).append("</td></tr>");
        sb.append("</table>");

        sb.append("<h4><b>").append("Cache Server Membership Details").append("</b></h4>");
        sb.append("<table><tr><td>").append("Maximum Connections").append("</td><td>").append(serverLauncher.getMaxConnections() == null ? CacheServer.DEFAULT_MAX_CONNECTIONS : serverLauncher.getMaxConnections()).append("</td></tr>");
        sb.append("<tr><td>").append("Maximum Threads").append("</td><td>").append(serverLauncher.getMaxThreads() == null ? CacheServer.DEFAULT_MAX_THREADS : serverLauncher.getMaxThreads()).append("</td></tr>");
        sb.append("<tr><td>").append("Maximum Message Count").append("</td><td>").append(serverLauncher.getMaxMessageCount() == null ? CacheServer.DEFAULT_MAXIMUM_MESSAGE_COUNT : serverLauncher.getMaxMessageCount()).append("</td></tr>");
        sb.append("<tr><td>").append("Message Time To Live").append("</td><td>").append(serverLauncher.getMessageTimeToLive() == null ? CacheServer.DEFAULT_MESSAGE_TIME_TO_LIVE : serverLauncher.getMessageTimeToLive()).append("</td></tr>");
        sb.append("<tr><td>").append("Working Directory").append("</td><td>").append(serverLauncher.getWorkingDirectory() == null ? "Not Defined" : serverLauncher.getWorkingDirectory()).append("</td></tr>");
        sb.append("<tr><td>").append("Disable Default Server").append("</td><td>").append(serverLauncher.isDisableDefaultServer()).append("</td></tr>");
        sb.append("<tr><td>").append("Membership Weight").append("</td><td>").append(currentMember.getMemberWeight()).append("</td></tr>");
        sb.append("<tr><td>").append("Membership Port").append("</td><td>").append(currentMember.getMembershipPort()).append("</td></tr>");
        sb.append("</table>");

        sb.append("<h4><b>").append("Cache Server Log Details").append("</b></h4>");
        sb.append("<table><tr><td>").append("Log File").append("</td><td>").append(cache.getDistributionManager().getConfig().getLogFile() == null ? "No Log File Defined" : cache.getDistributionManager().getConfig().getLogFile()).append("</td></tr>");
        sb.append("<tr><td>").append("Log Level").append("</td><td>").append(cache.getDistributionManager().getConfig().getLogLevel()).append("</td></tr>");
        sb.append("<tr><td>").append("Log File Size Limit").append("</td><td>").append(cache.getDistributionManager().getConfig().getLogFileSizeLimit() == 0 ? DistributionConfig.DEFAULT_LOG_FILE_SIZE_LIMIT : cache.getDistributionManager().getConfig().getLogFileSizeLimit()).append("</td></tr>");
        sb.append("<tr><td>").append("Log Disk Space Limit").append("</td><td>").append(cache.getDistributionManager().getConfig().getLogDiskSpaceLimit() == 0 ? DistributionConfig.DEFAULT_LOG_DISK_SPACE_LIMIT : cache.getDistributionManager().getConfig().getLogDiskSpaceLimit()).append("</td></tr>");
        sb.append("</table>");

        sb.append("<h4><b>").append("Cache Server Statistics Details").append("</b></h4>");
        sb.append("<table><tr><td>").append("Statistics Sampling Enabled").append("</td><td>").append(cache.getDistributionManager().getConfig().getStatisticSamplingEnabled()).append("</td></tr>");
        sb.append("<tr><td>").append("Statistics Sample Rate").append("</td><td>").append(cache.getDistributionManager().getConfig().getStatisticSampleRate()).append("</td></tr>");
        sb.append("<tr><td>").append("Time Statistics Enabled").append("</td><td>").append(cache.getDistributionManager().getConfig().getEnableTimeStatistics()).append("</td></tr>");
        sb.append("<tr><td>").append("Statistics File").append("</td><td>").append(cache.getDistributionManager().getConfig().getStatisticArchiveFile() == null || cache.getDistributionManager().getConfig().getStatisticArchiveFile().length() == 0 ? "No Statistics File Defined" : cache.getDistributionManager().getConfig().getStatisticArchiveFile()).append("</td></tr>");
        sb.append("<tr><td>").append("Statistics File Size Limit").append("</td><td>").append(cache.getDistributionManager().getConfig().getArchiveFileSizeLimit() == 0 ? DistributionConfig.DEFAULT_ARCHIVE_FILE_SIZE_LIMIT : cache.getDistributionManager().getConfig().getArchiveFileSizeLimit()).append("</td></tr>");
        sb.append("<tr><td>").append("Statistics Disk Space Limit").append("</td><td>").append(cache.getDistributionManager().getConfig().getArchiveDiskSpaceLimit() == 0 ? DistributionConfig.DEFAULT_ARCHIVE_DISK_SPACE_LIMIT : cache.getDistributionManager().getConfig().getArchiveDiskSpaceLimit()).append("</td></tr>");
        sb.append("</table>");

        sb.append("<h4><b>").append("Cache Server JMX Details").append("</b></h4>");
        sb.append("<table><tr><td>").append("JMX Manager Bind Address").append("</td><td>").append(cache.getDistributionManager().getConfig().getJmxManagerBindAddress() == null || cache.getDistributionManager().getConfig().getJmxManagerBindAddress().length() == 0 ? "No JMX Manager Bind Address Defined" : cache.getDistributionManager().getConfig().getJmxManagerBindAddress()).append("</td></tr>");
        sb.append("<tr><td>").append("JMX Manager Update rate").append("</td><td>").append(cache.getDistributionManager().getConfig().getJmxManagerUpdateRate()).append("</td></tr>");
        sb.append("<tr><td>").append("JMX Manager Port").append("</td><td>").append(cache.getDistributionManager().getConfig().getJmxManagerPort()).append("</td></tr>");
        sb.append("<tr><td>").append("JMX Manager Access File").append("</td><td>").append(cache.getDistributionManager().getConfig().getJmxManagerAccessFile() == null || cache.getDistributionManager().getConfig().getJmxManagerAccessFile().length() == 0 ? "No JMX Manager Access File Defined" : cache.getDistributionManager().getConfig().getJmxManagerAccessFile()).append("</td></tr>");
        sb.append("<tr><td>").append("JMX Manager Password File").append("</td><td>").append(cache.getDistributionManager().getConfig().getJmxManagerPasswordFile() == null || cache.getDistributionManager().getConfig().getJmxManagerPasswordFile().length() == 0 ? "No JMX Manager Password File Defined" : cache.getDistributionManager().getConfig().getJmxManagerPasswordFile()).append("</td></tr>");
        sb.append("<tr><td>").append("JMX SSL Alias").append("</td><td>").append(cache.getDistributionManager().getConfig().getJMXSSLAlias() == null || cache.getDistributionManager().getConfig().getJMXSSLAlias().length() == 0 ? "No JMX Manager SSL Alias Defined" : cache.getDistributionManager().getConfig().getJMXSSLAlias()).append("</td></tr>");
        sb.append("</table>");

        sb.append("<h4><b>").append("Cache Server Asynchronous Details").append("</b></h4>");
        sb.append("<table><tr><td>").append("Async Queue Timeout").append("</td><td>").append(cache.getDistributionManager().getConfig().getAsyncQueueTimeout()).append("</td></tr>");
        sb.append("<tr><td>").append("Async Distribution Timeout").append("</td><td>").append(cache.getDistributionManager().getConfig().getAsyncDistributionTimeout()).append("</td></tr>");
        sb.append("<tr><td>").append("Async Max Queue Size").append("</td><td>").append(cache.getDistributionManager().getConfig().getAsyncMaxQueueSize()).append("</td></tr>");
        sb.append("</table>");

        sb.append("<h4><b>").append("Cache Server Security Details").append("</b></h4>");
        sb.append("<table><tr><td>").append("Security Log Level").append("</td><td>").append(cache.getDistributionManager().getConfig().getSecurityLogLevel()).append("</td></tr>");
        sb.append("<tr><td>").append("Security Log File").append("</td><td>").append(cache.getDistributionManager().getConfig().getSecurityLogFile() == null || cache.getDistributionManager().getConfig().getSecurityLogFile().length() == 0 ? "No Security Log File Defined" : cache.getDistributionManager().getConfig().getSecurityLogFile()).append("</td></tr>");
        String[] components = cache.getDistributionManager().getConfig().getSecurityAuthTokenEnabledComponents();
        for (int i = 0; i < components.length; i++) {
            if (i == 0) {
                sb.append("<tr><td>").append("Security Auth Token Enabled Components").append("</td><td>").append(components[i]).append("</td></tr>");
            } else {
                sb.append("<tr><td>").append("").append("</td><td>").append(components[i]).append("</td></tr>");
            }
        }
        sb.append("<tr><td>").append("Security Peer Authenticator").append("</td><td>").append(cache.getDistributionManager().getConfig().getSecurityPeerAuthenticator() == null || cache.getDistributionManager().getConfig().getSecurityPeerAuthenticator().length() == 0 ? "No Security Peer Authenticator Defined" : cache.getDistributionManager().getConfig().getSecurityPeerAuthenticator()).append("</td></tr>");
        sb.append("<tr><td>").append("Security Peer Auth Init").append("</td><td>").append(cache.getDistributionManager().getConfig().getSecurityPeerAuthInit() == null || cache.getDistributionManager().getConfig().getSecurityPeerAuthInit().length() == 0 ? "No Security Peer Auth Init Defined" : cache.getDistributionManager().getConfig().getSecurityPeerAuthInit()).append("</td></tr>");
        sb.append("<tr><td>").append("Security Client Accessor").append("</td><td>").append(cache.getDistributionManager().getConfig().getSecurityClientAccessor() == null || cache.getDistributionManager().getConfig().getSecurityClientAccessor().length() == 0 ? "No Security Client Accessor Defined" : cache.getDistributionManager().getConfig().getSecurityClientAccessor()).append("</td></tr>");
        sb.append("<tr><td>").append("Security Client Accessor PP").append("</td><td>").append(cache.getDistributionManager().getConfig().getSecurityClientAccessorPP() == null || cache.getDistributionManager().getConfig().getSecurityClientAccessorPP().length() == 0 ? "No Security Client  Accessor PP Defined" : cache.getDistributionManager().getConfig().getSecurityClientAccessorPP()).append("</td></tr>");
        sb.append("<tr><td>").append("Security Client Authenticator").append("</td><td>").append(cache.getDistributionManager().getConfig().getSecurityClientAuthenticator() == null || cache.getDistributionManager().getConfig().getSecurityClientAuthenticator().length() == 0 ? "No Security Client Authenticator Defined" : cache.getDistributionManager().getConfig().getSecurityClientAuthenticator()).append("</td></tr>");
        sb.append("<tr><td>").append("Security Client Auth Init").append("</td><td>").append(cache.getDistributionManager().getConfig().getSecurityClientAuthInit() == null || cache.getDistributionManager().getConfig().getSecurityClientAuthInit().length() == 0 ? "No Security Client Auth Init Defined" : cache.getDistributionManager().getConfig().getSecurityClientAuthInit()).append("</td></tr>");
        sb.append("<tr><td>").append("Security Client DHAlgo").append("</td><td>").append(cache.getDistributionManager().getConfig().getSecurityClientDHAlgo() == null || cache.getDistributionManager().getConfig().getSecurityClientDHAlgo().length() == 0 ? "No Security Client DHAlogo Defined" : cache.getDistributionManager().getConfig().getSecurityClientDHAlgo()).append("</td></tr>");
        sb.append("</table>");

        sb.append("<h4><b>").append("Cache Server SSL Details").append("</b></h4>");
        sb.append("<table><tr><td>").append("SSL Server Alias").append("</td><td>").append(cache.getDistributionManager().getConfig().getServerSSLAlias() == null || cache.getDistributionManager().getConfig().getServerSSLAlias().length() == 0 ? "No Server SSL Alias Defined" : cache.getDistributionManager().getConfig().getServerSSLAlias()).append("</td></tr>");
        sb.append("<tr><td>").append("SSL Server Protocols").append("</td><td>").append(cache.getDistributionManager().getConfig().getSSLServerProtocols() == null || cache.getDistributionManager().getConfig().getSSLServerProtocols().length() == 0 ? "No Server SSL Protocols Defined" : cache.getDistributionManager().getConfig().getSSLProtocols()).append("</td></tr>");
        sb.append("<tr><td>").append("SSL Default Alias").append("</td><td>").append(cache.getDistributionManager().getConfig().getSSLDefaultAlias() == null || cache.getDistributionManager().getConfig().getSSLDefaultAlias().length() == 0 ? "No SSL Default Alias Defined" : cache.getDistributionManager().getConfig().getSSLDefaultAlias()).append("</td></tr>");
        sb.append("<tr><td>").append("SSL Ciphers").append("</td><td>").append(cache.getDistributionManager().getConfig().getSSLCiphers() == null || cache.getDistributionManager().getConfig().getSSLCiphers().length() == 0 ? "No SSL Ciphers Defined" : cache.getDistributionManager().getConfig().getSSLCiphers()).append("</td></tr>");
        sb.append("<tr><td>").append("SSL Protocols").append("</td><td>").append(cache.getDistributionManager().getConfig().getSSLProtocols() == null || cache.getDistributionManager().getConfig().getSSLProtocols().length() == 0 ? "No SSL Protocols Defined" : cache.getDistributionManager().getConfig().getSSLProtocols()).append("</td></tr>");
        sb.append("<tr><td>").append("SSL Parameter Extension").append("</td><td>").append(cache.getDistributionManager().getConfig().getSSLParameterExtension() == null || cache.getDistributionManager().getConfig().getSSLParameterExtension().length() == 0 ? "No SSL Parameter Extension Defined" : cache.getDistributionManager().getConfig().getSSLParameterExtension()).append("</td></tr>");
        sb.append("<tr><td>").append("SSL Trust Store Type").append("</td><td>").append(cache.getDistributionManager().getConfig().getSSLTrustStoreType() == null || cache.getDistributionManager().getConfig().getSSLTrustStoreType().length() == 0 ? "No SSL Trust Store Tpye Defined" : cache.getDistributionManager().getConfig().getSSLTrustStoreType()).append("</td></tr>");
        sb.append("<tr><td>").append("SSL Trust Store").append("</td><td>").append(cache.getDistributionManager().getConfig().getSSLTrustStore() == null || cache.getDistributionManager().getConfig().getSSLTrustStore().length() == 0 ? "No SSL Trust Store Defined" : cache.getDistributionManager().getConfig().getSSLTrustStore()).append("</td></tr>");
        sb.append("<tr><td>").append("SSL Key Store Type").append("</td><td>").append(cache.getDistributionManager().getConfig().getSSLKeyStoreType() == null || cache.getDistributionManager().getConfig().getSSLKeyStoreType().length() == 0 ? "No SSL Key Store Type Defined" : cache.getDistributionManager().getConfig().getSSLKeyStoreType()).append("</td></tr>");
        sb.append("<tr><td>").append("SSL Key Store").append("</td><td>").append(cache.getDistributionManager().getConfig().getSSLKeyStore() == null || cache.getDistributionManager().getConfig().getSSLKeyStore().length() == 0 ? "No SSL Key Store Defined" : cache.getDistributionManager().getConfig().getSSLKeyStore()).append("</td></tr>");
        sb.append("<tr><td>").append("SSL Require Authentication").append("</td><td>").append(cache.getDistributionManager().getConfig().getSSLRequireAuthentication()).append("</td></tr>");
        sb.append("<tr><td>").append("SSL Endpoint ID Enabled").append("</td><td>").append(cache.getDistributionManager().getConfig().getSSLEndPointIdentificationEnabled()).append("</td></tr>");
        sb.append("</table>");

        sb.append("<h4><b>").append("Cache Server HTTP Details").append("</b></h4>");
        sb.append("<table><tr><td>").append("HTTP Service Port").append("</td><td>").append(cache.getDistributionManager().getConfig().getHttpServicePort()).append("</td></tr>");
        sb.append("<tr><td>").append("HTTP Service Bind Address").append("</td><td>").append(cache.getDistributionManager().getConfig().getHttpServiceBindAddress() == null || cache.getDistributionManager().getConfig().getHttpServiceBindAddress().length() == 0 ? "No Http Service Bind Address Defined" : cache.getDistributionManager().getConfig().getHttpServiceBindAddress()).append("</td></tr>");
        sb.append("</table>");

        sb.append("<h4><b>").append("Cache Server Thread Details").append("</b></h4>");
        sb.append("<table><tr><td>").append("Thread Monitor Enabled").append("</td><td>").append(cache.getDistributionManager().getConfig().getThreadMonitorEnabled()).append("</td></tr>");
        sb.append("<tr><td>").append("Thread Monitor Time Limit").append("</td><td>").append(cache.getDistributionManager().getConfig().getThreadMonitorTimeLimit()).append("</td></tr>");
        sb.append("<tr><td>").append("Thread Monitor Interval").append("</td><td>").append(cache.getDistributionManager().getConfig().getThreadMonitorInterval()).append("</td></tr>");
        sb.append("</table>");

        sb.append("<h4><b>").append("Cache Server Other Properties").append("</b></h4>");
        sb.append("<table><tr><td>").append("Lock Memory Enabled").append("</td><td>").append(cache.getDistributionManager().getConfig().getLockMemory()).append("</td></tr>");
        sb.append("<tr><td>").append("TCP Port").append("</td><td>").append(cache.getDistributionManager().getConfig().getTcpPort()).append("</td></tr>");
        sb.append("<tr><td>").append("Disable TCP").append("</td><td>").append(cache.getDistributionManager().getConfig().getDisableTcp()).append("</td></tr>");
        sb.append("<tr><td>").append("Remote Locators").append("</td><td>").append(cache.getDistributionManager().getConfig().getRemoteLocators() == null || cache.getDistributionManager().getConfig().getRemoteLocators().length() == 0 ? "No Remote Locatord Defined" : cache.getDistributionManager().getConfig().getRemoteLocators()).append("</td></tr>");
        sb.append("<tr><td>").append("Redundancy Zone").append("</td><td>").append(cache.getDistributionManager().getConfig().getRedundancyZone() == null || cache.getDistributionManager().getConfig().getRedundancyZone().length() == 0 ? "No Redundancy Zone Defined" : cache.getDistributionManager().getConfig().getRedundancyZone()).append("</td></tr>");
        sb.append("<tr><td>").append("Off Heap Memory Size").append("</td><td>").append(cache.getDistributionManager().getConfig().getOffHeapMemorySize() == null || cache.getDistributionManager().getConfig().getOffHeapMemorySize().length() == 0 ? "No Off Heap Memory Defined" : cache.getDistributionManager().getConfig().getOffHeapMemorySize()).append("</td></tr>");
        sb.append("<tr><td>").append("Member Timeout").append("</td><td>").append(cache.getDistributionManager().getConfig().getMemberTimeout()).append("</td></tr>");
        sb.append("<tr><td>").append("Max Reconnect Wait Time ").append("</td><td>").append(cache.getDistributionManager().getConfig().getMaxWaitTimeForReconnect()).append("</td></tr>");
        sb.append("<tr><td>").append("Max Reconnect Number Retries").append("</td><td>").append(cache.getDistributionManager().getConfig().getMaxNumReconnectTries()).append("</td></tr>");
        sb.append("<tr><td>").append("Locator Wait Time").append("</td><td>").append(cache.getDistributionManager().getConfig().getLocatorWaitTime()).append("</td></tr>");
        sb.append("<tr><td>").append("Enforce Unique Host").append("</td><td>").append(cache.getDistributionManager().getConfig().getEnforceUniqueHost()).append("</td></tr>");
        sb.append("<tr><td>").append("Network Partition Detection").append("</td><td>").append(cache.getDistributionManager().getConfig().getEnableNetworkPartitionDetection()).append("</td></tr>");
        sb.append("<tr><td>").append("Disable Auto Reconnect").append("</td><td>").append(cache.getDistributionManager().getConfig().getDisableAutoReconnect()).append("</td></tr>");
        sb.append("<tr><td>").append("Conserve Sockets").append("</td><td>").append(cache.getDistributionManager().getConfig().getConserveSockets()).append("</td></tr>");
        sb.append("<tr><td>").append("Ack Wait Threshold").append("</td><td>").append(cache.getDistributionManager().getConfig().getAckWaitThreshold()).append("</td></tr>");
        sb.append("<tr><td>").append("Ack Severe Alert Threshold").append("</td><td>").append(cache.getDistributionManager().getConfig().getAckSevereAlertThreshold()).append("</td></tr>");
        sb.append("<tr><td>").append("Enable Cluster Configuration").append("</td><td>").append(cache.getDistributionManager().getConfig().getEnableClusterConfiguration()).append("</td></tr>");
        sb.append("<tr><td>").append("Enable Management REST Service").append("</td><td>").append(cache.getDistributionManager().getConfig().getEnableManagementRestService()).append("</td></tr>");
        int[] ports = cache.getDistributionManager().getConfig().getMembershipPortRange();
        sb.append("<tr><td>").append("Membership Port Range").append("</td><td>").append(ports[0]).append("-").append(ports[1]).append("</td></tr>");
        sb.append("<tr><td>").append("Post Processor").append("</td><td>").append(cache.getDistributionManager().getConfig().getPostProcessor() == null || cache.getDistributionManager().getConfig().getPostProcessor().length() == 0 ? "No Post processor Defined" : cache.getDistributionManager().getConfig().getPostProcessor()).append("</td></tr>");
        sb.append("<tr><td>").append("Lock Memory Enabled").append("</td><td>").append(cache.getDistributionManager().getConfig().getLockMemory()).append("</td></tr>");
        sb.append("<tr><td>").append("TCP Port").append("</td><td>").append(cache.getDistributionManager().getConfig().getTcpPort()).append("</td></tr>");
        sb.append("<tr><td>").append("Disable TCP").append("</td><td>").append(cache.getDistributionManager().getConfig().getDisableTcp()).append("</td></tr>");
        sb.append("<tr><td>").append("Cache XML File Location").append("</td><td>").append(cache.getInternalDistributedSystem().getConfig().getCacheXmlFile() == null ? "No Cache XML File Defined" : cache.getInternalDistributedSystem().getConfig().getCacheXmlFile().getAbsolutePath()).append("</td></tr>");
        sb.append("<tr><td>").append("Spring XML File Location").append("</td><td>").append(serverLauncher.getSpringXmlLocation() == null ? "No Spring XML File Defined" : serverLauncher.getSpringXmlLocation()).append("</td></tr>");
        sb.append("<tr><td>").append("Initializer").append("</td><td>").append(cache.getInitializer() == null ? "No Initializer Defined" : cache.getInitializer().getClass().getName()).append("</td></tr>");
        sb.append("</table>");

        sb.append("<h4><b>").append("Cache Server Mcast Details").append("</b></h4>");
        sb.append("<table><tr><td>").append("Mcast Port").append("</td><td>").append(cache.getDistributionManager().getConfig().getMcastPort()).append("</td></tr>");
        sb.append("<tr><td>").append("Mcast Address").append("</td><td>").append(cache.getDistributionManager().getConfig().getMcastAddress()).append("</td></tr>");
        sb.append("<tr><td>").append("Mcast Flow Control").append("</td><td>").append(cache.getDistributionManager().getConfig().getMcastFlowControl()).append("</td></tr>");
        sb.append("<tr><td>").append("Mcast Receive Buffer Size").append("</td><td>").append(cache.getDistributionManager().getConfig().getMcastRecvBufferSize()).append("</td></tr>");
        sb.append("<tr><td>").append("Mcast Send Buffer Size").append("</td><td>").append(cache.getDistributionManager().getConfig().getMcastSendBufferSize()).append("</td></tr>");
        sb.append("<tr><td>").append("Mcast TTL").append("</td><td>").append(cache.getDistributionManager().getConfig().getMcastTtl()).append("</td></tr>");
        sb.append("</table>");

        sb.append("<h4><b>").append("Cache Server Memcached Details").append("</b></h4>");
        sb.append("<table><tr><td>").append("Memcached Bind Address").append("</td><td>").append(cache.getDistributionManager().getConfig().getMemcachedBindAddress() == null || cache.getDistributionManager().getConfig().getMemcachedBindAddress().length() == 0 ? "No Memcached Bind Address Defined" : cache.getDistributionManager().getConfig().getMemcachedBindAddress()).append("</td></tr>");
        sb.append("<tr><td>").append("Memcached Port").append("</td><td>").append(cache.getDistributionManager().getConfig().getMemcachedPort()).append("</td></tr>");
        sb.append("<tr><td>").append("Memcached Protocol").append("</td><td>").append(cache.getDistributionManager().getConfig().getMemcachedProtocol()).append("</td></tr>");
        sb.append("</table>");
    }

    private void processGroups(InternalCache cache, StringBuilder sb, InternalDistributedMember currentMember) {
        sb.append("<h3><b>").append("Groups").append("</b></h3>");
        if (currentMember.getGroups() == null || currentMember.getGroups().size() == 0) {
            sb.append("<ui>").append("No Groups Defined").append("</ui><br>");
        } else {
            sb.append("<table><tr><td><b>").append("Group").append("</b></td></tr>");
            currentMember.getGroups().forEach(grp -> {
                sb.append("<tr><td>").append(grp).append("</td></tr>");
            });
            sb.append("</table>");
        }
    }

    private void processJVM(InternalCache cache, StringBuilder sb, HashMap<String, String> sysctl) {
        RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
        sb.append("<h3><b>").append("JVM Class Path").append("</b></h3>");

        String[] classPaths = runtimeMxBean.getClassPath().split(":");
        Arrays.stream(classPaths).sequential().forEach(classPath -> sb.append("<ui>").append(classPath).append("</ui><br>"));

        sb.append("<h3><b>").append("JVM Properties").append("</b></h3>");
        List<String> arguments = runtimeMxBean.getInputArguments();
        arguments.forEach(arg -> sb.append("<ui>").append(arg).append("</ui>").append("<br>"));

        sb.append("<h3><b>").append("System/User Limits").append("</b></h3>");
        try {
            List<String> lines = Files.readAllLines(Paths.get("/etc/security/limits.conf"));
            lines.forEach(line -> {
                if (!line.startsWith("#")) {
                    sb.append("<ui>").append(line).append("</ui>").append("<br>");
                }
            });
        } catch (IOException e) {
            sb.append("<ui>").append("Limits Not Found").append("</ui>").append("<br>");
        }

        sb.append("<h3><b>").append("System Configuration").append("</b></h3>");
        sysctl.forEach((k,v) -> {
            try {
                String line = Files.readString(Paths.get(v));
                sb.append("<ui>").append(k + ": ").append(line).append("</ui>").append("<br>");
            } catch (IOException e) {
                sb.append("<ui>").append(k + " property not found").append("</ui>").append("<br>");
            }
        });
    }

    private void processCacheServices(InternalCache cache, StringBuilder sb) {
        sb.append("<h3><b>").append("Cache Services").append("</b></h3>");
        Collection<CacheService> services = cache.getServices();
        if (services != null ) {
            sb.append("<table><tr><td><b>").append("Service").append("</b></td></tr>");
            if (services != null) {
                services.forEach(service -> sb.append("<tr><td>").append(service.getClass().getName()).append("</td></tr>"));
            } else {
                sb.append("<tr><td>").append("No Cache Services Defined").append("</td></tr>");
            }
            sb.append("</table>");
        } else {
            sb.append("<ui>").append("No Cache Services Defined").append("</ui><br>");
        }
    }

    private void processSecurity(InternalCache cache, StringBuilder sb) {
        sb.append("<h3><b>").append("Security Service").append("</b></h3>");
        sb.append("<table><tr><td><b>").append("Service").append("</b></td></tr>");
        if (cache.getSecurityService() == null) {
            sb.append("<tr><td>").append("No Security Service Defined").append("</td></tr></table>");
        } else {
            sb.append("<tr><td>").append(cache.getSecurityService().getClass().getName()).append("</td></tr></table>");
        }

        // security properties
        sb.append("<h3><b>").append("Security Properties").append("</b></h3>");
        if (cache.getInternalDistributedSystem().getSecurityProperties() == null || cache.getInternalDistributedSystem().getSecurityProperties().isEmpty()) {
            sb.append("<ui>").append("No Security Properties Defined").append("</ui><br>");
        } else {
            sb.append("<table><tr><td><b>").append("Property").append("</b></td><td><b>").append("Value").append("</b></td></tr>");
            cache.getInternalDistributedSystem().getSecurityProperties().forEach((k, v) -> {
                sb.append("<tr><td>").append(k == null ? "Not Defined" : k).append("</td><td>").append(v).append("</td></tr>");
            });
            sb.append("</table>");
        }
    }

    private void processSpringXml(InternalCache cache, StringBuilder sb, ServerLauncher serverLauncher) {
        sb.append("<h3><b>").append("Spring XML File - Distributed System").append("</b></h3>");
        if (serverLauncher.getSpringXmlLocation() != null) {
            File springXml = new File(serverLauncher.getSpringXmlLocation());
            if (springXml.exists()) {
                sb.append("<h3><b>").append("Spring XML File - Distributed System").append("</b></h3>");
                try {
                    List<String> xmlFileLines = Files.readAllLines(springXml.toPath());
                    xmlFileLines.forEach(line -> {
                        sb.append("<ui>").append(line).append("</ui><br>");
                    });
                } catch (IOException ex) {
                    log.warn("Unable to process Spring XML File", ex);
                    sb.append("<ui>").append("Unable to process Spring XML File").append("</ui><br>");
                }
            }
        } else {
            sb.append("<ui>").append("No Spring XML File Defined").append("</ui><br>");
        }

    }

    private void processCacheXml(InternalCache cache, StringBuilder sb) {
        sb.append("<h3><b>").append("Cache XML File - Distributed System").append("</b></h3>");
        if (cache.getInternalDistributedSystem().getConfig().getCacheXmlFile() != null &&
                cache.getInternalDistributedSystem().getConfig().getCacheXmlFile().exists()) {
            try {
                List<String> xmlFileLines = Files.readAllLines(cache.getInternalDistributedSystem().getConfig().getCacheXmlFile().toPath());
                xmlFileLines.forEach(line -> {
                    sb.append("<ui>").append(line).append("</ui><br>");
                });
            } catch (IOException ex) {
                log.warn("Unable to process Cache XML File", ex);
                sb.append("<ui>").append("Unable to process Cache XML File").append("</ui><br>");
            }
        } else {
            sb.append("<ui>").append("No Cache XML File Defined").append("</ui><br>");
        }
    }

    private void processMeters(InternalCache cache, StringBuilder sb) {
        sb.append("<h3><b>").append("Meter Registry").append("</b></h3>");
        if (cache.getMeterRegistry() != null && !cache.getMeterRegistry().getMeters().isEmpty())  {
            sb.append("<table><tr><td><b>").append("Meter Name").append("</b></td><td><b>").append("Value").append("</b></td><td><b>")
                    .append("Type").append("</b></td></tr>");
            CompositeMeterRegistry cmr = (CompositeMeterRegistry) cache.getMeterRegistry();
            if (cmr.getRegistries() != null && cmr.getRegistries().isEmpty()) {
                List<String> meters = new ArrayList<>();
                cache.getMeterRegistry().forEachMeter(meter -> {
                    if (!meters.contains(meter.getId().getName())) {
                        meters.add(meter.getId().getName());
                        log.debug("Meter name: {}, type {}", meter.getId().getName(), meter.getId().getType());
                        sb.append("<tr><td>").append(meter.getId().getName()).append("</td>");
                        sb.append("<td>").append(meter.getId().getDescription()).append("</td>");
                        sb.append("<td>").append(meter.getId().getType()).append("</td></tr>");
                    }
                });
            } else {
                cmr.getRegistries().forEach(registry -> {
                    log.info("Registry Tags: {}",registry.config().commonTags());
                    List<String> meters = new ArrayList<>();
                    registry.getMeters().forEach(meter -> {
                        if (!meters.contains(meter.getId().getName())) {
                            meters.add(meter.getId().getName());
                            log.debug("Meter name: {}, type {}", meter.getId().getName(), meter.getId().getType());
                            sb.append("<tr><td>").append(meter.getId().getName()).append("</td>");
                            sb.append("<td>").append(meter.getId().getDescription()).append("</td>");
                            sb.append("<td>").append(meter.getId().getType()).append("</td></tr>");
                        }
                    });
                });
            }
            sb.append("</table>");
        } else {
            sb.append("<ui>").append("No Registered Meters Defined").append("</ui><br>");
        }
    }

    private void processProperties(InternalCache cache, StringBuilder sb) {
        sb.append("<h3><b>").append("Properties - Distributed System").append("</b></h3>");
        sb.append("<table><tr><td><b>").append("Property").append("</b></td><td><b>").append("Value").append("</b></td></tr>");

        cache.getInternalDistributedSystem().getProperties().forEach((k, v) -> {
            sb.append("<tr><td>").append(k).append("</td><td>").append(v).append("</td></tr>");
        });

        sb.append("<tr><td>").append("DistributionManager.MAX.THREADS").append("</td><td>").append(System.getProperty("DistributionManager.MAX_THREADS") == null ? OperationExecutors.MAX_THREADS : System.getProperty("DistributionManager.MAX_THREADS")).append("</td></tr>");
        sb.append("<tr><td>").append("DistributionManager.MAX_FE_THREADS").append("</td><td>").append(System.getProperty("DistributionManager.MAX_FE_THREADS") == null ? OperationExecutors.MAX_FE_THREADS : System.getProperty("DistributionManager.MAX_FE_THREADS")).append("</td></tr>");
        sb.append("<tr><td>").append("gemfire.EXPIRY_THREADS").append("</td><td>").append(System.getProperty("gemfire.EXPIRY_THREADS") == null ? "No Expiry Threads Defined" : System.getProperty("gemfire.EXPIRY_THREADS")).append("</td></tr>");
        sb.append("<tr><td>").append("gemfire.non-replicated-tombstone-timeout").append("</td><td>").append(System.getProperty("gemfire.non-replicated-tombstone-timeout") == null ? TombstoneService.NON_REPLICATE_TOMBSTONE_TIMEOUT_DEFAULT : System.getProperty("gemfire.non-replicated-tombstone-timeout")).append("</td></tr>");
        sb.append("<tr><td>").append("gemfire.tombstone-gc-threshold").append("</td><td>").append(System.getProperty("gemfire.tombstone-gc-threshold") == null ? TombstoneService.GC_MEMORY_THRESHOLD : System.getProperty("gemfire.tombstone-gc-threshold")).append("</td></tr>");
        sb.append("<tr><td>").append("gemfire.tombstone-scan-interval").append("</td><td>").append(System.getProperty("gemfire.tombstone-scan-interval") == null ? TombstoneService.DEFUNCT_TOMBSTONE_SCAN_INTERVAL_DEFAULT : System.getProperty("gemfire.tombstone-scan-interval")).append("</td></tr>");
        sb.append("<tr><td>").append("gemfire.tombstone-gc-memory-threshold").append("</td><td>").append(System.getProperty("gemfire.tombstone-gc-memory-threshold") == null ? TombstoneService.GC_MEMORY_THRESHOLD_DEFAULT : System.getProperty("gemfire.tombstone-gc-memory-threshold")).append("</td></tr>");
        sb.append("<tr><td>").append("gemfire.Cache.EVENT_THREAD_LIMIT").append("</td><td>").append(System.getProperty("gemfire.Cache.EVENT_THREAD_LIMIT") == null ? "16" : System.getProperty("gemfire.Cache.EVENT_THREAD_LIMIT")).append("</td></tr>");
        sb.append("<tr><td>").append("gemfire.Cache.EVENT_QUEUE_LIMIT").append("</td><td>").append(System.getProperty("gemfire.Cache.EVENT_QUEUE_LIMIT") == null ? "4096" : System.getProperty("gemfire.Cache.EVENT_QUEUE_LIMIT")).append("</td></tr>");
        sb.append("<tr><td>").append("gemfire.ALLOW_PERSISTENT_TRANSACTIONS").append("</td><td>").append(System.getProperty("gemfire.ALLOW_PERSISTENT_TRANSACTIONS") == null ? TXManagerImpl.ALLOW_PERSISTENT_TRANSACTIONS : System.getProperty("gemfire.ALLOW_PERSISTENT_TRANSACTIONS")).append("</td></tr>");
        sb.append("<tr><td>").append("gemfire.transactionFailoverMapSize").append("</td><td>").append(System.getProperty("gemfire.transactionFailoverMapSize") == null ? TXManagerImpl.FAILOVER_TX_MAP_SIZE : System.getProperty("gemfire.transactionFailoverMapSize")).append("</td></tr>");
        sb.append("<tr><td>").append("gemfire.DEFAULT_MAX_OPLOG_SIZE").append("</td><td>").append(System.getProperty("gemfire.DEFAULT_MAX_OPLOG_SIZE") == null ? DiskWriteAttributesImpl.getDefaultMaxOplogSize() : System.getProperty("gemfire.DEFAULT_MAX_OPLOG_SIZE")).append("</td></tr>");
        sb.append("<tr><td>").append("gemfire.MAX_OPEN_INACTIVE_OPLOGS").append("</td><td>").append(System.getProperty("gemfire.MAX_OPEN_INACTIVE_OPLOGS") == null ? DiskStoreImpl.MAX_OPEN_INACTIVE_OPLOGS : System.getProperty("gemfire.MAX_OPEN_INACTIVE_OPLOGS")).append("</td></tr>");
        sb.append("<tr><td>").append("gemfire.MIN_DISK_SPACE_FOR_LOGS").append("</td><td>").append(System.getProperty("gemfire.MIN_DISK_SPACE_FOR_LOGS") == null ? DiskStoreImpl.MIN_DISK_SPACE_FOR_LOGS : System.getProperty("gemfire.MIN_DISK_SPACE_FOR_LOGS")).append("</td></tr>");
        sb.append("<tr><td>").append("gemfire.OVERFLOW_ROLL_PERCENTAGE").append("</td><td>").append(System.getProperty("gemfire.OVERFLOW_ROLL_PERCENTAGE") == null ? "50%" : System.getProperty("gemfire.OVERFLOW_ROLL_PERCENTAGE")).append("</td></tr>");
        sb.append("<tr><td>").append("gemfire.SHUTDOWN_ALL_POOL_SIZE").append("</td><td>").append(System.getProperty("gemfire.SHUTDOWN_ALL_POOL_SIZE") == null ? "-1" : System.getProperty("gemfire.SHUTDOWN_ALL_POOL_SIZE")).append("</td></tr>");
        sb.append("<tr><td>").append("gemfire.Cache.MAX_QUERY_EXECUTION_TIME").append("</td><td>").append(System.getProperty("gemfire.Cache.MAX_QUERY_EXECUTION_TIME") == null ? GemFireCacheImpl.MAX_QUERY_EXECUTION_TIME : System.getProperty("gemfire.Cache.MAX_QUERY_EXECUTION_TIME")).append("</td></tr>");
        sb.append("<tr><td>").append("gemfire.bridge.suppressIOExceptionLogging").append("</td><td>").append(System.getProperty("gemfire.bridge.suppressIOExceptionLogging") == null ? "false" : System.getProperty("gemfire.bridge.suppressIOExceptionLogging")).append("</td></tr>");
        sb.append("<tr><td>").append("gemfire.Query.VERBOSE").append("</td><td>").append(System.getProperty("gemfire.Query.VERBOSE") == null ? DefaultQuery.QUERY_VERBOSE : System.getProperty("gemfire.Query.VERBOSE")).append("</td></tr>");
        sb.append("<tr><td>").append("p2p.backlog").append("</td><td>").append(System.getProperty("p2p.backlog") == null ? 1280 : System.getProperty("p2p.backlog")).append("</td></tr>");
        sb.append("<tr><td>").append("p2p.listenerCloseTimeout").append("</td><td>").append(System.getProperty("p2p.listenerCloseTimeout") == null ? 60000 : System.getProperty("p2p.listenerCloseTimeout")).append("</td></tr>");
        sb.append("</table>");
    }

    private void processBackup(InternalCache cache, StringBuilder sb) {
        sb.append("<h3><b>").append("Backup").append("</b></h3>");
        if (cache.getBackupService() == null) {
            sb.append("<ui>").append("No Backup Service Defined").append("</ui>").append("<br>");
            return;
        }
        sb.append("<table><tr><td><b>").append("Property").append("</b></td><td><b>").append("Value").append("</b></td></tr>");
        sb.append("<tr><td>").append("Service Name").append("</td><td>").append(cache.getBackupService().getClass().getName()).append("</td></tr>");
        sb.append("<tr><td>").append("Files").append("</td><td>");
        if (cache.getBackupFiles() == null || cache.getBackupFiles().isEmpty()) {
            sb.append("No Backup Files Defined").append("</td></tr>");
        } else {
            for (int i = 0; i < cache.getBackupFiles().size(); i++) {
                if (i == 0) {
                    sb.append(cache.getBackupFiles().get(i).getClass().getName()).append("</td></tr>");
                } else {
                    sb.append("<tr><td>").append("").append("</td><td>").append(cache.getBackupFiles().get(i).getClass().getName()).append("</td></tr>");
                }
            }
        }
        sb.append("</table>");
    }

    private void processTxMgr(InternalCache cache, StringBuilder sb) {
        sb.append("<h3><b>").append("Transaction Manager").append("</b></h3>");
        if (cache.getTxManager() == null) {
            sb.append("<ui>").append("No Transaction Manager Defined").append("</ui>").append("<br>");
            return;
        }
        sb.append("<table><tr><td><b>").append("Property").append("</b></td><td><b>").append("Value").append("</b></td></tr>");
        sb.append("<tr><td>").append("TX Manager Name").append("</td><td>").append(cache.getTxManager().getClass().getName()).append("</td></tr>");
        sb.append("<tr><td>").append("Writer Name").append("</td><td>").append(cache.getTxManager().getWriter() == null ? "No Transaction Writer Defined" : cache.getTxManager().getWriter().getClass().getName()).append("</td></tr>");
        sb.append("<tr><td>").append("Listener Name").append("</td><td>").append(cache.getTxManager().getListener() == null ? "No Transaction Listener Defined" : cache.getTxManager().getListener().getClass().getName()).append("</td></tr>");
        sb.append("<tr><td>").append("Transaction Time To Live").append("</td><td>").append(cache.getTxManager().getTransactionTimeToLive()).append("</td></tr>");
        sb.append("<tr><td>").append("Suspend Transaction Timeout").append("</td><td>").append(cache.getTxManager().getSuspendedTransactionTimeout()).append("</td></tr></table>");
    }

    private void processPdx(InternalCache cache, StringBuilder sb) {
        sb.append("<h3><b>").append("PDX").append("</b></h3>");
        if (cache.getPdxSerializer() == null) {
            sb.append("<ui>").append("No PDX Serializer Defined").append("</ui>").append("<br>");
            return;
        }
        sb.append("<table><tr><td><b>").append("Property").append("</b></td><td><b>").append("Value").append("</b></td></tr>");
        sb.append("<tr><td>").append("Persistent").append("</td><td>").append(cache.getPdxPersistent()).append("</td></tr>");
        sb.append("<tr><td>").append("PDX Disk Store Name").append("</td><td>").append(cache.getPdxDiskStore()).append("</td></tr>");
        sb.append("<tr><td>").append("Read Serialized").append("</td><td>").append(cache.getPdxReadSerialized()).append("</td></tr>");
        sb.append("<tr><td>").append("Ignore Unread Fields").append("</td><td>").append(cache.getPdxIgnoreUnreadFields()).append("</td></tr>");
        sb.append("<tr><td>").append("Serializer Name").append("</td><td>").append(cache.getPdxSerializer().getClass().getName()).append("</td></tr></table>");
    }

    private void processResourceManager(InternalCache cache, StringBuilder sb) {
        sb.append("<h3><b>").append("Resource Manager").append("</b></h3>");
        ResourceManager rm = cache.getResourceManager();
        sb.append("<table><tr><td><b>").append("Property").append("</b></td><td><b>").append("Value").append("</b></td></tr>");
        sb.append("<tr><td>").append("EvictionHeapPercent").append("</td><td>").append(rm.getEvictionHeapPercentage()).append("</td></tr>");
        sb.append("<tr><td>").append("CriticalHeapPercent").append("</td><td>").append(rm.getCriticalHeapPercentage()).append("</td></tr>");
        sb.append("<tr><td>").append("EvictionOffHeapPercent").append("</td><td>").append(rm.getEvictionOffHeapPercentage()).append("</td></tr>");
        sb.append("<tr><td>").append("CriticalOffHeapPercent").append("</td><td>").append(rm.getCriticalOffHeapPercentage()).append("</td></tr></table>");
    }

    private void processAsync(InternalCache cache, StringBuilder sb) {
        sb.append("<h3><b>").append("Async Event Queues").append("</b></h3>");
        if (cache.getAsyncEventQueues() == null || cache.getAsyncEventQueues().isEmpty()) {
            sb.append("<ui>").append("No Async Event Queues Defined").append("</ui>").append("<br>");
        } else {
            sb.append("<table><tr><td><b>").append("Property").append("</b></td><td><b>").append("Value").append("</b></td></tr>");
            cache.getAsyncEventQueues().forEach(queue -> {
                sb.append("<tr><td>").append("Id").append("</td><td>").append(queue.getId()).append("</td></tr>");
                sb.append("<tr><td>").append("Persistent").append("</td><td>").append(queue.isPersistent()).append("</td></tr>");
                sb.append("<tr><td>").append("Queue Disk Store Name").append("</td><td>").append(queue.getDiskStoreName()).append("</td></tr>");
                sb.append("<tr><td>").append("Batch Size").append("</td><td>").append(queue.getBatchSize()).append("</td></tr>");
                sb.append("<tr><td>").append("Batch Time Interval").append("</td><td>").append(queue.getBatchTimeInterval()).append("</td></tr>");
                sb.append("<tr><td>").append("Dispatcher Threads").append("</td><td>").append(queue.getDispatcherThreads()).append("</td></tr>");
                sb.append("<tr><td>").append("Maximum Queue Memory").append("</td><td>").append(queue.getMaximumQueueMemory()).append("</td></tr>");
                sb.append("<tr><td>").append("Order Policy").append("</td><td>").append(queue.getOrderPolicy()).append("</td></tr>");
                sb.append("<tr><td>").append("Batch Conflation Enabled").append("</td><td>").append(queue.isBatchConflationEnabled()).append("</td></tr>");
                sb.append("<tr><td>").append("Parallel").append("</td><td>").append(queue.isParallel()).append("</td></tr>");
                sb.append("<tr><td>").append("Async Event Listener").append("</td><td>").append(queue.getAsyncEventListener().getClass().getName()).append("</td></tr>");
                if (queue.getGatewayEventSubstitutionFilter() == null) {
                    sb.append("<tr><td>").append("Async Gateway Event Substitution Filter").append("</td><td>").append("No Event Substitution Filter Defined").append("</td></tr>");
                } else {
                    sb.append("<tr><td>").append("Async Gateway Event Substitution Filter").append("</td><td>").append(queue.getGatewayEventSubstitutionFilter().getClass().getName()).append("</td></tr>");
                }
                sb.append("<tr><td>").append("Async Gateway Event Filter").append("</td><td>");
                if (queue.getGatewayEventFilters() == null || queue.getGatewayEventFilters().isEmpty()) {
                    sb.append("No Gateway Event Filters Defined").append("</td></tr>");
                } else {
                    for (int i = 0; i < queue.getGatewayEventFilters().size(); i++) {
                        if (i == 0) {
                            sb.append(queue.getGatewayEventFilters().get(i).getClass().getName()).append("</td></tr>");
                        } else {
                            sb.append("<tr><td>").append("").append("</td><td>").append(queue.getGatewayEventFilters().get(i).getClass().getName()).append("</td></tr>");
                        }
                    }
                }
                sb.append("<tr><td><b>").append("--------------").append("</b></td><td><b>").append("--------------").append("</b></td></tr>");
            });
            sb.append("</table>");
        }
    }

    private void processGatewaySenders(InternalCache cache, StringBuilder sb) {
        sb.append("<h3><b>").append("Gateway Senders").append("</b></h3>");
        if (cache.getAllGatewaySenders() == null || cache.getAllGatewaySenders().isEmpty()) {
            sb.append("<ui>").append("  No Gateway Senders Defined").append("</ui>").append("<br>");
        } else {
            sb.append("<table><tr><td><b>").append("Property").append("</b></td><td><b>").append("Value").append("</b></td></tr>");
            cache.getGatewaySenders().forEach(sender -> {
                sender.getRemoteDSId();
                sender.getEnforceThreadsConnectSameReceiver();
                sender.getMaxParallelismForReplicatedRegion();
                sb.append("<tr><td>").append("Sender Id").append("</td><td>").append(sender.getId()).append("</td></tr>");
                sb.append("<tr><td>").append("Disk Persistence Enabled").append("</td><td>").append(sender.isPersistenceEnabled()).append("</td></tr>");
                sb.append("<tr><td>").append("Sender Disk Store Name").append("</td><td>").append(sender.getDiskStoreName()).append("</td></tr>");
                sb.append("<tr><td>").append("Batch Size").append("</td><td>").append(sender.getBatchSize()).append("</td></tr>");
                sb.append("<tr><td>").append("Batch Interval Time").append("</td><td>").append(sender.getBatchTimeInterval()).append("</td></tr>");
                sb.append("<tr><td>").append("Dispatcher Threads").append("</td><td>").append(sender.getDispatcherThreads()).append("</td></tr>");
                sb.append("<tr><td>").append("Alert Threshold").append("</td><td>").append(sender.getAlertThreshold()).append("</td></tr>");
                sb.append("<tr><td>").append("Max Queue Memory").append("</td><td>").append(sender.getMaximumQueueMemory()).append("</td></tr>");
                sb.append("<tr><td>").append("Order Policy").append("</td><td>").append(sender.getOrderPolicy()).append("</td></tr>");
                sb.append("<tr><td>").append("Socket Buffer Size").append("</td><td>").append(sender.getSocketBufferSize()).append("</td></tr>");
                sb.append("<tr><td>").append("Socket Read Timeout").append("</td><td>").append(sender.getSocketReadTimeout()).append("</td></tr>");
                sb.append("<tr><td>").append("Batch Conflation Enabled").append("</td><td>").append(sender.isBatchConflationEnabled()).append("</td></tr>");
                sb.append("<tr><td>").append("Parallel Sender").append("</td><td>").append(sender.isParallel()).append("</td></tr>");
                sb.append("<tr><td>").append("Remote Distributed System Id").append("</td><td>").append(sender.getRemoteDSId()).append("</td></tr>");
                sb.append("<tr><td>").append("Enforce Threads Connect Same Receiver").append("</td><td>").append(sender.getEnforceThreadsConnectSameReceiver()).append("</td></tr>");
                sb.append("<tr><td>").append("Max Parallelism For Replicated Region").append("</td><td>").append(sender.getMaxParallelismForReplicatedRegion()).append("</td></tr>");
                if (sender.getGatewayEventSubstitutionFilter() == null) {
                    sb.append("<tr><td>").append("Gateway Substitution Filter").append("</td><td>").append("No Gateway Substitution Filer Defined").append("</td></tr>");
                } else {
                    sb.append("<tr><td>").append("Gateway Substitution Filter").append("</td><td>").append(sender.getGatewayEventSubstitutionFilter().getClass().getName()).append("</td></tr>");
                }
                sb.append("<tr><td>").append("Gateway Event Filters").append("</td><td>");
                if (sender.getGatewayEventFilters() == null || sender.getGatewayEventFilters().size() == 0) {
                    sb.append("No Gateway Event Filters Defined").append("</td></tr>");
                } else {
                    for (int i = 0; i < sender.getGatewayEventFilters().size(); i++) {
                        if (i == 0) {
                            sb.append(sender.getGatewayEventFilters().get(i).getClass().getName()).append("</td></tr>");
                        } else {
                            sb.append("<tr><td>").append("").append("</td><td>").append(sender.getGatewayEventFilters().get(i).getClass().getName()).append("</td></tr>");
                        }
                    }
                }
                sb.append("<tr><td>").append("Gateway Transport Filters").append("</td><td>");
                if (sender.getGatewayTransportFilters() == null || sender.getGatewayTransportFilters().size() == 0) {
                    sb.append("No Gateway Transport Filters Defined").append("</td></tr>");
                } else {
                    for (int i = 0; i < sender.getGatewayTransportFilters().size(); i++) {
                        if (i == 0) {
                            sb.append(sender.getGatewayTransportFilters().get(i).getClass().getName()).append("</td></tr>");
                        } else {
                            sb.append("<tr><td>").append("").append("</td><td>").append(sender.getGatewayTransportFilters().get(i).getClass().getName()).append("</td></tr>");
                        }
                    }
                }
                sb.append("<tr><td><b>").append("--------------").append("</b></td><td><b>").append("--------------").append("</b></td></tr>");
            });
            sb.append("</table>");
        }
    }

    private void processGatewayReceivers(InternalCache cache, StringBuilder sb) {
        sb.append("<h3><b>").append("Gateway Receivers").append("</b></h3>");
        if (cache.getGatewayReceivers() == null || cache.getGatewayReceivers().isEmpty()) {
            sb.append("<ui>").append("No Gateway Receivers Defined").append("</ui>").append("<br>");
        } else {
            sb.append("<table><tr><td><b>").append("Property").append("</b></td><td><b>").append("Value").append("</b></td></tr>");
            cache.getGatewayReceivers().forEach(receiver -> {
                sb.append("<tr><td>").append("Host Name").append("</td><td>").append(receiver.getHost()).append("</td></tr>");
                sb.append("<tr><td>").append("Host Name For Senders").append("</td><td>").append(receiver.getHostnameForSenders()).append("</td></tr>");
                sb.append("<tr><td>").append("Receiver Bind Address").append("</td><td>").append(receiver.getBindAddress()).append("</td></tr>");
                sb.append("<tr><td>").append("Starting Port Number").append("</td><td>").append(receiver.getStartPort()).append("</td></tr>");
                sb.append("<tr><td>").append("End Port Number").append("</td><td>").append(receiver.getEndPort()).append("</td></tr>");
                sb.append("<tr><td>").append("Maximum Time Between Pings").append("</td><td>").append(receiver.getMaximumTimeBetweenPings()).append("</td></tr>");
                sb.append("<tr><td>").append("Socket Buffer Size").append("</td><td>").append(receiver.getSocketBufferSize()).append("</td></tr>");
                sb.append("<tr><td>").append("Manual Start").append("</td><td>").append(receiver.isManualStart()).append("</td></tr>");
                sb.append("<tr><td>").append("Transport Filters").append("</td><td>");
                if (receiver.getGatewayTransportFilters() == null || receiver.getGatewayTransportFilters().size() == 0) {
                    sb.append("No Gateway Receiver Transport Filters Defined").append("</td></tr>");
                } else {
                    for (int i = 0; i < receiver.getGatewayTransportFilters().size(); i++) {
                        if (i == 0) {
                            sb.append(receiver.getGatewayTransportFilters().get(i).getClass().getName()).append("</td></tr>");
                        } else {
                            sb.append("<tr><td>").append(receiver.getGatewayTransportFilters().get(i).getClass().getName()).append("</td></tr>");
                        }
                    }
                }
            });
            sb.append("</table>");
        }
    }

    private void processLuceneIndexes(final InternalCache cache, final String name, final String regionName, StringBuilder sb) {
        sb.append("<h4><b>").append("Lucene Indexes").append("</b></h4>");
        final String regionPath;
        if (!regionName.startsWith("/")) {
            regionPath = "/" + regionName;
        } else {
            regionPath = regionName;
        }
        Set<LuceneIndexDetails> indexDetailsSet = getLuceneIndexes(cache, regionPath);
        if (indexDetailsSet.isEmpty()) {
            sb.append("<ui>").append("No Lucene Indexes Defined").append("</ui><br>");
        } else {
            sb.append("<table><tr><td><b>").append("Index Name").append("</b></td><td><b>").append("Field Analyzer").append("</b></td><td><b>")
                    .append("Searchable Fields").append("</b></td><td><b>").append("Serializer").append("</b></td></tr>");
            indexDetailsSet.forEach(indexDetail -> {
                if (indexDetail.getServerName().equals(name) && indexDetail.getRegionPath().equals(regionPath)) {
                    sb.append("<tr><td>").append(indexDetail.getIndexName()).append("</td><td>").append(indexDetail.getFieldAnalyzersString())
                            .append("</td><td>").append(indexDetail.getSearchableFieldNamesString()).append("</td><td>")
                            .append(indexDetail.getSerializerString()).append("</td></tr>");
                }
            });
            sb.append("</table>");
        }
    }

    private Set<LuceneIndexDetails> getLuceneIndexes(InternalCache cache, String regionName) {
        Set<LuceneIndexDetails> indexDetailsSet = new HashSet<>();
        String serverName = cache.getDistributedSystem().getDistributedMember().getName();
        LuceneServiceImpl service = (LuceneServiceImpl) LuceneServiceProvider.get(cache);
        for (LuceneIndex index : service.getIndexes(regionName)) {
            LuceneIndexStatus initialized;
            if (index.isIndexingInProgress()) {
                initialized = LuceneIndexStatus.INDEXING_IN_PROGRESS;
            } else {
                initialized = LuceneIndexStatus.INITIALIZED;
            }
            indexDetailsSet.add(new LuceneIndexDetails((LuceneIndexImpl) index, serverName, initialized));
        }
        return indexDetailsSet;
    }

    private void processRegions(InternalCache cache, Set<InternalRegion> regions, StringBuilder sb, String serverName) {
        sb.append("<h3><b>").append("Regions").append("</b></h3>");
        regions.forEach(region -> {
            processRegion(cache, region, region.getParentRegion() != null ? true : false, sb, serverName);
            sb.append("<b>").append("---------------------------------").append("</b><br>");
        });
    }

    private void processRegion(InternalCache cache, InternalRegion region, boolean isSubRegion, StringBuilder sb, String serverName) {
        sb.append("<h4><b>").append("Region Name: ").append(region.getName()).append("</b></h4>");
        sb.append("<table><tr><td><b>").append("Property").append("</b></td><td><b>").append("Value").append("</b></td></tr>");
        sb.append("<tr><td>").append("Sub-Region").append("</td><td>").append(isSubRegion).append("</td></tr>");
        sb.append("<tr><td>").append("Path").append("</td><td>").append(region.getFullPath()).append("</td></tr>");
        sb.append("<tr><td>").append("Number Server Keys").append("</td><td>").append(region.keySet() == null ? 0 : region.keySet().size()).append("</td></tr>");
        sb.append("<tr><td>").append("Statistics Enabled").append("</td><td>").append(region.getAttributes().getStatisticsEnabled()).append("</td></tr>");
        sb.append("<tr><td>").append("Off Heap").append("</td><td>").append(region.getAttributes().getOffHeap()).append("</td></tr>");
        sb.append("<tr><td>").append("Async Conflation Enabled").append("</td><td>").append(region.getAttributes().getEnableAsyncConflation()).append("</td></tr>");
        sb.append("<tr><td>").append("Subscription Conflation Enabled").append("</td><td>").append(region.getAttributes().getEnableSubscriptionConflation()).append("</td></tr>");
        sb.append("<tr><td>").append("Concurrency Level").append("</td><td>").append(region.getAttributes().getConcurrencyLevel()).append("</td></tr>");
        sb.append("<tr><td>").append("Compressor").append("</td><td>").append(region.getAttributes().getCompressor() == null ? "No Compressor Defined" : region.getAttributes().getCompressor().getClass().getName()).append("</td></tr>");
        sb.append("<tr><td>").append("Region Scope").append("</td><td>").append(region.getAttributes().getScope() == null ? "Not Defined" : region.getAttributes().getScope().toString()).append("</td></tr>");
        sb.append("<tr><td>").append("Data Policy").append("</td><td>").append(region.getAttributes().getDataPolicy() == null ? "Not Defined" : region.getAttributes().getDataPolicy().toString()).append("</td></tr>");
        AtomicBoolean first = new AtomicBoolean(true);
        if (region.getGatewaySenderIds() != null && region.getGatewaySenderIds().size() > 0) {
            region.getGatewaySenderIds().forEach(id -> {
                if (first.get()) {
                    sb.append("<tr><td>").append("Gateway Sender Id(s)").append("</td><td>").append(id).append("</td></tr>");
                    first.set(false);
                } else {
                    sb.append("<tr><td>").append("").append("</td><td>").append(id).append("</td></tr>");
                }
            });
        } else {
            sb.append("<tr><td>").append("Gateway Sender Id(s)").append("</td><td>").append("No Gateway Senders Defined").append("</td></tr>");
        }
        first.set(true);
        if (region.getAsyncEventQueueIds() != null && region.getAsyncEventQueueIds().size() > 0) {
            region.getAsyncEventQueueIds().forEach(id -> {
                if (first.get()) {
                    sb.append("<tr><td>").append("Async Event Queue Id(s)").append("</td><td>").append(id).append("</td></tr>");
                    first.set(false);
                } else {
                    sb.append("<tr><td>").append("").append("</td><td>").append(id).append("</td></tr>");
                }
            });
        } else {
            sb.append("<tr><td>").append("Async Event Queue Id(s)").append("</td><td>").append("No Async Event Queues Defined").append("</td></tr>");
        }


        if (region.getKeyConstraint() != null) {
            sb.append("<tr><td>").append("Key Constraint").append("</td><td>").append(region.getKeyConstraint().getName()).append("</td></tr>");
        } else {
            sb.append("<tr><td>").append("Key Constraint").append("</td><td>").append("No Key Constraint Defined").append("</td></tr>");
        }

        if (region.getValueConstraint() != null) {
            sb.append("<tr><td>").append("Value Constraint").append("</td><td>").append(region.getValueConstraint().getName()).append("</td></tr>");
        } else {
            sb.append("<tr><td>").append("Value Constraint").append("</td><td>").append("No Value Constraint Defined").append("</td></tr>");
        }

        if (region.getDataPolicy().withPartitioning()) {
            PartitionAttributes<?, ?> partitionAttr = region.getAttributes().getPartitionAttributes();
            sb.append("<tr><td>").append("Co-located With").append("</td><td>").append(partitionAttr.getColocatedWith() == null ? "Not Co-located With Any Region" : partitionAttr.getColocatedWith()).append("</td></tr>");
            sb.append("<tr><td>").append("Local Max Memory").append("</td><td>").append(partitionAttr.getLocalMaxMemory()).append("</td></tr>");
            sb.append("<tr><td>").append("Redundant Copies").append("</td><td>").append(partitionAttr.getRedundantCopies()).append("</td></tr>");
            sb.append("<tr><td>").append("Number Buckets").append("</td><td>").append(partitionAttr.getTotalNumBuckets()).append("</td></tr>");
            sb.append("<tr><td>").append("Recovery Delay").append("</td><td>").append(partitionAttr.getRecoveryDelay()).append("</td></tr>");
            sb.append("<tr><td>").append("Startup Recovery Delay").append("</td><td>").append(partitionAttr.getStartupRecoveryDelay()).append("</td></tr>");
            PartitionResolver<?, ?> partitionResolver = partitionAttr.getPartitionResolver();
            sb.append("<tr><td>").append("Partition Resolver").append("</td><td>").append(partitionResolver == null ? "No Partition Resolver Defined" : partitionResolver.getClass().getName())
                    .append("</td></tr>");

            PartitionListener[] partitionListeners = partitionAttr.getPartitionListeners();
            sb.append("<tr><td>").append("Partition Listeners").append("</td>");
            if (partitionListeners == null || partitionListeners.length == 0) {
                sb.append("<td>").append("No Partition Listeners Defined").append("</td></tr>");
            } else {
                for (int i = 0; i < partitionListeners.length; i++) {
                    if (i == 0) {
                        sb.append("<td>").append(partitionListeners[i].getClass().getName()).append("</td></tr>");
                    } else {
                        sb.append("<tr><td>").append("").append("</td><td>").append(partitionListeners[i].getClass().getName()).append("</td></tr>");
                    }
                }
            }
        }
        sb.append("</table>");

        sb.append("<h4><b>").append("Disk Store").append("</b></h4>");
        if (region.getAttributes().getDiskStoreName() != null) {
            DiskStore diskStore = cache.findDiskStore(region.getAttributes().getDiskStoreName());
            processDisks(diskStore, sb);
        } else {
            sb.append("<ui>").append("No Disk Store Defined").append("</ui>").append("<br>");
        }

        sb.append("<h4><b>").append("GemFire Indexes").append("</b></h4>");
        if (cache.getQueryService().getIndexes(region) == null || cache.getQueryService().getIndexes(region).size() == 0) {
            sb.append("<ui>").append("No Indexes Defined").append("</ui><br>");
        } else {
            sb.append("<table><tr><td><b>").append("Index Name").append("</b></td><td><b>").append("Type").append("</b></td><td><b>")
                    .append("From Clause").append("</b></td><td><b>").append("Indexed Expression").append("</b></td></tr>");
            cache.getQueryService().getIndexes(region).forEach(index -> {
                sb.append("<tr><td>").append(index.getName()).append("</td><td>").append(index.getType().getName())
                        .append("</td><td>").append(index.getFromClause()).append("</td><td>")
                        .append(index.getIndexedExpression()).append("</td></tr>");
            });
            sb.append("</table>");
        }

        processLuceneIndexes(cache, serverName, region.getName(), sb);

        sb.append("<h4><b>").append("Cache Listeners").append("</b></h4>");
        CacheListener<?, ?>[] listeners = region.getAttributes().getCacheListeners();
        if (listeners == null || listeners.length == 0) {
            sb.append("<ui>").append("No Cache Listeners Defined").append("</ui>").append("<br>");
        } else {
            sb.append("<table><tr><td><b>").append("Property").append("</b></td><td><b>").append("Value").append("</b></td></tr>");
            for (CacheListener<?, ?> listener : listeners)
                sb.append("<tr><td>").append("Class Name").append("</td><td>").append(listener.getClass().getName()).append("</td></tr>");
            sb.append("</table>");
        }

        sb.append("<h4><b>").append("Cache Loader").append("</b></h4>");
        if (region.getAttributes().getCacheLoader() == null) {
            sb.append("<ui>").append("No Cache Loader Defined").append("</ui>").append("<br>");
        } else {
            CacheLoader<?, ?> loader = region.getAttributes().getCacheLoader();
            sb.append("<table><tr><td><b>").append("Property").append("</b></td><td><b>").append("Value").append("</b></td></tr>");
            sb.append("<tr><td>").append("Class Name").append("</td><td>").append(loader.getClass().getName()).append("</td></tr></table>");
        }

        sb.append("<h4><b>").append("Cache Writer").append("</b></h4>");
        if (region.getAttributes().getCacheWriter() == null) {
            sb.append("<ui>").append("No Cache Writer Defined").append("</ui>").append("<br>");
        } else {
            sb.append("<table><tr><td><b>").append("Property").append("</b></td><td><b>").append("Value").append("</b></td></tr>");
            CacheWriter<?, ?> writer = region.getAttributes().getCacheWriter();
            sb.append("<tr><td>").append("Class Name").append("</td><td>").append(writer.getClass().getName()).append("</td></tr></table>");
        }

        sb.append("<h4><b>").append("Custom Entry Idle").append("</b></h4>");
        CustomExpiry<?, ?> customIdleTime = region.getAttributes().getCustomEntryIdleTimeout();
        if (customIdleTime == null) {
            sb.append("<ui>").append("No Custom Entry Idle Defined").append("</ui>").append("<br>");
        } else {
            sb.append("<table><tr><td><b>").append("Property").append("</b></td><td><b>").append("Value").append("</b></td></tr>");
            sb.append("<tr><td>").append("Class Name").append("</td><td>").append(customIdleTime.getClass().getName()).append("</td></tr>");
            if (!region.entrySet().isEmpty()) {
                Optional<Map.Entry<?, ?>> any = region.entrySet().stream().findAny();
                if (any.isPresent()) {
                    ExpirationAttributes customIdleTimeExpireAttr = customIdleTime.getExpiry((Region.Entry) any.get());
                    sb.append("<tr><td>").append("Timeout").append("</td><td>").append(customIdleTimeExpireAttr == null ? "None" : customIdleTimeExpireAttr.getTimeout())
                            .append("</td></tr>");
                    sb.append("<tr><td>").append("Action").append("</td><td>").append(customIdleTimeExpireAttr == null ? "None" : customIdleTimeExpireAttr.getAction().toString())
                            .append("</td></tr>");
                } else {
                    sb.append("<tr><td>").append("Timeout").append("</td><td>").append("No Entries Found - Cannot Determine Value").append("</td></tr>");
                    sb.append("<tr><td>").append("Action").append("</td><td>").append("No Entries Found - Cannot Determine Value").append("</td></tr>");
                }
            } else {
                sb.append("<tr><td>").append("Timeout").append("</td><td>").append("No Entries Found - Cannot Determine Value").append("</td></tr>");
                sb.append("<tr><td>").append("Action").append("</td><td>").append("No Entries Found - Cannot Determine Value").append("</td></tr>");
            }
            sb.append("</table>");
        }

        sb.append("<h4><b>").append("Custom Time To Live").append("</b></h4>");
        CustomExpiry<?, ?> customTimeToLive = region.getAttributes().getCustomEntryTimeToLive();
        if (customTimeToLive == null) {
            sb.append("<ui>").append("No Custom Time To Live Defined").append("</ui>").append("<br>");
        } else {
            sb.append("<table><tr><td><b>").append("Property").append("</b></td><td><b>").append("Value").append("</b></td></tr>");
            sb.append("<tr><td>").append("Class Name").append("</td><td>").append(customTimeToLive.getClass().getName()).append("</td></tr>");
            if (!region.entrySet().isEmpty()) {
                Optional<Map.Entry<?, ?>> any = region.entrySet().stream().findAny();
                if (any.isPresent()) {
                    ExpirationAttributes customTimeToLiveExpireAttr = customTimeToLive.getExpiry((Region.Entry) any.get());
                    sb.append("<tr><td>").append("Timeout").append("</td><td>").append(customTimeToLiveExpireAttr == null ? "None" : customTimeToLiveExpireAttr.getTimeout())
                            .append("</td></tr>");
                    sb.append("<tr><td>").append("Action").append("</td><td>")
                            .append(customTimeToLiveExpireAttr == null ? "None" : customTimeToLiveExpireAttr.getAction())
                            .append("</td></tr>");
                } else {
                    sb.append("<tr><td>").append("Timeout").append("</td><td>").append("No Entries Found - Cannot Determine Value").append("</td></tr>");
                    sb.append("<tr><td>").append("Action").append("</td><td>").append("No Entries Found - Cannot Determine Value").append("</td></tr>");
                }
            } else {
                sb.append("<tr><td>").append("Timeout").append("</td><td>").append("No Entries Found - Cannot Determine Value").append("</td></tr>");
                sb.append("<tr><td>").append("Action").append("</td><td>").append("No Entries Found - Cannot Determine Value").append("</td></tr>");
            }
            sb.append("</table>");
        }

        sb.append("<h4><b>").append("Region Entry Idle").append("</b></h4>");
        ExpirationAttributes regionIdleLive = region.getAttributes().getRegionIdleTimeout();
        if (regionIdleLive != null) {
            sb.append("<table><tr><td><b>").append("Property").append("</b></td><td><b>").append("Value").append("</b></td></tr>");
            sb.append("<tr><td>").append("Timeout").append("</td><td>").append(regionIdleLive.getTimeout())
                    .append("</td></tr>");
            sb.append("<tr><td>").append("Action").append("</td><td>").append(regionIdleLive.getAction())
                    .append("</td></tr></table>");
        } else {
            sb.append("<ui>").append("No Region Entry Idle Defined").append("</ui><br>");
        }

        sb.append("<h4><b>").append("Region Entry Time To Live").append("</b></h4>");
        ExpirationAttributes regionTimeToLive = region.getAttributes().getRegionTimeToLive();
        if (regionTimeToLive != null) {
            sb.append("<table><tr><td><b>").append("Property").append("</b></td><td><b>").append("Value").append("</b></td></tr>");
            sb.append("<tr><td>").append("Timeout").append("<//td><td>").append(regionTimeToLive.getTimeout())
                    .append("</td></tr>");
            sb.append("<tr><td>").append("Action").append("</td><td>").append(regionTimeToLive.getAction())
                    .append("</td></tr></table>");
        } else {
            sb.append("<ui>").append("No Region Entry Time To Live Defined").append("</ui><br>");
        }

        sb.append("<h4><b>").append("Entry Idle").append("</b></h4>");
        ExpirationAttributes entryIdle = region.getAttributes().getEntryIdleTimeout();
        if (entryIdle != null) {
            sb.append("<table><tr><td><b>").append("Property").append("</b></td><td><b>").append("Value").append("</b></td></tr>");
            sb.append("<tr><td>").append("Timeout: ").append("</td><td>").append(entryIdle.getTimeout()).append("</td></tr>");
            sb.append("<tr><td>").append("Action: ").append("</td><td>")
                    .append(entryIdle.getAction()).append("</td></tr></table>");
        } else {
            sb.append("<ui>").append("No Entry Idle Defined").append("<ui><br>");
        }

        sb.append("<h4><b>").append("Entry Time To Live").append("</b></h4>");
        ExpirationAttributes entryTimeToLive = region.getAttributes().getEntryTimeToLive();
        if (entryTimeToLive != null) {
            sb.append("<table><tr><td><b>").append("Property").append("</b></td><td><b>").append("Value").append("</b></td></tr>");
            sb.append("<tr><td>").append("Timeout").append("</td><td>").append(entryTimeToLive.getTimeout())
                    .append("</td></tr>");
            sb.append("<tr><td>").append("Action").append("</td><td>").append(entryTimeToLive.getAction())
                    .append("</td></tr></table>");
        } else {
            sb.append("<ui>").append("No Entry Time To Live Defined").append("</ui><br>");
        }

        sb.append("<h4><b>").append("Eviction").append("</b></h4>");
        EvictionAttributes evictionAttributes = region.getAttributes().getEvictionAttributes();
        if (evictionAttributes != null) {
            sb.append("<table><tr><td><b>").append("Property").append("</b></td><td><b>").append("Value").append("</b></td></tr>");
            sb.append("<tr><td>").append("Algorithm").append("</td><td>").append(evictionAttributes.getAlgorithm())
                    .append("</td></tr>");
            sb.append("<tr><td>").append("Action").append("</td><td>").append(evictionAttributes.getAction())
                    .append("</td></tr>");
            sb.append("<tr><td>").append("Maximum").append("</td><td>").append(evictionAttributes.getMaximum())
                    .append("</td></tr></table>");
        } else {
            sb.append("<ui>").append("No Eviction Defined").append("</ui><br>");
        }
    }

    private void processDisks(DiskStore diskStore, StringBuilder sb) {
        sb.append("<table><tr><td><b>").append("Property").append("</b></td><td><b>").append("Value").append("</b></td></tr>");
        sb.append("<tr><td>").append("Name").append("</td><td>").append(diskStore.getName()).append("</td></tr>");
        sb.append("<tr><td>").append("UUID").append("</td><td>").append(diskStore.getDiskStoreUUID()).append("</td></tr>");
        sb.append("<tr><td>").append("Auto Compact").append("</td><td>").append(diskStore.getAutoCompact()).append("</td></tr>");
        sb.append("<tr><td>").append("Allow Forced Compact").append("</td><td>").append(diskStore.getAllowForceCompaction()).append("</td></tr>");
        sb.append("<tr><td>").append("Auto Compact").append("</td><td>").append(diskStore.getAutoCompact()).append("</td></tr>");
        sb.append("<tr><td>").append("Compaction Threshold").append("</td><td>").append(diskStore.getCompactionThreshold()).append("</td></tr>");
        sb.append("<tr><td>").append("Time Interval").append("</td><td>").append(diskStore.getTimeInterval()).append("</td></tr>");
        sb.append("<tr><td>").append("Critical Disk Usage Percent").append("</td><td>").append(diskStore.getDiskUsageCriticalPercentage()).append("</td></tr>");
        sb.append("<tr><td>").append("Warning Disk Usage Percent").append("</td><td>").append(diskStore.getDiskUsageWarningPercentage()).append("</td></tr>");
        sb.append("<tr><td>").append("Max Op Log Size").append("</td><td>").append(diskStore.getMaxOplogSize()).append("</td></tr>");
        sb.append("<tr><td>").append("Queue Size").append("</td><td>").append(diskStore.getQueueSize()).append("</td></tr>");
        sb.append("<tr><td>").append("Write Buffer Size").append("</td><td>").append(diskStore.getWriteBufferSize()).append("</td></tr>");
        File[] dirs = diskStore.getDiskDirs();
        sb.append("<tr><td>").append("Directories").append("</td><td>");
        int[] diskSizes = diskStore.getDiskDirSizes();
        if (diskSizes == null || diskSizes.length == 0) {
            sb.append("No Disk Store Directories Defined").append("</td></tr>");
        } else {
            for (int i = 0; i < diskSizes.length; i++) {
                if (i == 0) {
                    sb.append(dirs[i].getAbsolutePath()).append(":").append(dirs[i].getName()).append(":")
                            .append(diskSizes[i]).append("</td></tr>");
                } else {
                    sb.append("<tr><td>").append("").append("</td><td>").append(dirs[i].getAbsolutePath()).append(":").append(dirs[i].getName()).append(":")
                            .append(diskSizes[i]).append("</td></tr>");
                }
            }
        }
        sb.append("</table>");
    }

    @Override
    public boolean hasResult() {
        return true;
    }

    @Override
    public void execute(FunctionContext functionContext) {
        String results = capture();
        log.debug(results);
        functionContext.getResultSender().lastResult(results);
    }

    @Override
    public String getId() {
        return this.getClass().getSimpleName();
    }

    @Override
    public boolean optimizeForWrite() {
        return false;
    }

    @Override
    public boolean isHA() {
        return false;
    }
}
