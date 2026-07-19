package club.ttg.comment.service;

import club.ttg.comment.config.RateLimitConfig;
import club.ttg.comment.dto.response.CommentResponse;
import club.ttg.comment.mapper.CommentMapperImpl;
import club.ttg.comment.model.Comment;
import club.ttg.comment.model.CommentStatus;
import club.ttg.comment.repository.CommentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        // Прямых опубликованных ответов у корня не осталось: replyA скрыт баном и считается
        // теперь надгробием, а не ответом.
        assertThat(reload(root).getReplyCount()).isZero();
        // А вот чужой replyC под скрытыми узлами жив и остаётся в числе потомков корня:
        // надгробия проницаемы для счётчиков, бан одного автора не уносит чужую ветку.
        assertThat(reload(root).getTotalReplyCount()).isEqualTo(1);
        // Оба скрытых узла держат ветку видимой — по totalReplyCount > 0 они отдаются
        // надгробиями, а не выпадают из выдачи вместе с replyC.
        assertThat(reload(replyA).getTotalReplyCount()).isEqualTo(1);
        assertThat(reload(replyB).getTotalReplyCount()).isEqualTo(1);

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
     * Точечное восстановление модератором: удаление узла и его возврат должны привести счётчики
     * ровно к исходным значениям. Пока узел удалён, его опубликованное поддерево остаётся
     * в счётчиках предков — оно видимо под надгробием, из счёта уходит только сам узел.
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

        // Из счётчиков корня уходит только сам replyA; replyB остаётся под надгробием.
        assertThat(reload(root).getReplyCount()).isZero();
        assertThat(reload(root).getTotalReplyCount()).isEqualTo(1);
        // Собственные счётчики надгробия живут дальше — по totalReplyCount > 0 оно видимо.
        assertThat(reload(replyA).getReplyCount()).isEqualTo(1);
        assertThat(reload(replyA).getTotalReplyCount()).isEqualTo(1);

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
     * Удаление ответа под надгробием: дельта проходит сквозь удалённого родителя и хоронит
     * надгробие (totalReplyCount падает до нуля — показывать больше нечего). Восстановление
     * родителя после этого возвращает только его самого: удалённый replyB не воскресает
     * и в счётчики не попадает.
     */
    @Test
    void deletingLastReplyUnderTombstoneBuriesItAndRestoreBringsOnlyTheNode()
    {
        final Comment root = save(comment(otherAuthorId, null, CommentStatus.PUBLISHED, "корень"));
        final Comment replyA = save(comment(otherAuthorId, root.getId(), CommentStatus.PUBLISHED, "ответ A"));
        final Comment replyB = save(comment(otherAuthorId, replyA.getId(), CommentStatus.PUBLISHED, "ответ B"));

        commentRepository.recalculateReplyCounts();
        commentRepository.recalculateTotalReplyCounts();
        entityManager.clear();

        commentService.deleteComment(replyA.getId(), otherAuthorId, true);
        entityManager.clear();

        // replyB удаляют, пока его родитель — надгробие: минус один доходит и до строки
        // родителя (надгробие хоронится), и до корня.
        commentService.deleteComment(replyB.getId(), otherAuthorId, true);
        entityManager.clear();
        assertThat(reload(replyA).getReplyCount()).isZero();
        assertThat(reload(replyA).getTotalReplyCount()).isZero();
        assertThat(reload(root).getTotalReplyCount()).isZero();

        commentService.restoreComment(replyA.getId(), true);
        entityManager.clear();

        // Вернулся только replyA: удалённый replyB в счёт не идёт ни у него, ни у корня.
        assertThat(reload(root).getReplyCount()).isEqualTo(1);
        assertThat(reload(root).getTotalReplyCount()).isEqualTo(1);
        assertThat(reload(replyA).getReplyCount()).isZero();
        assertThat(reload(replyA).getTotalReplyCount()).isZero();
        assertThat(statusOf(replyB)).isEqualTo(CommentStatus.DELETED);
    }

    /**
     * Сквозной сценарий надгробия: удалённый корень с живым ответом остаётся в публичной
     * выдаче замаскированным узлом, его ответы продолжают отдаваться; после удаления
     * последнего ответа надгробие уходит из выдачи само.
     */
    @Test
    void deletedCommentWithRepliesStaysVisibleAsTombstoneUntilBranchDies()
    {
        final Comment root = save(comment(otherAuthorId, null, CommentStatus.PUBLISHED, "корень"));
        final Comment reply = save(comment(bannedAuthorId, root.getId(), CommentStatus.PUBLISHED, "полезный ответ"));

        commentRepository.recalculateReplyCounts();
        commentRepository.recalculateTotalReplyCounts();
        entityManager.clear();

        commentService.deleteComment(root.getId(), otherAuthorId, false);
        entityManager.clear();

        final Page<CommentResponse> roots =
                commentService.getRootComments("blog", "/posts/x", PageRequest.of(0, 20));

        // Корень остался в выдаче надгробием: статус виден, содержимое — нет.
        assertThat(roots.getContent()).singleElement().satisfies(tombstone -> {
            assertThat(tombstone.getId()).isEqualTo(root.getId());
            assertThat(tombstone.getStatus()).isEqualTo(CommentStatus.DELETED);
            assertThat(tombstone.getContent()).isNull();
            assertThat(tombstone.getAuthorId()).isNull();
            assertThat(tombstone.getAuthorName()).isNull();
        });

        // Ветка под надгробием живёт: ответ отдаётся как обычно.
        assertThat(commentService.getReplies(root.getId()))
                .singleElement()
                .extracting(CommentResponse::getContent)
                .isEqualTo("полезный ответ");

        // По прямой ссылке надгробие тоже доступно — подъём по parentId не обрывается.
        assertThat(commentService.getComment(root.getId()).getContent()).isNull();

        commentService.deleteComment(reply.getId(), bannedAuthorId, false);
        entityManager.clear();

        // Последний ответ удалён — показывать больше нечего, надгробие ушло из выдачи.
        assertThat(commentService.getRootComments("blog", "/posts/x", PageRequest.of(0, 20))
                .getContent()).isEmpty();
    }

    /**
     * Ради чего надгробие распространили на бан: комментарий заблокированного автора, под
     * которым остались чужие ответы, не должен уносить их с собой. После блокировки он остаётся
     * в выдаче замаскированным узлом, ветка под ним живёт, а разблокировка возвращает его
     * целиком — в отличие от удаления, которое снимается только восстановлением узла.
     */
    @Test
    void hiddenByBanCommentWithRepliesStaysVisibleAsTombstone()
    {
        final Comment root = save(comment(bannedAuthorId, null, CommentStatus.PUBLISHED, "от забаненного"));
        save(comment(otherAuthorId, root.getId(), CommentStatus.PUBLISHED, "чужой полезный ответ"));

        commentService.hideCommentsByAuthor(bannedAuthorId);
        entityManager.clear();

        assertThat(commentService.getRootComments("blog", "/posts/x", PageRequest.of(0, 20)).getContent())
                .singleElement()
                .satisfies(tombstone -> {
                    assertThat(tombstone.getId()).isEqualTo(root.getId());
                    // Наружу — DELETED: то, что автора забанили, публичная выдача не сообщает.
                    assertThat(tombstone.getStatus()).isEqualTo(CommentStatus.DELETED);
                    assertThat(tombstone.getContent()).isNull();
                    assertThat(tombstone.getAuthorId()).isNull();
                    assertThat(tombstone.getAuthorName()).isNull();
                });

        // В базе статус прежний — иначе разблокировка не нашла бы, что возвращать.
        assertThat(statusOf(root)).isEqualTo(CommentStatus.HIDDEN_BY_BAN);

        // Ветка под надгробием живёт, и по прямой ссылке узел доступен: подъём по parentId
        // от ответа к корню не обрывается.
        assertThat(commentService.getReplies(root.getId()))
                .singleElement()
                .extracting(CommentResponse::getContent)
                .isEqualTo("чужой полезный ответ");
        assertThat(commentService.getComment(root.getId()).getContent()).isNull();

        commentService.restoreCommentsByAuthor(bannedAuthorId);
        entityManager.clear();

        assertThat(commentService.getRootComments("blog", "/posts/x", PageRequest.of(0, 20)).getContent())
                .singleElement()
                .extracting(CommentResponse::getContent)
                .isEqualTo("от забаненного");
    }

    /**
     * Обратная половина правила: без живых ответов скрывать нечего — комментарий забаненного
     * уходит из выдачи совсем, а не оставляет за собой пустое надгробие.
     */
    @Test
    void hiddenByBanCommentWithoutRepliesDisappearsFromFeed()
    {
        final Comment lonely = save(comment(bannedAuthorId, null, CommentStatus.PUBLISHED, "без ответов"));

        commentService.hideCommentsByAuthor(bannedAuthorId);
        entityManager.clear();

        assertThat(commentService.getRootComments("blog", "/posts/x", PageRequest.of(0, 20)).getContent())
                .isEmpty();
        assertThatThrownBy(() -> commentService.getComment(lonely.getId()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    /**
     * Надгробие бана держится на живых ответах ровно так же, как надгробие удаления: когда
     * удаляют последний ответ под ним, показывать больше нечего и оно уходит из выдачи.
     */
    @Test
    void banTombstoneDisappearsWhenLastReplyIsDeleted()
    {
        final Comment root = save(comment(bannedAuthorId, null, CommentStatus.PUBLISHED, "от забаненного"));
        final Comment reply = save(comment(otherAuthorId, root.getId(), CommentStatus.PUBLISHED, "чужой ответ"));

        commentService.hideCommentsByAuthor(bannedAuthorId);
        entityManager.clear();

        commentService.deleteComment(reply.getId(), otherAuthorId, false);
        entityManager.clear();

        assertThat(commentService.getRootComments("blog", "/posts/x", PageRequest.of(0, 20)).getContent())
                .isEmpty();
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
