package pe.traza;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Catálogo de vías independientes por plaza. Los controladores pueden consultar;
 * solo los supervisores pueden agregar o desactivar asignaciones.
 */
@RestController
@RequestMapping("/api")
public class PlazaViasController {
    private final JdbcTemplate db;

    public PlazaViasController(JdbcTemplate db) {
        this.db = db;
    }

    public record RangoVias(Integer desde, Integer hasta) {}

    @GetMapping("/plazas/{plaza}/vias")
    public List<Map<String,Object>> listar(@PathVariable String plaza) {
        validarPlaza(plaza);
        return db.queryForList("""
            select numero from public.traza_plaza_vias
            where plaza_codigo=? and activo=true order by numero::int
            """, plaza);
    }

    @GetMapping("/vias")
    @PreAuthorize("hasAuthority('ROLE_SUPERVISOR')")
    public List<Map<String,Object>> listarTodas() {
        return db.queryForList("""
            select v.plaza_codigo as "plazaCodigo", v.numero, v.activo
            from public.traza_plaza_vias v
            order by length(v.plaza_codigo), v.plaza_codigo, v.numero::int
            """);
    }

    @PostMapping("/plazas/{plaza}/vias")
    @PreAuthorize("hasAuthority('ROLE_SUPERVISOR')")
    public List<Map<String,Object>> asignar(@PathVariable String plaza, @RequestBody RangoVias rango) {
        validarPlaza(plaza);
        if(rango == null || rango.desde() == null || rango.hasta() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica vía inicial y final");
        int desde=rango.desde(), hasta=rango.hasta();
        if(desde<1 || hasta<desde || hasta>999999 || hasta-desde>99)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rango inválido (máximo 100 vías)");
        db.update("""
            insert into public.traza_plaza_vias (plaza_codigo,numero,activo)
            select ?, numero::text, true from generate_series(?,?) as numero
            on conflict (plaza_codigo,numero) do update set activo=true
            """,plaza,desde,hasta);
        return listar(plaza);
    }

    @DeleteMapping("/plazas/{plaza}/vias/{numero}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('ROLE_SUPERVISOR')")
    public void desactivar(@PathVariable String plaza, @PathVariable String numero) {
        validarPlaza(plaza);
        if(!numero.matches("[0-9]{1,6}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Número de vía inválido");
        int filas=db.update("""
            update public.traza_plaza_vias set activo=false
            where plaza_codigo=? and numero=? and activo=true
            """,plaza,numero);
        if(filas==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"La vía no está activa en esta plaza");
    }

    private void validarPlaza(String codigo) {
        Integer existe=db.queryForObject(
            "select count(*) from public.traza_plazas where codigo=? and activo=true",
            Integer.class,codigo);
        if(existe==null || existe==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Plaza no encontrada o inactiva");
    }
}
