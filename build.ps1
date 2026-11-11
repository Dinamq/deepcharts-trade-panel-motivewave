# build.ps1 - Compila, prueba, empaqueta e instala la estrategia Trade Panel (DeepCharts) para MotiveWave
#
#   .\build.ps1            compila + pruebas del motor + jar + instala en MotiveWave Extensions
#   .\build.ps1 -NoTest    omite las pruebas
#   .\build.ps1 -NoInstall no copia el jar a MotiveWave Extensions

param([switch]$NoTest, [switch]$NoInstall)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

# 1. JDK: reutiliza el JDK 26 portable de un proyecto vecino (no se copia); si no hay, lo descarga aquí
$jdk = $null
foreach ($cand in @(".jdk26", "..\OptionsExpirationTime\.jdk26", "..\NetDealerFlow\.jdk26", "..\DeltaDivergence\.jdk26")) {
    if (Test-Path (Join-Path $cand "bin\javac.exe")) { $jdk = (Resolve-Path $cand).Path; break }
}
if (-not $jdk) {
    Write-Host "Descargando JDK 26 portable..." -ForegroundColor Cyan
    $ProgressPreference = 'SilentlyContinue'
    $tempZip = "jdk26.zip"; $tempExtract = ".jdk26_temp"
    Invoke-WebRequest -Uri "https://api.adoptium.net/v3/binary/latest/26/ga/windows/x64/jdk/hotspot/normal/eclipse" -OutFile $tempZip -MaximumRedirection 5
    New-Item -ItemType Directory -Path $tempExtract -Force | Out-Null
    Expand-Archive -Path $tempZip -DestinationPath $tempExtract -Force
    $inner = Get-ChildItem -Path $tempExtract -Directory | Select-Object -First 1
    New-Item -ItemType Directory -Path ".jdk26" -Force | Out-Null
    Move-Item -Path "$($inner.FullName)\*" -Destination ".jdk26" -Force
    Remove-Item -Path $tempExtract -Recurse -Force; Remove-Item -Path $tempZip -Force
    $jdk = (Resolve-Path ".jdk26").Path
}

# 2. Dependencias: SDK de MotiveWave + JavaFX que trae la propia instalación
$mw = "C:\Program Files (x86)\MotiveWave"
$sdkJar = "$mw\lib\mwave_sdk.jar"
$fxLib = "$mw\javafx\lib"
if (-not (Test-Path $sdkJar)) { Write-Host "ERROR: SDK no encontrado en $sdkJar" -ForegroundColor Red; Exit 1 }
if (-not (Test-Path "$fxLib\javafx.controls.jar")) { Write-Host "ERROR: JavaFX no encontrado en $fxLib" -ForegroundColor Red; Exit 1 }
$cp = "$sdkJar;$fxLib\javafx.base.jar;$fxLib\javafx.graphics.jar;$fxLib\javafx.controls.jar"

# 3. Compilar
if (Test-Path "build") { Remove-Item -Path "build" -Recurse -Force }
New-Item -ItemType Directory -Path "build\classes" -Force | Out-Null
$sources = Get-ChildItem -Path "src" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
Write-Host "Compilando $($sources.Count) ficheros..." -ForegroundColor Cyan
& "$jdk\bin\javac.exe" -encoding UTF-8 -Xlint:deprecation -cp $cp -d "build\classes" $sources
if ($LASTEXITCODE -ne 0) { Write-Host "ERROR de compilación" -ForegroundColor Red; Exit 1 }

# 4. Pruebas del motor (simulador de bróker, sin MotiveWave)
if (-not $NoTest -and (Test-Path "test")) {
    New-Item -ItemType Directory -Path "build\test" -Force | Out-Null
    $tests = Get-ChildItem -Path "test" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
    & "$jdk\bin\javac.exe" -encoding UTF-8 -cp "build\classes;$cp" -d "build\test" $tests
    if ($LASTEXITCODE -ne 0) { Write-Host "ERROR compilando las pruebas" -ForegroundColor Red; Exit 1 }
    & "$jdk\bin\java.exe" -cp "build\test;build\classes;$cp" com.tradepanel.EngineTest
    if ($LASTEXITCODE -ne 0) { Write-Host "PRUEBAS FALLIDAS: no se instala" -ForegroundColor Red; Exit 1 }
}

# 5. Empaquetar
$jarName = "TradePanelDeepCharts.jar"
if (Test-Path $jarName) { Remove-Item -Path $jarName -Force }
& "$jdk\bin\jar.exe" cf $jarName -C "build\classes" .
if ($LASTEXITCODE -ne 0) { Write-Host "ERROR empaquetando" -ForegroundColor Red; Exit 1 }

# 6. Instalar
if (-not $NoInstall) {
    $ext = Join-Path $env:USERPROFILE "MotiveWave Extensions"
    if (-not (Test-Path $ext)) { New-Item -ItemType Directory -Path $ext -Force | Out-Null }
    Copy-Item -Path $jarName -Destination "$ext\$jarName" -Force
    # nombre antiguo del jar: si se queda, MotiveWave cargaría la estrategia dos veces
    $old = "$ext\TradePanelStrategy.jar"
    if (Test-Path $old) {
        try { Remove-Item $old -Force -ErrorAction Stop }
        catch { Write-Host "AVISO: no se pudo borrar $old (MotiveWave lo tiene abierto). Cierra MotiveWave y borralo." -ForegroundColor Yellow }
    }
    Write-Host "=============================================" -ForegroundColor Green
    Write-Host "Instalado: $ext\$jarName  (reinicia MotiveWave)" -ForegroundColor Green
    Write-Host "=============================================" -ForegroundColor Green
} else {
    Write-Host "Jar generado: $jarName (no instalado)" -ForegroundColor Green
}
