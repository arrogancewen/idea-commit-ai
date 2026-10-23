package com.commitai.idea;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;

/** Settings page: Tools &gt; AI Commit Message. */
public final class CommitAiSettingsConfigurable implements Configurable {

    private static final String[] API_ITEMS = {
            "OpenAI-compatible   (POST {base}/chat/completions)",
            "Anthropic-compatible   (POST {base}/v1/messages)"
    };
    private static final String[] LANG_ITEMS = {
            "auto (follow the diff)",
            "zh - Simplified Chinese",
            "en - English"
    };
    private static final String[] DETAIL_ITEMS = {
            "auto - scale with the size of the change (recommended)",
            "subject only - always one line",
            "subject + brief body (max 3 bullets)",
            "detailed - as long as the model wants"
    };

    private JPanel panel;
    private ComboBox<String> apiTypeCombo;
    private JBTextField baseUrlField;
    private JBTextField modelField;
    private JBPasswordField apiKeyField;
    private ComboBox<String> languageCombo;
    private ComboBox<String> detailCombo;
    private JBCheckBoxHolder conventional;
    private JBCheckBoxHolder unversioned;
    private JBTextField maxDiffField;
    private JBTextField timeoutField;
    private JBTextField maxTokensField;
    private JBTextField temperatureField;
    private JBTextArea instructionsArea;

    @Override
    public String getDisplayName() {
        return "AI Commit Message";
    }

    @Override
    public @Nullable String getHelpTopic() {
        return null;
    }

    @Override
    public JComponent createComponent() {
        apiTypeCombo = new ComboBox<>(API_ITEMS);
        baseUrlField = new JBTextField();
        modelField = new JBTextField();
        apiKeyField = new JBPasswordField();
        languageCombo = new ComboBox<>(LANG_ITEMS);
        detailCombo = new ComboBox<>(DETAIL_ITEMS);
        conventional = new JBCheckBoxHolder("Use Conventional Commits (type(scope): summary)");
        unversioned = new JBCheckBoxHolder("Include new / untracked files in the diff");
        maxDiffField = new JBTextField();
        timeoutField = new JBTextField();
        maxTokensField = new JBTextField();
        temperatureField = new JBTextField();

        instructionsArea = new JBTextArea(4, 60);
        instructionsArea.setLineWrap(true);
        instructionsArea.setWrapStyleWord(true);
        JBScrollPane instructionsScroll = new JBScrollPane(instructionsArea);
        instructionsScroll.setPreferredSize(new Dimension(600, 90));

        JButton testButton = new JButton("Test connection");
        testButton.addActionListener(e -> testConnection());
        JPanel testRow = new JPanel(new BorderLayout());
        testRow.add(testButton, BorderLayout.WEST);

        JBLabel hint = new JBLabel(
                "<html><body style='width:520px'>"
                + "Examples &mdash; DeepSeek: <code>https://api.deepseek.com/v1</code> + model <code>deepseek-chat</code> "
                + "(or <code>https://api.deepseek.com/anthropic</code> with the Anthropic protocol). "
                + "OpenAI: <code>https://api.openai.com/v1</code>. "
                + "Ollama (local): <code>http://localhost:11434/v1</code>. "
                + "Any OpenAI-compatible gateway works."
                + "</body></html>");

        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("API protocol:", apiTypeCombo)
                .addLabeledComponent("Base URL:", baseUrlField)
                .addLabeledComponent("Model:", modelField)
                .addLabeledComponent("API key:", apiKeyField)
                .addLabeledComponent("Commit message language:", languageCombo)
                .addLabeledComponent("Detail level:", detailCombo)
                .addComponent(conventional.component())
                .addComponent(unversioned.component())
                .addLabeledComponent("Max diff characters:", maxDiffField)
                .addLabeledComponent("Request timeout (seconds):", timeoutField)
                .addLabeledComponent("Max output tokens:", maxTokensField)
                .addLabeledComponent("Temperature:", temperatureField)
                .addLabeledComponent("Extra instructions:", instructionsScroll)
                .addComponent(testRow)
                .addComponent(hint)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        panel.setBorder(JBUI.Borders.empty(10));

        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        CommitAiSettings settings = CommitAiSettings.getInstance();
        CommitAiSettings.State s = settings.getState();

        if (!selectedApiType().equals(s.apiType)) {
            return true;
        }
        if (!selectedLanguage().equals(s.language)) {
            return true;
        }
        if (!selectedDetail().equals(s.detailLevel)) {
            return true;
        }
        if (!text(baseUrlField).equals(nullSafe(s.baseUrl))) {
            return true;
        }
        if (!text(modelField).equals(nullSafe(s.model))) {
            return true;
        }
        if (conventional.isSelected() != s.conventionalCommits) {
            return true;
        }
        if (unversioned.isSelected() != s.includeUnversionedFiles) {
            return true;
        }
        if (parseInt(text(maxDiffField), s.maxDiffChars) != s.maxDiffChars) {
            return true;
        }
        if (parseInt(text(timeoutField), s.timeoutSeconds) != s.timeoutSeconds) {
            return true;
        }
        if (parseInt(text(maxTokensField), s.maxOutputTokens) != s.maxOutputTokens) {
            return true;
        }
        if (parseDouble(text(temperatureField), s.temperature) != s.temperature) {
            return true;
        }
        if (!text(instructionsArea).equals(nullSafe(s.customInstructions))) {
            return true;
        }
        return !new String(apiKeyField.getPassword()).equals(nullSafe(settings.getApiKey()));
    }

    @Override
    public void apply() {
        CommitAiSettings settings = CommitAiSettings.getInstance();
        CommitAiSettings.State s = settings.getState();

        s.apiType = selectedApiType();
        s.language = selectedLanguage();
        s.detailLevel = selectedDetail();
        s.baseUrl = text(baseUrlField);
        s.model = text(modelField);
        s.conventionalCommits = conventional.isSelected();
        s.includeUnversionedFiles = unversioned.isSelected();
        s.maxDiffChars = parseInt(text(maxDiffField), 60000);
        s.timeoutSeconds = parseInt(text(timeoutField), 90);
        s.maxOutputTokens = parseInt(text(maxTokensField), 1024);
        s.temperature = parseDouble(text(temperatureField), 0.2);
        s.customInstructions = text(instructionsArea);

        settings.setApiKey(new String(apiKeyField.getPassword()));
    }

    @Override
    public void reset() {
        CommitAiSettings settings = CommitAiSettings.getInstance();
        CommitAiSettings.State s = settings.getState();

        apiTypeCombo.setSelectedIndex(CommitAiSettings.API_ANTHROPIC.equals(s.apiType) ? 1 : 0);
        baseUrlField.setText(nullSafe(s.baseUrl));
        modelField.setText(nullSafe(s.model));
        apiKeyField.setText(nullSafe(settings.getApiKey()));
        languageCombo.setSelectedIndex(languageIndex(s.language));
        detailCombo.setSelectedIndex(detailIndex(s.detailLevel));
        conventional.setSelected(s.conventionalCommits);
        unversioned.setSelected(s.includeUnversionedFiles);
        maxDiffField.setText(String.valueOf(s.maxDiffChars));
        timeoutField.setText(String.valueOf(s.timeoutSeconds));
        maxTokensField.setText(String.valueOf(s.maxOutputTokens));
        temperatureField.setText(String.valueOf(s.temperature));
        instructionsArea.setText(nullSafe(s.customInstructions));
    }

    // ------------------------------------------------------------ test button

    private void testConnection() {
        final CommitAiSettings.State probe = new CommitAiSettings.State();
        probe.apiType = selectedApiType();
        probe.baseUrl = text(baseUrlField);
        probe.model = text(modelField);
        probe.timeoutSeconds = parseInt(text(timeoutField), 90);
        probe.maxOutputTokens = Math.min(64, parseInt(text(maxTokensField), 1024));
        probe.temperature = 0;

        final String key = new String(apiKeyField.getPassword()).trim();

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String failure = null;
            String answer = null;
            try {
                LlmClient client = new LlmClient(probe);
                answer = client.complete(key, "You are a connectivity probe.", "Reply with exactly: OK");
            } catch (Exception ex) {
                failure = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            }
            final String fFailure = failure;
            final String fAnswer = answer;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (fFailure != null) {
                    Messages.showErrorDialog("Connection failed.\n\n" + fFailure, "AI Commit Message");
                } else {
                    Messages.showInfoMessage("Connection OK.\n\nModel replied: " + fAnswer, "AI Commit Message");
                }
            });
        });
    }

    // ----------------------------------------------------------------- helpers

    private String selectedApiType() {
        return apiTypeCombo.getSelectedIndex() == 1
                ? CommitAiSettings.API_ANTHROPIC
                : CommitAiSettings.API_OPENAI;
    }

    private String selectedLanguage() {
        switch (languageCombo.getSelectedIndex()) {
            case 1:
                return "zh";
            case 2:
                return "en";
            default:
                return "auto";
        }
    }

    private static int languageIndex(String language) {
        if ("zh".equals(language)) {
            return 1;
        }
        if ("en".equals(language)) {
            return 2;
        }
        return 0;
    }

    private String selectedDetail() {
        switch (detailCombo.getSelectedIndex()) {
            case 1:
                return CommitMessageFormatter.SUBJECT_ONLY;
            case 2:
                return CommitMessageFormatter.SUBJECT_BODY;
            case 3:
                return CommitMessageFormatter.DETAILED;
            default:
                return CommitMessageFormatter.AUTO;
        }
    }

    private static int detailIndex(String detail) {
        if (CommitMessageFormatter.SUBJECT_ONLY.equals(detail)) {
            return 1;
        }
        if (CommitMessageFormatter.SUBJECT_BODY.equals(detail)) {
            return 2;
        }
        if (CommitMessageFormatter.DETAILED.equals(detail)) {
            return 3;
        }
        return 0;
    }

    private static String text(javax.swing.text.JTextComponent component) {
        String value = component.getText();
        return value == null ? "" : value.trim();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Tiny wrapper so the checkbox type stays in one place. */
    private static final class JBCheckBoxHolder {
        private final com.intellij.ui.components.JBCheckBox box;

        JBCheckBoxHolder(String text) {
            this.box = new com.intellij.ui.components.JBCheckBox(text);
        }

        JComponent component() {
            return box;
        }

        boolean isSelected() {
            return box.isSelected();
        }

        void setSelected(boolean value) {
            box.setSelected(value);
        }
    }
}
