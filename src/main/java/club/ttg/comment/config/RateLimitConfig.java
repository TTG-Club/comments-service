package club.ttg.comment.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(CommentRateLimitProperties.class)
public class RateLimitConfig
{
    /**
     * Время лимитер берёт из этого бина, а не из {@code OffsetDateTime.now()}, чтобы в тестах
     * можно было двигать часы и проверять переходы между интервалами без ожидания в реальном
     * времени.
     */
    @Bean
    public Clock clock()
    {
        return Clock.systemDefaultZone();
    }
}
