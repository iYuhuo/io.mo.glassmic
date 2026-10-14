import io.mo.glassmic.core.model.SourceType;
import io.mo.glassmic.data.runtime.DecisionHistory;
import io.mo.glassmic.data.runtime.DecisionRecord;
import io.mo.glassmic.xposed.SourceQueryPolicy;
import io.mo.glassmic.xposed.XposedHookGate;
import java.util.List;

/** Exercises production policies on the JVM without hooking a device or application. */
public final class HookDiagnosticsRegression {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static DecisionRecord decision(long time, String pkg, String reason, String backend) {
        return new DecisionRecord(time, pkg, SourceType.REAL_MIC, reason, reason, time, 1, backend);
    }

    private static void queryPacing() {
        SourceQueryPolicy policy = new SourceQueryPolicy();
        check(policy.shouldQuery(0, false), "Initial query missing");
        policy.onQuery(0, true);
        check(!policy.shouldQuery(240, false), "Idle poll performed IPC");
        check(!policy.shouldQuery(4999, false), "Idle query too soon");
        check(policy.shouldQuery(5000, false), "Idle configuration refresh missing");
        policy.onQuery(5000, true);
        check(policy.shouldQuery(5020, true), "Capture onset did not bypass idle cache");
        policy.onQuery(5020, true);
        check(!policy.shouldQuery(5100, true), "Active query too soon");
        check(policy.shouldQuery(5220, true), "Active configuration update delayed");
        policy.onQuery(5220, true);
        check(!policy.shouldQuery(7000, false), "Capture ending did not restore idle pacing");

        SourceQueryPolicy hidden = new SourceQueryPolicy();
        check(hidden.shouldQuery(0, false), "Initial hidden-provider query missing");
        hidden.onQuery(0, false);
        long[] due = {2000, 6000, 14000, 30000, 60000};
        for (long now : due) {
            check(!hidden.shouldQuery(now - 1, false), "Unreachable backoff too short");
            check(hidden.shouldQuery(now, false), "Unreachable recovery probe missing");
            hidden.onQuery(now, false);
        }
        check(hidden.shouldQuery(60001, true), "Recording did not bypass longest backoff");
        hidden.onQuery(60001, false);
        check(!hidden.shouldQuery(60100, true), "Active unreachable query loop");
        check(hidden.shouldQuery(62001, true), "Active recovery probe delayed");
        hidden.onQuery(62001, true);
        check(hidden.shouldQuery(62201, true), "Recovery did not reset failure backoff");
        System.out.println("PASS: idle pacing, capture onset, unreachable backoff and recovery");
    }

    private static void historyRetention() {
        DecisionHistory history = new DecisionHistory(4, 2);
        history.record(decision(1, "target", "INTERCEPT_OK", "LSPOSED"));
        for (int i = 2; i <= 10001; i++)
            history.record(decision(i, "poller", "SCOPE_FILTERED", "LSPOSED"));
        List<DecisionRecord> records = history.recent();
        check(records.size() == 2, "Repeated polling expanded history");
        check(records.get(0).getOccurrences() == 10000, "Duplicate count lost");
        check(records.get(0).getTimestamp() == 2 && records.get(0).getLastSeen() == 10001,
            "First/last timestamps not preserved");
        check(records.stream().anyMatch(r -> r.getCallerPackage().equals("target")), "Target evidence evicted");
        history.record(decision(10002, "poller", "GLOBAL_SWITCH_OFF", "LSPOSED"));
        history.record(decision(10003, "poller", "BOOT_GATE_LOCKED", "LSPOSED"));
        check(history.recent().size() == 3, "Per-package transition bound broken");
        check(history.recent().stream().anyMatch(r -> r.getCallerPackage().equals("target")),
            "Other-package transitions evicted target");
        history.record(decision(10004, "target", "INTERCEPT_OK", "AUDIO_POLICY"));
        check(history.recent().stream().filter(r -> r.getCallerPackage().equals("target")).count() == 2,
            "Different backends merged");
        history.record(decision(10005, "new1", "SCOPE_FILTERED", "LSPOSED"));
        history.record(decision(10006, "new2", "SCOPE_FILTERED", "LSPOSED"));
        check(history.recent().size() <= 8, "History unbounded");
        check(history.recent().stream().noneMatch(r -> r.getCallerPackage().equals("target") && r.getBackend().equals("LSPOSED")),
            "Least-recently-observed package not evicted at capacity");
        System.out.println("PASS: per-package evidence, deduplication, transitions, backends and bounded retention");
    }

    public static void main(String[] args) {
        queryPacing();
        historyRetention();
        check(XposedHookGate.INSTANCE.shouldSkipPackage("com.android.location.fused"), "Location process unprotected");
        check(XposedHookGate.INSTANCE.shouldSkipPackage("io.mo.glassmic"), "Self protection lost");
        check(!XposedHookGate.INSTANCE.shouldSkipPackage("cn.zte.recorder"), "Recorder unexpectedly excluded");
        System.out.println("PASS: location and self protection, ordinary recording app remains eligible");
    }
}
