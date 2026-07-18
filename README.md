# Comments API

REST-сервис комментариев для веб-страниц: древовидные комментарии (корневые + ответы одного уровня), привязка к разделу и URL страницы, аутентификация по JWT (OAuth2 Resource Server).

## Технологический стек

- **Java 21**
- **Spring Boot 4.0.5** — Web MVC, Data JPA, Validation, Security (OAuth2 Resource Server)
- **PostgreSQL** — хранилище данных
- **Liquibase** — миграции схемы БД
- **MapStruct 1.5.5** — маппинг DTO ↔ сущности
- **Lombok**
- **springdoc-openapi** — OpenAPI / Swagger UI
- **Maven** (обёртка `mvnw` в комплекте)

## Возможности

- Постраничная выдача корневых комментариев для пары `section` + `url`.
- Загрузка ответов на конкретный комментарий.
- Подсчёт числа опубликованных комментариев страницы.
- Создание комментариев и ответов, редактирование и «мягкое» удаление (soft delete).
- Автоматический подсчёт числа ответов (`replyCount`) у родителя.
- Проверка прав: редактировать и удалять комментарий может только его автор.
- Нормализация входных данных (`section`/`url` приводятся к нижнему регистру и обрезаются).
- Снимок имени автора на момент создания (`author_name_snapshot`).

## Структура проекта

```
src/main/java/club/ttg/comment/
├── CommentApplication.java          # точка входа Spring Boot
├── controller/CommentController.java # REST-эндпоинты /api/v1/comments
├── service/CommentService.java       # бизнес-логика
├── repository/CommentRepository.java # Spring Data JPA
├── model/                            # Comment, CommentStatus
├── dto/request/                      # CreateCommentRequest, UpdateCommentRequest
├── dto/response/                     # CommentResponse
└── mapper/CommentMapper.java         # MapStruct-маппер

src/main/resources/
├── application.yaml                  # конфигурация
└── db/changelog/                     # миграции Liquibase
```

## Модель данных

Таблица `comment.comments`:

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | UUID | Первичный ключ |
| `section` | VARCHAR(100) | Раздел (нормализуется) |
| `url` | VARCHAR(255) | URL страницы (нормализуется) |
| `parent_id` | UUID | Родительский комментарий (`null` — корневой), FK с `ON DELETE CASCADE` |
| `author_id` | UUID | ID автора (из JWT `sub`) |
| `author_name_snapshot` | VARCHAR(255) | Имя автора на момент создания |
| `content` | TEXT | Текст комментария |
| `status` | VARCHAR(50) | Статус (см. ниже) |
| `reply_count` | INT | Число ответов |
| `edited_at` | TIMESTAMPTZ | Время последнего редактирования |
| `deleted_at` | TIMESTAMPTZ | Время удаления |
| `created_at` / `updated_at` | TIMESTAMPTZ | Служебные метки времени |

**Статусы (`CommentStatus`):** `PUBLISHED`, `DELETED`, `PENDING_MODERATION`, `REJECTED`, `SPAM`.
Выборки возвращают только комментарии со статусом `PUBLISHED`.

## Конфигурация

Приложение читает настройки из переменных окружения (см. `application.yaml`):

| Переменная | Назначение | По умолчанию |
|------------|-----------|--------------|
| `SPRING_DATASOURCE_URL` | JDBC-URL PostgreSQL | — |
| `SPRING_DATASOURCE_USERNAME` | Пользователь БД | — |
| `SPRING_DATASOURCE_PASSWORD` | Пароль БД | — |
| `DB_POOL_SIZE` | Максимум соединений HikariCP | `10` |
| `DB_MIN_IDLE` | Минимум простаивающих соединений | `2` |

JWT-аутентификация настроена на выданный локально Authorization Server:

- `issuer-uri`: `http://localhost:8081`
- `jwk-set-uri`: `http://localhost:8081/oauth2/jwks`

> **Внимание:** файл `local.env` в репозитории содержит реальные учётные данные к БД и секреты. Их следует ротировать и не хранить в системе контроля версий — вынесите значения в локальный, неотслеживаемый файл окружения.

## Запуск

### Требования

- JDK 21
- Доступный экземпляр PostgreSQL
- Работающий OAuth2 Authorization Server, выдающий JWT (для защищённых эндпоинтов)

### Локальный запуск

```bash
# задать переменные окружения (пример; используйте собственные значения)
export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/ttg"
export SPRING_DATASOURCE_USERNAME="ttg-api"
export SPRING_DATASOURCE_PASSWORD="********"

# запуск
./mvnw spring-boot:run
```

На Windows (PowerShell):

```powershell
$env:SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/ttg"
$env:SPRING_DATASOURCE_USERNAME="ttg-api"
$env:SPRING_DATASOURCE_PASSWORD="********"
.\mvnw.cmd spring-boot:run
```

Liquibase применит миграции при старте автоматически (`ddl-auto: none`).

### Сборка

```bash
./mvnw clean package
java -jar target/comment.ttg.club.jar
```

### Тесты

```bash
./mvnw test
```

## API

Базовый путь: `/api/v1/comments`. Все изменяющие данные операции требуют `Bearer`-токен (JWT).

| Метод | Путь | Описание | Авторизация |
|-------|------|----------|-------------|
| `GET` | `/api/v1/comments?section={section}&url={url}` | Корневые комментарии страницы (постранично, размер по умолчанию 20) | — |
| `GET` | `/api/v1/comments/{parentId}/replies` | Ответы на комментарий | — |
| `GET` | `/api/v1/comments/count?section={section}&url={url}` | Число опубликованных комментариев | — |
| `GET` | `/api/v1/comments/my/count` | Число опубликованных комментариев текущего пользователя (для профиля) | JWT |
| `POST` | `/api/v1/comments` | Создать корневой комментарий | JWT |
| `POST` | `/api/v1/comments/{parentId}/replies` | Создать ответ | JWT |
| `PATCH` | `/api/v1/comments/{commentId}` | Отредактировать свой комментарий | JWT |
| `DELETE` | `/api/v1/comments/{commentId}` | Мягко удалить свой комментарий | JWT |

`author_id` берётся из JWT (`sub`), имя — из клейма `preferred_username` → `username` → `sub`.

### Примеры

Создание комментария:

```http
POST /api/v1/comments
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "section": "blog",
  "url": "/posts/hello-world",
  "content": "Отличная статья!"
}
```

Ответ `201 Created`:

```json
{
  "id": "b3f1...",
  "section": "blog",
  "url": "/posts/hello-world",
  "parentId": null,
  "authorId": "a1c2...",
  "authorName": "john",
  "content": "Отличная статья!",
  "status": "PUBLISHED",
  "replyCount": 0,
  "createdAt": "2026-07-15T10:00:00Z",
  "editedAt": null
}
```

### Документация OpenAPI

После запуска доступны:

- Swagger UI — `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON — `http://localhost:8080/v3/api-docs`

## Поведение бизнес-логики

- **Удаление** — мягкое: статус меняется на `DELETED`, `content` очищается, у родителя уменьшается `replyCount`.
- **Ответы** можно оставлять только к опубликованным (`PUBLISHED`) неудалённым комментариям.
- **Редактирование/удаление** чужого комментария приводит к ошибке (`IllegalStateException`).
- Отсутствующий комментарий → `EntityNotFoundException`.
