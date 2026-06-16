# Spring Boot Learning Notes

This note captures a deferred learning exercise for returning to modern web
backend development using the existing Spring preview prototype.

## Context

The current Spring Boot prototype under `prototypes/spring-preview/` is a good
small exercise app because it is intentionally narrow:

- It exposes one HTTP endpoint, `POST /api/preview`.
- It wraps the JVM scoring prototype instead of owning domain logic.
- It has one controller and one MVC test suite.
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

Convert the prototype from manual construction to Spring-managed dependency
injection.

Current code in `PreviewController` manually creates the evaluator:

```java
private final PreviewEvaluator previewEvaluator = new PreviewEvaluator();
```

Exercise target:

1. Add a Spring configuration class that exposes `PreviewEvaluator` as a bean.
2. Change `PreviewController` to receive `PreviewEvaluator` through constructor
   injection.
3. Run the existing Spring MVC tests to confirm the HTTP contract is unchanged.
4. Discuss why this is more idiomatic Spring and where the boundary is between
   controller code and service/domain code.

Suggested verification:

```bash
(cd prototypes/jvm-scoring && mvn install)
(cd prototypes/spring-preview && mvn test)
```
