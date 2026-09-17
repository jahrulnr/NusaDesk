package gh.nusashell.nusadesk.infrastructure.compose;

import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import gh.nusashell.nusadesk.domain.compose.ComposePortMapping;
import gh.nusashell.nusadesk.domain.compose.ComposeProjectSpec;
import gh.nusashell.nusadesk.domain.compose.ComposeRestartPolicy;
import gh.nusashell.nusadesk.domain.compose.ComposeServiceSpec;
import gh.nusashell.nusadesk.domain.compose.ComposeVolumeMapping;

/**
 * Strict parser from a Compose YAML document to a validated
 * {@link ComposeProjectSpec} for the supported NusaDesk subset.
 *
 * <p>Supported root keys: {@code services} (required, non-empty map),
 * {@code name} (optional scalar; when present it must equal the caller's
 * project name after trimming) and {@code version} (optional scalar, accepted
 * for Compose-file compatibility and otherwise ignored). Supported service
 * keys: {@code image} (required string), {@code command}, {@code entrypoint},
 * {@code environment}, {@code volumes}, {@code ports}, {@code working_dir},
 * {@code depends_on} (short list of service names only) and {@code restart}
 * ({@code no}, {@code always}, {@code unless-stopped}; a YAML {@code false}
 * also maps to {@code no}).</p>
 *
 * <p>Every other key and Compose feature — {@code build}, {@code networks},
 * {@code healthcheck}, top-level {@code volumes}/{@code networks}, long-form
 * {@code depends_on} conditions, {@code on-failure} restarts, named or
 * anonymous volumes, and container-only port mappings — is rejected rather
 * than silently ignored, so an accepted spec never drops behaviour the
 * adapter cannot honour.</p>
 *
 * <p>Deliberate adaptations from upstream Compose, forced by the
 * udocker/PRoot target:</p>
 * <ul>
 *   <li>a scalar {@code command} or {@code entrypoint} is adapted to the
 *       explicit argv {@code [/bin/sh, -c, text]} instead of relying on
 *       word-splitting;</li>
 *   <li>{@code ${...}} interpolation is rejected everywhere — the host
 *       environment is never read;</li>
 *   <li>only bind mounts are accepted: short {@code source:target[:ro|rw]}
 *       whose source is a path, or long {@code {type: bind, source, target,
 *       read_only}};</li>
 *   <li>ports require an explicit host port: short
 *       {@code host:container[/tcp|udp]}, {@code host_ip:host:container[/proto]}
 *       or bracketed IPv6 {@code [ip]:host:container[/proto]}, or long
 *       {@code {target, published, protocol, host_ip}}.</li>
 * </ul>
 *
 * <p>Loading is bounded: the document is capped at 1 MiB of code points,
 * collection aliases are capped (alias bombs fail closed), duplicate keys and
 * non-scalar/recursive keys are rejected, and only standard maps, lists and
 * scalars are constructed — no arbitrary tags or Java objects. No host
 * environment substitution is configured, so {@code ${...}} reaches this
 * parser as literal text and is rejected. All failures throw
 * {@link ComposeParseException} with the offending path, e.g.
 * {@code services.web.ports[0]}.</p>
 */
public final class ComposeYamlParser {
    private static final int MAX_CODE_POINTS = 1024 * 1024;
    private static final int MAX_ALIASES = 50;
    private static final Pattern ENV_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static final List<String> ROOT_KEYS =
            Arrays.asList("name", "version", "services");
    private static final List<String> SERVICE_KEYS = Arrays.asList(
            "image", "command", "entrypoint", "environment", "volumes",
            "ports", "working_dir", "depends_on", "restart");
    private static final List<String> VOLUME_KEYS =
            Arrays.asList("type", "source", "target", "read_only");
    private static final List<String> PORT_KEYS =
            Arrays.asList("target", "published", "protocol", "host_ip");

    private static final LoadSettings SETTINGS = LoadSettings.builder()
            .setCodePointLimit(MAX_CODE_POINTS)
            .setMaxAliasesForCollections(MAX_ALIASES)
            .setAllowDuplicateKeys(false)
            .setAllowRecursiveKeys(false)
            .setAllowNonScalarKeys(false)
            .build();

    private ComposeYamlParser() {
    }

    /**
     * Parses {@code yaml} into a validated project spec.
     *
     * @param yaml        Compose document text; null/blank is rejected
     * @param projectName the project name the caller will run under; a
     *                    declared top-level {@code name} must match it
     * @return the validated, immutable project spec
     * @throws ComposeParseException     when the document is malformed or
     *                                  violates the supported subset
     * @throws IllegalArgumentException when {@code projectName} is null/blank
     */
    public static ComposeProjectSpec parse(String yaml, String projectName)
            throws ComposeParseException {
        if (projectName == null || projectName.trim().isEmpty()) {
            throw new IllegalArgumentException("projectName must not be blank");
        }
        if (yaml == null || yaml.trim().isEmpty()) {
            throw new ComposeParseException("compose yaml must not be blank");
        }
        Object document = load(yaml);
        Map<String, Object> root = asMap(document, "");
        rejectUnknownKeys(root, ROOT_KEYS, "");

        if (root.get("name") != null) {
            String declared = scalarText(root.get("name"), "name").trim();
            if (!declared.equals(projectName.trim())) {
                fail("name", "'" + declared + "' does not match project name '"
                        + projectName.trim() + "'");
            }
        }
        if (root.containsKey("version")) {
            scalarText(root.get("version"), "version"); // must be a scalar; value ignored
        }

        Map<String, Object> servicesMap =
                asMap(required(root.get("services"), "services"), "services");
        if (servicesMap.isEmpty()) {
            fail("services", "must declare at least one service");
        }
        List<ComposeServiceSpec> services = new ArrayList<>();
        for (Map.Entry<String, Object> entry : servicesMap.entrySet()) {
            services.add(parseService(entry.getKey(), entry.getValue()));
        }
        for (ComposeServiceSpec spec : services) {
            for (String dependency : spec.getDependsOn()) {
                if (!servicesMap.containsKey(dependency)) {
                    fail("services." + spec.getServiceName() + ".depends_on",
                            "unknown service '" + dependency + "'");
                }
            }
        }
        try {
            return new ComposeProjectSpec(projectName, services);
        } catch (IllegalArgumentException invalid) {
            throw new ComposeParseException("services: " + invalid.getMessage());
        }
    }

    private static Object load(String yaml) throws ComposeParseException {
        try {
            return new Load(SETTINGS).loadFromString(yaml);
        } catch (StackOverflowError tooDeep) {
            throw new ComposeParseException(
                    "compose document is nested too deeply", tooDeep);
        } catch (RuntimeException invalid) {
            String detail = invalid.getMessage();
            throw new ComposeParseException("invalid yaml"
                    + (detail == null ? "" : ": " + detail), invalid);
        }
    }

    private static ComposeServiceSpec parseService(String serviceName, Object node)
            throws ComposeParseException {
        String path = "services." + serviceName;
        Map<String, Object> service = asMap(node, path);
        rejectUnknownKeys(service, SERVICE_KEYS, path);

        String image = requiredString(service.get("image"), path + ".image");
        List<String> command = parseArgs(service.get("command"), path + ".command");
        List<String> entrypoint = parseArgs(service.get("entrypoint"), path + ".entrypoint");
        Map<String, String> environment =
                parseEnvironment(service.get("environment"), path + ".environment");
        List<ComposeVolumeMapping> volumes =
                parseVolumes(service.get("volumes"), path + ".volumes");
        List<ComposePortMapping> ports = parsePorts(service.get("ports"), path + ".ports");
        String workingDirectory =
                optionalString(service.get("working_dir"), path + ".working_dir");
        List<String> dependsOn =
                parseDependsOn(service.get("depends_on"), path + ".depends_on");
        ComposeRestartPolicy restart =
                parseRestart(service.get("restart"), path + ".restart");
        try {
            return new ComposeServiceSpec(serviceName, image, command, entrypoint,
                    environment, volumes, ports, workingDirectory, dependsOn, restart);
        } catch (IllegalArgumentException invalid) {
            throw new ComposeParseException(path + ": " + invalid.getMessage());
        }
    }

    /**
     * A scalar {@code command}/{@code entrypoint} is adapted to
     * {@code [/bin/sh, -c, text]}; a list becomes the literal argv. Maps,
     * booleans, and other non-string scalars are rejected.
     */
    private static List<String> parseArgs(Object node, String path)
            throws ComposeParseException {
        if (node == null) {
            return Collections.emptyList();
        }
        if (node instanceof String) {
            return new ArrayList<>(Arrays.asList(
                    "/bin/sh", "-c", noInterpolation((String) node, path)));
        }
        if (!(node instanceof List)) {
            fail(path, "must be a string or a list of arguments");
        }
        List<String> args = new ArrayList<>();
        List<?> items = (List<?>) node;
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            if (item == null) {
                fail(path + "[" + i + "]", "argument must not be null");
            }
            args.add(scalarText(item, path + "[" + i + "]"));
        }
        return args;
    }

    private static Map<String, String> parseEnvironment(Object node, String path)
            throws ComposeParseException {
        Map<String, String> environment = new LinkedHashMap<>();
        if (node == null) {
            return environment;
        }
        if (node instanceof Map) {
            for (Map.Entry<String, Object> entry : asMap(node, path).entrySet()) {
                requireEnvKey(entry.getKey(), path);
                if (entry.getValue() == null) {
                    fail(path + "." + entry.getKey(),
                            "value must not be null; host environment is never read");
                }
                environment.put(entry.getKey(),
                        scalarText(entry.getValue(), path + "." + entry.getKey()));
            }
            return environment;
        }
        if (!(node instanceof List)) {
            fail(path, "must be a mapping or a list of KEY=VALUE entries");
        }
        List<?> items = (List<?>) node;
        for (int i = 0; i < items.size(); i++) {
            String itemPath = path + "[" + i + "]";
            String entry = scalarText(items.get(i), itemPath);
            int equals = entry.indexOf('=');
            if (equals < 0) {
                fail(itemPath, "key-only entries are not supported; use KEY=VALUE "
                        + "(host environment is never read)");
            }
            String key = entry.substring(0, equals);
            requireEnvKey(key, itemPath);
            environment.put(key, entry.substring(equals + 1));
        }
        return environment;
    }

    private static void requireEnvKey(String key, String path)
            throws ComposeParseException {
        if (!ENV_KEY.matcher(key).matches()) {
            fail(path, "invalid environment name '" + key + "'"
                    + " (expected [A-Za-z_][A-Za-z0-9_]*)");
        }
    }

    private static List<ComposeVolumeMapping> parseVolumes(Object node, String path)
            throws ComposeParseException {
        if (node == null) {
            return Collections.emptyList();
        }
        List<?> items = asList(node, path);
        List<ComposeVolumeMapping> volumes = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            String itemPath = path + "[" + i + "]";
            Object item = items.get(i);
            if (item instanceof Map) {
                volumes.add(parseLongVolume(asMap(item, itemPath), itemPath));
            } else {
                volumes.add(parseShortVolume(scalarText(item, itemPath), itemPath));
            }
        }
        return volumes;
    }

    /** Short form {@code source:target[:ro|rw]}; bind paths only. */
    private static ComposeVolumeMapping parseShortVolume(String text, String path)
            throws ComposeParseException {
        String[] parts = text.split(":", -1);
        boolean readOnly = false;
        if (parts.length == 1) {
            fail(path, "anonymous volumes are not supported; declare source:target");
        }
        if (parts.length == 3) {
            if ("ro".equals(parts[2]) || "rw".equals(parts[2])) {
                readOnly = "ro".equals(parts[2]);
            } else {
                fail(path, "unsupported volume mode '" + parts[2]
                        + "' (only ro or rw)");
            }
        } else if (parts.length > 3) {
            fail(path, "expected source:target[:ro|rw]");
        }
        String source = parts[0];
        String target = parts[1];
        requireBindSource(source, path);
        return newVolume(source, target, readOnly, path);
    }

    /** Long form {@code {type: bind, source, target, read_only}} only. */
    private static ComposeVolumeMapping parseLongVolume(
            Map<String, Object> volume, String path) throws ComposeParseException {
        rejectUnknownKeys(volume, VOLUME_KEYS, path);
        String type = requiredString(volume.get("type"), path + ".type");
        if (!"bind".equals(type.trim())) {
            fail(path + ".type", "only bind mounts are supported: " + type);
        }
        String source = requiredString(volume.get("source"), path + ".source");
        String target = requiredString(volume.get("target"), path + ".target");
        requireBindSource(source, path + ".source");
        boolean readOnly = false;
        Object readOnlyNode = volume.get("read_only");
        if (readOnlyNode != null) {
            if (readOnlyNode instanceof Boolean) {
                readOnly = (Boolean) readOnlyNode;
            } else {
                String flag = scalarText(readOnlyNode, path + ".read_only").trim();
                if ("true".equalsIgnoreCase(flag) || "false".equalsIgnoreCase(flag)) {
                    readOnly = Boolean.parseBoolean(flag);
                } else {
                    fail(path + ".read_only", "must be true or false: " + flag);
                }
            }
        }
        return newVolume(source, target, readOnly, path);
    }

    /**
     * A bind source must be a path (absolute, {@code ./}/{@code ../} relative,
     * or {@code ~}); a bare name like {@code data} is a named volume, which
     * the subset does not support.
     */
    private static void requireBindSource(String source, String path)
            throws ComposeParseException {
        if (source.trim().isEmpty()) {
            fail(path, "volume source must not be blank");
        }
        boolean pathLike = source.startsWith("/") || source.startsWith(".")
                || source.startsWith("~") || source.indexOf('/') >= 0;
        if (!pathLike) {
            fail(path, "named volumes are not supported; bind-mount a path "
                    + "instead of '" + source + "'");
        }
    }

    private static ComposeVolumeMapping newVolume(
            String source, String target, boolean readOnly, String path)
            throws ComposeParseException {
        try {
            return new ComposeVolumeMapping(source, target, readOnly);
        } catch (IllegalArgumentException invalid) {
            throw new ComposeParseException(path + ": " + invalid.getMessage());
        }
    }

    private static List<ComposePortMapping> parsePorts(Object node, String path)
            throws ComposeParseException {
        if (node == null) {
            return Collections.emptyList();
        }
        List<?> items = asList(node, path);
        List<ComposePortMapping> ports = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            String itemPath = path + "[" + i + "]";
            Object item = items.get(i);
            if (item instanceof Map) {
                ports.add(parseLongPort(asMap(item, itemPath), itemPath));
            } else if (item instanceof String || item instanceof Number) {
                ports.add(parseShortPort(scalarText(item, itemPath), itemPath));
            } else {
                fail(itemPath, "must be a port mapping string or a mapping");
            }
        }
        return ports;
    }

    /**
     * Short form {@code [host_ip:]host:container[/protocol]} with an optional
     * bracketed IPv6 host address. A single number is a container-only port
     * and is rejected: NusaDesk requires an explicit host port.
     */
    private static ComposePortMapping parseShortPort(String text, String path)
            throws ComposeParseException {
        String protocol = null;
        int slash = text.lastIndexOf('/');
        String endpoint = slash >= 0 ? text.substring(0, slash) : text;
        if (slash >= 0) {
            protocol = text.substring(slash + 1);
        }
        String hostAddress = null;
        if (endpoint.startsWith("[")) {
            int close = endpoint.indexOf(']');
            if (close < 0 || close + 1 >= endpoint.length()
                    || endpoint.charAt(close + 1) != ':') {
                fail(path, "malformed bracketed host address: " + text);
            }
            hostAddress = endpoint.substring(1, close);
            endpoint = endpoint.substring(close + 2);
        }
        String[] segments = endpoint.split(":", -1);
        String hostPort;
        String containerPort;
        if (segments.length == 2) {
            hostPort = segments[0];
            containerPort = segments[1];
        } else if (segments.length == 3 && hostAddress == null) {
            hostAddress = segments[0];
            hostPort = segments[1];
            containerPort = segments[2];
        } else if (segments.length == 1) {
            fail(path, "container-only ports are not supported; "
                    + "declare host:container");
            return null; // unreachable
        } else {
            fail(path, "expected [host_ip:]host:container[/protocol]; "
                    + "bracket IPv6 host addresses");
            return null; // unreachable
        }
        if (hostAddress != null && hostAddress.trim().isEmpty()) {
            fail(path, "host address must not be blank: " + text);
        }
        int host = parsePortNumber(hostPort, path);
        int container = parsePortNumber(containerPort, path);
        return newPort(host, container, protocol, hostAddress, path);
    }

    /** Long form {@code {target, published, protocol, host_ip}} only. */
    private static ComposePortMapping parseLongPort(
            Map<String, Object> port, String path) throws ComposeParseException {
        rejectUnknownKeys(port, PORT_KEYS, path);
        int container = parsePortNumber(
                required(port.get("target"), path + ".target"), path + ".target");
        if (port.get("published") == null) {
            fail(path, "container-only ports are not supported; "
                    + "declare 'published'");
        }
        int host = parsePortNumber(port.get("published"), path + ".published");
        String protocol = optionalString(port.get("protocol"), path + ".protocol");
        String hostAddress = optionalString(port.get("host_ip"), path + ".host_ip");
        return newPort(host, container, protocol, hostAddress, path);
    }

    private static int parsePortNumber(Object node, String path)
            throws ComposeParseException {
        long value;
        if (node instanceof Double || node instanceof Float) {
            fail(path, "port must be an integer: " + node);
            return 0; // unreachable
        }
        if (node instanceof Number) {
            value = ((Number) node).longValue();
        } else {
            String text = scalarText(node, path).trim();
            try {
                value = Long.parseLong(text);
            } catch (NumberFormatException notNumeric) {
                fail(path, "port must be an integer: " + text);
                return 0; // unreachable
            }
        }
        if (value < 1 || value > 65535) {
            fail(path, "port must be between 1 and 65535: " + value);
        }
        return (int) value;
    }

    private static ComposePortMapping newPort(int host, int container,
            String protocol, String hostAddress, String path)
            throws ComposeParseException {
        try {
            return new ComposePortMapping(host, container, protocol, hostAddress);
        } catch (IllegalArgumentException invalid) {
            throw new ComposeParseException(path + ": " + invalid.getMessage());
        }
    }

    private static List<String> parseDependsOn(Object node, String path)
            throws ComposeParseException {
        if (node == null) {
            return Collections.emptyList();
        }
        if (!(node instanceof List)) {
            fail(path, "only the short list form is supported "
                    + "(no condition mappings)");
        }
        List<?> items = (List<?>) node;
        List<String> dependencies = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            if (!(item instanceof String)) {
                fail(path + "[" + i + "]", "must be a service name string");
            }
            dependencies.add(noInterpolation((String) item, path + "[" + i + "]"));
        }
        return dependencies;
    }

    private static ComposeRestartPolicy parseRestart(Object node, String path)
            throws ComposeParseException {
        if (node == null) {
            return ComposeRestartPolicy.NO;
        }
        String text;
        if (node instanceof Boolean) {
            if (!((Boolean) node)) {
                text = "no"; // YAML false is Compose 'no'
            } else {
                fail(path, "boolean restart must be false; "
                        + "use no, always or unless-stopped");
                return null; // unreachable
            }
        } else if (node instanceof String) {
            text = noInterpolation((String) node, path);
        } else {
            fail(path, "must be one of no, always or unless-stopped");
            return null; // unreachable
        }
        try {
            return ComposeRestartPolicy.fromComposeValue(text);
        } catch (IllegalArgumentException invalid) {
            throw new ComposeParseException(path + ": " + invalid.getMessage());
        }
    }

    private static Object required(Object node, String path)
            throws ComposeParseException {
        if (node == null) {
            fail(path, "is required");
        }
        return node;
    }

    private static String requiredString(Object node, String path)
            throws ComposeParseException {
        if (node == null) {
            fail(path, "is required");
        }
        if (!(node instanceof String)) {
            fail(path, "must be a string");
        }
        return noInterpolation((String) node, path);
    }

    private static String optionalString(Object node, String path)
            throws ComposeParseException {
        if (node == null) {
            return null;
        }
        if (!(node instanceof String)) {
            fail(path, "must be a string");
        }
        return noInterpolation((String) node, path);
    }

    /**
     * The literal text of a scalar node. Strings are checked for
     * {@code ${...}} interpolation; numbers and booleans render their
     * canonical text. Maps, lists, and exotic scalar types are rejected.
     */
    private static String scalarText(Object node, String path)
            throws ComposeParseException {
        if (node == null) {
            fail(path, "must not be null");
        }
        if (node instanceof String) {
            return noInterpolation((String) node, path);
        }
        if (node instanceof Boolean || node instanceof Number) {
            return String.valueOf(node);
        }
        fail(path, "must be a scalar");
        return null; // unreachable
    }

    private static String noInterpolation(String text, String path)
            throws ComposeParseException {
        if (text.indexOf("${") >= 0) {
            fail(path, "must not contain ${...} interpolation; "
                    + "the host environment is never read");
        }
        return text;
    }

    private static Map<String, Object> asMap(Object node, String path)
            throws ComposeParseException {
        if (!(node instanceof Map)) {
            fail(path, "must be a mapping");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) node).entrySet()) {
            result.put(scalarText(entry.getKey(), path), entry.getValue());
        }
        return result;
    }

    private static List<?> asList(Object node, String path)
            throws ComposeParseException {
        if (!(node instanceof List)) {
            fail(path, "must be a list");
        }
        return (List<?>) node;
    }

    private static void rejectUnknownKeys(
            Map<String, Object> map, List<String> allowed, String path)
            throws ComposeParseException {
        for (String key : map.keySet()) {
            if (!allowed.contains(key)) {
                fail(path.isEmpty() ? key : path + "." + key,
                        "unsupported key; supported: " + allowed);
            }
        }
    }

    private static void fail(String path, String detail)
            throws ComposeParseException {
        throw new ComposeParseException(path.isEmpty() ? detail : path + ": " + detail);
    }
}
