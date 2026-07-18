package club.ttg.comment.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет, что секция comments.rate-limit из application.yaml действительно ложится в
 * {@link CommentRateLimitProperties}. Значения интервалов задаются строками вида «20s», и
 * опечатка в YAML тихо оставила бы лимитер на значениях по умолчанию — этот тест такое ловит.
 */
class CommentRateLimitPropertiesTest
{
    @Test
    void applicationYamlBindsToProperties() throws IOException
    {
        final CommentRateLimitProperties properties = bindFromApplicationYaml();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getIntervals())
                .containsExactly(Duration.ofSeconds(20), Duration.ofSeconds(60));
        assertThat(properties.getBlockDuration()).isEqualTo(Duration.ofHours(3));
        assertThat(properties.getViolationDecay()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void defaultsMatchApplicationYaml()
    {
        // Значения по умолчанию в коде и в YAML должны совпадать, иначе поведение зависело бы
        // от того, дошёл ли конфиг до сервиса.
        final CommentRateLimitProperties defaults = new CommentRateLimitProperties();

        assertThat(defaults.isEnabled()).isTrue();
        assertThat(defaults.getIntervals())
                .containsExactly(Duration.ofSeconds(20), Duration.ofSeconds(60));
        assertThat(defaults.getBlockDuration()).isEqualTo(Duration.ofHours(3));
        assertThat(defaults.getViolationDecay()).isEqualTo(Duration.ofHours(1));
    }

    private static CommentRateLimitProperties bindFromApplicationYaml() throws IOException
    {
        final List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"));

        final StandardEnvironment environment = new StandardEnvironment();
        sources.forEach(source -> environment.getPropertySources().addFirst(source));

        return Binder.get(environment)
                .bind("comments.rate-limit", CommentRateLimitProperties.class)
                .orElseThrow(() -> new AssertionError("Секция comments.rate-limit не найдена в application.yaml"));
    }
}
