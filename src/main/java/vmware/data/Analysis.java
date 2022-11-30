package vmware.data;

import lombok.extern.slf4j.Slf4j;
import org.apache.geode.cache.CacheFactory;
import org.apache.geode.cache.execute.Function;
import org.apache.geode.cache.execute.FunctionContext;
import org.apache.geode.cache.execute.FunctionService;
import org.apache.geode.cache.execute.ResultCollector;
import org.apache.geode.distributed.DistributedMember;
import org.apache.geode.distributed.internal.membership.InternalDistributedMember;
import org.apache.geode.internal.cache.InternalCache;
import org.apache.geode.internal.cache.execute.DefaultResultCollector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

@Slf4j
public class Analysis implements Function {

    @Override
    public boolean hasResult() {
        return true;
    }

    @Override
    public void execute(FunctionContext functionContext) {
        InternalCache cache = (InternalCache) CacheFactory.getAnyInstance();
        Set<DistributedMember> members = cache.getInternalDistributedSystem().getAllOtherMembers();
        members.add(cache.getInternalDistributedSystem().getDistributedMember());
        Map<InternalDistributedMember, Collection<String>> locators = cache.getDistributionManager().getAllHostedLocators();
        locators.forEach((member, locator) -> {
            if (members.contains((DistributedMember) member)) {
                members.remove((DistributedMember) member);
            }
        });
        ResultCollector collector = FunctionService.onMembers(members).withCollector(new DefaultResultCollector()).execute("Capture");
        ArrayList<String> results = (ArrayList<String>) collector.getResult();
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
        return true;
    }
}
