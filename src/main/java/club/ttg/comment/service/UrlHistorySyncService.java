package club.ttg.comment.service;

import club.ttg.comment.client.CoreApiUrlHistoryClient;
import club.ttg.comment.client.CoreApiUrlHistoryClient.UrlHistoryEntry;
import club.ttg.comment.config.CoreApiProperties;
import club.ttg.comment.repository.PageUrlAliasRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Перенос обсуждений на новый адрес страницы после смены адреса записи в core-api.
 * <p>
 * Комментарий привязан к странице тройкой {@code source_platform + section + url}, где
 * {@code url} — путь страницы целиком ({@code /spells/fireball-phb}). core-api при смене
 * акронима источника меняет последний сегмент пути и отдаёт пары «прежний слаг → текущий»
 * по разделам. Синхронизация:
 * <ol>
 *     <li>берёт из core-api смены после последней известной (с запасом
 *     {@link CoreApiProperties.UrlHistory#getOverlap()});</li>
 *     <li>складывает их в {@code page_url_aliases}, схлопывая цепочки;</li>
 *     <li>переносит комментарии с прежних адресов на текущие.</li>
 * </ol>
 * Всё идемпотентно: повтор той же выдачи ничего не меняет, поэтому запас по времени и
 * повторные прогоны безопасны.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UrlHistorySyncService
{
    private final CoreApiUrlHistoryClient client;
    private final PageUrlAliasRepository aliasRepository;
    private final CoreApiProperties coreApiProperties;

    /**
     * Один прогон синхронизации.
     *
     * @return число перенесённых комментариев
     */
    @Transactional
    public int sync()
    {
        final Instant since = aliasRepository.findLatestChangedAt(PageUrlResolver.CORE_API_PLATFORM)
                .map(latest -> latest.minus(coreApiProperties.getUrlHistory().getOverlap()))
                .orElse(null);

        final List<UrlHistoryEntry> entries = client.fetch(since);
        if (entries.isEmpty())
        {
            return 0;
        }

        int remembered = 0;
        for (final UrlHistoryEntry entry : entries)
        {
            if (apply(entry))
            {
                remembered++;
            }
        }

        final int moved = aliasRepository.moveCommentsToCurrentUrls(PageUrlResolver.CORE_API_PLATFORM);
        log.info("URL history sync: {} changes since {}, {} applied, {} comments moved",
                entries.size(), since, remembered, moved);
        return moved;
    }

    private boolean apply(final UrlHistoryEntry entry)
    {
        final String section = normalize(entry.section());
        final String oldSlug = normalize(entry.oldUrl());
        final String newSlug = normalize(entry.newUrl());
        if (section == null || oldSlug == null || newSlug == null || entry.changedAt() == null
                || oldSlug.equals(newSlug))
        {
            log.warn("URL history sync: skipping malformed entry {}", entry);
            return false;
        }

        aliasRepository.remember(
                PageUrlResolver.CORE_API_PLATFORM,
                section,
                pagePath(section, oldSlug),
                pagePath(section, newSlug),
                entry.changedAt()
        );
        return true;
    }

    /**
     * Путь страницы в том виде, в каком его присылает фронт сайта 2024 и хранит сервис:
     * {@code /<раздел>/<слаг>}.
     */
    static String pagePath(final String section, final String slug)
    {
        return "/" + section + "/" + slug;
    }

    private static String normalize(final String value)
    {
        if (!StringUtils.hasText(value))
        {
            return null;
        }

        final String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return trimmed.contains("/") ? null : trimmed;
    }
}
