param(
    [string]$Tests,          # e.g. '--tests "com.mcos.runtime.llm.*"'
    [int]$TimeoutSec = 360,
    [string]$Tag = "run"
)
$ErrorActionPreference = "Continue"
$env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
$log = Join-Path $env:TEMP "gradle-timed-$Tag.log"
if (Test-Path $log) { Remove-Item $log -Force }
$cmd = "cd /d c:\Users\liumingzhi\Project\mcos && .\gradlew.bat :mcos-runtime:test $Tests --console=plain --no-daemon > `"$log`" 2>&1"
$p = Start-Process cmd.exe -ArgumentList "/c $cmd" -PassThru -WindowStyle Hidden
if ($p.WaitForExit($TimeoutSec * 1000)) {
    Write-Output "RESULT[$Tag]: EXIT=$($p.ExitCode) in $(([int]($p.ExitTime - $p.StartTime).TotalSeconds))s"
} else {
    taskkill /PID $p.Id /T /F | Out-Null
    Start-Sleep -Seconds 3
    Write-Output "RESULT[$Tag]: TIMEOUT after ${TimeoutSec}s (process tree killed)"
}
if (Test-Path $log) { Get-Content $log -Tail 15 }
