#!groovy
//
// Jenkins-begin.groovy - Jenkins script for initiating the Zenoss product build process.
//
// The Jenkins job parameters for this script are:
//
//    BRANCH            - the name of the GIT branch to build from.
//    GIT_CREDENTIAL_ID - the UUID of the Jenkins GIT credentials used to checkout stuff from github
//    MATURITY          - the image maturity level (e.g. 'unstable', 'testing', 'stable')
//    PINNED            - fail build if the artifacts are not pinned to explicitly released versions
//    CHECK_LATEST      - check if pinned versions are the latest known version. Only works for zenpacks
//    RUN_TESTS         - whether to run the Test image pipeline stage
//
node('build-zenoss-product') {
    // To avoid naming confusion with downstream jobs that have their own BUILD_NUMBER variables,
    // define 'PRODUCT_BUILD_NUMBER' as the parameter name that will be used by all downstream
    // jobs to identify a particular execution of the build pipeline.
    def TARGET_PRODUCT = "cse"
    def PRODUCT_BUILD_NUMBER = env.BUILD_NUMBER
    def JENKINS_URL = env.JENKINS_URL  // e.g. http://<server>/
    def JOB_NAME = env.JOB_NAME        // e.g. product-assembly/support-6.0.x/begin
    def JOB_URL = env.JOB_URL          // e.g. ${JENKINS_URL}job/${JOB_NAME}/
    def BUILD_URL = env.BUILD_URL      // e.g. ${JOB_URL}${PRODUCT_BUILD_NUMBER}
    def BRANCH = params.BRANCH         // e.g. develop
    def MATURITY = params.MATURITY     // e.g. unstable
    def PINNED = params.PINNED         // e.g. "true", "false"
    def CHECK_LATEST = params.CHECK_LATEST    // e.g. "true", "false"
    def IGNORE_TEST_IMAGE_FAILURE = params.IGNORE_TEST_IMAGE_FAILURE   // e.g. "true", "false"
    def NOTIFY_TEAM = "false"
    def TEAM_CHANNEL = ""

    println("Build parameters:")
    println("BRANCH = ${BRANCH}")
    println("MATURITY = ${MATURITY}")
    println("PINNED = ${PINNED}")
    println("CHECK_LATEST = ${CHECK_LATEST}")
    println("IGNORE_TEST_IMAGE_FAILURE = ${IGNORE_TEST_IMAGE_FAILURE}")

    currentBuild.displayName = "product build #${PRODUCT_BUILD_NUMBER} @${env.NODE_NAME}"

    try {
        stage('Checkout product-assembly repo') {
            // Make sure we start in a clean directory to ensure a fresh git clone
            deleteDir()
            git branch: BRANCH, credentialsId: GIT_CREDENTIAL_ID, url: 'https://github.com/zenoss/product-assembly'

            // Record the current git commit sha in the variable 'GIT_SHA'
            sh("git rev-parse HEAD >git_sha.id")
            GIT_SHA = readFile('git_sha.id').trim()
            println("Building from git commit='${GIT_SHA}' on branch ${BRANCH} for MATURITY='${MATURITY}'")

            if (PINNED == "true") {
                checkLatest = ""
                if (CHECK_LATEST == 'true') {
                    checkLatest = " --check-latest"
                }
                // make sure SVCDEF_GIT_REF and IMPACT_VERSION have the form x.x.x, where x is an integer
                println "Checking for pinned versions in versions.mk"
                sh("grep '^SVCDEF_GIT_REF=[0-9]\\{1,\\}\\.[0-9]\\{1,\\}\\.[0-9]\\{1,\\}' versions.mk")
                sh("grep '^IMPACT_VERSION=[0-9]\\{1,\\}\\.[0-9]\\{1,\\}\\.[0-9]\\{1,\\}' versions.mk")

                // make sure ZING_API_PROXY_VERSION and ZING_CONNECTOR_VERSION have the form YYYY-MM-DD-N
                sh("grep '^ZING_API_PROXY_VERSION=20[1-4][0-9]\\-[0-1][0-9]\\-[0-3][0-9]\\-[0-9]' versions.mk")
                sh("grep '^ZING_CONNECTOR_VERSION=20[1-4][0-9]\\-[0-1][0-9]\\-[0-3][0-9]\\-[0-9]' versions.mk")

                // Verify that everything in the component and ZP manifests are pinned
                println "Checking for pinned versions in component_versions.json"
                sh("./artifact_download.py component_versions.json --pinned" + checkLatest)

                println "Checking for pinned versions in zenpack_versions.json"
                sh("./artifact_download.py zenpack_versions.json --pinned" + checkLatest)
            }
        }

        stage('Build product-base') {
            dir("product-base") {
                withEnv(["MATURITY=${MATURITY}", "BUILD_NUMBER=${PRODUCT_BUILD_NUMBER}"]) {
                    sh("make clean build")
                }
            }
        }

        stage('Build mariadb-base') {
            dir("mariadb-base") {
                withEnv(["MATURITY=${MATURITY}", "BUILD_NUMBER=${PRODUCT_BUILD_NUMBER}"]) {
                    sh("make clean build")
                }
            }
        }

        def SVCDEF_GIT_REF = ""
        def ZENOSS_VERSION = ""
        def IMAGE_PROJECT = ""
        def productImageID = ""
        def productImageTag = ""
        def mariadbImageID = ""
        def mariadbImageTag = ""

        stage('Download zenpacks') {
            // Get the values of various versions out of the versions.mk file for use in later stages
            def versionProps = readProperties file: 'versions.mk'
            SVCDEF_GIT_REF = versionProps['SVCDEF_GIT_REF']
            ZENOSS_VERSION = versionProps['VERSION']
            SHORT_VERSION = versionProps['SHORT_VERSION']
            IMAGE_PROJECT = versionProps['IMAGE_PROJECT']
            echo "SVCDEF_GIT_REF=${SVCDEF_GIT_REF}"
            echo "ZENOSS_VERSION=${ZENOSS_VERSION}"

            // Make the target product
            dir("${TARGET_PRODUCT}") {
                withEnv(["MATURITY=${MATURITY}", "BUILD_NUMBER=${PRODUCT_BUILD_NUMBER}"]) {
                    sh("make clean build-deps")
                }
            }
        }

        stage('Build Images') {

            dir("${TARGET_PRODUCT}") {
                withEnv(["MATURITY=${MATURITY}", "BUILD_NUMBER=${PRODUCT_BUILD_NUMBER}"]) {
                    productImageTag = sh(returnStdout: true, script: "make product-image-tag").trim()
                    productImageID = sh(returnStdout: true, script: "make product-image-id").trim()
                    mariadbImageTag = sh(returnStdout: true, script: "make mariadb-image-tag").trim()
                    mariadbImageID = sh(returnStdout: true, script: "make mariadb-image-id").trim()
                    echo "productImageID=${productImageID}"

                    sh("make build")
                    sh("make getDownloadLogs")
                }
            }
            def includePattern = TARGET_PRODUCT + '/*artifact.log'
            archive includes: includePattern
        }

        stage('Test Product Image') {
            dir("${TARGET_PRODUCT}") {
                withEnv(["MATURITY=${MATURITY}", "BUILD_NUMBER=${PRODUCT_BUILD_NUMBER}"]) {
                    sh(
                        script: "make run-tests",
                        returnStatus: IGNORE_TEST_IMAGE_FAILURE.toBoolean()
                    )
                }
            }
        }

        stage('Push Images') {
            docker.withRegistry('https://gcr.io', 'gcr:zing-registry-188222') {
                productImage = docker.image(productImageID)
                productImage.push()
                if (PINNED == "true") {
                    //add a pinned tag so we know if this image is viable for promotion
                    productImage.push("${productImageTag}-pinned")
                }
                mariadbImage = docker.image(mariadbImageID)
                mariadbImage.push()
                if (PINNED == "true") {
                    //add a pinned tag so we know if this image is viable for promotion
                    mariadbImage.push("${mariadbImageTag}-pinned")
                }
            }
        }

        stage("Build service template package") {
            // Run the checkout in a separate directory.
            dir("svcdefs") {
                // We have to clean it ourselves, because Jenkins doesn't (apparently)
                sh("rm -rf build")
                sh("mkdir -p build/zenoss-service")
                dir("build/zenoss-service") {
                    echo "Cloning zenoss-service - ${SVCDEF_GIT_REF} with credentialsId=${GIT_CREDENTIAL_ID}"
                    // NOTE: The 'master' branch name here is only used to clone the github repo.
                    //       The next checkout command will align the build with the correct target revision.
                    git(
                        branch: 'master',
                        credentialsId: '${GIT_CREDENTIAL_ID}',
                        url: 'https://github.com/zenoss/zenoss-service.git'
                    )
                    sh("git checkout ${SVCDEF_GIT_REF}")
                    // Log the current SHA of zenoss-service so, when building from a branch,
                    // we know exactly which commit went into a particular build
                    sh("echo zenoss/zenoss-service git SHA = \$(git rev-parse HEAD)")
                }
                // Note that SVDEF_GIT_READY=true tells the make to NOT attempt a git operation on its own
                // because we need to use Jenkins credentials instead
                withEnv([
                    "BUILD_NUMBER=${PRODUCT_BUILD_NUMBER}",
                    "IMAGE_NUMBER=${PRODUCT_BUILD_NUMBER}",
                    "MATURITY=${MATURITY}",
                    "SVCDEF_GIT_READY=true",
                    "TARGET_PRODUCT=${TARGET_PRODUCT}"
                ]) {
                    sh("make build")
                }
            }
            sh("mkdir -p artifacts")
            sh("cp svcdefs/build/zenoss-service/output/*.json artifacts/.")
            dir("artifacts") {
                sh("for file in *json; do tar -cvzf \$file.tgz \$file; done")
            }
            archive includes: 'artifacts/*.json*'
        }

        stage('Upload service template package') {
            googleStorageUpload(
                bucket: "gs://cse_artifacts/${TARGET_PRODUCT}/${MATURITY}/${ZENOSS_VERSION}/${PRODUCT_BUILD_NUMBER}",
                credentialsId: 'zing-registry-188222',
                pathPrefix: 'artifacts/',
                pattern: 'artifacts/*tgz'
            )
        }

        stage('3rd-party Python packages check') {
            // Generate snapshot of Python packages
            productImage.inside {
                sh 'pip list --format json > 3rd-party.json'
            }

            // Archive Python packages list
            archive includes: '3rd-party.json'

            try {
                // Copy 3rd-party dependencies from last successful build
                copyArtifacts(
                    projectName: "${JOB_NAME}",
                    filter: '3rd-party.json',
                    target: 'lastSuccessful',
                    flatten: true,
                    selector: lastSuccessful()
                )

                // Generate differences report
                sh 'python compare_pip.py -o 3rd-party-difference.log lastSuccessful/3rd-party.json 3rd-party.json'

                // Archive report
                archive includes: '3rd-party-difference.log'

            } catch (err) {}
        }

    } catch (err) {
        echo "Job failed with the following error: ${err}"
        if (err.toString().contains("completed with status ABORTED") ||
                err.toString().contains("hudson.AbortException: script returned exit code 2")) {
            currentBuild.result = 'ABORTED'
        } else {
            currentBuild.result = 'FAILED'
        }

        if (NOTIFY_TEAM == "true" && TEAM_CHANNEL != "") {
            slackSend(
                color: 'warning',
                channel: '#${TEAM_CHANNEL}',
                message: "CSE Build Failed: ${env.JOB_NAME} Build #${env.BUILD_NUMBER} ${env.BUILD_URL}"
            )
        }
        error "Job failed with the following error: ${err}"
    } finally {
        withEnv(["MATURITY=${MATURITY}", "BUILD_NUMBER=${PRODUCT_BUILD_NUMBER}"]) {
            dir("${TARGET_PRODUCT}") {
                sh("make clean")
            }
            dir("product-base") {
                sh("make clean")
            }
            dir("mariadb-base") {
                sh("make clean")
            }
        }
    }
}
