package com.commitai.idea;

import com.intellij.openapi.diff.impl.patch.FilePatch;
import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder;
import com.intellij.openapi.diff.impl.patch.UnifiedDiffWriter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.vcs.commit.CommitWorkflowUi;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Turns the changes selected in the commit tool window into unified diff text.
 *
 * <p>Threading: {@link CommitWorkflowUi} is a UI model and must only be read on the
 * EDT, so {@link #capture} snapshots the selection there. Everything after that
 * ({@link #collect}) is pure background work. This mirrors how the Copilot action
 * splits {@code CapturedCommitData} from its diff service.
 *
 * <p>Budgeting: rather than dropping whole files once the diff exceeds the character
 * budget (which used to hide entire areas of a large commit from the model), every
 * file gets an equal share of the budget and is truncated individually. The complete
 * list of changed paths is always reported so the model knows the real scope even
 * when individual diffs are cut short.
 */
public final class DiffCollector {

    /** Snapshot of the commit tool window selection, taken on the EDT. */
    public static final class CapturedChanges {
        final List<Change> changes;
        final List<FilePath> unversionedFiles;

        CapturedChanges(List<Change> changes, List<FilePath> unversionedFiles) {
            this.changes = changes;
            this.unversionedFiles = unversionedFiles;
        }
    }

    /** Outcome of a diff collection run. */
    public static final class Result {
        /** Unified diff text for the files that had one, possibly truncated per file. */
        public final List<String> diffs;
        /** Every changed path, including files whose diff was dropped or unavailable. */
        public final List<String> filePaths;
        public final int totalFiles;
        public final boolean truncated;

        Result(List<String> diffs, List<String> filePaths, int totalFiles, boolean truncated) {
            this.diffs = diffs;
            this.filePaths = filePaths;
            this.totalFiles = totalFiles;
            this.truncated = truncated;
        }
    }

    private static final int MIN_PER_FILE_CHARS = 800;
    private static final int MAX_PER_FILE_CHARS = 20000;

    private DiffCollector() {
    }

    /** Must be called on the EDT. Returns {@code null} when there is no commit tool window. */
    public static @Nullable CapturedChanges capture(@Nullable CommitWorkflowUi ui) {
        if (ui == null) {
            return null;
        }
        return new CapturedChanges(
                new ArrayList<>(ui.getIncludedChanges()),
                new ArrayList<>(ui.getIncludedUnversionedFiles()));
    }

    /**
     * Background work: builds the diff text for the captured selection.
     *
     * @param captured           snapshot from {@link #capture}, or {@code null} to use all changes
     * @param maxChars           budget for the combined diff text
     * @param includeUnversioned whether new/untracked files should be described too
     */
    public static @NotNull Result collect(@NotNull Project project,
                                          @Nullable CapturedChanges captured,
                                          int maxChars,
                                          boolean includeUnversioned) {
        List<Change> changes = resolveChanges(project, captured, includeUnversioned);

        // Paths for every change, even the ones whose diff cannot be produced.
        List<String> paths = new ArrayList<>(changes.size());
        for (Change change : changes) {
            String path = pathOf(change);
            if (path != null) {
                paths.add(path);
            }
        }
        int fileCount = paths.size();
        if (fileCount == 0) {
            return new Result(Collections.emptyList(), Collections.emptyList(), 0, false);
        }

        int budget = Math.max(1000, maxChars);
        int perFile = Math.min(MAX_PER_FILE_CHARS, Math.max(MIN_PER_FILE_CHARS, budget / fileCount));

        boolean anyTruncated = false;
        List<String> raw = new ArrayList<>(fileCount);
        for (Change change : changes) {
            String diff = diffForChange(project, change);
            if (diff == null || diff.isBlank()) {
                continue;
            }
            if (diff.length() > perFile) {
                diff = cut(diff, perFile) + "\n... (diff truncated)\n";
                anyTruncated = true;
            }
            raw.add(diff);
        }

        if (raw.isEmpty()) {
            return new Result(Collections.emptyList(), paths, fileCount, false);
        }

        // Second pass: if even the per-file shares do not fit, drop from the largest.
        long total = 0;
        for (String d : raw) {
            total += d.length();
        }
        boolean droppedFiles = false;
        if (total > budget) {
            List<String> kept = new ArrayList<>(raw);
            kept.sort(Comparator.comparingInt(String::length));
            while (!kept.isEmpty() && total > budget) {
                String removed = kept.remove(kept.size() - 1);
                total -= removed.length();
                droppedFiles = true;
            }
            raw = kept;
        }

        return new Result(raw, paths, fileCount, anyTruncated || droppedFiles);
    }

    private static @Nullable String pathOf(@NotNull Change change) {
        ContentRevision after = change.getAfterRevision();
        FilePath file = after == null ? null : after.getFile();
        if (file == null) {
            ContentRevision before = change.getBeforeRevision();
            file = before == null ? null : before.getFile();
        }
        return file == null ? null : file.getPath();
    }

    private static @NotNull List<Change> resolveChanges(@NotNull Project project,
                                                        @Nullable CapturedChanges captured,
                                                        boolean includeUnversioned) {
        List<Change> changes = new ArrayList<>();
        List<FilePath> unversioned = new ArrayList<>();

        if (captured != null) {
            changes.addAll(captured.changes);
            if (includeUnversioned) {
                unversioned.addAll(captured.unversionedFiles);
            }
        }

        // Nothing selected (or no commit tool window): fall back to everything the
        // IDE knows about, the same way the Copilot action does.
        if (changes.isEmpty() && unversioned.isEmpty()) {
            ChangeListManager changeListManager = ChangeListManager.getInstance(project);
            changes.addAll(changeListManager.getAllChanges());
            if (includeUnversioned) {
                unversioned.addAll(changeListManager.getUnversionedFilesPaths());
            }
        }

        for (FilePath path : unversioned) {
            if (LocalFileSystem.getInstance().findFileByPath(path.getPath()) != null) {
                changes.add(new Change(null, new CurrentContentRevision(path)));
            }
        }
        return changes;
    }

    private static @Nullable String diffForChange(@NotNull Project project, @NotNull Change change) {
        try {
            FilePath file = null;
            ContentRevision after = change.getAfterRevision();
            if (after != null) {
                file = after.getFile();
            }
            if (file == null) {
                ContentRevision before = change.getBeforeRevision();
                file = before == null ? null : before.getFile();
            }
            if (file == null || file.getParentPath() == null) {
                return null;
            }

            Path basePath = file.getParentPath().getIOFile().toPath();
            List<FilePatch> patches = IdeaTextPatchBuilder.buildPatch(
                    project, Collections.singletonList(change), basePath, false, true);
            if (patches.isEmpty()) {
                return null;
            }

            StringWriter out = new StringWriter();
            UnifiedDiffWriter.write(project, patches, out, "\n", null);
            String text = out.toString();
            return text.isEmpty() ? null : text;
        } catch (Exception e) {
            // Binary files, deleted-but-unreadable files, unsupported VCS states: skip quietly.
            return null;
        }
    }

    /** {@code substring} that never splits a surrogate pair. */
    private static @NotNull String cut(@NotNull String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        int end = limit;
        if (Character.isHighSurrogate(text.charAt(end - 1)) && end < text.length()
                && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        return text.substring(0, end);
    }
}
