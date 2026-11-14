package club.ttg.comment.service;

import club.ttg.comment.client.CoreApiUrlHistoryClient;
import club.ttg.comment.client.CoreApiUrlHistoryClient.UrlHistoryEntry;
import club.ttg.comment.config.CoreApiProperties;
import club.ttg.comment.model.SourcePlatform;
import club.ttg.comment.repository.PageUrlAliasRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UrlHistorySyncServiceTest
{
    private static final Instant T1 = Instant.parse("2026-11-14T10:00:00Z");

    private CoreApiUrlHistoryClient client;
    private PageUrlAliasRepository aliasRepository;
    private UrlHistorySyncService syncService;

    @BeforeEach
    void setUp()
    {
        client = mock(CoreApiUrlHistoryClient.class);
        aliasRepository = mock(PageUrlAliasRepository.class);
        final CoreApiProperties properties = new CoreApiProperties();
        properties.getUrlHistory().setOverlap(Duration.ofMinutes(10));
        syncService = new UrlHistorySyncService(client, aliasRepository, properties);
    }

    @Test
    void firstRunFetchesWholeHistory()
    {
        when(aliasRepository.findLatestChangedAt(SourcePlatform.SITE_5E24)).thenReturn(Optional.empty());
        when(client.fetch(null)).thenReturn(List.of());

        assertThat(syncService.sync()).isZero();

        verify(client).fetch(null);
        verify(aliasRepository, never()).moveCommentsToCurrentUrls(any());
    }

    @Test
    void nextRunContinuesFromLatestChangeWithOverlap()
    {
        when(aliasRepository.findLatestChangedAt(SourcePlatform.SITE_5E24)).thenReturn(Optional.of(T1));
        when(client.fetch(any())).thenReturn(List.of());

        syncService.sync();

        verify(client).fetch(T1.minus(Duration.ofMinutes(10)));
    }

    @Test
    void slugsBecomePagePathsAndCommentsAreMoved()
    {
        when(aliasRepository.findLatestChangedAt(SourcePlatform.SITE_5E24)).thenReturn(Optional.empty());
        when(client.fetch(null)).thenReturn(List.of(
                new UrlHistoryEntry("spells", "fireball-phb", "fireball-ph", T1),
                new UrlHistoryEntry("Magic-Items", " Bag-Of-Holding-DMG ", "bag-of-holding-dm", T1)
        ));
        when(aliasRepository.moveCommentsToCurrentUrls(SourcePlatform.SITE_5E24)).thenReturn(3);

        assertThat(syncService.sync()).isEqualTo(3);

        verify(aliasRepository).remember(SourcePlatform.SITE_5E24, "spells",
                "/spells/fireball-phb", "/spells/fireball-ph", T1);
        verify(aliasRepository).remember(SourcePlatform.SITE_5E24, "magic-items",
                "/magic-items/bag-of-holding-dmg", "/magic-items/bag-of-holding-dm", T1);
        verify(aliasRepository).moveCommentsToCurrentUrls(SourcePlatform.SITE_5E24);
    }

    @Test
    void malformedEntriesAreSkipped()
    {
        when(aliasRepository.findLatestChangedAt(SourcePlatform.SITE_5E24)).thenReturn(Optional.empty());
        when(client.fetch(null)).thenReturn(List.of(
                new UrlHistoryEntry("spells", "same", "same", T1),
                new UrlHistoryEntry("spells", "a/b", "c", T1),
                new UrlHistoryEntry(null, "a", "b", T1),
                new UrlHistoryEntry("spells", "a", "b", null)
        ));

        syncService.sync();

        verify(aliasRepository, never()).remember(any(), anyString(), anyString(), anyString(), any());
    }
}
