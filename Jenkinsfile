// CI: build once, test, scan, package immutable images, deploy to DEV.
// CD to QA/UAT/PROD is handed to uDeploy or Harness (set CD_TOOL), which promote the SAME image tag.
pipeline {
    agent { label 'java21-docker' }

    options {
        timestamps()
        timeout(time: 60, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '30'))
        disableConcurrentBuilds(abortPrevious: true)
    }

    parameters {
        choice(name: 'CD_TOOL', choices: ['harness', 'udeploy'], description: 'Tool that promotes the build past DEV')
    }

    environment {
        AWS_REGION   = 'us-east-1'
        ECR_REGISTRY = '111111111111.dkr.ecr.us-east-1.amazonaws.com'
        MODULES      = 'order-service inventory-service payment-service reconciliation-batch'
        GIT_SHORT    = "${env.GIT_COMMIT?.take(8)}"
        VERSION      = "1.0.${env.BUILD_NUMBER}-${env.GIT_COMMIT?.take(8)}"
    }

    stages {
        stage('Build & unit test') {
            steps {
                sh './gradlew clean build -PreleaseVersion=$VERSION --no-daemon'
            }
            post {
                always {
                    junit '**/build/test-results/test/*.xml'
                    recordCoverage(tools: [[parser: 'JACOCO', pattern: '**/build/reports/jacoco/test/jacocoTestReport.xml']])
                }
            }
        }

        stage('Integration test') {
            steps {
                // Testcontainers: real Postgres, Redis and Kafka on the build agent's Docker.
                sh './gradlew integrationTest -PreleaseVersion=$VERSION --no-daemon'
            }
            post { always { junit allowEmptyResults: true, testResults: '**/build/test-results/integrationTest/*.xml' } }
        }

        stage('Quality & security gates') {
            parallel {
                stage('SonarQube') {
                    steps {
                        withSonarQubeEnv('sonarqube') {
                            sh './gradlew sonar -Dsonar.projectKey=order-platform -Dsonar.projectVersion=$VERSION --no-daemon'
                        }
                        timeout(time: 10, unit: 'MINUTES') { waitForQualityGate abortPipeline: true }
                    }
                }
                stage('Coverage gate') {
                    steps { sh './gradlew jacocoTestCoverageVerification -PminCoverage=0.60 --no-daemon' }
                }
                stage('Veracode SCA/SAST') {
                    when { branch 'main' }
                    steps {
                        withCredentials([usernamePassword(credentialsId: 'veracode', usernameVariable: 'VID', passwordVariable: 'VKEY')]) {
                            sh 'zip -qr scan.zip */build/libs/*.jar'
                            veracode applicationName: 'order-platform', criticality: 'High', scanName: "$VERSION",
                                     uploadIncludesPattern: 'scan.zip', vid: VID, vkey: VKEY, waitForScan: true,
                                     timeout: 30, canFailJob: true
                        }
                    }
                }
            }
        }

        stage('Build & push images') {
            when { anyOf { branch 'main'; branch 'release/*' } }
            steps {
                sh 'aws ecr get-login-password --region $AWS_REGION | docker login --username AWS --password-stdin $ECR_REGISTRY'
                script {
                    def builds = [:]
                    env.MODULES.split(' ').each { m ->
                        builds[m] = {
                            sh """
                              docker build --build-arg MODULE=${m} \
                                --label org.opencontainers.image.revision=${env.GIT_COMMIT} \
                                --label org.opencontainers.image.version=${env.VERSION} \
                                -t ${env.ECR_REGISTRY}/${m}:${env.VERSION} .
                              docker push ${env.ECR_REGISTRY}/${m}:${env.VERSION}
                            """
                        }
                    }
                    parallel builds
                }
                // Image scanning: ECR scan-on-push results are checked before deploying.
                sh 'for m in $MODULES; do aws ecr wait image-scan-complete --repository-name $m --image-id imageTag=$VERSION; done'
            }
        }

        stage('Deploy DEV') {
            when { branch 'main' }
            steps {
                dir('deploy/k8s/overlays/dev') {
                    sh 'for m in $MODULES; do kustomize edit set image $m=$ECR_REGISTRY/$m:$VERSION; done'
                }
                sh '''
                  aws eks update-kubeconfig --name orders-dev --region $AWS_REGION
                  kubectl apply -k deploy/k8s/overlays/dev
                  for d in order-service inventory-service payment-service; do
                    kubectl -n order-platform-dev rollout status deploy/$d --timeout=300s
                  done
                '''
            }
        }

        stage('Smoke test DEV') {
            when { branch 'main' }
            steps {
                sh '''
                  BASE=https://orders-dev.internal.example.com
                  curl -fsS $BASE/actuator/health/readiness
                  curl -fsS -o /dev/null -w "%{http_code}\\n" -X POST $BASE/api/v1/orders \
                    -H "Content-Type: application/json" -H "Idempotency-Key: smoke-$BUILD_NUMBER-$GIT_SHORT" \
                    -d '{"customerId":"smoke","currency":"USD","lines":[{"sku":"SKU-1001","quantity":1,"unitPrice":1.00}]}' | grep -E "201|200"
                '''
            }
        }

        stage('Hand off to CD') {
            when { branch 'main' }
            steps {
                script {
                    if (params.CD_TOOL == 'udeploy') {
                        // One uDeploy component per service; the version name is the image tag.
                        withCredentials([string(credentialsId: 'udeploy-token', variable: 'DS_AUTH_TOKEN')]) {
                            sh '''
                              for m in $MODULES; do
                                udclient -weburl https://udeploy.example.com createVersion -component $m -name $VERSION
                                udclient -weburl https://udeploy.example.com addVersionStatus -component $m -version $VERSION -status "CI Passed"
                              done
                              udclient -weburl https://udeploy.example.com requestApplicationProcess ci/udeploy/deploy-qa.json
                            '''
                        }
                    } else {
                        withCredentials([string(credentialsId: 'harness-webhook', variable: 'HARNESS_URL')]) {
                            sh 'curl -fsS -X POST "$HARNESS_URL" -H "Content-Type: application/json" -d "{\\"version\\":\\"$VERSION\\",\\"commit\\":\\"$GIT_COMMIT\\"}"'
                        }
                    }
                }
            }
        }
    }

    post {
        failure { slackSend channel: '#order-platform-builds', color: 'danger', message: "FAILED ${env.JOB_NAME} #${env.BUILD_NUMBER} (${env.VERSION}) ${env.BUILD_URL}" }
        fixed   { slackSend channel: '#order-platform-builds', color: 'good',   message: "FIXED ${env.JOB_NAME} #${env.BUILD_NUMBER}" }
        always  { archiveArtifacts artifacts: '**/build/reports/**', allowEmptyArchive: true }
    }
}
