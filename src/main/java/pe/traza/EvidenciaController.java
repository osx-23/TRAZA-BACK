package pe.traza;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class EvidenciaController {
    private final JdbcTemplate db;
    private final StorageEvidenciasService storage;

    public EvidenciaController(JdbcTemplate db, StorageEvidenciasService storage) {
        this.db = db;
        this.storage = storage;
    }

    @GetMapping("/evidencias")
    public List<Map<String,Object>> listar(@RequestParam(required=false) UUID eventoId) {
        String sql="""
            select v.id::text as id,v.evento_id::text as "eventoId",v.categoria,
            v.nombre_archivo as nombre,v.mime,v.tamano,
            e.plaza_codigo as plaza,e.fecha::text as fecha
            from public.traza_evidencias v join public.traza_eventos e on e.id=v.evento_id
            """;
        return eventoId==null?db.queryForList(sql+" order by v.creado_en desc limit 150"):
            db.queryForList(sql+" where v.evento_id=? order by v.creado_en desc",eventoId);
    }

    @PostMapping(value="/eventos/{eventoId}/evidencias",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> subir(@PathVariable UUID eventoId,@RequestParam String categoria,
        @RequestParam("archivo") MultipartFile archivo) throws Exception {
        if(!List.of("PLAZA","INGRESO","INCIDENTE","COLA").contains(categoria))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Categoría inválida");
        String mime=archivo.getContentType();
        if(mime==null||!List.of("image/jpeg","image/png","image/webp").contains(mime))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Solo se aceptan imágenes JPG, PNG o WebP");
        if(archivo.isEmpty()||archivo.getSize()>4_000_000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El archivo debe ser menor a 4 MB");
        if(db.queryForObject("select count(*) from public.traza_eventos where id=?",Integer.class,eventoId)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Evento no encontrado");

        byte[] bytes=archivo.getBytes();
        if(!firmaValida(bytes,mime))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El contenido no coincide con el formato de imagen");

        String nombre=archivo.getOriginalFilename()==null?"evidencia":archivo.getOriginalFilename();
        if(nombre.length()>180) nombre=nombre.substring(0,180);
        UUID id=UUID.randomUUID();
        String path=storage.subir(eventoId,id,mime,bytes);
        try {
            db.update("""
                insert into public.traza_evidencias(id,evento_id,categoria,nombre_archivo,mime,storage_path,tamano)
                values(?,?,?,?,?,?,?)
                """,id,eventoId,categoria,nombre,mime,path,bytes.length);
        } catch (RuntimeException ex) {
            try { storage.eliminar(path); } catch (RuntimeException ignored) {
                // El objeto huérfano se puede depurar después; no ocultar el error de BD.
            }
            throw ex;
        }
        return Map.of("id",id.toString(),"eventoId",eventoId.toString(),
            "categoria",categoria,"nombre",nombre);
    }

    @GetMapping("/evidencias/{id}/archivo")
    public ResponseEntity<byte[]> archivo(@PathVariable UUID id) {
        var filas=db.query("""
            select mime,nombre_archivo,contenido,storage_path
            from public.traza_evidencias where id=?
            """,(rs,n)->new Archivo(rs.getString("mime"),rs.getString("nombre_archivo"),
                rs.getBytes("contenido"),rs.getString("storage_path")),id);
        if(filas.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Archivo no encontrado");
        Archivo a=filas.get(0);
        byte[] bytes=a.path()!=null ? storage.descargar(a.path()) : a.bytes();
        if(bytes==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Fotografía no disponible");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.inline().filename(a.nombre()).build().toString())
            .contentType(MediaType.parseMediaType(a.mime())).body(bytes);
    }

    private record Archivo(String mime,String nombre,byte[] bytes,String path){}

    @DeleteMapping("/evidencias/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPERVISOR')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminar(@PathVariable UUID id) {
        var found=db.queryForList("select storage_path from public.traza_evidencias where id=?",id);
        if(found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Evidencia no encontrada");
        String path=(String)found.get(0).get("storage_path");
        if(path!=null)storage.eliminar(path);
        db.update("delete from public.traza_evidencias where id=?",id);
    }

    private boolean firmaValida(byte[] b,String mime) {
        if("image/png".equals(mime))
            return b.length>=8 && (b[0]&255)==137 && b[1]==80 && b[2]==78 && b[3]==71
                && b[4]==13 && b[5]==10 && b[6]==26 && b[7]==10;
        if("image/jpeg".equals(mime))
            return b.length>=3 && (b[0]&255)==255 && (b[1]&255)==216 && (b[2]&255)==255;
        if("image/webp".equals(mime))
            return b.length>=12 && b[0]=='R' && b[1]=='I' && b[2]=='F' && b[3]=='F'
                && b[8]=='W' && b[9]=='E' && b[10]=='B' && b[11]=='P';
        return false;
    }
}
