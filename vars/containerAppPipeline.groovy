def call(Map config = [:]) {

    /*
     * ============================================================
     * APPLICATION / SOURCE CONFIGURATION
     * ============================================================
     */

    def appName = config.appName

    def sourceRepo = config.sourceRepo
    def sourceBranch = config.get('sourceBranch', 'main')
    def sourceCredentialsId = config.get('sourceCredentialsId', '')

    /*
     * ============================================================
     * IMAGE / REGISTRY CONFIGURATION
     * ============================================================
     */

    def image = config.image

    def registryHost = config.get(
        'registryHost',
        'docker.io'
    )

    def registryCredentialsId = config.registryCredentialsId

    def dockerfile = config.get(
        'dockerfile',
        'Dockerfile'
    )

    def buildContext = config.get(
        'buildContext',
        '.'
    )

    /*
     * ============================================================
     * OKD DEPLOYMENT CONFIGURATION
     * ============================================================
     */

    def deployNamespace = config.deployNamespace

    def deploymentName = config.get(
        'deploymentName',
        appName
    )

    def deploymentContainer = config.get(
        'deploymentContainer',
        appName
    )

    /*
     * ============================================================
     * AGENT IMAGES
     * ============================================================
     */

    def buildahImage = config.get(
        'buildahImage',
        'quay.io/containers/buildah:v1.43.1'
    )

    def ocImage = config.get(
        'ocImage',
        'quay.io/okd/scos-content@sha256:24c90b37d65ae20fb14c97380cced7e7a36da4b80259b596369b0b849ceb869a'
    )

    /*
     * ============================================================
     * PIPELINE
     * ============================================================
     */

    pipeline {

        /*
         * Tidak pakai global agent.
         *
         * CI dan CD akan punya Kubernetes Agent Pod masing-masing.
         */
        agent none

        options {

            /*
             * Satu aplikasi tidak boleh punya dua pipeline
             * berjalan bersamaan.
             */
            disableConcurrentBuilds()

            /*
             * Timestamp Jenkins console output.
             */
            timestamps()

            /*
             * Timeout keseluruhan pipeline.
             */
            timeout(
                time: 30,
                unit: 'MINUTES'
            )

            /*
             * Jangan otomatis checkout repo Jenkinsfile.
             *
             * Source application akan kita checkout manual.
             */
            skipDefaultCheckout(true)
        }

        stages {

            /*
             * ====================================================
             * CI
             * ====================================================
             *
             * Jenkins akan membuat:
             *
             *   CI Agent Pod
             *   ├── jnlp
             *   └── buildah
             *
             * Pod ini digunakan dari checkout, build sampai image push.
             */

            stage('CI') {

                agent {
                    kubernetes {

                        defaultContainer 'buildah'

                        yaml """
apiVersion: v1
kind: Pod
spec:

  securityContext:
    runAsUser: 1000
    runAsGroup: 1000
    fsGroup: 1000

  containers:

    - name: buildah

      image: ${buildahImage}

      command:
        - cat

      resources:
        requests:
          cpu: "250m"
          memory: "512Mi"
        limits:
          cpu: "2"
          memory: "2Gi"

      tty: true

      securityContext:
        privileged: true
        runAsUser: 0
"""
                    }
                }

                stages {

                    /*
                     * --------------------------------------------
                     * VALIDATE CONFIGURATION
                     * --------------------------------------------
                     */

                    stage('Validate Configuration') {

                        steps {

                            script {

                                if (!appName) {
                                    error(
                                        'appName is required'
                                    )
                                }

                                if (!sourceRepo) {
                                    error(
                                        'sourceRepo is required'
                                    )
                                }

                                if (!image) {
                                    error(
                                        'image is required'
                                    )
                                }

                                if (!registryCredentialsId) {
                                    error(
                                        'registryCredentialsId is required'
                                    )
                                }

                                if (!deployNamespace) {
                                    error(
                                        'deployNamespace is required'
                                    )
                                }

                                if (!deploymentName) {
                                    error(
                                        'deploymentName is required'
                                    )
                                }

                                if (!deploymentContainer) {
                                    error(
                                        'deploymentContainer is required'
                                    )
                                }
                            }
                        }
                    }

                    /*
                     * --------------------------------------------
                     * CHECKOUT APPLICATION SOURCE
                     * --------------------------------------------
                     */

                    stage('Checkout Source') {

                        steps {

                            script {

                                def scmConfig = [

                                    $class: 'GitSCM',

                                    branches: [[
                                        name: "*/${sourceBranch}"
                                    ]],

                                    userRemoteConfigs: [[
                                        url: sourceRepo
                                    ]]
                                ]

                                /*
                                 * Credentials hanya ditambahkan
                                 * kalau memang diperlukan.
                                 */
                                if (sourceCredentialsId) {

                                    scmConfig
                                        .userRemoteConfigs[0]
                                        .credentialsId =
                                            sourceCredentialsId
                                }

                                checkout(
                                    scmConfig
                                )
                            }
                        }
                    }

                    /*
                     * --------------------------------------------
                     * VALIDATE SOURCE
                     * --------------------------------------------
                     */

                    stage('Validate Source') {

                        steps {

                            sh """
                                set -eu

                                echo "===== SOURCE INFORMATION ====="

                                echo "Application : ${appName}"
                                echo "Repository  : ${sourceRepo}"
                                echo "Branch      : ${sourceBranch}"

                                echo ""

                                echo "===== WORKSPACE ====="

                                pwd

                                echo ""

                                ls -la

                                echo ""

                                echo "===== DOCKERFILE ====="

                                if [ ! -f "${dockerfile}" ]; then
                                    echo "ERROR: Dockerfile not found:"
                                    echo "${dockerfile}"
                                    exit 1
                                fi

                                echo "Dockerfile found: ${dockerfile}"
                            """
                        }
                    }

                    /*
                     * --------------------------------------------
                     * BUILD CONTAINER IMAGE
                     * --------------------------------------------
                     */

                    stage('Build Image') {

                        steps {

                            sh """
                                set -eux

                                echo "===== BUILD IMAGE ====="

                                buildah bud \
                                    -f "${dockerfile}" \
                                    -t "${image}:latest" \
                                    "${buildContext}"
                            """
                        }
                    }

                    /*
                     * --------------------------------------------
                     * PUSH IMAGE
                     * --------------------------------------------
                     */

                    stage('Push Image') {

                        steps {

                            withCredentials([

                                usernamePassword(

                                    credentialsId:
                                        registryCredentialsId,

                                    usernameVariable:
                                        'REGISTRY_USERNAME',

                                    passwordVariable:
                                        'REGISTRY_PASSWORD'
                                )
                            ]) {

                                sh """
                                    echo "===== REGISTRY LOGIN ====="

                                    set +x

                                    printf '%s' "\$REGISTRY_PASSWORD" | \
                                        buildah login \
                                        --username "\$REGISTRY_USERNAME" \
                                        --password-stdin \
                                        "${registryHost}"

                                    set -x

                                    echo "===== PUSH IMAGE ====="

                                    buildah push \
                                        "${image}:latest"
                                """
                            }
                        }
                    }
                }
            }

            /*
             * ====================================================
             * CD
             * ====================================================
             *
             * CI Pod selesai.
             *
             * Jenkins kemudian membuat Pod BARU:
             *
             *   CD Agent Pod
             *   ├── jnlp
             *   └── oc
             *
             * ServiceAccount:
             *
             *   jenkins/jenkins-deployer
             *
             * Pod ini NON-PRIVILEGED.
             */

            stage('Deploy to OKD') {

                agent {
                    kubernetes {

                        defaultContainer 'oc'

                        yaml """
apiVersion: v1
kind: Pod
spec:

  serviceAccountName: jenkins-deployer

  securityContext:

    runAsNonRoot: true

    runAsUser: 1000

    runAsGroup: 1000

    fsGroup: 1000

    seccompProfile:
      type: RuntimeDefault

  containers:

    - name: oc

      image: ${ocImage}

      command:
        - cat

      tty: true

      env:

        - name: HOME
          value: /tmp

        - name: KUBECONFIG
          value: /tmp/kubeconfig

      securityContext:

        allowPrivilegeEscalation: false

        capabilities:
          drop:
            - ALL
"""
                    }
                }

                steps {

                    /*
                     * --------------------------------------------
                     * AUTHENTICATION
                     * --------------------------------------------
                     *
                     * Kita menggunakan ServiceAccount token yang
                     * otomatis mounted oleh Kubernetes.
                     *
                     * Token tidak boleh tercetak ke Jenkins log.
                     */

                    sh """
                        set -eu

                        echo "===== OKD AUTHENTICATION ====="

                        set +x

                        TOKEN="\$(cat \
                            /var/run/secrets/kubernetes.io/serviceaccount/token)"

                        CA="/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"

                        oc login \
                            https://kubernetes.default.svc \
                            --token="\$TOKEN" \
                            --certificate-authority="\$CA" \
                            >/dev/null

                        unset TOKEN

                        set -x

                        echo ""

                        echo "===== DEPLOYMENT IDENTITY ====="

                        oc whoami

                        echo ""

                        echo "===== DEPLOYMENT TARGET ====="

                        echo "Namespace  : ${deployNamespace}"
                        echo "Deployment : ${deploymentName}"
                        echo "Container  : ${deploymentContainer}"
                        echo "Image      : ${image}:latest"

                        echo ""

                        echo "===== VERIFY RBAC ====="

                        if ! oc auth can-i \
                            patch deployments \
                            -n "${deployNamespace}" \
                            | grep -q '^yes\$'; then

                            echo "ERROR:"
                            echo "jenkins-deployer cannot patch deployments"
                            echo "in namespace ${deployNamespace}"

                            exit 1
                        fi

                        echo "RBAC check: OK"

                        echo ""

                        echo "===== CURRENT DEPLOYMENT ====="

                        oc get \
                            deployment/${deploymentName} \
                            -n "${deployNamespace}"

                        echo ""

                        echo "===== SET IMAGE ====="

                        oc set image \
                            deployment/${deploymentName} \
                            ${deploymentContainer}=${image}:latest \
                            -n "${deployNamespace}"

                        echo ""

                        echo "===== FORCE NEW ROLLOUT ====="

                        oc rollout restart \
                            deployment/${deploymentName} \
                            -n "${deployNamespace}"

                        echo ""

                        echo "===== WAIT FOR ROLLOUT ====="

                        oc rollout status \
                            deployment/${deploymentName} \
                            -n "${deployNamespace}" \
                            --timeout=300s

                        echo ""

                        echo "===== DEPLOYED IMAGE ====="

                        oc get \
                            deployment/${deploymentName} \
                            -n "${deployNamespace}" \
                            -o jsonpath='{.spec.template.spec.containers[?(@.name=="${deploymentContainer}")].image}{"\\\\n"}'

                        echo ""

                        echo "===== DEPLOYMENT COMPLETE ====="
                    """
                }
            }
        }

        /*
         * ========================================================
         * PIPELINE RESULT
         * ========================================================
         */

        post {

            success {

                echo """
============================================================
PIPELINE SUCCESS
Application : ${appName}
Image       : ${image}:latest
Namespace   : ${deployNamespace}
Deployment  : ${deploymentName}
============================================================
"""
            }

            failure {

                echo """
============================================================
PIPELINE FAILED
Application : ${appName}
Namespace   : ${deployNamespace}
Deployment  : ${deploymentName}
============================================================
"""
            }
        }
    }
}
