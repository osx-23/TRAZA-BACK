-- Esquema TRAZA. Ejecutar en un proyecto Supabase limpio. No contiene DROP.
create table if not exists public.traza_usuarios (
 id uuid primary key default gen_random_uuid(),
 username varchar(80) not null unique,
 nombre varchar(140) not null,
 password_hash text not null,
 rol varchar(20) not null default 'OPERADOR' check(rol in ('OPERADOR','CONTROLADOR','SUPERVISOR')),
 activo boolean not null default true,
 creado_en timestamptz not null default now()
);
create table if not exists public.traza_plazas (
 codigo varchar(10) primary key,nombre varchar(90) not null,activo boolean not null default true
);
insert into public.traza_plazas(codigo,nombre)
select 'P'||s::text,'Plaza P'||s::text from generate_series(1,10) s on conflict do nothing;
create table if not exists public.traza_eventos (
 id uuid primary key default gen_random_uuid(),
 tipo varchar(12) not null check(tipo in ('INCIDENCIA','MEDICION')),
 plaza_codigo varchar(10) not null references public.traza_plazas(codigo),
 turno varchar(1) not null check(turno in ('A','B','C')),
 via varchar(60),fecha date not null,hora time not null,
 situacion varchar(60),ingreso varchar(60),incidente varchar(120),descripcion text,
 cola_metros integer check(cola_metros is null or cola_metros between 0 and 50000),
 estado varchar(20) not null default 'REGISTRADO' check(estado in ('REGISTRADO','ATENDIDO','EN_OBSERVACION')),
 creado_por uuid references public.traza_usuarios(id) on delete set null,
 creado_en timestamptz not null default now(),
 actualizado_en timestamptz not null default now(),
 constraint medida_con_cola check(tipo<>'MEDICION' or cola_metros is not null)
);
create index if not exists traza_eventos_filtros_idx on public.traza_eventos(fecha desc,plaza_codigo,tipo);
create table if not exists public.traza_evidencias (
 id uuid primary key default gen_random_uuid(),
 evento_id uuid not null references public.traza_eventos(id) on delete cascade,
 categoria varchar(12) not null check(categoria in ('PLAZA','INGRESO','INCIDENTE','COLA')),
 nombre_archivo varchar(180) not null,
 mime varchar(30) not null check(mime in ('image/jpeg','image/png','image/webp')),
 contenido bytea not null,
 tamano integer not null check(tamano between 1 and 4000000),
 creado_en timestamptz not null default now()
);
create index if not exists traza_evidencias_evento_idx on public.traza_evidencias(evento_id);
alter table public.traza_usuarios enable row level security;
alter table public.traza_plazas enable row level security;
alter table public.traza_eventos enable row level security;
alter table public.traza_evidencias enable row level security;
revoke all on public.traza_usuarios,public.traza_plazas,public.traza_eventos,public.traza_evidencias from anon,authenticated;
