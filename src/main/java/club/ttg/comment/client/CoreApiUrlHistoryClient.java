package club.ttg.comment.client;

import club.ttg.comment.config.CoreApiProperties;
import club.ttg.comment.config.InternalServiceProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

/**
 * Прежние адреса записей каталога из core-api: {@code GET /api/internal/url-history?since=…}
 * с общим межсервисным секретом в {@code X-Service-Token} (тот же {@code INTERNAL_SERVICE_SECRET},
 * что закрывает внутренние ручки этого сервиса).
 */
@Component
public class CoreApiUrlHistoryClient
{
    static final String PATH = "/api/internal/url-history";
    static final String TOKEN_HEADER = "X-Service-Token";

    /**
     * Смена адреса записи. {@code oldUrl}/{@code newUrl} — последний сегмент пути страницы
     * (слаг), {@code section} — первый; {@code newUrl} всегда текущий.
     */
    public record UrlHistoryEntry(String section, String oldUrl, String newUrl, Instant changedAt)
    {
    }

    private final RestClient restClient;
    private final String serviceSecret;
    private final boolean configured;

    @Autowired
    public CoreApiUrlHistoryClient(
            final CoreApiProperties coreApi,
            final InternalServiceProperties internal
    )
    {
        this(RestClient.builder().requestFactory(requestFactory(coreApi)), coreApi, internal);
    }

    CoreApiUrlHistoryClient(
            final RestClient.Builder builder,
            final CoreApiProperties coreApi,
            final InternalServiceProperties internal
    )
    {
        this.configured = StringUtils.hasText(coreApi.getBaseUrl())
                && StringUtils.hasText(internal.getServiceSecret());
        this.restClient = configured ? builder.baseUrl(coreApi.getBaseUrl()).build() : null;
        this.serviceSecret = internal.getServiceSecret();
    }

    /** Заданы ли адрес core-api и секрет — без них ходить некуда и не с чем. */
    public boolean isConfigured()
    {
        return configured;
    }

    /**
     * Смены адресов после {@code since} (все, если {@code null}). Ошибки транспорта и ответа
     * не глотаются — решать, что с ними делать, вызывающему.
     */
    public List<UrlHistoryEntry> fetch(final Instant since)
    {
        if (!configured)
        {
            throw new IllegalStateException("core-api.base-url or internal.service-secret is not configured");
        }

        final List<UrlHistoryEntry> entries = restClient.get()
                .uri(uri -> {
                    uri.path(PATH);
                    if (since != null)
                    {
                        uri.queryParam("since", since.toString());
                    }
                    return uri.build();
                })
                .header(TOKEN_HEADER, serviceSecret)
                .retrieve()
                .body(new ParameterizedTypeReference<>()
                {
                });
        return entries == null ? List.of() : entries;
    }

    private static SimpleClientHttpRequestFactory requestFactory(final CoreApiProperties coreApi)
    {
        final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(coreApi.getConnectTimeout());
        factory.setReadTimeout(coreApi.getReadTimeout());
        return factory;
    }
}
