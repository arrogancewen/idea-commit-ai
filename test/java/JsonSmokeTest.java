import com.commitai.idea.Json;
import com.commitai.idea.LlmClient;

import java.lang.reflect.Method;

/**
 * Offline smoke test for the riskiest hand-written parts:
 * the JSON reader/writer and the two response-shape extractors.
 * Lives outside src/ so it is never packaged into the plugin.
 */
public class JsonSmokeTest {

    private static int failures = 0;

    private static void check(String name, Object actual, Object expected) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        System.out.println((ok ? "PASS  " : "FAIL  ") + name
                + (ok ? "" : "   expected=[" + expected + "] actual=[" + actual + "]"));
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        String openai = "{\"id\":\"x\",\"choices\":[{\"index\":0,\"message\":"
                + "{\"role\":\"assistant\",\"content\":\"feat(coupon): add \\\"gift\\\" limit\\n\\n- body line\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":{\"total_tokens\":10}}";
        String anthropic = "{\"id\":\"m\",\"content\":[{\"type\":\"text\",\"text\":\"fix: handle null coupon\"}],"
                + "\"stop_reason\":\"end_turn\"}";

        Method extractOpenAi = LlmClient.class.getDeclaredMethod("extractOpenAi", String.class);
        extractOpenAi.setAccessible(true);
        Method extractAnthropic = LlmClient.class.getDeclaredMethod("extractAnthropic", String.class);
        extractAnthropic.setAccessible(true);

        check("openai  choices[0].message.content", extractOpenAi.invoke(null, openai),
                "feat(coupon): add \"gift\" limit\n\n- body line");
        check("anthropic content[0].text", extractAnthropic.invoke(null, anthropic),
                "fix: handle null coupon");
        check("quote() escaping", Json.quote("a\"b\\c\nd"), "\"a\\\"b\\\\c\\nd\"");
        check("nested navigation",
                Json.asString(Json.get(Json.at(Json.get(Json.parse(openai), "choices"), 0), "finish_reason")),
                "stop");
        check("\\u escape decoding",
                Json.asString(Json.get(Json.parse("{\"c\":\"\\u4e2d\\u6587\"}"), "c")),
                "\u4e2d\u6587");
        check("null literal", Json.get(Json.parse("{\"a\":null}"), "a"), null);
        check("array of numbers", Json.at(Json.parse("[1,2,3]"), 1), 2.0);

        Method describe = LlmClient.class.getDeclaredMethod("describeHttpError", int.class, String.class);
        describe.setAccessible(true);
        System.out.println("INFO  describeHttpError(402) = "
                + describe.invoke(null, 402, "{\"error\":{\"message\":\"Insufficient Balance\"}}"));
        System.out.println("INFO  describeHttpError(404) = " + describe.invoke(null, 404, "not json at all"));

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURE(S)");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
