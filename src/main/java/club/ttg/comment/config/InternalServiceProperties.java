package club.ttg.comment.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Общий секрет для аутентификации межсервисных вызовов внутренних ручек
 * {@code /api/v1/internal/**} (значение из переменной окружения {@code INTERNAL_SERVICE_SECRET}).
 * <p>
 * Значение по умолчанию — пустая строка: не сконфигурированный секрет означает, что внутренние
 * ручки закрыты полностью (fail-closed), а не открыты для всех.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "internal")
public class InternalServiceProperties
{
    private String serviceSecret;
}
