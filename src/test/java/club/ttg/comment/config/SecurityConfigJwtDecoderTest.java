package club.ttg.comment.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Проверяет, что JwtDecoder принимает токены auth-service. Токены здесь выпускаются тем же
 * способом, что и в JwtTokenServiceImpl auth-service: Keys.hmacShaKeyFor(secret) + signWith(key),
 * где алгоритм подписи jjwt выбирает по длине секрета.
 */
class SecurityConfigJwtDecoderTest
{
    private static final String SECRET_32_BYTES = "0123456789abcdef0123456789abcdef";
    private static final String SECRET_64_BYTES = SECRET_32_BYTES + SECRET_32_BYTES;

    private final SecurityConfig securityConfig = new SecurityConfig();

    @Test
    void decodesTokenSignedWith32ByteSecret()
    {
        final UUID userId = UUID.randomUUID();
        final String token = issueToken(SECRET_32_BYTES, userId, Instant.now().plus(1, ChronoUnit.HOURS));

        final Jwt jwt = securityConfig.jwtDecoder(SECRET_32_BYTES).decode(token);

        assertThat(jwt.getHeaders().get("alg")).isEqualTo("HS256");
        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString("username")).isEqualTo("peterko");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
    }

    /**
     * Секрет в 64 байта заставляет jjwt подписывать HS512. Захардкоженный HS256 отверг бы
     * такой токен с "Another algorithm expected" — ровно тот симптом, из-за которого чинили.
     */
    @Test
    void decodesTokenSignedWith64ByteSecret()
    {
        final UUID userId = UUID.randomUUID();
        final String token = issueToken(SECRET_64_BYTES, userId, Instant.now().plus(1, ChronoUnit.HOURS));

        final Jwt jwt = securityConfig.jwtDecoder(SECRET_64_BYTES).decode(token);

        assertThat(jwt.getHeaders().get("alg")).isEqualTo("HS512");
        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
    }

    @Test
    void rejectsTokenSignedWithAnotherSecret()
    {
        final String foreignToken = issueToken(
                "ffffffffffffffffffffffffffffffff",
                UUID.randomUUID(),
                Instant.now().plus(1, ChronoUnit.HOURS)
        );

        final JwtDecoder decoder = securityConfig.jwtDecoder(SECRET_32_BYTES);

        assertThatThrownBy(() -> decoder.decode(foreignToken)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredToken()
    {
        final String expiredToken = issueToken(
                SECRET_32_BYTES,
                UUID.randomUUID(),
                Instant.now().minus(1, ChronoUnit.HOURS)
        );

        final JwtDecoder decoder = securityConfig.jwtDecoder(SECRET_32_BYTES);

        assertThatThrownBy(() -> decoder.decode(expiredToken)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsMalformedToken()
    {
        final JwtDecoder decoder = securityConfig.jwtDecoder(SECRET_32_BYTES);

        assertThatThrownBy(() -> decoder.decode("garbage")).isInstanceOf(JwtException.class);
    }

    @Test
    void failsFastOnSecretShorterThanHs256Requires()
    {
        assertThatThrownBy(() -> securityConfig.jwtDecoder("too-short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too short");
    }

    /** Повторяет выпуск access-токена в auth-service (JwtTokenServiceImpl#generateTokens). */
    private static String issueToken(final String secret, final UUID userId, final Instant expiresAt)
    {
        final SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));

        return Jwts.builder()
                .subject(userId.toString())
                .claim("username", "peterko")
                .claim("email", "peterko@ttg.club")
                .claim("roles", List.of("USER"))
                .issuedAt(Date.from(Instant.now().minus(1, ChronoUnit.MINUTES)))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }
}
