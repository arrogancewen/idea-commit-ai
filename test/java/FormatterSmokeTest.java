import com.commitai.idea.CommitAiSettings;
import com.commitai.idea.CommitMessageFormatter;
import com.commitai.idea.PromptTemplates;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Regression tests for message shape.
 *
 * <p>Fixture 1 is the real over-long output observed in the IDE (5 long bullets naming
 * methods, stored procedures and tables). Fixture 2 reproduces the two scenarios the
 * user reported: 1-2 files (a one-liner is accurate) and 55 files across several areas
 * (a one-liner is too little).
 */
public class FormatterSmokeTest {

    private static int failures = 0;

    private static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name + (ok ? "" : "   " + detail));
        if (!ok) {
            failures++;
        }
    }

    private static void eq(String name, String actual, String expected) {
        check(name, expected.equals(actual), "expected=[" + expected + "] actual=[" + actual + "]");
    }

    private static int countBullets(String text) {
        int n = 0;
        for (String line : text.split("\n")) {
            if (line.startsWith("- ")) {
                n++;
            }
        }
        return n;
    }

    private static String fmt(String raw, String level, int files) {
        return CommitMessageFormatter.format(
                raw, CommitMessageFormatter.resolveMaxBullets(level, files));
    }

    public static void main(String[] args) {
        // ---- the budget curve: small change -> one line, big change -> room to explain
        check("1 file   -> one line", CommitMessageFormatter.autoBulletBudget(1) == 0, "got " + CommitMessageFormatter.autoBulletBudget(1));
        check("2 files  -> one line", CommitMessageFormatter.autoBulletBudget(2) == 0, "got " + CommitMessageFormatter.autoBulletBudget(2));
        check("3 files  -> 2 bullets", CommitMessageFormatter.autoBulletBudget(3) == 2, "got " + CommitMessageFormatter.autoBulletBudget(3));
        check("8 files  -> 2 bullets", CommitMessageFormatter.autoBulletBudget(8) == 2, "got " + CommitMessageFormatter.autoBulletBudget(8));
        check("9 files  -> 4 bullets", CommitMessageFormatter.autoBulletBudget(9) == 4, "got " + CommitMessageFormatter.autoBulletBudget(9));
        check("25 files -> 4 bullets", CommitMessageFormatter.autoBulletBudget(25) == 4, "got " + CommitMessageFormatter.autoBulletBudget(25));
        check("26 files -> 6 bullets", CommitMessageFormatter.autoBulletBudget(26) == 6, "got " + CommitMessageFormatter.autoBulletBudget(26));
        check("55 files -> 6 bullets", CommitMessageFormatter.autoBulletBudget(55) == 6, "got " + CommitMessageFormatter.autoBulletBudget(55));
        check("61 files -> 8 bullets", CommitMessageFormatter.autoBulletBudget(61) == 8, "got " + CommitMessageFormatter.autoBulletBudget(61));

        check("level subject -> 0", CommitMessageFormatter.resolveMaxBullets("subject", 55) == 0, "");
        check("level detailed -> unlimited", CommitMessageFormatter.resolveMaxBullets("detailed", 55) < 0, "");
        check("level null -> auto", CommitMessageFormatter.resolveMaxBullets(null, 55) == 6, "");

        // ---- the real verbose output from the IDE
        String verbose =
                "fix(refund): \u786e\u4fdd\u5e93\u5b58\u56de\u6eda\u5b8c\u6210\u540e\u518d\u843d\u7ec8\u6001\n"
                + "\n"
                + "- notifyLocalSyncService\uff1a\u5728 SUCCESS \u4e14\u4e0a\u4e00\u72b6\u6001\u4e3a 3 \u65f6\u5148\u6267\u884c restoreRefundInventory\uff0c\u518d\u8fd4\u56de\u7ed3\u679c\n"
                + "- notifyLocalSyncService\uff1a\u5c06 updateRefundOrderStatus \u79fb\u5230\u6240\u6709\u5e93\u5b58\u526f\u4f5c\u7528\u63d0\u4ea4\u4e4b\u540e\u6267\u884c\n"
                + "- stateMutationService\uff1a\u5e93\u5b58\u56de\u6eda\u6761\u4ef6\u6539\u4e3a\u4ec5\u9000\u6b3e(refund_type=1)\u4e14\u975e\u865a\u62df\u9884\u7ea6\u4e14\u8ba2\u5355\u72b6\u6001\u4e3a 0 \u6216 9\n"
                + "- stateMutationService\uff1a\u7528\u5b58\u50a8\u8fc7\u7a0b us_apply_store_refund_inventory_effect \u66ff\u6362\u76f4\u63a5\u66f4\u65b0\u5546\u54c1\u4e0e\u5c5e\u6027\u5e93\u5b58\u8868\uff0c\u6539\u7528 order_product_id \u4f5c\u4e3a\u5e42\u7b49\u952e\n"
                + "- stateMutationService\uff1a\u79fb\u9664 SHOP_PRODUCT_ATTR_VALUE_TABLE \u76f8\u5173\u903b\u8f91\u4e0e\u5bfc\u5165\n";

        String subject = "fix(refund): \u786e\u4fdd\u5e93\u5b58\u56de\u6eda\u5b8c\u6210\u540e\u518d\u843d\u7ec8\u6001";

        // scenario A: 2 files selected -> one accurate line, the old bug
        eq("auto/2 files -> single line", fmt(verbose, "auto", 2), subject);

        // scenario B: 55 files selected -> summary plus bullets, the "too little" bug
        String big = fmt(verbose, "auto", 55);
        check("auto/55 files -> keeps bullets", big.startsWith(subject + "\n\n- "), "got=[" + big + "]");
        check("auto/55 files -> bullet count > 1", countBullets(big) > 1, "bullets=" + countBullets(big));
        check("auto/55 files -> respects cap", countBullets(big) <= 6, "bullets=" + countBullets(big));

        // explicit levels still behave
        eq("level subject -> single line", fmt(verbose, "subject", 55), subject);
        check("level subject-body -> max 3", countBullets(fmt(verbose, "subject-body", 55)) <= 3, "");
        check("level detailed -> keeps all 5", countBullets(fmt(verbose, "detailed", 55)) == 5, "");

        // ---- cleaning
        eq("strips fences and preamble",
                fmt("Here is a commit message:\n```\nfix: correct total\n```\n", "auto", 1),
                "fix: correct total");
        eq("removes trailing period", fmt("fix: correct total.", "auto", 1), "fix: correct total");
        eq("null -> empty", fmt(null, "auto", 1), "");
        eq("blank -> empty", fmt("   \n\n  ", "auto", 1), "");

        // ---- prompt carries the full scope even when diffs are truncated
        List<String> paths = Arrays.asList(
                "D:/code/shop/usingshop/app/common/services/product/InventoryEffectService.php",
                "D:/code/shop/usingshop/ui/mer/src/views/product/addProduct/index.vue",
                "D:/code/shop/platform/shop-erp/src/main/java/com/xunma/shop/erp/JstCallbackController.java");
        String userPrompt = PromptTemplates.userPrompt(
                new ArrayList<>(Arrays.asList("diff one")), paths, true, 6);
        check("prompt states the true file count", userPrompt.contains("All changed files (3)"), "");
        check("prompt lists every path", userPrompt.contains("JstCallbackController.java"), "");
        check("prompt strips the shared prefix", !userPrompt.contains("D:/code/shop/"), "");
        check("prompt states the bullet budget", userPrompt.contains("at most 6 bullets"), "");
        check("prompt flags truncation", userPrompt.contains("the file list above is complete"), "");

        CommitAiSettings.State zh = new CommitAiSettings.State();
        zh.language = "zh";
        String oneLine = PromptTemplates.systemPrompt(zh, 0);
        check("system prompt: one line only", oneLine.contains("EXACTLY ONE line"), "");
        String many = PromptTemplates.systemPrompt(zh, 6);
        check("system prompt: no per-file bullets", many.contains("NEVER write one bullet per file"), "");
        check("system prompt: business semantics", many.contains("BUSINESS or BEHAVIOURAL"), "");
        check("system prompt: bans code mechanics", many.contains("no file names, class names"), "");
        check("system prompt: states budget", many.contains("AT MOST 6 bullets"), "");

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURE(S)");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
