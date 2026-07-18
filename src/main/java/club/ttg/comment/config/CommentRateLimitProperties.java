package club.ttg.comment.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Настройки антиспам-лимита на создание комментариев и ответов.
 * <p>
 * {@code intervals} задаёт минимальный промежуток между комментариями для каждого уровня
 * серии нарушений: нулевой элемент — обычный режим, следующие — ужесточение после каждого
 * нарушения. Когда нарушений становится больше, чем уровней, автор получает бан на
 * {@code blockDuration}. По умолчанию: 20с → 60с → бан 3 часа.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "comments.rate-limit")
public class CommentRateLimitProperties
{
    private boolean enabled = true;

    private List<Duration> intervals = List.of(Duration.ofSeconds(20), Duration.ofSeconds(60));

    private Duration blockDuration = Duration.ofHours(3);

    /** Через столько времени без нарушений серия обнуляется и интервал возвращается к базовому. */
    private Duration violationDecay = Duration.ofHours(1);
}
