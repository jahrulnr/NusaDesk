package gh.nusashell.nusadesk.infrastructure.proot;

import gh.nusashell.nusadesk.infrastructure.androidbridge.CalendarWriteRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Executes the shipped shell example against a recording CLI, never a real calendar. */
public class GuestCalendarDocExampleTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void exampleRemainsValidAndUsesTheCreatedEventId() throws Exception {
        // A second, distant clock catches examples tied to today's date.
        runExample(Instant.parse("2026-10-29T00:00:00Z").getEpochSecond(), 731);
        runExample(Instant.parse("2031-08-12T13:00:00Z").getEpochSecond(), 1907);
    }

    private void runExample(long nowSeconds, long eventId) throws Exception {
        Path dir = temporary.newFolder().toPath();
        Path calls = dir.resolve("calls.tsv");
        Path bin = Files.createDirectory(dir.resolve("bin"));
        Path cli = bin.resolve("nusadesk-android");
        Files.writeString(cli, String.join("\n",
                "#!/usr/bin/env python3",
                "import json, os, sys",
                "with open(os.environ['DOC_CALLS'], 'a') as log:",
                "    log.write('\\t'.join(sys.argv[1:]) + '\\n')",
                "print(json.dumps({'ok': True, 'written': True,",
                "                  'event_id': int(os.environ['DOC_EVENT_ID'])}))", ""));
        assertTrue(cli.toFile().setExecutable(true));
        Path date = bin.resolve("date");
        Files.writeString(date, "#!/bin/sh\n[ \"$1\" = +%s ] || exit 2\nprintf '%s\\n' \"$DOC_NOW_SECONDS\"\n");
        assertTrue(date.toFile().setExecutable(true));

        String doc = GuestDocsFixture.doc("docs/calendar.md", "test");
        var block = Pattern.compile("```sh\\n(.*?)```", Pattern.DOTALL).matcher(doc);
        assertTrue("calendar doc must contain a shell example", block.find());
        Path script = dir.resolve("example.sh");
        Files.writeString(script, block.group(1));
        Path output = dir.resolve("output.txt");
        ProcessBuilder command = new ProcessBuilder("sh", script.toString());
        command.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        command.environment().put("DOC_CALLS", calls.toString());
        command.environment().put("DOC_NOW_SECONDS", Long.toString(nowSeconds));
        command.environment().put("DOC_EVENT_ID", Long.toString(eventId));
        command.redirectErrorStream(true).redirectOutput(output.toFile());
        Process process = command.start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("calendar shell example timed out");
        }
        assertEquals(Files.readString(output), 0, process.exitValue());

        List<String> rows = Files.readAllLines(calls);
        assertEquals("list, add, update, delete", 4, rows.size());
        assertEquals("calendar\tlist", rows.get(0));
        String[] add = rows.get(1).split("\t");
        assertEquals("add", add[1]);
        Map<String, Object> params = Map.of("title", flag(add, "--title"),
                "begin_ms", Long.parseLong(flag(add, "--begin-ms")),
                "end_ms", Long.parseLong(flag(add, "--end-ms")));
        var parsed = CalendarWriteRequest.insert(params, nowSeconds * 1000L);
        assertTrue("documented add must pass the real calendar validator", parsed.isOk());
        assertEquals(Long.valueOf((nowSeconds + 3600) * 1000L), parsed.getRequest().getBeginMillis());
        assertEquals(Long.valueOf((nowSeconds + 7200) * 1000L), parsed.getRequest().getEndMillis());
        assertEquals("calendar\tupdate\t" + eventId + "\t--title\tRapat baru", rows.get(2));
        assertEquals("calendar\tdelete\t" + eventId, rows.get(3));
    }

    private static String flag(String[] args, String name) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (name.equals(args[i])) return args[i + 1];
        }
        throw new AssertionError("missing documented flag: " + name);
    }
}
