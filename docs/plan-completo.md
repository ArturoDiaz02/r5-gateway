# Plan: Gateway de emisión SOAT (TDD estricto)

**Modo**: conversacional (requerimientos en texto libre)
**MVP**: el alcance reducido para probar primero está en `plan-mvp.md`. Se ejecuta 100 % en local, con Postgres y RabbitMQ en lugar de los servicios de GCP. Este documento queda como hoja de ruta para un despliegue futuro en GCP.
**Complejidad**: Grande (~190–200 días-persona; 9–12 semanas con 4 desarrolladores)
**Patrones a replicar**: el directorio de trabajo está vacío y no hay código existente. Este plan define las convenciones desde cero; no se replica ningún patrón previo.

Convenciones del documento:
- **SUPUESTO**: dato que no viene en el contexto. Se debe validar antes de cerrar la historia que depende de él.
- **O-xx**: observación al diseño (ver §14).
- **SP-x**: spike con timebox.
- **D-/A-/I-/C-/W-/T-/P-/Q-/L-/B-/S-/X-/TF-/CF-/OB-**: identificadores de tests (dominio, aplicación, integración, contrato, webhook, tareas, Pub/Sub, DLQ, limitador, breaker, sistema, caos, Terraform, configuración, observabilidad).

---

## 1. Resumen ejecutivo

El gateway recibe solicitudes de emisión del core por Pub/Sub, las persiste en Cloud SQL y programa en Cloud Tasks las consultas de respaldo y el vencimiento antes de llamar al proveedor con `Idempotency-Key = referencia`. Webhook, consultas, DLQ y vencimiento convergen en un único agregado `Emision`, gobernado por una matriz de transiciones explícita y persistido con un UPDATE condicional. Los resultados salen por un outbox transaccional hacia `emision-resultados`, con secuencia y correcciones controladas.
El plan se construye de adentro hacia afuera: primero un dominio puro, sin Spring y con mutation testing ≥ 80 %, que se puede demostrar en la semana 3 con escenarios ejecutables. Después vienen los casos de uso con fakes, los adaptadores con Testcontainers, WireMock y el emulador de Pub/Sub, la resiliencia y, al final, las pruebas de sistema, carga y caos.
Los riesgos principales son la capacidad desconocida del proveedor (SP-1), el relay del outbox sin crons (O-01), el pinning de virtual threads con `BlockingLimiter` (O-09) y las transiciones que la matriz no cubre cuando la DLQ y la cadena de consultas se cruzan (O-04, O-05). Todos quedan como spikes u observaciones que bloquean historias concretas.

---

## 2. Pirámide de pruebas

| Nivel | Cantidad estimada | Herramientas | Riesgo que cubre |
|---|---|---|---|
| Unitario de dominio | ~190 (49 de la matriz parametrizados) | JUnit 5, AssertJ, jqwik (propiedades), PIT | Reglas de negocio: transiciones, desorden, duplicados, correcciones, plazos del SLA, HMAC |
| Reglas de arquitectura | ~14 | ArchUnit, Maven Enforcer | Fuga de Spring o infraestructura al dominio, `sleep`, `@Scheduled`, Redis, comparación no constante |
| Aplicación (casos de uso con fakes) | ~60 | JUnit 5, fakes en memoria, `Clock` controlable, SimpleMeterRegistry | Orquestación: orden "persistir → programar → llamar", ack/nack, unidad de trabajo, handler de fallidas |
| Contrato de puerto (fake = real) | ~30 (suites abstractas ejecutadas 2 veces) | JUnit 5 con herencia de tests | Que los fakes no mientan respecto al adaptador real |
| Integración de adaptadores | ~70 | Testcontainers (Postgres 16), emulador de Pub/Sub, WireMock, gRPC in-process (Cloud Tasks), Toxiproxy | SQL real, concurrencia, ON CONFLICT, timeouts, 429/5xx, nombres de tasks, ordering key |
| Slice web / entrada | ~35 | `@WebMvcTest`, MockMvc, Spring Security Test, JWKS local | Bytes crudos, códigos HTTP, OIDC, MDC |
| Contrato de mensajes / API | ~25 | JSON Schema (networknt), Pact de mensajes (SUPUESTO: el core acepta), grabaciones de WireMock del sandbox | Deriva de contratos con el core y el proveedor |
| Infraestructura como código | ~20 | `terraform test`, tflint, checkov, kubeconform, conftest (OPA) | DLQ = 5, backoff de 10 s, HPA sin CPU, presupuesto de conexiones, IAM mínimo |
| Sistema end-to-end | ~10 | docker compose o Testcontainers, fake de Cloud Tasks con compresión de tiempo | Flujos completos y correcciones de extremo a extremo |
| Carga | 3 escenarios | k6 (HTTP) y harness Java (publicación a Pub/Sub) | 50 avisos/s, ~400 llamadas concurrentes, presupuesto de conexiones, latencia |
| Caos | ~10 | Toxiproxy, `kubectl delete pod`, failover de Cloud SQL en staging | Pod muerto entre pasos, proveedor caído o lento, duplicados concurrentes |

Regla: ninguna prueba de nivel superior repite un caso que ya cubre un nivel inferior. Los niveles altos solo verifican el cableado y las propiedades emergentes.

---

## 3. Catálogo de tests del dominio (especificación ejecutable)

Clases bajo prueba: `MatrizTransiciones`, `Emision`, `PlazosSla`, `CalendarioConsultas`, `HistorialResultados`, `VerificadorFirma`. Todas son Java puro y el tiempo se inyecta como `Instant` o `Clock`.
El agregado no persiste nada: devuelve una `Decision`, una interfaz sellada con las variantes `Transicionar(destino, origenesPermitidos, resultado?)`, `Ignorar(motivo)` y `RevisionManual(motivo)`.

### 3.1 Matriz de transiciones (`MatrizTransicionesTest`)

| ID | Test | Given | When | Then |
|---|---|---|---|---|
| D-M01 | `permiteTransicionesDeLaMatriz` (parametrizado, 18 casos) | Cada par permitido: SOLICITADA→{ENVIADA, FALLIDA_ENVIO, VENCIDA, EMITIDA, RECHAZADA}; ENVIADA→{REQUIERE_REVISION, VENCIDA, EMITIDA, RECHAZADA}; REQUIERE_REVISION→{VENCIDA, EMITIDA, RECHAZADA}; FALLIDA_ENVIO→{ENVIADA, VENCIDA, EMITIDA, RECHAZADA}; VENCIDA→{EMITIDA, RECHAZADA} | `permite(desde, hacia)` | `true` |
| D-M02 | `rechazaTodoLoDemas` (parametrizado, 31 casos) | Los 49 pares del producto cartesiano menos los 18 permitidos, incluidas las 7 autotransiciones | `permite(desde, hacia)` | `false` |
| D-M03 | `finalesNoTienenSalida` | EMITIDA, RECHAZADA | `destinosDesde(e)` | Conjunto vacío |
| D-M04 | `origenesHaciaEmitida` | — | `origenesPermitidos(EMITIDA)` | {SOLICITADA, ENVIADA, REQUIERE_REVISION, FALLIDA_ENVIO, VENCIDA} |
| D-M05 | `origenesHaciaRechazada` | — | `origenesPermitidos(RECHAZADA)` | El mismo conjunto que D-M04 |
| D-M06 | `origenesHaciaVencida` | — | `origenesPermitidos(VENCIDA)` | {SOLICITADA, ENVIADA, REQUIERE_REVISION, FALLIDA_ENVIO} |
| D-M07 | `origenesHaciaEnviada` | — | `origenesPermitidos(ENVIADA)` | {SOLICITADA, FALLIDA_ENVIO} |
| D-M08 | `origenesHaciaRequiereRevision` | — | `origenesPermitidos(REQUIERE_REVISION)` | {ENVIADA} |
| D-M09 | `origenesHaciaFallidaEnvio` | — | `origenesPermitidos(FALLIDA_ENVIO)` | {SOLICITADA} |
| D-M10 | `solicitadaSoloPorCreacion` | — | `origenesPermitidos(SOLICITADA)` | Conjunto vacío |
| D-M11 | `origenesConsistentesConPermite` (propiedad, jqwik) | Cualquier destino d | `origenesPermitidos(d)` | Es igual a {o : permite(o, d)} |
| D-M12 | `soloEmitidaYRechazadaSonFinales` | Los 7 estados | `esFinal()` | `true` solo para EMITIDA y RECHAZADA |
| D-M13 | `matrizExhaustiva` | Todos los valores de `EstadoEmision` | Se construye la matriz | Cada estado tiene fila; agregar un estado sin fila rompe el test |

### 3.2 Agregado `Emision`: envío (`EmisionEnvioTest`)

| ID | Test | Given | When | Then |
|---|---|---|---|---|
| D-E01 | `nuevaSolicitudNaceSolicitada` | Solicitud con `solicitado_en = T` y prima P | `Emision.solicitar(...)` | Estado SOLICITADA; `fecha_limite = T + 24 h` |
| D-E02 | `enviadaDesdeSolicitada` | SOLICITADA | `confirmarEnvio()` | `Transicionar(ENVIADA)` sin resultado publicable |
| D-E03 | `enviadaDesdeFallidaEnvioPorReproceso` | FALLIDA_ENVIO | `confirmarEnvio()` | `Transicionar(ENVIADA)` |
| D-E04 | `carreraWebhookAntesDeRespuesta` | EMITIDA (el webhook ganó la carrera) | `confirmarEnvio()` | `Ignorar(AVISO_OBSOLETO)` |
| D-E05 | `sinAutotransicionEnEnviada` | ENVIADA | `confirmarEnvio()` | `Ignorar(AVISO_OBSOLETO)` |
| D-E06 | `fallidaEnvioDesdeSolicitada` | SOLICITADA | `registrarFalloEnvio()` | `Transicionar(FALLIDA_ENVIO)` sin resultado (O-12) |
| D-E07 | `fallidaEnvioTardiaSeIgnora` | ENVIADA | `registrarFalloEnvio()` | `Ignorar(AVISO_OBSOLETO)` |
| D-E08 | `fallidaEnvioSobreFinalSeIgnora` | EMITIDA o RECHAZADA | `registrarFalloEnvio()` | `Ignorar` |

### 3.3 Agregado `Emision`: avisos del proveedor (`EmisionAvisosTest`)

`AvisoProveedor(origen ∈ {WEBHOOK, CONSULTA, VENCIMIENTO}, estado, montoCentavos, numeroPoliza?, eventoId?)`. **No contiene `X-Timestamp`**, así que es imposible ordenar por él.

| ID | Test | Given | When | Then |
|---|---|---|---|---|
| D-A01 | `aprobadoEnEnviadaEmite` | ENVIADA, prima = 89130000 | APROBADO con monto 89130000 | `Transicionar(EMITIDA)` + EXITOSA, secuencia 1, definitivo |
| D-A02 | `aprobadoEnSolicitadaPorCarrera` | SOLICITADA | APROBADO | `Transicionar(EMITIDA)` |
| D-A03 | `pendienteNoCambiaEstado` | ENVIADA | PENDIENTE | `Ignorar(PENDIENTE_SIN_EFECTO)` |
| D-A04 | `pendienteDespuesDeAprobado` | EMITIDA | PENDIENTE | `Ignorar` sin resultado |
| D-A05 | `rechazadoEnEnviada` | ENVIADA | RECHAZADO (SUPUESTO: el valor existe, O-14) | `Transicionar(RECHAZADA)` + ERROR, motivo RECHAZADA, definitivo |
| D-A06 | `aprobadoRepetidoNoReemite` | EMITIDA | APROBADO | `Ignorar(DUPLICADO_U_OBSOLETO)`; ningún resultado nuevo |
| D-A07 | `rechazoDespuesDeAprobado` | EMITIDA | RECHAZADO | `Ignorar(CONFLICTO_PROVEEDOR)` y se señala el conflicto (TODO 2) |
| D-A08 | `aprobadoDespuesDeRechazo` | RECHAZADA | APROBADO | `Ignorar(CONFLICTO_PROVEEDOR)` |
| D-A09 | `aprobacionTardiaTrasVencida` | VENCIDA con resultado 1 = ERROR provisional | APROBADO | `Transicionar(EMITIDA)` + EXITOSA, secuencia 2, `corrige_secuencia = 1` |
| D-A10 | `rechazoTardioTrasVencida` | VENCIDA con resultado 1 provisional | RECHAZADO | RECHAZADA + ERROR definitivo, secuencia 2, corrige 1 |
| D-A11 | `aprobadoTrasFallidaEnvio` | FALLIDA_ENVIO, sin resultados | APROBADO | EMITIDA + EXITOSA, secuencia 1, sin corrección |
| D-A12 | `aprobadoTrasRequiereRevision` | REQUIERE_REVISION | APROBADO | EMITIDA + EXITOSA, secuencia 1 |
| D-A13 | `rechazoTrasFallidaEnvio` | FALLIDA_ENVIO | RECHAZADO | RECHAZADA + ERROR definitivo |
| D-A14 | `estadoProveedorDesconocido` | ENVIADA | Estado "XYZ" | `Ignorar(ESTADO_DESCONOCIDO)` |
| D-A15 | `nuncaSeRechazaPorFechaLimite` | ENVIADA, ahora = `fecha_limite` + 3 días | APROBADO | `Transicionar(EMITIDA)` |
| D-A16 | `mismaDecisionParaCualquierOrigen` (parametrizado) | Estados × origen ∈ {WEBHOOK, CONSULTA, VENCIMIENTO} | El mismo aviso | La misma decisión |
| D-A17 | `aprobadoSinNumeroPoliza` (SUPUESTO, TODO 7) | ENVIADA | APROBADO sin `numero_poliza` | `Transicionar(EMITIDA)` con póliza pendiente de completar por consulta (O-13) |
| D-A18 | `secuenciaDesordenadaConvergente` (propiedad) | Permutaciones de {PENDIENTE, APROBADO, PENDIENTE, APROBADO duplicado} | Se aplican en cualquier orden | Estado final EMITIDA y un único resultado |

### 3.4 Validación de monto (`EmisionMontoTest`)

| ID | Test | Given | When | Then |
|---|---|---|---|---|
| D-V01 | `montoIgualTransiciona` | ENVIADA, prima 89130000 | APROBADO 89130000 | EMITIDA |
| D-V02 | `montoDistintoVaARevision` | ENVIADA, prima 89130000 | APROBADO 89130001 | `RevisionManual(MONTO_DISCREPANTE)`; el estado no cambia |
| D-V03 | `rechazoNoValidaMonto` (SUPUESTO, O-08) | ENVIADA | RECHAZADO con monto distinto | RECHAZADA |
| D-V04 | `montoAusenteEnAprobado` | ENVIADA | APROBADO sin monto | `RevisionManual(MONTO_AUSENTE)` |
| D-V05 | `montoNoPositivoInvalido` | — | Aviso con monto ≤ 0 | Excepción de aviso inválido |
| D-V06 | `sinTolerancia` | Prima P | Monto P ± 1 centavo | `RevisionManual` |
| D-V07 | `montoDistintoEnVencimiento` | ENVIADA, ahora ≥ `fecha_limite` − 1 h | Consulta final APROBADO con monto distinto | VENCIDA + señal de revisión (O-08) |

### 3.5 Consultas agotadas (`EmisionConsultasAgotadasTest`)

| ID | Test | Given | When | Then |
|---|---|---|---|---|
| D-C01 | `agotadasEnEnviada` | ENVIADA | `agotarConsultas()` | `Transicionar(REQUIERE_REVISION)` sin resultado publicable |
| D-C02 | `agotadasEnSolicitada` | SOLICITADA | `agotarConsultas()` | `Ignorar(TRANSICION_NO_PERMITIDA)` (O-04) |
| D-C03 | `agotadasEnFallidaEnvio` | FALLIDA_ENVIO | `agotarConsultas()` | `Ignorar` (O-04) |
| D-C04 | `agotadasEnFinal` | EMITIDA | `agotarConsultas()` | `Ignorar` |

### 3.6 Vencimiento (`EmisionVencimientoTest`)

| ID | Test | Given | When | Then |
|---|---|---|---|---|
| D-X01 | `vencimientoPrematuroSeIgnora` | ENVIADA, ahora = `fecha_limite` − 1 h − 1 s | `vencer(ahora, consultaFinal)` | `Ignorar(VENCIMIENTO_PREMATURO)` |
| D-X02 | `vencimientoEnElBordeProcede` | ahora = `fecha_limite` − 1 h | `vencer` | Procede (borde inclusivo) |
| D-X03 | `venceSinAprobacion` | ENVIADA | Consulta final PENDIENTE | VENCIDA + ERROR VENCIDA, `definitivo = false`, secuencia 1 |
| D-X04 | `consultaFinalAprobada` | ENVIADA | Consulta final APROBADO | EMITIDA + EXITOSA |
| D-X05 | `consultaFinalRechazada` | ENVIADA | Consulta final RECHAZADO | RECHAZADA + ERROR definitivo |
| D-X06 | `consultaFinalSinRespuesta` | ENVIADA | Consulta final no disponible (timeout o breaker abierto) | VENCIDA |
| D-X07 | `vencerFinalSeIgnora` | EMITIDA | `vencer` | `Ignorar` |
| D-X08 | `vencerDesdeRevision` | REQUIERE_REVISION | Consulta final PENDIENTE | VENCIDA |
| D-X09 | `vencerDesdeFallidaEnvio` | FALLIDA_ENVIO | Sin respuesta | VENCIDA |
| D-X10 | `vencimientoDuplicado` | VENCIDA | `vencer` | `Ignorar`; no hay segundo ERROR |
| D-X11 | `vencerDesdeSolicitada` | SOLICITADA (proveedor nunca respondió) | Sin respuesta | VENCIDA |

### 3.7 Plazos del SLA (`PlazosSlaTest`)

| ID | Test | Given | When | Then |
|---|---|---|---|---|
| D-P01 | `fechaLimiteEs24h` | `solicitado_en` T | `fechaLimite()` | T + 24 h |
| D-P02 | `instanteVencimiento` | T | `instanteVencimiento()` | T + 23 h |
| D-P03 | `ventanaReprocesoDlq` (parametrizado) | ahora = T+23h−1s / T+23h / T+23h+1s | `puedeReprocesar(ahora)` | true / false / false |
| D-P04 | `enRiesgoA4Horas` | No final, ahora = `fecha_limite` − 3 h 59 min | `enRiesgo(ahora)` | true; con 4 h 01 min, false |
| D-P05 | `soloInstantUtc` | — | Revisión de API | Solo acepta `Instant` (verificado por compilación y ArchUnit R9) |
| D-P06 | `solicitadoEnFuturo` (SUPUESTO tolerancia de 5 min, O-15) | `solicitado_en` = ahora + 10 min | Crear | Excepción de solicitud inválida |

### 3.8 Calendario de consultas (`CalendarioConsultasTest`)

| ID | Test | Given | When | Then |
|---|---|---|---|---|
| D-K01 | `delaysDefinidos` (parametrizado) | Intentos 1..5 | `delay(i)` | 5 s, 1 min, 10 min, 20 min, 30 min |
| D-K02 | `seAgotaTrasQuinto` | Intento 5 ejecutado | `siguiente(5)` | `Optional.empty()` |
| D-K03 | `intentoFueraDeRango` | 0 o 6 | `delay(i)` | `IllegalArgumentException` |
| D-K04 | `nombreDeterministico` | ("SOAT-883214", 2) dos veces | `nombreTarea` | Nombres idénticos |
| D-K05 | `nombreDistintoPorIntento` | Intentos 1 y 2 | `nombreTarea` | Nombres distintos |
| D-K06 | `prefijoHashDorado` | "SOAT-883214" | `prefijo()` | `b24cc06b` (primeros 8 hex de SHA-256) |
| D-K07 | `nombreValidoParaCloudTasks` (propiedad) | Referencias arbitrarias | `nombreTarea` | Cumple `[A-Za-z0-9_-]{1,500}`; la referencia inválida se rechaza |
| D-K08 | `nombreVencimientoDistinto` | Misma referencia | `nombreVencimiento` vs `nombreTarea(…, i)` | Nunca colisionan (sufijo `-v` frente a `-c{i}`) |
| D-K09 | `delaysEntreIntentos` (SUPUESTO, O-06) | Ejecución del intento i en t | `programadaPara(i+1, t)` | t + delay(i+1) |
| D-K10 | `cadenaTerminaAntesDelVencimiento` | — | Suma de delays | 61 min 5 s < 23 h |
| D-K11 | `generacionTrasReproceso` (O-05) | Generación g | `nombreTarea(ref, g, i)` | Nombre distinto por generación |

### 3.9 Secuencia y corrección de resultados (`HistorialResultadosTest`)

| ID | Test | Given | When | Then |
|---|---|---|---|---|
| D-R01 | `primerResultadoSecuencia1` | Historial vacío | Registrar EMITIDA | Secuencia 1, `corrige_secuencia = null` |
| D-R02 | `exitosaCompleta` | — | EMITIDA con póliza N | `resultado = EXITOSA`, `motivo = null`, `definitivo = true`, `numero_poliza = N` |
| D-R03 | `errorRechazada` | — | RECHAZADA | ERROR, motivo RECHAZADA, definitivo |
| D-R04 | `errorVencidaProvisional` | — | VENCIDA | ERROR, motivo VENCIDA, `definitivo = false` |
| D-R05 | `correccionAExitosa` | [1: ERROR VENCIDA provisional] | EMITIDA | Secuencia 2, `corrige_secuencia = 1`, definitivo |
| D-R06 | `correccionARechazada` | [1: ERROR VENCIDA provisional] | RECHAZADA | Secuencia 2, corrige 1, definitivo |
| D-R07 | `noSeCorrigeUnDefinitivo` | [1: EXITOSA definitivo] | Cualquier resultado | `IllegalStateException` (invariante de defensa en profundidad) |
| D-R08 | `estadosIntermediosNoPublican` (parametrizado) | — | ENVIADA, FALLIDA_ENVIO, REQUIERE_REVISION | Sin resultado |
| D-R09 | `propagaCorrelationYReloj` | `correlation_id` C, reloj fijo | Registrar | `correlation_id = C`; `ocurrido_en` = instante de la decisión (SUPUESTO, O-16) |
| D-R10 | `secuenciaSinHuecos` (propiedad) | Secuencias válidas de transiciones | Registrar todas | Secuencias 1..n estrictamente crecientes |
| D-R11 | `soloUnProvisionalPrevio` | [1: VENCIDA] | Segundo VENCIDA | Lo impide la matriz; el historial lanza excepción |

### 3.10 Verificador HMAC (`VerificadorFirmaTest`)

Vector dorado: secreto `secreto-prueba-v1`, `X-Timestamp = 1794233002` (2026-11-09T14:03:22Z) y el cuerpo compacto del ejemplo del proveedor:
`{"id":"evt_01JC8Q2W7M","tipo":"pago.actualizado","creado_en":"2026-11-09T14:03:21.482Z","data":{"referencia":"SOAT-883214","estado":"APROBADO","monto_centavos":89130000}}`
→ `X-Firma = 6e4c5cd3315182229277b2c5fac58caeb142de384638706b51afd07ace65a6d3`

| ID | Test | Given | When | Then |
|---|---|---|---|---|
| D-H01 | `firmaValidaVectorDorado` | Vector dorado, ahora = ts + 1 s | `verificar` | VALIDA |
| D-H02 | `firmaAlterada` | Un carácter hex cambiado | `verificar` | FIRMA_INVALIDA |
| D-H03 | `cuerpoAlterado` | Un byte del cuerpo cambiado | `verificar` | FIRMA_INVALIDA |
| D-H04 | `cuerpoReserializado` | El mismo JSON con espacios u otro orden de claves | `verificar` | FIRMA_INVALIDA (prueba que se firman los bytes crudos) |
| D-H05 | `timestampViejo` | ts = ahora − 5 min − 1 s | `verificar` | FUERA_DE_VENTANA |
| D-H06 | `timestampFuturo` | ts = ahora + 5 min + 1 s | `verificar` | FUERA_DE_VENTANA |
| D-H07 | `bordesDeVentana` (SUPUESTO: inclusivos) | ts = ahora ± 5 min | `verificar` | VALIDA |
| D-H08 | `secretoAnteriorAceptado` | Firmado con v1; actual = v2, anterior = v1 | `verificar` | VALIDA |
| D-H09 | `secretoDesconocido` | Firmado con v0 | `verificar` | FIRMA_INVALIDA |
| D-H10 | `soloSecretoActual` | Anterior ausente | Firma con actual | VALIDA |
| D-H11 | `hexMayusculas` | Firma dorada en mayúsculas | `verificar` | VALIDA |
| D-H12 | `longitudIncorrecta` | 63 o 65 caracteres | `verificar` | MALFORMADA |
| D-H13 | `noHex` | Contiene "zz" | `verificar` | MALFORMADA |
| D-H14 | `timestampNoEpochSegundos` (SUPUESTO, TODO 7) | "abc", "", "1794233002.5", 13 dígitos (ms) | `verificar` | MALFORMADA |
| D-H15 | `headersAusentes` | Firma o timestamp nulos | `verificar` | MALFORMADA |
| D-H16 | `cuerpoVacio` | Cuerpo de 0 bytes | `verificar` | FIRMA_INVALIDA |
| D-H17 | `espaciosOSigno` | " 1794233002", "+1794233002" | `verificar` | MALFORMADA |
| D-H18 | `ventanaAntesQueHmac` | Firma válida y ts fuera de ventana | `verificar` | FUERA_DE_VENTANA |
| D-H19 | `prefijoNoSoportado` (SUPUESTO) | "sha256=…" | `verificar` | MALFORMADA |
| D-H20 | `comparacionTiempoConstante` | — | ArchUnit R8 | `VerificadorFirma` solo usa `MessageDigest.isEqual` sobre bytes decodificados; prohibido `String.equals` y `Arrays.equals` |

### 3.11 Escenarios ejecutables (valor demostrable de la fase 1, `EscenariosDominioTest`)

| ID | Línea de tiempo | Estado final y resultados |
|---|---|---|
| D-S01 | Solicitar → enviar → PENDIENTE → APROBADO → PENDIENTE tardío | EMITIDA; [1 EXITOSA] |
| D-S02 | Solicitar → APROBADO (carrera) → confirmar envío | EMITIDA; [1 EXITOSA] |
| D-S03 | Solicitar → enviar → 5 consultas PENDIENTE → vencer → APROBADO tardío | EMITIDA; [1 ERROR VENCIDA provisional, 2 EXITOSA corrige 1] |
| D-S04 | Solicitar → fallo de envío → reproceso → enviar → RECHAZADO | RECHAZADA; [1 ERROR RECHAZADA] |
| D-S05 | Solicitar → enviar → APROBADO con monto distinto → vencer | VENCIDA + revisión; [1 ERROR VENCIDA] |
| D-S06 | Solicitar → enviar → vencer prematuro → vencer a tiempo con APROBADO | EMITIDA; [1 EXITOSA] |

Un test adicional (`MatrizRenderizadaTest`) renderiza la matriz como tabla Markdown y la compara con la tabla de la especificación, para que la documentación no pueda desviarse del código.

---

## 4. Arquitectura de módulos

```
soat-gateway/                        (Maven multi-módulo, Java 21, BOM Spring Boot 3.x)
├── gateway-dominio/                 sin dependencias de producción (solo JDK)
│   └── co.aseguradora.soat.dominio
│       ├── emision/      Emision, EstadoEmision, MatrizTransiciones, Decision, AvisoProveedor,
│       │                 EstadoProveedor, OrigenAviso, MotivoIgnorado
│       ├── plazos/       PlazosSla, CalendarioConsultas, NombreTarea
│       ├── resultado/    ResultadoEmision, HistorialResultados, TipoResultado, MotivoResultado
│       └── seguridad/    VerificadorFirma, SecretosFirma, ResultadoVerificacion
├── gateway-aplicacion/              depende solo de gateway-dominio
│   └── co.aseguradora.soat.aplicacion
│       ├── puerto/entrada/  ProcesarSolicitud, RegistrarAviso, EjecutarConsulta,
│       │                    EjecutarVencimiento, ManejarFallidaEnvio, RelayResultados
│       ├── puerto/salida/   EmisionRepositorio, EventoProveedorRepositorio, OutboxRepositorio,
│       │                    ProgramadorTareas, ProveedorEmisiones, PublicadorResultados,
│       │                    ColaReintentos, EstadoCircuito, EmisionFallidaHandler, UnidadDeTrabajo
│       └── servicio/        implementaciones de los casos de uso
├── gateway-infraestructura/         adaptadores; Spring permitido
│   └── co.aseguradora.soat.infraestructura
│       ├── entrada/web/     WebhookPagosController, TareasController, SeguridadOidcConfig
│       ├── entrada/pubsub/  ConsumidorSolicitudes, ConsumidorDlq, ConsumidorReintentos
│       ├── salida/persistencia/  Jdbc*Repositorio (Flyway en resources/db/migration)
│       ├── salida/pubsub/   PublicadorPubSub, PublicadorColaReintentos
│       ├── salida/proveedor/ ClienteProveedorHttp, ResilienciaProveedor
│       ├── salida/tareas/   CloudTasksProgramador
│       └── observabilidad/  métricas, MDC, propagación de trazas
├── gateway-app/                     @SpringBootApplication, wiring y application.yml
├── gateway-pruebas-soporte/         fakes en memoria, suites de contrato de puerto, builders,
│                                    RelojControlable, contenedores compartidos (solo scope test)
├── gateway-sistema/                 E2E y caos (perfil Maven -Psistema)
├── carga/                           scripts k6 y harness de publicación
└── infra/
    ├── terraform/                   módulos pubsub, cloudtasks, cloudsql, iam, secrets, monitoring y sus tests
    └── k8s/                         manifiestos (Kustomize) y políticas conftest
```

### Reglas de ArchUnit (`ReglasArquitecturaTest`, módulo `gateway-app`, analiza todas las clases)

| ID | Regla |
|---|---|
| R1 | `..dominio..` no depende de `org.springframework..`, `jakarta..`, `com.google..`, `java.sql..`, `javax.sql..`, `com.fasterxml..`, `com.netflix..`, `io.github.resilience4j..` |
| R2 | `..aplicacion..` no depende de `..infraestructura..` ni de `org.springframework..` |
| R3 | `..infraestructura.entrada..` no depende de `..infraestructura.salida..` |
| R4 | Los adaptadores de entrada solo usan `..aplicacion.puerto.entrada..` |
| R5 | Solo `..salida.persistencia..` accede a `JdbcTemplate`, `DataSource` o `java.sql` |
| R6 | Ninguna clase de producción llama a `Thread.sleep`, `TimeUnit.sleep` ni `LockSupport.parkNanos` |
| R7 | Ningún método de producción es `synchronized`, para evitar pinning de virtual threads en Java 21 |
| R8 | `VerificadorFirma` no llama a `String.equals` ni a `Arrays.equals` y sí llama a `MessageDigest.isEqual` |
| R9 | Dominio y aplicación no llaman a `Instant.now()`, `LocalDateTime.now()` ni `System.currentTimeMillis()`; usan `Clock` |
| R10 | No hay ciclos entre slices `co.aseguradora.soat.(*)..` |
| R11 | Prohibido `@Scheduled`, `@EnableScheduling` y `@Retryable` (sin crons ni reintentos en código) |
| R12 | `..dominio.emision..` no depende de `..dominio.seguridad..` (el timestamp nunca entra al agregado) |
| R13 | Las clases de `..dominio..` son `final` o `record`, y sus campos son `final` |

Complemento físico: el Maven Enforcer `bannedDependencies` impide en `gateway-dominio` toda dependencia de compilación y en todos los módulos `redis.clients:*`, `io.lettuce:*`, `org.springframework.data:spring-data-redis` y `org.springframework.retry:*`.
Para ver cada regla fallar al menos una vez, se agrega una clase violadora en `src/test/fixtures` y un test que verifica que la regla la detecta.

---

## 5. Modelo de datos (Flyway, Postgres 16; SUPUESTO: la versión coincide con Cloud SQL)

### V1__emision.sql
```sql
CREATE TABLE emision (
  referencia              VARCHAR(64)  PRIMARY KEY,
  correlation_id          VARCHAR(64)  NOT NULL,
  estado                  VARCHAR(20)  NOT NULL,
  prima_cotizada_centavos BIGINT       NOT NULL,
  solicitado_en           TIMESTAMPTZ  NOT NULL,
  fecha_limite            TIMESTAMPTZ  NOT NULL,
  numero_poliza           VARCHAR(64),
  ultima_secuencia        INTEGER      NOT NULL DEFAULT 0,
  generacion_consultas    SMALLINT     NOT NULL DEFAULT 1,
  payload_solicitud       JSONB        NOT NULL,
  version                 BIGINT       NOT NULL DEFAULT 0,
  creado_en               TIMESTAMPTZ  NOT NULL DEFAULT now(),
  actualizado_en          TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT ck_emision_estado CHECK (estado IN ('SOLICITADA','ENVIADA','REQUIERE_REVISION',
             'FALLIDA_ENVIO','VENCIDA','EMITIDA','RECHAZADA')),
  CONSTRAINT ck_emision_prima CHECK (prima_cotizada_centavos > 0),
  CONSTRAINT ck_emision_fecha_limite CHECK (fecha_limite = solicitado_en + INTERVAL '24 hours'),
  CONSTRAINT ck_emision_secuencia CHECK (ultima_secuencia >= 0)
);
CREATE UNIQUE INDEX ux_emision_numero_poliza ON emision (numero_poliza) WHERE numero_poliza IS NOT NULL;
CREATE INDEX ix_emision_no_final_fecha_limite ON emision (fecha_limite)
  WHERE estado NOT IN ('EMITIDA','RECHAZADA');
```

### V2__evento_proveedor.sql
```sql
CREATE TABLE evento_proveedor (
  evento_id            VARCHAR(64)  PRIMARY KEY,
  referencia           VARCHAR(64)  NOT NULL,
  tipo                 VARCHAR(64)  NOT NULL,
  estado_proveedor     VARCHAR(32)  NOT NULL,
  monto_centavos       BIGINT,
  creado_en_proveedor  TIMESTAMPTZ  NOT NULL,
  recibido_en          TIMESTAMPTZ  NOT NULL DEFAULT now(),
  efecto               VARCHAR(20)  NOT NULL,
  cuerpo_crudo         BYTEA        NOT NULL,
  CONSTRAINT ck_evento_efecto CHECK (efecto IN ('APLICADO','IGNORADO','REVISION_MANUAL','DESCONOCIDA'))
);
CREATE INDEX ix_evento_referencia ON evento_proveedor (referencia, recibido_en);
```
Sin FK a `emision`, para poder auditar eventos de referencias desconocidas (O-11). Alta del evento: `INSERT ... ON CONFLICT (evento_id) DO NOTHING RETURNING evento_id`.

### V3__outbox.sql
```sql
CREATE TABLE outbox (
  id               BIGSERIAL    PRIMARY KEY,
  referencia       VARCHAR(64)  NOT NULL,
  secuencia        INTEGER      NOT NULL,
  ordering_key     VARCHAR(64)  NOT NULL,
  payload          JSONB        NOT NULL,
  creado_en        TIMESTAMPTZ  NOT NULL DEFAULT now(),
  publicado_en     TIMESTAMPTZ,
  intentos         INTEGER      NOT NULL DEFAULT 0,
  CONSTRAINT ux_outbox_ref_secuencia UNIQUE (referencia, secuencia),
  CONSTRAINT ck_outbox_secuencia CHECK (secuencia >= 1),
  CONSTRAINT ck_outbox_ordering CHECK (ordering_key = referencia)
);
CREATE INDEX ix_outbox_pendiente ON outbox (referencia, secuencia) WHERE publicado_en IS NULL;
```

### V4__consulta_programada.sql
```sql
CREATE TABLE consulta_programada (
  referencia       VARCHAR(64)  NOT NULL,
  tipo             VARCHAR(12)  NOT NULL,
  generacion       SMALLINT     NOT NULL,
  intento          SMALLINT     NOT NULL,
  nombre_task      VARCHAR(500) NOT NULL,
  programada_para  TIMESTAMPTZ  NOT NULL,
  estado           VARCHAR(12)  NOT NULL DEFAULT 'PROGRAMADA',
  ejecutada_en     TIMESTAMPTZ,
  PRIMARY KEY (referencia, tipo, generacion, intento),
  CONSTRAINT ux_consulta_nombre UNIQUE (nombre_task),
  CONSTRAINT ck_consulta_tipo CHECK (tipo IN ('CONSULTA','VENCIMIENTO','RELAY')),
  CONSTRAINT ck_consulta_estado CHECK (estado IN ('PROGRAMADA','EJECUTADA','DESCARTADA')),
  CONSTRAINT ck_consulta_intento CHECK (intento BETWEEN 0 AND 5)
);
```

### UPDATE condicional (núcleo de la persistencia)
```sql
UPDATE emision
   SET estado = :destino,
       numero_poliza = COALESCE(:numeroPoliza, numero_poliza),
       ultima_secuencia = ultima_secuencia + :incrementoSecuencia,
       version = version + 1,
       actualizado_en = now()
 WHERE referencia = :referencia
   AND estado = ANY(:origenesPermitidos)
RETURNING version, ultima_secuencia;
```
`:origenesPermitidos` sale siempre de `MatrizTransiciones.origenesPermitidos(destino)`. Cero filas significa que el aviso es viejo o repetido. La transición, la fila del outbox y el evento se escriben en **una sola transacción**. `UNIQUE(referencia, secuencia)` del outbox bloquea la carrera descrita en O-03.

### Tests de esquema (historia H3.1)

| ID | Test | Given | When | Then |
|---|---|---|---|---|
| I-F01 | `migracionesAplicanEnLimpio` | Postgres 16 vacío (Testcontainers) | Flyway `migrate` y `validate` | Sin errores; 4 versiones aplicadas |
| I-F02 | `estadoInvalidoRechazado` | Esquema | INSERT con estado 'X' | Viola `ck_emision_estado` |
| I-F03 | `eventoDuplicadoNoInserta` | Evento evt_1 | `INSERT ... ON CONFLICT DO NOTHING` | 0 filas y ninguna excepción |
| I-F04 | `outboxSecuenciaUnica` | (ref, 1) existe | INSERT (ref, 1) | Viola `ux_outbox_ref_secuencia` |
| I-F05 | `fechaLimiteCoherente` | — | INSERT con `fecha_limite` ≠ `solicitado_en` + 24 h | Viola el check |
| I-F06 | `polizaUnica` | Póliza N en ref A | UPDATE de ref B a N | Viola `ux_emision_numero_poliza` |
| I-F07 | `indiceDeRiesgoUsado` | 10 000 filas sembradas | `EXPLAIN` de la consulta de riesgo | Usa `ix_emision_no_final_fecha_limite` |
| I-F08 | `compatibilidadNmenos1` | Esquema N−1 con datos y versión anterior de la app | Migrar a N y ejecutar la suite de contrato del repositorio N−1 | Verde (expand/contract) |
| I-F09 | `nombreTaskUnico` | Nombre X existe | INSERT de X | Viola `ux_consulta_nombre` |

---

## 6. Contratos

### 6.1 Webhook `POST /webhooks/pagos` (entrada desde el proveedor)

| Condición | HTTP | Efecto |
|---|---|---|
| Firma válida, evento nuevo, aplicado, ignorado o enviado a revisión | 200 | Evento registrado con su efecto |
| Evento duplicado (`id` ya registrado) | 200 | Nada |
| Referencia desconocida (SUPUESTO, O-11) | 200 | Evento auditado como DESCONOCIDA, métrica y alerta |
| Headers ausentes, firma inválida, timestamp fuera de ventana o malformado | 401 | Cuerpo genérico, sin detalle; métrica por motivo |
| JSON inválido con firma válida | 400 | Métrica |
| `Content-Type` distinto de `application/json` | 415 | — |
| Cuerpo > 64 KB (SUPUESTO) | 413 | — |
| Conflicto de concurrencia (UNIQUE del outbox) o base de datos no disponible | 503 | Rollback; el proveedor reenvía (SUPUESTO: reintenta ante 5xx) |

El cuerpo se lee como `byte[]` (`@RequestBody byte[]`) y nunca se re-serializa antes de verificar la firma.

| ID | Test de contrato | Nivel | Given / When / Then |
|---|---|---|---|
| C-W01 | `ejemploDelProveedorCumpleEsquema` | Contrato | Dado el JSON de ejemplo del proveedor y `webhook-pago.schema.json`, al validarlo, resulta válido |
| C-W02 | `camposDesconocidosTolerados` | Contrato | Dado un payload con campos extra, al parsearlo, no falla (tolerant reader) |
| C-W03 | `vectorDoradoExtremoAExtremo` | Contrato | Dado el vector dorado de §3.10 contra la app levantada, al hacer POST, responde 200 |
| C-W04 | `grabacionesSandbox` | Contrato (nocturno) | Dadas las interacciones grabadas del sandbox (SP-1), al reproducirlas, todas parsean y verifican firma |

### 6.2 Endpoint de tareas (entrada desde Cloud Tasks)

- `POST /tareas/consultas`, cuerpo `{"referencia":"SOAT-883214","generacion":1,"intento":2}`
- `POST /tareas/vencimientos`, cuerpo `{"referencia":"SOAT-883214"}`
- `POST /tareas/relay`, cuerpo `{"referencia":"SOAT-883214","secuencia":1}` (O-01)
- Autenticación: `Authorization: Bearer <OIDC ID token>`. Se validan `iss` = `https://accounts.google.com`, `aud` = URL configurada, `email` = `sa-tareas-invocador@<proyecto>.iam.gserviceaccount.com` y `email_verified = true`, con firma JWKS de Google.
- Respuestas: 204 si se procesó o fue no-op idempotente; 400 si el cuerpo es inválido; 401 si el token falta o es inválido; 403 si la cuenta de servicio no es la esperada; 503 ante un error transitorio de infraestructura, para que Cloud Tasks reintente.
- Headers que se registran en el log: `X-CloudTasks-TaskName`, `X-CloudTasks-TaskRetryCount` y `traceparent`.

| ID | Test de contrato | Given / When / Then |
|---|---|---|
| C-T01 | `cuerpoTareaCumpleEsquema` | Dado lo que genera `CloudTasksProgramador`, al validarlo con `tarea-*.schema.json`, resulta válido; el mismo esquema valida la entrada del controller (productor = consumidor) |
| C-T02 | `oidcConfigCoincide` | Dada la `audience` del adaptador y la que valida el resource server, al compararlas, son la misma propiedad |

### 6.3 Mensajes de Pub/Sub

**Solicitud (`emision-solicitudes`), propuesta para el TODO 4:**
```json
{ "version": 1, "referencia": "SOAT-883214", "correlation_id": "c0a8...",
  "solicitado_en": "2026-11-09T14:00:00.000Z", "prima_cotizada_centavos": 89130000,
  "placa": "ABC123",
  "vehiculo": { "clase": "...", "modelo": 2020, "cilindraje": 1600 },
  "tomador": { "tipo_documento": "CC", "numero_documento": "..." },
  "vigencia_inicio": "2026-11-10" }
```
Atributos: `correlation_id`, `schema_version`, `traceparent`. Una solicitud inválida se confirma con ack y produce métrica y alerta, sin publicar resultado (O-10).

**Resultado (`emision-resultados`):** el contrato dado. Ordering key = `referencia`. Atributos: `correlation_id`, `schema_version`, `secuencia`.

**Reintento por breaker abierto (`emision-reintentos`, O-02):** `{"referencia":"...","correlation_id":"...","motivo":"CIRCUITO_ABIERTO","no_antes_de":"..."}`.

| ID | Test de contrato | Given / When / Then |
|---|---|---|
| C-S01 | `solicitudPropuestaCumpleEsquema` | Dados ejemplos válidos e inválidos contra `solicitud.schema.json`, al validarlos, se aceptan y rechazan según lo esperado |
| C-S02 | `pactoMensajeSolicitud` (SUPUESTO: el core adopta Pact) | Dado el pacto publicado por el core, al verificar el consumidor, verde |
| C-R01 | `resultadoCumpleEsquema` (parametrizado) | Dados los resultados EXITOSA, ERROR RECHAZADA, ERROR VENCIDA provisional y las dos correcciones, al validarlos con `resultado.schema.json`, resultan válidos y los campos `snake_case` son exactos |
| C-R02 | `pactoMensajeResultado` | Dado el gateway como proveedor del pacto, cuando el core lo verifica, verde |
| C-R03 | `motivoNullSoloEnExitosa` | Para EXITOSA, `motivo = null`; para ERROR, motivo no nulo |

### 6.4 Cliente del proveedor (salida). Rutas y payload: SUPUESTO hasta tener la documentación.

| Operación | Request | Respuestas y mapeo |
|---|---|---|
| Solicitar emisión | `POST {base}/emisiones` con `Idempotency-Key: <referencia>`, autenticación SUPUESTA (API key en Secret Manager) y timeout total de 10 s | 2xx → `Aceptada`; 409 → `Aceptada` (SUPUESTO: duplicado idempotente); 400/422 → `FalloPermanente` (O-07); 429 → `FalloTransitorio(retryAfter)`; 5xx, timeout o reset → `FalloTransitorio` |
| Consultar estado | `GET {base}/emisiones/{referencia}`, timeout de 10 s | 200 → `EstadoProveedor(estado, monto, numero_poliza?)`; 404 → `Desconocida`; resto como arriba |

| ID | Test de contrato | Given / When / Then |
|---|---|---|
| C-K01 | `stubsDesdeDocumentacion` | Dados los stubs de WireMock escritos desde la documentación del proveedor, al ejecutar la suite de integración contra ellos, verde |
| C-K02 | `derivaContraSandbox` (nocturno, SP-1) | Dado el sandbox del proveedor, al ejecutar la misma suite en modo grabación y comparar contra los stubs, no hay diferencias de esquema |

---

## 7. Fases e historias

**Escala de estimación**: S = 1–2 días-persona · M = 3–5 · L = 6–10.

**Definición de terminado estándar (DoD-E)**, aplicable a todas las historias:
1. El test se vio fallar antes de pasar. Evidencia: el PR contiene un commit `test(...)` en rojo antes del commit `feat(...)`. El job de CI `test-first` ejecuta los tests nuevos contra el commit padre de la implementación y exige que fallen.
2. Mutation score de PIT en `gateway-dominio` ≥ 80 %, objetivo del 100 % en `MatrizTransiciones` y `VerificadorFirma`.
3. ArchUnit R1–R13 y Maven Enforcer en verde.
4. Cobertura de líneas JaCoCo ≥ 90 % en dominio y ≥ 80 % en aplicación.
5. Code review aprobado y ningún TODO sin ticket.

Cada historia agrega su DoD específica al DoD-E.

### Fase 0: Arnés de pruebas

**H0.1 Esqueleto multi-módulo y reglas de arquitectura (M)**
1. *Tests primero*: R1–R13 (arquitectura). Cada regla tiene una clase violadora en fixtures y el test `reglaDetectaViolacion`. Ejemplo: dada `fixtures.dominio.UsaSpring`, que importa `@Component`, al evaluar R1 se reporta 1 violación.
2. *Implementación mínima*: POMs padre e hijos, Java 21, Enforcer con `bannedDependencies` y `ReglasArquitecturaTest`.
3. *Refactor*: extraer las reglas a una clase `ReglasSoat` reutilizable.
4. *DoD*: DoD-E y el build en CI en menos de 5 minutos.

**H0.2 Pipeline de CI con calidad (M)**
1. *Tests primero*: `CF-01 umbralMutacionRompeBuild`, de configuración. Dada una clase de dominio con un test débil sembrado, al ejecutar `mvn verify -Ppit`, el build falla por debajo del 80 %. Además, `jacocoUmbralRompeBuild`.
2. *Implementación mínima*: jobs de build, unit, ArchUnit, PIT (incremental por PR y completo cada noche), JaCoCo, integración (Testcontainers) y `test-first`.
3. *Refactor*: caché de Maven y de imágenes de Testcontainers; perfiles `-Pit` y `-Psistema`.
4. *DoD*: DoD-E y el pipeline bloqueando el merge.

**H0.3 Soporte de pruebas compartido (M)**
1. *Tests primero* (integración): `postgresContenedorReutilizable` (dos clases de test comparten la instancia y cada test arranca con esquema limpio), `emuladorPubSubCreaTopicYSuscripcion`, `wiremockExtensionLevanta`, `relojControlableAvanza`, `fakeProgramadorRegistraTareas`.
2. *Implementación mínima*: `ContenedoresCompartidos` (Postgres 16, `gcr.io/google.com/cloudsdktool/google-cloud-cli:emulators`), `RelojControlable extends Clock`, builders `UnaEmision()` y `UnAviso()`.
3. *Refactor*: anotación compuesta `@IntegracionSoat`.
4. *DoD*: DoD-E y la documentación de uso en el README del módulo de soporte.

**H0.4 Ambiente de pruebas en la máquina local (M)**. Detalle completo en §15; depende de SP-8.
1. *Tests primero* (sistema, ejecutados con `make local-verify`):
   - CF-02 `composeLevantaSano`: dado `docker compose up`, al consultar los healthchecks, Postgres, el emulador de Pub/Sub, el emulador de Cloud Tasks, el servidor OIDC simulado, WireMock y Toxiproxy responden en menos de 90 s.
   - CF-10 `bootstrapCreaRecursos`: después del bootstrap existen los topics, las suscripciones y las colas de §8.1 y §8.2, con los mismos nombres.
   - CF-11 `tareaLocalLlegaConOidc`: dada una tarea creada en el emulador de Cloud Tasks, cuando se despacha, la app la recibe con un token que su validador OIDC acepta (perfil `local`).
   - CF-12 `appArrancaEnPerfilLocal`: `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` deja `/actuator/health/readiness` en UP.
   - CF-13 `flujoFelizLocal`: el escenario S-01 corre contra el compose en menos de 2 min.
   - CF-14 `mismasImagenesQueTestcontainers`: las versiones de imagen de `compose.yaml` y de `ContenedoresCompartidos` salen del mismo archivo `versiones-imagenes.properties`.
2. *Implementación mínima*: `compose.yaml`, scripts de bootstrap, `application-local.yml`, `Makefile` con los objetivos `local-up`, `local-down`, `local-reset`, `local-verify`, `test`, `it`.
3. *Refactor*: `.env.example`; perfiles de compose (`core`, `observabilidad`, `caos`) para no levantar todo siempre.
4. *DoD*: DoD-E; un desarrollador nuevo levanta el ambiente en menos de 30 min siguiendo el README del módulo; el ambiente funciona en macOS con Apple Silicon y en Linux x86_64.

### Fase 1: Dominio puro (sin Spring ni infraestructura; demostrable en la semana 3)

| Historia | Tests primero (unitarios, G/W/T en §3) | Implementación mínima | Refactor esperado | DoD específica | Est. |
|---|---|---|---|---|---|
| **H1.1** Matriz de transiciones | D-M01–D-M13 | `enum EstadoEmision`, `MatrizTransiciones` con `EnumMap<Estado, EnumSet<Estado>>` | Derivar `origenesPermitidos` invirtiendo el mapa una sola vez | 100 % de mutación en la matriz | S |
| **H1.2** Envío y carrera | D-E01–D-E08 | `Emision.solicitar`, `confirmarEnvio`, `registrarFalloEnvio`; interfaz sellada `Decision` | Método privado `decidir(destino)` que consulta la matriz, único punto de decisión | Ningún `if` de estado fuera de `decidir` | S |
| **H1.3** Avisos del proveedor | D-A01–D-A18 | `aplicar(AvisoProveedor)` con mapeo EstadoProveedor→destino | `switch` con pattern matching sobre `EstadoProveedor`; tipo `Conflicto` | La propiedad D-A18 corre con 1000 permutaciones | L |
| **H1.4** Validación de monto | D-V01–D-V07 | Guarda previa a la transición a EMITIDA | Value object `Centavos` | — | S |
| **H1.5** Plazos y vencimiento | D-P01–D-P06, D-X01–D-X11 | `PlazosSla`, `Emision.vencer(ahora, Optional<AvisoProveedor>)` | El vencimiento reutiliza `aplicar` para la consulta final (un solo camino) | — | M |
| **H1.6** Calendario de consultas | D-K01–D-K11 | `CalendarioConsultas` con delays inyectables y `NombreTarea` | Delays como `List<Duration>` de configuración, con el default del contexto | Golden test del prefijo | S |
| **H1.7** Secuencia y corrección | D-R01–D-R11 | `HistorialResultados` que produce `ResultadoEmision` | Integrarlo en la `Decision` (el resultado viaja con la transición) | — | M |
| **H1.8** Verificador HMAC | D-H01–D-H20 | `VerificadorFirma(SecretosFirma, Clock, Duration ventana)`, decodificación hex manual y `MessageDigest.isEqual` | Parsing del timestamp en un value object | 100 % de mutación; R8 en verde | M |
| **H1.9** Escenarios ejecutables | D-S01–D-S06 y `MatrizRenderizadaTest` | `EscenarioBuilder` de líneas de tiempo | Reporte HTML de Surefire con los escenarios, que se demuestra a negocio | Demo con negocio realizada | S |

Las cinco partes obligatorias de cada historia están en las columnas de la tabla. La DoD de cada una es DoD-E más la columna "DoD específica".

### Fase 2: Casos de uso con puertos fakeados en memoria

**H2.1 Puertos, fakes y suites de contrato de puerto (M)**
1. *Tests primero* (contrato de puerto, ejecutados sobre el fake ahora y sobre el adaptador real en la fase 3):
   - `EmisionRepositorioContrato.insertarDosVecesDevuelveFalse`
   - `EmisionRepositorioContrato.transicionarDesdeOrigenNoPermitidoDevuelveCero`
   - `EventoProveedorRepositorioContrato.duplicadoDevuelveFalse`
   - `ProgramadorTareasContrato.mismoNombreEsIdempotente`
   - `OutboxContrato.secuenciaDuplicadaLanzaConflicto`
   - `UnidadDeTrabajoContrato.rollbackDescartaTodo`
2. *Implementación mínima*: interfaces de puertos; fakes `EnMemoria*` con un `DiarioInteracciones` compartido que registra el orden de las llamadas.
3. *Refactor*: una sola clase de excepción, `ConflictoConcurrencia`.
4. *DoD*: DoD-E y cada fake pasando su suite de contrato.

**H2.2 Procesar solicitud (L)**

| ID | Test (aplicación) | Given | When | Then |
|---|---|---|---|---|
| A-S01 | `persisteYProgramaAntesDeLlamar` | Solicitud nueva | `procesar` | Diario = [insertar, programarConsulta(g1, i1, +5 s), programarVencimiento(fl − 1 h), solicitarEmision]; estado ENVIADA; resultado ACK |
| A-S02 | `redeliveryYaEnviadaNoLlama` | Emisión ENVIADA | `procesar` del mismo mensaje | No llama al proveedor; ACK |
| A-S03 | `redeliveryTrasCaidaReprograma` | SOLICITADA con tareas ya existentes | `procesar` | `programar` devuelve YA_EXISTE sin error; llama con la misma `Idempotency-Key` |
| A-S04 | `falloTransitorioNack` | El proveedor lanza `FalloTransitorio` | `procesar` | NACK; SOLICITADA; outbox vacío |
| A-S05 | `circuitoAbiertoPublicaReintentoYAck` | `EstadoCircuito` abierto | `procesar` | `ColaReintentos` recibe 1 mensaje; ninguna llamada; ACK |
| A-S06 | `webhookGanaCarrera` | El fake del proveedor marca EMITIDA durante la llamada | La llamada responde 2xx | Transicionar ENVIADA devuelve 0 filas; ACK; métrica `aviso_obsoleto` |
| A-S07 | `falloPermanente` (O-07) | El proveedor responde 422 | `procesar` | FALLIDA_ENVIO + `EmisionFallidaHandler.envioRechazado`; ACK |
| A-S08 | `solicitudInvalida` (O-10) | Sin `prima_cotizada_centavos` | `procesar` | ACK; nada persistido; métrica `solicitud_invalida` |
| A-S09 | `referenciaExistenteConPayloadDistinto` | Ref A guardada con prima P | Llega ref A con prima Q | ACK; no sobrescribe; alerta de conflicto |
| A-S10 | `sinTransaccionDuranteLlamada` | `UnidadDeTrabajo` fake | `procesar` | No hay transacción abierta mientras se ejecuta `solicitarEmision` |
| A-S11 | `falloAlProgramarNack` | `ProgramadorTareas` lanza transitorio | `procesar` | NACK; el proveedor no fue llamado |

- *Implementación mínima*: `ProcesarSolicitudServicio`.
- *Refactor*: separar `EnviarEmision`, que reutiliza el reproceso de la DLQ (H2.6).
- *DoD*: DoD-E.

**H2.3 Registrar aviso del webhook (M)**

| ID | Test | Given | When | Then |
|---|---|---|---|---|
| A-W01 | `eventoNuevoAplicado` | ENVIADA | APROBADO evt_1 | EMITIDA; outbox [EXITOSA s1]; evento con efecto APLICADO; todo en una unidad de trabajo |
| A-W02 | `eventoDuplicado` | evt_1 registrado | evt_1 otra vez | Respuesta DUPLICADO; sin transición ni outbox |
| A-W03 | `eventoNuevoObsoleto` | EMITIDA | PENDIENTE evt_2 | Efecto IGNORADO; sin outbox |
| A-W04 | `montoDiscrepante` | ENVIADA | APROBADO con otro monto | Efecto REVISION_MANUAL; `handler.montoDiscrepante` llamado una vez |
| A-W05 | `referenciaDesconocida` | No existe | Aviso | Respuesta DESCONOCIDA; auditado; sin excepción |
| A-W06 | `conflictoConcurrencia` | El outbox lanza `ConflictoConcurrencia` | Aviso | Respuesta REINTENTAR; rollback (evento no registrado) |
| A-W07 | `aprobacionTardia` | VENCIDA [s1 provisional] | APROBADO | Outbox [s2 EXITOSA corrige 1] |
| A-W08 | `conflictoProveedor` | EMITIDA | RECHAZADO | `handler.conflictoProveedor`; efecto IGNORADO |

- *Implementación*: `RegistrarAvisoServicio`.
- *Refactor*: `AplicarDecision`, componente común a webhook, consulta y vencimiento (un solo camino de escritura).
- *DoD*: DoD-E.

**H2.4 Ejecutar consulta (M)**

| ID | Test | Given | When | Then |
|---|---|---|---|---|
| A-C01 | `consultaAprobada` | ENVIADA, intento 2 | El proveedor responde APROBADO | EMITIDA; outbox; no programa el siguiente |
| A-C02 | `pendienteProgramaSiguiente` | ENVIADA, intento 1 | PENDIENTE | Programa el intento 2 en +1 min |
| A-C03 | `agotadaPasaARevision` | ENVIADA, intento 5 | PENDIENTE | REQUIERE_REVISION; `handler.requiereRevision`; sin outbox |
| A-C04 | `finalNoLlama` | EMITIDA | Tarea | Sin llamada; no-op |
| A-C05 | `tareaDuplicadaIdempotente` | Intento 2 marcado EJECUTADA | El mismo intento llega de nuevo | No-op |
| A-C06 | `circuitoAbiertoCuentaComoSinDatos` (SUPUESTO) | Breaker abierto | Tarea intento 2 | No llama; programa el intento 3 |
| A-C07 | `desconocidaEnProveedor` | FALLIDA_ENVIO, intento 5 | 404 | Sin transición (O-04); métrica |
| A-C08 | `transitorioNoDuplicaCadena` | Timeout en la consulta | Tarea | Programa el siguiente intento; devuelve OK (no 503) para que Cloud Tasks no reintente la misma |
| A-C09 | `generacionVieja` (O-05) | Generación actual 2 | Llega tarea de la generación 1 | No-op |

- *Implementación*: `EjecutarConsultaServicio`.
- *Refactor*: mover `marcarEjecutada` a `ProgramadorTareas`.
- *DoD*: DoD-E.

**H2.5 Ejecutar vencimiento (M)**: A-V01 `prematuroNoOp`; A-V02 `pendienteVence` (VENCIDA + ERROR provisional + `handler.vencida`); A-V03 `consultaFinalAprobada`; A-V04 `circuitoAbiertoVenceSinLlamar`; A-V05 `finalNoOp`; A-V06 `duplicadoSinDobleOutbox`; A-V07 `timeoutConsultaFinalVence` (presupuesto total < 15 s). Las condiciones G/W/T son las de D-X01–D-X11 trasladadas al servicio con fakes.
- *Implementación*: `EjecutarVencimientoServicio`.
- *Refactor*: reutilizar `AplicarDecision`.
- *DoD*: DoD-E.

**H2.6 Manejar fallida de envío y reproceso de la DLQ (M)**

| ID | Test | Given | When | Then |
|---|---|---|---|---|
| A-D01 | `primeraLlegadaMarcaFallida` | SOLICITADA, ahora < fl − 1 h, breaker cerrado | Mensaje de la DLQ | FALLIDA_ENVIO, después llama; si responde 2xx, ENVIADA y nueva generación de consultas (O-05); ACK |
| A-D02 | `reprocesoFallaNack` | FALLIDA_ENVIO | Proveedor transitorio | NACK (backoff de la suscripción DLQ) |
| A-D03 | `fueraDeVentanaAck` | ahora ≥ fl − 1 h | Mensaje | ACK sin llamada (el vencimiento se encarga) |
| A-D04 | `circuitoAbiertoNack` | Breaker abierto | Mensaje | NACK sin llamada |
| A-D05 | `yaEnviadaAck` | ENVIADA o final | Mensaje | ACK no-op |

- *Implementación*: `ManejarFallidaEnvioServicio`, que reutiliza `EnviarEmision`.
- *Refactor*: `PoliticaReproceso` en el dominio (usa `PlazosSla.puedeReprocesar`).
- *DoD*: DoD-E.

**H2.7 Puerto `EmisionFallidaHandler` (S)**
1. *Tests*:
   - A-F01 `invocadoUnaVezPorTransicion`: dada una transición a REQUIERE_REVISION repetida 2 veces, se invoca 1 vez.
   - A-F02 `noInvocadoEnDuplicado`.
   - A-F03 `falloDelHandlerNoRevierteTransicion`: el handler se llama después del commit y su fallo produce métrica y log.
2. *Implementación*: puerto con `requiereRevision`, `vencida`, `montoDiscrepante`, `conflictoProveedor`, `envioRechazado`; adaptador por defecto que registra log y métrica.
3. *Refactor*: ninguno previsto.
4. *DoD*: DoD-E y el TODO 3 enlazado.

### Fase 3: Adaptadores de salida

**H3.1 Migraciones Flyway (M)**
1. *Tests*: I-F01–I-F09 (§5).
2. *Implementación*: V1–V4.
3. *Refactor*: convención de nombres `ck_`, `ux_`, `ix_`.
4. *DoD*: DoD-E y la revisión del DBA.

**H3.2 Repositorio JDBC y concurrencia real (L)**

| ID | Test (integración, Testcontainers) | Given | When | Then |
|---|---|---|---|---|
| I-R01 | Suites de contrato de H2.1 | Adaptador JDBC | Ejecutar las suites | Verde |
| I-R02 | `updateCondicionalPermitido` | SOLICITADA | Transicionar a ENVIADA | 1 fila; `version + 1` |
| I-R03 | `updateCondicionalNoPermitido` | EMITIDA | Transicionar a ENVIADA | 0 filas |
| I-R04 | `carreraAprobadoVsVencimiento` (200 repeticiones, `CyclicBarrier`) | ENVIADA | Hilo 1 APROBADO, hilo 2 vencer, a la vez | Estado final EMITIDA. Outbox = [s1 EXITOSA] o [s1 ERROR provisional, s2 EXITOSA corrige 1]. Nunca dos s1, nunca EXITOSA sin corrección tras un provisional |
| I-R05 | `dobleAprobadoConcurrente` | ENVIADA | Webhook APROBADO y consulta APROBADO a la vez | Una sola transición y una sola fila de outbox |
| I-R06 | `eventoConcurrenteMismoId` | — | 10 hilos insertan evt_1 | Exactamente 1 `true` |
| I-R07 | `roundTripCompleto` | Emisión con todos los campos | Guardar y leer | Igual campo a campo |
| I-R08 | `insertOnConflictSolicitud` | — | 2 hilos insertan la misma referencia | 1 fila; un `true` y un `false` |

- *Implementación*: `JdbcEmisionRepositorio` con `JdbcClient`, `ON CONFLICT DO NOTHING`, UPDATE condicional y `JdbcUnidadDeTrabajo` (`TransactionTemplate`).
- *Refactor*: SQL en constantes o archivos `.sql`; mapper dedicado.
- *DoD*: DoD-E y I-R04 estable en 20 ejecuciones seguidas en CI.

**H3.3 Outbox y relay sin barrido (L), bloqueada por la decisión de O-01**
1. *Tests* (integración):
   - I-O01 `outboxEnMismaTransaccion`: si se fuerza un rollback, no queda ni transición ni fila.
   - I-O02 `publicaTrasCommit`: tras el commit, el relay publica y marca `publicado_en`.
   - I-O03 `relayPorTareaRespaldo`: si la publicación post-commit falla, la tarea `RELAY` (creada antes del commit, en +2 min) publica los pendientes de esa referencia en orden de secuencia.
   - I-O04 `relayTareaHuerfana`: si la transacción hizo rollback, la tarea de relay no encuentra filas y termina en no-op.
   - I-O05 `resumePublishTrasError`: tras un error con ordering key, se llama `resumePublish(key)` y no se publica s2 antes que s1.
   - I-O06 `duplicadoToleradoAtLeastOnce`: publicar 2 veces s1 no rompe nada (el core deduplica por `referencia + secuencia`).
2. *Implementación*: `RelayResultadosServicio`, listener `afterCommit` y handler `/tareas/relay`.
3. *Refactor*: un único método `publicarPendientes(referencia)` usado por ambos caminos.
4. *DoD*: DoD-E y la métrica `outbox_pendiente_edad_max` emitida.

**H3.4 Publicador de Pub/Sub (M)**
1. *Tests* (emulador):
   - I-P01 `publicaConOrderingKeyYAtributos`.
   - I-P02 `payloadCumpleEsquema` (C-R01 sobre lo publicado).
   - I-P03 `ordenPorReferencia`: s1..s3 llegan en orden a una suscripción con ordering activado.
   - I-P04 `publicaColaReintentos`.
2. *Implementación*: `PublicadorPubSub` con `Publisher.setEnableMessageOrdering(true)`.
3. *Refactor*: un `Publisher` por topic, cerrado en el shutdown.
4. *DoD*: DoD-E; SP-4 confirma que el emulador soporta ordering.

**H3.5 Cliente del proveedor con WireMock (L)**

| ID | Test (integración) | Given | When | Then |
|---|---|---|---|---|
| I-K01 | `enviaIdempotencyKey` | Stub 202 | `solicitar` | WireMock verifica el header `Idempotency-Key: SOAT-883214` y el body |
| I-K02 | `aceptada` | 200/202 | `solicitar` | `Aceptada` |
| I-K03 | `latencia8sExito` | Retardo fijo de 8 s | `solicitar` | `Aceptada` |
| I-K04 | `timeout10s` | Retardo de 11 s | `solicitar` | `FalloTransitorio(TIMEOUT)` en ≤ 10,5 s |
| I-K05 | `retryAfterSegundos` | 429 con `Retry-After: 30` | `solicitar` | `FalloTransitorio(retryAfter = 30 s)`; exactamente 1 request |
| I-K06 | `retryAfterFechaHttp` | 429 con fecha HTTP | `solicitar` | Se parsea a la duración restante |
| I-K07 | `retryAfterAusente` | 429 sin header | `solicitar` | Default configurable (SUPUESTO: 10 s) |
| I-K08 | `errores5xx` (500, 502, 503, 504) | Stub | `solicitar` | Transitorio; 1 request |
| I-K09 | `errores4xxPermanentes` (400, 422) | Stub | `solicitar` | `FalloPermanente` |
| I-K10 | `conflicto409` | Stub | `solicitar` | `Aceptada` (SUPUESTO) |
| I-K11 | `conexionReseteada` | `Fault.CONNECTION_RESET_BY_PEER` | `solicitar` | Transitorio |
| I-K12 | `consultaMapeaEstados` (parametrizado) | APROBADO, PENDIENTE, RECHAZADO, desconocido, 404 | `consultar` | Mapeo correcto |
| I-K13 | `respuestaMalformada` | 200 con body inválido | `consultar` | Transitorio y métrica |
| I-K14 | `cuatrocientasConcurrentes` | Latencia aleatoria de 2–8 s | 400 llamadas en virtual threads | Todas completan; no se agota el pool de conexiones HTTP |

- *Implementación*: `RestClient` sobre `JdkClientHttpRequestFactory` con timeouts de conexión y lectura y `Duration` total de 10 s.
- *Refactor*: `RetryAfterParser` aislado.
- *DoD*: DoD-E; ningún reintento interno (verificación de WireMock `exactly(1)`).

**H3.6 Adaptador de Cloud Tasks (M)**
1. *Tests* (integración con un servidor gRPC in-process que implementa `CloudTasksGrpc.CloudTasksImplBase`):
   - I-T01 `creaTareaNombradaConOidc`: comprueba nombre, `scheduleTime`, `oidcToken` (cuenta de servicio y audience) y body.
   - I-T02 `alreadyExistsEsExito`.
   - I-T03 `unavailableEsTransitorio`.
   - I-T04 `registraConsultaProgramada`.
   - I-T05 `vencimientoEnFechaLimiteMenos1h`.
   - Suite de contrato de `ProgramadorTareas` (H2.1).
2. *Implementación*: `CloudTasksProgramador`.
3. *Refactor*: `TareaHttpFactory`.
4. *DoD*: DoD-E.

### Fase 4: Adaptadores de entrada

**H4.1 Webhook (M)**

| ID | Test (slice `@WebMvcTest` con caso de uso mockeado) | Given / When / Then |
|---|---|---|
| W-01 | `firmaValidaInvocaConBytesCrudos` | Dado el vector dorado y un reloj fijo, al hacer POST, responde 200 y el caso de uso recibe exactamente esos bytes |
| W-02 | `firmaInvalida401SinDetalle` | El caso de uso no se invoca; el cuerpo es genérico |
| W-03 | `headersFaltantes401` | — |
| W-04 | `fueraDeVentana401` | — |
| W-05 | `cuerpoGrande413` | — |
| W-06 | `jsonInvalido400` | Firma válida sobre bytes que no son JSON |
| W-07 | `reintentar503` | El caso de uso devuelve REINTENTAR |
| W-08 | `duplicado200` | — |
| W-09 | `contentType415` | — |
| W-10 | `noReserializa` | Un body con espacios y escapes unicode verifica correctamente |
| W-11 | `soloPost` | GET devuelve 405 |
| W-12 | `mdcConReferencia` | `OutputCaptureExtension`: el log JSON contiene `referencia`, `evento_id` y `correlation_id` |
| W-I01 | `webhookExtremoAExtremo` (integración) | SpringBootTest con Postgres real: tras el POST firmado, el estado es EMITIDA y hay fila en el outbox |

- *Implementación*: `WebhookPagosController` y `SecretosFirma` cargados desde Secret Manager (fake en tests).
- *Refactor*: un filtro para el tamaño y el content-type.
- *DoD*: DoD-E y la revisión de seguridad.

**H4.2 Endpoint de tareas con OIDC (M), dependiente de SP-3 para el despliegue**
1. *Tests* (slice con Spring Security Test, tokens firmados con una clave local y JWKS servido por WireMock):
   - T-01 sin token → 401.
   - T-02 clave desconocida → 401.
   - T-03 `aud` incorrecta → 401.
   - T-04 `email` de otra cuenta de servicio → 403.
   - T-05 token expirado → 401.
   - T-06 token válido → 204 y caso de uso invocado.
   - T-07 caso de uso transitorio → 503.
   - T-08 `retryCount` en el MDC.
   - T-09 la ruta del webhook no exige OIDC y las de `/tareas/**` sí.
2. *Implementación*: `oauth2ResourceServer().jwt()` con `issuer` de Google, validador de `aud` y `email`, y `TareasController`.
3. *Refactor*: `ValidadorClaimsTareas`.
4. *DoD*: DoD-E.

**H4.3 Consumidor de `emision-solicitudes` (L)**
1. *Tests* (integración con el emulador):
   - P-01 `ackTrasProcesar`.
   - P-02 `nackProvocaRedelivery`.
   - P-03 `invalidoAck`.
   - P-04 `flowControlTecho`: con `maxOutstandingElementCount = N` y un proveedor lento, nunca hay más de N en vuelo (contador atómico en el fake).
   - P-05 `shutdownGraceful`: con SIGTERM a mitad, los mensajes en vuelo terminan o se devuelven con nack y ninguno se pierde.
   - P-06 `propagaTraceparentYCorrelation`.
   - P-07 `extensionDeAckDeadline`: un procesamiento de 25 s no genera redelivery.
2. *Implementación*: `Subscriber` de la librería cliente (StreamingPull) con `FlowControlSettings`, un executor de virtual threads y un `ConsumidorSolicitudes` que traduce ACK/NACK.
3. *Refactor*: `ConsumidorBase` común para solicitudes, DLQ y reintentos.
4. *DoD*: DoD-E. La política de dead-letter (5 entregas y luego la DLQ) se verifica en I-DLQ-01, en un proyecto GCP efímero, si SP-4 confirma que el emulador no la soporta.

**H4.4 Consumidores de la DLQ y de la cola de reintentos (M)**
1. *Tests*:
   - Q-01 `dlqInvocaManejarFallida`.
   - Q-02 `dlqNackRespetaBackoffSuscripcion`.
   - Q-03 `reintentoAntesDeNoAntesDeNack` (O-02).
   - Q-04 `reintentoReinvocaEnviar`.
   - I-DLQ-01 `cincoEntregasLuegoDlq` (sistema en GCP efímero).
2. *Implementación*: `ConsumidorDlq` y `ConsumidorReintentos`.
3. *Refactor*: ninguno previsto.
4. *DoD*: DoD-E.

### Fase 5: Resiliencia

**H5.1 Límite de concurrencia adaptativo (M), dependiente de SP-5**
1. *Tests* (integración con WireMock):
   - L-01 `gradient2BajaConLatencia`: al pasar la latencia de 2 s a 8 s, el límite reportado baja.
   - L-02 `blockingConTimeoutAcotado`: sin permisos, se espera como máximo `limitador.espera-max` (SUPUESTO: 12 s; espera + 10 s de llamada < 30 s de ack deadline) y luego `Rechazado` → NACK.
   - L-03 `permisoLiberadoSiempre` (parametrizado por éxito, error, timeout y 429): llama a `onSuccess`, `onIgnore` o `onDropped`; el 429 cuenta como `onDropped`.
   - L-04 `sinPinning`: un evento JFR `jdk.VirtualThreadPinned` con umbral de 20 ms cuenta 0 bajo 400 llamadas concurrentes.
   - L-05 `metricasLimiteEInflight`.
2. *Implementación*: `ResilienciaProveedor` con `BlockingLimiter.wrap(SimpleLimiter.newBuilder().limit(Gradient2Limit...))`.
3. *Refactor*: si L-04 falla, reemplazar `BlockingLimiter` por un adaptador basado en `Semaphore` con el mismo `Limiter` (O-09).
4. *DoD*: DoD-E.

**H5.2 Circuit breaker, timeout y `Retry-After` (M)**
1. *Tests*:
   - B-01 `abreConTasaDeFallos`.
   - B-02 `abrePorLlamadasLentas` (umbral configurado; O-17).
   - B-03 `abiertoNoConsumePermiso`: orden de decoradores CB → limitador → timeout → HTTP.
   - B-04 `halfOpenPermiteMLlamadas`.
   - B-05 `retryAfterFuerzaApertura` (O-02): un 429 con `Retry-After: 60` abre el breaker durante 60 s.
   - B-06 `fallosPermanentesNoCuentan`.
   - B-07 `estadoCircuitoExpuesto`: el puerto `EstadoCircuito` refleja el estado real.
2. *Implementación*: Resilience4j `CircuitBreaker` y `TimeLimiter`, o el timeout del cliente.
3. *Refactor*: la configuración se centraliza en `@ConfigurationProperties`.
4. *DoD*: DoD-E.

**H5.3 Flow control, pool y escalado (M)**
1. *Tests* (configuración):
   - CF-03 `presupuestoConexiones`: lee los valores de Kustomize y `application.yml` y verifica `hpa.maxReplicas × hikari.maximumPoolSize + reserva ≤ cloudsql.max_connections`.
   - CF-04 `hpaSinCpu`: una política de conftest niega cualquier métrica `Resource` de CPU.
   - CF-05 `flowControlCoherente`: `maxOutstanding × pods ≥ objetivo de concurrencia` y `maxOutstanding ≥ límite máximo del limitador`.
   - I-R09 `noRetieneConexionDuranteLlamada`: con pool de 2 y un proveedor de 8 s, 20 solicitudes concurrentes completan.
2. *Implementación*: valores de configuración.
3. *Refactor*: un único archivo de capacidad (`capacidad.yaml`) que se usa como fuente de los demás.
4. *DoD*: DoD-E.

### Fase 6: Pruebas de sistema

**H6.1 End-to-end con todos los contenedores (L)**
- Entorno: Postgres, emulador de Pub/Sub, WireMock como proveedor (con un transformer que emite webhooks firmados), `FakeCloudTasksServer` (gRPC más un despachador HTTP con token OIDC de prueba) y la app. El tiempo se comprime con delays del calendario configurados en milisegundos.

| ID | Escenario (Given / When / Then) |
|---|---|
| S-01 | Dada una solicitud publicada, cuando el proveedor acepta y envía APROBADO, entonces llega EXITOSA s1 a `emision-resultados` |
| S-02 | Sin webhook, la consulta 3 devuelve APROBADO y llega EXITOSA |
| S-03 | Sin webhook ni aprobación: consultas agotadas → REQUIERE_REVISION → vencimiento → ERROR VENCIDA provisional → webhook APROBADO → EXITOSA s2 corrige 1 |
| S-04 | El proveedor responde 5xx en 5 entregas → DLQ → FALLIDA_ENVIO → reproceso → ENVIADA → webhook → EXITOSA |
| S-05 | 3 webhooks duplicados y un PENDIENTE desordenado producen un solo resultado |
| S-06 | Un webhook con firma inválida recibe 401 y no tiene efecto |
| S-07 | Un monto discrepante no produce transición, invoca al handler y termina en VENCIDA |
| S-08 | RECHAZADO produce ERROR definitivo |
| S-09 | Con el breaker abierto, el mensaje pasa por la cola de reintentos y la emisión termina ENVIADA al cerrarse |
| S-10 | Para cada referencia de las corridas, `SELECT count(*) ... GROUP BY numero_poliza` y el conteo de solicitudes al proveedor por `Idempotency-Key` no muestran duplicados |

- *Implementación*: módulo `gateway-sistema`.
- *Refactor*: DSL `Escenario.dado().cuando().entonces()`.
- *DoD*: DoD-E y la suite completa en menos de 15 minutos en CI nocturno.

**H6.2 Carga a 50 rps (L), dependiente de SP-1 para los umbrales reales**
1. *Tests* (sistema, staging):
   - S-L01 webhooks a 50 rps sostenidos durante 30 min, con un pico de 100 rps durante 2 min (k6).
   - S-L02 50 solicitudes/s publicadas (harness Java), con WireMock de latencia uniforme de 2–8 s, para alcanzar unas 400 llamadas en vuelo.
   - S-L03 mezcla real: solicitudes, webhooks y consultas.
   - Criterios (SUPUESTO hasta SP-1): p99 del webhook < 500 ms, 5xx < 0,1 %, `oldest_unacked` < 60 s, Hikari `pending` = 0, conexiones ≤ presupuesto, 0 pólizas duplicadas y 0 pinning.
2. *Implementación*: scripts en `carga/`, ejecutados como pipeline manual.
3. *Refactor*: parametrizar la tasa y la duración.
4. *DoD*: DoD-E y el informe de capacidad con la recomendación de `maxReplicas` y del pool.

**H6.3 Caos (L)**

| ID | Escenario | Herramienta | Expectativa |
|---|---|---|---|
| X-01 | Proveedor caído 10 min | Toxiproxy `down` | El breaker abre, los mensajes pasan a la cola de reintentos, no se pierde nada y al recuperarse todas quedan ENVIADA |
| X-02 | Proveedor lento (9–12 s) | Toxiproxy `latency` | El limitador baja, el breaker detecta llamadas lentas y no hay pólizas duplicadas |
| X-03 | Pod muerto tras la llamada y antes de ENVIADA | `kill -9` desde un hook de prueba | Redelivery con la misma `Idempotency-Key`, consultas ya programadas y una sola póliza |
| X-04 | Pod muerto entre el insert y la creación de tareas | Igual | El redelivery crea las tareas |
| X-05 | 10 webhooks concurrentes con el mismo `id` | k6 | Uno aplicado |
| X-06 | Webhook tardío (después de VENCIDA y después de `fecha_limite`) | Script | Corrección s2 |
| X-07 | Flood de 500 rps con firma inválida | k6 | 401 rápidos; p99 de los válidos dentro del criterio |
| X-08 | Failover de Cloud SQL HA | `gcloud sql instances failover` en staging | Reconexión; sin pérdida; outbox íntegro |
| X-09 | Publicación de resultados fallando | Bloquear IAM del publisher temporalmente | El outbox retiene y el relay por tarea publica en orden |
| X-10 | Rotación de secreto HMAC en caliente | Nueva versión en Secret Manager | Cero 401 durante la rotación |

- *DoD*: DoD-E y un runbook por escenario.

### Track transversal: infraestructura, observabilidad y despliegue (en paralelo desde la semana 2)

| Historia | Tests primero | Implementación mínima | Refactor | Est. |
|---|---|---|---|---|
| **HT.1** Terraform base | TF-01 `suscripcionSolicitudesDlq5`, TF-02 `backoffFijo10s`, TF-03 `ackDeadline30`, TF-04 `ordenamientoResultados`, TF-05 `colasTareasRetryAcotado`, TF-06 `iamMinimo` (checkov y asserts sobre bindings), TF-07 `serviceAgentPuedeDeadLetter` (`terraform test` con `command = plan`) | Módulos pubsub, cloudtasks, cloudsql, iam, secrets | Variables por ambiente | L |
| **HT.2** Manifiestos de GKE | CF-04, CF-06 `probesDefinidas`, CF-07 `pdbMinAvailable1`, CF-08 `terminationGraceMayorQueShutdown` (kubeconform y conftest) | Kustomize base y overlays | Componentes comunes | M |
| **HT.3** Métricas, trazas y logs | OB-01–OB-06 (§9) | Micrometer, OpenTelemetry y logging estructurado | `ObservadorEmision` como decorador | M |
| **HT.4** Alertas y dashboards | OB-07, OB-08 (§9) | `google_monitoring_alert_policy` y dashboard | — | M |
| **HT.5** Flags, canary y rollback | CF-09 `flagsConDefaultSeguro`, simulacro de rollback en staging | §10 | — | M |

### Spikes (con timebox; bloquean las historias indicadas)

| Spike | Pregunta | Timebox | Bloquea |
|---|---|---|---|
| SP-1 | Capacidad y rate limits del proveedor; carga controlada en su sandbox (TODO 1) | 3 días | Umbrales de H5.1, H5.2, H6.2 y HPA |
| SP-2 | ¿Hay anulación? ¿Puede llegar una reversa después de APROBADO? (TODO 2) | 1 día más la respuesta del proveedor | D-A07, D-A08 y un posible estado nuevo |
| SP-3 | Exposición del endpoint de tareas y del webhook desde GKE (TODO 5) | 2 días con el equipo de red | Despliegue de H4.2 |
| SP-4 | Qué soporta el emulador de Pub/Sub: dead-letter, retry policy, ordering | 1 día | Estrategia de tests de H4.3, H4.4 y H3.4 |
| SP-5 | Pinning de `BlockingLimiter` y de Resilience4j con virtual threads en Java 21 | 2 días | H5.1 |
| SP-6 | Formato real de `X-Timestamp` y origen de `numero_poliza` (TODO 7) | 1 día más la respuesta del proveedor | D-H14, D-A17 |
| SP-7 | Mecanismo de relay del outbox sin barrido (O-01) | 2 días | H3.3 |
| SP-8 | Viabilidad de los emuladores locales: Cloud Tasks (comunitario) con OIDC, imágenes ARM64, dead-letter y retry policy en el emulador de Pub/Sub (§15) | 2 días | H0.4, H4.2, H4.3 |

### Resumen de estimación

| Fase | Historias | Días-persona |
|---|---|---|
| 0 Arnés | 4 | ~16 |
| 1 Dominio | 9 | ~25 |
| 2 Aplicación | 7 | ~30 |
| 3 Salida | 6 | ~36 |
| 4 Entrada | 4 | ~22 |
| 5 Resiliencia | 3 | ~12 |
| 6 Sistema | 3 | ~24 |
| Transversal | 5 | ~24 |
| Spikes | 7 | ~12 |
| **Total** | | **~203** (spikes ~14) |

Ruta crítica: F0 → F1 → F2 → H3.2/H3.3 → H4.3 → H5.1 → H6.1 → H6.2. HT.x y SP-x avanzan en paralelo. Con 4 desarrolladores el plan toma entre 9 y 12 semanas.

---

## 8. Configuración de infraestructura

### 8.1 Pub/Sub

| Recurso | Configuración |
|---|---|
| Topic `emision-solicitudes` | Retención de 7 días |
| Sub `emision-solicitudes-gateway` | Pull, `ack_deadline_seconds = 30`, `retry_policy { minimum_backoff = "10s", maximum_backoff = "10s" }`, `dead_letter_policy { dead_letter_topic = emision-solicitudes-dlq, max_delivery_attempts = 5 }` |
| Topic `emision-solicitudes-dlq` y sub `...-dlq-gateway` | `ack_deadline = 30`, backoff exponencial de 10 s a 600 s (SUPUESTO), retención de 7 días, sin dead-letter propia (O-04) |
| Topic `emision-reintentos` y su sub (O-02) | El mismo esquema; el consumidor respeta `no_antes_de` con NACK |
| Topic `emision-resultados` | El core crea su suscripción con `enable_message_ordering = true` (O-18) |
| IAM del service agent de Pub/Sub | `roles/pubsub.publisher` sobre el topic DLQ y `roles/pubsub.subscriber` sobre la sub origen (TF-07) |

```hcl
resource "google_pubsub_subscription" "solicitudes_gateway" {
  name                 = "emision-solicitudes-gateway"
  topic                = google_pubsub_topic.solicitudes.id
  ack_deadline_seconds = 30
  retry_policy { minimum_backoff = "10s"  maximum_backoff = "10s" }
  dead_letter_policy {
    dead_letter_topic     = google_pubsub_topic.solicitudes_dlq.id
    max_delivery_attempts = 5
  }
}
```

Flow control por pod (SUPUESTO hasta SP-1): `maxOutstandingElementCount = 120`, `maxOutstandingRequestBytes = 10 MB`, `LimitExceededBehavior.Block`, `parallelPullCount = 1` y `maxAckExtensionPeriod = 60 s`. Con 4 pods el techo es de 480 mensajes en vuelo, por encima de los ~400 necesarios. El limitador adaptativo actúa por debajo de ese techo.

### 8.2 Cloud Tasks

| Cola | Rate (SUPUESTO hasta SP-1) | Retry |
|---|---|---|
| `emision-consultas` | `max_dispatches_per_second = 50`, `max_concurrent_dispatches = 100` | `max_attempts = 5`, backoff de 5 s a 60 s, `max_retry_duration = 10m` |
| `emision-vencimientos` | 20/s, 50 concurrentes | `max_attempts = 10`, backoff de 10 s a 300 s, `max_retry_duration = 45m` (debe terminar antes de `fecha_limite`) |
| `emision-relay` | 50/s | `max_attempts = 20`, backoff de 5 s a 300 s |

- Las tareas usan OIDC con `sa-tareas-invocador`; el audience es la URL pública definida en SP-3.
- Nombres: `{prefijoHash8}-{referencia}-g{generacion}-c{intento}`, `{prefijo}-{ref}-v` y `{prefijo}-{ref}-r{secuencia}`.

### 8.3 Cloud SQL

- Postgres 16 (SUPUESTO), HA regional, IP privada, Java Cloud SQL Connector con autenticación IAM de base de datos y backups PITR.
- Presupuesto de conexiones, verificado por CF-03: `max_connections` del tier (p. ej. 200) ≥ 4 pods × 20 (Hikari) = 80, más 10 de Flyway y administración, más un margen de 30 %.
- Hikari: `maximumPoolSize = 20`, `connectionTimeout = 3 s`, `maxLifetime = 30 min`.
- Las transacciones nunca abarcan llamadas HTTP (A-S10, I-R09).
- Flyway corre como un `Job` previo al despliegue con un usuario de migración distinto del de la app.

### 8.4 GKE

| Elemento | Configuración |
|---|---|
| Deployment `soat-gateway` | 1 deployment inicial (webhook, tareas y consumidores). La separación en `api` y `worker` queda como opción tras SP-1 |
| Recursos | requests de 1 vCPU y 1,5 Gi; límite de memoria de 1,5 Gi; JVM `-XX:MaxRAMPercentage=70` |
| HPA | `minReplicas: 2`, `maxReplicas: 4`; métrica External `pubsub.googleapis.com/subscription/num_undelivered_messages` (sub de solicitudes, SUPUESTO `averageValue: 200`) y opcionalmente la métrica custom `soat_inflight`; **sin CPU** (CF-04). Escalado hacia arriba: 1 pod cada 120 s; hacia abajo: estabilización de 600 s |
| PDB | `minAvailable: 1` |
| Probes | startup `/actuator/health/liveness` (`failureThreshold 30 × 2 s`); liveness `/actuator/health/liveness`; readiness `/actuator/health/readiness`, que incluye la base de datos pero **no** al proveedor |
| Graceful shutdown | `preStop: sleep 10`; `server.shutdown=graceful`; `spring.lifecycle.timeout-per-shutdown-phase=25s`; el Subscriber hace `stopAsync()` y espera las llamadas en vuelo (≤ 10 s + espera del limitador); `terminationGracePeriodSeconds: 45` |
| Topología | `topologySpreadConstraints` por zona |
| Ingress | Gateway API más Cloud Armor (rate limit y lista de IPs del proveedor si la publica, SUPUESTO); detalle en SP-3 |

### 8.5 Secret Manager

- `proveedor-webhook-hmac`, con dos versiones habilitadas a la vez durante la rotación. La app lee los alias `actual` y `anterior` (version aliases) y recarga cada 5 min sin reiniciar.
- `proveedor-api-key`, para autenticarse ante el proveedor (SUPUESTO).
- Runbook de rotación: crear v(n+1) → mover `anterior` a v(n) y `actual` a v(n+1) → avisar al proveedor → esperar 24 h → deshabilitar v(n−1). Se valida con X-10.

### 8.6 IAM (Workload Identity: la KSA `soat-gateway` se vincula a la GSA `sa-soat-gateway`)

| Principal | Rol | Recurso |
|---|---|---|
| sa-soat-gateway | `roles/pubsub.subscriber` | Subs de solicitudes, DLQ y reintentos |
| sa-soat-gateway | `roles/pubsub.publisher` | Topics de resultados y reintentos |
| sa-soat-gateway | `roles/cloudtasks.enqueuer` | Las 3 colas |
| sa-soat-gateway | `roles/iam.serviceAccountUser` | `sa-tareas-invocador` (para emitir tareas con su OIDC) |
| sa-soat-gateway | `roles/cloudsql.client`, `roles/cloudsql.instanceUser` | Instancia |
| sa-soat-gateway | `roles/secretmanager.secretAccessor` | Solo los 2 secretos |
| sa-soat-gateway | `roles/monitoring.metricWriter`, `roles/cloudtrace.agent` | Proyecto |
| Service agent de Pub/Sub | publisher y subscriber | DLQ y sub origen |
| sa-migraciones | Usuario de base de datos con DDL | Solo en el Job de Flyway |

### 8.7 Terraform

- Estructura `infra/terraform/{modules/*, envs/{dev,staging,prod}}` con state remoto en GCS.
- CI ejecuta `fmt`, `validate`, `tflint`, `checkov`, `terraform test` (TF-01–TF-07) y un `plan` revisado en el PR.

---

## 9. Observabilidad

### 9.1 Métricas (Micrometer → Managed Service for Prometheus / Cloud Monitoring)

| Métrica | Tipo | Etiquetas |
|---|---|---|
| `soat_transiciones_total` | counter | desde, hacia, origen |
| `soat_avisos_ignorados_total` | counter | motivo |
| `soat_webhook_rechazado_total` | counter | motivo (firma, ventana, malformada) |
| `soat_resultados_publicados_total` | counter | resultado, definitivo, correccion |
| `soat_vencidas_total` | counter | estado_origen |
| `soat_correcciones_total` | counter | tipo |
| `soat_consultas_agotadas_total` | counter | — |
| `soat_monto_discrepante_total` | counter | — |
| `soat_conflicto_proveedor_total` | counter | — |
| `soat_emisiones_en_riesgo` | gauge | No finales con `fecha_limite` − ahora < 4 h (lectura de `ix_emision_no_final_fecha_limite` cada 60 s, O-19) |
| `soat_outbox_pendiente_edad_max_seconds` | gauge | — |
| `soat_proveedor_llamada_seconds` | timer (histograma) | operacion, resultado |
| `soat_limitador_limite`, `soat_limitador_inflight` | gauge | — |
| `resilience4j_circuitbreaker_state` | gauge (nativa) | name |
| `hikaricp_connections_*` | nativas | pool |
| `pubsub.googleapis.com/subscription/oldest_unacked_message_age` | GCP | subscription_id |

### 9.2 Trazas y logs

- **Trazas**: OpenTelemetry con exportación a Cloud Trace. `traceparent` viaja en los atributos de Pub/Sub, en los headers de Cloud Tasks y hacia el proveedor. `correlation_id` y `referencia` van como atributos del span y en el baggage.
- **Logs**: JSON estructurado (logging estructurado de Spring Boot). El MDC lleva `correlation_id`, `referencia`, `evento_id`, `intento`, `trace_id`. Nunca se registran firmas, secretos, tokens ni documentos del tomador; la placa se enmascara.

### 9.3 Alertas

| Alerta | Condición | Severidad |
|---|---|---|
| Backlog viejo | `oldest_unacked_message_age` > 1 h (subs de solicitudes, DLQ y reintentos) | Advertencia |
| Backlog crítico | > 6 h | Crítica (page) |
| Riesgo de SLA | `soat_emisiones_en_riesgo` > 0 durante 5 min | Crítica |
| Cualquier VENCIDA | `rate(soat_vencidas_total[5m])` > 0 | Crítica (page) |
| Tasa de correcciones | `soat_correcciones_total / soat_resultados_publicados_total` > 2 % en 1 h (SUPUESTO) | Advertencia |
| Breaker abierto | Estado OPEN durante más de 5 min | Crítica |
| Límite adaptativo colapsado | `soat_limitador_limite` < 20 durante 10 min, o `inflight == limite` durante 10 min | Advertencia |
| Outbox atascado | `soat_outbox_pendiente_edad_max_seconds` > 300 | Crítica |
| Ataque de firma | `soat_webhook_rechazado_total{motivo="firma"}` > 10/min | Advertencia (seguridad) |
| Conflicto del proveedor | `soat_conflicto_proveedor_total` > 0 | Advertencia (negocio) |

### 9.4 Cómo se prueba la observabilidad

| ID | Test | Nivel | Given / When / Then |
|---|---|---|---|
| OB-01 | `transicionIncrementaContador` | Aplicación | Con `SimpleMeterRegistry` y A-W01, después de ejecutarlo, `soat_transiciones_total{desde=ENVIADA,hacia=EMITIDA,origen=WEBHOOK} = 1` |
| OB-02 | `ignoradoIncrementaMotivo` | Aplicación | Tras A-W03, `soat_avisos_ignorados_total{motivo=...} = 1` |
| OB-03 | `endpointPrometheusExpone` | Integración | Con la app levantada, `GET /actuator/prometheus` contiene todos los nombres de §9.1 (lista dorada) |
| OB-04 | `logJsonConCorrelacion` | Integración | Con `OutputCapture` y un webhook, cada línea es JSON válido con `correlation_id`; no aparece la firma ni el secreto (assert negativo) |
| OB-05 | `trazaEncadenada` | Integración | Con `InMemorySpanExporter` y una solicitud procesada, los spans consumo → base de datos → proveedor comparten `trace_id` y el atributo `referencia` |
| OB-06 | `gaugeRiesgo` | Integración | Con 2 emisiones sembradas a 3 h del límite, el gauge vale 2 |
| OB-07 | `politicasAlertaUmbrales` | IaC | `terraform test` verifica los umbrales y duraciones de §9.3 |
| OB-08 | `simulacroDeAlertas` | Sistema (staging) | Al inyectar una referencia sintética que vence y abrir el breaker con Toxiproxy, las alertas disparan en menos de 10 min y el runbook se ejecuta |

---

## 10. Plan de despliegue

| Ambiente | Proveedor | Propósito |
|---|---|---|
| Local | WireMock | Desarrollo y TDD (compose) |
| CI | WireMock y emuladores | Unitarias, integración y sistema |
| dev (GCP) | WireMock desplegado | Integración real con Pub/Sub, Tasks e IAM; I-DLQ-01 |
| staging (GCP) | Sandbox del proveedor y WireMock para carga | Contrato, carga, caos y simulacros de alertas |
| prod | Proveedor real | — |

**Feature flags** (ConfigMap y `@ConfigurationProperties` recargables; SUPUESTO: no hay plataforma de flags):

| Flag | Default | Uso |
|---|---|---|
| `emision.consumo.pausado` | false | Kill-switch: el Subscriber deja de hacer pull y el backlog queda en Pub/Sub (vigilar el SLA) |
| `emision.monto.modo` | ENFORCE (LOG_ONLY en el primer canary) | Validación de monto |
| `emision.dlq.reproceso.habilitado` | true | Reproceso automático |
| `webhook.hmac.anterior.habilitado` | true | Rotación |
| `emision.consultas.habilitadas` | true | Cadena de respaldo |

**Canary**:
1. Migraciones expand-only con el Job de Flyway (compatibles con N−1, I-F08).
2. Un pod canary entre 3 recibe alrededor del 33 % de los mensajes, porque los consumidores de Pub/Sub compiten. El webhook y las tareas usan un `HTTPRoute` con pesos 90/10.
3. Observación de 30 min contra los SLO de §9: 5xx, latencia, ignorados, `conflicto`, VENCIDAS, outbox.
4. Si todo está en verde, 100 %.
5. La fase contract de las migraciones va en una release posterior.

**Rollback**:
- `kubectl rollout undo` o revert del commit en GitOps. Es seguro porque el esquema es compatible con N−1.
- Los flags permiten apagar un comportamiento sin redeploy.
- Nunca hay migraciones destructivas en la misma release.
- Los mensajes no se pierden: un NACK o la caída de un pod producen redelivery, y la `Idempotency-Key` protege contra la doble emisión.

---

## 11. Riesgos y mitigaciones

| # | Riesgo | Prob. | Impacto | Mitigación |
|---|---|---|---|---|
| 1 | La capacidad del proveedor es menor que ~400 concurrentes | Alta | Alto | SP-1, limitador adaptativo, HPA conservador, breaker y alertas de backlog |
| 2 | Pinning de virtual threads (`BlockingLimiter` con `synchronized`) | Media | Alto | SP-5, L-04 (JFR), R7, alternativa con `Semaphore` |
| 3 | El relay del outbox sin barrido pierde mensajes | Media | Alto | O-01: post-commit más tarea de relay creada antes del commit, alerta de edad |
| 4 | Espera del limitador + llamada > ack deadline → redelivery duplicada | Media | Medio | Espera máxima de 12 s, extensión de ack, `Idempotency-Key` |
| 5 | Agotamiento de conexiones de Cloud SQL | Media | Alto | CF-03, I-R09, transacciones cortas |
| 6 | Webhooks falsos o replay | Media | Alto | HMAC sobre bytes crudos, ventana de ±5 min, dedupe por `id`, Cloud Armor |
| 7 | Reversa después de APROBADO no modelada | Media | Alto | SP-2, métrica de conflicto, revisión manual |
| 8 | `numero_poliza` no viene en el webhook | Media | Medio | SP-6, consulta complementaria |
| 9 | El emulador no soporta dead-letter ni retry policy | Alta | Bajo | SP-4, I-DLQ-01 en GCP efímero |
| 10 | Corrección tardía después de que el core reembolsó o bloqueó la compra | Media | Alto | TODO 6 con el core; alerta de correcciones |
| 11 | Discrepancia de monto frecuente (redondeos o IVA) | Baja | Medio | Modo LOG_ONLY en el canary; análisis antes de ENFORCE |
| 12 | Hot-spotting de nombres de tareas | Baja | Bajo | Prefijo hash (D-K06) |
| 13 | Ordering de resultados roto por errores de publicación | Media | Medio | `resumePublish`, secuencia en el payload y dedupe en el core |
| 14 | Red: Cloud Tasks no alcanza el endpoint interno | Media | Alto | SP-3 temprano (semana 2) |
| 15 | Tests de concurrencia inestables | Media | Medio | `CyclicBarrier`, 200 repeticiones, 20 corridas de estabilidad |
| 16 | El ambiente local diverge de GCP: emuladores incompletos (Cloud Tasks comunitario, Pub/Sub sin todas las políticas), sin Secret Manager ni IAM | Alta | Medio | Adaptadores detrás de puertos, mismas imágenes en compose y Testcontainers (CF-14), y lo que no se emula se prueba en dev (I-DLQ-01, TF-xx). §15.4 |
| 17 | El emulador comunitario de Cloud Tasks queda sin mantenimiento o no corre en ARM64 | Media | Bajo | SP-8; alternativa: fake propio de `gateway-pruebas-soporte` empaquetado como contenedor |

---

## 12. Matriz de trazabilidad

| Requerimiento o decisión | Tests | Historia |
|---|---|---|
| Ninguna póliza duplicada por referencia | D-A06, D-A18, I-R05, I-R08, I-F06, I-K01, S-10, X-03, X-05 | H1.3, H3.2, H3.5, H6.1, H6.3 |
| SLA de 24 h | D-P01–D-P04, D-X01–D-X11, A-V01–A-V07, S-03, OB-06, OB-08 | H1.5, H2.5, HT.4 |
| 50 avisos/s y ~400 concurrentes | I-K14, L-01, CF-05, S-L01–S-L03 | H3.5, H5.1, H5.3, H6.2 |
| Matriz de transiciones | D-M01–D-M13, `MatrizRenderizadaTest` | H1.1 |
| UPDATE condicional con origenes permitidos | I-R02–I-R05 | H3.2 |
| Dedupe por `evento_id` | A-W02, I-F03, I-R06, X-05 | H2.3, H3.1, H3.2 |
| Avisos desordenados; PENDIENTE sin efecto | D-A03, D-A04, D-A07, D-A18, S-05 | H1.3 |
| Tareas programadas antes de llamar | A-S01, A-S11, X-04 | H2.2 |
| `Idempotency-Key = referencia` | I-K01, A-S03, X-03 | H3.5, H2.2 |
| Reintentos de Pub/Sub (10 s, 5, DLQ) sin reintentos en código | TF-01–TF-03, I-DLQ-01, I-K05, I-K08, R6, R11 | HT.1, H4.4, H3.5, H0.1 |
| Cadena de consultas [5 s, 1 min, 10 min, 20 min, 30 min] con nombre determinístico | D-K01–D-K11, A-C01–A-C09, I-T01, I-T02 | H1.6, H2.4, H3.6 |
| Agotamiento → REQUIERE_REVISION, no publica | D-C01, D-R08, A-C03 | H1.3, H2.4 |
| Vencimiento en fl − 1 h, prematuro ignorado | D-X01–D-X11, A-V01 | H1.5, H2.5 |
| Aprobación tardía como corrección; webhook nunca rechazado por fecha | D-A09–D-A11, D-A15, A-W07, S-03, X-06 | H1.3, H2.3 |
| Reproceso de la DLQ mientras ahora < fl − 1 h y el breaker esté cerrado | D-P03, A-D01–A-D05, Q-01, Q-02 | H2.6, H4.4 |
| Limitador Gradient2 con `BlockingLimiter` | L-01–L-05 | H5.1 |
| Breaker con llamadas lentas; abierto → cola de reintentos y ack | B-01–B-07, A-S05, S-09, X-01 | H5.2, H2.2 |
| Timeout de 10 s y `Retry-After` | I-K04–I-K07, B-05 | H3.5, H5.2 |
| Flow control; HPA sin CPU | P-04, CF-04, CF-05 | H4.3, H5.3 |
| Outbox transaccional | I-O01–I-O06, X-09 | H3.3 |
| Contrato y reglas de corrección de resultados | D-R01–D-R11, C-R01–C-R03, I-P01–I-P03 | H1.7, H3.4 |
| HMAC sobre bytes crudos, en tiempo constante | D-H01–D-H20, W-01, W-10, R8 | H1.8, H4.1 |
| Rotación de secretos | D-H08–D-H10, X-10 | H1.8, H4.1, HT.1 |
| OIDC en el endpoint de tareas | T-01–T-09, C-T02 | H4.2 |
| Workload Identity e IAM mínimo | TF-06, TF-07 | HT.1 |
| Validación de monto | D-V01–D-V07, A-W04, S-07 | H1.4, H2.3 |
| Sin Redis, sin crons, sin `sleep` | Enforcer, R6, R11 | H0.1 |
| No ordenar por `X-Timestamp` | R12 y el diseño de `AvisoProveedor` (D-A18) | H1.3 |
| `max_connections` respetado | CF-03, I-R09, S-L02 | H5.3, H6.2 |
| Dominio sin Spring; hexagonal | R1–R5, R10, R13 | H0.1 |
| Alertas y métricas | OB-01–OB-08 | HT.3, HT.4 |
| Puerto `EmisionFallidaHandler` | A-F01–A-F03, A-C03, A-V02 | H2.7 |
| Ambiente local reproducible con servicios cloud emulados | CF-02, CF-10–CF-14 | H0.4 |

---

## 13. Preguntas abiertas que bloquean

| # | Pregunta | Bloquea | Responsable sugerido |
|---|---|---|---|
| 1 | Capacidad, rate limits y acceso a carga en el sandbox (TODO 1) | H5.x, H6.2, HPA | Gestor del proveedor y tech lead |
| 2 | ¿Hay anulación? ¿Puede llegar una reversa después de APROBADO? (TODO 2) | D-A07, D-A08 | Proveedor y Producto SOAT |
| 3 | Acción de negocio ante REQUIERE_REVISION, VENCIDA, monto discrepante y conflicto (TODO 3) | Adaptador real de `EmisionFallidaHandler` | Operaciones y Producto SOAT |
| 4 | Aprobar el contrato de solicitud propuesto, incluida la `prima_cotizada_centavos` (TODO 4) | H2.2, C-S02 | Equipo core |
| 5 | Exposición pública o privada de `/tareas/**` y `/webhooks/pagos` (TODO 5) | Despliegue de H4.2 | Red y plataforma |
| 6 | Bloqueo por placa ante ERROR provisional, reembolso solo ante ERROR definitivo y mensaje al cliente (TODO 6) | Semántica de correcciones en el core | Core y Producto |
| 7 | Formato de `X-Timestamp` y origen de `numero_poliza` (TODO 7) | D-H14, D-A17 | Proveedor |
| 8 | Valores de estado del proveedor (¿existe RECHAZADO? ¿hay otros?) | D-A05, D-A14 | Proveedor |
| 9 | Códigos que el proveedor reintenta en el webhook y su política de reenvío | §6.1 | Proveedor |
| 10 | Rutas, autenticación y códigos del API de emisión y consulta (409, 4xx) | H3.5 | Proveedor |
| 11 | ¿Debe publicarse un resultado para solicitudes inválidas? Hoy el `motivo` no lo permite | H2.2 | Core y arquitectura |
| 12 | Mecanismo aprobado de relay del outbox (O-01) | H3.3 | Arquitectura |
| 13 | Rango de IPs del proveedor para Cloud Armor | §8.4 | Proveedor y seguridad |

---

## 14. Observaciones al diseño (contradicciones y huecos detectados; no se resolvieron en silencio)

| ID | Observación | Propuesta del plan (requiere aprobación) |
|---|---|---|
| O-01 | **Outbox frente a "no crons que barran tablas"**: un relay clásico de outbox barre la tabla. | Publicar en `afterCommit` y, como respaldo, crear antes del commit una tarea de Cloud Tasks `RELAY` por (referencia, secuencia). La tarea es idempotente y termina en no-op si hubo rollback. Se completa con la alerta de edad del outbox. SP-7 |
| O-02 | La "cola de reintentos" (breaker abierto) no está definida, y Pub/Sub no admite entrega diferida, así que no puede respetar `Retry-After` por mensaje. | Topic `emision-reintentos` con `no_antes_de` y NACK hasta esa hora, o bien Cloud Tasks con `scheduleTime`. Además, `Retry-After` fuerza la apertura del breaker (B-05). Acotar por `fl − 1 h` |
| O-03 | Un UPDATE solo con `estado IN (origenes)` admite que dos escritores concurrentes ganen en secuencia (VENCIDA y luego EMITIDA); el segundo pudo calcular su resultado con un estado viejo (sin `corrige_secuencia`). | `UNIQUE(referencia, secuencia)` en el outbox hace fallar la transacción obsoleta → 503 o reintento de Cloud Tasks y relectura. Se puede agregar `AND version = :versionLeida` sin cambiar la decisión. I-R04 |
| O-04 | La matriz no permite SOLICITADA → REQUIERE_REVISION ni FALLIDA_ENVIO → REQUIERE_REVISION; si la cadena se agota en esos estados, no hay transición. | Se ignora con métrica (D-C02, D-C03); el vencimiento cubre el SLA. Confirmar |
| O-05 | Si la DLQ reprocesa con éxito **después** de agotada la cadena de consultas, no quedan consultas programadas; solo quedan el webhook y el vencimiento. | Al pasar FALLIDA_ENVIO → ENVIADA, iniciar una nueva **generación** de la cadena (`generacion_consultas`, D-K11, A-D01) |
| O-06 | ¿Los delays de la cadena son entre intentos o desde la solicitud? | Entre intentos, con total de ~61 min (SUPUESTO, D-K09) |
| O-07 | No está definido el tratamiento de 4xx permanentes del proveedor; reintentarlos 5 veces es inútil. | FALLIDA_ENVIO directo + `EmisionFallidaHandler.envioRechazado` (A-S07). Confirmar |
| O-08 | Monto distinto: "va a revisión manual", pero no existe ese estado accesible desde todos los orígenes, y el SLA sigue corriendo. ¿Se valida el monto en un rechazo? | Sin transición; se notifica al handler; el vencimiento publica VENCIDA. No se valida el monto en RECHAZADO (D-V03, D-V07) |
| O-09 | `BlockingLimiter` usa `synchronized` y wait/notify: en Java 21 eso fija el carrier de los virtual threads. | SP-5 y L-04; alternativa con `Semaphore` |
| O-10 | Una solicitud inválida nunca recibe respuesta: el contrato solo admite `motivo` RECHAZADA o VENCIDA, lo que choca con el SLA de "respuesta en 24 h". | Ampliar el `motivo` con `SOLICITUD_INVALIDA` o acordar una alerta operativa (pregunta 11) |
| O-11 | Webhook con referencia desconocida: falta definir el código HTTP. | 200 + auditoría + alerta (evita reenvíos infinitos). Confirmar |
| O-12 | FALLIDA_ENVIO no se publica, así que el core no se entera hasta la hora 23. | Coherente con "solo finales y correcciones"; se hace visible con métricas y el handler |
| O-13 | El webhook de ejemplo no trae `numero_poliza`, pero el resultado EXITOSA lo exige. | Consulta complementaria tras APROBADO si falta (SP-6) |
| O-14 | Solo se mencionan los estados APROBADO y PENDIENTE; RECHAZADO es SUPUESTO. Además, el evento es `pago.actualizado` en `/webhooks/pagos`: ¿APROBADO significa pago aprobado o póliza emitida? | Confirmar la semántica con el proveedor (pregunta 8) |
| O-15 | `fecha_limite` depende de `solicitado_en` del core: un reloj desviado mueve el SLA. | Validar `solicitado_en` ≤ ahora + 5 min (D-P06) y registrar `publishTime` |
| O-16 | `ocurrido_en` del resultado no está definido: ¿instante de la transición o `creado_en` del proveedor? | Instante de la transición en el gateway (D-R09) |
| O-17 | El umbral de llamadas lentas (10 s) es igual al timeout (10 s): toda llamada lenta es un timeout y la detección no aporta nada. | Umbral de 8 s (SUPUESTO; la emisión dura hasta 8 s) o 9 s, que se calibra con SP-1 |
| O-18 | El orden por ordering key solo se garantiza si la suscripción del core lo habilita y se publica desde la misma región. | Requisito para el core (C-R02) |
| O-19 | El gauge de "a menos de 4 h del límite" necesita una consulta periódica, lo que podría leerse como un cron. | Es una lectura de solo métrica sobre un índice parcial, no un barrido que muta estado. Confirmar que se acepta |
| O-20 | Con la espera del limitador (`BlockingLimiter`) más 10 s de llamada se puede superar el ack deadline de 30 s. | Espera máxima de 12 s y `maxAckExtensionPeriod` (L-02) |
| O-21 | El vencimiento hace una consulta final; si el breaker está abierto no está claro qué hacer. | Marcar VENCIDA sin llamar (A-V04) |
| O-22 | "50 avisos por segundo" es ambiguo: ¿webhooks, solicitudes o ambos? | La carga prueba ambos por separado y combinados (S-L01–S-L03) |
| O-23 | No todos los servicios GCP tienen emulador oficial (Cloud Tasks, Secret Manager, IAM, Cloud Monitoring), así que el ambiente local no puede ser una réplica completa. | §15: sustitutos detrás de los mismos puertos y una tabla explícita de brechas que se cubren en dev o staging |

---

## 15. Ambiente de pruebas en la máquina local

**Objetivo**: que cada desarrollador ejecute en su máquina, sin credenciales de GCP ni acceso al proveedor, todas las pruebas unitarias, de integración y de sistema (S-01–S-10), los escenarios de caos que no dependen de GKE y una carga reducida. Se implementa en la historia H0.4 y lo valida SP-8.

### 15.1 Requisitos de la máquina

| Elemento | Requisito |
|---|---|
| Motor de contenedores | Docker Desktop, OrbStack o Colima (macOS), o Docker Engine (Linux). Recursos asignados: 6 vCPU y 10 GB de RAM como mínimo, porque el perfil completo levanta unos 9 contenedores |
| JDK y build | JDK 21 (Temurin) y Maven wrapper (`./mvnw`) |
| Herramientas | `make`, `curl`, `jq`, `openssl`, k6, Terraform ≥ 1.7, gcloud CLI (opcional; solo para dev y staging) |
| Opcional | kind o k3d, para probar los manifiestos de GKE en local |
| Testcontainers con Colima | Exportar `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` y `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` |
| Apple Silicon | Todas las imágenes deben tener variante ARM64; si una no la tiene, se usa la emulación con Rosetta del motor. SP-8 lo verifica imagen por imagen |

### 15.2 ¿LocalStack?

LocalStack emula servicios de **AWS** (SQS, SNS, Lambda, Secrets Manager de AWS, etc.), no de Google Cloud. No ofrece Pub/Sub, Cloud Tasks ni Cloud SQL. Para usarlo habría que escribir adaptadores AWS que no existen en producción, y eso rompe la paridad que buscan los tests de contrato de puerto.
**Decisión del plan**: no usar LocalStack. Se usan los emuladores oficiales de Google donde existen y contenedores de Docker (oficiales o comunitarios) para el resto, siempre detrás de los mismos puertos de la arquitectura hexagonal.

### 15.3 Sustituto local por servicio

| Servicio en la nube | Sustituto local | Cómo se conecta la app (perfil `local`) | Limitaciones conocidas o por verificar |
|---|---|---|---|
| Cloud SQL (Postgres) | Contenedor `postgres:16` | JDBC directo `jdbc:postgresql://localhost:5432/soat`, sin el Cloud SQL connector; Flyway al arrancar | Sin autenticación IAM ni failover HA |
| Pub/Sub | Emulador oficial (imagen `gcr.io/google.com/cloudsdktool/google-cloud-cli:emulators`, comando `gcloud beta emulators pubsub start`) | `spring.cloud.gcp.pubsub.emulator-host=localhost:8085` o la variable `PUBSUB_EMULATOR_HOST` | Por verificar en SP-4 y SP-8: soporte de dead-letter, retry policy y ordering. No expone `oldest_unacked_message_age` ni IAM |
| Cloud Tasks | **No hay emulador oficial.** Opción A: emulador comunitario `aertje/cloud-tasks-emulator` (gRPC, despacha HTTP a la app). Opción B: `FakeCloudTasksServer` propio (el servidor gRPC de H3.6 empaquetado como contenedor) | Endpoint gRPC sobrescrito en `CloudTasksSettings`, canal sin TLS y `NoCredentialsProvider` | Por verificar en SP-8: que la opción A emita tokens OIDC aceptables y corra en ARM64. Si no, se usa la opción B. Ninguna reproduce fielmente los rate limits ni los reintentos de la cola |
| OIDC de Cloud Tasks (JWKS de Google) | `navikt/mock-oauth2-server` | `spring.security.oauth2.resourceserver.jwt.issuer-uri` apunta al servidor simulado; el `email` y la `aud` esperados se configuran igual que en la nube | Emisor distinto del de Google; la validación real del emisor de Google se prueba en dev |
| Secret Manager | **No hay emulador oficial.** Implementación local del puerto `SecretosFirma` que lee dos versiones desde variables de entorno (`HMAC_ACTUAL`, `HMAC_ANTERIOR`) definidas en `.env` | Bean activado solo con el perfil `local` | Sin alias de versión ni recarga real; la rotación se prueba en staging (X-10) |
| Proveedor externo | WireMock (imagen `wiremock/wiremock`) con mappings versionados en `local/wiremock/`, más un `simulador-proveedor` que envía webhooks firmados con HMAC y `X-Timestamp` actual | `proveedor.base-url=http://localhost:8474` (a través de Toxiproxy) | Simula el contrato SUPUESTO de §6.4 hasta tener la documentación del proveedor |
| Fallas de red | Toxiproxy (imagen `ghcr.io/shopify/toxiproxy`) delante del proveedor y de Postgres | La app usa los puertos del proxy; los scripts de caos agregan toxics (latencia, corte, reset) | — |
| Cloud Monitoring, Trace y Logging | OpenTelemetry Collector, Prometheus, Grafana y Jaeger (perfil de compose `observabilidad`) | `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317`; Prometheus lee `/actuator/prometheus` | Las políticas de alerta reales solo existen en GCP (OB-07, OB-08) |
| GKE | Opcional: kind o k3d con el overlay de Kustomize `local` | `kubectl apply -k infra/k8s/overlays/local` | El HPA con métricas externas de Pub/Sub y Workload Identity no se pueden reproducir |
| IAM y Workload Identity | No se emulan; en local no hay credenciales | — | Se verifican con `terraform test` y en dev |
| Terraform | `terraform test` con `mock_provider "google"` (Terraform ≥ 1.7) | Sin credenciales | El `plan` real contra GCP solo corre en CI con la cuenta de dev |

Todas las versiones de imagen se fijan (nunca `latest`) en `versiones-imagenes.properties`. Ese archivo lo leen tanto `compose.yaml` (vía `.env`) como `ContenedoresCompartidos` de Testcontainers (CF-14). Así los tests de integración y el ambiente local usan exactamente las mismas imágenes.

### 15.4 Brechas: lo que el ambiente local no valida y dónde se cubre

| Aspecto | Por qué no se cubre en local | Dónde se valida |
|---|---|---|
| Dead-letter tras 5 entregas y backoff de 10 s | Pendiente de SP-4; el emulador podría no aplicar estas políticas | I-DLQ-01 en dev |
| Rate limits y reintentos de las colas de Cloud Tasks | Los emuladores no los reproducen | Staging (S-L03) |
| Emisor OIDC real de Google y audience | Emisor simulado | dev (T-06 con un token real) |
| Workload Identity, IAM y Secret Manager real | No hay emulador | TF-06, TF-07 y dev |
| HPA por backlog, PDB y failover de Cloud SQL | Requiere GKE y Cloud SQL reales | Staging (X-08, S-L01–S-L03) |
| Carga a 50 rps | La máquina local no es representativa | Staging; en local solo una carga de humo de 5 rps |
| Capacidad real del proveedor | Solo existe en su sandbox | SP-1 |

### 15.5 Composición de referencia (`compose.yaml`, esbozo)

```yaml
name: soat-local
services:
  postgres:
    image: postgres:${POSTGRES_TAG}
    environment: { POSTGRES_DB: soat, POSTGRES_USER: soat, POSTGRES_PASSWORD: soat }
    ports: ["5432:5432"]
    healthcheck: { test: ["CMD", "pg_isready", "-U", "soat"], interval: 5s }
  pubsub:
    image: gcr.io/google.com/cloudsdktool/google-cloud-cli:${GCLOUD_EMULATORS_TAG}
    command: gcloud beta emulators pubsub start --project=soat-local --host-port=0.0.0.0:8085
    ports: ["8085:8085"]
  cloud-tasks:
    image: ${CLOUD_TASKS_EMULATOR_IMAGE}
    ports: ["8123:8123"]
  oidc:
    image: ghcr.io/navikt/mock-oauth2-server:${MOCK_OAUTH2_TAG}
    ports: ["8090:8080"]
  proveedor:
    image: wiremock/wiremock:${WIREMOCK_TAG}
    volumes: ["./local/wiremock:/home/wiremock"]
  toxiproxy:
    image: ghcr.io/shopify/toxiproxy:${TOXIPROXY_TAG}
    ports: ["8474:8474", "8666:8666"]
  bootstrap:
    image: curlimages/curl:${CURL_TAG}
    depends_on: [pubsub, cloud-tasks, toxiproxy]
    volumes: ["./local/bootstrap:/scripts"]
    entrypoint: ["/bin/sh", "/scripts/bootstrap.sh"]
  otel-collector: { image: "otel/opentelemetry-collector-contrib:${OTEL_TAG}", profiles: ["observabilidad"] }
  prometheus:     { image: "prom/prometheus:${PROMETHEUS_TAG}", profiles: ["observabilidad"] }
  grafana:        { image: "grafana/grafana:${GRAFANA_TAG}", profiles: ["observabilidad"] }
  jaeger:         { image: "jaegertracing/all-in-one:${JAEGER_TAG}", profiles: ["observabilidad"] }
```

El script `bootstrap.sh` crea, mediante la API REST del emulador, los topics y suscripciones de §8.1 con los mismos nombres (incluida la política de dead-letter si SP-4 confirma soporte), las colas de §8.2 en el emulador de Cloud Tasks y los proxies de Toxiproxy (`proveedor` y `postgres`). Es idempotente: ejecutarlo dos veces no falla (CF-10).

### 15.6 Perfil `local` de la aplicación (`application-local.yml`, claves principales)

| Clave | Valor local |
|---|---|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/soat` |
| `spring.cloud.gcp.project-id` | `soat-local` |
| `spring.cloud.gcp.pubsub.emulator-host` | `localhost:8085` |
| `tareas.cloud-tasks.endpoint` | `localhost:8123` (sin TLS) |
| `tareas.url-base` | `http://host.docker.internal:8080` (para que el emulador alcance la app) |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | `http://localhost:8090/default` |
| `proveedor.base-url` | `http://localhost:8666` (Toxiproxy hacia WireMock) |
| `calendario.consultas.delays` | `[5s, 1m, 10m, 20m, 30m]` por defecto; el perfil `local-rapido` los reduce a segundos para la demo |
| Secretos HMAC | `HMAC_ACTUAL` y `HMAC_ANTERIOR` desde `.env` (valores de prueba de `.env.example`, nunca secretos reales) |

### 15.7 Flujo de trabajo del desarrollador

```bash
make local-up
```
```bash
make local-verify
```
```bash
./mvnw -pl gateway-app spring-boot:run -Dspring-boot.run.profiles=local
```
```bash
./local/scripts/publicar-solicitud.sh SOAT-000001
```
```bash
./local/scripts/enviar-webhook.sh SOAT-000001 APROBADO
```
```bash
make local-reset
```

`publicar-solicitud.sh` publica en el emulador una solicitud con el contrato de §6.3. `enviar-webhook.sh` firma el cuerpo con `HMAC_ACTUAL` y el timestamp actual, usando `openssl`. `local-reset` borra los volúmenes y vuelve a ejecutar el bootstrap.

### 15.8 Relación con los niveles de prueba

| Nivel | ¿Necesita Docker? | Qué usa |
|---|---|---|
| Unitario de dominio y aplicación, ArchUnit | No | JVM únicamente (fase 1 y 2 sin infraestructura) |
| Integración de adaptadores | Sí | Testcontainers, que levanta sus propios contenedores con las mismas imágenes del compose |
| Sistema (S-01–S-10) y caos local (X-01–X-07, X-09) | Sí | El compose completo, o Testcontainers con `ComposeContainer` en CI |
| Carga | Sí | k6 a 5 rps en local como prueba de humo; los 50 rps solo en staging |
| Exploratorio y demos | Sí | El compose más la app en perfil `local` |

---

**ESPERANDO CONFIRMACIÓN**: ¿Procedo con este plan? (sí / no / modificar: …)
