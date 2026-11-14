package club.ttg.comment.service;

import club.ttg.comment.model.SourcePlatform;
import club.ttg.comment.repository.PageUrlAliasRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Приводит адрес страницы к текущему. Запрос, пришедший по прежнему адресу записи
 * ({@code /spells/fireball-phb} после переезда на {@code /spells/fireball-ph}), читает и пишет
 * обсуждение текущего адреса — иначе по старой ссылке была бы пустая лента, а новый
 * комментарий лёг бы туда, где его на новой странице никто не увидит.
 * <p>
 * Адреса меняет только core-api, то есть сайт 2024: для остальных платформ адрес отдаётся
 * как есть, без похода в базу.
 */
@Service
@RequiredArgsConstructor
public class PageUrlResolver
{
    /** Платформа, чьи адреса ведёт core-api. */
    public static final SourcePlatform CORE_API_PLATFORM = SourcePlatform.SITE_5E24;

    private final PageUrlAliasRepository aliasRepository;

    /**
     * @param platform платформа (уже с подставленным значением по умолчанию)
     * @param section  раздел (нормализованный)
     * @param url      адрес страницы (нормализованный)
     * @return текущий адрес страницы; сам {@code url}, если он не числится прежним
     */
    public String resolve(final SourcePlatform platform, final String section, final String url)
    {
        if (platform != CORE_API_PLATFORM || section == null || url == null)
        {
            return url;
        }

        return aliasRepository.findCurrentUrl(platform, section, url).orElse(url);
    }
}
