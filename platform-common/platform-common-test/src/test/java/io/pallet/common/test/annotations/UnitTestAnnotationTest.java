package io.pallet.common.test.annotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/**
 * Proves {@code @UnitTest} actually engages {@link org.mockito.junit.jupiter.MockitoExtension}:
 * the {@code @Mock} field below resolves with no {@code MockitoAnnotations.openMocks(this)}
 * anywhere in this class. Without the extension, {@code mockedList} would be {@code null} and
 * the test would fail with a {@link NullPointerException}, not an assertion failure.
 */
@UnitTest
class UnitTestAnnotationTest {

    @Mock
    private List<String> mockedList;

    @Test
    void mockFieldResolvesWithoutManualMockitoSetup() {
        when(mockedList.size()).thenReturn(42);

        assertThat(mockedList.size()).isEqualTo(42);
    }
}
