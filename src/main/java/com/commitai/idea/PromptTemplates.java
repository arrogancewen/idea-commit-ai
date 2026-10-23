package com.commitai.idea;

import java.util.ArrayList;
import java.util.List;

/** Builds the system/user prompt pair sent to the model. */
public final class PromptTemplates {

    /** Above this many files the prompt lists a sample and a count instead of every path. */
    private static final int MAX_LISTED_PATHS = 200;

    private PromptTemplates() {
    }

    /**
     * @param maxBullets resolved by {@link CommitMessageFormatter#resolveMaxBullets}:
     *                   0 = one line only, positive = bullet cap, negative = unlimited
     */
    public static String systemPrompt(CommitAiSettings.State s, int maxBullets) {
        StringBuilder sb = new StringBuilder();
        sb.append("You write Git commit messages from a unified diff.\n\n");

        sb.append("OUTPUT FORMAT - follow exactly:\n");
        sb.append("- Output the commit message text only. No preamble, no explanation, no markdown fences, no quotes.\n");

        if (maxBullets == 0) {
            sb.append("- Output EXACTLY ONE line. No body, no bullets, no trailing blank line.\n");
            sb.append("- That line is at most 72 characters, imperative mood, no trailing period.\n");
            sb.append("- The change is small, so one precise line is enough. Do not pad it.\n");
        } else if (maxBullets > 0) {
            sb.append("- Line 1: one summary line, at most 72 characters, imperative mood, no trailing period.\n");
            sb.append("- Then one blank line, then AT MOST ").append(maxBullets).append(" bullets.\n");
            sb.append("- Write ONE bullet per distinct concern or area, grouping related files together.\n");
            sb.append("- NEVER write one bullet per file, and never just echo file names.\n");
            sb.append("- Use fewer bullets if the change really only covers one or two concerns.\n");
            sb.append("- Each bullet is a single line, at most about 100 characters.\n");
        } else {
            sb.append("- Line 1: one summary line, at most 72 characters, imperative mood, no trailing period.\n");
            sb.append("- Then a blank line and bullets grouped by concern (not one per file).\n");
        }

        String lang = s.language == null ? "auto" : s.language;

        // The single most important instruction: describe the business/behavioural change.
        // Without this, models fall back to a code-level changelog (method names, tables, SQL).
        sb.append("\nWHAT TO DESCRIBE (most important):\n");
        sb.append("- Describe the BUSINESS or BEHAVIOURAL change: what the product now does differently, in domain terms.\n");
        sb.append("- Write for a product owner or a code reviewer, not for the compiler.\n");
        sb.append("- NEVER mention code mechanics: no file names, class names, method names, field names, ")
          .append("table names, stored-procedure names, SQL, ids, flags, or line numbers.\n");
        sb.append("- BAD:  \"stateMutationService: replace direct table update with stored procedure ")
          .append("us_apply_store_refund_inventory_effect\"\n");
        if ("zh".equals(lang)) {
            sb.append("- GOOD: \"库存回滚改为原子执行，避免并发下库存与订单状态不一致\"\n");
        } else {
            sb.append("- GOOD: \"make inventory rollback atomic so stock and order state cannot diverge under concurrency\"\n");
        }
        sb.append("- If the diff does not reveal the business intent, state the user-visible effect ")
          .append("instead of listing code changes.\n");
        sb.append("- Never invent intent that the diff does not support.\n");
        sb.append("- If some diffs were truncated or omitted, the file list still tells you the full scope; ")
          .append("describe the scope from it instead of ignoring it.\n");

        if (s.conventionalCommits) {
            sb.append("- Use Conventional Commits: `type(scope): summary` with type one of ")
              .append("feat, fix, refactor, perf, docs, test, build, ci, chore, style, revert.\n");
        }

        if ("zh".equals(lang)) {
            sb.append("\nWrite in Simplified Chinese; keep the type/scope keyword in English.\n");
        } else if ("en".equals(lang)) {
            sb.append("\nWrite in English.\n");
        } else {
            sb.append("\nWrite in the dominant language of the diff's comments and file names.\n");
        }

        if (s.customInstructions != null && !s.customInstructions.isBlank()) {
            sb.append("\nAdditional project-specific instructions:\n").append(s.customInstructions.trim()).append('\n');
        }

        return sb.toString();
    }

    /**
     * @param diffs        unified diffs for the files that fit in the budget
     * @param allFilePaths every changed path, so the model sees the real scope
     */
    public static String userPrompt(List<String> diffs,
                                    List<String> allFilePaths,
                                    boolean truncated,
                                    int maxBullets) {
        StringBuilder sb = new StringBuilder();
        sb.append("Write the commit message for these changes.\n\n");

        List<String> shown = shorten(allFilePaths);
        int total = shown.size();
        sb.append("All changed files (").append(total).append("):\n");
        int listed = Math.min(total, MAX_LISTED_PATHS);
        for (int i = 0; i < listed; i++) {
            sb.append("  ").append(shown.get(i)).append('\n');
        }
        if (total > listed) {
            sb.append("  ... and ").append(total - listed).append(" more\n");
        }

        sb.append("\nDiff content:\n");
        if (diffs.isEmpty()) {
            sb.append("(no diff text could be produced; rely on the file list above)\n");
        } else if (truncated || diffs.size() < total) {
            sb.append("(showing ").append(diffs.size()).append(" of ").append(total)
              .append(" files; large diffs are truncated - the file list above is complete)\n");
        }
        if (maxBullets > 0) {
            sb.append("(write at most ").append(maxBullets).append(" bullets, one per concern)\n");
        } else if (maxBullets == 0) {
            sb.append("(the answer must be a single line)\n");
        }
        sb.append("\n```diff\n");

        for (String diff : diffs) {
            sb.append(diff);
            if (!diff.endsWith("\n")) {
                sb.append('\n');
            }
        }

        sb.append("```\n");
        return sb.toString();
    }

    /** Strips the directory prefix shared by every path, to keep the prompt compact. */
    static List<String> shorten(List<String> paths) {
        if (paths.size() < 2) {
            return paths;
        }
        String prefix = paths.get(0);
        for (String path : paths) {
            int i = 0;
            while (i < prefix.length() && i < path.length() && prefix.charAt(i) == path.charAt(i)) {
                i++;
            }
            prefix = prefix.substring(0, i);
            if (prefix.isEmpty()) {
                break;
            }
        }
        int sep = Math.max(prefix.lastIndexOf('/'), prefix.lastIndexOf('\\'));
        if (sep <= 0) {
            return paths;
        }
        String base = prefix.substring(0, sep + 1);
        List<String> out = new ArrayList<>(paths.size());
        for (String path : paths) {
            out.add(path.startsWith(base) ? path.substring(base.length()) : path);
        }
        return out;
    }
}
