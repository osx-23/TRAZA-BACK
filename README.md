# TRAZA — API Spring Boot

Backend de TRAZA con **Java 21**, **Spring Boot 3.4.2**, **PostgreSQL/Supabase**, **Spring Security JWT**.

## Base de datos
Proyecto Supabase TRAZA: `mmoefoeywkydbnivirhu`. El esquema inicial se aplicó en Supabase (migración `inicial_traza_con_respaldo_seguro`). Tablas: `traza_usuarios`, `traza_plazas`, `traza_eventos`, `traza_evidencias`.
Las tablas antiguas fueron conservadas en `traza_respaldo_20261023`; no eliminarlas sin revisar sus datos.

## Requisitos y ejecución
1. Java 21 y Maven 3.9+.
2. Crear variables de entorno siguiendo `.env.example`. Obtener la conexión PostgreSQL directamente en Supabase > Connect. **Nunca confirmar contraseñas ni JWT secret en Git.**
3. Definir `TRAZA_BOOTSTRAP_USERNAME` y `TRAZA_BOOTSTRAP_PASSWORD` (12+ caracteres) para crear el primer supervisor cuando no haya usuarios.
4. Ejecutar `mvn spring-boot:run` con las variables exportadas.
5. Probar `GET http://localhost:8080/api/health`.

## Seguridad
Solo `POST /api/auth/login` y `GET /api/health` son públicos. El resto usa `Authorization: Bearer <token>`. Eliminaciones requieren rol SUPERVISOR. JWT expira en 6 horas. No exponer credenciales DB al navegador. Tablas públicas con RLS habilitado, sin acceso directo de anon/authenticated; el backend se conecta mediante JDBC.
**Para producción:** utilizar HTTPS, contraseñas fuertes, conexión Supabase con SSL, rate limiting de login y backups. Evidencias JPG/PNG/WebP de hasta 4 MB se guardan en Supabase Storage (bucket privado `traza-evidencias`); PostgreSQL conserva metadatos y `storage_path`. El backend mantiene compatibilidad de lectura con registros antiguos en `BYTEA`.

## Endpoints
- `POST /api/auth/login` credenciales; devuelve token y rol
- `GET /api/catalogos` plazas y turnos
- `GET|POST /api/eventos` filtro por tipo, plaza, desde y hasta
- `GET|PUT|DELETE /api/eventos/{id}`
- `GET /api/estadisticas` KPIs, datos diarios, por plaza, recientes
- `GET /api/evidencias?eventoId=...`
- `POST /api/eventos/{id}/evidencias` multipart `categoria` + `archivo`
- `GET /api/evidencias/{id}/archivo`; `DELETE /api/evidencias/{id}`

Tipos: INCIDENCIA, MEDICION. Estados: REGISTRADO, EN_OBSERVACION, ATENDIDO. Categorías foto: PLAZA, INGRESO, INCIDENTE, COLA.

## Supabase Storage (evidencias)
- Proyecto Supabase: `mmoefoeywkydbnivirhu`. Bucket PRIVADO: `traza-evidencias`, creado con límite de 4 MB y formatos JPEG, PNG y WebP.
- Variables SOLO del backend: `TRAZA_STORAGE_URL=https://mmoefoeywkydbnivirhu.supabase.co` y `TRAZA_STORAGE_SERVICE_KEY` (clave secreta `sb_secret_...` del proyecto TRAZA (o `service_role` legacy); obtenerla en Supabase > Project Settings > API Keys). No subir esta clave a Git ni incluirla en Vercel o Angular.
- La configuración Spring Boot está en `src/main/resources/application.properties` (no en YAML).
- Esquema: `public.traza_evidencias.storage_path` contiene `eventos/{eventoId}/{evidenciaId}.jpg|png|webp`; `contenido BYTEA` se conserva solo como compatibilidad con evidencias anteriores.
- El frontend **no** accede a Storage ni tiene claves de servicio; mantiene los endpoints existentes de carga, consulta y eliminación. El backend descarga imágenes privadas únicamente para usuarios que ya tienen acceso autorizado a la API.
- Al borrar una evidencia o un evento, el backend elimina sus objetos de Storage; ante un fallo externo puede requerirse revisar objetos huérfanos. La escritura primero sube a Storage y luego confirma los metadatos; si falla el INSERT, intenta limpiar el objeto.
- Antes de registrar fotografías, definir `TRAZA_STORAGE_SERVICE_KEY` en el backend local o en Railway. Sin la clave, los endpoints de fotos devuelven HTTP 503 (no vuelven a escribir bytes en PostgreSQL).
- Comprobación funcional: iniciar sesión, registrar incidencia, subir evidencia, consultar `/api/evidencias/{id}/archivo`, revisar el objeto privado en Storage y eliminarlo; comprobar la eliminación al borrar el evento. Revisar errores HTTP en los logs del backend sin exponer credenciales.
