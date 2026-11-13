package com.mychatbot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "PET_LIVE_GROQ_TEST", matches = "true")
class LiveGroqTest {
    @Test void existingPrivateYamlCanStreamOneSafeGeneralQuestion() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        for (String path : new String[]{".secret/application.yml", "src/main/resources/application.yml"}) {
            var resource = new FileSystemResource(path);
            if (resource.exists()) {
                new YamlPropertySourceLoader().load(path, resource)
                        .forEach(source -> environment.getPropertySources().addLast(source));
            }
        }
        AiProperties properties = Binder.get(environment).bind("ai", AiProperties.class).get();
        GroqAiProvider provider = new GroqAiProvider(properties, new ObjectMapper());
        var fragments = provider.stream(new AiRequest("Java의 불변 객체를 한 문장으로 설명해 주세요.", ""))
                .collectList().block(Duration.ofSeconds(60));
        assertThat(fragments != null && fragments.stream().anyMatch(text -> !text.isBlank()))
                .as("Safe live Groq stream returned content; key/model/response deliberately omitted").isTrue();
    }
}
