package club.ttg.comment.model;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Стережёт единственный инвариант, поломка которого гасит комментарии на проде молча.
 * <p>
 * Уже задеплоенный фронт параметр {@code sourcePlatform} не присылает: его запросы обслуживает
 * фолбэк {@link Comment#DEFAULT_SOURCE_PLATFORM}, а существующие строки размечены значением из
 * бэкфилла миграции 008. Пока значения совпадают, миграция для прода незаметна. Стоит им
 * разойтись — фронт начнёт спрашивать один ключ, а строки будут лежать под другим, и все
 * обсуждения опустеют, не уронив при этом ни одного запроса: ошибки не будет, будет пустота.
 */
class CommentSourcePlatformDefaultTest
{
    private static final String CHANGELOG_PATH =
            "db/changelog/changes/008-comment-source-platform.yaml";

    @Test
    void defaultSourcePlatformMatchesMigrationBackfill() throws IOException
    {
        final String changelog = readChangelog();
        final String expected = Comment.DEFAULT_SOURCE_PLATFORM.name();

        // Кавычки вокруг значения не обязательны для YAML, поэтому принимаются оба написания.
        assertThat(changelog)
                .as("бэкфилл миграции 008 должен совпадать с Comment.DEFAULT_SOURCE_PLATFORM")
                .containsAnyOf(
                        "defaultValue: " + expected,
                        "defaultValue: '" + expected + "'",
                        "defaultValue: \"" + expected + "\""
                );
    }

    /**
     * Колонка читается Hibernate как {@code EnumType.STRING}, поэтому бэкфилл обязан быть
     * именем константы перечисления: произвольная строка свалила бы выдачу при чтении, а не
     * при записи, — то есть на живом трафике, а не на миграции.
     */
    @Test
    void migrationBackfillIsKnownPlatform()
    {
        assertThat(SourcePlatform.valueOf(Comment.DEFAULT_SOURCE_PLATFORM.name()))
                .isEqualTo(Comment.DEFAULT_SOURCE_PLATFORM);
    }

    private String readChangelog() throws IOException
    {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(CHANGELOG_PATH))
        {
            assertThat(stream).as("changelog %s не найден", CHANGELOG_PATH).isNotNull();

            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
