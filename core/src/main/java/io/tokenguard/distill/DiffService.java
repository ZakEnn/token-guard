package io.tokenguard.distill;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;

import java.util.List;

/** Minimal unified diffs (Engine 4, plan §2 diffing row). */
public final class DiffService {

    private static final int CONTEXT_LINES = 3;

    public String unifiedDiff(String path, String before, String after) {
        List<String> a = before.lines().toList();
        List<String> b = after.lines().toList();
        Patch<String> patch = DiffUtils.diff(a, b);
        if (patch.getDeltas().isEmpty()) return "";
        List<String> diff = UnifiedDiffUtils.generateUnifiedDiff(
                "a/" + path, "b/" + path, a, patch, CONTEXT_LINES);
        return String.join("\n", diff);
    }
}
