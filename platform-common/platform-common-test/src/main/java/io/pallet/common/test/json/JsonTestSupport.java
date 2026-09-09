package io.pallet.common.test.json;

import tools.jackson.databind.json.JsonMapper;

/**
 * A standalone Jackson 3 {@link JsonMapper} for {@code MockMvc} request/response bodies in a
 * {@code @ControllerTest}, without depending on the application's own mapper bean. A
 * {@code @WebMvcTest} slice may not even expose one, depending on what's under test.
 */
public final class JsonTestSupport {

    public static final JsonMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();

    private JsonTestSupport() {}

    public static String toJson(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static <T> T fromJson(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }
}
