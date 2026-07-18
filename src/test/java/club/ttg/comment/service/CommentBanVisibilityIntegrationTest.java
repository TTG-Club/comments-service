package club.ttg.comment.service;

import club.ttg.comment.config.RateLimitConfig;
import club.ttg.comment.mapper.CommentMapperImpl;
import club.ttg.comment.model.Comment;
import club.ttg.comment.model.CommentStatus;
import club.ttg.comment.repository.CommentRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет смену статусов и следующий за ней пересчёт счётчиков на настоящем PostgreSQL:
 * массовое скрытие/восстановление при бане и точечное восстановление удалённого комментария
 * модератором. Рекурсивный CTE и {@code UPDATE ... FROM} — синтаксис PostgreSQL, на встроенной
 * БД их не проверить. Схему накатывает Liquibase теми же миграциями, что и в проде.
 * <p>
 * Без Docker тест пропускается (Testcontainers не сможет поднять контейнер) — «пропущен» и
 * «пройден» в отчёте не одно и то же.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CommentService.class, CommentMapperImpl.class, CommentRateLimitService.class, RateLimitConfig.class})
class CommentBanVisibilityIntegrationTest
{
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
    private CommentService commentService;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private EntityManager entityManager;

    private UUID bannedAuthorId;
    private UUID otherAuthorId;

    @BeforeEach
    void setUp()
    {
        commentRepository.deleteAllInBatch();
        bannedAuthorId = UUID.randomUUID();
        otherAuthorId = UUID.randomUUID();
    }

    @Test
    void hideTranslatesOnlyPublishedCommentsOfGivenAuthor()
    {
        final Comment banned = save(comment(bannedAuthorId, null, CommentStatus.PUBLISHED, "от забаненного"));
        final Comment foreign = save(comment(otherAuthorId, null, CommentStatus.PUBLISHED, "чужой"));
        final Comment rejected = save(comment(bannedAuthorId, null, CommentStatus.REJECTED, "снят модератором"));

        assertThat(commentService.hideCommentsByAuthor(bannedAuthorId)).isEqualTo(1);

        assertThat(statusOf(banned)).isEqualTo(CommentStatus.HIDDEN_BY_BAN);
        // Чужие комментарии не трогаются, решения модератора не перезаписываются.
        assertThat(statusOf(foreign)).isEqualTo(CommentStatus.PUBLISHED);
        assertThat(statusOf(rejected)).isEqualTo(CommentStatus.REJECTED);
    }

    /**
     * Скрытие меняет только статус: ни текст, ни автор, ни время создания не трогаются —
     * разблокировка возвращает комментарий ровно в том виде, в каком он был.
     */
    @Test
    void hidePreservesCommentContent()
    {
        final Comment banned = save(comment(bannedAuthorId, null, CommentStatus.PUBLISHED, "важный текст"));

        commentService.hideCommentsByAuthor(bannedAuthorId);

        assertThat(reload(banned).getContent()).isEqualTo("важный текст");

        commentService.restoreCommentsByAuthor(bannedAuthorId);

        assertThat(reload(banned).getContent()).isEqualTo("важный текст");
        assertThat(statusOf(banned)).isEqualTo(CommentStatus.PUBLISHED);
    }

    @Test
    void restoreDoesNotResurrectSelfDeletedComments()
    {
        final Comment published = save(comment(bannedAuthorId, null, CommentStatus.PUBLISHED, "живой"));

        final Comment selfDeleted = comment(bannedAuthorId, null, CommentStatus.PUBLISHED, "удалил сам");
        selfDeleted.markAsDeleted();
        save(selfDeleted);

        commentService.hideCommentsByAuthor(bannedAuthorId);
        assertThat(commentService.restoreCommentsByAuthor(bannedAuthorId)).isEqualTo(1);

        assertThat(statusOf(published)).isEqualTo(CommentStatus.PUBLISHED);
        // Комментарий, удалённый пользователем до бана, остаётся удалённым.
        assertThat(statusOf(selfDeleted)).isEqualTo(CommentStatus.DELETED);
    }

    @Test
    void hideAndRestoreAreIdempotent()
    {
        save(comment(bannedAuthorId, null, CommentStatus.PUBLISHED, "первый"));
        save(comment(bannedAuthorId, null, CommentStatus.PUBLISHED, "второй"));

        assertThat(commentService.hideCommentsByAuthor(bannedAuthorId)).isEqualTo(2);
        assertThat(commentService.hideCommentsByAuthor(bannedAuthorId)).isZero();

        assertThat(commentService.restoreCommentsByAuthor(bannedAuthorId)).isEqualTo(2);
        assertThat(commentService.restoreCommentsByAuthor(bannedAuthorId)).isZero();
    }

    @Test
    void hideByAuthorWithoutCommentsAffectsNothing()
    {
        save(comment(otherAuthorId, null, CommentStatus.PUBLISHED, "чужой"));

        assertThat(commentService.hideCommentsByAuthor(bannedAuthorId)).isZero();
        assertThat(commentService.restoreCommentsByAuthor(bannedAuthorId)).isZero();
    }

    /**
     * Ключевой сценарий: комментарии забаненного вложены друг в друга, поэтому дельты задвоились
     * бы. Пересчёт с нуля должен вернуть счётчики ровно к исходным значениям после
     * скрытия и последующего восстановления.
     * <p>
     * Дерево: root (чужой) → replyA (забаненный) → replyB (забаненный) → replyC (чужой).
     */
    @Test
    void countersReturnToOriginalValuesAfterHideAndRestore()
    {
        final Comment root = save(comment(otherAuthorId, null, CommentStatus.PUBLISHED, "корень"));
        final Comment replyA = save(comment(bannedAuthorId, root.getId(), CommentStatus.PUBLISHED, "ответ A"));
        final Comment replyB = save(comment(bannedAuthorId, replyA.getId(), CommentStatus.PUBLISHED, "ответ B"));
        final Comment replyC = save(comment(otherAuthorId, replyB.getId(), CommentStatus.PUBLISHED, "ответ C"));

        // Приводим счётчики к согласованному состоянию тем же пересчётом, что и в проде.
        commentRepository.recalculateReplyCounts();
        commentRepository.recalculateTotalReplyCounts();
        entityManager.clear();

        assertThat(reload(root).getReplyCount()).isEqualTo(1);
        assertThat(reload(root).getTotalReplyCount()).isEqualTo(3);

        commentService.hideCommentsByAuthor(bannedAuthorId);

        // Ветка под скрытым узлом уходит из счётчиков корня целиком, без задвоения.
        assertThat(reload(root).getReplyCount()).isZero();
        assertThat(reload(root).getTotalReplyCount()).isZero();

        commentService.restoreCommentsByAuthor(bannedAuthorId);

        assertThat(reload(root).getReplyCount()).isEqualTo(1);
        assertThat(reload(root).getTotalReplyCount()).isEqualTo(3);
        assertThat(reload(replyA).getReplyCount()).isEqualTo(1);
        assertThat(reload(replyA).getTotalReplyCount()).isEqualTo(2);
        assertThat(reload(replyB).getReplyCount()).isEqualTo(1);
        assertThat(reload(replyB).getTotalReplyCount()).isEqualTo(1);
        assertThat(reload(replyC).getTotalReplyCount()).isZero();
    }

    /**
     * Комментарий, удалённый пользователем самостоятельно, не возвращается в счётчики предков
     * после разблокировки: рекурсия идёт только по PUBLISHED-узлам.
     */
    @Test
    void selfDeletedRepliesStayOutOfCountersAfterRestore()
    {
        final Comment root = save(comment(bannedAuthorId, null, CommentStatus.PUBLISHED, "корень"));
        save(comment(otherAuthorId, root.getId(), CommentStatus.PUBLISHED, "живой ответ"));

        final Comment deletedReply = comment(otherAuthorId, root.getId(), CommentStatus.PUBLISHED, "удалённый ответ");
        deletedReply.markAsDeleted();
        save(deletedReply);

        commentService.hideCommentsByAuthor(bannedAuthorId);
        commentService.restoreCommentsByAuthor(bannedAuthorId);

        assertThat(reload(root).getReplyCount()).isEqualTo(1);
        assertThat(reload(root).getTotalReplyCount()).isEqualTo(1);
    }

    /**
     * Точечное восстановление модератором: удаление ветки и её возврат должны привести счётчики
     * ровно к исходным значениям. Восстанавливается только сам узел — ответы под ним остались
     * PUBLISHED и снова попадают в счёт вместе с ним.
     * <p>
     * Дерево: root → replyA → replyB, удаляется и возвращается replyA.
     */
    @Test
    void countersReturnToOriginalValuesAfterDeleteAndRestore()
    {
        final Comment root = save(comment(otherAuthorId, null, CommentStatus.PUBLISHED, "корень"));
        final Comment replyA = save(comment(otherAuthorId, root.getId(), CommentStatus.PUBLISHED, "ответ A"));
        final Comment replyB = save(comment(otherAuthorId, replyA.getId(), CommentStatus.PUBLISHED, "ответ B"));

        commentRepository.recalculateReplyCounts();
        commentRepository.recalculateTotalReplyCounts();
        entityManager.clear();

        assertThat(reload(root).getReplyCount()).isEqualTo(1);
        assertThat(reload(root).getTotalReplyCount()).isEqualTo(2);

        commentService.deleteComment(replyA.getId(), otherAuthorId, true);
        entityManager.clear();

        // Вся ветка уходит из счётчиков корня.
        assertThat(reload(root).getReplyCount()).isZero();
        assertThat(reload(root).getTotalReplyCount()).isZero();

        commentService.restoreComment(replyA.getId(), true);
        entityManager.clear();

        assertThat(statusOf(replyA)).isEqualTo(CommentStatus.PUBLISHED);
        assertThat(reload(replyA).getDeletedAt()).isNull();
        assertThat(reload(root).getReplyCount()).isEqualTo(1);
        assertThat(reload(root).getTotalReplyCount()).isEqualTo(2);
        assertThat(reload(replyA).getReplyCount()).isEqualTo(1);
        assertThat(reload(replyA).getTotalReplyCount()).isEqualTo(1);
        assertThat(reload(replyB).getTotalReplyCount()).isZero();
    }

    /**
     * Ради чего пересчёт предпочтён симметричной дельте: пока узел удалён, ответы под ним могут
     * удалять, и сохранённый в удалённой строке totalReplyCount устаревает (обход предков
     * останавливается на первом удалённом и до неё не доходит). Сложение вернуло бы корню
     * поддерево вместе с уже удалённым ответом; пересчёт с нуля даёт согласованные счётчики.
     */
    @Test
    void restoreDoesNotReturnRepliesDeletedWhileNodeWasDeleted()
    {
        final Comment root = save(comment(otherAuthorId, null, CommentStatus.PUBLISHED, "корень"));
        final Comment replyA = save(comment(otherAuthorId, root.getId(), CommentStatus.PUBLISHED, "ответ A"));
        final Comment replyB = save(comment(otherAuthorId, replyA.getId(), CommentStatus.PUBLISHED, "ответ B"));

        commentRepository.recalculateReplyCounts();
        commentRepository.recalculateTotalReplyCounts();
        entityManager.clear();

        commentService.deleteComment(replyA.getId(), otherAuthorId, true);
        entityManager.clear();

        // replyB удаляют, пока его родитель уже удалён: счётчик в строке replyA остаётся прежним.
        commentService.deleteComment(replyB.getId(), otherAuthorId, true);
        entityManager.clear();
        assertThat(reload(replyA).getTotalReplyCount()).isEqualTo(1);

        commentService.restoreComment(replyA.getId(), true);
        entityManager.clear();

        // Вернулся только replyA: удалённый replyB в счёт не идёт ни у него, ни у корня.
        assertThat(reload(root).getReplyCount()).isEqualTo(1);
        assertThat(reload(root).getTotalReplyCount()).isEqualTo(1);
        assertThat(reload(replyA).getReplyCount()).isZero();
        assertThat(reload(replyA).getTotalReplyCount()).isZero();
        assertThat(statusOf(replyB)).isEqualTo(CommentStatus.DELETED);
    }

    private Comment save(final Comment comment)
    {
        final Comment saved = commentRepository.saveAndFlush(comment);
        entityManager.clear();
        return saved;
    }

    private Comment reload(final Comment comment)
    {
        entityManager.clear();
        return commentRepository.findById(comment.getId()).orElseThrow();
    }

    private CommentStatus statusOf(final Comment comment)
    {
        return reload(comment).getStatus();
    }

    private static Comment comment(
            final UUID authorId,
            final UUID parentId,
            final CommentStatus status,
            final String content
    )
    {
        final Comment comment = new Comment();
        comment.setSection("blog");
        comment.setUrl("/posts/x");
        comment.setParentId(parentId);
        comment.setAuthorId(authorId);
        comment.setAuthorNameSnapshot("user");
        comment.setContent(content);
        comment.setStatus(status);
        comment.setReplyCount(0);
        comment.setTotalReplyCount(0);
        comment.setDislikeCount(0);
        return comment;
    }
}
