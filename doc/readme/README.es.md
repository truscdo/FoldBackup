# FoldBackup

[English](../../README.md) | [简体中文](README.zh.md) | Español | [Português (Brasil)](README.pt-br.md) | [Русский](README.ru.md)

FoldBackup es una herramienta de copias de seguridad de mundos para servidores de Minecraft con NeoForge. Admite copias de seguridad por instantáneas automáticas y manuales, compresión en archivos de respaldo, vistas previas de chunks históricos y restauración de chunks.

Las instantáneas utilizan un esquema de deduplicación de contenido similar a Git: cada copia de seguridad registra únicamente los archivos nuevos y modificados. Los archivos de región se almacenan en formato LRH. En un servidor de supervivencia de Minecraft 1.21.1 con NeoForge y más de 80 mods, un archivo comprimido creado con el nivel de compresión 19 puede ocupar tan solo un tercio del tamaño original del mundo.

El mismo archivo JAR puede instalarse como un mod de NeoForge o ejecutarse como una herramienta independiente. Ofrece una interfaz de línea de comandos (CLI) y un menú interactivo (TUI) para poder usar sus funciones mientras el servidor está apagado.

- **Entorno**: Minecraft `1.21.1`, NeoForge `21.1+`, Java `21+` (las funciones ofrecidas por el modo independiente CLI / TUI no dependen de la versión del juego y son compatibles con la mayoría de las versiones de Minecraft)
- **Instalación**: Solo se requiere en el **servidor** (o en el cliente para un jugador); los jugadores que se conecten al servidor **no** necesitan instalar este mod en sus clientes para usar todas las funciones (incluidas las vistas previas de chunks históricos dentro del juego)

## Características

- **Copias de seguridad en línea**: Programa copias de seguridad basadas en el tiempo con jugadores conectados, inícialas manualmente o realiza copias opcionales al iniciar o detener el servidor.
- **Copias de seguridad sin conexión**: Crea copias de seguridad de mundos con la TUI o la CLI sin iniciar el juego.
- **Deduplicación de contenido**: El contenido sin cambios no ocupa espacio adicional en una nueva instantánea.
- **Gestión de instantáneas**: Consulta el resumen de espacio del repositorio y el espacio único, compartido y recuperable acumulado de cada instantánea, fija instantáneas importantes (`pin`) para protegerlas de la limpieza automática, estima el espacio a liberar antes de la eliminación por lotes y configura la limpieza automática.
- **Vista previa y restauración de chunks**: Previsualiza cómo se veía un chunk en una instantánea anterior y restaura un único chunk de la instantánea seleccionada en el mundo; también puedes restaurar chunks directamente en una partida guardada mediante la CLI sin iniciar el juego, útil para resolver chunks problemáticos o cierres inesperados causados por demasiadas entidades.
- **Restauración completa rápida**: Al exportar una instantánea como un mundo de Minecraft, si ya existe una partida guardada en la ubicación de destino, se omiten las partes sin cambios para acelerar el proceso de restauración.
- **Compresión en archivos (Archives)**: Crea archivos completos o diferenciales de alta compresión para distribución o almacenamiento, que pueden restaurarse como un mundo de Minecraft o importarse a un repositorio de instantáneas; en servidores, los archivos diferenciales permiten transferir únicamente las partes modificadas del mundo para editar el mapa localmente.

> [!NOTE]
> **Diferencia entre instantáneas (Snapshots) y archivos (Archives)**:
>
> - **Instantáneas**: Copias de seguridad frecuentes (automáticas o manuales) almacenadas con deduplicación incremental en un repositorio local, utilizadas para restauraciones rápidas, exportación de mundos y recuperación de chunks individuales.
> - **Archivos**: Archivos independientes optimizados para una alta tasa de compresión (admiten archivos completos y diferenciales), adecuados para copias en frío externas, sincronización entre equipos o distribución de mundos.

### LRH y Linear

**LRH es una implementación en flujo (streaming) basada en el formato de región Linear** y se utiliza para comprimir y almacenar datos de regiones en FoldBackup. El proyecto original es [xymb-endcrystalme/LinearRegionFileFormatTools](https://github.com/xymb-endcrystalme/LinearRegionFileFormatTools). Agradecemos a sus autores el diseño y la implementación del formato.

FoldBackup también introduce el formato LRHD para registrar diferencias de chunks en los archivos comprimidos, lo que permite realizar copias de seguridad y almacenamiento diferencial a nivel de chunk.

## Inicio rápido

### Crear una copia de seguridad

1. Coloca el archivo JAR de FoldBackup en el directorio `mods/` del servidor NeoForge.
2. Inicia el servidor y espera a que el mundo se cargue y FoldBackup se inicialice.
3. Dentro del juego, ejecuta los siguientes comandos con nivel de permisos 2 o superior:

   ```text
   /foldbackup backup --force
   /foldbackup list
   ```

   En el modo de un jugador, el anfitrión del mundo puede usar estos comandos aunque los trucos estén desactivados. Otros jugadores que se unan por LAN no podrán usarlos.

De forma predeterminada, las copias de seguridad se guardan en `foldbackups/` dentro del directorio de trabajo del servidor y puedes cambiarlo con `general.backupPath`. Un servidor dedicado usa este directorio directamente como repositorio, mientras que en un jugador se utiliza `foldbackups/<nombre-de-carpeta-del-mundo>` para que cada mundo mantenga su propio repositorio.

Las copias de seguridad automáticas están activadas por defecto. El intervalo contabiliza el tiempo acumulado mientras hay jugadores conectados. Las copias manuales también están sujetas al intervalo mínimo entre copias; usa `/foldbackup backup --force` para omitirlo.

Incluso sin instalar el mod en el juego, puedes crear instantáneas sin conexión para partidas guardadas de cualquier versión de Minecraft mediante la CLI o la TUI independiente:

<details>
<summary>Haz clic para expandir las instrucciones para crear copias de seguridad sin conexión con CLI / TUI</summary>

> Antes de ejecutar la TUI o la CLI, primero debes exportar las bibliotecas de ejecución incluidas en el JAR (este diseño evita conflictos de dependencias entre el mod y la CLI) y luego ejecutar la TUI o la CLI (puedes omitir este paso si utilizas el script de inicio rápido de la TUI).
>
> ```bash
> # Servidor dedicado: foldbackups/
> java -jar ../mods/foldbackup-<version>.jar extract
>
> # Un jugador: foldbackups/<nombre-de-carpeta-del-mundo>
> java -jar ../../mods/foldbackup-<version>.jar extract
> ```
>
> Los parámetros predeterminados de los comandos asumen que el directorio actual es el directorio del repositorio del mundo de destino (`foldbackups/` en un servidor dedicado y `foldbackups/<nombre-de-carpeta-del-mundo>` en un jugador). Si los ejecutas desde otra ubicación, ajusta los parámetros correspondientes.

**Antes de crear una copia de seguridad sin conexión debes detener el servidor o salir del mundo de un jugador.**

**Crear una instantánea con la CLI:**

```bash
# Crear una instantánea con las reglas de filtrado predeterminadas
java -jar ../mods/foldbackup-<version>.jar snapshot create --world ../world

# Añadir reglas de exclusión adicionales (entrecomilla en tu shell las reglas con * o ** para evitar su expansión prematura)
java -jar ../mods/foldbackup-<version>.jar snapshot create --world ../world \
  --exclude "**/DistantHorizons.sqlite" \
  --exclude "cache/"
```

- `--world <directorio>`: Obligatorio; apunta al directorio raíz de la partida guardada.
- `--exclude <glob>`: Opcional y repetible; añade exclusiones sobre las reglas de filtrado predeterminadas.

**Crear una instantánea con la TUI:**

Ejecuta `java -jar ../mods/foldbackup-<version>.jar tui` (o el script de inicio) en el directorio del repositorio, selecciona **`6. Create snapshot from world`** y sigue las indicaciones:

1. **Directorio del mundo**: Introduce la ruta del directorio raíz de la partida guardada (pulsa Intro para volver al menú principal).
2. **Reglas de exclusión adicionales**: La pantalla muestra primero las reglas de protección predeterminadas; si quieres añadir más, escribe varias reglas separadas por comas en una sola línea (admite `{a,b}`, por ejemplo `**/DistantHorizons.sqlite, cache/`). Pulsa Intro para no añadir ninguna o escribe `0` para volver; si alguna regla no es válida, se pedirá volver a introducirla en el momento.
3. **Número de hilos**: Introduce un entero positivo, pulsa Intro para selección automática o escribe `0` para volver.

**Reglas de filtrado de archivos:**

- La creación de instantáneas sin conexión conserva siempre 4 reglas básicas predeterminadas: `**/session.lock`, `**/backups`, `**/.backups` y `**/*.wip` (a diferencia de la configuración predeterminada del mod, el modo sin conexión no excluye `**/DistantHorizons.sqlite` por defecto; añádelo manualmente como se indica arriba si lo necesitas).
- Las reglas comparan rutas relativas dentro del directorio del mundo usando `/` como separador y **no pueden empezar por `/`** (por ejemplo, escribe `backups/` en lugar de `/backups/`); se admiten `*`, `**`, `?`, `[...]` y `{a,b}`.
- Si coincide un directorio, se omite todo su subárbol; una regla terminada en `/` (como `cache/`) coincide solo con directorios y no con archivos del mismo nombre. Las reglas vacías, una `/` inicial o una sintaxis glob inválida detienen la operación inmediatamente con un error.

</details>

### Inicio rápido de la TUI

Si prefieres un lanzador, guarda uno de los siguientes scripts en el directorio del repositorio como `foldbackup-tui.bat` o `foldbackup-tui.sh` (y dale permisos con `chmod +x` en Linux). El script busca el JAR junto a sí mismo, luego en `../mods` y después en `../../mods`, y exporta automáticamente las bibliotecas de ejecución.

<details>
<summary>Haz clic para expandir el script de inicio para Windows (<code>foldbackup-tui.bat</code>)</summary>

```bat
@echo off
setlocal DisableDelayedExpansion
set "SCRIPT_DIR=%~dp0"
set "JAR="
call :find "%SCRIPT_DIR%."
call :find "%SCRIPT_DIR%..\mods"
call :find "%SCRIPT_DIR%..\..\mods"
if not defined JAR (
    echo Error: foldbackup jar not found beside this script, in ..\mods, or in ..\..\mods.
    pause
    exit /b 1
)
java -jar "%JAR%" extract --output "%SCRIPT_DIR%.foldbackup-cli"
if errorlevel 1 (
    echo Error: failed to export bundled libraries ^(read-only repository directory?^).
    pause
    exit /b 1
)
java -jar "%JAR%" tui --snapshot-root "%SCRIPT_DIR%." --archive-root "%SCRIPT_DIR%." --lib "%SCRIPT_DIR%.foldbackup-cli"
set "RESULT=%ERRORLEVEL%"
if not "%RESULT%"=="0" pause
exit /b %RESULT%

:find
if defined JAR exit /b 0
if exist "%~1\foldbackup.jar" set "JAR=%~1\foldbackup.jar"
if defined JAR exit /b 0
for %%f in ("%~1\foldbackup-*.jar") do if not defined JAR if exist "%%~ff" set "JAR=%%~ff"
exit /b 0
```

</details>

<details>
<summary>Haz clic para expandir el script de inicio para Linux / macOS (<code>foldbackup-tui.sh</code>)</summary>

```sh
#!/bin/sh
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR=
for dir in "$SCRIPT_DIR" "$SCRIPT_DIR/../mods" "$SCRIPT_DIR/../../mods"; do
    if [ -f "$dir/foldbackup.jar" ]; then
        JAR="$dir/foldbackup.jar"
        break
    fi
    for candidate in "$dir"/foldbackup-*.jar; do
        if [ -f "$candidate" ]; then
            JAR="$candidate"
            break
        fi
    done
    if [ -n "$JAR" ]; then
        break
    fi
done
if [ -z "$JAR" ]; then
    echo "Error: foldbackup jar not found beside this script, in ../mods, or in ../../mods." >&2
    exit 1
fi
java -jar "$JAR" extract --output "$SCRIPT_DIR/.foldbackup-cli" || {
    echo "Error: failed to export bundled libraries (read-only repository directory?)." >&2
    exit 1
}
exec java -jar "$JAR" tui --snapshot-root "$SCRIPT_DIR" --archive-root "$SCRIPT_DIR" --lib "$SCRIPT_DIR/.foldbackup-cli"
```

</details>

### Restaurar un chunk

Puedes previsualizar y restaurar un único chunk dentro del juego, o usar la CLI para restaurar un chunk directamente en un mundo sin conexión.

Dentro del juego, usa `/foldbackup preview from <snapshot-id>` (por defecto el chunk en el que estás) o `/foldbackup preview from <snapshot-id> at <x> <z>` para obtener una vista previa de un chunk desde una instantánea. Esto cambia la apariencia del chunk de destino en el cliente para mostrar su estado en el momento de esa instantánea, sin modificar el chunk real. `x` y `z` son coordenadas de chunk; puedes verlas en la pantalla F3 o dividir las coordenadas de bloque entre 16 redondeando hacia abajo. Un efecto de partículas azules marca el chunk previsualizado. Usa `/foldbackup preview exit` para salir de la vista previa.

Para restaurar un chunk dentro del juego, usa `/foldbackup rollback chunk from <snapshot-id>` para restaurar el chunk en el que te encuentras. Para indicar coordenadas concretas, añade `at <x> <z>` o `in <dimensión> at <x> <z>` (obligatorio desde la consola del servidor o RCON), y opcionalmente limita el alcance con `blocks` (solo terreno, entidades de bloque y POI, conservando las entidades actuales) o `entities` (solo entidades, sin tocar el terreno ni las entidades de bloque): por ejemplo, `/foldbackup rollback chunk blocks from <snapshot-id> at <x> <z>` o `/foldbackup rollback chunk entities from <snapshot-id> in <dimensión> at <x> <z>`. El chunk de destino debe estar cargado en memoria. Para un chunk no cargado, utiliza el método sin conexión mediante la CLI que se muestra a continuación:

<details>
<summary>Haz clic para expandir las instrucciones de restauración sin conexión con CLI</summary>

> Antes de ejecutar la TUI o la CLI, primero debes exportar las bibliotecas de ejecución incluidas en el JAR (este diseño evita conflictos de dependencias entre el mod y la CLI) y luego ejecutar la TUI o la CLI (puedes omitir este paso si utilizas el script de inicio rápido de la TUI).
>
> ```bash
> # Servidor dedicado: foldbackups/
> java -jar ../mods/foldbackup-<version>.jar extract
>
> # Un jugador: foldbackups/<nombre-de-carpeta-del-mundo>
> java -jar ../../mods/foldbackup-<version>.jar extract
> ```
>
> Los parámetros predeterminados de los comandos asumen que el directorio actual es el directorio del repositorio del mundo de destino (`foldbackups/` en un servidor dedicado y `foldbackups/<nombre-de-carpeta-del-mundo>` en un jugador). Si los ejecutas desde otra ubicación, ajusta los parámetros correspondientes.

**Detén el servidor antes de realizar una restauración sin conexión.**

Listar las instantáneas:

```bash
java -jar ../mods/foldbackup-<version>.jar snapshot list
```

Copia el ID de la instantánea de destino de la lista y restaura el chunk. El siguiente comando restaura el chunk `[x, z]` en el directorio de la superficie (`world`) a su estado en dicha instantánea:

```bash
java -jar ../mods/foldbackup-<version>.jar snapshot rollback <snapshot-id> --world ../world --chunk <chunk-x> <chunk-z> [--scope <all|blocks|entities>]
```

`--world` apunta al directorio de la dimensión de destino: usa la raíz del mundo para la superficie (Overworld), `../world/DIM-1` para el Nether, `../world/DIM1` para el End, o el directorio `dimensions/<namespace>/<path>` correspondiente para una dimensión personalizada. `--chunk` recibe coordenadas de chunk enteras con signo, no coordenadas de bloque. `--scope` usa `all` por defecto, o puede establecerse en `blocks` (solo terreno, entidades de bloque y POI) o `entities` (solo entidades). La CLI pide confirmación antes de continuar; añade `--yes` para omitir la confirmación.

Por defecto, la CLI restaura el terreno del chunk de destino, las entidades de bloque, los datos de entidades recuperables y los puntos de interés (POI) de ese chunk tal como se registraron en la instantánea, incluido el estado de ocupación de tickets. No restaura datos de jugadores ni la configuración general del mundo.

</details>

### Restaurar un mundo completo

Por motivos de rendimiento, la restauración completa del mundo dentro del juego no está disponible por ahora, pero puedes restaurar todo el mundo mediante la TUI o la CLI.

> Antes de ejecutar la TUI o la CLI, primero debes exportar las bibliotecas de ejecución incluidas en el JAR (este diseño evita conflictos de dependencias entre el mod y la CLI) y luego ejecutar la TUI o la CLI (puedes omitir este paso si utilizas el script de inicio rápido de la TUI).
>
> ```bash
> # Servidor dedicado: foldbackups/
> java -jar ../mods/foldbackup-<version>.jar extract
>
> # Un jugador: foldbackups/<nombre-de-carpeta-del-mundo>
> java -jar ../../mods/foldbackup-<version>.jar extract
> ```
>
> Los parámetros predeterminados de los comandos asumen que el directorio actual es el directorio del repositorio del mundo de destino (`foldbackups/` en un servidor dedicado y `foldbackups/<nombre-de-carpeta-del-mundo>` en un jugador). Si los ejecutas desde otra ubicación, ajusta los parámetros correspondientes.

```bash
# Servidor dedicado: foldbackups/
java -jar ../mods/foldbackup-<version>.jar tui

# Un jugador: foldbackups/<nombre-de-carpeta-del-mundo>
java -jar ../../mods/foldbackup-<version>.jar tui
```

En el menú, selecciona "2. Export world files" y sigue las indicaciones para elegir una instantánea, exportar como directorio o ZIP y la ruta de salida. Pulsa Intro para aceptar la ruta por defecto (`world-export/<snapshot-id>`, o el mismo nombre con `.zip` para un ZIP).

> [!IMPORTANT]
> **Detén primero el servidor** antes de exportar sobre el directorio de un mundo activo para restaurarlo.

Al exportar a un directorio, el destino puede ser una partida guardada existente: FoldBackup actualiza solo los archivos que difieren de la instantánea y omite los archivos idénticos. Antes de exportar se realiza una comprobación; tras la comprobación, elige "1 = overwrite" para conservar los archivos del destino que no estén en la instantánea, o "2 = delete extra" para eliminarlos y dejar el destino exactamente igual que la instantánea. En la solicitud del número de hilos puedes pulsar Intro para usar el valor predeterminado.

La CLI puede realizar la misma exportación: `snapshot export <snapshot-id> --output <directorio>`. `--zip` genera un archivo ZIP; al exportar a un directorio, `--delete-extra` elimina el contenido que no está en la instantánea; `--dry-run` muestra las estadísticas sin escribir ni eliminar nada.

### Archivos completos y diferenciales

Los archivos comprimidos (Archives) están pensados para almacenar y distribuir mundos priorizando una alta tasa de compresión. Dado que su creación consume bastantes recursos, las operaciones con archivos están disponibles únicamente a través de la CLI y la TUI.

Los archivos se crean a partir de instantáneas, por lo que necesitas tener una instantánea antes de crear un archivo. Puedes crearla dentro del juego o mediante la TUI o la CLI. En la TUI, selecciona "5. Create archive", elige una instantánea y el tipo de archivo; un archivo diferencial requiere elegir una instantánea anterior como base de comparación para guardar únicamente lo que haya cambiado.

Un archivo puede restaurarse como un mundo de Minecraft ("7. Restore as world files" en la TUI) o importarse como una instantánea en un repositorio de instantáneas. Al restaurar como mundo, si ya existe una partida guardada en la ubicación de destino, también se actualizará sobre esa partida existente, y al elegir "delete extra" se eliminarán los archivos que no están en el archivo comprimido. Si distribuyes un mundo mediante archivos, puedes empaquetar juntos el mod y el script de inicio de la TUI (el contenido del script se muestra más arriba).

El formato de archivo es independiente de la versión de Minecraft. Puedes usar la CLI o la TUI para comprimir mundos de versiones que el mod no admite.

## Comandos habituales dentro del juego

| Comando | Propósito |
| --- | --- |
| `/foldbackup backup` | Crear una instantánea manualmente |
| `/foldbackup backup --force` | Crear una copia de seguridad sin esperar el intervalo mínimo |
| `/foldbackup list [página]` | Listar las instantáneas (10 por página) con resumen de espacio del repositorio (único, compartido y recuperable acumulado) |
| `/foldbackup status` | Mostrar el estado de las tareas |
| `/foldbackup info <snapshot-id>` | Mostrar los detalles de una instantánea y su distribución actual de espacio único/compartido |
| `/foldbackup delete [--dry-run] <id1> [id2 ...]` | Eliminar una o varias instantáneas, o estimar el espacio recuperable con `--dry-run` |
| `/foldbackup pin <snapshot-id>` | Fijar una instantánea para protegerla de la limpieza automática y la eliminación manual |
| `/foldbackup unpin <snapshot-id>` | Quitar la protección de fijado de una instantánea |
| `/foldbackup preview from <snapshot-id> [at <x> <z>]` | Previsualizar un chunk histórico en la dimensión actual (por defecto, el chunk actual) |
| `/foldbackup preview exit` | Salir de la vista previa |
| `/foldbackup rollback chunk from <snapshot-id> [at <x> <z>]` | Restaurar un único chunk en la dimensión actual (por defecto, el chunk actual) |
| `/foldbackup rollback chunk from <snapshot-id> in <dimensión> at <x> <z>` | Restaurar un único chunk en la dimensión especificada |
| `/foldbackup rollback chunk blocks from <snapshot-id> [in <dimensión> at <x> <z> \| at <x> <z>]` | Restaurar solo bloques, entidades de bloque y POI de un chunk (conservando las entidades actuales) |
| `/foldbackup rollback chunk entities from <snapshot-id> [in <dimensión> at <x> <z> \| at <x> <z>]` | Restaurar solo entidades de un chunk (sin modificar bloques ni entidades de bloque) |

<details>
<summary>Haz clic para expandir la guía y el ejemplo de la lista de instantáneas y estadísticas de espacio (Mod / TUI / CLI)</summary>

La lista de instantáneas se muestra **de la más reciente a la más antigua**. En español, el mod muestra encabezados en español, mientras que la TUI (`1. List snapshots`) y la CLI (`snapshot list`) comparten la misma salida en inglés, con una correspondencia directa entre los datos.

| Mod (español) | TUI / CLI (inglés) | Significado |
| --- | --- | --- |
| `Ocupado` | `Stored` | Uso real en disco del repositorio (bloques de datos deduplicados + archivo de índice `index.db`) |
| `Único` / `Compartido (Global)` | `Unique` / `Shared [All]` | Espacio referenciado por una sola instantánea / compartido entre varias; `Global` (`All`) son los datos base referenciados por todas las instantáneas |
| `Fijadas (Único)` | `Pinned (Unique)` | Número de instantáneas fijadas y espacio ocupado exclusivamente por el conjunto de instantáneas fijadas |
| — | `Logical (saved %)` | Suma de tamaños comprimidos sin deduplicación entre instantáneas y porcentaje de ahorro por deduplicación |
| `Tamaño` | `Size` | Tamaño comprimido de la propia instantánea (no equivale al espacio liberado al eliminarla) |
| `Único` (en la fila) | `Unique` (columna) | Espacio que se libera de inmediato al eliminar solo esa instantánea |
| `Recuperable` / `[PIN]` | `Reclaim` / `-` | Espacio total liberado al eliminar de forma continua desde la instantánea no fijada más antigua hasta la fila actual en el orden de limpieza automática (omitidas las `[PIN]`) |

**Ejemplo de `1. List snapshots` en la TUI (igual que `snapshot list` en la CLI):**

```text
Snapshots (5):
Stored: 601.0M (Unique: 350.0M + Shared: 250.0M [All: 50.0M], index: 1.0M) | Logical: 1000.0M (saved 40.0%) | Pinned: 1 (Unique: 80.0M)

#  ID                       Date                 Regions  Files    Size  Unique  Reclaim
----------------------------------------------------------------------------------------
1  S-20260901_120000        2026-09-01 12:00:00      145     88  230.0M  120.0M   410.0M
2  S-20260901_113000        2026-09-01 11:30:00      135     85  210.0M   40.0M   230.0M
3  S-20260901_110000 [PIN]  2026-09-01 11:00:00      125     82  190.0M   80.0M        -
4  S-20260901_103000        2026-09-01 10:30:00      125     82  190.0M   60.0M   190.0M
5  S-20260901_100000        2026-09-01 10:00:00      120     80  180.0M   50.0M    50.0M
```

- **Interacción y paginación**: El mod muestra 10 entradas por página; al pasar el cursor sobre una fila en el chat se muestran la fecha, el número de regiones y el número de archivos adicionales, al hacer clic se ejecuta `/foldbackup info <snapshot-id>` y, si hay varias páginas, aparece abajo una barra de paginación clicable. La TUI muestra 15 entradas por página e indica abajo las teclas de navegación cuando hay más de 1 página (las pantallas de selección para exportar, eliminar, fijar y crear archivos usan la tabla compacta `# / ID / Date / Size / Unique`).
- **Estimación de eliminación por lotes**: Cuando necesites eliminar cualquier combinación de instantáneas, puedes usar `/foldbackup delete --dry-run`, `snapshot delete --dry-run` en la CLI o la pantalla de confirmación de eliminación de la TUI para ver el espacio exacto que se liberará.

</details>

## Opciones clave de configuración

El archivo de configuración se encuentra por defecto en `config/foldbackup-server.toml` dentro del directorio de trabajo. Los nombres de la tabla usan el formato `grupo.clave`.

| Opción | Valor por defecto | Descripción |
| --- | --- | --- |
| `general.enabled` | `true` | Activar las copias de seguridad automáticas |
| `general.backupPath` | `./foldbackups` | Raíz de copias relativa al directorio de trabajo del servidor; también admite rutas absolutas. Un servidor dedicado la usa como repositorio; en un jugador se crea un repositorio por cada carpeta de mundo dentro de ella |
| `general.compressionLevel` | `6` | Nivel de compresión Zstd, de 1 a 22 |
| `scheduling.intervalMinutes` | `30` | Tiempo acumulado con jugadores conectados entre copias automáticas, en minutos |
| `scheduling.minIntervalMinutes` | `10` | Tiempo real mínimo entre copias de seguridad, en minutos |
| `scheduling.savePauseStallSeconds` | `300` | Cancelar la copia y restaurar el guardado automático del mundo cuando el análisis del mundo no avance durante este tiempo; 0 desactiva la comprobación |
| `scheduling.backupOnStart` | `false` | Realizar copia de seguridad al iniciar |
| `scheduling.backupOnStop` | `false` | Realizar copia de seguridad al detener |
| `retention.enabled` | `true` | Activar la limpieza automática |
| `retention.maxSizeGB` | `50` | Tamaño objetivo del repositorio tras la limpieza, en GiB; 0 significa sin límite |
| `retention.minFreeSpaceGB` | `5` | Espacio libre objetivo en el sistema de archivos, en GiB; 0 desactiva este límite |
| `retention.maxAgeDays` | `0` | Antigüedad máxima de retención de instantáneas, en días; 0 significa sin límite |
| `performance.regionThreads` | `0` | Número de hilos de trabajo; 0 lo selecciona automáticamente |
| `performance.regionMemoryLimitMB` | `0` | Presupuesto de memoria para tareas en flujo; 0 lo selecciona automáticamente |
| `logging.progressLogIntervalSeconds` | `3` | Segundos entre mensajes de registro de progreso (1 es el más detallado, 300 el más breve) |
| `localization.defaultLanguage` | `en_us` | Idioma utilizado cuando la detección de idioma del jugador está desactivada o el idioma indicado no está soportado; puede ser `zh_cn`, `en_us`, `es_es`, `pt_br`, `ru_ru` |
| `localization.autoDetectPlayerLanguage` | `true` | Usar el idioma configurado por cada jugador cuando esté soportado |

> [!WARNING]
> **La limpieza automática puede eliminar todas las instantáneas no fijadas.** Los límites predeterminados solo restringen el tamaño del repositorio (50 GiB) y el espacio libre (5 GiB), por lo que la limpieza automática puede eliminar instantáneas recientes para cumplir esos objetivos.
>
> Usa `/foldbackup pin <snapshot-id>` (o la CLI/TUI) para proteger instantáneas importantes, establece los tres límites de limpieza en 0 para desactivar la eliminación automática, o desactiva la limpieza automática con `retention.enabled`.

`general.excludePatterns` compara rutas relativas al directorio del mundo usando `/` como separador de rutas y sin `/` inicial. Por defecto excluye `**/session.lock`, `**/backups`, `**/.backups`, `**/*.wip` y `**/DistantHorizons.sqlite`. Si un directorio coincide, se excluye todo su subárbol, y una barra final `/` (como `backups/`) coincide solo con directorios. Para las opciones de filtrado al crear instantáneas sin conexión con la CLI / TUI, consulta «Crear una copia de seguridad» más arriba.

## Licencia y agradecimientos

Los metadatos del proyecto FoldBackup declaran la licencia LGPL v3.

LRH se basa en el formato de región Linear. El proyecto original, [LinearRegionFileFormatTools](https://github.com/xymb-endcrystalme/LinearRegionFileFormatTools), se distribuye bajo la licencia BSD-2-Clause. Gracias a su autor original y a sus colaboradores.
