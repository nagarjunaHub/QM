import org.jenkinsci.plugins.pipeline.modeldefinition.Utils
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import org.yaml.snakeyaml.Yaml
import com.eb.lib.aosp.aospUtils
import com.eb.lib.aosp.PipelineEnvironment
import java.net.URL

def call(body) {
    def commonlib = new aospUtils()
    pipeline {
        this.options {
            copyArtifactPermission('*') // Add the copyArtifactPermission('*') option
            // set timestamps
            timestamps()
        }
        // this.agent { label "Linux_BuildBot" }
        this.parameters {
            string(name: 'SONAR_SOURCE_VOLUME', defaultValue: '', description: 'Sonar Source Volume')
            string(name: 'DOCKER_IMAGE_ID', defaultValue: '', description: 'Docker Image ID')
            string(name: 'SONAR_MODULES', defaultValue: '', description: 'Sonar Modules')
            string(name: 'SONAR_ADDITIONAL_SOURCES', defaultValue: 'main', description: 'Sonar additional sources note:this is for java based projects having sources in non standard place like src/pag instead of src/main')
            string(name: 'SONAR_SCANNER', defaultValue: '/sonarqube/sonar-scanner/bin/sonar-scanner', description: 'Sonar Scanner')
            string(name: 'SONAR_SERVER', defaultValue: 'https://sonarqube-pj-cos-1-test.ebgroup.elektrobit.com', description: 'Sonar Server make sure there is noe / at the end of the URL')
            string(name: 'SONAR_BUILD_WRAPPER', defaultValue: '/sonarqube/build-wrapper-linux-x86/build-wrapper-linux-x86-64', description: 'Sonar Build Wrapper')
            string(name: 'SONAR_PROJECTKEY_PREFIX', defaultValue: '', description: 'Sonar Project Prefix')
            booleanParam(name: 'VERBOSE', defaultValue: false, description: 'When checked, "bash -x" is run.')
            booleanParam(name: 'SKIP_BUILD', defaultValue: false, description: 'When checked, skip the build.')
            booleanParam(name: 'SKIP_SCAN', defaultValue: false, description: 'When checked, skip the scan.')
            booleanParam(name: 'SKIP_CLEANUP', defaultValue: false, description: 'When checked, skips the cleanup.')
            string(name: 'SNAPSHOT_NAME', defaultValue: '', description: 'Used to set sonar.projectVersion')
            string(name: 'STACK_TARGET_LABEL', defaultValue: '', description: 'Where to run the build?')
            string(name: 'SONAR_BRANCH', defaultValue: '', description: 'Provide SONAR_BRANCH')
            string(name: 'LUNCH_TARGET', defaultValue: '1', description: 'Lunch Target')
            string(name: 'JACOCO_BUILD_TARGET', defaultValue: '', description: 'The Jacoco build target')
            string(name: 'BUILD_SCRIPT', defaultValue: '', description: 'The build script to run')
            string(name: 'PIPELINE_TYPE', defaultValue: '', description: 'PIPELINE_TYPE to describe the type of pipeline from which this job is triggered')
        }
        this.agent { node { label STACK_TARGET_LABEL } }
        this.environment {
            CUSTOM_WORKSPACE = "${SONAR_SOURCE_VOLUME}/sonarqube_jenkins_workspace"
            SCRIPT_ARGS = ' '
        }
        stages {
            stage('init') {
                steps {
                    script {
                        def descriptionSuffix = PIPELINE_TYPE == "verify"?"${SONAR_MODULES}":"${SONAR_BRANCH}-${SNAPSHOT_NAME}"
                        currentBuild.description = "${PIPELINE_TYPE}-${descriptionSuffix}"
                        //******** BEGINING OF THE VARIABLE INITIALIZATION AND ENV SETUP ********
                        def pipeline_node = "Linux_BuildBot"
                        boolean NOTIFICATION_FAILURE = true
                        def pipeline_env = new PipelineEnvironment(this)
                        pipeline_env.setupEnvironment()
                        pipeline_env.printConfiguration()
                        config = pipeline_env.configuration
                        // list all files in shared library
                        // def files = sh(returnStdout: true, script: "tree ; pwd ")
                        env.SHARED_LIBS = sh(returnStdout: true, script: "pwd").trim()
                        // set the BUILD_SCRIPT path from shared_lib_path
                        // println "BUILD_SCRIPT outside if: ${BUILD_SCRIPT}"
                        if (BUILD_SCRIPT != '') {
                            env.BUILD_SCRIPT = env.SHARED_LIBS + '/' + BUILD_SCRIPT
                            println "BUILD_SCRIPT: ${BUILD_SCRIPT}"
                        }

                        // print all files in shared library loaded from AOSP-manifest
                        // println "files: ${files}"
                        // println "env.SHARED_LIBS: ${env.SHARED_LIBS}"
                        if (SONAR_SOURCE_VOLUME.isEmpty()) {
                            error("SONAR_SOURCE_VOLUME cannot be empty")
                        }
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
                        }

                        SCRIPT_ARGS = "--source-volume /aosp_workspace --sq-prefix ${SONAR_PROJECTKEY_PREFIX}  --sq-branch ${SONAR_BRANCH} --sq-additional-sources ${SONAR_ADDITIONAL_SOURCES} --sq-scanner ${SONAR_SCANNER}  --sq-wrapper ${SONAR_BUILD_WRAPPER} --sq-server ${SONAR_SERVER}"

                        if (SONAR_PROJECTKEY_PREFIX.isEmpty()) {
                            error("SONAR_PROJECTKEY_PREFIX cannot be empty")
                        }

                        def has_jacoco_target = JACOCO_BUILD_TARGET != ''
                        def has_launch_target = LUNCH_TARGET != ''
                        def has_build_script = BUILD_SCRIPT != ''


                        if (has_jacoco_target) {
                            SCRIPT_ARGS += " --jacoco-target ${JACOCO_BUILD_TARGET}"
                        }

                        if (has_launch_target) {
                            SCRIPT_ARGS += " --lunch-target ${LUNCH_TARGET}"
                        }

                        if (has_build_script) {
                            SCRIPT_ARGS += " --build-script ${BUILD_SCRIPT}"
                        }

                        if (!has_launch_target && !has_build_script) {
                            error("ERROR :::: Either LUNCH_TARGET or BUILD_SCRIPT or both must be provided")
                        }

                        if (has_build_script && has_jacoco_target ) {
                            error("ERROR :::: JACOCO_BUILD_TARGET should not be provided along with BUILD_SCRIPT")
                        }

                        if (SNAPSHOT_NAME.contains('REL')) {
                            error('We are not running SonarQube for release branches')
                        }

                        if (VERBOSE == 'true') {
                            SCRIPT_ARGS += " -v"
                        }

                        // remove the CUSTOM_WORKSPACE if it exists
                        sh """
                            if [ -d \${CUSTOM_WORKSPACE} ]; then
                                rm -rf \${CUSTOM_WORKSPACE}
                            fi
                            if [ -d \${WORKSPACE}/sonarqube_outdir ]; then
                                rm -rf \${WORKSPACE}/sonarqube_outdir
                            fi
                            #remove SONAR_SOURCE_VOLUME/sonarqube_outdir if it exists
                            if [ -d \${SONAR_SOURCE_VOLUME}/sonarqube_outdir ]; then
                               rm -rf \${SONAR_SOURCE_VOLUME}/sonarqube_outdir
                            fi
                        """

                        //******** END OF THE VARIABLE INITIALIZATION AND ENV SETUP ********                  
                    }
                }
            }
            stage('Build docker image'){
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
                        if (DOCKER_IMAGE_ID == '') {
                            DOCKER_IMAGE_ID = config.docker_image_id
                        }
                        // if config.sona_analysis.docker_image_id is empty then  build the images using DOCKER_IMAGE_ID as base image
                        // else use the config.sona_analysis.docker_image_id
                        if (config.sonar_analysis?.docker_image_id?:'' == '') {
                            // Build SQA_IMAGE from the DOCKER_IMAGE_ID
                            env.SQA_IMAGE = "sqa_image:latest"
                            def userId = sh(returnStdout: true, script: 'id -u').trim()
                            def userName = sh(returnStdout: true, script: 'id -un').trim()
                            docker.build("${SQA_IMAGE}", "--build-arg FROM=${DOCKER_IMAGE_ID} --build-arg HOST_USER_ID=${userId} --build-arg HOST_USER_NAME=${userName} -f ${CUSTOM_WORKSPACE}/docker_env/Dockerfile ${CUSTOM_WORKSPACE}/docker_env")
                            // Use the newly built SQA_IMAGE:latest in subsequent stages
                        } else {
                            env.SQA_IMAGE = config.sonar_analysis.docker_image_id
                        }
                        // print 
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
                        args  '-v /net/deulmhustorage:/net/deulmhustorage -v /etc/profile.d:/etc/profile.d -v \${HOME}:\${HOME} -v /ccache:/ccache -v \${SONAR_SOURCE_VOLUME}:/aosp_workspace -v \${CUSTOM_WORKSPACE}:\${CUSTOM_WORKSPACE} -v \${SHARED_LIBS}:\${SHARED_LIBS}'
                    }
                }
                steps {
                    
                    script {
                        if (SONAR_MODULES != ''){
                            SCRIPT_ARGS += " --sq-modules ${SONAR_MODULES}"
                        }                        
                        sh """
                            #!/bin/bash
                            set -e
                            #Skip the build if set explicitly in the parameters
                            export SCRIPT_ARGS="${SCRIPT_ARGS}"
                            if [ "\${SKIP_BUILD}" = true ]; then
                                echo "Skipping the build"
                                export SCRIPT_ARGS="${SCRIPT_ARGS} --skip-build"
                            fi
                          
                            cd /aosp_workspace

                            echo 'Running Build and Sonar analysis'
                            python3 \${CUSTOM_WORKSPACE}/sonarqubeAnalysis.py \${SCRIPT_ARGS} --skip-scan                            
                            cd -
                        """
                    }                    
                }
            }
            stage('Run analysis') {
                // Skip the scan if set explicitly in the parameters
                when {
                    expression { SKIP_SCAN != 'true' }
                }
                agent { 
                    docker {
                        image env.SQA_IMAGE
                        label STACK_TARGET_LABEL
                        args  '-v /net/deulmhustorage:/net/deulmhustorage -v /etc/profile.d:/etc/profile.d -v \${HOME}:\${HOME} -v /ccache:/ccache -v \${SONAR_SOURCE_VOLUME}:/aosp_workspace -v \${CUSTOM_WORKSPACE}:\${CUSTOM_WORKSPACE} -v \${SHARED_LIBS}:\${SHARED_LIBS}'
                    }
                }
                steps {
                    // Run in sonarqube env context
                    script {
                        if (PIPELINE_TYPE == "verify") {
                            // Run sonarToGerrit for each module
                            for (module in SONAR_MODULES.split(" ")) {
                                module_name = module.split(":")[0]
                                module_refspec = module.split(":")[1]
                                println "==========> module_name: ${module_name} -----> ${module_refspec}"
                                def sqa_report = "${module_name.replaceAll('/', '_')}_sonar-task.txt" 
                                if (module_refspec != "") {
                                    env.GERRIT_CHANGE_NUMBER = module_refspec.split("/")[3]
                                    env.GERRIT_PATCHSET_NUMBER = module_refspec.split("/")[4]
                                    withSonarQubeEnv(SONAR_SERVER) {
                                        sh """
                                            #!/bin/bash
                                            set -e
                                            SONAR_MODULES=${module}
                                            #Skip the scan if set explicitly in the parameters
                                            export SCRIPT_ARGS="${SCRIPT_ARGS}"
                                            if [ "\${SKIP_SCAN}" = true ]; then
                                                echo "Skipping the scan"
                                                export SCRIPT_ARGS="${SCRIPT_ARGS} --skip-scan"
                                            fi
                                            echo 'Running Build and Sonar analysis'
                                            cd /aosp_workspace
                                            python3 \${CUSTOM_WORKSPACE}/sonarqubeAnalysis.py \${SCRIPT_ARGS} --skip-build --sq-modules \${SONAR_MODULES}
                                            find  \${WORKSPACE}/ -name report-task.txt | xargs rm -rf
                                            mkdir -p \${WORKSPACE}/sonarqube_outdir
                                            #copy the report-task.txt from the module to /aosp_workspace/sonarqube_outdir/module_name/report-task.txt
                                            cp -r /aosp_workspace/${module_name}/sonar_working_dir/report-task.txt \${WORKSPACE}/sonarqube_outdir/${sqa_report} || echo "No report-task.txt found for ${module_name}"
                                            cp -r /aosp_workspace/${module_name}/sonar_working_dir/report-task.txt \${WORKSPACE}/ || echo "No report-task.txt found for ${module_name}"
                                            cp -r /aosp_workspace/sonarqube_outdir/* \${WORKSPACE}/sonarqube_outdir/ || echo "No sonarqube_outdir found for ${module_name}"

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
                                if (SONAR_MODULES != ''){
                                    SCRIPT_ARGS += " --sq-modules ${SONAR_MODULES}"
                                }

                                sh """
                                    #!/bin/bash
                                    set -e
                                    #Skip the scan if set explicitly in the parameters
                                    export SCRIPT_ARGS="${SCRIPT_ARGS}"
                                    if [ "\${SKIP_SCAN}" = true ]; then
                                        echo "Skipping the scan"
                                        export SCRIPT_ARGS="${SCRIPT_ARGS} --skip-scan"
                                    fi                                    
                                    echo 'Running Build and Sonar analysis'
                                    cd /aosp_workspace
                                    python3 \${CUSTOM_WORKSPACE}/sonarqubeAnalysis.py \${SCRIPT_ARGS} --skip-build
                                    mkdir -p \${WORKSPACE}/sonarqube_outdir
                                    cp -r /aosp_workspace/sonarqube_outdir/* \${WORKSPACE}/sonarqube_outdir/ || echo "No sonarqube_outdir found"
                                    cd -
                                """                
                            }                            
                        }

                        // Archive the sonarqube_outdir
                        archiveArtifacts artifacts: 'sonarqube_outdir/**', fingerprint: true
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
                            sudo btrfs subvolume delete \${SONAR_SOURCE_VOLUME}/out || rm -rf \${SONAR_SOURCE_VOLUME}/out
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
                    subject: "Sonarqube Analysis job status: ${currentBuild.currentResult} for ${SONAR_BRANCH}--${SNAPSHOT_NAME}",
                    body: """                                                
                            Build Status: ${currentBuild.currentResult}
                            Build URL: ${env.BUILD_URL}                       
                            """,
                    to: config.sonar_analysis['contact']?:'chandrashekhar.dh@elektrobit.com, navyashree.vittal@elektrobit.com'
                )
            }
        }
    }
}