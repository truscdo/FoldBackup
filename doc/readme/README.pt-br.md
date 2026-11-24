# FoldBackup

[English](../../README.md) | [简体中文](README.zh.md) | [Español](README.es.md) | Português (Brasil) | [Русский](README.ru.md)

FoldBackup é uma ferramenta de backup de mundos para servidores Minecraft NeoForge. Ela oferece suporte a backups por snapshots automáticos e manuais, compactação em arquivos (archives), prévias de chunks históricos e restauração (rollback) de chunks.

Os snapshots usam um esquema de deduplicação de conteúdo semelhante ao Git: cada backup registra apenas arquivos novos e alterados. Os arquivos de região são armazenados no formato LRH. Em um servidor de sobrevivência Minecraft 1.21.1 NeoForge com mais de 80 mods, um arquivo compactado criado no nível de compactação 19 pode ter apenas um terço do tamanho original do mundo.

O mesmo JAR pode ser instalado como um mod NeoForge ou executado como uma ferramenta independente. Ele fornece uma interface de linha de comando (CLI) e um menu interativo (TUI), permitindo usar seus recursos enquanto o servidor está offline.

- **Ambiente**: Minecraft `1.21.1`, NeoForge `21.1+`, Java `21+` (o modo CLI / TUI independente não está vinculado a uma versão do Minecraft)
- **Instalação**: Necessária apenas no **servidor** (ou cliente singleplayer); jogadores que se conectam ao servidor **não** precisam instalar este mod em seus clientes para usar todos os recursos (incluindo prévias de chunks históricos dentro do jogo)

## Recursos

- **Backups online**: Agende backups com base no tempo online dos jogadores, acione-os manualmente ou faça backup do mundo opcionalmente quando o servidor iniciar ou parar.
- **Deduplicação de conteúdo**: Conteúdos inalterados não ocupam espaço adicional em um novo snapshot.
- **Gerenciamento de snapshots**: Consulte o resumo de espaço do repositório e o espaço único, compartilhado e recuperável acumulado de cada snapshot, fixe snapshots importantes (`pin`) para protegê-los da limpeza automática, estime o espaço a liberar antes de excluir um ou mais snapshots (`--dry-run`) e configure a limpeza automática.
- **Prévias e restauração de chunks**: Visualize como um chunk era em um snapshot anterior e restaure um único chunk a partir de um snapshot selecionado.

    A restauração de chunk está disponível tanto dentro do jogo quanto pela CLI enquanto o servidor está offline. A opção offline ajuda a recuperar um chunk problemático, inclusive um que trave o jogo por conter entidades demais.
- **Exportação de mundo**: Exporte um snapshot como um mundo de Minecraft.
- **Compactação em arquivos (Archives)**: Crie arquivos completos ou diferenciais e depois restaure um arquivo como um mundo de Minecraft ou importe-o para um repositório de snapshots.

    Os arquivos compactados destinam-se ao armazenamento e à distribuição de mundos. Após baixar um arquivo completo uma única vez, os arquivos diferenciais seguintes transferem apenas os dados alterados, permitindo sincronizar o mundo de um servidor com uma máquina local, editá-lo localmente e sincronizá-lo de volta. Isso economiza largura de banda.

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

Por padrão, os backups são armazenados em `foldbackups/` no diretório de trabalho do servidor. Você pode alterar isso com `general.backupPath`, que é a raiz dos backups: um servidor dedicado usa esse diretório diretamente como repositório, enquanto o singleplayer usa `foldbackups/<nome-da-pasta-do-mundo>` para que cada mundo mantenha seu próprio repositório. Antes de um backup, o FoldBackup força o salvamento do mundo e desativa temporariamente o salvamento automático. O salvamento automático é restaurado assim que a verificação do mundo termina, para que a gravação final do snapshot e a limpeza não mantenham o salvamento pausado. Se a verificação não avançar durante `scheduling.savePauseStallSeconds` (padrão: 300), o backup é cancelado, o salvamento é restaurado, os jogadores são avisados e a próxima tentativa agendada aguarda um intervalo completo.

Os backups automáticos ficam ativados por padrão. O intervalo conta o tempo acumulado enquanto há jogadores online. Backups manuais também estão sujeitos ao intervalo mínimo entre backups; use `/foldbackup backup --force` para ignorá-lo.

### Restaurar um Mundo Inteiro

Você pode restaurar um mundo inteiro pela TUI ou pela CLI.

A partir do diretório do repositório do mundo (`foldbackups/` em um servidor dedicado, `foldbackups/<nome-da-pasta-do-mundo>` no singleplayer), execute os comandos abaixo para exportar as bibliotecas de execução embutidas (isso evita conflitos de dependência entre o mod e a CLI) e depois abra o menu. Substitua `<version>` pelo nome real do arquivo JAR:

```bash
# Servidor dedicado: foldbackups/
java -jar ../mods/foldbackup-<version>.jar extract
java -jar ../mods/foldbackup-<version>.jar tui

# Singleplayer: foldbackups/<nome-da-pasta-do-mundo>
java -jar ../../mods/foldbackup-<version>.jar extract
java -jar ../../mods/foldbackup-<version>.jar tui
```

No menu, selecione "2. Export world files" e siga as instruções para escolher um snapshot, exportar como diretório ou ZIP e definir o caminho de saída. Pressione Enter para aceitar o caminho padrão (`world-export/<snapshot-id>`, ou o mesmo nome com `.zip` para um arquivo ZIP).

> [!IMPORTANT]
> **Pare o servidor primeiro** antes de exportar para o diretório de um mundo ativo para restaurá-lo.

Ao exportar para um diretório, o destino pode ser um mundo salvo existente: o FoldBackup atualiza apenas o conteúdo diferente do snapshot e nunca regrava arquivos idênticos. Após a verificação, escolha "1 = overwrite" para manter no destino os arquivos que o snapshot não contém, ou "2 = delete extra" para removê-los e deixar o destino exatamente igual ao snapshot. No prompt de número de threads, basta pressionar Enter para aceitar o padrão.

A CLI pode fazer a mesma exportação: `snapshot export <snapshot-id> --output <diretório>`. `--zip` grava um ZIP; para exportação em diretório, `--delete-extra` remove o conteúdo que não está no snapshot; `--dry-run` exibe as estatísticas sem gravar nem excluir nada.

### Iniciar a TUI Rapidamente

Se preferir um inicializador, salve um dos scripts abaixo no diretório do repositório como `foldbackup-tui.bat` ou `foldbackup-tui.sh` (e execute `chmod +x` nele no Linux). O script procura o JAR ao lado dele mesmo, depois em `../mods` e em `../../mods`, de modo que as estruturas padrão de servidor e singleplayer funcionem sem alterações.

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

No jogo, use `/foldbackup preview <x> <z> [snapshot-id]` para ver a prévia de um chunk de um snapshot. Isso altera a aparência do chunk de destino no cliente para mostrar seu estado no momento daquele snapshot, sem modificar o chunk real. `x` e `z` são coordenadas de chunk. Você pode encontrá-las na tela F3 ou dividir as coordenadas de bloco por 16 arredondando para baixo. Um efeito de partículas azuis marca o chunk em prévia. Use `/foldbackup preview exit` para encerrar a prévia.

Para restaurar um chunk no jogo, use `/foldbackup rollback chunk <x> <z> [snapshot-id]`. Você pode especificar uma dimensão com `/foldbackup rollback chunk in <dimensão> <x> <z> [snapshot-id]`. O chunk de destino já deve estar carregado na memória. Para um chunk descarregado, use o método offline pela CLI abaixo. Se você omitir o ID do snapshot, o snapshot mais recente será usado.

Para os exemplos de CLI abaixo, considere que o diretório atual é `foldbackups` e o diretório do mundo do servidor está no mesmo nível, chamado `world`. Se executar os comandos de outro local, ajuste os caminhos do JAR, do mundo e dos snapshots conforme necessário. Você também pode especificar o diretório de snapshots com `--snapshot-root <diretório>`. No singleplayer, o repositório fica em `foldbackups/<nome-da-pasta-do-mundo>`, então aponte `--snapshot-root` para esse diretório. **Pare o servidor antes de realizar uma restauração offline.**

Liste os snapshots:

```bash
java -jar ../mods/foldbackup-<version>.jar snapshot list
```

Copie o ID do snapshot desejado na lista e restaure o chunk. O comando a seguir restaura o chunk `[x, z]` no diretório da superfície (`world`) para o estado daquele snapshot:

```bash
java -jar ../mods/foldbackup-<version>.jar snapshot rollback <snapshot-id> --world ../world --chunk <chunk-x> <chunk-z>
```

`--world` aponta para o diretório da dimensão de destino: use a raiz do mundo para a superfície (Overworld), `../world/DIM-1` para o Nether, `../world/DIM1` para o End, ou o diretório `dimensions/<namespace>/<path>` correspondente para uma dimensão personalizada. `--chunk` recebe coordenadas de chunk inteiras com sinal, e não coordenadas de bloco. Por exemplo, a coordenada de bloco `-1` pertence à coordenada de chunk `-1`. A CLI pede confirmação antes de prosseguir; adicione `--yes` para pular a confirmação.

A CLI restaura o terreno do chunk de destino, entidades de bloco, dados de entidades recuperáveis e os pontos de interesse (POI) daquele chunk exatamente como registrados no snapshot, incluindo a ocupação de tickets. Ela não restaura dados de jogadores nem configurações gerais do mundo.

### Arquivos Completos e Diferenciais

Os arquivos compactados (Archives) são voltados para o armazenamento e a distribuição de mundos, priorizando altas taxas de compactação. Como sua criação consome muitos recursos, as operações de arquivo estão disponíveis apenas pela CLI e pela TUI.

Os arquivos são criados a partir de snapshots, portanto você precisa de um snapshot antes de criar um arquivo. Você pode criar um dentro do jogo ou pela TUI ou CLI. Na TUI, selecione "5. Create archive", escolha um snapshot e o tipo de arquivo. Um arquivo diferencial também solicita um snapshot anterior para comparação, armazenando apenas o que mudou.

Um arquivo pode ser restaurado como um mundo de Minecraft ("7. Restore as world files" na TUI) ou importado como um snapshot para um repositório de snapshots. Restaurar como mundo também atualiza um salvamento existente no local, e escolher "delete extra" remove arquivos que não estão no arquivo compactado. Se você distribuir um mundo usando arquivos, poderá empacotar o mod e o script de inicialização da TUI junto com eles (o conteúdo do script é mostrado anteriormente neste README).

O formato de arquivo é independente da versão do Minecraft. Você pode usar a CLI ou a TUI para compactar mundos de versões às quais o mod não oferece suporte.

## Comandos Comuns no Jogo

| Comando | Finalidade |
| --- | --- |
| `/foldbackup backup` | Criar um snapshot manualmente |
| `/foldbackup backup --force` | Criar um backup sem aguardar o intervalo mínimo |
| `/foldbackup list` | Listar snapshots com resumo de espaço do repositório (único, compartilhado e recuperável acumulado) |
| `/foldbackup status` | Mostrar o status da tarefa |
| `/foldbackup info <snapshot-id>` | Mostrar detalhes de um snapshot e sua distribuição atual de espaço único/compartilhado |
| `/foldbackup delete [--dry-run] <id1> [id2 ...]` | Excluir um ou mais snapshots, ou estimar o espaço liberado com `--dry-run` |
| `/foldbackup pin <snapshot-id>` | Fixar um snapshot para protegê-lo da limpeza automática e da exclusão manual |
| `/foldbackup unpin <snapshot-id>` | Remover a proteção de fixação de um snapshot |
| `/foldbackup preview <x> <z> [snapshot-id]` | Visualizar a prévia de um chunk histórico na dimensão atual |
| `/foldbackup preview exit` | Encerrar a prévia |
| `/foldbackup rollback chunk <x> <z> [snapshot-id]` | Restaurar um único chunk na dimensão atual |
| `/foldbackup rollback chunk in <dimensão> <x> <z> [snapshot-id]` | Restaurar um único chunk em uma dimensão especificada |

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
> **A limpeza automática pode excluir todos os snapshots históricos não fixados, incluindo backups criados manualmente.** Os limites padrão restringem apenas o tamanho do repositório (50 GiB) e o espaço livre (5 GiB); não há limite de idade por padrão, portanto a limpeza pode excluir snapshots recentes para atingir essas metas. A meta de espaço livre não significa que o espaço foi reservado para o próximo backup. Use `/foldbackup pin <snapshot-id>` (ou pela CLI/TUI) para proteger snapshots importantes, defina os três limites de limpeza como 0 para desativar a exclusão automática ou desative a limpeza automática com `retention.enabled`.

`general.excludePatterns` corresponde a caminhos relativos ao diretório do mundo, usando `/` como separador de caminho. Por padrão, exclui `**/session.lock`, `**/backups`, `**/.backups`, `**/*.wip` e `**/DistantHorizons.sqlite`. Se um diretório corresponder, toda a sua subárvore será excluída.

## Licença e Agradecimentos

Os metadados do projeto FoldBackup declaram a licença LGPL v3.

O LRH é baseado no formato de região Linear. O projeto original, [LinearRegionFileFormatTools](https://github.com/xymb-endcrystalme/LinearRegionFileFormatTools), é licenciado sob a BSD-2-Clause. Agradecemos ao autor original e aos colaboradores.
