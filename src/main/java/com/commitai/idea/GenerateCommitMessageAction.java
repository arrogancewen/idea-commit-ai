package com.commitai.idea;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.CommitMessageI;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.vcs.commit.AbstractCommitWorkflowHandler;
import com.intellij.vcs.commit.CommitWorkflowHandler;
import com.intellij.vcs.commit.CommitWorkflowUi;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Toolbar button in the Commit tool window ("Vcs.MessageActionGroup" - the same
 * place the GitHub Copilot commit button lives).
 *
 * <p>Reads the message control and the selected changes from the action's data
 * context, generates the message on a background thread, and writes it back.
 */
public final class GenerateCommitMessageAction extends AnAction implements DumbAware {

    private static final String TEXT = "Generate Commit Message with AI";
    private static final String DESC =
            "Generate a commit message from the selected changes using your own model endpoint";

    public GenerateCommitMessageAction() {
        super(TEXT, DESC, AllIcons.Actions.IntentionBulb);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Presentation presentation = e.getPresentation();
        if (project == null) {
            presentation.setEnabledAndVisible(false);
            return;
        }

        presentation.setVisible(true);

        CommitMessageGenerator generator = project.getService(CommitMessageGenerator.class);
        if (generator != null && generator.isRunning()) {
            presentation.setEnabled(true);
            presentation.setIcon(AllIcons.Actions.Suspend);
            presentation.setText("Cancel AI commit message generation");
            presentation.setDescription("Cancel the running commit message generation");
            return;
        }

        presentation.setIcon(AllIcons.Actions.IntentionBulb);
        presentation.setText(TEXT);
        presentation.setDescription(DESC);
        presentation.setEnabled(hasChanges(project));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }

        CommitMessageGenerator generator = project.getService(CommitMessageGenerator.class);
        if (generator == null) {
            return;
        }
        if (generator.isRunning()) {
            generator.cancel();
            return;
        }

        CommitMessageI target = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL);
        if (target == null) {
            return;
        }

        CommitAiSettings settings = CommitAiSettings.getInstance();
        if (!settings.isConfigured()) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, CommitAiSettingsConfigurable.class);
            return;
        }

        generator.generate(resolveCommitUi(e), target);
    }

    /**
     * Prefers the commit workflow UI data key; falls back to the workflow handler,
     * which needs the {@link AbstractCommitWorkflowHandler} cast to expose {@code getUi()}.
     */
    private static @Nullable CommitWorkflowUi resolveCommitUi(AnActionEvent e) {
        CommitWorkflowUi ui = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI);
        if (ui != null) {
            return ui;
        }
        CommitWorkflowHandler handler = e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER);
        if (handler instanceof AbstractCommitWorkflowHandler<?, ?> workflowHandler) {
            return workflowHandler.getUi();
        }
        return null;
    }

    private static boolean hasChanges(Project project) {
        try {
            ChangeListManager changeListManager = ChangeListManager.getInstance(project);
            return !changeListManager.getAllChanges().isEmpty()
                    || !changeListManager.getUnversionedFilesPaths().isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }
}
