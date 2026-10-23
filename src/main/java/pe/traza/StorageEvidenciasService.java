package pe.traza;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Guarda evidencias en el bucket PRIVADO de Supabase.
 * La clave de servicio solo se utiliza desde Spring Boot, nunca desde Angular.
 */
@Service
public class StorageEvidenciasService {
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();
    private final String url;
    private final String bucket;
    private final String secret;

    public StorageEvidenciasService(
        @Value("${traza.storage.url}") String url,
        @Value("${traza.storage.bucket:traza-evidencias}") String bucket,
        @Value("${traza.storage.service-key:}") String secret) {
        this.url = url.replaceAll("/+$", "");
        this.bucket = bucket;
        this.secret = secret;
    }

    public String subir(UUID eventoId, UUID evidenciaId, String mime, byte[] bytes) {
        String extension = switch (mime) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Formato no permitido");
        };
        String path = "eventos/" + eventoId + "/" + evidenciaId + "." + extension;
        HttpRequest req = request("/storage/v1/object/" + bucket + "/" + path)
            .header("Content-Type", mime)
            .header("x-upsert", "false")
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
        HttpResponse<byte[]> respuesta = enviar(req);
        if (respuesta.statusCode() < 200 || respuesta.statusCode() >= 300)
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "No se pudo almacenar la fotografía en Supabase Storage (HTTP " + respuesta.statusCode() + ")");
        return path;
    }

    public byte[] descargar(String path) {
        HttpRequest req = request("/storage/v1/object/authenticated/" + bucket + "/" + rutaValida(path))
            .GET().build();
        HttpResponse<byte[]> respuesta = enviar(req);
        if (respuesta.statusCode() == 404)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fotografía no encontrada en Storage");
        if (respuesta.statusCode() < 200 || respuesta.statusCode() >= 300)
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "No se pudo consultar la fotografía en Supabase Storage");
        return respuesta.body();
    }

    public void eliminar(String path) {
        HttpRequest req = request("/storage/v1/object/" + bucket + "/" + rutaValida(path))
            .DELETE().build();
        HttpResponse<byte[]> respuesta = enviar(req);
        if ((respuesta.statusCode() < 200 || respuesta.statusCode() >= 300) &&
            respuesta.statusCode() != 404)
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "No se pudo eliminar la fotografía de Supabase Storage");
    }

    private HttpRequest.Builder request(String path) {
        if (secret.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Configura TRAZA_STORAGE_SERVICE_KEY en el entorno del backend");
        return HttpRequest.newBuilder(URI.create(url + path))
            .timeout(Duration.ofSeconds(30))
            .header("apikey", secret)
            .header("Authorization", "Bearer " + secret);
    }

    private String rutaValida(String path) {
        if (path == null || !path.matches("eventos/[0-9a-fA-F-]{36}/[0-9a-fA-F-]{36}\\.(jpg|png|webp)"))
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Ruta de evidencia no válida");
        return path;
    }

    private HttpResponse<byte[]> enviar(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Interrupción al conectar con Storage", ex);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo conectar con Storage", ex);
        }
    }
}
