# Plan MVP: Gateway de emisión SOAT (100 % local)

**Objetivo**: probar el gateway completo en una máquina local con Docker, sin nube, sin cuentas de GCP y sin depender del proveedor real ni del core. El flujo a probar es: solicitud → emisión → confirmación por webhook o por consulta → resultado, sin pólizas duplicadas.
**Equipo y plazo**: 3 desarrolladores, 4 semanas (~45 días-persona).
**Forma de trabajo**: TDD. En cada historia se escribe primero el test que falla, después el código mínimo y al final el refactor.
**Plan completo**: `plan-completo.md` describe el despliegue en GCP; ese documento queda como referencia futura.

---

## 1. Servicios de GCP reemplazados por equivalentes locales

La lógica de negocio (dominio y casos de uso) no cambia. Solo cambian los adaptadores, que siguen detrás de los mismos puertos, así que se podrán escribir adaptadores de GCP más adelante sin tocar el dominio.

| En GCP | En local (Docker) | Qué cubre |
|---|---|---|
| Cloud SQL | **Postgres 16** | Lo mismo: única fuente de verdad, `UNIQUE`, `ON CONFLICT`, UPDATE condicional |
| Pub/Sub (`emision-solicitudes`, `emision-resultados`, DLQ) | **RabbitMQ** con colas, reintentos y dead-letter | Entrega al menos una vez, ack/nack, 5 intentos cada 10 s, DLQ, límite de mensajes en vuelo (`prefetch`) |
| Cloud Tasks (consultas y vencimiento) | **El mismo RabbitMQ**, con mensajes diferidos (plugin `rabbitmq_delayed_message_exchange`) | Consultas a los 5 s, 1 min, 10 min, 20 min y 30 min, y vencimiento a las 23 h, sin crons ni `sleep` |
| OIDC del endpoint de tareas | **Ya no se necesita**: las tareas llegan como mensajes internos, no por HTTP | — |
| Secret Manager | Variables de entorno en `.env` (`HMAC_ACTUAL`, `HMAC_ANTERIOR`) | Rotación con dos secretos |
| GKE, HPA, Terraform, IAM | **docker compose** con 2 instancias del gateway | Concurrencia real entre instancias y caída de una instancia |
| Proveedor externo | **Simulador del proveedor** (contenedor propio): acepta emisiones, responde consultas y envía webhooks firmados | Aprobación, rechazo, webhook que no llega, duplicados, desorden, errores 5xx y latencia |
| Core | **Scripts** que publican solicitudes y leen resultados (más la consola web de RabbitMQ) | Publicación de solicitudes y lectura de `emision-resultados` |
| Cloud Monitoring | Logs JSON y `/actuator/metrics`; Prometheus y Grafana opcionales | Métricas básicas |

Un solo broker (RabbitMQ) cubre a la vez Pub/Sub y Cloud Tasks, y elimina el bloqueo de que Cloud Tasks no tiene emulador.

---

## 2. Cómo funciona en local

**Colas de RabbitMQ**

| Cola | Uso | Configuración |
|---|---|---|
| `emision.solicitudes` | Solicitudes del core | `prefetch = 20` por instancia (techo de llamadas al proveedor) |
| `emision.solicitudes.dlq` | Solicitudes que fallaron 5 veces | El consumidor marca FALLIDA_ENVIO; el reproceso es manual, con un script |
| `emision.consultas` | Consultas de respaldo | Llegan diferidas por el exchange `emision.diferido` |
| `emision.vencimientos` | Vencimiento en `solicitado_en + 23 h` | Llega diferido; si llega antes de tiempo, se ignora |
| `emision.resultados` | Resultados hacia el core | Una sola cola, en orden de publicación |

**Reintento de la llamada al proveedor**: si la llamada falla, el gateway publica el mismo mensaje en el exchange diferido con 10 s de espera y un header `intento + 1`, y hace ack del original. En el intento 5 lo envía a la DLQ. No hay `sleep` en el código: la espera la hace el broker.

**Dedupe de tareas**: antes de publicar una consulta, el gateway inserta su nombre determinístico en `consulta_programada`, que tiene `UNIQUE`. Si la fila ya existía, no se vuelve a publicar.

**Tablas (Flyway)**

| Tabla | Para qué sirve | Restricción clave |
|---|---|---|
| `emision` | Estado de cada referencia | PK `referencia` |
| `evento_proveedor` | Webhooks recibidos | PK `evento_id` (`ON CONFLICT DO NOTHING`) |
| `outbox` | Resultados pendientes de publicar | `UNIQUE (referencia, secuencia)` |
| `consulta_programada` | Consultas y vencimientos programados | `UNIQUE (nombre_task)` |

**Escritura de estado**: `UPDATE emision SET estado = :nuevo WHERE referencia = :ref AND estado IN (:origenes)`. Si actualiza 0 filas, el aviso es viejo o repetido y se ignora.

**Outbox**: el resultado se publica justo después del commit. Si esa publicación falla, la siguiente consulta o el vencimiento de esa referencia publica los pendientes.

**Simulador del proveedor**: expone `POST /emisiones`, que acepta y, pasados N segundos, envía al gateway un webhook firmado con HMAC. También expone `GET /emisiones/{referencia}`. El comportamiento de cada prueba se elige con un sufijo en la referencia (SUPUESTO de diseño del simulador):

| Sufijo | Comportamiento |
|---|---|
| `-OK` | Aprueba |
| `-RECHAZO` | Rechaza |
| `-SINWEBHOOK` | No envía webhook; la aprobación solo se ve por consulta |
| `-DUP` | Envía el webhook 3 veces y un PENDIENTE después del APROBADO |
| `-5XX` | Responde 500 a la solicitud de emisión |
| `-LENTO` | Responde en 8 s |
| `-NUNCA` | Nunca aprueba |

---

## 3. Historias

Cada historia está terminada cuando:
- los tests se vieron fallar antes de pasar;
- todos los tests pasan en CI;
- el dominio tiene al menos 80 % de cobertura de mutación (PIT);
- el código tuvo revisión.

| Sem. | Historia | Tests a escribir primero | Implementación mínima | Refactor |
|---|---|---|---|---|
| 1 | **1. Proyecto y CI** | El dominio no puede depender de Spring ni de RabbitMQ (ArchUnit) | Un módulo Maven (Java 21, Spring Boot 3.x), pipeline con tests, JaCoCo y PIT | — |
| 1 | **2. Firma HMAC** | Firma válida; firma o cuerpo alterados; timestamp a más de 5 min hacia atrás o adelante; secreto anterior aceptado; hex en mayúsculas; headers malformados | `VerificadorFirma` | — |
| 1 | **3. Docker compose** | `make local-up` deja Postgres, RabbitMQ (con el plugin activo y las colas creadas) y el simulador sanos en menos de 90 s | `compose.yaml`, definiciones de colas de RabbitMQ, `Makefile` | — |
| 2 | **4. Máquina de estados** | Todas las transiciones permitidas pasan y las demás se rechazan; PENDIENTE no cambia nada; aviso duplicado o desordenado sin efecto; APROBADO tardío tras VENCIDA corrige; monto distinto va a revisión sin cambiar el estado | `MatrizTransiciones`, `Emision` | Un único punto de decisión |
| 2 | **5. Plazos y resultados** | Fecha límite = solicitud + 24 h; vencimiento prematuro ignorado; delays de la cadena; nombres de tarea determinísticos; secuencia y `corrige_secuencia` correctos | `PlazosSla`, `CalendarioConsultas`, `HistorialResultados` | — |
| 2 | **6. Casos de uso** (con fakes en memoria) | Se persiste y se programan las tareas antes de llamar al proveedor; error → reintento diferido y, en el intento 5, DLQ; webhook nuevo aplicado y repetido ignorado; consulta pendiente programa la siguiente; consultas agotadas → REQUIERE_REVISION; vencimiento → ERROR provisional | Servicios de solicitud, webhook, consulta, vencimiento y DLQ | Lógica común de "aplicar decisión" |
| 3 | **7. Base de datos** (Testcontainers) | Las migraciones aplican; insertar dos veces deja una fila; UPDATE con origen no permitido → 0 filas; dos hilos sobre la misma referencia no duplican resultados | Flyway y repositorios JDBC | — |
| 3 | **8. Cliente del proveedor** (WireMock en tests) | Envía `Idempotency-Key`; 8 s responde OK; 11 s → timeout; 5xx → error transitorio y una sola llamada; consulta de estado mapeada | `RestClient` con timeout de 10 s | — |
| 3 | **9. Mensajería** (Testcontainers con RabbitMQ) | Ack tras procesar; un fallo reprograma a los 10 s con el intento incrementado; el quinto fallo va a la DLQ; una consulta diferida llega después de su delay; una tarea duplicada no se publica dos veces; el resultado se publica tras el commit y la siguiente tarea republica los pendientes; con `prefetch = 20` nunca hay más de 20 en vuelo | Adaptadores de RabbitMQ (Spring AMQP), outbox | `ConsumidorBase` común |
| 3 | **10. Webhook** (MockMvc) | Firma válida → 200; inválida → 401; duplicado → 200; el cuerpo crudo no se re-serializa | `WebhookPagosController` con `byte[]` | — |
| 4 | **11. Simulador del proveedor** | Cada sufijo produce su comportamiento; los webhooks salen firmados con el secreto de `.env` | Contenedor pequeño (Java, `HttpServer` del JDK) | — |
| 4 | **12. Prueba completa local** | Con 2 instancias del gateway: los 7 sufijos dan el resultado esperado; 10 rps durante 10 min sin pólizas duplicadas; `docker kill` de una instancia en mitad del flujo no pierde ni duplica nada; vencimiento probado con delays acortados (perfil `local-rapido`) | Scripts `publicar-solicitud.sh`, `ver-resultados.sh` y `reprocesar-dlq.sh`, más un test end-to-end automatizado | — |

---

## 4. Uso diario

```bash
make local-up
```
```bash
./scripts/publicar-solicitud.sh SOAT-000001-OK
```
```bash
./scripts/ver-resultados.sh
```
```bash
make local-down
```

La consola de RabbitMQ (`http://localhost:15672`) muestra las colas, la DLQ y los mensajes diferidos.

---

## 5. Supuestos que no bloquean (se confirman cuando haya proveedor y core reales)

| Supuesto | Valor usado en el MVP |
|---|---|
| Rutas y respuestas del proveedor | `POST /emisiones` y `GET /emisiones/{referencia}`, tal como los implementa el simulador |
| Formato de `X-Timestamp` | Epoch en segundos |
| Origen de `numero_poliza` | Viene en el webhook y en la consulta |
| Estados del proveedor | APROBADO, PENDIENTE, RECHAZADO |
| Mensaje de solicitud del core | `referencia`, `correlation_id`, `solicitado_en`, `prima_cotizada_centavos`, `placa` |
| Plugin de mensajes diferidos | Compatible con la versión de RabbitMQ elegida. Si no lo fuera, se usan colas de espera con TTL fijo para los delays fijos, y el vencimiento consulta el tiempo restante y se re-difiere |

---

## 6. Si más adelante se despliega en GCP

Hay que reemplazar los adaptadores de RabbitMQ por Pub/Sub y Cloud Tasks, agregar OIDC al endpoint de tareas, Secret Manager y Terraform, y completar la resiliencia (limitador adaptativo, circuit breaker y `Retry-After`). El dominio y los casos de uso no cambian. El detalle está en el plan completo.
