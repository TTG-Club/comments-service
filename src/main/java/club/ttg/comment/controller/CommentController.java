package club.ttg.comment.controller;

import club.ttg.comment.dto.request.CreateCommentRequest;
import club.ttg.comment.dto.request.UpdateCommentRequest;
import club.ttg.comment.dto.response.CommentResponse;
import club.ttg.comment.service.CommentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/comments")
@RequiredArgsConstructor
public class CommentController
{
    private final CommentService commentService;

    @GetMapping
    public Page<CommentResponse> getRootComments(
            @RequestParam final String section,
            @RequestParam final String url,
            @PageableDefault(size = 20) final Pageable pageable
    )
    {
        return commentService.getRootComments(section, url, pageable);
    }

    @GetMapping("/{parentId}/replies")
    public List<CommentResponse> getReplies(@PathVariable final UUID parentId)
    {
        return commentService.getReplies(parentId);
    }

    @GetMapping("/count")
    public ResponseEntity<Long> getCommentCount(
            @RequestParam final String section,
            @RequestParam final String url
    )
    {
        return ResponseEntity.ok(commentService.getCommentCount(section, url));
    }

    @PostMapping
    public ResponseEntity<CommentResponse> createComment(
            @Valid @RequestBody final CreateCommentRequest request,
            @AuthenticationPrincipal final Jwt jwt
    )
    {
        final CommentResponse response = commentService.createComment(
                request,
                extractUserId(jwt),
                extractUserName(jwt)
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{parentId}/replies")
    public ResponseEntity<CommentResponse> createReply(
            @PathVariable final UUID parentId,
            @Valid @RequestBody final CreateCommentRequest request,
            @AuthenticationPrincipal final Jwt jwt
    )
    {
        final CommentResponse response = commentService.createReply(
                parentId,
                request,
                extractUserId(jwt),
                extractUserName(jwt)
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{commentId}")
    public CommentResponse updateComment(
            @PathVariable final UUID commentId,
            @Valid @RequestBody final UpdateCommentRequest request,
            @AuthenticationPrincipal final Jwt jwt
    )
    {
        return commentService.updateComment(
                commentId,
                extractUserId(jwt),
                request
        );
    }

    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> deleteComment(
            @PathVariable final UUID commentId,
            @AuthenticationPrincipal final Jwt jwt
    )
    {
        commentService.deleteComment(commentId, extractUserId(jwt));
        return ResponseEntity.noContent().build();
    }

    private UUID extractUserId(final Jwt jwt)
    {
        return UUID.fromString(jwt.getSubject());
    }

    private String extractUserName(final Jwt jwt)
    {
        final String preferredUsername = jwt.getClaimAsString("preferred_username");
        if (preferredUsername != null && !preferredUsername.isBlank())
        {
            return preferredUsername;
        }

        final String username = jwt.getClaimAsString("username");
        if (username != null && !username.isBlank())
        {
            return username;
        }

        return jwt.getSubject();
    }
}