package com.commitai.idea;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Application-level settings for the commit message generator.
 *
 * <p>The API key is deliberately NOT part of the serialized state: it goes to
 * {@link PasswordSafe} (OS keychain / IDE encrypted store). This is the opposite
 * of storing a key in plain text inside a JSON file next to the IDE.
 */
@State(name = "CommitAiSettings", storages = @Storage("commit-ai.xml"))
public final class CommitAiSettings implements PersistentStateComponent<CommitAiSettings.State> {

    /** OpenAI-compatible {@code /chat/completions}. */
    public static final String API_OPENAI = "openai";
    /** Anthropic-compatible {@code /v1/messages}. */
    public static final String API_ANTHROPIC = "anthropic";

    public static final class State {
        /** {@link #API_OPENAI} or {@link #API_ANTHROPIC}. */
        public String apiType = API_OPENAI;
        /** Base URL, e.g. https://api.deepseek.com/v1 or https://api.deepseek.com/anthropic */
        public String baseUrl = "https://api.deepseek.com/v1";
        /** Model name, free text - any model the endpoint accepts. */
        public String model = "deepseek-chat";
        /** zh | en | auto */
        public String language = "auto";
        /**
         * How much text to produce: {@link CommitMessageFormatter#SUBJECT_ONLY} (one line,
         * the default), SUBJECT_BODY, or DETAILED. Enforced in code, not just prompted.
         */
        public String detailLevel = CommitMessageFormatter.AUTO;
        public boolean conventionalCommits = true;
        /** Extra instructions appended to the system prompt. */
        public String customInstructions = "";
        public int maxDiffChars = 60000;
        public int timeoutSeconds = 90;
        public int maxOutputTokens = 1024;
        public double temperature = 0.2;
        public boolean includeUnversionedFiles = true;
    }

    private State state = new State();

    public static CommitAiSettings getInstance() {
        return ApplicationManager.getApplication().getService(CommitAiSettings.class);
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    // ------------------------------------------------------------- API key

    private static CredentialAttributes credentialAttributes() {
        return new CredentialAttributes("CommitAi");
    }

    public @Nullable String getApiKey() {
        return PasswordSafe.getInstance().getPassword(credentialAttributes());
    }

    public void setApiKey(@Nullable String key) {
        PasswordSafe.getInstance().setPassword(credentialAttributes(),
                key == null || key.isBlank() ? null : key.trim());
    }

    public boolean isConfigured() {
        String key = getApiKey();
        return key != null && !key.isBlank()
                && state.baseUrl != null && !state.baseUrl.isBlank()
                && state.model != null && !state.model.isBlank();
    }

    // ------------------------------------------------------------- helpers

    /** Trims trailing slashes so URL joining is predictable. */
    public String normalizedBaseUrl() {
        String url = state.baseUrl == null ? "" : state.baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
