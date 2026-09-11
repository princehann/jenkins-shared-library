def call(Map config = [:]) {

    def appName = config.appName
    def sourceRepo = config.sourceRepo
    def sourceBranch = config.get('sourceBranch', 'main')
    def sourceCredentialsId = config.get('sourceCredentialsId', '')

    def registryHost = config.registryHost
    def registryCredentialsId = config.registryCredentialsId

    def image = config.image
    def dockerfile = config.get('dockerfile', 'Dockerfile')
    def buildContext = config.get('buildContext', '.')

    def buildahImage = config.get(
        'buildahImage',
        'quay.io/containers/buildah:v1.43.1'
    )

    pipeline {
        agent {
            kubernetes {
                yaml """
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: buildah
      image: ${buildahImage}
      command:
        - cat
      tty: true
      securityContext:
        privileged: true
        runAsUser: 0
"""
            }
        }

        options {
            disableConcurrentBuilds()
            timestamps()
            timeout(time: 30, unit: 'MINUTES')
            skipDefaultCheckout(true)
        }

        stages {

            stage('Validate Configuration') {
                steps {
                    script {
                        if (!appName) {
                            error('appName is required')
                        }

                        if (!sourceRepo) {
                            error('sourceRepo is required')
                        }

                        if (!image) {
                            error('image is required')
                        }

                        if (!registryHost) {
                           error('registryHost is required')
                        }

                        if (!registryCredentialsId) {
                           error('registryCredentialsId is required')
                        }
                    }
                }
            }

            stage('Checkout Source') {
                steps {
                    script {
                        if (sourceCredentialsId) {
                            git branch: sourceBranch,
                                credentialsId: sourceCredentialsId,
                                url: sourceRepo
                        } else {
                            git branch: sourceBranch,
                                url: sourceRepo
                        }
                    }
                }
            }

            stage('Validate Source') {
                steps {
                    sh """
                        echo "===== APPLICATION ====="
                        echo '${appName}'

                        echo "===== WORKSPACE ====="
                        pwd

                        echo "===== GIT COMMIT ====="
                        git rev-parse HEAD

                        echo "===== SOURCE ====="
                        ls -lah

                        echo "===== DOCKERFILE ====="
                        test -f '${dockerfile}'
                    """
                }
            }

            stage('Build Image') {
                steps {
                    container('buildah') {
                        sh """
                            echo "===== BUILDAH VERSION ====="
                            buildah version

                            echo "===== BUILD START ====="

                            buildah bud \
                                -f '${dockerfile}' \
                                -t '${image}:latest' \
                                '${buildContext}'

                            echo "===== BUILT IMAGE ====="
                            buildah images
                        """
                    }
                }
            }

	    stage('Push Image') {
	        steps {
		    container('buildah') {
		        withCredentials([
			    usernamePassword(
			        credentialsId: registryCredentialsId,
			        usernameVariable: 'REGISTRY_USERNAME',
			        passwordVariable: 'REGISTRY_PASSWORD'
			    )
		        ]) {
			   sh '''
			        echo "===== REGISTRY LOGIN ====="

			        printf '%s' "$REGISTRY_PASSWORD" | \
				    buildah login \
				        --username "$REGISTRY_USERNAME" \
				        --password-stdin \
				        ''' + "'${registryHost}'" + '''

			        echo "===== PUSH IMAGE ====="

			        buildah push \
				    ''' + "'${image}:latest'" + '''

			        echo "===== PUSH COMPLETE ====="
			    '''
		        }
		    }
	        }
	    }

        }

        post {
            success {
                echo "Pipeline SUCCESS: ${appName}"
            }

            failure {
                echo "Pipeline FAILURE: ${appName}"
            }
        }
    }
}
