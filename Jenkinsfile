
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

                        $work = "$env:WORKSPACE/qa-transfer"
                        $uploadDir = "$work/upload-zips"
                        $tempDir = "$work/temp"

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

                            if (Test-Path $DestinationZip) {
                                Remove-Item $DestinationZip -Force
                            }

                            $stream = [IO.File]::Create($DestinationZip)

                            try {
                                $archive = New-Object System.IO.Compression.ZipArchive(
                                    $stream,
                                    [System.IO.Compression.ZipArchiveMode]::Create
                                )

                                try {
                                    $files = Get-ChildItem `
                                        -LiteralPath $SourceFolder `
                                        -Recurse `
                                        -File `
                                        -Force

                                    foreach ($file in $files) {
                                        $relative = $file.FullName.Substring(
                                            $SourceFolder.TrimEnd('\\').Length
                                        )

                                        $relative = $relative.TrimStart('\\','/')
                                        $entryName = $relative.Replace('\\','/')

                                        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                                            $archive,
                                            $file.FullName,
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

                            if (!(Test-Path $DestinationZip)) {
                                throw "Normalized ZIP was not created."
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

                            $sourceZip = Join-Path `
                                "$env:WORKSPACE/artifacts" `
                                "$id.zip"

                            if (!(Test-Path $sourceZip)) {
                                throw "Source ZIP not found: $sourceZip"
                            }

                            # -------------------------------------
                            # 6. Check whether artifact exists in QA
                            # -------------------------------------

                            $artifactUrl = "$api/IntegrationDesigntimeArtifacts(Id='$id',Version='active')"

                            $existing = $null

                            try {
                                $existing = Invoke-RestMethod `
                                    -Uri $artifactUrl `
                                    -Method GET `
                                    -Headers $headers `
                                    -WebSession $session `
                                    -TimeoutSec 60

                                Write-Host "Existing QA artifact found."
                            }
                            catch {
                                $status = 0

                                if ($_.Exception.Response) {
                                    $status = [int]$_.Exception.Response.StatusCode
                                }

                                if ($status -ne 404) {
                                    throw "Could not check artifact $id. HTTP status: $status"
                                }

                                Write-Host "Artifact does not exist in QA."
                            }

                            # -------------------------------------
                            # 7. Prepare isolated upload project
                            # -------------------------------------

                            $stageFolder = Join-Path $tempDir $id
                            $qaExtract = Join-Path $tempDir "$id-qa"

                            if (Test-Path $stageFolder) {
                                Remove-Item $stageFolder -Recurse -Force
                            }

                            if (Test-Path $qaExtract) {
                                Remove-Item $qaExtract -Recurse -Force
                            }

                            New-Item -ItemType Directory -Force -Path $stageFolder | Out-Null

                            # Copy latest GitHub source project
                            Get-ChildItem $sourceFolder -Force |
                                Copy-Item -Destination $stageFolder -Recurse -Force

                            # -------------------------------------
                            # 8. Preserve existing QA parameters
                            # -------------------------------------

                            if ($existing) {

                                Write-Host "Downloading existing QA artifact to preserve parameters."

                                $version = $existing.Version

                                $downloadUrl = "$api/IntegrationDesigntimeArtifacts(Id='$id',Version='$version')/`$value"

                                $qaZip = Join-Path $tempDir "$id-current-qa.zip"

                                Invoke-WebRequest `
                                    -Uri $downloadUrl `
                                    -Method GET `
                                    -Headers $headers `
                                    -WebSession $session `
                                    -OutFile $qaZip `
                                    -UseBasicParsing `
                                    -TimeoutSec 120

                                if (!(Test-Path $qaZip)) {
                                    throw "Could not download existing QA artifact $id"
                                }

                                [System.IO.Compression.ZipFile]::ExtractToDirectory(
                                    $qaZip,
                                    $qaExtract
                                )

                                $qaParameters = Join-Path `
                                    $qaExtract `
                                    "src/main/resources/parameters.prop"

                                $targetParameters = Join-Path `
                                    $stageFolder `
                                    "src/main/resources/parameters.prop"

                                if (!(Test-Path $qaParameters)) {
                                    throw "Existing QA artifact has no parameters.prop. Stopping to avoid overwriting configuration."
                                }

                                if (!(Test-Path (Split-Path $targetParameters -Parent))) {
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

                            # Validate ZIP structure
                            $checkZip = [System.IO.Compression.ZipFile]::OpenRead($uploadZip)

                            try {
                                foreach ($entry in $checkZip.Entries) {
                                    if ($entry.FullName.Contains('\\')) {
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

                            if ($existing) {

                                # ---------------------------------
                                # 11A. PUT existing artifact
                                # ---------------------------------

                                Write-Host "Updating existing QA artifact: $id"

                                $body = @{
                                    Name = $existing.Name
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
                                    -TimeoutSec 180 | Out-Null

                                Write-Host "PUT completed successfully: $id"
                            }
                            else {

                                # ---------------------------------
                                # 11B. POST new artifact
                                # ---------------------------------

                                Write-Host "Creating new QA artifact: $id"

                                $body = @{
                                    Id = $id
                                    Name = $id
                                    PackageId = $packageId
                                    ArtifactContent = $artifactContent
                                } | ConvertTo-Json -Depth 10 -Compress

                                $bodyBytes = [Text.Encoding]::UTF8.GetBytes($body)

                                Invoke-RestMethod `
                                    -Uri "$api/IntegrationDesigntimeArtifacts" `
                                    -Method POST `
                                    -Headers $headers `
                                    -WebSession $session `
                                    -ContentType "application/json; charset=utf-8" `
                                    -Body $bodyBytes `
                                    -TimeoutSec 180 | Out-Null

                                Write-Host "POST completed successfully: $id"
                            }

                            # Clear artifact payload from memory
                            $artifactContent = $null
                            $zipBytes = $null
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
                                -TimeoutSec 60

                            if ($verified.d.Id -ne $id) {
                                throw "Verification failed for $id"
                            }

                            if ($verified.d.PackageId -ne $packageId) {
                                throw "Incorrect package for $id"
                            }

                            Write-Host "VERIFIED: $id"
                            Write-Host "VERSION: $($verified.d.Version)"
                            Write-Host "PACKAGE: $($verified.d.PackageId)"

                            # Verify downloadable content
                            $downloadUrl = "$api/IntegrationDesigntimeArtifacts(Id='$id',Version='active')/`$value"

                            $verifyZip = Join-Path $tempDir "$id-verify.zip"

                            Invoke-WebRequest `
                                -Uri $downloadUrl `
                                -Method GET `
                                -Headers $headers `
                                -WebSession $session `
                                -OutFile $verifyZip `
                                -UseBasicParsing `
                                -TimeoutSec 120

                            if (!(Test-Path $verifyZip)) {
                                throw "Artifact content verification failed: $id"
                            }

                            Write-Host "ARTIFACT ZIP DOWNLOAD VERIFIED: $id"
                        }

                        Write-Host ""
                        Write-Host "All iFlows verified in QA design time."
                        Write-Host "No runtime deployment was performed."

                        # Clear sensitive variables
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
