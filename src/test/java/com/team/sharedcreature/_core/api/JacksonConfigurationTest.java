package com.team.sharedcreature._core.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonConfigurationTest {

    @Test
    void 애플리케이션은_미등록_JSON_필드_거부를_활성화한다() throws Exception {
        List<PropertySource<?>> propertySources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"));

        assertThat(propertySources)
                .anySatisfy(source -> assertThat(source.getProperty(
                        "spring.jackson.deserialization.fail-on-unknown-properties"
                )).isEqualTo(true));
    }
}
