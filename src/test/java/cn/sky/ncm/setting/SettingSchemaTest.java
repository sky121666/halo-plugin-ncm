package cn.sky.ncm.setting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class SettingSchemaTest {

    @Test
    void haloCanReadDefaultsFromEachSettingsGroup() throws IOException {
        try (var input = getClass().getResourceAsStream("/extensions/settings.yaml")) {
            assertThat(input).isNotNull();
            Map<?, ?> root = new Yaml().load(input);
            var spec = (Map<?, ?>) root.get("spec");
            var forms = (List<?>) spec.get("forms");

            for (var entry : forms) {
                var form = (Map<?, ?>) entry;
                var schema = (List<?>) form.get("formSchema");
                boolean hasDefault = schema.stream().anyMatch(node -> node instanceof Map<?, ?> field
                    && field.containsKey("name") && field.containsKey("value"));
                assertThat(hasDefault).as("%s has top-level defaults", form.get("group")).isTrue();

                for (var node : schema) {
                    if (node instanceof Map<?, ?> layout && layout.containsKey("$el")) {
                        assertLayoutHasNoDefaults(layout);
                    }
                }
            }
        }
    }

    private static void assertLayoutHasNoDefaults(Map<?, ?> layout) {
        assertThat(layout.containsKey("name") && layout.containsKey("value"))
            .as("Halo does not extract defaults inside layout elements")
            .isFalse();
        if (layout.get("children") instanceof List<?> children) {
            for (var child : children) {
                if (child instanceof Map<?, ?> node) {
                    assertLayoutHasNoDefaults(node);
                }
            }
        }
    }
}
