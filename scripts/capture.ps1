<#
.SYNOPSIS
  Grab the four JVM incident artifacts from a running demo-victim JVM (Windows / PowerShell).

.DESCRIPTION
  Twin of scripts/capture.sh. Writes, into -Out:
    threads.dump   jstack -l        (instant A)
    threads-2.dump jstack -l        (instant B, -Delay seconds later -> multi-dump rules)
    heap.histo     jmap -histo:live
    gc.log         copy of the JVM's own -Xlog:gc*:file= output (auto-detected, or -GcLog)
    app.log        copy of the application stdout/log file (or -AppLog)
  Requires only JAVA_HOME. Docker is not required.

.EXAMPLE
  .\scripts\capture.ps1 -Out corpus\incident-deadlock
.EXAMPLE
  .\scripts\capture.ps1 -Out corpus\incident-thread-leak -Delay 8 `
      -BetweenCmd 'curl.exe -s "http://localhost:8080/victim/leak-threads?count=60"'
#>
[CmdletBinding()]
param(
  [string] $Out = '.',
  [long]   $ProcessId = 0,
  [string] $Match = 'demo-victim',
  [int]    $Delay = 5,
  [string] $GcLog = '',
  [string] $AppLog = '',
  [string] $BetweenCmd = '',
  [switch] $SkipHisto
)

$ErrorActionPreference = 'Stop'

# -Xlog decorations, used to strip the trailing ':time,uptime,level,tags' off a file= specifier
# without falling for the ':' in a Windows drive letter.
$DECOR = '(?:time|uptime|ticks|ttlt|thread|pid|level|tags|hostname|date|rt|message|all)'
$DECOR_STRIP = ":$DECOR(?:,$DECOR)*(?::[^\s]*)?$"

function Resolve-JavaHome {
  if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin'))) { return $env:JAVA_HOME }
  $j = Get-Command java -ErrorAction SilentlyContinue
  if ($j) { return (Split-Path (Split-Path $j.Source -Parent) -Parent) }
  throw 'JAVA_HOME is not set to a JDK (need bin\jstack.exe, bin\jmap.exe, bin\jps.exe)'
}

function Get-JdkTool([string] $name) {
  $exe = Join-Path (Resolve-JavaHome) "bin\$name.exe"
  if (-not (Test-Path $exe)) { throw "$name not found under JAVA_HOME\bin" }
  return (Resolve-Path $exe).Path
}

function Invoke-Tool([string] $exe, [object[]] $toolArgs) {
  # native output goes to stdout; return it verbatim as an array of lines
  return @(& $exe @toolArgs 2>&1 | ForEach-Object { "$_" })
}

function Write-Text([string] $path, [object[]] $lines) {
  # UTF-8 without BOM, LF endings - byte-identical to what capture.sh produces via `> file`
  $text = ($lines -join "`n") + "`n"
  [IO.File]::WriteAllBytes($path, [Text.UTF8Encoding]::new($false).GetBytes($text))
}

$jps    = Get-JdkTool 'jps'
$jstack = Get-JdkTool 'jstack'
$jmap   = Get-JdkTool 'jmap'
$jcmd   = Get-JdkTool 'jcmd'

if (-not (Test-Path $Out)) { New-Item -ItemType Directory -Path $Out -Force | Out-Null }
$Out = (Resolve-Path $Out).Path

# ---------------------------------------------------------------- find the pid
$jpsLines = Invoke-Tool $jps @('-l')
$candidates = @($jpsLines | Where-Object {
  $_ -match '(?i)demo-victim|dev\.jingyu\.jia\.victim' -and $_ -notmatch '(?i)com\.intellij|\bJps\b' })
if ($ProcessId -eq 0) {
  if ($candidates.Count -eq 0) {
    Write-Host "jps -l reported:"
    $jpsLines | ForEach-Object { Write-Host "    $_" }
    throw "no JVM matching '$Match' is running - start demo-victim first"
  }
  if ($candidates.Count -gt 1) {
    Write-Warning "several matching JVMs, using the first; pass -ProcessId to choose"
    $candidates | ForEach-Object { Write-Warning "    $_" }
  }
  $target = $candidates[0]
} else {
  $target = $candidates | Where-Object { $_ -match "^$ProcessId\s" } | Select-Object -First 1
  if (-not $target) { throw "pid $ProcessId is not a running JVM according to jps" }
}
$targetPid = [long](($target -split '\s+', 2)[0])
Write-Host "==> target JVM pid $targetPid ($(($target -split '\s+', 2)[1]))"

# ---------------------------------------------------------------- auto-detect the GC log path
function Get-TargetUserDir {
  try {
    $line = (Invoke-Tool $jcmd @("$targetPid", 'VM.system_properties')) |
            Where-Object { $_ -match '^user\.dir=' } | Select-Object -First 1
    if (-not $line) { return '' }
    # java escapes it as  D\:\\some\\path  - undo both escapes
    return (($line -replace '^user\.dir=', '') -replace '\\(.)', '$1')
  } catch { return '' }
}

if (-not $GcLog) {
  try {
    $cmdline = (Invoke-Tool $jcmd @("$targetPid", 'VM.command_line')) -join ' '
    # strip decorations by name: a Windows path may contain ':' after the drive letter
    $m = [regex]::Match($cmdline, '-Xlog:\S*?file=(\S*)')
    $spec = $m.Groups[1].Value
    $spec = ($spec -replace $DECOR_STRIP, '')
    if ($spec) {
      if (Test-Path $spec) {
        $GcLog = (Resolve-Path $spec).Path
      } else {
        $dir = Get-TargetUserDir
        $guess = if ($dir) { Join-Path $dir $spec } else { $spec }
        if ($guess -and (Test-Path $guess)) { $GcLog = (Resolve-Path $guess).Path } else { $GcLog = $spec }
      }
    }
  } catch {
    Write-Warning "could not auto-detect the GC log ($_) - pass -GcLog explicitly"
    $GcLog = ''
  }
}

function Copy-Into([string] $src, [string] $name, [string] $what) {
  if (-not $src) { Write-Warning "no $what found - inspect jcmd $targetPid VM.command_line"; return }
  $dst = Join-Path $Out $name
  if ((Test-Path $dst) -and ((Resolve-Path $dst).Path -ieq ([IO.Path]::GetFullPath($src)))) {
    Write-Host "    == $name already belongs to the target JVM at $src (left in place)"
    return
  }
  if (-not (Test-Path $src)) { Write-Warning "$what $src does not exist"; return }
  Copy-Item -Force $src $dst
  Write-Host "    -> copied $what from $src"
}

# ---------------------------------------------------------------- dump 1, dump 2
Write-Host "==> jstack -l -> threads.dump"
Write-Text (Join-Path $Out 'threads.dump') (Invoke-Tool $jstack @('-l', "$targetPid"))

Start-Sleep -Seconds $Delay
if ($BetweenCmd) {
  Write-Host "==> between-cmd: $BetweenCmd"
  Invoke-Expression $BetweenCmd | Out-Null
  Start-Sleep -Seconds 2
}

Write-Host "==> jstack -l -> threads-2.dump"
Write-Text (Join-Path $Out 'threads-2.dump') (Invoke-Tool $jstack @('-l', "$targetPid"))

# ---------------------------------------------------------------- gc.log + app.log
# copied BEFORE the histogram on purpose: `jmap -histo:live` forces one full collection of its
# own, and that entry belongs to the tooling, not to the incident the analyzer should read.
if (-not $AppLog) {
  $udir = Get-TargetUserDir
  $cands = @('app.log', 'logs\app.log')
  if ($udir) { $cands = @((Join-Path $udir 'app.log'), (Join-Path $udir 'logs\app.log')) + $cands }
  foreach ($cand in $cands) {
    if (Test-Path $cand) { $AppLog = (Resolve-Path $cand).Path; break }
  }
}
Copy-Into $GcLog  'gc.log'  'GC log'
Copy-Into $AppLog 'app.log' 'application log'

# ---------------------------------------------------------------- histogram
if (-not $SkipHisto) {
  Write-Host "==> jmap -histo:live -> heap.histo   (forces one full GC in the target JVM)"
  Write-Text (Join-Path $Out 'heap.histo') (Invoke-Tool $jmap @('-histo:live', "$targetPid"))
} else {
  Write-Host '==> skipping heap histogram (-SkipHisto)'
}

# ---------------------------------------------------------------- what did we get
Write-Host ''
Write-Host "==> wrote $Out"
foreach ($f in 'threads.dump', 'threads-2.dump', 'heap.histo', 'gc.log', 'app.log') {
  $p = Join-Path $Out $f
  if (Test-Path $p) {
    $fi = Get-Item $p
    $lines = @(Get-Content $p -ErrorAction SilentlyContinue).Count
    Write-Host ("    {0,-16} {1,10} bytes  {2,8} lines" -f $f, $fi.Length, $lines)
  } else {
    Write-Host ("    {0,-16} (absent)" -f $f)
  }
}

function Count-Match([string] $file, [string] $pattern) {
  $p = Join-Path $Out $file
  if (-not (Test-Path $p)) { return 0 }
  return @(Select-String -Path $p -Pattern $pattern -ErrorAction SilentlyContinue).Count
}
Write-Host ''
Write-Host '==> evidence quick-check'
foreach ($c in @(
    @('threads.dump',   'deadlocks in dump 1',       'Found one Java-level deadlock'),
    @('threads-2.dump', 'deadlocks in dump 2',       'Found one Java-level deadlock'),
    @('threads.dump',   'BLOCKED threads (dump 1)',  'java\.lang\.Thread\.State: BLOCKED'),
    @('threads.dump',   'victim-worker threads (1)', '"victim-worker-'),
    @('threads-2.dump', 'victim-worker threads (2)', '"victim-worker-'),
    @('threads.dump',   'total threads in dump 1',   '^"'),
    @('gc.log',         'Full GC pauses',            'Pause Full'),
    @('gc.log',         'concurrent cycles',         'Concurrent Mark Cycle'),
    @('gc.log',         'young pauses',              'Pause Young'),
    @('heap.histo',     'histogram rows',            '^ *\d+:'),
    @('app.log',        'ERROR lines',               ' ERROR '),
    @('app.log',        'caused-by chains',          'Caused by:')
  )) {
  Write-Host ("    {0,-34} {1}" -f $c[1], (Count-Match $c[0] $c[2]))
}
