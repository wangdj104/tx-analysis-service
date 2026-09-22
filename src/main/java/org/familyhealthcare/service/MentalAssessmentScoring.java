package org.familyhealthcare.service;

import com.alibaba.fastjson2.JSON;
import java.math.BigDecimal;
import java.util.*;

/** Validates complete screening responses; results are screening categories, not diagnoses.
 * WHO-5: https://www.who.int/publications/m/item/WHO-UCN-MSD-MHE-2024.01
 * PHQ-9 / GAD-7: https://www.hiv.uw.edu/page/mental-health-screening/phq-9
 */
public final class MentalAssessmentScoring {
    private MentalAssessmentScoring() {}

    public static String scale(String input) {
        String scale = input == null ? "" : input.trim().toUpperCase(Locale.ROOT).replace("-", "");
        if (!Arrays.asList("PHQ9", "GAD7", "WHO5").contains(scale)) throw new IllegalArgumentException("Unsupported assessment scale.");
        return scale;
    }

    public static Map<String,Object> evaluate(String input, Object raw) {
        String scale = scale(input);
        if (raw instanceof String) {
            try { raw = JSON.parseArray((String)raw); } catch (RuntimeException invalid) { throw new IllegalArgumentException("Assessment answers must be an array of item scores."); }
        }
        int expected = "PHQ9".equals(scale) ? 9 : "GAD7".equals(scale) ? 7 : 5;
        int maximum = "WHO5".equals(scale) ? 5 : 3;
        if (!(raw instanceof Collection) || ((Collection<?>)raw).size() != expected) throw new IllegalArgumentException(scale + " requires " + expected + " item scores.");
        List<Integer> answers = new ArrayList<>();
        int total = 0;
        for (Object answer : (Collection<?>)raw) {
            Object value = answer instanceof Map ? ((Map<?,?>)answer).get("score") : answer;
            int score;
            try { if (value == null) throw new NumberFormatException(); score = new BigDecimal(value.toString()).intValueExact(); }
            catch (ArithmeticException | NumberFormatException invalid) { throw new IllegalArgumentException("Assessment item scores must be whole numbers."); }
            if (score < 0 || score > maximum) throw new IllegalArgumentException(scale + " item scores must be between 0 and " + maximum + ".");
            answers.add(score); total += score;
        }
        String severity;
        boolean review;
        if ("WHO5".equals(scale)) {
            review = total < 13;
            severity = review ? "REVIEW_REQUIRED" : "MINIMAL";
        } else {
            severity = total < 5 ? "MINIMAL" : total < 10 ? "MILD" : total < 15 ? "MODERATE"
                    : "PHQ9".equals(scale) && total < 20 ? "MODERATELY_SEVERE" : "SEVERE";
            review = total >= 10 || "PHQ9".equals(scale) && answers.get(8) > 0;
        }
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("scale", scale); out.put("answers", answers); out.put("score", total); out.put("severity", severity); out.put("requiresReview", review);
        return out;
    }
}
