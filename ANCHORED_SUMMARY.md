# Reproducción Offline — Progreso

## Goal
- Hacer que la reproducción offline sea fluida: cuando se acaba el caché, pasar a la siguiente canción sin pausas; al tocar/arrastrar la barra, responder sin errores ni saltos no deseados.

## Constraints & Preferences
- Sin conexión → nunca pausar el reproductor (a menos que el usuario toque pausa)
- Al arrastrar la barra, el pulgar debe quedarse donde se suelta y desde ahí reproducir, no saltar hacia atrás
- Al tocar la barra verde, ir inmediatamente a esa posición (con snap conservador solo en el último span)
- El snap al inicio del span solo aplica al último bloque cacheado; los demás usan posición exacta
- No calcular spans durante el arrastre (solo al soltar)
- La transición entre canciones al agotarse el caché debe ser inmediata, sin demora

## Progress
### Done
- **onPlayerError offline — skip inmediato en último span**: Detectamos si la posición actual está en el ÚLTIMO bloque cacheado (`inLastSpan`). Si es así, skip directo (sin esperar ~3.3s del Loader backoff). Si está en un span no-último → resume (error de read-ahead en gap). Si está fuera del cache → skip.
- **CachedSeekBar – drag**: durante arrastre offline solo actualiza `dragFraction` (visual). Al soltar, snap + seek una sola vez. Sin cálculos intermedios.
- **CachedSeekBar – tap**: `detectTapGestures` para salto inmediato al tocar (con snap si offline).
- **CachedSeekBar – snap final**: `snapToNearestCached` devuelve `span.startMs` solo para el último span; spans no-últimos usan posición exacta.
- **CachedSeekBar – thumb no retrocede**: `pendingSeekTargetMs` + `LaunchedEffect` para que `dragFraction` no se resetee hasta que el player alcance la posición (diferencia < 300ms).
- **StreamResolver – fallback offline**: URL dummy cambiada de `http://127.0.0.1:9/…` a `data:application/octet-stream;base64,` (sin conexión de red ni CLEARTEXT).
- **StreamResolver – pre-poblado offlineCache**: `markOfflineFallbackForCached()` pobla `offlineFallbackCache` para tracks restantes al skipear (`onPlayerError`) y al perder conectividad (`monitorConnectivity.onLost`). Evita llamadas a la API de YouTube cuando no hay internet.
- **`lastErrorPositionMs` removido**: el tracking de errores repetidos ya no se necesita. Se reemplazó por `inLastSpan` (más directo y confiable).

### In Progress
- *(ninguno)*

### Blocked
- *(ninguno)*

## Key Decisions
- **Skip inmediato en lugar de resume+retry**: Con `inLastSpan`, detectamos en el PRIMER error que el caché se agotó y skipeamos. La vieja lógica (`lastErrorPositionMs`) esperaba un segundo error (~3.3s después del Loader backoff), causando una pausa audible.
- URL fallback offline cambiada a `data:application/octet-stream;base64,` porque `http://127.0.0.1:9/…` seguía bloqueado por CLEARTEXT incluso con `base-config` (posiblemente requiere reinstalación completa o no aplicaba). El `DataSchemeDataSource` de Media3 sirve el data: vacío instantáneamente sin red ni timeouts.
- No se puede usar `subrange(0, 0)` porque `DataSpec` en Media3 exige `length > 0 || length == -1` (lanza `IllegalArgumentException`).
- El `offlineFallbackCache` es en memoria (no persistido). Se pre-puebla al perder conectividad o al skipear para evitar reintentos de red.

## Next Steps
- Probar que el skip inmediato en último span elimine la demora de ~3.3s.
- Verificar que la transición entre canciones al agotarse el caché sea inmediata (sin pausa audible ni delay en UI).
- Probar arrastre/tap offline con spans múltiples y gap entre spans.

## Critical Context
- **Demora ~3.3s en skip (antiguo)**: causada por el exponential backoff del `Loader` de ExoPlayer cuando reintenta el upstream (HTTP que fallaba). Ahora se evita con `inLastSpan`: skipeamos en el primer error sin reintentar.
- **`lastErrorPositionMs` obsoleto**: la vieja lógica reanudaba en el primer error (asumiendo error obsoleto) y skipeaba en el segundo (misma posición). Ahora `inLastSpan` es más directo: si estamos en el último bloque cacheado, el error solo puede ser por caché agotado → skip inmediato.
- **`coerceAtLeast(0)` inválido**: `DataSpec.subrange()` chequea `length > 0 || length == -1`. `coerceAtLeast(0)` produce `length=0` → `IllegalArgumentException` en `DataSpec.<init>`. Se revirtió a `coerceAtLeast(1)`.
- **Flujo offline actual**: error upstream → `onPlayerError` → si en último span → skip + play con `offlineFallbackCache` pre-poblado para siguientes tracks. Si en span no-último → resume. Si fuera del cache → skip.

## Relevant Files
- `app/src/main/java/com/music/app/player/StreamResolver.kt`: offline fallback con `data:application/octet-stream;base64,`, `offlineFallbackCache`, métodos `markOfflineFallback()` y `markOfflineFallbackForCached()`
- `app/src/main/java/com/music/app/ui/components/CachedSeekBar.kt`: drag solo visual, tap inmediato, snap conservador en último span, `pendingSeekTargetMs` para evitar salto del thumb
- `app/src/main/java/com/music/app/ui/screens/MainViewModel.kt`: `onPlayerError` con `inLastSpan` detecta caché agotado y skipea inmediatamente, `monitorConnectivity.onLost` marca URIs para offline
