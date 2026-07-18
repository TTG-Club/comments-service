package club.ttg.comment.security;

import club.ttg.comment.config.InternalServiceProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Защищает внутренние ручки {@code /api/v1/internal/**} общим секретом сервисов вместо JWT:
 * входящий заголовок {@value #SERVICE_TOKEN_HEADER} должен совпасть с настроенным секретом
 * ({@code internal.service-secret}). Вызывает их auth-service, у которого пользовательского
 * токена нет.
 * <p>
 * Fail-closed: если секрет не сконфигурирован (пустой) или заголовок не совпадает — запрос
 * отклоняется с 401. Остальные пути фильтр не трогает ({@link #shouldNotFilter}), их обслуживает
 * обычная цепочка OAuth2 Resource Server.
 * <p>
 * В SecurityConfig внутренние пути помечены {@code permitAll} намеренно: фильтр — обычный
 * сервлет-фильтр и по порядку идёт после цепочки Spring Security, поэтому без {@code permitAll}
 * запрос без Bearer-токена отвергался бы раньше и до проверки секрета дело бы не дошло. Вся
 * защита этих путей лежит на этом фильтре.
 */
@Component
@RequiredArgsConstructor
public class InternalServiceTokenFilter extends OncePerRequestFilter
{
    public static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

    private static final String INTERNAL_PATH_PREFIX = "/api/v1/internal/";

    private final InternalServiceProperties properties;

    @Override
    protected void doFilterInternal(
            @NonNull final HttpServletRequest request,
            @NonNull final HttpServletResponse response,
            @NonNull final FilterChain filterChain
    ) throws ServletException, IOException
    {
        if (!isAuthorized(request))
        {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Секреты сравниваются {@link MessageDigest#isEqual} за время, не зависящее от длины общего
     * префикса. Обычный {@code String.equals} возвращается на первом различии, и по разнице во
     * времени ответов секрет можно подбирать посимвольно.
     */
    private boolean isAuthorized(final HttpServletRequest request)
    {
        final String configuredSecret = properties.getServiceSecret();

        if (!StringUtils.hasText(configuredSecret))
        {
            return false;
        }

        final String providedToken = request.getHeader(SERVICE_TOKEN_HEADER);

        if (providedToken == null)
        {
            return false;
        }

        return MessageDigest.isEqual(
                configuredSecret.getBytes(StandardCharsets.UTF_8),
                providedToken.getBytes(StandardCharsets.UTF_8)
        );
    }

    @Override
    protected boolean shouldNotFilter(@NonNull final HttpServletRequest request)
    {
        return !request.getRequestURI().startsWith(INTERNAL_PATH_PREFIX);
    }
}
