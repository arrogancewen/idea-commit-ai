package com.commitai.idea;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.CommitMessageI;
import com.intellij.vcs.commit.CommitWorkflowUi;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Project service that owns the running generation task.
 *
 * <p>Keeps the "am I generating right now" state so the toolbar button can turn
 * into a cancel button, exactly like the Copilot action does.
 */
public final class CommitMessageGenerator {

    public static final String NOTIFICATION_GROUP = "CommitAi.Notifications";

    private final Project project;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    public CommitMessageGenerator(@NotNull Project project) {
        this.project = project;
    }

    public boolean isRunning() {
        return running.get();
    }

    /** Requests cancellation of the in-flight generation. */
    public void cancel() {
        cancelRequested.set(true);
    }

    public void generate(@Nullable CommitWorkflowUi ui, @NotNull CommitMessageI target) {
        if (!running.compareAndSet(false, true)) {
            cancel();
            return;
        }
        cancelRequested.set(false);

        CommitAiSettings settings = CommitAiSettings.getInstance();
        final CommitAiSettings.State state = snapshot(settings.getState());
        final String apiKey = settings.getApiKey();
        // generate() is called from actionPerformed, i.e. on the EDT: snapshot the UI model here.
        final DiffCollector.CapturedChanges captured = DiffCollector.capture(ui);

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Generating commit message", true) {
            private String message;
            private String error;
            private boolean cancelled;
            private int maxBullets;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    DiffCollector.Result result =
                            DiffCollector.collect(project, captured, state.maxDiffChars, state.includeUnversionedFiles);

                    if (cancelRequested.get() || indicator.isCanceled()) {
                        cancelled = true;
                        return;
                    }
                    if (result.diffs.isEmpty()) {
                        error = "No changes found to describe.";
                        return;
                    }

                    // Message length scales with the size of the change: 1-2 files stay a
                    // one-liner, a multi-area commit gets a few bullets grouped by concern.
                    maxBullets = CommitMessageFormatter.resolveMaxBullets(state.detailLevel, result.totalFiles);

                    LlmClient client = new LlmClient(state);
                    message = client.complete(
                            apiKey,
                            PromptTemplates.systemPrompt(state, maxBullets),
                            PromptTemplates.userPrompt(result.diffs, result.filePaths, result.truncated, maxBullets));
                } catch (Exception e) {
                    error = e.getMessage() == null || e.getMessage().isBlank()
                            ? e.getClass().getSimpleName()
                            : e.getMessage();
                }
            }

            @Override
            public void onSuccess() {
                running.set(false);
                if (cancelled) {
                    return;
                }
                if (error != null) {
                    showNotification("Failed to generate commit message", error, NotificationType.ERROR);
                    return;
                }
                // Enforce the shape in code: prompting alone is not a guarantee.
                String formatted = CommitMessageFormatter.format(message, maxBullets);
                if (!formatted.isBlank()) {
                    // onSuccess runs on the EDT, which is where the message control must be touched.
                    target.setCommitMessage(formatted);
                }
            }

            @Override
            public void onCancel() {
                running.set(false);
            }

            @Override
            public void onThrowable(@NotNull Throwable e) {
                running.set(false);
                showNotification("Failed to generate commit message",
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                        NotificationType.ERROR);
            }
        });
    }

    private void showNotification(String title, String content, NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(title, content == null ? "" : content, type)
                .notify(project);
    }

    private static CommitAiSettings.State snapshot(CommitAiSettings.State source) {
        CommitAiSettings.State copy = new CommitAiSettings.State();
        copy.apiType = source.apiType;
        copy.baseUrl = source.baseUrl;
        copy.model = source.model;
        copy.language = source.language;
        copy.conventionalCommits = source.conventionalCommits;
        copy.customInstructions = source.customInstructions;
        copy.maxDiffChars = source.maxDiffChars;
        copy.timeoutSeconds = source.timeoutSeconds;
        copy.maxOutputTokens = source.maxOutputTokens;
        copy.temperature = source.temperature;
        copy.includeUnversionedFiles = source.includeUnversionedFiles;
        return copy;
    }
}
