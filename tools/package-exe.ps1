param(
    [string]$Name = "Limecraft",
    [string]$Version = "1.4",
    [string]$Dest = "dist"
)

$ErrorActionPreference = "Stop"

function Convert-PngToIco {
    param(
        [Parameter(Mandatory = $true)][string]$PngPath,
        [Parameter(Mandatory = $true)][string]$IcoPath
    )

    Add-Type -AssemblyName System.Drawing
    $bitmap = [System.Drawing.Bitmap]::FromFile($PngPath)
    try {
        $pngBytes = [System.IO.File]::ReadAllBytes($PngPath)
        $width = if ($bitmap.Width -ge 256) { 0 } else { [byte]$bitmap.Width }
        $height = if ($bitmap.Height -ge 256) { 0 } else { [byte]$bitmap.Height }

        $stream = New-Object System.IO.MemoryStream
        $writer = New-Object System.IO.BinaryWriter($stream)
        try {
            $writer.Write([UInt16]0)
            $writer.Write([UInt16]1)
            $writer.Write([UInt16]1)

            $writer.Write([byte]$width)
            $writer.Write([byte]$height)
            $writer.Write([byte]0)
            $writer.Write([byte]0)
            $writer.Write([UInt16]1)
            $writer.Write([UInt16]32)
            $writer.Write([UInt32]$pngBytes.Length)
            $writer.Write([UInt32]22)

            $writer.Write($pngBytes)
            [System.IO.File]::WriteAllBytes($IcoPath, $stream.ToArray())
        } finally {
            $writer.Dispose()
            $stream.Dispose()
        }
    } finally {
        $bitmap.Dispose()
    }
}

Write-Host "Building app distribution..."
& gradle clean installDist

if (!(Test-Path "build/install/Limecraft/lib")) {
    throw "Missing build/install/Limecraft/lib. installDist failed."
}

$mainJar = Get-ChildItem "build/install/Limecraft/lib" -Filter "Limecraft-*.jar" |
    Where-Object { $_.Name -notmatch "-plain\\.jar$" } |
    Select-Object -First 1

if ($null -eq $mainJar) {
    throw "Could not find Limecraft-*.jar in build/install/Limecraft/lib"
}

$iconArgs = @()
if (Test-Path "limecraft.ico") {
    $iconArgs = @("--icon", (Resolve-Path "limecraft.ico").Path)
} elseif (Test-Path "limecraft.png") {
    $generatedIco = Join-Path (Resolve-Path ".").Path "limecraft.generated.ico"
    Convert-PngToIco -PngPath (Resolve-Path "limecraft.png").Path -IcoPath $generatedIco
    $iconArgs = @("--icon", $generatedIco)
}

Write-Host "Packaging jpackage app-image to $Dest..."
$modulePath = (Resolve-Path "build/install/Limecraft/lib").Path
$javafxModules = "javafx.base,javafx.controls,javafx.graphics,javafx.media,javafx.web"
$runtimeModules = "java.base,java.compiler,java.datatransfer,java.desktop,java.instrument,java.logging,java.management,java.management.rmi,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.smartcardio,java.sql,java.sql.rowset,java.transaction.xa,java.xml,java.xml.crypto,jdk.accessibility,jdk.attach,jdk.charsets,jdk.compiler,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.crypto.mscapi,jdk.dynalink,jdk.httpserver,jdk.incubator.vector,jdk.internal.ed,jdk.internal.jvmstat,jdk.internal.le,jdk.internal.opt,jdk.jartool,jdk.javadoc,jdk.jconsole,jdk.jdeps,jdk.jdi,jdk.jdwp.agent,jdk.jfr,jdk.jlink,jdk.jpackage,jdk.jshell,jdk.jsobject,jdk.jstatd,jdk.localedata,jdk.management,jdk.management.agent,jdk.management.jfr,jdk.naming.dns,jdk.naming.rmi,jdk.net,jdk.nio.mapmode,jdk.random,jdk.sctp,jdk.security.auth,jdk.security.jgss,jdk.unsupported,jdk.unsupported.desktop,jdk.xml.dom,jdk.zipfs,$javafxModules"
& jpackage `
  --type app-image `
  --name $Name `
  --app-version $Version `
  --input "build/install/Limecraft/lib" `
  --main-jar $mainJar.Name `
  --main-class "com.limecraft.launcher.LimecraftApp" `
  --module-path $modulePath `
  --add-modules $runtimeModules `
  --java-options "--module-path" `
  --java-options "`$APPDIR" `
  --java-options "--add-modules" `
  --java-options $javafxModules `
  --java-options "--enable-native-access=javafx.graphics" `
  @iconArgs `
  --dest $Dest

Write-Host "Done. Run: $Dest\\$Name\\$Name.exe"
