# Gateway de emisión SOAT (MVP local)

Servicio que recibe solicitudes de emisión de pólizas SOAT del core, las envía a un proveedor externo, recibe la confirmación (por webhook o consultando al proveedor) y devuelve al core un resultado en menos de 24 horas, **sin emitir nunca dos pólizas para la misma referencia**.

Esta versión es un **MVP que corre completo en una sola máquina con Docker**, sin nube y sin depender del proveedor real ni del core. Los servicios de Google Cloud del plan original se reemplazaron por equivalentes locales (ver [Qué se reemplazó del plan original](#qué-se-reemplazó-del-plan-original)).

**Idioma**: el código (paquetes, clases, métodos, tests, tablas, colas, propiedades, variables de entorno y scripts) está en inglés. Los contratos externos se mantienen tal como los definió el negocio: el JSON del proveedor (`/emisiones`, webhook `pago.actualizado`, campos `referencia`, `estado`, `monto_centavos`...) y el JSON de solicitud y resultado del core (`referencia`, `resultado: EXITOSA|ERROR`, `motivo: RECHAZADA|VENCIDA`...). Esta documentación está en español.

---

## Arranque rápido

**Requisitos**

| Para qué | Qué necesitas |
|---|---|
| Levantar y probar el gateway | Docker Desktop (o Docker Engine con Compose v2), `make`, `curl` y `jq` |
| Correr los tests de código (`make ci`) | Además, JDK 21. Maven no hace falta: el proyecto trae `gateway/mvnw` |

No necesitas instalar Java ni Maven para levantar el ambiente: todo se compila dentro de Docker.

**Tres comandos**

```bash
make local-up
```
```bash
make e2e
```
```bash
make local-down
```

- `make local-up` construye las imágenes y levanta Postgres, RabbitMQ, el simulador del proveedor y **2 instancias del gateway**, y espera a que estén sanas. La primera vez tarda unos 4 minutos porque descarga dependencias; después, segundos.
- `make e2e` ejecuta los escenarios de punta a punta (aprobación, rechazo, webhook que no llega, duplicados, proveedor lento, proveedor caído, emisión que nunca se aprueba, vencimiento con aprobación tardía, firma inválida) en unos 30 segundos.
- `make local-down` apaga todo.

**Qué queda corriendo**

| Servicio | Dirección | Para qué sirve |
|---|---|---|
| Gateway (instancia 1) | http://localhost:8081 | `POST /webhooks/pagos`, `GET /actuator/health` |
| Gateway (instancia 2) | http://localhost:8082 | Lo mismo; las dos instancias comparten base y colas |
| Simulador del proveedor | http://localhost:8090 | Hace de proveedor externo (ver [Simulador](#simulador-del-proveedor)) |
| Consola de RabbitMQ | http://localhost:15672 (usuario `soat`, clave `soat`) | Ver colas, DLQ y mensajes |
| Postgres | `localhost:5432`, base `soat`, usuario `soat`, clave `soat` | Única fuente de verdad |

Todas las credenciales son **solo para uso local**.

---

## Comandos

| Comando | Qué hace |
|---|---|
| `make` o `make help` | Lista los comandos |
| `make local-up` | Levanta todo y espera a que los dos gateways estén sanos |
| `make local-down` | Apaga todo |
| `make local-reset` | Apaga, borra los datos y vuelve a levantar |
| `make local-verify` | Verifica Postgres, RabbitMQ, las colas y que los mensajes diferidos funcionen |
| `make logs` | Sigue los logs del gateway y del simulador |
| `make e2e` | Escenarios de punta a punta contra el ambiente levantado |
| `make load` | Publica 10 solicitudes por segundo durante 60 s y verifica que todas terminen con un único resultado. Se puede cambiar: `make load RPS=20 SECONDS=120` |
| `make failover` | Publica 40 solicitudes con proveedor lento, mata un gateway en mitad del proceso, lo vuelve a levantar y verifica que no se pierde ni se duplica nada |
| `make ci` | Tests unitarios, de integración (con Testcontainers), cobertura (JaCoCo) y mutación (PIT) del gateway y del simulador. Necesita JDK 21 y Docker |

**Scripts sueltos** (en `scripts/`, útiles para probar a mano):

| Script | Uso |
|---|---|
| `publish-request.sh REF [segundos]` | Publica una solicitud como si fuera el core. El sufijo de `REF` elige el comportamiento del simulador. El segundo parámetro corre `solicitado_en` hacia el pasado (sirve para probar el vencimiento sin esperar 23 h) |
| `status.sh REF` | Muestra el estado de la emisión, sus resultados y las tareas programadas |
| `show-results.sh` | Muestra los resultados que llegaron a la cola `emission.results` (sin consumirlos) |
| `reprocess-dlq.sh` | Devuelve a la cola de solicitudes todo lo que haya en la DLQ (reproceso manual) |

Ejemplo:

```bash
./scripts/publish-request.sh SOAT-000001-OK
```
```bash
./scripts/status.sh SOAT-000001-OK
```

---

## Cómo funciona

1. **El core publica la solicitud** en la cola `emission.requests` (en local, el script `publish-request.sh` hace de core).
2. **El gateway la guarda** en Postgres con estado `REQUESTED` (si ya existía, no la duplica).
3. **Antes de llamar al proveedor, programa** la primera consulta de respaldo (a los 5 s) y el vencimiento (a las 23 h de la solicitud). Así, si el gateway se cae justo después de llamar, las consultas ya están programadas.
4. **Llama al proveedor** con `Idempotency-Key = referencia` (timeout de 10 s). Si responde bien, la emisión pasa a `SENT`. Si falla, la solicitud se reintenta a los 10 s; al quinto fallo va a la DLQ y queda `SEND_FAILED`.
5. **El proveedor confirma por webhook** (`POST /webhooks/pagos`). El gateway verifica la firma HMAC sobre los bytes crudos, descarta eventos repetidos por `id` y aplica el cambio de estado.
6. **Si el webhook no llega**, las consultas de respaldo preguntan al proveedor a los 5 s, 1 min, 10 min, 20 min y 30 min. Si se agotan sin respuesta, la emisión pasa a `REVIEW_REQUIRED` (no se publica nada todavía).
7. **A las 23 h** el vencimiento hace una consulta final. Si no hay aprobación, marca `EXPIRED` y publica un ERROR provisional. Si después llega un APROBADO, se emite y se publica una corrección (`secuencia 2`, `corrige_secuencia 1`).
8. **Cada resultado se publica** en `emission.results` a través de un outbox en la base: primero se guarda en la misma transacción que el cambio de estado, y luego se publica. Si la publicación falla, la siguiente consulta o el vencimiento lo vuelven a publicar.

**Estados** (y su equivalente en el plan original): `REQUESTED` (SOLICITADA), `SENT` (ENVIADA), `REVIEW_REQUIRED` (REQUIERE_REVISION), `SEND_FAILED` (FALLIDA_ENVIO), `EXPIRED` (VENCIDA), `ISSUED` (EMITIDA) y `REJECTED` (RECHAZADA). Los dos últimos son finales. Las transiciones permitidas están en `TransitionMatrix` y en la base se aplican con `UPDATE ... WHERE status IN (orígenes permitidos)`: si no se actualiza ninguna fila, el aviso era viejo o repetido y se ignora. Un `PENDIENTE` del proveedor nunca cambia el estado.

**Cómo se evita la póliza duplicada**: `Idempotency-Key` hacia el proveedor, `INSERT ... ON CONFLICT DO NOTHING` para solicitudes y eventos, el `UPDATE` condicional, la clave `(reference, sequence)` del outbox (si dos procesos compiten, el segundo falla y se reintenta con el estado nuevo) y un índice único sobre `policy_number`.

---

## Qué se reemplazó del plan original

El plan completo ([docs/plan-completo.md](docs/plan-completo.md)) se diseñó para Google Cloud. El MVP ([docs/plan-mvp.md](docs/plan-mvp.md)) corre todo en local. Los planes usan los nombres originales en español; la tabla de estados de arriba y la de [Estructura del código](#estructura-del-código) dan la equivalencia. La lógica de negocio (dominio y casos de uso) es la misma; solo cambian los adaptadores, que están detrás de interfaces (puertos), así que más adelante se pueden escribir los de GCP sin tocar el dominio.

### Servicios de infraestructura

| En el plan original (GCP) | En este MVP (local) | Cómo se cubre lo mismo |
|---|---|---|
| Cloud SQL (Postgres) | Postgres 16 en Docker | Igual: única fuente de verdad, migraciones Flyway, `UNIQUE` y `ON CONFLICT` |
| Pub/Sub `emision-solicitudes` | Cola RabbitMQ `emission.requests` | Entrega al menos una vez y confirmación (ack) al terminar |
| Política de reintentos de Pub/Sub (10 s, 5 intentos) y dead letter topic | El gateway republica la solicitud en el exchange diferido a los 10 s con el header `x-attempt`; en el intento 5 la envía a `emission.requests.dlq` | Mismo comportamiento, sin `sleep` en el código: la espera la hace RabbitMQ |
| Pub/Sub `emision-resultados` con ordering key | Cola `emission.results` | Una sola cola en orden de publicación; cada resultado lleva `secuencia` |
| Cloud Tasks (consultas y vencimiento) | El mismo RabbitMQ, con el plugin `rabbitmq_delayed_message_exchange` (exchange `emission.delayed`) | Mensajes que llegan pasado un tiempo. El nombre determinístico de cada tarea se registra en la tabla `scheduled_task` para no programarla dos veces |
| Endpoint HTTP de Cloud Tasks con validación OIDC | **Ya no existe**: las tareas llegan como mensajes internos de RabbitMQ | No hay endpoint expuesto que proteger |
| Secret Manager (dos versiones del secreto HMAC) | Variables de entorno `HMAC_CURRENT` y `HMAC_PREVIOUS` | La rotación con dos secretos funciona igual |
| GKE, HPA, PDB, Workload Identity, IAM, Terraform | `docker compose` con 2 réplicas del gateway | Concurrencia real entre instancias y prueba de caída (`make failover`) |
| Flow control de StreamingPull como techo por pod | `prefetch = 1` y 20 consumidores por instancia | Nunca más de 20 llamadas al proveedor en vuelo por instancia (40 en total) |
| Proveedor externo y su sandbox | Simulador propio (`simulador-proveedor/`) | Aprobación, rechazo, webhook que no llega, duplicados, lentitud, errores 5xx y aprobación tardía |
| Core | Scripts que publican solicitudes y leen resultados | — |
| Cloud Monitoring, Trace y alertas | Logs en consola y `GET /actuator/health` / `/actuator/metrics` | Sin alertas en local |

### Otras simplificaciones respecto al plan

| En el plan original | En este MVP | Motivo |
|---|---|---|
| Límite de concurrencia adaptativo (Gradient2) y circuit breaker (Resilience4j) | Techo fijo de 20 llamadas por instancia | Con tráfico local no hay capacidad real del proveedor que medir |
| Respeto del header `Retry-After` | Un 429 se trata como cualquier error: reintento a los 10 s | Ídem |
| Reproceso automático de la DLQ | Manual, con `scripts/reprocess-dlq.sh` | Pocas solicitudes, operadas por el equipo |
| Relay del outbox con tarea dedicada | Publicación inmediata tras el commit; si falla, la republican la siguiente consulta o el vencimiento de esa referencia | Evita otra cola y cualquier barrido de tablas |
| Payload del outbox en JSONB | Columnas tipadas (`result_type`, `reason`, `definitive`, ...) | La base valida los valores y no hace falta serializar en la capa de datos |
| Proyecto Maven multi-módulo | Un módulo con paquetes hexagonales y reglas de ArchUnit | Menos ceremonia; las reglas impiden que el dominio dependa de Spring |
| Consultas y vencimiento con delays reales | Perfil `local-fast` por defecto en Docker: consultas a 2, 4, 6, 8 y 10 s y reintentos a 2 s | Poder probar en segundos. El vencimiento sigue siendo a las 23 h: para probarlo se publica una solicitud con `solicitado_en` en el pasado |

---

## Estructura del código

El gateway sigue una **arquitectura hexagonal**: el dominio no conoce Spring, RabbitMQ ni la base; los casos de uso solo conocen interfaces; los adaptadores implementan esas interfaces con tecnología concreta. Las reglas de ArchUnit en `ArchitectureRulesTest` hacen fallar el build si alguien rompe esa separación.

```
r5-gateway/
├── gateway/                                Proyecto del gateway (Maven, Java 21, Spring Boot)
│   ├── src/main/java/com/soat/gateway
│   │   ├── GatewayApplication.java         Arranque de Spring Boot
│   │   ├── domain/                         Reglas de negocio puras (sin Spring ni infraestructura)
│   │   ├── application/                    Casos de uso y puertos (interfaces)
│   │   └── infrastructure/                 Adaptadores: base de datos, RabbitMQ, HTTP, configuración
│   ├── src/main/resources
│   │   ├── application.yml                 Configuración (todo sobreescribible con variables de entorno)
│   │   ├── application-local-fast.yml      Delays cortos para probar en local
│   │   └── db/migration/                   Migraciones Flyway
│   ├── src/test/                           Tests (unitarios, integración con Testcontainers, arquitectura)
│   ├── Dockerfile                          Imagen del gateway (se compila dentro de Docker)
│   └── mvnw                                Maven wrapper (no hace falta instalar Maven)
├── simulador-proveedor/                    Proyecto aparte: simulador del proveedor externo (com.soat.simulator)
├── local/rabbitmq/                         Imagen de RabbitMQ con el plugin de mensajes diferidos y la topología
├── scripts/                                Operación y pruebas del ambiente local
├── docs/                                   Plan completo (GCP) y plan del MVP
├── compose.yaml                            Ambiente local completo
└── Makefile                                Comandos (se ejecutan desde esta carpeta)
```

Todos los comandos `make` se ejecutan desde la carpeta raíz (`r5-gateway/`). Las rutas de código de este documento son relativas a `gateway/src/main/java/com/soat/gateway/`.

### Dominio (`domain/`)

| Clase | Nombre en el plan original | Para qué sirve |
|---|---|---|
| `emission/EmissionStatus` | `EstadoEmision` | Los 7 estados de una emisión y cuáles son finales |
| `emission/TransitionMatrix` | `MatrizTransiciones` | Las 18 transiciones permitidas. `allowedOrigins(target)` alimenta el `UPDATE ... WHERE status IN (...)` |
| `emission/Emission` | `Emision` | El agregado. No guarda nada: ante cada evento (envío confirmado, fallo de envío, aviso del proveedor, consultas agotadas, vencimiento) devuelve una `Decision` |
| `emission/Decision` | `Decision` | Resultado de una decisión: `Transition`, `Ignore(reason)` o `ManualReview(reason)` |
| `emission/ProviderNotice`, `ProviderStatus` | `AvisoProveedor`, `EstadoProveedor` | Lo que dice el proveedor (estado, monto, póliza). `ProviderStatus.fromWire` traduce APROBADO, PENDIENTE y RECHAZADO; cualquier otro valor es `UNKNOWN` |
| `emission/IgnoreReason`, `ReviewReason` | `MotivoIgnorado`, `MotivoRevision` | Por qué se ignoró un aviso (obsoleto, pendiente, conflicto, vencimiento prematuro...) o por qué va a revisión (monto distinto o ausente) |
| `sla/SlaDeadlines` | `PlazosSla` | Fecha límite = solicitud + 24 h; vencimiento = fecha límite − 1 h |
| `sla/StatusCheckSchedule` | `CalendarioConsultas` | Delays de la cadena de consultas (configurables) |
| `sla/TaskName` | `NombreTarea` | Nombre determinístico de cada tarea: prefijo hash + referencia + intento (`-c2`) o vencimiento (`-e`) |
| `result/ResultHistory` | `HistorialResultados` | Calcula la secuencia de cada resultado y si corrige a uno provisional. Solo se puede corregir un ERROR provisional (EXPIRED) |
| `result/EmissionResult`, `ResultType`, `ResultReason` | `ResultadoEmision`, `TipoResultado`, `MotivoResultado` | El resultado que se publica al core. `SUCCESS` sale como `EXITOSA`, `REJECTED` como `RECHAZADA` y `EXPIRED` como `VENCIDA` |
| `security/SignatureVerifier` | `VerificadorFirma` | Verifica `X-Firma` (HMAC-SHA256 en hex de `"{X-Timestamp}.{cuerpo}"`) en tiempo constante, con ventana de ±5 min y dos secretos para rotar |

### Aplicación (`application/`)

| Clase | Nombre en el plan original | Para qué sirve |
|---|---|---|
| `service/ProcessRequest` | `ProcesarSolicitud` | Guarda la solicitud, programa consulta y vencimiento, llama al proveedor y decide reintento o DLQ |
| `service/RegisterNotice` | `RegistrarAviso` | Procesa un webhook: descarta repetidos, aplica la decisión y publica el resultado |
| `service/RunStatusCheck` | `EjecutarConsulta` | Consulta de respaldo: pregunta al proveedor, aplica la respuesta y programa la siguiente o pasa a `REVIEW_REQUIRED` |
| `service/RunExpiration` | `EjecutarVencimiento` | Consulta final y, si no hay aprobación, `EXPIRED` con ERROR provisional |
| `service/MarkSendFailed` | `MarcarFallidaEnvio` | Marca `SEND_FAILED` lo que llega a la DLQ |
| `service/DecisionApplier` | `AplicadorDecisiones` | Único camino de escritura: transición + outbox en una transacción y publicación después del commit |
| `port/*` | `puerto/*` | Interfaces que la aplicación necesita: `EmissionRepository`, `ProviderEventRepository`, `OutboxRepository`, `ResultPublisher`, `DelayedTaskScheduler`, `RequestQueue`, `EmissionProvider`, `UnitOfWork` y `FailedEmissionHandler` |

### Infraestructura (`infrastructure/`)

| Paquete | Para qué sirve |
|---|---|
| `persistence/` | Repositorios JDBC (`JdbcEmissionRepository`, `JdbcProviderEventRepository`, `JdbcOutboxRepository`) y transacciones (`JdbcUnitOfWork`) |
| `messaging/` | RabbitMQ: envío con confirmación del broker (`RabbitSender`), reintentos y DLQ (`RabbitRequestQueue`), tareas diferidas (`RabbitDelayedTaskScheduler`), publicación de resultados (`RabbitResultPublisher`), consumidores (`Consumers`) y formato JSON de los mensajes (`MessageCodec`). Si procesar un mensaje falla por un error interno (por ejemplo, la base caída), el mensaje se vuelve a diferir; si el mensaje es ilegible, se descarta y queda un aviso en el log, para que no quede reintentándose para siempre |
| `provider/` | Cliente HTTP del proveedor (`HttpProviderClient`): timeout de 10 s, sin reintentos internos |
| `web/` | Webhook `POST /webhooks/pagos` (`PaymentWebhookController`) |
| `config/` | Configuración de Spring que conecta todo (`GatewayConfiguration`), propiedades (`GatewayProperties`) y el handler de emisiones fallidas que hoy solo registra en el log (`LoggingFailedEmissionHandler`) |

### Base de datos (`gateway/src/main/resources/db/migration`)

| Tabla | Nombre en el plan original | Para qué sirve | Restricción clave |
|---|---|---|---|
| `emission` | `emision` | Estado de cada referencia, prima cotizada, póliza y última secuencia publicada | PK `reference`; índice único sobre `policy_number` |
| `provider_event` | `evento_proveedor` | Webhooks ya procesados | PK `event_id` (descarta reenvíos) |
| `outbox` | `outbox` | Resultados pendientes y publicados | PK `(reference, sequence)` |
| `scheduled_task` | `consulta_programada` | Tareas ya programadas | PK `task_name` (no se programan dos veces) |

### Colas de RabbitMQ (`local/rabbitmq/definitions.json`)

| Cola | Para qué sirve |
|---|---|
| `emission.requests` | Solicitudes del core y sus reintentos |
| `emission.requests.dlq` | Solicitudes que fallaron 5 veces |
| `emission.status-checks` | Consultas de respaldo (llegan diferidas) |
| `emission.expirations` | Vencimientos (llegan diferidos) |
| `emission.results` | Resultados hacia el core |

El exchange `emission` entrega de inmediato y `emission.delayed` (tipo `x-delayed-message`) entrega pasado el tiempo indicado en el header `x-delay`. El servicio `rabbitmq-init` del compose carga esta topología al arrancar.

---

## Simulador del proveedor

Proyecto Java independiente (`simulador-proveedor/`, paquete `com.soat.simulator`, sin dependencias externas) que imita al proveedor. El comportamiento se elige con el **sufijo de la referencia**:

| Sufijo | Qué hace el simulador | Resultado esperado en el gateway |
|---|---|---|
| `-OK` (o sin sufijo) | Acepta y a los 2 s envía webhook APROBADO | `ISSUED`, EXITOSA |
| `-REJECT` | Acepta y envía webhook RECHAZADO | `REJECTED`, ERROR RECHAZADA |
| `-NOWEBHOOK` | Acepta pero no envía webhook; la consulta responde APROBADO | `ISSUED` por consulta de respaldo |
| `-DUP` | Envía 3 veces el mismo APROBADO y luego un PENDIENTE | `ISSUED`, un solo resultado |
| `-SLOW` | Tarda 8 s en responder y luego aprueba | `ISSUED` |
| `-5XX` | Responde siempre 500 | 5 intentos, DLQ y `SEND_FAILED` |
| `-NEVER` | Acepta y la consulta responde siempre PENDIENTE | `REVIEW_REQUIRED` al agotar las consultas |

**Endpoints**

| Endpoint | Para qué sirve |
|---|---|
| `POST /emisiones` | Solicitud de emisión (lo llama el gateway). Es idempotente por referencia |
| `GET /emisiones/{referencia}` | Consulta de estado; 404 si no existe |
| `POST /simulator/approve/{referencia}` | Para pruebas: aprueba una emisión y envía el webhook (sirve para la aprobación tardía después de `EXPIRED`) |

Los webhooks salen firmados con `HMAC_SECRET` y con un `X-Timestamp` nuevo en cada reenvío. Si el gateway no responde 2xx, el simulador reintenta hasta 5 veces, igual que haría el proveedor.

---

## Configuración

Todo se configura con variables de entorno (valores por defecto en `application.yml`):

| Variable | Por defecto | Para qué sirve |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/soat`, `soat`, `soat` | Base de datos |
| `RABBIT_HOST`, `RABBIT_PORT`, `RABBIT_USER`, `RABBIT_PASSWORD` | `localhost`, `5672`, `soat`, `soat` | RabbitMQ |
| `PROVIDER_URL` | `http://localhost:8090` | Dirección del proveedor (en Docker, el simulador) |
| `HMAC_CURRENT`, `HMAC_PREVIOUS` | `secreto-local-v1`, vacío | Secretos del webhook. Durante una rotación, el nuevo va en `HMAC_CURRENT` y el viejo en `HMAC_PREVIOUS` |
| `SPRING_PROFILES_ACTIVE` | `local-fast` en Docker | Vacío para usar los delays reales (5 s, 1 min, 10 min, 20 min, 30 min y reintentos a 10 s) |
| `PORT` | `8080` | Puerto HTTP del gateway |

En el compose, `PROFILE` y `HMAC_SECRET` cambian el perfil y el secreto compartido entre gateway y simulador. Por ejemplo, para usar los delays reales:

```bash
PROFILE=default make local-up
```

**Correr el gateway fuera de Docker** (para depurar desde el IDE): levanta todo con `make local-up`, detén los gateways con `docker compose stop gateway` y ejecuta:

```bash
cd gateway && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local-fast
```

En ese caso el simulador sigue enviando los webhooks al gateway de Docker; para recibirlos en el IDE, cambia `WEBHOOK_URL` del simulador a `http://host.docker.internal:8080/webhooks/pagos`.

---

## Contratos de mensajes

Estos contratos son externos y **no se tradujeron**.

**Solicitud** (cola `emission.requests`, propuesta para el core):

```json
{ "referencia": "SOAT-883214", "correlation_id": "c-1", "solicitado_en": "2026-11-09T14:00:00Z",
  "prima_cotizada_centavos": 89130000, "placa": "ABC123" }
```

**Resultado** (cola `emission.results`, header `correlation_id`):

```json
{ "referencia": "SOAT-883214", "secuencia": 2, "resultado": "EXITOSA", "motivo": null, "definitivo": true,
  "corrige_secuencia": 1, "numero_poliza": "POL-1", "ocurrido_en": "2026-11-10T13:00:05.123Z", "correlation_id": "c-1" }
```

**Webhook del proveedor** (`POST /webhooks/pagos`, headers `X-Firma` y `X-Timestamp`):

```json
{ "id": "evt_01JC8Q2W7M", "tipo": "pago.actualizado", "creado_en": "2026-11-09T14:03:21.482Z",
  "data": { "referencia": "SOAT-883214", "estado": "APROBADO", "monto_centavos": 89130000, "numero_poliza": "POL-1" } }
```

Respuestas del webhook: `200` (aplicado, ignorado, repetido, a revisión o referencia desconocida), `401` (firma o timestamp inválidos), `400` (JSON inválido), `415` (no es JSON) y `503` (conflicto de concurrencia: el proveedor debe reenviar).

**API del proveedor** (lo que espera el gateway; es un supuesto hasta conocer la documentación real): `POST /emisiones` con `Idempotency-Key` y cuerpo `{referencia, monto_centavos, placa}`; `GET /emisiones/{referencia}` que responde `{referencia, estado, monto_centavos, numero_poliza}`.

Las consultas y vencimientos internos (`emission.status-checks`, `emission.expirations`) sí usan campos en inglés (`reference`, `attempt`), porque solo los lee el gateway.

---

## Pruebas

El proyecto se construyó con TDD: cada pieza tiene primero su test.

| Nivel | Dónde (`gateway/src/test/java/com/soat/gateway/`) | Qué verifica |
|---|---|---|
| Dominio (unitario) | `domain/` | Matriz completa (49 pares), avisos desordenados y duplicados, aprobación tardía, monto, plazos, nombres de tareas, secuencias y correcciones, firma HMAC con vector dorado |
| Arquitectura | `architecture/ArchitectureRulesTest` | Dominio y aplicación sin Spring, RabbitMQ ni JDBC; comparación de firma en tiempo constante |
| Casos de uso | `application/` | Orden persistir → programar → llamar, reintentos y DLQ, publicación fuera de la transacción, republicación de pendientes. Usa fakes en memoria |
| Persistencia | `infrastructure/persistence/` | Migraciones, `ON CONFLICT`, `UPDATE` condicional y **concurrencia real con dos hilos** contra Postgres (Testcontainers) |
| Mensajería | `infrastructure/messaging/` | Reintentos, DLQ, mensajes diferidos, tareas no duplicadas, contrato del resultado y techo de 20 en vuelo, contra RabbitMQ real (Testcontainers) |
| Proveedor | `infrastructure/provider/` | Timeout de 10 s, errores 5xx/429 con una sola llamada, 409, mapeo de la consulta (WireMock) |
| Webhook | `infrastructure/web/` | Firma sobre bytes crudos y códigos HTTP (MockMvc) |
| Aplicación completa | `GatewayApplicationTest` | Arranque de Spring y flujo solicitud → consulta → resultado con Postgres, RabbitMQ y WireMock |
| Simulador | `simulador-proveedor/src/test` | Cada sufijo, firma y reintento de webhooks |
| Punta a punta | `make e2e`, `make load`, `make failover` | El ambiente Docker completo con dos gateways |

`make ci` exige cobertura de líneas ≥ 80 % (JaCoCo) y mutación del dominio ≥ 80 % (PIT). Los tests de integración necesitan Docker corriendo.

---

## Supuestos y pendientes antes de producción

**Supuestos** (no bloquean el MVP, hay que confirmarlos con el proveedor y el core):

- Rutas, campos y códigos del API del proveedor (incluido que 409 significa "ya recibida").
- `X-Timestamp` en epoch segundos.
- `numero_poliza` llega en el webhook y en la consulta.
- Estados del proveedor: APROBADO, PENDIENTE y RECHAZADO.
- Formato del mensaje de solicitud del core.
- El cliente HTTP usa HTTP/1.1, porque el intento de HTTP/2 en texto plano del cliente de Java fallaba contra los servidores de prueba.

**Pendiente para producción** (detalle en el plan completo):

- Adaptadores de Pub/Sub y Cloud Tasks (con OIDC en el endpoint de tareas), Secret Manager, Terraform y despliegue en GKE.
- Limitador adaptativo, circuit breaker y respeto de `Retry-After`, calibrados con una prueba de capacidad en el sandbox del proveedor.
- Reproceso automático de la DLQ y una tarea dedicada para el relay del outbox.
- Métricas de negocio, trazas, `correlation_id` en todos los logs y alertas (EXPIRED, backlog, riesgo de SLA).
- Integración real de `FailedEmissionHandler` con el proceso de revisión manual.
- Validar qué pasa con los mensajes diferidos si RabbitMQ se reinicia.

---

## Solución de problemas

| Síntoma | Qué hacer |
|---|---|
| `make local-up` falla con "port is already allocated" | Algún puerto (5432, 5672, 15672, 8081, 8082 u 8090) está ocupado. Libéralo o detén el otro servicio |
| El gateway no queda sano | `docker compose logs gateway`. Suele ser la base o RabbitMQ aún arrancando: `make local-reset` |
| La primera construcción tarda mucho | Es la descarga de dependencias de Maven; queda en caché para las siguientes |
| `make e2e` falla en un escenario | `./scripts/status.sh REFERENCIA` muestra estado, resultados y tareas; `make logs` muestra lo que hizo cada servicio |
| Los tests de integración fallan con "Could not find a valid Docker environment" | Docker no está corriendo |
