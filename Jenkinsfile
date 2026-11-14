
pipeline {
    agent any

    options {
        timestamps()
        skipDefaultCheckout(true)
    }

    environment {
        CPI_QA_BASE_URL = 'https://d42a134dtrial.it-cpitrial05.cfapps.us10-001.hana.ondemand.com'
        CPI_QA_TOKEN_URL = 'https://d42a134dtrial.authentication.us10.hana.ondemand.com/oauth/token'

        CPI_CREDENTIAL_ID = 'CPI_QA_OAUTH'
        CPI_PACKAGE_ID = 'PKGCUSTOMERINTEGRATION'
    }

    stages {

        stage('1. Checkout GitHub') {
            steps {
                echo 'Checking out Integration Suite repository...'

                checkout scm

                bat 'git log -1 --oneline'
            }
        }

        stage('2. Discover iFlows') {
            steps {
                powershell '''
                    $ErrorActionPreference = "Stop"

                    $projects = Get-ChildItem `
                        -Path $env:WORKSPACE `
                        -Directory

                    $iflows = @()

                    foreach ($project in $projects) {

                        $meta = Join-Path $project.FullName "META-INF"

                        $integrationFlow = Join-Path `
                            $project.FullName `
                            "src/main/resources/scenarioflows/integrationflow"

                        if (
                            (Test-Path $meta) -and
                            (Test-Path $integrationFlow)
                        ) {
                            Write-Host "DISCOVERED IFLOW: $($project.Name)"

                            $iflows += $project.Name
                        }
                    }

                    if ($iflows.Count -eq 0) {
                        throw "No valid SAP iFlow projects discovered."
                    }

                    $iflows | Set-Content `
                        "$env:WORKSPACE/discovered-iflows.txt"

                    Write-Host "TOTAL IFLOWS: $($iflows.Count)"
                '''
            }
        }

        stage('3. Package iFlows') {
			steps {
				powershell '''
					$ErrorActionPreference = "Stop"

					Add-Type -AssemblyName System.IO.Compression
					Add-Type -AssemblyName System.IO.Compression.FileSystem

					$projects = Get-Content "$env:WORKSPACE/discovered-iflows.txt"

					$output = "$env:WORKSPACE/artifacts"

					New-Item -ItemType Directory -Force -Path $output |
						Out-Null

					foreach ($name in $projects) {

						$source = Join-Path $env:WORKSPACE $name
						$destination = Join-Path $output "$name.zip"

						if (!(Test-Path $source)) {
							throw "Project folder not found: $source"
						}

						if (Test-Path $destination) {
							Remove-Item $destination -Force
						}

						Write-Host "Packaging: $name"

						$root = (Resolve-Path $source).Path.TrimEnd([char]92) + [char]92

						$zip = [System.IO.Compression.ZipFile]::Open(
							$destination,
							[System.IO.Compression.ZipArchiveMode]::Create
						)

						try {
							$files = Get-ChildItem `
								-Path $source `
								-Recurse `
								-File `
								-Force

							foreach ($file in $files) {

								$relativePath = $file.FullName.Substring($root.Length)

								# Convert Windows backslashes to ZIP forward slashes.
								$relativePath = $relativePath -replace '\\', '/'

								[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
									$zip,
									$file.FullName,
									$relativePath,
									[System.IO.Compression.CompressionLevel]::Optimal
								) | Out-Null

								Write-Host "Added: $relativePath"
							}
						}
						finally {
							$zip.Dispose()
						}

						if (!(Test-Path $destination)) {
							throw "ZIP generation failed: $name"
						}

						Write-Host "Successfully packaged: $name"
					}
				'''
			}
		}

        stage('4. Verify Local Artifacts') {
            steps {
                powershell '''
                    $ErrorActionPreference = "Stop"

                    $projects = Get-Content `
                        "$env:WORKSPACE/discovered-iflows.txt"

                    foreach ($name in $projects) {

                        $zip = Join-Path `
                            "$env:WORKSPACE/artifacts" `
                            "$name.zip"

                        if (!(Test-Path $zip)) {
                            throw "Missing ZIP: $zip"
                        }

                        Write-Host "$name - $((Get-Item $zip).Length) bytes"
                    }

                    Write-Host "All local artifacts verified."
                '''

                archiveArtifacts(
                    artifacts: 'artifacts/*.zip',
                    fingerprint: true
                )
            }
        }

        stage('5. Authenticate with QA') {
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

                        $pair = "$env:CPI_CLIENT_ID`:$env:CPI_CLIENT_SECRET"

                        $bytes = [Text.Encoding]::UTF8.GetBytes($pair)

                        $basic = [Convert]::ToBase64String($bytes)

                        $response = Invoke-RestMethod `
                            -Uri $env:CPI_QA_TOKEN_URL `
                            -Method POST `
                            -Headers @{
                                Authorization = "Basic $basic"
                            } `
                            -Body @{
                                grant_type = "client_credentials"
                            } `
                            -ContentType "application/x-www-form-urlencoded"

                        if ([string]::IsNullOrWhiteSpace($response.access_token)) {
                            throw "OAuth access token was not returned."
                        }

                        Write-Host "QA OAuth authentication successful."

                        $pair = $null
                        $basic = $null
                        $bytes = $null
                        $response = $null
                    '''
                }
            }
        }

        stage('6. Upload iFlows to QA Design Time') {
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

                        $base = $env:CPI_QA_BASE_URL.TrimEnd('/')
                        $api = "$base/api/v1"

                        # Get OAuth token
                        $pair = "$env:CPI_CLIENT_ID`:$env:CPI_CLIENT_SECRET"

                        $basic = [Convert]::ToBase64String(
                            [Text.Encoding]::UTF8.GetBytes($pair)
                        )

                        $tokenResponse = Invoke-RestMethod `
                            -Uri $env:CPI_QA_TOKEN_URL `
                            -Method POST `
                            -Headers @{ Authorization = "Basic $basic" } `
                            -Body @{ grant_type = "client_credentials" } `
                            -ContentType "application/x-www-form-urlencoded"

                        $token = $tokenResponse.access_token

                        if (!$token) {
                            throw "Unable to authenticate with QA."
                        }

                        $headers = @{
                            Authorization = "Bearer $token"
                            Accept = "application/json"
                        }

                        # Validate that the destination package exists
                        $packageUrl = "$api/IntegrationPackages('$($env:CPI_PACKAGE_ID)')"

                        try {
                            $package = Invoke-RestMethod `
                                -Uri $packageUrl `
                                -Method GET `
                                -Headers $headers
                        }
                        catch {
                            throw "Target package not found or inaccessible: $($env:CPI_PACKAGE_ID)"
                        }

                        Write-Host "Target package verified: $($env:CPI_PACKAGE_ID)"

                        # Fetch CSRF token and session cookies
                        $session = New-Object `
                            Microsoft.PowerShell.Commands.WebRequestSession

                        $csrfHeaders = @{
                            Authorization = "Bearer $token"
                            "X-CSRF-Token" = "Fetch"
                        }

                        $csrfResponse = Invoke-WebRequest `
                            -Uri "$api/" `
                            -Method GET `
                            -Headers $csrfHeaders `
                            -WebSession $session `
                            -UseBasicParsing

                        $csrf = $csrfResponse.Headers["X-CSRF-Token"]

                        if ([string]::IsNullOrWhiteSpace($csrf)) {
                            throw "SAP CSRF token was not returned."
                        }

                        $headers["X-CSRF-Token"] = $csrf

                        # Discover projects and use folder name as technical ID
                        $projects = Get-Content `
                            "$env:WORKSPACE/discovered-iflows.txt"

                        foreach ($id in $projects) {

                            Write-Host "----------------------------------"
                            Write-Host "Processing iFlow: $id"

                            $zipPath = Join-Path `
                                "$env:WORKSPACE/artifacts" `
                                "$id.zip"

                            if (!(Test-Path $zipPath)) {
                                throw "ZIP artifact not found: $zipPath"
                            }

                            $packageArtifactsUrl = "$api/IntegrationPackages('$($env:CPI_PACKAGE_ID)')/IntegrationDesigntimeArtifacts"

                            # Check whether the artifact is already in the target package
                            $packageArtifacts = Invoke-RestMethod `
                                -Uri $packageArtifactsUrl `
                                -Method GET `
                                -Headers $headers `
                                -WebSession $session

                            $results = @()

                            if ($packageArtifacts.d.results) {
                                $results = @($packageArtifacts.d.results)
                            }

                            $existing = $results |
                                Where-Object { $_.Id -eq $id } |
                                Select-Object -First 1

                            $artifactContent = [Convert]::ToBase64String(
                                [IO.File]::ReadAllBytes($zipPath)
                            )

                            if ($existing) {

                                Write-Host "Artifact exists in target package. Updating: $id"

                                $artifactUrl = "$api/IntegrationDesigntimeArtifacts(Id='$id',Version='active')"

                                $updateBody = @{
                                    Name = $existing.Name
                                    ArtifactContent = $artifactContent
                                } | ConvertTo-Json -Depth 10

                                Invoke-RestMethod `
                                    -Uri $artifactUrl `
                                    -Method PUT `
                                    -Headers $headers `
                                    -WebSession $session `
                                    -ContentType "application/json" `
                                    -Body $updateBody | Out-Null

                                Write-Host "Updated design-time artifact: $id"
                            }
                            else {

                                # Check for an ID collision elsewhere in the tenant
                                $globalArtifactUrl = "$api/IntegrationDesigntimeArtifacts(Id='$id',Version='active')"

                                $globalExists = $false

                                try {
                                    $globalArtifact = Invoke-RestMethod `
                                        -Uri $globalArtifactUrl `
                                        -Method GET `
                                        -Headers $headers `
                                        -WebSession $session

                                    $globalExists = $true
                                }
                                catch {
                                    $status = 0

                                    if ($_.Exception.Response) {
                                        $status = [int]$_.Exception.Response.StatusCode
                                    }

                                    if ($status -ne 404) {
                                        throw "Could not check existing artifact $id. HTTP status: $status"
                                    }
                                }

                                if ($globalExists) {
                                    throw "Artifact ID $id already exists outside the target package. Manual review required."
                                }

                                Write-Host "Creating new design-time artifact: $id"

                                $createBody = @{
                                    Id = $id
                                    Name = $id
                                    PackageId = $env:CPI_PACKAGE_ID
                                    ArtifactContent = $artifactContent
                                } | ConvertTo-Json -Depth 10

                                Invoke-RestMethod `
                                    -Uri "$api/IntegrationDesigntimeArtifacts" `
                                    -Method POST `
                                    -Headers $headers `
                                    -WebSession $session `
                                    -ContentType "application/json" `
                                    -Body $createBody | Out-Null

                                Write-Host "Created design-time artifact: $id"
                            }

                            $artifactContent = $null
                            $updateBody = $null
                            $createBody = $null
                        }

                        Write-Host "All upload operations completed."

                        $token = $null
                        $tokenResponse = $null
                        $basic = $null
                        $pair = $null
                    '''
                }
            }
        }

        stage('7. Verify QA Design-Time Artifacts') {
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

                        $base = $env:CPI_QA_BASE_URL.TrimEnd('/')
                        $api = "$base/api/v1"

                        $pair = "$env:CPI_CLIENT_ID`:$env:CPI_CLIENT_SECRET"

                        $basic = [Convert]::ToBase64String(
                            [Text.Encoding]::UTF8.GetBytes($pair)
                        )

                        $tokenResponse = Invoke-RestMethod `
                            -Uri $env:CPI_QA_TOKEN_URL `
                            -Method POST `
                            -Headers @{ Authorization = "Basic $basic" } `
                            -Body @{ grant_type = "client_credentials" } `
                            -ContentType "application/x-www-form-urlencoded"

                        $headers = @{
                            Authorization = "Bearer $($tokenResponse.access_token)"
                            Accept = "application/json"
                        }

                        $url = "$api/IntegrationPackages('$($env:CPI_PACKAGE_ID)')/IntegrationDesigntimeArtifacts"

                        $response = Invoke-RestMethod `
                            -Uri $url `
                            -Method GET `
                            -Headers $headers

                        $results = @()

                        if ($response.d.results) {
                            $results = @($response.d.results)
                        }

                        $projects = Get-Content `
                            "$env:WORKSPACE/discovered-iflows.txt"

                        foreach ($id in $projects) {

                            $found = $results |
                                Where-Object { $_.Id -eq $id } |
                                Select-Object -First 1

                            if (!$found) {
                                throw "Verification failed. Artifact missing from QA package: $id"
                            }

                            Write-Host "VERIFIED: $($found.Id)"
                            Write-Host "PACKAGE: $($found.PackageId)"
                            Write-Host "VERSION: $($found.Version)"
                        }

                        Write-Host "All iFlows verified in QA design time."

                        $tokenResponse = $null
                        $pair = $null
                        $basic = $null
                    '''
                }
            }
        }
    }

    post {
        success {
            echo 'All iFlows transferred and verified in QA design time.'
            echo 'No runtime deployment was performed.'
        }

        failure {
            echo 'Pipeline failed. Check the failed stage and Console Output.'
        }

        always {
            echo 'Pipeline execution finished.'
        }
    }
}
