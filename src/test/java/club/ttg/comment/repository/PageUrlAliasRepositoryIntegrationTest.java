package club.ttg.comment.repository;

import club.ttg.comment.model.Comment;
import club.ttg.comment.model.CommentStatus;
import club.ttg.comment.model.SourcePlatform;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Хранение прежних адресов и перенос комментариев на настоящем PostgreSQL: {@code ON CONFLICT}
 * и {@code UPDATE ... FROM} — синтаксис PostgreSQL. Схему накатывает Liquibase.
 * <p>
 * Без Docker тест пропускается — «пропущен» и «пройден» в отчёте не одно и то же.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PageUrlAliasRepository.class)
class PageUrlAliasRepositoryIntegrationTest
{
    private static final SourcePlatform SITE = SourcePlatform.SITE_5E24;
    private static final Instant T1 = Instant.parse("2026-11-14T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-14T11:00:00Z");

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(final DynamicPropertyRegistry registry)
    {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private PageUrlAliasRepository aliasRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp()
    {
        commentRepository.deleteAllInBatch();
        jdbc.update("delete from comment.page_url_aliases");
    }

    @Test
    void chainCollapsesToCurrentUrl()
    {
        aliasRepository.remember(SITE, "spells", "/spells/a", "/spells/b", T1);
        aliasRepository.remember(SITE, "spells", "/spells/b", "/spells/c", T2);

        assertThat(aliasRepository.findCurrentUrl(SITE, "spells", "/spells/a")).contains("/spells/c");
        assertThat(aliasRepository.findCurrentUrl(SITE, "spells", "/spells/b")).contains("/spells/c");
        assertThat(aliasRepository.findCurrentUrl(SITE, "spells", "/spells/c")).isEmpty();
        assertThat(aliasRepository.findLatestChangedAt(SITE)).contains(T2);
    }

    @Test
    void renameBackRemovesCurrentUrlFromAliases()
    {
        aliasRepository.remember(SITE, "spells", "/spells/a", "/spells/b", T1);
        aliasRepository.remember(SITE, "spells", "/spells/b", "/spells/a", T2);

        assertThat(aliasRepository.findCurrentUrl(SITE, "spells", "/spells/a")).isEmpty();
        assertThat(aliasRepository.findCurrentUrl(SITE, "spells", "/spells/b")).contains("/spells/a");
    }

    @Test
    void repeatedChangeIsIdempotent()
    {
        aliasRepository.remember(SITE, "spells", "/spells/a", "/spells/b", T1);
        aliasRepository.remember(SITE, "spells", "/spells/a", "/spells/b", T1);

        assertThat(jdbc.queryForObject("select count(*) from comment.page_url_aliases", Integer.class)).isOne();
        assertThat(aliasRepository.findCurrentUrl(SITE, "spells", "/spells/a")).contains("/spells/b");
    }

    @Test
    void commentsMoveToCurrentUrlOnceAndOnlyOnTheirPlatformAndSection()
    {
        final Comment root = save(comment(SITE, "spells", "/spells/a", null));
        final Comment reply = save(comment(SITE, "spells", "/spells/a", root.getId()));
        final Comment midChain = save(comment(SITE, "spells", "/spells/b", null));
        final Comment otherSite = save(comment(SourcePlatform.SITE_5E14, "spells", "/spells/a", null));
        final Comment otherSection = save(comment(SITE, "feats", "/spells/a", null));
        final Comment unrelated = save(comment(SITE, "spells", "/spells/z", null));

        aliasRepository.remember(SITE, "spells", "/spells/a", "/spells/b", T1);
        aliasRepository.remember(SITE, "spells", "/spells/b", "/spells/c", T2);

        assertThat(aliasRepository.moveCommentsToCurrentUrls(SITE)).isEqualTo(3);
        assertThat(aliasRepository.moveCommentsToCurrentUrls(SITE)).isZero();

        entityManager.clear();
        assertThat(urlOf(root)).isEqualTo("/spells/c");
        assertThat(urlOf(reply)).isEqualTo("/spells/c");
        assertThat(urlOf(midChain)).isEqualTo("/spells/c");
        assertThat(urlOf(otherSite)).isEqualTo("/spells/a");
        assertThat(urlOf(otherSection)).isEqualTo("/spells/a");
        assertThat(urlOf(unrelated)).isEqualTo("/spells/z");
    }

    private String urlOf(final Comment comment)
    {
        return commentRepository.findById(comment.getId()).orElseThrow().getUrl();
    }

    private Comment save(final Comment comment)
    {
        final Comment saved = commentRepository.saveAndFlush(comment);
        entityManager.clear();
        return saved;
    }

    private static Comment comment(
            final SourcePlatform platform,
            final String section,
            final String url,
            final UUID parentId
    )
    {
        final Comment comment = new Comment();
        comment.setSourcePlatform(platform);
        comment.setSection(section);
        comment.setUrl(url);
        comment.setParentId(parentId);
        comment.setAuthorId(UUID.randomUUID());
        comment.setContent("text");
        comment.setStatus(CommentStatus.PUBLISHED);
        return comment;
    }
}
