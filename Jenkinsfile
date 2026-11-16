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

                        # Use shorter Windows paths for temporary processing
						$work = Join-Path $env:TEMP "CPIQA-$env:BUILD_NUMBER"

						$uploadDir = Join-Path $work "z"
						$tempDir = Join-Path $work "t"

						New-Item -ItemType Directory -Force -Path $uploadDir | Out-Null
						New-Item -ItemType Directory -Force -Path $tempDir | Out-Null

                        # -----------------------------------------
                        # 1. OAuth authentication
                        # -----------------------------------------

                        $pair = "$env:CPI_CLIENT_ID`:$env:CPI_CLIENT_SECRET"

                        $basic = [Convert]::ToBase64String(
                            [Text.Encoding]::UTF8.GetBytes($pair)
                        )

                        $tokenResponse = Invoke-RestMethod `
                            -Uri $env:CPI_QA_TOKEN_URL `
                            -Method POST `
                            -Headers @{
                                Authorization = "Basic $basic"
                            } `
                            -Body @{
                                grant_type = "client_credentials"
                            } `
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

                        # -----------------------------------------
                        # 2. Verify target package
                        # -----------------------------------------

                        $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

                        $packageUrl = "$api/IntegrationPackages('$packageId')"

                        $package = Invoke-RestMethod `
                            -Uri $packageUrl `
                            -Method GET `
                            -Headers $headers `
                            -WebSession $session `
                            -TimeoutSec 60

                        Write-Host "QA package verified: $packageId"

                        # -----------------------------------------
                        # 3. Fetch CSRF token and session cookies
                        # -----------------------------------------

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

                        # -----------------------------------------
                        # 4. Helper: create normalized ZIP
                        # -----------------------------------------

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

                            # Resolve a canonical absolute path. Remove only trailing
                            # directory separators so relative paths start at project root.
                            $root = [IO.Path]::GetFullPath($SourceFolder).TrimEnd(
                                [char[]]@([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
                            )

                            $files = Get-ChildItem `
                                -LiteralPath $root `
                                -Recurse `
                                -File `
                                -Force

                            if ($files.Count -eq 0) {
                                throw "No files found in staged iFlow project: $root"
                            }

                            # Fail before ZIP creation if the mandatory SAP manifest is absent.
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

                                        # Relative path is calculated from the project root,
                                        # never from the temporary parent (for example t/s).
                                        $relativePath = $fullFilePath.Substring($root.Length)
                                        $relativePath = $relativePath.TrimStart(
                                            [char[]]@([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
                                        )

                                        # ZIP entry names must use forward slashes.
                                        $entryName = $relativePath.Replace([char]92, [char]47)

                                        if ([string]::IsNullOrWhiteSpace($entryName) -or
                                            $entryName.StartsWith("/") -or
                                            $entryName -match '^[A-Za-z]:') {
                                            throw "Invalid ZIP entry path generated: $entryName"
                                        }

                                        Write-Host "Adding ZIP entry: $entryName"

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

                            # Re-open and validate the exact archive that will be uploaded.
                            $validationZip = [System.IO.Compression.ZipFile]::OpenRead($DestinationZip)

                            try {
                                $manifestEntry = $validationZip.Entries |
                                    Where-Object { $_.FullName -ceq "META-INF/MANIFEST.MF" }

                                if (!$manifestEntry) {
                                    throw "Invalid SAP iFlow ZIP: META-INF/MANIFEST.MF is not at the archive root."
                                }

                                foreach ($entry in $validationZip.Entries) {
                                    if ($entry.FullName.Contains([string][char]92)) {
                                        throw "Invalid ZIP entry uses a backslash: $($entry.FullName)"
                                    }

                                    if ($entry.FullName.StartsWith("t/") -or
                                        $entry.FullName.StartsWith("s/") -or
                                        $entry.FullName.StartsWith("t/s/")) {
                                        throw "Unexpected temporary folder prefix in ZIP: $($entry.FullName)"
                                    }
                                }

                                Write-Host "ZIP validation successful. Manifest found at archive root."
                                Write-Host "Validated ZIP entries:"
                                $validationZip.Entries | ForEach-Object {
                                    Write-Host "ZIP ENTRY: $($_.FullName)"
                                }
                            }
                            finally {
                                $validationZip.Dispose()
                            }

                            Write-Host "Normalized ZIP created: $DestinationZip"
                        }

                        # -----------------------------------------
                        # 5. Read discovered iFlow projects
                        # -----------------------------------------

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

                            # -------------------------------------
                            # 6. Check whether artifact exists in QA
                            # -------------------------------------

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
                                    throw "QA artifact was found, but its version could not be read."
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

                            # -------------------------------------
                            # 7. Prepare isolated upload project
                            # -------------------------------------

                            # Short directory names prevent Windows path-length issues.
                            $stageFolder = Join-Path $tempDir "s"
                            $qaExtract = Join-Path $tempDir "q"

                            if (Test-Path -LiteralPath $stageFolder) {
                                Remove-Item -LiteralPath $stageFolder -Recurse -Force
                            }

                            if (Test-Path -LiteralPath $qaExtract) {
                                Remove-Item -LiteralPath $qaExtract -Recurse -Force
                            }

                            New-Item -ItemType Directory -Force -Path $stageFolder | Out-Null

                            # Copy latest GitHub source project.
                            Get-ChildItem -LiteralPath $sourceFolder -Force |
                                Copy-Item -Destination $stageFolder -Recurse -Force

                            # -------------------------------------
                            # 8. Use the complete GitHub source project
                            # -------------------------------------

                            # GitHub is the source of truth for the design-time artifact.
                            # Do not copy parameters.prop or parameters.propdef from QA.
                            # QA-specific values are injected separately in Stage 6.
                            $sourceParameters = Join-Path $stageFolder "src/main/resources/parameters.prop"
                            $sourceParameterDefinitions = Join-Path $stageFolder "src/main/resources/parameters.propdef"

                            if (!(Test-Path -LiteralPath $sourceParameters -PathType Leaf)) {
                                throw "GitHub source project is missing parameters.prop for '$id'."
                            }

                            if (!(Test-Path -LiteralPath $sourceParameterDefinitions -PathType Leaf)) {
                                throw "GitHub source project is missing parameters.propdef for '$id'."
                            }

                            Write-Host "Using GitHub parameters.prop and parameters.propdef unchanged."

                            # -------------------------------------
                            # 9. Create SAP-compatible ZIP
                            # -------------------------------------

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

                            # -------------------------------------
                            # 10. Convert ZIP to Base64
                            # -------------------------------------

                            $zipBytes = [IO.File]::ReadAllBytes($uploadZip)
                            $artifactContent = [Convert]::ToBase64String($zipBytes)

                            if ($existingData) {

                                # ---------------------------------
                                # 11A. PUT existing artifact
                                # ---------------------------------

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

                                # ---------------------------------
                                # 11B. POST new artifact
                                # ---------------------------------

                                Write-Host "Creating new QA artifact: $id"

                                $iflowFolder = Join-Path $stageFolder "src/main/resources/scenarioflows/integrationflow"

                                $iflowFiles = @(
                                    Get-ChildItem -LiteralPath $iflowFolder -Filter "*.iflw" -File
                                )

                                if ($iflowFiles.Count -ne 1) {
                                    throw "Expected exactly one .iflw file for '$id'; found $($iflowFiles.Count)."
                                }

                                $displayName = [IO.Path]::GetFileNameWithoutExtension($iflowFiles[0].Name)

                                Write-Host "Project ID: $id"
                                Write-Host "iFlow filename: $($iflowFiles[0].Name)"
                                Write-Host "Artifact display name: $displayName"
                                Write-Host "Package ID: $packageId"

                                Write-Host "===== POST PAYLOAD DIAGNOSTICS ====="

                                Write-Host "Artifact ID: $id"
                                Write-Host "Artifact Name: $displayName"
                                Write-Host "Package ID: $packageId"

                                if ([string]::IsNullOrWhiteSpace($artifactContent)) {
                                    throw "ArtifactContent is empty. Cannot create artifact."
                                }

                                Write-Host "Base64 character count: $($artifactContent.Length)"

                                try {
                                    $decodedBytes = [Convert]::FromBase64String($artifactContent)
                                    Write-Host "Decoded ZIP size: $($decodedBytes.Length) bytes"
                                    Write-Host "ZIP signature: $([BitConverter]::ToString($decodedBytes[0..3]))"
                                }
                                catch {
                                    throw "ArtifactContent is not valid Base64: $($_.Exception.Message)"
                                }

                                Write-Host "Building JSON request..."

                                $artifactName = $displayName

                                Write-Host "STEP 1: Preparing JSON fields..."

                                $nameJson = ConvertTo-Json -InputObject ([string]$artifactName) -Compress
                                $idJson = ConvertTo-Json -InputObject ([string]$id) -Compress
                                $packageJson = ConvertTo-Json -InputObject ([string]$packageId) -Compress

                                Write-Host "STEP 2: JSON fields prepared."

                                if ([string]::IsNullOrWhiteSpace($artifactContent)) {
                                    throw "ArtifactContent is empty."
                                }

                                Write-Host "STEP 3: Constructing JSON request..."

                                # Base64 contains only JSON-safe characters.
                                $body = '{"Name":' + $nameJson +
                                        ',"Id":' + $idJson +
                                        ',"PackageId":' + $packageJson +
                                        ',"ArtifactContent":"' + $artifactContent + '"}'

                                Write-Host "STEP 4: JSON request constructed."
                                Write-Host "JSON character count: $($body.Length)"

                                Write-Host "STEP 5: Converting JSON to UTF-8..."

                                $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($body)

                                Write-Host "STEP 6: UTF-8 conversion completed."
                                Write-Host "Request byte count: $($bodyBytes.Length)"

                                Write-Host "STEP 7: Sending POST request to SAP..."

                                Write-Host "POST URL: $api/IntegrationDesigntimeArtifacts"
                                Write-Host "Payload size: $($bodyBytes.Length) bytes"
                                Write-Host "Sending POST request to SAP..."

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
                                    Write-Host "POST RESPONSE: $($response.Content)"

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
                                            $reader = New-Object System.IO.StreamReader($httpResponse.GetResponseStream())
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
                            $bodyObject = $null
                            $body = $null
                            $bodyBytes = $null
                        }

                        # -----------------------------------------
                        # 12. Verify all uploaded artifact metadata
                        # -----------------------------------------

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

                            # Verify downloadable artifact content.
                            $verifiedVersion = [string]$verifiedData.Version
                            $downloadUrl = "$api/IntegrationDesigntimeArtifacts(Id='$id',Version='$verifiedVersion')/`$value"
                            $safeFileName = [Guid]::NewGuid().ToString() + "-verify.zip"
                            $verifyZip = Join-Path $tempDir $safeFileName

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

                        # Clear sensitive variables.
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

        stage('6. Inject QA Externalized Parameters') {
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

                        $api = "$($env:CPI_QA_BASE_URL.TrimEnd('/'))/api/v1"
                        $configRoot = Join-Path $env:WORKSPACE "config/QA"
                        $projects = Get-Content "$env:WORKSPACE/discovered-iflows.txt"

                        # OAuth
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

                        $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

                        $headers = @{
                            Authorization = "Bearer $token"
                            Accept = "application/json"
                        }

                        # CSRF token
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
                            throw "Could not obtain QA CSRF token."
                        }

                        $headers["X-CSRF-Token"] = $csrf

                        foreach ($id in $projects) {
                            Write-Host "Configuring iFlow: $id"

                            $configFile = Join-Path $configRoot "$id.json"

                            if (!(Test-Path -LiteralPath $configFile -PathType Leaf)) {
                                throw "Missing QA configuration file: $configFile"
                            }

                            $config = Get-Content -LiteralPath $configFile -Raw |
                                ConvertFrom-Json

                            if ($null -eq $config) {
                                throw "Configuration JSON is empty or invalid for $id."
                            }

                            $properties = @($config.PSObject.Properties)
                            if ($properties.Count -eq 0) {
                                throw "No parameter values defined in $configFile."
                            }

                            # Resolve the active artifact version.
                            $encodedId = [Uri]::EscapeDataString($id)
                            $metadataUrl = "$api/IntegrationDesigntimeArtifacts(Id='$id',Version='active')"

                            $metadata = Invoke-RestMethod `
                                -Uri $metadataUrl `
                                -Method GET `
                                -Headers $headers `
                                -WebSession $session `
                                -TimeoutSec 60

                            $artifact = if ($metadata.d) { $metadata.d } else { $metadata }
                            $version = [string]$artifact.Version

                            if ([string]::IsNullOrWhiteSpace($version)) {
                                throw "Could not determine active version for $id."
                            }

                            # Read the externalized configuration keys.
                            $configUrl = "$api/IntegrationDesigntimeArtifacts(Id='$id',Version='$version')/Configurations"

                            $configurationResponse = Invoke-RestMethod `
                                -Uri $configUrl `
                                -Method GET `
                                -Headers $headers `
                                -WebSession $session `
                                -TimeoutSec 60

                            if ($configurationResponse.d.results) {
                                $availableParameters = @($configurationResponse.d.results)
                            }
                            elseif ($configurationResponse.value) {
                                $availableParameters = @($configurationResponse.value)
                            }
                            else {
                                throw "Unexpected Configurations response for $id. Inspect the API response before proceeding."
                            }

                            foreach ($property in $properties) {
                                $key = [string]$property.Name
                                $value = [string]$property.Value

                                $parameter = $availableParameters |
                                    Where-Object { $_.ParameterKey -ceq $key } |
                                    Select-Object -First 1

                                if (!$parameter) {
                                    throw "Parameter '$key' is not exposed by iFlow '$id'. Check the JSON key and iFlow externalization."
                                }

                                $encodedKey = [Uri]::EscapeDataString($key)

                                $parameterUrl = "$api/IntegrationDesigntimeArtifacts(Id='$id',Version='$version')/`$links/Configurations('$encodedKey')"

                                $bodyObject = @{
                                    ParameterKey = $key
                                    ParameterValue = $value
                                    DataType = $parameter.DataType
                                }

                                $body = $bodyObject |
                                    ConvertTo-Json -Depth 10 -Compress

                                Invoke-RestMethod `
                                    -Uri $parameterUrl `
                                    -Method PUT `
                                    -Headers $headers `
                                    -WebSession $session `
                                    -ContentType "application/json; charset=utf-8" `
                                    -Body ([Text.Encoding]::UTF8.GetBytes($body)) `
                                    -TimeoutSec 60

                                Write-Host "Updated parameter: $id / $key"
                            }

                            # Read-after-write verification.
                            $check = Invoke-RestMethod `
                                -Uri $configUrl `
                                -Method GET `
                                -Headers $headers `
                                -WebSession $session `
                                -TimeoutSec 60

                            if ($check.d.results) {
                                $verifiedParameters = @($check.d.results)
                            }
                            elseif ($check.value) {
                                $verifiedParameters = @($check.value)
                            }
                            else {
                                throw "Could not read back configuration for $id."
                            }

                            foreach ($property in $properties) {
                                $actual = $verifiedParameters |
                                    Where-Object { $_.ParameterKey -ceq $property.Name } |
                                    Select-Object -First 1

                                if (!$actual -or
                                    [string]$actual.ParameterValue -cne [string]$property.Value) {
                                    throw "Parameter verification failed for '$id / $($property.Name)'."
                                }
                            }

                            Write-Host "QA PARAMETERS VERIFIED: $id"
                        }

                        Write-Host "Externalized parameter injection completed."
                        $token = $null
                        $basic = $null
                        $pair = $null
                        $headers = $null
                    '''
                }
            }
        }

        stage('7. Download and Verify QA Artifacts') {
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

                        # OAuth
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
                                    $_.FullName.Replace([char]92, [char]47)
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

                            # Compare source project file paths with downloaded ZIP paths.
                            # SAP-generated or changed metadata may cause additional entries;
                            # source files must still be present.
                            $sourceFolder = Join-Path $env:WORKSPACE $id

                            $sourceFiles = Get-ChildItem `
                                -LiteralPath $sourceFolder `
                                -Recurse `
                                -File `
                                -Force

                            foreach ($file in $sourceFiles) {
                                $sourceRoot = [IO.Path]::GetFullPath($sourceFolder).TrimEnd(
                                    [char[]]@([char]92, [char]47)
                                )
                                $fullSourcePath = [IO.Path]::GetFullPath($file.FullName)
                                $relative = $fullSourcePath.Substring($sourceRoot.Length).TrimStart(
                                    [char[]]@([char]92, [char]47)
                                ).Replace([char]92, [char]47)

                                if ($entryNames -cnotcontains $relative) {
                                    throw "Source file missing from downloaded QA ZIP: $id / $relative"
                                }
                            }

                            Write-Host "SOURCE FILE PATHS VERIFIED: $id"
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

        stage('8. Validate QA iFlows') {
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

                        $api = "$($env:CPI_QA_BASE_URL.TrimEnd('/'))/api/v1"
                        $projects = Get-Content "$env:WORKSPACE/discovered-iflows.txt"

                        # OAuth
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

                        $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

                        $headers = @{
                            Authorization = "Bearer $token"
                            Accept = "application/json"
                        }

                        # Fetch CSRF token
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
                            throw "Unable to obtain QA CSRF token."
                        }

                        $headers["X-CSRF-Token"] = $csrf

                        foreach ($id in $projects) {
                            Write-Host "Validating iFlow: $id"

                            $url = "$api/ValidateIntegrationDesigntimeArtifact?Id='$id'&Version='active'"

                            $response = Invoke-WebRequest `
                                -Uri $url `
                                -Method POST `
                                -Headers $headers `
                                -WebSession $session `
                                -ContentType "application/json" `
                                -UseBasicParsing `
                                -TimeoutSec 180

                            $result = [string]$response.Content

                            Write-Host "Validation HTTP status: $([int]$response.StatusCode)"
                            Write-Host "Validation response: $result"

                            if ([int]$response.StatusCode -ne 202) {
                                throw "Validation request did not return HTTP 202 for $id."
                            }

                            if ($result -match '(?i)Check\\s+execution\\s+result\\s*:\\s*Passed') {
                                Write-Host "VALIDATION PASSED: $id"
                            }
                            elseif ($result -match '(?i)Check\\s+execution\\s+result\\s*:\\s*Failed' -or
                                    $result -match '(?i)"severity"\\s*:\\s*"Error"') {
                                throw "SAP validation failed for $id. Check the validation response."
                            }
                            else {
                                throw "Could not confirm a Passed validation result for $id. Inspect the response before deploying."
                            }
                        }

                        Write-Host "All iFlows passed validation."
                    '''
                }
            }
        }

        stage('9. Trigger QA Deployment') {
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

                        $api = "$($env:CPI_QA_BASE_URL.TrimEnd('/'))/api/v1"
                        $projects = Get-Content "$env:WORKSPACE/discovered-iflows.txt"

                        # OAuth
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

                        $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

                        $headers = @{
                            Authorization = "Bearer $token"
                            Accept = "application/json"
                        }

                        # Fetch CSRF token
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
                            throw "Unable to obtain QA CSRF token."
                        }

                        $headers["X-CSRF-Token"] = $csrf

                        $deploymentTasks = @()

                        foreach ($id in $projects) {
                            Write-Host "Triggering deployment for: $id"

                            $url = "$api/DeployIntegrationDesigntimeArtifact?Id='$id'&Version='active'"

                            $response = Invoke-WebRequest `
                                -Uri $url `
                                -Method POST `
                                -Headers $headers `
                                -WebSession $session `
                                -ContentType "application/json" `
                                -UseBasicParsing `
                                -TimeoutSec 180

                            $responseText = $response.Content
							if ($responseText -is [byte[]]) {
    							$responseText = [System.Text.Encoding]::UTF8.GetString($responseText)
							}
							$responseText = ([string]$responseText).Trim()

                            Write-Host "Deployment HTTP status: $([int]$response.StatusCode)"
                            Write-Host "Deployment response: $responseText"

                            if ([int]$response.StatusCode -ne 202) {
                                throw "Deployment trigger did not return HTTP 202 for $id."
                            }

                                                        # Extract TaskId: plain text (normal case), JSON or XML.
                            $taskId = $null

                            # 1. Plain-text GUID, which is what SAP normally returns
                            if ($responseText -match '^\s*([0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})\s*$') {
                                $taskId = $Matches[1]
                            }

                            # 2. JSON fallback
                            if ([string]::IsNullOrWhiteSpace($taskId)) {
                                try {
                                    $json = $responseText | ConvertFrom-Json
                                    if ($json.d.TaskId)      { $taskId = [string]$json.d.TaskId }
                                    elseif ($json.TaskId)    { $taskId = [string]$json.TaskId }
                                }
                                catch { }
                            }

                            # 3. XML fallback
                            if ([string]::IsNullOrWhiteSpace($taskId)) {
                                if ($responseText -match '(?i)<(?:[^:>]+:)?TaskId[^>]*>([^<]+)</(?:[^:>]+:)?TaskId>') {
                                    $taskId = $Matches[1].Trim()
                                }
                            }

                            # 4. Last resort: any GUID anywhere in the response
                            if ([string]::IsNullOrWhiteSpace($taskId)) {
                                $m = [regex]::Match($responseText, '[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}')
                                if ($m.Success) { $taskId = $m.Value }
                            }

                            if ([string]::IsNullOrWhiteSpace($taskId)) {
                                throw "SAP returned no readable TaskId for $id. Response was: $responseText"
                            }

                            Write-Host "DEPLOYMENT TASK CREATED: $id"
                            Write-Host "TASK ID: $taskId"

                            $deploymentTasks += [PSCustomObject]@{
                                Id = $id
                                TaskId = $taskId
                            }
                        }

                        $taskFile = Join-Path $env:WORKSPACE "deployment-tasks.json"

                        $deploymentTasks |
                            ConvertTo-Json -Depth 5 |
                            Set-Content -LiteralPath $taskFile -Encoding UTF8

                        Write-Host "All deployment tasks have been created."
                        Write-Host "Task details saved to: $taskFile"
                    '''
                }

                archiveArtifacts(
                    artifacts: 'deployment-tasks.json',
                    fingerprint: true
                )
            }
        }

        stage('10. Verify QA Runtime Deployment') {
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

                        $api = "$($env:CPI_QA_BASE_URL.TrimEnd('/'))/api/v1"

                        $taskFile = Join-Path $env:WORKSPACE "deployment-tasks.json"

                        if (!(Test-Path -LiteralPath $taskFile)) {
                            throw "Deployment task file is missing."
                        }

                        $tasks = @(Get-Content -LiteralPath $taskFile -Raw |
                            ConvertFrom-Json)

                        if ($tasks.Count -eq 0) {
                            throw "No deployment tasks found."
                        }

                        # OAuth
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

                        foreach ($task in $tasks) {
                            $id = [string]$task.Id
                            $taskId = [string]$task.TaskId

                            Write-Host ""
                            Write-Host "======================================"
                            Write-Host "Monitoring: $id"
                            Write-Host "Task ID: $taskId"
                            Write-Host "======================================"

                            $taskUrl = "$api/BuildAndDeployStatus(TaskId='$taskId')"
                            $deploymentStatus = ""

                            # Poll deployment task for up to 10 minutes.
                            for ($attempt = 1; $attempt -le 60; $attempt++) {
                                $statusResponse = Invoke-RestMethod `
                                    -Uri $taskUrl `
                                    -Method GET `
                                    -Headers $headers `
                                    -TimeoutSec 60

                                $statusData = if ($statusResponse.d) {
                                    $statusResponse.d
                                }
                                else {
                                    $statusResponse
                                }

                                $deploymentStatus = [string]$statusData.Status

                                Write-Host "Attempt $attempt - $id deployment status: $deploymentStatus"

                                if ($deploymentStatus -match '^(?i)SUCCESS$') {
                                    break
                                }

                                if ($deploymentStatus -match '^(?i)FAIL$|^FAIL_ON_LICENSE_ERROR$') {
                                    throw "Deployment failed for $id. Task status: $deploymentStatus"
                                }

                                if ($deploymentStatus -notmatch '^(?i)DEPLOYING$') {
                                    throw "Unexpected deployment status for $id: '$deploymentStatus'. Inspect the API response."
                                }

                                Start-Sleep -Seconds 10
                            }

                            if ($deploymentStatus -notmatch '^(?i)SUCCESS$') {
                                throw "Deployment did not complete within 10 minutes for $id. Last status: $deploymentStatus"
                            }

                            Write-Host "BUILD AND DEPLOY STATUS SUCCESS: $id"

                            # Verify actual runtime status.
                            $runtimeUrl = "$api/IntegrationRuntimeArtifacts('$id')"
                            $runtimeStatus = ""

                            for ($attempt = 1; $attempt -le 60; $attempt++) {
                                $runtimeResponse = Invoke-RestMethod `
                                    -Uri $runtimeUrl `
                                    -Method GET `
                                    -Headers $headers `
                                    -TimeoutSec 60

                                $runtimeData = if ($runtimeResponse.d) {
                                    $runtimeResponse.d
                                }
                                else {
                                    $runtimeResponse
                                }

                                $runtimeStatus = [string]$runtimeData.Status

                                Write-Host "Attempt $attempt - $id runtime status: $runtimeStatus"

                                if ($runtimeStatus -match '^(?i)STARTED$') {
                                    break
                                }

                                if ($runtimeStatus -match '^(?i)ERROR$|^STOPPED$|^NOT_STARTED$') {
                                    throw "Runtime did not start for $id. Runtime status: $runtimeStatus"
                                }

                                Start-Sleep -Seconds 10
                            }

                            if ($runtimeStatus -notmatch '^(?i)STARTED$') {
                                throw "Runtime did not reach Started within 10 minutes for $id. Last status: $runtimeStatus"
                            }

                            Write-Host "RUNTIME STARTED SUCCESSFULLY: $id"
                        }

                        Write-Host ""
                        Write-Host "======================================"
                        Write-Host "ALL QA IFLOWS DEPLOYED AND STARTED"
                        Write-Host "======================================"

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
            echo 'GitHub to QA transfer completed successfully.'
            echo 'QA externalized parameters injected and verified.'
            echo 'QA iFlows validated, deployed and runtime status verified.'
        }

        failure {
            echo 'Pipeline failed. Check the failed stage and Console Output.'
        }
    }
}
