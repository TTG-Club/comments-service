package club.ttg.comment.service;

import club.ttg.comment.dto.request.CreateCommentRequest;
import club.ttg.comment.mapper.CommentMapperImpl;
import club.ttg.comment.model.Comment;
import club.ttg.comment.model.CommentStatus;
import club.ttg.comment.model.SourcePlatform;
import club.ttg.comment.repository.CommentComplaintRepository;
import club.ttg.comment.repository.CommentRepository;
import club.ttg.comment.repository.PageUrlAliasRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Запросы по прежнему адресу записи каталога читают и пишут обсуждение её текущего адреса.
 */
class CommentServiceUrlAliasTest
{
    private CommentRepository commentRepository;
    private PageUrlAliasRepository aliasRepository;
    private CommentService commentService;

    @BeforeEach
    void setUp()
    {
        commentRepository = mock(CommentRepository.class);
        aliasRepository = mock(PageUrlAliasRepository.class);
        commentService = new CommentService(
                commentRepository,
                mock(CommentComplaintRepository.class),
                new CommentMapperImpl(),
                mock(CommentRateLimitService.class),
                new PageUrlResolver(aliasRepository)
        );

        when(aliasRepository.findCurrentUrl(any(), anyString(), anyString())).thenReturn(Optional.empty());
        when(aliasRepository.findCurrentUrl(SourcePlatform.SITE_5E24, "spells", "/spells/fireball-phb"))
                .thenReturn(Optional.of("/spells/fireball-ph"));
        when(commentRepository.save(any(Comment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(commentRepository.findVisibleRootComments(any(), any(), any(), any())).thenReturn(Page.empty());
    }

    @Test
    void rootCommentsByOldUrlReadCurrentUrl()
    {
        // Без платформы — значение по умолчанию (сайт 2024), регистр и пробелы нормализуются.
        commentService.getRootComments(null, "Spells", " /Spells/Fireball-PHB ", PageRequest.of(0, 10));

        verify(commentRepository).findVisibleRootComments(
                eq(SourcePlatform.SITE_5E24), eq("spells"), eq("/spells/fireball-ph"), any());
    }

    @Test
    void countAndLatestByOldUrlReadCurrentUrl()
    {
        commentService.getCommentCount(SourcePlatform.SITE_5E24, "spells", "/spells/fireball-phb");
        commentService.getLatestComment(SourcePlatform.SITE_5E24, "spells", "/spells/fireball-phb");

        verify(commentRepository).countBySourcePlatformAndSectionAndUrlAndStatus(
                SourcePlatform.SITE_5E24, "spells", "/spells/fireball-ph", CommentStatus.PUBLISHED);
        verify(commentRepository).findFirstBySourcePlatformAndSectionAndUrlAndStatusOrderByCreatedAtDescIdDesc(
                SourcePlatform.SITE_5E24, "spells", "/spells/fireball-ph", CommentStatus.PUBLISHED);
    }

    @Test
    void newCommentByOldUrlIsStoredUnderCurrentUrl()
    {
        final CreateCommentRequest request = new CreateCommentRequest();
        request.setSection("spells");
        request.setUrl("/spells/fireball-phb");
        request.setContent("text");

        commentService.createComment(request, UUID.randomUUID(), "author", false);

        final ArgumentCaptor<Comment> saved = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepository).save(saved.capture());
        assertThat(saved.getValue().getUrl()).isEqualTo("/spells/fireball-ph");
        assertThat(saved.getValue().getSourcePlatform()).isEqualTo(SourcePlatform.SITE_5E24);
    }

    @Test
    void unknownUrlIsKeptAsIs()
    {
        commentService.getRootComments(SourcePlatform.SITE_5E24, "spells", "/spells/magic-missile-ph",
                PageRequest.of(0, 10));

        verify(commentRepository).findVisibleRootComments(
                eq(SourcePlatform.SITE_5E24), eq("spells"), eq("/spells/magic-missile-ph"), any());
    }

    @Test
    void otherPlatformsAreNotResolved()
    {
        final PageUrlAliasRepository untouched = mock(PageUrlAliasRepository.class);
        final PageUrlResolver resolver = new PageUrlResolver(untouched);

        assertThat(resolver.resolve(SourcePlatform.SITE_5E14, "spells", "/spells/fireball-phb"))
                .isEqualTo("/spells/fireball-phb");
        assertThat(resolver.resolve(SourcePlatform.VTTG, "spells", "/spells/fireball-phb"))
                .isEqualTo("/spells/fireball-phb");
        verifyNoInteractions(untouched);
    }
}
