package club.ttg.comment.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(InternalServiceProperties.class)
public class SecurityConfig
{
    private static final String[] SWAGGER_WHITELIST = {
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/v3/api-docs.yaml"
    };

    private static final String MODERATION_PATH = "/api/v1/comments/moderation";
    private static final String MODERATION_PATH_PATTERN = "/api/v1/comments/moderation/**";

    /**
     * Восстановление удалённого комментария. Лежит не под /moderation (ручка действует над
     * конкретным комментарием), поэтому модераторским матчером выше не покрывается и нуждается
     * в собственном правиле: без него запрос попал бы под {@code anyRequest().authenticated()}
     * и был бы доступен любому вошедшему пользователю. Метод указан явно — POST здесь
     * единственный, а /{id}/replies под этот шаблон не подпадает.
     */
    private static final String RESTORE_PATH_PATTERN = "/api/v1/comments/*/restore";

    /**
     * Чтение комментариев публично: гость должен видеть обсуждение и плашку «войдите,
     * чтобы ответить». Пути перечислены точечно, а не шаблоном /api/v1/comments/**,
     * чтобы никакой из них не мог перекрыть модераторские эндпоинты.
     */
    private static final String PUBLIC_ROOT_COMMENTS_PATH = "/api/v1/comments";
    private static final String PUBLIC_COMMENTS_COUNT_PATH = "/api/v1/comments/count";
    private static final String PUBLIC_COMMENTS_LATEST_PATH = "/api/v1/comments/latest";
    private static final String PUBLIC_COMMENT_REPLIES_PATTERN = "/api/v1/comments/*/replies";

    // Только GET одиночного комментария по UUID. Regex-переменная пути (матчит лишь путь,
    // не строку запроса), а не /*, чтобы не открыть публично /moderation и любой будущий
    // одно-сегментный путь под /api/v1/comments.
    private static final String PUBLIC_COMMENT_BY_ID_PATTERN =
            "/api/v1/comments/{id:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}";

    /**
     * Межсервисные ручки: их вызывает auth-service, пользовательского JWT у него нет.
     * {@code permitAll} здесь не означает «открыто всем» — доступ проверяет
     * {@link club.ttg.comment.security.InternalServiceTokenFilter} по общему секрету.
     * Без этого исключения запрос без Bearer-токена отвергался бы цепочкой Spring Security
     * раньше, чем фильтр (обычный сервлет-фильтр, идущий после неё) получил бы управление.
     */
    private static final String INTERNAL_PATH_PATTERN = "/api/v1/internal/**";

    private static final int MIN_SECRET_LENGTH_BYTES = 32;

    @Bean
    public SecurityFilterChain securityFilterChain(final HttpSecurity http) {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(SWAGGER_WHITELIST).permitAll()
                        .requestMatchers(INTERNAL_PATH_PATTERN).permitAll()
                        .requestMatchers(HttpMethod.GET, MODERATION_PATH, MODERATION_PATH_PATTERN)
                        .hasAnyRole("MODERATOR", "ADMIN")
                        .requestMatchers(HttpMethod.POST, RESTORE_PATH_PATTERN)
                        .hasAnyRole("MODERATOR", "ADMIN")
                        .requestMatchers(
                                HttpMethod.GET,
                                PUBLIC_ROOT_COMMENTS_PATH,
                                PUBLIC_COMMENTS_COUNT_PATH,
                                PUBLIC_COMMENTS_LATEST_PATH,
                                PUBLIC_COMMENT_REPLIES_PATTERN
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_COMMENT_BY_ID_PATTERN).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));

        return http.build();
    }

    /**
     * Токены выпускает auth-service, подписывая их общим секретом через jjwt:
     * {@code Keys.hmacShaKeyFor(secret)} + {@code signWith(key)}. Алгоритм там не задаётся
     * явно — jjwt выводит его из длины секрета, поэтому здесь применяется то же правило.
     * Захардкоженный HS256 отверг бы токен, подписанный 64-байтным секретом (HS512),
     * с ошибкой «Another algorithm expected».
     * <p>
     * Валидатор issuer не подключается сознательно: auth-service не проставляет claim
     * {@code iss}, поэтому проверка issuer отвергала бы все токены. Срок действия
     * ({@code exp}) проверяется валидаторами NimbusJwtDecoder по умолчанию.
     */
    @Bean
    public JwtDecoder jwtDecoder(@Value("${auth-service.jwt-secret}") final String secret)
    {
        final byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);

        if (keyBytes.length < MIN_SECRET_LENGTH_BYTES)
        {
            throw new IllegalStateException(
                    "auth-service.jwt-secret is too short. Current length: " + keyBytes.length
                            + " bytes. Minimum required length for HS256 is " + MIN_SECRET_LENGTH_BYTES + " bytes."
            );
        }

        final MacAlgorithm macAlgorithm = resolveMacAlgorithm(keyBytes.length);

        return NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(keyBytes, jcaAlgorithmName(macAlgorithm)))
                .macAlgorithm(macAlgorithm)
                .build();
    }

    /** Повторяет выбор алгоритма в jjwt {@code Keys.hmacShaKeyFor}: по длине ключа. */
    private static MacAlgorithm resolveMacAlgorithm(final int secretLengthBytes)
    {
        final int secretLengthBits = secretLengthBytes * 8;

        if (secretLengthBits >= 512)
        {
            return MacAlgorithm.HS512;
        }

        if (secretLengthBits >= 384)
        {
            return MacAlgorithm.HS384;
        }

        return MacAlgorithm.HS256;
    }

    private static String jcaAlgorithmName(final MacAlgorithm macAlgorithm)
    {
        if (MacAlgorithm.HS512.equals(macAlgorithm))
        {
            return "HmacSHA512";
        }

        if (MacAlgorithm.HS384.equals(macAlgorithm))
        {
            return "HmacSHA384";
        }

        return "HmacSHA256";
    }

    /**
     * Извлекает роли из claim "roles" JWT и преобразует их в authorities вида ROLE_*.
     * auth-service кладёт туда плоский список имён ролей (ADMIN, MODERATOR, USER).
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter()
    {
        final JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::extractRoles);
        return converter;
    }

    private static Collection<GrantedAuthority> extractRoles(final Jwt jwt)
    {
        final List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null)
        {
            return List.of();
        }

        return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }
}
