# Spring Boot Learning Notes

This note captures a learning exercise for returning to modern web backend
development using the real backend module.

## Context

The current Spring Boot backend under `backend/` is a good small exercise app
because it is intentionally narrow:

- It exposes one HTTP endpoint, `POST /api/opportunities/search`.
- It wraps the JVM scoring prototype behind a backend-owned opportunity search
  seam instead of owning all domain logic yet.
- It has one controller and focused WebTestClient tests.
- It avoids production concerns such as persistence, accounts, sessions,
  security, deployment, live weather calls, and geocoding.

This makes it useful for relearning modern backend concepts without the weight
of a full production application.

## Concepts To Revisit

- Spring Boot as a self-contained app with an embedded server, rather than a
  traditional J2EE WAR deployed into an external application server.
- Controller methods as ordinary Java methods that Spring calls in response to
  matched HTTP requests.
- `ResponseEntity` as the object used to describe HTTP status, headers, and
  body.
- Parameter annotations such as `@RequestBody`, `@PathVariable`,
  `@RequestParam`, and `@RequestHeader`.
- `@ControllerAdvice` and `@ExceptionHandler` for mapping Java exceptions to
  HTTP error responses.
- Spring dependency injection through component scanning, constructor
  injection, `@Configuration`, and `@Bean`.
- `application.yml` as configuration values, not usually as the whole DI object
  graph.

## Exercise To Do Later

Continue evolving the backend from a prototype adapter toward idiomatic
Spring-managed application services.

Current shape:

```text
OpportunitySearchController
  -> OpportunitySearchService
      -> OpportunitySearchEngine
          -> PrototypeOpportunitySearchEngine
              -> PreviewEvaluator from jvm-scoring-prototype
```

Exercise target:

1. Add backend-owned response handling without copying prototype details too
   literally.
2. Introduce provider interfaces only when geocoding, weather, ephemeris, or
   scoring boundaries are ready to move into the backend.
3. Keep controller code thin and push application behavior into services.
4. Discuss where Spring configuration ends and domain/application code begins.

Suggested verification:

```bash
mvn test -pl backend -am
```
