package pe.traza;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;

    public AuthController(JdbcTemplate db, PasswordEncoder passwords, JwtEncoder encoder) {
        this.db=db; this.passwords=passwords; this.encoder=encoder;
    }
    public record Credentials(@NotBlank String username, @NotBlank String password) {}
    @PostMapping("/login")
    public Map<String,Object> login(@Valid @RequestBody Credentials credentials) {
        List<Map<String,Object>> users = db.queryForList(
          "select id::text as id,username,nombre,password_hash,rol from public.traza_usuarios where lower(username)=lower(?) and activo=true",
          credentials.username().trim());
        if (users.isEmpty() || !passwords.matches(credentials.password(), (String) users.get(0).get("password_hash")))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Usuario o contraseña incorrectos");
        var u = users.get(0);
        Instant now=Instant.now();
        JwtClaimsSet claims=JwtClaimsSet.builder().issuer("traza-api").subject((String)u.get("id"))
            .issuedAt(now).expiresAt(now.plus(6, ChronoUnit.HOURS))
            .claim("rol",u.get("rol")).claim("nombre",u.get("nombre")).build();
        String token=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
        return Map.of("token",token,"nombre",u.get("nombre"),"rol",u.get("rol"),"expiresIn",21600);
    }
    @Bean ApplicationRunner bootstrapUser(
        @Value("${traza.bootstrap-user}") String username,
        @Value("${traza.bootstrap-password}") String password) {
        return args -> {
            Integer count=db.queryForObject("select count(*) from public.traza_usuarios",Integer.class);
            if (count!=null && count==0) {
                if (username.isBlank() || password.length()<12) throw new IllegalStateException(
                  "Primera instalación: configure TRAZA_BOOTSTRAP_USERNAME y TRAZA_BOOTSTRAP_PASSWORD (>=12 caracteres)");
                db.update("insert into public.traza_usuarios(username,nombre,password_hash,rol) values (?,?,?,'SUPERVISOR')",
                    username.trim(),"Administrador TRAZA",passwords.encode(password));
            }
        };
    }
}
