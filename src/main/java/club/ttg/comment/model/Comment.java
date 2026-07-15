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
    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

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

    public void markAsEdited()
    {
        editedAt = OffsetDateTime.now();
    }

    public void markAsDeleted()
    {
        status = CommentStatus.DELETED;
        deletedAt = OffsetDateTime.now();
        content = "";
    }

    public void incrementReplyCount()
    {
        if (replyCount == null)
        {
            replyCount = 0;
        }

        replyCount++;
    }

    public void decrementReplyCount()
    {
        if (replyCount == null || replyCount == 0)
        {
            replyCount = 0;
            return;
        }

        replyCount--;
    }

    public void incrementDislikeCount()
    {
        if (dislikeCount == null)
        {
            dislikeCount = 0;
        }

        dislikeCount++;
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