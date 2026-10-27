package ru.pukpukov.pwrap.core;

import module java.base;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import ru.pukpukov.pwrap.backends.nixos.SystemdInstances;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledInNativeImage;
import static org.junit.jupiter.api.Assertions.*;
import static ru.pukpukov.pwrap.core.TestJson.obj;

/** Cohort invariants from test_shared_state.py, with real flock and simulated cgroup files. */
@EnabledInNativeImage
class SharedStateTest {
    
    final Path root;
    Path records, groups;
    Instances instances;
    int counter;

    private static final Instances.Codec<JsonObject, JsonObject> CODEC = new Instances.Codec<>("test-v1", JsonObject.class, JsonObject.class);

    SharedStateTest(@TempDir Path root) throws IOException {
        this.root = root;
        records = root.resolve("instances/app");
        groups = root.resolve("cgroups");
        instances = SystemdInstances.create(root.resolve("instances"), groups, root.resolve("proc"));
    }

    private JsonObject capture() { return obj("captured", ++counter); }

    private Instances.Lease<JsonObject, JsonObject> lease(int intent, boolean scratch) throws Exception {
        return instances.lease("app", CODEC, obj("intent", intent), this::capture, scratch);
    }

    private long count(String glob) throws IOException {
        if (!Files.isDirectory(records)) return 0;
        try (var entries = Files.newDirectoryStream(records, glob)) {
            long result = 0;
            for (Path ignored : entries) result++;
            return result;
        }
    }

    private void publish(String unit, Instances.Snapshot<JsonObject, JsonObject> snapshot) throws Exception {
        Path group = Files.createDirectories(groups.resolve(unit));
        Files.writeString(group.resolve("cgroup.events"), "populated 1\n");
        instances.register("app", unit, "fingerprint", Map.of(),
            Map.of(CODEC.key(), Json.encode(snapshot).getAsJsonObject()), true
        );
    }

    @Test void test_same_cohort_shares_one_capture_and_fresh_cohort_recaptures() throws Exception {
        try (var first = lease(1, false)) {
            try (var second = lease(1, false)) {
                assertEquals(first.snapshot, second.snapshot);
                assertEquals(1, counter);
                second.snapshot.state().addProperty("captured", "caller-mutation");
            }
            try (var third = lease(1, false)) { assertEquals(first.snapshot, third.snapshot); }
        }
        try (var fresh = lease(1, false)) { assertEquals(obj("captured", 2), fresh.snapshot.state()); }
        assertEquals(0, count("*.lease"));
    }

    @Test void test_changed_intent_never_reuses_or_overwrites_live_state() throws Exception {
        try (var _ = lease(1, false)) {
            assertThrows(IllegalArgumentException.class, () -> lease(2, false));
            assertEquals(1, counter);
        }
    }

    @Test void test_live_child_keeps_snapshot_after_outer_lease_exits() throws Exception {
        Instances.Snapshot<JsonObject, JsonObject> snapshot;
        try (var first = lease(1, false)) {
            snapshot = first.snapshot;
            publish("pwrap-test.service", snapshot);
        }
        try (var borrowed = lease(1, false)) {
            assertEquals(snapshot, borrowed.snapshot);
            assertEquals(1, counter);
        }
        instances.release("app", "pwrap-test.service");
        try (var fresh = lease(1, false)) { assertEquals(obj("captured", 2), fresh.snapshot.state()); }
    }

    @Test void test_missing_or_inconsistent_live_child_records_fail_closed() throws Exception {
        String unit = "pwrap-test.service";
        Files.createDirectories(records);
        Files.createDirectories(groups.resolve(unit));
        Files.writeString(groups.resolve(unit + "/cgroup.events"), "populated 1\n");
        Path file = records.resolve(unit + ".json");
        for (JsonObject extra : List.of(obj(), obj("snapshots", obj()), obj("snapshots", obj(CODEC.key(), obj("state", obj()))))) {
            JsonObject value = obj("unit", unit, "fingerprint", "fingerprint", "launchEnvironment", obj());
            extra.entrySet().forEach(e -> value.add(e.getKey(), e.getValue()));
            Files.writeString(file, value.toString());
            assertThrows(IllegalArgumentException.class, () -> lease(1, false));
        }
        Files.delete(file);
        publish(unit, new Instances.Snapshot<>(obj("intent", 1), obj("captured", 1), null));
        publish("pwrap-second.service", new Instances.Snapshot<>(obj("intent", 1), obj("captured", 2), null));
        assertThrows(IllegalArgumentException.class, () -> lease(1, false));
    }

    @Test void test_dead_pending_lease_is_not_an_immortal_snapshot() throws Exception {
        Files.createDirectories(records);
        Path stale = records.resolve(".snapshot-test-v1-stale.lease");
        Files.writeString(stale, Json.encode(new Instances.Snapshot<>(obj("intent", 1), obj("captured", "dead"), null)).toString());
        // An unlocked lease is exactly the on-disk state after a holder exits or is killed.
        try (var fresh = lease(1, false)) { assertEquals(obj("captured", 1), fresh.snapshot.state()); }
        assertEquals(0, count("*.lease"));
    }

    @Test void dead_legacy_record_is_checked_for_liveness_before_requiring_snapshots() throws Exception {
        Files.createDirectories(records);
        String unit = "pwrap-legacy.service";
        Path file = records.resolve(unit + ".json");
        for (boolean absentGroup : List.of(true, false)) for (boolean nullSnapshots : List.of(false, true)) {
            if (!absentGroup) Files.writeString(Files.createDirectories(groups.resolve(unit)).resolve("cgroup.events"), "populated 0\n");
            JsonObject legacy = obj("unit", unit, "fingerprint", "old", "launchEnvironment", obj());
            if (nullSnapshots) legacy.add("snapshots", JsonNull.INSTANCE);
            Files.writeString(file, legacy.toString());
            try (var fresh = lease(1, false)) {
                assertEquals(obj("captured", counter), fresh.snapshot.state());
                assertFalse(Files.exists(file));
            }
        }
        assertEquals(4, counter);
    }

    @Test void live_legacy_record_is_preserved_and_never_silently_recaptured() throws Exception {
        Files.createDirectories(records);
        String unit = "pwrap-legacy.service";
        Path file = records.resolve(unit + ".json");
        Files.writeString(Files.createDirectories(groups.resolve(unit)).resolve("cgroup.events"), "populated 1\n");
        Path scratch = Files.createDirectory(records.resolve(".scratch-preserved"));
        for (boolean nullSnapshots : List.of(false, true)) {
            JsonObject legacy = obj("unit", unit, "fingerprint", "old", "launchEnvironment", obj());
            if (nullSnapshots) legacy.add("snapshots", JsonNull.INSTANCE);
            String original = legacy.toString();
            Files.writeString(file, original);
            var error = assertThrows(IllegalArgumentException.class, () -> lease(1, false));
            assertTrue(Json.required(error.getMessage(), "Expected compatibility diagnostic").contains("Close the existing instance " + unit));
            assertEquals(original, Files.readString(file));
            assertTrue(Files.isDirectory(scratch));
            assertEquals(0, counter);
            assertEquals(0, count("*.lease"));
        }
    }

    @Test void test_ad_hoc_never_borrows_named_cohort() throws Exception {
        try (
            var first = instances.lease("run", CODEC, obj(), this::capture, false);
            var second = instances.lease("run", CODEC, obj(), this::capture, false)
        ) {
            assertNotEquals(first.snapshot, second.snapshot);
        }
    }

    @Test void test_capture_failure_does_not_publish_a_partial_lease() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> instances.lease(
            "app", CODEC, obj(), () -> { throw new IllegalArgumentException("discovery failure"); }, false
        ));
        assertEquals(0, count("*.lease"));
    }

    @Test void test_scratch_is_private_to_one_cohort_and_removed_after_last_lease() throws Exception {
        Path scratch;
        try (var first = lease(1, true)) {
            scratch = Path.of(Json.required(first.snapshot.scratch(), "Expected fixture value"));
            assertEquals(records, Fs.parent(scratch));
            assertEquals(0700, ((Number) Files.getAttribute(scratch, "unix:mode")).intValue() & 0777);
            Files.writeString(scratch.resolve("state"), "first cohort");
            try (var second = lease(1, true)) { assertEquals(first.snapshot, second.snapshot); }
            assertTrue(Files.exists(scratch));
        }
        assertFalse(Files.exists(scratch));
        Path replacement;
        try (var fresh = lease(2, true)) {
            replacement = Path.of(Json.required(fresh.snapshot.scratch(), "Expected fixture value"));
            assertNotEquals(scratch, replacement);
            assertFalse(Files.exists(replacement.resolve("state")));
        }
        assertFalse(Files.exists(replacement));
    }

    @Test void test_live_child_and_pending_parent_keep_scratch_until_both_release() throws Exception {
        Path scratch;
        try (var first = lease(1, true)) {
            scratch = Path.of(Json.required(first.snapshot.scratch(), "Expected fixture value"));
            publish("pwrap-test.service", first.snapshot);
        }
        assertTrue(Files.exists(scratch));
        instances.release("app", "pwrap-test.service");
        assertFalse(Files.exists(scratch));
        instances.release("app", "pwrap-test.service");
        try (var pending = lease(1, true)) {
            scratch = Path.of(Json.required(pending.snapshot.scratch(), "Expected fixture value"));
            instances.release("app", "pwrap-test.service");
            assertTrue(Files.exists(scratch), "ExecStopPost raced with a pending launcher");
        }
        assertFalse(Files.exists(scratch));
    }

    @Test void test_cleanup_removes_only_generated_unowned_scratch_not_declared_paths() throws Exception {
        Files.createDirectories(records);
        Path selected = Files.createDirectory(records.resolve("ordinary-grant"));
        Path abandoned = Files.createDirectory(records.resolve(".scratch-generated"));
        Path external = Files.createDirectory(root.resolve("external"));
        Files.createSymbolicLink(abandoned.resolve("link"), external);
        try (var _ = lease(1, false)) {
            assertFalse(Files.exists(abandoned));
            assertTrue(Files.exists(selected));
            assertTrue(Files.exists(external));
        }
    }

    @Test void test_failed_publication_does_not_leave_scratch_or_half_lease() throws Exception {
        var unsupported = new Instances.Codec<>("test-v1", JsonObject.class, Double.class);
        // NaN is not JSON: discovery succeeds, serialization/publication must fail.
        assertThrows(IllegalArgumentException.class, () -> instances.lease("app", unsupported, obj(), () -> Double.NaN, true));
        assertEquals(0, count(".scratch-*"));
        assertEquals(0, count("*.lease"));
    }

    @Test void test_release_rejects_invalid_unit_without_touching_any_records() {
        for (String unit : List.of("../outside", "other.service", "pwrap-test.service/child")) {
            assertThrows(IllegalArgumentException.class, () -> instances.release("app", unit));
        }
    }

    @Test void test_missing_live_scratch_is_an_error_not_a_private_tmp_fallback() throws Exception {
        try (var first = lease(1, true)) {
            Files.delete(Path.of(Json.required(first.snapshot.scratch(), "Expected fixture value")));
            assertThrows(IllegalArgumentException.class, () -> lease(1, true));
        }
    }
}