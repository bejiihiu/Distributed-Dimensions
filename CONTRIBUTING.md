# Contributing

Коротко: форк → ветка → PR в main. Дальше детали.

## Коммиты

Conventional Commits: `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`.
Пример: `feat: add nether portal search to common`.

## Код

- Java 25 везде, единый тулчейн (см. корневой `build.gradle.kts`). Preview-фичи
  (`--enable-preview`) в шиппаемом коде запрещены: серверы флаг не дадут, jar не стартует.
- Общая логика — в `common`, платформенный код — только адаптеры в `paper`/`velocity`.
- Публичные методы покрываются тестами в `common/src/test` (JUnit, швы — публичные интерфейсы).
- Пользовательский ввод в MiniMessage только через `Placeholder.unparsed/parsed`, никогда конкатенацией.
- Folia-safe с самого начала: entity/region/global/async schedulers по месту,
  никакого Bukkit API из чужих потоков.
- Никаких mock/stub-заглушек в шиппаемом коде: реализуй полностью или не делай.
- Комменты в коде — человеческие, на английском. Юзеру видимые строки — грамотный английский.

## Проверки перед PR

```bash
./gradlew build
```

Зелёный build = тесты, shade-сборка обоих jar. Дев-серверы для ручной проверки:
`./gradlew :paper:runServer`, `./gradlew :velocity:runVelocity`.
