
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
                            # 8. Preserve existing QA parameters
                            # -------------------------------------

                            if ($existingData) {
                                Write-Host "Downloading existing QA artifact to preserve parameters."

                                $version = [string]$existingData.Version
                                $downloadUrl = "$api/IntegrationDesigntimeArtifacts(Id='$id',Version='$version')/`$value"
                                $qaZip = Join-Path $tempDir "current-qa.zip"

                                Invoke-WebRequest `
                                    -Uri $downloadUrl `
                                    -Method GET `
                                    -Headers $headers `
                                    -WebSession $session `
                                    -OutFile $qaZip `
                                    -UseBasicParsing `
                                    -TimeoutSec 120 `
                                    -ErrorAction Stop

                                if (!(Test-Path -LiteralPath $qaZip -PathType Leaf)) {
                                    throw "Could not download existing QA artifact '$id'."
                                }

                                [System.IO.Compression.ZipFile]::ExtractToDirectory(
                                    $qaZip,
                                    $qaExtract
                                )

                                $qaParameters = Join-Path $qaExtract "src/main/resources/parameters.prop"
                                $targetParameters = Join-Path $stageFolder "src/main/resources/parameters.prop"

                                if (!(Test-Path -LiteralPath $qaParameters -PathType Leaf)) {
                                    throw "Existing QA artifact has no parameters.prop. Stopping to avoid overwriting configuration."
                                }

                                if (!(Test-Path -LiteralPath (Split-Path $targetParameters -Parent) -PathType Container)) {
                                    throw "Source project does not contain the expected resources directory."
                                }

                                Copy-Item `
                                    -LiteralPath $qaParameters `
                                    -Destination $targetParameters `
                                    -Force

                                Write-Host "Existing QA parameters.prop preserved."
                            }
                            else {
                                Write-Host "New artifact: using source parameters.prop."
                            }

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

                                # Use the .iflw filename (without extension) as the
                                # display Name, matching the successful Postman request.
                                $iflowFiles = @(
                                    Get-ChildItem `
                                        -LiteralPath (Join-Path $stageFolder "src/main/resources/scenarioflows/integrationflow") `
                                        -Filter "*.iflw" `
                                        -File
                                )

                                if ($iflowFiles.Count -ne 1) {
                                    throw "Expected exactly one .iflw file for '$id'; found $($iflowFiles.Count)."
                                }

                                $displayName = [IO.Path]::GetFileNameWithoutExtension($iflowFiles[0].Name)

                                $bodyObject = @{
                                    Name = $displayName
                                    Id = $id
                                    PackageId = $packageId
                                    ArtifactContent = $artifactContent
                                }

                                $body = $bodyObject | ConvertTo-Json -Depth 10 -Compress
                                $bodyBytes = [Text.Encoding]::UTF8.GetBytes($body)

                                Write-Host "POST URL: $api/IntegrationDesigntimeArtifacts"
                                Write-Host "Artifact ID: $id"
                                Write-Host "Artifact display name: $displayName"
                                Write-Host "Package ID: $packageId"
                                Write-Host "ZIP size: $($zipBytes.Length) bytes"
                                Write-Host "POST JSON size: $($bodyBytes.Length) bytes"

                                try {
                                    $postResponse = Invoke-WebRequest `
                                        -Uri "$api/IntegrationDesigntimeArtifacts" `
                                        -Method POST `
                                        -Headers $headers `
                                        -WebSession $session `
                                        -ContentType "application/json; charset=utf-8" `
                                        -Body $bodyBytes `
                                        -UseBasicParsing `
                                        -TimeoutSec 180 `
                                        -ErrorAction Stop

                                    $postStatus = [int]$postResponse.StatusCode
                                    Write-Host "POST HTTP STATUS: $postStatus"

                                    if ($postStatus -lt 200 -or $postStatus -ge 300) {
                                        throw "SAP POST returned HTTP status $postStatus."
                                    }

                                    Write-Host "POST completed successfully: $id"
                                }
                                catch {
                                    Write-Host "POST FAILED for: $id"
                                    Write-Host "POST ERROR: $($_.Exception.Message)"

                                    if ($_.Exception.Response) {
                                        try {
                                            $response = $_.Exception.Response
                                            Write-Host "POST HTTP STATUS: $([int]$response.StatusCode)"

                                            $reader = New-Object System.IO.StreamReader(
                                                $response.GetResponseStream()
                                            )
                                            $errorBody = $reader.ReadToEnd()
                                            $reader.Dispose()

                                            if (![string]::IsNullOrWhiteSpace($errorBody)) {
                                                Write-Host "SAP ERROR RESPONSE:"
                                                Write-Host $errorBody
                                            }
                                        }
                                        catch {
                                            Write-Host "Could not read the SAP error response body."
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
    }

    post {
        success {
            echo 'GitHub to QA design-time transfer completed successfully.'
            echo 'No runtime deployment was performed.'
        }

        failure {
            echo 'Pipeline failed. Check the failed stage and Console Output.'
        }
    }
}
