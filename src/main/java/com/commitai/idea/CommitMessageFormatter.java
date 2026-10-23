package com.commitai.idea;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Shapes whatever the model returned into a proper commit message.
 *
 * <p>Prompting alone is not a guarantee, so the requested shape is enforced here:
 * a "one line" request really yields one line even if the model wrote an essay.
 *
 * <p>The length is driven by a <em>bullet budget</em> derived from how many files are
 * being committed (see {@link #autoBulletBudget}). A one-file change gets a single line;
 * a 55-file change gets a summary plus a handful of bullets grouped by concern. A fixed
 * level cannot do both, which is exactly why the default is {@link #AUTO}.
 */
public final class CommitMessageFormatter {

    /** Scale the message length with the size of the change (default). */
    public static final String AUTO = "auto";
    /** One line, nothing else. */
    public static final String SUBJECT_ONLY = "subject";
    /** Subject plus at most 3 bullets. */
    public static final String SUBJECT_BODY = "subject-body";
    /** Whatever the model produced, only lightly cleaned. */
    public static final String DETAILED = "detailed";

    /** Passed to {@link #format} to mean "no bullet limit". */
    public static final int UNLIMITED = -1;

    /** Git convention: keep the subject line short. */
    private static final int SUBJECT_LIMIT = 72;
    private static final int BULLET_CHAR_LIMIT = 120;
    private static final int SUBJECT_BODY_BULLETS = 3;

    private CommitMessageFormatter() {
    }

    /**
     * How many bullets the change deserves.
     *
     * <p>Calibrated so that 1-2 files stay a one-liner (the common case where a single
     * line is accurate), while a large multi-area commit gets room to describe each area.
     */
    public static int autoBulletBudget(int fileCount) {
        if (fileCount <= 2) {
            return 0;
        }
        if (fileCount <= 8) {
            return 2;
        }
        if (fileCount <= 25) {
            return 4;
        }
        if (fileCount <= 60) {
            return 6;
        }
        return 8;
    }

    /** Turns a configured level into the concrete bullet budget for this change. */
    public static int resolveMaxBullets(@Nullable String detailLevel, int fileCount) {
        String level = detailLevel == null ? AUTO : detailLevel;
        switch (level) {
            case DETAILED:
                return UNLIMITED;
            case SUBJECT_ONLY:
                return 0;
            case SUBJECT_BODY:
                return SUBJECT_BODY_BULLETS;
            case AUTO:
            default:
                return autoBulletBudget(fileCount);
        }
    }

    /**
     * @param maxBullets {@link #UNLIMITED}, 0 for a single line, or a positive bullet cap
     */
    public static @NotNull String format(@Nullable String raw, int maxBullets) {
        String cleaned = clean(raw);
        if (cleaned.isEmpty()) {
            return "";
        }
        if (maxBullets < 0) {
            return cleaned;
        }
        if (maxBullets == 0) {
            return subjectOf(cleaned);
        }
        return subjectWithBody(cleaned, maxBullets);
    }

    /** Strips code fences, leading chatter and trailing whitespace. */
    static @NotNull String clean(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (String line : raw.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String trimmed = line.strip();
            // Drop markdown fences and any fence language tag.
            if (trimmed.startsWith("```")) {
                continue;
            }
            lines.add(line.stripTrailing());
        }

        int start = 0;
        while (start < lines.size() && lines.get(start).isBlank()) {
            start++;
        }

        // Drop up to 3 leading "chatter" lines such as "Here is a commit message:".
        int dropped = 0;
        while (start < lines.size() && dropped < 3) {
            String candidate = lines.get(start).strip();
            if (isChatter(candidate) && hasContentAfter(lines, start + 1)) {
                start++;
                dropped++;
                while (start < lines.size() && lines.get(start).isBlank()) {
                    start++;
                }
            } else {
                break;
            }
        }

        StringBuilder sb = new StringBuilder();
        boolean lastBlank = true;
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                if (!lastBlank) {
                    sb.append('\n');
                    lastBlank = true;
                }
            } else {
                sb.append(line).append('\n');
                lastBlank = false;
            }
        }
        return sb.toString().strip();
    }

    private static boolean isChatter(String line) {
        if (line.isEmpty()) {
            return false;
        }
        String lower = line.toLowerCase();
        if (lower.startsWith("here") || lower.startsWith("sure") || lower.startsWith("certainly")
                || lower.startsWith("based on") || lower.startsWith("this commit message")
                || lower.startsWith("commit message:") || lower.startsWith("the following")) {
            return true;
        }
        return line.length() <= 60 && line.endsWith(":") && !line.startsWith("-");
    }

    private static boolean hasContentAfter(List<String> lines, int from) {
        for (int i = from; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) {
                return true;
            }
        }
        return false;
    }

    /** First non-empty line, tidied and length-capped. */
    static @NotNull String subjectOf(@NotNull String cleaned) {
        for (String line : cleaned.split("\n")) {
            String subject = tidySubject(line);
            if (!subject.isEmpty()) {
                return subject;
            }
        }
        return "";
    }

    private static @NotNull String subjectWithBody(@NotNull String cleaned, int maxBullets) {
        String[] lines = cleaned.split("\n");
        String subject = "";
        int subjectIndex = -1;
        for (int i = 0; i < lines.length; i++) {
            String candidate = tidySubject(lines[i]);
            if (!candidate.isEmpty()) {
                subject = candidate;
                subjectIndex = i;
                break;
            }
        }
        if (subject.isEmpty() || subjectIndex < 0) {
            return "";
        }

        List<String> bullets = new ArrayList<>();
        for (int i = subjectIndex + 1; i < lines.length && bullets.size() < maxBullets; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            String bullet = line.replaceFirst("^[-*\u2022+]\\s*", "").strip();
            if (bullet.isEmpty()) {
                continue;
            }
            if (bullet.length() > BULLET_CHAR_LIMIT) {
                bullet = cut(bullet, BULLET_CHAR_LIMIT).stripTrailing();
            }
            bullets.add("- " + bullet);
        }

        if (bullets.isEmpty()) {
            return subject;
        }
        return subject + "\n\n" + String.join("\n", bullets);
    }

    /** Removes wrapping quotes/backticks, a trailing period, and caps the length. */
    private static @NotNull String tidySubject(@NotNull String line) {
        String subject = line.strip();
        if (subject.isEmpty()) {
            return "";
        }
        subject = subject.replaceFirst("^[`\"'\\s]+", "").replaceFirst("[`\"'\\s]+$", "");
        subject = subject.replaceFirst("^(commit message|message)\\s*[:\\-]\\s*", "");
        if (subject.endsWith(".")) {
            subject = subject.substring(0, subject.length() - 1).stripTrailing();
        }
        if (subject.length() > SUBJECT_LIMIT) {
            int cut = subject.lastIndexOf(' ', SUBJECT_LIMIT);
            subject = cut > 40 ? subject.substring(0, cut) : cut(subject, SUBJECT_LIMIT);
            subject = subject.stripTrailing();
        }
        return subject;
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
