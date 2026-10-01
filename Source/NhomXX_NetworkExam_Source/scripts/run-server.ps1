$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'check-java.ps1')
Set-Location $projectRoot
mvn compile exec:java '-Dexec.mainClass=vn.edu.exam.server.ServerMain'

