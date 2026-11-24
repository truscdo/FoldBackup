# FoldBackup

[English](../../README.md) | [简体中文](README.zh.md) | Español | [Português (Brasil)](README.pt-br.md) | [Русский](README.ru.md)

FoldBackup es una herramienta de copias de seguridad de mundos para servidores de Minecraft con NeoForge. Admite copias de seguridad por instantáneas automáticas y manuales, compresión en archivos de respaldo, vistas previas de chunks históricos y restauración de chunks.

Las instantáneas utilizan un esquema de deduplicación de contenido similar a Git: cada copia de seguridad registra únicamente los archivos nuevos y modificados. Los archivos de región se almacenan en formato LRH. En un servidor de supervivencia de Minecraft 1.21.1 con NeoForge y más de 80 mods, un archivo comprimido creado con el nivel de compresión 19 puede ocupar tan solo un tercio del tamaño original del mundo.

El mismo archivo JAR puede instalarse como un mod de NeoForge o ejecutarse como una herramienta independiente. Ofrece una interfaz de línea de comandos (CLI) y un menú interactivo (TUI) para poder usar sus funciones mientras el servidor está apagado.

- **Entorno**: Minecraft `1.21.1`, NeoForge `21.1+`, Java `21+` (el modo independiente CLI / TUI no depende de la versión de Minecraft)
- **Instalación**: Solo se requiere en el **servidor** (o en el cliente para un jugador); los jugadores que se conecten al servidor **no** necesitan instalar este mod en sus clientes para usar todas las funciones (incluidas las vistas previas de chunks históricos dentro del juego)

## Características

- **Copias de seguridad en línea**: Programa copias de seguridad basadas en el tiempo con jugadores conectados, inícialas manualmente o realiza copias opcionales al iniciar o detener el servidor.
- **Deduplicación de contenido**: El contenido sin cambios no ocupa espacio adicional en una nueva instantánea.
- **Gestión de instantáneas**: Consulta el resumen de espacio del repositorio y el espacio único, compartido y recuperable acumulado de cada instantánea, fija instantáneas importantes (`pin`) para protegerlas de la limpieza automática, estima el espacio a liberar antes de eliminar una o varias instantáneas (`--dry-run`) y configura la limpieza automática.
- **Vista previa y restauración de chunks**: Previsualiza cómo se veía un chunk en una instantánea anterior y restaura un único chunk desde la instantánea seleccionada.

    La restauración de chunks está disponible tanto dentro del juego como mediante la CLI con el servidor apagado. La opción sin conexión ayuda a recuperar un chunk problemático, por ejemplo uno que provoque el cierre del juego por contener demasiadas entidades.
- **Exportación de mundos**: Exporta una instantánea como un mundo de Minecraft.
- **Compresión en archivos (Archives)**: Crea archivos completos o diferenciales y luego restáuralos como un mundo de Minecraft o impórtalos en un repositorio de instantáneas.

    Los archivos comprimidos están pensados para almacenar y distribuir mundos. Tras descargar un archivo completo una vez, los archivos diferenciales posteriores transfieren únicamente los datos modificados, lo que permite sincronizar el mundo de un servidor con un equipo local, editarlo allí y sincronizarlo de vuelta ahorrando ancho de banda.

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

De forma predeterminada, las copias de seguridad se guardan en `foldbackups/` dentro del directorio de trabajo del servidor. Puedes cambiarlo con `general.backupPath`, que define la raíz de las copias: un servidor dedicado la usa directamente como repositorio, mientras que en un jugador se utiliza `foldbackups/<nombre-de-carpeta-del-mundo>` para que cada mundo mantenga su propio repositorio. Antes de una copia de seguridad, FoldBackup fuerza el guardado del mundo y desactiva temporalmente el guardado automático. El guardado se restablece tan pronto como termina el análisis del mundo, por lo que confirmar la instantánea y limpiar datos no prolongan esa pausa. Si el análisis no avanza durante `scheduling.savePauseStallSeconds` (300 por defecto), la copia se cancela, el guardado automático se restaura, se avisa a los jugadores y el siguiente intento programado espera un intervalo completo.

Las copias de seguridad automáticas están activadas por defecto. El intervalo contabiliza el tiempo acumulado mientras hay jugadores conectados. Las copias manuales también están sujetas al intervalo mínimo entre copias; usa `/foldbackup backup --force` para omitirlo.

### Restaurar un mundo completo

Puedes restaurar un mundo completo mediante la TUI o la CLI.

Desde el directorio del repositorio del mundo (`foldbackups/` en un servidor dedicado, `foldbackups/<nombre-de-carpeta-del-mundo>` en un jugador), ejecuta los siguientes comandos para exportar las bibliotecas de ejecución incluidas (esto evita conflictos de dependencias entre el mod y la CLI) y luego abre el menú. Reemplaza `<version>` con el nombre real del archivo JAR:

```bash
# Servidor dedicado: foldbackups/
java -jar ../mods/foldbackup-<version>.jar extract
java -jar ../mods/foldbackup-<version>.jar tui

# Un jugador: foldbackups/<nombre-de-carpeta-del-mundo>
java -jar ../../mods/foldbackup-<version>.jar extract
java -jar ../../mods/foldbackup-<version>.jar tui
```

En el menú, selecciona "2. Export world files" y sigue las indicaciones para elegir una instantánea, exportar como directorio o ZIP y la ruta de salida. Pulsa Intro para aceptar la ruta por defecto (`world-export/<snapshot-id>`, o el mismo nombre con `.zip` para un ZIP).

> [!IMPORTANT]
> **Detén primero el servidor** antes de exportar sobre el directorio de un mundo activo para restaurarlo.

Al exportar a un directorio, el destino puede ser una partida guardada existente: FoldBackup actualiza solo el contenido que difiere de la instantánea y nunca reescribe archivos idénticos. Tras la comprobación, elige "1 = overwrite" para conservar los archivos del destino que no estén en la instantánea, o "2 = delete extra" para eliminarlos y dejar el destino exactamente igual que la instantánea. En la solicitud del número de hilos puedes pulsar Intro para usar el valor predeterminado.

La CLI puede realizar la misma exportación: `snapshot export <snapshot-id> --output <directorio>`. `--zip` genera un archivo ZIP; al exportar a un directorio, `--delete-extra` elimina el contenido que no está en la instantánea; `--dry-run` muestra las estadísticas sin escribir ni eliminar nada.

### Inicio rápido de la TUI

Si prefieres un lanzador, guarda uno de los siguientes scripts en el directorio del repositorio como `foldbackup-tui.bat` o `foldbackup-tui.sh` (y dale permisos con `chmod +x` en Linux). El script busca el JAR junto a sí mismo, luego en `../mods` y después en `../../mods`, por lo que las estructuras predeterminadas de servidor y de un jugador funcionan sin necesidad de editarlo.

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

Dentro del juego, usa `/foldbackup preview <x> <z> [snapshot-id]` para obtener una vista previa de un chunk desde una instantánea. Esto cambia la apariencia del chunk de destino en el cliente para mostrar su estado en el momento de esa instantánea, sin modificar el chunk real. `x` y `z` son coordenadas de chunk; puedes verlas en la pantalla F3 o dividir las coordenadas de bloque entre 16 redondeando hacia abajo. Un efecto de partículas azules marca el chunk previsualizado. Usa `/foldbackup preview exit` para salir de la vista previa.

Para restaurar un chunk dentro del juego, usa `/foldbackup rollback chunk <x> <z> [snapshot-id]`. Puedes especificar una dimensión con `/foldbackup rollback chunk in <dimensión> <x> <z> [snapshot-id]`, o limitar el alcance con `blocks` (solo terreno, entidades de bloque y POI, conservando las entidades actuales) o `entities` (solo entidades, sin tocar el terreno ni las entidades de bloque): por ejemplo, `/foldbackup rollback chunk blocks <x> <z> [snapshot-id]` o `/foldbackup rollback chunk entities in <dimensión> <x> <z> [snapshot-id]`. El chunk de destino debe estar cargado en memoria. Para un chunk no cargado, utiliza el método sin conexión mediante la CLI que se muestra a continuación. Si omites el ID de la instantánea, se utiliza la más reciente.

Para los siguientes ejemplos con la CLI, se asume que el directorio actual es `foldbackups` y que el directorio del mundo del servidor está al mismo nivel y se llama `world`. Si ejecutas los comandos desde otro lugar, ajusta las rutas del JAR, del mundo y de las instantáneas según corresponda. También puedes indicar el directorio de instantáneas con `--snapshot-root <directorio>`. En modo de un jugador, el repositorio es `foldbackups/<nombre-de-carpeta-del-mundo>`, por lo que debes apuntar `--snapshot-root` a ese directorio. **Detén el servidor antes de realizar una restauración sin conexión.**

Listar las instantáneas:

```bash
java -jar ../mods/foldbackup-<version>.jar snapshot list
```

Copia el ID de la instantánea de destino de la lista y restaura el chunk. El siguiente comando restaura el chunk `[x, z]` en el directorio de la superficie (`world`) a su estado en dicha instantánea:

```bash
java -jar ../mods/foldbackup-<version>.jar snapshot rollback <snapshot-id> --world ../world --chunk <chunk-x> <chunk-z> [--scope <all|blocks|entities>]
```

`--world` apunta al directorio de la dimensión de destino: usa la raíz del mundo para la superficie (Overworld), `../world/DIM-1` para el Nether, `../world/DIM1` para el End, o el directorio `dimensions/<namespace>/<path>` correspondiente para una dimensión personalizada. `--chunk` recibe coordenadas de chunk enteras con signo, no coordenadas de bloque. Por ejemplo, la coordenada de bloque `-1` pertenece a la coordenada de chunk `-1`. `--scope` usa `all` por defecto, o puede establecerse en `blocks` (solo terreno, entidades de bloque y POI) o `entities` (solo entidades). La CLI pide confirmación antes de continuar; añade `--yes` para omitir la confirmación.

Por defecto (`--scope all`), la CLI restaura el terreno del chunk de destino, las entidades de bloque, los datos de entidades recuperables y los puntos de interés (POI) de ese chunk tal como se registraron en la instantánea, incluido el estado de ocupación de tickets. No restaura datos de jugadores ni la configuración general del mundo.

### Archivos completos y diferenciales

Los archivos comprimidos (Archives) están pensados para almacenar y distribuir mundos priorizando una alta tasa de compresión. Dado que su creación consume bastantes recursos, las operaciones con archivos están disponibles únicamente a través de la CLI y la TUI.

Los archivos se crean a partir de instantáneas, por lo que necesitas tener una instantánea antes de crear un archivo. Puedes crearla dentro del juego o mediante la TUI o la CLI. En la TUI, selecciona "5. Create archive", elige una instantánea y el tipo de archivo. Un archivo diferencial también te pedirá una instantánea anterior como base de comparación para almacenar únicamente lo que haya cambiado.

Un archivo puede restaurarse como un mundo de Minecraft ("7. Restore as world files" en la TUI) o importarse como una instantánea en un repositorio de instantáneas. Al restaurar como mundo también se actualiza una partida existente en el mismo sitio, y al elegir "delete extra" se eliminan los archivos que no están en el archivo comprimido. Si distribuyes un mundo mediante archivos, puedes empaquetar junto a ellos el mod y el script de inicio de la TUI (el contenido del script se muestra más arriba en este README).

El formato de archivo es independiente de la versión de Minecraft. Puedes usar la CLI o la TUI para comprimir mundos de versiones que el mod no admite.

## Comandos habituales dentro del juego

| Comando | Propósito |
| --- | --- |
| `/foldbackup backup` | Crear una instantánea manualmente |
| `/foldbackup backup --force` | Crear una copia de seguridad sin esperar el intervalo mínimo |
| `/foldbackup list` | Listar las instantáneas con resumen de espacio del repositorio (único, compartido y recuperable acumulado) |
| `/foldbackup status` | Mostrar el estado de las tareas |
| `/foldbackup info <snapshot-id>` | Mostrar los detalles de una instantánea y su distribución actual de espacio único/compartido |
| `/foldbackup delete [--dry-run] <id1> [id2 ...]` | Eliminar una o varias instantáneas, o estimar el espacio recuperable con `--dry-run` |
| `/foldbackup pin <snapshot-id>` | Fijar una instantánea para protegerla de la limpieza automática y la eliminación manual |
| `/foldbackup unpin <snapshot-id>` | Quitar la protección de fijado de una instantánea |
| `/foldbackup preview <x> <z> [snapshot-id]` | Previsualizar un chunk histórico en la dimensión actual |
| `/foldbackup preview exit` | Salir de la vista previa |
| `/foldbackup rollback chunk <x> <z> [snapshot-id]` | Restaurar un único chunk en la dimensión actual |
| `/foldbackup rollback chunk in <dimensión> <x> <z> [snapshot-id]` | Restaurar un único chunk en la dimensión especificada |
| `/foldbackup rollback chunk blocks [in <dimensión>] <x> <z> [snapshot-id]` | Restaurar solo bloques, entidades de bloque y POI de un chunk (conservando las entidades actuales) |
| `/foldbackup rollback chunk entities [in <dimensión>] <x> <z> [snapshot-id]` | Restaurar solo entidades de un chunk (sin modificar bloques ni entidades de bloque) |

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
> **La limpieza automática puede eliminar todas las instantáneas históricas no fijadas, incluidas las copias creadas manualmente.** Los límites predeterminados solo restringen el tamaño del repositorio (50 GiB) y el espacio libre (5 GiB); no hay límite de días de antigüedad, por lo que la limpieza puede eliminar instantáneas recientes para cumplir esos objetivos. El objetivo de espacio libre no significa que se haya reservado espacio para la siguiente copia. Usa `/foldbackup pin <snapshot-id>` (o la CLI/TUI) para proteger instantáneas importantes, establece los tres límites de limpieza en 0 para desactivar la eliminación automática, o desactiva la limpieza automática con `retention.enabled`.

`general.excludePatterns` compara rutas relativas al directorio del mundo usando `/` como separador de rutas. Por defecto excluye `**/session.lock`, `**/backups`, `**/.backups`, `**/*.wip` y `**/DistantHorizons.sqlite`. Si un directorio coincide, se excluye todo su subárbol.

## Licencia y agradecimientos

Los metadatos del proyecto FoldBackup declaran la licencia LGPL v3.

LRH se basa en el formato de región Linear. El proyecto original, [LinearRegionFileFormatTools](https://github.com/xymb-endcrystalme/LinearRegionFileFormatTools), se distribuye bajo la licencia BSD-2-Clause. Gracias a su autor original y a sus colaboradores.
