pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
        timeout(time: 60, unit: 'MINUTES')
    }

    environment {
        CPI_QA_BASE_URL = 'https://d42a134dtrial.it-cpitrial05.cfapps.us10-001.hana.ondemand.com'
        CPI_QA_TOKEN_URL = 'https://d42a134dtrial.authentication.us10.hana.ondemand.com/oauth/token'
        CPI_PACKAGE_ID = 'PKGCUSTOMERINTEGRATION'
    }

    stages {
        stage('1. Checkout GitHub') {
            steps {
                checkout scm
                bat 'git log -1 --oneline'
            }
        }

        stage('2. Discover and Validate iFlows') {
            steps {
                powershell '''
                    $ErrorActionPreference = 'Stop'
                    $workspace = $env:WORKSPACE
                    $configRoot = Join-Path $workspace 'config/QA'
                    $projects = @()

                    Get-ChildItem -LiteralPath $workspace -Directory | ForEach-Object {
                        $project = $_
                        $manifest = Join-Path $project.FullName 'META-INF/MANIFEST.MF'
                        $iflowFolder = Join-Path $project.FullName 'src/main/resources/scenarioflows/integrationflow'

                        if ((Test-Path -LiteralPath $manifest -PathType Leaf) -and
                            (Test-Path -LiteralPath $iflowFolder -PathType Container)) {

                            $iflowFiles = @(Get-ChildItem -LiteralPath $iflowFolder -Filter '*.iflw' -File)
                            if ($iflowFiles.Count -ne 1) {
                                throw "Expected exactly one .iflw file in '$($project.Name)', found $($iflowFiles.Count)."
                            }

                            $resources = Join-Path $project.FullName 'src/main/resources'
                            foreach ($required in @('parameters.prop','parameters.propdef')) {
                                if (!(Test-Path -LiteralPath (Join-Path $resources $required) -PathType Leaf)) {
                                    throw "Missing $required in '$($project.Name)'."
                                }
                            }

                            $configFile = Join-Path $configRoot "$($project.Name).json"
                            if (!(Test-Path -LiteralPath $configFile -PathType Leaf)) {
                                throw "Missing QA configuration: $configFile"
                            }

                            $json = Get-Content -LiteralPath $configFile -Raw | ConvertFrom-Json
                            if ($null -eq $json -or $json -isnot [PSCustomObject] -or @($json.PSObject.Properties).Count -eq 0) {
                                throw "QA config must be a non-empty flat JSON object: $configFile"
                            }

                            Write-Host "DISCOVERED IFLOW: $($project.Name)"
                            $projects += $project.Name
                        }
                    }

                    if ($projects.Count -eq 0) { throw 'No valid iFlow projects discovered.' }
                    $projects | Set-Content -LiteralPath (Join-Path $workspace 'discovered-iflows.txt') -Encoding UTF8
                    Write-Host "TOTAL IFLOWS: $($projects.Count)"
                '''
            }
        }

        stage('3. Package Source iFlows') {
            steps {
                powershell '''
                    $ErrorActionPreference = 'Stop'
                    Add-Type -AssemblyName System.IO.Compression
                    Add-Type -AssemblyName System.IO.Compression.FileSystem

                    $workspace = $env:WORKSPACE
                    $projects = @(Get-Content -LiteralPath (Join-Path $workspace 'discovered-iflows.txt'))
                    $artifactDir = Join-Path $workspace 'artifacts'

                    # Remove stale ZIPs from previous builds.
                    if (Test-Path -LiteralPath $artifactDir) {
                        Remove-Item -LiteralPath $artifactDir -Recurse -Force
                    }
                    New-Item -ItemType Directory -Path $artifactDir -Force | Out-Null

                    foreach ($id in $projects) {
                        $source = Join-Path $workspace $id
                        $zipPath = Join-Path $artifactDir "$id.zip"
                        $stream = [IO.File]::Create($zipPath)

                        try {
                            $archive = New-Object System.IO.Compression.ZipArchive(
                                $stream,
                                [System.IO.Compression.ZipArchiveMode]::Create
                            )
                            try {
                                $files = @(Get-ChildItem -LiteralPath $source -Recurse -File -Force)
                                foreach ($file in $files) {
                                    $relative = $file.FullName.Substring($source.TrimEnd('\').Length).TrimStart('\','/')
                                    $entryName = $relative.Replace('\','/')
                                    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                                        $archive, $file.FullName, $entryName,
                                        [System.IO.Compression.CompressionLevel]::Optimal
                                    ) | Out-Null
                                }
                            }
                            finally { $archive.Dispose() }
                        }
                        finally { $stream.Dispose() }

                        $check = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
                        try {
                            foreach ($requiredEntry in @(
                                'META-INF/MANIFEST.MF',
                                'src/main/resources/parameters.prop',
                                'src/main/resources/parameters.propdef'
                            )) {
                                if (!($check.Entries | Where-Object { $_.FullName -ceq $requiredEntry })) {
                                    throw "ZIP '$id' is missing required entry '$requiredEntry'."
                                }
                            }
                            Write-Host "PACKAGED: $id | Entries: $($check.Entries.Count)"
                        }
                        finally { $check.Dispose() }
                    }
                '''
                archiveArtifacts artifacts: 'artifacts/*.zip', fingerprint: true
            }
        }

        stage('4. Transfer iFlows to QA Design Time (POST or PUT)') {
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: 'CPI_QA_OAUTH',
                        usernameVariable: 'CPI_CLIENT_ID',
                        passwordVariable: 'CPI_CLIENT_SECRET'
                    )
                ]) {
                    powershell '''
                        $ErrorActionPreference = 'Stop'
                        $api = "$($env:CPI_QA_BASE_URL.TrimEnd('/'))/api/v1"
                        $projects = @(Get-Content -LiteralPath (Join-Path $env:WORKSPACE 'discovered-iflows.txt'))
                        $artifactDir = Join-Path $env:WORKSPACE 'artifacts'

                        $pair = "$env:CPI_CLIENT_ID`:$env:CPI_CLIENT_SECRET"
                        $basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair))
                        $tokenResponse = Invoke-RestMethod `
                            -Uri $env:CPI_QA_TOKEN_URL -Method POST `
                            -Headers @{ Authorization = "Basic $basic" } `
                            -Body @{ grant_type = 'client_credentials' } `
                            -ContentType 'application/x-www-form-urlencoded' -TimeoutSec 60
                        $token = $tokenResponse.access_token
                        if ([string]::IsNullOrWhiteSpace($token)) { throw 'QA OAuth failed.' }

                        $headers = @{ Authorization = "Bearer $token"; Accept = 'application/json' }
                        $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

                        $null = Invoke-RestMethod `
                            -Uri "$api/IntegrationPackages('$env:CPI_PACKAGE_ID')" `
                            -Method GET -Headers $headers -WebSession $session -TimeoutSec 60
                        Write-Host "QA PACKAGE VERIFIED: $env:CPI_PACKAGE_ID"

                        $csrfResponse = Invoke-WebRequest `
                            -Uri "$api/" -Method GET `
                            -Headers @{ Authorization = "Bearer $token"; 'X-CSRF-Token' = 'Fetch' } `
                            -WebSession $session -UseBasicParsing -TimeoutSec 60
                        $csrf = $csrfResponse.Headers['X-CSRF-Token']
                        if ([string]::IsNullOrWhiteSpace($csrf)) { throw 'SAP did not return CSRF token.' }
                        $headers['X-CSRF-Token'] = $csrf

                        foreach ($id in $projects) {
                            Write-Host "TRANSFERRING: $id"
                            $artifactUrl = "$api/IntegrationDesigntimeArtifacts(Id='$id',Version='active')"
                            $zipPath = Join-Path $artifactDir "$id.zip"
                            if (!(Test-Path -LiteralPath $zipPath -PathType Leaf)) { throw "Missing ZIP: $zipPath" }

                            # GET determines the operation. Only HTTP 404 means the iFlow is absent.
                            $existing = $null
                            try {
                                $metadataResponse = Invoke-RestMethod `
                                    -Uri $artifactUrl -Method GET -Headers $headers `
                                    -WebSession $session -TimeoutSec 60 -ErrorAction Stop
                                $existing = if ($metadataResponse.d) { $metadataResponse.d } else { $metadataResponse }
                                if ([string]::IsNullOrWhiteSpace([string]$existing.Version)) {
                                    throw "Artifact '$id' exists but its version is missing."
                                }
                                Write-Host "EXISTS IN QA: PUT selected. Version $($existing.Version)"
                            }
                            catch {
                                $status = 0
                                if ($_.Exception.Response) {
                                    try { $status = [int]$_.Exception.Response.StatusCode } catch { $status = 0 }
                                }
                                if ($status -eq 404) {
                                    $existing = $null
                                    Write-Host 'NOT IN QA: POST selected.'
                                } else {
                                    throw "Artifact existence check failed for '$id'. HTTP $status. $($_.Exception.Message)"
                                }
                            }

                            # This is the complete GitHub source ZIP. No QA files are merged into it.
                            $zipBytes = [IO.File]::ReadAllBytes($zipPath)
                            $artifactContent = [Convert]::ToBase64String($zipBytes)

                            if ($existing) {
                                $body = @{
                                    Name = [string]$existing.Name
                                    ArtifactContent = $artifactContent
                                } | ConvertTo-Json -Depth 10 -Compress

                                $null = Invoke-RestMethod `
                                    -Uri $artifactUrl -Method PUT -Headers $headers `
                                    -WebSession $session -ContentType 'application/json; charset=utf-8' `
                                    -Body ([Text.Encoding]::UTF8.GetBytes($body)) `
                                    -TimeoutSec 180 -ErrorAction Stop
                                Write-Host "PUT SUCCESS: $id"
                            }
                            else {
                                $iflowFolder = Join-Path (Join-Path $env:WORKSPACE $id) 'src/main/resources/scenarioflows/integrationflow'
                                $iflowFiles = @(Get-ChildItem -LiteralPath $iflowFolder -Filter '*.iflw' -File)
                                if ($iflowFiles.Count -ne 1) { throw "Expected one .iflw file for '$id'." }
                                $displayName = [IO.Path]::GetFileNameWithoutExtension($iflowFiles[0].Name)

                                $nameJson = ConvertTo-Json -InputObject ([string]$displayName) -Compress
                                $idJson = ConvertTo-Json -InputObject ([string]$id) -Compress
                                $packageJson = ConvertTo-Json -InputObject ([string]$env:CPI_PACKAGE_ID) -Compress
                                $body = '{"Name":' + $nameJson +
                                        ',"Id":' + $idJson +
                                        ',"PackageId":' + $packageJson +
                                        ',"ArtifactContent":"' + $artifactContent + '"}'

                                $response = Invoke-WebRequest `
                                    -Uri "$api/IntegrationDesigntimeArtifacts" -Method POST `
                                    -Headers $headers -WebSession $session `
                                    -ContentType 'application/json; charset=utf-8' `
                                    -Body ([Text.Encoding]::UTF8.GetBytes($body)) `
                                    -UseBasicParsing -TimeoutSec 180 -ErrorAction Stop

                                if ([int]$response.StatusCode -ne 201) {
                                    throw "Unexpected POST status for '$id': $([int]$response.StatusCode)"
                                }
                                Write-Host "POST SUCCESS: $id"
                            }

                            $zipBytes = $null
                            $artifactContent = $null
                        }

                        Write-Host 'ALL IFLOWS TRANSFERRED TO QA.'
                        Write-Host 'Source parameters.prop and parameters.propdef were included unchanged.'
                    '''
                }
            }
        }

        stage('5. Inject QA Externalized Parameter Values') {
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: 'CPI_QA_OAUTH',
                        usernameVariable: 'CPI_CLIENT_ID',
                        passwordVariable: 'CPI_CLIENT_SECRET'
                    )
                ]) {
                    powershell '''
                        $ErrorActionPreference = 'Stop'
                        $api = "$($env:CPI_QA_BASE_URL.TrimEnd('/'))/api/v1"
                        $projects = @(Get-Content -LiteralPath (Join-Path $env:WORKSPACE 'discovered-iflows.txt'))
                        $configRoot = Join-Path $env:WORKSPACE 'config/QA'

                        $pair = "$env:CPI_CLIENT_ID`:$env:CPI_CLIENT_SECRET"
                        $basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair))
                        $tokenResponse = Invoke-RestMethod `
                            -Uri $env:CPI_QA_TOKEN_URL -Method POST `
                            -Headers @{ Authorization = "Basic $basic" } `
                            -Body @{ grant_type = 'client_credentials' } `
                            -ContentType 'application/x-www-form-urlencoded' -TimeoutSec 60
                        $token = $tokenResponse.access_token
                        if ([string]::IsNullOrWhiteSpace($token)) { throw 'QA OAuth failed for parameter injection.' }

                        $headers = @{ Authorization = "Bearer $token"; Accept = 'application/json' }
                        $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

                        $csrfResponse = Invoke-WebRequest `
                            -Uri "$api/" -Method GET `
                            -Headers @{ Authorization = "Bearer $token"; 'X-CSRF-Token' = 'Fetch' } `
                            -WebSession $session -UseBasicParsing -TimeoutSec 60
                        $csrf = $csrfResponse.Headers['X-CSRF-Token']
                        if ([string]::IsNullOrWhiteSpace($csrf)) { throw 'Could not obtain CSRF token for configuration PUT.' }
                        $headers['X-CSRF-Token'] = $csrf

                        foreach ($id in $projects) {
                            Write-Host "INJECTING QA CONFIGURATION: $id"
                            $configFile = Join-Path $configRoot "$id.json"
                            if (!(Test-Path -LiteralPath $configFile -PathType Leaf)) { throw "Missing QA JSON: $configFile" }

                            # Expected JSON: a flat object containing parameter-name/value pairs.
                            $config = Get-Content -LiteralPath $configFile -Raw | ConvertFrom-Json
                            if ($null -eq $config -or $config -isnot [PSCustomObject]) {
                                throw "QA JSON must be a flat JSON object: $configFile"
                            }
                            $properties = @($config.PSObject.Properties)
                            if ($properties.Count -eq 0) { throw "No QA parameter values in $configFile" }

                            $metadataResponse = Invoke-RestMethod `
                                -Uri "$api/IntegrationDesigntimeArtifacts(Id='$id',Version='active')" `
                                -Method GET -Headers $headers -WebSession $session -TimeoutSec 60
                            $artifact = if ($metadataResponse.d) { $metadataResponse.d } else { $metadataResponse }
                            $version = [string]$artifact.Version
                            if ([string]::IsNullOrWhiteSpace($version)) { throw "No active version returned for '$id'." }

                            $configUrl = "$api/IntegrationDesigntimeArtifacts(Id='$id',Version='$version')/Configurations"
                            $configResponse = Invoke-RestMethod `
                                -Uri $configUrl -Method GET -Headers $headers -WebSession $session -TimeoutSec 60

                            if ($configResponse.d.results) { $available = @($configResponse.d.results) }
                            elseif ($configResponse.value) { $available = @($configResponse.value) }
                            else { throw "Unexpected Configurations response for '$id'." }

                            foreach ($property in $properties) {
                                $key = [string]$property.Name
                                $value = if ($null -eq $property.Value) { '' } else { [string]$property.Value }
                                $parameter = $available |
                                    Where-Object { $_.ParameterKey -ceq $key } |
                                    Select-Object -First 1

                                if (!$parameter) {
                                    throw "JSON key '$key' is not exposed by '$id'. Check parameters.propdef and the JSON key spelling."
                                }

                                $encodedKey = [Uri]::EscapeDataString($key)
                                $parameterUrl = "$api/IntegrationDesigntimeArtifacts(Id='$id',Version='$version')/`$links/Configurations('$encodedKey')"
                                $body = @{
                                    ParameterKey = $key
                                    ParameterValue = $value
                                    DataType = [string]$parameter.DataType
                                } | ConvertTo-Json -Depth 10 -Compress

                                $null = Invoke-RestMethod `
                                    -Uri $parameterUrl -Method PUT -Headers $headers `
                                    -WebSession $session -ContentType 'application/json; charset=utf-8' `
                                    -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 60 -ErrorAction Stop

                                Write-Host "UPDATED: $key"
                            }

                            # Read-after-write verification for every key in this JSON.
                            $verifyResponse = Invoke-RestMethod `
                                -Uri $configUrl -Method GET -Headers $headers `
                                -WebSession $session -TimeoutSec 60
                            if ($verifyResponse.d.results) { $verified = @($verifyResponse.d.results) }
                            elseif ($verifyResponse.value) { $verified = @($verifyResponse.value) }
                            else { throw "Could not read back configuration for '$id'." }

                            foreach ($property in $properties) {
                                $actual = $verified |
                                    Where-Object { $_.ParameterKey -ceq [string]$property.Name } |
                                    Select-Object -First 1
                                $expected = if ($null -eq $property.Value) { '' } else { [string]$property.Value }
                                if (!$actual -or [string]$actual.ParameterValue -cne $expected) {
                                    throw "QA value verification failed: '$id / $($property.Name)'."
                                }
                            }
                            Write-Host "QA VALUES VERIFIED: $id"
                        }

                        Write-Host 'QA EXTERNALIZED PARAMETER INJECTION COMPLETED.'
                    '''
                }
            }
        }

        stage('6. Verify Downloaded QA Artifact Files') {
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: 'CPI_QA_OAUTH',
                        usernameVariable: 'CPI_CLIENT_ID',
                        passwordVariable: 'CPI_CLIENT_SECRET'
                    )
                ]) {
                    powershell '''
                        $ErrorActionPreference = 'Stop'
                        Add-Type -AssemblyName System.IO.Compression
                        Add-Type -AssemblyName System.IO.Compression.FileSystem

                        $api = "$($env:CPI_QA_BASE_URL.TrimEnd('/'))/api/v1"
                        $projects = @(Get-Content -LiteralPath (Join-Path $env:WORKSPACE 'discovered-iflows.txt'))

                        # Short TEMP path avoids the long workspace extraction failure.
                        $verifyRoot = Join-Path $env:TEMP "CPIQV-$env:BUILD_NUMBER"
                        if (Test-Path -LiteralPath $verifyRoot) { Remove-Item -LiteralPath $verifyRoot -Recurse -Force }
                        New-Item -ItemType Directory -Path $verifyRoot -Force | Out-Null

                        $pair = "$env:CPI_CLIENT_ID`:$env:CPI_CLIENT_SECRET"
                        $basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair))
                        $tokenResponse = Invoke-RestMethod `
                            -Uri $env:CPI_QA_TOKEN_URL -Method POST `
                            -Headers @{ Authorization = "Basic $basic" } `
                            -Body @{ grant_type = 'client_credentials' } `
                            -ContentType 'application/x-www-form-urlencoded' -TimeoutSec 60
                        $token = $tokenResponse.access_token
                        if ([string]::IsNullOrWhiteSpace($token)) { throw 'QA OAuth failed during artifact verification.' }
                        $headers = @{ Authorization = "Bearer $token"; Accept = 'application/json' }
                        $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

                        foreach ($id in $projects) {
                            Write-Host "VERIFYING QA ARTIFACT: $id"
                            $metadataResponse = Invoke-RestMethod `
                                -Uri "$api/IntegrationDesigntimeArtifacts(Id='$id',Version='active')" `
                                -Method GET -Headers $headers -WebSession $session -TimeoutSec 60
                            $artifact = if ($metadataResponse.d) { $metadataResponse.d } else { $metadataResponse }

                            if ($artifact.Id -ne $id) { throw "Artifact ID mismatch for '$id'." }
                            if ($artifact.PackageId -ne $env:CPI_PACKAGE_ID) { throw "Package mismatch for '$id'." }
                            $version = [string]$artifact.Version
                            if ([string]::IsNullOrWhiteSpace($version)) { throw "No version returned for '$id'." }

                            $safe = [Guid]::NewGuid().ToString('N')
                            $zipPath = Join-Path $verifyRoot "$safe.zip"
                            $extractPath = Join-Path $verifyRoot $safe
                            New-Item -ItemType Directory -Path $extractPath -Force | Out-Null
                            $downloadUrl = "$api/IntegrationDesigntimeArtifacts(Id='$id',Version='$version')/`$value"

                            Invoke-WebRequest `
                                -Uri $downloadUrl -Method GET -Headers $headers -WebSession $session `
                                -OutFile $zipPath -UseBasicParsing -TimeoutSec 120 -ErrorAction Stop

                            $archive = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
                            try {
                                if (!($archive.Entries | Where-Object { $_.FullName -ceq 'META-INF/MANIFEST.MF' })) {
                                    throw "Downloaded ZIP for '$id' has no root manifest."
                                }
                            }
                            finally { $archive.Dispose() }

                            [System.IO.Compression.ZipFile]::ExtractToDirectory($zipPath, $extractPath)

                            $sourceResources = Join-Path (Join-Path (Join-Path $env:WORKSPACE $id) 'src') 'main/resources'
                            $qaResources = Join-Path (Join-Path (Join-Path $extractPath 'src') 'main') 'resources'

                            foreach ($fileName in @('parameters.prop','parameters.propdef')) {
                                $sourceFile = Join-Path $sourceResources $fileName
                                $qaFile = Join-Path $qaResources $fileName
                                if (!(Test-Path -LiteralPath $qaFile -PathType Leaf)) { throw "QA ZIP missing $fileName for '$id'." }

                                $sourceHash = (Get-FileHash -LiteralPath $sourceFile -Algorithm SHA256).Hash
                                $qaHash = (Get-FileHash -LiteralPath $qaFile -Algorithm SHA256).Hash
                                Write-Host "SOURCE $fileName SHA256: $sourceHash"
                                Write-Host "QA     $fileName SHA256: $qaHash"
                                if ($sourceHash -cne $qaHash) { throw "$fileName differs between GitHub and QA for '$id'." }
                                Write-Host "MATCH: $fileName"
                            }

                            Write-Host "VERIFIED: $id | Version: $version | Package: $($artifact.PackageId)"
                        }

                        Write-Host 'SUCCESS: QA artifact files match GitHub.'
                        Write-Host 'SUCCESS: QA parameter values were injected from config/QA JSON.'
                        Write-Host 'No runtime deployment was performed.'
                    '''
                }
            }
        }
    }

    post {
        success {
            echo 'GitHub to QA design-time transport completed successfully.'
            echo 'Existing iFlows used PUT; new iFlows used POST.'
            echo 'parameters.prop and parameters.propdef were transported unchanged.'
            echo 'QA-specific values from config/QA JSON were injected and verified.'
            echo 'No runtime deployment was performed.'
        }
        failure {
            echo 'Pipeline failed. Check the first failed stage and the SAP/PowerShell error.'
        }
        always {
            echo 'CPI QA CI/CD pipeline execution finished.'
        }
    }
}
