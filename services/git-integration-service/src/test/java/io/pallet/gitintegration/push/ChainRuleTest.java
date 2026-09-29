package io.pallet.gitintegration.push;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.annotations.UnitTest;
import io.pallet.gitintegration.scm.ScmProvider.CompareStatus;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@UnitTest
class ChainRuleTest {

    private static final String HEAD = "a".repeat(40);
    private static final String BEFORE = "b".repeat(40);
    private static final String AFTER = "c".repeat(40);

    @Test
    void theFirstPushSinceLinkingIsAccepted() {
        assertThat(ChainRule.decide(Optional.empty(), BEFORE, AFTER, false, Optional.empty()))
                .isEqualTo(ChainDecision.ACCEPT);
    }

    @ParameterizedTest
    @EnumSource(CompareStatus.class)
    void aPushOfTheHeadIsADuplicateWhateverElseIsKnown(CompareStatus status) {
        assertThat(ChainRule.decide(Optional.of(AFTER), BEFORE, AFTER, true, Optional.of(status)))
                .isEqualTo(ChainDecision.DUPLICATE);
        assertThat(ChainRule.decide(Optional.of(AFTER), AFTER, AFTER, false, Optional.empty()))
                .isEqualTo(ChainDecision.DUPLICATE);
    }

    @Test
    void withNoKnownBeforeOnlyTheCompareCanPlaceANewHead() {
        assertThat(ChainRule.decide(Optional.of(HEAD), null, AFTER, false, Optional.empty()))
                .isEqualTo(ChainDecision.NEEDS_COMPARE);
        assertThat(ChainRule.decide(Optional.of(HEAD), null, AFTER, false, Optional.of(CompareStatus.BEHIND)))
                .isEqualTo(ChainDecision.STALE);
        assertThat(ChainRule.decide(Optional.of(HEAD), null, HEAD, false, Optional.empty()))
                .isEqualTo(ChainDecision.DUPLICATE);
    }

    @Test
    void aFastForwardIsAcceptedWithoutACompare() {
        assertThat(ChainRule.decide(Optional.of(HEAD), HEAD, AFTER, false, Optional.empty()))
                .isEqualTo(ChainDecision.ACCEPT);
    }

    @Test
    void aForcePushIsAcceptedWithoutACompare() {
        assertThat(ChainRule.decide(Optional.of(HEAD), BEFORE, AFTER, true, Optional.empty()))
                .isEqualTo(ChainDecision.ACCEPT);
    }

    @Test
    void aGapWithoutAnAnswerNeedsACompare() {
        assertThat(ChainRule.decide(Optional.of(HEAD), BEFORE, AFTER, false, Optional.empty()))
                .isEqualTo(ChainDecision.NEEDS_COMPARE);
    }

    @ParameterizedTest
    @MethodSource("compareAnswers")
    void aGapIsDecidedByTheCompareAnswer(CompareStatus status, ChainDecision expected) {
        assertThat(ChainRule.decide(Optional.of(HEAD), BEFORE, AFTER, false, Optional.of(status)))
                .isEqualTo(expected);
    }

    @Test
    void needsCompareOnlyAppearsWithoutAnAnswer() {
        Stream<Optional<String>> heads = Stream.of(Optional.empty(), Optional.of(HEAD), Optional.of(AFTER));
        heads.forEach(head -> {
            for (boolean forced : new boolean[] {false, true}) {
                for (String before : new String[] {BEFORE, HEAD}) {
                    assertThat(Arrays.stream(CompareStatus.values())
                                    .map(status -> ChainRule.decide(head, before, AFTER, forced, Optional.of(status))))
                            .doesNotContain(ChainDecision.NEEDS_COMPARE);
                }
            }
        });
    }

    static Stream<Arguments> compareAnswers() {
        return Stream.of(
                Arguments.of(CompareStatus.AHEAD, ChainDecision.ACCEPT),
                Arguments.of(CompareStatus.IDENTICAL, ChainDecision.DUPLICATE),
                Arguments.of(CompareStatus.BEHIND, ChainDecision.STALE),
                Arguments.of(CompareStatus.DIVERGED, ChainDecision.ACCEPT));
    }
}
