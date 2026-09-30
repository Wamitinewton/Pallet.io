package io.pallet.gitintegration.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.MountableFile;
import org.yaml.snakeyaml.Yaml;

/** The alert rules parse, every alert opens a runbook section that exists, and every series it reads is ours. */
class AlertRulesIntegrationTest {

    private static final Path REPO = Path.of("../..");
    private static final Path RULES = REPO.resolve("deploy/local/prometheus/rules/git-integration-service.rules.yml");
    private static final Path RUNBOOK = REPO.resolve("docs/git-integration-service/RUNBOOK.md");
    private static final String RUNBOOK_LINK = "docs/git-integration-service/RUNBOOK.md#";
    private static final String PROMETHEUS_IMAGE =
            System.getProperty("pallet.test.prometheus.image", "prom/prometheus:v3.14.0");
    private static final Pattern GIT_SERIES = Pattern.compile("\\bgit_[a-z_]+");
    private static final Pattern HEADING = Pattern.compile("^#{2,3} (.+)$", Pattern.MULTILINE);
    private static final List<String> SUFFIXES =
            List.of("_seconds_bucket", "_seconds_count", "_seconds_sum", "_seconds_max", "_seconds", "_total");

    @Test
    void promtoolAcceptsTheRules() {
        try (GenericContainer<?> promtool = new GenericContainer<>(PROMETHEUS_IMAGE)
                .withCreateContainerCmdModifier(command -> command.withEntrypoint("promtool"))
                .withCopyFileToContainer(MountableFile.forHostPath(RULES), "/rules.yml")
                .withCommand("check", "rules", "/rules.yml")
                .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(1)))) {
            promtool.start();
            assertThat(promtool.getLogs()).contains("SUCCESS");
        }
    }

    @Test
    void everyAlertLinksToARunbookSectionThatExists() throws IOException {
        Set<String> anchors = HEADING.matcher(Files.readString(RUNBOOK))
                .results()
                .map(heading -> anchor(heading.group(1)))
                .collect(Collectors.toSet());
        List<Map<String, Object>> alerts = alerts();

        assertThat(alerts).hasSizeGreaterThanOrEqualTo(10);
        for (Map<String, Object> alert : alerts) {
            @SuppressWarnings("unchecked")
            Map<String, String> annotations = (Map<String, String>) alert.get("annotations");
            String link = annotations.get("runbook_url");
            assertThat(link).as(alert.get("alert").toString()).startsWith(RUNBOOK_LINK);
            assertThat(anchors).as(link).contains(link.substring(RUNBOOK_LINK.length()));
        }
    }

    @Test
    void everyServiceSeriesAnAlertReadsIsACatalogMeter() throws Exception {
        Set<String> catalog = catalogSeries();
        List<String> read = new ArrayList<>();
        for (Map<String, Object> alert : alerts()) {
            Matcher series = GIT_SERIES.matcher(alert.get("expr").toString());
            while (series.find()) {
                read.add(series.group());
            }
        }

        assertThat(read).isNotEmpty();
        for (String series : read) {
            assertThat(catalog.contains(series) || catalog.contains(stripSuffix(series)))
                    .as(series)
                    .isTrue();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> alerts() throws IOException {
        Map<String, Object> document = new Yaml().load(Files.readString(RULES));
        List<Map<String, Object>> alerts = new ArrayList<>();
        for (Map<String, Object> group : (List<Map<String, Object>>) document.get("groups")) {
            alerts.addAll((List<Map<String, Object>>) group.get("rules"));
        }
        return alerts;
    }

    private static Set<String> catalogSeries() throws IllegalAccessException {
        Set<String> names = new HashSet<>();
        for (Field field : MetricsCatalog.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                String value = (String) field.get(null);
                if (value.startsWith(MetricsCatalog.PREFIX + ".")) {
                    names.add(value.replace('.', '_'));
                }
            }
        }
        return names;
    }

    private static String stripSuffix(String series) {
        for (String suffix : SUFFIXES) {
            if (series.endsWith(suffix)) {
                return series.substring(0, series.length() - suffix.length());
            }
        }
        return series;
    }

    /** GitHub's heading anchors: lower case, punctuation other than hyphens dropped, spaces to hyphens. */
    private static String anchor(String heading) {
        return heading.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 -]", "")
                .trim()
                .replace(' ', '-');
    }
}
