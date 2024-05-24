import org.jenkinsci.plugins.pipeline.modeldefinition.Utils
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import org.yaml.snakeyaml.Yaml
import com.eb.lib.aosp.aospUtils

import com.eb.lib.aosp.PipelineEnvironment

def call(body) {
    def commonlib = new aospUtils()
    pipeline {
        this.options {
            copyArtifactPermission('*') // Add the copyArtifactPermission('*') option
            timestamps()
        }
        this.agent { label "Linux_BuildBot" }
        stages {
            stage('init') {
                steps {
                    script {
                        //******** BEGINING OF THE VARIABLE INITIALIZATION AND ENV SETUP ********
                        def pipeline_node = "Linux_BuildBot"
                        boolean NOTIFICATION_FAILURE = true
                        def pipeline_env = new PipelineEnvironment(this)
                        pipeline_env.setupEnvironment()
                        pipeline_env.printConfiguration()

                        config = pipeline_env.configuration
                        configRuntime = pipeline_env.configProperties

                        configRuntime.pipelineType = PIPELINE_TYPE.toLowerCase().trim() //verify, devel, snapshot

                        configRuntime.pipeline_node = "Linux_BuildBot"
                        configRuntime.build_on_node = (BUILD_ON_NODE != "") ? BUILD_ON_NODE : ""

                        configRuntime.get_flash_image = false
                        configRuntime.supported_targets = config.supported_target_ids.keySet().collect().join(" ") // This default list will be filter out base on gerrit event
                        //build_type_list is depend on target_id. so, moved to all *.groovy file under target_id_list.each loop
                        //configRuntime.build_type_list = config[configRuntime.pipelineType]["build_type_list"].tokenize(" ")


                        if (config.project_branch == null) {
                            configRuntime.project_branch = config.project_line+"_"+config.android_version+"_"+config.branch_identifier
                        } else {
                        configRuntime.project_branch = config.project_branch
                        }

                        Date date = new Date()
                        configRuntime.project_release_version = [config.project_line,config.project_type,config.android_version,config.branch_identifier,date.format("yyyy-MM-dd_HH-mm")].join("_").trim().toUpperCase()

                        configRuntime.versionTemplateBase = [config.project_line,config.project_type,config.android_version,config.branch_identifier].join("_").trim().toUpperCase()
                        if (config.prebuilt_release_required == null) {
                        configRuntime.prebuilt_release_name = "n/a"
                        } else {
                        configRuntime.prebuilt_release_name = [config.project_line,config.project_type,config.android_version,config.branch_identifier,"prebuilt",configRuntime.pipelineType].join("_").trim().toUpperCase()
                        }

                        configRuntime.repo_dev_manifest_revision = configRuntime.project_branch
                        configRuntime.repo_rel_manifest_revision = configRuntime.project_branch + "_master"

                        configRuntime.build_variant = [:]

                        configRuntime.verify_message = ""
                        configRuntime.verify_score = 1
                        configRuntime.dependencies = ""

                        configRuntime.BUILD_STATUS = "SUCCESS"
                        configRuntime.TEST_STATUS = "SUCCESS"


                        configRuntime.NET_SHAREDRIVE_TABLE = [
                            "qnx": config.net_sharedrive + "/qnx_release/",
                            "snapshot": config.net_sharedrive + "/snapshots/",
                            "apps": config.net_sharedrive + "/app_releases/",
                            "devel": config.net_sharedrive + "/devel/",
                            "verify": config.net_sharedrive  + "/devel/",
                            "get_flash": config.net_sharedrive + "/get_flash/"
                        ]

                        configRuntime.test_addinfo = ""
                        configRuntime.build_description = ""
                        configRuntime.failure_mail_body = """JOB URL: ${BUILD_URL}
                        ERROR: %s
                        INFO: %s
                        """
                        // Get stages from config pipelinetype stages is an arry of stages
                        configRuntime.stages = config[configRuntime.pipelineType]["stages"]
                        //capture each stage results to a map
                        configRuntime.stage_results = [:]
                        //eg :
                        //configRuntime.stage_results[STAGE_NAME] = "SUCCESS"


                        configRuntime.stages_to_run_always = config[configRuntime.pipelineType].stages_to_run_always?:[]

                        //Replace aospTesing stage with aospGetFlashParallel stage if GERRIT_EVENT_TYPE == "comment-added" and comment is '__get_flash__' or '__get_flash_clean__'
                        if (env.GERRIT_EVENT_TYPE?:'' == "comment-added" ){
                            gerrit_comment_text = commonlib.getGerritEventMsg(env.GERRIT_EVENT_COMMENT_TEXT?:'n/a')
                            if (gerrit_comment_text.contains('__get_flash__') || gerrit_comment_text.contains('__get_flash_clean__')){
                                configRuntime.stages = configRuntime.stages.collect { it.replace("Testing", "GetFlashParallel") }
                                configRuntime.get_flash_image = true
                            }
                        }

                        // Remove the Testing stage if disable_testing is true
                        if ( TESTING_DISABLE?.toBoolean()) {
                            commonlib.__INFO("Remove Testing stage from pipeline" )
                            configRuntime.stages = configRuntime.stages.findAll { it != "Testing" }
                        }

                        configRuntime.email_build_info = ""
                        configRuntime.email_recipients = config.email_recipients
                        //Print the configRuntime
                        commonlib.printConfigRuntimeYaml(configRuntime)
                        currentBuild.result = "SUCCESS"

                        //******** END OF VARIABLE INITIALIZATION AND ENV SEETUP   ********

                        //******** BEGINING OF THE STAGE EXECUTION ********
                        configRuntime.stages.each { stageName ->
                            stage(stageName) {
                                try {
                                    // Run the stage if currentBuild result is success or stage is  marked to run always
                                    if (currentBuild.result.equalsIgnoreCase('SUCCESS') || configRuntime.stages_to_run_always.contains(stageName)) {
                                        echo "Running ${stageName} stage"
                                        configRuntime.stage_results[STAGE_NAME] = "SUCCESS"
                                        "aosp${stageName}" (script: this)
                                        //set the stageresult to false if any stage failed

                                        if (configRuntime.stage_results[STAGE_NAME].equalsIgnoreCase('ABORTED')) {
                                                catchError(stageResult: currentBuild.result) {
                                                    commonlib.__INFO("Stage ${stageName} aborted")
                                                    error("Stage ----> ${stageName} aborted")
                                                }
                                        }

                                        if (configRuntime.stage_results[STAGE_NAME].equalsIgnoreCase('FAILURE')) {
                                             catchError(stageResult: currentBuild.result) {
                                                commonlib.__INFO("Stage ${stageName} failed")
                                                error("Stage ----> ${stageName} failed")
                                             }
                                        }
                                    } else {
                                        echo "Skip ${stageName} stage due to previous stage failed"
                                        Utils.markStageSkippedForConditional(STAGE_NAME)
                                    }
                                } catch(FlowInterruptedException e) {
                                    /*
                                    FlowInterruptedException is thrown when build is aborted.
                                    If the user aborted the build then e.isActualInterruption() is true (see JavaDoc
                                    of FlowInterruptedException). Otherwise, the abort reason is unknown.
                                     */
                                    def abortReason = e.isActualInterruption() ? 'by user' : 'due to unknown reason'
                                    commonlib.__INFO("Build aborted ${abortReason}")
                                    commonlib.__INFO(e.toString())

                                    // use catchError to set build and stage result
                                    catchError(buildResult: 'ABORTED', stageResult: 'ABORTED') {
                                        commonlib.__INFO("Stage ${stageName} ABORTED")
                                        error("Stage ----> ${stageName} ABORTED")
                                    }
                                    configRuntime.stage_results[stageName] = 'ABORTED'
                                    configRuntime.BUILD_STATUS = 'ABORTED'
                                    configRuntime.build_addinfo += "(${stageName} ABORTED)!!!"

                                } catch(err) {
                                    catchError(buildResult: currentBuild.result, stageResult: currentBuild.result) {
                                        commonlib.__INFO(err.toString())
                                        currentBuild.result = "FAILURE"
                                        configRuntime.stage_results[STAGE_NAME] = 'FAILURE'
                                        configRuntime.BUILD_STATUS = "FAILURE"
                                        configRuntime.build_addinfo = "(${stageName} failed)!!!"
                                        configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(),err.toString()+configRuntime.build_addinfo+"%s",configRuntime.email_build_info)
                                        error("FAIL Build early due to :" + err.getMessage())
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        post {
            always{
                script{
                    // print all the stage results
                    commonlib.__INFO("EACH STAGE RESULT : ${configRuntime.stage_results}")
                    if (configRuntime.build_on_node != "") {
                        commonlib.removeNodeLabel(configRuntime.build_on_node, "RESERVED")
                    }
                    sh """#!/bin/bash -x
                        rm -rf *_buildlog.txt *_testlog.txt
                    """

                    if (configRuntime.BUILD_STATUS == 'FAILURE'){
                        currentBuild.description = configRuntime.build_description + "B:-1" + configRuntime.build_addinfo + " T:0"
                    } else {
                        if (configRuntime.TEST_STATUS == "FAILURE"){
                            currentBuild.description = configRuntime.build_description + "B:+1 T:-1" + configRuntime.test_addinfo
                        } else {
                            if (config.RELEASE_STATUS == "FAILURE"){
                                currentBuild.description = configRuntime.build_description + "B:+1 T:+1 R:-1"
                            } else {
                                if (currentBuild.result == "ABORTED") {
                                    currentBuild.description = configRuntime.build_description + " (ABORTED/SKIPPED)"
                                } else {
                                    NOTIFICATION_FAILURE = false
                                    currentBuild.description = configRuntime.build_description + "B:+1 T:+1 R:+1"
                                }
                            }
                        }
                    }
                }
            }
            failure {
                sh 'echo Build Failed'
                mail to: "${configRuntime.email_recipients.replaceAll(",null","").trim()}",
                from: "${config.default_email_sender}",
                    subject: "${currentBuild.result} : ${JOB_NAME}#${BUILD_NUMBER}.",
                    body: """${configRuntime.failure_mail_body.toString()}\nRegards\nBRITT """
            }
            aborted {
                sh 'echo  Build has been aborted'
                mail to: configRuntime.email_recipients.replaceAll(",null","").trim(),
                from: "${config.default_email_sender}",
                    subject: "${currentBuild.result} : ${JOB_NAME}#${BUILD_NUMBER}.",
                    body: """${configRuntime.failure_mail_body.toString()}\nRegards\nBRITT """
            }
        }
    }
}
