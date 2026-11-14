package club.ttg.comment.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Подключение к core-api — сервису каталога сайта 2024. Сейчас сервис комментариев ходит
 * туда за одним: прежними адресами записей ({@code GET /api/internal/url-history}), чтобы
 * переносить обсуждения на новый адрес страницы.
 * <p>
 * Пустой {@code baseUrl} выключает синхронизацию: без адреса core-api (локальный запуск,
 * тесты) сервис работает как раньше, просто без переноса.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "core-api")
public class CoreApiProperties
{
    /** Адрес core-api, например {@code http://core-api:8080}. */
    private String baseUrl;

    private Duration connectTimeout = Duration.ofSeconds(2);

    private Duration readTimeout = Duration.ofSeconds(10);

    private UrlHistory urlHistory = new UrlHistory();

    @Getter
    @Setter
    public static class UrlHistory
    {
        /** Пауза между синхронизациями прежних адресов. */
        private Duration syncInterval = Duration.ofMinutes(5);

        /**
         * Насколько раньше последней известной смены запрашивать историю. Смены, записанные
         * в core-api чуть раньше уже полученной, но закоммиченные позже, иначе потерялись бы
         * навсегда; повтор уже известных пар безвреден — применение идемпотентно.
         */
        private Duration overlap = Duration.ofMinutes(10);
    }
}
