package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.Context;
import android.util.JsonWriter;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Bounded on-disk record of the last execution outcome of each scheduled
 * guest job. One tiny {@code job-<id>.outcome} file per job under app-private
 * storage; the platform keeps the {@code JobInfo} itself, so this store only
 * carries what JobScheduler cannot tell a guest: whether the last trigger ran
 * the script, hit a dead guest session, or failed.
 *
 * <p>Files are flat ASCII {@code result|fired_ms|exit_code}; the store caps
 * itself at {@link #MAX_RECORDS} files and prunes oldest first, so it can
 * never grow without bound. It is deliberately best-effort — a corrupt or
 * unreadable record is dropped, never surfaced as a platform message.</p>
 */
final class CommsJobStore {

    /** The guest session was not running when the trigger fired. */
    static final String RESULT_SESSION_DOWN = "session-down";
    /** The script ran over the guest SSH channel; {@code exit_code} carries it. */
    static final String RESULT_RAN = "ran";
    /** The job was stopped or the exec could not start (typed: {@code failed}). */
    static final String RESULT_FAILED = "failed";

    private static final String DIR_NAME = "jobscheduler";
    private static final String SUFFIX = ".outcome";
    private static final int MAX_RECORDS = 64;
    private static final int MAX_FIELD = 32;

    private CommsJobStore() {
    }

    /** One job's most recent outcome. */
    static final class Outcome {
        final int jobId;
        final String result;
        final long firedMs;
        /** Exit status of the guest script, or {@code null} when it never ran. */
        final Integer exitCode;

        Outcome(int jobId, String result, long firedMs, Integer exitCode) {
            this.jobId = jobId;
            this.result = result;
            this.firedMs = firedMs;
            this.exitCode = exitCode;
        }
    }

    static void record(Context context, int jobId, String result, Integer exitCode) {
        File dir = dir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return;
        }
        File file = file(dir, jobId);
        StringBuilder line = new StringBuilder()
                .append(result).append('|')
                .append(System.currentTimeMillis()).append('|')
                .append(exitCode == null ? "-" : exitCode.intValue());
        FileWriter writer = null;
        try {
            writer = new FileWriter(file, false);
            writer.write(line.toString());
        } catch (IOException ignored) {
            // Outcome records are best-effort; never fail the job over it.
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                }
            }
        }
        prune(dir);
    }

    static Outcome load(Context context, int jobId) {
        return parse(jobId, read(file(dir(context), jobId)));
    }

    /**
     * Outcomes for jobs that are no longer pending (one-shots that fired, jobs
     * that missed their trigger), newest first, bounded to {@code max}.
     */
    static List<Outcome> recent(Context context, Set<Integer> pendingIds, int max) {
        List<Outcome> out = new ArrayList<>();
        File[] files = dir(context).listFiles();
        if (files == null) {
            return out;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (File file : files) {
            if (out.size() >= max) {
                break;
            }
            String name = file.getName();
            if (!name.startsWith("job-") || !name.endsWith(SUFFIX)) {
                continue;
            }
            int jobId;
            try {
                jobId = Integer.parseInt(
                        name.substring(4, name.length() - SUFFIX.length()));
            } catch (NumberFormatException e) {
                continue;
            }
            if (pendingIds.contains(jobId)) {
                continue;
            }
            Outcome outcome = parse(jobId, read(file));
            if (outcome != null) {
                out.add(outcome);
            }
        }
        return out;
    }

    static void delete(Context context, int jobId) {
        file(dir(context), jobId).delete();
    }

    static void clear(Context context) {
        File[] files = dir(context).listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.getName().endsWith(SUFFIX)) {
                file.delete();
            }
        }
    }

    private static void prune(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= MAX_RECORDS) {
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i <= files.length - MAX_RECORDS; i++) {
            files[i].delete();
        }
    }

    private static Outcome parse(int jobId, String content) {
        if (content == null) {
            return null;
        }
        String[] parts = content.trim().split("\\|", -1);
        if (parts.length < 3) {
            return null;
        }
        try {
            long firedMs = Long.parseLong(parts[1]);
            Integer exitCode = "-".equals(parts[2])
                    ? null : Integer.valueOf(parts[2]);
            return new Outcome(jobId, clip(parts[0]), firedMs, exitCode);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String clip(String value) {
        return value.length() <= MAX_FIELD ? value : value.substring(0, MAX_FIELD);
    }

    private static String read(File file) {
        if (!file.isFile()) {
            return null;
        }
        char[] buffer = new char[128];
        FileReader reader = null;
        try {
            reader = new FileReader(file);
            int read = reader.read(buffer);
            return read <= 0 ? null : new String(buffer, 0, read);
        } catch (IOException e) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static File dir(Context context) {
        return new File(context.getFilesDir(), DIR_NAME);
    }

    private static File file(File dir, int jobId) {
        return new File(dir, "job-" + jobId + SUFFIX);
    }

    /** Encode a recent-outcome list as the {@code recent_json} field value. */
    static String recentJson(List<Outcome> outcomes) {
        StringWriter out = new StringWriter();
        JsonWriter writer = new JsonWriter(out);
        try {
            writer.beginArray();
            for (Outcome outcome : outcomes) {
                writer.beginObject();
                writer.name("id").value(outcome.jobId);
                writer.name("result").value(outcome.result);
                writer.name("fired_ms").value(outcome.firedMs);
                if (outcome.exitCode != null) {
                    writer.name("exit_code").value(outcome.exitCode.intValue());
                }
                writer.endObject();
            }
            writer.endArray();
            writer.flush();
        } catch (IOException e) {
            return "[]";
        } finally {
            try {
                writer.close();
            } catch (IOException ignored) {
            }
        }
        return out.toString();
    }
}
