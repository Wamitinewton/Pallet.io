package io.pallet.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.CannotCreateTransactionException;

class PersistenceExceptionHandlerTest {

    private final PersistenceExceptionHandler handler = new PersistenceExceptionHandler();

    static Stream<Exception> databaseUnavailable() {
        return Stream.of(
                new DataAccessResourceFailureException("connection refused"),
                new TransientDataAccessResourceException("connection reset"),
                new QueryTimeoutException("statement timeout"),
                new CannotCreateTransactionException("no connection"));
    }

    @ParameterizedTest
    @MethodSource("databaseUnavailable")
    void anUnreachableDatabaseIsServiceUnavailableWithoutLeakingTheCause(Exception failure) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/widgets");

        ResponseEntity<ErrorResponse> response = handler.handleDatabaseUnavailable(failure, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(response.getBody().message()).doesNotContain(failure.getMessage());
    }
}
