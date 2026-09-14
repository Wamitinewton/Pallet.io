package io.pallet.apigateway.docs;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.annotations.UnitTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@UnitTest
class DocsHubControllerTest {

    @ParameterizedTest
    @CsvSource({"identity-service,Identity", "notification-service,Notification", "billing,Billing"})
    void humanizesARouteNameIntoATabLabel(String routeName, String expected) {
        assertThat(DocsHubController.humanize(routeName)).isEqualTo(expected);
    }
}
