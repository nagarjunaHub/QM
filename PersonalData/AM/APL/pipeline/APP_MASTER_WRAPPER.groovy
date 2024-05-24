@Library(['pipeline-global-library@aosp']) _
import com.eb.lib.aosp.aospUtils
def commonlib = new aospUtils()
pipeline {
    agent { node 'Master_Wrapper' }
    options {
        timestamps ()
        skipDefaultCheckout(true)
    }
    stages {
        stage('Publish ref Update details') {
            steps {
                script {
                    isTriggeredByTimer = !currentBuild.getBuildCauses('hudson.triggers.TimerTrigger$TimerTriggerCause').isEmpty()
                    if(env.GERRIT_PROJECT) {
                        currentBuild.displayName = VersionNumber (versionNumberString: "#${BUILD_NUMBER} ${GERRIT_PROJECT}")
                        refUpdateInfo = '#!/bin/bash'+"\n"
                        fileToCreate = new File(GERRIT_PROJECT).name + ".sh"
                        refUpdateInfo += "GERRIT_PROJECT=${GERRIT_PROJECT}\n"
                        refUpdateInfo += "GERRIT_REFNAME=${GERRIT_REFNAME}\n"
                        refUpdateInfo += "GERRIT_OLDREV=${GERRIT_OLDREV}\n"
                        refUpdateInfo += "GERRIT_NEWREV=${GERRIT_NEWREV}\n"
                        refUpdateInfo += "echo $GERRIT_PROJECT\n"
                        writeFile file: fileToCreate, text: refUpdateInfo
                        sh("chmod +x ${fileToCreate}")
                    }
                    else if(isTriggeredByTimer || FORCE_RUN == 'true') {
                        println("Build triggered by Timer or FORCE_RUN")
                        if(FORCE_RUN == 'true') {
                           currentBuild.displayName = "#${currentBuild.number} FORCE_RUN"
                        }
                        else if(isTriggeredByTimer) {
                            currentBuild.displayName = "#${currentBuild.number} TIMER_TRIGGER"
                        }
                        dir(WORKSPACE) {
                            shListTmp = findFiles(glob: '*.sh')
                            println("Master pipeline will be triggered for ${shListTmp.size()}: ${shListTmp} app(s)")
                            if(shListTmp.size() > 0) {
                                currentBuild.description = shListTmp.toString()
                                def master_build_parallel_map = [:]
                                def launched_apps = [:]
                                def gerrit_projects_map = [:]
                                def gerrit_refs_map = [:]
                                def master_build_parameter_list = [:]
                                def shList = []
                                def successful_apps = []
                                def failed_apps = []
                                shListTmp.every{file -> shList.add(file.getName()) }
                                shList.each{ shFile ->
                                    gerrit_projects_map[shFile] = sh(returnStdout: true, script: "cat ${shFile} | grep 'GERRIT_PROJECT' | cut -d'=' -f2-").trim()
                                    gerrit_refs_map[shFile] = sh(returnStdout: true, script: "cat ${shFile} | grep 'GERRIT_REFNAME' | cut -d'=' -f2-").trim()
                                    master_build_parameter_list[shFile] = [
                                            "GERRIT_PROJECT=" + gerrit_projects_map[shFile],
                                            "GERRIT_BRANCH=" + gerrit_refs_map[shFile] ]
                                    println("master_build_parameter_list: ${master_build_parameter_list}")
                                    master_build_parallel_map[shFile] = {
                                        stage(shFile.replaceAll('.sh', '')) {
                                            launched_apps[shFile] = commonlib.eb_build(job: "${APP_MASTER_PIPELINE}", timeout: 7200, propagate: false, parameters: master_build_parameter_list[shFile])
                                            print("Results from Master builds: ")
                                            print(launched_apps[shFile])
                                            if (launched_apps[shFile].RESULT != "SUCCESS") {
                                                failed_apps.add(gerrit_projects_map[shFile])
                                                print("${shFile} added to failed list")
                                                errMsg = "${shFile}: ${launched_apps[shFile].RESULT}"
                                                throw new RuntimeException(errMsg)
                                            }
                                            else {
                                                successful_apps.add(gerrit_projects_map[shFile])
                                                print("${shFile} added to successful list")
                                                sh("rm -rf $shFile")
                                            }
                                        }
                                    }
                                }
                                try {
                                  parallel master_build_parallel_map
                                } catch(err) {
                                    for (launched_app in launched_apps.keySet()) {
                                        if(launched_apps[launched_app]["RESULT"] != "SUCCESS") {
                                            currentBuild.result = 'UNSTABLE'
                                              catchError(buildResult: currentBuild.result, stageResult: launched_apps[launched_app]["RESULT"]) {
                                                commonlib.__INFO("Error: An error occured during ${launched_app} master build.. marking build as UNSTABLE.")
                                            }
                                        }
                                    }
                                }
                                if (!successful_apps.isEmpty()) {
                                    writeFile file: 'SuccessBuilds.txt', text: successful_apps.toString()
                                    archiveArtifacts artifacts: 'SuccessBuilds.txt', followSymlinks: false
                                }
                                if (!failed_apps.isEmpty()) {
                                    writeFile file: 'FailedBuilds.txt', text: failed_apps.toString()
                                    archiveArtifacts artifacts: 'FailedBuilds.txt', followSymlinks: false
                                }
                            } else {
                                println("No app changes were submitted from last build...")
                                currentBuild.result = 'ABORTED'
                            }
                        }
                        }
                    else {
                        println("Invalid trigger. Please select FORCE_RUN for manual build...")
                        currentBuild.result = 'ABORTED'
                    }
                }
            }
        }
    }
}
