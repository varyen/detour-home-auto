# Сборка dhcore.aar (ядро на mihomo) для Android: pwsh core/build-android.ps1
# Нужны Go, Android SDK + NDK, JDK 17. Результат — app/libs/dhcore.aar.
param(
  [string] $Targets = 'android/arm64,android/arm,android/amd64',
  [string] $MihomoVersion = '1.19.32'
)
$ErrorActionPreference = 'Stop'
$core = $PSScriptRoot
$root = Split-Path $core
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$ndk = Get-ChildItem "$sdk\ndk" | Sort-Object Name | Select-Object -Last 1
$jdk = Get-ChildItem 'C:\Program Files\Microsoft\jdk-17*', 'C:\Program Files\Android\Android Studio\jbr' -ErrorAction SilentlyContinue | Select-Object -First 1
$go = (Get-Command go).Source
$goRoot = Split-Path (Split-Path $go)
$gobin = "$env:USERPROFILE\go\bin"
foreach ($cmd in 'gomobile', 'gobind') {
  & $go install "github.com/sagernet/gomobile/cmd/$cmd@v0.1.12"
  if ($LASTEXITCODE) { throw "не поставился $cmd" }
}

# gomobile падает на служебных переменных Windows вида "=C:", поэтому окружение
# собирается с нуля.
$psi = [Diagnostics.ProcessStartInfo]::new("$gobin\gomobile.exe")
$ld = "-X github.com/metacubex/mihomo/constant.Version=$MihomoVersion -checklinkname=0 -s -w -buildid="
foreach ($a in 'bind', '-v', '-o', "$root\app\libs\dhcore.aar", '-target', $Targets, '-androidapi', '26',
    '-javapkg', 'io.github.varyen.dha', '-trimpath', '-tags', 'with_gvisor,cmfa', '-ldflags', $ld, '.') {
  $psi.ArgumentList.Add($a)
}
$psi.UseShellExecute = $false
$psi.WorkingDirectory = $core
$psi.Environment.Clear()
$envs = @{
  SystemRoot = $env:SystemRoot; windir = $env:windir; TEMP = $env:TEMP; TMP = $env:TEMP
  USERPROFILE = $env:USERPROFILE; LOCALAPPDATA = $env:LOCALAPPDATA; APPDATA = $env:APPDATA
  HOMEDRIVE = $env:HOMEDRIVE; HOMEPATH = $env:HOMEPATH; NUMBER_OF_PROCESSORS = $env:NUMBER_OF_PROCESSORS
  JAVA_HOME = $jdk.FullName; ANDROID_HOME = $sdk; ANDROID_NDK_HOME = $ndk.FullName; NDK_HOME = $ndk.FullName
  GOROOT = $goRoot; GOFLAGS = '-buildvcs=false'
  PATH = "$($jdk.FullName)\bin;$goRoot\bin;$gobin;$env:SystemRoot\system32;$env:SystemRoot"
}
foreach ($k in $envs.Keys) { $psi.Environment[$k] = $envs[$k] }
New-Item -ItemType Directory -Force "$root\app\libs" | Out-Null
$p = [Diagnostics.Process]::Start($psi)
$p.WaitForExit()
if ($p.ExitCode) { throw "gomobile bind упал ($($p.ExitCode))" }
Get-Item "$root\app\libs\dhcore.aar" | Select-Object Name, Length
