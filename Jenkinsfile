pipeline {
    agent any

    options {
        timestamps()
    }

    environment {
        CPI_QA_BASE_URL = 'https://d42a134dtrial.it-cpitrial05.cfapps.us10-001.hana.ondemand.com'
        CPI_QA_TOKEN_URL = 'https://d42a134dtrial.authentication.us10.hana.ondemand.com/oauth/token'
        CPI_PACKAGE_ID = 'PKGCUSTOMERINTEGRATION'
    }

    stages {

        stage('1. Checkout GitHub') {
            steps {
                echo 'Fetching Integration Suite repository...'
                checkout scm
                bat 'git log -1 --oneline'
            }
        }

        stage('2. Discover iFlows') {
            steps {
                powershell '''
                    $ErrorActionPreference = "Stop"

                    $projects = Get-ChildItem -Directory
                    $iflows = @()

                    foreach ($project in $projects) {
                        $meta = Join-Path $project.FullName "META-INF"
                        $resources = Join-Path $project.FullName "src/main/resources"
                        $integrationFlow = Join-Path $resources "scenarioflows/integrationflow"

                        if ((Test-Path $meta) -and (Test-Path $integrationFlow)) {
                            Write-Host "DISCOVERED IFLOW: $($project.Name)"
                            $iflows += $project.Name
                        }
                    }

                    if ($iflows.Count -eq 0) {
                        throw "No valid SAP iFlow projects discovered."
                    }

                    Write-Host "TOTAL IFLOWS DISCOVERED: $($iflows.Count)"
                    $iflows | Set-Content "$env:WORKSPACE/discovered-iflows.txt"
                '''
            }
        }

        stage('3. Package iFlows') {
            steps {
                powershell '''
                    $ErrorActionPreference = "Stop"

                    $projects = Get-Content "$env:WORKSPACE/discovered-iflows.txt"
                    $output = "$env:WORKSPACE/artifacts"

                    New-Item -ItemType Directory -Force -Path $output | Out-Null

                    foreach ($name in $projects) {
                        $source = Join-Path $env:WORKSPACE $name
                        $zipName = "$name.zip"
                        $destination = Join-Path $output $zipName

                        Write-Host "Packaging: $name"

                        Compress-Archive `
                            -Path "$source/*" `
                            -DestinationPath $destination `
                            -Force

                        if (!(Test-Path $destination)) {
                            throw "Packaging failed for $name"
                        }

                        Write-Host "Created: $zipName"
                    }
                '''
            }
        }

        stage('4. Verify Artifacts') {
            steps {
                powershell '''
                    $ErrorActionPreference = "Stop"

                    $files = Get-ChildItem "$env:WORKSPACE/artifacts/*.zip"

                    if ($files.Count -eq 0) {
                        throw "No ZIP artifacts generated."
                    }

                    foreach ($file in $files) {
                        Write-Host "$($file.Name) - $($file.Length) bytes"
                    }

                    Write-Host "All iFlow ZIP files generated."
                '''

                archiveArtifacts(
                    artifacts: 'artifacts/*.zip',
                    fingerprint: true
                )
            }
        }

        stage('5. Transfer iFlows to QA Design Time') {
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: 'CPI_QA_OAUTH',
                        usernameVariable: 'CPI_CLIENT_ID',
                        passwordVariable: 'CPI_CLIENT_SECRET'
                    )
                ]) {
                    powershell '''
                        $ErrorActionPreference = "Stop"

                        Add-Type -AssemblyName System.IO.Compression
                        Add-Type -AssemblyName System.IO.Compression.FileSystem

                        $base = $env:CPI_QA_BASE_URL.TrimEnd('/')
                        $api = "$base/api/v1"
                        $packageId = $env:CPI_PACKAGE_ID

                        # Use short temporary paths for Windows path-length safety.
                        $work = Join-Path $env:TEMP "CPIQA-$env:BUILD_NUMBER"
                        $uploadDir = Join-Path $work "z"
                        $tempDir = Join-Path $work "t"

                        New-Item -ItemType Directory -Force -Path $uploadDir | Out-Null
                        New-Item -ItemType Directory -Force -Path $tempDir | Out-Null

                        # OAuth authentication
                        $pair = "$env:CPI_CLIENT_ID`:$env:CPI_CLIENT_SECRET"
                        $basic = [Convert]::ToBase64String(
                            [Text.Encoding]::UTF8.GetBytes($pair)
                        )

                        $tokenResponse = Invoke-RestMethod `
                            -Uri $env:CPI_QA_TOKEN_URL `
                            -Method POST `
                            -Headers @{ Authorization = "Basic $basic" } `
                            -Body @{ grant_type = "client_credentials" } `
                            -ContentType "application/x-www-form-urlencoded" `
                            -TimeoutSec 60

                        $token = $tokenResponse.access_token

                        if ([string]::IsNullOrWhiteSpace($token)) {
                            throw "QA OAuth authentication failed."
                        }

                        Write-Host "OAuth authentication successful."

                        $headers = @{
                            Authorization = "Bearer $token"
                            Accept = "application/json"
                        }

                        # Verify target package
                        $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
                        $packageUrl = "$api/IntegrationPackages('$packageId')"

                        $package = Invoke-RestMethod `
                            -Uri $packageUrl `
                            -Method GET `
                            -Headers $headers `
                            -WebSession $session `
                            -TimeoutSec 60

                        Write-Host "QA package verified: $packageId"

                        # Fetch CSRF token and session cookies
                        $csrfResponse = Invoke-WebRequest `
                            -Uri "$api/" `
                            -Method GET `
                            -Headers @{
                                Authorization = "Bearer $token"
                                "X-CSRF-Token" = "Fetch"
                            } `
                            -WebSession $session `
                            -UseBasicParsing `
                            -TimeoutSec 60

                        $csrf = $csrfResponse.Headers["X-CSRF-Token"]

                        if ([string]::IsNullOrWhiteSpace($csrf)) {
                            throw "SAP did not return a CSRF token."
                        }

                        $headers["X-CSRF-Token"] = $csrf
                        Write-Host "CSRF token obtained."

                        # Create SAP-compatible ZIP with project files at archive root.
                        function New-NormalizedZip {
                            param(
                                [string]$SourceFolder,
                                [string]$DestinationZip
                            )

                            if (!(Test-Path -LiteralPath $SourceFolder -PathType Container)) {
                                throw "Source folder does not exist: $SourceFolder"
                            }

                            if (Test-Path -LiteralPath $DestinationZip) {
                                Remove-Item -LiteralPath $DestinationZip -Force
                            }

                            $root = [IO.Path]::GetFullPath($SourceFolder).TrimEnd(
                                [char[]]@(
                                    [IO.Path]::DirectorySeparatorChar,
                                    [IO.Path]::AltDirectorySeparatorChar
                                )
                            )

                            $files = Get-ChildItem `
                                -LiteralPath $root `
                                -Recurse `
                                -File `
                                -Force

                            if ($files.Count -eq 0) {
                                throw "No files found in staged iFlow project: $root"
                            }

                            $manifestPath = Join-Path $root "META-INF/MANIFEST.MF"

                            if (!(Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
                                throw "Required project manifest is missing: $manifestPath"
                            }

                            $stream = [IO.File]::Create($DestinationZip)

                            try {
                                $archive = New-Object System.IO.Compression.ZipArchive(
                                    $stream,
                                    [System.IO.Compression.ZipArchiveMode]::Create
                                )

                                try {
                                    foreach ($file in $files) {
                                        $fullFilePath = [IO.Path]::GetFullPath($file.FullName)
                                        $relativePath = $fullFilePath.Substring($root.Length)

                                        $relativePath = $relativePath.TrimStart(
                                            [char[]]@(
                                                [IO.Path]::DirectorySeparatorChar,
                                                [IO.Path]::AltDirectorySeparatorChar
                                            )
                                        )

                                        $entryName = $relativePath.Replace([char]92, [char]47)

                                        if ([string]::IsNullOrWhiteSpace($entryName) -or
                                            $entryName.StartsWith("/") -or
                                            $entryName -match '^[A-Za-z]:') {
                                            throw "Invalid ZIP entry path generated: $entryName"
                                        }

                                        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                                            $archive,
                                            $fullFilePath,
                                            $entryName,
                                            [System.IO.Compression.CompressionLevel]::Optimal
                                        ) | Out-Null
                                    }
                                }
                                finally {
                                    $archive.Dispose()
                                }
                            }
                            finally {
                                $stream.Dispose()
                            }

                            if (!(Test-Path -LiteralPath $DestinationZip -PathType Leaf)) {
                                throw "Normalized ZIP was not created: $DestinationZip"
                            }

                            $validationZip = [System.IO.Compression.ZipFile]::OpenRead($DestinationZip)

                            try {
                                $manifestEntry = $validationZip.Entries |
                                    Where-Object { $_.FullName -ceq "META-INF/MANIFEST.MF" }

                                if (!$manifestEntry) {
                                    throw "META-INF/MANIFEST.MF is not at the ZIP root."
                                }

                                foreach ($entry in $validationZip.Entries) {
                                    if ($entry.FullName.Contains([string][char]92)) {
                                        throw "ZIP entry uses a backslash: $($entry.FullName)"
                                    }

                                    if ($entry.FullName.StartsWith("t/") -or
                                        $entry.FullName.StartsWith("s/") -or
                                        $entry.FullName.StartsWith("t/s/")) {
                                        throw "Unexpected temporary folder prefix: $($entry.FullName)"
                                    }
                                }

                                Write-Host "ZIP validation successful."
                                Write-Host "ZIP entry count: $($validationZip.Entries.Count)"
                            }
                            finally {
                                $validationZip.Dispose()
                            }

                            Write-Host "Normalized ZIP created: $DestinationZip"
                        }

                        # Read discovered iFlow projects
                        $projects = Get-Content "$env:WORKSPACE/discovered-iflows.txt"

                        foreach ($id in $projects) {
                            Write-Host ""
                            Write-Host "========================================"
                            Write-Host "Processing iFlow: $id"
                            Write-Host "========================================"

                            $sourceFolder = Join-Path $env:WORKSPACE $id
                            $sourceZip = Join-Path "$env:WORKSPACE/artifacts" "$id.zip"

                            if (!(Test-Path -LiteralPath $sourceFolder -PathType Container)) {
                                throw "Source project folder not found: $sourceFolder"
                            }

                            if (!(Test-Path -LiteralPath $sourceZip -PathType Leaf)) {
                                throw "Source ZIP not found: $sourceZip"
                            }

                            # Check whether artifact exists in QA.
                            $artifactUrl = "$api/IntegrationDesigntimeArtifacts(Id='$id',Version='active')"
                            $existingData = $null

                            try {
                                $existing = Invoke-RestMethod `
                                    -Uri $artifactUrl `
                                    -Method GET `
                                    -Headers $headers `
                                    -WebSession $session `
                                    -TimeoutSec 60 `
                                    -ErrorAction Stop

                                if ($existing.d) {
                                    $existingData = $existing.d
                                }
                                else {
                                    $existingData = $existing
                                }

                                if ([string]::IsNullOrWhiteSpace([string]$existingData.Version)) {
                                    throw "QA artifact found, but its version could not be read."
                                }

                                Write-Host "Existing QA artifact found."
                                Write-Host "QA artifact version: $($existingData.Version)"
                            }
                            catch {
                                $status = 0

                                if ($_.Exception.Response) {
                                    try {
                                        $status = [int]$_.Exception.Response.StatusCode
                                    }
                                    catch {
                                        $status = 0
                                    }
                                }

                                if ($status -eq 404) {
                                    Write-Host "Artifact does not exist in QA. Creation path selected."
                                    $existingData = $null
                                }
                                else {
                                    throw "Unable to retrieve QA artifact metadata for '$id'. HTTP status: $status. $($_.Exception.Message)"
                                }
                            }

                            # Prepare isolated upload project from GitHub source.
                            $stageFolder = Join-Path $tempDir "s"

                            if (Test-Path -LiteralPath $stageFolder) {
                                Remove-Item -LiteralPath $stageFolder -Recurse -Force
                            }

                            New-Item -ItemType Directory -Force -Path $stageFolder | Out-Null

                            Get-ChildItem -LiteralPath $sourceFolder -Force |
                                Copy-Item -Destination $stageFolder -Recurse -Force

                            # IMPORTANT:
                            # GitHub is the source of truth.
                            # Do not preserve or copy old QA parameters.prop.
                            # parameters.prop and parameters.propdef are uploaded
                            # exactly as they exist in the GitHub source folder.

                            $uploadZip = Join-Path $uploadDir "$id.zip"

                            New-NormalizedZip `
                                -SourceFolder $stageFolder `
                                -DestinationZip $uploadZip

                            # Validate ZIP entry separators.
                            $checkZip = [System.IO.Compression.ZipFile]::OpenRead($uploadZip)

                            try {
                                foreach ($entry in $checkZip.Entries) {
                                    if ($entry.FullName.Contains([string][char]92)) {
                                        throw "Invalid ZIP path separator: $($entry.FullName)"
                                    }
                                }
                            }
                            finally {
                                $checkZip.Dispose()
                            }

                            # Convert ZIP to Base64.
                            $zipBytes = [IO.File]::ReadAllBytes($uploadZip)
                            $artifactContent = [Convert]::ToBase64String($zipBytes)

                            if ($existingData) {
                                # PUT existing artifact.
                                Write-Host "Updating existing QA artifact: $id"

                                $body = @{
                                    Name = $existingData.Name
                                    ArtifactContent = $artifactContent
                                } | ConvertTo-Json -Depth 10 -Compress

                                $bodyBytes = [Text.Encoding]::UTF8.GetBytes($body)

                                Invoke-RestMethod `
                                    -Uri $artifactUrl `
                                    -Method PUT `
                                    -Headers $headers `
                                    -WebSession $session `
                                    -ContentType "application/json; charset=utf-8" `
                                    -Body $bodyBytes `
                                    -TimeoutSec 180 `
                                    -ErrorAction Stop | Out-Null

                                Write-Host "PUT completed successfully: $id"
                            }
                            else {
                                # POST new artifact.
                                Write-Host "Creating new QA artifact: $id"

                                $iflowFolder = Join-Path $stageFolder "src/main/resources/scenarioflows/integrationflow"

                                $iflowFiles = @(
                                    Get-ChildItem -LiteralPath $iflowFolder -Filter "*.iflw" -File
                                )

                                if ($iflowFiles.Count -ne 1) {
                                    throw "Expected exactly one .iflw file for '$id'; found $($iflowFiles.Count)."
                                }

                                $displayName = [IO.Path]::GetFileNameWithoutExtension($iflowFiles[0].Name)

                                if ([string]::IsNullOrWhiteSpace($artifactContent)) {
                                    throw "ArtifactContent is empty. Cannot create artifact."
                                }

                                $nameJson = ConvertTo-Json -InputObject ([string]$displayName) -Compress
                                $idJson = ConvertTo-Json -InputObject ([string]$id) -Compress
                                $packageJson = ConvertTo-Json -InputObject ([string]$packageId) -Compress

                                $body = '{"Name":' + $nameJson +
                                        ',"Id":' + $idJson +
                                        ',"PackageId":' + $packageJson +
                                        ',"ArtifactContent":"' + $artifactContent + '"}'

                                $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($body)

                                try {
                                    $response = Invoke-WebRequest `
                                        -Uri "$api/IntegrationDesigntimeArtifacts" `
                                        -Method POST `
                                        -Headers $headers `
                                        -WebSession $session `
                                        -ContentType "application/json; charset=utf-8" `
                                        -Body $bodyBytes `
                                        -UseBasicParsing `
                                        -TimeoutSec 90 `
                                        -ErrorAction Stop

                                    Write-Host "POST HTTP STATUS: $([int]$response.StatusCode)"

                                    if ([int]$response.StatusCode -ne 201) {
                                        throw "Unexpected POST status: $([int]$response.StatusCode)"
                                    }

                                    Write-Host "POST completed successfully: $id"
                                }
                                catch {
                                    Write-Host "POST REQUEST FAILED"
                                    Write-Host "ERROR: $($_.Exception.Message)"

                                    if ($_.Exception.Response) {
                                        $httpResponse = $_.Exception.Response
                                        Write-Host "HTTP STATUS: $([int]$httpResponse.StatusCode)"

                                        try {
                                            $reader = New-Object System.IO.StreamReader(
                                                $httpResponse.GetResponseStream()
                                            )
                                            $errorBody = $reader.ReadToEnd()
                                            $reader.Dispose()
                                            Write-Host "SAP ERROR RESPONSE: $errorBody"
                                        }
                                        catch {
                                            Write-Host "Could not read SAP error response."
                                        }
                                    }

                                    throw
                                }
                            }

                            # Clear artifact payload from memory.
                            $artifactContent = $null
                            $zipBytes = $null
                            $body = $null
                            $bodyBytes = $null
                        }

                        # Verify uploaded artifact metadata and downloadable ZIP content.
                        Write-Host ""
                        Write-Host "Verifying all uploaded artifacts..."

                        foreach ($id in $projects) {
                            $verifyUrl = "$api/IntegrationDesigntimeArtifacts(Id='$id',Version='active')"

                            $verified = Invoke-RestMethod `
                                -Uri $verifyUrl `
                                -Method GET `
                                -Headers $headers `
                                -WebSession $session `
                                -TimeoutSec 60 `
                                -ErrorAction Stop

                            if ($verified.d) {
                                $verifiedData = $verified.d
                            }
                            else {
                                $verifiedData = $verified
                            }

                            if ($verifiedData.Id -ne $id) {
                                throw "Verification failed for artifact ID '$id'."
                            }

                            if ($verifiedData.PackageId -ne $packageId) {
                                throw "Incorrect package for artifact '$id'. Expected '$packageId', received '$($verifiedData.PackageId)'."
                            }

                            Write-Host "VERIFIED: $id"
                            Write-Host "VERSION: $($verifiedData.Version)"
                            Write-Host "PACKAGE: $($verifiedData.PackageId)"

                            $verifiedVersion = [string]$verifiedData.Version
                            $downloadUrl = "$api/IntegrationDesigntimeArtifacts(Id='$id',Version='$verifiedVersion')/`$value"
                            $verifyZip = Join-Path $tempDir "$([Guid]::NewGuid().ToString())-verify.zip"

                            Invoke-WebRequest `
                                -Uri $downloadUrl `
                                -Method GET `
                                -Headers $headers `
                                -WebSession $session `
                                -OutFile $verifyZip `
                                -UseBasicParsing `
                                -TimeoutSec 120 `
                                -ErrorAction Stop

                            if (!(Test-Path -LiteralPath $verifyZip -PathType Leaf)) {
                                throw "Artifact content verification failed: $id"
                            }

                            $verifyArchive = [System.IO.Compression.ZipFile]::OpenRead($verifyZip)

                            try {
                                $manifestEntry = $verifyArchive.Entries |
                                    Where-Object { $_.FullName -ceq "META-INF/MANIFEST.MF" }

                                if (!$manifestEntry) {
                                    throw "Downloaded QA artifact '$id' has no root META-INF/MANIFEST.MF."
                                }

                                Write-Host "ARTIFACT ZIP DOWNLOAD VERIFIED: $id"
                                Write-Host "VERIFIED ZIP ENTRY COUNT: $($verifyArchive.Entries.Count)"
                            }
                            finally {
                                $verifyArchive.Dispose()
                            }
                        }

                        Write-Host ""
                        Write-Host "All iFlows verified in QA design time."
                        Write-Host "No runtime deployment was performed."

                        $token = $null
                        $tokenResponse = $null
                        $pair = $null
                        $basic = $null
                        $headers = $null
                        $csrf = $null
                    '''
                }
            }
        }

        stage('6. Download and Verify QA Artifacts') {
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: 'CPI_QA_OAUTH',
                        usernameVariable: 'CPI_CLIENT_ID',
                        passwordVariable: 'CPI_CLIENT_SECRET'
                    )
                ]) {
                    powershell '''
                        $ErrorActionPreference = "Stop"

                        Add-Type -AssemblyName System.IO.Compression
                        Add-Type -AssemblyName System.IO.Compression.FileSystem

                        $api = "$($env:CPI_QA_BASE_URL.TrimEnd('/'))/api/v1"
                        $projects = Get-Content "$env:WORKSPACE/discovered-iflows.txt"

                        $verifyDir = Join-Path $env:WORKSPACE "qa-download-verification"
                        New-Item -ItemType Directory -Force -Path $verifyDir | Out-Null

                        # OAuth authentication.
                        $pair = "$env:CPI_CLIENT_ID`:$env:CPI_CLIENT_SECRET"
                        $basic = [Convert]::ToBase64String(
                            [Text.Encoding]::UTF8.GetBytes($pair)
                        )

                        $tokenResponse = Invoke-RestMethod `
                            -Uri $env:CPI_QA_TOKEN_URL `
                            -Method POST `
                            -Headers @{ Authorization = "Basic $basic" } `
                            -Body @{ grant_type = "client_credentials" } `
                            -ContentType "application/x-www-form-urlencoded" `
                            -TimeoutSec 60

                        $token = $tokenResponse.access_token

                        if ([string]::IsNullOrWhiteSpace($token)) {
                            throw "QA OAuth authentication failed."
                        }

                        $headers = @{
                            Authorization = "Bearer $token"
                            Accept = "application/json"
                        }

                        $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

                        foreach ($id in $projects) {
                            Write-Host "Verifying downloaded artifact: $id"

                            $metadataUrl = "$api/IntegrationDesigntimeArtifacts(Id='$id',Version='active')"

                            $metadata = Invoke-RestMethod `
                                -Uri $metadataUrl `
                                -Method GET `
                                -Headers $headers `
                                -WebSession $session `
                                -TimeoutSec 60

                            $artifact = if ($metadata.d) { $metadata.d } else { $metadata }

                            if ($artifact.Id -ne $id) {
                                throw "Artifact ID mismatch for $id."
                            }

                            if ($artifact.PackageId -ne $env:CPI_PACKAGE_ID) {
                                throw "Package mismatch for $id."
                            }

                            $version = [string]$artifact.Version

                            if ([string]::IsNullOrWhiteSpace($version)) {
                                throw "No version returned for $id."
                            }

                            $downloadUrl = "$api/IntegrationDesigntimeArtifacts(Id='$id',Version='$version')/`$value"
                            $downloadZip = Join-Path $verifyDir "$id.zip"

                            Invoke-WebRequest `
                                -Uri $downloadUrl `
                                -Method GET `
                                -Headers $headers `
                                -WebSession $session `
                                -OutFile $downloadZip `
                                -UseBasicParsing `
                                -TimeoutSec 120

                            if (!(Test-Path -LiteralPath $downloadZip -PathType Leaf)) {
                                throw "Downloaded ZIP not found for $id."
                            }

                            $archive = [System.IO.Compression.ZipFile]::OpenRead($downloadZip)

                            try {
                                $entries = @($archive.Entries)

                                $manifest = $entries |
                                    Where-Object { $_.FullName -ceq "META-INF/MANIFEST.MF" } |
                                    Select-Object -First 1

                                if (!$manifest) {
                                    throw "Downloaded ZIP for $id has no root META-INF/MANIFEST.MF."
                                }

                                $entryNames = @($entries | ForEach-Object {
                                    $_.FullName.Replace('\\', '/')
                                })

                                if ($entryNames.Count -eq 0) {
                                    throw "Downloaded ZIP for $id is empty."
                                }

                                Write-Host "Downloaded ZIP is valid: $id"
                                Write-Host "Artifact version: $version"
                                Write-Host "ZIP entry count: $($entryNames.Count)"
                            }
                            finally {
                                $archive.Dispose()
                            }

                            # Verify every source file path exists in the downloaded QA ZIP.
                            $sourceFolder = Join-Path $env:WORKSPACE $id

                            $sourceFiles = Get-ChildItem `
                                -LiteralPath $sourceFolder `
                                -Recurse `
                                -File `
                                -Force

                            foreach ($file in $sourceFiles) {
                                $relative = $file.FullName.Substring(
                                    $sourceFolder.TrimEnd('\\').Length
                                ).TrimStart('\\','/').Replace('\\','/')

                                if ($entryNames -cnotcontains $relative) {
                                    throw "Source file missing from downloaded QA ZIP: $id / $relative"
                                }
                            }

                            Write-Host "SOURCE FILE PATHS VERIFIED: $id"

                            # Compare parameter files byte-for-byte using SHA-256.
                            $extractFolder = Join-Path $verifyDir "$id-extracted"

                            if (Test-Path -LiteralPath $extractFolder) {
                                Remove-Item -LiteralPath $extractFolder -Recurse -Force
                            }

                            New-Item -ItemType Directory -Force -Path $extractFolder | Out-Null

                            [System.IO.Compression.ZipFile]::ExtractToDirectory(
                                $downloadZip,
                                $extractFolder
                            )

                            foreach ($parameterFile in @("parameters.prop", "parameters.propdef")) {
                                $sourceParameter = Join-Path $sourceFolder "src/main/resources/$parameterFile"
                                $qaParameter = Join-Path $extractFolder "src/main/resources/$parameterFile"

                                $sourceExists = Test-Path -LiteralPath $sourceParameter -PathType Leaf
                                $qaExists = Test-Path -LiteralPath $qaParameter -PathType Leaf

                                if ($sourceExists -ne $qaExists) {
                                    throw "Parameter file presence mismatch for $id / $parameterFile"
                                }

                                if ($sourceExists) {
                                    $sourceHash = (Get-FileHash -LiteralPath $sourceParameter -Algorithm SHA256).Hash
                                    $qaHash = (Get-FileHash -LiteralPath $qaParameter -Algorithm SHA256).Hash

                                    if ($sourceHash -cne $qaHash) {
                                        throw "Parameter file content mismatch between GitHub and QA: $id / $parameterFile"
                                    }

                                    Write-Host "PARAMETER FILE MATCHED: $id / $parameterFile (SHA-256)"
                                }
                                else {
                                    Write-Host "Parameter file absent in both GitHub and QA: $id / $parameterFile"
                                }
                            }
                        }

                        Write-Host "All QA artifacts downloaded and structurally verified."

                        $token = $null
                        $basic = $null
                        $pair = $null
                        $headers = $null
                    '''
                }
            }
        }
    }

    post {
        success {
            echo 'GitHub to QA design-time transfer completed successfully.'
            echo 'Source parameters.prop and parameters.propdef were propagated and verified against downloaded QA artifacts.'
            echo 'No runtime deployment was performed.'
        }

        failure {
            echo 'Pipeline failed. Check the failed stage and Console Output.'
        }
    }
}
