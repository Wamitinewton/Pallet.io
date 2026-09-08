package io.pallet.common.events;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class TopicsTest {

    private static final Pattern TOPIC_NAME = Pattern.compile("^[a-z]+(\\.[a-z-]+)+$");

    /**
     * Every public String constant on Topics except the DLT suffix, so a new topic that skips all() fails here.
     */
    private static Set<String> declaredTopicConstants() {
        return java.util.Arrays.stream(Topics.class.getDeclaredFields())
            .filter(f -> Modifier.isPublic(f.getModifiers()) && Modifier.isStatic(f.getModifiers()))
            .filter(f -> f.getType() == String.class)
            .filter(f -> !f.getName().equals("DLT_SUFFIX"))
            .map(TopicsTest::value)
            .collect(Collectors.toSet());
    }

    private static String value(Field f) {
        try {
            return (String) f.get(null);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void deadLetterAppendsTheSuffix() {
        assertThat(Topics.deadLetter("build.started")).isEqualTo("build.started.DLT");
    }

    @Test
    void allContainsEveryDeclaredTopicConstant() {
        assertThat(Topics.all()).isEqualTo(declaredTopicConstants());
    }

    @Test
    void everyTopicNameIsLowercaseDottedNoWhitespace() {
        assertThat(Topics.all()).allSatisfy(name -> {
            assertThat(name).doesNotContainAnyWhitespaces();
            assertThat(name).isLowerCase();
            assertThat(TOPIC_NAME.matcher(name).matches())
                .as("%s matches %s", name, TOPIC_NAME.pattern())
                .isTrue();
        });
    }
}
