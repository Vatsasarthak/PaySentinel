package com.pay.outbox.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that the application's Jackson configuration differs from Spring Boot's defaults in exactly one
 * way: ACCEPT_FLOAT_AS_INT is disabled. Every other deserialization, serialization and mapper feature is
 * unchanged. The application's settings are read from the real application.yaml.
 */
class JacksonConfigurationTest {

    private static ObjectMapper mapper(String... properties) {
        List<ObjectMapper> holder = new ArrayList<>();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withPropertyValues(properties)
                .run(context -> holder.add(context.getBean(ObjectMapper.class)));
        return holder.get(0);
    }

    private static String[] jacksonPropertiesFromApplicationYaml() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"));
        List<String> jackson = new ArrayList<>();
        for (PropertySource<?> source : sources) {
            if (source.getSource() instanceof java.util.Map<?, ?> map) {
                map.forEach((key, value) -> {
                    if (key.toString().startsWith("spring.jackson.")) {
                        jackson.add(key + "=" + value);
                    }
                });
            }
        }
        return jackson.toArray(String[]::new);
    }

    @Test
    void applicationYamlConfiguresOnlyAcceptFloatAsInt() throws Exception {
        assertThat(jacksonPropertiesFromApplicationYaml())
                .containsExactly("spring.jackson.deserialization.accept-float-as-int=false");
    }

    @Test
    void onlyAcceptFloatAsIntDiffersFromSpringBootDefaults() throws Exception {
        ObjectMapper bootDefault = mapper();
        ObjectMapper application = mapper(jacksonPropertiesFromApplicationYaml());

        assertThat(bootDefault.isEnabled(DeserializationFeature.ACCEPT_FLOAT_AS_INT)).isTrue();
        assertThat(application.isEnabled(DeserializationFeature.ACCEPT_FLOAT_AS_INT)).isFalse();

        List<DeserializationFeature> changedDeserialization = Arrays.stream(DeserializationFeature.values())
                .filter(f -> bootDefault.isEnabled(f) != application.isEnabled(f)).toList();
        List<SerializationFeature> changedSerialization = Arrays.stream(SerializationFeature.values())
                .filter(f -> bootDefault.isEnabled(f) != application.isEnabled(f)).toList();
        List<MapperFeature> changedMapper = Arrays.stream(MapperFeature.values())
                .filter(f -> bootDefault.isEnabled(f) != application.isEnabled(f)).toList();

        assertThat(changedDeserialization).containsExactly(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        assertThat(changedSerialization).isEmpty();
        assertThat(changedMapper).isEmpty();
        assertThat(application.getRegisteredModuleIds()).isEqualTo(bootDefault.getRegisteredModuleIds());
    }
}
