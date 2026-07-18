package club.ttg.comment.security;

import club.ttg.comment.config.InternalServiceProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет защиту внутренних ручек общим секретом: и «пропустить», и «отклонить», и то,
 * что не сконфигурированный секрет закрывает ручки, а не открывает их.
 */
class InternalServiceTokenFilterTest
{
    private static final String SECRET = "internal-secret-value";
    private static final String INTERNAL_PATH = "/api/v1/internal/comments/hide-by-author";

    @Test
    void passesRequestWithCorrectSecret() throws Exception
    {
        final MockFilterChain chain = new MockFilterChain();
        final MockHttpServletResponse response = new MockHttpServletResponse();

        filterWithSecret(SECRET).doFilter(requestWithToken(INTERNAL_PATH, SECRET), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void rejectsRequestWithoutHeader() throws Exception
    {
        final MockFilterChain chain = new MockFilterChain();
        final MockHttpServletResponse response = new MockHttpServletResponse();

        filterWithSecret(SECRET).doFilter(requestWithToken(INTERNAL_PATH, null), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        // Запрос до ручки не дошёл: 401 отдан фильтром, а не контроллером.
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void rejectsRequestWithWrongSecret() throws Exception
    {
        final MockFilterChain chain = new MockFilterChain();
        final MockHttpServletResponse response = new MockHttpServletResponse();

        filterWithSecret(SECRET).doFilter(requestWithToken(INTERNAL_PATH, "wrong-secret"), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(chain.getRequest()).isNull();
    }

    /**
     * Правильный префикс секрета не должен давать преимущества: сравнение идёт по всей длине.
     */
    @Test
    void rejectsSecretWithMatchingPrefix() throws Exception
    {
        final MockFilterChain chain = new MockFilterChain();
        final MockHttpServletResponse response = new MockHttpServletResponse();

        filterWithSecret(SECRET).doFilter(
                requestWithToken(INTERNAL_PATH, SECRET.substring(0, SECRET.length() - 1)),
                response,
                chain
        );

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(chain.getRequest()).isNull();
    }

    /**
     * Fail-closed: пустой секрет в конфиге закрывает внутренние ручки, а не открывает их всем.
     * Иначе забытая переменная окружения превратила бы {@code permitAll} в SecurityConfig
     * в публичный доступ к массовому скрытию комментариев.
     */
    @Test
    void rejectsEverythingWhenSecretNotConfigured() throws Exception
    {
        final MockFilterChain chain = new MockFilterChain();
        final MockHttpServletResponse response = new MockHttpServletResponse();

        filterWithSecret("").doFilter(requestWithToken(INTERNAL_PATH, ""), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void rejectsWhenSecretIsNull() throws Exception
    {
        final MockFilterChain chain = new MockFilterChain();
        final MockHttpServletResponse response = new MockHttpServletResponse();

        filterWithSecret(null).doFilter(requestWithToken(INTERNAL_PATH, SECRET), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(chain.getRequest()).isNull();
    }

    /**
     * Публичные и пользовательские пути фильтр не трогает — их обслуживает обычная цепочка
     * с проверкой JWT.
     */
    @Test
    void doesNotFilterNonInternalPaths()
    {
        final InternalServiceTokenFilter filter = filterWithSecret(SECRET);

        assertThat(filter.shouldNotFilter(requestWithToken("/api/v1/comments", null))).isTrue();
        assertThat(filter.shouldNotFilter(requestWithToken("/api/v1/comments/moderation", null))).isTrue();
        assertThat(filter.shouldNotFilter(requestWithToken(INTERNAL_PATH, null))).isFalse();
    }

    private static InternalServiceTokenFilter filterWithSecret(final String secret)
    {
        final InternalServiceProperties properties = new InternalServiceProperties();
        properties.setServiceSecret(secret);
        return new InternalServiceTokenFilter(properties);
    }

    private static MockHttpServletRequest requestWithToken(final String uri, final String token)
    {
        final MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);

        if (token != null)
        {
            request.addHeader(InternalServiceTokenFilter.SERVICE_TOKEN_HEADER, token);
        }

        return request;
    }
}
