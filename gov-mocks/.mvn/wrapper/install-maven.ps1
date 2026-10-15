# Resolves (and if needed downloads) the Maven distribution named in maven-wrapper.properties.
# Prints the Maven home directory. Used by mvnw.cmd.
$ErrorActionPreference = 'Stop'
$propsFile = Join-Path $PSScriptRoot 'maven-wrapper.properties'
$line = Get-Content $propsFile | Where-Object { $_ -match '^\s*distributionUrl\s*=' } | Select-Object -First 1
if (-not $line) { throw "distributionUrl not found in $propsFile" }
$url = ($line -split '=', 2)[1].Trim()

$name = [IO.Path]::GetFileNameWithoutExtension($url) -replace '-bin$', ''
$sha = [Security.Cryptography.SHA256]::Create()
$hash = -join ($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($url)) | ForEach-Object { $_.ToString('x2') })
$userHome = if ($env:MAVEN_USER_HOME) { $env:MAVEN_USER_HOME } else { Join-Path $env:USERPROFILE '.m2' }
$mavenHome = Join-Path $userHome "wrapper\dists\$name\$hash"

if (-not (Test-Path (Join-Path $mavenHome 'bin\mvn.cmd'))) {
    [Console]::Error.WriteLine("Downloading $url")
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("mvnw-" + [Guid]::NewGuid())
    New-Item -ItemType Directory -Path $tmp | Out-Null
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $zip = Join-Path $tmp 'maven.zip'
        Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
        Expand-Archive -Path $zip -DestinationPath $tmp -Force
        $inner = Get-ChildItem -Path $tmp -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'bin\mvn.cmd') } | Select-Object -First 1
        if (-not $inner) { throw 'Downloaded archive does not contain bin\mvn.cmd' }
        New-Item -ItemType Directory -Path $mavenHome -Force | Out-Null
        Copy-Item -Path (Join-Path $inner.FullName '*') -Destination $mavenHome -Recurse -Force
    } finally {
        Remove-Item -Path $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}
Write-Output $mavenHome
