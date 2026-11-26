# in2-dome-desmos-core-api

Desmos, el Blockchain Connector del Access Node de DOME: replica entidades NGSI-LD entre nodos (DLT + P2P) y lleva la
cadena de audits en `desmos.audit_records`. Flujos y sus pasos: `docs/APPLICATION_FLOW.md`.

## Build y tests

- JDK 17. Siempre `sh ./gradlew`.
- Unitarios: `sh ./gradlew test`. Integración (Testcontainers: Scorpio `java-6.0.2`, PostGIS, Postgres):
  `sh ./gradlew integrationTest` (`src/integrationTest`). Checkstyle corre en los tres source sets.
- Imagen para desmos-load: `docker build --build-arg SKIP_TESTS=true -t desmos-api:<tag> .`

## Convenciones

- Commits convencionales en inglés (`fix(sync): ...`, `feat(audit): ...`, `test(replication): ...`).
- Cada versión: entrada `## [Released]: vX.Y.Z` arriba en `CHANGELOG.md` y `version` en `build.gradle`, en un commit
  `chore(release): bump version to X.Y.Z`.
- Las PR se fusionan con squash (una release por PR): `git branch --merged` no sirve para saber si una rama ya está en
  `main`. Para eso, comprobar si `git merge-tree --write-tree main <rama>` devuelve el árbol de `main`.

## Audits y ecos (resumen)

- Toda escritura de audits pasa por `AuditChainWriter.append`, que calcula `hash`/`hashLink` y serializa la cadena.
- El eco se detecta comparando el hash del aviso de Scorpio con el último `RETRIEVED` del `id`. Lo que se
  escribe en Scorpio sin `RETRIEVED` previo se toma por cambio local y se publica como `PRODUCER`.
- `calculateSHA256` ordena las claves del JSON: el orden no rompe los ecos.
- La verificación de consistencia busca por `0x…` y la tabla guarda la URN: hoy no encuentra nada y se salta.

## Logs

- Las líneas `[audit]`, `[subscribe]`, `[p2p-sync]` e `[init]` van a INFO y bastan para seguir un flujo.
- Los avisos recibidos (`NotificationController`) solo salen en DEBUG: para contarlos, usar las filas de audit.
