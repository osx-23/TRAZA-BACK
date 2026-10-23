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
**Para producción:** utilizar HTTPS, contraseñas fuertes, conexión Supabase con SSL, rate limiting de login y backups. Evidencias JPG/PNG/WebP de 4 MB máximo se guardan como BYTEA en PostgreSQL (MVP).

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
