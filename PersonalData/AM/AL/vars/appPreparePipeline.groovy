import com.eb.lib.aosp.aospUtils
import com.eb.lib.aosp.CommonEnvironment
import groovy.json.JsonBuilder

def call(body) {
    // For pipeline common config
    def commonlib = new aospUtils()
    def config = body.script.config
    def configRuntime = body.script.configRuntime

    node(configRuntime.pipeline_node) {
        // Checkout build tools into workspsace
        configRuntime.workspace = env.WORKSPACE
        dir "${configRuntime.workspace}/.launchers", {
            git branch: "${body.script.buildtools_branch}",
                    url: "${body.script.buildtools_url}"
        }

        configRuntime.pipelineType = PIPELINE_TYPE.toLowerCase().trim() //verify, devel, snapshot
        configRuntime.build_on_node = (BUILD_ON_NODE != "") ? BUILD_ON_NODE : ""

        configRuntime.verify_message = ""
        configRuntime.verify_score = 1
        configRuntime.dependencies = ""

        configRuntime.BUILD_STATUS = "SUCCESS"
        configRuntime.TEST_STATUS = "SUCCESS"
        configRuntime.PUBLISH_STATUS = "SUCCESS"
        configRuntime.NOTIFICATION_FAILURE = true

        configRuntime.build_addinfo = ""
        configRuntime.test_addinfo = ""
        configRuntime.build_description = ""
        configRuntime.failure_mail_body = """JOB URL: ${BUILD_URL}
        ERROR: %s
        INFO: %s
        """

        configRuntime.email_build_info = ""
        configRuntime.email_recipients = config.email_recipients

        /* display name */
        configRuntime.project = GERRIT_PROJECT.substring(GERRIT_PROJECT.lastIndexOf('/') + 1)
        currentBuild.displayName = VersionNumber (versionNumberString: "#${BUILD_NUMBER} ${configRuntime.project}")
        configRuntime.ssd_path = "${config.ssd_root}/${JOB_BASE_NAME}/${configRuntime.project}"
        configRuntime.build_workspace = "${configRuntime.ssd_path}/${BUILD_NUMBER}"
        configRuntime.app_workspace = "${configRuntime.build_workspace}/app"
        configRuntime.prebuilt_app_workspace = "${configRuntime.build_workspace}/prebuilt_app"
        configRuntime.repo_manifest_xml = "${configRuntime.project}.xml"
        configRuntime.test_job = config[configRuntime.pipelineType]["test_job"]
        configRuntime.test_job_timeout = config[configRuntime.pipelineType]["timeout"]

        if (configRuntime.pipelineType.contains("verify")) {
            if (GERRIT_EVENT_TYPE == "wip-state-changed" && GERRIT_CHANGE_WIP_STATE == "true") {
                currentBuild.result = 'ABORTED'
                currentBuild.description = 'WIP change. Hence aborting early.'
                error("WIP change. Hence aborting the job early.")
            }
        }

        configRuntime.app_info = config[GERRIT_PROJECT]
        if (configRuntime.app_info) {
            configRuntime.deploy_to_git = config[GERRIT_PROJECT]["deploy_to_git"]
            configRuntime.deploy_to_artifactory = config[GERRIT_PROJECT]["deploy_to_artifactory"]
            configRuntime.deploy_to_documentation = config[GERRIT_PROJECT]["deploy_to_documentation"]
            configRuntime.publish_app = config[GERRIT_PROJECT]["publish_app"]
            configRuntime.aosp_preinstalled_app = config[GERRIT_PROJECT]["aosp_preinstalled_app"]
            configRuntime.disable_app = config[GERRIT_PROJECT]["disable_app"]
            configRuntime.ext_build_script = config[GERRIT_PROJECT]["ext_build_script"]?:""
            configRuntime.gradle_url_validation = config[GERRIT_PROJECT]["gradle_url_validation"]?:""
            configRuntime.jdk_version = config[GERRIT_PROJECT]["jdk_version"]?:""
            if (!configRuntime.disable_app && configRuntime.disable_app != null && configRuntime.disable_app == true) {
                currentBuild.result = 'ABORTED'
                commonlib.__INFO("${GERRIT_PROJECT} is disabled to build ==> SKIP!!!")
                currentBuild.description = "SKIPPED: ${GERRIT_PROJECT}"
                configRuntime.verify_score = -1
                return
            }

            // Check testing is disable for GERRIT_PROJECT or not, If yes then testing stage will not run for that project.
            if (configRuntime.test_job == "" || TESTING_DISABLE == "true") {
                configRuntime.disable_testing = true
                commonlib.__INFO("Testing is disabled for ${GERRIT_PROJECT}")
            } else {
                if (config[GERRIT_PROJECT]["testing_disable"] == "true") {
                    configRuntime.disable_testing = true
                    commonlib.__INFO("Testing is disabled for ${GERRIT_PROJECT}")
                } else {
                    configRuntime.disable_testing = false
                    commonlib.__INFO("Testing is enabled for ${GERRIT_PROJECT}")
                }
            }

            // Check sonarqube is enabled for GERRIT_PROJECT or not, If yes then sonarqube analysis stage will run for that project.
            if ( config[GERRIT_PROJECT]["sonarqube_enable"] == "true" && SONARQUBE_ENABLE == "true" ) {
                configRuntime.sonarqube_enable = true
                commonlib.__INFO("Sonarqube analysis is enabled for ${GERRIT_PROJECT}")
            } else {
                configRuntime.sonarqube_enable = false
                commonlib.__INFO("Sonarqube analysis is disabled for${GERRIT_PROJECT}")
            }

            // Check qualitygate is enabled for GERRIT_PROJECT or not.
            if (configRuntime.pipelineType.contains("verify")) {
                if ( config[GERRIT_PROJECT]["qualitygate_enable"] == "true" && SONARQUBE_ENABLE == "true") {
                    configRuntime.qualitygate_enable = true
                    commonlib.__INFO("Quality Qate status check is enabled for ${GERRIT_PROJECT}")
                } else {
                    configRuntime.qualitygate_enable = false
                    commonlib.__INFO("Quality Qate status check is disabled for${GERRIT_PROJECT}")
                }
            }

            // Restrict to using BUILD_ON_NODE if supplied.
            if (env.BUILD_ON_NODE != "") {
                configRuntime.leastloadednode = env.BUILD_ON_NODE
            } else {
                if (config[GERRIT_PROJECT]["pipeline_node"] != "") {
                    commonlib.__INFO("config pipeline_node")
                    println config[GERRIT_PROJECT]["pipeline_node"]
                    configRuntime.pipeline_node = config[GERRIT_PROJECT]["pipeline_node"]
                }
                println configRuntime.pipeline_node
                configRuntime.build_node_list = nodesByLabel label: configRuntime.pipeline_node
                if (configRuntime.build_node_list) {
                    configRuntime.leastloadednode = commonlib.getLeastLoadedNode(configRuntime.build_node_list.join(' ').tokenize(' '))
                } else {
                    currentBuild.result = 'ABORTED'
                    commonlib.__INFO("No Build Bot Found For ${GERRIT_PROJECT} ==> SKIP!!!")
                    return
                }
            }
            commonlib.__INFO("build will run on ${configRuntime.pipeline_node} : ${configRuntime.leastloadednode}")
        } else {
            commonlib.__INFO("configuration for the ${GERRIT_PROJECT} is not available. Please add configuration into ${PROJECT_CONFIG_FILE} file ==> SKIP!!!")
            commonlib.__INFO("To build your project with Gradle tool please contact Integration team")
            currentBuild.description = "SKIPPED: ${GERRIT_PROJECT}"
            currentBuild.result = 'ABORTED'
            configRuntime.verify_score = 0
            return
        }

        try {
            if (configRuntime.pipelineType.contains("verify")) {
                try {
                    // Not all gerrit events set GERRIT_EVENT_ACCOUNT_EMAIL. That's why, a try catch block. We give precedence to GERRIT_EVENT_ACCOUNT_EMAIL in our email communications.
                    configRuntime.email_build_info = "\n\t-Change: ${GERRIT_CHANGE_URL}\n\t-Patchset: ${GERRIT_PATCHSET_NUMBER} (${GERRIT_REFSPEC})\n\t-Project: ${GERRIT_PROJECT}\n\t-Branch: ${GERRIT_BRANCH}\n\t-Triggered By: ${GERRIT_EVENT_ACCOUNT_EMAIL}"
                } catch (err) {
                    configRuntime.email_build_info = "\n\t-Change: ${GERRIT_CHANGE_URL}\n\t-Patchset: ${GERRIT_PATCHSET_NUMBER} (${GERRIT_REFSPEC})\n\t-Project: ${GERRIT_PROJECT}\n\t-Branch: ${GERRIT_BRANCH}\n\t-Triggered By: ${GERRIT_PATCHSET_UPLOADER_EMAIL}"
                }

                configRuntime.build_description = "${env.GERRIT_PROJECT}-${env.GERRIT_BRANCH}: "

                configRuntime.email_recipients = config.email_recipients + commonlib.getReviewersEmailFromChangeNumber(GERRIT_HOST, GERRIT_CHANGE_NUMBER)
                configRuntime.email_recipients = "${configRuntime.email_recipients}${GERRIT_CHANGE_OWNER_EMAIL},${GERRIT_PATCHSET_UPLOADER_EMAIL}".tokenize(',').unique().join(',')
                // If the change that triggered a commit has dependent changes, collect them in this variable.
                def currentChangeDependencies = commonlib.gerritGetDependentChanges(VERBOSE, GERRIT_CHANGE_NUMBER, GERRIT_HOST)

                configRuntime.dependencies = commonlib.gerritGetDependencies(VERBOSE, GERRIT_CHANGE_COMMIT_MESSAGE, GERRIT_HOST)
                if (configRuntime.dependencies != "") {
                    configRuntime.dependencies = configRuntime.dependencies + " " + currentChangeDependencies
                    commonlib.__INFO("Dependencies: " + configRuntime.dependencies)
                    configRuntime.verify_message = configRuntime.verify_message + "\n\n * Info:\n\t Depends on: " + configRuntime.dependencies.replace("true", "up-to-date").replace("false", "patchset-out-of-date")
                }
                def vmsgtemp = "Automated verification pipeline has started: \n\t\t${env.BUILD_URL}consoleFull"
                commonlib.gerrit_SetReview(GERRIT_HOST, GERRIT_CHANGE_NUMBER, GERRIT_PATCHSET_NUMBER, vmsgtemp, 0)

                vmsgtemp = commonlib.isCommitRebasable(GERRIT_HOST, GERRIT_CHANGE_NUMBER, GERRIT_PROJECT, GERRIT_BRANCH)
                if (vmsgtemp != "") {
                    configRuntime.verify_message = configRuntime.verify_message + "\n\n * Warning:\n\t Review commit is not on top of the latest commit of the branch (REBASE)"
                }

                vmsgtemp = commonlib.isJiraTicket(GERRIT_CHANGE_COMMIT_MESSAGE)
                if (vmsgtemp != "") {
                    commonlib.__INFO("TRACING ID: " + vmsgtemp)
                    configRuntime.verify_message = configRuntime.verify_message + "\n\n * Summary:\n\t [OK] TRACING ID: " + vmsgtemp
                } else {
                    commonlib.__INFO("TRACING ID: NOT FOUND!")
                    configRuntime.verify_message = configRuntime.verify_message + "\n\n * Summary:\n\t [  ] TRACING ID: NOT FOUND!"
                }
            } else if (configRuntime.pipelineType.contains("master")) {
                email_build_info = "\n\t-Project: ${GERRIT_PROJECT}\n\t-Branch: ${GERRIT_BRANCH}"
            }
        } catch (err) {
            commonlib.__INFO(err.toString())
            currentBuild.result = "FAILURE"
            configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(), err.toString() + "%s", configRuntime.email_build_info)
        }


        // Each stage is run if this variable remains set to SUCCESS
        // When a stage fails, this is set to FAILURE, so further stages are not run.
        currentBuild.result = currentBuild.result ?: 'SUCCESS'

        // Used only in verify pipelines.
        if (currentBuild.result == 'ABORTED') {
            configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(), currentBuild.description + "%s", configRuntime.email_build_info)
        }
    }
}
