$ErrorActionPreference = 'Stop'
$versionLine = (& java -version 2>&1 | Select-Object -First 1).ToString()
if ($versionLine -notmatch '"(?:(1)\.)?(\d+)') {
    throw "Cannot determine Java version from: $versionLine"
}

$majorVersion = if ($Matches[1] -eq '1') { [int]$Matches[2] } else { [int]$Matches[2] }
if ($majorVersion -lt 17) {
    throw "Java 17 or newer is required. Current Java: $versionLine. Set JAVA_HOME to JDK 17+ before running this script."
}

