import com.eb.lib.jobUtils
import com.eb.lib.gerritUtils
import com.eb.lib.commonEnvironment
import com.eb.lib.PipelineEnvironmentException

def call(Map parameters) {
    /***************************** read the configurations  *****************************/
    def jobUtils = new jobUtils()
    def commonEnvironment = new commonEnvironment()
    configMap = commonEnvironment.setupConfiguration(parameters.projectConfig)
    pipeline {
        this.options {
            copyArtifactPermission('*') // Add the copyArtifactPermission('*') option
            // set timestamps
            timestamps()
        }
        // this.agent { label "Linux_BuildBot" }
        this.parameters {
            string(name: 'SONAR_SOURCE_VOLUME', defaultValue: '', description: 'Sonar Source Volume')
            string(name: 'SONAR_MODULES', defaultValue: '', description: 'Sonar Modules')
            string(name: 'SONAR_SERVER', defaultValue: 'https://sonarqube-pj-cos-1-test.ebgroup.elektrobit.com', description: 'Sonar Server make sure there is noe / at the end of the URL')
            string(name: 'SONAR_PROJECTKEY_PREFIX', defaultValue: '', description: 'Sonar Project Prefix')
            booleanParam(name: 'VERBOSE', defaultValue: false, description: 'When checked, "bash -x" is run.')
            booleanParam(name: 'SKIP_BUILD', defaultValue: false, description: 'When checked, skip the build.')
            booleanParam(name: 'SKIP_SCAN', defaultValue: false, description: 'When checked, skip the scan.')
            booleanParam(name: 'SKIP_CLEANUP', defaultValue: true, description: 'When checked, skips the cleanup.')
            string(name: 'SNAPSHOT_NAME', defaultValue: '', description: 'Used to set sonar.projectVersion')
            string(name: 'STACK_TARGET_LABEL', defaultValue: 'Linux_BuildBot', description: 'Where to run the build?')
            string(name: 'PIPELINE_TYPE', defaultValue: '', description: 'PIPELINE_TYPE to describe the type of pipeline from which this job is triggered')
            string(name: 'BUILD_TARGET', defaultValue: '', description: 'QNX build target')
            string(name: 'GERRIT_BRANCH', defaultValue: '', description: 'Provide GERRIT_BRANCH')
            string(name: 'GERRIT_REFSPEC', defaultValue: '', description: 'Provide GERRIT_REFSPEC')
            string(name: 'GERRIT_CHANGE_NUMBER', defaultValue: '', description: 'Provide GERRIT_CHANGE_NUMBER')
            string(name: 'GERRIT_PATCHSET_NUMBER', defaultValue: '', description: 'Provide GERRIT_PATCHSET_NUMBER')
        }
        this.agent { node { label STACK_TARGET_LABEL } }
        this.environment {
            CUSTOM_WORKSPACE = "${SONAR_SOURCE_VOLUME}/sonarqube_jenkins_workspace"
            SCRIPT_ARGS = ' '
        }
        stages {
            stage('Prepare_Environment') {
                steps {
                    script {
                        commonEnvironment.printConfiguration(configMap)
                        def descriptionSuffix = PIPELINE_TYPE == "verify"?"${SONAR_MODULES}":"${GERRIT_BRANCH}-${SNAPSHOT_NAME}"
                        currentBuild.description = "${PIPELINE_TYPE}-${descriptionSuffix}"
                        if (scm instanceof hudson.plugins.git.GitSCM) {
                            def gerritUrl = scm.userRemoteConfigs[0].url
                            println "gerritUrl: ${gerritUrl}"
                            def matcher = gerritUrl =~ /ssh:\/\/(?:[^@]+@)?([^:\/]+)/
                            if (matcher) {
                                env.GERRIT_NAME = matcher[0][1]
                            } else {
                                error("GERRIT_NAME cannot be empty")
                            }
                            println "GERRIT_NAME: ${env.GERRIT_NAME}"
                        } else {
                            error("SCM is not Git")
                        }
                        // if VERBOSE pass -vv to bitbake
                        if (VERBOSE) {
                            SCRIPT_ARGS = "-vv"
                        }
                    }
                }
            }
            stage('Prepare_image') {
                agent {
                    label STACK_TARGET_LABEL
                }
                steps {
                    //create custom workspace for the job and clone the repo
                    dir("${CUSTOM_WORKSPACE}") {
                        git branch: 'master', 
                            url: "ssh://${env.GERRIT_NAME}:29418/bri/scripts/sonarqube"
                    }
                    script {
                        // If DOCKER_IMAGE_ID from the parameters is empty the get the image from the config file
                        if ( env.DOCKER_IMAGE_ID?:'' == '') {
                            DOCKER_IMAGE_ID = """${configMap.docker?(configMap.docker.registry?:configMap.general.docker.registry)+'/'+configMap.docker.image+':'+configMap.docker.tag:configMap.general.docker.registry+'/'+configMap.general.docker.image+':'+configMap.general.docker.tag}"""
                            DEFAULT_DOCKER_ARGS = """${configMap.docker?jobUtils.runShell("echo ${configMap.docker.dargs}"):jobUtils.runShell("echo ${configMap.general.docker.dargs}")}"""
                        }
                        env.SQA_IMAGE = "sqa_image:latest"
                        echo "DOCKER_IMAGE_ID: ${DOCKER_IMAGE_ID}"
                        def userId = sh(returnStdout: true, script: 'id -u').trim()
                        def userName = sh(returnStdout: true, script: 'id -un').trim()
                        docker.build("${SQA_IMAGE}", "--build-arg FROM=${DOCKER_IMAGE_ID} --build-arg HOST_USER_ID=${userId} --build-arg HOST_USER_NAME=${userName} -f ${CUSTOM_WORKSPACE}/docker_env/Dockerfile ${CUSTOM_WORKSPACE}/docker_env")
                    }
                }
            }
            stage('Build') {
                // Skip the build if set explicitly in the parameters
                when {
                    expression { SKIP_BUILD != 'true' }
                }
                agent {
                    docker {
                        image env.SQA_IMAGE
                        label STACK_TARGET_LABEL
                        args  '-v /net:/net -v /etc/profile.d:/etc/profile.d -v \${HOME}:\${HOME} -v /ccache:/ccache -v \${SONAR_SOURCE_VOLUME}:/ssd/jenkins/workdir -v \${CUSTOM_WORKSPACE}:\${CUSTOM_WORKSPACE} '
                    }
                }
                steps {
                    script {
                        // if SONAR_MODULES is empty then get the modules from the workspace
                        if (SONAR_MODULES == '') {
                            SONAR_MODULES = jobUtils.runShell("find /ssd/jenkins/workdir/meta-sonarqube/ -name *.bbappend -exec basename {} .bbappend \\; | tr '\\n' ' '")
                        }
                        sh """
                            #!/bin/bash
                            set -e
                            cd /ssd/jenkins/workdir
                            export BUILD_DIR=workdir
                            ./meta-distro-common/scripts/setup_buildenv.py -d base-qnx -b workdir -m  ${BUILD_TARGET}  -a meta-sw-shm -a meta-sonarqube
                            source poky/oe-init-build-env  workdir
                            echo \$sq_modules
                            bitbake ${SONAR_MODULES} -c cleansstates  || true
                            bitbake ${SONAR_MODULES} -c clean || true
                            bitbake ${SONAR_MODULES} -c compile  || true
                            """
                    }
                }
            }
            stage('SonarQube_Analysis') {
                // Skip the scan if set explicitly in the parameters
                when {
                    expression { SKIP_SCAN != 'true' }
                }
                agent {
                    docker {
                        image env.SQA_IMAGE
                        label STACK_TARGET_LABEL
                        args  '-v /net:/net -v /etc/profile.d:/etc/profile.d -v \${HOME}:\${HOME} -v /ccache:/ccache -v \${SONAR_SOURCE_VOLUME}:/ssd/jenkins/workdir -v \${CUSTOM_WORKSPACE}:\${CUSTOM_WORKSPACE} '
                    }
                }
                steps {
                    script {
                        if (PIPELINE_TYPE == "verify") {
                            // Run sonarToGerrit for each module
                            for (module in SONAR_MODULES.split(" ")) {
                                module_name = module.split(":")[0]
                                module_refspec = module.split(":")[1]
                                println "==========> module_name: ${module_name} -----> ${module_refspec}"
                                def sqa_report = "${module_name.replaceAll('/', '_')}_sonar-task.txt"
                                if (module_refspec != "") {
                                    env.GERRIT_REFSPEC = module_refspec
                                    env.GERRIT_CHANGE_NUMBER = module_refspec.split("/")[3]
                                    env.GERRIT_PATCHSET_NUMBER = module_refspec.split("/")[4]
                                    withSonarQubeEnv(SONAR_SERVER) {
                                        sh """
                                            #!/bin/bash
                                            set -e
                                            cd /ssd/jenkins/workdir
                                            export BUILD_DIR=workdir
                                            ./meta-distro-common/scripts/setup_buildenv.py -d base-qnx -b workdir -m  ${BUILD_TARGET}  -a meta-sw-shm -a meta-sonarqube
                                            source poky/oe-init-build-env  workdir
                                            echo \$sq_modules
                                            >extra.conf
                                            # write sonarqube additional properties to the extra.conf file and pass it to bitbake
                                            echo "GERRIT_BRANCH=\"${GERRIT_BRANCH}\"" >> extra.conf
                                            echo "GERRIT_REFSPEC=\"${GERRIT_REFSPEC}\"" >> extra.conf
                                            echo "GERRIT_CHANGE_NUMBER=\"${GERRIT_CHANGE_NUMBER}\"" >> extra.conf
                                            echo "GERRIT_PATCHSET_NUMBER=\"${GERRIT_PATCHSET_NUMBER}\"" >> extra.conf
                                            bitbake ${module_name} -c sonarqube ${SCRIPT_ARGS} --postread=extra.conf || true
                                            find  /ssd/jenkins/workdir/workdir/ -name "report-task.txt" -exec cp {} ${WORKSPACE} \\;
                                            cd -
                                        """
                                    }
                                    sonarToGerrit(
                                            inspectionConfig: [
                                                analysisStrategy: pullRequest(),
                                            ],
                                            /* Optional parameters */
                                            reviewConfig: [
                                                 omitDuplicateComments  : true,
                                            ]
                                    )
                                }
                            }
                        } else {
                            withSonarQubeEnv(SONAR_SERVER) {
                                sh """
                                    #!/bin/bash
                                    set -e
                                    cd /ssd/jenkins/workdir
                                    export BUILD_DIR=workdir
                                    ./meta-distro-common/scripts/setup_buildenv.py -d base-qnx -b workdir -m  ${BUILD_TARGET}  -a meta-sw-shm -a meta-sonarqube
                                    source poky/oe-init-build-env  workdir
                                    echo \$sq_modules
                                    bitbake ${SONAR_MODULES} ${SCRIPT_ARGS} -c sonarqube  || true
                                    cd -
                                """
                            }
                        }
                    }
                }
            }
        }
        post {
            always {
                script {
                    // Cleanup the workspace if SKIP_CLEANUP is not set
                    sh """
                        #!/bin/bash
                        set -e
                        #Skip the cleanup if set explicitly in the parameters
                        if [ "\${SKIP_CLEANUP}" = true ]; then
                            echo "Skipping the cleanup"
                        else
                            echo "Cleaning up the workspace"
                            sudo btrfs subvolume delete \${SONAR_SOURCE_VOLUME}
                            if [ "\${PIPELINE_TYPE}" != "verify" ]; then
                                rm -rf \${WORKSPACE}/report-task.txt
                            fi
                        fi
                    """
                }
            }
            failure {
                emailext (
                    subject: "Sonarqube Analysis job status: ${currentBuild.currentResult} for ${GERRIT_BRANCH}--${SNAPSHOT_NAME}",
                    body: """
                            Build Status: ${currentBuild.currentResult}
                            Build URL: ${env.BUILD_URL}
                            """,
                    to: 'chandrashekhar.dh@elektrobit.com, navyashree.vittal@elektrobit.com'
                )
            }
        }
    }
}