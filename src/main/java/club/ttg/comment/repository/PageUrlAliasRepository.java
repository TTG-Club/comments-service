package club.ttg.comment.repository;

import club.ttg.comment.model.SourcePlatform;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Прежние адреса страниц ({@code comment.page_url_aliases}) и перенос комментариев на текущий
 * адрес. Чистый SQL, а не JPA-сущность: таблица — служебный справочник без поведения, а все
 * операции над ней — массовые {@code UPDATE}/{@code UPSERT}, которые JPA всё равно делал бы
 * нативными запросами.
 * <p>
 * Все операции идемпотентны: повторное применение той же смены адреса ничего не меняет.
 */
@Repository
@RequiredArgsConstructor
public class PageUrlAliasRepository
{
    private final NamedParameterJdbcTemplate jdbc;

    /** Текущий адрес для прежнего; пусто — адрес не числится прежним. */
    public Optional<String> findCurrentUrl(final SourcePlatform platform, final String section, final String oldUrl)
    {
        final List<String> found = jdbc.queryForList(
                """
                        select new_url from comment.page_url_aliases
                        where source_platform = :platform and section = :section and old_url = :oldUrl
                        """,
                new MapSqlParameterSource()
                        .addValue("platform", platform.name())
                        .addValue("section", section)
                        .addValue("oldUrl", oldUrl),
                String.class
        );
        return found.stream().findFirst();
    }

    /** Момент последней известной смены адреса на платформе — курсор синхронизации. */
    public Optional<Instant> findLatestChangedAt(final SourcePlatform platform)
    {
        final Timestamp latest = jdbc.queryForObject(
                "select max(changed_at) from comment.page_url_aliases where source_platform = :platform",
                new MapSqlParameterSource("platform", platform.name()),
                Timestamp.class
        );
        return Optional.ofNullable(latest).map(Timestamp::toInstant);
    }

    /**
     * Запоминает смену адреса {@code oldUrl → newUrl} так же, как это делает core-api:
     * <ol>
     *     <li>все ещё более старые адреса, ведущие на {@code oldUrl}, теперь ведут на
     *     {@code newUrl} (цепочка A→B, B→C схлопывается в A→C, B→C);</li>
     *     <li>{@code newUrl} снова текущий, поэтому прежним он больше не числится — это
     *     и возврат к старому адресу (A→B, затем B→A), и петля A→A, оставшаяся после шага 1;</li>
     *     <li>сама пара записывается или обновляется.</li>
     * </ol>
     * Шаг 1 нельзя оставить на core-api: при схлопывании там у старых строк не меняется
     * {@code changed_at}, и в выдачу с курсором {@code since} они не попадают.
     */
    public void remember(
            final SourcePlatform platform,
            final String section,
            final String oldUrl,
            final String newUrl,
            final Instant changedAt
    )
    {
        final MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("platform", platform.name())
                .addValue("section", section)
                .addValue("oldUrl", oldUrl)
                .addValue("newUrl", newUrl)
                .addValue("changedAt", Timestamp.from(changedAt));

        jdbc.update(
                """
                        update comment.page_url_aliases set new_url = :newUrl
                        where source_platform = :platform and section = :section and new_url = :oldUrl
                        """,
                params
        );
        jdbc.update(
                """
                        delete from comment.page_url_aliases
                        where source_platform = :platform and section = :section and old_url = :newUrl
                        """,
                params
        );
        jdbc.update(
                """
                        insert into comment.page_url_aliases as a (source_platform, section, old_url, new_url, changed_at)
                        values (:platform, :section, :oldUrl, :newUrl, :changedAt)
                        on conflict (source_platform, section, old_url) do update
                            set new_url = excluded.new_url,
                                changed_at = greatest(a.changed_at, excluded.changed_at)
                        """,
                params
        );
    }

    /**
     * Переносит на текущий адрес все комментарии платформы, лежащие под прежними адресами.
     * Сразу по всей таблице, а не по одной паре: так подбираются и комментарии, оставленные
     * по старому адресу до того, как сервис узнал о смене, и повторный прогон ничего не меняет.
     * {@code updated_at} не трогается — переезд страницы не правка комментария.
     *
     * @return число перенесённых комментариев
     */
    public int moveCommentsToCurrentUrls(final SourcePlatform platform)
    {
        return jdbc.update(
                """
                        update comment.comments c set url = a.new_url
                        from comment.page_url_aliases a
                        where a.source_platform = :platform
                          and c.source_platform = a.source_platform
                          and c.section = a.section
                          and c.url = a.old_url
                        """,
                new MapSqlParameterSource("platform", platform.name())
        );
    }
}
