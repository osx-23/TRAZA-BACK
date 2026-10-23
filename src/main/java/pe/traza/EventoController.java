package pe.traza;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api")
public class EventoController {
    private final JdbcTemplate db;
    private final StorageEvidenciasService storage;
    public EventoController(JdbcTemplate db, StorageEvidenciasService storage) {
        this.db=db;
        this.storage=storage;
    }

    public record EventoInput(
        @NotBlank String tipo, @NotBlank String plazaCodigo, @NotBlank String turno,
        String via, @NotNull LocalDate fecha, @NotNull LocalTime hora,
        String situacion, String ingreso, String incidente, String descripcion,
        Integer colaMetros, String estado) {}

    private static final String BASE = """
      select e.id::text as id,e.tipo,e.plaza_codigo as "plazaCodigo",e.turno,e.via,
      e.fecha::text as fecha,to_char(e.hora,'HH24:MI') as hora,e.situacion,e.ingreso,
      e.incidente,e.descripcion,e.cola_metros as "colaMetros",e.estado,
      to_char(e.creado_en at time zone 'America/Lima','YYYY-MM-DD HH24:MI') as "creadoEn",
      coalesce(u.nombre,'Sistema') as "creadoPor"
      from public.traza_eventos e left join public.traza_usuarios u on u.id=e.creado_por
      """;

    @GetMapping("/health")
    public Map<String,Object> health() { db.queryForObject("select 1",Integer.class); return Map.of("status","UP"); }

    @GetMapping("/catalogos")
    public Map<String,Object> catalogos() {
        return Map.of("plazas",db.queryForList("select codigo,nombre from public.traza_plazas where activo order by length(codigo),codigo"),
            "turnos",List.of("A","B","C"));
    }

    @GetMapping("/eventos")
    public List<Map<String,Object>> listar(@RequestParam(required=false) String tipo,
        @RequestParam(required=false) String plaza, @RequestParam(required=false) LocalDate desde,
        @RequestParam(required=false) LocalDate hasta) {
        StringBuilder sql=new StringBuilder(BASE+" where 1=1");
        List<Object> args=new ArrayList<>();
        if(tipo!=null&&!tipo.isBlank()){sql.append(" and e.tipo=?");args.add(tipo);}
        if(plaza!=null&&!plaza.isBlank()){sql.append(" and e.plaza_codigo=?");args.add(plaza);}
        if(desde!=null){sql.append(" and e.fecha>=?");args.add(desde);}
        if(hasta!=null){sql.append(" and e.fecha<=?");args.add(hasta);}
        sql.append(" order by e.fecha desc,e.hora desc,e.creado_en desc limit 500");
        return db.queryForList(sql.toString(),args.toArray());
    }
    @GetMapping("/eventos/{id}")
    public Map<String,Object> obtener(@PathVariable UUID id) {
        var found=db.queryForList(BASE+" where e.id=?",id);
        if(found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Evento no encontrado");
        return found.get(0);
    }

    private void validar(EventoInput e, UUID idExistente) {
        if(!List.of("INCIDENCIA","MEDICION").contains(e.tipo()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Tipo no válido");
        if(!List.of("A","B","C").contains(e.turno()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Turno no válido");
        if(e.colaMetros()!=null&&(e.colaMetros()<0||e.colaMetros()>50000))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Cola fuera de rango");
        if("MEDICION".equals(e.tipo())&&e.colaMetros()==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Indique tamaño de cola");
        if(e.estado()!=null&&!List.of("REGISTRADO","ATENDIDO","EN_OBSERVACION").contains(e.estado()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Estado no válido");
        if(e.via()!=null&&!e.via().isBlank()) {
            if(e.via().length()>60)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Vía demasiado larga");
            Integer asignada=db.queryForObject("""
                select count(*) from public.traza_plaza_vias
                where plaza_codigo=? and numero=? and activo=true
                """,Integer.class,e.plazaCodigo(),e.via().trim());
            if(asignada==null || asignada==0) {
                Integer historica=idExistente==null ? 0 : db.queryForObject("""
                    select count(*) from public.traza_eventos
                    where id=? and plaza_codigo=? and via=?
                    """,Integer.class,idExistente,e.plazaCodigo(),e.via().trim());
                if(historica==null || historica==0)
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "La vía no está asignada a la plaza seleccionada");
            }
        }
        if(e.situacion()!=null&&e.situacion().length()>60) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Situación demasiado larga");
        if(e.ingreso()!=null&&e.ingreso().length()>60) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Ingreso demasiado largo");
        if(e.incidente()!=null&&e.incidente().length()>120) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Incidente demasiado largo");
        if(e.descripcion()!=null&&e.descripcion().length()>10000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Descripción demasiado larga");
        Integer count=db.queryForObject("select count(*) from public.traza_plazas where codigo=? and activo=true",Integer.class,e.plazaCodigo());
        if(count==null||count==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Plaza no existe");
    }
    @PostMapping("/eventos")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> crear(@Valid @RequestBody EventoInput e, JwtAuthenticationToken user) {
        validar(e,null);
        UUID id=UUID.randomUUID();
        db.update("""
            insert into public.traza_eventos(id,tipo,plaza_codigo,turno,via,fecha,hora,situacion,ingreso,incidente,
             descripcion,cola_metros,estado,creado_por)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,e.tipo(),e.plazaCodigo(),e.turno(),e.via(),e.fecha(),e.hora(),e.situacion(),e.ingreso(),e.incidente(),
             e.descripcion(),e.colaMetros(),e.estado()==null?"REGISTRADO":e.estado(),UUID.fromString(user.getName()));
        return obtener(id);
    }
    @PutMapping("/eventos/{id}")
    public Map<String,Object> actualizar(@PathVariable UUID id,@Valid @RequestBody EventoInput e) {
        validar(e,id);
        int rows=db.update("""
            update public.traza_eventos set tipo=?,plaza_codigo=?,turno=?,via=?,fecha=?,hora=?,
            situacion=?,ingreso=?,incidente=?,descripcion=?,cola_metros=?,estado=?,actualizado_en=now()
            where id=?
            """,e.tipo(),e.plazaCodigo(),e.turno(),e.via(),e.fecha(),e.hora(),e.situacion(),e.ingreso(),
              e.incidente(),e.descripcion(),e.colaMetros(),e.estado()==null?"REGISTRADO":e.estado(),id);
        if(rows==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Evento no encontrado");
        return obtener(id);
    }
    @DeleteMapping("/eventos/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('ROLE_SUPERVISOR')")
    public void eliminar(@PathVariable UUID id) {
        if(db.queryForObject("select count(*) from public.traza_eventos where id=?",Integer.class,id)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Evento no encontrado");
        // Borrar objetos privados ANTES del ON DELETE CASCADE de traza_evidencias.
        List<String> paths=db.queryForList("""
            select storage_path from public.traza_evidencias
            where evento_id=? and storage_path is not null
            """,String.class,id);
        for(String path:paths)storage.eliminar(path);
        db.update("delete from public.traza_eventos where id=?",id);
    }

    @GetMapping("/estadisticas")
    public Map<String,Object> estadisticas() {
        Map<String,Object> kpi=db.queryForMap("""
            select count(*) filter (where tipo='INCIDENCIA') as "incidencias",
            count(*) filter (where tipo='MEDICION') as "mediciones",
            coalesce(max(cola_metros),0) as "maxCola",
            coalesce(round(avg(cola_metros)::numeric,1),0) as "promedioCola",
            count(*) as "total"
            from public.traza_eventos
            """);
        List<Map<String,Object>> diario=db.queryForList("""
            select fecha::text as fecha,count(*) as total,
            coalesce(max(cola_metros),0) as "maxCola" from public.traza_eventos
            where fecha >= current_date-13 group by fecha order by fecha
            """);
        List<Map<String,Object>> plazas=db.queryForList("""
            select p.codigo as plaza,count(e.id) as total,
            count(e.id) filter(where e.tipo='INCIDENCIA') as incidencias,
            coalesce(max(e.cola_metros),0) as "maxCola"
            from public.traza_plazas p left join public.traza_eventos e on e.plaza_codigo=p.codigo
            group by p.codigo order by length(p.codigo),p.codigo
            """);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("kpi",kpi);result.put("diario",diario);result.put("plazas",plazas);
        result.put("recientes",db.queryForList(BASE+" order by e.creado_en desc limit 7"));
        return result;
    }
}
