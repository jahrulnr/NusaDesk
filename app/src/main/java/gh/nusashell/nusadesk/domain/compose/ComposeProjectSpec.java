package gh.nusashell.nusadesk.domain.compose;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validated, immutable description of one Compose project: a name plus a
 * non-empty, dependency-consistent set of {@link ComposeServiceSpec}s.
 *
 * <p>Beyond the per-service rules of {@link ComposeServiceSpec}, a project
 * guarantees: service names are unique, every {@code dependsOn} name resolves
 * to a declared service, and the dependency graph is acyclic — so an adapter
 * can always compute a valid start order. Declared service order is preserved
 * and exposed through {@link #getServices()}.</p>
 *
 * <p>{@code projectName} follows the same identifier-safety rules as a service
 * name because it prefixes generated container/unit identifiers.</p>
 */
public final class ComposeProjectSpec {
    private final String projectName;
    private final List<ComposeServiceSpec> services;
    private final Map<String, ComposeServiceSpec> servicesByName;

    /**
     * @param projectName identifier-safe project name
     * @param services    non-empty service list in declared order
     * @throws IllegalArgumentException when the name is invalid, the list is
     *                                  null/empty/contains null, service names
     *                                  are duplicated, a {@code dependsOn}
     *                                  name is unknown, or the dependency
     *                                  graph contains a cycle
     */
    public ComposeProjectSpec(String projectName, List<ComposeServiceSpec> services) {
        this.projectName = ComposeValidation.requireName(projectName, "projectName");
        List<ComposeServiceSpec> specs =
                ComposeValidation.immutableList(services, "services");
        if (specs.isEmpty()) {
            throw new IllegalArgumentException("services must not be empty");
        }
        Map<String, ComposeServiceSpec> byName = new LinkedHashMap<>();
        for (ComposeServiceSpec spec : specs) {
            if (spec == null) {
                throw new IllegalArgumentException("services must not contain null");
            }
            if (byName.put(spec.getServiceName(), spec) != null) {
                throw new IllegalArgumentException(
                        "duplicate service name: " + spec.getServiceName());
            }
        }
        for (ComposeServiceSpec spec : specs) {
            for (String dependency : spec.getDependsOn()) {
                if (!byName.containsKey(dependency)) {
                    throw new IllegalArgumentException("service '" + spec.getServiceName()
                            + "' depends on unknown service '" + dependency + "'");
                }
            }
        }
        checkAcyclic(byName);
        this.services = specs;
        this.servicesByName = Collections.unmodifiableMap(byName);
    }

    public String getProjectName() {
        return projectName;
    }

    /** Services in declared order. */
    public List<ComposeServiceSpec> getServices() {
        return services;
    }

    /** @return the named service, or null when the project does not declare it */
    public ComposeServiceSpec getService(String serviceName) {
        return servicesByName.get(serviceName);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ComposeProjectSpec)) {
            return false;
        }
        ComposeProjectSpec that = (ComposeProjectSpec) other;
        return projectName.equals(that.projectName) && services.equals(that.services);
    }

    @Override
    public int hashCode() {
        return Objects.hash(projectName, services);
    }

    @Override
    public String toString() {
        return "ComposeProjectSpec{name=" + projectName
                + ", services=" + servicesByName.keySet() + "}";
    }

    /**
     * Depth-first cycle check over the depends_on graph. {@code visiting}
     * holds the current DFS path in order so a detected back edge can name the
     * actual cycle in the error.
     */
    private static void checkAcyclic(Map<String, ComposeServiceSpec> byName) {
        Set<String> done = new HashSet<>();
        Set<String> visiting = new LinkedHashSet<>();
        for (String name : byName.keySet()) {
            visit(name, byName, visiting, done);
        }
    }

    private static void visit(
            String name,
            Map<String, ComposeServiceSpec> byName,
            Set<String> visiting,
            Set<String> done) {
        if (done.contains(name)) {
            return;
        }
        if (!visiting.add(name)) {
            StringBuilder cycle = new StringBuilder("depends_on cycle detected: ");
            boolean inCycle = false;
            for (String step : visiting) {
                if (step.equals(name)) {
                    inCycle = true;
                }
                if (inCycle) {
                    cycle.append(step).append(" -> ");
                }
            }
            cycle.append(name);
            throw new IllegalArgumentException(cycle.toString());
        }
        for (String dependency : byName.get(name).getDependsOn()) {
            visit(dependency, byName, visiting, done);
        }
        visiting.remove(name);
        done.add(name);
    }
}
