package io.tokenguard.distill;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tokenguard.cache.DependencyTracker;
import io.tokenguard.store.Database;
import io.tokenguard.telemetry.Metrics;
import io.tokenguard.util.IgnoreRules;
import io.tokenguard.util.Json;
import io.tokenguard.util.PathJail;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

/** Shared wiring for Engine 4 tool tests. */
final class DistillFixture implements AutoCloseable {

    final Database db;
    final PathJail jail;
    final Metrics metrics;
    final FileStateTracker tracker;
    final DependencyTracker deps = new DependencyTracker();
    final DiffService diff = new DiffService();
    final ReadFileTool readFile;
    final ApplyEditTool applyEdit;
    final ListDirectoryTool listDirectory;
    final SearchFilesTool searchFiles;

    DistillFixture(Path workspace, Path dataDir) throws SQLException, IOException {
        this.db = Database.open(dataDir.resolve("guard.db"));
        this.jail = new PathJail(workspace);
        this.metrics = new Metrics(db);
        this.tracker = new FileStateTracker(db);
        IgnoreRules ignore = new IgnoreRules(workspace);
        this.readFile = new ReadFileTool(jail, tracker, diff, metrics, () -> true);
        this.applyEdit = new ApplyEditTool(jail, tracker, diff, metrics);
        this.listDirectory = new ListDirectoryTool(jail, ignore, metrics);
        this.searchFiles = new SearchFilesTool(jail, ignore, metrics, deps);
    }

    static ObjectNode args(Object... kv) {
        ObjectNode node = Json.obj();
        for (int i = 0; i < kv.length; i += 2) {
            String key = (String) kv[i];
            Object val = kv[i + 1];
            switch (val) {
                case Integer n -> node.put(key, n);
                case String s -> node.put(key, s);
                case ObjectNode o -> node.set(key, o);
                default -> node.putPOJO(key, val);
            }
        }
        return node;
    }

    @Override public void close() { db.close(); }
}
