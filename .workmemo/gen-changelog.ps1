param(
    [string]$OutputPath = "$PSScriptRoot\archive\changelog.md"
)

$since = if (Test-Path $OutputPath) {
    $lastLine = Get-Content $OutputPath -Tail 1
    if ($lastLine -match '(\d{4}-\d{2}-\d{2})') { $matches[1] } else { "2026-05-01" }
} else { "2026-05-01" }

$log = git log --since=$since --no-merges --pretty=format:"%ad | %s | %h" --date=short

if (-not $log) { Write-Host "No new commits since $since"; exit }

$header = @"
# Baafoo 变更日志

> 自动生成自 git log。最后更新：$(Get-Date -Format 'yyyy-MM-dd HH:mm')
> 运行 `pwsh .workmemo\gen-changelog.ps1` 刷新。

---

## $(Get-Date -Format 'yyyy-MM-dd')

"@

$entries = $log | ForEach-Object {
    $parts = $_ -split ' \| '
    $date = $parts[0]
    $msg = $parts[1]
    $hash = $parts[2]
    "- $msg [$hash](https://github.com/anomalyco/baafoo/commit/$hash)"
}

$content = @"
$header
$($entries -join "`n")
"@

if (Test-Path $OutputPath) {
    $existing = Get-Content $OutputPath -Raw
    $content = $existing.TrimEnd() + "`n`n" + $content
}

Set-Content -Path $OutputPath -Value $content -Encoding UTF8
Write-Host "Updated: $OutputPath ($($entries.Count) entries)"
