package com.baafoo.core.util;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * Tests for the Faker increment features (R-S2 AC-11 increment):
 * randomElement, regexify, and rule-level seed support.
 */
public class FakerIncrementTest {

    // --- randomElement tests (AC-04) ---

    @Test
    public void testRandomElementWithBrackets() {
        String result = FakerProvider.resolve("randomElement [a,b,c]");
        assertNotNull(result);
        assertTrue("Result should be one of a/b/c: " + result,
                result.equals("a") || result.equals("b") || result.equals("c"));
    }

    @Test
    public void testRandomElementWithoutBrackets() {
        String result = FakerProvider.resolve("randomElement apple,banana,cherry");
        assertNotNull(result);
        assertTrue("Result should be one of the fruits: " + result,
                result.equals("apple") || result.equals("banana") || result.equals("cherry"));
    }

    @Test
    public void testRandomElementSingleElement() {
        String result = FakerProvider.resolve("randomElement [only]");
        assertEquals("only", result);
    }

    @Test
    public void testRandomElementWithSpaces() {
        // Elements should be trimmed so "[a, b, c]" works as expected.
        String result = FakerProvider.resolve("randomElement [a, b, c]");
        assertNotNull(result);
        assertTrue("Trimmed result should be one of a/b/c: " + result,
                result.equals("a") || result.equals("b") || result.equals("c"));
    }

    @Test
    public void testRandomElementEmpty() {
        String result = FakerProvider.resolve("randomElement ");
        assertEquals("", result);
    }

    @Test
    public void testRandomElementNumeric() {
        String result = FakerProvider.resolve("randomElement [1,2,3,4,5]");
        assertNotNull(result);
        int n = Integer.parseInt(result);
        assertTrue("Numeric result should be 1-5: " + n, n >= 1 && n <= 5);
    }

    @Test
    public void testRandomElementDistribution() {
        // Run many times and verify all elements appear (probabilistic).
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < 200; i++) {
            seen.add(FakerProvider.resolve("randomElement [x,y,z]"));
        }
        assertTrue("All three elements should appear in 200 runs: " + seen,
                seen.contains("x") && seen.contains("y") && seen.contains("z"));
    }

    // --- regexify tests (AC-05) ---

    @Test
    public void testRegexifySimpleClass() {
        String result = FakerProvider.resolve("regexify '[A-Z]{3}'");
        assertNotNull(result);
        assertEquals("Should be 3 uppercase letters: " + result, 3, result.length());
        assertTrue("Should match [A-Z]{3}: " + result, result.matches("[A-Z]{3}"));
    }

    @Test
    public void testRegexifyDigits() {
        String result = FakerProvider.resolve("regexify '[0-9]{4}'");
        assertNotNull(result);
        assertEquals("Should be 4 digits: " + result, 4, result.length());
        assertTrue("Should match [0-9]{4}: " + result, result.matches("[0-9]{4}"));
    }

    @Test
    public void testRegexifyMixedPattern() {
        // Like a license plate: ABC-1234
        String result = FakerProvider.resolve("regexify '[A-Z]{3}-[0-9]{4}'");
        assertNotNull(result);
        assertTrue("Should match [A-Z]{3}-[0-9]{4}: " + result,
                result.matches("[A-Z]{3}-[0-9]{4}"));
    }

    @Test
    public void testRegexifyAlternation() {
        String result = FakerProvider.resolve("regexify 'foo|bar|baz'");
        assertNotNull(result);
        assertTrue("Should be one of foo/bar/baz: " + result,
                result.equals("foo") || result.equals("bar") || result.equals("baz"));
    }

    @Test
    public void testRegexifyWithDoubleQuotes() {
        String result = FakerProvider.resolve("regexify \"[0-9]{2}\"");
        assertNotNull(result);
        assertEquals("Should be 2 digits: " + result, 2, result.length());
        assertTrue("Should match [0-9]{2}: " + result, result.matches("[0-9]{2}"));
    }

    @Test
    public void testRegexifyWithoutQuotes() {
        String result = FakerProvider.resolve("regexify [A-Z]{2}");
        assertNotNull(result);
        assertEquals("Should be 2 uppercase letters: " + result, 2, result.length());
        assertTrue("Should match [A-Z]{2}: " + result, result.matches("[A-Z]{2}"));
    }

    @Test
    public void testRegexifyQuantifierRange() {
        // {2,4} — should produce 2-4 chars
        String result = FakerProvider.resolve("regexify '[a-z]{2,4}'");
        assertNotNull(result);
        assertTrue("Length should be 2-4: " + result.length(),
                result.length() >= 2 && result.length() <= 4);
        assertTrue("Should match [a-z]{2,4}: " + result, result.matches("[a-z]{2,4}"));
    }

    @Test
    public void testRegexifyQuestionMark() {
        // a? — 0 or 1 'a'
        String result = FakerProvider.resolve("regexify 'a?b'");
        assertNotNull(result);
        assertTrue("Should be 'ab' or 'b': " + result, result.equals("ab") || result.equals("b"));
    }

    @Test
    public void testRegexifyPlus() {
        // a+b — 1 or more 'a' followed by 'b'
        String result = FakerProvider.resolve("regexify 'a+b'");
        assertNotNull(result);
        assertTrue("Should match a+b: " + result, result.matches("a+b"));
    }

    @Test
    public void testRegexifyDigitEscape() {
        // \d{3} — 3 digits
        String result = FakerProvider.resolve("regexify '\\d{3}'");
        assertNotNull(result);
        assertEquals("Should be 3 digits: " + result, 3, result.length());
        assertTrue("Should match \\d{3}: " + result, result.matches("\\d{3}"));
    }

    @Test
    public void testRegexifyEmpty() {
        String result = FakerProvider.resolve("regexify ");
        assertEquals("", result);
    }

    // --- Seed support tests (AC-06) ---

    @Test
    public void testSeedProducesDeterministicSequence() {
        // Same seed should produce the same sequence of values.
        FakerProvider.setSeed(42L);
        String first1 = FakerProvider.resolve("int.1.1000");
        String second1 = FakerProvider.resolve("int.1.1000");
        String third1 = FakerProvider.resolve("int.1.1000");
        FakerProvider.setSeed(null);

        FakerProvider.setSeed(42L);
        String first2 = FakerProvider.resolve("int.1.1000");
        String second2 = FakerProvider.resolve("int.1.1000");
        String third2 = FakerProvider.resolve("int.1.1000");
        FakerProvider.setSeed(null);

        assertEquals("First value with same seed should match", first1, first2);
        assertEquals("Second value with same seed should match", second1, second2);
        assertEquals("Third value with same seed should match", third1, third2);
    }

    @Test
    public void testDifferentSeedsProduceDifferentValues() {
        // Different seeds should (very likely) produce different values.
        FakerProvider.setSeed(1L);
        String v1 = FakerProvider.resolve("int.1.1000000");
        FakerProvider.setSeed(null);

        FakerProvider.setSeed(2L);
        String v2 = FakerProvider.resolve("int.1.1000000");
        FakerProvider.setSeed(null);

        assertNotEquals("Different seeds should produce different values", v1, v2);
    }

    @Test
    public void testNoSeedProducesNonDeterministicValues() {
        // Without a seed, values should vary (probabilistic — at least one
        // of 5 calls should differ from the first).
        String first = FakerProvider.resolve("int.1.1000000");
        boolean anyDifferent = false;
        for (int i = 0; i < 5; i++) {
            String v = FakerProvider.resolve("int.1.1000000");
            if (!v.equals(first)) {
                anyDifferent = true;
                break;
            }
        }
        assertTrue("Without seed, at least one value should differ from the first", anyDifferent);
    }

    @Test
    public void testSeedClearRestoresNonDeterminism() {
        // After clearing the seed, the sequence should not match the seeded one.
        FakerProvider.setSeed(123L);
        String seededVal = FakerProvider.resolve("int.1.1000000");
        FakerProvider.setSeed(null);

        // Without seed, very unlikely to match the seeded value.
        boolean anyDifferent = false;
        for (int i = 0; i < 10; i++) {
            String v = FakerProvider.resolve("int.1.1000000");
            if (!v.equals(seededVal)) {
                anyDifferent = true;
                break;
            }
        }
        assertTrue("After clearing seed, values should differ from seeded value", anyDifferent);
    }

    @Test
    public void testSeedWithRandomElement() {
        // Seeded randomElement should produce a deterministic sequence.
        FakerProvider.setSeed(99L);
        String a1 = FakerProvider.resolve("randomElement [a,b,c,d,e]");
        String b1 = FakerProvider.resolve("randomElement [a,b,c,d,e]");
        FakerProvider.setSeed(null);

        FakerProvider.setSeed(99L);
        String a2 = FakerProvider.resolve("randomElement [a,b,c,d,e]");
        String b2 = FakerProvider.resolve("randomElement [a,b,c,d,e]");
        FakerProvider.setSeed(null);

        assertEquals("First randomElement with same seed should match", a1, a2);
        assertEquals("Second randomElement with same seed should match", b1, b2);
    }

    @Test
    public void testSeedWithRegexify() {
        // Seeded regexify should produce a deterministic string.
        FakerProvider.setSeed(7L);
        String v1 = FakerProvider.resolve("regexify '[A-Z]{8}'");
        FakerProvider.setSeed(null);

        FakerProvider.setSeed(7L);
        String v2 = FakerProvider.resolve("regexify '[A-Z]{8}'");
        FakerProvider.setSeed(null);

        assertEquals("Seeded regexify should produce same value", v1, v2);
        assertTrue("Should match [A-Z]{8}: " + v1, v1.matches("[A-Z]{8}"));
    }

    @Test
    public void testSeedDoesNotAffectOtherThreads() throws Exception {
        // Seed is thread-local, so setting it on one thread should not
        // affect another thread's faker output.
        FakerProvider.setSeed(555L);
        String mainThreadSeeded = FakerProvider.resolve("int.1.1000000");

        final String[] otherThreadValue = new String[1];
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                // Other thread has no seed set; should use default random.
                otherThreadValue[0] = FakerProvider.resolve("int.1.1000000");
            }
        });
        t.start();
        t.join();

        FakerProvider.setSeed(null);

        // The other thread's value should not match the seeded value (probabilistically).
        // Note: there's a tiny chance they match by coincidence, but with range 1-1000000
        // the probability is ~1/1000000, which is negligible.
        assertNotEquals("Other thread should not be affected by seed on main thread",
                mainThreadSeeded, otherThreadValue[0]);
    }

    // --- TemplateEngine integration with seed ---

    @Test
    public void testTemplateRenderWithSeed() {
        String template = "{\"id\": \"{{faker.int.1.1000}}\", \"name\": \"{{faker.name}}\"}";

        // Render with seed 42 — should produce deterministic output.
        String result1 = TemplateEngine.render(template, null, 42L);
        String result2 = TemplateEngine.render(template, null, 42L);

        assertNotNull(result1);
        assertEquals("Same seed should produce same template output", result1, result2);
        assertFalse("Result should not contain unresolved templates: " + result1,
                result1.contains("{{"));
    }

    @Test
    public void testTemplateRenderWithDifferentSeeds() {
        String template = "{\"id\": \"{{faker.int.1.1000000}}\"}";

        String result1 = TemplateEngine.render(template, null, 1L);
        String result2 = TemplateEngine.render(template, null, 2L);

        assertNotEquals("Different seeds should produce different output", result1, result2);
    }

    @Test
    public void testTemplateRenderClearsSeedAfterUse() {
        // After a seeded render, subsequent unseeded renders should use
        // the default (non-deterministic) random source.
        String template = "{{faker.int.1.1000000}}";

        TemplateEngine.render(template, null, 42L);

        // Now render without seed — should not match the seeded value.
        String seededVal = TemplateEngine.render(template, null, 42L);
        boolean anyDifferent = false;
        for (int i = 0; i < 10; i++) {
            String v = TemplateEngine.render(template, null, null);
            if (!v.equals(seededVal)) {
                anyDifferent = true;
                break;
            }
        }
        assertTrue("After seeded render, unseeded render should differ", anyDifferent);
    }

    @Test
    public void testTemplateRenderWithRandomElementAndSeed() {
        String template = "{\"choice\": \"{{faker.randomElement [red,green,blue]}}\"}";

        String r1 = TemplateEngine.render(template, null, 100L);
        String r2 = TemplateEngine.render(template, null, 100L);

        assertEquals("Seeded randomElement in template should be deterministic", r1, r2);
        assertTrue("Should contain a valid color: " + r1,
                r1.contains("red") || r1.contains("green") || r1.contains("blue"));
    }

    @Test
    public void testTemplateRenderWithRegexifyAndSeed() {
        String template = "{\"code\": \"{{faker.regexify '[A-Z]{4}'}}\"}";

        String r1 = TemplateEngine.render(template, null, 200L);
        String r2 = TemplateEngine.render(template, null, 200L);

        assertEquals("Seeded regexify in template should be deterministic", r1, r2);
        assertTrue("Should match [A-Z]{4}: " + r1, r1.matches("\\{\"code\": \"[A-Z]{4}\"}"));
    }
}
