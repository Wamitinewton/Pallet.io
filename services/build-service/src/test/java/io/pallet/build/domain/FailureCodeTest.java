package io.pallet.build.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.events.BuildFailed;
import io.pallet.common.test.annotations.UnitTest;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@UnitTest
class FailureCodeTest {

    @Test
    void theCodesAreExactlyTheOnesTheEventContractDeclares() {
        String[] published = Arrays.stream(BuildFailed.class.getFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .filter(field -> field.getName().startsWith("CODE_"))
                .map(FailureCodeTest::value)
                .toArray(String[]::new);

        assertThat(Arrays.stream(FailureCode.values()).map(FailureCode::wireName))
                .containsExactlyInAnyOrder(published);
    }

    @ParameterizedTest
    @EnumSource(FailureCode.class)
    void eachCodeIsStoredAndPublishedUnderOneName(FailureCode code) {
        assertThat(code.wireName()).isEqualTo(code.name());
    }

    @ParameterizedTest
    @EnumSource(FailureCode.class)
    void eachReasonFitsTheEventsLimit(FailureCode code) {
        assertThat(code.reason()).isNotBlank().hasSizeLessThanOrEqualTo(256);
    }

    private static String value(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
