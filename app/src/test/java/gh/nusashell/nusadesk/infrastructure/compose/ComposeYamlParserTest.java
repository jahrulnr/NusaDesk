package gh.nusashell.nusadesk.infrastructure.compose;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import gh.nusashell.nusadesk.domain.compose.ComposePortMapping;
import gh.nusashell.nusadesk.domain.compose.ComposeProjectSpec;
import gh.nusashell.nusadesk.domain.compose.ComposeRestartPolicy;
import gh.nusashell.nusadesk.domain.compose.ComposeServiceSpec;
import gh.nusashell.nusadesk.domain.compose.ComposeVolumeMapping;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ComposeYamlParserTest {
    private static final String PROJECT = "myproj";

    private static ComposeProjectSpec parse(String yaml) throws ComposeParseException {
        return ComposeYamlParser.parse(yaml, PROJECT);
    }

    private static ComposeParseException rejected(String yaml) {
        try {
            parse(yaml);
            fail("expected ComposeParseException");
        } catch (ComposeParseException expected) {
            return expected;
        }
        return null; // unreachable
    }

    private static String project(String serviceBody) {
        return "services:\n  web:\n    image: web-img\n" + serviceBody;
    }

    @Test
    public void parsesATwoServiceProject() throws ComposeParseException {
        String yaml =
                "name: myproj\n"
                + "version: \"3.8\"\n"
                + "services:\n"
                + "  db:\n"
                + "    image: db-img\n"
                + "    restart: always\n"
                + "    environment:\n"
                + "      DB_ROOT: root\n"
                + "      POOL: 4\n"
                + "      DEBUG: true\n"
                + "    volumes:\n"
                + "      - ./dbdata:/var/lib/db:ro\n"
                + "      - type: bind\n"
                + "        source: ./conf\n"
                + "        target: /etc/db\n"
                + "        read_only: false\n"
                + "    ports:\n"
                + "      - \"5432:5432\"\n"
                + "      - \"127.0.0.1:15432:5433/udp\"\n"
                + "      - \"[::1]:25432:5434\"\n"
                + "      - target: 5440\n"
                + "        published: 25440\n"
                + "        protocol: udp\n"
                + "        host_ip: 127.0.0.1\n"
                + "    working_dir: /var/lib/db\n"
                + "  web:\n"
                + "    image: web-img\n"
                + "    command: echo hi && serve\n"
                + "    entrypoint:\n"
                + "      - /entry.sh\n"
                + "      - --flag\n"
                + "    environment:\n"
                + "      - WEB_PORT=8080\n"
                + "      - EMPTY=\n"
                + "    ports:\n"
                + "      - \"8080:80\"\n"
                + "    depends_on:\n"
                + "      - db\n"
                + "    restart: unless-stopped\n";
        ComposeProjectSpec spec = parse(yaml);
        assertEquals(PROJECT, spec.getProjectName());
        assertEquals(Arrays.asList("db", "web"),
                Arrays.asList(spec.getServices().get(0).getServiceName(),
                        spec.getServices().get(1).getServiceName()));

        ComposeServiceSpec db = spec.getService("db");
        assertEquals("db-img", db.getImage());
        assertEquals(ComposeRestartPolicy.ALWAYS, db.getRestartPolicy());
        assertEquals("root", db.getEnvironment().get("DB_ROOT"));
        assertEquals("4", db.getEnvironment().get("POOL"));
        assertEquals("true", db.getEnvironment().get("DEBUG"));
        assertEquals(new ComposeVolumeMapping("./dbdata", "/var/lib/db", true),
                db.getVolumes().get(0));
        assertEquals(new ComposeVolumeMapping("./conf", "/etc/db", false),
                db.getVolumes().get(1));
        List<ComposePortMapping> ports = db.getPorts();
        assertEquals(new ComposePortMapping(5432, 5432, "tcp", null), ports.get(0));
        assertEquals(new ComposePortMapping(15432, 5433, "udp", "127.0.0.1"),
                ports.get(1));
        assertEquals(new ComposePortMapping(25432, 5434, "tcp", "::1"),
                ports.get(2));
        assertEquals(new ComposePortMapping(25440, 5440, "udp", "127.0.0.1"),
                ports.get(3));
        assertEquals("/var/lib/db", db.getWorkingDirectory());

        ComposeServiceSpec web = spec.getService("web");
        assertEquals(Arrays.asList("/bin/sh", "-c", "echo hi && serve"),
                web.getCommand());
        assertEquals(Arrays.asList("/entry.sh", "--flag"), web.getEntrypoint());
        assertEquals("8080", web.getEnvironment().get("WEB_PORT"));
        assertEquals("", web.getEnvironment().get("EMPTY"));
        assertEquals(Arrays.asList("db"), web.getDependsOn());
        assertEquals(ComposeRestartPolicy.UNLESS_STOPPED, web.getRestartPolicy());
    }

    @Test
    public void defaultsAbsentOptionalKeys() throws ComposeParseException {
        ComposeServiceSpec spec = parse(project("")).getService("web");
        assertTrue(spec.getCommand().isEmpty());
        assertTrue(spec.getEntrypoint().isEmpty());
        assertTrue(spec.getEnvironment().isEmpty());
        assertTrue(spec.getVolumes().isEmpty());
        assertTrue(spec.getPorts().isEmpty());
        assertNull(spec.getWorkingDirectory());
        assertTrue(spec.getDependsOn().isEmpty());
        assertEquals(ComposeRestartPolicy.NO, spec.getRestartPolicy());
    }

    @Test
    public void adaptsScalarEntrypointToShellArgv() throws ComposeParseException {
        ComposeServiceSpec spec =
                parse(project("    entrypoint: run --fast\n")).getService("web");
        assertEquals(Arrays.asList("/bin/sh", "-c", "run --fast"),
                spec.getEntrypoint());
    }

    @Test
    public void mapsRestartValues() throws ComposeParseException {
        assertEquals(ComposeRestartPolicy.NO, parse(project("    restart: \"no\"\n"))
                .getService("web").getRestartPolicy());
        assertEquals(ComposeRestartPolicy.NO, parse(project("    restart: false\n"))
                .getService("web").getRestartPolicy());
        assertEquals(ComposeRestartPolicy.NO, parse(project("    restart: no\n"))
                .getService("web").getRestartPolicy());
        assertEquals(ComposeRestartPolicy.ALWAYS,
                parse(project("    restart: always\n"))
                        .getService("web").getRestartPolicy());
        assertEquals(ComposeRestartPolicy.UNLESS_STOPPED,
                parse(project("    restart: unless-stopped\n"))
                        .getService("web").getRestartPolicy());
    }

    @Test
    public void rejectsMalformedYamlAndNonMapRoots() {
        rejected("services: [not closed\n");
        rejected("- a\n- b\n");
        rejected("just a string\n");
        rejected("");
        rejected(null);
    }

    @Test
    public void rejectsUnknownRootKeys() {
        ComposeParseException e = rejected(project("") + "networks:\n  front:\n");
        assertTrue(e.getMessage().contains("networks"));
        rejected(project("") + "x-extension: 1\n");
        rejected(project("") + "volumes:\n  data:\n");
    }

    @Test
    public void rejectsMissingOrEmptyServices() {
        rejected("name: myproj\n");
        rejected("services: {}\n");
        rejected("services:\n");
    }

    @Test
    public void rejectsTopLevelNameMismatch() {
        ComposeParseException e = rejected("name: other\n" + project(""));
        assertTrue(e.getMessage().contains("name"));
        assertTrue(e.getMessage().contains("other"));
    }

    @Test
    public void rejectsNonScalarVersion() {
        rejected("version: {major: 3}\n" + project(""));
    }

    @Test
    public void rejectsUnknownServiceKeys() {
        assertTrue(rejected(project("    build: .\n")).getMessage()
                .contains("services.web.build"));
        assertTrue(rejected(project("    networks: [front]\n")).getMessage()
                .contains("services.web.networks"));
        assertTrue(rejected(project("    healthcheck: {test: true}\n")).getMessage()
                .contains("services.web.healthcheck"));
        rejected(project("    container_name: x\n"));
    }

    @Test
    public void rejectsMissingOrNonStringImage() {
        rejected("services:\n  web:\n    command: hi\n");
        rejected(project("    image: {ref: x}\n"));
        rejected(project("    image: 8080\n"));
    }

    @Test
    public void rejectsInterpolationEverywhere() {
        rejected(project("    image: \"img-${TAG}\"\n"));
        rejected(project("    environment:\n      A: \"${HOME}\"\n"));
        rejected(project("    environment:\n      - \"A=${HOME}\"\n"));
        rejected(project("    volumes:\n      - \"${HOME}/x:/data\"\n"));
        rejected(project("    ports:\n      - \"${P}:80\"\n"));
        rejected(project("    command: echo ${HOME}\n"));
    }

    @Test
    public void rejectsBadCommandAndEntrypointShapes() {
        rejected(project("    command: {run: x}\n"));
        rejected(project("    command: true\n"));
        rejected(project("    command: 8080\n"));
        rejected(project("    entrypoint:\n      - /x\n      - {a: b}\n"));
        rejected(project("    entrypoint:\n      - /x\n      -\n"));
    }

    @Test
    public void rejectsBadEnvironmentEntries() {
        // key-only list entry (would read host env in upstream Compose)
        ComposeParseException e =
                rejected(project("    environment:\n      - JUST_KEY\n"));
        assertTrue(e.getMessage().contains("services.web.environment[0]"));
        // null map value
        rejected(project("    environment:\n      A:\n"));
        // non-scalar map value
        rejected(project("    environment:\n      A: {nested: x}\n"));
        // invalid key characters / leading digit / '='
        rejected(project("    environment:\n      \"9BAD\": x\n"));
        rejected(project("    environment:\n      \"BAD KEY\": x\n"));
        rejected(project("    environment:\n      - \"=v\"\n"));
        rejected(project("    environment:\n      - \"BAD KEY=1\"\n"));
    }

    @Test
    public void rejectsBadVolumes() {
        // named volume (bare name source)
        ComposeParseException e =
                rejected(project("    volumes:\n      - data:/data\n"));
        assertTrue(e.getMessage().contains("services.web.volumes[0]"));
        // anonymous volume (target only)
        rejected(project("    volumes:\n      - /data\n"));
        // unsupported mode
        rejected(project("    volumes:\n      - /a:/b:zz\n"));
        rejected(project("    volumes:\n      - /a:/b:ro:extra\n"));
        // traversal target
        rejected(project("    volumes:\n      - /a:/b/../c\n"));
        rejected(project("    volumes:\n      - /a:relative\n"));
        // long form: unknown key, non-bind type, missing pieces
        rejected(project("    volumes:\n      - type: bind\n        source: /a\n"
                + "        target: /b\n        propagation: rslave\n"));
        rejected(project("    volumes:\n      - type: volume\n        source: data\n"
                + "        target: /b\n"));
        rejected(project("    volumes:\n      - type: bind\n        source: /a\n"));
        rejected(project("    volumes:\n      - type: bind\n        source: data\n"
                + "        target: /b\n"));
    }

    @Test
    public void rejectsBadPorts() {
        // container-only, both string and numeric forms
        ComposeParseException e =
                rejected(project("    ports:\n      - \"80\"\n"));
        assertTrue(e.getMessage().contains("services.web.ports[0]"));
        rejected(project("    ports:\n      - 80\n"));
        // non-numeric and out-of-range ports
        rejected(project("    ports:\n      - \"abc:80\"\n"));
        rejected(project("    ports:\n      - \"70000:80\"\n"));
        rejected(project("    ports:\n      - \"8080:0\"\n"));
        // unbracketed multi-colon host (ambiguous IPv6)
        rejected(project("    ports:\n      - \"::1:8080:80\"\n"));
        // hostname host_ip is not a numeric literal
        rejected(project("    ports:\n      - \"localhost:8080:80\"\n"));
        // bad protocol
        rejected(project("    ports:\n      - \"8080:80/sctp\"\n"));
        // long form: missing published, unknown key
        rejected(project("    ports:\n      - target: 80\n"));
        rejected(project("    ports:\n      - target: 80\n        published: 8080\n"
                + "        mode: host\n"));
        rejected(project("    ports:\n      - target: eighty\n"
                + "        published: 8080\n"));
        // ports must be a list
        rejected(project("    ports: \"8080:80\"\n"));
    }

    @Test
    public void rejectsBadDependsOn() {
        // long condition form
        ComposeParseException e = rejected(project(
                "    depends_on:\n      db:\n        condition: service_started\n"));
        assertTrue(e.getMessage().contains("services.web.depends_on"));
        // non-string entry
        rejected(project("    depends_on:\n      - {service: db}\n"));
        // unknown dependency
        e = rejected(project("    depends_on:\n      - ghost\n"));
        assertTrue(e.getMessage().contains("unknown service 'ghost'"));
        // self-dependency
        rejected(project("    depends_on:\n      - web\n"));
        // cycle a -> b -> a
        rejected("services:\n"
                + "  a:\n    image: i1\n    depends_on: [b]\n"
                + "  b:\n    image: i2\n    depends_on: [a]\n");
    }

    @Test
    public void rejectsBadRestartValues() {
        rejected(project("    restart: on-failure\n"));
        rejected(project("    restart: on-failure:3\n"));
        rejected(project("    restart: true\n"));
        rejected(project("    restart: {policy: always}\n"));
    }

    @Test
    public void rejectsDuplicateKeys() {
        rejected("services:\n  web:\n    image: a\n    image: b\n");
    }

    @Test
    public void rejectsAliasBombs() {
        StringBuilder yaml = new StringBuilder("anchor: &x [1]\nservices:\n  web:\n"
                + "    image: i\n    command:\n");
        for (int i = 0; i < 60; i++) {
            yaml.append("      - *x\n");
        }
        rejected(yaml.toString());
    }

    @Test
    public void rejectsOversizedDocuments() {
        StringBuilder yaml = new StringBuilder(project(""));
        for (int i = 0; i < 1050; i++) {
            yaml.append("#").append(new char[1024]).append('\n');
        }
        rejected(yaml.toString());
    }

    @Test
    public void rejectsBlankProjectNameAsCallerError() {
        for (String bad : new String[]{null, "   "}) {
            try {
                ComposeYamlParser.parse(project(""), bad);
                fail("expected rejection of projectName: " + bad);
            } catch (IllegalArgumentException expected) {
                // expected
            } catch (ComposeParseException unexpected) {
                fail("blank projectName is a caller error, not a parse error");
            }
        }
    }
}
