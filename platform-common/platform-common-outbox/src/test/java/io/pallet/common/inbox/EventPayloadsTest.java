package io.pallet.common.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.events.OrgMemberAdded;
import io.pallet.common.test.annotations.UnitTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

@UnitTest
class EventPayloadsTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void readsAWellFormedPayload() {
        OrgMemberAdded event = OrgMemberAdded.of("org-1", "user-1", "member@example.com");

        OrgMemberAdded read = EventPayloads.read(jsonMapper, jsonMapper.valueToTree(event), OrgMemberAdded.class);

        assertThat(read).isEqualTo(event);
    }

    @Test
    void anEmptyPayloadIsMalformed() {
        assertThatThrownBy(() -> EventPayloads.read(jsonMapper, null, OrgMemberAdded.class))
                .isInstanceOf(MalformedEventException.class)
                .hasMessage("OrgMemberAdded payload is empty");
        assertThatThrownBy(
                        () -> EventPayloads.read(jsonMapper, JsonNodeFactory.instance.nullNode(), OrgMemberAdded.class))
                .isInstanceOf(MalformedEventException.class)
                .hasMessage("OrgMemberAdded payload is empty");
    }

    @Test
    void anUnreadablePayloadIsMalformedAndNotRetried() {
        assertThatThrownBy(() -> EventPayloads.read(
                        jsonMapper, JsonNodeFactory.instance.textNode("not an object"), OrgMemberAdded.class))
                .isInstanceOf(MalformedEventException.class)
                .isInstanceOf(NonRetryableEventException.class)
                .hasMessage("OrgMemberAdded payload is unreadable");
    }

    @Test
    void requireTextRejectsMissingBlankAndOverlongValues() {
        assertThatThrownBy(() -> EventPayloads.requireText("Event", "orgId", null, 10))
                .isInstanceOf(MalformedEventException.class)
                .hasMessage("Event.orgId is required");
        assertThatThrownBy(() -> EventPayloads.requireText("Event", "orgId", "   ", 10))
                .isInstanceOf(MalformedEventException.class)
                .hasMessage("Event.orgId is required");
        assertThatThrownBy(() -> EventPayloads.requireText("Event", "orgId", "12345678901", 10))
                .isInstanceOf(MalformedEventException.class)
                .hasMessage("Event.orgId is too long");
    }

    @Test
    void requireTextMeasuresTheStrippedValue() {
        assertThatCode(() -> EventPayloads.requireText("Event", "orgId", "  1234567890  ", 10))
                .doesNotThrowAnyException();
    }

    @Test
    void aGivenDisplayNameIsStripped() {
        assertThat(EventPayloads.displayNameOrLocalPart("  Ada  ", "ada@example.com"))
                .isEqualTo("Ada");
    }

    @Test
    void aMissingDisplayNameFallsBackToTheEmailLocalPart() {
        assertThat(EventPayloads.displayNameOrLocalPart(null, "ada@example.com"))
                .isEqualTo("ada");
        assertThat(EventPayloads.displayNameOrLocalPart(" ", "ada@example.com")).isEqualTo("ada");
    }

    @Test
    void anEmailWithoutALocalPartIsMalformedRatherThanAnIndexError() {
        assertThatThrownBy(() -> EventPayloads.displayNameOrLocalPart(null, "no-at-sign"))
                .isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> EventPayloads.displayNameOrLocalPart(null, "@example.com"))
                .isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> EventPayloads.displayNameOrLocalPart(null, null))
                .isInstanceOf(MalformedEventException.class);
    }
}
