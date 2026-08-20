[CmdletBinding()]
param(
    [string]$SourceBranch = 'dev'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$targetBranch = 'main'
$excludedPath = '.github/workflows/test-apk.yml'

function Invoke-Git {
    param(
        [Parameter(Mandatory)]
        [string[]]$Arguments,

        [switch]$AllowFailure
    )

    & git @Arguments | Out-Host
    $exitCode = $LASTEXITCODE
    if (-not $AllowFailure -and $exitCode -ne 0) {
        throw "git $($Arguments -join ' ') failed with exit code $exitCode."
    }

    return $exitCode
}

$currentBranch = (& git branch --show-current).Trim()
if ($LASTEXITCODE -ne 0 -or $currentBranch -ne $targetBranch) {
    throw "This script must be run from the '$targetBranch' branch. Current branch: '$currentBranch'."
}

Invoke-Git -Arguments @('rev-parse', '--verify', '--quiet', $SourceBranch) | Out-Null

Invoke-Git -Arguments @('diff', '--quiet') | Out-Null
Invoke-Git -Arguments @('diff', '--cached', '--quiet') | Out-Null

$mergeExitCode = Invoke-Git -Arguments @('merge', '--no-commit', '--no-ff', $SourceBranch) -AllowFailure

# test-apk.yml is intentionally dev-only. Resolve both clean additions and
# modify/delete conflicts by keeping the file absent from main.
Invoke-Git -Arguments @('rm', '--force', '--ignore-unmatch', '--', $excludedPath) | Out-Null

$unmergedPaths = @(& git diff --name-only --diff-filter=U)
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to inspect merge conflicts.'
}

if ($unmergedPaths.Count -gt 0) {
    Write-Error "The dev-only workflow was excluded, but other merge conflicts remain:`n$($unmergedPaths -join "`n")"
    exit 1
}

if ($mergeExitCode -ne 0) {
    Write-Warning 'The merge initially reported a conflict, but excluding the dev-only workflow resolved all remaining conflicts.'
}

if (Test-Path -LiteralPath $excludedPath) {
    throw "The excluded path still exists: $excludedPath"
}

& git ls-files --error-unmatch -- $excludedPath 2>$null
if ($LASTEXITCODE -eq 0) {
    throw "The excluded path is still staged or tracked: $excludedPath"
}

Write-Host "Merged '$SourceBranch' into '$targetBranch' without $excludedPath."
Write-Host 'Review the staged merge, then create the commit with GPG signing enabled.'
