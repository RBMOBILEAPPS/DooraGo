$sourceDir = "d:\DooraGo"
$desktopPath = [Environment]::GetFolderPath("Desktop")
$zipPath = Join-Path $desktopPath "DooraGo_Source_Review.zip"

if (Test-Path $zipPath) {
    Remove-Item $zipPath -Force
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$zip = [System.IO.Compression.ZipFile]::Open($zipPath, [System.IO.Compression.ZipArchiveMode]::Create)

$excludeDirs = @(
    "build",
    ".dart_tool",
    ".gradle",
    ".kotlin",
    ".git",
    "node_modules"
)

$excludeExtensions = @(
    ".jks",
    ".keystore",
    ".p12",
    ".pem",
    ".key",
    ".hprof",
    ".litertlm"
)

$excludeFiles = @(
    "local.properties",
    "google-services.json",
    "crash_log.txt"
)

$allFiles = Get-ChildItem -Path $sourceDir -Recurse -File

$count = 0
foreach ($file in $allFiles) {
    $relPath = $file.FullName.Substring($sourceDir.Length).TrimStart('\', '/')
    $pathParts = $relPath.Split([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
    
    $skip = $false
    foreach ($part in $pathParts) {
        if ($excludeDirs -contains $part) {
            $skip = $true
            break
        }
    }
    
    if (-not $skip) {
        $ext = $file.Extension.ToLower()
        if ($excludeExtensions -contains $ext) {
            $skip = $true
        }
    }
    
    if (-not $skip) {
        if ($excludeFiles -contains $file.Name.ToLower()) {
            $skip = $true
        }
    }
    
    if (-not $skip) {
        $entryName = $relPath.Replace('\', '/')
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $file.FullName, $entryName, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
        $count++
    }
}

$zip.Dispose()

$fileItem = Get-Item $zipPath
Write-Output "ARCHIVE_CREATED: $zipPath (Files: $count, Size: $($fileItem.Length) bytes, SizeMB: $([math]::Round($fileItem.Length / 1MB, 2)) MB)"
