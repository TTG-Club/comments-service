package club.ttg.comment.exception;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler
{
    @ExceptionHandler(EntityNotFoundException.class)
    public ProblemDetail handleEntityNotFound(final EntityNotFoundException ex)
    {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(CommentAccessDeniedException.class)
    public ProblemDetail handleAccessDenied(final CommentAccessDeniedException ex)
    {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(CommentStateException.class)
    public ProblemDetail handleCommentState(final CommentStateException ex)
    {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    /**
     * Нарушение ограничения БД (например, гонка двойного дизлайка бьётся об уникальный
     * индекс uq_complaints_comment_author) — это конфликт состояния, а не 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(final DataIntegrityViolationException ex)
    {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Конфликт: нарушение ограничения целостности");
    }

    /**
     * Антиспам-лимит. Кроме 429 отдаётся стандартный {@code Retry-After} и дублирующее его
     * поле {@code retryAfterSeconds} в теле — фронту удобнее читать из JSON, чтобы показать
     * обратный отсчёт. {@code blocked} различает «не выдержан интервал» и «бан за серию».
     */
    @ExceptionHandler(CommentRateLimitException.class)
    public ResponseEntity<ProblemDetail> handleRateLimit(final CommentRateLimitException ex)
    {
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS,
                ex.getMessage()
        );
        problem.setProperty("retryAfterSeconds", ex.getRetryAfterSeconds());
        problem.setProperty("blocked", ex.isBlocked());

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(final IllegalArgumentException ex)
    {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(final MethodArgumentNotValidException ex)
    {
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Ошибка валидации запроса"
        );

        final Map<String, String> errors = new LinkedHashMap<>();
        for (final FieldError fieldError : ex.getBindingResult().getFieldErrors())
        {
            errors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        problem.setProperty("errors", errors);

        return problem;
    }
}
