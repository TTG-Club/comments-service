package club.ttg.comment.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "comments")
public class Comment
{
    /**
     * Платформа-источник по умолчанию. До появления поля в сервис писал только сайт 2024
     * (core-app), поэтому им же заполнены существующие строки — и то же значение
     * подставляется запросам без поля.
     * <p>
     * Совпадение обязательно: уже задеплоенный фронт поле не присылает, и если бы фолбэк
     * разошёлся с бэкфиллом миграции, он спрашивал бы один ключ, а строки лежали бы под
     * другим — обсуждения на проде опустели бы сразу после выката сервиса.
     */
    public static final SourcePlatform DEFAULT_SOURCE_PLATFORM = SourcePlatform.SITE_5E24;

    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Платформа, на которой живёт обсуждение. Вместе с section и url образует ключ треда:
     * разделы у сайтов совпадают (spells, bestiary), а слаги сущностей приходят из разных
     * бэкендов — без этого поля принадлежность треда решалась бы совпадением слагов.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_platform", nullable = false, length = 50)
    private SourcePlatform sourcePlatform;

    @Column(name = "section", nullable = false, length = 100)
    private String section;

    @Column(name = "url", nullable = false, length = 255)
    private String url;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(name = "author_name_snapshot", length = 255)
    private String authorNameSnapshot;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private CommentStatus status;

    @Column(name = "reply_count", nullable = false)
    private Integer replyCount = 0;

    @Column(name = "total_reply_count", nullable = false)
    private Integer totalReplyCount = 0;

    @Column(name = "dislike_count", nullable = false)
    private Integer dislikeCount = 0;

    @Column(name = "edited_at")
    private OffsetDateTime editedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public boolean isDeleted()
    {
        return status == CommentStatus.DELETED;
    }

    /**
     * Статус, который скрывает содержимое, но не обрывает ветку: мягкое удаление и скрытие при
     * бане автора. Узел в таком статусе отдаётся публично надгробием, если под ним остались
     * опубликованные ответы, и проницаем для счётчиков — по обоим правилам эти два случая
     * неразличимы, поэтому и предикат один. Остальные скрывающие статусы (REJECTED, SPAM,
     * PENDING_MODERATION) уносят поддерево из выдачи вместе с собой.
     * <p>
     * От {@link #isDeleted()} отличается назначением: там, где важна именно отмена удаления
     * (восстановление модератором, «уже удалён»), скрытие баном приравнивать нельзя — снимать
     * его должна разблокировка автора.
     */
    public boolean isTombstoneStatus()
    {
        return status == CommentStatus.DELETED || status == CommentStatus.HIDDEN_BY_BAN;
    }

    public void markAsEdited()
    {
        editedAt = OffsetDateTime.now();
    }

    /**
     * Мягкое удаление. Если под комментарием остались опубликованные ответы
     * ({@code totalReplyCount > 0}), в публичных выдачах он превращается в надгробие — узел
     * без текста и автора, который держит ветку ответов видимой; без живых потомков он из
     * выдач уходит совсем. Строка и её текст в любом случае остаются в базе.
     * <p>
     * Текст намеренно не затирается: сервис ничего не удаляет безвозвратно. Полный текст
     * удалённого комментария остаётся доступен модератору и администратору в списках
     * {@code /api/v1/comments/moderation} — публично он не отдаётся нигде, надгробие
     * отдаётся без него.
     */
    public void markAsDeleted()
    {
        status = CommentStatus.DELETED;
        deletedAt = OffsetDateTime.now();
    }

    /**
     * Возврат мягко удалённого комментария в выдачу модератором. {@code deletedAt} очищается:
     * строка больше не удалена, и повторное удаление проставит новую отметку времени, а не
     * оставит старую.
     * <p>
     * Ветку ответов трогать не нужно — они и так остались {@link CommentStatus#PUBLISHED},
     * а невидимыми их делал обход дерева, начинающийся с корня. Возврат узла возвращает
     * и всё поддерево под ним.
     */
    public void markAsRestored()
    {
        status = CommentStatus.PUBLISHED;
        deletedAt = null;
    }

    @Override
    public boolean equals(final Object o)
    {
        if (this == o)
        {
            return true;
        }

        if (!(o instanceof Comment comment))
        {
            return false;
        }

        return Objects.equals(id, comment.id);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(id);
    }
}