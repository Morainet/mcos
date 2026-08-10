# MCOS Development Environment Setup
# Requires: JDK 17+ 
# JDK 17 download: https://adoptium.net/download/

Write-Host "Checking Java version..." -ForegroundColor Cyan
$javaVersion = java -version 2>&1 | Select-String "version" | ForEach-Object { $_.ToString() }
Write-Host "  $javaVersion"

if ($javaVersion -match "1\.8") {
    Write-Host "`nWARNING: Java 8 detected. JDK 17+ is required." -ForegroundColor Yellow
    Write-Host "Download JDK 17 from: https://adoptium.net/download/" -ForegroundColor Yellow
    Write-Host "After installing, set JAVA_HOME and update PATH." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Quick install via PowerShell (run as Administrator):" -ForegroundColor Gray
    Write-Host '  $url = "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.14%2B7/OpenJDK17U-jdk_x64_windows_hotspot_17.0.14_7.zip"' -ForegroundColor Gray
    Write-Host '  Invoke-WebRequest -Uri $url -OutFile "$env:TEMP\jdk17.zip"' -ForegroundColor Gray
    Write-Host '  Expand-Archive "$env:TEMP\jdk17.zip" -DestinationPath "C:\Program Files\Java"' -ForegroundColor Gray
    Write-Host '  [Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Java\jdk-17.0.14+7", "Machine")' -ForegroundColor Gray
}

Write-Host "`nGenerating Gradle wrapper..." -ForegroundColor Cyan
# Create gradle wrapper properties manually
$wrapperDir = "gradle/wrapper"
New-Item -ItemType Directory -Force -Path $wrapperDir | Out-Null

@"
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.10-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
"@ | Out-File -FilePath "$wrapperDir/gradle-wrapper.properties" -Encoding utf8

Write-Host "`nDone! Project structure created." -ForegroundColor Green
Write-Host "`nTo build (after installing JDK 17):" -ForegroundColor White
Write-Host "  cd $PSScriptRoot" -ForegroundColor Gray
Write-Host "  .\gradlew test    # Run DslParser tests" -ForegroundColor Gray
Write-Host "  .\gradlew build   # Build all modules" -ForegroundColor Gray
