# FoldBackup

[English](../../README.md) | [简体中文](README.zh.md) | [Español](README.es.md) | Português (Brasil) | [Русский](README.ru.md)

FoldBackup é uma ferramenta de backup de mundos para servidores Minecraft NeoForge. Ela oferece suporte a backups por snapshots automáticos e manuais, compactação em arquivos (archives), prévias de chunks históricos e restauração (rollback) de chunks.

Os snapshots usam um esquema de deduplicação de conteúdo semelhante ao Git: cada backup registra apenas arquivos novos e alterados. Os arquivos de região são armazenados no formato LRH. Em um servidor de sobrevivência Minecraft 1.21.1 NeoForge com mais de 80 mods, um arquivo compactado criado no nível de compactação 19 pode ter apenas um terço do tamanho original do mundo.

O mesmo JAR pode ser instalado como um mod NeoForge ou executado como uma ferramenta independente. Ele fornece uma interface de linha de comando (CLI) e um menu interativo (TUI), permitindo usar seus recursos enquanto o servidor está offline.

- **Ambiente**: Minecraft `1.21.1`, NeoForge `21.1+`, Java `21+` (os recursos oferecidos pelo modo CLI / TUI independente não estão vinculados à versão do jogo e são compatíveis com a maioria das versões do Minecraft)
- **Instalação**: Necessária apenas no **servidor** (ou cliente singleplayer); jogadores que se conectam ao servidor **não** precisam instalar este mod em seus clientes para usar todos os recursos (incluindo prévias de chunks históricos dentro do jogo)

## Recursos

- **Backups online**: Agende backups com base no tempo online dos jogadores, acione-os manualmente ou faça backup do mundo opcionalmente quando o servidor iniciar ou parar.
- **Backups offline**: Faça backup de mundos usando a TUI ou a CLI sem iniciar o jogo.
- **Deduplicação de conteúdo**: Conteúdos inalterados não ocupam espaço adicional em um novo snapshot.
- **Gerenciamento de snapshots**: Consulte o resumo de espaço do repositório e o espaço único, compartilhado e recuperável acumulado de cada snapshot, fixe snapshots importantes (`pin`) para protegê-los da limpeza automática, estime o espaço a liberar antes da exclusão em lote e configure a limpeza automática.
- **Prévias e restauração de chunks**: Visualize como um chunk era em um snapshot anterior e restaure um único chunk de um snapshot selecionado para o mundo; também é possível restaurar chunks diretamente no salvamento usando a CLI sem iniciar o jogo, ajudando a resolver chunks problemáticos ou travamentos causados por entidades demais.
- **Restauração completa rápida**: Ao exportar um snapshot como um mundo de Minecraft, se já existir um salvamento na localização de destino, as partes inalteradas são ignoradas para acelerar o processo de restauração.
- **Compactação em arquivos (Archives)**: Crie arquivos completos ou diferenciais de alta compactação para distribuição ou armazenamento, que podem ser restaurados como um mundo de Minecraft ou importados para um repositório de snapshots; em servidores, os arquivos diferenciais permitem transferir apenas as partes alteradas do mundo para editar o mapa localmente.

> [!NOTE]
> **Diferença entre Snapshots e Arquivos (Archives)**:
>
> - **Snapshots**: Backups frequentes (automáticos ou manuais) armazenados com deduplicação incremental em um repositório local, usados para restauração rápida, exportação de mundo e recuperação de chunks individuais.
> - **Arquivos (Archives)**: Arquivos independentes otimizados para altas taxas de compactação (com suporte a arquivos completos e diferenciais), ideais para backups frios externos, sincronização entre máquinas ou distribuição de mundos.

### LRH e Linear

**LRH é uma implementação em streaming baseada no formato de região Linear** e é usada para compactar e armazenar dados de regiões no FoldBackup. O projeto original é [xymb-endcrystalme/LinearRegionFileFormatTools](https://github.com/xymb-endcrystalme/LinearRegionFileFormatTools). Agradecemos aos autores pelo design e implementação do formato.

O FoldBackup também introduz o formato LRHD para registrar diferenças de chunks nos arquivos compactados, permitindo backups e armazenamento diferenciais em nível de chunk.

## Início Rápido

### Criar um Backup

1. Coloque o JAR do FoldBackup no diretório `mods/` do servidor NeoForge.
2. Inicie o servidor e aguarde o carregamento do mundo e a inicialização do FoldBackup.
3. No jogo, execute os seguintes comandos com nível de permissão 2 ou superior:

   ```text
   /foldbackup backup --force
   /foldbackup list
   ```

   No modo singleplayer, o anfitrião do mundo pode usar esses comandos mesmo com os comandos (cheats) desativados. Outros jogadores que entrarem via LAN não podem usá-los.

Por padrão, os backups são armazenados em `foldbackups/` no diretório de trabalho do servidor e podem ser alterados com `general.backupPath`. Um servidor dedicado usa esse diretório diretamente como repositório, enquanto o singleplayer usa `foldbackups/<nome-da-pasta-do-mundo>` para que cada mundo mantenha seu próprio repositório.

Os backups automáticos ficam ativados por padrão. O intervalo conta o tempo acumulado enquanto há jogadores online. Backups manuais também estão sujeitos ao intervalo mínimo entre backups; use `/foldbackup backup --force` para ignorá-lo.

Mesmo sem instalar o mod no jogo, você pode criar snapshots offline para salvamentos de qualquer versão do Minecraft usando a CLI ou a TUI independente:

<details>
<summary>Clique para expandir as instruções de backup offline com CLI / TUI</summary>

> Antes de executar a TUI ou a CLI, é necessário exportar primeiro as bibliotecas de execução embutidas no JAR (esse design evita conflitos de dependência entre o mod e a CLI) e depois executar a TUI ou a CLI (você pode ignorar esta etapa se usar o script de inicialização rápida da TUI).
>
> ```bash
> # Servidor dedicado: foldbackups/
> java -jar ../mods/foldbackup-<version>.jar extract
>
> # Singleplayer: foldbackups/<nome-da-pasta-do-mundo>
> java -jar ../../mods/foldbackup-<version>.jar extract
> ```
>
> Os parâmetros padrão dos comandos assumem que o diretório atual é o diretório do repositório do mundo de destino (`foldbackups/` em um servidor dedicado e `foldbackups/<nome-da-pasta-do-mundo>` no singleplayer). Se executá-los de outro local, ajuste os parâmetros correspondentes.

**Antes de fazer um backup offline, pare o servidor ou saia do mundo singleplayer.**

**Criar um snapshot com a CLI:**

```bash
# Criar um snapshot usando as regras de filtragem padrão
java -jar ../mods/foldbackup-<version>.jar snapshot create --world ../world

# Adicionar regras de exclusão extras (coloque padrões com * ou ** entre aspas no shell para evitar expansão antecipada)
java -jar ../mods/foldbackup-<version>.jar snapshot create --world ../world \
  --exclude "**/DistantHorizons.sqlite" \
  --exclude "cache/"
```

- `--world <diretório>`: Obrigatório; aponta para o diretório raiz do mundo salvo.
- `--exclude <glob>`: Opcional e repetível; adiciona exclusões além das regras de filtragem padrão.

**Criar um snapshot com a TUI:**

Execute `java -jar ../mods/foldbackup-<version>.jar tui` (ou o script de inicialização) no diretório do repositório, selecione **`6. Create snapshot from world`** e siga as instruções:

1. **Diretório do mundo**: Informe o caminho do diretório raiz do salvamento (pressione Enter para voltar ao menu principal).
2. **Regras de exclusão extras**: A tela lista primeiro as regras de proteção padrão; se quiser adicionar mais, digite várias regras separadas por vírgula em uma única linha (compatível com `{a,b}`, por exemplo `**/DistantHorizons.sqlite, cache/`). Pressione Enter para não adicionar nenhuma ou `0` para voltar; regras inválidas solicitarão nova entrada no mesmo local.
3. **Número de threads**: Digite um número inteiro positivo, pressione Enter para seleção automática ou `0` para voltar.

**Regras de filtragem de arquivos:**

- A criação de snapshots offline mantém sempre 4 regras padrão essenciais: `**/session.lock`, `**/backups`, `**/.backups` e `**/*.wip` (diferentemente da configuração padrão do mod, o modo offline não exclui `**/DistantHorizons.sqlite` por padrão; adicione-o manualmente como mostrado acima, se necessário).
- As regras correspondem a caminhos relativos dentro do diretório do mundo usando `/` como separador e **não podem começar com `/`** (por exemplo, escreva `backups/` em vez de `/backups/`); há suporte para `*`, `**`, `?`, `[...]` e `{a,b}`.
- Quando um diretório corresponde, toda a sua subárvore é ignorada; uma regra terminada em `/` (como `cache/`) corresponde apenas a diretórios, e não a arquivos com o mesmo nome. Regras vazias, `/` inicial ou sintaxe glob inválida abortam imediatamente com erro.

</details>

### Iniciar a TUI Rapidamente

Se preferir um inicializador, salve um dos scripts abaixo no diretório do repositório como `foldbackup-tui.bat` ou `foldbackup-tui.sh` (e execute `chmod +x` nele no Linux). O script procura o JAR ao lado dele mesmo, depois em `../mods` e em `../../mods`, e exporta automaticamente as bibliotecas de execução.

<details>
<summary>Clique para expandir o script de inicialização do Windows (<code>foldbackup-tui.bat</code>)</summary>

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
<summary>Clique para expandir o script de inicialização do Linux / macOS (<code>foldbackup-tui.sh</code>)</summary>

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

### Restaurar um Chunk

Você pode visualizar a prévia e restaurar um único chunk dentro do jogo, ou usar a CLI para restaurar um chunk diretamente em um mundo offline.

No jogo, use `/foldbackup preview from <snapshot-id>` (usa por padrão o chunk em que você está) ou `/foldbackup preview from <snapshot-id> at <x> <z>` para ver a prévia de um chunk de um snapshot. Isso altera a aparência do chunk de destino no cliente para mostrar seu estado no momento daquele snapshot, sem modificar o chunk real. `x` e `z` são coordenadas de chunk. Você pode encontrá-las na tela F3 ou dividir as coordenadas de bloco por 16 arredondando para baixo. Um efeito de partículas azuis marca o chunk em prévia. Use `/foldbackup preview exit` para encerrar a prévia.

Para restaurar um chunk no jogo, use `/foldbackup rollback chunk from <snapshot-id>` para restaurar o chunk em que você está. Para especificar coordenadas, adicione `at <x> <z>` ou `in <dimensão> at <x> <z>` (obrigatório ao executar pelo console do servidor ou RCON), e opcionalmente restrinja o escopo com `blocks` (apenas terreno, entidades de bloco e POI, preservando as entidades atuais) ou `entities` (apenas entidades, deixando terreno e entidades de bloco intactos): por exemplo, `/foldbackup rollback chunk blocks from <snapshot-id> at <x> <z>` ou `/foldbackup rollback chunk entities from <snapshot-id> in <dimensão> at <x> <z>`. O chunk de destino já deve estar carregado na memória. Para um chunk descarregado, use o método offline pela CLI abaixo:

<details>
<summary>Clique para expandir as instruções de restauração offline com CLI</summary>

> Antes de executar a TUI ou a CLI, é necessário exportar primeiro as bibliotecas de execução embutidas no JAR (esse design evita conflitos de dependência entre o mod e a CLI) e depois executar a TUI ou a CLI (você pode ignorar esta etapa se usar o script de inicialização rápida da TUI).
>
> ```bash
> # Servidor dedicado: foldbackups/
> java -jar ../mods/foldbackup-<version>.jar extract
>
> # Singleplayer: foldbackups/<nome-da-pasta-do-mundo>
> java -jar ../../mods/foldbackup-<version>.jar extract
> ```
>
> Os parâmetros padrão dos comandos assumem que o diretório atual é o diretório do repositório do mundo de destino (`foldbackups/` em um servidor dedicado e `foldbackups/<nome-da-pasta-do-mundo>` no singleplayer). Se executá-los de outro local, ajuste os parâmetros correspondentes.

**Pare o servidor antes de realizar uma restauração offline.**

Liste os snapshots:

```bash
java -jar ../mods/foldbackup-<version>.jar snapshot list
```

Copie o ID do snapshot desejado na lista e restaure o chunk. O comando a seguir restaura o chunk `[x, z]` no diretório da superfície (`world`) para o estado daquele snapshot:

```bash
java -jar ../mods/foldbackup-<version>.jar snapshot rollback <snapshot-id> --world ../world --chunk <chunk-x> <chunk-z> [--scope <all|blocks|entities>]
```

`--world` aponta para o diretório da dimensão de destino: use a raiz do mundo para a superfície (Overworld), `../world/DIM-1` para o Nether, `../world/DIM1` para o End, ou o diretório `dimensions/<namespace>/<path>` correspondente para uma dimensão personalizada. `--chunk` recebe coordenadas de chunk inteiras com sinal, e não coordenadas de bloco. `--scope` usa `all` por padrão, ou pode ser definido como `blocks` (apenas terreno, entidades de bloco e POI) ou `entities` (apenas entidades). A CLI pede confirmação antes de prosseguir; adicione `--yes` para pular a confirmação.

Por padrão, a CLI restaura o terreno do chunk de destino, entidades de bloco, dados de entidades recuperáveis e os pontos de interesse (POI) daquele chunk exatamente como registrados no snapshot, incluindo a ocupação de tickets. Ela não restaura dados de jogadores nem configurações gerais do mundo.

</details>

### Restaurar um Mundo Inteiro

Por motivos de desempenho, a restauração completa do mundo dentro do jogo ainda não é suportada, mas você pode restaurar todo o salvamento usando a TUI ou a CLI.

> Antes de executar a TUI ou a CLI, é necessário exportar primeiro as bibliotecas de execução embutidas no JAR (esse design evita conflitos de dependência entre o mod e a CLI) e depois executar a TUI ou a CLI (você pode ignorar esta etapa se usar o script de inicialização rápida da TUI).
>
> ```bash
> # Servidor dedicado: foldbackups/
> java -jar ../mods/foldbackup-<version>.jar extract
>
> # Singleplayer: foldbackups/<nome-da-pasta-do-mundo>
> java -jar ../../mods/foldbackup-<version>.jar extract
> ```
>
> Os parâmetros padrão dos comandos assumem que o diretório atual é o diretório do repositório do mundo de destino (`foldbackups/` em um servidor dedicado e `foldbackups/<nome-da-pasta-do-mundo>` no singleplayer). Se executá-los de outro local, ajuste os parâmetros correspondentes.

```bash
# Servidor dedicado: foldbackups/
java -jar ../mods/foldbackup-<version>.jar tui

# Singleplayer: foldbackups/<nome-da-pasta-do-mundo>
java -jar ../../mods/foldbackup-<version>.jar tui
```

No menu, selecione "2. Export world files" e siga as instruções para escolher um snapshot, exportar como diretório ou ZIP e definir o caminho de saída. Pressione Enter para aceitar o caminho padrão (`world-export/<snapshot-id>`, ou o mesmo nome com `.zip` para um arquivo ZIP).

> [!IMPORTANT]
> **Pare o servidor primeiro** antes de exportar para o diretório de um mundo ativo para restaurá-lo.

Ao exportar para um diretório, o destino pode ser um mundo salvo existente: o FoldBackup atualiza apenas os arquivos diferentes do snapshot e ignora os arquivos idênticos. Antes de exportar é feita uma verificação; após a verificação, escolha "1 = overwrite" para manter no destino os arquivos que o snapshot não contém, ou "2 = delete extra" para removê-los e deixar o destino exatamente igual ao snapshot. No prompt de número de threads, basta pressionar Enter para aceitar o padrão.

A CLI pode fazer a mesma exportação: `snapshot export <snapshot-id> --output <diretório>`. `--zip` grava um ZIP; para exportação em diretório, `--delete-extra` remove o conteúdo que não está no snapshot; `--dry-run` exibe as estatísticas sem gravar nem excluir nada.

### Arquivos Completos e Diferenciais

Os arquivos compactados (Archives) são voltados para o armazenamento e a distribuição de mundos, priorizando altas taxas de compactação. Como sua criação consome muitos recursos, as operações de arquivo estão disponíveis apenas pela CLI e pela TUI.

Os arquivos são criados a partir de snapshots, portanto você precisa de um snapshot antes de criar um arquivo. Você pode criar um dentro do jogo ou pela TUI ou CLI. Na TUI, selecione "5. Create archive", escolha um snapshot e o tipo de arquivo; um arquivo diferencial exige escolher um snapshot anterior como base de comparação, salvando apenas o que mudou.

Um arquivo pode ser restaurado como um mundo de Minecraft ("7. Restore as world files" na TUI) ou importado como um snapshot para um repositório de snapshots. Ao restaurar como mundo, se já existir um salvamento no local de destino, a atualização também será feita com base nesse salvamento, e escolher "delete extra" remove arquivos que não estão no arquivo compactado. Se você distribuir um mundo usando arquivos, poderá empacotar o mod e o script de inicialização da TUI juntos (o conteúdo do script é mostrado acima).

O formato de arquivo é independente da versão do Minecraft. Você pode usar a CLI ou a TUI para compactar mundos de versões às quais o mod não oferece suporte.

## Comandos Comuns no Jogo

| Comando | Finalidade |
| --- | --- |
| `/foldbackup backup` | Criar um snapshot manualmente |
| `/foldbackup backup --force` | Criar um backup sem aguardar o intervalo mínimo |
| `/foldbackup list [página]` | Listar snapshots (10 por página) com resumo de espaço do repositório (único, compartilhado e recuperável acumulado) |
| `/foldbackup status` | Mostrar o status da tarefa |
| `/foldbackup info <snapshot-id>` | Mostrar detalhes de um snapshot e sua distribuição atual de espaço único/compartilhado |
| `/foldbackup delete [--dry-run] <id1> [id2 ...]` | Excluir um ou mais snapshots, ou estimar o espaço liberado com `--dry-run` |
| `/foldbackup pin <snapshot-id>` | Fixar um snapshot para protegê-lo da limpeza automática e da exclusão manual |
| `/foldbackup unpin <snapshot-id>` | Remover a proteção de fixação de um snapshot |
| `/foldbackup preview from <snapshot-id> [at <x> <z>]` | Visualizar a prévia de um chunk histórico na dimensão atual (usa o chunk atual por padrão) |
| `/foldbackup preview exit` | Encerrar a prévia |
| `/foldbackup rollback chunk from <snapshot-id> [at <x> <z>]` | Restaurar um único chunk na dimensão atual (usa o chunk atual por padrão) |
| `/foldbackup rollback chunk from <snapshot-id> in <dimensão> at <x> <z>` | Restaurar um único chunk em uma dimensão especificada |
| `/foldbackup rollback chunk blocks from <snapshot-id> [in <dimensão> at <x> <z> \| at <x> <z>]` | Restaurar apenas blocos, entidades de bloco e POI de um chunk (preservando as entidades atuais) |
| `/foldbackup rollback chunk entities from <snapshot-id> [in <dimensão> at <x> <z> \| at <x> <z>]` | Restaurar apenas entidades de um chunk (deixando blocos e entidades de bloco intactos) |

<details>
<summary>Clique para expandir o guia e o exemplo da lista de snapshots e estatísticas de espaço (Mod / TUI / CLI)</summary>

A lista de snapshots é exibida **do mais recente para o mais antigo**. Em português, o mod exibe cabeçalhos em português, enquanto a TUI (`1. List snapshots`) e a CLI (`snapshot list`) compartilham a mesma saída em inglês, com correspondência direta entre os dados.

| Mod (português) | TUI / CLI (inglês) | Significado |
| --- | --- | --- |
| `Ocupado` | `Stored` | Uso real em disco do repositório (blocos de dados deduplicados + arquivo de índice `index.db`) |
| `Único` / `Compartilhado (Global)` | `Unique` / `Shared [All]` | Espaço referenciado por um único snapshot / compartilhado entre vários snapshots; `Global` (`All`) representa os dados base referenciados por todos os snapshots |
| `Fixados (Único)` | `Pinned (Unique)` | Quantidade de snapshots fixados e espaço ocupado exclusivamente pelo conjunto de snapshots fixados |
| — | `Logical (saved %)` | Soma do tamanho compactado sem deduplicação entre snapshots e porcentagem de economia obtida pela deduplicação |
| `Tamanho` | `Size` | Tamanho compactado do próprio snapshot (não equivale ao espaço liberado ao excluí-lo) |
| `Único` (na linha) | `Unique` (coluna) | Espaço liberado imediatamente ao excluir apenas esse snapshot |
| `Recuperável` / `[PIN]` | `Reclaim` / `-` | Espaço total liberado ao excluir continuamente do snapshot não fixado mais antigo até a linha atual na ordem da limpeza automática (pulando `[PIN]`) |

**Exemplo de `1. List snapshots` na TUI (igual a `snapshot list` na CLI):**

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

- **Interação e paginação**: O mod exibe 10 itens por página; ao passar o cursor sobre a linha de um snapshot no chat são exibidos a data/hora, o número de regiões e o número de arquivos extras, ao clicar é executado `/foldbackup info <snapshot-id>` e, quando há várias páginas, uma barra de paginação clicável aparece na parte inferior. A TUI exibe 15 itens por página e mostra na parte inferior as teclas de paginação quando há mais de 1 página (as telas de seleção para exportar, excluir, fixar e criar arquivos usam a tabela compacta `# / ID / Date / Size / Unique`).
- **Estimativa de exclusão em lote**: Quando precisar excluir qualquer combinação de snapshots, use `/foldbackup delete --dry-run`, `snapshot delete --dry-run` na CLI ou a tela de confirmação de exclusão da TUI para ver exatamente quanto espaço será liberado.

</details>

## Principais Opções de Configuração

O arquivo de configuração fica localizado em `config/foldbackup-server.toml` no diretório de trabalho por padrão. Os nomes na tabela usam o formato `grupo.chave`.

| Opção | Padrão | Descrição |
| --- | --- | --- |
| `general.enabled` | `true` | Ativar backups automáticos |
| `general.backupPath` | `./foldbackups` | Raiz de backups relativa ao diretório de trabalho do servidor; caminhos absolutos também são suportados. Um servidor dedicado a utiliza como repositório; o singleplayer cria um repositório por pasta de mundo dentro dela |
| `general.compressionLevel` | `6` | Nível de compactação Zstd, de 1 a 22 |
| `scheduling.intervalMinutes` | `30` | Tempo acumulado com jogadores online entre backups automáticos, em minutos |
| `scheduling.minIntervalMinutes` | `10` | Tempo mínimo de relógio entre backups, em minutos |
| `scheduling.savePauseStallSeconds` | `300` | Cancelar o backup e restaurar o salvamento automático do mundo quando a verificação do mundo não avançar durante esse tempo; 0 desativa a verificação |
| `scheduling.backupOnStart` | `false` | Fazer backup ao iniciar |
| `scheduling.backupOnStop` | `false` | Fazer backup ao desligar |
| `retention.enabled` | `true` | Ativar limpeza automática |
| `retention.maxSizeGB` | `50` | Tamanho alvo do repositório após a limpeza, em GiB; 0 significa ilimitado |
| `retention.minFreeSpaceGB` | `5` | Espaço livre alvo no sistema de arquivos, em GiB; 0 desativa este limite |
| `retention.maxAgeDays` | `0` | Tempo máximo de retenção de snapshots, em dias; 0 significa ilimitado |
| `performance.regionThreads` | `0` | Número de threads de trabalho; 0 seleciona automaticamente |
| `performance.regionMemoryLimitMB` | `0` | Limite de memória para tarefas em streaming; 0 seleciona automaticamente |
| `logging.progressLogIntervalSeconds` | `3` | Segundos entre mensagens de log de progresso (1 é o mais detalhado, 300 é o mais resumido) |
| `localization.defaultLanguage` | `en_us` | Idioma usado quando a detecção de idioma do jogador está desativada ou o idioma informado não é suportado; um entre `zh_cn`, `en_us`, `es_es`, `pt_br`, `ru_ru` |
| `localization.autoDetectPlayerLanguage` | `true` | Usar o idioma informado por cada jogador quando for suportado |

> [!WARNING]
> **A limpeza automática pode excluir todos os snapshots não fixados.** Os limites padrão restringem apenas o tamanho do repositório (50 GiB) e o espaço livre (5 GiB), portanto a limpeza automática pode excluir snapshots recentes para atingir essas metas.
>
> Use `/foldbackup pin <snapshot-id>` (ou pela CLI/TUI) para proteger snapshots importantes, defina os três limites de limpeza como 0 para desativar a exclusão automática ou desative a limpeza automática com `retention.enabled`.

`general.excludePatterns` corresponde a caminhos relativos ao diretório do mundo, usando `/` como separador de caminho e sem `/` inicial. Por padrão, exclui `**/session.lock`, `**/backups`, `**/.backups`, `**/*.wip` e `**/DistantHorizons.sqlite`. Se um diretório corresponder, toda a sua subárvore será excluída, e uma `/` final (como `backups/`) corresponde apenas a diretórios. Para as opções de filtragem ao criar snapshots offline via CLI / TUI, consulte "Criar um Backup" acima.

## Licença e Agradecimentos

Os metadados do projeto FoldBackup declaram a licença LGPL v3.

O LRH é baseado no formato de região Linear. O projeto original, [LinearRegionFileFormatTools](https://github.com/xymb-endcrystalme/LinearRegionFileFormatTools), é licenciado sob a BSD-2-Clause. Agradecemos ao autor original e aos colaboradores.
