package io.pallet.identity.verification;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.annotations.UnitTest;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

@UnitTest
class VerificationCodeGeneratorTest {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    @Test
    void generatesAnEightCharacterCode() {
        assertThat(VerificationCodeGenerator.generate()).hasSize(8);
    }

    @Test
    void everyCharacterComesFromTheDocumentedAlphabet() {
        IntStream.range(0, 10_000)
                .mapToObj(i -> VerificationCodeGenerator.generate())
                .forEach(code -> assertThat(code.chars()).allMatch(c -> ALPHABET.indexOf(c) >= 0));
    }

    @Test
    void neverContainsAVisuallyAmbiguousCharacter() {
        String generated = IntStream.range(0, 10_000)
                .mapToObj(i -> VerificationCodeGenerator.generate())
                .reduce("", String::concat);

        assertThat(generated)
                .doesNotContain("0")
                .doesNotContain("O")
                .doesNotContain("1")
                .doesNotContain("I");
    }

    @Test
    void showsNoObviouslyBiasedCharacterDistribution() {
        int samples = 10_000;
        long[] counts = new long[ALPHABET.length()];
        IntStream.range(0, samples)
                .mapToObj(i -> VerificationCodeGenerator.generate())
                .forEach(code -> code.chars().forEach(c -> counts[ALPHABET.indexOf(c)]++));

        double expected = samples * 8.0 / ALPHABET.length();
        for (long count : counts) {
            assertThat(count).isCloseTo((long) expected, org.assertj.core.data.Percentage.withPercentage(30));
        }
    }
}
