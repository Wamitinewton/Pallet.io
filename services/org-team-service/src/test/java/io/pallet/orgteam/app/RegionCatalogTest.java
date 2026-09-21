package io.pallet.orgteam.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.test.annotations.UnitTest;
import io.pallet.orgteam.config.OrgTeamProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

@UnitTest
class RegionCatalogTest {

    private static RegionCatalog catalog(List<String> aws, List<String> gcp) {
        OrgTeamProperties.Apps apps = new OrgTeamProperties.Apps(new OrgTeamProperties.Apps.Regions(aws, gcp));
        return new RegionCatalog(new OrgTeamProperties(null, null, apps, null, null, null, null));
    }

    @Test
    void aRegionIsValidOnlyForTheProviderThatListsIt() {
        RegionCatalog catalog = catalog(List.of("us-east-1"), List.of("us-central1"));

        assertThat(catalog.isValid(CloudProvider.AWS, "us-east-1")).isTrue();
        assertThat(catalog.isValid(CloudProvider.GCP, "us-central1")).isTrue();
        assertThat(catalog.isValid(CloudProvider.AWS, "us-central1")).isFalse();
        assertThat(catalog.isValid(CloudProvider.GCP, "us-east-1")).isFalse();
    }

    @Test
    void matchingIsExact() {
        RegionCatalog catalog = catalog(List.of("us-east-1"), List.of("us-central1"));

        assertThat(catalog.isValid(CloudProvider.AWS, "US-EAST-1")).isFalse();
        assertThat(catalog.isValid(CloudProvider.AWS, " us-east-1")).isFalse();
        assertThat(catalog.isValid(CloudProvider.AWS, "")).isFalse();
    }

    @Test
    void nullInputsAreInvalidRatherThanFailing() {
        RegionCatalog catalog = catalog(List.of("us-east-1"), List.of("us-central1"));

        assertThat(catalog.isValid(null, "us-east-1")).isFalse();
        assertThat(catalog.isValid(CloudProvider.AWS, null)).isFalse();
    }

    @Test
    void startupFailsWhenAProviderHasNoRegions() {
        assertThatThrownBy(() -> catalog(List.of(), List.of("us-central1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AWS");
        assertThatThrownBy(() -> catalog(List.of("us-east-1"), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GCP");
    }

    @Test
    void startupFailsOnABlankRegion() {
        assertThatThrownBy(() -> catalog(List.of("us-east-1", " "), List.of("us-central1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Blank");
    }
}
