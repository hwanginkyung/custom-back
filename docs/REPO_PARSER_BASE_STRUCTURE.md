# Repo Parser Base Package Structure

This is a minimal, domain-first Spring Boot structure you can upload as the first commit.
It mirrors the current `cariv` style: `global` (shared concerns) + `domain` (business modules).

## 1. Project Tree

```text
repo-parser/
  build.gradle
  settings.gradle
  Dockerfile
  docker-compose.yml
  .env.example
  README.md

  src/
    main/
      java/
        com/yourorg/repoparser/
          RepoParserApplication.java

          global/
            config/
              SecurityConfig.java
              RedisConfig.java
              SwaggerConfig.java
            exception/
              ErrorCode.java
              CustomException.java
              GlobalExceptionHandler.java
              ErrorResponse.java
            common/
              BaseEntity.java
              TenantEntity.java
            tenant/
              TenantContext.java
              TenantFiltered.java
              TenantFilterAspect.java
            jwt/
              JwtTokenProvider.java
              JwtAuthenticationFilter.java
              service/
                RefreshTokenService.java

          domain/
            auth/
              controller/
              dto/
              entity/
              repository/
              service/
            document/
              controller/
              dto/
              entity/
              repository/
              service/
            parser/
              controller/
              dto/
              entity/
              repository/
              service/
            job/
              controller/
              dto/
              entity/
              repository/
              service/

      resources/
        application.yml
        application-local.yml
        application-prod.yml
        db/
          migration/

    test/
      java/
        com/yourorg/repoparser/
```

## 2. Environment Files To Include From Day 1

- `.env.example`
- `docker-compose.yml` (app + mysql + redis)
- `Dockerfile`
- `src/main/resources/application-local.yml`
- `src/main/resources/application-prod.yml`

## 3. Suggested First Upload Order

1. `chore: bootstrap spring project and package skeleton`
2. `chore: add local/prod config and docker-compose`
3. `feat: add auth + token baseline`
4. `feat: add parser job domain skeleton`

## 4. Minimal README Sections

- Project overview
- Tech stack
- Run local (`docker compose up -d`, `./gradlew bootRun`)
- Environment variables
- API docs URL (`/swagger-ui/index.html`)

## 5. Optional: Create Folder Skeleton Quickly

```bash
mkdir -p src/main/java/com/yourorg/repoparser/{global/{config,exception,common,tenant,jwt/service},domain/{auth,document,parser,job}/{controller,dto,entity,repository,service}} \
         src/main/resources/db/migration \
         src/test/java/com/yourorg/repoparser
```
