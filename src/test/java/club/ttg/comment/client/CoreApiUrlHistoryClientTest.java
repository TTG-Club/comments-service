package club.ttg.comment.client;

import club.ttg.comment.client.CoreApiUrlHistoryClient.UrlHistoryEntry;
import club.ttg.comment.config.CoreApiProperties;
import club.ttg.comment.config.InternalServiceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CoreApiUrlHistoryClientTest
{
    private static CoreApiProperties coreApi(final String baseUrl)
    {
        final CoreApiProperties properties = new CoreApiProperties();
        properties.setBaseUrl(baseUrl);
        return properties;
    }

    private static InternalServiceProperties internal(final String secret)
    {
        final InternalServiceProperties properties = new InternalServiceProperties();
        properties.setServiceSecret(secret);
        return properties;
    }

    @Test
    void fetchesHistorySinceCursorWithServiceToken()
    {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        final CoreApiUrlHistoryClient client =
                new CoreApiUrlHistoryClient(builder, coreApi("http://core-api"), internal("secret"));

        server.expect(requestTo("http://core-api/api/internal/url-history?since=2026-11-14T10:00:00Z"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Service-Token", "secret"))
                .andRespond(withSuccess("""
                        [{"section":"spells","oldUrl":"fireball-phb","newUrl":"fireball-ph",
                          "changedAt":"2026-11-14T10:05:00.123Z"}]
                        """, MediaType.APPLICATION_JSON));

        final List<UrlHistoryEntry> entries = client.fetch(Instant.parse("2026-11-14T10:00:00Z"));

        assertThat(entries).containsExactly(new UrlHistoryEntry(
                "spells", "fireball-phb", "fireball-ph", Instant.parse("2026-11-14T10:05:00.123Z")));
        server.verify();
    }

    @Test
    void withoutCursorFetchesEverything()
    {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        final CoreApiUrlHistoryClient client =
                new CoreApiUrlHistoryClient(builder, coreApi("http://core-api"), internal("secret"));

        server.expect(requestTo("http://core-api/api/internal/url-history"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.fetch(null)).isEmpty();
        server.verify();
    }

    @Test
    void errorResponseIsNotSwallowed()
    {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        final CoreApiUrlHistoryClient client =
                new CoreApiUrlHistoryClient(builder, coreApi("http://core-api"), internal("wrong"));

        server.expect(requestTo("http://core-api/api/internal/url-history"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.fetch(null)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void notConfiguredWithoutUrlOrSecret()
    {
        assertThat(new CoreApiUrlHistoryClient(coreApi(""), internal("secret")).isConfigured()).isFalse();
        assertThat(new CoreApiUrlHistoryClient(coreApi("http://core-api"), internal("")).isConfigured()).isFalse();
        assertThat(new CoreApiUrlHistoryClient(coreApi("http://core-api"), internal("s")).isConfigured()).isTrue();
    }
}
