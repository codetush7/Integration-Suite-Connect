
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

        // STAGE 5: Authenticate and transfer to QA
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

                        $base = $env:CPI_QA_BASE_URL.TrimEnd('/')
                        $api = "$base/api/v1"
                        $packageId = $env:CPI_PACKAGE_ID

                        # ----------------------------------
                        # 1. Obtain OAuth token
                        # ----------------------------------

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
                            -ContentType "application/x-www-form-urlencoded"

                        $token = $tokenResponse.access_token

                        if (!$token) {
                            throw "QA OAuth authentication failed."
                        }

                        Write-Host "QA OAuth authentication successful."

                        $headers = @{
                            Authorization = "Bearer $token"
                            Accept = "application/json"
                        }

                        # ----------------------------------
                        # 2. Verify target package
                        # ----------------------------------

                        $packageUrl = "$api/IntegrationPackages('$packageId')"

                        try {
                            $package = Invoke-RestMethod `
                                -Uri $packageUrl `
                                -Method GET `
                                -Headers $headers
                        }
                        catch {
                            throw "Cannot access QA package $packageId. Check its technical ID and API permissions."
                        }

                        Write-Host "Target QA package verified: $packageId"

                        # ----------------------------------
                        # 3. Fetch CSRF token and cookies
                        # ----------------------------------

                        $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

                        $csrfResponse = Invoke-WebRequest `
                            -Uri "$api/" `
                            -Method GET `
                            -Headers @{
                                Authorization = "Bearer $token"
                                "X-CSRF-Token" = "Fetch"
                            } `
                            -WebSession $session `
                            -UseBasicParsing

                        $csrf = $csrfResponse.Headers["X-CSRF-Token"]

                        if ([string]::IsNullOrWhiteSpace($csrf)) {
                            throw "SAP did not return a CSRF token."
                        }

                        $headers["X-CSRF-Token"] = $csrf

                        Write-Host "CSRF token obtained."

                        # ----------------------------------
                        # 4. Read discovered iFlow projects
                        # ----------------------------------

                        $projects = Get-Content `
                            "$env:WORKSPACE/discovered-iflows.txt"

                        foreach ($project in $projects) {

                            # The GitHub folder name is the
                            # confirmed technical iFlow ID.
                            $id = $project

                            $zipPath = Join-Path `
                                "$env:WORKSPACE/artifacts" `
                                "$project.zip"

                            if (!(Test-Path $zipPath)) {
                                throw "ZIP not found: $zipPath"
                            }

                            Write-Host "-----------------------------------"
                            Write-Host "Processing iFlow: $id"

                            # ----------------------------------
                            # 5. Check target package artifacts
                            # ----------------------------------

                            $packageArtifactsUrl = "$api/IntegrationPackages('$packageId')/IntegrationDesigntimeArtifacts"

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

                            # Read ZIP and convert to Base64
                            $zipBytes = [IO.File]::ReadAllBytes($zipPath)

                            $artifactContent = [Convert]::ToBase64String($zipBytes)

                            if ($existing) {

                                # ----------------------------------
                                # 6A. Update existing target artifact
                                # ----------------------------------

                                Write-Host "Artifact already exists in QA package. Updating."

                                $artifactUrl = "$api/IntegrationDesigntimeArtifacts(Id='$id',Version='active')"

                                $body = @{
                                    Name = $existing.Name
                                    ArtifactContent = $artifactContent
                                } | ConvertTo-Json -Depth 10

                                Invoke-RestMethod `
                                    -Uri $artifactUrl `
                                    -Method PUT `
                                    -Headers $headers `
                                    -WebSession $session `
                                    -ContentType "application/json" `
                                    -Body $body | Out-Null

                                Write-Host "Updated successfully: $id"
                            }
                            else {

                                # ----------------------------------
                                # 6B. Check if ID exists elsewhere
                                # ----------------------------------

                                $globalUrl = "$api/IntegrationDesigntimeArtifacts(Id='$id',Version='active')"

                                $globalExists = $false

                                try {
                                    $globalArtifact = Invoke-RestMethod `
                                        -Uri $globalUrl `
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
                                        throw "Unable to check global artifact ID $id. HTTP $status"
                                    }
                                }

                                if ($globalExists) {
                                    throw "Artifact $id exists outside package $packageId. Stopping to avoid changing the wrong package."
                                }

                                # ----------------------------------
                                # 6C. Create new target artifact
                                # ----------------------------------

                                Write-Host "Creating new artifact in QA."

                                $body = @{
                                    Id = $id
                                    Name = $id
                                    PackageId = $packageId
                                    ArtifactContent = $artifactContent
                                } | ConvertTo-Json -Depth 10

                                Invoke-RestMethod `
                                    -Uri "$api/IntegrationDesigntimeArtifacts" `
                                    -Method POST `
                                    -Headers $headers `
                                    -WebSession $session `
                                    -ContentType "application/json" `
                                    -Body $body | Out-Null

                                Write-Host "Created successfully: $id"
                            }

                            $artifactContent = $null
                            $zipBytes = $null
                            $body = $null
                        }

                        # ----------------------------------
                        # 7. Verify all uploaded artifacts
                        # ----------------------------------

                        Write-Host "Checking artifacts in QA..."

                        $verify = Invoke-RestMethod `
                            -Uri $packageArtifactsUrl `
                            -Method GET `
                            -Headers $headers `
                            -WebSession $session

                        $qaResults = @()

                        if ($verify.d.results) {
                            $qaResults = @($verify.d.results)
                        }

                        foreach ($project in $projects) {

                            $found = $qaResults |
                                Where-Object { $_.Id -eq $project } |
                                Select-Object -First 1

                            if (!$found) {
                                throw "QA verification failed: $project not found."
                            }

                            Write-Host "VERIFIED IN QA: $($found.Id)"
                            Write-Host "PACKAGE: $($found.PackageId)"
                            Write-Host "VERSION: $($found.Version)"
                        }

                        Write-Host "All iFlows successfully verified in QA design time."

                        # Clear sensitive variables
                        $token = $null
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
            echo 'GitHub to QA design-time transfer completed successfully.'
            echo 'No runtime deployment was performed.'
        }

        failure {
            echo 'Pipeline failed. Check the failed stage and Console Output.'
        }
    }
}
