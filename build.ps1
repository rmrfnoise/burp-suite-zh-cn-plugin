# ---------------------------------------------------------------------------
# burp-suite-zh-cn-plugin - 本地构建脚本（Windows PowerShell 5.1+ / PowerShell 7+）
#
# 用途：
#   补词条、改 Java 代码后，重新生成 dist 里的 jar。
#
# 用法：
#   .\build.ps1
#   .\build.ps1 -Clean
#   .\build.ps1 -Version 1.8.1
# ---------------------------------------------------------------------------
[CmdletBinding()]
param(
    [string]$Version = '1.8.0',
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'

# Windows PowerShell 5.1 有时默认使用旧 TLS；Maven Central 需要 TLS 1.2+
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
} catch {
    # PowerShell 7 或没有 ServicePointManager 的环境
}

$Root = $PSScriptRoot
$ApiVersion = '2024.7'
$ApiJar = Join-Path $Root "lib\montoya-api-$ApiVersion.jar"
$ApiUrl = "https://repo1.maven.org/maven2/net/portswigger/burp/extensions/montoya-api/$ApiVersion/montoya-api-$ApiVersion.jar"
$ApiSha256 = 'c091788bdd9e8eaef56d52404b19dc52cee4038e54a594376ff295ac7f6524f8'

Push-Location $Root
try {
    if ($Version -notmatch '^\d+\.\d+\.\d+$') {
        throw "版本号格式不对：$Version（示例：1.8.1）"
    }

    # 1. 准备 Montoya API（只用于编译，不打进 jar）
    if (-not (Test-Path $ApiJar)) {
        Write-Host "下载 Montoya API $ApiVersion ..."
        New-Item -ItemType Directory -Force -Path (Split-Path $ApiJar) | Out-Null
        Invoke-WebRequest -UseBasicParsing -Uri $ApiUrl -OutFile $ApiJar
    }
    $actualHash = (Get-FileHash $ApiJar -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $ApiSha256) {
        throw "Montoya API SHA256 不匹配：$actualHash"
    }

    # 2. 清理并编译 Java 源码
    if ($Clean -and (Test-Path 'build')) {
        Remove-Item 'build' -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path 'build\classes', 'dist' | Out-Null
    if (Test-Path 'build\classes') {
        Get-ChildItem 'build\classes' -Force | Remove-Item -Recurse -Force
    }

    $sources = Get-ChildItem 'src\main\java' -Recurse -File -Filter '*.java' |
        Sort-Object FullName |
        ForEach-Object { $_.FullName }
    if (-not $sources) {
        throw 'src\main\java 下没有找到 Java 源文件'
    }

    & javac -encoding UTF-8 --release 17 -Xlint:all -Xlint:-options -Xlint:-serial `
        -cp $ApiJar -d 'build\classes' @sources
    if ($LASTEXITCODE -ne 0) {
        throw "javac 编译失败，exit code $LASTEXITCODE"
    }

    # 3. 词库打进 jar，作为首次启动/升级时的内置副本
    Copy-Item 'src\main\resources\dict.json' 'build\classes\dict.json' -Force

    # 4. 生成 manifest
    $buildTime = Get-Date -Format 'yyyyMMdd-HHmm'
    $manifest = @(
        'Manifest-Version: 1.0',
        'Implementation-Title: burp-suite-zh-cn-plugin',
        "Implementation-Version: $Version+$buildTime",
        ''
    ) -join "`r`n"
    [IO.File]::WriteAllText((Join-Path $Root 'build\MANIFEST.MF'), $manifest,
        [Text.UTF8Encoding]::new($false))

    # 5. 打包
    $OutJar = Join-Path $Root "dist\burp-suite-zh-cn-plugin-$Version.jar"
    if (Test-Path $OutJar) {
        try {
            Remove-Item $OutJar -Force
        } catch {
            throw "无法覆盖 $OutJar。请先在 Burp 里 Remove 扩展，或关闭 Burp 后重试。"
        }
    }

    & jar cfm $OutJar (Join-Path $Root 'build\MANIFEST.MF') -C 'build\classes' .
    if ($LASTEXITCODE -ne 0) {
        throw "jar 打包失败，exit code $LASTEXITCODE"
    }

    $hash = (Get-FileHash $OutJar -Algorithm SHA256).Hash.ToLowerInvariant()
    $dictText = Get-Content 'src\main\resources\dict.json' -Raw -Encoding UTF8
    $dictVersion = if ($dictText -match '"version"\s*:\s*(\d+)') { $Matches[1] } else { '?' }

    Write-Host ''
    Write-Host "构建完成：$OutJar"
    Write-Host "插件版本：$Version+$buildTime"
    Write-Host "词库版本：$dictVersion"
    Write-Host "SHA256  ：$hash"
    Write-Host ''
    Write-Host '下一步：Burp → Extensions → Remove 旧扩展 → Add 这个 jar。'
}
finally {
    Pop-Location
}
