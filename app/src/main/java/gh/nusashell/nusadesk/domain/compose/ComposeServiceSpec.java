package gh.nusashell.nusadesk.domain.compose;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validated, immutable description of one service in the supported Compose
 * subset.
 *
 * <p>{@code serviceName} is an identifier-safe name — ASCII letters, digits,
 * {@code .}, {@code _}, {@code -}, never beginning with {@code .} or
 * {@code -} — because it becomes part of generated container/unit
 * identifiers. {@code image} accepts the wider image-reference surface an
 * adapter can serialize into argv: registry paths, tags, and digests such as
 * {@code nginx:alpine}, {@code ghcr.io/org/app:1.2}, or
 * {@code repo/app@sha256:<hex>}. That validation proves argv/envp safety
 * only — this domain does not curate or allowlist images; a catalog/policy
 * layer may add that restriction later.</p>
 *
 * <p>Collection arguments are required (pass an empty list/map for "absent")
 * and defensively copied; only {@code workingDirectory} is nullable. A null
 * {@code restartPolicy} means {@link ComposeRestartPolicy#NO}, matching the
 * Compose default for an omitted {@code restart} key.</p>
 *
 * <p>Uniqueness rules enforced here are the ones a single service can own: no
 * two volumes may share a target, and no two port mappings may share the
 * {@code hostPort:containerPort:protocol} triple (the host address is not part
 * of that identity — two overlapping binds are a conflict even on different
 * addresses). Cross-service rules — unknown {@code dependsOn} names and
 * dependency cycles — belong to {@link ComposeProjectSpec}.</p>
 */
public final class ComposeServiceSpec {
    private final String serviceName;
    private final String image;
    private final List<String> command;
    private final List<String> entrypoint;
    private final Map<String, String> environment;
    private final List<ComposeVolumeMapping> volumes;
    private final List<ComposePortMapping> ports;
    private final String workingDirectory;
    private final List<String> dependsOn;
    private final ComposeRestartPolicy restartPolicy;

    /**
     * @param serviceName      identifier-safe service name
     * @param image            argv-safe image reference — registry/tag/digest
     *                         syntax allowed; validated for serialization,
     *                         not allowlisted by this layer
     * @param command          argument list overriding the image default; each
     *                         entry non-blank and NUL-free
     * @param entrypoint       argument list replacing the image entrypoint;
     *                         same rules as {@code command}
     * @param environment      environment map; keys non-blank, NUL-free, and
     *                         free of {@code '='} so each entry serializes as
     *                         exactly one {@code KEY=VALUE}; values non-null
     *                         (may be empty or contain {@code '='}), NUL-free
     * @param volumes          mount list with unique targets
     * @param ports            published ports with unique
     *                         hostPort:containerPort:protocol triples
     * @param workingDirectory absolute guest path, or null for the image default
     * @param dependsOn        service names that must start first (short-list
     *                         form only); entries are trimmed, non-blank, and
     *                         may not name this service
     * @param restartPolicy    restart policy, or null for
     *                         {@link ComposeRestartPolicy#NO}
     * @throws IllegalArgumentException on any violated rule above
     */
    public ComposeServiceSpec(
            String serviceName,
            String image,
            List<String> command,
            List<String> entrypoint,
            Map<String, String> environment,
            List<ComposeVolumeMapping> volumes,
            List<ComposePortMapping> ports,
            String workingDirectory,
            List<String> dependsOn,
            ComposeRestartPolicy restartPolicy) {
        this.serviceName = ComposeValidation.requireName(serviceName, "serviceName");
        this.image = ComposeValidation.requireImageRef(image, "image");
        this.command = immutableArgs(command, "command");
        this.entrypoint = immutableArgs(entrypoint, "entrypoint");
        this.environment = immutableEnvironment(environment);
        this.volumes = immutableVolumes(volumes);
        this.ports = immutablePorts(ports);
        this.workingDirectory = workingDirectory == null
                ? null
                : ComposeValidation.requireAbsoluteGuestPath(
                        workingDirectory, "workingDirectory");
        this.dependsOn = immutableDependsOn(dependsOn, this.serviceName);
        this.restartPolicy = restartPolicy == null ? ComposeRestartPolicy.NO : restartPolicy;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getImage() {
        return image;
    }

    /** Command argv, in declared order; empty when the service sets none. */
    public List<String> getCommand() {
        return command;
    }

    /** Entrypoint argv, in declared order; empty when the service sets none. */
    public List<String> getEntrypoint() {
        return entrypoint;
    }

    /** Environment entries, in declared order. */
    public Map<String, String> getEnvironment() {
        return environment;
    }

    /** Mounts, in declared order. */
    public List<ComposeVolumeMapping> getVolumes() {
        return volumes;
    }

    /** Published ports, in declared order. */
    public List<ComposePortMapping> getPorts() {
        return ports;
    }

    /** Absolute guest working directory, or null for the image default. */
    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public boolean hasWorkingDirectory() {
        return workingDirectory != null;
    }

    /** Names of services that must start first, in declared order. */
    public List<String> getDependsOn() {
        return dependsOn;
    }

    /** Restart policy; never null — an omitted policy is {@link ComposeRestartPolicy#NO}. */
    public ComposeRestartPolicy getRestartPolicy() {
        return restartPolicy;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ComposeServiceSpec)) {
            return false;
        }
        ComposeServiceSpec that = (ComposeServiceSpec) other;
        return serviceName.equals(that.serviceName)
                && image.equals(that.image)
                && command.equals(that.command)
                && entrypoint.equals(that.entrypoint)
                && environment.equals(that.environment)
                && volumes.equals(that.volumes)
                && ports.equals(that.ports)
                && Objects.equals(workingDirectory, that.workingDirectory)
                && dependsOn.equals(that.dependsOn)
                && restartPolicy == that.restartPolicy;
    }

    @Override
    public int hashCode() {
        return Objects.hash(serviceName, image, command, entrypoint, environment,
                volumes, ports, workingDirectory, dependsOn, restartPolicy);
    }

    @Override
    public String toString() {
        return "ComposeServiceSpec{name=" + serviceName + ", image=" + image
                + ", restart=" + restartPolicy + ", ports=" + ports + "}";
    }

    private static List<String> immutableArgs(List<String> args, String field) {
        List<String> copy = ComposeValidation.immutableList(args, field);
        for (String arg : copy) {
            ComposeValidation.requireNonBlank(arg, field + " entry");
            ComposeValidation.requireNoNul(arg, field + " entry");
        }
        return copy;
    }

    private static Map<String, String> immutableEnvironment(Map<String, String> environment) {
        Map<String, String> copy =
                ComposeValidation.immutableMap(environment, "environment");
        for (Map.Entry<String, String> entry : copy.entrySet()) {
            ComposeValidation.requireEnvironmentKey(entry.getKey(), "environment key");
            if (entry.getValue() == null) {
                throw new IllegalArgumentException(
                        "environment value must not be null for key: " + entry.getKey());
            }
            ComposeValidation.requireNoNul(entry.getValue(), "environment value");
        }
        return copy;
    }

    private static List<ComposeVolumeMapping> immutableVolumes(
            List<ComposeVolumeMapping> volumes) {
        List<ComposeVolumeMapping> copy =
                ComposeValidation.immutableList(volumes, "volumes");
        Set<String> targets = new HashSet<>();
        for (ComposeVolumeMapping volume : copy) {
            if (volume == null) {
                throw new IllegalArgumentException("volumes must not contain null");
            }
            if (!targets.add(volume.getTarget())) {
                throw new IllegalArgumentException(
                        "duplicate volume target: " + volume.getTarget());
            }
        }
        return copy;
    }

    private static List<ComposePortMapping> immutablePorts(List<ComposePortMapping> ports) {
        List<ComposePortMapping> copy = ComposeValidation.immutableList(ports, "ports");
        Set<String> mappings = new HashSet<>();
        for (ComposePortMapping port : copy) {
            if (port == null) {
                throw new IllegalArgumentException("ports must not contain null");
            }
            String identity = port.getHostPort() + ":" + port.getContainerPort()
                    + "/" + port.getProtocol();
            if (!mappings.add(identity)) {
                throw new IllegalArgumentException(
                        "duplicate host:container:protocol port mapping: " + identity);
            }
        }
        return copy;
    }

    private static List<String> immutableDependsOn(List<String> dependsOn, String serviceName) {
        if (dependsOn == null) {
            throw new IllegalArgumentException("dependsOn must not be null");
        }
        List<String> copy = new ArrayList<>();
        for (String dependency : dependsOn) {
            String name = ComposeValidation.requireNonBlank(dependency, "dependsOn entry").trim();
            if (name.equals(serviceName)) {
                throw new IllegalArgumentException(
                        "service '" + serviceName + "' must not depend on itself");
            }
            copy.add(name);
        }
        return Collections.unmodifiableList(copy);
    }
}
